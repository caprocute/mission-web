{{/* Ảnh theo digest (SEC-55). ref dùng nguyên văn, chỉ cho ảnh dựng cục bộ trên kind. */}}
{{- define "api-gateway.image" -}}
{{- $img := index . 0 -}}{{- $what := index . 1 -}}
{{- if $img.ref -}}
{{- $img.ref -}}
{{- else if regexMatch "^sha256:[a-f0-9]{64}$" (toString $img.digest) -}}
{{- printf "%s@%s" $img.repository $img.digest -}}
{{- else -}}
{{- fail (printf "api-gateway: ảnh %s phải tham chiếu theo digest sha256 (SEC-55), khai ở %s.image.digest" $what $what) -}}
{{- end -}}
{{- end -}}

{{- define "api-gateway.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
vt.io/cell: {{ .Values.cell | quote }}
{{- end -}}

{{/* nodeSelector và toleration của pool ingress (TL-04 mục 3.3). */}}
{{- define "api-gateway.scheduling" -}}
{{- if ne .Values.apisix.nodePool "none" }}
nodeSelector:
  vt.io/pool: {{ .Values.apisix.nodePool }}
tolerations:
  - { key: vt.io/pool, operator: Equal, value: {{ .Values.apisix.nodePool }}, effect: NoSchedule }
{{- end }}
{{- end -}}

{{- define "api-gateway.containerSecurity" -}}
allowPrivilegeEscalation: false
runAsNonRoot: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{- define "api-gateway.discovery" -}}
{{- printf "%s/realms/%s/.well-known/openid-configuration" (trimSuffix "/" .Values.oidc.baseUrl) .Values.oidc.realm -}}
{{- end -}}

{{/* Cấu hình Coraza: CRS 4, đặt paranoia và ngưỡng trước khi nạp bộ quy tắc. */}}
{{- define "api-gateway.corazaConf" -}}
{{- $c := .Values.coraza -}}
{{- $d := list
  "Include @demo-conf"
  "Include @crs-setup-conf"
  (printf "SecRuleEngine %s" $c.ruleEngine)
  (printf "SecAction \"id:900000,phase:1,pass,t:none,nolog,setvar:tx.blocking_paranoia_level=%d\"" (int $c.paranoiaLevel))
  (printf "SecAction \"id:900110,phase:1,pass,t:none,nolog,setvar:tx.inbound_anomaly_score_threshold=%d\"" (int $c.anomalyThreshold))
  (printf "SecAction \"id:900200,phase:1,pass,t:none,nolog,setvar:'tx.allowed_methods=%s'\"" $c.allowedMethods) -}}
{{- $d = concat $d (default list $c.exclusions) (list "Include @owasp_crs/*.conf") -}}
{{- dict "directives_map" (dict "default" $d) "default_directives" "default" | toJson -}}
{{- end -}}

{{/* Plugin toàn cục: Coraza, header bảo mật (SEC-24), lỗi RFC 9457 của cổng (vt-problem), OpenTelemetry (traceparent). */}}
{{- define "api-gateway.globalPlugins" -}}
{{- $v := .Values -}}
{{- if $v.coraza.enabled }}
coraza-filter:
  conf: {{ include "api-gateway.corazaConf" . | quote }}
# Đưa thân request vào Coraza (APISIX không tự chuyển thân cho coraza-proxy-wasm, DV-01-02).
vt-waf-body:
  max_bytes: {{ $v.coraza.inspectBodyBytes | int }}
{{- end }}
response-rewrite:
  headers:
    set:
      {{- toYaml $v.securityHeaders | nindent 6 }}
{{ include "api-gateway.problemPlugin" . }}
prometheus: {}
# Mã yêu cầu cho mọi tuyến; giữ X-Request-Id client gửi nếu có, trả lại trong phản hồi.
request-id:
  header_name: X-Request-Id
  include_in_response: true
  algorithm: uuid
{{- if $v.opentelemetry.enabled }}
opentelemetry:
  sampler:
    name: always_on
  additional_attributes: ["http_x_debug_trace"]
{{- end }}
{{- end -}}

{{/* Plugin xác thực của ApisixPluginConfig protected-api* (TL-04 mục 6.8); giới hạn tần suất và cắt tải ghép thêm
ở api-gateway.pluginConfig (_rate-limit.tpl, DV-01-02). */}}
{{- define "api-gateway.protectedApi" -}}
{{- $v := .Values -}}
{{- if eq $v.oidc.verifier "openid-connect" }}
openid-connect:
  discovery: {{ include "api-gateway.discovery" . }}
  bearer_only: true
  use_jwks: true
  client_id: {{ $v.oidc.clientId }}
  client_secret: $env://APISIX_OIDC_CLIENT_SECRET
  set_userinfo_header: false
  # Chỉ nhận token ký bằng thuật toán của realm, không nhận alg khác hay none.
  token_signing_alg_values_expected: {{ $v.oidc.signingAlg }}
  accept_unsupported_alg: false
{{- else if ne $v.oidc.verifier "vt-claims" }}
{{- fail "api-gateway: oidc.verifier chỉ nhận vt-claims hoặc openid-connect" }}
{{- else if not (has $v.oidc.signingAlg (list "ES256" "ES384" "RS256")) }}
{{- fail "api-gateway: oidc.signingAlg của vt-claims chỉ nhận ES256, ES384, RS256" }}
{{- end }}
vt-claims:
  required_cell: {{ include "api-gateway.cellCode" . | quote }}
  tenant_claim: {{ $v.oidc.claims.tenant | quote }}
  cell_claim: {{ $v.oidc.claims.cell | quote }}
  {{- if eq $v.oidc.verifier "vt-claims" }}
  # Kiểm chữ ký JWT qua JWKS của realm (openid-connect của APISIX 3.13 không kiểm được ES256, wiki 14 DV-01-01).
  verify: true
  discovery: {{ include "api-gateway.discovery" . }}
  algorithms: [{{ $v.oidc.signingAlg }}]
  jwks_ttl: {{ $v.oidc.jwksTtl | int }}
  {{- end }}
  {{- if $v.routingCookie.enabled }}
  # Cookie định tuyến ký HMAC của SVC-03 và APISIX lớp ngoài (DV-01-04).
  route_cookie:
    cell_code: {{ include "api-gateway.cellCode" . | quote }}
  {{- end }}
{{- end -}}

{{/* Metadata của plugin opentelemetry (APISIX 3.13 đọc collector từ plugin_metadata, không từ plugin_attr). */}}
{{- define "api-gateway.otelMetadata" -}}
resource:
  service.name: apisix-{{ .Values.cell }}
collector:
  address: {{ .Values.opentelemetry.collector }}
  request_timeout: 3
{{- end -}}


{{/* Mã cell theo registry.cell.code (nguồn chuẩn: SVC-03), ví dụ CELL-HN; mặc định "CELL-" + tên cell viết hoa,
đúng quy ước displayName của deploy/cells/<cell>/cell.yaml. Claim cell của token và cookie định tuyến so với mã này. */}}
{{- define "api-gateway.cellCode" -}}
{{- default (printf "CELL-%s" (upper .Values.cell)) .Values.cellCode -}}
{{- end -}}

{{/* Tên máy của cổng API cell, phải trùng host của registry.cell.api_base_url (SVC-03); APISIX lớp ngoài chuyển tiếp
với Host này. Mặc định api.cell-<cell>.okrkpi.vn như dữ liệu registry. */}}
{{- define "api-gateway.apiHost" -}}
{{- default (printf "api.cell-%s.okrkpi.vn" (lower .Values.cell)) .Values.cellApiHost -}}
{{- end -}}

{{/* Host mặc định của mọi tuyến: tên máy cổng cell (registry) cộng các tên miền tenant ở routeDefaults.hosts. */}}
{{- define "api-gateway.hosts" -}}
{{- uniq (append (default list .Values.routeDefaults.hosts) (include "api-gateway.apiHost" .)) | toJson -}}
{{- end -}}

{{/* Danh sách tuyến đã trộn routeDefaults (DV-01-01), dạng JSON để template dùng fromJsonArray. */}}
{{- define "api-gateway.routes" -}}
{{- $out := list -}}
{{- range concat (default list .Values.routes) (default list .Values.classRoutes) (default list .Values.extraRoutes) -}}
{{- $r := mergeOverwrite (deepCopy $.Values.routeDefaults) (deepCopy .) -}}
{{- if not .hosts }}{{ $_ := set $r "hosts" (include "api-gateway.hosts" $ | fromJsonArray) }}{{ end -}}
{{- if and $r.uriRegex (not (regexMatch "^\\^" $r.uriRegex)) }}{{ fail (printf "api-gateway: uriRegex của tuyến %s phải neo ^...$" $r.name) }}{{ end -}}
{{- $out = append $out $r -}}
{{- end -}}
{{- toJson $out -}}
{{- end -}}

{{/* Thân 404 của tuyến chặn (deny): thao tác chỉ cho client nội bộ không ra cổng ngoài (DV-01-03). */}}
{{- define "api-gateway.denyAbort" -}}
http_status: 404
headers:
  Content-Type: application/problem+json
body: {{ dict "type" "about:blank" "title" "Không tìm thấy" "status" 404 | toJson | quote }}
{{- end -}}


{{/* Giới hạn tần suất theo địa chỉ client cho tuyến công khai (không có khoá tenant). */}}
{{- define "api-gateway.publicLimit" -}}
rate: {{ .Values.rateLimit.public.rate | int }}
burst: {{ .Values.rateLimit.public.burst | int }}
key_type: var
key: remote_addr
rejected_code: 429
nodelay: true
allow_degradation: true
{{- end -}}
