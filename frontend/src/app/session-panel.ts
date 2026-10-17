import { Component, inject } from '@angular/core';
import { Authentication } from './authentication';

@Component({
  selector: 'app-session-panel',
  standalone: true,
  template: `
    <section aria-labelledby="session-title" class="session">
      <h2 id="session-title">Your workspace</h2>
      <div role="status" aria-live="polite">
        @switch (auth.state()) {
          @case ('starting') { <p>Checking your sign-in…</p> }
          @case ('anonymous') { <p>Sign in with your local Keycloak account.</p><button (click)="auth.login()">Sign in</button> }
          @case ('authenticated') {
            <p><strong>{{ auth.member()?.companyName }}</strong> · {{ auth.member()?.departmentName }}</p>
            <p>Assigned roles: {{ auth.member()?.roles?.join(', ') }}</p>
            <p>Identity and access are ready. Purchase-request screens are still in development.</p>
            <button (click)="auth.logout()">Sign out</button>
          }
          @case ('forbidden') {
            <p>Your identity is valid, but an active company membership is required. Contact your company administrator.</p>
            <button (click)="auth.logout()">Sign out</button>
          }
          @case ('error') { <p>Sign-in services are unavailable. Try again.</p><button (click)="auth.retry()">Retry sign-in</button> }
        }
      </div>
    </section>
  `,
  styles: `
    .session { border: 1px solid var(--line); border-radius: .8rem; padding: 1.5rem; margin: 0 0 1.5rem; background: white; }
    h2 { margin-top: 0; font-size: 1.1rem; } p { color: var(--muted); line-height: 1.6; font-size: .9rem; }
    button { background: var(--ink); color: white; border: 0; border-radius: .4rem; padding: .75rem 1rem; font: inherit; cursor: pointer; }
  `,
})
export class SessionPanel {
  readonly auth = inject(Authentication);
  constructor() { void this.auth.initialize(); }
}
