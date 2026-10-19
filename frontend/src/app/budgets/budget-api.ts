import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MONTH = /^([0-9]{4})-([0-9]{2})$/;
const CALENDAR_MONTH = /^([0-9]{4})-([0-9]{2})-01$/;

/** One `GET /api/budgets?month=YYYY-MM` row. Amounts and remaining are backend-calculated. */
export interface BudgetView {
  readonly id: string;
  readonly departmentId: string;
  readonly month: string;
  readonly limitAmount: number;
  readonly usedAmount: number;
  readonly remainingAmount: number;
  readonly version: number;
}

/** One scoped row from `GET /api/departments`. */
export interface DepartmentView {
  readonly id: string;
  readonly name: string;
}

export interface SetLimitInput {
  readonly limitAmount: number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value);
}

function isAmount(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function isVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isCalendarMonth(value: unknown): value is string {
  const match = typeof value === 'string' ? CALENDAR_MONTH.exec(value) : null;
  if (match === null) return false;
  const month = Number(match[2]);
  return month >= 1 && month <= 12;
}

/** A supported backend query period: four-digit year 2000-9999 and month 01-12. */
export function parseBudgetMonth(value: string): string | null {
  const match = MONTH.exec(value.trim());
  if (match === null) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (year < 2000 || year > 9999 || month < 1 || month > 12) return null;
  return `${match[1]}-${match[2]}`;
}

export function currentUtcMonth(now: Date = new Date()): string {
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function parseBudgetView(body: unknown): BudgetView {
  if (!isRecord(body)) throw new Error('Unexpected budget response');
  const id = body['id'];
  const departmentId = body['departmentId'];
  const month = body['month'];
  const limitAmount = body['limitAmount'];
  const usedAmount = body['usedAmount'];
  const remainingAmount = body['remainingAmount'];
  const version = body['version'];
  if (!isUuid(id) || !isUuid(departmentId) || !isCalendarMonth(month)) {
    throw new Error('Unexpected budget response');
  }
  if (!isAmount(limitAmount) || !isAmount(usedAmount) || !isAmount(remainingAmount) || !isVersion(version)) {
    throw new Error('Unexpected budget response');
  }
  return { id, departmentId, month, limitAmount, usedAmount, remainingAmount, version };
}

export function parseBudgetList(body: unknown): readonly BudgetView[] {
  if (!Array.isArray(body)) throw new Error('Unexpected budgets response');
  return body.map(parseBudgetView);
}

export function parseDepartmentList(body: unknown): readonly DepartmentView[] {
  if (!Array.isArray(body)) throw new Error('Unexpected departments response');
  return body.map((raw: unknown) => {
    if (!isRecord(raw) || !isUuid(raw['id'])) throw new Error('Unexpected departments response');
    const name = raw['name'];
    if (typeof name !== 'string' || name.trim().length === 0 || name.length > 200) {
      throw new Error('Unexpected departments response');
    }
    return { id: raw['id'], name };
  });
}

/** Returns only a whitelisted problem code; never server detail/title text. */
export function budgetErrorCode(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse) || !isRecord(error.error)) return null;
  const code = error.error['code'];
  return typeof code === 'string' && /^[a-z_]{1,64}$/.test(code) ? code : null;
}

export function budgetErrorStatus(error: unknown): number | null {
  return error instanceof HttpErrorResponse ? error.status : null;
}

/**
 * Typed boundary for the frozen budget/directory contracts. Company scope is never sent;
 * the backend derives it from the validated member actor.
 */
@Injectable({ providedIn: 'root' })
export class BudgetApi {
  private readonly http = inject(HttpClient);

  list(month: string): Observable<readonly BudgetView[]> {
    const params = new HttpParams().set('month', month);
    return this.http.get<unknown>('/api/budgets', { params }).pipe(map(parseBudgetList));
  }

  departments(): Observable<readonly DepartmentView[]> {
    return this.http.get<unknown>('/api/departments').pipe(map(parseDepartmentList));
  }

  setLimit(departmentId: string, month: string, limitAmount: number): Observable<BudgetView> {
    const body: SetLimitInput = { limitAmount };
    return this.http
      .put<unknown>(
        `/api/admin/departments/${encodeURIComponent(departmentId)}/budgets/${encodeURIComponent(month)}`,
        body,
      )
      .pipe(map(parseBudgetView));
  }
}
