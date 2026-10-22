{{/*
Chart-wide naming and label helpers.
*/}}
{{- define "lyrics-canvas.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "lyrics-canvas.fullname" -}}
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

{{- define "lyrics-canvas.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "lyrics-canvas.labels" -}}
helm.sh/chart: {{ include "lyrics-canvas.chart" . }}
{{ include "lyrics-canvas.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "lyrics-canvas.selectorLabels" -}}
app.kubernetes.io/name: {{ include "lyrics-canvas.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Component resource names. */}}
{{- define "lyrics-canvas.backend.fullname" -}}
{{- printf "%s-backend" (include "lyrics-canvas.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "lyrics-canvas.frontend.fullname" -}}
{{- printf "%s-frontend" (include "lyrics-canvas.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "lyrics-canvas.postgres.fullname" -}}
{{- printf "%s-postgres" (include "lyrics-canvas.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "lyrics-canvas.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "lyrics-canvas.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image reference. Usage: include "lyrics-canvas.image" (dict "image" .Values.backend.image "default" .Chart.AppVersion)
*/}}
{{- define "lyrics-canvas.image" -}}
{{- printf "%s:%s" .image.repository (default .default .image.tag) }}
{{- end }}

{{- define "lyrics-canvas.imagePullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
Secrets. Generated values are read back from the live Secret (lookup) so an
upgrade never rotates them; `helm template` has no cluster and renders a fresh
random value each time.
*/}}
{{- define "lyrics-canvas.backend.secretName" -}}
{{- default (include "lyrics-canvas.backend.fullname" .) .Values.backend.existingSecret }}
{{- end }}

{{- define "lyrics-canvas.backend.secretKey" -}}
{{- if .Values.backend.secretKey }}
{{- .Values.backend.secretKey }}
{{- else }}
{{- $current := dig "data" "secret-key" "" (lookup "v1" "Secret" .Release.Namespace (include "lyrics-canvas.backend.fullname" .)) }}
{{- if $current }}{{ $current | b64dec }}{{ else }}{{ randAlphaNum 64 }}{{ end }}
{{- end }}
{{- end }}

{{- define "lyrics-canvas.postgres.secretName" -}}
{{- default (include "lyrics-canvas.postgres.fullname" .) .Values.postgres.auth.existingSecret }}
{{- end }}

{{- define "lyrics-canvas.postgres.password" -}}
{{- if .Values.postgres.auth.password }}
{{- .Values.postgres.auth.password }}
{{- else }}
{{- $current := dig "data" "password" "" (lookup "v1" "Secret" .Release.Namespace (include "lyrics-canvas.postgres.fullname" .)) }}
{{- if $current }}{{ $current | b64dec }}{{ else }}{{ randAlphaNum 32 }}{{ end }}
{{- end }}
{{- end }}

{{/* True when the chart must render the externalDatabase URL into its own Secret. */}}
{{- define "lyrics-canvas.externalDatabase.managed" -}}
{{- if and (not .Values.postgres.enabled) .Values.externalDatabase.url (not .Values.externalDatabase.existingSecret) }}true{{ end }}
{{- end }}

{{/*
Database env for the API and the migration init container. With the bundled
PostgreSQL the URL is assembled from the password Secret via $(VAR) expansion so
the password never appears in the manifest.
*/}}
{{- define "lyrics-canvas.backend.databaseEnv" -}}
{{- if .Values.postgres.enabled }}
- name: POSTGRES_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "lyrics-canvas.postgres.secretName" . }}
      key: {{ .Values.postgres.auth.existingSecretPasswordKey }}
- name: DATABASE_URL
  value: {{ printf "postgresql+asyncpg://%s:$(POSTGRES_PASSWORD)@%s:%d/%s" .Values.postgres.auth.username (include "lyrics-canvas.postgres.fullname" .) (int .Values.postgres.service.port) .Values.postgres.auth.database | quote }}
{{- else if .Values.externalDatabase.existingSecret }}
- name: DATABASE_URL
  valueFrom:
    secretKeyRef:
      name: {{ .Values.externalDatabase.existingSecret }}
      key: {{ .Values.externalDatabase.existingSecretKey }}
{{- else if .Values.externalDatabase.url }}
- name: DATABASE_URL
  valueFrom:
    secretKeyRef:
      name: {{ include "lyrics-canvas.backend.fullname" . }}
      key: database-url
{{- else }}
{{- fail "postgres.enabled=false requires externalDatabase.url or externalDatabase.existingSecret" }}
{{- end }}
{{- end }}

{{/*
CORS origins as the JSON list pydantic-settings expects. Explicit values win;
otherwise one origin per ingress host, https when that host is covered by a TLS
entry.
*/}}
{{- define "lyrics-canvas.backend.corsOrigins" -}}
{{- if .Values.backend.corsOrigins }}
{{- toJson .Values.backend.corsOrigins }}
{{- else if .Values.ingress.enabled }}
{{- $tlsHosts := list }}
{{- range .Values.ingress.tls }}{{ range .hosts }}{{ $tlsHosts = append $tlsHosts . }}{{ end }}{{ end }}
{{- $origins := list }}
{{- range .Values.ingress.hosts }}
{{- $origins = append $origins (printf "%s://%s" (ternary "https" "http" (has . $tlsHosts)) .) }}
{{- end }}
{{- toJson $origins }}
{{- else }}
{{- "[]" }}
{{- end }}
{{- end }}

{{- define "lyrics-canvas.backend.url" -}}
{{- printf "http://%s:%d" (include "lyrics-canvas.backend.fullname" .) (int .Values.backend.service.port) }}
{{- end }}

{{- define "lyrics-canvas.frontend.url" -}}
{{- printf "http://%s:%d" (include "lyrics-canvas.frontend.fullname" .) (int .Values.frontend.service.port) }}
{{- end }}
