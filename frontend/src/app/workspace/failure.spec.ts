import { HttpErrorResponse } from '@angular/common/http';
import { failureFor, problemCode } from './failure';

function error(status: number, code?: string): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    statusText: 'Error',
    error: code === undefined ? null : { status, title: 'Error', code },
  });
}

describe('API failure mapping', () => {
  it.each([
    ['stale_request', 'stale'],
    ['request_department_changed', 'department-changed'],
    ['retryable_conflict', 'retryable'],
    ['idempotency_key_reused', 'key-reused'],
    ['insufficient_budget', 'budget'],
    ['budget_missing', 'budget-missing'],
    ['invalid_request_state', 'stale'],
    ['data_conflict', 'conflict'],
  ] as const)('maps 409 %s to a distinct %s message', (code, kind) => {
    const failure = failureFor(error(409, code), 'command');
    expect(failure.code).toBe(code);
    expect(failure.kind).toBe(kind);
    expect(failure.message.length).toBeGreaterThan(20);
  });

  it('states that a budget denial persisted no approval and no charge', () => {
    const insufficient = failureFor(error(409, 'insufficient_budget'), 'command');
    expect(insufficient.message).toContain('remains pending');
    expect(insufficient.message).toContain('no amount was charged');
    expect(insufficient.needsReload).toBe(true);

    const missing = failureFor(error(409, 'budget_missing'), 'command');
    expect(missing.message).toContain('no budget exists');
    expect(missing.message).toContain('no amount was charged');
  });

  it('keeps stale and department-change conflicts explicit and does not tell the user to resubmit blindly', () => {
    const stale = failureFor(error(409, 'stale_request'), 'command');
    expect(stale.message).toContain('Reload the request');
    expect(stale.needsReload).toBe(true);

    const department = failureFor(error(409, 'request_department_changed'), 'command');
    expect(department.message).toContain('save it again before submitting');
    expect(department.needsReload).toBe(true);
  });

  it('explains transport ambiguity for commands without implying a duplicate', () => {
    const network = failureFor(error(0), 'command');
    expect(network.kind).toBe('network');
    expect(network.message).toContain('may or may not have saved');
    expect(network.message).toContain('reuses its original retry key');

    const server = failureFor(error(503), 'command');
    expect(server.kind).toBe('server');
    expect(server.message).toContain('could not confirm');
  });

  it('maps read failures without granting access', () => {
    expect(failureFor(error(401), 'read').kind).toBe('unauthorized');
    expect(failureFor(error(403), 'read').kind).toBe('forbidden');
    expect(failureFor(error(404), 'read').kind).toBe('not-found');
    expect(failureFor(error(404), 'history').message).toContain('timeline');
    expect(failureFor(error(500), 'read').message).toContain('HTTP 500');
  });

  it('reads the problem code only from the documented body shape', () => {
    expect(problemCode(error(409, 'stale_request'))).toBe('stale_request');
    expect(problemCode(error(409))).toBeNull();
    expect(problemCode(new Error('boom'))).toBeNull();
  });
});
