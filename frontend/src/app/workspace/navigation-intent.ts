import { Injectable } from '@angular/core';

/** Per-tab, non-secret location key for one pending authenticated deep-link target. */
export const NAVIGATION_INTENT_STORAGE_KEY = 'procureflow.navigation-intent.v1';
export const NAVIGATION_INTENT_VERSION = 1;
export const NAVIGATION_INTENT_TTL_MS = 10 * 60_000;

const MAX_PATH_LENGTH = 256;
const UUID_REQUEST_PATH =
  /^\/requests\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATIC_PATHS = new Set([
  '/requests',
  '/requests/new',
  '/approvals',
  '/budgets',
  '/administration',
  '/administration/audit',
  '/notifications',
]);

interface StoredIntent {
  readonly version: number;
  readonly path: string;
  readonly expiresAt: number;
}

function storageOrNull(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * A navigation intent is only a pathname, never a token, identity, draft or credential. It is
 * written before the Keycloak redirect and consumed once after the callback has been validated and
 * exchanged by keycloak-js. Every stored value is revalidated on read because sessionStorage can
 * be changed by the same tab.
 */
export function isAllowedNavigationIntentPath(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_PATH_LENGTH) return false;
  if (!value.startsWith('/') || value.startsWith('//')) return false;
  if (value.includes('?') || value.includes('#') || value.includes('%') || value.includes('\\')) return false;
  if (value.includes('//') || value.includes('..')) return false;
  if (/[\u0000-\u001f\u007f]/.test(value)) return false;
  return STATIC_PATHS.has(value) || UUID_REQUEST_PATH.test(value);
}

/**
 * Owns the single non-secret deep-link intent that must survive an unavoidable callback at `/`.
 *
 * The normal redirect URI remains the exact origin root. Only a whitelisted product pathname is
 * retained, with a bounded lifetime and one-shot consumption. Invalid, expired or unavailable
 * storage falls back to the existing root landing flow without raising authentication errors.
 */
@Injectable({ providedIn: 'root' })
export class NavigationIntent {
  /**
   * Stores the current internal target when it is a known member route. A callback at `/` or a
   * public page intentionally does not overwrite an earlier valid intent.
   */
  capture(pathname: string, now: number = Date.now()): boolean {
    if (!isAllowedNavigationIntentPath(pathname)) return false;
    const storage = storageOrNull();
    if (storage === null) return false;
    const record: StoredIntent = {
      version: NAVIGATION_INTENT_VERSION,
      path: pathname,
      expiresAt: now + NAVIGATION_INTENT_TTL_MS,
    };
    try {
      storage.setItem(NAVIGATION_INTENT_STORAGE_KEY, JSON.stringify(record));
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Reads and removes the intent atomically for this navigation. Any malformed, expired or
   * out-of-range value is discarded and never returned.
   */
  consume(now: number = Date.now()): string | null {
    const storage = storageOrNull();
    if (storage === null) return null;
    let raw: string | null;
    try {
      raw = storage.getItem(NAVIGATION_INTENT_STORAGE_KEY);
    } catch {
      return null;
    }
    if (raw === null) return null;
    try {
      storage.removeItem(NAVIGATION_INTENT_STORAGE_KEY);
    } catch {
      // A failed cleanup must not surface as an authentication error; the record will expire.
    }
    let record: unknown;
    try {
      record = JSON.parse(raw);
    } catch {
      return null;
    }
    if (typeof record !== 'object' || record === null) return null;
    const stored = record as Partial<StoredIntent>;
    if (stored.version !== NAVIGATION_INTENT_VERSION) return null;
    if (typeof stored.expiresAt !== 'number' || !Number.isFinite(stored.expiresAt)) return null;
    if (stored.expiresAt <= now || stored.expiresAt > now + NAVIGATION_INTENT_TTL_MS) return null;
    return isAllowedNavigationIntentPath(stored.path) ? stored.path : null;
  }

  /**
   * Applies a pending path before the Angular router performs its initial navigation. The browser
   * address is updated in place, so the router reads a clean same-origin path and no OIDC fragment
   * is reintroduced. Anonymous or denied sessions keep the intent for a later explicit sign-in.
   */
  restoreAfterAuthentication(authenticated: boolean): string | null {
    if (!authenticated) return null;
    const path = this.consume();
    if (path === null) return null;
    try {
      if (window.location.pathname !== path) {
        window.history.replaceState(window.history.state, '', path);
      }
      return path;
    } catch {
      return null;
    }
  }

  /** Clears the intent on logout, session invalidation or an explicit one-shot consumption. */
  clear(): void {
    const storage = storageOrNull();
    if (storage === null) return;
    try {
      storage.removeItem(NAVIGATION_INTENT_STORAGE_KEY);
    } catch {
      // Storage may be unavailable; there is nothing else to clear.
    }
  }
}
