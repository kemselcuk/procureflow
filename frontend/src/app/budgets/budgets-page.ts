import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { finalize } from 'rxjs';
import { Authentication } from '../authentication';
import { WorkspaceSession } from '../workspace/workspace-session';
import {
  BudgetApi,
  BudgetView,
  DepartmentView,
  budgetErrorCode,
  budgetErrorStatus,
  currentUtcMonth,
  parseBudgetMonth,
} from './budget-api';

type BudgetsState =
  | 'starting'
  | 'signed-out'
  | 'loading'
  | 'ready'
  | 'empty'
  | 'forbidden'
  | 'session-expired'
  | 'error';

interface WriteStatus {
  readonly kind: 'success' | 'error';
  readonly message: string;
  readonly needsReload: boolean;
}

const MONTH_FORMAT = new Intl.DateTimeFormat('en-GB', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

const MONEY_FORMAT = new Intl.NumberFormat('en-IE', {
  style: 'currency',
  currency: 'EUR',
  currencyDisplay: 'symbol',
});

const MONTH_VALIDATOR: ValidatorFn = (control: AbstractControl): ValidationErrors | null =>
  typeof control.value === 'string' && parseBudgetMonth(control.value) !== null ? null : { month: true };

type AmountReason = 'required' | 'format' | 'precision' | 'range';

/** Validates the limit as text: nonnegative, at most 12 integer digits and two decimals. */
export function limitAmountReason(raw: string): AmountReason | null {
  const value = raw.trim();
  if (value.length === 0) return 'required';
  if (!/^[0-9]+(?:\.[0-9]+)?$/.test(value)) return 'format';
  const [integer, fraction = ''] = value.split('.');
  if (integer.length > 1 && integer.startsWith('0')) return 'format';
  if (fraction.length > 2) return 'precision';
  if (integer.length > 12) return 'range';
  return null;
}

const LIMIT_VALIDATOR: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  if (typeof control.value !== 'string') return { budgetAmount: { reason: 'format' } };
  const reason = limitAmountReason(control.value);
  return reason === null ? null : { budgetAmount: { reason } };
};

/** Converts validated text through integer cents so no decimal is silently rounded. */
export function parseLimitAmount(raw: string): number | null {
  if (limitAmountReason(raw) !== null) return null;
  const [integer, fraction = ''] = raw.trim().split('.');
  const cents = Number(integer) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(cents)) return null;
  return cents / 100;
}

