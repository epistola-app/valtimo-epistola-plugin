{{/*
Expand the name of the chart.
*/}}
{{- define "valtimo-demo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "valtimo-demo.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Backend fully qualified name.
*/}}
{{- define "valtimo-demo.backend.fullname" -}}
{{- printf "%s-backend" (include "valtimo-demo.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Frontend fully qualified name.
*/}}
{{- define "valtimo-demo.frontend.fullname" -}}
{{- printf "%s-frontend" (include "valtimo-demo.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "valtimo-demo.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "valtimo-demo.labels" -}}
helm.sh/chart: {{ include "valtimo-demo.chart" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Backend labels
*/}}
{{- define "valtimo-demo.backend.labels" -}}
{{ include "valtimo-demo.labels" . }}
{{ include "valtimo-demo.backend.selectorLabels" . }}
{{- end }}

{{/*
Backend selector labels
*/}}
{{- define "valtimo-demo.backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "valtimo-demo.name" . }}-backend
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: backend
{{- end }}

{{/*
Frontend labels
*/}}
{{- define "valtimo-demo.frontend.labels" -}}
{{ include "valtimo-demo.labels" . }}
{{ include "valtimo-demo.frontend.selectorLabels" . }}
{{- end }}

{{/*
Frontend selector labels
*/}}
{{- define "valtimo-demo.frontend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "valtimo-demo.name" . }}-frontend
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: frontend
{{- end }}

{{/*
Keycloak fully qualified name.
*/}}
{{- define "valtimo-demo.keycloak.fullname" -}}
{{- printf "%s-keycloak" (include "valtimo-demo.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Keycloak labels
*/}}
{{- define "valtimo-demo.keycloak.labels" -}}
{{ include "valtimo-demo.labels" . }}
{{ include "valtimo-demo.keycloak.selectorLabels" . }}
{{- end }}

{{/*
Keycloak selector labels
*/}}
{{- define "valtimo-demo.keycloak.selectorLabels" -}}
app.kubernetes.io/name: {{ include "valtimo-demo.name" . }}-keycloak
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: keycloak
{{- end }}

{{/*
Keycloak database host — explicit override or CNPG cluster RW service.
*/}}
{{- define "valtimo-demo.keycloak.dbHost" -}}
{{- if .Values.keycloak.database.host }}
{{- .Values.keycloak.database.host }}
{{- else }}
{{- include "valtimo-demo.database.host" . }}
{{- end }}
{{- end }}

{{/*
Keycloak database secret name — explicit override or CNPG auto-generated secret.
*/}}
{{- define "valtimo-demo.keycloak.dbSecretName" -}}
{{- if .Values.keycloak.database.existingSecret }}
{{- .Values.keycloak.database.existingSecret }}
{{- else }}
{{- include "valtimo-demo.cnpg.secretName" . }}
{{- end }}
{{- end }}

{{/*
Keycloak internal URL (for backend-to-keycloak communication within the cluster).
*/}}
{{- define "valtimo-demo.keycloak.internalUrl" -}}
{{- if .Values.keycloak.enabled }}
{{- $port := .Values.keycloak.service.port | default 80 | int }}
{{- if eq $port 80 }}
{{- printf "http://%s/auth" (include "valtimo-demo.keycloak.fullname" .) }}
{{- else }}
{{- printf "http://%s:%d/auth" (include "valtimo-demo.keycloak.fullname" .) $port }}
{{- end }}
{{- else }}
{{- .Values.externalKeycloak.url }}
{{- end }}
{{- end }}

{{/*
Keycloak external URL (used by browser clients to reach Keycloak).
Priority: explicit frontendUrl > ingress-derived > internal URL.
*/}}
{{- define "valtimo-demo.keycloak.externalUrl" -}}
{{- if .Values.publicUrls.keycloak }}
{{- trimSuffix "/" .Values.publicUrls.keycloak }}
{{- else if .Values.externalKeycloak.frontendUrl }}
{{- .Values.externalKeycloak.frontendUrl }}
{{- else if and .Values.keycloak.enabled .Values.ingress.enabled }}
{{- $host := (index .Values.ingress.hosts 0).host }}
{{- $scheme := ternary "https" "http" (not (empty .Values.ingress.tls)) }}
{{- printf "%s://%s/auth" $scheme $host }}
{{- else }}
{{- include "valtimo-demo.keycloak.internalUrl" . }}
{{- end }}
{{- end }}

{{/*
Base URL for the application (scheme + host), derived from ingress or appHostname.
*/}}
{{- define "valtimo-demo.appBaseUrl" -}}
{{- if .Values.publicUrls.frontend }}
{{- trimSuffix "/" .Values.publicUrls.frontend }}
{{- else if .Values.ingress.enabled }}
{{- $host := (index .Values.ingress.hosts 0).host }}
{{- $scheme := ternary "https" "http" (not (empty .Values.ingress.tls)) }}
{{- printf "%s://%s" $scheme $host }}
{{- else }}
{{- printf "http://%s" .Values.backend.valtimo.appHostname }}
{{- end }}
{{- end }}

{{/*
Return the hostname component of a URL string.
*/}}
{{- define "valtimo-demo.hostFromUrl" -}}
{{- $url := .url -}}
{{- if $url }}
  {{- $parsed := urlParse $url -}}
  {{- if $parsed.host -}}
    {{- $parsed.host -}}
  {{- else -}}
    {{- $url -}}
  {{- end -}}
{{- else -}}
  {{- "" -}}
{{- end -}}
{{- end }}

{{/*
Keycloak redirect URI for the frontend OIDC client.
*/}}
{{- define "valtimo-demo.keycloak.redirectUri" -}}
{{- if .Values.frontend.env.keycloakRedirectUri }}
{{- .Values.frontend.env.keycloakRedirectUri }}
{{- else }}
{{- include "valtimo-demo.appBaseUrl" . }}
{{- end }}
{{- end }}

{{/*
Keycloak logout redirect URI for the frontend OIDC client.
*/}}
{{- define "valtimo-demo.keycloak.logoutRedirectUri" -}}
{{- if .Values.frontend.env.keycloakLogoutRedirectUri }}
{{- .Values.frontend.env.keycloakLogoutRedirectUri }}
{{- else }}
{{- include "valtimo-demo.appBaseUrl" . }}
{{- end }}
{{- end }}

{{/*
Whitelisted domain for Angular HTTP interceptor (hostname only).
*/}}
{{- define "valtimo-demo.whitelistedDomain" -}}
{{- if .Values.frontend.env.whitelistedDomain }}
{{- .Values.frontend.env.whitelistedDomain }}
{{- else if .Values.publicUrls.frontend }}
{{- include "valtimo-demo.hostFromUrl" (dict "url" .Values.publicUrls.frontend) }}
{{- else if .Values.ingress.enabled }}
{{- (index .Values.ingress.hosts 0).host }}
{{- else }}
{{- "localhost" }}
{{- end }}
{{- end }}

{{/*
 Valtimo application hostname used by backend for redirects.
*/}}
{{- define "valtimo-demo.valtimoAppHostname" -}}
{{- if .Values.publicUrls.frontend }}
{{- include "valtimo-demo.hostFromUrl" (dict "url" .Values.publicUrls.frontend) }}
{{- else }}
{{- .Values.backend.valtimo.appHostname }}
{{- end }}
{{- end }}

{{/*
 Keycloak hostname (without scheme) for KC_HOSTNAME.
*/}}
{{- define "valtimo-demo.keycloak.host" -}}
{{- $external := include "valtimo-demo.keycloak.externalUrl" . -}}
{{- if $external }}
  {{- include "valtimo-demo.hostFromUrl" (dict "url" $external) -}}
{{- else -}}
  {{- "" -}}
{{- end -}}
{{- end }}

{{/*
Keycloak full external URL for KC_HOSTNAME v2 (includes scheme and relative path).
The OIDC issuer is derived from this value, so it must include /auth to match
the URLs that backend services expect.
*/}}
{{- define "valtimo-demo.keycloak.hostnameUrl" -}}
{{- include "valtimo-demo.keycloak.externalUrl" . -}}
{{- end }}

{{/*
  Bootstrap admin password for Keycloak. Resuses existing secret when present to
  avoid regenerating credentials on every upgrade.
*/}}
{{- define "valtimo-demo.keycloak.adminPassword" -}}
{{- $password := .Values.keycloak.adminPassword | default "" -}}
{{- if and .Values.keycloak.enabled (eq $password "") -}}
  {{- $existing := lookup "v1" "Secret" .Release.Namespace (include "valtimo-demo.backend.fullname" .) -}}
  {{- if and $existing (index $existing.data "keycloak-admin-password") -}}
    {{- $password = ((index $existing.data "keycloak-admin-password") | b64dec) -}}
  {{- else -}}
    {{- $password = randAlphaNum 32 -}}
  {{- end -}}
{{- end -}}
{{- $password -}}
{{- end }}

{{/*
CNPG cluster name for Valtimo database.
*/}}
{{- define "valtimo-demo.cnpg.clusterName" -}}
{{- if .Values.database.cnpg.name }}
{{- .Values.database.cnpg.name }}
{{- else }}
{{- printf "%s-db" (include "valtimo-demo.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
CNPG secret name for Valtimo database credentials.
For cnpg: auto-generated "{clusterName}-app"
For cnpgExisting: explicit secretName or "{clusterName}-app"
*/}}
{{- define "valtimo-demo.cnpg.secretName" -}}
{{- if eq .Values.database.type "cnpgExisting" }}
{{- if .Values.database.cnpgExisting.secretName }}
{{- .Values.database.cnpgExisting.secretName }}
{{- else }}
{{- printf "%s-app" .Values.database.cnpgExisting.clusterName }}
{{- end }}
{{- else }}
{{- printf "%s-app" (include "valtimo-demo.cnpg.clusterName" .) }}
{{- end }}
{{- end }}

{{/*
Database host for the Valtimo backend (CNPG RW service or external host).
*/}}
{{- define "valtimo-demo.database.host" -}}
{{- if eq .Values.database.type "cnpg" }}
{{- printf "%s-rw" (include "valtimo-demo.cnpg.clusterName" .) }}
{{- else if eq .Values.database.type "cnpgExisting" }}
{{- printf "%s-rw" .Values.database.cnpgExisting.clusterName }}
{{- else }}
{{- .Values.database.external.host }}
{{- end }}
{{- end }}

{{/*
Database port for the Valtimo backend.
*/}}
{{- define "valtimo-demo.database.port" -}}
{{- if or (eq .Values.database.type "cnpg") (eq .Values.database.type "cnpgExisting") }}
{{- 5432 }}
{{- else }}
{{- .Values.database.external.port }}
{{- end }}
{{- end }}

{{/*
Image tag: per-component tag > global.imageTag > Chart.appVersion.
*/}}
{{- define "valtimo-demo.imageTag" -}}
{{- .componentTag | default .global.imageTag | default .appVersion }}
{{- end }}

{{/*
Epistola service URL (for Valtimo backend to reach Epistola).
*/}}
{{- define "valtimo-demo.epistola.serviceUrl" -}}
{{- if .Values.epistola.enabled }}
{{- printf "http://%s-epistola:%v/api" .Release.Name (.Values.epistola.service.port | default 8080) }}
{{- else }}
{{- .Values.externalEpistola.url }}
{{- end }}
{{- end }}
