#!/usr/bin/env bash
# =============================================================================
# Thêm vào realm mission của Keycloak dev đang chạy các tài khoản thử có trong keycloak/realm-mission.json mà realm
# chưa có (ví dụ giamdocso, phogiamdocso cấp Sở cho HT-08, HT-09). Keycloak chỉ nhập tệp realm ở lần khởi động
# đầu, nên realm cũ không tự có tài khoản mới. Tài khoản đã có được giữ nguyên (partialImport, ifResourceExists=SKIP);
# tài khoản dịch vụ (service-account-*) do script thêm client tương ứng lo.
#
#   ./infra/dev/scripts/them-tai-khoan-thu.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET từ infra/dev/.env; mật khẩu
# và khoá OTP chỉ đi qua stdin của kcadm, không in ra, không ghi tệp.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi
KC="${KEYCLOAK_CONTAINER:-mission-dev-keycloak}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Cần KEYCLOAK_ADMIN_PASSWORD trong infra/dev/.env}"
: "${KC_TEST_USER_PASSWORD:?Cần KC_TEST_USER_PASSWORD trong infra/dev/.env}"
: "${KC_TEST_OTP_SECRET:?Cần KC_TEST_OTP_SECRET trong infra/dev/.env}"
export KC_TEST_USER_PASSWORD KC_TEST_OTP_SECRET

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

python3 -I - "${SCRIPT_DIR}/../keycloak/realm-mission.json" <<'PY' | kc create partialImport -r mission -f - -o |
import json, os, sys
realm = json.load(open(sys.argv[1], encoding="utf-8"))
users = [u for u in realm["users"] if not u["username"].startswith("service-account-")]
raw = json.dumps({"ifResourceExists": "SKIP", "users": users}, ensure_ascii=False)
raw = raw.replace("${KC_TEST_USER_PASSWORD}", os.environ["KC_TEST_USER_PASSWORD"])
raw = raw.replace("${KC_TEST_OTP_SECRET}", os.environ["KC_TEST_OTP_SECRET"])
sys.stdout.write(raw)
PY
  python3 -I -c 'import json,sys; r=json.load(sys.stdin); print("Đã thêm", r.get("added", 0), "tài khoản, bỏ qua", r.get("skipped", 0), "tài khoản đã có")'
