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
  window['env']['oidcIssuerUri'] = undefined;
  window['env']['oidcClientId'] = undefined;
  window['env']['oidcRedirectUri'] = undefined;
  window['env']['oidcLogoutRedirectUri'] = undefined;
  window['env']['oidcScopes'] = undefined;
  window['env']['whiteListedDomain'] = undefined;
  window['env']['epistolaEnabled'] = true;
})(this);
