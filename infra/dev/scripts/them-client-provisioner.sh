#!/usr/bin/env bash
# =============================================================================
# VH-04: thêm client tenant-registry-provisioner (tài khoản dịch vụ của saga cấp phát tenant) vào realm mission
# của Keycloak dev đang chạy. Realm mới nhập từ keycloak/realm-mission.json đã có client này; script dành cho
# Keycloak đã nhập realm trước khi client được thêm (Keycloak bỏ qua tệp nhập khi realm đã tồn tại).
# Chạy lại không tạo trùng: client đã có thì chỉ đặt lại bí mật và vai trò.
#
#   ./infra/dev/scripts/them-client-provisioner.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, PROVISION_CLIENT_SECRET từ infra/dev/.env; không in bí mật.
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
: "${PROVISION_CLIENT_SECRET:?Cần PROVISION_CLIENT_SECRET trong infra/dev/.env}"
CID=tenant-registry-provisioner

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
if [[ -z "${id}" ]]; then
  kc create clients -r mission -s clientId="${CID}" -s enabled=true -s publicClient=false \
    -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false \
    -s clientAuthenticatorType=client-secret -s "secret=${PROVISION_CLIENT_SECRET}" >/dev/null
  echo "Đã tạo client ${CID}"
else
  kc update "clients/${id}" -r mission -s "secret=${PROVISION_CLIENT_SECRET}" >/dev/null
  echo "Client ${CID} đã có, đặt lại bí mật theo .env"
fi
kc add-roles -r mission --uusername "service-account-${CID}" --cclientid realm-management \
  --rolename manage-realm --rolename manage-users --rolename manage-identity-providers \
  --rolename view-realm --rolename query-users
echo "Đã gán vai trò realm-management cho service-account-${CID}"
