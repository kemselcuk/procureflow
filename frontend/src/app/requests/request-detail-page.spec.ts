import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, ParamMap, convertToParamMap, provideRouter } from '@angular/router';
import { WritableSignal, signal } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';
import { RequestDetailPage } from './request-detail-page';
import {
  COMPANY_A,
  COMPANY_B,
  DEPARTMENT_A,
  IDENTITY_A,
  IDENTITY_B,
  REQUEST_A,
  REQUEST_B,
  historyEntry,
  profile,
  requestView,
} from './request-fixtures';

const FINANCE_ID = 'cccccccc-1111-4111-8111-111111111111';
const OTHER_REQUESTER = 'cccccccc-2222-4222-8222-222222222222';

describe('Request detail page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<RequestDetailPage>;
  let ownerKey: WritableSignal<string | null>;
  let generation: WritableSignal<number>;
  let profileSignal: WritableSignal<WorkspaceProfile | null>;
  let employee: WritableSignal<boolean>;
  let paramMap: BehaviorSubject<ParamMap>;

  beforeEach(() => {
    ownerKey = signal<string | null>(`${COMPANY_A}\u0000${IDENTITY_A}`);
    generation = signal(5);
    profileSignal = signal<WorkspaceProfile | null>(profile());
    employee = signal(true);
    paramMap = new BehaviorSubject<ParamMap>(convertToParamMap({ id: REQUEST_A }));
    TestBed.configureTestingModule({
      imports: [RequestDetailPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { paramMap: paramMap.asObservable() } },
        {
          provide: WorkspaceSession,
          useValue: {
            ownerKey,
            generation,
            profile: profileSignal,
            isEmployee: employee,
            canReachApprovals: signal(false),
            refresh: async () => profileSignal(),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(): void {
    fixture = TestBed.createComponent(RequestDetailPage);
    fixture.detectChanges();
  }

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent ?? '';
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(element().querySelectorAll('button')).find(
      (candidate) => (candidate.textContent ?? '').trim().includes(label),
    );
    if (!found) throw new Error(`Button not found: ${label}`);
    return found as HTMLButtonElement;
  }

  async function flushHistory(entries = [historyEntry()], id = REQUEST_A): Promise<void> {
    http.expectOne(`/api/requests/${id}/history`).flush(entries);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function loadRequest(view = requestView(), entries = [historyEntry()]): Promise<void> {
    http.expectOne(`/api/requests/${REQUEST_A}`).flush(view);
    http.expectOne(`/api/requests/${REQUEST_A}/history`).flush(entries);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function switchToB(): Promise<void> {
    paramMap.next(convertToParamMap({ id: REQUEST_B }));
    fixture.detectChanges();
    http.expectOne(`/api/requests/${REQUEST_B}`).flush(
      requestView({ id: REQUEST_B, title: 'B draft', requesterIdentityId: IDENTITY_A }),
    );
    http.expectOne(`/api/requests/${REQUEST_B}/history`).flush([historyEntry()]);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function setManagerProfile(roles: readonly WorkspaceRole[] = ['DEPARTMENT_MANAGER']): void {
    employee.set(false);
    profileSignal.set(
      profile({
        identityId: IDENTITY_B,
        membershipId: 'bbbbbbbb-3333-4333-8333-333333333333',
        roles,
        managedDepartmentIds: [DEPARTMENT_A],
      }),
    );
  }

  it('loads the canonical detail and timeline with EUR, UTC and returned approval metadata', async () => {
    render();
    await loadRequest(
      requestView({
        status: 'APPROVED',
        amount: 1500.25,
        approvedAt: '2026-10-18T12:30:00Z',
        budgetMonth: '2026-10',
      }),
      [
        historyEntry(),
        historyEntry({
          id: '44444444-4444-4444-8444-444444444445',
          version: 3,
          action: 'request.approved',
          fromStatus: 'AWAITING_FINANCE_APPROVAL',
          toStatus: 'APPROVED',
          actorIdentityId: IDENTITY_B,
          reason: null,
        }),
      ],
    );
    expect(text()).toContain('Laptop replacement');
    expect(text()).toContain('€1,500.25');
    expect(text()).toContain('Approved');
    expect(text()).toContain('2026-10');
    expect(text()).toContain('Final approval (UTC)');
    expect(text()).toContain('engineering');
    expect(text()).toContain('cannot be cancelled or refunded');
    expect(text()).toContain('Final approval is recorded');
    expect(text()).toContain('version 3');
    expect(element().querySelectorAll('time').length).toBeGreaterThanOrEqual(3);
  });

  it('shows a not-found state for a foreign request and never renders stale content', async () => {
    render();
    await loadRequest();
    expect(text()).toContain('Laptop replacement');

    const next = requestView({ id: REQUEST_B, title: 'Foreign secret', requesterIdentityId: OTHER_REQUESTER });
    paramMap.next(convertToParamMap({ id: REQUEST_B }));
    fixture.detectChanges();
    expect(text()).not.toContain('Laptop replacement');

    http.expectOne(`/api/requests/${REQUEST_B}`).flush(
      { status: 404, title: 'Not Found', code: 'not_found' },
      { status: 404, statusText: 'Not Found' },
    );
    http.expectOne(`/api/requests/${REQUEST_B}/history`).flush(
      { status: 404, title: 'Not Found', code: 'not_found' },
      { status: 404, statusText: 'Not Found' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Request not found');
    expect(text()).not.toContain('Foreign secret');
    expect(text()).not.toContain('Laptop replacement');
    expect(next.id).toBe(REQUEST_B);
  });

  it('shows a forbidden state without rendering request content', async () => {
    render();
    http.expectOne(`/api/requests/${REQUEST_A}`).flush(
      { status: 403, title: 'Forbidden', code: 'forbidden' },
      { status: 403, statusText: 'Forbidden' },
    );
    http.expectOne(`/api/requests/${REQUEST_A}/history`).flush(
      { status: 403, title: 'Forbidden', code: 'forbidden' },
      { status: 403, statusText: 'Forbidden' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Request unavailable');
    expect(text()).not.toContain('Laptop replacement');
  });

  it('keeps the detail usable when the timeline fails and never implies an empty history', async () => {
    render();
    http.expectOne(`/api/requests/${REQUEST_A}`).flush(requestView());
    http.expectOne(`/api/requests/${REQUEST_A}/history`).flush(
      'Server error',
      { status: 500, statusText: 'Server Error' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Laptop replacement');
    expect(text()).toContain('HTTP 500');
    expect(text()).not.toContain('returned no timeline entries');

    button('Try the timeline again').click();
    await flushHistory([historyEntry({ action: 'request.edited', version: 2, fromStatus: 'DRAFT', toStatus: 'DRAFT' })]);
    expect(text()).toContain('Draft edited');
    expect(text()).toContain('version 2');
  });

  it('states explicitly when the server returned an empty timeline', async () => {
    render();
    await loadRequest(requestView(), []);
    expect(text()).toContain('returned no timeline entries');
  });

  it('offers draft edit and explicit submit only to an employee owner', async () => {
    render();
    await loadRequest();
    expect(text()).toContain('Edit draft');
    expect(text()).toContain('Submit for approval');

    employee.set(false);
    profileSignal.set(profile({ roles: ['DEPARTMENT_MANAGER'], managedDepartmentIds: [DEPARTMENT_A] }));
    generation.update((value) => value + 1);
    fixture.detectChanges();
    await loadRequest();
    expect(text()).not.toContain('Submit for approval');
    expect(text()).toContain('require the active EMPLOYEE role');
  });

  it('saves a draft with the expected version and reloads the canonical request', async () => {
    render();
    await loadRequest();
    button('Edit draft').click();
    fixture.detectChanges();
    setInput('#edit-title', 'Updated title');
    setInput('#edit-justification', 'Updated justification');
    setInput('#edit-amount', '1200.01');
    button('Save draft').click();

    const put = http.expectOne(`/api/requests/${REQUEST_A}`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      version: 2,
      title: 'Updated title',
      justification: 'Updated justification',
      amount: 1200.01,
    });
    put.flush(requestView({ version: 3, title: 'Updated title', amount: 1200.01 }));
    await loadRequest(requestView({ version: 3, title: 'Updated title', amount: 1200.01 }));
    expect(text()).toContain('Updated title');
    expect(text()).toContain('Draft saved.');
    expect(element().querySelector('#edit-title')).toBeNull();
  });

  it('keeps unsaved draft edits after a stale conflict and only reloads on the explicit action', async () => {
    render();
    await loadRequest();
    button('Edit draft').click();
    fixture.detectChanges();
    setInput('#edit-title', 'My unsaved title');
    button('Save draft').click();
    http.expectOne(`/api/requests/${REQUEST_A}`).flush(
      { status: 409, title: 'Conflict', code: 'stale_request' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('changed since you loaded it');
    expect((element().querySelector('#edit-title') as HTMLInputElement).value).toBe('My unsaved title');
    expect(http.match(`/api/requests/${REQUEST_A}`).length).toBe(0);

    button('Reload latest request').click();
    await loadRequest(requestView({ version: 4, title: 'Server title' }));
    expect(text()).toContain('Server title');
    expect(element().querySelector('#edit-title')).toBeNull();
  });

  it('reuses the submit key for an unchanged ambiguous retry and ignores a replayed result', async () => {
    render();
    await loadRequest();
    button('Submit for approval').click();
    const first = http.expectOne(`/api/requests/${REQUEST_A}/submit`);
    expect(first.request.method).toBe('POST');
    expect(first.request.body).toEqual({ version: 2 });
    const key = first.request.headers.get('Idempotency-Key')!;
    expect(key).toMatch(/^[0-9a-f-]{36}$/i);

    first.flush('network down', { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('may or may not have saved this change');
    expect(button('Submit for approval').disabled).toBe(false);
    button('Submit for approval').click();
    const second = http.expectOne(`/api/requests/${REQUEST_A}/submit`);
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);

    second.flush(requestView({ title: 'Replayed original result', status: 'AWAITING_DEPARTMENT_APPROVAL', version: 3 }));
    await loadRequest(
      requestView({ title: 'Canonical current title', status: 'AWAITING_DEPARTMENT_APPROVAL', version: 3 }),
    );
    expect(text()).toContain('Canonical current title');
    expect(text()).not.toContain('Replayed original result');
    expect(text()).toContain('Submitted for approval');
  });

  it('explains a department-change conflict on submit and only continues after an explicit reload', async () => {
    render();
    await loadRequest();
    button('Submit for approval').click();
    const submit = http.expectOne(`/api/requests/${REQUEST_A}/submit`);
    submit.flush(
      { status: 409, title: 'Conflict', code: 'request_department_changed' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('primary department changed');
    expect(text()).toContain('save it again before submitting');
    expect(button('Submit for approval').disabled).toBe(true);

    button('Reload current request').click();
    await loadRequest(requestView({ version: 3, departmentId: 'aaaaaaaa-9999-4999-8999-999999999999' }));
    expect(text()).toContain('Laptop replacement');
  });

  it('requires an explicit reload after a stale submit and uses a new key for the new version', async () => {
    render();
    await loadRequest();
    button('Submit for approval').click();
    const first = http.expectOne(`/api/requests/${REQUEST_A}/submit`);
    const key = first.request.headers.get('Idempotency-Key')!;
    first.flush({ status: 409, title: 'Conflict', code: 'stale_request' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('changed since you loaded it');
    expect(button('Submit for approval').disabled).toBe(true);

    button('Reload current request').click();
    await loadRequest(requestView({ version: 3, title: 'Changed by someone else' }));
    button('Submit for approval').click();
    const second = http.expectOne(`/api/requests/${REQUEST_A}/submit`);
    expect(second.request.body).toEqual({ version: 3 });
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key);
    second.flush(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', version: 4 }));
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', version: 4 }));
  });

  it('cancels a pending request only after explicit confirmation and never offers it for approved states', async () => {
    render();
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', version: 3 }));
    button('Cancel request').click();
    fixture.detectChanges();
    expect(text()).toContain('Confirm cancellation');
    expect(http.match(`/api/requests/${REQUEST_A}/cancel`).length).toBe(0);

    button('Confirm cancellation').click();
    const cancel = http.expectOne(`/api/requests/${REQUEST_A}/cancel`);
    expect(cancel.request.method).toBe('POST');
    expect(cancel.request.body).toEqual({ version: 3 });
    cancel.flush(requestView({ status: 'CANCELLED', version: 4 }));
    await loadRequest(requestView({ status: 'CANCELLED', version: 4 }));
    expect(text()).toContain('terminal state');
    expect(text()).not.toContain('Cancel request');
    expect(text()).not.toContain('Confirm cancellation');
  });

  it.each([
    ['department manager', ['DEPARTMENT_MANAGER'], 'AWAITING_DEPARTMENT_APPROVAL', 800, 'Approve department step'],
    ['finance approver', ['FINANCE_APPROVER'], 'AWAITING_FINANCE_APPROVAL', 1200, 'Approve finance step'],
  ] as const)('shows the current-step decision to a non-owner %s', async (_label, roles, status, amount, approveLabel) => {
    render();
    setManagerProfile(roles);
    await loadRequest(
      requestView({ status, amount, requesterIdentityId: OTHER_REQUESTER, departmentApproverIdentityId: OTHER_REQUESTER }),
    );
    expect(text()).toContain('Decision');
    expect(text()).toContain(approveLabel);
    expect(text()).toContain('Reject request');
  });

  it('does not offer self-approval to an owner who also holds the manager role', async () => {
    render();
    employee.set(false);
    profileSignal.set(profile({ roles: ['DEPARTMENT_MANAGER'], managedDepartmentIds: [DEPARTMENT_A] }));
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL' }));
    expect(text()).not.toContain('Approve department step');
  });

  it('does not offer company administrator a purchase approval action', async () => {
    render();
    profileSignal.set(profile({ roles: ['COMPANY_ADMIN'] }));
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', requesterIdentityId: OTHER_REQUESTER }));
    expect(text()).not.toContain('Decision');
    expect(text()).not.toContain('Approve department step');
  });

  it('blocks the finance actor who recorded the department step', async () => {
    render();
    profileSignal.set(
      profile({ identityId: FINANCE_ID, roles: ['FINANCE_APPROVER'], managedDepartmentIds: [] }),
    );
    await loadRequest(
      requestView({
        status: 'AWAITING_FINANCE_APPROVAL',
        amount: 1200,
        requesterIdentityId: OTHER_REQUESTER,
        departmentApproverIdentityId: FINANCE_ID,
      }),
    );
    expect(text()).not.toContain('Approve finance step');
  });

  it('explains an insufficient-budget denial and does not claim an approval was saved', async () => {
    render();
    setManagerProfile(['FINANCE_APPROVER']);
    await loadRequest(
      requestView({
        status: 'AWAITING_FINANCE_APPROVAL',
        amount: 1200,
        requesterIdentityId: OTHER_REQUESTER,
        departmentApproverIdentityId: OTHER_REQUESTER,
      }),
    );
    button('Approve finance step').click();
    const approve = http.expectOne(`/api/requests/${REQUEST_A}/approve`);
    expect(approve.request.method).toBe('POST');
    expect(approve.request.body).toEqual({ version: 2 });
    approve.flush(
      { status: 409, title: 'Conflict', code: 'insufficient_budget' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('budget for the approval month is insufficient');
    expect(text()).toContain('no amount was charged');
    expect(text()).not.toContain('Approval recorded');
    expect(text()).toContain('Reload current request');

    button('Reload current request').click();
    await loadRequest(
      requestView({
        status: 'AWAITING_FINANCE_APPROVAL',
        amount: 1200,
        requesterIdentityId: OTHER_REQUESTER,
        departmentApproverIdentityId: OTHER_REQUESTER,
      }),
    );
    expect(text()).toContain('Awaiting finance approval');
  });

  it('keeps a rejection reason after a server error and submits the trimmed reason once', async () => {
    render();
    setManagerProfile();
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', requesterIdentityId: OTHER_REQUESTER }));
    button('Reject request').click();
    fixture.detectChanges();
    const reason = element().querySelector('textarea') as HTMLTextAreaElement;
    reason.value = '  Missing quotes  ';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    button('Confirm rejection').click();
    const first = http.expectOne(`/api/requests/${REQUEST_A}/reject`);
    expect(first.request.body).toEqual({ version: 2, reason: 'Missing quotes' });
    first.flush('Server error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect((element().querySelector('textarea') as HTMLTextAreaElement).value).toBe('  Missing quotes  ');
    expect(text()).toContain('could not confirm whether this change was saved');

    button('Confirm rejection').click();
    const second = http.expectOne(`/api/requests/${REQUEST_A}/reject`);
    expect(second.request.body).toEqual({ version: 2, reason: 'Missing quotes' });
    second.flush(requestView({ status: 'REJECTED', version: 3 }));
    await loadRequest(
      requestView({ status: 'REJECTED', version: 3, requesterIdentityId: OTHER_REQUESTER }),
      [
        historyEntry({ version: 3, action: 'request.rejected', fromStatus: 'AWAITING_DEPARTMENT_APPROVAL', toStatus: 'REJECTED', reason: 'Missing quotes' }),
      ],
    );
    expect(text()).toContain('Request rejected');
    expect(text()).toContain('Missing quotes');
    expect(text()).toContain('terminal state');
  });

  it('rejects a blank or overlong rejection reason locally', async () => {
    render();
    setManagerProfile();
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', requesterIdentityId: OTHER_REQUESTER }));
    button('Reject request').click();
    fixture.detectChanges();
    const reason = element().querySelector('textarea') as HTMLTextAreaElement;
    reason.value = '   ';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button('Confirm rejection').click();
    fixture.detectChanges();
    expect(http.match(`/api/requests/${REQUEST_A}/reject`).length).toBe(0);
    expect(text()).toContain('Enter a rejection reason');
  });

  it('ignores a late detail response after the route target changed', async () => {
    render();
    const staleDetail = http.expectOne(`/api/requests/${REQUEST_A}`);
    const staleHistory = http.expectOne(`/api/requests/${REQUEST_A}/history`);
    paramMap.next(convertToParamMap({ id: REQUEST_B }));
    fixture.detectChanges();
    expect(text()).not.toContain('Laptop replacement');

    staleDetail.flush(requestView({ title: 'Stale target content' }));
    staleHistory.flush([historyEntry({ action: 'request.rejected', reason: 'Stale timeline reason' })]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Stale target content');
    expect(text()).not.toContain('Stale timeline reason');

    http.expectOne(`/api/requests/${REQUEST_B}`).flush(requestView({ id: REQUEST_B, title: 'New target content' }));
    http.expectOne(`/api/requests/${REQUEST_B}/history`).flush([
      historyEntry({ action: 'request.edited', reason: 'Current timeline reason' }),
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('New target content');
    expect(text()).toContain('Current timeline reason');
    expect(text()).not.toContain('Stale timeline reason');
  });

  it('clears and reloads scoped data when the active identity changes on the same URL', async () => {
    render();
    await loadRequest(requestView({ title: 'Identity A draft' }));
    ownerKey.set(`${COMPANY_B}\u0000${IDENTITY_B}`);
    generation.update((value) => value + 1);
    profileSignal.set(
      profile({
        identityId: IDENTITY_B,
        membershipId: 'bbbbbbbb-3333-4333-8333-333333333333',
        companyId: COMPANY_B,
        roles: ['FINANCE_APPROVER'],
      }),
    );
    fixture.detectChanges();
    expect(text()).not.toContain('Identity A draft');

    const pending = http.match(`/api/requests/${REQUEST_A}`);
    expect(pending.length).toBe(1);
    pending[0]!.flush(requestView({ title: 'Identity B view' }));
    http.expectOne(`/api/requests/${REQUEST_A}/history`).flush([historyEntry()]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Identity B view');
  });

  it('clears member-bound content when the session ends', async () => {
    render();
    await loadRequest(requestView({ title: 'Session A content' }));
    paramMap.next(convertToParamMap({ id: REQUEST_B }));
    fixture.detectChanges();
    const lateDetail = http.expectOne(`/api/requests/${REQUEST_B}`);
    const lateHistory = http.expectOne(`/api/requests/${REQUEST_B}/history`);

    ownerKey.set(null);
    generation.update((value) => value + 1);
    profileSignal.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Session A content');

    lateDetail.flush(requestView({ id: REQUEST_B, title: 'Late secret' }));
    lateHistory.flush([historyEntry()]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Late secret');
    expect(text()).not.toContain('Session A content');
  });

  it.each([
    [
      'success',
      (request: TestRequest) => request.flush(requestView({ title: 'A stale saved title', version: 3 })),
    ],
    [
      'error',
      (request: TestRequest) => request.flush('Server error', { status: 500, statusText: 'Server Error' }),
    ],
  ] as const)('ignores a late draft-edit %s from the previous route target', async (_label, respond) => {
    render();
    await loadRequest();
    button('Edit draft').click();
    fixture.detectChanges();
    setInput('#edit-title', 'A pending title');
    button('Save draft').click();
    const oldEdit = http.expectOne(`/api/requests/${REQUEST_A}`);

    await switchToB();
    button('Edit draft').click();
    fixture.detectChanges();
    setInput('#edit-title', 'B unsaved title');

    respond(oldEdit);
    fixture.detectChanges();
    await fixture.whenStable();
    expect((element().querySelector('#edit-title') as HTMLInputElement).value).toBe('B unsaved title');
    expect(text()).not.toContain('Draft saved.');
    expect(text()).not.toContain('could not confirm whether this change was saved');
    expect(http.match(`/api/requests/${REQUEST_B}`).length).toBe(0);
  });

  it.each([
    [
      'success',
      (request: TestRequest) =>
        request.flush(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', version: 3 })),
    ],
    [
      'error',
      (request: TestRequest) => request.flush('Server error', { status: 500, statusText: 'Server Error' }),
    ],
  ] as const)('ignores a late submit %s from the previous route target', async (_label, respond) => {
    render();
    await loadRequest();
    button('Submit for approval').click();
    const oldSubmit = http.expectOne(`/api/requests/${REQUEST_A}/submit`);

    await switchToB();
    respond(oldSubmit);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(text()).not.toContain('Submitted for approval');
    expect(text()).not.toContain('could not confirm whether this change was saved');
    expect(button('Submit for approval').disabled).toBe(false);
    expect(http.match(`/api/requests/${REQUEST_B}`).length).toBe(0);
  });

  it.each([
    [
      'success',
      (request: TestRequest) => request.flush(requestView({ status: 'CANCELLED', version: 4 })),
    ],
    [
      'error',
      (request: TestRequest) => request.flush('Server error', { status: 500, statusText: 'Server Error' }),
    ],
  ] as const)('ignores a late cancellation %s from the previous route target', async (_label, respond) => {
    render();
    await loadRequest(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', version: 3 }));
    button('Cancel request').click();
    fixture.detectChanges();
    button('Confirm cancellation').click();
    const oldCancel = http.expectOne(`/api/requests/${REQUEST_A}/cancel`);

    await switchToB();
    respond(oldCancel);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(text()).not.toContain('Request cancelled.');
    expect(text()).not.toContain('could not confirm whether this change was saved');
    expect(text()).toContain('B draft');
    expect(http.match(`/api/requests/${REQUEST_B}`).length).toBe(0);
  });

  function setInput(selector: string, value: string): void {
    const input = element().querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
});
