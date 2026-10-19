import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Failure, failureFor } from '../workspace/failure';
import { formatAmount, formatInstant, shortId, statusLabel } from '../workspace/format';
import { WorkspaceProfile } from '../workspace/workspace-api';
import { WorkspaceSession } from '../workspace/workspace-session';
import { ApprovalDecision } from './approval-decision';
import { RequestApi, RequestPage, RequestStatus, RequestSummary, RequestView } from './request-api';

export const APPROVAL_PAGE_SIZE = 20;
const INBOX_STATUSES: readonly RequestStatus[] = ['AWAITING_DEPARTMENT_APPROVAL', 'AWAITING_FINANCE_APPROVAL'];

type InboxState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly page: RequestPage }
  | { readonly kind: 'empty'; readonly page: RequestPage }
  | { readonly kind: 'forbidden'; readonly message: string }
  | { readonly kind: 'error'; readonly failure: Failure };

type ReviewState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly request: RequestView }
  | { readonly kind: 'not-found'; readonly message: string }
  | { readonly kind: 'forbidden'; readonly message: string }
  | { readonly kind: 'error'; readonly failure: Failure };

interface ExpandedReview {
  readonly id: string;
  readonly state: ReviewState;
}

/**
 * Actionable current-step inbox. Rows open the canonical request before any decision; the
 * shared decision control then derives department/finance authority from that response and the
 * current `/api/me`. The backend still rechecks everything and owns the expected version.
 */
@Component({
  selector: 'app-approval-inbox-page',
  standalone: true,
  imports: [RouterLink, ApprovalDecision],
  templateUrl: './approval-inbox-page.html',
  styleUrl: './approval-inbox-page.css',
})
export class ApprovalInboxPage {
  private readonly api = inject(RequestApi);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(WorkspaceSession);

  readonly pageSize = APPROVAL_PAGE_SIZE;
  readonly statuses = INBOX_STATUSES;
  readonly state = signal<InboxState>({ kind: 'loading' });
  readonly status = signal<RequestStatus | null>(null);
  readonly pageNumber = signal(0);
  readonly expanded = signal<ExpandedReview | null>(null);
  readonly notice = signal<string | null>(null);
  readonly amount = formatAmount;
  readonly instant = formatInstant;
  readonly label = statusLabel;
  readonly shortId = shortId;

