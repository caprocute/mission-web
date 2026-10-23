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
openid-connect:
  discovery: {{ include "api-gateway.discovery" . }}
  bearer_only: true
  use_jwks: true
  client_id: {{ $v.oidc.clientId }}
  client_secret: $env://APISIX_OIDC_CLIENT_SECRET
  set_userinfo_header: false
vt-claims:
  required_cell: {{ $v.cell | quote }}
{{- end -}}

{{/* Metadata của plugin opentelemetry (APISIX 3.13 đọc collector từ plugin_metadata, không từ plugin_attr). */}}
{{- define "api-gateway.otelMetadata" -}}
resource:
  service.name: apisix-{{ .Values.cell }}
collector:
  address: {{ .Values.opentelemetry.collector }}
  request_timeout: 3
{{- end -}}
