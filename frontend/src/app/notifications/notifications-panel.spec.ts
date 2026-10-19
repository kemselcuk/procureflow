import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { Authentication, MemberProfile } from '../authentication';
import { NotificationItem, NotificationPage } from './notification-api';
import { NotificationsPanel } from './notifications-panel';

const IDENTITY_A = 'aaaaaaaa-1111-4111-8111-111111111111';
const COMPANY_A = 'aaaaaaaa-2222-4222-8222-222222222222';
const IDENTITY_B = 'bbbbbbbb-1111-4111-8111-111111111111';
const COMPANY_B = 'bbbbbbbb-2222-4222-8222-222222222222';

const ID_1 = '11111111-1111-4111-8111-111111111111';
const ID_2 = '55555555-5555-4555-8555-555555555555';
const EVENT_1 = '22222222-2222-4222-8222-222222222222';
const REQUEST_1 = '33333333-3333-4333-8333-333333333333';
const CORRELATION_1 = '44444444-4444-4444-8444-444444444444';

function item(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: ID_1,
    eventId: EVENT_1,
    eventType: 'request.submitted',
    requestId: REQUEST_1,
    correlationId: CORRELATION_1,
    occurredAt: '2026-10-18T09:30:00Z',
    createdAt: '2026-10-18T09:30:01Z',
    readAt: null,
    ...overrides,
  };
}

function page(items: NotificationItem[], overrides: Partial<Omit<NotificationPage, 'items'>> = {}): NotificationPage {
  return { page: 0, size: 20, total: items.length, items, ...overrides };
}

function member(identityId = IDENTITY_A, companyId = COMPANY_A): MemberProfile {
  return { identityId, companyId, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] };
}

