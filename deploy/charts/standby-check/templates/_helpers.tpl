{{- define "sc.labels" -}}
app.kubernetes.io/name: standby-check
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
vt.io/cell: {{ .Values.cell | quote }}
{{- end -}}
{{- /* Danh sách kiểm dạng JSON cho kiem_tra.py. */ -}}
{{- define "sc.kiemTra" -}}
{{- $v := .Values }}
{{- $ds := list }}
{{- range $v.clusters }}
{{- $ds = append $ds (dict "ten" (printf "%s: đang là bản sao nhận WAL" .) "promql" (printf "min(cnpg_pg_replication_in_recovery{cluster=%q})" .) "so" "==" "nguong" 1) }}
{{- $ds = append $ds (dict "ten" (printf "%s: độ trễ WAL (giây)" .) "promql" (printf "max(cnpg_pg_replication_lag{cluster=%q})" .) "so" "<" "nguong" $v.nguong.walGiay) }}
{{- end }}
{{- $ds = append $ds (dict "ten" "MirrorMaker 2: thông điệp mới trong 24 giờ" "promql" "sum(increase(kafka_connect_mirror_source_connector_record_count[24h]))" "so" ">" "nguong" 0) }}
{{- $ds = append $ds (dict "ten" "MirrorMaker 2: độ trễ nhân bản (ms)" "promql" "max(kafka_connect_mirror_source_connector_replication_latency_ms_max)" "so" "<" "nguong" $v.nguong.mm2Ms) }}
{{- $ds = append $ds (dict "ten" "RGW multisite: shard chưa đồng bộ" "promql" (printf "max(ceph_rgw_sync_behind_shards{zonegroup=\"cell-%s-zg\"})" $v.cell) "so" "<" "nguong" 1) }}
{{- range $v.kiemThem }}{{ $ds = append $ds . }}{{ end }}
{{- toJson $ds }}
{{- end -}}
