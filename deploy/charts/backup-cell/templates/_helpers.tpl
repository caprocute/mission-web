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
{{- define "bk.bucket" -}}{{ .bucket | default (printf "bk-cell-%s-%s" (include "bk.ma" .root) .loai) }}{{- end -}}
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
{{/* ExternalSecret một thuộc tính: dict root, ten, ns, khoa, thuocTinh, secretKey. */}}
{{- define "bk.externalSecretMot" -}}
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
  data:
    - secretKey: {{ .secretKey | default "password" }}
      remoteRef: { key: {{ .khoa }}, property: {{ .thuocTinh | default "password" }} }
{{- end -}}
{{/* CronJob của namespace sao-luu (INF-18). Đầu vào dict: root, ten, schedule, deadline, script, env (list), mounts
(list), volumes (list), sa (tuỳ chọn). */}}
{{- define "bk.cronJobSaoLuu" -}}
{{- $v := .root.Values -}}
apiVersion: batch/v1
kind: CronJob
metadata:
  name: {{ .ten }}
  namespace: {{ $v.saoLuu.namespace }}
  labels:
    {{- include "bk.labels" .root | nindent 4 }}
spec:
  schedule: {{ .schedule | quote }}
  timeZone: {{ $v.timeZone }}
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 5
  jobTemplate:
    spec:
      backoffLimit: 1
      activeDeadlineSeconds: {{ .deadline }}
      template:
        metadata:
          labels:
            vt.io/thanh-phan: sao-luu-job
            vt.io/job: {{ .ten }}
            {{- if .sa }}
            vt.io/goi-api: "true"
            {{- end }}
            {{- include "bk.labels" .root | nindent 12 }}
        spec:
          restartPolicy: Never
          {{- if .sa }}
          serviceAccountName: {{ .sa }}
          {{- else }}
          automountServiceAccountToken: false
          {{- end }}
          securityContext:
            {{- include "bk.podSecCtx" .root | nindent 12 }}
          containers:
            - name: {{ .ten }}
              image: {{ include "bk.anh" .root }}
              imagePullPolicy: {{ $v.image.pullPolicy }}
              command: [bash, /cfg/{{ .script }}]
              env:
                {{- include "bk.envS3" .root | nindent 16 }}
                {{- with .env }}
                {{- toYaml . | nindent 16 }}
                {{- end }}
              resources:
                {{- toYaml $v.saoLuu.resourcesJob | nindent 16 }}
              securityContext:
                {{- include "bk.secCtx" .root | nindent 16 }}
              volumeMounts:
                - { name: cfg, mountPath: /cfg }
                - { name: tmp, mountPath: /tmp }
                {{- with $v.khoSaoLuu.caBundle }}
                - { name: ca, mountPath: /ca, readOnly: true }
                {{- end }}
                {{- with .mounts }}
                {{- toYaml . | nindent 16 }}
                {{- end }}
          volumes:
            - { name: cfg, configMap: { name: {{ .cfg }} } }
            - { name: tmp, emptyDir: { sizeLimit: 256Mi } }
            {{- with $v.khoSaoLuu.caBundle }}
            - { name: ca, secret: { secretName: {{ .secret }} } }
            {{- end }}
            {{- with .volumes }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
{{- end -}}
{{/* NetworkPolicy ra của một job namespace sao-luu: dict root, ten, dich (list {to, ports}). Luôn có kho sao lưu và
Pushgateway. */}}
{{- define "bk.mangJob" -}}
{{- $v := .root.Values -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ .ten }}
  namespace: {{ $v.saoLuu.namespace }}
  labels:
    {{- include "bk.labels" .root | nindent 4 }}
spec:
  podSelector:
    matchLabels: { vt.io/job: {{ .ten }} }
  policyTypes: [Egress]
  egress:
    {{- with $v.khoSaoLuu.egressTo }}
    - to:
        {{- toYaml . | nindent 8 }}
      ports: [{ port: {{ $v.khoSaoLuu.egressPort }}, protocol: TCP }]
    {{- end }}
    {{- if $v.pushgateway }}
    - to:
        {{- toYaml $v.pushgatewayEgressTo | nindent 8 }}
      ports: [{ port: 9091, protocol: TCP }]
    {{- end }}
    {{- range .dich }}
    {{- if .to }}
    - to:
        {{- toYaml .to | nindent 8 }}
      ports:
        {{- range .ports }}
        - { port: {{ . }}, protocol: TCP }
        {{- end }}
    {{- end }}
    {{- end }}
{{- end -}}
{{/* INF-28: thư mục serverName của cụm trong kho (tên cụm, thêm -<ttdl> ở TTDL không khởi tạo replica cluster). */}}
{{- define "bk.hauToServer" -}}
{{- $r := .Values.pg.replica | default dict -}}
{{- $site := toString (dig "site" "" (.Values.global | default dict)) -}}
{{- if and $r.enabled $site (ne $site (toString $r.initdbSite)) }}-{{ $site }}{{ end -}}
{{- end -}}
