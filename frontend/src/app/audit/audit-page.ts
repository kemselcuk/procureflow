import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, map, of, switchMap } from 'rxjs';
import { WorkspaceSession } from '../workspace/workspace-session';
import { AUDIT_PAGE_SIZE, AuditApi, AuditEntry, AuditPageEnvelope } from './audit-api';

/** Audit action codes written by the backend; anything unknown falls back to a readable label. */
export const AUDIT_ACTION_LABELS: Readonly<Record<string, string>> = {
  'request.created': 'Request created',
  'request.edited': 'Request edited',
  'request.submitted': 'Request submitted',
  'request.department_approved': 'Department approval recorded',
  'request.approved': 'Request approved',
  'request.rejected': 'Request rejected',
  'request.cancelled': 'Request cancelled',
  'department.created': 'Department created',
  'membership.created': 'Membership created',
  'membership.updated': 'Membership updated',
  'budget.limit_set': 'Budget limit set',
};

const TIMESTAMP_FORMAT = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
  timeZone: 'UTC',
  timeZoneName: 'short',
});

export interface AuditList {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly items: readonly AuditEntry[];
}

export type AuditState =
  | { readonly kind: 'hidden' }
  | { readonly kind: 'denied' }
  | { readonly kind: 'loading' }
  | { readonly kind: 'forbidden' }
  | { readonly kind: 'error'; readonly message: string }
  | ({ readonly kind: 'empty' } & AuditList)
  | ({ readonly kind: 'ready' } & AuditList);

/**
 * Session view derived only from the current `/api/me` profile. The compact sign-in snapshot
 * of `Authentication` is never consulted for visibility.
 */
type SessionView =
  | { readonly kind: 'pending'; readonly key: string }
  | { readonly kind: 'blocked'; readonly key: string; readonly message: string }
  | { readonly kind: 'ready'; readonly key: string; readonly admin: boolean };

type LoadFailure = { readonly kind: 'forbidden' } | { readonly kind: 'error'; readonly message: string };

interface PageRequest {
  readonly generation: number;
  readonly key: string | null;
  readonly page: number;
  readonly size: number;
}

type PageOutcome =
  | { readonly request: PageRequest; readonly page: AuditPageEnvelope }
  | { readonly request: PageRequest; readonly failure: LoadFailure };

function loadFailure(error: unknown): LoadFailure {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 403) return { kind: 'forbidden' };
    if (error.status === 401) {
      return { kind: 'error', message: 'Your session is no longer valid. Sign in again to view audit records.' };
    }
    if (error.status === 400) {
      return { kind: 'error', message: 'The audit request was rejected (HTTP 400). Try again.' };
    }
    if (error.status === 0) {
      return { kind: 'error', message: 'The audit service is unreachable. Check the local backend and try again.' };
    }
    return { kind: 'error', message: `Audit records could not be loaded (HTTP ${error.status}). Try again.` };
  }
  return { kind: 'error', message: 'Audit records could not be loaded. Try again.' };
}

/**
 * Standalone read-only company-admin audit page mounted on `/administration/audit`.
 *
 * Rendering rules that matter for acceptance:
 * - Only an authenticated member whose current server-validated membership profile (from
 *   `/api/me` through `WorkspaceSession`) lists COMPANY_ADMIN fetches or renders; neither token
 *   claims nor the initial sign-in snapshot are inspected here.
 * - The session key is owner + profile generation + roles, so logout, identity/company changes,
 *   role loss/gain and membership refreshes clear rows, pagination and any in-flight request.
 *   A transient loading state on an unchanged profile does not reset the visible page.
 * - A superseded page request is cancelled by `switchMap`, and every applied outcome is checked
 *   against the generation, so late successes or errors cannot repopulate changed sessions.
 * - Rows are replaced only when a new outcome arrives or the session changes; while a request is
 *   pending the current table stays in the document with its pager controls disabled, so
 *   keyboard focus is not lost to a removed control.
 */
@Component({
  selector: 'app-audit-page',
  standalone: true,
  templateUrl: './audit-page.html',
  styleUrl: './audit-page.css',
})
export class AuditPage {
  private readonly api = inject(AuditApi);
  private readonly session = inject(WorkspaceSession);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requests = new Subject<PageRequest>();
  private generation = 0;
  private activeKey: string | null = null;
  private activeAdmin = false;
  private requestedPage = 0;

  readonly state = signal<AuditState>({ kind: 'hidden' });
  readonly pending = signal(false);

  private readonly sessionView = computed<SessionView | null>(() => {
    const owner = this.session.ownerKey();
    if (owner === null) return null;
    const profile = this.session.profile();
    const sessionState = this.session.state();
    if (profile === null) {
      const key = `${owner}\u0000${this.session.generation()}\u0000none:${sessionState}`;
      if (sessionState === 'forbidden' || sessionState === 'error') {
        return {
          kind: 'blocked',
          key,
          message:
            this.session.errorMessage() ??
            'Your current company membership could not be verified. Sign in again or retry.',
        };
      }
      return { kind: 'pending', key };
    }
    const roles = [...profile.roles].sort();
    return {
      kind: 'ready',
      key: `${owner}\u0000${this.session.generation()}\u0000${roles.join(',')}`,
      admin: profile.roles.includes('COMPANY_ADMIN'),
    };
  });

