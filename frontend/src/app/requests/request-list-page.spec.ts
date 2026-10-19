import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { WritableSignal, signal } from '@angular/core';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile } from '../workspace/workspace-api';
import { RequestListPage } from './request-list-page';
import { requestPage, summary, COMPANY_A, IDENTITY_A, IDENTITY_B, COMPANY_B, DEPARTMENT_A, REQUEST_A, REQUEST_B } from './request-fixtures';

describe('Request list page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<RequestListPage>;
  let ownerKey: WritableSignal<string | null>;
  let generation: WritableSignal<number>;
  let profileSignal: WritableSignal<WorkspaceProfile | null>;
  let employee: WritableSignal<boolean>;
  let refreshCalls: number;

  beforeEach(() => {
    ownerKey = signal<string | null>(`${COMPANY_A}\u0000${IDENTITY_A}`);
    generation = signal(3);
    profileSignal = signal<WorkspaceProfile | null>({
      identityId: IDENTITY_A,
      membershipId: 'aaaaaaaa-3333-4333-8333-333333333333',
      companyId: COMPANY_A,
      companyName: 'Larkvane',
      departmentId: DEPARTMENT_A,
      departmentName: 'engineering',
      version: 3,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    });
    employee = signal(true);
    refreshCalls = 0;
    const refresh = async (): Promise<WorkspaceProfile | null> => {
      refreshCalls += 1;
      return profileSignal();
    };
    TestBed.configureTestingModule({
      imports: [RequestListPage],
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
            isEmployee: employee,
            canReachApprovals: signal(false),
            refresh,
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(): HTMLElement {
    fixture = TestBed.createComponent(RequestListPage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function expectList(page = 0, status?: string): TestRequest {
    const request = http.expectOne((candidate) => candidate.url === '/api/requests');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('view')).toBe('mine');
    expect(request.request.params.get('page')).toBe(String(page));
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('status')).toBe(status ?? null);
    return request;
  }

  async function flushPage(items = [summary()], overrides: Record<string, unknown> = {}): Promise<void> {
    expectList(0).flush(requestPage(items, overrides));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('loads the zero-based own list and renders EUR, UTC and status labels', async () => {
    render();
    await flushPage([summary({ status: 'APPROVED', amount: 1200.5 })], { total: 1 });
    expect(text()).toContain('Laptop replacement');
    expect(text()).toContain('€1,200.50');
    expect(text()).toContain('Approved');
    expect(text()).toContain('page 1 of 1');
    const link = (fixture.nativeElement as HTMLElement).querySelector('h2 a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe(`/requests/${REQUEST_A}`);
  });

  it('resets to page zero when the status filter changes and clears stale rows', async () => {
    render();
    expectList(0).flush(requestPage([summary()], { total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();
    const next = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Next page'),
    ) as HTMLButtonElement;
    next.click();
    expectList(1).flush(requestPage([summary({ id: REQUEST_B })], { page: 1, total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();

    const select = (fixture.nativeElement as HTMLElement).querySelector('#status-filter') as HTMLSelectElement;
    select.value = 'REJECTED';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text()).toContain('Loading your requests');
    expect(text()).not.toContain('Laptop replacement');
    expectList(0, 'REJECTED').flush(requestPage([], { total: 0 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('No requests match this status');
  });

  it('pages forward and backward with the zero-based page parameter', async () => {
    render();
    expectList(0).flush(requestPage([summary()], { total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 3');

    const next = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Next page'),
    ) as HTMLButtonElement;
    next.click();
    expectList(1).flush(requestPage([summary({ id: REQUEST_B, title: 'Second page request' })], { page: 1, total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');
    expect(text()).toContain('Second page request');

    const previous = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Previous page'),
    ) as HTMLButtonElement;
    previous.click();
    expectList(0).flush(requestPage([summary()], { total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 3');
  });

  it('ignores a late response from a superseded filter request', async () => {
    render();
    const first = expectList(0, undefined);
    const select = (fixture.nativeElement as HTMLElement).querySelector('#status-filter') as HTMLSelectElement;
    select.value = 'APPROVED';
    select.dispatchEvent(new Event('change'));
    const second = expectList(0, 'APPROVED');

    first.flush(requestPage([summary({ title: 'Stale result' })], { total: 45 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Stale result');
    expect(text()).toContain('Loading your requests');

    second.flush(requestPage([summary({ title: 'Current result', status: 'APPROVED' })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Current result');
  });

  it('reloads after a recoverable list failure without losing the filter', async () => {
    render();
    expectList(0).flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Try again');
    expect(text()).toContain('HTTP 503');

    const retry = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Try again',
    ) as HTMLButtonElement;
    retry.click();
    expectList(0).flush(requestPage([summary()]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Laptop replacement');
  });

  it('shows a forbidden state for an inactive membership and re-checks access explicitly', async () => {
    render();
    expectList(0).flush({ status: 403, title: 'Forbidden', code: 'forbidden' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Requests are unavailable for this account');
    expect(text()).toContain('does not allow this view');

    const retry = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Check access again',
    ) as HTMLButtonElement;
    retry.click();
    await fixture.whenStable();
    expect(refreshCalls).toBe(1);
    expectList(0).flush(requestPage([summary()]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Laptop replacement');
  });

  it('clears rows and reloads when the active identity changes on the same URL', async () => {
    render();
    expectList(0).flush(requestPage([summary({ title: 'Identity A draft' })]));
    fixture.detectChanges();
    await fixture.whenStable();

    ownerKey.set(`${COMPANY_B}\u0000${IDENTITY_B}`);
    generation.update((value) => value + 1);
    profileSignal.set({
      identityId: IDENTITY_B,
      membershipId: 'bbbbbbbb-3333-4333-8333-333333333333',
      companyId: COMPANY_B,
      companyName: 'Other company',
      departmentId: 'bbbbbbbb-4444-4444-8444-444444444444',
      departmentName: 'operations',
      version: 1,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Identity A draft');
    expect(text()).toContain('Loading your requests');

    expectList(0).flush(requestPage([summary({ title: 'Identity B draft' })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Identity B draft');
  });

  it('ignores a late response after the session ends', async () => {
    render();
    const pending = expectList(0);
    ownerKey.set(null);
    generation.update((value) => value + 1);
    profileSignal.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    pending.flush(requestPage([summary({ title: 'Previous session secret' })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('Previous session secret');
    expect(text()).not.toContain('Identity A');
  });

  it('shows the new-request link only for an active employee', async () => {
    employee.set(false);
    render();
    expectList(0).flush(requestPage([]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).not.toContain('New request');
  });

  it('renders an accessible empty state with a labelled status filter', async () => {
    render();
    const select = (fixture.nativeElement as HTMLElement).querySelector('label[for="status-filter"]');
    expect(select).not.toBeNull();
    expectList(0).flush(requestPage([], { total: 0 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('You have not created any purchase requests yet');
    expect(text()).toContain('0 requests');
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="status"]')).not.toBeNull();
  });
});
