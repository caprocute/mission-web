{{/*
Giới hạn tần suất ba tầng, lớp ưu tiên và cắt tải (DV-01-02; TL-04 "Cắt tải và giới hạn tốc độ ở APISIX",
"Hành vi khi bão hoà"; TL-01 mục 12.5, 14.5).
*/}}

{{/* Lớp ưu tiên của tuyến ("1", "2", "3"), trường priorityClass; trường priority là độ ưu tiên khớp tuyến của APISIX. Tham số: dict root, route. */}}
{{- define "api-gateway.routeClass" -}}
{{- $c := toString (default .root.Values.priorityClasses.defaultClass .route.priorityClass) -}}
{{- if not (has $c (list "1" "2" "3")) }}{{ fail (printf "api-gateway: tuyến %s có priorityClass %s, chỉ nhận 1, 2 hoặc 3" .route.name $c) }}{{ end -}}
{{- $c -}}
{{- end -}}

{{/* Tên ApisixPluginConfig của một lớp: lớp 2 giữ tên protected-api, lớp khác thêm hậu tố -p<lớp>. */}}
{{- define "api-gateway.classConfigName" -}}
{{- if eq (toString .) "2" }}protected-api{{ else }}protected-api-p{{ . }}{{ end -}}
{{- end -}}

{{/* plugin_config của tuyến: protected-api đổi theo lớp ưu tiên, tên khác giữ nguyên. Tham số: dict root, route. */}}
{{- define "api-gateway.routePluginConfig" -}}
{{- if eq (toString .route.pluginConfig) "protected-api" -}}
{{- include "api-gateway.classConfigName" (include "api-gateway.routeClass" .) -}}
{{- else -}}
{{- .route.pluginConfig -}}
{{- end -}}
{{- end -}}

{{/* Trường kết nối Valkey của limit-count (cả tầng 1, tầng 3 và luật ghi đè theo tenant). */}}
{{- define "api-gateway.redis" -}}
{{- $r := .Values.rateLimit -}}
policy: {{ $r.policy }}
{{- if eq $r.policy "redis-cluster" }}
redis_cluster_nodes:
  {{- toYaml $r.redisClusterNodes | nindent 2 }}
redis_cluster_name: {{ $r.redisClusterName }}
redis_cluster_ssl: {{ $r.redisSsl }}
redis_cluster_ssl_verify: {{ $r.redisSsl }}
{{- else if eq $r.policy "redis" }}
redis_host: {{ $r.redisHost }}
redis_port: {{ $r.redisPort | int }}
{{- end }}
{{- if ne $r.policy "local" }}
redis_timeout: {{ $r.redisTimeout | int }}
{{- end }}
{{- end -}}

{{/* Thân RFC 9457 của phản hồi cắt tải mức 3. */}}
{{- define "api-gateway.degradeProblem" -}}
{{- $d := .Values.degrade -}}
{{- dict "type" $d.problemType "title" $d.problemTitle "status" 503 "detail" $d.problemDetail | toJson -}}
{{- end -}}

{{/*
Plugin giới hạn và cắt tải của một lớp ở một mức suy giảm. Tham số: dict root, class ("1".."3"), level (0..3).
Kết quả là map tên plugin → cấu hình, ghép với plugin xác thực trong api-gateway.pluginConfig.
*/}}
{{- define "api-gateway.rateLimitPlugins" -}}
{{- $v := .root.Values -}}
{{- $r := $v.rateLimit -}}
{{- $d := $v.degrade -}}
{{- $level := int .level -}}
{{- $class := toString .class -}}
{{- $factor := 1.0 -}}
{{- if ge $level 2 }}{{ $factor = float64 $d.tenantFactor }}{{ end -}}
{{- $q := $r.tenantQuota -}}
limit-count:
  count: {{ $r.user.count | int }}
  time_window: {{ $r.user.timeWindow | int }}
  {{- with $r.user.group }}
  group: {{ . }}
  {{- end }}
  key_type: var_combination
  key: "$vt_tenant $vt_sub"
  {{- include "api-gateway.redis" .root | nindent 2 }}
  rejected_code: 429
  show_limit_quota_header: true
  allow_degradation: true
