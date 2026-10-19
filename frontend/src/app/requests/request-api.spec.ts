import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  newIdempotencyKey,
  parseRequestHistory,
  parseRequestPage,
  parseRequestView,
  RequestApi,
  RequestPage,
  RequestView,
} from './request-api';
import { CORRELATION_A, DEPARTMENT_A, IDENTITY_A, REQUEST_A, REQUEST_B, historyEntry, requestPage, requestView, summary } from './request-fixtures';

const KEY = '99999999-9999-4999-8999-999999999999';

/** Canonical two-decimal amounts that cross the actual JSON number parsing boundary. */
const CENT_AMOUNT_TEXTS = ['0.01', '0.29', '1', '800.5', '123456789.12', '9999999999.03', '999999999999.99'] as const;

function jsonAmount(text: string): number {
  return JSON.parse(text) as number;
}

describe('Request API boundary', () => {
  let http: HttpTestingController;
  let api: RequestApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(RequestApi);
  });

  afterEach(() => http.verify());

  it('requests the zero-based own-request page with view, page and size', () => {
    let received: RequestPage | undefined;
    api.list({ view: 'mine', page: 0, size: 20 }).subscribe((value) => (received = value));
    const request = http.expectOne((candidate) => candidate.url === '/api/requests');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('view')).toBe('mine');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.has('status')).toBe(false);
    request.flush(requestPage([summary()]));
    expect(received?.page).toBe(0);
    expect(received?.items[0]?.title).toBe('Laptop replacement');
  });

  it('requests an explicitly filtered second inbox page', () => {
    api.list({ view: 'inbox', page: 2, size: 20, status: 'AWAITING_FINANCE_APPROVAL' }).subscribe();
    const request = http.expectOne((candidate) => candidate.url === '/api/requests');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('view')).toBe('inbox');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('status')).toBe('AWAITING_FINANCE_APPROVAL');
    request.flush(requestPage([], { page: 2, total: 41 }));
  });

  it('reads detail and history from their id-scoped paths', () => {
    let detail: RequestView | undefined;
    api.detail(REQUEST_A).subscribe((value) => (detail = value));
    const detailRequest = http.expectOne(`/api/requests/${REQUEST_A}`);
    expect(detailRequest.request.method).toBe('GET');
    detailRequest.flush(requestView({ status: 'APPROVED', approvedAt: '2026-10-18T12:00:00Z', budgetMonth: '2026-10' }));
    expect(detail?.budgetMonth).toBe('2026-10');

    let history: readonly unknown[] | undefined;
    api.history(REQUEST_A).subscribe((value) => (history = value));
    const historyRequest = http.expectOne(`/api/requests/${REQUEST_A}/history`);
    expect(historyRequest.request.method).toBe('GET');
    historyRequest.flush([historyEntry({ fromStatus: 'DRAFT', toStatus: 'AWAITING_DEPARTMENT_APPROVAL' })]);
    expect(history?.length).toBe(1);
  });

  it('creates with the exact body and a UUID Idempotency-Key header only', () => {
    let created: RequestView | undefined;
    api.create({ title: 'Laptop replacement', justification: 'Battery failure', amount: 800.5 }, KEY).subscribe(
      (value) => (created = value),
    );
    const request = http.expectOne('/api/requests');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe(KEY);
    expect(request.request.body).toEqual({
      title: 'Laptop replacement',
      justification: 'Battery failure',
      amount: 800.5,
    });
    expect(Object.keys(request.request.body as object).sort()).toEqual(['amount', 'justification', 'title']);
    request.flush(requestView({ amount: 800.5 }));
    expect(created?.id).toBe(REQUEST_A);
  });

  it('edits with a complete versioned body and no idempotency header', () => {
    api.edit(REQUEST_A, { version: 2, title: 'Updated', justification: 'Updated justification', amount: 1200.01 }).subscribe();
    const request = http.expectOne(`/api/requests/${REQUEST_A}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    expect(request.request.body).toEqual({
      version: 2,
      title: 'Updated',
      justification: 'Updated justification',
      amount: 1200.01,
    });
    request.flush(requestView({ version: 3, title: 'Updated' }));
  });

  it('submits the expected version with a stable key header', () => {
    api.submit(REQUEST_A, 2, KEY).subscribe();
    const request = http.expectOne(`/api/requests/${REQUEST_A}/submit`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe(KEY);
    expect(request.request.body).toEqual({ version: 2 });
    request.flush(requestView({ status: 'AWAITING_DEPARTMENT_APPROVAL', version: 3 }));
  });

  it('sends cancel, approve and reject bodies with only the version and reason', () => {
    api.cancel(REQUEST_A, 3).subscribe();
    const cancel = http.expectOne(`/api/requests/${REQUEST_A}/cancel`);
    expect(cancel.request.method).toBe('POST');
    expect(cancel.request.body).toEqual({ version: 3 });
    cancel.flush(requestView({ status: 'CANCELLED', version: 4 }));

    api.approve(REQUEST_A, 4).subscribe();
    const approve = http.expectOne(`/api/requests/${REQUEST_A}/approve`);
    expect(approve.request.method).toBe('POST');
    expect(approve.request.body).toEqual({ version: 4 });
    expect(approve.request.headers.has('Idempotency-Key')).toBe(false);
    approve.flush(requestView({ status: 'APPROVED', version: 5 }));

    api.reject(REQUEST_A, 5, 'Missing quotes').subscribe();
    const reject = http.expectOne(`/api/requests/${REQUEST_A}/reject`);
    expect(reject.request.method).toBe('POST');
    expect(reject.request.body).toEqual({ version: 5, reason: 'Missing quotes' });
    reject.flush(requestView({ status: 'REJECTED', version: 6 }));
  });

  it('accepts supported cent amounts across the JSON number range in detail responses', () => {
    for (const text of CENT_AMOUNT_TEXTS) {
      const amount = jsonAmount(text);
      const view = parseRequestView({ ...requestView(), amount });
      expect(view.amount).toBe(amount);
      // The accepted double serializes back to the exact two-decimal backend value.
      expect(JSON.stringify(view.amount)).toBe(text);
    }
  });

  it('accepts interior cent amounts in a page of summaries', () => {
    const texts = ['0.01', '9999999999.03', '999999999999.99'] as const;
    const page = parseRequestPage(requestPage(texts.map((text) => summary({ amount: jsonAmount(text) }))));
    expect(page.items.map((item) => JSON.stringify(item.amount))).toEqual([...texts]);
  });

  it('keeps a committed create response with an interior cent amount on the success path', () => {
    const amount = jsonAmount('9999999999.03');
    let created: RequestView | undefined;
    let failure: unknown;
    api.create({ title: 'Reconciliation', justification: 'Interior cent amount', amount }, KEY).subscribe({
      next: (value) => (created = value),
      error: (error: unknown) => (failure = error),
    });
    const request = http.expectOne('/api/requests');
    expect(request.request.body).toEqual({ title: 'Reconciliation', justification: 'Interior cent amount', amount });
    request.flush(JSON.parse(JSON.stringify(requestView({ amount }))));
    expect(failure).toBeUndefined();
    expect(created?.amount).toBe(amount);
    expect(JSON.stringify(created?.amount)).toBe('9999999999.03');
  });

  it('rejects unsafe amounts and invalid history/status payloads instead of rendering them', () => {
    const rejected: readonly unknown[] = [
      0,
      -0.01,
      0.001,
      1.001,
      1.005,
      1.0000000000000002,
      9999999999.031,
      1_000_000_000_000,
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
      '1.23',
      null,
      true,
    ];
    for (const amount of rejected) {
      expect(() => parseRequestView({ ...requestView(), amount }), `amount ${String(amount)}`).toThrow();
    }
    expect(() => parseRequestView(requestView({ status: 'PENDING' as never }))).toThrow();
    expect(() => parseRequestView(requestView({ currency: 'USD' }))).toThrow();
    expect(() => parseRequestPage({ page: 0, size: 20, total: 1, items: [{ id: 'not-a-uuid' }] })).toThrow();
    expect(() =>
      parseRequestHistory([historyEntry({ fromStatus: 'UNKNOWN', reason: 'No' })]),
    ).toThrow();
  });

  it('rejects over-precise or out-of-range amounts in page summaries', () => {
    for (const amount of [1.001, 9999999999.031, 1_000_000_000_000]) {
      expect(() => parseRequestPage(requestPage([summary({ amount })])), `amount ${String(amount)}`).toThrow();
    }
  });

  it('accepts a nullable rejection reason and null previous status in history', () => {
    const entries = parseRequestHistory([historyEntry({ fromStatus: null, reason: null })]);
    expect(entries[0]?.fromStatus).toBeNull();
    expect(entries[0]?.reason).toBeNull();
    expect(entries[0]?.actorIdentityId).toBe(IDENTITY_A);
    expect(entries[0]?.correlationId).toBe(CORRELATION_A);
  });

  it('generates a UUID retry key', () => {
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(newIdempotencyKey()).not.toBe(key);
  });

  it('requests the other request id without reusing a target', () => {
    api.detail(REQUEST_B).subscribe();
    http.expectOne(`/api/requests/${REQUEST_B}`).flush(requestView({ id: REQUEST_B }));
  });
});
