import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { failureFor, Failure } from '../workspace/failure';
import { formatAmount, formatInstant, statusLabel } from '../workspace/format';
import { WorkspaceSession } from '../workspace/workspace-session';
import { RequestApi, RequestPage, RequestStatus, REQUEST_STATUSES } from './request-api';

export const REQUEST_PAGE_SIZE = 20;

type ListState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly page: RequestPage }
  | { readonly kind: 'empty'; readonly page: RequestPage }
  | { readonly kind: 'forbidden'; readonly message: string }
  | { readonly kind: 'error'; readonly failure: Failure };

@Component({
  selector: 'app-request-list-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './request-list-page.html',
  styleUrl: './request-list-page.css',
})
export class RequestListPage {
  private readonly api = inject(RequestApi);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(WorkspaceSession);

  readonly pageSize = REQUEST_PAGE_SIZE;
  readonly statuses = REQUEST_STATUSES;
  readonly state = signal<ListState>({ kind: 'loading' });
  readonly status = signal<RequestStatus | null>(null);
  readonly pageNumber = signal(0);
  readonly amount = formatAmount;
  readonly instant = formatInstant;
  readonly label = statusLabel;

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

  private requestGeneration = 0;
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
      if (profile === null) {
        this.requestGeneration += 1;
        this.state.set({ kind: 'loading' });
        return;
      }
      if (ownerChanged) {
        this.pageNumber.set(0);
        this.status.set(null);
      }
      this.load();
    });
  }

  onStatusChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.status.set(value === '' ? null : (value as RequestStatus));
    // Changing the filter always restarts at the first zero-based page.
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
    if (generation === this.session.generation()) this.load();
  }

  hasNext(page: RequestPage): boolean {
    return (page.page + 1) * page.size < page.total;
  }

  pageCount(page: RequestPage): number {
    return Math.max(1, Math.ceil(page.total / Math.max(1, page.size)));
  }

  canCreate(): boolean {
    return this.session.isEmployee();
  }

  private load(): void {
    const profile = this.session.profile();
    if (profile === null) return;
    const generation = ++this.requestGeneration;
    const ownerKey = this.session.ownerKey();
    const sessionGeneration = this.session.generation();
    const page = this.pageNumber();
    const status = this.status();
    this.state.set({ kind: 'loading' });
    this.api
      .list({ view: 'mine', page, size: REQUEST_PAGE_SIZE, status })
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

  private isCurrent(generation: number, ownerKey: string | null, sessionGeneration: number): boolean {
    return (
      generation === this.requestGeneration &&
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
