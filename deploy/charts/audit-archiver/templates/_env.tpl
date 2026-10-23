{{/*
Biến môi trường của audit-archiver (services-go/audit-archiver/internal/config), dựng từ khoá archive.* để
Deployment (qua ConfigMap của vt-service) và CronJob kiểm hằng ngày dùng cùng một cấu hình. Bí mật không nằm ở đây:
khoá S3 đọc từ tệp (S3_*_FILE), token OpenBao lấy bằng đăng nhập Kubernetes với token tài khoản dịch vụ ngắn hạn.
*/}}
{{- define "audit-archiver.env" -}}
{{- $a := .Values.archive -}}
{{- $cell := required "global.cell là bắt buộc (mã cell, ví dụ g4)" .Values.global.cell -}}
CELL: {{ printf "cell-%s" (trimPrefix "cell-" $cell) | quote }}
KAFKA_BROKERS: {{ $a.kafkaBootstrap | quote }}
{{- if $a.kafkaTlsSecret }}
KAFKA_TLS_DIR: /etc/vt/kafka
{{- end }}
KAFKA_GROUP: {{ $a.group | quote }}
TOPICS: {{ join "," $a.topics | quote }}
BATCH_WINDOW: {{ $a.batchWindow | quote }}
BATCH_MAX_RECORDS: {{ $a.batchMaxRecords | quote }}
BATCH_MAX_BYTES: {{ $a.batchMaxBytes | quote }}
S3_ENDPOINT: {{ $a.s3Endpoint | quote }}
S3_TLS: {{ $a.s3Tls | quote }}
S3_REGION: {{ $a.s3Region | quote }}
S3_BUCKET: {{ default (printf "audit-cell-%s" (trimPrefix "cell-" $cell)) $a.s3Bucket | quote }}
S3_ACCESS_KEY_FILE: /etc/vt/s3/access-key
S3_SECRET_KEY_FILE: /etc/vt/s3/secret-key
LOCK_MODE: {{ $a.lockMode | quote }}
LOCK_RETENTION_YEARS: {{ $a.retentionYears | quote }}
{{- with $a.lockRetention }}
LOCK_RETENTION: {{ . | quote }}
{{- end }}
BAO_ADDR: {{ $a.openBaoAddr | quote }}
BAO_TRANSIT_MOUNT: {{ $a.transitMount | quote }}
BAO_SIGN_KEY: {{ $a.signKey | quote }}
{{- if $a.openBaoTokenFile }}
{{- /* Chỉ dùng ở môi trường thử không có đăng nhập Kubernetes của OpenBao (kind). */}}
BAO_TOKEN_PATH: {{ $a.openBaoTokenFile | quote }}
{{- else }}
BAO_K8S_ROLE: {{ $a.k8sAuthRole | quote }}
BAO_K8S_MOUNT: {{ $a.k8sAuthMount | quote }}
BAO_K8S_JWT_PATH: /var/run/secrets/openbao/token
{{- end }}
MGMT_ADDR: ":8081"
PUSHGATEWAY_URL: {{ $a.pushgatewayUrl | quote }}
{{- end -}}