@Component({
  selector: 'app-budgets-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './budgets-page.html',
  styleUrl: './budgets-page.css',
})
export class BudgetsPage {
  private readonly api = inject(BudgetApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(NonNullableFormBuilder);
  readonly auth = inject(Authentication);
  /** Current server membership; role and identity decisions come only from here. */
  readonly session = inject(WorkspaceSession);

  readonly monthControl = this.forms.control(currentUtcMonth(), { validators: MONTH_VALIDATOR });
  readonly limitForm = this.forms.group({
    departmentId: ['', Validators.required],
    limitAmount: ['', LIMIT_VALIDATOR],
  });

  readonly state = signal<BudgetsState>('starting');
  readonly budgets = signal<readonly BudgetView[]>([]);
  readonly departments = signal<readonly DepartmentView[]>([]);
  readonly loadedMonth = signal<string | null>(null);
  readonly monthError = signal<string | null>(null);
  readonly departmentNotice = signal<string | null>(null);
  readonly departmentSelectionInvalid = signal(false);
  readonly pending = signal(false);
  readonly writeStatus = signal<WriteStatus | null>(null);

  private activeKey: string | null = null;
  /** Monotonic session epoch; every authentication transition advances it. */
  private sessionEpoch = 0;
  /** Budget read generation; month changes advance it. */
  private requestSequence = 0;
  /** Department reads are scoped to the session and are independent of the selected month. */
  private departmentSequence = 0;
  private writeSequence = 0;

  /**
   * Budget visibility follows the current `/api/me` membership only. The compact sign-in
   * snapshot may still claim COMPANY_ADMIN; it never grants this screen, the admin form or a
   * fetch. The backend remains authoritative for every request.
   */
  readonly isAdmin = computed(() => this.session.hasRole('COMPANY_ADMIN'));

  /** Reads match the backend scope: company admin, finance approver or department manager. */
  readonly canRead = computed(
    () => this.isAdmin() || this.session.isManager() || this.session.isFinance(),
  );

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
    this.monthControl.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.monthChanged());
    this.limitForm.controls.departmentId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.departmentChanged());
  }

  departmentName(departmentId: string): string {
    return this.departmentNames().get(departmentId) ?? 'Department unavailable';
  }

  formatMonth(month: string): string {
    const [year, value] = month.split('-').map(Number);
    return MONTH_FORMAT.format(new Date(Date.UTC(year, value - 1, 1)));
  }

  formatMoney(amount: number): string {
    return MONEY_FORMAT.format(amount);
  }

  selectedBudget(): BudgetView | null {
    const month = this.loadedMonth();
    const departmentId = this.limitForm.controls.departmentId.value;
    if (month === null || departmentId.length === 0) return null;
    return (
      this.budgets().find(
        (budget) => budget.departmentId === departmentId && budget.month.slice(0, 7) === month,
      ) ?? null
    );
  }

  monthLabel(): string {
    const month = this.loadedMonth() ?? parseBudgetMonth(this.monthControl.value);
    return month === null ? 'the selected month' : this.formatMonth(`${month}-01`);
  }

  limitError(): string | null {
    const control = this.limitForm.controls.limitAmount;
    if (!control.invalid || !(control.touched || control.dirty)) return null;
    const reason = control.getError('budgetAmount')?.['reason'] as AmountReason | undefined;
    switch (reason) {
      case 'required':
        return 'Enter a limit amount. Zero is allowed.';
      case 'precision':
        return 'Use at most two decimal places. The amount is never rounded.';
      case 'range':
        return 'Use at most 12 integer digits (maximum 999,999,999,999.99).';
      case 'format':
        return 'Enter a nonnegative amount using digits and an optional decimal point, for example 1250.50.';
      default:
        return 'Enter a valid nonnegative EUR amount.';
    }
  }

  departmentError(): string | null {
    const control = this.limitForm.controls.departmentId;
    if (this.departmentSelectionInvalid()) return 'Select a department from your current scoped list.';
    return control.invalid && control.touched ? 'Select a department.' : null;
  }

  reload(): void {
    if (!this.canRead() || this.session.profile() === null) return;
    this.writeStatus.set(null);
    this.departmentNotice.set(null);
    this.loadAll();
  }

  /**
   * Re-checks the current membership before any privileged fetch. A changed profile advances
   * the session key and the effect reloads; an unchanged, still-eligible profile retries the
   * scoped reads without a second profile poll.
   */
  async retryAccess(): Promise<void> {
    this.writeStatus.set(null);
    if (this.session.ownerKey() === null) {
      await this.auth.login();
      return;
    }
    const generation = this.session.generation();
    const activeKey = this.activeKey;
    await this.session.refresh();
    // If the profile key changed, the session effect already reloaded or denied. Only retry the
    // scoped reads when the same, still-eligible session survived the re-check.
    if (
      this.activeKey === activeKey &&
      this.session.generation() === generation &&
      this.canRead() &&
      this.session.profile() !== null
    ) {
      this.loadAll();
    }
  }

  checkSession(): void {
    this.auth.retry();
  }

  useCurrentMonth(): void {
    this.monthControl.setValue(currentUtcMonth());
  }

  reloadBudgets(): void {
    const month = parseBudgetMonth(this.monthControl.value);
    if (month === null) return;
    this.loadBudgets(month);
  }

  saveLimit(): void {
    if (!this.isAdmin() || this.pending()) return;
    const month = parseBudgetMonth(this.monthControl.value);
    if (month === null) {
      this.monthError.set('Choose a valid month between 2000-01 and 9999-12.');
      return;
    }
    const departmentId = this.limitForm.controls.departmentId.value;
    if (!this.departments().some((department) => department.id === departmentId)) {
      this.departmentSelectionInvalid.set(true);
      this.limitForm.controls.departmentId.markAsTouched();
      return;
    }
    if (this.limitForm.invalid) {
      this.limitForm.markAllAsTouched();
      return;
    }
    const amount = parseLimitAmount(this.limitForm.controls.limitAmount.value);
    if (amount === null) {
      this.limitForm.controls.limitAmount.markAsTouched();
      return;
    }

    const key = this.activeKey;
    const sequence = ++this.writeSequence;
    this.pending.set(true);
    this.writeStatus.set(null);
    this.api
      .setLimit(departmentId, month, amount)
      .pipe(
        finalize(() => {
          if (sequence === this.writeSequence) this.pending.set(false);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (canonical) => {
          if (sequence !== this.writeSequence || key !== this.activeKey) return;
          this.applyCanonicalBudget(canonical);
        },
        error: (error: unknown) => {
          if (sequence !== this.writeSequence || key !== this.activeKey) return;
          this.handleWriteFailure(error);
        },
      });
  }

  private applySession(key: string): void {
    if (key === this.activeKey) return;
    this.activeKey = key;
    this.sessionEpoch += 1;
    this.requestSequence += 1;
    this.departmentSequence += 1;
    this.writeSequence += 1;
    this.budgets.set([]);
    this.departments.set([]);
    this.loadedMonth.set(null);
    this.monthError.set(null);
    this.departmentNotice.set(null);
    this.departmentSelectionInvalid.set(false);
    this.pending.set(false);
    this.writeStatus.set(null);
    this.limitForm.reset({ departmentId: '', limitAmount: '' });

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
    if (!this.canRead()) {
      this.state.set('forbidden');
      return;
    }

    this.monthControl.setValue(currentUtcMonth(), { emitEvent: false });
    this.monthControl.markAsPristine();
    this.loadAll();
  }

  private loadAll(): void {
    if (this.session.ownerKey() === null || this.session.profile() === null || !this.canRead()) return;
    const month = parseBudgetMonth(this.monthControl.value);
    if (month === null) {
      this.requestSequence += 1;
      this.monthError.set('Choose a valid month between 2000-01 and 9999-12.');
      this.budgets.set([]);
      this.loadedMonth.set(null);
      this.state.set('error');
      return;
    }
    const sequence = ++this.requestSequence;
    const departmentSequence = ++this.departmentSequence;
    const key = this.activeKey;
    const epoch = this.sessionEpoch;
    this.loadedMonth.set(month);
    this.state.set('loading');
    this.fetchDepartments(departmentSequence, key, epoch);
    this.fetchBudgets(sequence, key, epoch, month);
  }

  private loadBudgets(month: string): void {
    if (this.session.profile() === null || !this.canRead() || this.activeKey === null) return;
    const sequence = ++this.requestSequence;
    this.loadedMonth.set(month);
    this.state.set('loading');
    this.fetchBudgets(sequence, this.activeKey, this.sessionEpoch, month);
  }

  private fetchDepartments(sequence: number, key: string | null, epoch: number): void {
    this.departmentNotice.set(null);
    this.api.departments().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (departments) => {
        if (epoch !== this.sessionEpoch || sequence !== this.departmentSequence || key !== this.activeKey) return;
        this.departments.set(departments);
        this.maybePrefillLimit();
      },
      error: (error: unknown) => {
        if (epoch !== this.sessionEpoch || key !== this.activeKey) return;
        const status = budgetErrorStatus(error);
        if (status === 401) {
          this.accessFailure('session');
          return;
        }
        if (status === 403) {
          this.accessFailure('forbidden');
          return;
        }
        if (sequence !== this.departmentSequence) return;
        this.departmentNotice.set('The scoped department list is unavailable. Reload to show department names.');
      },
    });
  }

  private fetchBudgets(sequence: number, key: string | null, epoch: number, month: string): void {
    this.api.list(month).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (budgets) => {
        if (epoch !== this.sessionEpoch || sequence !== this.requestSequence || key !== this.activeKey) return;
        if (parseBudgetMonth(this.monthControl.value) !== month) return;
        this.budgets.set(budgets);
        this.loadedMonth.set(month);
        this.state.set(budgets.length === 0 ? 'empty' : 'ready');
        this.maybePrefillLimit();
        if (this.writeStatus()?.needsReload === true) this.writeStatus.set(null);
      },
      error: (error: unknown) => {
        if (epoch !== this.sessionEpoch || key !== this.activeKey) return;
        const status = budgetErrorStatus(error);
        if (status === 401) {
          this.accessFailure('session');
          return;
        }
        if (status === 403) {
          this.accessFailure('forbidden');
          return;
        }
        if (sequence !== this.requestSequence) return;
        this.handleReadFailure(error);
      },
    });
  }

  private monthChanged(): void {
    const month = parseBudgetMonth(this.monthControl.value);
    this.writeStatus.set(null);
    this.departmentSelectionInvalid.set(false);
    this.limitForm.controls.limitAmount.setValue('', { emitEvent: false });
    this.limitForm.controls.limitAmount.markAsPristine();
    this.limitForm.controls.limitAmount.markAsUntouched();
    if (month === null) {
      this.requestSequence += 1;
      this.budgets.set([]);
      this.loadedMonth.set(null);
      this.monthError.set('Choose a valid month between 2000-01 and 9999-12.');
      this.state.set('error');
      return;
    }
    this.monthError.set(null);
    this.loadBudgets(month);
  }

  private departmentChanged(): void {
    this.departmentSelectionInvalid.set(false);
    const budget = this.selectedBudget();
    const control = this.limitForm.controls.limitAmount;
    control.setValue(budget === null ? '' : this.amountInput(budget.limitAmount), { emitEvent: false });
    control.markAsPristine();
    control.markAsUntouched();
  }

  private maybePrefillLimit(): void {
    if (!this.isAdmin()) return;
    const departmentId = this.limitForm.controls.departmentId.value;
    if (departmentId.length === 0) return;
    const control = this.limitForm.controls.limitAmount;
    if (control.dirty) return;
    const budget = this.selectedBudget();
    control.setValue(budget === null ? '' : this.amountInput(budget.limitAmount), { emitEvent: false });
    control.markAsPristine();
  }

  private applyCanonicalBudget(canonical: BudgetView): void {
    const selectedMonth = parseBudgetMonth(this.monthControl.value);
    if (selectedMonth === null || this.loadedMonth() !== selectedMonth || canonical.month.slice(0, 7) !== selectedMonth) {
      return;
    }
    // A successful write is newer than any list read that started before it.
    this.requestSequence += 1;
    const remaining = this.budgets().filter(
      (budget) =>
        budget.month.slice(0, 7) === selectedMonth &&
        budget.departmentId !== canonical.departmentId &&
        budget.id !== canonical.id,
    );
    const next = [...remaining, canonical].sort((left, right) => {
      const leftName = this.departmentName(left.departmentId);
      const rightName = this.departmentName(right.departmentId);
      return leftName.localeCompare(rightName) || left.id.localeCompare(right.id);
    });
    this.budgets.set(next);
    this.state.set('ready');
    if (this.limitForm.controls.departmentId.value === canonical.departmentId) {
      const control = this.limitForm.controls.limitAmount;
      control.setValue(this.amountInput(canonical.limitAmount), { emitEvent: false });
      control.markAsPristine();
      control.markAsUntouched();
    }
    this.writeStatus.set({
      kind: 'success',
      needsReload: false,
      message:
        `Saved. Server-calculated limit ${this.formatMoney(canonical.limitAmount)}, ` +
        `used ${this.formatMoney(canonical.usedAmount)}, remaining ${this.formatMoney(canonical.remainingAmount)} ` +
        `for ${this.formatMonth(canonical.month)} (version ${canonical.version}).`,
    });
  }

  private handleReadFailure(error: unknown): void {
    const status = budgetErrorStatus(error);
    const code = budgetErrorCode(error);
    this.budgets.set([]);
    this.loadedMonth.set(null);
    if (status === 401) {
      this.accessFailure('session');
      return;
    }
    if (status === 403) {
      this.accessFailure('forbidden');
      return;
    }
    this.monthError.set(null);
    this.writeStatus.set(null);
    if (status === 400 && code === 'invalid_month') {
      this.monthError.set('The backend rejected the selected month. Choose a month between 2000-01 and 9999-12.');
      this.state.set('error');
      return;
    }
    this.state.set('error');
  }

  private handleWriteFailure(error: unknown): void {
    const status = budgetErrorStatus(error);
    const code = budgetErrorCode(error);
    if (status === 401) {
      this.accessFailure('session');
      return;
    }
    if (status === 403) {
      this.accessFailure('forbidden');
      return;
    }
    if (code === 'budget_below_usage') {
      this.writeStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The server rejected this limit because recorded usage is already above it. Your input is preserved; reload the current budget and review the usage before trying again.',
      });
      return;
    }
    if (code === 'retryable_conflict') {
      this.writeStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The budget changed while this limit was being saved. Your input is preserved; reload the current budget and review it before trying again.',
      });
      return;
    }
    if (status === 404) {
      this.writeStatus.set({
        kind: 'error',
        needsReload: true,
        message:
          'The selected department is not available in your current company scope. Your input is preserved; reload the scoped list.',
      });
      return;
    }
    if (status === 400) {
      this.writeStatus.set({
        kind: 'error',
        needsReload: false,
        message:
          'The backend rejected this limit. Enter a nonnegative amount with at most two decimals; nothing was rounded.',
      });
      return;
    }
    this.writeStatus.set({
      kind: 'error',
      needsReload: false,
      message:
        'The limit could not be saved. Your input is preserved. Check the connection and try again; the server remains authoritative.',
    });
  }

  private accessFailure(kind: 'session' | 'forbidden'): void {
    this.requestSequence += 1;
    this.departmentSequence += 1;
    this.writeSequence += 1;
    this.budgets.set([]);
    this.departments.set([]);
    this.loadedMonth.set(null);
    this.departmentNotice.set(null);
    this.departmentSelectionInvalid.set(false);
    this.monthError.set(null);
    this.pending.set(false);
    this.writeStatus.set(null);
    this.limitForm.reset({ departmentId: '', limitAmount: '' });
    this.state.set(kind === 'session' ? 'session-expired' : 'forbidden');
  }

  private amountInput(amount: number): string {
    return amount.toFixed(2);
  }
}
