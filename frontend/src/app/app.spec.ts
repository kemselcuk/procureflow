import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { signal } from '@angular/core';
import { Authentication } from './authentication';

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
