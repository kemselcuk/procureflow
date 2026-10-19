import { TestBed } from '@angular/core/testing';
import {
  isAllowedNavigationIntentPath,
  NAVIGATION_INTENT_STORAGE_KEY,
  NAVIGATION_INTENT_TTL_MS,
  NavigationIntent,
} from './navigation-intent';

const REQUEST_ID = '0f0e0d0c-0b0a-4908-8706-050403020100';
const DEEP_PATH = `/requests/${REQUEST_ID}`;

function rawRecord(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({ version: 1, path: DEEP_PATH, expiresAt: Date.now() + 60_000, ...overrides });
}

describe('NavigationIntent', () => {
  let intent: NavigationIntent;

  beforeEach(() => {
    window.history.replaceState(window.history.state, '', '/');
    window.sessionStorage.clear();
    intent = TestBed.inject(NavigationIntent);
  });

  afterEach(() => {
    window.sessionStorage.clear();
    window.history.replaceState(window.history.state, '', '/');
    vi.restoreAllMocks();
  });

  it('stores and consumes one bounded internal request detail path', () => {
    expect(intent.capture(DEEP_PATH)).toBe(true);
    expect(intent.consume()).toBe(DEEP_PATH);
    expect(intent.consume()).toBeNull();
    expect(window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY)).toBeNull();
  });

  it.each([
    '/requests',
    '/requests/new',
    '/approvals',
    '/budgets',
    '/administration',
    '/administration/audit',
    '/notifications',
  ])('accepts the bounded product path %s', (path) => {
    expect(intent.capture(path)).toBe(true);
    expect(intent.consume()).toBe(path);
  });

  it.each([
    '',
    '/',
    '/welcome',
    'https://attacker.invalid/requests',
    '//attacker.invalid/requests',
    '\\requests',
    '/\\attacker.invalid',
    '/requests/../administration',
    '/requests/%2e%2e/administration',
    '/requests?returnUrl=https://attacker.invalid',
    '/requests#fragment',
    '/requests/not-a-uuid',
    `/requests/${REQUEST_ID}/extra`,
    '/unknown',
    '/admin',
    '/notifications\u0000',
    `/${'a'.repeat(300)}`,
  ])('rejects an unsafe or unknown path %s', (path) => {
    expect(isAllowedNavigationIntentPath(path)).toBe(false);
    expect(intent.capture(path)).toBe(false);
    expect(intent.consume()).toBeNull();
  });

  it('does not overwrite an earlier deep intent when the callback lands on the public root', () => {
    expect(intent.capture(DEEP_PATH)).toBe(true);
    expect(intent.capture('/')).toBe(false);
    expect(intent.capture('/welcome')).toBe(false);
    expect(intent.consume()).toBe(DEEP_PATH);
  });

  it('discards malformed, wrong-version and expired records', () => {
    window.sessionStorage.setItem(NAVIGATION_INTENT_STORAGE_KEY, '{not-json');
    expect(intent.consume()).toBeNull();
    window.sessionStorage.setItem(NAVIGATION_INTENT_STORAGE_KEY, rawRecord({ version: 2 }));
    expect(intent.consume()).toBeNull();
    window.sessionStorage.setItem(NAVIGATION_INTENT_STORAGE_KEY, rawRecord({ expiresAt: Date.now() - 1 }));
    expect(intent.consume()).toBeNull();
    expect(window.sessionStorage.getItem(NAVIGATION_INTENT_STORAGE_KEY)).toBeNull();
  });

  it('rejects an unbounded expiry and an external path even when storage is tampered', () => {
    const origin = window.location.origin;
    window.sessionStorage.setItem(
      NAVIGATION_INTENT_STORAGE_KEY,
      rawRecord({ expiresAt: Date.now() + NAVIGATION_INTENT_TTL_MS + 1 }),
    );
    expect(intent.consume()).toBeNull();
    window.sessionStorage.setItem(
      NAVIGATION_INTENT_STORAGE_KEY,
      rawRecord({ path: 'https://attacker.invalid/requests' }),
    );
    expect(intent.consume()).toBeNull();
    expect(window.location.origin).toBe(origin);
  });

  it('clears the intent on logout or explicit invalidation', () => {
    expect(intent.capture(DEEP_PATH)).toBe(true);
    intent.clear();
    expect(intent.consume()).toBeNull();
  });

  it('restores a pending path only after authentication and never changes origin', () => {
    window.history.replaceState(window.history.state, '', '/');
    expect(intent.capture(DEEP_PATH)).toBe(true);
    expect(intent.restoreAfterAuthentication(false)).toBeNull();
    expect(window.location.pathname).toBe('/');
    expect(intent.consume()).toBe(DEEP_PATH);

    expect(intent.capture(DEEP_PATH)).toBe(true);
    const origin = window.location.origin;
    expect(intent.restoreAfterAuthentication(true)).toBe(DEEP_PATH);
    expect(window.location.pathname).toBe(DEEP_PATH);
    expect(window.location.origin).toBe(origin);
    expect(intent.consume()).toBeNull();
  });

  it('survives unavailable storage without throwing or authenticating differently', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(() => intent.capture(DEEP_PATH)).not.toThrow();
    expect(intent.capture(DEEP_PATH)).toBe(false);
    expect(intent.consume()).toBeNull();
    expect(intent.restoreAfterAuthentication(true)).toBeNull();
    expect(() => intent.clear()).not.toThrow();
  });
});
