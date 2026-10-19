import { HttpClient } from '@angular/common/http';
import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import Keycloak, { KeycloakConfig } from 'keycloak-js';
import { firstValueFrom } from 'rxjs';
import { NavigationIntent } from './workspace/navigation-intent';

export const KEYCLOAK_FACTORY = new InjectionToken<(config: KeycloakConfig) => Keycloak>('KEYCLOAK_FACTORY', {
  providedIn: 'root', factory: () => (config) => new Keycloak(config),
});

export interface MemberProfile {
  identityId: string;
  companyId: string;
  companyName: string;
  departmentName: string;
  roles: string[];
}

@Injectable({ providedIn: 'root' })
export class Authentication {
  private readonly http = inject(HttpClient);
  private readonly factory = inject(KEYCLOAK_FACTORY);
  private readonly navigationIntent = inject(NavigationIntent);
  private keycloak?: Keycloak;
  private initialization?: Promise<void>;
  readonly state = signal<'starting' | 'anonymous' | 'authenticated' | 'forbidden' | 'error'>('starting');
  readonly member = signal<MemberProfile | null>(null);

  initialize(): Promise<void> {
    if (this.initialization === undefined) {
      // Capture before keycloak-js can replace the address with its exact root callback. Only a
      // bounded, whitelisted internal pathname is written; query, hash and credentials never are.
      this.navigationIntent.capture(window.location.pathname);
      this.initialization = this.start();
    }
    return this.initialization;
  }

  private async start(): Promise<void> {
    try {
      const configuration = await firstValueFrom(this.http.get<{ issuer: string; clientId: string }>('/api/auth/config'));
      const issuer = new URL(configuration.issuer);
      const marker = issuer.pathname.lastIndexOf('/realms/');
      if (!['http:', 'https:'].includes(issuer.protocol) || issuer.username || issuer.password || marker < 0) {
        throw new Error('Invalid OIDC configuration');
      }
      const realm = issuer.pathname.slice(marker + '/realms/'.length);
      if (!realm || realm.includes('/') || issuer.search || issuer.hash) throw new Error('Invalid OIDC realm');
      this.keycloak = this.factory({ url: issuer.origin + issuer.pathname.slice(0, marker), realm, clientId: configuration.clientId });
      this.keycloak.onAuthLogout = () => { this.navigationIntent.clear(); this.member.set(null); this.state.set('anonymous'); };
      this.keycloak.onTokenExpired = () => { void this.accessToken(); };
      const authenticated = await this.keycloak.init({
        onLoad: 'check-sso', pkceMethod: 'S256', flow: 'standard', checkLoginIframe: false,
        redirectUri: window.location.origin + '/',
      });
      if (!authenticated) { this.state.set('anonymous'); return; }
      await firstValueFrom(this.http.post('/api/identities/self', null));
      try {
        const member = await firstValueFrom(this.http.get<unknown>('/api/me'));
        if (typeof member !== 'object' || member === null ||
          !['identityId', 'companyId', 'companyName', 'departmentName'].every((key) => key in member && typeof (member as Record<string, unknown>)[key] === 'string') ||
          !('roles' in member) || !Array.isArray(member.roles) || !member.roles.every((role: unknown) => typeof role === 'string')) {
          throw new Error('Unexpected membership response');
        }
        this.member.set(member as MemberProfile);
        this.state.set('authenticated');
      } catch (error: unknown) {
        this.state.set(typeof error === 'object' && error !== null && 'status' in error && error.status === 403 ? 'forbidden' : 'error');
      }
    } catch { this.state.set('error'); }
  }

  async accessToken(): Promise<string | undefined> {
    if (!this.keycloak?.authenticated) return undefined;
    try {
      await this.keycloak.updateToken(30);
      return this.keycloak.token;
    } catch {
      this.keycloak.clearToken();
      this.navigationIntent.clear();
      this.member.set(null);
      this.state.set('anonymous');
      return undefined;
    }
  }

  async login(): Promise<void> {
    try { await this.keycloak?.login({ redirectUri: window.location.origin + '/' }); }
    catch { this.state.set('error'); }
  }

  async logout(): Promise<void> {
    // The pending return target belongs to this signed-in session and must not survive it.
    this.navigationIntent.clear();
    try { await this.keycloak?.logout({ redirectUri: window.location.origin + '/' }); }
    catch { this.state.set('error'); }
  }

  retry(): void {
    this.member.set(null); this.state.set('starting');
    this.keycloak?.clearToken(); this.keycloak = undefined;
    this.initialization = undefined;
    void this.initialize();
  }
}
