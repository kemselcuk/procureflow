import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, computed, signal } from '@angular/core';
import { Authentication, MemberProfile } from '../authentication';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';
import { DepartmentView, MembershipView, Role } from './administration-api';
import { AdministrationPage } from './administration-page';

const COMPANY = 'aaaaaaaa-2222-4222-8222-222222222222';
const OTHER_COMPANY = 'bbbbbbbb-2222-4222-8222-222222222222';
const IDENTITY = '11111111-1111-4111-8111-111111111111';
const OTHER_IDENTITY = '22222222-2222-4222-8222-222222222222';
const MEMBERSHIP_ID = '33333333-3333-4333-8333-333333333333';
const OTHER_MEMBERSHIP_ID = '44444444-4444-4444-8444-444444444444';
const ENGINEERING = '55555555-5555-4555-8555-555555555555';
const OPERATIONS = '66666666-6666-4666-8666-666666666666';
const NEW_DEPARTMENT = '77777777-7777-4777-8777-777777777777';

type SessionKind = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden';

/** Fresh WorkspaceSession stand-in mirroring the public contract and its generation rules. */
class SessionStub {
  readonly profile = signal<WorkspaceProfile | null>(null);
  readonly state = signal<SessionKind>('idle');
  readonly errorMessage = signal<string | null>(null);
  readonly ownerKey = signal<string | null>(null);
  readonly generation = signal(0);
  readonly roles = computed(() => new Set(this.profile()?.roles ?? []));

  private fingerprint: string | null = null;

  hasRole(role: WorkspaceRole): boolean {
    return this.roles().has(role);
  }

  isEmployee = computed(() => this.hasRole('EMPLOYEE'));
  isManager = computed(() => this.hasRole('DEPARTMENT_MANAGER'));
  isFinance = computed(() => this.hasRole('FINANCE_APPROVER'));
  canReachApprovals = computed(() => this.isManager() || this.isFinance());

  ensure(): Promise<WorkspaceProfile | null> {
    return Promise.resolve(this.profile());
  }

  refresh(): Promise<WorkspaceProfile | null> {
    return Promise.resolve(this.profile());
  }

  apply(owner: string | null, next: WorkspaceProfile | null, kind: SessionKind): void {
    const fingerprint = JSON.stringify([
      owner,
      next?.identityId ?? null,
      next?.membershipId ?? null,
      next?.companyId ?? null,
      next?.version ?? null,
      next?.departmentId ?? null,
      next === null ? null : [...next.roles].sort(),
      next === null ? null : [...next.managedDepartmentIds].sort(),
    ]);
    if (fingerprint !== this.fingerprint) {
      this.fingerprint = fingerprint;
      this.generation.update((value) => value + 1);
    }
    this.ownerKey.set(owner);
    this.profile.set(next);
    this.state.set(kind);
  }

  signIn(next: WorkspaceProfile, kind: SessionKind = 'ready'): void {
    this.apply(`${next.companyId}\u0000${next.identityId}`, next, kind);
  }
}

function scoped(overrides: Partial<WorkspaceProfile> = {}): WorkspaceProfile {
  return {
    identityId: IDENTITY,
    membershipId: MEMBERSHIP_ID,
    companyId: COMPANY,
    companyName: 'Larkvane',
    departmentId: ENGINEERING,
    departmentName: 'engineering',
    version: 3,
    roles: ['COMPANY_ADMIN'],
    managedDepartmentIds: [],
    ...overrides,
  };
}

/** Compact sign-in snapshot; deliberately ordinary so a stale admin claim cannot grant access. */
function compactAuth(overrides: Partial<MemberProfile> = {}): MemberProfile {
  return {
    identityId: IDENTITY,
    companyId: COMPANY,
    companyName: 'Compact auth company',
    departmentName: 'engineering',
    roles: ['EMPLOYEE'],
    ...overrides,
  };
}

function member(overrides: Partial<MemberProfile> = {}): MemberProfile {
  return {
    identityId: IDENTITY,
    companyId: COMPANY,
    companyName: 'Larkvane',
    departmentName: 'engineering',
    roles: ['COMPANY_ADMIN'],
    ...overrides,
  };
}