  readonly listed = computed(() => {
    const state = this.state();
    return state.kind === 'ready' || state.kind === 'empty' ? state.page : null;
  });
  readonly failure = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.failure : null;
  });
  readonly forbiddenMessage = computed(() => {
    const state = this.state();
    return state.kind === 'forbidden' ? state.message : null;
  });
  readonly reviewFailure = computed(() => {
    const expanded = this.expanded();
    return expanded?.state.kind === 'error' ? expanded.state.failure : null;
  });
  readonly reviewNotFound = computed(() => {
    const expanded = this.expanded();
    return expanded?.state.kind === 'not-found' ? expanded.state.message : null;
  });
  readonly reviewForbidden = computed(() => {
    const expanded = this.expanded();
    return expanded?.state.kind === 'forbidden' ? expanded.state.message : null;
  });
  readonly reviewRequest = computed(() => {
    const state = this.expanded()?.state;
    return state?.kind === 'ready' ? state.request : null;
  });

  private generation = 0;
  private reviewGeneration = 0;
  private lastOwnerKey: string | null | undefined = undefined;
  private lastSessionGeneration = -1;

  constructor() {
    effect(() => {
      const ownerKey = this.session.ownerKey();
      const generation = this.session.generation();
      const profile = this.session.profile();
      if (ownerKey === this.lastOwnerKey && generation === this.lastSessionGeneration) return;
      const ownerChanged = ownerKey !== this.lastOwnerKey;
      this.lastOwnerKey = ownerKey;
      this.lastSessionGeneration = generation;
      this.reviewGeneration += 1;
      this.expanded.set(null);
      this.notice.set(null);
      if (profile === null) {
        this.generation += 1;
        this.state.set({ kind: 'loading' });
        return;
      }
      if (ownerChanged) {
        this.pageNumber.set(0);
        this.status.set(null);
      }
      if (!this.canReachInbox(profile)) {
        this.generation += 1;
        this.state.set({
          kind: 'forbidden',
          message: 'The approval inbox is available to department managers and finance approvers.',
        });
        return;
      }
      this.load();
    });
  }

  onStatusChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.status.set(value === '' ? null : (value as RequestStatus));
    this.pageNumber.set(0);
    this.load();
  }

  previousPage(): void {
    const page = this.listed();
    if (page === null || page.page <= 0) return;
    this.pageNumber.set(page.page - 1);
    this.load();
  }

  nextPage(): void {
    const page = this.listed();
    if (page === null || !this.hasNext(page)) return;
    this.pageNumber.set(page.page + 1);
    this.load();
  }

  async refresh(): Promise<void> {
    const generation = this.session.generation();
    await this.session.refresh();
    const profile = this.session.profile();
    if (generation === this.session.generation() && profile !== null && this.canReachInbox(profile)) this.load();
  }

  hasNext(page: RequestPage): boolean {
    return (page.page + 1) * page.size < page.total;
  }

  pageCount(page: RequestPage): number {
    return Math.max(1, Math.ceil(page.total / Math.max(1, page.size)));
  }

  review(item: RequestSummary): void {
    const currentlyExpanded = this.expanded();
    if (currentlyExpanded?.id === item.id) {
      this.expanded.set(null);
      this.reviewGeneration += 1;
      return;
    }
    this.loadReview(item.id);
  }

  retryReview(): void {
    const expanded = this.expanded();
    if (expanded !== null) this.loadReview(expanded.id);
  }

  decisionChanged(): void {
    this.notice.set('Decision recorded. The inbox and request were reloaded.');
    this.expanded.set(null);
    this.reviewGeneration += 1;
    this.load();
  }

  reloadReview(): void {
    const expanded = this.expanded();
    if (expanded !== null) this.loadReview(expanded.id);
  }

  canDecide(request: RequestView): boolean {
    const profile = this.session.profile();
    if (profile === null) return false;
    const owner = request.requesterIdentityId === profile.identityId;
    if (request.status === 'AWAITING_DEPARTMENT_APPROVAL') {
      return (
        !owner &&
        profile.roles.includes('DEPARTMENT_MANAGER') &&
        profile.managedDepartmentIds.includes(request.departmentId)
      );
    }
    if (request.status === 'AWAITING_FINANCE_APPROVAL') {
      return (
        !owner &&
        request.amount > 1000 &&
        profile.roles.includes('FINANCE_APPROVER') &&
        request.departmentApproverIdentityId !== null &&
        request.departmentApproverIdentityId !== profile.identityId
      );
    }
    return false;
  }

  departmentLabel(request: RequestSummary | RequestView): string {
    const profile = this.session.profile();
    if (profile !== null && request.departmentId === profile.departmentId) return profile.departmentName;
    return `Department ${shortId(request.departmentId)}`;
  }

  private canReachInbox(profile: WorkspaceProfile): boolean {
    return profile.roles.includes('DEPARTMENT_MANAGER') || profile.roles.includes('FINANCE_APPROVER');
  }

  private load(): void {
    const profile = this.session.profile();
    if (profile === null || !this.canReachInbox(profile)) return;
    const generation = ++this.generation;
    const ownerKey = this.session.ownerKey();
    const sessionGeneration = this.session.generation();
    const page = this.pageNumber();
    const status = this.status();
    this.state.set({ kind: 'loading' });
    this.api
      .list({ view: 'inbox', page, size: APPROVAL_PAGE_SIZE, status })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (!this.isCurrent(generation, ownerKey, sessionGeneration)) return;
          this.pageNumber.set(result.page);
          this.state.set(result.items.length === 0 ? { kind: 'empty', page: result } : { kind: 'ready', page: result });
        },
        error: (error: unknown) => {
          if (!this.isCurrent(generation, ownerKey, sessionGeneration)) return;
          const failure = failureFor(error, 'read');
          this.state.set(
            failure.kind === 'forbidden'
              ? { kind: 'forbidden', message: failure.message }
              : { kind: 'error', failure },
          );
        },
      });
  }

  private loadReview(id: string): void {
    const generation = ++this.reviewGeneration;
    const ownerKey = this.session.ownerKey();
    const sessionGeneration = this.session.generation();
    this.expanded.set({ id, state: { kind: 'loading' } });
    this.api.detail(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (request) => {
        if (!this.isCurrentReview(generation, id, ownerKey, sessionGeneration)) return;
        this.expanded.set({ id, state: { kind: 'ready', request } });
      },
      error: (error: unknown) => {
        if (!this.isCurrentReview(generation, id, ownerKey, sessionGeneration)) return;
        const failure = failureFor(error, 'read');
        if (failure.status === 404) {
          this.expanded.set({ id, state: { kind: 'not-found', message: failure.message } });
        } else if (failure.status === 403) {
          this.expanded.set({ id, state: { kind: 'forbidden', message: failure.message } });
        } else {
          this.expanded.set({ id, state: { kind: 'error', failure } });
        }
      },
    });
  }

  private isCurrent(generation: number, ownerKey: string | null, sessionGeneration: number): boolean {
    return (
      generation === this.generation &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }

  private isCurrentReview(
    generation: number,
    id: string,
    ownerKey: string | null,
    sessionGeneration: number,
  ): boolean {
    return (
      generation === this.reviewGeneration &&
      this.expanded()?.id === id &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }

  retry(): void {
    if (this.state().kind === 'forbidden') {
      void this.refresh();
      return;
    }
    this.load();
  }
}
