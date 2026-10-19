import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  NotificationApi,
  NotificationItem,
  NotificationPage,
  NotificationReadReceipt,
  parseNotificationPage,
  parseNotificationReadReceipt,
} from './notification-api';

const ID_A = '11111111-1111-4111-8111-111111111111';
const EVENT_A = '22222222-2222-4222-8222-222222222222';
const REQUEST_A = '33333333-3333-4333-8333-333333333333';
const CORRELATION_A = '44444444-4444-4444-8444-444444444444';

function item(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: ID_A,
    eventId: EVENT_A,
    eventType: 'request.submitted',
    requestId: REQUEST_A,
    correlationId: CORRELATION_A,
    occurredAt: '2026-10-18T09:30:00Z',
    createdAt: '2026-10-18T09:30:01Z',
    readAt: null,
    ...overrides,
  };
}

function bodyWithItem(raw: unknown, remove?: keyof NotificationItem): Record<string, unknown> {
  const value: Record<string, unknown> = { ...(raw as Record<string, unknown>) };
  if (remove) delete value[remove];
  return { page: 0, size: 20, total: 1, items: [value] };
}

describe('Notification API boundary', () => {
  let http: HttpTestingController;
  let api: NotificationApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(NotificationApi);
  });

  afterEach(() => http.verify());

  it('requests the frozen zero-based page path with page and size query parameters', () => {
    let received: NotificationPage | undefined;
    api.list(2, 20).subscribe((page) => (received = page));
    const request = http.expectOne((candidate) => candidate.url === '/api/notifications');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ page: 2, size: 20, total: 41, items: [item()] });
    expect(received?.page).toBe(2);
    expect(received?.total).toBe(41);
    expect(received?.items.length).toBe(1);
  });

  it('keeps only the exact contract fields and never exposes recipient/envelope extras', () => {
    let received: NotificationPage | undefined;
    api.list(0, 20).subscribe((page) => (received = page));
    http
      .expectOne((candidate) => candidate.url === '/api/notifications')
      .flush({
        page: 0,
        size: 20,
        total: 1,
        recipientAddress: 'someone@demo.invalid',
        items: [{ ...item({ readAt: '2026-10-18T10:00:00Z' }), email: 'someone@demo.invalid' }],
      });
    const parsed = received?.items[0];
    expect(parsed).toBeDefined();
    expect(Object.keys(parsed as NotificationItem)).toEqual([
      'id',
      'eventId',
      'eventType',
      'requestId',
      'correlationId',
      'occurredAt',
      'createdAt',
      'readAt',
    ]);
    expect(Object.values(parsed as NotificationItem)).not.toContain('someone@demo.invalid');
    expect(parsed?.readAt).toBe('2026-10-18T10:00:00Z');
  });

  it('errors instead of emitting when a successful response breaks the list contract', () => {
    let emitted = false;
    let failed = false;
    api.list(0, 20).subscribe({ next: () => (emitted = true), error: () => (failed = true) });
    http
      .expectOne((candidate) => candidate.url === '/api/notifications')
      .flush({ page: 0, size: 20, total: 1, items: [{ id: 'not-a-uuid', email: 'person@demo.invalid' }] });
    expect(failed).toBe(true);
    expect(emitted).toBe(false);
  });

  it.each([
    ['a non-object body', null],
    ['a non-array items value', { page: 0, size: 20, total: 0, items: {} }],
    ['a negative page', { page: -1, size: 20, total: 0, items: [] }],
    ['a fractional total', { page: 0, size: 20, total: 1.5, items: [item()] }],
    ['a missing readAt', bodyWithItem(item(), 'readAt')],
    ['a non-null, invalid readAt', bodyWithItem(item({ readAt: 'yesterday' }))],
    ['a non-UUID notification id', bodyWithItem(item({ id: 'notification-1' }))],
    ['an empty event type', bodyWithItem(item({ eventType: '' }))],
    ['a missing correlation id', bodyWithItem(item(), 'correlationId')],
    ['an unparseable occurredAt', bodyWithItem(item({ occurredAt: 'not-a-time' }))],
  ])('rejects %s', (_label, body) => {
    expect(() => parseNotificationPage(body)).toThrow();
  });

  it('posts a bodyless read command to the id-scoped route and parses the receipt', () => {
    let receipt: NotificationReadReceipt | undefined;
    api.markRead(ID_A).subscribe((value) => (receipt = value));
    const request = http.expectOne((candidate) => candidate.url === `/api/notifications/${ID_A}/read`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush({ id: ID_A, readAt: '2026-10-18T10:00:00Z' });
    expect(receipt).toEqual({ id: ID_A, readAt: '2026-10-18T10:00:00Z' });
  });

  it('rejects a read receipt for another id, a missing readAt or a non-timestamp readAt', () => {
    expect(() => parseNotificationReadReceipt({ id: EVENT_A, readAt: '2026-10-18T10:00:00Z' }, ID_A)).toThrow();
    expect(() => parseNotificationReadReceipt({ id: ID_A }, ID_A)).toThrow();
    expect(() => parseNotificationReadReceipt({ id: ID_A, readAt: null }, ID_A)).toThrow();
    expect(() => parseNotificationReadReceipt({ id: ID_A, readAt: 'later' }, ID_A)).toThrow();
  });

  it('errors the read stream when a matching-id response carries an invalid timestamp', () => {
    let emitted = false;
    let failed = false;
    api.markRead(ID_A).subscribe({ next: () => (emitted = true), error: () => (failed = true) });
    http
      .expectOne((candidate) => candidate.url === `/api/notifications/${ID_A}/read`)
      .flush({ id: ID_A, readAt: 'tomorrow' });
    expect(failed).toBe(true);
    expect(emitted).toBe(false);
  });
});
