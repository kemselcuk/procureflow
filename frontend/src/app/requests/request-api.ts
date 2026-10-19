import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export type RequestStatus =
  | 'DRAFT'
  | 'AWAITING_DEPARTMENT_APPROVAL'
  | 'AWAITING_FINANCE_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED';

export const REQUEST_STATUSES: readonly RequestStatus[] = [
  'DRAFT',
  'AWAITING_DEPARTMENT_APPROVAL',
  'AWAITING_FINANCE_APPROVAL',
  'APPROVED',
  'REJECTED',
  'CANCELLED',
];

/** Exact `RequestData.View` response contract. */
export interface RequestView {
  readonly id: string;
  readonly companyId: string;
  readonly requesterIdentityId: string;
  readonly departmentId: string;
  readonly title: string;
  readonly justification: string;
  readonly amount: number;
  readonly currency: string;
  readonly status: RequestStatus;
  readonly version: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly submittedAt: string | null;
  readonly departmentApproverIdentityId: string | null;
  readonly correlationId: string;
  readonly approvedAt: string | null;
  readonly budgetMonth: string | null;
}

/** Exact `RequestData.Summary` row contract. */
export interface RequestSummary {
  readonly id: string;
  readonly departmentId: string;
  readonly title: string;
  readonly amount: number;
  readonly status: RequestStatus;
  readonly version: number;
  readonly createdAt: string;
}

/** Exact zero-based `RequestData.Page` envelope. */
export interface RequestPage {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly items: readonly RequestSummary[];
}

/** Exact `RequestData.History` row contract. */
export interface RequestHistoryEntry {
  readonly id: string;
  readonly version: number;
  readonly action: string;
  readonly fromStatus: string | null;
  readonly toStatus: string;
  readonly actorIdentityId: string;
  readonly reason: string | null;
  readonly correlationId: string;
  readonly occurredAt: string;
}

export interface CreateRequestInput {
  readonly title: string;
  readonly justification: string;
  readonly amount: number;
}

export interface EditRequestInput extends CreateRequestInput {
  readonly version: number;
}

export interface RequestListQuery {
  readonly view: 'mine' | 'inbox';
  readonly page: number;
  readonly size: number;
  readonly status?: RequestStatus | null;
}

/** The server supports 12 integer digits with at most two decimals. */
export const MAX_REQUEST_AMOUNT = 999_999_999_999.99;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MONTH = /^\d{4}-(?:0[1-9]|1[0-2])$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value);
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && !Number.isNaN(Date.parse(value));
}

function isNullableInstant(value: unknown): value is string | null {
  return value === null || isInstant(value);
}

/**
 * JSON BigDecimal arrives as a number. Accept only a positive value whose shortest round-trip
 * decimal text is itself a two-decimal amount inside the server range.
 *
 * Reconstructing exact cents avoids a fixed epsilon on `value * 100`: that epsilon both rejected
 * valid large cent values (for example 9999999999.03, whose product is 999999999903.0001) and
 * admitted genuinely over-precise doubles such as 1.0000000000000002. Dividing the exact cents by
 * 100 proves the input is the correctly rounded double for that decimal amount. JSON.parse has
 * already collapsed any extra decimal digits; that lost information cannot be recovered, but every
 * supported cent value maps uniquely into this range and round-trips through this check.
 */
function isAmount(value: unknown): value is number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || value > MAX_REQUEST_AMOUNT) {
    return false;
  }
  const text = String(value);
  const match = /^(\d{1,12})(?:\.(\d{1,2}))?$/.exec(text);
  if (match === null) return false;
  const cents = BigInt(match[1]!) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'));
  if (cents <= 0n || cents > 99_999_999_999_999n) return false;
  const exactCents = Number(cents);
  return Number.isSafeInteger(exactCents) && exactCents / 100 === value;
}

function isStatus(value: unknown): value is RequestStatus {
  return typeof value === 'string' && (REQUEST_STATUSES as readonly string[]).includes(value);
}

function isBudgetMonth(value: unknown): value is string {
  return typeof value === 'string' && MONTH.test(value);
}

export function parseRequestView(body: unknown): RequestView {
  if (!isRecord(body)) throw new Error('Unexpected request response');
  const id = body['id'];
  const companyId = body['companyId'];
  const requesterIdentityId = body['requesterIdentityId'];
  const departmentId = body['departmentId'];
  const title = body['title'];
  const justification = body['justification'];
  const amount = body['amount'];
  const currency = body['currency'];
  const status = body['status'];
  const version = body['version'];
  const createdAt = body['createdAt'];
  const updatedAt = body['updatedAt'];
  const submittedAt = body['submittedAt'];
  const departmentApproverIdentityId = body['departmentApproverIdentityId'];
  const correlationId = body['correlationId'];
  const approvedAt = body['approvedAt'];
  const budgetMonth = body['budgetMonth'];
  if (
    !isUuid(id) ||
    !isUuid(companyId) ||
    !isUuid(requesterIdentityId) ||
    !isUuid(departmentId) ||
    !isText(title) ||
    typeof justification !== 'string' ||
    !isAmount(amount) ||
    currency !== 'EUR' ||
    !isStatus(status) ||
    !isCount(version) ||
    !isInstant(createdAt) ||
    !isInstant(updatedAt) ||
    !isNullableInstant(submittedAt) ||
    !(departmentApproverIdentityId === null || isUuid(departmentApproverIdentityId)) ||
    !isUuid(correlationId) ||
    !isNullableInstant(approvedAt) ||
    !(budgetMonth === null || isBudgetMonth(budgetMonth))
  ) {
    throw new Error('Unexpected request response');
  }
  return {
    id,
    companyId,
    requesterIdentityId,
    departmentId,
    title,
    justification,
    amount,
    currency,
    status,
    version,
    createdAt,
    updatedAt,
    submittedAt,
    departmentApproverIdentityId,
    correlationId,
    approvedAt,
    budgetMonth,
  };
}

