import { RequestHistoryEntry, RequestPage, RequestSummary, RequestView } from './request-api';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';

export const IDENTITY_A = 'aaaaaaaa-1111-4111-8111-111111111111';
export const IDENTITY_B = 'bbbbbbbb-1111-4111-8111-111111111111';
export const COMPANY_A = 'aaaaaaaa-2222-4222-8222-222222222222';
export const COMPANY_B = 'bbbbbbbb-2222-4222-8222-222222222222';
export const MEMBERSHIP_A = 'aaaaaaaa-3333-4333-8333-333333333333';
export const DEPARTMENT_A = 'aaaaaaaa-4444-4444-8444-444444444444';
export const DEPARTMENT_B = 'bbbbbbbb-4444-4444-8444-444444444444';
export const REQUEST_A = '11111111-1111-4111-8111-111111111111';
export const REQUEST_B = '22222222-2222-4222-8222-222222222222';
export const CORRELATION_A = '33333333-3333-4333-8333-333333333333';
export const HISTORY_A = '44444444-4444-4444-8444-444444444444';

export function profile(overrides: Partial<WorkspaceProfile> = {}): WorkspaceProfile {
  return {
    identityId: IDENTITY_A,
    membershipId: MEMBERSHIP_A,
    companyId: COMPANY_A,
    companyName: 'Larkvane',
    departmentId: DEPARTMENT_A,
    departmentName: 'engineering',
    version: 3,
    roles: ['EMPLOYEE'] as readonly WorkspaceRole[],
    managedDepartmentIds: [] as readonly string[],
    ...overrides,
  };
}

export function requestView(overrides: Partial<RequestView> = {}): RequestView {
  return {
    id: REQUEST_A,
    companyId: COMPANY_A,
    requesterIdentityId: IDENTITY_A,
    departmentId: DEPARTMENT_A,
    title: 'Laptop replacement',
    justification: 'The current laptop no longer holds a charge.',
    amount: 800,
    currency: 'EUR',
    status: 'DRAFT',
    version: 2,
    createdAt: '2026-10-18T09:00:00Z',
    updatedAt: '2026-10-18T09:05:00Z',
    submittedAt: null,
    departmentApproverIdentityId: null,
    correlationId: CORRELATION_A,
    approvedAt: null,
    budgetMonth: null,
    ...overrides,
  };
}

export function summary(overrides: Partial<RequestSummary> = {}): RequestSummary {
  const request = requestView(overrides as Partial<RequestView>);
  return {
    id: request.id,
    departmentId: request.departmentId,
    title: request.title,
    amount: request.amount,
    status: request.status,
    version: request.version,
    createdAt: request.createdAt,
  };
}

export function requestPage(
  items: readonly RequestSummary[],
  overrides: Partial<Omit<RequestPage, 'items'>> = {},
): RequestPage {
  return { page: 0, size: 20, total: items.length, items, ...overrides };
}

export function historyEntry(overrides: Partial<RequestHistoryEntry> = {}): RequestHistoryEntry {
  return {
    id: HISTORY_A,
    version: 1,
    action: 'request.created',
    fromStatus: null,
    toStatus: 'DRAFT',
    actorIdentityId: IDENTITY_A,
    reason: null,
    correlationId: CORRELATION_A,
    occurredAt: '2026-10-18T09:00:00Z',
    ...overrides,
  };
}
