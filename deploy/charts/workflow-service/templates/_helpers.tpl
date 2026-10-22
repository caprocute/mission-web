{{- define "workflow-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "workflow-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "workflow-service.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "workflow-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
mission.vn/svc: SVC-06
{{- end -}}

{{- define "workflow-service.apiSelectorLabels" -}}
app.kubernetes.io/name: {{ include "workflow-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
{{- end -}}

{{- define "workflow-service.executorSelectorLabels" -}}
app.kubernetes.io/name: {{ include "workflow-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: executor
{{- end -}}

{{- define "workflow-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "workflow-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "workflow-service.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{- define "workflow-service.securityContext" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
readOnlyRootFilesystem: true
allowPrivilegeEscalation: false
capabilities:
  drop:
    - ALL
{{- end -}}

{{/* Namespace đích (INF-02): khoá namespace trong values, mặc định là namespace của release. */}}
{{- define "workflow-service.namespace" -}}
{{- default .Release.Namespace .Values.namespace -}}
{{- end -}}
