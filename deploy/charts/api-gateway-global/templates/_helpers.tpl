{{/* Ảnh theo digest (SEC-55); ref dùng nguyên văn, chỉ cho ảnh dựng cục bộ. */}}
{{- define "gw-global.image" -}}
{{- $img := .Values.apisix.image -}}
{{- if $img.ref -}}{{- $img.ref -}}
{{- else if regexMatch "^sha256:[a-f0-9]{64}$" (toString $img.digest) -}}{{- printf "%s@%s" $img.repository $img.digest -}}
{{- else -}}{{- fail "api-gateway-global: ảnh apisix phải tham chiếu theo digest sha256 (SEC-55)" -}}
{{- end -}}
{{- end -}}

{{- define "gw-global.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
vt.io/plane: global
{{- end -}}

{{/* Cấu hình plugin vt-cell-router (DV-01-04). */}}
{{- define "gw-global.router" -}}
{{- $v := .Values -}}
registry_url: {{ trimSuffix "/" $v.registry.url | quote }}
registry_timeout_ms: {{ $v.registry.timeoutMs | int }}
common_hosts: {{ toJson $v.commonHosts }}
cache_ttl: {{ $v.cache.ttlSeconds | int }}
discovery_ttl: {{ $v.cache.discoveryTtlSeconds | int }}
stale_ttl: {{ $v.cache.staleSeconds | int }}
negative_ttl: {{ $v.cache.negativeSeconds | int }}
{{- with $v.registry.tokenUrl }}
token_url: {{ . | quote }}
client_id: {{ $v.registry.clientId | quote }}
{{- end }}
{{- with $v.routingCookie.domain }}
cookie_domain: {{ . | quote }}
{{- end }}
cells: {{ toJson $v.cells }}
{{- end -}}
