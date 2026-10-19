import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuditApi, AuditEntry, AuditPageEnvelope, parseAuditPage } from './audit-api';

const ID_A = '11111111-1111-4111-8111-111111111111';
const ENTITY_A = '22222222-2222-4222-8222-222222222222';
const ACTOR_IDENTITY_A = '33333333-3333-4333-8333-333333333333';
const ACTOR_MEMBERSHIP_A = '44444444-4444-4444-8444-444444444444';
const CORRELATION_A = '55555555-5555-4555-8555-555555555555';

const REQUEST = { page: 0, size: 20 } as const;

function entry(overrides: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id: ID_A,
    action: 'request.created',
    entityId: ENTITY_A,
    actorIdentityId: ACTOR_IDENTITY_A,
    actorMembershipId: ACTOR_MEMBERSHIP_A,
    correlationId: CORRELATION_A,
    occurredAt: '2026-10-19T09:00:00Z',
    ...overrides,
  };
}

function bodyWith(raw: unknown, remove?: keyof AuditEntry): Record<string, unknown> {
  const value: Record<string, unknown> = { ...(raw as Record<string, unknown>) };
  if (remove) delete value[remove];
  return { page: 0, size: 20, total: 1, items: [value] };
}

describe('Audit API boundary', () => {
  let http: HttpTestingController;
  let api: AuditApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AuditApi);
  });

  afterEach(() => http.verify());

  it('requests the frozen read-only audit route with only page and size and parses the envelope', () => {
    let received: AuditPageEnvelope | undefined;
    api.list(2, 20).subscribe((page) => (received = page));
    const requests = http.match(() => true);
    // Exactly one read request: the boundary issues no follow-up mutation call.
    expect(requests.length).toBe(1);
    const request = requests[0]!;
    expect(request.request.method).toBe('GET');
    expect(request.request.url).toBe('/api/admin/audit');
    expect(request.request.body).toBeNull();
    expect(request.request.params.keys().sort()).toEqual(['page', 'size']);
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ page: 2, size: 20, total: 41, items: [entry()] });
    expect(received?.page).toBe(2);
    expect(received?.total).toBe(41);
    expect(received?.items.length).toBe(1);
    // The read boundary issues no follow-up mutation request.
    expect(http.match(() => true).length).toBe(0);
  });

  it('defaults to the fixed contract page size', () => {
    api.list(0).subscribe();
    const request = http.expectOne((candidate) => candidate.url === '/api/admin/audit');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ page: 0, size: 20, total: 0, items: [] });
  });

  it('keeps only the exact seven contract fields and never surfaces extra payload fields', () => {
    let received: AuditPageEnvelope | undefined;
    api.list(0, 20).subscribe((page) => (received = page));
    http
      .expectOne((candidate) => candidate.url === '/api/admin/audit')
      .flush({
        page: 0,
        size: 20,
        total: 1,
        payload: 'envelope payload must be dropped',
        items: [
          {
            ...entry(),
            title: 'Private laptop title',
            justification: 'Confidential justification',
            email: 'person@demo.invalid',
            amount: '1200.00',
            issuer: 'issuer-claim',
            token: 'token-claim',
          },
        ],
      });
    const parsed = received?.items[0];
    expect(parsed).toBeDefined();
    expect(Object.keys(parsed as AuditEntry)).toEqual([
      'id',
      'action',
      'entityId',
      'actorIdentityId',
      'actorMembershipId',
      'correlationId',
      'occurredAt',
    ]);
    const serialized = JSON.stringify(received);
    for (const secret of ['envelope payload must be dropped', 'Private laptop title', 'Confidential justification', 'person@demo.invalid', 'issuer-claim', 'token-claim']) {
      expect(serialized).not.toContain(secret);
    }
  });

  it('accepts an out-of-range empty page with a nonzero total when it echoes the requested page', () => {
    expect(parseAuditPage({ page: 50, size: 20, total: 5, items: [] }, { page: 50, size: 20 })).toEqual({
      page: 50,
      size: 20,
      total: 5,
      items: [],
    });
  });

  it('accepts the maximum server page size of 100 and rejects 101 or 0 without coercion', () => {
    expect(() => parseAuditPage({ page: 0, size: 101, total: 0, items: [] }, { page: 0, size: 101 })).toThrow();
    expect(() => parseAuditPage({ page: 0, size: 0, total: 0, items: [] }, { page: 0, size: 0 })).toThrow();
    expect(parseAuditPage({ page: 0, size: 100, total: 0, items: [] }, { page: 0, size: 100 })).toEqual({
      page: 0,
      size: 100,
      total: 0,
      items: [],
    });
  });

  it('rejects a response whose page or size does not match the requested API parameters', () => {
    expect(() => parseAuditPage({ page: 1, size: 20, total: 0, items: [] }, REQUEST)).toThrow();
    expect(() => parseAuditPage({ page: 0, size: 10, total: 0, items: [] }, REQUEST)).toThrow();
    expect(parseAuditPage({ page: 0, size: 20, total: 0, items: [] }, REQUEST)).toEqual({
      page: 0,
      size: 20,
      total: 0,
      items: [],
    });
  });

  it('errors instead of emitting when a successful response breaks the item contract', () => {
    let emitted = false;
    let failed = false;
    api.list(0, 20).subscribe({ next: () => (emitted = true), error: () => (failed = true) });
    http
      .expectOne((candidate) => candidate.url === '/api/admin/audit')
      .flush({ page: 0, size: 20, total: 1, items: [{ id: 'audit-1', action: 'request.created', email: 'person@demo.invalid' }] });
    expect(failed).toBe(true);
    expect(emitted).toBe(false);
  });

  it('rejects a runtime envelope with a mismatched page, a zero size or a size above the server maximum', () => {
    let emitted = false;
    let failures = 0;
    api.list(2, 20).subscribe({ next: () => (emitted = true), error: () => (failures += 1) });
    http.expectOne((candidate) => candidate.url === '/api/admin/audit').flush({ page: 1, size: 20, total: 0, items: [] });

    api.list(0, 20).subscribe({ next: () => (emitted = true), error: () => (failures += 1) });
    http.expectOne((candidate) => candidate.url === '/api/admin/audit').flush({ page: 0, size: 0, total: 0, items: [] });

    api.list(0, 20).subscribe({ next: () => (emitted = true), error: () => (failures += 1) });
    http.expectOne((candidate) => candidate.url === '/api/admin/audit').flush({ page: 0, size: 101, total: 0, items: [] });

    expect(emitted).toBe(false);
    expect(failures).toBe(3);
  });

  it('accepts a runtime out-of-range empty page that echoes the request', () => {
    let received: AuditPageEnvelope | undefined;
    api.list(50, 20).subscribe((page) => (received = page));
    http
      .expectOne((candidate) => candidate.url === '/api/admin/audit')
      .flush({ page: 50, size: 20, total: 5, items: [] });
    expect(received).toEqual({ page: 50, size: 20, total: 5, items: [] });
  });

  it.each([
    ['a non-object body', null],
    ['an array body', []],
    ['a non-array items value', { page: 0, size: 20, total: 0, items: {} }],
    ['a negative page', { page: -1, size: 20, total: 0, items: [] }],
    ['a string page that could be coerced', { page: '0', size: 20, total: 0, items: [] }],
    ['a fractional total', { page: 0, size: 20, total: 1.5, items: [entry()] }],
    ['a string total', { page: 0, size: 20, total: '3', items: [entry()] }],
    ['a boolean size', { page: 0, size: true, total: 0, items: [] }],
    ['a missing action', bodyWith(entry(), 'action')],
    ['an empty action', bodyWith(entry({ action: '' }))],
    ['a non-UUID audit id', bodyWith(entry({ id: 'audit-1' }))],
    ['a non-UUID entity id', bodyWith(entry({ entityId: 'request-1' }))],
    ['a non-UUID actor identity', bodyWith(entry({ actorIdentityId: 'identity-1' }))],
    ['a non-UUID actor membership', bodyWith(entry({ actorMembershipId: 'membership-1' }))],
    ['a missing correlation id', bodyWith(entry(), 'correlationId')],
    ['a missing occurredAt', bodyWith(entry(), 'occurredAt')],
    ['an unparseable occurredAt', bodyWith(entry({ occurredAt: 'not-a-time' }))],
    ['a numeric occurredAt', bodyWith(entry({ occurredAt: 1700000000000 as unknown as string }))],
    ['a null item', { page: 0, size: 20, total: 1, items: [null] }],
  ])('rejects %s', (_label, body) => {
    expect(() => parseAuditPage(body, REQUEST)).toThrow();
  });
});
