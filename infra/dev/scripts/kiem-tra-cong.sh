#!/bin/bash
# Kiểm tra mọi container của project mission-dev chỉ công bố cổng trên 127.0.0.1.
# Chạy trên server dev; chỉ đọc, không đụng container của project khác.
set -euo pipefail
lo=$(docker ps --filter label=com.docker.compose.project=mission-dev \
  --format '{{.Names}} {{.Ports}}' | grep -E '(0\.0\.0\.0|\[::\]|:::)[0-9]*:?[0-9]+->' || true)
if [ -n "$lo" ]; then
  echo "LỖI: container mission-dev đang công bố cổng ra mọi địa chỉ:"
  echo "$lo"
  exit 1
fi
echo "Đạt: mọi cổng mission-dev chỉ gắn 127.0.0.1"
