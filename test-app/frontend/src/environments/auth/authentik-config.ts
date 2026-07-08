import { CommonModule } from '@angular/common';
import { HTTP_INTERCEPTORS, HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Component, Injectable, Injector, NgModule, OnDestroy } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivate, Router, RouterModule, RouterStateSnapshot } from '@angular/router';
import { Auth, AuthProviders, UserIdentity, UserService, ValtimoUserIdentity } from '@valtimo/shared';
import { jwtDecode } from 'jwt-decode';
import { NGXLogger } from 'ngx-logger';
import { Observable, ReplaySubject } from 'rxjs';

interface OidcDiscovery {
  authorization_endpoint: string;
  token_endpoint: string;
  end_session_endpoint?: string;
  userinfo_endpoint?: string;
}

interface TokenSet {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_at: number;
}

const STORAGE_KEYS = {
  codeVerifier: 'authentik.codeVerifier',
  state: 'authentik.state',
  redirectTo: 'authentik.redirectTo',
  tokens: 'authentik.tokens',
};

const oidcConfig = {
  issuerUri: stripTrailingSlash(window['env']['oidcIssuerUri'] || ''),
  clientId: window['env']['oidcClientId'] || 'valtimo-console',
  redirectUri: window['env']['oidcRedirectUri'] || 'http://localhost:4200/auth/callback',
  logoutRedirectUri: window['env']['oidcLogoutRedirectUri'] || 'http://localhost:4200',
  scopes: window['env']['oidcScopes'] || 'openid profile email roles',
  bearerExcludedUrls: ['/assets'],
};

export function initializerAuthentik(injector: Injector) {
  const service = injector.get(AuthentikUserService);

  return async () => service.init();
}

@Injectable({ providedIn: 'root' })
export class AuthentikOidcService {
  private discovery?: OidcDiscovery;

  constructor(private readonly logger: NGXLogger) {}

  async ensureDiscovery(): Promise<OidcDiscovery> {
    if (this.discovery) {
      return this.discovery;
    }

    if (!oidcConfig.issuerUri) {
      throw new Error('Missing oidcIssuerUri for Authentik authentication');
    }

    const response = await fetch(`${oidcConfig.issuerUri}/.well-known/openid-configuration`);
    if (!response.ok) {
      throw new Error(`Failed to load OIDC discovery document: ${response.status}`);
    }

    this.discovery = await response.json();
    return this.discovery;
  }

  async login(returnTo: string = window.location.pathname): Promise<void> {
    const discovery = await this.ensureDiscovery();
    const codeVerifier = randomBase64Url(64);
    const state = randomBase64Url(32);
    const codeChallenge = await sha256Base64Url(codeVerifier);
    const authorizationUrl = new URL(discovery.authorization_endpoint);

    sessionStorage.setItem(STORAGE_KEYS.codeVerifier, codeVerifier);
    sessionStorage.setItem(STORAGE_KEYS.state, state);
    sessionStorage.setItem(STORAGE_KEYS.redirectTo, returnTo || '/');

    authorizationUrl.searchParams.set('client_id', oidcConfig.clientId);
    authorizationUrl.searchParams.set('redirect_uri', oidcConfig.redirectUri);
    authorizationUrl.searchParams.set('response_type', 'code');
    authorizationUrl.searchParams.set('scope', oidcConfig.scopes);
    authorizationUrl.searchParams.set('state', state);
    authorizationUrl.searchParams.set('code_challenge', codeChallenge);
    authorizationUrl.searchParams.set('code_challenge_method', 'S256');

    window.location.assign(authorizationUrl.toString());
  }

  async handleCallback(url: string = window.location.href): Promise<string> {
    const callbackUrl = new URL(url);
    const code = callbackUrl.searchParams.get('code');
    const state = callbackUrl.searchParams.get('state');
    const expectedState = sessionStorage.getItem(STORAGE_KEYS.state);
    const codeVerifier = sessionStorage.getItem(STORAGE_KEYS.codeVerifier);

    if (!code || !state || !expectedState || state !== expectedState || !codeVerifier) {
      throw new Error('Invalid OIDC callback state');
    }

    const tokens = await this.requestTokens({
      grant_type: 'authorization_code',
      client_id: oidcConfig.clientId,
      redirect_uri: oidcConfig.redirectUri,
      code,
      code_verifier: codeVerifier,
    });

    this.storeTokens(tokens);
    sessionStorage.removeItem(STORAGE_KEYS.codeVerifier);
    sessionStorage.removeItem(STORAGE_KEYS.state);

    return sessionStorage.getItem(STORAGE_KEYS.redirectTo) || '/';
  }

