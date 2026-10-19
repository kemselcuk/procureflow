import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, finalize, map, of, switchMap } from 'rxjs';
import { Authentication } from '../authentication';
import {
  NotificationApi,
  NotificationItem,
  NotificationPage,
  NotificationReadReceipt,
} from './notification-api';

/** Matches the frozen API default; the panel only ever sends this size. */
export const NOTIFICATION_PAGE_SIZE = 20;

/** Known backend event types (`request.*`) mapped to readable English labels. */
export const NOTIFICATION_EVENT_LABELS: Readonly<Record<string, string>> = {
  'request.created': 'Request created',
  'request.edited': 'Request edited',
  'request.submitted': 'Request submitted',
  'request.department_approved': 'Department approval recorded',
  'request.approved': 'Request approved',
  'request.rejected': 'Request rejected',
  'request.cancelled': 'Request cancelled',
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

export interface NotificationList {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly items: readonly NotificationItem[];
}

export type NotificationsState =
  | { readonly kind: 'hidden' }
  | { readonly kind: 'loading' }
  | { readonly kind: 'forbidden' }
  | { readonly kind: 'error'; readonly message: string }
  | ({ readonly kind: 'empty' } & NotificationList)
  | ({ readonly kind: 'ready' } & NotificationList);

type LoadFailure = { readonly kind: 'forbidden' } | { readonly kind: 'error'; readonly message: string };

interface ListRequest {
  readonly generation: number;
  readonly key: string | null;
  readonly page: number;
  readonly size: number;
}

type ListOutcome =
  | { readonly request: ListRequest; readonly page: NotificationPage }
  | { readonly request: ListRequest; readonly failure: LoadFailure };

function loadFailure(error: unknown): LoadFailure {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 403) return { kind: 'forbidden' };
    if (error.status === 401) {
      return { kind: 'error', message: 'Your session is no longer valid. Sign in again to see your notifications.' };
    }
    if (error.status === 0) {
      return { kind: 'error', message: 'The notifications service is unreachable. Check the local backend and try again.' };
    }
    return { kind: 'error', message: `Notifications could not be loaded (HTTP ${error.status}). Try again.` };
  }
  return { kind: 'error', message: 'Notifications could not be loaded. Try again.' };
}

function readFailure(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 404) return 'This notification is no longer available. Reload the list.';
    if (error.status === 403) return 'Your current membership does not allow this action.';
    if (error.status === 401) return 'Your session is no longer valid. Sign in again to continue.';
    if (error.status === 0) {
      return 'The notifications service is unreachable. Check the local backend and try again.';
    }
    return `Marking as read failed (HTTP ${error.status}). Try again.`;
  }
  return 'Marking as read failed. Try again.';
}

@Component({
  selector: 'app-notifications-panel',
  standalone: true,
  templateUrl: './notifications-panel.html',
  styleUrl: './notifications-panel.css',
})
export class NotificationsPanel {
  private readonly api = inject(NotificationApi);
  private readonly auth = inject(Authentication);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly requests = new Subject<ListRequest>();
  private generation = 0;
  private activeKey: string | null = null;
  private requestedPage = 0;

  /** Server-confirmed first read times for this session, keyed by notification id.
   *  A refresh/navigation snapshot taken before the write commits may still say `readAt: null`;
   *  this overlay keeps a confirmed read from being shown as unread. Reset on every session change. */
  private readonly confirmedReads = new Map<string, string>();

  readonly state = signal<NotificationsState>({ kind: 'hidden' });
  readonly pendingReads = signal<ReadonlySet<string>>(new Set());
  readonly writeError = signal<string | null>(null);

  /** Company + identity of the eligible member; any change resets the panel.
   *  Visibility is a UX affordance only — the backend remains authoritative for authorization. */
  private readonly sessionKey = computed(() => {
    const member = this.auth.member();
    return this.auth.state() === 'authenticated' && member
      ? `${member.companyId}\u0000${member.identityId}`
      : null;
  });

