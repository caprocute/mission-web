{{- define "openbao.labels" -}}
app.kubernetes.io/name: openbao
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/component: kho-bi-mat
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "openbao.selector" -}}
app.kubernetes.io/name: openbao
app.kubernetes.io/component: kho-bi-mat
{{- end -}}
{{- define "openbao.anh" -}}
{{- $i := .Values.image -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString $i.digest)) }}{{ fail "openbao: ảnh phải ghim digest sha256 (SEC-55)" }}{{ end -}}
{{ $i.repository }}:{{ $i.tag }}@{{ $i.digest }}
{{- end -}}
{{- define "openbao.cell" -}}
{{- required "openbao: cần global.cell (mã cell, ví dụ hcm)" .Values.global.cell -}}
{{- end -}}
{{- define "openbao.authMount" -}}
{{- .Values.cell.authMount | default (printf "k8s-cell-%s" (include "openbao.cell" .)) -}}
{{- end -}}
{{- define "openbao.pkiMount" -}}
{{- .Values.cell.pkiMount | default (printf "pki_int_cell_%s" (include "openbao.cell" . | replace "-" "_")) -}}
{{- end -}}
{{- define "openbao.addr" -}}
https://openbao.{{ .Values.namespace }}.svc:8200
{{- end -}}
