import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const ROLES = ['EMPLOYEE', 'DEPARTMENT_MANAGER', 'FINANCE_APPROVER', 'COMPANY_ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export interface DepartmentView {
  readonly id: string;
  readonly name: string;
}

/** Admin membership row. The contract exposes identity/department UUIDs, never names or emails. */
export interface MembershipView {
  readonly id: string;
  readonly identityId: string;
  readonly departmentId: string;
  readonly active: boolean;
  readonly version: number;
  readonly roles: readonly Role[];
  readonly managedDepartmentIds: readonly string[];
}

export interface NewDepartmentInput {
  readonly name: string;
}

export interface NewMembershipInput {
  readonly identityId: string;
  readonly departmentId: string;
  readonly roles: readonly Role[];
  readonly managedDepartmentIds: readonly string[];
}

export interface UpdateMembershipInput {
  readonly departmentId: string;
  readonly active: boolean;
  readonly version: number;
  readonly roles: readonly Role[];
  readonly managedDepartmentIds: readonly string[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value);
}

function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

function roleList(value: unknown): readonly Role[] | null {
  if (!Array.isArray(value) || value.length === 0) return null;
  const roles = new Set<Role>();
  for (const raw of value) {
    if (!isRole(raw) || roles.has(raw)) return null;
    roles.add(raw);
  }
  return ROLES.filter((role) => roles.has(role));
}

function uuidList(value: unknown): readonly string[] | null {
  if (!Array.isArray(value)) return null;
  const ids = new Set<string>();
  for (const raw of value) {
    if (!isUuid(raw) || ids.has(raw)) return null;
    ids.add(raw);
  }
  return [...ids].sort();
}

function isVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

export function parseDepartment(body: unknown): DepartmentView {
  if (!isRecord(body) || !isUuid(body['id'])) throw new Error('Unexpected department response');
  const name = body['name'];
  if (typeof name !== 'string' || name.trim().length === 0 || name.length > 200) {
    throw new Error('Unexpected department response');
  }
  return { id: body['id'], name };
}

export function parseDepartments(body: unknown): readonly DepartmentView[] {
  if (!Array.isArray(body)) throw new Error('Unexpected departments response');
  return body.map(parseDepartment);
}

export function parseMembership(body: unknown): MembershipView {
  if (!isRecord(body)) throw new Error('Unexpected membership response');
  const id = body['id'];
  const identityId = body['identityId'];
  const departmentId = body['departmentId'];
  const active = body['active'];
  const version = body['version'];
  const roles = roleList(body['roles']);
  const managedDepartmentIds = uuidList(body['managedDepartmentIds']);
  if (!isUuid(id) || !isUuid(identityId) || !isUuid(departmentId) || typeof active !== 'boolean' || !isVersion(version)) {
    throw new Error('Unexpected membership response');
  }
  if (roles === null || managedDepartmentIds === null) throw new Error('Unexpected membership response');
  return { id, identityId, departmentId, active, version, roles, managedDepartmentIds };
}

export function parseMemberships(body: unknown): readonly MembershipView[] {
  if (!Array.isArray(body)) throw new Error('Unexpected memberships response');
  return body.map(parseMembership);
}

/** Returns only a whitelisted problem code; never server detail/title text. */
export function adminErrorCode(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse) || !isRecord(error.error)) return null;
  const code = error.error['code'];
  return typeof code === 'string' && /^[a-z_]{1,64}$/.test(code) ? code : null;
}

export function adminErrorStatus(error: unknown): number | null {
  return error instanceof HttpErrorResponse ? error.status : null;
}

/** Typed company-administration boundary. No company, identity directory or account API is used. */
@Injectable({ providedIn: 'root' })
export class AdministrationApi {
  private readonly http = inject(HttpClient);

  departments(): Observable<readonly DepartmentView[]> {
    return this.http.get<unknown>('/api/departments').pipe(map(parseDepartments));
  }

  memberships(): Observable<readonly MembershipView[]> {
    return this.http.get<unknown>('/api/admin/memberships').pipe(map(parseMemberships));
  }

  createDepartment(name: string): Observable<DepartmentView> {
    const body: NewDepartmentInput = { name };
    return this.http.post<unknown>('/api/admin/departments', body).pipe(map(parseDepartment));
  }

  createMembership(input: NewMembershipInput): Observable<MembershipView> {
    const body: NewMembershipInput = {
      identityId: input.identityId,
      departmentId: input.departmentId,
      roles: [...input.roles],
      managedDepartmentIds: [...input.managedDepartmentIds],
    };
    return this.http.post<unknown>('/api/admin/memberships', body).pipe(map(parseMembership));
  }

  updateMembership(id: string, input: UpdateMembershipInput): Observable<MembershipView> {
    const body: UpdateMembershipInput = {
      departmentId: input.departmentId,
      active: input.active,
      version: input.version,
      roles: [...input.roles],
      managedDepartmentIds: [...input.managedDepartmentIds],
    };
    return this.http
      .put<unknown>(`/api/admin/memberships/${encodeURIComponent(id)}`, body)
      .pipe(map(parseMembership));
  }
}
