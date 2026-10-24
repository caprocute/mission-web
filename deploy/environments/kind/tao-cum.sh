#!/usr/bin/env bash
# Dựng cụm kind ba vùng và cài Cilium (ảnh lấy từ Docker Hub). Dùng: deploy/environments/kind/tao-cum.sh
# KIND_CONFIG=cluster-an-ninh.yaml bật nhật ký kiểm toán API theo chính sách của cụm thật (INF-19).
# KIND_CONFIG=cluster-gon.yaml KHONG_CILIUM=1: cụm gọn hai node dùng kindnet cho máy ít đĩa (INF-17, INF-18, INF-19).
set -euo pipefail
cd "$(dirname "$0")"
CILIUM_VERSION=${CILIUM_VERSION:-1.18.1}
KIND_IMAGE=${KIND_IMAGE:-kindest/node:v1.33.1}
if ! kind get clusters 2>/dev/null | grep -qx vt-cell-kind; then
  kind create cluster --config "${KIND_CONFIG:-cluster.yaml}" --image "$KIND_IMAGE" --wait 0s
fi
kubectl config use-context kind-vt-cell-kind >/dev/null
if [ "${KHONG_CILIUM:-0}" != 1 ]; then
  ./nap-anh.sh "docker.io/cilium/cilium:v${CILIUM_VERSION}" "docker.io/cilium/operator-generic:v${CILIUM_VERSION}"
  if ! kubectl -n kube-system get ds cilium >/dev/null 2>&1; then
    cilium install --version "$CILIUM_VERSION" -f cilium-values.yaml
  fi
  cilium status --wait --wait-duration 6m
fi
kubectl wait --for=condition=Ready nodes --all --timeout=180s
kubectl get nodes -L topology.kubernetes.io/zone,vt.io/pool
