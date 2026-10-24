#!/usr/bin/env bash
# Tải công cụ cho kiểm thử vận hành trên API server thật (INF-25, INF-26) vào $VT_CONG_CU (mặc định /dev/shm/vt-cong-cu,
# bộ nhớ, vì máy kiểm thử có thể thiếu đĩa): kube-apiserver, etcd, kubectl (envtest 1.34.1), Alertmanager và amtool
# 0.28.1, helm 3.19.0, PostgreSQL 17.5 (gói nhị phân zonky), rồi dựng degrade-controller, vtctl, tenant-registry,
# provision-gia-lap từ mã trong repo. Không cần Docker.
set -euo pipefail
D=${VT_CONG_CU:-/dev/shm/vt-cong-cu}; GOC=$(cd "$(dirname "$0")/../../../.." && pwd)
mkdir -p "$D"; cd "$D"
[ -x kube-apiserver ] || curl -sSfL https://github.com/kubernetes-sigs/controller-tools/releases/download/envtest-v1.34.1/envtest-v1.34.1-linux-amd64.tar.gz | tar xz --strip-components=2
[ -x alertmanager ] || curl -sSfL https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz \
  | tar xz --strip-components=1 alertmanager-0.28.1.linux-amd64/alertmanager alertmanager-0.28.1.linux-amd64/amtool
[ -x helm ] || curl -sSfL https://get.helm.sh/helm-v3.19.0-linux-amd64.tar.gz | tar xz --strip-components=1 linux-amd64/helm
if [ ! -x pg/bin/postgres ]; then
  mkdir -p pg && curl -sSfL -o pg/pg.jar https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/17.5.0/embedded-postgres-binaries-linux-amd64-17.5.0.jar
  (cd pg && unzip -q -o pg.jar '*.txz' && tar xJf ./*.txz && rm -f pg.jar ./*.txz)
fi
export GOCACHE=${GOCACHE:-$D/gocache}
(cd "$GOC/services-go/degrade-controller" && go build -o "$D/degrade-controller" ./cmd/degrade-controller)
(cd "$GOC/services-go/vtctl" && go build -o "$D/vtctl" ./cmd/vtctl)
(cd "$GOC/services-go/tenant-registry" && go build -o "$D/tenant-registry" ./cmd/tenant-registry && go build -o "$D/provision-gia-lap" ./cmd/provision-gia-lap)
echo "Công cụ ở $D"
