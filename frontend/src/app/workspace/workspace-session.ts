import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Authentication } from '../authentication';
import { failureFor } from './failure';
import { WorkspaceApi, WorkspaceProfile, WorkspaceRole } from './workspace-api';

export type WorkspaceSessionState = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden';

interface InFlightProfile {
  readonly key: string;
  readonly epoch: number;
  readonly requestId: number;
  readonly promise: Promise<WorkspaceProfile | null>;
}

/**
 * Session-bound view of the current membership.
 *
 * `Authentication` owns the token lifecycle and deliberately exposes only the compact sign-in
 * profile. This service reads the richer `/api/me` view for authorization-aware presentation.
 * It never treats those fields as authority: every write is still decided by the backend.
 *
 * Invalidation rules:
 * - company or identity changes increment `generation` and clear the cached profile;
 * - a refreshed `/api/me` whose membership version, roles, department or manager assignments
 *   differ increments `generation`, so mounted screens drop stale role-dependent UI;
 * - only the newest in-flight profile response may be applied.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceSession {
  private readonly api = inject(WorkspaceApi);
  private readonly auth = inject(Authentication);
  private readonly destroyRef = inject(DestroyRef);

  readonly profile = signal<WorkspaceProfile | null>(null);
  readonly state = signal<WorkspaceSessionState>('idle');
  readonly errorMessage = signal<string | null>(null);

  /** Company + identity of the signed-in person; changes when a different session is active. */
  readonly ownerKey = computed(() => {
    const member = this.auth.member();
    return this.auth.state() === 'authenticated' && member ? `${member.companyId}\u0000${member.identityId}` : null;
  });

  /** Effective membership snapshot version. Screens use it to ignore late responses. */
  readonly generation = signal(0);

  readonly roles = computed<ReadonlySet<WorkspaceRole>>(() => new Set(this.profile()?.roles ?? []));
  readonly isEmployee = computed(() => this.roles().has('EMPLOYEE'));
  readonly isManager = computed(() => this.roles().has('DEPARTMENT_MANAGER'));
  readonly isFinance = computed(() => this.roles().has('FINANCE_APPROVER'));
  readonly canReachApprovals = computed(() => this.isManager() || this.isFinance());

  private readonly authKey = this.ownerKey;
  private lastAuthKey: string | null | undefined = undefined;
  private appliedFingerprint: string | null = null;
  private inFlight: InFlightProfile | null = null;
  /**
   * Monotonic epoch for the authenticated owner. It changes on every real transition, including
   * logout followed by sign-in of the same company/identity, so an old response cannot become
   * current again merely because its string key matches.
   */
  private authEpoch = 0;
  private requestSequence = 0;

  constructor() {
    effect(() => {
      const key = this.authKey();
      if (key === this.lastAuthKey) return;
      this.handleAuthChange(key);
    });

    if (typeof window !== 'undefined') {
      const refreshOnFocus = (): void => {
        if (this.authKey() !== null) void this.ensure(true);
      };
      window.addEventListener('focus', refreshOnFocus);
      this.destroyRef.onDestroy(() => window.removeEventListener('focus', refreshOnFocus));
    }
  }

  hasRole(role: WorkspaceRole): boolean {
    return this.roles().has(role);
  }

  /**
   * Loads the current membership. A guard calls this with `force` on each navigation so revoked
   * roles or manager assignments take effect without waiting for token expiry.
   */
  ensure(force = false): Promise<WorkspaceProfile | null> {
    const key = this.authKey();
    if (key === null) {
      this.clear();
      return Promise.resolve(null);
    }
    // The effect normally observes an owner transition first. Adopt it synchronously here as well
    // so a guard that runs in the same turn cannot start a request that the effect immediately
    // invalidates.
    if (key !== this.lastAuthKey) this.handleAuthChange(key);

    const current = this.profile();
    if (!force && current !== null && this.state() === 'ready') return Promise.resolve(current);
    if (
      this.inFlight !== null &&
      this.inFlight.key === key &&
      this.inFlight.epoch === this.authEpoch
    ) {
      return this.inFlight.promise;
    }

    const epoch = this.authEpoch;
    const requestId = ++this.requestSequence;
    this.state.set('loading');
    this.errorMessage.set(null);
    const promise = firstValueFrom(this.api.loadProfile())
      .then((profile) => {
        if (!this.isCurrentRequest(key, epoch, requestId)) return null;
        if (!this.apply(profile)) {
          this.profile.set(null);
          this.state.set('error');
          this.errorMessage.set(
            'The membership response did not match the active signed-in identity. Sign in again.',
          );
          this.generation.update((value) => value + 1);
          return null;
        }
        return profile;
      })
      .catch((error: unknown) => {
        if (!this.isCurrentRequest(key, epoch, requestId)) return null;
        const failure = failureFor(error, 'profile');
        this.profile.set(null);
        this.state.set(failure.status === 403 ? 'forbidden' : 'error');
        this.errorMessage.set(
          failure.status === 403
            ? 'Your identity is valid, but this workspace needs an active company membership. Contact your company administrator.'
            : failure.message,
        );
        this.generation.update((value) => value + 1);
        return null;
      })
      .finally(() => {
        if (this.inFlight?.promise === promise) this.inFlight = null;
      });
    this.inFlight = { key, epoch, requestId, promise };
    return promise;
  }

  /** Forces a fresh membership read; used by explicit refresh/retry actions. */
  refresh(): Promise<WorkspaceProfile | null> {
    return this.ensure(true);
  }

  private handleAuthChange(key: string | null): void {
    if (key === this.lastAuthKey) return;
    const initial = this.lastAuthKey === undefined;
    this.lastAuthKey = key;
    if (!initial) this.authEpoch += 1;
    this.inFlight = null;
    this.appliedFingerprint = null;
    this.generation.update((value) => value + 1);
    this.profile.set(null);
    this.errorMessage.set(null);
    if (key === null) {
      this.state.set('idle');
      return;
    }
    this.state.set('loading');
    void this.ensure(false);
  }

  private isCurrentRequest(key: string, epoch: number, requestId: number): boolean {
    return (
      this.authKey() === key &&
      this.authEpoch === epoch &&
      this.inFlight?.requestId === requestId
    );
  }

  private apply(profile: WorkspaceProfile): boolean {
    const member = this.auth.member();
    if (
      this.auth.state() !== 'authenticated' ||
      member === null ||
      profile.identityId !== member.identityId ||
      profile.companyId !== member.companyId
    ) {
      return false;
    }
    const fingerprint = JSON.stringify([
      profile.identityId,
      profile.membershipId,
      profile.companyId,
      profile.version,
      profile.departmentId,
      [...profile.roles].sort(),
      [...profile.managedDepartmentIds].sort(),
    ]);
    this.profile.set(profile);
    this.state.set('ready');
    this.errorMessage.set(null);
    if (fingerprint !== this.appliedFingerprint) {
      this.appliedFingerprint = fingerprint;
      this.generation.update((value) => value + 1);
    }
    return true;
  }

  private clear(): void {
    this.inFlight = null;
    this.appliedFingerprint = null;
    this.profile.set(null);
    this.state.set('idle');
    this.errorMessage.set(null);
  }
}
