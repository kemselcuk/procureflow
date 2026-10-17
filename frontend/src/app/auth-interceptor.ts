import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { Authentication } from './authentication';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const destination = new URL(request.url, window.location.origin);
  const isPrivateApi = destination.origin === window.location.origin && destination.pathname.startsWith('/api/') &&
    !['/api/system', '/api/auth/config'].includes(destination.pathname);
  if (!isPrivateApi) return next(request);
  const authentication = inject(Authentication);
  return from(authentication.accessToken()).pipe(switchMap((token) =>
    next(token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request),
  ));
};
