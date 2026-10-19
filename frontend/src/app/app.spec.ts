import { Component, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from './app';
import { Authentication, MemberProfile } from './authentication';
import { NotificationsPage } from './workspace/notifications-page';
import { WorkspaceSession } from './workspace/workspace-session';

@Component({ standalone: true, template: '' })
class EmptyPage {}

function workspaceSessionMock() {
  return {
    profile: signal(null),
    state: signal('idle'),
    errorMessage: signal(null),
    ownerKey: signal(null),
    generation: signal(0),
    isEmployee: signal(false),
    isManager: signal(false),
    isFinance: signal(false),
    canReachApprovals: signal(false),
    hasRole: () => false,
  };
}

describe('Foundation connection boundary', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: 'welcome', component: EmptyPage },
          { path: 'requests', component: EmptyPage },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: { initialize: async () => {}, state: signal('anonymous'), member: signal(null), logout: async () => {} },
        },
        { provide: WorkspaceSession, useValue: workspaceSessionMock() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows a loading state, then confirms a valid API response without implying product readiness', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.connection button')?.hasAttribute('disabled')).toBe(true);
    const request = http.expectOne('/api/system');
    expect(request.request.method).toBe('GET');
    request.flush({ name: 'ProcureFlow', stage: 'foundation' });
    await fixture.whenStable();
    expect(element.textContent).toContain('Foundation API connected');
    expect(element.textContent).toContain('not purchase-feature readiness');
    expect(element.querySelector('.connection button')?.hasAttribute('disabled')).toBe(false);
  });

  it('keeps an API failure recoverable through the visible retry action', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/system').flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('API is unavailable');
    (element.querySelector('.connection button') as HTMLButtonElement).click();
    http.expectOne('/api/system').flush({ name: 'ProcureFlow', stage: 'foundation' });
    await fixture.whenStable();
    expect(element.textContent).toContain('Foundation API connected');
  });

  it('does not mistake an incompatible successful HTTP response for a healthy API', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/system').flush({ name: 'Another service', stage: 'production' });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('API is unavailable');
  });
});

describe('Notifications route mounting', () => {
  let http: HttpTestingController;
  let router: Router;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: 'welcome', component: EmptyPage },
          { path: 'requests', component: EmptyPage },
          { path: 'notifications', component: NotificationsPage },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Authentication,
          useValue: {
            initialize: async () => {},
            state: authState,
            member: authMember,
            login: async () => {},
            logout: async () => {},
          },
        },
        { provide: WorkspaceSession, useValue: workspaceSessionMock() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  async function renderNotifications(): Promise<{ fixture: ComponentFixture<App>; element: HTMLElement }> {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await router.navigateByUrl('/notifications');
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('keeps the panel hidden and sends no private request for an anonymous session', async () => {
    const { element } = await renderNotifications();
    http.expectOne('/api/system').flush({ name: 'ProcureFlow', stage: 'foundation' });
    await Promise.resolve();
    const panel = element.querySelector('app-notifications-panel');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).not.toContain('Notifications');
    expect(http.match((request) => request.url.startsWith('/api/notifications')).length).toBe(0);
  });

  it('mounts notifications for an authenticated member and renders the first page', async () => {
    authState.set('authenticated');
    authMember.set({
      identityId: '11111111-1111-4111-8111-111111111111',
      companyId: '22222222-2222-4222-8222-222222222222',
      companyName: 'Larkvane',
      departmentName: 'engineering',
      roles: ['EMPLOYEE'],
    });
    const { fixture, element } = await renderNotifications();
    http.expectOne('/api/system').flush({ name: 'ProcureFlow', stage: 'foundation' });
    const request = http.expectOne((candidate) => candidate.url === '/api/notifications');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({
      page: 0,
      size: 20,
      total: 1,
      items: [
        {
          id: '33333333-3333-4333-8333-333333333333',
          eventId: '44444444-4444-4444-8444-444444444444',
          eventType: 'request.approved',
          requestId: '55555555-5555-4555-8555-555555555555',
          correlationId: '66666666-6666-4666-8666-666666666666',
          occurredAt: '2026-10-18T09:30:00Z',
          createdAt: '2026-10-18T09:30:01Z',
          readAt: null,
        },
      ],
    });
    fixture.detectChanges();
    await fixture.whenStable();
    const text = element.textContent ?? '';
    expect(text).toContain('Notifications');
    expect(text).toContain('Request approved');
    expect(text).toContain('Unread');
    expect(element.querySelector('app-notifications-panel')).not.toBeNull();
  });
});