limit-req:
  rate: {{ max 1 (floor (mulf $r.tenant.rate $factor)) | int }}
  burst: {{ floor (mulf $r.tenant.burst $factor) | int }}
  key_type: var
  key: vt_tenant
  rejected_code: 429
  nodelay: true
  allow_degradation: true
{{- with $q.overrides }}
# Ghi đè theo tenant sinh từ registry.tenant_quota (API_RPS), dùng chung bộ đếm Valkey giữa các instance.
workflow:
  rules:
    {{- range . }}
    {{- $rps := 0 }}
    {{- if .rps }}{{ $rps = int .rps }}{{ else }}{{ $rps = max (int $q.minRps) (div (mul (int .accounts) (int $q.rpsPerUnit)) (int $q.accountsPerUnit)) }}{{ end }}
    - case:
        - ["vt_tenant", "==", {{ .tenant | quote }}]
      actions:
        - - limit-count
          - count: {{ max 1 (floor (mulf (mul $rps (int $q.window)) $factor)) | int }}
            time_window: {{ $q.window | int }}
            key_type: var
            key: vt_tenant
            {{- include "api-gateway.redis" $.root | nindent 12 }}
            rejected_code: 429
            show_limit_quota_header: true
            allow_degradation: true
    {{- end }}
{{- end }}
limit-conn:
  {{- $pc := index $v.priorityClasses.classes $class }}
  conn: {{ $pc.conn | int }}
  burst: {{ $pc.burst | int }}
  default_conn_delay: {{ $v.priorityClasses.defaultConnDelay }}
  key_type: var
  key: vt_tenant
  rejected_code: 503
  allow_degradation: true
client-control:
  max_body_size: {{ $r.maxBodySize | int }}
proxy-rewrite:
  # Mức suy giảm hiện hành cho upstream: tìm kiếm rút gọn, bảng điều hành đọc tổng hợp sẵn, xuất chuyển hàng đợi.
  headers:
    set:
      {{ $d.levelHeader }}: {{ $level | quote }}
{{- if and (ge $level 3) (ne $class "1") }}
fault-injection:
  abort:
    http_status: 503
    body: {{ include "api-gateway.degradeProblem" .root | quote }}
    headers:
      Content-Type: application/problem+json
      Retry-After: {{ $d.retryAfter | quote }}
{{- else if ge $level 1 }}
fault-injection:
  # Mức 1 trở lên: tắt trợ lý AI.
  abort:
    http_status: 503
    body: {{ include "api-gateway.degradeProblem" .root | quote }}
    headers:
      Content-Type: application/problem+json
      Retry-After: {{ $d.retryAfter | quote }}
    vars:
      {{- range $d.aiPaths }}
      - [["uri", "~~", {{ . | quote }}]]
      {{- end }}
{{- end }}
response-rewrite:
  # limit-count tự bọc rejected_msg trong {"error_msg": ...}, nên thân RFC 9457 được ghi lại ở đây.
  vars:
    - ["status", "==", 429]
  headers:
    set:
      Retry-After: {{ $r.retryAfter | quote }}
      Content-Type: application/problem+json
  body: {{ dict "type" $r.problemType "title" $r.problemTitle "status" 429 | toJson | quote }}
{{- end -}}

{{/* ApisixPluginConfig của một lớp ở một mức: xác thực (api-gateway.protectedApi) cùng giới hạn và cắt tải. */}}
{{- define "api-gateway.pluginConfig" -}}
{{- $auth := include "api-gateway.protectedApi" .root | fromYaml -}}
{{- $limits := include "api-gateway.rateLimitPlugins" . | fromYaml -}}
{{- merge $auth $limits | toYaml -}}
{{- end -}}

{{/* Plugin riêng của tuyến: tầng 3 (limit-count thấp hơn), kích thước thân riêng. Tham số: dict root, route. */}}
{{- define "api-gateway.routePlugins" -}}
{{- $v := .root.Values -}}
{{- with .route.rateLimit }}
{{- $t := index $v.rateLimit.routeTiers . }}
{{- if not $t }}{{ fail (printf "api-gateway: tuyến %s khai rateLimit %s không có trong rateLimit.routeTiers" $.route.name .) }}{{ end }}
limit-count:
  count: {{ $t.count | int }}
  time_window: {{ $t.timeWindow | int }}
  key_type: var_combination
  key: "$vt_tenant $vt_sub"
  {{- include "api-gateway.redis" $.root | nindent 2 }}
  rejected_code: 429
  show_limit_quota_header: true
  allow_degradation: true
{{- end }}
{{- with .route.maxBodySize }}
client-control:
  max_body_size: {{ . | int }}
{{- end }}
{{- end -}}

{{/* Plugin vt-problem ở quy tắc toàn cục: lỗi do chính cổng sinh (limit-conn 503, client-control 413) theo RFC 9457. */}}
{{- define "api-gateway.problemPlugin" -}}
{{- $d := .Values.degrade -}}
vt-problem:
  problems:
    "503": { type: {{ $d.problemType | quote }}, title: {{ $d.problemTitle | quote }}, detail: {{ $d.problemDetail | quote }}, retry_after: {{ $d.retryAfter | int }} }
    "413": { type: "https://danhgia.vn/problems/payload-too-large", title: "Dữ liệu gửi lên vượt kích thước cho phép" }
{{- end -}}