  getTokens(): TokenSet | null {
    const rawTokens = sessionStorage.getItem(STORAGE_KEYS.tokens);
    return rawTokens ? JSON.parse(rawTokens) : null;
  }

  async getAccessToken(minValiditySeconds = 30): Promise<string | null> {
    const tokens = this.getTokens();

    if (!tokens) {
      return null;
    }

    if (tokens.expires_at - Date.now() > minValiditySeconds * 1000) {
      return tokens.access_token;
    }

    if (!tokens.refresh_token) {
      return null;
    }

    const refreshedTokens = await this.requestTokens({
      grant_type: 'refresh_token',
      client_id: oidcConfig.clientId,
      refresh_token: tokens.refresh_token,
    });
    this.storeTokens({...refreshedTokens, refresh_token: refreshedTokens.refresh_token || tokens.refresh_token});

    return this.getTokens()?.access_token || null;
  }

  isAuthenticated(): boolean {
    const tokens = this.getTokens();
    return !!tokens && tokens.expires_at > Date.now();
  }

  logout(): void {
    const idToken = this.getTokens()?.id_token;
    sessionStorage.removeItem(STORAGE_KEYS.tokens);

    this.ensureDiscovery()
      .then(discovery => {
        if (!discovery.end_session_endpoint) {
          window.location.assign(oidcConfig.logoutRedirectUri);
          return;
        }

        const logoutUrl = new URL(discovery.end_session_endpoint);
        logoutUrl.searchParams.set('post_logout_redirect_uri', oidcConfig.logoutRedirectUri);
        if (idToken) {
          logoutUrl.searchParams.set('id_token_hint', idToken);
        }
        window.location.assign(logoutUrl.toString());
      })
      .catch(error => {
        this.logger.warn('Failed to resolve logout endpoint', error);
        window.location.assign(oidcConfig.logoutRedirectUri);
      });
  }

  private async requestTokens(params: Record<string, string>): Promise<TokenSet> {
    const discovery = await this.ensureDiscovery();
    const body = new URLSearchParams(params);
    const response = await fetch(discovery.token_endpoint, {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body,
    });

    if (!response.ok) {
      throw new Error(`OIDC token request failed: ${response.status}`);
    }

    const tokenResponse = await response.json();
    const expiresIn = Number(tokenResponse.expires_in || 300);

    return {
      access_token: tokenResponse.access_token,
      refresh_token: tokenResponse.refresh_token,
      id_token: tokenResponse.id_token,
      expires_at: Date.now() + expiresIn * 1000,
    };
  }

  private storeTokens(tokens: TokenSet): void {
    sessionStorage.setItem(STORAGE_KEYS.tokens, JSON.stringify(tokens));
  }
}

@Injectable({ providedIn: 'root' })
export class AuthentikUserService implements UserService, OnDestroy {
  private readonly userIdentity = new ReplaySubject<UserIdentity>(1);

  constructor(
    private readonly oidcService: AuthentikOidcService,
    private readonly logger: NGXLogger
  ) {}

  async init(): Promise<boolean> {
    if (window.location.pathname === '/auth/callback') {
      const redirectTo = await this.oidcService.handleCallback();
      this.publishUserIdentity();
      window.history.replaceState({}, document.title, redirectTo);
      return true;
    }

    if (!this.oidcService.isAuthenticated()) {
      await this.oidcService.login(`${window.location.pathname}${window.location.search}`);
      return false;
    }

    this.publishUserIdentity();
    return true;
  }

  ngOnDestroy(): void {
    this.userIdentity.complete();
  }

  getUserSubject(): ReplaySubject<UserIdentity> {
    return this.userIdentity;
  }

  logout(): void {
    this.oidcService.logout();
  }

