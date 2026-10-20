#!/usr/bin/env bash
# Kéo ảnh trên máy chủ Docker rồi nạp vào mọi node kind (dùng khi node không tự kéo ảnh được,
# ví dụ máy chỉ ra Internet qua proxy cục bộ). Chỉ nạp nền tảng linux/amd64 vì "kind load" lỗi với
# ảnh đa nền tảng khi Docker dùng kho ảnh containerd. Dùng: nap-anh.sh <ảnh> [<ảnh>...]
set -euo pipefail
CLUSTER=${CLUSTER:-vt-cell-kind}
NODES=$(kind get nodes --name "$CLUSTER")
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for img in "$@"; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull -q --platform linux/amd64 "$img" >/dev/null
  docker save --platform linux/amd64 -o "$TMP/a.tar" "$img"
  for n in $NODES; do
    docker exec -i "$n" ctr --namespace=k8s.io images import --platform linux/amd64 --snapshotter=overlayfs - < "$TMP/a.tar" >/dev/null
  done
  echo "đã nạp $img"
done
