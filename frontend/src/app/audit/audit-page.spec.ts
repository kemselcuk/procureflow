import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, computed, signal } from '@angular/core';
import { Authentication, MemberProfile } from '../authentication';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';
import { AuditEntry, AuditPageEnvelope } from './audit-api';
import { AuditPage } from './audit-page';

const IDENTITY_A = 'aaaaaaaa-1111-4111-8111-111111111111';
const COMPANY_A = 'aaaaaaaa-2222-4222-8222-222222222222';
const IDENTITY_B = 'bbbbbbbb-1111-4111-8111-111111111111';
const COMPANY_B = 'bbbbbbbb-2222-4222-8222-222222222222';
const MEMBERSHIP_A = 'aaaaaaaa-3333-4333-8333-333333333333';

const AUDIT_1 = '11111111-1111-4111-8111-111111111111';
const AUDIT_2 = '55555555-5555-4555-8555-555555555555';
const AUDIT_3 = '66666666-6666-4666-8666-666666666666';
const AUDIT_4 = '77777777-7777-4777-8777-777777777777';
const ENTITY_1 = '22222222-2222-4222-8222-222222222222';
const MEMBERSHIP_1 = '33333333-3333-4333-8333-333333333333';
const CORRELATION_1 = '44444444-4444-4444-8444-444444444444';

type SessionKind = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden';

/**
 * Fresh WorkspaceSession stand-in that mirrors the public contract, including a monotonic
 * generation that advances only when the owner or the scoped profile fingerprint changes.
 */
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

  signOut(): void {
    this.errorMessage.set(null);
    this.apply(null, null, 'idle');
  }
}

function scopedProfile(overrides: Partial<WorkspaceProfile> = {}): WorkspaceProfile {
  return {
    identityId: IDENTITY_A,
    membershipId: MEMBERSHIP_A,
    companyId: COMPANY_A,
    companyName: 'Larkvane',
    departmentId: ENTITY_1,
    departmentName: 'engineering',
    version: 3,
    roles: ['COMPANY_ADMIN'],
    managedDepartmentIds: [],
    ...overrides,
  };
}

/** Compact sign-in snapshot; deliberately different from the scoped profile in most tests. */
function compactAuth(roles: string[]): MemberProfile {
  return {
    identityId: IDENTITY_A,
    companyId: COMPANY_A,
    companyName: 'Compact auth company',
    departmentName: 'engineering',
    roles,
  };
}

function entry(overrides: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id: AUDIT_1,
    action: 'request.created',
    entityId: ENTITY_1,
    actorIdentityId: IDENTITY_A,
    actorMembershipId: MEMBERSHIP_1,
    correlationId: CORRELATION_1,
    occurredAt: '2026-10-19T09:00:00Z',
    ...overrides,
  };
}

function envelope(
  items: AuditEntry[],
  overrides: Partial<Omit<AuditPageEnvelope, 'items'>> = {},
): AuditPageEnvelope {
  return { page: 0, size: 20, total: items.length, items, ...overrides };
}

