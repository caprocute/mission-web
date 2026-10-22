{{- define "org-service.name" -}}org-service{{- end -}}

{{- define "org-service.fullname" -}}
{{- default .Release.Name .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "org-service.labels" -}}
app.kubernetes.io/name: {{ include "org-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mission
mission.vn/svc: SVC-04
{{- end -}}

{{- define "org-service.selector" -}}
app.kubernetes.io/name: {{ include "org-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "org-service.image" -}}
{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}
{{- end -}}

{{- define "org-service.serviceAccount" -}}
{{- if .Values.serviceAccount.create -}}
{{ default (include "org-service.fullname" .) .Values.serviceAccount.name }}
{{- else -}}
{{ default "default" .Values.serviceAccount.name }}
{{- end -}}
{{- end -}}

{{- define "org-service.securityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
capabilities:
  drop: ["ALL"]
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{/* Namespace đích (INF-02): khoá namespace trong values, mặc định là namespace của release. */}}
{{- define "org-service.namespace" -}}
{{- default .Release.Namespace .Values.namespace -}}
{{- end -}}
