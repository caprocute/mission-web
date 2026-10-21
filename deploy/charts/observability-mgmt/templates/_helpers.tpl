{{- define "obs.image" -}}
{{- $img := index . 0 -}}{{- $what := index . 1 -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString $img.digest)) -}}
{{- fail (printf "observability-mgmt: ảnh %s phải tham chiếu theo digest sha256 (SEC-55), khai ở %s.image.digest" $what $what) -}}
{{- end -}}
{{- printf "%s@%s" $img.repository $img.digest -}}
{{- end -}}

{{- define "obs.secCtx" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities: { drop: ["ALL"] }
{{- end -}}

{{- define "obs.podSec" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile: { type: RuntimeDefault }
{{- end -}}
