(function(window) {
  window['env'] = window['env'] || {};
  window['env']['swaggerUri'] = '/swagger-ui.html';
  window['env']['mockApiUri'] = '/mock-api';
  window['env']['apiUri'] = '/api';
  window['env']['keycloakUrl'] = 'http://localhost:8081';
  window['env']['keycloakRealm'] = 'valtimo';
  window['env']['keycloakClientId'] = 'valtimo-console';
  window['env']['keycloakRedirectUri'] = 'http://localhost:4200';
  window['env']['keycloakLogoutRedirectUri'] = 'http://localhost:4200';
  window['env']['whiteListedDomain'] = 'localhost:4200';
  window['env']['openZaakCatalogusId'] = '';
})(this);
