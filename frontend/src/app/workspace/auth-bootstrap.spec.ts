import { ApplicationConfig, ApplicationRef, Component, WritableSignal, signal } from '@angular/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { bootstrapApplication } from '@angular/platform-browser';
import { RouterOutlet } from '@angular/router';
import { Authentication, MemberProfile } from '../authentication';
import { appConfig } from '../app.config';
import { WorkspaceProfile } from './workspace-api';
import { WorkspaceSession } from './workspace-session';
import { NAVIGATION_INTENT_STORAGE_KEY } from './navigation-intent';

const REQUEST_ID = '0f0e0d0c-0b0a-4908-8706-050403020100';
const DEEP_PATH = `/requests/${REQUEST_ID}`;
const CALLBACK_FRAGMENT = '#opaque-callback-fixture';
const MEMBER: MemberProfile = {
  identityId: 'identity',
  companyId: 'company',
  companyName: 'Larkvane',
  departmentName: 'engineering',
  roles: ['EMPLOYEE'],
};
const PROFILE: WorkspaceProfile = {
  identityId: 'identity',
  membershipId: 'membership',
  companyId: 'company',
  companyName: 'Larkvane',
  departmentId: 'department',
  departmentName: 'engineering',
  version: 1,
  roles: ['EMPLOYEE'],
  managedDepartmentIds: [],
};

@Component({ selector: 'app-bootstrap-probe', standalone: true, imports: [RouterOutlet], template: '<router-outlet />' })
class ProbeRoot {}

interface Harness {
  readonly appRef: ApplicationRef;
  readonly host: HTMLElement;
  readonly authentication: {
    readonly initialize: ReturnType<typeof vi.fn>;
    readonly state: WritableSignal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>;
    readonly member: WritableSignal<MemberProfile | null>;
  };
}

/**
 * Boots the real application providers (including the router and the auth bootstrap) in jsdom.
 * The authentication adapter is replaced by a deterministic callback simulation: it removes the
 * validated OIDC fragment from the address as keycloak-js does before its code exchange.
 */
async function bootstrapWithSimulatedCallback(options: {
  readonly initialUrl: string;
  readonly cleanedPath: string;
  readonly authenticated?: boolean;
  readonly initialIntent?: string;
  readonly expectedPath: string;
}): Promise<Harness> {
  window.history.replaceState(window.history.state, '', options.initialUrl);
  window.sessionStorage.clear();
  if (options.initialIntent !== undefined) {
    window.sessionStorage.setItem(
      NAVIGATION_INTENT_STORAGE_KEY,
      JSON.stringify({ version: 1, path: options.initialIntent, expiresAt: Date.now() + 60_000 }),
    );
  }

  const state = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('starting');
  const member = signal<MemberProfile | null>(null);
  const host = document.createElement('app-bootstrap-probe');
  document.body.appendChild(host);
  const initialize = vi.fn(async () => {
    // keycloak-js removes the validated callback parameters synchronously at the start of
    // #processInit; the delay represents the remaining token exchange and membership reads.
    window.history.replaceState(window.history.state, '', options.cleanedPath);
    await new Promise((resolve) => setTimeout(resolve, 10));
    if (options.authenticated === false) {
      state.set('anonymous');
      return;
    }
    member.set(MEMBER);
    state.set('authenticated');
  });

  const config: ApplicationConfig = {
    providers: [
      ...appConfig.providers,
      provideHttpClientTesting(),
      { provide: Authentication, useValue: { initialize, state, member } },
      {
        provide: WorkspaceSession,
        useValue: {
          state: signal('ready'),
          profile: signal(PROFILE),
          ownerKey: signal('company\u0000identity'),
          ensure: async () => PROFILE,
          refresh: async () => PROFILE,
          isEmployee: signal(true),
          isManager: signal(false),
          isFinance: signal(false),
          canReachApprovals: signal(false),
          hasRole: () => false,
          generation: signal(1),
        },
      },
    ],
  };
  const appRef = await bootstrapApplication(ProbeRoot, config);
  await appRef.whenStable();
  await vi.waitFor(() => {
    if (window.location.pathname !== options.expectedPath) {
      throw new Error(`Router did not settle at ${options.expectedPath}`);
    }
  });
  return { appRef, host, authentication: { initialize, state, member } };
}

describe('authentication bootstrap ordering', () => {
  afterEach(() => {
    window.sessionStorage.clear();
    window.history.replaceState(window.history.state, '', '/');
  });

  it('finishes callback processing before the initial navigation and does not reintroduce the fragment', async () => {
    const harness = await bootstrapWithSimulatedCallback({
      initialUrl: `${DEEP_PATH}${CALLBACK_FRAGMENT}`,
      cleanedPath: DEEP_PATH,
      expectedPath: DEEP_PATH,
    });
    try {
      expect(harness.authentication.initialize).toHaveBeenCalledTimes(1);
      expect(window.location.pathname).toBe(DEEP_PATH);
      expect(window.location.hash).toBe('');
      expect(window.location.search).toBe('');
    } finally {
      harness.appRef.destroy();
      harness.host.remove();
    }
  });

  it('keeps the root callback address clean after the initial redirect to the member landing route', async () => {
    const harness = await bootstrapWithSimulatedCallback({
      initialUrl: `/${CALLBACK_FRAGMENT}`,
      cleanedPath: '/',
      expectedPath: '/requests',
    });
    try {
      expect(window.location.pathname).toBe('/requests');
      expect(window.location.hash).toBe('');
    } finally {
      harness.appRef.destroy();
      harness.host.remove();
    }
  });

  it('restores one validated internal intent only after authentication succeeds', async () => {
    const harness = await bootstrapWithSimulatedCallback({
      initialUrl: `/${CALLBACK_FRAGMENT}`,
      cleanedPath: '/',
      initialIntent: DEEP_PATH,
      expectedPath: DEEP_PATH,
    });
    try {
      expect(window.location.pathname).toBe(DEEP_PATH);
      expect(window.location.hash).toBe('');
      expect(window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY)).toBeNull();
    } finally {
      harness.appRef.destroy();
      harness.host.remove();
    }
  });
});
