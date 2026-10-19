import { Component } from '@angular/core';
import { NotificationsPanel } from '../notifications/notifications-panel';

/** Route wrapper only. The accepted notification panel and its behavior remain unchanged. */
@Component({
  selector: 'app-notifications-page',
  standalone: true,
  imports: [NotificationsPanel],
  template: `
    <section class="page" aria-labelledby="notifications-page-title">
      <p class="eyebrow">ACCOUNT UPDATES</p>
      <h1 id="notifications-page-title">Notifications</h1>
      <p class="lede">Submission, approval and terminal outcomes for requests relevant to you.</p>
      <app-notifications-panel />
    </section>
  `,
  styles: `
    :host { display: block; }
    .page { min-width: 0; }
    .eyebrow { font-size: .68rem; letter-spacing: .13em; font-weight: 750; color: var(--accent); margin: 0 0 .5rem; }
    h1 { font-size: 1.6rem; letter-spacing: -.03em; margin: 0 0 .5rem; }
    .lede { color: var(--muted); line-height: 1.6; margin: 0 0 1.2rem; }
  `,
})
export class NotificationsPage {}
