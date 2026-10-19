import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { Authentication } from '../authentication';
import { WorkspaceSession } from '../workspace/workspace-session';
import {
  AdministrationApi,
  DepartmentView,
  MembershipView,
  Role,
  ROLES,
  adminErrorCode,
  adminErrorStatus,
} from './administration-api';

type AdminState =
  | 'starting'
  | 'signed-out'
  | 'restricted'
  | 'loading'
  | 'ready'
  | 'empty'
  | 'forbidden'
  | 'session-expired'
  | 'access-changed'
  | 'error';

interface FormStatus {
  readonly kind: 'success' | 'error';
  readonly message: string;
  readonly needsReload: boolean;
}

interface RoleControls {
  EMPLOYEE: FormControl<boolean>;
  DEPARTMENT_MANAGER: FormControl<boolean>;
  FINANCE_APPROVER: FormControl<boolean>;
  COMPANY_ADMIN: FormControl<boolean>;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const ROLE_LABELS: Readonly<Record<Role, string>> = {
  EMPLOYEE: 'Employee',
  DEPARTMENT_MANAGER: 'Department manager',
  FINANCE_APPROVER: 'Finance approver',
  COMPANY_ADMIN: 'Company administrator',
};

const DEPARTMENT_NAME_VALIDATOR: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  if (typeof control.value !== 'string') return { departmentName: true };
  const name = control.value.trim();
  if (name.length === 0) return { departmentName: 'required' };
  if (name.length > 200) return { departmentName: 'length' };
  return null;
};

const IDENTITY_VALIDATOR: ValidatorFn = (control: AbstractControl): ValidationErrors | null =>
  typeof control.value === 'string' && UUID.test(control.value.trim()) ? null : { identity: true };

function roleGroup(forms: NonNullableFormBuilder): FormGroup<RoleControls> {
  return forms.group({
    EMPLOYEE: forms.control<boolean>(false),
    DEPARTMENT_MANAGER: forms.control<boolean>(false),
    FINANCE_APPROVER: forms.control<boolean>(false),
    COMPANY_ADMIN: forms.control<boolean>(false),
  });
}

function rolesOf(group: FormGroup<RoleControls>): readonly Role[] {
  return ROLES.filter((role) => group.controls[role].value);
}

function patchRoles(group: FormGroup<RoleControls>, roles: readonly Role[]): void {
  for (const role of ROLES) group.controls[role].setValue(roles.includes(role), { emitEvent: false });
  group.markAsPristine();
  group.markAsUntouched();
}

function membershipAssignmentError(control: AbstractControl): ValidationErrors | null {
  const rolesGroup = control.get('roles') as FormGroup<RoleControls> | null;
  if (rolesGroup === null) return null;
  const roles = rolesOf(rolesGroup);
  if (roles.length === 0) return { membership: 'roles' };
  const managed = (control.get('managedDepartmentIds')?.value as readonly string[] | null) ?? [];
  if (roles.includes('DEPARTMENT_MANAGER') && managed.length === 0) return { membership: 'manager' };
  if (!roles.includes('DEPARTMENT_MANAGER') && managed.length > 0) return { membership: 'nonmanager' };
  return null;
}

