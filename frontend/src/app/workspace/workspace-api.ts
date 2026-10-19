import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/** Roles exposed by the frozen `GET /api/me` membership contract. */
export const WORKSPACE_ROLES = ['EMPLOYEE', 'DEPARTMENT_MANAGER', 'FINANCE_APPROVER', 'COMPANY_ADMIN'] as const;

export type WorkspaceRole = (typeof WORKSPACE_ROLES)[number];

/**
 * Authenticated membership snapshot from `GET /api/me`.
 *
 * The central Authentication service intentionally keeps the smaller profile used by the
 * sign-in boundary. Screens that need the current department or manager assignments read this
 * scoped representation so the private token lifecycle remains untouched.
 */
export interface WorkspaceProfile {
  readonly identityId: string;
  readonly membershipId: string;
  readonly companyId: string;
  readonly companyName: string;
  readonly departmentId: string;
  readonly departmentName: string;
  readonly version: number;
  readonly roles: readonly WorkspaceRole[];
  readonly managedDepartmentIds: readonly string[];
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value);
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isRole(value: unknown): value is WorkspaceRole {
  return typeof value === 'string' && (WORKSPACE_ROLES as readonly string[]).includes(value);
}

function uniqueUuids(value: unknown): string[] | null {
  if (!Array.isArray(value) || !value.every(isUuid)) return null;
  const ids = [...value];
  return new Set(ids).size === ids.length ? ids : null;
}

/** Strictly validates the membership fields this workspace relies on; unknown extras are dropped. */
export function parseWorkspaceProfile(body: unknown): WorkspaceProfile {
  if (!isRecord(body)) throw new Error('Unexpected membership response');
  const identityId = body['identityId'];
  const membershipId = body['membershipId'];
  const companyId = body['companyId'];
  const departmentId = body['departmentId'];
  const companyName = body['companyName'];
  const departmentName = body['departmentName'];
  const version = body['version'];
  const roles = body['roles'];
  const managed = body['managedDepartmentIds'];
  if (
    !isUuid(identityId) ||
    !isUuid(membershipId) ||
    !isUuid(companyId) ||
    !isUuid(departmentId) ||
    !isText(companyName) ||
    !isText(departmentName) ||
    !isCount(version) ||
    !Array.isArray(roles) ||
    !roles.every(isRole) ||
    new Set(roles).size !== roles.length
  ) {
    throw new Error('Unexpected membership response');
  }
  const managedDepartmentIds = uniqueUuids(managed);
  if (managedDepartmentIds === null) throw new Error('Unexpected membership response');
  return {
    identityId,
    membershipId,
    companyId,
    companyName,
    departmentName,
    departmentId,
    version,
    roles: [...roles] as WorkspaceRole[],
    managedDepartmentIds,
  };
}

/** Reads only the current membership view; company scope always comes from the bearer token. */
@Injectable({ providedIn: 'root' })
export class WorkspaceApi {
  private readonly http = inject(HttpClient);

  loadProfile(): Observable<WorkspaceProfile> {
    return this.http.get<unknown>('/api/me').pipe(map(parseWorkspaceProfile));
  }
}
