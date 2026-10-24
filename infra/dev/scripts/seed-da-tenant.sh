#!/usr/bin/env bash
# =============================================================================
# Nạp bộ dữ liệu thử đa tenant (giả lập) lên môi trường dev: ba tenant của tỉnh giả định Phú Lâm gồm Đảng bộ
# tỉnh (khối Đảng), UBND tỉnh (khối chính quyền) và Công ty TNHH MTV Cấp nước (doanh nghiệp nhà nước), 51 tài
# khoản với vai trò đa dạng. Nguồn dữ liệu: data/seed/da-tenant/du_lieu.py; kịch bản trải nghiệm:
# docs/kich-ban-trai-nghiem/KICH-BAN-DA-TENANT.md.
#
# Các bước, chạy lại an toàn (không sinh trùng):
#   1. SQL: tenant-registry, tổ chức, căn cứ OKR, danh mục công việc (postgres/seed/da-tenant-du-lieu-thu.sql);
#   2. tài khoản realm mission (keycloak/tai-khoan-da-tenant.json, partialImport bỏ qua tài khoản đã có);
#   3. phát lại tổ chức của từng tenant sang bản sao của mission-core (dong-bo-to-chuc.sh);
#   4. kỳ quý hiện hành, OKR, check-in, nhiệm vụ qua API như người dùng (seed-da-tenant-api.py).
#
#   ./infra/dev/scripts/seed-da-tenant.sh            # cả bốn bước
#   ./infra/dev/scripts/seed-da-tenant.sh --khong-api # chỉ ba bước đầu
#
# Đọc infra/dev/.env (không commit): POSTGRES_*, KEYCLOAK_ADMIN*, KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET.
# Mật khẩu và khoá OTP chỉ đi qua stdin của kcadm, không in ra, không ghi tệp.
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
SEED="${SCRIPT_DIR}/../postgres/seed/da-tenant-du-lieu-thu.sql"
USERS="${SCRIPT_DIR}/../keycloak/tai-khoan-da-tenant.json"
PG_SUPERUSER="${POSTGRES_USER:-mission_admin}"
PG_SUPERPASS="${POSTGRES_PASSWORD:?Cần POSTGRES_PASSWORD trong infra/dev/.env}"
PG_DB="${POSTGRES_DB:-mission}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-mission-dev-postgres}"
KC="${KEYCLOAK_CONTAINER:-mission-dev-keycloak}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Cần KEYCLOAK_ADMIN_PASSWORD trong infra/dev/.env}"
: "${KC_TEST_USER_PASSWORD:?Cần KC_TEST_USER_PASSWORD trong infra/dev/.env}"
: "${KC_TEST_OTP_SECRET:?Cần KC_TEST_OTP_SECRET trong infra/dev/.env}"
export KC_TEST_USER_PASSWORD KC_TEST_OTP_SECRET
TENANTS="e1000000-0000-4000-8000-000000000001 e2000000-0000-4000-8000-000000000001 e3000000-0000-4000-8000-000000000001"

echo "==> 1/4 Tổ chức, tenant-registry, căn cứ OKR, danh mục công việc"
docker exec -i -e PGPASSWORD="${PG_SUPERPASS}" "${POSTGRES_CONTAINER}" \
  psql -q -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${PG_DB}" < "${SEED}"

echo "==> 2/4 Tài khoản realm mission"
kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null
python3 -I - "${USERS}" <<'PY' | kc create partialImport -r mission -f - -o |
import json, os, sys
users = json.load(open(sys.argv[1], encoding="utf-8"))["users"]
raw = json.dumps({"ifResourceExists": "SKIP", "users": users}, ensure_ascii=False)
raw = raw.replace("${KC_TEST_USER_PASSWORD}", os.environ["KC_TEST_USER_PASSWORD"])
raw = raw.replace("${KC_TEST_OTP_SECRET}", os.environ["KC_TEST_OTP_SECRET"])
sys.stdout.write(raw)
PY
  python3 -I -c 'import json,sys; r=json.load(sys.stdin); print("  Đã thêm", r.get("added", 0), "tài khoản, bỏ qua", r.get("skipped", 0), "tài khoản đã có")'

echo "==> 3/4 Đồng bộ tổ chức sang mission-core"
for t in ${TENANTS}; do
  "${SCRIPT_DIR}/dong-bo-to-chuc.sh" "${t}"
done

if [[ "${1:-}" == "--khong-api" ]]; then
  echo "Bỏ qua bước 4 (OKR, nhiệm vụ qua API)."
  exit 0
fi
echo "==> 4/4 Kỳ, OKR, check-in, nhiệm vụ qua API"
python3 -I "${SCRIPT_DIR}/seed-da-tenant-api.py"
