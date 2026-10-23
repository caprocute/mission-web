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
    {{- with .v.placement.nodeSelector }}
    affinity:
      nodeAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          nodeSelectorTerms:
            - matchExpressions:
                {{- range $k, $gt := . }}
                - { key: {{ $k }}, operator: In, values: [{{ $gt | quote }}] }
                {{- end }}
    {{- end }}
    {{- with .v.placement.tolerations }}
    tolerations:
      {{- toYaml . | nindent 6 }}
    {{- end }}
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
{{/* MirrorMaker 2 đã giải vai trò (INF-18, INF-28): vaiTro ttdl suy ra nguon/dich, tên, bí danh, tài khoản, CA và
bootstrap từ global.site, global.siteRole; vai trò khác giữ nguyên values. Trả YAML, dùng với fromYaml. */}}
{{- define "kafka.mm2" -}}
{{- $v := .Values }}
{{- $m := deepCopy $v.mirrorMaker2 }}
{{- if not (has $m.vaiTro (list "" "nguon" "dich" "ttdl")) }}{{ fail (printf "kafka: mirrorMaker2.vaiTro %q không hợp lệ (nguon, dich, ttdl)" $m.vaiTro) }}{{ end }}
{{- $_ := set $m "nguonPort" 9093 }}
{{- $_ := set $m "lienTtdlMo" $m.lienTtdl.enabled }}
{{- if eq $m.vaiTro "ttdl" }}
{{- $self := toString $v.global.site }}
{{- $role := toString $v.global.siteRole }}
{{- if not (has $self $m.ttdl.sites) }}{{ fail (printf "kafka: mirrorMaker2.vaiTro ttdl cần global.site thuộc %v, có %q" $m.ttdl.sites $self) }}{{ end }}
{{- if not (has $role (list "primary" "standby")) }}{{ fail (printf "kafka: mirrorMaker2.vaiTro ttdl cần global.siteRole primary hoặc standby, có %q" $role) }}{{ end }}
{{- $peer := first (without $m.ttdl.sites $self) }}
{{- $cell := required "kafka: mirrorMaker2 cần global.cell" $v.global.cell | lower }}
{{- $_ := set $m "vaiTro" (ternary "nguon" "dich" (eq $role "primary")) }}
{{- $_ := set $m "ten" (printf "mm2-%s-to-%s" $peer $self) }}
{{- $_ := set $m "nguoiDungNguon" (printf "mm2-%s-user" $self) }}
{{- $_ := set $m "nguon" (merge (dict "alias" $peer "caSecret" (printf "kafka-%s-cluster-ca-cert" $peer) "userSecret" (printf "mm2-%s-user" $peer) "bootstrapServers" (printf $m.ttdl.bootstrapFormat $cell $peer)) (dict "openbaoPath" $m.nguon.openbaoPath)) }}
{{- $_ := set $m "dich" (dict "alias" $self "user" (printf "mm2-%s-user" $self)) }}
{{- $_ := set $m "nguonPort" (int $m.lienTtdl.port) }}
{{- $_ := set $m "lienTtdlMo" true }}
{{- if not $m.egressTo }}
{{- $ds := list }}
{{- range $m.lienTtdl.peerCIDRs }}{{ $ds = append $ds (dict "ipBlock" (dict "cidr" .)) }}{{ end }}
{{- $_ := set $m "egressTo" $ds }}
{{- end }}
{{- end }}
{{- toYaml $m }}
{{- end -}}