describe('Audit page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AuditPage>;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;
  let session: SessionStub;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    session = new SessionStub();
    TestBed.configureTestingModule({
      imports: [AuditPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: { initialize: async () => {}, state: authState, member: authMember, login: async () => {}, logout: async () => {} },
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
    fixture = TestBed.createComponent(AuditPage);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function authenticate(profile: WorkspaceProfile = scopedProfile()): Promise<void> {
    authState.set('authenticated');
    // The compact sign-in snapshot stays ordinary; the scoped profile decides access.
    authMember.set(compactAuth(['EMPLOYEE']));
    session.signIn(profile);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function authenticateOrdinary(roles: readonly WorkspaceRole[]): Promise<void> {
    authState.set('authenticated');
    // A stale privileged compact snapshot must not grant the audit screen.
    authMember.set(compactAuth(['COMPANY_ADMIN']));
    session.signIn(scopedProfile({ roles }));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function expectAudit(pageNumber = 0, size = 20): TestRequest {
    const request = http.expectOne((candidate) => candidate.url === '/api/admin/audit');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe(String(pageNumber));
    expect(request.request.params.get('size')).toBe(String(size));
    return request;
  }

  function auditRequests(): TestRequest[] {
    return http.match((candidate) => candidate.url === '/api/admin/audit');
  }

  function queryButton(label: string): HTMLButtonElement | null {
    const found = Array.from(element().querySelectorAll('button')).find(
      (button) => (button.textContent ?? '').trim() === label,
    );
    return (found as HTMLButtonElement | undefined) ?? null;
  }

  function buttonByText(label: string): HTMLButtonElement {
    const found = queryButton(label);
    if (!found) throw new Error(`Button not found: ${label}`);
    return found;
  }

  function rows(): HTMLElement[] {
    return Array.from(element().querySelectorAll('li'));
  }

  it('renders nothing and sends no request while there is no authenticated owner', async () => {
    await render();
    expect(text()).not.toContain('Audit trail');
    expect(auditRequests().length).toBe(0);

    authState.set('starting');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Audit trail');
    expect(auditRequests().length).toBe(0);
  });

  it('stays hidden when the compact auth service is authenticated but the scoped owner is absent', async () => {
    authState.set('authenticated');
    authMember.set(compactAuth(['COMPANY_ADMIN']));
    await render();
    expect(text()).not.toContain('Audit trail');
    expect(auditRequests().length).toBe(0);
  });

  it('shows a checking state without a request while the scoped profile is still loading', async () => {
    await render();
    session.apply(`${COMPANY_A}\u0000${IDENTITY_A}`, null, 'loading');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Audit trail');
    expect(text()).toContain('Loading audit records');
    expect(auditRequests().length).toBe(0);
  });

  it.each(['forbidden', 'error'] as const)(
    'shows a truthful error panel without a request when the scoped profile is %s',
    async (kind) => {
      await render();
      session.errorMessage.set('Current membership is not available.');
      session.apply(`${COMPANY_A}\u0000${IDENTITY_A}`, null, kind);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(text()).toContain('Audit trail');
      expect(text()).toContain('Current membership is not available.');
      expect(rows().length).toBe(0);
      expect(auditRequests().length).toBe(0);
    },
  );

  it('never grants the screen from the stale compact sign-in admin role', async () => {
    expect(authState()).toBe('anonymous');
    for (const role of ['EMPLOYEE', 'DEPARTMENT_MANAGER', 'FINANCE_APPROVER'] as const) {
      session.signOut();
      fixture?.destroy();
      await render();
      await authenticateOrdinary([role]);
      expect(authMember()?.roles).toEqual(['COMPANY_ADMIN']);
      expect(text()).toContain('available to company administrators only');
      expect(rows().length).toBe(0);
      expect(auditRequests().length).toBe(0);
    }
  });

  it('allows the current profile admin without re-login even when the compact snapshot is ordinary', async () => {
    await render();
    await authenticate();
    expect(authMember()?.roles).toEqual(['EMPLOYEE']);
    expect(text()).toContain('Loading audit records');
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);
  });

  it('loads the first page only after an eligible COMPANY_ADMIN session exists', async () => {
    await render();
    expect(auditRequests().length).toBe(0);

    await authenticate();
    expect(text()).toContain('Loading audit records');
    expect(queryButton('Refresh')).toBeNull();
    const request = expectAudit(0, 20);
    expect(request.request.params.keys().sort()).toEqual(['page', 'size']);
    expect(request.request.body).toBeNull();
    request.flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).not.toContain('Loading audit records');
    expect(rows().length).toBe(1);
    expect(queryButton('Refresh')).not.toBeNull();
  });

  it('renders readable action labels, UTC times and the exact reference ids without links', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(
      envelope(
        [
          entry(),
          entry({ id: AUDIT_2, action: 'budget.limit_set', occurredAt: '2026-10-18T23:30:00Z' }),
        ],
        { total: 2 },
      ),
    );
    await fixture.whenStable();

    const listed = rows();
    expect(listed.length).toBe(2);
    expect(text()).toContain('Request created');
    expect(text()).toContain('Budget limit set');

    const first = listed[0]!;
    const time = first.querySelector('time')!;
    expect(time.getAttribute('datetime')).toBe('2026-10-19T09:00:00Z');
    expect(time.textContent).toContain('2026');
    expect(time.textContent).toContain('09:00');
    expect(time.textContent).toContain('UTC');

    expect(first.textContent).toContain(AUDIT_1);
    expect(first.textContent).toContain(ENTITY_1);
    expect(first.textContent).toContain(IDENTITY_A);
    expect(first.textContent).toContain(MEMBERSHIP_1);
    expect(first.textContent).toContain(CORRELATION_1);
    // References are plain text: no link grants purchase-request access.
    expect(element().querySelectorAll('a').length).toBe(0);

    expect(text()).toContain('2 audit records');
    expect(text()).toContain('page 1 of 1');
    expect(buttonByText('Previous page').disabled).toBe(true);
    expect(buttonByText('Next page').disabled).toBe(true);
  });

  it('falls back to a readable plain label for unknown or hostile action codes', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(
      envelope(
        [
          entry({ action: 'request.future_state' }),
          entry({ id: AUDIT_2, action: 'mystery-event' }),
          entry({ id: AUDIT_3, action: 'constructor' }),
          entry({ id: AUDIT_4, action: '<img src=x onerror=alert(1)>' }),
        ],
        { total: 4 },
      ),
    );
    await fixture.whenStable();

    expect(text()).toContain('Request future state');
    expect(text()).toContain('Mystery event');
    expect(text()).toContain('Constructor');
    expect(text()).toContain('<img src=x onerror=alert(1)>');
    // Interpolated, never parsed as markup.
    expect(element().querySelector('img')).toBeNull();
  });

  it('drops extra payload fields and never renders confidential request content', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush({
      page: 0,
      size: 20,
      total: 1,
      items: [
        {
          ...entry(),
          title: 'Private laptop title',
          justification: 'Confidential justification text',
          email: 'person@demo.invalid',
          token: 'token-claim',
        },
      ],
    });
    await fixture.whenStable();

    expect(rows().length).toBe(1);
    expect(text()).toContain('Request created');
    for (const secret of ['Private laptop title', 'Confidential justification text', 'person@demo.invalid', 'token-claim']) {
      expect(text()).not.toContain(secret);
    }
  });

  it('fails closed with a retryable generic error instead of rendering a malformed response', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush({
      page: 0,
      size: 20,
      total: 1,
      items: [{ ...entry(), id: 'audit-1', occurredAt: 'yesterday', email: 'person@demo.invalid' }],
    });
    await fixture.whenStable();

    expect(rows().length).toBe(0);
    expect(text()).not.toContain('person@demo.invalid');
    expect(element().querySelector('.state.error[role="alert"]')).not.toBeNull();
    expect(text()).toContain('Audit records could not be loaded. Try again.');

    buttonByText('Try again').click();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).toContain('Request created');
  });

  it('rejects a successful response whose envelope does not echo the requested page or size', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush({ page: 1, size: 20, total: 0, items: [] });
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).toContain('Audit records could not be loaded. Try again.');

    buttonByText('Try again').click();
    expectAudit(0, 20).flush({ page: 0, size: 101, total: 0, items: [] });
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).toContain('Audit records could not be loaded. Try again.');
  });

  it('shows an empty state with pager metadata and no list for an empty scope', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([], { total: 0 }));
    await fixture.whenStable();

    expect(text()).toContain('No audit records yet');
    expect(element().querySelector('ul')).toBeNull();
    expect(text()).toContain('0 audit records');
    expect(text()).toContain('page 1 of 1');
    expect(buttonByText('Previous page').disabled).toBe(true);
    expect(buttonByText('Next page').disabled).toBe(true);
    expect(queryButton('First page')).toBeNull();
  });

  it('pages forward and backward within bounds and disables navigation while a page is pending', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('45 audit records');
    expect(text()).toContain('page 1 of 3');
    expect(buttonByText('Previous page').disabled).toBe(true);
    expect(buttonByText('Next page').disabled).toBe(false);

    buttonByText('Next page').click();
    fixture.detectChanges();
    // Pending: the previous page stays readable, both navigation actions are disabled.
    expect(rows().length).toBe(1);
    expect(text()).toContain('Request created');
    expect(text()).not.toContain('Loading audit records');
    expect(buttonByText('Previous page').disabled).toBe(true);
    expect(buttonByText('Next page').disabled).toBe(true);

    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.approved' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');
    expect(text()).toContain('Request approved');
    expect(buttonByText('Previous page').disabled).toBe(false);
    expect(buttonByText('Next page').disabled).toBe(false);

    buttonByText('Previous page').click();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 3');
    expect(text()).toContain('Request created');
  });

  it('disables Next on a partial final page', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 21 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2 })], { page: 1, total: 21 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 2');
    expect(buttonByText('Next page').disabled).toBe(true);
    expect(buttonByText('Previous page').disabled).toBe(false);
  });

  it('suppresses a second navigation while the first page request is pending', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();

    const next = buttonByText('Next page');
    next.click();
    next.click();
    fixture.detectChanges();
    expect(next.disabled).toBe(true);
    const request = expectAudit(1, 20);
    expect(auditRequests().length).toBe(0);
    request.flush(envelope([entry({ id: AUDIT_2 })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');
  });

  it('keeps keyboard focus on the pager control across a pending page change', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();

    const next = buttonByText('Next page');
    next.focus();
    expect(document.activeElement).toBe(next);
    next.click();
    fixture.detectChanges();
    expect(next.disabled).toBe(true);
    expect(next.isConnected).toBe(true);
    expect(document.activeElement).toBe(next);

    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.approved' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(document.activeElement).toBe(next);
    expect(next.isConnected).toBe(true);
    expect(text()).toContain('Request approved');
    expect(text()).toContain('page 2 of 3');
  });

  it('reloads the requested page on Refresh and applies only the newest refresh response', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.approved' })], { page: 1, total: 45 }));
    await fixture.whenStable();

    buttonByText('Refresh').click();
    const superseded = expectAudit(1, 20);
    buttonByText('Refresh').click();
    const newest = expectAudit(1, 20);
    expect(superseded.cancelled).toBe(true);
    newest.flush(envelope([entry({ id: AUDIT_2, action: 'request.rejected' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('Request rejected');
    expect(text()).not.toContain('Request approved');
    expect(text()).toContain('page 2 of 3');
  });

  it('never lets a superseded page request overwrite the page that replaced it', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();

    buttonByText('Next page').click();
    const supersededNavigation = expectAudit(1, 20);
    buttonByText('Refresh').click();
    const newest = expectAudit(1, 20);
    expect(supersededNavigation.cancelled).toBe(true);
    newest.flush(envelope([entry({ id: AUDIT_2, action: 'request.cancelled' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('Request cancelled');
    expect(text()).not.toContain('Request created');
    expect(text()).toContain('page 2 of 3');
  });

  it('preserves the requested page across an ordinary retry after a server failure', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectAudit(1, 20).flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(text()).toContain('could not be loaded (HTTP 500)');
    expect(rows().length).toBe(0);

    buttonByText('Try again').click();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2 })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');
    expect(rows().length).toBe(1);
  });

  it('explains an invalid request (HTTP 400) and recovers on retry', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush('Bad request', { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect(text()).toContain('rejected (HTTP 400)');
    expect(rows().length).toBe(0);
    expect(element().querySelector('.state.error[role="alert"]')).not.toBeNull();

    buttonByText('Try again').click();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).toContain('Request created');
  });

  it('explains an invalid session (HTTP 401) and can recover after signing in again', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(text()).toContain('session is no longer valid');
    expect(rows().length).toBe(0);

    buttonByText('Try again').click();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).toContain('Request created');
  });

  it('explains an unreachable audit service (network failure) and retries explicitly', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(text()).toContain('audit service is unreachable');
    expect(rows().length).toBe(0);

    buttonByText('Try again').click();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).toContain('Request created');
  });

  it('clears every row on HTTP 403 and shows the forbidden panel with a recheck action', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    buttonByText('Next page').click();
    expectAudit(1, 20).flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(element().querySelector('ul')).toBeNull();
    expect(text()).not.toContain('Request created');
    expect(text()).toContain('Audit records are unavailable for this account');
    expect(element().querySelector('.state.error[role="alert"]')).not.toBeNull();

    buttonByText('Check again').click();
    await fixture.whenStable();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.approved' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(rows().length).toBe(1);
    expect(text()).toContain('Request approved');
  });

  it('lets the reader escape an out-of-range empty page with first-page navigation', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();

    // Move to page 2 while the scope still has 45 records, then let the server report a
    // shrunken scope (5 records): page 2 is now outside the data and comes back empty.
    buttonByText('Next page').click();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2 })], { page: 1, total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectAudit(2, 20).flush(envelope([], { page: 2, total: 5 }));
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).toContain('This page has no audit records');
    expect(text()).toContain('5 audit records');
    expect(buttonByText('Next page').disabled).toBe(true);
    expect(buttonByText('Previous page').disabled).toBe(false);

    buttonByText('First page').click();
    expectAudit(0, 20).flush(envelope([entry()], { total: 5 }));
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 1');
    expect(rows().length).toBe(1);
    expect(queryButton('First page')).toBeNull();
  });

  it('clears rendered rows and cancels a pending request when the session ends', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    buttonByText('Refresh').click();
    const pendingRequest = expectAudit(0, 20);
    session.signOut();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(pendingRequest.cancelled).toBe(true);
    expect(rows().length).toBe(0);
    expect(text()).not.toContain('Audit trail');
  });

  it('cannot restore previous-session data from a late in-flight response', async () => {
    await render();
    await authenticate(scopedProfile({ identityId: IDENTITY_A, companyId: COMPANY_A }));
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();

    buttonByText('Refresh').click();
    const stale = expectAudit(0, 20);
    session.signIn(scopedProfile({ identityId: IDENTITY_B, companyId: COMPANY_A }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(stale.cancelled).toBe(true);
    expect(text()).not.toContain('Request created');
    expect(text()).toContain('Loading audit records');
    expectAudit(0, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.rejected', actorIdentityId: IDENTITY_B })]));
    await fixture.whenStable();
    expect(text()).toContain('Request rejected');
    expect(text()).not.toContain('Request created');
  });

  it('resets to the first page for a company switch and cannot resurrect old rows', async () => {
    await render();
    await authenticate(scopedProfile({ identityId: IDENTITY_A, companyId: COMPANY_A }));
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    const superseded = expectAudit(1, 20);

    session.signIn(scopedProfile({ identityId: IDENTITY_B, companyId: COMPANY_B }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(superseded.cancelled).toBe(true);
    expect(text()).toContain('Loading audit records');
    expect(text()).not.toContain('Request created');

    expectAudit(0, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.cancelled', actorIdentityId: IDENTITY_B })]));
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 1');
    expect(text()).toContain('Request cancelled');
    expect(text()).not.toContain('Request created');
  });

  it('clears rows, pagination and in-flight data when the same identity loses the admin role', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    const pending = expectAudit(1, 20);

    session.signIn(scopedProfile({ roles: ['EMPLOYEE'] }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(pending.cancelled).toBe(true);
    expect(rows().length).toBe(0);
    expect(text()).toContain('available to company administrators only');
    expect(auditRequests().length).toBe(0);
  });

  it('returns to the first page when admin access is granted again', async () => {
    await render();
    await authenticate(scopedProfile({ roles: ['EMPLOYEE'] }));
    expect(text()).toContain('available to company administrators only');
    expect(auditRequests().length).toBe(0);

    session.signIn(scopedProfile());
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Loading audit records');
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).toContain('Request created');
    expect(text()).toContain('page 1 of 1');
  });

  it('loses rows and pagination when the sign-in session restarts', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2 })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');

    session.signOut();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).not.toContain('Audit trail');

    session.signIn(scopedProfile());
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Loading audit records');
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 1');
    expect(text()).toContain('Request created');
  });

  it('preserves the visible page and rows across a transient loading state on an unchanged profile', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectAudit(1, 20).flush(envelope([entry({ id: AUDIT_2, action: 'request.approved' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');

    // Focus refresh: the workspace session reports loading while keeping the same profile.
    session.state.set('loading');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Request approved');
    expect(text()).toContain('page 2 of 3');
    expect(auditRequests().length).toBe(0);

    session.state.set('ready');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Request approved');
  });

  it('clears rows and a pending request when a settled profile change removes authority', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    buttonByText('Refresh').click();
    const pending = expectAudit(0, 20);
    session.signIn(scopedProfile({ roles: ['FINANCE_APPROVER'], version: 4 }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(pending.cancelled).toBe(true);
    expect(rows().length).toBe(0);
    expect(text()).toContain('available to company administrators only');
    expect(auditRequests().length).toBe(0);
  });

  it('clears rows on a settled forbidden profile and cannot restore them from a late prior read', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    buttonByText('Refresh').click();
    const stale = expectAudit(0, 20);
    session.errorMessage.set('Your membership is no longer active.');
    session.apply(`${COMPANY_A}\u0000${IDENTITY_A}`, null, 'forbidden');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(stale.cancelled).toBe(true);
    expect(rows().length).toBe(0);
    expect(text()).toContain('Your membership is no longer active.');
    expect(auditRequests().length).toBe(0);
  });

  it('exposes accessible total/page text through a single live region', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()], { total: 45 }));
    await fixture.whenStable();

    const statuses = element().querySelectorAll('[role="status"]');
    expect(statuses.length).toBe(1);
    expect(statuses[0]!.getAttribute('aria-live')).toBe('polite');
    expect(statuses[0]!.textContent).toContain('45 audit records');
    expect(statuses[0]!.textContent).toContain('page 1 of 3');
    expect(element().querySelector('ul')?.getAttribute('aria-label')).toBe('Audit records');
    expect(buttonByText('Refresh').tagName).toBe('BUTTON');
    expect(buttonByText('Next page').tagName).toBe('BUTTON');
  });

  it('never persists audit data or session material in browser storage', async () => {
    await render();
    await authenticate();
    expectAudit(0, 20).flush(envelope([entry()]));
    await fixture.whenStable();

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('formats timestamps in UTC and returns unparseable text unchanged instead of crashing', async () => {
    await render();
    const component = fixture.componentInstance;
    expect(component.formatTimestamp('2026-10-19T09:00:00Z')).toContain('UTC');
    expect(component.formatTimestamp('not-a-date')).toBe('not-a-date');
  });
});
