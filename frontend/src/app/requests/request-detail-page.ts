import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Failure, failureFor } from '../workspace/failure';
import { formatAmount, formatInstant, historyLabel, shortId, statusLabel } from '../workspace/format';
import { WorkspaceSession } from '../workspace/workspace-session';
import { ApprovalDecision } from './approval-decision';
import { newIdempotencyKey, RequestApi, RequestHistoryEntry, RequestView } from './request-api';
import {
  AMOUNT_VALIDATORS,
  CanonicalAmount,
  formatMoneyInput,
  JUSTIFICATION_MAX,
  JUSTIFICATION_VALIDATORS,
  parseMoneyInput,
  TITLE_MAX,
  TITLE_VALIDATORS,
} from './request-validation';

type DetailState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly request: RequestView }
  | { readonly kind: 'not-found'; readonly message: string }
  | { readonly kind: 'forbidden'; readonly message: string }
  | { readonly kind: 'error'; readonly failure: Failure };

type HistoryState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly entries: readonly RequestHistoryEntry[] }
  | { readonly kind: 'error'; readonly failure: Failure };

interface SubmitAttempt {
  readonly id: string;
  readonly version: number;
  readonly key: string;
}

/**
 * Canonical request detail, timeline and the owner's draft/submit/cancel controls.
 *
 * The detail response plus the current `/api/me` decide which controls are shown. The backend
 * remains authoritative for every action; the expected version always comes from this response.
 */