export function parseRequestSummary(body: unknown): RequestSummary {
  if (!isRecord(body)) throw new Error('Unexpected request list response');
  const id = body['id'];
  const departmentId = body['departmentId'];
  const title = body['title'];
  const amount = body['amount'];
  const status = body['status'];
  const version = body['version'];
  const createdAt = body['createdAt'];
  if (
    !isUuid(id) ||
    !isUuid(departmentId) ||
    !isText(title) ||
    !isAmount(amount) ||
    !isStatus(status) ||
    !isCount(version) ||
    !isInstant(createdAt)
  ) {
    throw new Error('Unexpected request list response');
  }
  return { id, departmentId, title, amount, status, version, createdAt };
}

export function parseRequestPage(body: unknown): RequestPage {
  if (!isRecord(body)) throw new Error('Unexpected request list response');
  const page = body['page'];
  const size = body['size'];
  const total = body['total'];
  const items = body['items'];
  if (!isCount(page) || !isCount(size) || !isCount(total) || !Array.isArray(items)) {
    throw new Error('Unexpected request list response');
  }
  return { page, size, total, items: items.map(parseRequestSummary) };
}

export function parseRequestHistory(body: unknown): RequestHistoryEntry[] {
  if (!Array.isArray(body)) throw new Error('Unexpected request history response');
  return body.map((raw): RequestHistoryEntry => {
    if (!isRecord(raw)) throw new Error('Unexpected request history response');
    const id = raw['id'];
    const version = raw['version'];
    const action = raw['action'];
    const fromStatus = raw['fromStatus'];
    const toStatus = raw['toStatus'];
    const actorIdentityId = raw['actorIdentityId'];
    const reason = raw['reason'];
    const correlationId = raw['correlationId'];
    const occurredAt = raw['occurredAt'];
    if (
      !isUuid(id) ||
      !isCount(version) ||
      !isText(action) ||
      !(fromStatus === null || isStatus(fromStatus)) ||
      !isStatus(toStatus) ||
      !isUuid(actorIdentityId) ||
      !(reason === null || typeof reason === 'string') ||
      !isUuid(correlationId) ||
      !isInstant(occurredAt)
    ) {
      throw new Error('Unexpected request history response');
    }
    return { id, version, action, fromStatus, toStatus, actorIdentityId, reason, correlationId, occurredAt };
  });
}

/** UUID v4 idempotency key for create/submit; the caller owns its retry lifetime. */
export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID();
  const bytes = new Uint8Array(16);
  cryptoApi.getRandomValues(bytes);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Typed boundary for the frozen P2 request REST contract.
 *
 * Company, department, status and role scope are never sent by this client. The bearer token and
 * the backend's current membership decide scope; this service only carries validated UI input.
 */
@Injectable({ providedIn: 'root' })
export class RequestApi {
  private readonly http = inject(HttpClient);

  list(query: RequestListQuery): Observable<RequestPage> {
    let params = new HttpParams()
      .set('view', query.view)
      .set('page', String(query.page))
      .set('size', String(query.size));
    if (query.status) params = params.set('status', query.status);
    return this.http.get<unknown>('/api/requests', { params }).pipe(map(parseRequestPage));
  }

  detail(id: string): Observable<RequestView> {
    return this.http.get<unknown>(`/api/requests/${encodeURIComponent(id)}`).pipe(map(parseRequestView));
  }

  history(id: string): Observable<readonly RequestHistoryEntry[]> {
    return this.http.get<unknown>(`/api/requests/${encodeURIComponent(id)}/history`).pipe(map(parseRequestHistory));
  }

  create(input: CreateRequestInput, key: string): Observable<RequestView> {
    return this.http
      .post<unknown>('/api/requests', input, { headers: idempotencyHeaders(key) })
      .pipe(map(parseRequestView));
  }

  edit(id: string, input: EditRequestInput): Observable<RequestView> {
    return this.http
      .put<unknown>(`/api/requests/${encodeURIComponent(id)}`, input)
      .pipe(map(parseRequestView));
  }

  submit(id: string, version: number, key: string): Observable<RequestView> {
    return this.http
      .post<unknown>(`/api/requests/${encodeURIComponent(id)}/submit`, { version }, { headers: idempotencyHeaders(key) })
      .pipe(map(parseRequestView));
  }

  cancel(id: string, version: number): Observable<RequestView> {
    return this.http
      .post<unknown>(`/api/requests/${encodeURIComponent(id)}/cancel`, { version })
      .pipe(map(parseRequestView));
  }

  approve(id: string, version: number): Observable<RequestView> {
    return this.http
      .post<unknown>(`/api/requests/${encodeURIComponent(id)}/approve`, { version })
      .pipe(map(parseRequestView));
  }

  reject(id: string, version: number, reason: string): Observable<RequestView> {
    return this.http
      .post<unknown>(`/api/requests/${encodeURIComponent(id)}/reject`, { version, reason })
      .pipe(map(parseRequestView));
  }
}

function idempotencyHeaders(key: string): HttpHeaders {
  return new HttpHeaders({ 'Idempotency-Key': key });
}
