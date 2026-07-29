// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

(function (window) {
  window['env'] = window['env'] || {};
  window['env']['swaggerUri'] = '/swagger-ui.html';
  window['env']['mockApiUri'] = '/mock-api';
  window['env']['apiUri'] = '/api';
  window['env']['authProvider'] = 'keycloak';
  window['env']['keycloakUrl'] = 'http://localhost:8081';
  window['env']['keycloakRealm'] = 'valtimo';
  window['env']['keycloakClientId'] = 'valtimo-console';
  window['env']['keycloakRedirectUri'] = 'http://localhost:4200';
  window['env']['keycloakLogoutRedirectUri'] = 'http://localhost:4200';
  window['env']['oidcIssuerUri'] = '';
  window['env']['oidcClientId'] = 'valtimo-console';
  window['env']['oidcRedirectUri'] = 'http://localhost:4200/auth/callback';
  window['env']['oidcLogoutRedirectUri'] = 'http://localhost:4200';
  window['env']['oidcScopes'] = 'openid profile email';
  window['env']['whiteListedDomain'] = 'localhost:4200';
  window['env']['openZaakCatalogusId'] = '';
})(this);
