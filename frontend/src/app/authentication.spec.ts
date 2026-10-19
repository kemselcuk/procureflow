import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import Keycloak from 'keycloak-js';
import { Authentication, KEYCLOAK_FACTORY } from './authentication';
import { authInterceptor } from './auth-interceptor';
import { NAVIGATION_INTENT_STORAGE_KEY } from './workspace/navigation-intent';

const profile = { identityId: 'identity', companyId: 'company', companyName: 'Larkvane', departmentName: 'engineering', roles: ['EMPLOYEE'] };

describe('Authentication lifecycle', () => {
  let http: HttpTestingController;
  let auth: Authentication;
  let adapter: {
    authenticated: boolean; token: string; init: ReturnType<typeof vi.fn>;
    updateToken: ReturnType<typeof vi.fn>; clearToken: ReturnType<typeof vi.fn>;
    login: ReturnType<typeof vi.fn>; logout: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    adapter = { authenticated: true, token: 'ephemeral-test-token', init: vi.fn().mockResolvedValue(true),
      updateToken: vi.fn().mockResolvedValue(false), clearToken: vi.fn(), login: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue(undefined) };
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: KEYCLOAK_FACTORY, useValue: () => adapter as unknown as Keycloak }] });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(Authentication);
  });
  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    window.history.replaceState(window.history.state, '', '/');
    window.sessionStorage.clear();
  });

  async function start(): Promise<Promise<void>[]> {
    const pending = auth.initialize();
    http.expectOne('/api/auth/config').flush({ issuer: 'http://localhost:8180/realms/procureflow', clientId: 'procureflow-web' });
    await Promise.resolve(); await Promise.resolve();
    return [pending];
  }

  it('uses explicit code+S256, registers only self, and reads membership from the API', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const [pending] = await start();
    expect(adapter.init).toHaveBeenCalledWith(expect.objectContaining({ flow: 'standard', pkceMethod: 'S256', checkLoginIframe: false }));
    const registration = http.expectOne('/api/identities/self');
    expect(registration.request.method).toBe('POST'); registration.flush({ id: 'identity' });
    await Promise.resolve();
    http.expectOne('/api/me').flush(profile);
    await pending;
    expect(auth.state()).toBe('authenticated');
    expect(auth.member()?.companyId).toBe('company');
    expect(storage).not.toHaveBeenCalled();
  });

  it('keeps a valid identity without active membership in a forbidden state', async () => {
    const [pending] = await start();
    http.expectOne('/api/identities/self').flush({ id: 'identity' }); await Promise.resolve();
    http.expectOne('/api/me').flush({}, { status: 403, statusText: 'Forbidden' });
    await pending;
    expect(auth.state()).toBe('forbidden'); expect(auth.member()).toBeNull();
  });

  it('does not bootstrap or fetch a company for an anonymous session', async () => {
    adapter.init.mockResolvedValue(false);
    const [pending] = await start(); await pending;
    expect(auth.state()).toBe('anonymous'); http.expectNone('/api/me');
  });

  it('clears the session rather than reusing an expired token after refresh failure', async () => {
    const [pending] = await start();
    http.expectOne('/api/identities/self').flush({ id: 'identity' }); await Promise.resolve();
    http.expectOne('/api/me').flush(profile); await pending;
    adapter.updateToken.mockRejectedValue(new Error('refresh unavailable'));
    expect(await auth.accessToken()).toBeUndefined();
    expect(adapter.clearToken).toHaveBeenCalled(); expect(auth.state()).toBe('anonymous'); expect(auth.member()).toBeNull();
  });

  it('treats an incompatible successful membership response as an error', async () => {
    const [pending] = await start();
    http.expectOne('/api/identities/self').flush({ id: 'identity' }); await Promise.resolve();
    http.expectOne('/api/me').flush({ companyId: 'not-a-profile' }); await pending;
    expect(auth.state()).toBe('error'); expect(auth.member()).toBeNull();
  });

  it('initializes the SDK adapter exactly once for concurrent callers', async () => {
    const first = auth.initialize();
    const second = auth.initialize();
    http.expectOne('/api/auth/config').flush({ issuer: 'http://localhost:8180/realms/procureflow', clientId: 'procureflow-web' });
    await Promise.resolve(); await Promise.resolve();
    expect(adapter.init).toHaveBeenCalledTimes(1);
    http.expectOne('/api/identities/self').flush({ id: 'identity' }); await Promise.resolve();
    http.expectOne('/api/me').flush(profile);
    await Promise.all([first, second]);
    expect(adapter.init).toHaveBeenCalledTimes(1);
    expect(auth.state()).toBe('authenticated');
  });

  it('captures only a validated deep path before the SDK redirect and clears it on logout', async () => {
    const requestId = '0f0e0d0c-0b0a-4908-8706-050403020100';
    window.history.replaceState(window.history.state, '', `/requests/${requestId}`);
    const [pending] = await start();
    const stored = window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY);
    expect(stored).not.toBeNull();
    expect(stored).toContain(`/requests/${requestId}`);
    expect(stored).not.toContain('token');
    expect(stored).not.toContain('code');
    http.expectOne('/api/identities/self').flush({ id: 'identity' }); await Promise.resolve();
    http.expectOne('/api/me').flush(profile); await pending;
    await auth.logout();
    expect(adapter.logout).toHaveBeenCalledWith({ redirectUri: window.location.origin + '/' });
    expect(window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY)).toBeNull();
  });

  it('clears a pending deep-link intent when Keycloak reports an external logout', async () => {
    const requestId = '0f0e0d0c-0b0a-4908-8706-050403020100';
    window.history.replaceState(window.history.state, '', `/requests/${requestId}`);
    const [pending] = await start();
    http.expectOne('/api/identities/self').flush({ id: 'identity' }); await Promise.resolve();
    http.expectOne('/api/me').flush(profile); await pending;
    expect(window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY)).not.toBeNull();
    (adapter as unknown as { onAuthLogout?: () => void }).onAuthLogout?.();
    expect(window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY)).toBeNull();
    expect(auth.state()).toBe('anonymous');
  });
});

describe('Bearer token boundary', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let accessToken: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    accessToken = vi.fn().mockResolvedValue('ephemeral-test-token');
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      { provide: Authentication, useValue: { accessToken } }] });
    http = TestBed.inject(HttpTestingController); client = TestBed.inject(HttpClient);
  });
  afterEach(() => http.verify());

  it('refreshes and attaches the token only to same-origin private API requests', async () => {
    client.get('/api/me').subscribe(); await Promise.resolve();
    const request = http.expectOne('/api/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer ephemeral-test-token');
    expect(accessToken).toHaveBeenCalledTimes(1); request.flush(profile);
  });

  it.each(['https://attacker.invalid/api/me', '//attacker.invalid/api/me', '/api/auth/config', '/api/system', '/assets/file'])
    ('does not leak a token to %s', (url) => {
      client.get(url).subscribe(); const request = http.expectOne(url);
      expect(request.request.headers.has('Authorization')).toBe(false);
      expect(accessToken).not.toHaveBeenCalled(); request.flush({});
    });
});
