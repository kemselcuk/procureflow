import { HttpErrorResponse } from '@angular/common/http';

export type FailureKind =
  | 'stale'
  | 'department-changed'
  | 'retryable'
  | 'key-reused'
  | 'budget'
  | 'budget-missing'
  | 'forbidden'
  | 'not-found'
  | 'unauthorized'
  | 'validation'
  | 'network'
  | 'server'
  | 'conflict'
  | 'other';

export type FailureContext = 'read' | 'history' | 'command' | 'profile';

export interface Failure {
  readonly status: number;
  readonly code: string | null;
  readonly kind: FailureKind;
  readonly message: string;
  readonly needsReload?: boolean;
}

export function problemCode(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) return null;
  const body: unknown = error.error;
  if (typeof body !== 'object' || body === null) return null;
  const code = (body as Record<string, unknown>)['code'];
  return typeof code === 'string' ? code : null;
}

function readMessage(status: number, context: FailureContext): string {
  if (status === 401) return 'Your session is no longer valid. Sign in again to continue.';
  if (status === 403) return 'Your current membership does not allow this view.';
  if (status === 404) {
    return context === 'history'
      ? 'The timeline for this request is not available in your current company.'
      : 'This item is not available in your current company. It may belong to another company or no longer exist.';
  }
  if (status === 0) return 'The request service is unreachable. Check the local backend and try again.';
  if (status >= 500) return `The request service could not complete this read (HTTP ${status}). Try again.`;
  return `This view could not be loaded (HTTP ${status}). Try again.`;
}

/**
 * Maps the frozen API problem codes to explicit operator-facing messages.
 *
 * The backend remains the authorization and idempotency authority. These messages only make the
 * outcome understandable; they never grant an action or turn a conflict into a retry.
 */
export function failureFor(error: unknown, context: FailureContext = 'command'): Failure {
  if (!(error instanceof HttpErrorResponse)) {
    return {
      status: 0,
      code: null,
      kind: 'other',
      message: context === 'command'
        ? 'The request could not be completed. Your entered content was kept. Review the current state before retrying.'
        : 'This view could not be loaded. Try again.',
    };
  }
  const status = error.status;
  const code = problemCode(error);

  if (status === 409) {
    switch (code) {
      case 'stale_request':
        return {
          status,
          code,
          kind: 'stale',
          needsReload: true,
          message:
            'This request changed since you loaded it. Nothing from this stale view was submitted. Reload the request and review the latest version before trying again.',
        };
      case 'request_department_changed':
        return {
          status,
          code,
          kind: 'department-changed',
          needsReload: true,
          message:
            'Your primary department changed after this draft was created. Reload the draft, review it and save it again before submitting.',
        };
      case 'idempotency_key_reused':
        return {
          status,
          code,
          kind: 'key-reused',
          message:
            'This retry key was already used for different content. Nothing was replayed. Review the form and submit a new attempt.',
        };
      case 'retryable_conflict':
        return {
          status,
          code,
          kind: 'retryable',
          message:
            'A temporary database conflict prevented the change. Wait a moment, then retry. An unchanged create or submit reuses its original retry key.',
        };
      case 'insufficient_budget':
        return {
          status,
          code,
          kind: 'budget',
          needsReload: true,
          message:
            'Final approval was not saved because the budget for the approval month is insufficient. The request remains pending and no amount was charged.',
        };
      case 'budget_missing':
        return {
          status,
          code,
          kind: 'budget-missing',
          needsReload: true,
          message:
            'Final approval was not saved because no budget exists for the approval month. The request remains pending and no amount was charged.',
        };
      case 'invalid_request_state':
        return {
          status,
          code,
          kind: 'stale',
          needsReload: true,
          message:
            'This action is no longer possible in the current state of the request. Nothing was changed. Reload the request and review it.',
        };
      case 'data_conflict':
        return {
          status,
          code,
          kind: 'conflict',
          needsReload: true,
          message:
            'The server rejected this change because of a data conflict. Reload the current state and review it before trying again.',
        };
      default:
        return {
          status,
          code,
          kind: 'conflict',
          message: 'The server reported a conflict. Reload the current state before trying again.',
        };
    }
  }

  if (status === 400) {
    if (code === 'validation_failed') {
      return { status, code, kind: 'validation', message: 'Check the form fields and try again.' };
    }
    return {
      status,
      code,
      kind: 'validation',
      message: 'The request was rejected as invalid. Review the entered values and try again.',
    };
  }

  if (status === 401) {
    return { status, code, kind: 'unauthorized', message: readMessage(status, context) };
  }
  if (status === 403) {
    return {
      status,
      code,
      kind: 'forbidden',
      message: context === 'command'
        ? 'Your current membership does not allow this action.'
        : readMessage(status, context),
    };
  }
  if (status === 404) {
    return {
      status,
      code,
      kind: 'not-found',
      message: context === 'command'
        ? 'This request is not available in your current company. Reload the list and check whether it still exists.'
        : readMessage(status, context),
    };
  }
  if (status === 0) {
    return {
      status,
      code,
      kind: 'network',
      message: context === 'command'
        ? 'The request service is unreachable. The server may or may not have saved this change. Check the current state before retrying; an unchanged create or submit reuses its original retry key.'
        : readMessage(status, context),
    };
  }
  if (status >= 500) {
    return {
      status,
      code,
      kind: 'server',
      message: context === 'command'
        ? 'The server could not confirm whether this change was saved. Your entered content was kept. Check the current state before retrying; an unchanged create or submit reuses its original retry key.'
        : readMessage(status, context),
    };
  }
  return { status, code, kind: 'other', message: readMessage(status, context) };
}
