import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

/** Metadata-only in-app notification row from `GET /api/notifications`. */
export interface NotificationItem {
  readonly id: string;
  readonly eventId: string;
  readonly eventType: string;
  readonly requestId: string;
  readonly correlationId: string;
  readonly occurredAt: string;
  readonly createdAt: string;
  readonly readAt: string | null;
}

/** Zero-based page envelope: `{page, size, total, items}`. */
export interface NotificationPage {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly items: readonly NotificationItem[];
}

/** `POST /api/notifications/{id}/read` response: `{id, readAt}` with the stable first read time. */
export interface NotificationReadReceipt {
  readonly id: string;
  readonly readAt: string;
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

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && !Number.isNaN(Date.parse(value));
}

function parseItem(value: unknown): NotificationItem | null {
  if (!isRecord(value)) return null;
  const id = value['id'];
  const eventId = value['eventId'];
  const eventType = value['eventType'];
  const requestId = value['requestId'];
  const correlationId = value['correlationId'];
  const occurredAt = value['occurredAt'];
  const createdAt = value['createdAt'];
  const readAt = value['readAt'];
  if (!isUuid(id) || !isUuid(eventId) || !isUuid(requestId) || !isUuid(correlationId)) return null;
  if (typeof eventType !== 'string' || eventType.length === 0) return null;
  if (!isTimestamp(occurredAt) || !isTimestamp(createdAt)) return null;
  if (readAt !== null && !isTimestamp(readAt)) return null;
  // Only the exact contract fields cross the boundary; envelope/recipient extras are dropped.
  return { id, eventId, eventType, requestId, correlationId, occurredAt, createdAt, readAt: readAt };
}

/** Throws when the response does not match the frozen list contract. */
export function parseNotificationPage(body: unknown): NotificationPage {
  if (!isRecord(body)) throw new Error('Unexpected notifications list response');
  const page = body['page'];
  const size = body['size'];
  const total = body['total'];
  const rawItems = body['items'];
  if (!isCount(page) || !isCount(size) || !isCount(total) || !Array.isArray(rawItems)) {
    throw new Error('Unexpected notifications list response');
  }
  const items: NotificationItem[] = [];
  for (const raw of rawItems) {
    const item = parseItem(raw);
    if (item === null) throw new Error('Unexpected notifications list response');
    items.push(item);
  }
  return { page, size, total, items };
}

/** Throws when the read receipt is missing, mismatched with the requested id or not a timestamp. */
export function parseNotificationReadReceipt(body: unknown, expectedId: string): NotificationReadReceipt {
  if (!isRecord(body) || body['id'] !== expectedId) throw new Error('Unexpected notification read response');
  const readAt = body['readAt'];
  if (!isTimestamp(readAt)) throw new Error('Unexpected notification read response');
  return { id: expectedId, readAt };
}

/**
 * Typed boundary for the frozen P4 notification contract.
 *
 * The backend routes exist in the accepted notification API baseline. Panel integration against
 * the live local stack (real Keycloak + real notification API) is covered by separate browser
 * verification evidence; this slice's tests exercise the boundary through `HttpTestingController`
 * fixtures and do not by themselves prove delivery, the consumer/SMTP pipeline or broker recovery.
 */
@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);

  list(page: number, size: number): Observable<NotificationPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<unknown>('/api/notifications', { params }).pipe(map(parseNotificationPage));
  }

  markRead(id: string): Observable<NotificationReadReceipt> {
    return this.http
      .post<unknown>(`/api/notifications/${encodeURIComponent(id)}/read`, null)
      .pipe(map((body) => parseNotificationReadReceipt(body, id)));
  }
}
