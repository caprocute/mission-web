#!/usr/bin/env bash
# Cài Istio ambient lên cụm kind (INF-09) theo ghim ở deploy/charts/cell-mesh/istio/chart.yaml và các tệp values ở đó,
# cộng phần ghi đè chỉ dành cho kind: ảnh lấy thẳng từ Docker Hub, một replica istiod không ràng buộc pool, tắt
# IPv6 (máy kiểm thử lồng container không hỗ trợ IPv6 nên ztunnel và istio-cni lỗi "Address family not supported").
# Cần cụm từ tao-cum.sh (Cilium đã đặt cni.exclusive=false, socketLB.hostNamespaceOnly=true). Dùng: ./cai-istio.sh
set -euo pipefail
cd "$(dirname "$0")"
M=../../charts/cell-mesh/istio
V=$(awk '/^version:/{print $2}' "$M/chart.yaml")
REPO=$(awk '/^repo:/{print $2}' "$M/chart.yaml")
GW_API=${GW_API:-v1.3.0}
helm repo add istio "$REPO" >/dev/null 2>&1 || true
helm repo update istio >/dev/null
# Hồ sơ ambient dùng ảnh distroless; nạp sẵn vào node vì kubelet trong kind không kéo được qua proxy của máy.
./nap-anh.sh "docker.io/istio/pilot:${V}-distroless" "docker.io/istio/install-cni:${V}-distroless" \
  "docker.io/istio/ztunnel:${V}-distroless" "docker.io/istio/proxyv2:${V}-distroless" >/dev/null
kubectl apply --server-side -f "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GW_API}/standard-install.yaml" >/dev/null
KIND=(--set global.hub=docker.io/istio --set "global.tag=${V}")
helm upgrade --install istio-base istio/base --version "$V" -n istio-system --create-namespace -f "$M/values-base.yaml" --wait >/dev/null
helm upgrade --install istiod istio/istiod --version "$V" -n istio-system -f "$M/values-istiod.yaml" "${KIND[@]}" \
  --set pilot.nodeSelector=null --set pilot.autoscaleMin=1 --set pilot.replicaCount=1 \
  --set pilot.resources.requests.memory=256Mi --wait --timeout 5m >/dev/null
helm upgrade --install istio-cni istio/cni --version "$V" -n istio-system -f "$M/values-cni.yaml" "${KIND[@]}" \
  --set ambient.ipv6=false --wait --timeout 5m >/dev/null
helm upgrade --install ztunnel istio/ztunnel --version "$V" -n istio-system -f "$M/values-ztunnel.yaml" \
  --set hub=docker.io/istio --set "tag=${V}" --set resources.requests.memory=128Mi --set env.IPV6_ENABLED=false \
  --wait --timeout 5m >/dev/null
kubectl -n istio-system get pods
