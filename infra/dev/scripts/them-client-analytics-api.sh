#!/usr/bin/env bash
# =============================================================================
# Thêm client analytics-api (tài khoản dịch vụ SVC-15) vào realm mission của Keycloak dev đang chạy. analytics-api
# (ServiceToken) xin token client credentials của client này để gọi cổng dịch vụ MOD-08 của mission-core
# (/api/v1/config/dashboard-defs, CORE-08-17; vai trò service_client). Thiếu client thì danh sách Bảng điều hành trả
# 503 "Hệ thống đang bận" (dependency=keycloak). Realm mới nhập từ keycloak/realm-mission.json đã có sẵn; script dành
# cho Keycloak đã nhập realm trước đó. Chạy lại không tạo trùng: client đã có thì chỉ đặt lại bí mật và vai trò.
#
#   ./infra/dev/scripts/them-client-analytics-api.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, ANALYTICS_CLIENT_SECRET từ infra/dev/.env; không in bí mật.
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
: "${ANALYTICS_CLIENT_SECRET:?Cần ANALYTICS_CLIENT_SECRET trong infra/dev/.env (scripts/gen-env.sh --bo-sung)}"
CID=analytics-api
ROLE=service_client

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

if ! kc get "roles/${ROLE}" -r mission >/dev/null 2>&1; then
  kc create roles -r mission -s "name=${ROLE}" \
    -s 'description=Client dịch vụ nội bộ được gọi cổng dịch vụ của mission-core' >/dev/null
  echo "Đã tạo vai trò ${ROLE}"
fi
id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
if [[ -z "${id}" ]]; then
  # Bí mật truyền qua stdin (tệp JSON đọc từ "-f -") để không nằm trên dòng lệnh của tiến trình.
  printf '{"clientId":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"clientAuthenticatorType":"client-secret","secret":"%s"}' \
    "${CID}" "${ANALYTICS_CLIENT_SECRET}" | kc create clients -r mission -f - >/dev/null
  echo "Đã tạo client ${CID}"
else
  printf '{"secret":"%s"}' "${ANALYTICS_CLIENT_SECRET}" | kc update "clients/${id}" -r mission -f - >/dev/null
  echo "Client ${CID} đã có, đặt lại bí mật theo .env"
fi
kc add-roles -r mission --uusername "service-account-${CID}" --rolename "${ROLE}"
echo "Đã gán vai trò ${ROLE} cho service-account-${CID}"
scope="$(kc get client-scopes -r mission --fields id,name --format csv --noquotes | awk -F, '$2=="profile"{print $1}')"
cid="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
kc update "clients/${cid}/default-client-scopes/${scope}" -r mission >/dev/null
echo "Đã gắn phạm vi profile cho ${CID}"