  readonly listed = computed<AuditList | null>(() => {
    const state = this.state();
    return state.kind === 'ready' || state.kind === 'empty' ? state : null;
  });

  readonly errorMessage = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
  });

  constructor() {
    this.requests
      .pipe(
        switchMap((request) =>
          request.key === null
            ? of<PageOutcome | null>(null)
            : this.api.list(request.page, request.size).pipe(
                map((page): PageOutcome => ({ request, page })),
                catchError((error: unknown) => of<PageOutcome>({ request, failure: loadFailure(error) })),
              ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((outcome) => {
        if (outcome === null || outcome.request.generation !== this.generation) return;
        this.pending.set(false);
        if ('failure' in outcome) {
          this.state.set(outcome.failure);
          return;
        }
        this.applyPage(outcome.page);
      });

    this.applySession(this.sessionView());
    effect(() => this.applySession(this.sessionView()));
  }

  /** Explicit reload of the requested page; a newer refresh supersedes any in-flight load. */
  refresh(): void {
    this.start(this.requestedPage);
  }

  /**
   * Retry from an error or forbidden panel. A current admin retries the requested page and, for
   * a backend-forbidden state, also re-reads the membership through `WorkspaceSession`; a
   * blocked profile only re-reads the membership. The session rules clear or reload on change.
   */
  retry(): void {
    if (this.session.ownerKey() === null) return;
    const view = this.sessionView();
    if (view !== null && view.kind === 'ready' && view.admin) {
      if (this.state().kind === 'forbidden') void this.session.refresh();
      this.start(this.requestedPage);
      return;
    }
    void this.session.refresh();
  }

  previousPage(): void {
    const list = this.listed();
    if (list === null || this.pending() || list.page <= 0) return;
    this.start(list.page - 1);
  }

  nextPage(): void {
    const list = this.listed();
    if (list === null || this.pending() || !this.hasNext(list)) return;
    this.start(list.page + 1);
  }

  firstPage(): void {
    const list = this.listed();
    if (list === null || this.pending() || list.page <= 0) return;
    this.start(0);
  }

  hasNext(list: AuditList): boolean {
    return (list.page + 1) * Math.max(1, list.size) < list.total;
  }

  pageCount(list: AuditList): number {
    return Math.max(1, Math.ceil(list.total / Math.max(1, list.size)));
  }

  /** Known codes map to readable English; unknown or future codes degrade to a plain label. */
  actionLabel(action: string): string {
    const known = Object.hasOwn(AUDIT_ACTION_LABELS, action) ? AUDIT_ACTION_LABELS[action] : undefined;
    if (known !== undefined) return known;
    const words = action
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .split(/[._\-\s]+/)
      .filter((word) => word.length > 0);
    if (words.length === 0) return 'Audit event';
    const text = words.join(' ');
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  /** Server timestamps are shown in UTC; unexpected text is returned unchanged instead of crashing. */
  formatTimestamp(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return TIMESTAMP_FORMAT.format(date);
  }

  private applySession(view: SessionView | null): void {
    const key = view?.key ?? null;
    if (key === this.activeKey) return;
    this.activeKey = key;
    this.activeAdmin = view?.kind === 'ready' && view.admin;
    this.generation += 1;
    this.requestedPage = 0;
    this.pending.set(false);
    if (view === null) {
      this.state.set({ kind: 'hidden' });
      // Cancels any in-flight page request so late data or errors from the previous session
      // cannot land, and so an anonymous session never issues an audit request.
      this.requests.next({ generation: this.generation, key: null, page: 0, size: AUDIT_PAGE_SIZE });
      return;
    }
    if (view.kind === 'pending') {
      this.state.set({ kind: 'loading' });
      this.requests.next({ generation: this.generation, key: null, page: 0, size: AUDIT_PAGE_SIZE });
      return;
    }
    if (view.kind === 'blocked') {
      this.state.set({ kind: 'error', message: view.message });
      this.requests.next({ generation: this.generation, key: null, page: 0, size: AUDIT_PAGE_SIZE });
      return;
    }
    if (!view.admin) {
      this.state.set({ kind: 'denied' });
      // Cancels any in-flight page request so late data or errors from the previous session
      // cannot land, and so a non-admin session never issues an audit request.
      this.requests.next({ generation: this.generation, key: null, page: 0, size: AUDIT_PAGE_SIZE });
      return;
    }
    this.state.set({ kind: 'loading' });
    this.requests.next({ generation: this.generation, key, page: 0, size: AUDIT_PAGE_SIZE });
  }

  private start(page: number): void {
    if (this.activeKey === null || !this.activeAdmin) return;
    this.requestedPage = page;
    this.pending.set(true);
    this.requests.next({ generation: this.generation, key: this.activeKey, page, size: AUDIT_PAGE_SIZE });
  }

  private applyPage(page: AuditPageEnvelope): void {
    this.requestedPage = page.page;
    const list: AuditList = { page: page.page, size: page.size, total: page.total, items: page.items };
    this.state.set(page.items.length === 0 ? { kind: 'empty', ...list } : { kind: 'ready', ...list });
  }
}
