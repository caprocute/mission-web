{{- define "bk.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/name: backup-cell
app.kubernetes.io/managed-by: {{ .Release.Service }}
vt.io/cell: {{ include "bk.ma" . | quote }}
{{- end -}}
{{- define "bk.ma" -}}{{ required "backup-cell: cần cell (mã cell)" .Values.cell | lower }}{{- end -}}
{{- define "bk.anh" -}}
{{- $i := .Values.image -}}
{{- if regexMatch "^sha256:[a-f0-9]{64}$" (toString $i.digest) -}}{{ $i.repository }}:{{ $i.tag }}@{{ $i.digest }}
{{- else if .Values.choPhepKhongDigest -}}{{ $i.repository }}:{{ $i.tag }}
{{- else -}}{{ fail "backup-cell: ảnh phải ghim digest sha256 (SEC-55)" }}{{- end -}}
{{- end -}}
{{- define "bk.bucketPg" -}}{{ .Values.pg.bucket | default (printf "bk-cell-%s-pg" (include "bk.ma" .)) }}{{- end -}}
{{- define "bk.bucketK8s" -}}{{ .bucket | default (printf "bk-cell-%s-k8s" (include "bk.ma" .root)) }}{{- end -}}
{{- define "bk.khoa" -}}{{ .path | default (printf "kv/cell-%s/backup/%s" (include "bk.ma" .root) .loai) }}{{- end -}}
{{/* Biến môi trường chung của job gọi kho sao lưu. */}}
{{- define "bk.envS3" -}}
- { name: HOME, value: /tmp }
- { name: S3_ENDPOINT, value: {{ .Values.khoSaoLuu.endpoint | quote }} }
- { name: S3_REGION, value: {{ .Values.khoSaoLuu.region | quote }} }
- { name: PUSHGATEWAY, value: {{ .Values.pushgateway | quote }} }
- { name: CELL, value: {{ include "bk.ma" . | quote }} }
{{- with .Values.khoSaoLuu.caBundle }}
- { name: AWS_CA_BUNDLE, value: /ca/{{ .key }} }
{{- end }}
{{- end -}}
{{- define "bk.secCtx" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities: { drop: [ALL] }
{{- end -}}
{{- define "bk.podSecCtx" -}}
runAsNonRoot: true
runAsUser: 65532
runAsGroup: 65532
fsGroup: 65532
seccompProfile: { type: RuntimeDefault }
{{- end -}}
{{/* ExternalSecret khoá S3 (thuộc tính ACCESS_KEY_ID, ACCESS_SECRET_KEY như data-pg): dict root, ten, ns, khoa, cloud. */}}
{{- define "bk.externalSecret" -}}
{{- $v := .root.Values -}}
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: {{ .ten }}
  namespace: {{ .ns }}
  labels:
    {{- include "bk.labels" .root | nindent 4 }}
spec:
  refreshInterval: {{ $v.externalSecrets.refreshInterval }}
  secretStoreRef:
    kind: {{ $v.externalSecrets.secretStore.kind }}
    name: {{ $v.externalSecrets.secretStore.name }}
  target:
    name: {{ .ten }}
    creationPolicy: Owner
    {{- if .cloud }}
    # Tệp thông tin xác thực kiểu AWS mà velero-plugin-for-aws đọc.
    template:
      data:
        cloud: |
          [default]
          aws_access_key_id={{ "{{ .ACCESS_KEY_ID }}" }}
          aws_secret_access_key={{ "{{ .ACCESS_SECRET_KEY }}" }}
    {{- end }}
  data:
    {{- range $k := list "ACCESS_KEY_ID" "ACCESS_SECRET_KEY" }}
    - secretKey: {{ $k }}
      remoteRef: { key: {{ $.khoa }}, property: {{ $k }} }
    {{- end }}
{{- end -}}
