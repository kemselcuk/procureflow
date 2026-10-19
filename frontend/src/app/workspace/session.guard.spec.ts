import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, provideRouter } from '@angular/router';
import { WritableSignal, signal } from '@angular/core';
import { Authentication, MemberProfile } from '../authentication';
import { authenticatedGuard } from './session.guard';
import { WorkspaceSession } from './workspace-session';
import { WorkspaceProfile } from './workspace-api';
import { COMPANY_A, IDENTITY_A, MEMBERSHIP_A, DEPARTMENT_A } from '../requests/request-fixtures';

const MEMBER: MemberProfile = {
  identityId: IDENTITY_A,
  companyId: COMPANY_A,
  companyName: 'Larkvane',
  departmentName: 'engineering',
  roles: ['EMPLOYEE'],
};

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

function runGuard(): ReturnType<typeof authenticatedGuard> {
  return TestBed.runInInjectionContext(() =>
    authenticatedGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
  );
}

describe('authenticatedGuard', () => {
  let state: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let member: WritableSignal<MemberProfile | null>;
  let profile: WorkspaceProfile | null;
  let ensureCalls: number;
  let initializeCalls: number;

  beforeEach(() => {
    state = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    member = signal<MemberProfile | null>(null);
    profile = PROFILE;
    ensureCalls = 0;
    initializeCalls = 0;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'welcome', children: [] }]),
        {
          provide: Authentication,
          useValue: {
            initialize: async () => {
              initializeCalls += 1;
              state.set('authenticated');
              member.set(MEMBER);
            },
            state,
            member,
          },
        },
        {
          provide: WorkspaceSession,
          useValue: {
            ensure: async () => {
              ensureCalls += 1;
              return profile;
            },
          },
        },
      ],
    });
  });

  it('waits for sign-in initialization, then allows an authenticated member with a profile', async () => {
    state.set('starting');
    await expect(runGuard()).resolves.toBe(true);
    expect(initializeCalls).toBe(1);
    expect(ensureCalls).toBe(1);
  });

  it('fails closed to /welcome for an anonymous session without starting a login', async () => {
    const result = await runGuard();
    expect(result).not.toBe(true);
    expect(String(result)).toBe('/welcome');
    expect(ensureCalls).toBe(0);
  });

  it.each(['forbidden', 'error'] as const)('fails closed for a settled %s authentication state', async (authState) => {
    state.set(authState);
    member.set(authState === 'forbidden' ? null : MEMBER);
    const result = await runGuard();
    expect(String(result)).toBe('/welcome');
    expect(ensureCalls).toBe(0);
  });

  it('fails closed when the richer membership profile cannot be loaded', async () => {
    state.set('authenticated');
    member.set(MEMBER);
    profile = null;
    const result = await runGuard();
    expect(String(result)).toBe('/welcome');
    expect(ensureCalls).toBe(1);
  });

  it('creates a same-origin router URL tree, not an external redirect', async () => {
    const result = await runGuard();
    expect(result).toBeInstanceOf(Object);
    const tree = TestBed.inject(Router).createUrlTree(['/welcome']);
    expect(String(result)).toBe(String(tree));
    expect(String(result)).not.toContain('http');
    expect(String(result)).not.toContain('returnUrl');
  });
});
