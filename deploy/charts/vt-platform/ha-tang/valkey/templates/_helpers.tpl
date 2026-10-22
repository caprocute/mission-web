{{- define "valkey.sizing" -}}{{ .Values.global.sizing | default "L" }}{{- end -}}
{{- define "valkey.size" -}}
{{- $k := index .Values.hoSo (include "valkey.sizing" .) -}}
{{- if not $k -}}{{- fail (printf "valkey: không có hồ sơ định cỡ %s" (include "valkey.sizing" .)) -}}{{- end -}}
{{- toYaml $k -}}
{{- end -}}
{{/* Ba nhóm a, b, c ánh xạ vào vùng sự cố; ít hơn ba vùng thì quay vòng (compact một vùng). */}}
{{- define "valkey.nhom" -}}
{{- $z := .Values.global.topology.zones -}}
{{- if not $z -}}{{- fail "valkey: global.topology.zones rỗng" -}}{{- end -}}
a: {{ index $z 0 }}
b: {{ index $z (mod 1 (len $z)) }}
c: {{ index $z (mod 2 (len $z)) }}
{{- end -}}
{{- define "valkey.labels" -}}
app.kubernetes.io/name: valkey
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/component: cache
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "valkey.anh" -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString .digest)) }}{{ fail "valkey: ảnh phải ghim digest sha256 (SEC-55)" }}{{ end -}}
{{ .repository }}:{{ .tag }}@{{ .digest }}
{{- end -}}
