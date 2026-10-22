{{- define "clickhouse.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/component: analytics-store
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "clickhouse.anh" -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString .digest)) }}{{ fail "clickhouse: ảnh phải ghim digest sha256 (SEC-55)" }}{{ end -}}
{{ .repository }}:{{ .tag }}@{{ .digest }}
{{- end -}}
{{/* securityContext chung của pod và container (Pod Security restricted). */}}
{{- define "clickhouse.podSec" -}}
runAsNonRoot: true
runAsUser: 101
runAsGroup: 101
fsGroup: 101
seccompProfile: { type: RuntimeDefault }
{{- end -}}
{{- define "clickhouse.ctrSec" -}}
allowPrivilegeEscalation: false
capabilities: { drop: [ALL] }
{{- end -}}
