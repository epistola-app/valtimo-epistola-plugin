(function (window) {
  window['env'] = window['env'] || {};

  // Environment variables
  window['env']['swaggerUri'] = '${SWAGGER_URI}';
  window['env']['mockApiUri'] = '${MOCK_API_URI}';
  window['env']['apiUri'] = '${API_URI}';
  window['env']['authProvider'] = '${AUTH_PROVIDER}';
  window['env']['keycloakUrl'] = '${KEYCLOAK_URL}';
  window['env']['keycloakRealm'] = '${KEYCLOAK_REALM}';
  window['env']['keycloakClientId'] = '${KEYCLOAK_CLIENT_ID}';
  window['env']['keycloakRedirectUri'] = '${KEYCLOAK_REDIRECT_URI}';
  window['env']['keycloakLogoutRedirectUri'] = '${KEYCLOAK_LOGOUT_REDIRECT_URI}';
  window['env']['oidcIssuerUri'] = '${OIDC_ISSUER_URI}';
  window['env']['oidcClientId'] = '${OIDC_CLIENT_ID}';
  window['env']['oidcRedirectUri'] = '${OIDC_REDIRECT_URI}';
  window['env']['oidcLogoutRedirectUri'] = '${OIDC_LOGOUT_REDIRECT_URI}';
  window['env']['oidcScopes'] = '${OIDC_SCOPES}';
  window['env']['whiteListedDomain'] = '${WHITELISTED_DOMAIN}';
  window['env']['openZaakCatalogusId'] = '${OPENZAAK_CATALOGUS_ID}';
  window['env']['epistolaEnabled'] = '${EPISTOLA_ENABLED}';
})(this);
