# INF-17, INF-18 · Ảnh công cụ của các job sao lưu (restore-check, giu-dai-han, chup-etcd, minh chứng, ClickHouse,
# OpenSearch, Git, Harbor, tải snapshot Raft OpenBao): bash, coreutils (date -d, shred), jq, curl, psql 17, aws-cli,
# age, rclone, git, gzip, kubectl, etcdctl và etcdutl. Dựng: docker build -t <harbor>/platform/backup-tools:<tag>
# deploy/charts/backup-cell/anh, đẩy qua pipeline ký ảnh (SEC-55) rồi ghim digest vào values.
ARG KUBECTL=registry.k8s.io/kubectl:v1.33.5
ARG ETCD=registry.k8s.io/etcd:3.6.4-0
FROM ${KUBECTL} AS kubectl
FROM ${ETCD} AS etcd

FROM alpine:3.22
RUN apk add --no-cache bash coreutils jq curl postgresql17-client aws-cli age rclone git gzip tzdata ca-certificates
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl
COPY --from=etcd /usr/local/bin/etcdctl /usr/local/bin/etcdutl /usr/local/bin/
USER 65532:65532
ENTRYPOINT ["bash"]
