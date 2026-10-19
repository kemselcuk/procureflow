import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Authentication } from '../authentication';
import { roleLabel } from './format';
import { WorkspaceSession } from './workspace-session';

@Component({
  selector: 'app-workspace-landing',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="landing" aria-labelledby="workspace-title">
      <p class="eyebrow">PURCHASING WORKSPACE</p>
      <h1 id="workspace-title">Purchase requests, with a clear path to approval.</h1>
      <p class="lede">
        Create a request, follow its approval steps and see the decision history. Company scope and
        approval authority always come from your current membership on the server.
      </p>

      @if (auth.state() === 'authenticated') {
        @switch (session.state()) {
          @case ('loading') {
            <p class="state" role="status" aria-live="polite">Loading your workspace access…</p>
          }
          @case ('ready') {
            <div class="ready">
              <p>
                <strong>{{ session.profile()?.companyName }}</strong> · {{ session.profile()?.departmentName }}
              </p>
              <p>Roles: {{ roleNames() }}</p>
              <a class="primary" routerLink="/requests">Open my requests</a>
            </div>
          }
          @case ('forbidden') {
            <div class="state error" role="alert">
              <p>{{ session.errorMessage() }}</p>
            </div>
          }
          @case ('error') {
            <div class="state error" role="alert">
              <p>{{ session.errorMessage() }}</p>
              <button type="button" (click)="retryAccess()" [disabled]="retrying()">
                {{ retrying() ? 'Checking access…' : 'Retry access check' }}
              </button>
            </div>
          }
        }
      } @else if (auth.state() === 'starting') {
        <p class="state" role="status" aria-live="polite">Checking your sign-in…</p>
      } @else if (auth.state() === 'anonymous') {
        <p class="state">
          Sign in with your local account to continue. The session panel shows the available sign-in
          action and any authentication error.
        </p>
      }

      <section class="rule" aria-labelledby="rule-title">
        <h2 id="rule-title">How approval routing works</h2>
        <p>
          Department approval is final for amounts up to €1,000.00. Amounts above €1,000.00 move to a
          separate finance approver for final approval. The final approval month's budget is checked by
          the backend when the final step is committed.
        </p>
        <p class="hint">Budgets are fictional. No payments are made.</p>
      </section>
    </section>
  `,
  styles: `
    :host { display: block; }
    .landing { background: white; border: 1px solid var(--line); border-radius: .8rem; padding: clamp(1.4rem, 4vw, 2.4rem); }
    .eyebrow { font-size: .68rem; letter-spacing: .13em; font-weight: 750; color: var(--accent); margin: 0 0 .8rem; }
    h1 { font-size: clamp(1.8rem, 4vw, 2.8rem); line-height: 1.12; letter-spacing: -.04em; margin: 0 0 1rem; }
    .lede { color: var(--muted); line-height: 1.7; max-width: 60ch; margin: 0 0 1.4rem; }
    .state { color: var(--muted); line-height: 1.6; }
    .state.error { border-left: 3px solid #b36113; padding-left: .9rem; color: var(--ink); }
    .state.error p { margin: 0 0 .8rem; font-weight: 600; }
    .ready { border-left: 3px solid var(--accent); padding-left: 1rem; }
    .ready p { margin: 0 0 .5rem; line-height: 1.6; }
    .primary { display: inline-block; margin-top: .5rem; background: var(--ink); color: white; padding: .75rem 1rem; border-radius: .4rem; text-decoration: none; font-weight: 600; }
    button { border: 1px solid var(--ink); background: var(--ink); color: white; padding: .7rem 1rem; font: inherit; font-size: .85rem; font-weight: 600; border-radius: .4rem; cursor: pointer; }
    button:disabled { opacity: .55; cursor: wait; }
    .rule { margin-top: 2rem; border-top: 1px solid var(--line); padding-top: 1.4rem; }
    h2 { font-size: 1.05rem; margin: 0 0 .7rem; }
    .rule p { color: var(--muted); line-height: 1.7; margin: 0 0 .6rem; max-width: 70ch; }
    .rule .hint { font-size: .8rem; }
  `,
})
export class WorkspaceLanding {
  readonly auth = inject(Authentication);
  readonly session = inject(WorkspaceSession);
  private readonly router = inject(Router);
  readonly retrying = signal(false);

  roleNames(): string {
    return (this.session.profile()?.roles ?? []).map(roleLabel).join(', ') || 'No roles';
  }

  async retryAccess(): Promise<void> {
    if (this.retrying()) return;
    this.retrying.set(true);
    const profile = await this.session.refresh();
    this.retrying.set(false);
    if (profile !== null) await this.router.navigateByUrl('/requests');
  }
}
