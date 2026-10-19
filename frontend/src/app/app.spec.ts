import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { WritableSignal, signal } from '@angular/core';
import { Authentication, MemberProfile } from './authentication';

describe('Foundation connection boundary', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), {
        provide: Authentication, useValue: { initialize: async () => {}, state: signal('anonymous'), member: signal(null) },
      }],
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

describe('Notifications panel mounting', () => {
  let http: HttpTestingController;
  let authState: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
  let authMember: WritableSignal<MemberProfile | null>;

  beforeEach(() => {
    authState = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('anonymous');
    authMember = signal<MemberProfile | null>(null);
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Authentication, useValue: { initialize: async () => {}, state: authState, member: authMember } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('keeps the panel hidden and sends no private request for an anonymous session', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/system').flush({ name: 'ProcureFlow', stage: 'foundation' });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Notifications');
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
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/system').flush({ name: 'ProcureFlow', stage: 'foundation' });
    await fixture.whenStable();
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
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Notifications');
    expect(text).toContain('Request approved');
    expect(text).toContain('Unread');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-notifications-panel')).not.toBeNull();
  });
});