  readonly listed = computed<NotificationList | null>(() => {
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
            ? of(null)
            : this.api.list(request.page, request.size).pipe(
                map((page): ListOutcome => ({ request, page })),
                catchError((error: unknown) => of<ListOutcome>({ request, failure: loadFailure(error) })),
              ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((outcome) => {
        if (outcome === null || outcome.request.generation !== this.generation) return;
        if ('failure' in outcome) {
          this.state.set(outcome.failure);
          return;
        }
        this.applyPage(outcome.page);
      });

    this.applySession(this.sessionKey());
    effect(() => this.applySession(this.sessionKey()));
  }

  refresh(): void {
    if (this.activeKey === null) return;
    this.load(this.requestedPage);
  }

  previousPage(): void {
    const list = this.listed();
    if (list === null || list.page <= 0) return;
    this.load(list.page - 1);
  }

  nextPage(): void {
    const list = this.listed();
    if (list === null || !this.hasNext(list)) return;
    this.load(list.page + 1);
  }

  hasNext(list: NotificationList): boolean {
    return (list.page + 1) * list.size < list.total;
  }

  pageCount(list: NotificationList): number {
    return Math.max(1, Math.ceil(list.total / Math.max(1, list.size)));
  }

  isPending(id: string): boolean {
    return this.pendingReads().has(id);
  }

  label(eventType: string): string {
    const known = Object.hasOwn(NOTIFICATION_EVENT_LABELS, eventType)
      ? NOTIFICATION_EVENT_LABELS[eventType]
      : undefined;
    if (known !== undefined) return known;
    const words = eventType
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .split(/[._\-\s]+/)
      .filter((word) => word.length > 0);
    if (words.length === 0) return 'Notification';
    const text = words.join(' ');
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  formatTimestamp(value: string): string {
    return TIMESTAMP_FORMAT.format(new Date(value));
  }

  markRead(item: NotificationItem): void {
    if (this.state().kind !== 'ready' || item.readAt !== null) return;
    if (this.pendingReads().has(item.id)) return;
    const generation = this.generation;
    this.writeError.set(null);
    this.handOffFocusFromReadAction(item.id);
    this.setPending(item.id, true);
    this.api
      .markRead(item.id)
      .pipe(
        finalize(() => {
          if (generation === this.generation) this.setPending(item.id, false);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (receipt) => {
          if (generation !== this.generation) return;
          this.applyReadReceipt(receipt);
          this.restoreFocusAfterRead(receipt.id);
        },
        error: (error: unknown) => {
          if (generation === this.generation) this.writeError.set(readFailure(error));
        },
      });
  }

  private applySession(key: string | null): void {
    if (key === this.activeKey) return;
    this.activeKey = key;
    this.generation += 1;
    this.requestedPage = 0;
    this.confirmedReads.clear();
    this.pendingReads.set(new Set());
    this.writeError.set(null);
    if (key === null) {
      this.state.set({ kind: 'hidden' });
      // Cancels any in-flight list request so late responses cannot repopulate the panel.
      this.requests.next({ generation: this.generation, key: null, page: 0, size: NOTIFICATION_PAGE_SIZE });
      return;
    }
    this.state.set({ kind: 'loading' });
    this.requests.next({ generation: this.generation, key, page: 0, size: NOTIFICATION_PAGE_SIZE });
  }

  private load(page: number): void {
    if (this.activeKey === null) return;
    this.writeError.set(null);
    this.requestedPage = page;
    this.state.set({ kind: 'loading' });
    this.requests.next({ generation: this.generation, key: this.activeKey, page, size: NOTIFICATION_PAGE_SIZE });
  }

  private applyPage(page: NotificationPage): void {
    this.requestedPage = page.page;
    const items = page.items.map((item) => {
      if (item.readAt !== null) return item;
      const confirmed = this.confirmedReads.get(item.id);
      return confirmed === undefined ? item : { ...item, readAt: confirmed };
    });
    const list: NotificationList = { page: page.page, size: page.size, total: page.total, items };
    this.state.set(items.length === 0 ? { kind: 'empty', ...list } : { kind: 'ready', ...list });
  }

  /** Applies only the server-returned read time and never overwrites an existing one.
   *  The receipt is retained for this session even if the list is currently loading or failed,
   *  so a snapshot taken before the commit cannot make the row unread again. */
  private applyReadReceipt(receipt: NotificationReadReceipt): void {
    this.confirmedReads.set(receipt.id, receipt.readAt);
    const state = this.state();
    if (state.kind !== 'ready') return;
    const items = state.items.map((item) =>
      item.id === receipt.id && item.readAt === null ? { ...item, readAt: receipt.readAt } : item,
    );
    this.state.set({ ...state, items });
  }

  /** Native browsers blur the focused control the moment it turns disabled, which would drop
   *  the reader's keyboard position to the page body during the pending render. Hand the
   *  position to the row first, and only while this row's read action actually owns focus. */
  private handOffFocusFromReadAction(itemId: string): void {
    const row = this.row(itemId);
    if (row === null) return;
    if (row.ownerDocument.activeElement !== row.querySelector('button.read-action')) return;
    row.focus();
  }

  /** Completion-phase safety net: keep the reader on the updated row while the row still
   *  holds the focus position. Focus moved elsewhere (another control, another page, a
   *  changed session) or a removed row is left untouched. */
  private restoreFocusAfterRead(itemId: string): void {
    const active = this.host.nativeElement.ownerDocument.activeElement;
    if (!(active instanceof HTMLElement)) return;
    const focusedRow = active.closest('li.item');
    if (focusedRow === null || focusedRow.getAttribute('data-notification-id') !== itemId) return;
    this.row(itemId)?.focus();
  }

  private row(itemId: string): HTMLElement | null {
    return this.host.nativeElement.querySelector<HTMLElement>(`li.item[data-notification-id="${itemId}"]`);
  }

  private setPending(id: string, pending: boolean): void {
    const next = new Set(this.pendingReads());
    if (pending) next.add(id);
    else next.delete(id);
    this.pendingReads.set(next);
  }
}
