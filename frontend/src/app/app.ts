import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Authentication } from './authentication';
import { SystemInfo, SystemStatus } from './system-status';
import { SessionPanel } from './session-panel';
import { roleLabel } from './workspace/format';
import { WorkspaceSession } from './workspace/workspace-session';

type ConnectionState =
  | { kind: 'loading' }
  | { kind: 'ready'; info: SystemInfo }
  | { kind: 'error' };

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, SessionPanel],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly system = inject(SystemStatus);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  readonly auth = inject(Authentication);
  readonly session = inject(WorkspaceSession);

  readonly connection = signal<ConnectionState>({ kind: 'loading' });
  readonly signingOut = signal(false);
  readonly authenticated = computed(() => this.auth.state() === 'authenticated' && this.auth.member() !== null);
  readonly showNewRequest = computed(() => this.authenticated() && this.session.isEmployee());
  readonly showApprovals = computed(() => this.authenticated() && this.session.canReachApprovals());
  /**
   * Product navigation follows only the current `/api/me` membership. The compact sign-in
   * snapshot held by `Authentication` is not an authorization source and may be older.
   */
  readonly showBudgets = computed(
    () => this.authenticated() && (this.session.isManager() || this.session.isFinance() || this.session.hasRole('COMPANY_ADMIN')),
  );
  readonly showAdministration = computed(() => this.authenticated() && this.session.hasRole('COMPANY_ADMIN'));
  private wasAuthenticated = false;

  constructor() {
    this.loadStatus();
    void this.auth.initialize();
    effect(() => {
      const state = this.auth.state();
      const sessionState = this.session.state();
      if (state === 'authenticated') {
        this.wasAuthenticated = true;
        // A revoked/expired membership discovered by the scoped profile read must not leave
        // role-dependent screens with stale controls or a permanent loading state.
        if ((sessionState === 'forbidden' || sessionState === 'error') && !this.router.url.startsWith('/welcome')) {
          void this.router.navigateByUrl('/welcome');
        }
        return;
      }
      if (this.wasAuthenticated && state !== 'starting' && !this.router.url.startsWith('/welcome')) {
        // A logout, revocation or retry invalidates member-bound screens. SessionPanel stays visible.
        void this.router.navigateByUrl('/welcome');
      }
    });
  }

  loadStatus(): void {
    this.connection.set({ kind: 'loading' });
    this.system.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (info) => this.connection.set({ kind: 'ready', info }),
      error: () => this.connection.set({ kind: 'error' }),
    });
  }

  roleNames(): string {
    const profile = this.session.profile();
    if (profile !== null) return profile.roles.map(roleLabel).join(', ') || 'No roles';
    // The initial sign-in snapshot is deliberately not reused here: a stale COMPANY_ADMIN
    // label would imply authority that the current scoped membership has not confirmed.
    return this.session.state() === 'loading' || this.session.state() === 'idle'
      ? 'Checking current membership…'
      : 'Current roles unavailable';
  }

  /** Display-only scope label; the scoped profile wins over the older sign-in snapshot. */
  accountCompany(): string {
    return this.session.profile()?.companyName ?? this.auth.member()?.companyName ?? '';
  }

  /** Display-only scope label; the scoped profile wins over the older sign-in snapshot. */
  accountDepartment(): string {
    return this.session.profile()?.departmentName ?? this.auth.member()?.departmentName ?? '';
  }

  async signOut(): Promise<void> {
    if (this.signingOut()) return;
    this.signingOut.set(true);
    await this.auth.logout();
    this.signingOut.set(false);
  }
}