function profileToScoped(profile: MemberProfile): WorkspaceProfile {
  return scoped({
    identityId: profile.identityId,
    companyId: profile.companyId,
    companyName: profile.companyName,
    departmentName: profile.departmentName,
    roles: profile.roles as WorkspaceRole[],
  });
}

function department(overrides: Partial<DepartmentView> = {}): DepartmentView {
  return { id: ENGINEERING, name: 'Engineering', ...overrides };
}

function membership(overrides: Partial<MembershipView> = {}): MembershipView {
  return {
    id: MEMBERSHIP_ID,
    identityId: IDENTITY,
    departmentId: ENGINEERING,
    active: true,
    version: 2,
    roles: ['COMPANY_ADMIN'],
    managedDepartmentIds: [],
    ...overrides,
  };
}

describe('Administration page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdministrationPage>;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;
  let session: SessionStub;
  let retryCalls: number;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    session = new SessionStub();
    retryCalls = 0;
    TestBed.configureTestingModule({
      imports: [AdministrationPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: {
            initialize: async () => {},
            state: authState,
            member: authMember,
            login: async () => {},
            logout: async () => {},
            retry: () => {
              retryCalls += 1;
            },
          },
        },
        { provide: WorkspaceSession, useValue: session },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent ?? '';
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(AdministrationPage);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function authenticate(profile: MemberProfile = member()): Promise<void> {
    authState.set('authenticated');
    // The compact sign-in snapshot stays ordinary; the scoped profile decides access.
    authMember.set(compactAuth());
    session.signIn(profileToScoped(profile));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function departmentsRequest(): ReturnType<HttpTestingController['expectOne']> {
    return http.expectOne((candidate) => candidate.url === '/api/departments' && candidate.method === 'GET');
  }

  function membershipsRequest(): ReturnType<HttpTestingController['expectOne']> {
    return http.expectOne((candidate) => candidate.url === '/api/admin/memberships' && candidate.method === 'GET');
  }

  async function load(
    membershipRows: MembershipView[] = [membership()],
    departmentRows: DepartmentView[] = [department()],
  ): Promise<void> {
    await authenticate();
    departmentsRequest().flush(departmentRows);
    membershipsRequest().flush(membershipRows);
    await fixture.whenStable();
  }

  function buttonByText(label: string): HTMLButtonElement {
    const found = Array.from(element().querySelectorAll('button')).find(
      (button) => (button.textContent ?? '').trim() === label,
    );
    if (!found) throw new Error(`Button not found: ${label}`);
    return found as HTMLButtonElement;
  }

  function inputValue(selector: string, value: string): void {
    const input = element().querySelector<HTMLInputElement>(selector);
    if (input === null) throw new Error(`Input not found: ${selector}`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function selectValue(selector: string, value: string): void {
    const select = element().querySelector<HTMLSelectElement>(selector);
    if (select === null) throw new Error(`Select not found: ${selector}`);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function clickCheckbox(selector: string): void {
    const checkbox = element().querySelector<HTMLInputElement>(selector);
    if (checkbox === null) throw new Error(`Checkbox not found: ${selector}`);
    checkbox.click();
    fixture.detectChanges();
  }

  function setCreateMembershipFields(identity = OTHER_IDENTITY, departmentId = ENGINEERING): void {
    inputValue('#create-identity', identity);
    selectValue('#create-department', departmentId);
  }

  it('sends no privileged request for an ordinary authenticated member and renders no admin data', async () => {
    await render();
    await authenticate(member({ roles: ['EMPLOYEE'] }));
    expect(text()).toContain('Company administration is restricted');
    expect(text()).toContain('did not request the privileged membership list');
    expect(element().querySelector('form')).toBeNull();
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it.each(['starting', 'anonymous', 'forbidden', 'error'] as const)(
    'sends no request while the session is %s',
    async (state) => {
      authState.set(state);
      await render();
      expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
    },
  );

  it('loads scoped departments and memberships for COMPANY_ADMIN with UUIDs but no invented names or emails', async () => {
    await render();
    await load(
      [
        membership({ roles: ['EMPLOYEE'] }),
        membership({
          id: OTHER_MEMBERSHIP_ID,
          identityId: OTHER_IDENTITY,
          departmentId: OPERATIONS,
          version: 5,
          roles: ['DEPARTMENT_MANAGER', 'FINANCE_APPROVER'],
          managedDepartmentIds: [ENGINEERING, OPERATIONS],
        }),
      ],
      [department(), department({ id: OPERATIONS, name: 'Operations' })],
    );

    expect(text()).toContain('Engineering');
    expect(text()).toContain('Operations');
    expect(text()).toContain(IDENTITY);
    expect(text()).toContain(OTHER_IDENTITY);
    expect(text()).toContain('Department manager, Finance approver');
    expect(text()).toContain('version 5');
    expect(text()).not.toContain('@');
    expect(element().querySelector('input[type="email"], input[type="password"]')).toBeNull();
    expect(http.match((candidate) => candidate.url.includes('identit')).length).toBe(0);
  });

  it('shows an honest empty membership state while keeping the scoped create form', async () => {
    await render();
    await load([], [department()]);
    expect(text()).toContain('No memberships have been linked yet');
    expect(element().querySelector('#create-identity')).not.toBeNull();
    expect(element().querySelector('.membership-card')).toBeNull();
  });

  it('clears privileged rows and forms on a 403 load and can re-check access', async () => {
    await render();
    await authenticate();
    const departments = departmentsRequest();
    departments.flush([department()]);
    membershipsRequest().flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(text()).toContain('Company administration access is unavailable');
    expect(text()).toContain('All membership and department rows were cleared');
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(element().querySelector('form')).toBeNull();

    buttonByText('Check access').click();
    await fixture.whenStable();
    departmentsRequest().flush([department()]);
    membershipsRequest().flush([membership({ roles: ['EMPLOYEE'] })]);
    await fixture.whenStable();
    expect(text()).toContain(IDENTITY);
  });

  it('creates a department with the trimmed exact name and appends the returned canonical row', async () => {
    await render();
    await load([membership()], [department()]);
    inputValue('#department-name', '  Operations  ');
    buttonByText('Create department').click();
    fixture.detectChanges();

    const request = http.expectOne('/api/admin/departments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ name: 'Operations' });
    request.flush(department({ id: NEW_DEPARTMENT, name: 'Operations' }), {
      status: 201,
      statusText: 'Created',
    });
    await fixture.whenStable();
    expect(text()).toContain('Created department');
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('');
    expect(text()).toContain('Operations');
  });

  it.each([
    ['', 'Enter a nonblank department name'],
    ['   ', 'Enter a nonblank department name'],
    ['x'.repeat(201), 'Use 200 characters or fewer'],
  ])('rejects the invalid department name without a request', async (name, message) => {
    await render();
    await load();
    inputValue('#department-name', name);
    buttonByText('Create department').click();
    fixture.detectChanges();
    expect(text()).toContain(message);
    expect(http.match((candidate) => candidate.url === '/api/admin/departments' && candidate.method === 'POST').length).toBe(0);
  });

  it('preserves the department input on a unique-name conflict', async () => {
    await render();
    await load();
    inputValue('#department-name', 'Engineering');
    buttonByText('Create department').click();
    http
      .expectOne('/api/admin/departments')
      .flush({ status: 409, code: 'data_conflict' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    expect(text()).toContain('may already exist');
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('Engineering');
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);
  });

  it('does not retry an ambiguous department POST automatically and requires a refresh before retry', async () => {
    await render();
    await load();
    inputValue('#department-name', 'Operations');
    const create = buttonByText('Create department');
    create.click();
    fixture.detectChanges();
    create.click();
    fixture.detectChanges();
    const writes = http.match(
      (candidate) => candidate.url === '/api/admin/departments' && candidate.method === 'POST',
    );
    expect(writes.length).toBe(1);
    writes[0]?.flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(text()).toContain('may have reached the server');
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('Operations');
    expect(buttonByText('Create department').disabled).toBe(true);
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);

    buttonByText('Refresh department list').click();
    departmentsRequest().flush([department()]);
    membershipsRequest().flush([membership()]);
    await fixture.whenStable();
    expect(buttonByText('Create department').disabled).toBe(false);
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('Operations');

    buttonByText('Create department').click();
    const retried = http.expectOne('/api/admin/departments');
    expect(retried.request.body).toEqual({ name: 'Operations' });
    retried.flush(department({ id: NEW_DEPARTMENT, name: 'Operations' }), {
      status: 201,
      statusText: 'Created',
    });
    await fixture.whenStable();
  });

  it('links an existing identity with exact UUIDs, roles and no account/password fields', async () => {
    await render();
    await load([], [department(), department({ id: OPERATIONS, name: 'Operations' })]);
    setCreateMembershipFields();
    clickCheckbox('#create-role-EMPLOYEE');
    buttonByText('Link membership').click();
    fixture.detectChanges();

    const request = http.expectOne('/api/admin/memberships');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      identityId: OTHER_IDENTITY,
      departmentId: ENGINEERING,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    });
    expect(request.request.body).not.toHaveProperty('companyId');
    expect(request.request.body).not.toHaveProperty('password');
    request.flush(
      membership({ id: OTHER_MEMBERSHIP_ID, identityId: OTHER_IDENTITY, roles: ['EMPLOYEE'], version: 0 }),
      { status: 201, statusText: 'Created' },
    );
    await fixture.whenStable();
    expect(text()).toContain('Linked identity');
    expect(text()).toContain(OTHER_IDENTITY);
    expect(element().querySelector<HTMLInputElement>('#create-identity')?.value).toBe('');
    expect(text()).toContain('must first sign in once');
  });

  it('requires at least one role and an already registered identity UUID', async () => {
    await render();
    await load([], [department()]);
    setCreateMembershipFields();
    inputValue('#create-identity', 'not-a-uuid');
    buttonByText('Link membership').click();
    fixture.detectChanges();
    expect(text()).toContain('already registered identity');
    expect(http.match((candidate) => candidate.url === '/api/admin/memberships' && candidate.method === 'POST').length).toBe(0);

    inputValue('#create-identity', OTHER_IDENTITY);
    buttonByText('Link membership').click();
    fixture.detectChanges();
    expect(text()).toContain('Choose at least one role');
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);
  });

  it('enforces manager assignments in both directions before sending a request', async () => {
    await render();
    await load([], [department(), department({ id: OPERATIONS, name: 'Operations' })]);
    setCreateMembershipFields();
    clickCheckbox('#create-role-DEPARTMENT_MANAGER');
    buttonByText('Link membership').click();
    fixture.detectChanges();
    expect(text()).toContain('requires at least one managed department');
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);

    clickCheckbox(`#create-managed-${OPERATIONS}`);
    buttonByText('Link membership').click();
    const request = http.expectOne('/api/admin/memberships');
    expect(request.request.body).toEqual({
      identityId: OTHER_IDENTITY,
      departmentId: ENGINEERING,
      roles: ['DEPARTMENT_MANAGER'],
      managedDepartmentIds: [OPERATIONS],
    });
    request.flush(
      membership({
        id: OTHER_MEMBERSHIP_ID,
        identityId: OTHER_IDENTITY,
        roles: ['DEPARTMENT_MANAGER'],
        managedDepartmentIds: [OPERATIONS],
        version: 0,
      }),
      { status: 201, statusText: 'Created' },
    );
    await fixture.whenStable();
    expect(text()).toContain('Operations');
  });

  it('rejects managed assignments for a nonmanager', async () => {
    await render();
    await load([], [department()]);
    setCreateMembershipFields();
    clickCheckbox('#create-role-EMPLOYEE');
    clickCheckbox(`#create-managed-${ENGINEERING}`);
    buttonByText('Link membership').click();
    fixture.detectChanges();
    expect(text()).toContain('Only a department manager');
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);
  });

  it('does not retry an ambiguous membership POST automatically', async () => {
    await render();
    await load([], [department()]);
    setCreateMembershipFields();
    clickCheckbox('#create-role-EMPLOYEE');
    buttonByText('Link membership').click();
    http
      .expectOne('/api/admin/memberships')
      .flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(text()).toContain('may have reached the server');
    expect(element().querySelector<HTMLInputElement>('#create-identity')?.value).toBe(OTHER_IDENTITY);
    expect(buttonByText('Link membership').disabled).toBe(true);
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);

    buttonByText('Refresh membership list').click();
    departmentsRequest().flush([department()]);
    membershipsRequest().flush([]);
    await fixture.whenStable();
    expect(buttonByText('Link membership').disabled).toBe(false);
  });

  it('edits department, roles and active flag with the selected canonical version', async () => {
    await render();
    await load([membership({ identityId: OTHER_IDENTITY, roles: ['EMPLOYEE'], version: 2 })], [
      department(),
      department({ id: OPERATIONS, name: 'Operations' }),
    ]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    expect((element().querySelector('#edit-membership') as HTMLSelectElement).value).toBe(MEMBERSHIP_ID);
    expect(text()).toContain('Expected version: 2');

    selectValue('#edit-department', OPERATIONS);
    clickCheckbox('#edit-role-FINANCE_APPROVER');
    clickCheckbox('#edit-active');
    buttonByText('Save membership').click();
    fixture.detectChanges();

    const request = http.expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      departmentId: OPERATIONS,
      active: false,
      version: 2,
      roles: ['EMPLOYEE', 'FINANCE_APPROVER'],
      managedDepartmentIds: [],
    });
    expect(request.request.body).not.toHaveProperty('identityId');
    request.flush(
      membership({
        identityId: OTHER_IDENTITY,
        departmentId: OPERATIONS,
        active: false,
        version: 3,
        roles: ['EMPLOYEE', 'FINANCE_APPROVER'],
      }),
    );
    await fixture.whenStable();
    expect(text()).toContain('Saved membership');
    expect(text()).toContain('version 3');
    expect(text()).toContain('Operations');
  });

  it('keeps edits and the original version on stale_membership until an explicit reload and reselect', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'], version: 2 })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-role-FINANCE_APPROVER');
    buttonByText('Save membership').click();
    http
      .expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`)
      .flush({ status: 409, code: 'stale_membership' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(text()).toContain('This membership changed after it was selected');
    expect(text()).toContain('Expected version: 2');
    expect((element().querySelector('#edit-role-FINANCE_APPROVER') as HTMLInputElement).checked).toBe(true);
    expect(buttonByText('Save membership').disabled).toBe(true);
    expect(http.match((candidate) => candidate.url === '/api/admin/memberships' && candidate.method === 'GET').length).toBe(0);

    buttonByText('Reload memberships').click();
    departmentsRequest().flush([department()]);
    membershipsRequest().flush([membership({ roles: ['EMPLOYEE'], version: 5 })]);
    await fixture.whenStable();
    expect(text()).toContain('Expected version: 2');
    expect((element().querySelector('#edit-role-FINANCE_APPROVER') as HTMLInputElement).checked).toBe(true);
    expect(buttonByText('Save membership').disabled).toBe(true);

    selectValue('#edit-membership', MEMBERSHIP_ID);
    expect(text()).toContain('Expected version: 5');
    expect((element().querySelector('#edit-role-FINANCE_APPROVER') as HTMLInputElement).checked).toBe(false);
    expect(buttonByText('Save membership').disabled).toBe(false);
  });

  it('explains last_company_admin and preserves the edit without silently changing the version', async () => {
    await render();
    await load([membership({ roles: ['COMPANY_ADMIN'], version: 4 })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-role-COMPANY_ADMIN');
    clickCheckbox('#edit-role-EMPLOYEE');
    buttonByText('Save membership').click();
    http
      .expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`)
      .flush({ status: 409, code: 'last_company_admin' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(text()).toContain('last active company administrator');
    expect(text()).toContain('Expected version: 4');
    expect((element().querySelector('#edit-role-EMPLOYEE') as HTMLInputElement).checked).toBe(true);
    expect(buttonByText('Save membership').disabled).toBe(false);
    expect(http.match((candidate) => candidate.method === 'PUT').length).toBe(0);
  });

  it('scrubs every privileged row and form immediately after a self-edit removes own admin access', async () => {
    await render();
    await load([membership({ roles: ['COMPANY_ADMIN'], version: 2 })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-role-COMPANY_ADMIN');
    clickCheckbox('#edit-role-EMPLOYEE');
    buttonByText('Save membership').click();
    http
      .expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`)
      .flush(membership({ roles: ['EMPLOYEE'], version: 3 }));
    await fixture.whenStable();

    expect(text()).toContain('Your own administrator membership may have changed');
    expect(text()).toContain('All privileges and forms were cleared');
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(element().querySelector('form')).toBeNull();
    expect(element().querySelector('#create-identity')).toBeNull();
    buttonByText('Check session').click();
    expect(retryCalls).toBe(1);
  });

  it('scrubs immediately when a self-edit deactivates the current membership', async () => {
    await render();
    await load([membership({ roles: ['COMPANY_ADMIN'], version: 2 })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-active');
    buttonByText('Save membership').click();
    http
      .expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`)
      .flush(membership({ active: false, roles: ['COMPANY_ADMIN'], version: 3 }));
    await fixture.whenStable();
    expect(text()).toContain('Your own administrator membership may have changed');
    expect(element().querySelector('.membership-card')).toBeNull();
  });

  it('clears privileged data on a 403 edit write', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'] })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-role-FINANCE_APPROVER');
    buttonByText('Save membership').click();
    http
      .expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`)
      .flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(text()).toContain('Company administration access is unavailable');
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(element().querySelector('form')).toBeNull();
  });

  it('ignores an old edit response after the authenticated company changes', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'], version: 2 })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-role-FINANCE_APPROVER');
    buttonByText('Save membership').click();
    const write = http.expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`);

    authMember.set(compactAuth({ companyId: OTHER_COMPANY, companyName: 'Other company' }));
    session.signIn(profileToScoped(member({ companyId: OTHER_COMPANY, companyName: 'Other company' })));
    fixture.detectChanges();
    expect(text()).toContain('Loading scoped departments');
    write.flush(membership({ roles: ['EMPLOYEE', 'FINANCE_APPROVER'], version: 3 }));
    await fixture.whenStable();
    expect(text()).not.toContain('Saved membership');

    departmentsRequest().flush([department({ id: OPERATIONS, name: 'Operations' })]);
    membershipsRequest().flush([
      membership({ id: OTHER_MEMBERSHIP_ID, identityId: OTHER_IDENTITY, departmentId: OPERATIONS, roles: ['EMPLOYEE'] }),
    ]);
    await fixture.whenStable();
    expect(text()).toContain('Operations');
    expect(text()).toContain(OTHER_IDENTITY);
  });

  it('scrubs on a 401 read and offers a session check', async () => {
    await render();
    await authenticate();
    const departments = departmentsRequest();
    departments.flush([department()]);
    membershipsRequest().flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(text()).toContain('Your session is no longer valid');
    expect(element().querySelector('.membership-card')).toBeNull();
    buttonByText('Check session').click();
    expect(retryCalls).toBe(1);
  });

  it('does not let a membership read that started before a successful edit overwrite the canonical version', async () => {
    await render();
    await load([membership({ identityId: OTHER_IDENTITY, roles: ['EMPLOYEE'], version: 2 })], [department()]);
    buttonByText('Edit this membership').click();
    fixture.detectChanges();
    clickCheckbox('#edit-role-FINANCE_APPROVER');
    buttonByText('Refresh data').click();
    const staleMemberships = membershipsRequest();
    const staleDepartments = departmentsRequest();
    buttonByText('Save membership').click();
    const write = http.expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`);
    write.flush(
      membership({ identityId: OTHER_IDENTITY, roles: ['EMPLOYEE', 'FINANCE_APPROVER'], version: 3 }),
    );
    await fixture.whenStable();
    staleMemberships.flush([membership({ identityId: OTHER_IDENTITY, roles: ['EMPLOYEE'], version: 2 })]);
    staleDepartments.flush([department()]);
    await fixture.whenStable();

    expect(text()).toContain('version 3');
    expect(text()).toContain('Saved membership');
    expect(text()).not.toContain('version 2');
  });

  it('preserves a confirmed department when a refresh that started earlier returns an older snapshot', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'], version: 2 })], [department()]);

    inputValue('#department-name', 'Operations');
    buttonByText('Create department').click();
    const create = http.expectOne('/api/admin/departments');
    buttonByText('Refresh data').click();
    const staleDepartments = departmentsRequest();
    const staleMemberships = membershipsRequest();

    create.flush(department({ id: NEW_DEPARTMENT, name: 'Operations' }), {
      status: 201,
      statusText: 'Created',
    });
    await fixture.whenStable();
    expect(text()).toContain('Created department');
    expect(element().querySelector(`#create-department option[value="${NEW_DEPARTMENT}"]`)).not.toBeNull();

    staleDepartments.flush([department()]);
    staleMemberships.flush([membership({ roles: ['FINANCE_APPROVER'], version: 9 })]);
    await fixture.whenStable();

    expect(text()).toContain('Created department');
    expect(text()).toContain('Operations');
    expect(element().querySelector(`#create-department option[value="${NEW_DEPARTMENT}"]`)).not.toBeNull();
    expect(text()).toContain('Finance approver');
    expect(text()).not.toContain('Loading scoped departments and memberships');

    buttonByText('Refresh data').click();
    const freshDepartments = departmentsRequest();
    const freshMemberships = membershipsRequest();
    freshDepartments.flush([
      department({ name: 'Engineering updated' }),
      department({ id: NEW_DEPARTMENT, name: 'Operations' }),
    ]);
    freshMemberships.flush([membership({ roles: ['EMPLOYEE'], version: 10 })]);
    await fixture.whenStable();
    expect(text()).toContain('Engineering updated');
    expect(element().querySelector(`#create-department option[value="${NEW_DEPARTMENT}"]`)).not.toBeNull();
    expect(text()).toContain('Employee');
  });

  it('clears every privileged row and form immediately on logout', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'] })], [department()]);
    expect(element().querySelector('.membership-card')).not.toBeNull();

    authMember.set(null);
    authState.set('anonymous');
    session.apply(null, null, 'idle');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Sign in with an active company membership');
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(element().querySelector('form')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it('stops exposing administration data and sends no request when the admin role disappears', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'] })], [department()]);
    expect(element().querySelector('.membership-card')).not.toBeNull();

    authMember.set(compactAuth());
    session.signIn(profileToScoped(member({ roles: ['EMPLOYEE'] })));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Company administration is restricted');
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(element().querySelector('form')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it('maps an unregistered identity conflict to fixed guidance and keeps the form', async () => {
    await render();
    await load([], [department()]);
    setCreateMembershipFields();
    clickCheckbox('#create-role-EMPLOYEE');
    buttonByText('Link membership').click();
    http
      .expectOne('/api/admin/memberships')
      .flush({ status: 404, code: 'not_found' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(text()).toContain('This identity is not registered yet');
    expect(element().querySelector<HTMLInputElement>('#create-identity')?.value).toBe(OTHER_IDENTITY);
    expect(http.match((candidate) => candidate.method === 'POST').length).toBe(0);
  });

  it('keeps the latest membership selection when an older list read settles later', async () => {
    await render();
    await load(
      [
        membership({ identityId: OTHER_IDENTITY, roles: ['EMPLOYEE'], version: 2 }),
        membership({
          id: OTHER_MEMBERSHIP_ID,
          identityId: '88888888-8888-4888-8888-888888888888',
          roles: ['EMPLOYEE'],
          version: 7,
        }),
      ],
      [department()],
    );
    selectValue('#edit-membership', MEMBERSHIP_ID);
    expect(text()).toContain('Expected version: 2');

    buttonByText('Refresh data').click();
    const staleMemberships = membershipsRequest();
    const staleDepartments = departmentsRequest();
    selectValue('#edit-membership', OTHER_MEMBERSHIP_ID);
    expect(text()).toContain('Expected version: 7');

    staleMemberships.flush([
      membership({ identityId: OTHER_IDENTITY, roles: ['EMPLOYEE'], version: 3 }),
      membership({
        id: OTHER_MEMBERSHIP_ID,
        identityId: '88888888-8888-4888-8888-888888888888',
        roles: ['EMPLOYEE'],
        version: 7,
      }),
    ]);
    staleDepartments.flush([department()]);
    await fixture.whenStable();
    expect(text()).toContain('Expected version: 7');
    expect(element().querySelector('.edit-form .identity code')?.textContent).toBe(
      '88888888-8888-4888-8888-888888888888',
    );
  });

  it('uses scoped native selects, labelled checkboxes, legends and no company selector', async () => {
    await render();
    await load([membership({ roles: ['EMPLOYEE'] })], [
      department(),
      department({ id: OPERATIONS, name: 'Operations' }),
    ]);
    const departmentOptions = Array.from(
      element().querySelectorAll<HTMLOptionElement>('#create-department option'),
    ).map((option) => option.value);
    expect(departmentOptions).toEqual(['', ENGINEERING, OPERATIONS]);
    expect(element().querySelector('label[for="create-department"]')).not.toBeNull();
    expect(element().querySelector('label[for="create-identity"]')).not.toBeNull();
    expect(element().querySelector('fieldset legend')?.textContent).toContain('Link an existing identity');

    for (const role of ['EMPLOYEE', 'DEPARTMENT_MANAGER', 'FINANCE_APPROVER', 'COMPANY_ADMIN'] as Role[]) {
      expect(element().querySelector(`label[for="create-role-${role}"]`)).not.toBeNull();
      expect(element().querySelector(`#create-role-${role}`)).not.toBeNull();
    }
    expect(element().querySelector('select option[value="company"]')).toBeNull();
    expect(element().querySelectorAll('button:not([type])').length).toBe(0);
  });

  it('never grants administration from the stale compact sign-in admin snapshot', async () => {
    await render();
    authState.set('authenticated');
    authMember.set(compactAuth({ roles: ['COMPANY_ADMIN'] }));
    session.signIn(scoped({ roles: ['EMPLOYEE'] }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Company administration is restricted');
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/admin')).length).toBe(0);
  });

  it('allows the current profile admin without re-login even when the compact snapshot is ordinary', async () => {
    await render();
    await authenticate();
    expect(authMember()?.roles).toEqual(['EMPLOYEE']);
    expect(text()).toContain('Loading scoped departments');
    departmentsRequest().flush([department()]);
    membershipsRequest().flush([membership({ roles: ['EMPLOYEE'] })]);
    await fixture.whenStable();
    expect(text()).toContain(IDENTITY);
  });

  it('uses the current scoped company in the scope label instead of the older auth label', async () => {
    await render();
    authState.set('authenticated');
    authMember.set(compactAuth({ companyName: 'Old auth label' }));
    session.signIn(scoped({ companyName: 'Fresh scope company' }));
    fixture.detectChanges();
    await fixture.whenStable();
    departmentsRequest().flush([department()]);
    membershipsRequest().flush([membership()]);
    await fixture.whenStable();
    const scope = element().querySelector('.scope');
    expect(scope?.textContent).toContain('Fresh scope company');
    expect(scope?.textContent).not.toContain('Old auth label');
  });

  it('preserves a department draft during a transient loading state and clears it when authority is lost', async () => {
    await render();
    await load();
    inputValue('#department-name', 'Operations');
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('Operations');

    session.state.set('loading');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('Operations');
    expect(element().querySelector('.membership-card')).not.toBeNull();

    session.state.set('ready');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element().querySelector<HTMLInputElement>('#department-name')?.value).toBe('Operations');

    session.signIn(scoped({ roles: ['EMPLOYEE'], version: 4 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Company administration is restricted');
    expect(element().querySelector('#department-name')).toBeNull();
    expect(element().querySelector('.membership-card')).toBeNull();
  });

  it('clears privileged rows on a settled forbidden profile and cannot restore them from a late prior read', async () => {
    await render();
    await load();
    buttonByText('Refresh data').click();
    const staleDepartments = departmentsRequest();
    const staleMemberships = membershipsRequest();

    session.errorMessage.set('Your membership is no longer active.');
    session.apply(`${COMPANY}\u0000${IDENTITY}`, null, 'forbidden');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Company administration access is unavailable');
    expect(element().querySelector('.membership-card')).toBeNull();

    staleDepartments.flush([department()]);
    staleMemberships.flush([membership()]);
    await fixture.whenStable();
    expect(element().querySelector('.membership-card')).toBeNull();
    expect(text()).toContain('Company administration access is unavailable');
  });
});
