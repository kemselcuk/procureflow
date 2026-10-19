import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, computed, signal } from '@angular/core';
import { Authentication, MemberProfile } from '../authentication';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';
import { BudgetView, DepartmentView, currentUtcMonth } from './budget-api';
import { BudgetsPage } from './budgets-page';

const IDENTITY = 'aaaaaaaa-1111-4111-8111-111111111111';
const COMPANY = 'aaaaaaaa-2222-4222-8222-222222222222';
const OTHER_COMPANY = 'bbbbbbbb-2222-4222-8222-222222222222';
const ENGINEERING = '11111111-1111-4111-8111-111111111111';
const OPERATIONS = '22222222-2222-4222-8222-222222222222';
const BUDGET_ID = '33333333-3333-4333-8333-333333333333';
const OTHER_BUDGET_ID = '44444444-4444-4444-8444-444444444444';
const MEMBERSHIP = '55555555-5555-4555-8555-555555555555';

const MONTH = currentUtcMonth();
const MONTH_DAY = `${MONTH}-01`;

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
    membershipId: MEMBERSHIP,
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

function department(overrides: Partial<DepartmentView> = {}): DepartmentView {
  return { id: ENGINEERING, name: 'Engineering', ...overrides };
}

function budget(overrides: Partial<BudgetView> = {}): BudgetView {
  return {
    id: BUDGET_ID,
    departmentId: ENGINEERING,
    month: MONTH_DAY,
    limitAmount: 1000,
    usedAmount: 400,
    remainingAmount: 600,
    version: 3,
    ...overrides,
  };
}