@Component({
  selector: 'app-administration-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './administration-page.html',
  styleUrl: './administration-page.css',
})
export class AdministrationPage {
  private readonly api = inject(AdministrationApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(NonNullableFormBuilder);
  readonly auth = inject(Authentication);
  /** Current server membership; restriction and self-identity decisions come only from here. */
  readonly session = inject(WorkspaceSession);
  readonly roles = ROLES;

  readonly departmentForm = this.forms.group({
    name: ['', DEPARTMENT_NAME_VALIDATOR],
  });

  readonly createForm = this.forms.group(
    {
      identityId: ['', [Validators.required, IDENTITY_VALIDATOR]],
      departmentId: ['', Validators.required],
      roles: roleGroup(this.forms),
      managedDepartmentIds: this.forms.control<readonly string[]>([]),
    },
    { validators: membershipAssignmentError },
  );

  readonly editForm = this.forms.group(
    {
      departmentId: ['', Validators.required],
      active: [true],
      roles: roleGroup(this.forms),
      managedDepartmentIds: this.forms.control<readonly string[]>([]),
    },
    { validators: membershipAssignmentError },
  );

  readonly state = signal<AdminState>('starting');
  readonly departments = signal<readonly DepartmentView[]>([]);
  readonly memberships = signal<readonly MembershipView[]>([]);
  readonly selectedMembershipId = signal<string | null>(null);
  readonly editSource = signal<MembershipView | null>(null);

  readonly departmentPending = signal(false);
  readonly createPending = signal(false);
  readonly editPending = signal(false);
  readonly departmentAmbiguous = signal(false);
  readonly createAmbiguous = signal(false);
  readonly editStale = signal(false);
  readonly createAttempted = signal(false);
  readonly editAttempted = signal(false);

  readonly departmentStatus = signal<FormStatus | null>(null);
  readonly createStatus = signal<FormStatus | null>(null);
  readonly editStatus = signal<FormStatus | null>(null);

  private activeKey: string | null = null;
  private requestSequence = 0;
  private departmentWriteVersion = 0;
  private departmentWriteSequence = 0;
  private createWriteSequence = 0;
  private editWriteSequence = 0;

  /**
   * Administration access follows the current `/api/me` membership only. A stale sign-in
   * snapshot claiming COMPANY_ADMIN never renders the privileged lists or sends their requests.
   */
  readonly isAdmin = computed(() => this.session.hasRole('COMPANY_ADMIN'));

  /** Display-only scope label; the current profile wins over the older sign-in snapshot. */
  readonly companyName = computed(
    () => this.session.profile()?.companyName ?? this.auth.member()?.companyName ?? '',
  );

  private readonly departmentNames = computed(
    () => new Map(this.departments().map((department) => [department.id, department.name])),
  );

  /**
   * Session changes clear privileged state. The key follows the scoped owner, the profile
   * generation and the current roles, but not a transient loading state on an unchanged
   * profile, so a focus refresh does not wipe an unsaved draft.
   */
  private readonly sessionKey = computed(() => {
    const owner = this.session.ownerKey();
    const state = this.session.state();
    if (owner === null) return `owner:none:${state}`;
    const profile = this.session.profile();
    if (profile === null) return `owner:${owner}:generation:${this.session.generation()}:profile:none:${state}`;
    return `owner:${owner}:generation:${this.session.generation()}:roles:${[...profile.roles].sort().join(',')}`;
  });

  constructor() {
    effect(() => this.applySession(this.sessionKey()));
  }

  roleLabel(role: Role): string {
    return ROLE_LABELS[role];
  }

  departmentName(departmentId: string): string {
    return this.departmentNames().get(departmentId) ?? 'Department unavailable';
  }

  roleLabels(roles: readonly Role[]): string {
    return roles.map((role) => this.roleLabel(role)).join(', ');
  }

  managedNames(ids: readonly string[]): string {
    return ids.length === 0 ? 'None' : ids.map((id) => this.departmentName(id)).join(', ');
  }

  departmentNameError(): string | null {
    const control = this.departmentForm.controls.name;
    if (!control.invalid || !(control.touched || control.dirty)) return null;
    return control.getError('departmentName') === 'length'
      ? 'Use 200 characters or fewer.'
      : 'Enter a nonblank department name (200 characters or fewer).';
  }

  identityError(): string | null {
    const control = this.createForm.controls.identityId;
    if (!control.invalid || !(control.touched || this.createAttempted())) return null;
    return 'Enter the UUID of an already registered identity.';
  }

  createDepartmentError(): string | null {
    const control = this.createForm.controls.departmentId;
    return control.invalid && (control.touched || this.createAttempted()) ? 'Select a department.' : null;
  }

  departmentSelectionError(form: FormGroup, attempted: boolean): string | null {
    const control = form.get('departmentId');
    return control !== null && control.invalid && (control.touched || attempted)
      ? 'Select a department from your current scoped list.'
      : null;
  }

  selectMembershipFromEvent(event: Event): void {
    const id = (event.target as HTMLSelectElement).value;
    if (id.length === 0) {
      this.selectedMembershipId.set(null);
      this.editSource.set(null);
      this.editStale.set(false);
      this.editStatus.set(null);
      this.clearEditForm();
      return;
    }
    this.selectMembership(id);
  }

  assignmentError(form: FormGroup, attempted: boolean): string | null {
    if (!attempted && !form.dirty) return null;
    const reason = form.getError('membership');
    if (reason === 'roles') return 'Choose at least one role.';
    if (reason === 'manager') return 'A department manager requires at least one managed department.';
    if (reason === 'nonmanager') return 'Only a department manager may have managed department assignments.';
    return null;
  }

  isManaged(value: readonly string[], departmentId: string): boolean {
    return value.includes(departmentId);
  }

  toggleManaged(control: FormControl<readonly string[]>, departmentId: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(control.value);
    if (checked) next.add(departmentId);
    else next.delete(departmentId);
    control.setValue([...next].sort());
    control.markAsDirty();
    control.markAsTouched();
  }

  reload(): void {
    if (!this.isAdmin() || this.session.profile() === null) return;
    this.loadAll();
  }

  /**
   * Re-checks the current membership before any privileged fetch. A changed profile advances
   * the session key and the effect reloads or denies; an unchanged, still-admin profile
   * retries the scoped reads without a second profile poll.
   */
  async retryAccess(): Promise<void> {
    if (this.session.ownerKey() === null) {
      await this.auth.login();
      return;
    }
    const generation = this.session.generation();
    const activeKey = this.activeKey;
    await this.session.refresh();
    // If the profile key changed, the session effect already reloaded or denied. Only retry the
    // scoped reads when the same, still-admin session survived the re-check.
    if (
      this.activeKey === activeKey &&
      this.session.generation() === generation &&
      this.isAdmin() &&
      this.session.profile() !== null
    ) {
      this.loadAll();
    }
  }

  checkSession(): void {
    this.auth.retry();
  }

  createDepartment(): void {
    if (!this.isAdmin() || this.departmentPending() || this.departmentAmbiguous()) return;
    if (this.departmentForm.invalid) {
      this.departmentForm.markAllAsTouched();
      return;
    }
    const name = this.departmentForm.controls.name.value.trim();
    if (name.length === 0) {
      this.departmentForm.controls.name.markAsTouched();
      return;
    }
    const key = this.activeKey;
    const sequence = ++this.departmentWriteSequence;
    this.departmentPending.set(true);
    this.departmentStatus.set(null);
    this.api
      .createDepartment(name)
      .pipe(
        finalize(() => {
          if (sequence === this.departmentWriteSequence) this.departmentPending.set(false);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (canonical) => {
          if (sequence !== this.departmentWriteSequence || key !== this.activeKey) return;
          const next = [...this.departments().filter((department) => department.id !== canonical.id), canonical].sort(
            (left, right) => left.name.localeCompare(right.name) || left.id.localeCompare(right.id),
          );
          this.departments.set(next);
          // A read that started before this confirmed write must not replace the canonical row with an older snapshot.
          this.departmentWriteVersion += 1;
          this.departmentForm.reset({ name: '' });
          this.departmentForm.markAsPristine();
          this.departmentForm.markAsUntouched();
          this.departmentStatus.set({
            kind: 'success',
            needsReload: false,
            message: `Created department “${canonical.name}” (${canonical.id}).`,
          });
        },
        error: (error: unknown) => {
          if (sequence !== this.departmentWriteSequence || key !== this.activeKey) return;
          this.handleDepartmentFailure(error);
        },
      });
  }

  createMembership(): void {
    if (!this.isAdmin() || this.createPending() || this.createAmbiguous()) return;
    this.createAttempted.set(true);
    if (this.createForm.invalid || !this.departmentInScope(this.createForm.controls.departmentId.value)) {
      this.createForm.markAllAsTouched();
      return;
    }
    if (!this.managedInScope(this.createForm.controls.managedDepartmentIds.value)) {
      this.createStatus.set({
        kind: 'error',
        needsReload: true,
        message: 'A managed department is not in your current scoped list. Reload and review the assignments.',
      });
      return;
    }
    const input = {
      identityId: this.createForm.controls.identityId.value.trim(),
      departmentId: this.createForm.controls.departmentId.value,
      roles: rolesOf(this.createForm.controls.roles),
      managedDepartmentIds: [...this.createForm.controls.managedDepartmentIds.value].sort(),
    };
    const key = this.activeKey;
    const sequence = ++this.createWriteSequence;
    this.createPending.set(true);
    this.createStatus.set(null);
    this.api
      .createMembership(input)
      .pipe(
        finalize(() => {
          if (sequence === this.createWriteSequence) this.createPending.set(false);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (canonical) => {
          if (sequence !== this.createWriteSequence || key !== this.activeKey) return;
          this.updateMembershipList(canonical);
          this.clearCreateForm();
          this.createAttempted.set(false);
          this.createStatus.set({
            kind: 'success',
            needsReload: false,
            message:
              `Linked identity ${canonical.identityId} to ${this.departmentName(canonical.departmentId)} ` +
              `at version ${canonical.version}. The returned membership is now shown below.`,
          });
        },
        error: (error: unknown) => {
          if (sequence !== this.createWriteSequence || key !== this.activeKey) return;
          this.handleCreateFailure(error);
        },
      });
  }

  selectMembership(id: string): void {
    if (this.editPending()) return;
    const membership = this.memberships().find((candidate) => candidate.id === id) ?? null;
    this.selectedMembershipId.set(membership?.id ?? null);
    this.editSource.set(membership);
    this.editStale.set(false);
    this.editAttempted.set(false);
    this.editStatus.set(null);
    if (membership === null) {
      this.clearEditForm();
      return;
    }
    this.editForm.controls.departmentId.setValue(membership.departmentId);
    this.editForm.controls.active.setValue(membership.active);
    this.editForm.controls.managedDepartmentIds.setValue([...membership.managedDepartmentIds]);
    patchRoles(this.editForm.controls.roles, membership.roles);
    this.editForm.markAsPristine();
    this.editForm.markAsUntouched();
  }

  saveMembership(): void {
    const source = this.editSource();
    if (!this.isAdmin() || source === null || this.editPending() || this.editStale()) return;
    this.editAttempted.set(true);
    if (this.editForm.invalid || !this.departmentInScope(this.editForm.controls.departmentId.value)) {
      this.editForm.markAllAsTouched();
      return;
    }
    if (!this.managedInScope(this.editForm.controls.managedDepartmentIds.value)) {
      this.editStatus.set({
        kind: 'error',
        needsReload: true,
        message: 'A managed department is not in your current scoped list. Reload and review the assignments.',
      });
      return;
    }
    const key = this.activeKey;
    const sequence = ++this.editWriteSequence;
    this.editPending.set(true);
    this.editStatus.set(null);
    this.api
      .updateMembership(source.id, {
        departmentId: this.editForm.controls.departmentId.value,
        active: this.editForm.controls.active.value,
        version: source.version,
        roles: rolesOf(this.editForm.controls.roles),
        managedDepartmentIds: [...this.editForm.controls.managedDepartmentIds.value].sort(),
      })
      .pipe(
        finalize(() => {
          if (sequence === this.editWriteSequence) this.editPending.set(false);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (canonical) => {
          if (sequence !== this.editWriteSequence || key !== this.activeKey) return;
          const currentIdentity = this.session.profile()?.identityId;
          if (
            canonical.identityId === currentIdentity &&
            (!canonical.active || !canonical.roles.includes('COMPANY_ADMIN'))
          ) {
            this.accessFailure('access-changed');
            return;
          }
          this.updateMembershipList(canonical);
          if (this.editSource()?.id === canonical.id) {
            this.editSource.set(canonical);
            this.selectedMembershipId.set(canonical.id);
            this.editForm.controls.departmentId.setValue(canonical.departmentId);
            this.editForm.controls.active.setValue(canonical.active);
            this.editForm.controls.managedDepartmentIds.setValue([...canonical.managedDepartmentIds]);
            patchRoles(this.editForm.controls.roles, canonical.roles);
            this.editForm.markAsPristine();
            this.editForm.markAsUntouched();
          }
          this.editAttempted.set(false);
          this.editStale.set(false);
          this.editStatus.set({
            kind: 'success',
            needsReload: false,
            message:
              `Saved membership ${canonical.id} at version ${canonical.version}. Roles: ` +
              `${this.roleLabels(canonical.roles)}. Active: ${canonical.active ? 'yes' : 'no'}.`,
          });
        },
        error: (error: unknown) => {
          if (sequence !== this.editWriteSequence || key !== this.activeKey) return;
          this.handleEditFailure(error);
        },
      });
  }

  private applySession(key: string): void {
    if (key === this.activeKey) return;
    this.activeKey = key;
    this.requestSequence += 1;
    this.departmentWriteSequence += 1;
    this.createWriteSequence += 1;
    this.editWriteSequence += 1;
    this.departments.set([]);
    this.memberships.set([]);
    this.selectedMembershipId.set(null);
    this.editSource.set(null);
    this.departmentPending.set(false);
    this.createPending.set(false);
    this.editPending.set(false);
    this.departmentAmbiguous.set(false);
    this.createAmbiguous.set(false);
    this.editStale.set(false);
    this.createAttempted.set(false);
    this.editAttempted.set(false);
    this.departmentStatus.set(null);
    this.createStatus.set(null);
    this.editStatus.set(null);
    this.clearCreateForm();
    this.clearEditForm();
    this.departmentForm.reset({ name: '' });

    const owner = this.session.ownerKey();
    if (owner === null) {
      const authState = this.auth.state();
      if (authState === 'starting') {
        this.state.set('starting');
        return;
      }
      if (authState === 'forbidden') {
        this.state.set('forbidden');
        return;
      }
      this.state.set('signed-out');
      return;
    }
    const sessionState = this.session.state();
    if (this.session.profile() === null) {
      // A settled failed or forbidden profile read clears every privileged value; a still
      // loading profile only shows the checking state.
      this.state.set(sessionState === 'forbidden' || sessionState === 'error' ? 'forbidden' : 'starting');
      return;
    }
    if (!this.isAdmin()) {
      this.state.set('restricted');
      return;
    }
    this.loadAll();
  }

  private loadAll(): void {
    if (this.session.ownerKey() === null || this.session.profile() === null || !this.isAdmin()) return;
    if (this.state() === 'access-changed' || this.state() === 'session-expired') return;
    const sequence = ++this.requestSequence;
    const key = this.activeKey;
    const departmentWriteVersion = this.departmentWriteVersion;
    this.state.set('loading');
    forkJoin({ departments: this.api.departments(), memberships: this.api.memberships() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ departments, memberships }) => {
          if (sequence !== this.requestSequence || key !== this.activeKey) return;
          if (departmentWriteVersion === this.departmentWriteVersion) {
            this.departments.set(departments);
          }
          this.memberships.set(memberships);
          this.state.set(memberships.length === 0 ? 'empty' : 'ready');
          this.departmentAmbiguous.set(false);
          this.createAmbiguous.set(false);
          if (this.departmentStatus()?.kind === 'error' && this.departmentStatus()?.needsReload) {
            this.departmentStatus.set(null);
          }
          if (this.createStatus()?.kind === 'error' && this.createStatus()?.needsReload) {
            this.createStatus.set(null);
          }
          if (this.editStatus()?.kind === 'error' && this.editStatus()?.needsReload && !this.editStale()) {
            this.editStatus.set(null);
          }
          const source = this.editSource();
          if (source !== null && !memberships.some((membership) => membership.id === source.id)) {
            this.selectedMembershipId.set(null);
            this.editSource.set(null);
            this.editStale.set(false);
            this.clearEditForm();
          }
        },
        error: (error: unknown) => {
          if (sequence !== this.requestSequence || key !== this.activeKey) return;
          const status = adminErrorStatus(error);
          if (status === 401) this.accessFailure('session');
          else if (status === 403) this.accessFailure('forbidden');
          else this.state.set('error');
        },
      });
  }

  private handleDepartmentFailure(error: unknown): void {
    const status = adminErrorStatus(error);
    if (status === 401) {
      this.accessFailure('session');
      return;
    }
    if (status === 403) {
      this.accessFailure('forbidden');
      return;
    }
    if (status === 409) {
      this.departmentStatus.set({
        kind: 'error',
        needsReload: false,
        message:
          'A department with this name may already exist in your company. The name is preserved; review the list and change it before trying again.',
      });
      return;
    }
    if (status === 400) {
      this.departmentStatus.set({
        kind: 'error',
        needsReload: false,
        message: 'The backend rejected the department name. Use a nonblank name of at most 200 characters.',
      });
      return;
    }
    if (status === null || status === 0 || status >= 500) {
      this.departmentAmbiguous.set(true);
      this.departmentStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The request may have reached the server, but no confirmation arrived. The name is preserved. Refresh the department list and check it before retrying; this form will not retry automatically.',
      });
      return;
    }
    this.departmentStatus.set({
      kind: 'error',
      needsReload: false,
      message: 'The department could not be created. The name is preserved. Review the input and try again.',
    });
  }

