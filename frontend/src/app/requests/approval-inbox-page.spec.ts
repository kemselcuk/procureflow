import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { WritableSignal, signal } from '@angular/core';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';
import { ApprovalInboxPage } from './approval-inbox-page';
import { requestPage, requestView, summary, COMPANY_A, COMPANY_B, DEPARTMENT_A, IDENTITY_A, IDENTITY_B, REQUEST_A, REQUEST_B } from './request-fixtures';

function managerProfile(roles: readonly WorkspaceRole[] = ['DEPARTMENT_MANAGER']): WorkspaceProfile {
  return {
    identityId: IDENTITY_B,
    membershipId: 'bbbbbbbb-3333-4333-8333-333333333333',
    companyId: COMPANY_A,
    companyName: 'Larkvane',
    departmentId: DEPARTMENT_A,
    departmentName: 'department management',
    version: 3,
    roles,
    managedDepartmentIds: [DEPARTMENT_A],
  };
}

describe('Approval inbox page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ApprovalInboxPage>;
  let ownerKey: WritableSignal<string | null>;
  let generation: WritableSignal<number>;
  let profileSignal: WritableSignal<WorkspaceProfile | null>;

  beforeEach(() => {
    ownerKey = signal<string | null>(`${COMPANY_A}\u0000${IDENTITY_B}`);
    generation = signal(4);
    profileSignal = signal<WorkspaceProfile | null>(managerProfile());
    TestBed.configureTestingModule({
      imports: [ApprovalInboxPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: WorkspaceSession,
          useValue: {
            ownerKey,
            generation,
            profile: profileSignal,
            isEmployee: signal(false),
            canReachApprovals: signal(true),
            refresh: async () => profileSignal(),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(): void {
    fixture = TestBed.createComponent(ApprovalInboxPage);
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

  function expectInbox(page = 0, status?: string): TestRequest {
    const request = http.expectOne((candidate) => candidate.url === '/api/requests');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('view')).toBe('inbox');
    expect(request.request.params.get('page')).toBe(String(page));
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('status')).toBe(status ?? null);
    return request;
  }

  it('loads the actionable inbox for a manager and renders canonical summary fields', async () => {
    render();
    expectInbox(0).flush(
      requestPage([summary({ status: 'AWAITING_DEPARTMENT_APPROVAL', amount: 1200.5 })], { total: 1 }),
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Requests awaiting your decision');
    expect(text()).toContain('Laptop replacement');
    expect(text()).toContain('€1,200.50');
    expect(text()).toContain('department management');
    expect(text()).toContain('Review');
    const link = element().querySelector('a[href^="/requests/"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe(`/requests/${REQUEST_A}`);
  });

  it.each([
    ['an employee', ['EMPLOYEE']],
    ['a company administrator', ['COMPANY_ADMIN']],
  ] as const)('fails closed for %s without sending an inbox request', async (_label, roles) => {
    profileSignal.set(managerProfile(roles));
    render();
    await fixture.whenStable();
    expect(text()).toContain('Approvals are unavailable for this account');
    expect(http.match('/api/requests').length).toBe(0);
  });

  it('loads the finance queue for a finance approver', async () => {
    profileSignal.set(managerProfile(['FINANCE_APPROVER']));
    render();
    expectInbox(0).flush(requestPage([summary({ status: 'AWAITING_FINANCE_APPROVAL', amount: 1500 })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Awaiting finance approval');
  });

  it('resets to page zero and clears rows when the status filter changes', async () => {
    render();
    expectInbox(0).flush(requestPage([summary({ status: 'AWAITING_DEPARTMENT_APPROVAL' })], { total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();

    const next = button('Next page');
    next.click();
    expectInbox(1).flush(
      requestPage([summary({ id: REQUEST_B, title: 'Second page' })], { page: 1, total: 45 }),
    );
    fixture.detectChanges();
    await fixture.whenStable();

    const select = element().querySelector('#approval-status-filter') as HTMLSelectElement;
    select.value = 'AWAITING_FINANCE_APPROVAL';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text()).toContain('Loading actionable requests');
    expect(text()).not.toContain('Second page');
    expectInbox(0, 'AWAITING_FINANCE_APPROVAL').flush(requestPage([], { total: 0 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('No requests currently need your decision');
  });

  it('ignores a late inbox response from a superseded filter', async () => {
    render();
    const first = expectInbox(0);
    const select = element().querySelector('#approval-status-filter') as HTMLSelectElement;
    select.value = 'AWAITING_FINANCE_APPROVAL';
    select.dispatchEvent(new Event('change'));
    const second = expectInbox(0, 'AWAITING_FINANCE_APPROVAL');

    first.flush(requestPage([summary({ title: 'Stale inbox row' })], { total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Stale inbox row');

    second.flush(requestPage([summary({ title: 'Finance row', status: 'AWAITING_FINANCE_APPROVAL', amount: 1500 })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Finance row');
  });

  it('opens the canonical request before a decision and reloads the inbox after approval', async () => {
    render();
    expectInbox(0).flush(
      requestPage([summary({ status: 'AWAITING_DEPARTMENT_APPROVAL', amount: 800 })], { total: 1 }),
    );
    fixture.detectChanges();
    await fixture.whenStable();

    button('Review').click();
    fixture.detectChanges();
    http
      .expectOne(`/api/requests/${REQUEST_A}`)
      .flush(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', requesterIdentityId: IDENTITY_A }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Decision');
    expect(text()).toContain('Approve department step');

    button('Approve department step').click();
    const approve = http.expectOne(`/api/requests/${REQUEST_A}/approve`);
    expect(approve.request.method).toBe('POST');
    expect(approve.request.body).toEqual({ version: 2 });
    approve.flush(requestView({ status: 'APPROVED', version: 3 }));
    expectInbox(0).flush(requestPage([], { total: 0 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Decision recorded');
    expect(text()).toContain('No requests currently need your decision');
  });

  it('explains when a reviewed request has left the current decision step', async () => {
    render();
    expectInbox(0).flush(requestPage([summary({ status: 'AWAITING_DEPARTMENT_APPROVAL' })]));
    fixture.detectChanges();
    await fixture.whenStable();
    button('Review').click();
    fixture.detectChanges();
    http
      .expectOne(`/api/requests/${REQUEST_A}`)
      .flush(requestView({ status: 'APPROVED', requesterIdentityId: IDENTITY_A, approvedAt: '2026-10-18T12:00:00Z', budgetMonth: '2026-10' }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('no longer at a step your current membership may decide');
    expect(text()).not.toContain('Approve department step');
  });

  it('shows a review error when the canonical request is no longer available', async () => {
    render();
    expectInbox(0).flush(requestPage([summary({ status: 'AWAITING_DEPARTMENT_APPROVAL' })]));
    fixture.detectChanges();
    await fixture.whenStable();
    button('Review').click();
    fixture.detectChanges();
    http
      .expectOne(`/api/requests/${REQUEST_A}`)
      .flush({ status: 404, title: 'Not Found', code: 'not_found' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('not available in your current company');
    expect(text()).not.toContain('Approve department step');
  });

  it('clears the queue when the active identity changes and reloads for the new role set', async () => {
    render();
    expectInbox(0).flush(requestPage([summary({ title: 'Manager A row' })]));
    fixture.detectChanges();
    await fixture.whenStable();

    ownerKey.set(`${COMPANY_B}\u0000${IDENTITY_A}`);
    generation.update((value) => value + 1);
    profileSignal.set({
      identityId: IDENTITY_A,
      membershipId: 'aaaaaaaa-3333-4333-8333-333333333333',
      companyId: COMPANY_B,
      companyName: 'Other company',
      departmentId: DEPARTMENT_A,
      departmentName: 'operations',
      version: 2,
      roles: ['FINANCE_APPROVER'],
      managedDepartmentIds: [],
    });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Manager A row');
    expectInbox(0).flush(requestPage([summary({ title: 'Finance B row', status: 'AWAITING_FINANCE_APPROVAL', amount: 1500 })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Finance B row');
  });

  it('shows the empty state and a labelled filter for assistive technology', async () => {
    render();
    expectInbox(0).flush(requestPage([], { total: 0 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('No requests currently need your decision');
    expect(element().querySelector('label[for="approval-status-filter"]')).not.toBeNull();
    expect(element().querySelector('[role="status"]')).not.toBeNull();
  });
});