describe('Budgets page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<BudgetsPage>;
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
      imports: [BudgetsPage],
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
    fixture = TestBed.createComponent(BudgetsPage);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function authenticate(profile: WorkspaceProfile = scoped()): Promise<void> {
    authState.set('authenticated');
    authMember.set(compactAuth());
    session.signIn(profile);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function signInScoped(profile: WorkspaceProfile): Promise<void> {
    session.signIn(profile);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function budgetRequest(month = MONTH): TestRequest {
    return http.expectOne(
      (candidate) => candidate.url === '/api/budgets' && candidate.params.get('month') === month,
    );
  }

  function departmentsRequest(): TestRequest {
    return http.expectOne((candidate) => candidate.url === '/api/departments' && candidate.method === 'GET');
  }

  async function load(
    profile: WorkspaceProfile,
    rows: BudgetView[],
    departments: DepartmentView[] = [department()],
  ): Promise<void> {
    await authenticate(profile);
    departmentsRequest().flush(departments);
    budgetRequest().flush(rows);
    await fixture.whenStable();
  }

  async function restartSessionWithPendingReads(): Promise<{
    oldDepartments: TestRequest;
    oldBudgets: TestRequest;
    newDepartments: TestRequest;
    newBudgets: TestRequest;
  }> {
    const oldDepartments = departmentsRequest();
    const oldBudgets = budgetRequest();

    authMember.set(null);
    authState.set('anonymous');
    session.apply(null, null, 'idle');
    fixture.detectChanges();
    await fixture.whenStable();

    await authenticate();

    const outstandingDepartments = http.match(
      (candidate) => candidate.url === '/api/departments' && candidate.method === 'GET',
    );
    const outstandingBudgets = http.match((candidate) => candidate.url === '/api/budgets');
    const newDepartments = outstandingDepartments.find((request) => request !== oldDepartments);
    const newBudgets = outstandingBudgets.find((request) => request !== oldBudgets);
    if (newDepartments === undefined || newBudgets === undefined) {
      throw new Error('Expected reads from the re-authenticated session');
    }
    return { oldDepartments, oldBudgets, newDepartments, newBudgets };
  }

  function monthInput(): HTMLInputElement {
    const input = element().querySelector<HTMLInputElement>('#budget-month');
    if (input === null) throw new Error('Month input not found');
    return input;
  }

  function selectDepartment(id = ENGINEERING): void {
    const select = element().querySelector<HTMLSelectElement>('#limit-department');
    if (select === null) throw new Error('Department select not found');
    select.value = id;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function enterLimit(value: string): void {
    const input = element().querySelector<HTMLInputElement>('#limit-amount');
    if (input === null) throw new Error('Limit input not found');
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function saveButton(): HTMLButtonElement {
    const button = element().querySelector<HTMLButtonElement>('.admin-form button[type="submit"]');
    if (button === null) throw new Error('Save button not found');
    return button;
  }

  function clickSave(): void {
    saveButton().click();
    fixture.detectChanges();
  }

  function buttonByText(label: string): HTMLButtonElement {
    const found = Array.from(element().querySelectorAll('button')).find(
      (button) => (button.textContent ?? '').trim() === label,
    );
    if (!found) throw new Error(`Button not found: ${label}`);
    return found as HTMLButtonElement;
  }

  it('renders a sign-in prompt and sends no private request while anonymous', async () => {
    await render();
    expect(text()).toContain('Sign in with an active company membership');
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it('denies an ordinary employee client-side without sending a budget request', async () => {
    await render();
    await authenticate(scoped({ roles: ['EMPLOYEE'] }));
    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.admin-form')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it('never grants budget access from the stale compact sign-in admin snapshot', async () => {
    await render();
    authState.set('authenticated');
    authMember.set(compactAuth({ roles: ['COMPANY_ADMIN'] }));
    session.signIn(scoped({ roles: ['EMPLOYEE'] }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.card')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it('allows the current profile admin without re-login even when the compact snapshot is ordinary', async () => {
    await render();
    await authenticate();
    expect(authMember()?.roles).toEqual(['EMPLOYEE']);
    expect(text()).toContain('Loading budgets');
    departmentsRequest().flush([department()]);
    budgetRequest().flush([budget()]);
    await fixture.whenStable();
    expect(text()).toContain('Engineering');
    expect(element().querySelector('.admin-form')).not.toBeNull();
  });

  it('loads the current UTC month for a department manager', async () => {
    await render();
    await authenticate(scoped({ roles: ['DEPARTMENT_MANAGER'], managedDepartmentIds: [ENGINEERING] }));
    expect(text()).toContain('Loading budgets');
    expect(monthInput().value).toBe(MONTH);
    const request = budgetRequest();
    expect(request.request.method).toBe('GET');
    expect(request.request.params.has('companyId')).toBe(false);
    departmentsRequest().flush([department()]);
    request.flush([]);
    await fixture.whenStable();
    expect(text()).toContain('No budget rows');
  });

  it('shows scoped department names, UTC month, EUR values and the server remaining amount without local arithmetic', async () => {
    await render();
    await load(scoped(), [budget({ remainingAmount: 550.25 })]);

    expect(text()).toContain('Engineering');
    expect(text()).toContain('October 2026');
    expect(text()).toContain('1,000.00');
    expect(text()).toContain('400.00');
    expect(text()).toContain('550.25');
    expect(text()).not.toContain('600.00');
    expect(text()).toContain('server-calculated');
    expect(element().querySelector('time')?.getAttribute('datetime')).toBe(MONTH_DAY);
  });

  it('does not fabricate a zero budget when the scoped list is empty', async () => {
    await render();
    await load(scoped(), []);
    expect(text()).toContain('No budget rows were returned');
    expect(text()).toContain('missing row is not a zero budget');
    expect(element().querySelector('.budget-card')).toBeNull();
  });

  it('shows the forbidden state when the backend denies budget visibility to a still-current manager', async () => {
    await render();
    await authenticate(scoped({ roles: ['DEPARTMENT_MANAGER'], managedDepartmentIds: [ENGINEERING] }));
    const departments = departmentsRequest();
    budgetRequest().flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    departments.flush([department()]);
    await fixture.whenStable();
    expect(text()).toContain('Budget access is unavailable');
    expect(text()).toContain('Employees can read their own department directory only');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/admin')).length).toBe(0);
  });

  it('keeps a recoverable error, preserves the month and reloads on retry', async () => {
    await render();
    await authenticate();
    departmentsRequest().flush([department()]);
    budgetRequest().flush('Unavailable', { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(text()).toContain('Budgets could not be loaded');
    expect(monthInput().value).toBe(MONTH);

    buttonByText('Try again').click();
    departmentsRequest().flush([department()]);
    budgetRequest().flush([budget()]);
    await fixture.whenStable();
    expect(text()).toContain('Engineering');
  });

  it('rejects an unsupported month without sending another request and offers the current UTC month', async () => {
    await render();
    await load(scoped(), [budget()]);

    fixture.componentInstance.monthControl.setValue('2026-13');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Choose a valid month');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(http.match((candidate) => candidate.url === '/api/budgets').length).toBe(0);

    buttonByText('Use current UTC month').click();
    budgetRequest().flush([budget()]);
    await fixture.whenStable();
    expect(monthInput().value).toBe(MONTH);
  });

  it('applies only the latest month response when an older request settles later', async () => {
    await render();
    await authenticate();
    departmentsRequest().flush([department()]);
    await fixture.whenStable();
    const october = budgetRequest();
    fixture.componentInstance.monthControl.setValue('2026-09');
    fixture.detectChanges();
    const september = budgetRequest('2026-09');
    september.flush([budget({ month: '2026-09-01', id: OTHER_BUDGET_ID, remainingAmount: 300 })]);
    october.flush([budget({ month: '2026-10-01' })]);
    await fixture.whenStable();

    expect(text()).toContain('September 2026');
    expect(text()).not.toContain('October 2026');
    expect(text()).toContain('300.00');
    expect(text()).not.toContain('600.00');
  });

  it('clears loaded rows when the scoped company changes and ignores the late previous response', async () => {
    await render();
    await load(scoped(), [budget()]);
    expect(text()).toContain('Engineering');

    authMember.set(compactAuth({ companyId: OTHER_COMPANY, companyName: 'Other company' }));
    await signInScoped(scoped({ companyId: OTHER_COMPANY, companyName: 'Other company' }));
    expect(text()).not.toContain('Engineering');
    expect(text()).toContain('Loading budgets');

    departmentsRequest().flush([department({ id: OPERATIONS, name: 'Operations' })]);
    budgetRequest().flush([budget({ id: OTHER_BUDGET_ID, departmentId: OPERATIONS, remainingAmount: 25 })]);
    await fixture.whenStable();
    expect(text()).toContain('Operations');
    expect(text()).not.toContain('Engineering');
  });

  it('shows the administrator limit form only to COMPANY_ADMIN and only offers scoped departments', async () => {
    await render();
    await load(scoped(), [budget()]);
    expect(element().querySelector('.admin-form')).not.toBeNull();
    const options = Array.from(element().querySelectorAll<HTMLOptionElement>('#limit-department option')).map(
      (option) => option.value,
    );
    expect(options).toEqual(['', ENGINEERING]);
    expect(text()).toContain('Set monthly limit');

    await signInScoped(scoped({ roles: ['FINANCE_APPROVER'], version: 4 }));
    departmentsRequest().flush([department()]);
    budgetRequest().flush([budget()]);
    await fixture.whenStable();
    expect(element().querySelector('.admin-form')).toBeNull();
  });

  it('allows zero and sends exactly the typed canonical limit without usage or company fields', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('0');
    clickSave();

    const request = http.expectOne(
      (candidate) => candidate.url === `/api/admin/departments/${ENGINEERING}/budgets/${MONTH}`,
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ limitAmount: 0 });
    request.flush(budget({ limitAmount: 0, usedAmount: 0, remainingAmount: 0, version: 4 }));
    await fixture.whenStable();
    expect(text()).toContain('Saved. Server-calculated limit');
    expect(text()).toContain('0.00');
  });

  it.each([
    ['1.234', 'at most two decimal places'],
    ['1000000000000', 'at most 12 integer digits'],
    ['-5', 'nonnegative amount'],
  ])('rejects the invalid limit text %s without a write', async (value, message) => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit(value);
    clickSave();
    expect(text()).toContain(message);
    expect(http.match((candidate) => candidate.method === 'PUT').length).toBe(0);
  });

  it('replaces the row with the returned canonical values and never resets usage locally', async () => {
    await render();
    await load(scoped(), [budget({ usedAmount: 400, remainingAmount: 600 })]);
    selectDepartment();
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('1000.00');
    enterLimit('1200.50');
    clickSave();

    const request = http.expectOne(
      (candidate) => candidate.url === `/api/admin/departments/${ENGINEERING}/budgets/${MONTH}`,
    );
    expect(request.request.body).toEqual({ limitAmount: 1200.5 });
    expect(request.request.body).not.toHaveProperty('version');
    expect(request.request.body).not.toHaveProperty('usedAmount');
    request.flush(
      budget({ limitAmount: 1200.5, usedAmount: 700, remainingAmount: 500.5, version: 5 }),
    );
    await fixture.whenStable();

    expect(text()).toContain('700.00');
    expect(text()).toContain('500.50');
    expect(text()).toContain('version 5');
    expect(text()).not.toContain('600.00');
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('1200.50');
  });

  it('suppresses a duplicate submit while the limit write is pending', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('900');
    clickSave();
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().textContent).toContain('Saving limit');

    saveButton().click();
    fixture.detectChanges();
    const writes = http.match((candidate) => candidate.method === 'PUT');
    expect(writes.length).toBe(1);
    writes[0]?.flush(budget({ limitAmount: 900, usedAmount: 400, remainingAmount: 500, version: 4 }));
    await fixture.whenStable();
    expect(saveButton().disabled).toBe(false);
  });

  it('preserves the typed limit after budget_below_usage and requires an explicit reload', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('50');
    clickSave();

    http
      .expectOne((candidate) => candidate.method === 'PUT')
      .flush(
        { status: 409, code: 'budget_below_usage', detail: '<img src=x onerror=alert(1)>' },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('50');
    expect(text()).toContain('recorded usage is already above it');
    expect(text()).not.toContain('<img');
    expect(http.match((candidate) => candidate.method === 'PUT').length).toBe(0);

    buttonByText('Reload current budget').click();
    const reload = budgetRequest();
    expect(reload.request.method).toBe('GET');
    expect(http.match((candidate) => candidate.method === 'PUT').length).toBe(0);
    reload.flush([budget({ usedAmount: 900, remainingAmount: 100, version: 4 })]);
    await fixture.whenStable();
    expect(text()).toContain('900.00');
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('50');
  });

  it('treats a retryable conflict as recoverable without blindly resubmitting', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('800');
    clickSave();
    http
      .expectOne((candidate) => candidate.method === 'PUT')
      .flush({ status: 409, code: 'retryable_conflict' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    expect(text()).toContain('budget changed while this limit was being saved');
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('800');
    expect(http.match((candidate) => candidate.method === 'PUT').length).toBe(0);
    expect(buttonByText('Reload current budget')).toBeTruthy();
  });

  it('clears all budget state and the administrator form on a 403 write', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('700');
    clickSave();
    http
      .expectOne((candidate) => candidate.method === 'PUT')
      .flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();
    expect(element().querySelector('#limit-amount')).toBeNull();
  });

  it('preserves the form on a network failure and only retries on an explicit second submit', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('75');
    clickSave();
    http
      .expectOne((candidate) => candidate.method === 'PUT')
      .flush('Offline', { status: 0, statusText: 'Unknown Error' });
    await fixture.whenStable();
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('75');
    expect(text()).toContain('input is preserved');
    expect(http.match((candidate) => candidate.method === 'PUT').length).toBe(0);

    clickSave();
    http
      .expectOne((candidate) => candidate.method === 'PUT')
      .flush(budget({ limitAmount: 75, usedAmount: 0, remainingAmount: 75, version: 4 }));
    await fixture.whenStable();
    expect(text()).toContain('Saved. Server-calculated limit');
  });

  it('scrubs a 401 read and offers a session check without retaining rows', async () => {
    await render();
    await authenticate();
    departmentsRequest().flush([department()]);
    budgetRequest().flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(text()).toContain('Your session is no longer valid');
    expect(element().querySelector('.budget-card')).toBeNull();

    buttonByText('Check session').click();
    expect(retryCalls).toBe(1);
  });

  it('maps an invalid_month read error to fixed guidance and a recoverable selector', async () => {
    await render();
    await authenticate();
    departmentsRequest().flush([department()]);
    budgetRequest().flush(
      { status: 400, code: 'invalid_month', detail: '<b>bad</b>' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(text()).toContain('backend rejected the selected month');
    expect(text()).not.toContain('<b>bad</b>');
    expect(buttonByText('Use current UTC month')).toBeTruthy();
  });

  it('does not let a list read that started before a successful limit write overwrite the canonical row', async () => {
    await render();
    await load(scoped(), [budget({ usedAmount: 400, remainingAmount: 600 })]);
    selectDepartment();
    enterLimit('1200.50');
    clickSave();
    const write = http.expectOne((candidate) => candidate.method === 'PUT');
    buttonByText('Refresh').click();
    const staleBudgets = budgetRequest();
    const staleDepartments = departmentsRequest();

    write.flush(budget({ limitAmount: 1200.5, usedAmount: 700, remainingAmount: 500.5, version: 5 }));
    await fixture.whenStable();
    staleBudgets.flush([budget({ usedAmount: 400, remainingAmount: 600, version: 3 })]);
    staleDepartments.flush([department()]);
    await fixture.whenStable();

    expect(text()).toContain('500.50');
    expect(text()).toContain('version 5');
    expect(text()).not.toContain('600.00');
  });

  it('still loads scoped department names when the month changes before the initial department read returns', async () => {
    await render();
    await authenticate();
    const initialBudget = budgetRequest();
    const initialDepartments = departmentsRequest();

    fixture.componentInstance.monthControl.setValue('2026-09');
    fixture.detectChanges();
    const september = budgetRequest('2026-09');

    initialBudget.flush([budget()]);
    initialDepartments.flush([department()]);
    september.flush([
      budget({ id: OTHER_BUDGET_ID, month: '2026-09-01', remainingAmount: 300 }),
    ]);
    await fixture.whenStable();

    expect(text()).toContain('September 2026');
    expect(text()).not.toContain('October 2026');
    expect(text()).toContain('Engineering');
    expect(text()).toContain('300.00');
    expect(
      Array.from(element().querySelectorAll<HTMLOptionElement>('#limit-department option')).map(
        (option) => option.value,
      ),
    ).toEqual(['', ENGINEERING]);
  });

  it('keeps a pending scoped department read when a canonical limit write completes', async () => {
    await render();
    await load(scoped(), [budget({ usedAmount: 400, remainingAmount: 600 })]);
    selectDepartment();
    enterLimit('1200.50');
    buttonByText('Refresh').click();
    const refreshedDepartments = departmentsRequest();
    const refreshedBudgets = budgetRequest();

    clickSave();
    const write = http.expectOne((candidate) => candidate.method === 'PUT');
    write.flush(
      budget({ limitAmount: 1200.5, usedAmount: 700, remainingAmount: 500.5, version: 5 }),
    );
    await fixture.whenStable();

    refreshedDepartments.flush([
      department(),
      department({ id: OPERATIONS, name: 'Operations' }),
    ]);
    refreshedBudgets.flush([budget({ usedAmount: 400, remainingAmount: 600, version: 3 })]);
    await fixture.whenStable();

    expect(
      Array.from(element().querySelectorAll<HTMLOptionElement>('#limit-department option')).map(
        (option) => option.value,
      ),
    ).toEqual(['', ENGINEERING, OPERATIONS]);
    expect(text()).toContain('500.50');
    expect(text()).toContain('version 5');
    expect(text()).not.toContain('600.00');
  });

  it('clears privileged state when a department read is denied after the month changed', async () => {
    await render();
    await authenticate();
    const initialBudget = budgetRequest();
    const initialDepartments = departmentsRequest();

    fixture.componentInstance.monthControl.setValue('2026-09');
    fixture.detectChanges();
    const september = budgetRequest('2026-09');

    initialBudget.flush([budget()]);
    september.flush([
      budget({ id: OTHER_BUDGET_ID, month: '2026-09-01', remainingAmount: 300 }),
    ]);
    await fixture.whenStable();

    initialDepartments.flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();
  });

  it('clears privileged state when an older budget read is unauthorized after the month changed', async () => {
    await render();
    await authenticate();
    const initialBudget = budgetRequest();
    const initialDepartments = departmentsRequest();

    fixture.componentInstance.monthControl.setValue('2026-09');
    fixture.detectChanges();
    const september = budgetRequest('2026-09');

    initialDepartments.flush([department()]);
    september.flush([
      budget({ id: OTHER_BUDGET_ID, month: '2026-09-01', remainingAmount: 300 }),
    ]);
    await fixture.whenStable();
    expect(text()).toContain('September 2026');

    initialBudget.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(text()).toContain('Your session is no longer valid');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();
  });

  it('sends the maximum valid amount as the same decimal text without silent rounding', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('999999999999.99');
    clickSave();
    const request = http.expectOne((candidate) => candidate.method === 'PUT');
    expect(request.request.body).toEqual({ limitAmount: 999999999999.99 });
    request.flush(
      budget({
        limitAmount: 999999999999.99,
        usedAmount: 0,
        remainingAmount: 999999999999.99,
        version: 4,
      }),
    );
    await fixture.whenStable();
    expect(text()).toContain('999,999,999,999.99');
  });

  it('clears rows and the administrator form immediately when the session logs out', async () => {
    await render();
    await load(scoped(), [budget()]);
    expect(element().querySelector('.budget-card')).not.toBeNull();

    authMember.set(null);
    authState.set('anonymous');
    session.apply(null, null, 'idle');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Sign in with an active company membership');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/')).length).toBe(0);
  });

  it('clears scoped data and refetches when the identity changes inside the same company', async () => {
    await render();
    await load(scoped(), [budget()]);
    expect(text()).toContain('Engineering');

    authMember.set(compactAuth({ identityId: '99999999-9999-4999-8999-999999999999' }));
    await signInScoped(scoped({ identityId: '99999999-9999-4999-8999-999999999999' }));
    expect(text()).toContain('Loading budgets');
    expect(element().querySelector('.budget-card')).toBeNull();

    departmentsRequest().flush([department()]);
    budgetRequest().flush([budget({ id: OTHER_BUDGET_ID, remainingAmount: 42 })]);
    await fixture.whenStable();
    expect(text()).toContain('42.00');
  });

  it('keeps the new session data when the previous session department denial resolves after re-authentication', async () => {
    await render();
    await authenticate();
    const { oldDepartments, oldBudgets, newDepartments, newBudgets } =
      await restartSessionWithPendingReads();

    newDepartments.flush([department(), department({ id: OPERATIONS, name: 'Operations' })]);
    newBudgets.flush([budget()]);
    await fixture.whenStable();
    expect(text()).toContain('Engineering');
    expect(
      Array.from(element().querySelectorAll<HTMLOptionElement>('#limit-department option')).map(
        (option) => option.value,
      ),
    ).toEqual(['', ENGINEERING, OPERATIONS]);

    oldDepartments.flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(text()).not.toContain('Budget access is unavailable');
    expect(element().querySelector('.budget-card')).not.toBeNull();
    expect(
      Array.from(element().querySelectorAll<HTMLOptionElement>('#limit-department option')).map(
        (option) => option.value,
      ),
    ).toEqual(['', ENGINEERING, OPERATIONS]);

    oldBudgets.flush([budget()]);
    await fixture.whenStable();
    expect(element().querySelector('.budget-card')).not.toBeNull();
  });

  it('keeps the new session data when the previous session budget denial resolves after re-authentication', async () => {
    await render();
    await authenticate();
    const { oldDepartments, oldBudgets, newDepartments, newBudgets } =
      await restartSessionWithPendingReads();

    newDepartments.flush([department(), department({ id: OPERATIONS, name: 'Operations' })]);
    newBudgets.flush([budget({ remainingAmount: 525.5 })]);
    await fixture.whenStable();
    expect(text()).toContain('Engineering');
    expect(text()).toContain('525.50');

    oldBudgets.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(text()).not.toContain('Your session is no longer valid');
    expect(element().querySelector('.budget-card')).not.toBeNull();
    expect(text()).toContain('525.50');

    oldDepartments.flush([department()]);
    await fixture.whenStable();
    expect(element().querySelector('.budget-card')).not.toBeNull();
  });

  it('still clears the re-authenticated session when its new department read is forbidden', async () => {
    await render();
    await authenticate();
    const { oldDepartments, oldBudgets, newDepartments, newBudgets } =
      await restartSessionWithPendingReads();

    newBudgets.flush([budget()]);
    await fixture.whenStable();
    expect(element().querySelector('.budget-card')).not.toBeNull();

    newDepartments.flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();

    oldDepartments.flush([department()]);
    oldBudgets.flush([budget()]);
    await fixture.whenStable();
    expect(element().querySelector('.budget-card')).toBeNull();
  });

  it('still clears the re-authenticated session when its new budget read is unauthorized', async () => {
    await render();
    await authenticate();
    const { oldDepartments, oldBudgets, newDepartments, newBudgets } =
      await restartSessionWithPendingReads();

    newDepartments.flush([department()]);
    newBudgets.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(text()).toContain('Your session is no longer valid');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();

    oldDepartments.flush([department()]);
    oldBudgets.flush([budget()]);
    await fixture.whenStable();
    expect(element().querySelector('.budget-card')).toBeNull();
  });

  it('uses the current scoped company in the scope label instead of the older auth label', async () => {
    await render();
    authState.set('authenticated');
    authMember.set(compactAuth({ companyName: 'Old auth label' }));
    session.signIn(scoped({ companyName: 'Fresh scope company' }));
    fixture.detectChanges();
    await fixture.whenStable();
    departmentsRequest().flush([department()]);
    budgetRequest().flush([budget()]);
    await fixture.whenStable();
    const scope = element().querySelector('.scope');
    expect(scope?.textContent).toContain('Fresh scope company');
    expect(scope?.textContent).not.toContain('Old auth label');
  });

  it('preserves an unsaved draft when the workspace only reports a transient loading state', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('42');

    session.state.set('loading');
    fixture.detectChanges();
    await fixture.whenStable();
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('42');
    expect(element().querySelector('.budget-card')).not.toBeNull();

    session.state.set('ready');
    fixture.detectChanges();
    await fixture.whenStable();
    expect((element().querySelector('#limit-amount') as HTMLInputElement).value).toBe('42');
  });

  it('invalidates rows and drafts immediately when a settled profile removes budget authority', async () => {
    await render();
    await load(scoped(), [budget()]);
    selectDepartment();
    enterLimit('42');
    expect(element().querySelector('.admin-form')).not.toBeNull();

    await signInScoped(scoped({ roles: ['EMPLOYEE'], version: 4 }));
    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(element().querySelector('.admin-form')).toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/budgets')).length).toBe(0);
  });

  it('clears rows on a settled forbidden profile and cannot restore them from a late prior read', async () => {
    await render();
    await load(scoped(), [budget()]);
    buttonByText('Refresh').click();
    const stale = budgetRequest();
    const staleDepartments = departmentsRequest();

    session.errorMessage.set('Your membership is no longer active.');
    session.apply(`${COMPANY}\u0000${IDENTITY}`, null, 'forbidden');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Budget access is unavailable');
    expect(element().querySelector('.budget-card')).toBeNull();

    staleDepartments.flush([department()]);
    stale.flush([budget()]);
    await fixture.whenStable();
    expect(element().querySelector('.budget-card')).toBeNull();
    expect(text()).toContain('Budget access is unavailable');
  });

  it('uses labels and native controls for keyboard operation', async () => {
    await render();
    await load(scoped(), [budget()]);
    expect(element().querySelector('label[for="budget-month"]')).not.toBeNull();
    expect(monthInput().type).toBe('month');
    selectDepartment();
    expect(element().querySelector('label[for="limit-department"]')).not.toBeNull();
    expect(element().querySelector('label[for="limit-amount"]')).not.toBeNull();
    expect(saveButton().type).toBe('submit');
    expect(element().querySelectorAll('button:not([type])').length).toBe(0);
  });
});
