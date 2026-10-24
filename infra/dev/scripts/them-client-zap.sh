#!/usr/bin/env bash
# =============================================================================
# QA-13: thêm client mission-zap (quét bảo mật OWASP ZAP bằng client credentials) vào realm mission của Keycloak dev
# đang chạy. Tài khoản dịch vụ service-account-mission-zap mang vai trò member và thuộc tính tenant, unit_id của cán
# bộ thử, nên token có claim tenant như người dùng thường. Realm mới nhập từ keycloak/realm-mission.json đã có sẵn;
# script dành cho Keycloak đã nhập realm trước đó. Chạy lại không tạo trùng: client đã có thì chỉ đặt lại bí mật.
# Chỉ dùng cho dev, không áp cho realm cell.
#
#   ./infra/dev/scripts/them-client-zap.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, ZAP_CLIENT_SECRET từ infra/dev/.env; không in bí mật.
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
: "${ZAP_CLIENT_SECRET:?Cần ZAP_CLIENT_SECRET trong infra/dev/.env (scripts/gen-env.sh --bo-sung)}"
CID=mission-zap
# Đơn vị và tenant của tài khoản canbo trong realm-mission.json.
TENANT=d0000000-0000-4000-8000-000000000001
UNIT=d0000000-0000-4000-8000-000000000101

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
if [[ -z "${id}" ]]; then
  # Bí mật truyền qua stdin (tệp JSON đọc từ "-f -") để không nằm trên dòng lệnh của tiến trình.
  printf '{"clientId":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"implicitFlowEnabled":false,"clientAuthenticatorType":"client-secret","secret":"%s"}' \
    "${CID}" "${ZAP_CLIENT_SECRET}" | kc create clients -r mission -f - >/dev/null
  echo "Đã tạo client ${CID}"
  id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
else
  printf '{"secret":"%s"}' "${ZAP_CLIENT_SECRET}" | kc update "clients/${id}" -r mission -f - >/dev/null
  echo "Client ${CID} đã có, đặt lại bí mật theo .env"
fi
kc add-roles -r mission --uusername "service-account-${CID}" --rolename member
uid="$(kc get users -r mission -q username="service-account-${CID}" --fields id --format csv --noquotes | head -1)"
printf '{"attributes":{"tenant":["%s"],"unit_id":["%s"]}}' "${TENANT}" "${UNIT}" \
  | kc update "users/${uid}" -r mission -f - >/dev/null
for s in profile mission-context; do
  sid="$(kc get client-scopes -r mission --fields id,name --format csv --noquotes | awk -F, -v n="${s}" '$2==n{print $1}')"
  kc update "clients/${id}/default-client-scopes/${sid}" -r mission >/dev/null
done
echo "Đã gán vai trò member, tenant thử và phạm vi profile, mission-context cho service-account-${CID}"
