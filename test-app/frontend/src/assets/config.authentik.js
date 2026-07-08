(function (window) {
  window['env'] = window['env'] || {};

  // Environment variables
  window['env']['swaggerUri'] = undefined;
  window['env']['mockApiUri'] = undefined;
  window['env']['apiUri'] = undefined;
  window['env']['authProvider'] = 'authentik';
  window['env']['keycloakUrl'] = undefined;
  window['env']['keycloakRealm'] = undefined;
  window['env']['keycloakClientId'] = undefined;
  window['env']['keycloakRedirectUri'] = undefined;
  window['env']['keycloakLogoutRedirectUri'] = undefined;
  window['env']['oidcIssuerUri'] = 'http://localhost:9000/application/o/valtimo-demo/';
  window['env']['oidcClientId'] = 'valtimo-console';
  window['env']['oidcRedirectUri'] = 'http://localhost:4200/auth/callback';
  window['env']['oidcLogoutRedirectUri'] = 'http://localhost:4200';
  window['env']['oidcScopes'] = 'openid profile email roles';
  window['env']['whiteListedDomain'] = undefined;
  window['env']['epistolaEnabled'] = true;
})(this);