  async getToken(): Promise<string> {
    return (await this.oidcService.getAccessToken()) || '';
  }

  async updateToken(minValidity: number): Promise<boolean> {
    return !!(await this.oidcService.getAccessToken(minValidity));
  }

  publishUserIdentity(): void {
    const token = this.oidcService.getTokens()?.access_token;

    if (!token) {
      return;
    }

    const claims: any = jwtDecode(token);
    const roles = extractRoles(claims, oidcConfig.clientId);
    const user = new ValtimoUserIdentity(
      claims.email || claims.preferred_username || claims.sub,
      claims.given_name || '',
      claims.family_name || '',
      roles,
      claims.preferred_username || claims.email,
      claims.sub
    );

    this.logger.debug('Authentik user identity loaded', user);
    this.userIdentity.next(user);
  }
}

@Injectable({ providedIn: 'root' })
export class AuthentikAuthGuardService implements CanActivate {
  constructor(
    private readonly oidcService: AuthentikOidcService,
    private readonly userService: AuthentikUserService
  ) {}

  async canActivate(route: ActivatedRouteSnapshot, state: RouterStateSnapshot): Promise<boolean> {
    if (!this.oidcService.isAuthenticated()) {
      await this.oidcService.login(state.url);
      return false;
    }

    const requiredRoles: string[] = route.data.roles;
    if (!requiredRoles || requiredRoles.length === 0) {
      return true;
    }

    const token = this.oidcService.getTokens()?.access_token;
    const claims: any = token ? jwtDecode(token) : {};
    const roles = extractRoles(claims, oidcConfig.clientId);

    this.userService.publishUserIdentity();
    return requiredRoles.some(role => roles.includes(role));
  }
}

@Injectable()
export class AuthentikBearerInterceptor implements HttpInterceptor {
  constructor(private readonly oidcService: AuthentikOidcService) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    if (oidcConfig.bearerExcludedUrls.some(url => request.url.includes(url))) {
      return next.handle(request);
    }

    return new Observable<HttpEvent<unknown>>(subscriber => {
      this.oidcService
        .getAccessToken()
        .then(token => {
          const authorizedRequest = token
            ? request.clone({setHeaders: {Authorization: `Bearer ${token}`}})
            : request;
          next.handle(authorizedRequest).subscribe(subscriber);
        })
        .catch(error => subscriber.error(error));
    });
  }
}

@Component({
  standalone: false,
  selector: 'valtimo-authentik-callback',
  template: '',
})
export class AuthentikCallbackComponent {
  constructor(
    oidcService: AuthentikOidcService,
    userService: AuthentikUserService,
    router: Router
  ) {
    oidcService.handleCallback().then(redirectTo => {
      userService.publishUserIdentity();
      router.navigateByUrl(redirectTo);
    });
  }
}

@NgModule({
  declarations: [AuthentikCallbackComponent],
  imports: [
    CommonModule,
    RouterModule.forChild([
      {
        path: 'auth/callback',
        component: AuthentikCallbackComponent,
        data: {title: 'Loading...'},
      },
    ]),
  ],
  providers: [
    {
      provide: HTTP_INTERCEPTORS,
      useClass: AuthentikBearerInterceptor,
      multi: true,
    },
  ],
})
export class AuthentikModule {}

const authentikAuthenticationProviders: AuthProviders = {
  guardServiceProvider: AuthentikAuthGuardService,
  userServiceProvider: AuthentikUserService,
};

export const authenticationAuthentik: Auth = {
  module: AuthentikModule,
  initializer: initializerAuthentik,
  authProviders: authentikAuthenticationProviders,
  options: oidcConfig,
};

function extractRoles(claims: any, clientId: string): string[] {
  const realmRoles = claims?.realm_access?.roles || [];
  const clientRoles = claims?.resource_access?.[clientId]?.roles || [];
  return Array.from(new Set([...realmRoles, ...clientRoles]));
}

function stripTrailingSlash(value: string): string {
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

function randomBase64Url(length: number): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

async function sha256Base64Url(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes: Uint8Array): string {
  const binary = Array.from(bytes, byte => String.fromCharCode(byte)).join('');
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
