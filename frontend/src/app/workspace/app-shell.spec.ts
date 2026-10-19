import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { WritableSignal, computed, signal } from '@angular/core';
import { App } from '../app';
import { appRoutes } from '../app.routes';
import { Authentication, MemberProfile } from '../authentication';
import { WorkspaceSession } from './workspace-session';
import { WorkspaceProfile } from './workspace-api';
import { COMPANY_A, DEPARTMENT_A, IDENTITY_A, IDENTITY_B, MEMBERSHIP_A, requestPage, summary } from '../requests/request-fixtures';

const PROFILE: WorkspaceProfile = {
  identityId: IDENTITY_A,
  membershipId: MEMBERSHIP_A,
  companyId: COMPANY_A,
  companyName: 'Larkvane',
  departmentId: DEPARTMENT_A,
  departmentName: 'engineering',
  version: 3,
  roles: ['EMPLOYEE'],
  managedDepartmentIds: [],
};

describe('Application shell and routes', () => {
  let http: HttpTestingController;
  let router: Router;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;
  let profileSignal: WritableSignal<WorkspaceProfile | null>;
  let sessionState: WritableSignal<'idle' | 'loading' | 'ready' | 'error' | 'forbidden'>;
  let generation: WritableSignal<number>;
  let fixture: ComponentFixture<App>;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    profileSignal = signal<WorkspaceProfile | null>(PROFILE);
    sessionState = signal<'idle' | 'loading' | 'ready' | 'error' | 'forbidden'>('ready');
    generation = signal(3);
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(appRoutes),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: {
            initialize: async () => {},
            state: authState,
            member: authMember,
            login: async () => {},
            logout: async () => {
              authState.set('anonymous');
              authMember.set(null);
            },
          },
        },
        {
          provide: WorkspaceSession,
          useValue: {
            profile: profileSignal,
            state: sessionState,
            errorMessage: signal(null),
            ownerKey: computed(() => {
              const member = authMember();
              return authState() === 'authenticated' && member !== null
                ? `${member.companyId}\u0000${member.identityId}`
                : null;
            }),
            generation,
            roles: computed(() => new Set(profileSignal()?.roles ?? [])),
            hasRole: (role: string) => (profileSignal()?.roles ?? []).includes(role as WorkspaceProfile['roles'][number]),
            isEmployee: computed(() => profileSignal()?.roles.includes('EMPLOYEE') ?? false),
            isManager: computed(() => profileSignal()?.roles.includes('DEPARTMENT_MANAGER') ?? false),
            isFinance: computed(() => profileSignal()?.roles.includes('FINANCE_APPROVER') ?? false),
            canReachApprovals: computed(
              () =>
                (profileSignal()?.roles.includes('DEPARTMENT_MANAGER') ?? false) ||
                (profileSignal()?.roles.includes('FINANCE_APPROVER') ?? false),
            ),
            ensure: async () => profileSignal(),
            refresh: async () => profileSignal(),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  function authenticate(
    member: MemberProfile,
    roles: WorkspaceProfile['roles'],
    managed: readonly string[] = [],
  ): void {
    authMember.set(member);
    authState.set('authenticated');
    profileSignal.set({ ...PROFILE, identityId: member.identityId, roles, managedDepartmentIds: managed });
  }

  async function renderAt(url: string): Promise<HTMLElement> {
    fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await router.navigateByUrl(url);
    fixture.detectChanges();
    await fixture.whenStable();
    http.match('/api/system').forEach((request) => request.flush({ name: 'ProcureFlow', stage: 'foundation' }));
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function navText(): string {
    return (fixture.nativeElement as HTMLElement).querySelector('nav')?.textContent ?? '';
  }

  it('keeps the untouched SessionPanel reachable and hides member navigation while signed out', async () => {
    const element = await renderAt('/welcome');
    expect(element.querySelector('app-session-panel')).not.toBeNull();
    expect(element.textContent).toContain('Workspace home');
    expect(element.textContent).not.toContain('My requests');
    expect(element.querySelector('.account')).toBeNull();
  });

  it('shows employee navigation, the account summary and sign-out without a department selector', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/welcome');
    const text = element.textContent ?? '';
    expect(text).toContain('My requests');
    expect(text).toContain('New request');
    expect(text).toContain('Notifications');
    expect(navText()).not.toContain('Approvals');
    expect(navText()).not.toContain('Budgets');
    expect(navText()).not.toContain('Administration');
    expect(element.querySelector('.account')).not.toBeNull();
    expect(element.querySelector('app-session-panel')).toBeNull();
    expect(text).toContain('Employee');
    expect(text).toContain('Sign out');
  });

  it('hides the manager/finance inbox unless a relevant role is active', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['DEPARTMENT_MANAGER'] },
      ['DEPARTMENT_MANAGER'],
      [DEPARTMENT_A],
    );
    const element = await renderAt('/welcome');
    expect(element.textContent).toContain('Approvals');
    expect(element.textContent).not.toContain('New request');

    authState.set('authenticated');
    profileSignal.set({ ...PROFILE, roles: ['COMPANY_ADMIN'] });
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Approvals');
    expect(navText()).toContain('Budgets');
  });

  it('shows Budgets for manager/finance and the administration area only for COMPANY_ADMIN', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['FINANCE_APPROVER'] },
      ['FINANCE_APPROVER'],
    );
    await renderAt('/welcome');
    expect(navText()).toContain('Budgets');
    expect(navText()).not.toContain('Administration');
    expect(navText()).not.toContain('Audit');

    profileSignal.set({ ...PROFILE, roles: ['COMPANY_ADMIN'] });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(navText()).toContain('Budgets');
    expect(navText()).toContain('Administration');
    expect(navText()).toContain('Audit');
  });

  it('never grants product navigation from a stale sign-in role snapshot', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Stale sign-in company', departmentName: 'engineering', roles: ['COMPANY_ADMIN', 'EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    profileSignal.set({ ...PROFILE, companyName: 'Current scope company', roles: ['EMPLOYEE'] });
    const element = await renderAt('/welcome');
    expect(navText()).not.toContain('Administration');
    expect(navText()).not.toContain('Audit');
    expect(navText()).not.toContain('Budgets');
    const text = element.textContent ?? '';
    expect(text).toContain('Current scope company');
    expect(text).not.toContain('Stale sign-in company');
    expect(text).toContain('Employee');
    expect(text).not.toContain('Company administrator');
  });

  it('uses the current scoped company and department in the account summary', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Old auth label', departmentName: 'old department', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    profileSignal.set({
      ...PROFILE,
      companyName: 'Current scope company',
      departmentName: 'current department',
      roles: ['EMPLOYEE'],
    });
    const element = await renderAt('/welcome');
    const account = element.querySelector('.account');
    expect(account?.textContent).toContain('Current scope company');
    expect(account?.textContent).toContain('current department');
    expect(account?.textContent).not.toContain('Old auth label');
    expect(account?.textContent).not.toContain('old department');
  });

  it('demonstrates the required admin-permitted test profile is distinct from the compact sign-in profile', () => {
    // The required divergent fixture: compact sign-in says COMPANY_ADMIN while the scoped
    // profile is an ordinary employee. Navigation and the account summary must follow the profile.
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['COMPANY_ADMIN'] },
      ['EMPLOYEE'],
    );
    expect(profileSignal()?.roles).toEqual(['EMPLOYEE']);
    expect(authMember()?.roles).toEqual(['COMPANY_ADMIN']);
  });

  it('renders a restricted administration screen for an ordinary current membership without privileged requests', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/administration');
    expect(element.textContent).toContain('Company administration is restricted');
    expect(http.match((candidate) => candidate.url.startsWith('/api/admin')).length).toBe(0);
  });

  it('renders the audit denied state for an ordinary current membership without an audit request', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/administration/audit');
    expect(element.textContent).toContain('available to company administrators only');
    expect(http.match((candidate) => candidate.url === '/api/admin/audit').length).toBe(0);
  });

  it('renders the budget forbidden state for an ordinary current membership without a budget request', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/budgets');
    expect(element.textContent).toContain('Budget access is unavailable');
    expect(http.match((candidate) => candidate.url === '/api/budgets').length).toBe(0);
    expect(http.match((candidate) => candidate.url === '/api/departments').length).toBe(0);
  });

  it('marks only the exact request-list link as the current page next to New request', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    await renderAt('/requests/new');
    const current = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[aria-current="page"]')).map(
      (link) => link.textContent?.trim(),
    );
    expect(current).toEqual(['New request']);
  });

  it('marks only Audit as current under the administration prefix', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['COMPANY_ADMIN'] },
      ['COMPANY_ADMIN'],
    );
    await renderAt('/administration/audit');
    const current = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[aria-current="page"]')).map(
      (link) => link.textContent?.trim(),
    );
    expect(current).toEqual(['Audit']);
    http.match((candidate) => candidate.url === '/api/admin/audit').forEach((request) => request.flush({ page: 0, size: 20, total: 0, items: [] }));
  });

  it('redirects the default member path to /requests and loads the own-request list', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/');
    expect(router.url).toBe('/requests');
    const list = http.expectOne((candidate) => candidate.url === '/api/requests');
    expect(list.request.params.get('view')).toBe('mine');
    list.flush(requestPage([summary({ title: 'Shell route request' })]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.textContent).toContain('Shell route request');
  });

  it('fails closed to the workspace page for an anonymous deep link without triggering login', async () => {
    let loginCalls = 0;
    const auth = TestBed.inject(Authentication) as unknown as { login: () => Promise<void> };
    auth.login = async () => {
      loginCalls += 1;
    };
    const element = await renderAt('/requests');
    expect(router.url).toBe('/welcome');
    expect(loginCalls).toBe(0);
    expect(element.textContent).toContain('Sign in with your local account');
    expect(http.match('/api/requests').length).toBe(0);
  });

  it('ignores an external return URL parameter instead of redirecting off-origin', async () => {
    const element = await renderAt('/administration?returnUrl=https%3A%2F%2Fevil.example%2Fsteal');
    expect(router.url).toBe('/welcome');
    expect(window.location.origin).not.toContain('evil.example');
    expect(element.querySelector('app-session-panel')).not.toBeNull();
    expect(http.match((candidate) => candidate.url.startsWith('/api/admin')).length).toBe(0);
  });

  it('shows a usable not-found screen for unknown routes', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/unknown/path');
    expect(router.url).toBe('/unknown/path');
    expect(element.textContent).toContain('Page not found');
    expect(element.querySelector('a[href="/requests"]')).not.toBeNull();
  });

  it('leaves member screens and clears the account when the session ends', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    await renderAt('/welcome');
    await router.navigateByUrl('/requests');
    fixture.detectChanges();
    http.expectOne((candidate) => candidate.url === '/api/requests').flush(requestPage([]));
    fixture.detectChanges();
    await fixture.whenStable();

    authState.set('anonymous');
    authMember.set(null);
    profileSignal.set(null);
    sessionState.set('idle');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(router.url).toBe('/welcome');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-session-panel')).not.toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Shell route request');
  });

  it('leaves a member screen when the scoped membership read stops working', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    await renderAt('/');
    http.expectOne((candidate) => candidate.url === '/api/requests').flush(requestPage([]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(router.url).toBe('/requests');

    sessionState.set('error');
    profileSignal.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(router.url).toBe('/welcome');
  });

  it('does not leak a previous identity name when another identity signs in on the same shell', async () => {
    authenticate(
      { identityId: IDENTITY_A, companyId: COMPANY_A, companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] },
      ['EMPLOYEE'],
    );
    const element = await renderAt('/welcome');
    expect(element.textContent).toContain('Larkvane');

    authMember.set({
      identityId: IDENTITY_B,
      companyId: COMPANY_A,
      companyName: 'Other company',
      departmentName: 'operations',
      roles: ['EMPLOYEE'],
    });
    profileSignal.set({
      ...PROFILE,
      identityId: IDENTITY_B,
      companyName: 'Other company',
      departmentName: 'operations',
    });
    fixture.detectChanges();
    await fixture.whenStable();
    const text = element.textContent ?? '';
    expect(text).toContain('Other company');
    expect(text).not.toContain('Larkvane');
  });
});
