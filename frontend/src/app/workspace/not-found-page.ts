import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="not-found" aria-labelledby="not-found-title">
      <p class="eyebrow">404</p>
      <h1 id="not-found-title">Page not found</h1>
      <p>The address does not match a workspace screen. No purchase data was loaded.</p>
      <div class="actions">
        <a class="primary" routerLink="/requests">Go to my requests</a>
        <a routerLink="/welcome">Open the workspace home</a>
      </div>
    </section>
  `,
  styles: `
    :host { display: block; }
    .not-found { background: white; border: 1px solid var(--line); border-radius: .8rem; padding: clamp(1.4rem, 4vw, 2.4rem); }
    .eyebrow { font-size: .7rem; letter-spacing: .13em; font-weight: 750; color: var(--accent); margin: 0 0 .8rem; }
    h1 { margin: 0 0 .8rem; font-size: clamp(1.6rem, 4vw, 2.2rem); letter-spacing: -.03em; }
    p { color: var(--muted); line-height: 1.7; margin: 0 0 1.2rem; }
    .actions { display: flex; flex-wrap: wrap; gap: .8rem; align-items: center; }
    .primary { background: var(--ink); color: white; padding: .7rem 1rem; border-radius: .4rem; text-decoration: none; font-weight: 600; }
    a { color: var(--ink); text-underline-offset: .25em; }
  `,
})
export class NotFoundPage {}
