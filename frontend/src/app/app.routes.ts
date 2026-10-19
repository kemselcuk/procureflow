import { Routes } from '@angular/router';
import { ApprovalInboxPage } from './requests/approval-inbox-page';
import { RequestDetailPage } from './requests/request-detail-page';
import { RequestFormPage } from './requests/request-form-page';
import { RequestListPage } from './requests/request-list-page';
import { NotFoundPage } from './workspace/not-found-page';
import { NotificationsPage } from './workspace/notifications-page';
import { authenticatedGuard } from './workspace/session.guard';
import { WorkspaceLanding } from './workspace/workspace-landing';

/**
 * Standard same-origin router paths. No return URL or external redirect parameter is accepted.
 * The default member landing redirects to the own-request list; the guard sends settled
 * non-members to the public workspace page instead of starting a login loop.
 *
 * The company-admin area routes are declared before the shorter administration path so the
 * audit deep link resolves to its own page. Role-specific denial is rendered by the mounted
 * component; the backend remains authoritative for every fetch.
 */
export const appRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'requests' },
  { path: 'welcome', component: WorkspaceLanding },
  { path: 'requests', canActivate: [authenticatedGuard], component: RequestListPage },
  { path: 'requests/new', canActivate: [authenticatedGuard], component: RequestFormPage },
  { path: 'requests/:id', canActivate: [authenticatedGuard], component: RequestDetailPage },
  { path: 'approvals', canActivate: [authenticatedGuard], component: ApprovalInboxPage },
  {
    path: 'budgets',
    canActivate: [authenticatedGuard],
    // Newly mounted product areas are split into their own chunks so the initial bundle stays
    // within the existing build budget; they are still bundled and served by this application.
    loadComponent: () => import('./budgets/budgets-page').then((module) => module.BudgetsPage),
  },
  {
    path: 'administration/audit',
    canActivate: [authenticatedGuard],
    loadComponent: () => import('./audit/audit-page').then((module) => module.AuditPage),
  },
  {
    path: 'administration',
    canActivate: [authenticatedGuard],
    loadComponent: () =>
      import('./administration/administration-page').then((module) => module.AdministrationPage),
  },
  { path: 'notifications', canActivate: [authenticatedGuard], component: NotificationsPage },
  { path: '**', component: NotFoundPage },
];
