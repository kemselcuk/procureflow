import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { appRoutes } from './app.routes';
import { Authentication } from './authentication';
import { authInterceptor } from './auth-interceptor';
import { NavigationIntent } from './workspace/navigation-intent';

/**
 * Authentication must settle before the router performs its initial navigation. Otherwise the
 * router can finalize against the pre-callback address and reintroduce the OIDC fragment that
 * keycloak-js already removed, and a root callback loses the requested internal path.
 */
function initializeAuthentication(): Promise<void> {
  const authentication = inject(Authentication);
  const navigationIntent = inject(NavigationIntent);
  return authentication.initialize().then(() => {
    navigationIntent.restoreAfterAuthentication(authentication.state() === 'authenticated');
  });
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter(appRoutes),
    provideAppInitializer(initializeAuthentication),
  ],
};