describe('Notifications panel', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NotificationsPanel>;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    TestBed.configureTestingModule({
      imports: [NotificationsPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: { initialize: async () => {}, state: authState, member: authMember, login: async () => {}, logout: async () => {} },
        },
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
    fixture = TestBed.createComponent(NotificationsPanel);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function authenticate(profile: MemberProfile = member()): Promise<void> {
    authState.set('authenticated');
    authMember.set(profile);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function expectList(pageNumber = 0, size = 20): TestRequest {
    const request = http.expectOne((candidate) => candidate.url === '/api/notifications');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe(String(pageNumber));
    expect(request.request.params.get('size')).toBe(String(size));
    return request;
  }

  function expectRead(id: string): TestRequest {
    const request = http.expectOne((candidate) => candidate.url === `/api/notifications/${id}/read`);
    expect(request.request.method).toBe('POST');
    return request;
  }

  function notificationRequests(): TestRequest[] {
    return http.match((candidate) => candidate.url.startsWith('/api/notifications'));
  }

  function buttonByText(label: string): HTMLButtonElement {
    const found = Array.from(element().querySelectorAll('button')).find(
      (button) => (button.textContent ?? '').trim() === label,
    );
    if (!found) throw new Error(`Button not found: ${label}`);
    return found as HTMLButtonElement;
  }

  function readAction(index = 0): HTMLButtonElement {
    const found = element().querySelectorAll('button.read-action')[index];
    if (!found) throw new Error(`Read action ${index} not found`);
    return found as HTMLButtonElement;
  }

  function rows(): HTMLElement[] {
    return Array.from(element().querySelectorAll('li'));
  }

  it.each(['starting', 'anonymous', 'forbidden', 'error'] as const)(
    'renders nothing and sends no private request while the session is %s',
    async (state) => {
      authState.set(state);
      await render();
      expect(text()).not.toContain('Notifications');
      expect(notificationRequests().length).toBe(0);
    },
  );

  it('stays hidden when the session is authenticated without an active member', async () => {
    authState.set('authenticated');
    await render();
    expect(text()).not.toContain('Notifications');
    expect(notificationRequests().length).toBe(0);
  });

  it('loads the first page only after the session becomes authenticated with a member', async () => {
    await render();
    expect(notificationRequests().length).toBe(0);
    await authenticate();
    expect(text()).toContain('Loading notifications');
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(text()).toContain('Request submitted');
  });

  it.each(['EMPLOYEE', 'DEPARTMENT_MANAGER', 'FINANCE_APPROVER', 'COMPANY_ADMIN'])(
    'shows the same own-notification list to an active %s member',
    async (role) => {
      await render();
      await authenticate({ ...member(), roles: [role] });
      expectList(0, 20).flush(page([item()]));
      await fixture.whenStable();
      expect(rows().length).toBe(1);
      expect(text()).toContain('Request submitted');
    },
  );

  it('lists readable event labels, UTC timestamps and accessible unread markers', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(
      page([
        item(),
        item({ id: ID_2, eventType: 'request.approved', readAt: '2026-10-18T10:00:00Z' }),
      ]),
    );
    await fixture.whenStable();
    const listed = rows();
    expect(listed.length).toBe(2);
    expect(text()).toContain('Request submitted');
    expect(text()).toContain('Request approved');
    expect(listed[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(listed[1]?.querySelector('.badge')?.textContent).toContain('Read');
    expect(listed[0]?.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-18T09:30:00Z');
    expect(listed[0]?.querySelector('time')?.textContent).toContain('2026');
    expect(listed[0]?.querySelector('time')?.textContent).toContain('UTC');
    expect(listed[1]?.querySelectorAll('time')[1]?.getAttribute('datetime')).toBe('2026-10-18T10:00:00Z');
    expect(text()).toContain('2 notifications');
    expect(text()).toContain('page 1 of 1');
    expect(buttonByText('Next page').disabled).toBe(true);
    expect(buttonByText('Previous page').disabled).toBe(true);
    expect(element().querySelectorAll('button.read-action').length).toBe(1);
  });

  it('falls back to a readable label for an unknown event type', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(
      page([
        item({ eventType: 'request.future_state' }),
        item({ id: ID_2, eventType: 'mystery-event' }),
        item({ id: '77777777-7777-4777-8777-777777777777', eventType: 'constructor' }),
      ]),
    );
    await fixture.whenStable();
    expect(text()).toContain('Request future state');
    expect(text()).toContain('Mystery event');
    expect(text()).toContain('Constructor');
    expect(text()).not.toContain('[object Object]');
  });

  it('shows the empty state with a refresh action and no list', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([], { total: 0 }));
    await fixture.whenStable();
    expect(text()).toContain('You have no notifications yet');
    expect(element().querySelector('ul')).toBeNull();
    expect(buttonByText('Refresh')).toBeTruthy();
  });

  it('pages forward and backward with stable page and size parameters', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()], { total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 3');
    expect(buttonByText('Next page').disabled).toBe(false);
    expect(buttonByText('Previous page').disabled).toBe(true);

    buttonByText('Next page').click();
    expectList(1, 20).flush(page([item({ id: ID_2 })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');
    expect(buttonByText('Previous page').disabled).toBe(false);

    buttonByText('Previous page').click();
    expectList(0, 20).flush(page([item()], { total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 1 of 3');
  });

  it('disables Next on the last page of a partial final page', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()], { page: 1, total: 21 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 2');
    expect(buttonByText('Next page').disabled).toBe(true);
  });

  it('keeps only the newest list response when refresh is triggered twice', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()], { total: 3 }));
    await fixture.whenStable();

    buttonByText('Refresh').click();
    const superseded = notificationRequests()[0];
    buttonByText('Refresh').click();
    const newest = expectList(0, 20);
    expect(superseded?.cancelled).toBe(true);
    newest.flush(page([item({ eventType: 'request.rejected' })], { total: 3 }));
    await fixture.whenStable();
    expect(text()).toContain('Request rejected');
    expect(text()).not.toContain('Request submitted');
    expect(rows().length).toBe(1);
  });

  it('applies only the server-returned read time and leaves other rows untouched', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item(), item({ id: ID_2, eventType: 'request.approved' })], { total: 2 }));
    await fixture.whenStable();

    readAction(0).click();
    const request = expectRead(ID_1);
    expect(request.request.body).toBeNull();
    request.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    const listed = rows();
    expect(listed[0]?.querySelector('.badge')?.textContent).toContain('Read');
    expect(listed[0]?.querySelectorAll('time')[1]?.getAttribute('datetime')).toBe('2026-10-18T10:15:00Z');
    expect(listed[0]?.querySelector('button.read-action')).toBeNull();
    expect(listed[1]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(listed[1]?.querySelectorAll('time').length).toBe(1);
    expect(listed[1]?.querySelector('button.read-action')).not.toBeNull();
  });

  it('keeps an existing read timestamp and hides the action for already-read rows', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item({ readAt: '2026-10-18T09:00:00Z' })]));
    await fixture.whenStable();
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Read');
    expect(rows()[0]?.querySelector('button.read-action')).toBeNull();
    expect(
      Array.from(rows()[0]?.querySelectorAll('time') ?? [])
        .map((time) => time.getAttribute('datetime'))
        .includes('2026-10-18T09:00:00Z'),
    ).toBe(true);
  });

  it('disables a pending read action and suppresses duplicate requests for the same row', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item(), item({ id: ID_2 })]));
    await fixture.whenStable();

    readAction(0).click();
    fixture.detectChanges();
    expect(readAction(0).disabled).toBe(true);
    expect(readAction(0).textContent).toContain('Marking as read');
    const request = expectRead(ID_1);
    readAction(0).click();
    fixture.detectChanges();
    expect(http.match((candidate) => candidate.method === 'POST' && candidate.url.endsWith('/read')).length).toBe(0);
    request.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();
    expect(element().querySelectorAll('button.read-action').length).toBe(1);
  });

  it('keeps the list and shows a retryable error when a read write fails', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item(), item({ id: ID_2 })]));
    await fixture.whenStable();

    readAction(0).click();
    expectRead(ID_1).flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(rows().length).toBe(2);
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(text()).toContain('Marking as read failed (HTTP 500). Try again.');
    expect(element().querySelector('.action-error[role="alert"]')).not.toBeNull();
    expect(readAction(0).disabled).toBe(false);

    readAction(0).click();
    expectRead(ID_1).flush({ id: ID_1, readAt: '2026-10-18T10:20:00Z' });
    await fixture.whenStable();
    expect(text()).not.toContain('Marking as read failed');
    expect(element().querySelector('.action-error')).toBeNull();
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Read');
  });

  it.each([
    [404, 'This notification is no longer available. Reload the list.'],
    [403, 'Your current membership does not allow this action.'],
    [401, 'Your session is no longer valid. Sign in again to continue.'],
  ])('explains a %i read failure and keeps the row unread', async (status, expected) => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    expectRead(ID_1).flush('Denied', { status, statusText: 'Denied' });
    await fixture.whenStable();
    expect(rows().length).toBe(1);
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(text()).toContain(expected);
    expect(element().querySelector('.action-error[role="alert"]')).not.toBeNull();
    expect(buttonByText('Reload list')).toBeTruthy();
    expect(readAction(0).disabled).toBe(false);
  });

  it('does not apply a read receipt that does not match the requested notification', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    expectRead(ID_1).flush({ id: ID_2, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(text()).toContain('Marking as read failed. Try again.');
  });

  it('shows a membership-forbidden state and can re-check the session', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(text()).toContain('An active company membership is required');
    expect(element().querySelector('.state.error[role="alert"]')).not.toBeNull();

    buttonByText('Check again').click();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(text()).toContain('Request submitted');
  });

  it.each([401, 503])('shows a recoverable list error for HTTP %i and reloads after retry', async (status) => {
    await render();
    await authenticate();
    expectList(0, 20).flush('Unavailable', { status, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(element().querySelector('.state.error[role="alert"]')).not.toBeNull();
    if (status === 401) expect(text()).toContain('session is no longer valid');

    buttonByText('Try again').click();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(text()).toContain('Request submitted');
  });

  it('retries the same page after a pagination failure', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()], { total: 45 }));
    await fixture.whenStable();
    buttonByText('Next page').click();
    expectList(1, 20).flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    buttonByText('Try again').click();
    expectList(1, 20).flush(page([item()], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');
  });

  it('treats an incompatible payload as an error instead of rendering raw data', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush({
      page: 0,
      size: 20,
      total: 1,
      items: [{ id: 'notification-1', email: 'person@demo.invalid' }],
    });
    await fixture.whenStable();
    expect(element().querySelector('.state.error[role="alert"]')).not.toBeNull();
    expect(text()).not.toContain('person@demo.invalid');
    expect(rows().length).toBe(0);
  });

  it('clears rendered items when the session ends', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    authState.set('anonymous');
    authMember.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).not.toContain('Notifications');
  });

  it('ignores a late read response after logout', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    readAction(0).click();
    const pendingRead = expectRead(ID_1);

    authState.set('anonymous');
    authMember.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).not.toContain('Notifications');
  });

  it('clears items when the member identity changes within the same company', async () => {
    await render();
    await authenticate(member(IDENTITY_A, COMPANY_A));
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    authMember.set(member(IDENTITY_B, COMPANY_A));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(rows().length).toBe(0);
    expect(text()).toContain('Loading notifications');
    expectList(0, 20).flush(page([item({ eventType: 'request.rejected' })]));
    await fixture.whenStable();
    expect(text()).toContain('Request rejected');
  });

  it('clears prior items on a company switch and cannot resurrect them from the old list response', async () => {
    await render();
    await authenticate(member(IDENTITY_A, COMPANY_A));
    const superseded = expectList(0, 20);

    authMember.set(member(IDENTITY_B, COMPANY_B));
    fixture.detectChanges();
    await fixture.whenStable();
    // The pending tenant-A list request is aborted, so its late data cannot resurrect.
    expect(superseded.cancelled).toBe(true);
    expect(text()).toContain('Loading notifications');
    expect(text()).not.toContain('Request submitted');

    const newest = expectList(0, 20);
    newest.flush(page([item({ eventType: 'request.cancelled' })]));
    await fixture.whenStable();
    expect(text()).toContain('Request cancelled');
    expect(text()).not.toContain('Request submitted');
    expect(rows().length).toBe(1);
  });

  it('ignores a late previous-tenant read response even when the id matches the new list', async () => {
    await render();
    await authenticate(member(IDENTITY_A, COMPANY_A));
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    readAction(0).click();
    const pendingRead = expectRead(ID_1);

    authMember.set(member(IDENTITY_B, COMPANY_B));
    fixture.detectChanges();
    await fixture.whenStable();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(rows()[0]?.querySelectorAll('time').length).toBe(1);
    expect(readAction(0).disabled).toBe(false);
  });

  it('keeps a committed read when a refresh snapshot taken before the write arrives after it', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    // Start the read write and hold its response.
    readAction(0).click();
    const pendingRead = expectRead(ID_1);

    // Start a refresh while the write is pending; its server snapshot predates the commit.
    buttonByText('Refresh').click();
    const staleList = expectList(0, 20);

    // The write commits first, while the list is still loading.
    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    // The stale snapshot (readAt still null) must not resurrect the unread state.
    staleList.flush(page([item()]));
    await fixture.whenStable();

    const row = rows()[0];
    expect(row?.querySelector('.badge')?.textContent).toContain('Read');
    expect(row?.querySelectorAll('time').length).toBe(2);
    expect(row?.querySelector('button.read-action')).toBeNull();
  });

  it('keeps a committed read after navigating away and back with a stale page snapshot', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()], { total: 45 }));
    await fixture.whenStable();

    readAction(0).click();
    const pendingRead = expectRead(ID_1);

    buttonByText('Next page').click();
    const secondPage = expectList(1, 20);
    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();
    secondPage.flush(page([item({ id: ID_2, eventType: 'request.approved' })], { page: 1, total: 45 }));
    await fixture.whenStable();
    expect(text()).toContain('page 2 of 3');

    // Returning to the first page hits a snapshot from before the read commit.
    buttonByText('Previous page').click();
    expectList(0, 20).flush(page([item()], { total: 45 }));
    await fixture.whenStable();

    const row = rows()[0];
    expect(row?.querySelector('.badge')?.textContent).toContain('Read');
    expect(row?.querySelectorAll('time').length).toBe(2);
    expect(row?.querySelector('button.read-action')).toBeNull();
  });

  it('applies a confirmed read even when the stale snapshot arrives before the write response', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    const pendingRead = expectRead(ID_1);
    buttonByText('Refresh').click();
    const staleList = expectList(0, 20);

    // The snapshot from before the commit arrives while the write is still pending.
    staleList.flush(page([item()]));
    await fixture.whenStable();
    expect(readAction(0).disabled).toBe(true);

    // The write response then confirms the read; the row must leave the unread state.
    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    const row = rows()[0];
    expect(row?.querySelector('.badge')?.textContent).toContain('Read');
    expect(row?.querySelectorAll('time')[1]?.getAttribute('datetime')).toBe('2026-10-18T10:15:00Z');
    expect(row?.querySelector('button.read-action')).toBeNull();
  });

  it('keeps the row unread and retryable when the write fails during a refresh', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    const pendingRead = expectRead(ID_1);
    buttonByText('Refresh').click();
    const staleList = expectList(0, 20);

    pendingRead.flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    staleList.flush(page([item()]));
    await fixture.whenStable();

    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(text()).toContain('Marking as read failed (HTTP 500). Try again.');

    // A failed write leaves no confirmation behind for a later reload.
    buttonByText('Reload list').click();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(rows()[0]?.querySelectorAll('time').length).toBe(1);
    expect(readAction(0).disabled).toBe(false);
  });

  it('never lets a previous-session receipt mark a row read after the session changed', async () => {
    await render();
    await authenticate(member(IDENTITY_A, COMPANY_A));
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    const pendingRead = expectRead(ID_1);

    authMember.set(member(IDENTITY_B, COMPANY_B));
    fixture.detectChanges();
    await fixture.whenStable();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    // The late receipt belongs to the previous session even though the id matches the new list.
    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    // A later refresh with a null snapshot must still show the new session's row as unread.
    buttonByText('Refresh').click();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    expect(rows().length).toBe(1);
    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(rows()[0]?.querySelectorAll('time').length).toBe(1);
    expect(rows()[0]?.querySelector('button.read-action')).not.toBeNull();
  });

  it('never replaces a non-null server read time with a stored confirmation', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    expectRead(ID_1).flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    // The server reports its own first read time on the next snapshot.
    buttonByText('Refresh').click();
    expectList(0, 20).flush(page([item({ readAt: '2026-10-18T10:16:00Z' })]));
    await fixture.whenStable();
    expect(rows()[0]?.querySelectorAll('time')[1]?.getAttribute('datetime')).toBe('2026-10-18T10:16:00Z');

    // A later null snapshot still falls back to the confirmed time, not a newer replacement.
    buttonByText('Refresh').click();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    expect(rows()[0]?.querySelectorAll('time')[1]?.getAttribute('datetime')).toBe('2026-10-18T10:15:00Z');
  });

  it('exposes native, labelled actions and live regions for assistive technology', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();
    const action = readAction(0);
    expect(action.tagName).toBe('BUTTON');
    expect(action.getAttribute('aria-label')).toBe('Mark as read: Request submitted');
    expect(element().querySelector('[role="status"]')).not.toBeNull();
    expect(element().querySelector('ul')?.getAttribute('aria-label')).toBe('Your notifications');
    expect(text()).toContain('Unread');
    expect(element().querySelector('time')?.getAttribute('datetime')).toBe('2026-10-18T09:30:00Z');
  });

  it('moves keyboard focus to the updated row when the focused read button disappears', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item(), item({ id: ID_2 })]));
    await fixture.whenStable();

    const action = readAction(0);
    action.focus();
    expect(document.activeElement).toBe(action);

    action.click();
    expectRead(ID_1).flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    const row = rows()[0];
    expect(row?.querySelector('button.read-action')).toBeNull();
    expect(document.activeElement).toBe(row);
  });

  it('does not steal focus from a control the reader moved to before the read completed', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    readAction(0).click();
    const pendingRead = expectRead(ID_1);
    const refresh = buttonByText('Refresh');
    refresh.focus();

    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    expect(rows()[0]?.querySelector('.badge')?.textContent).toContain('Read');
    expect(document.activeElement).toBe(refresh);
  });

  it('parks focus on the row before a held read disables the action and keeps it through success', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item(), item({ id: ID_2, eventType: 'request.approved' })]));
    await fixture.whenStable();

    // Keyboard path: the read action owns focus when it is activated.
    const action = readAction(0);
    action.focus();
    action.click();

    // Settle the pending render while the server response is still held.
    const pendingRead = expectRead(ID_1);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(readAction(0).disabled).toBe(true);
    expect(text()).toContain('Marking as read');

    // jsdom keeps a disabled control focused, but native browsers blur it; focus must
    // already sit on the row before completion, not on the disabled action or the body.
    const row = rows()[0];
    expect(document.activeElement).toBe(row);

    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    // Track-by-id keeps the same row element, so the parked position survives the update.
    expect(rows()[0]).toBe(row);
    expect(row?.querySelector('.badge')?.textContent).toContain('Read');
    expect(row?.querySelector('button.read-action')).toBeNull();
    expect(document.activeElement).toBe(row);
  });

  it('parks focus on the row before a held read disables the action and keeps it through failure', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    const action = readAction(0);
    action.focus();
    action.click();

    const pendingRead = expectRead(ID_1);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(readAction(0).disabled).toBe(true);

    const row = rows()[0];
    expect(document.activeElement).toBe(row);

    pendingRead.flush('Server error', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    // The failed write leaves the row unread and retryable from the parked position.
    expect(rows()[0]).toBe(row);
    expect(row?.querySelector('.badge')?.textContent).toContain('Unread');
    expect(readAction(0).disabled).toBe(false);
    expect(text()).toContain('Marking as read failed (HTTP 500). Try again.');
    expect(document.activeElement).toBe(row);
  });

  it('does not move focus when the session ends before a pending read completes', async () => {
    await render();
    await authenticate();
    expectList(0, 20).flush(page([item()]));
    await fixture.whenStable();

    const action = readAction(0);
    action.focus();
    action.click();
    const pendingRead = expectRead(ID_1);

    authState.set('anonymous');
    authMember.set(null);
    fixture.detectChanges();
    await fixture.whenStable();

    pendingRead.flush({ id: ID_1, readAt: '2026-10-18T10:15:00Z' });
    await fixture.whenStable();

    expect(rows().length).toBe(0);
    expect(text()).not.toContain('Notifications');
    expect(document.activeElement?.closest?.('app-notifications-panel')).toBeNull();
  });
});
