# INF-17, INF-18 · Ảnh công cụ của các job sao lưu (restore-check, giu-dai-han, chup-etcd, minh chứng, ClickHouse,
# OpenSearch, Git, Harbor, tải snapshot Raft OpenBao): bash, coreutils (date -d, shred), jq, curl, psql 17, aws-cli,
# age, rclone, git, gzip, kubectl, etcdctl và etcdutl. Dựng: docker build -t <harbor>/platform/backup-tools:<tag>
# deploy/charts/backup-cell/anh, đẩy qua pipeline ký ảnh (SEC-55) rồi ghim digest vào values.
# Ảnh nền ghim digest ngay trên dòng FROM (không qua ARG) để cổng Dockerfile của thư viện pipeline đọc được (SEC-55).
FROM registry.k8s.io/kubectl:v1.33.5@sha256:126d8f5898d19973cf0e2f7b89f06bd5338eaa11cba9b8a8a95985058af6012f AS kubectl
FROM registry.k8s.io/etcd:3.6.4-0@sha256:e36c081683425b5b3bc1425bc508b37e7107bb65dfa9367bf5a80125d431fa19 AS etcd

FROM alpine:3.22@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f
RUN apk add --no-cache bash coreutils jq curl postgresql17-client aws-cli age rclone git gzip tzdata ca-certificates
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl
COPY --from=etcd /usr/local/bin/etcdctl /usr/local/bin/etcdutl /usr/local/bin/
USER 65532:65532
ENTRYPOINT ["bash"]
