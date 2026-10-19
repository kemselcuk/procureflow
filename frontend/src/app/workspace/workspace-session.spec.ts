import { Component, WritableSignal, inject, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Authentication, MemberProfile } from '../authentication';
import { WorkspaceSession } from './workspace-session';
import { COMPANY_A, COMPANY_B, IDENTITY_A, IDENTITY_B, MEMBERSHIP_A, DEPARTMENT_A } from '../requests/request-fixtures';

@Component({ standalone: true, template: '' })
class SessionHarness {
  readonly session = inject(WorkspaceSession);
}

function member(identityId = IDENTITY_A, companyId = COMPANY_A): MemberProfile {
  return {
    identityId,
    companyId,
    companyName: companyId === COMPANY_A ? 'Larkvane' : 'Other company',
    departmentName: 'engineering',
    roles: ['EMPLOYEE'],
  };
}

function profileBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    identityId: IDENTITY_A,
    membershipId: MEMBERSHIP_A,
    companyId: COMPANY_A,
    companyName: 'Larkvane',
    departmentId: DEPARTMENT_A,
    departmentName: 'engineering',
    version: 3,
    roles: ['EMPLOYEE'],
    managedDepartmentIds: [],
    ...overrides,
  };
}

describe('Workspace session state', () => {
  let http: HttpTestingController;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;
  let fixture: ComponentFixture<SessionHarness>;
  let session: WorkspaceSession;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    TestBed.configureTestingModule({
      imports: [SessionHarness],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: { initialize: async () => {}, state: authState, member: authMember },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SessionHarness);
    session = fixture.componentInstance.session;
  });

  afterEach(() => http.verify());

  async function authenticate(profile: MemberProfile = member()): Promise<TestRequest> {
    authMember.set(profile);
    authState.set('authenticated');
    fixture.detectChanges();
    await fixture.whenStable();
    return http.expectOne('/api/me');
  }

  it('loads /api/me only after an authenticated member exists', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    expect(http.match('/api/me').length).toBe(0);

    const request = await authenticate();
    expect(request.request.method).toBe('GET');
    request.flush(profileBody());
    await fixture.whenStable();
    expect(session.state()).toBe('ready');
    expect(session.profile()?.companyName).toBe('Larkvane');
    expect(session.isEmployee()).toBe(true);
    expect(session.isManager()).toBe(false);
  });

  it('shares one in-flight membership read between the guard and the session effect', async () => {
    const request = await authenticate();
    const first = session.ensure(true);
    const second = session.ensure(true);
    expect(http.match('/api/me').length).toBe(0);
    request.flush(profileBody());
    await fixture.whenStable();
    await expect(first).resolves.toBeDefined();
    await expect(second).resolves.toBeDefined();
    expect(session.profile()?.identityId).toBe(IDENTITY_A);
  });

  it('ignores an old identity membership response and applies the new session', async () => {
    const oldRequest = await authenticate();
    authMember.set(member(IDENTITY_B, COMPANY_B));
    fixture.detectChanges();
    await fixture.whenStable();
    const newRequest = http.expectOne('/api/me');

    oldRequest.flush(profileBody({ identityId: IDENTITY_B, companyId: COMPANY_B, companyName: 'Other company' }));
    await fixture.whenStable();
    expect(session.profile()).toBeNull();

    newRequest.flush(
      profileBody({ identityId: IDENTITY_B, companyId: COMPANY_B, companyName: 'Other company', membershipId: MEMBERSHIP_A }),
    );
    await fixture.whenStable();
    expect(session.profile()?.identityId).toBe(IDENTITY_B);
    expect(session.profile()?.companyId).toBe(COMPANY_B);
  });

  it('keeps the generation stable for an unchanged refresh and invalidates role changes', async () => {
    await authenticate().then((request) => request.flush(profileBody()));
    await fixture.whenStable();
    const initialGeneration = session.generation();

    const unchanged = session.refresh();
    http.expectOne('/api/me').flush(profileBody());
    await fixture.whenStable();
    await unchanged;
    expect(session.generation()).toBe(initialGeneration);

    const changed = session.refresh();
    http.expectOne('/api/me').flush(
      profileBody({ version: 4, roles: ['EMPLOYEE', 'DEPARTMENT_MANAGER'], managedDepartmentIds: [DEPARTMENT_A] }),
    );
    await fixture.whenStable();
    await changed;
    expect(session.generation()).toBeGreaterThan(initialGeneration);
    expect(session.isManager()).toBe(true);
    expect(session.profile()?.managedDepartmentIds).toEqual([DEPARTMENT_A]);
  });

  it('fails closed with a forbidden state when membership is not active', async () => {
    await authenticate().then((request) =>
      request.flush({ status: 403, title: 'Forbidden', code: 'forbidden' }, { status: 403, statusText: 'Forbidden' }),
    );
    await fixture.whenStable();
    expect(session.state()).toBe('forbidden');
    expect(session.profile()).toBeNull();
    expect(session.errorMessage()).toContain('active company membership');
  });

  it('surfaces a retryable membership error without keeping a previous profile', async () => {
    await authenticate().then((request) => request.flush(profileBody()));
    await fixture.whenStable();
    expect(session.profile()).not.toBeNull();

    const refreshed = session.refresh();
    http.expectOne('/api/me').flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    await refreshed;
    expect(session.state()).toBe('error');
    expect(session.profile()).toBeNull();
    expect(session.errorMessage()).toContain('HTTP 503');
    expect(session.generation()).toBeGreaterThan(0);
  });

  it('clears member-bound state when the session ends', async () => {
    await authenticate().then((request) => request.flush(profileBody()));
    await fixture.whenStable();
    expect(session.profile()).not.toBeNull();

    authState.set('anonymous');
    authMember.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(session.profile()).toBeNull();
    expect(session.state()).toBe('idle');
    expect(session.roles().size).toBe(0);
  });

  it.each([
    [
      'a stale successful profile',
      (request: TestRequest) => request.flush(profileBody({ companyName: 'Old stale session name' })),
    ],
    [
      'a stale 401',
      (request: TestRequest) =>
        request.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' }),
    ],
    [
      'a stale 403',
      (request: TestRequest) =>
        request.flush({ status: 403, title: 'Forbidden', code: 'forbidden' }, { status: 403, statusText: 'Forbidden' }),
    ],
  ])('ignores %s that resolves after logout and re-login of the same identity', async (_label, respond) => {
    await authenticate().then((request) => request.flush(profileBody({ companyName: 'Initial name' })));
    await fixture.whenStable();

    void session.refresh();
    const oldRequest = http.expectOne('/api/me');
    authState.set('anonymous');
    authMember.set(null);
    fixture.detectChanges();
    await fixture.whenStable();

    authMember.set(member());
    authState.set('authenticated');
    fixture.detectChanges();
    await fixture.whenStable();
    const freshRequest = http.expectOne('/api/me');

    freshRequest.flush(profileBody({ companyName: 'New session name', version: 4 }));
    await fixture.whenStable();
    expect(session.profile()?.companyName).toBe('New session name');
    expect(session.state()).toBe('ready');

    respond(oldRequest);
    await fixture.whenStable();
    expect(session.profile()?.companyName).toBe('New session name');
    expect(session.profile()?.version).toBe(4);
    expect(session.state()).toBe('ready');
    expect(session.errorMessage()).toBeNull();
  });

  it('rejects a membership response whose identity or company does not match the signed-in session', async () => {
    const request = await authenticate();
    request.flush(profileBody({ identityId: IDENTITY_B, companyId: COMPANY_B, companyName: 'Other company' }));
    await fixture.whenStable();
    expect(session.profile()).toBeNull();
    expect(session.state()).toBe('error');
    expect(session.errorMessage()).toContain('did not match');
  });
});
