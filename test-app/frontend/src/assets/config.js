(function (window) {
  window['env'] = window['env'] || {};

  var authProviderParam = new URLSearchParams(window.location.search).get('authProvider');
  if (authProviderParam === 'keycloak' || authProviderParam === 'authentik') {
    window.localStorage.setItem('valtimo.authProvider', authProviderParam);
  }

  var authProvider = window.localStorage.getItem('valtimo.authProvider') || 'keycloak';

  // Environment variables
  window['env']['swaggerUri'] = undefined;
  window['env']['mockApiUri'] = undefined;
  window['env']['apiUri'] = undefined;
  window['env']['authProvider'] = authProvider === 'authentik' ? 'authentik' : undefined;
  window['env']['keycloakUrl'] = undefined;
  window['env']['keycloakRealm'] = undefined;
  window['env']['keycloakClientId'] = undefined;
  window['env']['keycloakRedirectUri'] = undefined;
  window['env']['keycloakLogoutRedirectUri'] = undefined;
  window['env']['oidcIssuerUri'] =
    authProvider === 'authentik' ? 'http://localhost:9000/application/o/valtimo-demo/' : undefined;
  window['env']['oidcClientId'] = authProvider === 'authentik' ? 'valtimo-console' : undefined;
  window['env']['oidcRedirectUri'] =
    authProvider === 'authentik' ? 'http://localhost:4200/auth/callback' : undefined;
  window['env']['oidcLogoutRedirectUri'] =
    authProvider === 'authentik' ? 'http://localhost:4200' : undefined;
  window['env']['oidcScopes'] =
    authProvider === 'authentik' ? 'openid profile email roles' : undefined;
  window['env']['whiteListedDomain'] = undefined;
  window['env']['epistolaEnabled'] = true;
})(this);
