import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Failure, failureFor } from '../workspace/failure';
import { WorkspaceSession } from '../workspace/workspace-session';
import { newIdempotencyKey, RequestApi } from './request-api';
import {
  AMOUNT_VALIDATORS,
  CanonicalAmount,
  JUSTIFICATION_MAX,
  JUSTIFICATION_VALIDATORS,
  parseMoneyInput,
  TITLE_MAX,
  TITLE_VALIDATORS,
} from './request-validation';

interface CreateAttempt {
  readonly fingerprint: string;
  readonly key: string;
}

function fingerprintOf(title: string, justification: string, amount: CanonicalAmount): string {
  return JSON.stringify(['create', title, justification, amount.text]);
}

/**
 * Employee draft creation. Creating a draft and submitting it are deliberately separate actions:
 * this form never submits for approval, and the detail screen owns the explicit submit command.
 */
@Component({
  selector: 'app-request-form-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './request-form-page.html',
  styleUrl: './request-form-page.css',
})
export class RequestFormPage {
  private readonly api = inject(RequestApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(WorkspaceSession);

  readonly titleMax = TITLE_MAX;
  readonly justificationMax = JUSTIFICATION_MAX;
  readonly pending = signal(false);
  readonly submitted = signal(false);
  readonly failure = signal<Failure | null>(null);
  readonly completed = signal(false);
  readonly payloadChanged = signal(false);
  readonly sessionNotice = signal<string | null>(null);
  readonly employeeActive = computed(() => this.session.isEmployee());

  readonly form = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: TITLE_VALIDATORS }),
    justification: new FormControl('', { nonNullable: true, validators: JUSTIFICATION_VALIDATORS }),
    amount: new FormControl('', { nonNullable: true, validators: AMOUNT_VALIDATORS }),
  });

  private attempt: CreateAttempt | null = null;
  private lastOwnerKey: string | null | undefined = undefined;
  private lastSessionGeneration = -1;
  private lastEmployeeActive: boolean | null = null;
  /** Identifies the command callback currently allowed to update this page. */
  private operationToken = 0;

  constructor() {
    this.lastOwnerKey = this.session.ownerKey();
    this.lastSessionGeneration = this.session.generation();
    this.lastEmployeeActive = this.session.isEmployee();

    effect(() => {
      const key = this.session.ownerKey();
      const generation = this.session.generation();
      const employeeActive = this.session.isEmployee();
      const previousOwner = this.lastOwnerKey;
      const ownerChanged = key !== previousOwner;
      const generationChanged = generation !== this.lastSessionGeneration;
      const employeeLost = this.lastEmployeeActive === true && !employeeActive;
      if (!ownerChanged && !generationChanged && !employeeLost) return;

      this.lastOwnerKey = key;
      this.lastSessionGeneration = generation;
      this.lastEmployeeActive = employeeActive;

      if (ownerChanged || employeeLost) {
        this.operationToken += 1;
        this.resetDraft();
        if (ownerChanged) {
          if (previousOwner !== undefined && previousOwner !== null && key !== null) {
            this.sessionNotice.set(
              'The active workspace session changed. This form was cleared to avoid mixing drafts.',
            );
          } else if (key === null) {
            this.sessionNotice.set('The workspace session ended. This form was cleared.');
          } else {
            this.sessionNotice.set(null);
          }
        } else {
          this.sessionNotice.set(
            'The active EMPLOYEE role is no longer available. This form was cleared.',
          );
        }
        return;
      }

      // The person and employee authority are unchanged, but their membership snapshot is not.
      // Ignore any callback started under the old snapshot without discarding ordinary retry input.
      this.operationToken += 1;
      if (this.pending()) {
        this.pending.set(false);
        this.sessionNotice.set(
          'Your workspace membership changed while this form was submitting. The pending result will not be applied; review the form before retrying.',
        );
      }
    });

    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.payloadChanged.set(this.attempt !== null && this.currentFingerprint() !== this.attempt.fingerprint);
    });
  }

  departmentName(): string {
    return this.session.profile()?.departmentName ?? 'your current membership';
  }

  create(): void {
    if (this.pending() || this.completed() || !this.employeeActive()) return;
    this.submitted.set(true);
    this.form.markAllAsTouched();
    const payload = this.canonicalPayload();
    if (payload === null || this.form.invalid) return;

    const fingerprint = fingerprintOf(payload.title, payload.justification, payload.amount);
    const sameAttempt = this.attempt?.fingerprint === fingerprint;
    const key = sameAttempt ? this.attempt!.key : newIdempotencyKey();
    const ownerKey = this.session.ownerKey();
    const sessionGeneration = this.session.generation();
    const operation = ++this.operationToken;
    this.attempt = { fingerprint, key };
    this.payloadChanged.set(false);
    this.pending.set(true);
    this.failure.set(null);

    this.api.create(
      { title: payload.title, justification: payload.justification, amount: payload.amount.value },
      key,
    ).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (view) => {
        if (!this.isCurrentOperation(ownerKey, sessionGeneration, operation)) return;
        this.pending.set(false);
        this.completed.set(true);
        // The command response is the original result, not a promise of current state.
        // Navigate and let the detail screen read the canonical request.
        void this.router.navigate(['/requests', view.id]);
      },
      error: (error: unknown) => {
        if (!this.isCurrentOperation(ownerKey, sessionGeneration, operation)) return;
        this.pending.set(false);
        const failure = failureFor(error, 'command');
        if (failure.kind === 'key-reused') this.attempt = null;
        this.failure.set(failure);
      },
    });
  }

  fieldInvalid(name: 'title' | 'justification' | 'amount'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || this.submitted());
  }

  private canonicalPayload(): { title: string; justification: string; amount: CanonicalAmount } | null {
    const raw = this.form.getRawValue();
    const title = raw.title.trim();
    const justification = raw.justification.trim();
    const amount = parseMoneyInput(raw.amount);
    if (title.length === 0 || justification.length === 0 || amount === null) return null;
    return { title, justification, amount };
  }

  private currentFingerprint(): string | null {
    const payload = this.canonicalPayload();
    return payload === null ? null : fingerprintOf(payload.title, payload.justification, payload.amount);
  }

  private resetDraft(): void {
    this.form.reset({ title: '', justification: '', amount: '' });
    this.attempt = null;
    this.payloadChanged.set(false);
    this.submitted.set(false);
    this.pending.set(false);
    this.completed.set(false);
    this.failure.set(null);
  }

  private isCurrentOperation(
    ownerKey: string | null,
    sessionGeneration: number,
    operation: number,
  ): boolean {
    return (
      ownerKey !== null &&
      operation === this.operationToken &&
      ownerKey === this.session.ownerKey() &&
      sessionGeneration === this.session.generation()
    );
  }

  retryHint(): string | null {
    const failure = this.failure();
    if (failure === null || this.attempt === null) return null;
    if (failure.kind === 'network' || failure.kind === 'server' || failure.kind === 'retryable') {
      return this.payloadChanged()
        ? 'The form changed, so the next attempt uses a new retry key.'
        : 'Submitting this unchanged form again reuses the same retry key.';
    }
    return null;
  }
}
