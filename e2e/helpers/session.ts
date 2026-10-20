import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { ApiProbe } from './api';
import { BASE_URL, DEMO_PASSWORD, jwtFacts, username, type Account } from './config';
import { facts, flushFacts } from './facts';

const APP_ORIGIN = new URL(BASE_URL).origin;
const SECRET_CALLBACK_NAMES = new Set(['code', 'state', 'session_state', 'iss']);

export interface OpenedSession {
  context: BrowserContext;
  page: Page;
  probe: ApiProbe;
}

/** Tracks every context it creates so a failed journey still closes its authenticated state. */
export class BrowserSessions {
  private readonly contexts: BrowserContext[] = [];
  private readonly browser: Browser;

  constructor(browser: Browser) {
    this.browser = browser;
  }

  async open(viewport = { width: 1280, height: 900 }): Promise<OpenedSession> {
    const context = await this.browser.newContext({
      baseURL: BASE_URL,
      viewport,
      colorScheme: 'light',
      locale: 'en-GB',
      timezoneId: 'UTC',
    });
    this.contexts.push(context);
    const page = await context.newPage();
    return { context, page, probe: new ApiProbe(page) };
  }

  async login(account: Account, viewport?: { width: number; height: number }): Promise<OpenedSession> {
    const opened = await this.open(viewport);
    await loginAt(opened.page, account);
    return opened;
  }

  async closeAll(): Promise<void> {
    const contexts = this.contexts.splice(0);
    await Promise.all(contexts.map(async (context) => {
      try {
        await context.close();
      } catch {
        // A browser close during teardown is not a product result; the runner reports cleanup.
      }
    }));
  }
}

/**
 * Drives the real Keycloak login form. The caller decides whether to precede this with a
 * navigation so an anonymous deep-link intent can survive the exact root callback.
 */
export async function signIn(page: Page, account: Account): Promise<void> {
  if (!page.url().startsWith(APP_ORIGIN)) throw new Error('Sign-in must start on the product origin');
  const panel = page.locator('app-session-panel');
  await expect(panel.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await panel.getByRole('button', { name: 'Sign in' }).click();
  await page.locator('#username').waitFor({ state: 'visible', timeout: 60_000 });
  await page.locator('#username').fill(username(account));
  await page.locator('#password').fill(DEMO_PASSWORD);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === APP_ORIGIN && !url.pathname.startsWith('/realms/'), {
    timeout: 60_000,
  });
  await expect(page.locator('#account-title')).toBeVisible({ timeout: 60_000 });
}

/** Standard member landing: every active fictional member may read their own request list. */
export async function loginAt(page: Page, account: Account): Promise<void> {
  await page.goto('/welcome');
  await signIn(page, account);
  await expect(page.locator('#my-requests-title')).toBeVisible({ timeout: 60_000 });
}

export interface AuthRequestFacts {
  redirectUriExactRoot: boolean;
  responseTypeCode: boolean;
  codeChallengeMethodS256: boolean;
  codeChallengePresent: boolean;
  noncePresent: boolean;
  scopeHasOpenId: boolean;
}

/** Captures the actual browser authorization request without retaining its query values. */
export class AuthRequestCapture {
  private captured: AuthRequestFacts | null = null;

  constructor(page: Page) {
    page.on('request', (request) => {
      try {
        const url = new URL(request.url());
        if (!url.pathname.endsWith('/protocol/openid-connect/auth')) return;
        const params = url.searchParams;
        if (params.get('prompt') !== null) return;
        this.captured = {
          redirectUriExactRoot: params.get('redirect_uri') === `${BASE_URL}/`,
          responseTypeCode: params.get('response_type') === 'code',
          codeChallengeMethodS256: params.get('code_challenge_method') === 'S256',
          codeChallengePresent: (params.get('code_challenge') ?? '').length >= 32,
          noncePresent: (params.get('nonce') ?? '').length >= 16,
          scopeHasOpenId: (params.get('scope') ?? '').split(' ').includes('openid'),
        };
      } catch {
        // A malformed non-product URL is not an authorization observation.
      }
    });
  }

  get facts(): AuthRequestFacts | null {
    return this.captured;
  }
}

export interface LocationFacts {
  pathname: string;
  queryNames: string[];
  hashNames: string[];
  clean: boolean;
}

/** Reads names only; no callback value or full URL is retained. */
export function locationFacts(page: Page): LocationFacts {
  const url = new URL(page.url());
  const queryNames = [...url.searchParams.keys()];
  const hash = url.hash.startsWith('#') ? url.hash.slice(1) : url.hash;
  const hashNames = [...new URLSearchParams(hash).keys()];
  const clean = url.origin === APP_ORIGIN
    && url.search === ''
    && url.hash === ''
    && ![...queryNames, ...hashNames].some((name) => SECRET_CALLBACK_NAMES.has(name));
  return { pathname: url.pathname, queryNames, hashNames, clean };
}

/** Waits for the router to settle on one clean same-origin product path. */
export async function waitForCleanPath(page: Page, expectedPath: string): Promise<LocationFacts> {
  const deadline = Date.now() + 45_000;
  while (Date.now() < deadline) {
    const observed = locationFacts(page);
    if (observed.pathname === expectedPath && observed.clean) {
      facts.callback['lastLocation'] = {
        pathname: observed.pathname,
        queryNames: observed.queryNames,
        hashNames: observed.hashNames,
        clean: observed.clean,
      };
      flushFacts();
      return observed;
    }
    await page.waitForTimeout(100);
  }
  const last = locationFacts(page);
  throw new Error(
    `The application did not settle on the expected clean same-origin path `
    + `(observed pathname=${last.pathname}, queryNames=${last.queryNames.join(',') || 'none'}, `
    + `hashNames=${last.hashNames.join(',') || 'none'})`,
  );
}

/** Waits for any clean same-origin product route after a history navigation. */
export async function waitForCleanApp(page: Page): Promise<LocationFacts> {
  const deadline = Date.now() + 45_000;
  while (Date.now() < deadline) {
    const observed = locationFacts(page);
    if (observed.clean && observed.pathname.startsWith('/')) {
      return observed;
    }
    await page.waitForTimeout(100);
  }
  throw new Error('The application did not settle on a clean same-origin product route');
}

export async function assertNoHorizontalOverflow(page: Page, viewportWidth: number): Promise<{
  viewportWidth: number;
  documentWidth: number;
  bodyWidth: number;
}> {
  const measured = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
  }));
  expect(measured.documentWidth, 'document width must fit the narrow viewport').toBeLessThanOrEqual(viewportWidth + 1);
  expect(measured.bodyWidth, 'body width must fit the narrow viewport').toBeLessThanOrEqual(viewportWidth + 1);
  return { viewportWidth, ...measured };
}

export async function expectVisibleFocus(locator: ReturnType<Page['locator']>): Promise<{
  outlineStyle: string;
  outlineWidth: string;
}> {
  await expect(locator).toBeFocused();
  const style = await locator.evaluate((element) => {
    const computed = getComputedStyle(element);
    return { outlineStyle: computed.outlineStyle, outlineWidth: computed.outlineWidth };
  });
  expect(style.outlineStyle, 'keyboard focus must have a visible outline').not.toBe('none');
  expect(style.outlineWidth, 'keyboard focus outline must be non-zero').not.toBe('0px');
  return style;
}

export function recordIdentity(
  slot: string,
  probe: ApiProbe,
  me: { identityId: string },
): void {
  facts.identities[slot] = { ...jwtFacts(probe.accessValue), identityId: me.identityId };
  flushFacts();
}
