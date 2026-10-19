import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorkspaceApi, WorkspaceProfile, parseWorkspaceProfile } from './workspace-api';
import { IDENTITY_A, MEMBERSHIP_A, COMPANY_A, DEPARTMENT_A } from '../requests/request-fixtures';

const MANAGEMENT_ID = 'aaaaaaaa-5555-4555-8555-555555555555';

function body(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    identityId: IDENTITY_A,
    membershipId: MEMBERSHIP_A,
    companyId: COMPANY_A,
    companyName: 'Larkvane',
    departmentId: DEPARTMENT_A,
    departmentName: 'engineering',
    version: 3,
    roles: ['EMPLOYEE', 'DEPARTMENT_MANAGER'],
    managedDepartmentIds: [DEPARTMENT_A, MANAGEMENT_ID],
    ...overrides,
  };
}

describe('Workspace API boundary', () => {
  let http: HttpTestingController;
  let api: WorkspaceApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(WorkspaceApi);
  });

  afterEach(() => http.verify());

  it('reads the current membership from /api/me and keeps only the typed fields', () => {
    let received: WorkspaceProfile | undefined;
    api.loadProfile().subscribe((value) => (received = value));
    const request = http.expectOne('/api/me');
    expect(request.request.method).toBe('GET');
    request.flush(body({ active: true, email: 'private@example.invalid' }));
    expect(received?.identityId).toBe(IDENTITY_A);
    expect(received?.membershipId).toBe(MEMBERSHIP_A);
    expect(received?.roles).toEqual(['EMPLOYEE', 'DEPARTMENT_MANAGER']);
    expect(received?.managedDepartmentIds).toEqual([DEPARTMENT_A, MANAGEMENT_ID]);
    expect(Object.keys(received as WorkspaceProfile)).toEqual([
      'identityId',
      'membershipId',
      'companyId',
      'companyName',
      'departmentName',
      'departmentId',
      'version',
      'roles',
      'managedDepartmentIds',
    ]);
  });

  it.each([
    ['a missing membership id', body({ membershipId: undefined })],
    ['a non-UUID department id', body({ departmentId: 'engineering' })],
    ['a negative membership version', body({ version: -1 })],
    ['an unknown role', body({ roles: ['SUPER_ADMIN'] })],
    ['a duplicate role', body({ roles: ['EMPLOYEE', 'EMPLOYEE'] })],
    ['a missing managed department list', body({ managedDepartmentIds: undefined })],
    ['a non-UUID managed department', body({ managedDepartmentIds: ['all'] })],
  ])('rejects %s', (_label, invalid) => {
    expect(() => parseWorkspaceProfile(invalid)).toThrow();
  });
});
