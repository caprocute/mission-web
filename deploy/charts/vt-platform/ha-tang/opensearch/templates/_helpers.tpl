{{- define "opensearch.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/component: search
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "opensearch.bucketSnapshot" -}}
{{- .Values.snapshot.bucket | default (printf "bk-cell-%s-os" (required "opensearch: snapshot cần global.cell" .Values.global.cell | lower)) -}}
{{- end -}}
