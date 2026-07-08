(function (window) {
  window['env'] = window['env'] || {};
  window['env']['swaggerUri'] = '/swagger-ui.html';
  window['env']['mockApiUri'] = '/mock-api';
  window['env']['apiUri'] = '/api';
  window['env']['authProvider'] = 'authentik';
  window['env']['keycloakUrl'] = '';
  window['env']['keycloakRealm'] = '';
  window['env']['keycloakClientId'] = '';
  window['env']['keycloakRedirectUri'] = '';
  window['env']['keycloakLogoutRedirectUri'] = '';
  window['env']['oidcIssuerUri'] = 'http://localhost:9000/application/o/valtimo-demo/';
  window['env']['oidcClientId'] = 'valtimo-console';
  window['env']['oidcRedirectUri'] = 'http://localhost:4200/auth/callback';
  window['env']['oidcLogoutRedirectUri'] = 'http://localhost:4200';
  window['env']['oidcScopes'] = 'openid profile email roles';
  window['env']['whiteListedDomain'] = 'localhost:4200';
  window['env']['openZaakCatalogusId'] = '';
})(this);
