{{- define "kafka.sizing" -}}{{ .Values.global.sizing | default "L" }}{{- end -}}
{{- define "kafka.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "kafka.anh" -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString .digest)) }}{{ fail "kafka: ảnh phải ghim digest sha256 (SEC-55)" }}{{ end -}}
{{ .repository }}:{{ .tag }}@{{ .digest }}
{{- end -}}
{{/* Thời gian giữ dạng 7d, 14d, 30d hoặc 72h sang mili giây. */}}
{{- define "kafka.ms" -}}
{{- $m := regexFind "^[0-9]+[dh]$" . -}}
{{- if not $m }}{{ fail (printf "kafka: thời gian giữ không hợp lệ %q (dùng <số>d hoặc <số>h)" .) }}{{ end -}}
{{- $n := int (trimSuffix "d" (trimSuffix "h" .)) -}}
{{- if hasSuffix "d" . }}{{ mul $n 86400000 }}{{ else }}{{ mul $n 3600000 }}{{ end -}}
{{- end -}}
{{/* Tên đối tượng Kubernetes hợp lệ từ tên topic hoặc CSDL (chữ thường, '_' thành '-'). */}}
{{- define "kafka.tenK8s" -}}{{ . | lower | replace "_" "-" | trunc 253 | trimSuffix "-" }}{{- end -}}
{{- define "kafka.danhMuc" -}}{{ .Files.Get "files/danh-muc.yaml" }}{{- end -}}
{{/* Rải pod của một node pool: theo vùng khi có nhiều vùng, luôn theo node (compact một vùng). */}}
{{- define "kafka.raiNode" -}}
template:
  pod:
    topologySpreadConstraints:
      {{- if gt (len (uniq .v.global.topology.zones)) 1 }}
      - maxSkew: 1
        topologyKey: topology.kubernetes.io/zone
        whenUnsatisfiable: DoNotSchedule
        labelSelector: { matchLabels: { strimzi.io/pool-name: {{ .pool }} } }
      {{- end }}
      - maxSkew: 1
        topologyKey: kubernetes.io/hostname
        whenUnsatisfiable: DoNotSchedule
        labelSelector: { matchLabels: { strimzi.io/pool-name: {{ .pool }} } }
    securityContext:
      {{- toYaml .v.podSecurityContext | nindent 6 }}
  kafkaContainer:
    securityContext:
      {{- toYaml .v.containerSecurityContext | nindent 6 }}
{{- end -}}
{{/* Cụm PostgreSQL thực sau khi gộp (data-pg, TL-05): compact gộp pg-flowable, pg-platform, pg-iam vào pg-core và
pg-secure vào pg-ledger; connect.consolidated "true"/"false" ghi đè theo cell. Đầu vào: dict root, cum. */}}
{{- define "kafka.cumThuc" -}}
{{- $c := .root.Values.connect -}}
{{- $gop := or (eq (toString $c.consolidated) "true") (and (eq (toString ($c.consolidated | default "")) "") (eq (include "kafka.sizing" .root) "compact")) -}}
{{- $vao := dict "pg-flowable" "pg-core" "pg-platform" "pg-core" "pg-iam" "pg-core" "pg-secure" "pg-ledger" -}}
{{- if and $gop (hasKey $vao .cum) }}{{ index $vao .cum }}{{ else }}{{ .cum }}{{ end -}}
{{- end -}}
