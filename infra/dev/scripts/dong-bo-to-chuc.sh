#!/usr/bin/env bash
# =============================================================================
# Đẩy dữ liệu tổ chức của org-service (sau seed-org.sh) sang bản sao org_replica của mission-core bằng lệnh
# org-republish (DV-04-08) lên topic org.bootstrap.v1. Thiếu bước này, mọi API cần đơn vị công tác của người
# gọi (danh sách việc của đơn vị, giao việc) trả 403 "Chưa xác định được đơn vị công tác".
# Cần chạy dang-ky-luoc-do.py trước (serde của lệnh không tự đăng ký lược đồ). Chạy lại an toàn: bản sao ghi đè
# theo phiên bản nguồn.
#
#   ./infra/dev/scripts/dong-bo-to-chuc.sh [tenant-uuid]
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TENANT="${1:-d0000000-0000-4000-8000-000000000001}"
[[ "${TENANT}" =~ ^[0-9a-f-]{36}$ ]] || { echo "Mã tenant không hợp lệ: ${TENANT}" >&2; exit 2; }
cd "${SCRIPT_DIR}/.."
docker compose --profile backend run --rm --no-deps -T \
  -e SPRING_PROFILES_ACTIVE=republish \
  org-service --tenant="${TENANT}" 2>&1 | grep -ao '"message":"org-republish[^"]*' | sed 's/"message":"//'