  private handleCreateFailure(error: unknown): void {
    const status = adminErrorStatus(error);
    if (status === 401) {
      this.accessFailure('session');
      return;
    }
    if (status === 403) {
      this.accessFailure('forbidden');
      return;
    }
    if (status === 409) {
      this.createStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'This identity may already have an active company membership, or the data conflicts with the current company scope. Input is preserved; reload and review before trying again.',
      });
      return;
    }
    if (status === 404) {
      this.createStatus.set({
        kind: 'error',
        needsReload: false,
        message:
          'This identity is not registered yet, or the selected department is outside your current company scope. The person must sign in once before you link them; input is preserved.',
      });
      return;
    }
    if (status === 400) {
      this.createStatus.set({
        kind: 'error',
        needsReload: false,
        message:
          'The backend rejected this membership. Use an already registered identity, a scoped department and roles consistent with managed departments.',
      });
      return;
    }
    if (status === null || status === 0 || status >= 500) {
      this.createAmbiguous.set(true);
      this.createStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The request may have reached the server, but no confirmation arrived. The form is preserved. Refresh the membership list and check it before retrying; this form will not retry automatically.',
      });
      return;
    }
    this.createStatus.set({
      kind: 'error',
      needsReload: false,
      message: 'The membership could not be created. The form is preserved. Review the input and try again.',
    });
  }

  private handleEditFailure(error: unknown): void {
    const status = adminErrorStatus(error);
    const code = adminErrorCode(error);
    if (status === 401) {
      this.accessFailure('session');
      return;
    }
    if (status === 403) {
      this.accessFailure('forbidden');
      return;
    }
    if (code === 'stale_membership') {
      this.editStale.set(true);
      this.editStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'This membership changed after it was selected. Your edits and the original expected version are preserved. Reload and review, then select the membership again before saving; the version is never refreshed silently.',
      });
      return;
    }
    if (code === 'last_company_admin') {
      this.editStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The backend refused to remove the last active company administrator. Your edits are preserved. Assign another active company administrator, reload and review before trying again.',
      });
      return;
    }
    if (code === 'manager_assignments_required') {
      this.editStatus.set({
        kind: 'error',
        needsReload: false,
        message: 'A department manager requires at least one managed department, and a nonmanager requires none.',
      });
      return;
    }
    if (status === 404) {
      this.editStale.set(true);
      this.editStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'This membership is no longer available in your company scope. Edits are preserved until reload; reload and review the current list.',
      });
      return;
    }
    if (code === 'retryable_conflict') {
      this.editStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The membership was busy or changed concurrently. Your edits and expected version are preserved; reload and review before trying again.',
      });
      return;
    }
    if (status === 409) {
      this.editStatus.set({
        kind: 'error',
        needsReload: true,
        message: 'The membership could not be updated because of a data conflict. Edits are preserved; reload and review.',
      });
      return;
    }
    if (status === 400) {
      this.editStatus.set({
        kind: 'error',
        needsReload: false,
        message:
          'The backend rejected these assignments. Use a scoped department and roles consistent with managed departments. Edits are preserved.',
      });
      return;
    }
    this.editStatus.set({
      kind: 'error',
      needsReload: false,
      message: 'The membership could not be saved. Edits are preserved. Check the connection and try again.',
    });
  }

  private accessFailure(kind: 'session' | 'forbidden' | 'access-changed'): void {
    this.requestSequence += 1;
    this.departmentWriteSequence += 1;
    this.createWriteSequence += 1;
    this.editWriteSequence += 1;
    this.departments.set([]);
    this.memberships.set([]);
    this.selectedMembershipId.set(null);
    this.editSource.set(null);
    this.departmentPending.set(false);
    this.createPending.set(false);
    this.editPending.set(false);
    this.departmentAmbiguous.set(false);
    this.createAmbiguous.set(false);
    this.editStale.set(false);
    this.createAttempted.set(false);
    this.editAttempted.set(false);
    this.departmentStatus.set(null);
    this.createStatus.set(null);
    this.editStatus.set(null);
    this.departmentForm.reset({ name: '' });
    this.clearCreateForm();
    this.clearEditForm();
    this.state.set(
      kind === 'session' ? 'session-expired' : kind === 'forbidden' ? 'forbidden' : 'access-changed',
    );
  }

  private clearCreateForm(): void {
    this.createForm.controls.identityId.setValue('');
    this.createForm.controls.departmentId.setValue('');
    this.createForm.controls.managedDepartmentIds.setValue([]);
    patchRoles(this.createForm.controls.roles, []);
    this.createForm.markAsPristine();
    this.createForm.markAsUntouched();
  }

  private clearEditForm(): void {
    this.editForm.controls.departmentId.setValue('');
    this.editForm.controls.active.setValue(true);
    this.editForm.controls.managedDepartmentIds.setValue([]);
    patchRoles(this.editForm.controls.roles, []);
    this.editForm.markAsPristine();
    this.editForm.markAsUntouched();
  }

  private updateMembershipList(canonical: MembershipView): void {
    // A successful write is newer than any membership read that started before it.
    this.requestSequence += 1;
    const next = [...this.memberships().filter((membership) => membership.id !== canonical.id), canonical].sort(
      (left, right) => left.identityId.localeCompare(right.identityId) || left.id.localeCompare(right.id),
    );
    this.memberships.set(next);
    this.state.set('ready');
  }

  private departmentInScope(departmentId: string): boolean {
    return this.departments().some((department) => department.id === departmentId);
  }

  private managedInScope(ids: readonly string[]): boolean {
    return ids.every((id) => this.departmentInScope(id));
  }
}
