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
Keycloak internal URL (for backend-to-keycloak communication within the cluster).
*/}}
{{- define "valtimo-demo.keycloak.internalUrl" -}}
{{- if .Values.keycloak.enabled }}
{{- printf "http://%s-keycloak:%v" .Release.Name (.Values.keycloak.service.ports.http | default 80) }}
{{- else }}
{{- .Values.externalKeycloak.url }}
{{- end }}
{{- end }}

{{/*
Keycloak external URL (used by browser clients to reach Keycloak).
Falls back to internal URL if not explicitly set.
*/}}
{{- define "valtimo-demo.keycloak.externalUrl" -}}
{{- if .Values.externalKeycloak.frontendUrl }}
{{- .Values.externalKeycloak.frontendUrl }}
{{- else }}
{{- include "valtimo-demo.keycloak.internalUrl" . }}
{{- end }}
{{- end }}

{{/*
Valtimo PostgreSQL hostname.
*/}}
{{- define "valtimo-demo.postgresql.host" -}}
{{- printf "%s-valtimopostgresql" .Release.Name }}
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
