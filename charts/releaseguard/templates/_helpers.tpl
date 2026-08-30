{{- define "releaseguard.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "releaseguard.fullname" -}}
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

{{- define "releaseguard.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "releaseguard.labels" -}}
helm.sh/chart: {{ include "releaseguard.chart" . }}
{{ include "releaseguard.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "releaseguard.selectorLabels" -}}
app.kubernetes.io/name: {{ include "releaseguard.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: operator
{{- end }}

{{- define "releaseguard.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "releaseguard.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- required "serviceAccount.name is required when serviceAccount.create is false" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "releaseguard.image" -}}
{{- if .Values.image.digest }}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag }}
{{- end }}
{{- end }}

{{- define "releaseguard.metricGateFullname" -}}
{{- printf "%s-metric-gate" (include "releaseguard.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "releaseguard.metricGateSelectorLabels" -}}
app.kubernetes.io/name: {{ include "releaseguard.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: metric-gate
{{- end }}

{{- define "releaseguard.metricGateLabels" -}}
helm.sh/chart: {{ include "releaseguard.chart" . }}
{{ include "releaseguard.metricGateSelectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "releaseguard.metricGateImage" -}}
{{- if .Values.metricGate.image.digest }}
{{- printf "%s@%s" .Values.metricGate.image.repository .Values.metricGate.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.metricGate.image.repository .Values.metricGate.image.tag }}
{{- end }}
{{- end }}
