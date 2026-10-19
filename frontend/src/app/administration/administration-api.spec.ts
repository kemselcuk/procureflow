import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  AdministrationApi,
  DepartmentView,
  MembershipView,
  ROLES,
  adminErrorCode,
  adminErrorStatus,
  parseMemberships,
} from './administration-api';

const MEMBERSHIP_ID = '11111111-1111-4111-8111-111111111111';
const IDENTITY_ID = '22222222-2222-4222-8222-222222222222';
const DEPARTMENT_ID = '33333333-3333-4333-8333-333333333333';
const MANAGED_ID = '44444444-4444-4444-8444-444444444444';

function department(overrides: Partial<DepartmentView> = {}): DepartmentView {
  return { id: DEPARTMENT_ID, name: 'Engineering', ...overrides };
}

function membership(overrides: Partial<MembershipView> = {}): MembershipView {
  return {
    id: MEMBERSHIP_ID,
    identityId: IDENTITY_ID,
    departmentId: DEPARTMENT_ID,
    active: true,
    version: 2,
    roles: ['EMPLOYEE', 'DEPARTMENT_MANAGER'],
    managedDepartmentIds: [MANAGED_ID],
    ...overrides,
  };
}

describe('Administration API boundary', () => {
  let http: HttpTestingController;
  let api: AdministrationApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AdministrationApi);
  });

  afterEach(() => http.verify());

  it('reads the scoped department and admin membership paths without a company selector', () => {
    let departments: readonly DepartmentView[] | undefined;
    let memberships: readonly MembershipView[] | undefined;
    api.departments().subscribe((value) => (departments = value));
    api.memberships().subscribe((value) => (memberships = value));

    const departmentRequest = http.expectOne('/api/departments');
    const membershipRequest = http.expectOne('/api/admin/memberships');
    expect(departmentRequest.request.method).toBe('GET');
    expect(membershipRequest.request.method).toBe('GET');
    expect(departmentRequest.request.params.has('companyId')).toBe(false);
    expect(membershipRequest.request.params.has('companyId')).toBe(false);
    departmentRequest.flush([department()]);
    membershipRequest.flush([membership()]);
    expect(departments).toEqual([department()]);
    expect(memberships).toEqual([membership()]);
  });

  it('creates a department with exactly the name body and parses the 201 response', () => {
    let received: DepartmentView | undefined;
    api.createDepartment('Operations').subscribe((value) => (received = value));
    const request = http.expectOne('/api/admin/departments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ name: 'Operations' });
    request.flush(department({ name: 'Operations' }), { status: 201, statusText: 'Created' });
    expect(received?.name).toBe('Operations');
  });

  it('links an already registered identity with exact UUIDs, roles and managed assignments', () => {
    let received: MembershipView | undefined;
    api
      .createMembership({
        identityId: IDENTITY_ID,
        departmentId: DEPARTMENT_ID,
        roles: ['EMPLOYEE', 'DEPARTMENT_MANAGER'],
        managedDepartmentIds: [MANAGED_ID],
      })
      .subscribe((value) => (received = value));
    const request = http.expectOne('/api/admin/memberships');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      identityId: IDENTITY_ID,
      departmentId: DEPARTMENT_ID,
      roles: ['EMPLOYEE', 'DEPARTMENT_MANAGER'],
      managedDepartmentIds: [MANAGED_ID],
    });
    expect(request.request.body).not.toHaveProperty('companyId');
    expect(request.request.body).not.toHaveProperty('email');
    request.flush(membership(), { status: 201, statusText: 'Created' });
    expect(received).toEqual(membership());
  });

  it('updates a membership with the selected canonical version and no identity or company field', () => {
    let received: MembershipView | undefined;
    api
      .updateMembership(MEMBERSHIP_ID, {
        departmentId: DEPARTMENT_ID,
        active: false,
        version: 7,
        roles: ['EMPLOYEE'],
        managedDepartmentIds: [],
      })
      .subscribe((value) => (received = value));
    const request = http.expectOne(`/api/admin/memberships/${MEMBERSHIP_ID}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      departmentId: DEPARTMENT_ID,
      active: false,
      version: 7,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    });
    expect(request.request.body).not.toHaveProperty('identityId');
    expect(request.request.body).not.toHaveProperty('companyId');
    request.flush(membership({ active: false, version: 8, roles: ['EMPLOYEE'], managedDepartmentIds: [] }));
    expect(received?.version).toBe(8);
  });

  it.each([
    ['an unknown role', [membership({ roles: ['OWNER' as never] })]],
    ['duplicate roles', [membership({ roles: ['EMPLOYEE', 'EMPLOYEE'] })]],
    ['no roles', [membership({ roles: [] })]],
    ['a non-UUID managed department', [membership({ managedDepartmentIds: ['operations'] })]],
    ['a negative version', [membership({ version: -1 })]],
    ['a non-boolean active flag', [{ ...membership(), active: 'yes' }]],
    ['a non-array body', { items: [] }],
  ])('rejects %s instead of rendering untyped membership data', (_label, body) => {
    expect(() => parseMemberships(body)).toThrow();
  });

  it('accepts every exact backend role and orders them canonically', () => {
    expect(parseMemberships([membership({ roles: [...ROLES] })])[0]?.roles).toEqual(ROLES);
    expect(() => parseMemberships([membership({ roles: ['EMPLOYEE', 'FINANCE_APPROVER'] })])).not.toThrow();
  });

  it('extracts only a whitelisted problem code from an HttpErrorResponse', () => {
    const response = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { status: 409, code: 'stale_membership', detail: '<b>raw</b>' },
    });
    expect(adminErrorCode(response)).toBe('stale_membership');
    expect(adminErrorStatus(response)).toBe(409);
    const unsafe = new HttpErrorResponse({ status: 500, error: { code: '<script>' } });
    expect(adminErrorCode(unsafe)).toBeNull();
    expect(adminErrorCode(new Error('offline'))).toBeNull();
  });
});
