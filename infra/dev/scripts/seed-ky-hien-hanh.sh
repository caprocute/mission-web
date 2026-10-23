#!/usr/bin/env bash
# =============================================================================
# Nạp kỳ đánh giá hiện hành cho tenant thử trên dev (CORE-01-04, QT-08, SCR-0801 → createPeriod → MOD-08).
#
# Tenant dev mới chưa có kỳ nào thì `GET /okr/tree` không kèm kỳ trả 404 và cây mục tiêu báo chưa có kỳ đang
# mở. Script đăng nhập quản trị đơn vị thử (mặc định `quantri`, vai trò tenant_admin) qua client mission-web
# bằng PKCE và OTP (lay-token-thu.py) rồi chạy data/seed/config/nap_ky_hien_hanh.py qua APISIX: có kỳ đang
# mở bao trùm hôm nay thì không làm gì, chưa có thì tạo kỳ quý hiện hành. Chạy lại không tạo trùng. Đi qua
# API thay vì chèn SQL, nên kỳ mới có lịch mốc và ghim Policy Pack như khi tạo trên giao diện.
#
# Chạy sau khi mission-core healthy (deploy-backend.sh gọi script này ở bước cuối khi có mission-core).
#   infra/dev/scripts/seed-ky-hien-hanh.sh           # tạo kỳ nếu thiếu
#   infra/dev/scripts/seed-ky-hien-hanh.sh --kiem    # chỉ liệt kê kỳ bao trùm hôm nay
#
# Đọc infra/dev/.env (không commit): DEV_HOST, APISIX_PORT, KEYCLOAK_PUBLIC_URL, KC_TEST_USER_PASSWORD,
# KC_TEST_OTP_SECRET; SEED_ADMIN_USER đổi tài khoản, MISSION_FLOW đổi luồng áp dụng (mặc định CA_HAI),
# MISSION_TOKEN (nếu đặt) dùng thay bước đăng nhập. Không in token hay mật khẩu.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

HOST="${DEV_HOST:-127.0.0.1}"
export MISSION_API="http://${HOST}:${APISIX_PORT:-35000}/api/v1"
export KC_URL="${KEYCLOAK_PUBLIC_URL:-http://${HOST}:${KEYCLOAK_PORT:-35080}}"
export SMOKE_USER="${SEED_ADMIN_USER:-quantri}"

if [[ -z "${MISSION_TOKEN:-}" ]]; then
  MISSION_TOKEN="$(python3 "${SCRIPT_DIR}/lay-token-thu.py")" || {
    echo "Lỗi: không đăng nhập được ${SMOKE_USER} (kiểm KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET)" >&2
    exit 1
  }
fi
export MISSION_TOKEN

echo "==> Kỳ đánh giá hiện hành (tài khoản ${SMOKE_USER})"
python3 "${REPO_ROOT}/data/seed/config/nap_ky_hien_hanh.py" "$@"
