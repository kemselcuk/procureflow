import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

/** Metadata-only company audit row from `GET /api/admin/audit`; references are plain identifiers. */
export interface AuditEntry {
  readonly id: string;
  readonly action: string;
  readonly entityId: string;
  readonly actorIdentityId: string;
  readonly actorMembershipId: string;
  readonly correlationId: string;
  readonly occurredAt: string;
}

/** Zero-based page envelope `{page, size, total, items}`; an out-of-range page can be empty. */
export interface AuditPageEnvelope {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly items: readonly AuditEntry[];
}

/** The fixed page size this UI requests. The backend accepts 1–100; 20 is only the UI default. */
export const AUDIT_PAGE_SIZE = 20;

/** Largest page size the frozen server contract accepts (inclusive). */
export const AUDIT_MAX_PAGE_SIZE = 100;

/** Request parameters the response envelope must echo back exactly. */
export interface AuditPageRequest {
  readonly page: number;
  readonly size: number;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value);
}

/** Accepts only strings a Date can actually parse, so the UI never formats an invalid date. */
function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && !Number.isNaN(Date.parse(value));
}

function parseEntry(value: unknown): AuditEntry | null {
  if (!isRecord(value)) return null;
  const id = value['id'];
  const action = value['action'];
  const entityId = value['entityId'];
  const actorIdentityId = value['actorIdentityId'];
  const actorMembershipId = value['actorMembershipId'];
  const correlationId = value['correlationId'];
  const occurredAt = value['occurredAt'];
  if (
    !isUuid(id) ||
    !isUuid(entityId) ||
    !isUuid(actorIdentityId) ||
    !isUuid(actorMembershipId) ||
    !isUuid(correlationId)
  ) {
    return null;
  }
  if (typeof action !== 'string' || action.length === 0) return null;
  if (!isTimestamp(occurredAt)) return null;
  // Only the exact seven contract fields cross the boundary; extra envelope, payload or identity
  // fields are dropped instead of being coerced or rendered.
  return { id, action, entityId, actorIdentityId, actorMembershipId, correlationId, occurredAt };
}

/** Throws when a successful response does not match the frozen audit page contract. */
export function parseAuditPage(body: unknown, request: AuditPageRequest): AuditPageEnvelope {
  if (!isRecord(body)) throw new Error('Unexpected audit page response');
  const page = body['page'];
  const size = body['size'];
  const total = body['total'];
  const rawItems = body['items'];
  if (
    !isCount(page) ||
    !isCount(size) ||
    !isCount(total) ||
    !Array.isArray(rawItems) ||
    size < 1 ||
    size > AUDIT_MAX_PAGE_SIZE
  ) {
    throw new Error('Unexpected audit page response');
  }
  // The server rewrites the request; a response that does not echo the exact requested page
  // and size cannot be attributed to this request, so it is rejected instead of shown.
  if (page !== request.page || size !== request.size) {
    throw new Error('Unexpected audit page response');
  }
  const items: AuditEntry[] = [];
  for (const raw of rawItems) {
    const entry = parseEntry(raw);
    if (entry === null) throw new Error('Unexpected audit page response');
    items.push(entry);
  }
  return { page, size, total, items };
}

/**
 * Typed read-only boundary for the frozen company-admin audit contract.
 *
 * The route is read-only: there is no write, export, mark-read or delete verb, no company or
 * arbitrary-identity parameter and no individual audit detail endpoint. Company scope plus the
 * current membership and role authority are decided by the backend on every request; the page
 * and size parameters below are the only request inputs this boundary can send.
 */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);

  list(page: number, size: number = AUDIT_PAGE_SIZE): Observable<AuditPageEnvelope> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<unknown>('/api/admin/audit', { params }).pipe(map((body) => parseAuditPage(body, { page, size })));
  }
}