@Component({
  selector: 'app-request-detail-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, ApprovalDecision],
  templateUrl: './request-detail-page.html',
  styleUrl: './request-detail-page.css',
})
export class RequestDetailPage {
  private readonly api = inject(RequestApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(WorkspaceSession);

  readonly titleMax = TITLE_MAX;
  readonly justificationMax = JUSTIFICATION_MAX;
  readonly detail = signal<DetailState>({ kind: 'loading' });
  readonly history = signal<HistoryState>({ kind: 'loading' });
  readonly editing = signal(false);
  readonly editSubmitted = signal(false);
  readonly editPending = signal(false);
  readonly editFailure = signal<Failure | null>(null);
  readonly submitPending = signal(false);
  readonly submitFailure = signal<Failure | null>(null);
  readonly cancelPending = signal(false);
  readonly cancelFailure = signal<Failure | null>(null);
  readonly confirmCancel = signal(false);
  readonly notice = signal<string | null>(null);

  readonly request = computed(() => {
    const state = this.detail();
    return state.kind === 'ready' ? state.request : null;
  });
  readonly detailFailure = computed(() => {
    const state = this.detail();
    return state.kind === 'error' ? state.failure : null;
  });
  readonly notFoundMessage = computed(() => {
    const state = this.detail();
    return state.kind === 'not-found' ? state.message : null;
  });
  readonly forbiddenMessage = computed(() => {
    const state = this.detail();
    return state.kind === 'forbidden' ? state.message : null;
  });
  readonly historyEntries = computed(() => {
    const state = this.history();
    return state.kind === 'ready' ? state.entries : null;
  });
  readonly historyFailure = computed(() => {
    const state = this.history();
    return state.kind === 'error' ? state.failure : null;
  });

  readonly amountText = formatAmount;
  readonly instant = formatInstant;
  readonly label = statusLabel;
  readonly historyLabel = historyLabel;
  readonly shortId = shortId;

  readonly editForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: TITLE_VALIDATORS }),
    justification: new FormControl('', { nonNullable: true, validators: JUSTIFICATION_VALIDATORS }),
    amount: new FormControl('', { nonNullable: true, validators: AMOUNT_VALIDATORS }),
  });

  private currentId = '';
  private detailGeneration = 0;
  private historyGeneration = 0;
  private lastOwnerKey: string | null | undefined = undefined;
  private lastSessionGeneration = -1;
  private submitAttempt: SubmitAttempt | null = null;
  /** Identifies the command callback currently allowed to update the presented request. */
  private actionEpoch = 0;

  constructor() {
    this.lastOwnerKey = this.session.ownerKey();
    this.lastSessionGeneration = this.session.generation();

    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('id') ?? '';
      if (id === this.currentId) return;
      this.currentId = id;
      this.resetForRequest();
      this.loadAll();
    });

    effect(() => {
      const ownerKey = this.session.ownerKey();
      const generation = this.session.generation();
      const profile = this.session.profile();
      if (ownerKey === this.lastOwnerKey && generation === this.lastSessionGeneration) return;
      const ownerChanged = ownerKey !== this.lastOwnerKey;
      const generationChanged = generation !== this.lastSessionGeneration;
      this.lastOwnerKey = ownerKey;
      this.lastSessionGeneration = generation;
      if (ownerChanged || generationChanged) this.actionEpoch += 1;
      if (profile === null) {
        this.detailGeneration += 1;
        this.historyGeneration += 1;
        this.detail.set({ kind: 'loading' });
        this.history.set({ kind: 'loading' });
        this.resetActionState();
        if (ownerChanged) this.clearEditForm();
        return;
      }
      if (generationChanged) {
        // Responses started under the previous membership snapshot are ignored, so their
        // pending indicators must not stay stuck after an access change.
        this.editPending.set(false);
        this.submitPending.set(false);
        this.cancelPending.set(false);
        this.editFailure.set(null);
        this.submitFailure.set(null);
        this.cancelFailure.set(null);
      }
      if (ownerChanged) {
        this.editing.set(false);
        this.clearEditForm();
        this.submitAttempt = null;
      }
      this.loadAll();
    });
  }

  isOwner(): boolean {
    const request = this.request();
    const profile = this.session.profile();
    return request !== null && profile !== null && request.requesterIdentityId === profile.identityId;
  }

  canEditDraft(): boolean {
    const request = this.request();
    return request !== null && this.isOwner() && this.session.isEmployee() && request.status === 'DRAFT';
  }

  canSubmit(): boolean {
    return this.canEditDraft();
  }

  canCancel(): boolean {
    const request = this.request();
    return (
      request !== null &&
      this.isOwner() &&
      (request.status === 'AWAITING_DEPARTMENT_APPROVAL' || request.status === 'AWAITING_FINANCE_APPROVAL')
    );
  }

  departmentLabel(): string {
    const request = this.request();
    const profile = this.session.profile();
    if (request === null) return '—';
    if (profile !== null && request.departmentId === profile.departmentId) return profile.departmentName;
    return `Department ${shortId(request.departmentId)}`;
  }

  approvalRouteNote(): string {
    const request = this.request();
    if (request === null) return '';
    if (request.status === 'APPROVED') {
      return 'Final approval is recorded. The returned budget month below comes from the server.';
    }
    if (request.status === 'AWAITING_FINANCE_APPROVAL') {
      return 'Two-step route: department approval is recorded, and a finance approver must make the final decision.';
    }
    if (request.status === 'AWAITING_DEPARTMENT_APPROVAL' && request.amount > 1000) {
      return 'Two-step route: department approval must be recorded first; finance then makes the final decision.';
    }
    if (request.status === 'AWAITING_DEPARTMENT_APPROVAL') {
      return 'Department approval is the final step for this amount when it is at or below €1,000.00.';
    }
    return 'Department approval is final at or below €1,000.00; amounts above that require finance final approval.';
  }

  startEdit(): void {
    const request = this.request();
    if (request === null || !this.canEditDraft()) return;
    this.editForm.reset({
      title: request.title,
      justification: request.justification,
      amount: formatMoneyInput(request.amount),
    });
    this.editing.set(true);
    this.editSubmitted.set(false);
    this.editFailure.set(null);
  }

  cancelEdit(): void {
    if (this.editPending()) return;
    this.editing.set(false);
    this.clearEditForm();
  }

  saveDraft(): void {
    const request = this.request();
    if (request === null || !this.canEditDraft() || this.editPending()) return;
    this.editSubmitted.set(true);
    this.editForm.markAllAsTouched();
    const payload = this.editPayload();
    if (payload === null || this.editForm.invalid) return;
    const generation = this.session.generation();
    const ownerKey = this.session.ownerKey();
    const operation = ++this.actionEpoch;
    this.editPending.set(true);
    this.editFailure.set(null);
    this.notice.set(null);
    this.api.edit(request.id, {
      version: request.version,
      title: payload.title,
      justification: payload.justification,
      amount: payload.amount.value,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (!this.isCurrentAction(operation, request.id, ownerKey, generation)) return;
        this.editPending.set(false);
        this.editing.set(false);
        this.notice.set('Draft saved.');
        this.loadAll();
      },
      error: (error: unknown) => {
        if (!this.isCurrentAction(operation, request.id, ownerKey, generation)) return;
        this.editPending.set(false);
        this.editFailure.set(failureFor(error, 'command'));
      },
    });
  }

  submitRequest(): void {
    const request = this.request();
    if (request === null || !this.canSubmit() || this.submitPending()) return;
    const generation = this.session.generation();
    const ownerKey = this.session.ownerKey();
    const sameAttempt =
      this.submitAttempt !== null &&
      this.submitAttempt.id === request.id &&
      this.submitAttempt.version === request.version;
    const key = sameAttempt ? this.submitAttempt!.key : newIdempotencyKey();
    const operation = ++this.actionEpoch;
    this.submitAttempt = { id: request.id, version: request.version, key };
    this.submitPending.set(true);
    this.submitFailure.set(null);
    this.notice.set(null);
    this.api.submit(request.id, request.version, key).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (!this.isCurrentAction(operation, request.id, ownerKey, generation)) return;
        this.submitPending.set(false);
        this.submitAttempt = null;
        this.notice.set('Submitted for approval. The current state was reloaded from the server.');
        this.loadAll();
      },
      error: (error: unknown) => {
        if (!this.isCurrentAction(operation, request.id, ownerKey, generation)) return;
        this.submitPending.set(false);
        const failure = failureFor(error, 'command');
        if (failure.kind === 'key-reused') this.submitAttempt = null;
        this.submitFailure.set(failure);
      },
    });
  }

  cancelRequest(): void {
    const request = this.request();
    if (request === null || !this.canCancel() || this.cancelPending()) return;
    const generation = this.session.generation();
    const ownerKey = this.session.ownerKey();
    const operation = ++this.actionEpoch;
    this.cancelPending.set(true);
    this.cancelFailure.set(null);
    this.notice.set(null);
    this.api.cancel(request.id, request.version).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (!this.isCurrentAction(operation, request.id, ownerKey, generation)) return;
        this.cancelPending.set(false);
        this.confirmCancel.set(false);
        this.notice.set('Request cancelled. The current state was reloaded from the server.');
        this.loadAll();
      },
      error: (error: unknown) => {
        if (!this.isCurrentAction(operation, request.id, ownerKey, generation)) return;
        this.cancelPending.set(false);
        this.cancelFailure.set(failureFor(error, 'command'));
      },
    });
  }

  approveOrRejectChanged(): void {
    this.notice.set('Decision recorded. The current request and timeline were reloaded.');
    this.loadAll();
  }

  reloadCanonical(): void {
    this.editing.set(false);
    this.clearEditForm();
    this.submitAttempt = null;
    this.cancelFailure.set(null);
    this.editFailure.set(null);
    this.submitFailure.set(null);
    this.confirmCancel.set(false);
    this.loadAll();
  }

  retryDetail(): void {
    this.loadAll();
  }

  retryHistory(): void {
    this.loadHistory();
  }

  fieldInvalid(name: 'title' | 'justification' | 'amount'): boolean {
    const control = this.editForm.controls[name];
    return control.invalid && (control.touched || this.editSubmitted());
  }

  submitNeedsReload(): boolean {
    return this.submitFailure()?.needsReload === true;
  }

  cancelNeedsReload(): boolean {
    return this.cancelFailure()?.needsReload === true;
  }

  editNeedsReload(): boolean {
    const failure = this.editFailure();
    return failure !== null && (failure.needsReload || failure.kind === 'network' || failure.kind === 'server');
  }

  private resetForRequest(): void {
    this.actionEpoch += 1;
    this.detailGeneration += 1;
    this.historyGeneration += 1;
    this.detail.set({ kind: 'loading' });
    this.history.set({ kind: 'loading' });
    this.editing.set(false);
    this.clearEditForm();
    this.submitAttempt = null;
    this.resetActionState();
  }

  private resetActionState(): void {
    this.editPending.set(false);
    this.editSubmitted.set(false);
    this.editFailure.set(null);
    this.submitPending.set(false);
    this.submitFailure.set(null);
    this.cancelPending.set(false);
    this.cancelFailure.set(null);
    this.confirmCancel.set(false);
    this.notice.set(null);
  }

  private clearEditForm(): void {
    this.editForm.reset({ title: '', justification: '', amount: '' });
    this.editSubmitted.set(false);
    this.editFailure.set(null);
  }

  private loadAll(): void {
    if (this.currentId.length === 0) {
      this.detail.set({ kind: 'not-found', message: 'No request identifier was provided.' });
      this.history.set({ kind: 'ready', entries: [] });
      return;
    }
    this.loadDetail();
    this.loadHistory();
  }

  private loadDetail(): void {
    const generation = ++this.detailGeneration;
    const id = this.currentId;
    const ownerKey = this.session.ownerKey();
    const sessionGeneration = this.session.generation();
    this.detail.set({ kind: 'loading' });
    this.api.detail(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (request) => {
        if (!this.isCurrentDetail(generation, id, ownerKey, sessionGeneration)) return;
        this.detail.set({ kind: 'ready', request });
      },
      error: (error: unknown) => {
        if (!this.isCurrentDetail(generation, id, ownerKey, sessionGeneration)) return;
        const failure = failureFor(error, 'read');
        if (failure.status === 404) {
          this.historyGeneration += 1;
          this.history.set({ kind: 'ready', entries: [] });
          this.detail.set({ kind: 'not-found', message: failure.message });
        } else if (failure.status === 403) {
          this.historyGeneration += 1;
          this.history.set({ kind: 'ready', entries: [] });
          this.detail.set({ kind: 'forbidden', message: failure.message });
        } else {
          this.detail.set({ kind: 'error', failure });
        }
      },
    });
  }

  private loadHistory(): void {
    if (this.currentId.length === 0) return;
    const generation = ++this.historyGeneration;
    const id = this.currentId;
    const ownerKey = this.session.ownerKey();
    const sessionGeneration = this.session.generation();
    this.history.set({ kind: 'loading' });
    this.api.history(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (entries) => {
        if (!this.isCurrentHistory(generation, id, ownerKey, sessionGeneration)) return;
        this.history.set({ kind: 'ready', entries });
      },
      error: (error: unknown) => {
        if (!this.isCurrentHistory(generation, id, ownerKey, sessionGeneration)) return;
        this.history.set({ kind: 'error', failure: failureFor(error, 'history') });
      },
    });
  }

  private isCurrentAction(
    operation: number,
    id: string,
    ownerKey: string | null,
    sessionGeneration: number,
  ): boolean {
    return (
      operation === this.actionEpoch &&
      id === this.currentId &&
      id === this.request()?.id &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }

  private isCurrentDetail(
    generation: number,
    id: string,
    ownerKey: string | null,
    sessionGeneration: number,
  ): boolean {
    return (
      generation === this.detailGeneration &&
      id === this.currentId &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }

  private isCurrentHistory(
    generation: number,
    id: string,
    ownerKey: string | null,
    sessionGeneration: number,
  ): boolean {
    return (
      generation === this.historyGeneration &&
      id === this.currentId &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }

  private editPayload(): { title: string; justification: string; amount: CanonicalAmount } | null {
    const raw = this.editForm.getRawValue();
    const title = raw.title.trim();
    const justification = raw.justification.trim();
    const amount = parseMoneyInput(raw.amount);
    if (title.length === 0 || justification.length === 0 || amount === null) return null;
    return { title, justification, amount };
  }
}
