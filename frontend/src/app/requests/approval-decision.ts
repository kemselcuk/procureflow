import { Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { WorkspaceProfile } from '../workspace/workspace-api';
import { WorkspaceSession } from '../workspace/workspace-session';
import { failureFor, Failure } from '../workspace/failure';
import { RequestApi, RequestView } from './request-api';
import { REASON_VALIDATORS, REJECTION_REASON_MAX } from './request-validation';

type PendingAction = 'approve' | 'reject' | null;

/**
 * Current-step approval/rejection controls shared by the request detail screen and the actionable
 * inbox. Visibility is derived from the canonical request view plus the current `/api/me`; every
 * command still carries only the expected version and is re-authorized by the backend.
 */
@Component({
  selector: 'app-approval-decision',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    @if (canAct()) {
      <section class="decision" aria-labelledby="decision-title">
        <div class="heading">
          <h3 id="decision-title">Decision</h3>
          <span class="step">{{ stepLabel() }}</span>
        </div>
        <p class="hint">
          This uses version {{ request().version }} of the request. If another change lands first, the
          server returns a conflict and nothing is applied.
        </p>

        @if (failure(); as failure) {
          <div class="error" role="alert">
            <p>{{ failure.message }}</p>
            @if (failure.needsReload) {
              <button type="button" class="secondary" (click)="reload.emit()" [disabled]="pending() !== null">
                Reload current request
              </button>
            }
          </div>
        }

        <div class="actions">
          <button type="button" (click)="approve()" [disabled]="pending() !== null">
            {{ pending() === 'approve' ? 'Approving…' : 'Approve ' + stepLabel() }}
          </button>
          <button type="button" class="secondary" (click)="toggleReject()" [disabled]="pending() !== null">
            {{ showReject() ? 'Cancel rejection' : 'Reject request' }}
          </button>
        </div>

        @if (showReject()) {
          <div class="reject">
            <label [for]="reasonId()">Rejection reason</label>
            <p class="hint" [id]="reasonHintId()">Required, at most {{ maxReason }} characters.</p>
            <textarea
              [id]="reasonId()"
              [formControl]="reason"
              rows="4"
              [attr.maxlength]="maxReason"
              [attr.aria-describedby]="reasonHintId() + ' ' + reasonErrorId()"
              [attr.aria-invalid]="reason.invalid && (reason.touched || reasonSubmitted())"
            ></textarea>
            @if (reasonErrorId(); as errorId) {
              @if (reason.invalid && (reason.touched || reasonSubmitted())) {
                <p class="field-error" [id]="errorId" role="alert">
                  @if (reason.hasError('required') || reason.hasError('blank')) {
                    Enter a rejection reason.
                  } @else if (reason.hasError('maxlength')) {
                    The rejection reason must be {{ maxReason }} characters or fewer.
                  } @else {
                    The rejection reason is invalid.
                  }
                </p>
              }
            }
            <button
              type="button"
              class="danger"
              (click)="reject()"
              [disabled]="pending() !== null"
            >
              {{ pending() === 'reject' ? 'Rejecting…' : 'Confirm rejection' }}
            </button>
            <p class="hint">Rejection is terminal; an approved request cannot be reopened.</p>
          </div>
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    .decision { border: 1px solid var(--line); border-radius: .6rem; padding: 1.1rem; margin: 1rem 0; background: #fbfcfd; }
    .heading { display: flex; align-items: baseline; justify-content: space-between; gap: .8rem; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 1rem; }
    .step { font-size: .7rem; font-weight: 700; letter-spacing: .06em; color: var(--accent); text-transform: uppercase; }
    .hint { color: var(--muted); font-size: .8rem; line-height: 1.5; margin: .4rem 0 .8rem; }
    .actions { display: flex; flex-wrap: wrap; gap: .6rem; }
    button { border: 1px solid var(--ink); background: var(--ink); color: white; padding: .65rem .9rem; font: inherit; font-size: .8rem; font-weight: 600; border-radius: .4rem; cursor: pointer; }
    button:disabled { opacity: .55; cursor: wait; }
    button.secondary { background: white; color: var(--ink); border-color: var(--line); }
    button.danger { background: #7e2d1d; border-color: #7e2d1d; }
    .reject { margin-top: 1rem; border-top: 1px solid var(--line); padding-top: .9rem; }
    label { display: block; font-size: .82rem; font-weight: 700; margin-bottom: .2rem; }
    textarea { width: 100%; box-sizing: border-box; font: inherit; font-size: .9rem; padding: .6rem; border: 1px solid var(--line); border-radius: .4rem; color: var(--ink); background: white; resize: vertical; }
    textarea[aria-invalid='true'] { border-color: #a43b22; }
    .field-error { color: #8a2f17; font-size: .8rem; font-weight: 600; margin: .4rem 0 .7rem; }
    .error { border-left: 3px solid #b36113; padding-left: .9rem; margin: .6rem 0; }
    .error p { margin: 0 0 .6rem; font-size: .85rem; line-height: 1.55; font-weight: 600; }
  `,
})
export class ApprovalDecision {
  readonly request = input.required<RequestView>();
  readonly profile = input.required<WorkspaceProfile>();
  readonly changed = output<void>();
  readonly reload = output<void>();

  private readonly api = inject(RequestApi);
  private readonly session = inject(WorkspaceSession);
  private readonly destroyRef = inject(DestroyRef);
  readonly maxReason = REJECTION_REASON_MAX;

  readonly pending = signal<PendingAction>(null);
  readonly failure = signal<Failure | null>(null);
  readonly showReject = signal(false);
  readonly reasonSubmitted = signal(false);
  readonly reason = new FormControl('', { nonNullable: true, validators: REASON_VALIDATORS });

  readonly canAct = computed(() => {
    const request = this.request();
    const profile = this.profile();
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
  });

  readonly stepLabel = computed(() =>
    this.request().status === 'AWAITING_FINANCE_APPROVAL' ? 'finance step' : 'department step',
  );

  private lastRequestKey = '';
  private lastGeneration = -1;
  /** Identifies the command callback allowed to update the currently presented request. */
  private actionEpoch = 0;

  constructor() {
    this.lastGeneration = this.session.generation();
    effect(() => {
      const request = this.request();
      const generation = this.session.generation();
      const requestChanged = `${request.id}:${request.version}:${request.status}` !== this.lastRequestKey;
      const generationChanged = generation !== this.lastGeneration;
      if (!requestChanged && !generationChanged) return;
      this.lastRequestKey = `${request.id}:${request.version}:${request.status}`;
      this.lastGeneration = generation;
      this.actionEpoch += 1;
      this.failure.set(null);
      this.pending.set(null);
      if (requestChanged) {
        this.showReject.set(false);
        this.reasonSubmitted.set(false);
        this.reason.markAsUntouched();
      }
    });
  }

  reasonId(): string {
    return `reject-reason-${this.request().id}`;
  }

  reasonHintId(): string {
    return `reject-reason-hint-${this.request().id}`;
  }

  reasonErrorId(): string {
    return `reject-reason-error-${this.request().id}`;
  }

  toggleReject(): void {
    if (this.pending() !== null) return;
    this.showReject.update((value) => !value);
    if (!this.showReject()) {
      this.reason.reset('');
      this.reasonSubmitted.set(false);
    }
  }

  approve(): void {
    if (this.pending() !== null || !this.canAct()) return;
    const request = this.request();
    const generation = this.session.generation();
    const ownerKey = this.session.ownerKey();
    const operation = ++this.actionEpoch;
    this.pending.set('approve');
    this.failure.set(null);
    this.api.approve(request.id, request.version).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (!this.isCurrentAction(operation, request.id, request.version, ownerKey, generation)) return;
        this.pending.set(null);
        this.changed.emit();
      },
      error: (error: unknown) => {
        if (!this.isCurrentAction(operation, request.id, request.version, ownerKey, generation)) return;
        this.pending.set(null);
        this.failure.set(failureFor(error, 'command'));
      },
    });
  }

  reject(): void {
    if (this.pending() !== null || !this.canAct()) return;
    this.reasonSubmitted.set(true);
    this.reason.markAsTouched();
    if (this.reason.invalid) return;
    const request = this.request();
    const reason = this.reason.value.trim();
    const generation = this.session.generation();
    const ownerKey = this.session.ownerKey();
    const operation = ++this.actionEpoch;
    this.pending.set('reject');
    this.failure.set(null);
    this.api.reject(request.id, request.version, reason).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (!this.isCurrentAction(operation, request.id, request.version, ownerKey, generation)) return;
        this.pending.set(null);
        this.changed.emit();
      },
      error: (error: unknown) => {
        if (!this.isCurrentAction(operation, request.id, request.version, ownerKey, generation)) return;
        this.pending.set(null);
        this.failure.set(failureFor(error, 'command'));
      },
    });
  }

  private isCurrentAction(
    operation: number,
    id: string,
    version: number,
    ownerKey: string | null,
    sessionGeneration: number,
  ): boolean {
    const request = this.request();
    return (
      operation === this.actionEpoch &&
      request.id === id &&
      request.version === version &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }
}
