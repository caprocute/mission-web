#!/usr/bin/env bash
# Dựng cụm kind ba vùng và cài Cilium (ảnh lấy từ Docker Hub). Dùng: deploy/environments/kind/tao-cum.sh
set -euo pipefail
cd "$(dirname "$0")"
CILIUM_VERSION=${CILIUM_VERSION:-1.18.1}
KIND_IMAGE=${KIND_IMAGE:-kindest/node:v1.33.1}
if ! kind get clusters 2>/dev/null | grep -qx vt-cell-kind; then
  kind create cluster --config cluster.yaml --image "$KIND_IMAGE" --wait 0s
fi
kubectl config use-context kind-vt-cell-kind >/dev/null
if ! kubectl -n kube-system get ds cilium >/dev/null 2>&1; then
  cilium install --version "$CILIUM_VERSION" \
    --set image.repository=docker.io/cilium/cilium --set image.useDigest=false \
    --set operator.image.repository=docker.io/cilium/operator --set operator.image.useDigest=false \
    --set operator.replicas=1 \
    --set ipam.mode=kubernetes \
    --set envoy.enabled=false
fi
cilium status --wait --wait-duration 6m
kubectl wait --for=condition=Ready nodes --all --timeout=180s
kubectl get nodes -L topology.kubernetes.io/zone,vt.io/pool
