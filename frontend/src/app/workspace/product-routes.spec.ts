import { WritableSignal, computed, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TestBed } from '@angular/core/testing';
import { appRoutes } from '../app.routes';
import { AdministrationPage } from '../administration/administration-page';
import { AuditPage } from '../audit/audit-page';
import { BudgetsPage } from '../budgets/budgets-page';
import { Authentication } from '../authentication';
import { NotificationsPage } from './notifications-page';
import { WorkspaceSession } from './workspace-session';
import { WorkspaceProfile } from './workspace-api';

const COMPANY = 'aaaaaaaa-2222-4222-8222-222222222222';
const IDENTITY = 'aaaaaaaa-1111-4111-8111-111111111111';
const MEMBERSHIP = 'aaaaaaaa-3333-4333-8333-333333333333';
const DEPARTMENT = 'aaaaaaaa-4444-4444-8444-444444444444';

const ADMIN_PROFILE: WorkspaceProfile = {
  identityId: IDENTITY,
  membershipId: MEMBERSHIP,
  companyId: COMPANY,
  companyName: 'Larkvane',
  departmentId: DEPARTMENT,
  departmentName: 'engineering',
  version: 4,
  roles: ['COMPANY_ADMIN'],
  managedDepartmentIds: [],
};

describe('Product route mounting', () => {
  let http: HttpTestingController;
  let router: Router;
  let harness: RouterTestingHarness;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let profile: WritableSignal<WorkspaceProfile | null>;
  let loginCalls: number;

  beforeEach(async () => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('authenticated');
    profile = signal<WorkspaceProfile | null>({ ...ADMIN_PROFILE });
    loginCalls = 0;
    TestBed.configureTestingModule({
      providers: [
        provideRouter(appRoutes),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: {
            initialize: async () => {},
            state: authState,
            member: signal({
              identityId: IDENTITY,
              companyId: COMPANY,
              companyName: 'Larkvane',
              departmentName: 'engineering',
              roles: ['EMPLOYEE'],
            }),
            login: async () => {
              loginCalls += 1;
            },
            logout: async () => {
              authState.set('anonymous');
            },
          },
        },
        {
          provide: WorkspaceSession,
          useValue: {
            profile,
            state: signal<'idle' | 'loading' | 'ready' | 'error' | 'forbidden'>('ready'),
            errorMessage: signal(null),
            ownerKey: computed(() => (authState() === 'authenticated' ? `${COMPANY}\u0000${IDENTITY}` : null)),
            generation: signal(7),
            hasRole: (role: string) => (profile()?.roles ?? []).includes(role as WorkspaceProfile['roles'][number]),
            isEmployee: computed(() => profile()?.roles.includes('EMPLOYEE') ?? false),
            isManager: computed(() => profile()?.roles.includes('DEPARTMENT_MANAGER') ?? false),
            isFinance: computed(() => profile()?.roles.includes('FINANCE_APPROVER') ?? false),
            canReachApprovals: computed(
              () =>
                (profile()?.roles.includes('DEPARTMENT_MANAGER') ?? false) ||
                (profile()?.roles.includes('FINANCE_APPROVER') ?? false),
            ),
            ensure: async () => profile(),
            refresh: async () => profile(),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  it('declares the audit deep link before the administration prefix', () => {
    const paths = appRoutes.map((route) => route.path);
    expect(paths).toContain('budgets');
    expect(paths).toContain('administration');
    expect(paths).toContain('administration/audit');
    expect(paths.indexOf('administration/audit')).toBeLessThan(paths.indexOf('administration'));
    // Existing routes stay declared.
    for (const preserved of ['welcome', 'requests', 'requests/new', 'requests/:id', 'approvals', 'notifications', '**']) {
      expect(paths).toContain(preserved);
    }
  });

  it('mounts the budget page and only reads its scoped endpoints', async () => {
    const component = await harness.navigateByUrl('/budgets', BudgetsPage);
    expect(component).toBeInstanceOf(BudgetsPage);
    expect(router.url).toBe('/budgets');
    http.expectOne((candidate) => candidate.url === '/api/budgets').flush([]);
    http.expectOne((candidate) => candidate.url === '/api/departments').flush([]);
  });

  it('mounts the administration page through the authenticated guard', async () => {
    const component = await harness.navigateByUrl('/administration', AdministrationPage);
    expect(component).toBeInstanceOf(AdministrationPage);
    http.expectOne((candidate) => candidate.url === '/api/admin/memberships').flush([]);
    http.expectOne((candidate) => candidate.url === '/api/departments').flush([]);
  });

  it('resolves the audit deep link to the audit page, not the administration page', async () => {
    const component = await harness.navigateByUrl('/administration/audit', AuditPage);
    expect(component).toBeInstanceOf(AuditPage);
    expect(router.url).toBe('/administration/audit');
    http.expectOne((candidate) => candidate.url === '/api/admin/audit').flush({ page: 0, size: 20, total: 0, items: [] });
  });

  it('keeps the untouched notifications deep link mountable', async () => {
    const component = await harness.navigateByUrl('/notifications', NotificationsPage);
    expect(component).toBeInstanceOf(NotificationsPage);
    http.expectOne((candidate) => candidate.url === '/api/notifications').flush({ page: 0, size: 20, total: 0, items: [] });
  });

  it('fails closed to /welcome for anonymous product deep links without starting a login', async () => {
    authState.set('anonymous');
    for (const url of ['/budgets', '/administration', '/administration/audit', '/notifications']) {
      await harness.navigateByUrl(url);
      expect(router.url).toBe('/welcome');
    }
    expect(loginCalls).toBe(0);
  });

  it('does not redirect to an external return URL supplied as a query parameter', async () => {
    authState.set('anonymous');
    await harness.navigateByUrl('/administration/audit?returnUrl=https%3A%2F%2Fevil.example%2Fsteal');
    expect(router.url).toBe('/welcome');
    expect(window.location.origin).not.toContain('evil.example');
    expect(http.match((candidate) => candidate.url.startsWith('/api/admin')).length).toBe(0);
  });
});
