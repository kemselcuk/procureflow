import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Authentication } from '../authentication';
import { WorkspaceSession } from './workspace-session';

/**
 * UX gate for member screens. It waits for the existing sign-in initialization, then verifies a
 * current membership profile. It never starts a login and never carries a return URL.
 *
 * This is not authorization: every API call is still decided by the backend. Anonymous,
 * forbidden and failed sessions are sent to the public workspace page, where the untouched
 * SessionPanel can show the correct state and retry action.
 */
export const authenticatedGuard: CanActivateFn = async () => {
  const auth = inject(Authentication);
  const session = inject(WorkspaceSession);
  const router = inject(Router);

  if (auth.state() === 'starting') await auth.initialize();
  // A still-starting or settled non-authenticated state fails closed. No login is triggered.
  if (auth.state() !== 'authenticated' || auth.member() === null) {
    return router.createUrlTree(['/welcome']);
  }
  const profile = await session.ensure(true);
  return profile === null ? router.createUrlTree(['/welcome']) : true;
};
