#!/usr/bin/env bash
# =============================================================================
# Cấp cho tài khoản dịch vụ của client mission-core (SVC-05) trong realm mission của Keycloak dev đang chạy các quyền
# mà mission-core cần khi gọi workflow-service (SVC-06) và Admin API Keycloak: vai trò realm `service_client`
# (workflow-service chỉ nhận /internal/** và khởi động saga từ client mang vai trò này, CORE-02-08) và các vai trò
# realm-management xem, tra cứu, quản lý người dùng (KeycloakAdminClient: người giữ vai trò, cấp và thu hồi vai trò).
# Realm mới nhập từ keycloak/realm-mission.json đã có sẵn; script dành cho Keycloak đã nhập realm trước đó (Keycloak
# bỏ qua tệp nhập khi realm đã tồn tại). Chạy lại không tạo trùng: bí mật client được đặt lại theo .env.
#
#   ./infra/dev/scripts/them-client-mission-core.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, MISSION_CORE_CLIENT_SECRET từ infra/dev/.env; không in bí mật.
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
: "${MISSION_CORE_CLIENT_SECRET:?Cần MISSION_CORE_CLIENT_SECRET trong infra/dev/.env (scripts/gen-env.sh --bo-sung)}"
CID=mission-core
ROLE=service_client

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

if ! kc get "roles/${ROLE}" -r mission >/dev/null 2>&1; then
  kc create roles -r mission -s "name=${ROLE}" \
    -s 'description=Client dịch vụ nội bộ được gọi API nội bộ và khởi động saga của workflow-service (SVC-06)' >/dev/null
  echo "Đã tạo vai trò ${ROLE}"
fi
id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
if [[ -z "${id}" ]]; then
  # Bí mật truyền qua stdin (tệp JSON đọc từ "-f -") để không nằm trên dòng lệnh của tiến trình.
  printf '{"clientId":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"clientAuthenticatorType":"client-secret","secret":"%s"}' \
    "${CID}" "${MISSION_CORE_CLIENT_SECRET}" | kc create clients -r mission -f - >/dev/null
  echo "Đã tạo client ${CID}"
else
  # Client cũ của realm nhập trước có thể tắt tài khoản dịch vụ; bật lại cùng lúc với đặt bí mật.
  printf '{"secret":"%s","serviceAccountsEnabled":true}' "${MISSION_CORE_CLIENT_SECRET}" |
    kc update "clients/${id}" -r mission -f - >/dev/null
  echo "Client ${CID} đã có, đặt lại bí mật theo .env"
fi
kc add-roles -r mission --uusername "service-account-${CID}" --rolename "${ROLE}"
kc add-roles -r mission --uusername "service-account-${CID}" --cclientid realm-management \
  --rolename view-users --rolename query-users --rolename manage-users \
  --rolename view-realm --rolename manage-realm
# view-realm, manage-realm: mission-core tạo vai trò realm cho vai trò tuỳ chỉnh của đơn vị (BS-04, CORE-08-18).
echo "Đã gán ${ROLE} và quyền người dùng, vai trò realm của realm-management cho service-account-${CID}"
# Token client credentials cần preferred_username "service-account-mission-core" để workflow-service nhận tenant đích
# ở X-Tenant-Id (okrkpi.tenant.delegating-clients); preferred_username đến từ phạm vi profile.
scope="$(kc get client-scopes -r mission --fields id,name --format csv --noquotes | awk -F, '$2=="profile"{print $1}')"
id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
kc update "clients/${id}/default-client-scopes/${scope}" -r mission >/dev/null
echo "Đã gắn phạm vi profile cho ${CID}"
