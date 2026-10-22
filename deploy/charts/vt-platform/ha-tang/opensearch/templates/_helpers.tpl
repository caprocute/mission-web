{{- define "opensearch.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/component: search
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
