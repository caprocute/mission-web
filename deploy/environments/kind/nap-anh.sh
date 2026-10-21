#!/usr/bin/env bash
# Kéo ảnh trên máy chủ Docker rồi nạp vào mọi node kind (dùng khi node không tự kéo ảnh được,
# ví dụ máy chỉ ra Internet qua proxy cục bộ). Chỉ nạp nền tảng linux/amd64 vì "kind load" lỗi với
# ảnh đa nền tảng khi Docker dùng kho ảnh containerd. Dùng: nap-anh.sh <ảnh> [<ảnh>...]
set -euo pipefail
CLUSTER=${CLUSTER:-vt-cell-kind}
# NAP_NODES: chỉ nạp vào các node này (tiết kiệm đĩa với ảnh lớn); mặc định mọi node.
NODES=${NAP_NODES:-$(kind get nodes --name "$CLUSTER")}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for img in "$@"; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull -q --platform linux/amd64 "$img" >/dev/null
  # Ảnh tham chiếu theo digest (chart vt-service bắt buộc): lưu dưới một tag tạm, nạp vào node rồi gắn lại
  # đúng tên "repo@sha256:..." để kubelet thấy ảnh có sẵn và không kéo từ registry.
  ten="$img"
  if [[ "$img" == *@sha256:* ]]; then
    ten="${img%@*}:vt-nap"
    docker tag "$img" "$ten"
  fi
  docker save --platform linux/amd64 -o "$TMP/a.tar" "$ten"
  for n in $NODES; do
    docker exec -i "$n" ctr --namespace=k8s.io images import --platform linux/amd64 --snapshotter=overlayfs - < "$TMP/a.tar" >/dev/null
    if [ "$ten" != "$img" ]; then
      docker exec "$n" ctr --namespace=k8s.io images rm "$img" >/dev/null 2>&1 || true
      docker exec "$n" ctr --namespace=k8s.io images tag "$ten" "$img" >/dev/null
    fi
  done
  echo "đã nạp $img"
done
