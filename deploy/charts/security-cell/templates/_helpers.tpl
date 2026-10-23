{{- /* Ảnh theo digest (SEC-55); dừng render khi digest không phải sha256. */ -}}
{{- define "sec.image" -}}
{{- $i := index .root.Values.images .name -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString $i.digest)) -}}
{{- fail (printf "security-cell: images.%s.digest phải là sha256 (SEC-55)" .name) -}}
{{- end -}}
{{- printf "%s@%s" $i.repository $i.digest -}}
{{- end -}}

{{- define "sec.labels" -}}
app.kubernetes.io/part-of: vt-security
vt.io/cell: {{ .Values.cell | quote }}
{{- end -}}

{{- /* securityContext container chuẩn của vùng restricted (SEC-49, vt-pod-hardening). */ -}}
{{- define "sec.restrictedContainer" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities: { drop: ["ALL"] }
{{- end -}}

{{- define "sec.restrictedPod" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile: { type: RuntimeDefault }
{{- end -}}

{{- /* Mọi namespace mà quy tắc và RBAC coi là vùng ứng dụng hoặc dữ liệu. */ -}}
{{- define "sec.appDataNamespaces" -}}
{{- concat .Values.zones.app .Values.zones.data | toJson -}}
{{- end -}}
