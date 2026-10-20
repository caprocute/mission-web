{{- define "tenant-registry.name" -}}tenant-registry{{- end -}}
{{- define "tenant-registry.fullname" -}}{{ .Release.Name | trunc 40 | trimSuffix "-" }}-tenant-registry{{- end -}}
{{- define "tenant-registry.labels" -}}
app.kubernetes.io/name: {{ include "tenant-registry.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: mission
app.kubernetes.io/component: svc-03
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "tenant-registry.selector" -}}
app.kubernetes.io/name: {{ include "tenant-registry.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "tenant-registry.serviceAccount" -}}
{{- if .Values.serviceAccount.create -}}{{ default (include "tenant-registry.fullname" .) .Values.serviceAccount.name }}{{- else -}}{{ default "default" .Values.serviceAccount.name }}{{- end -}}
{{- end -}}
{{- define "tenant-registry.image" -}}{{ .Values.image.repository }}:{{ default .Chart.AppVersion .Values.image.tag }}{{- end -}}
