#!/usr/bin/env bash
# =============================================================================
# HT-07: thêm vai trò svc_integration_gateway và client integration-gateway (tài khoản dịch vụ cổng tích hợp đẩy lô
# nhân sự về org-service) vào realm mission của Keycloak dev đang chạy. Realm mới nhập từ keycloak/realm-mission.json
# đã có sẵn; script dành cho Keycloak đã nhập realm trước đó (Keycloak bỏ qua tệp nhập khi realm đã tồn tại).
# Chạy lại không tạo trùng: client đã có thì chỉ đặt lại bí mật và vai trò. Script cũng gắn phạm vi profile cho client
# org-service và integration-gateway: token client credentials cần preferred_username "service-account-<client>" để
# dịch vụ nhận nhận tenant đích ở X-Tenant-Id (okrkpi.tenant.delegating-clients).
#
#   ./infra/dev/scripts/them-client-integration-gateway.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, INTEGRATION_GATEWAY_CLIENT_SECRET từ infra/dev/.env; không in bí mật.
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
: "${INTEGRATION_GATEWAY_CLIENT_SECRET:?Cần INTEGRATION_GATEWAY_CLIENT_SECRET trong infra/dev/.env (scripts/gen-env.sh --bo-sung)}"
CID=integration-gateway
ROLE=svc_integration_gateway

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

if ! kc get "roles/${ROLE}" -r mission >/dev/null 2>&1; then
  kc create roles -r mission -s "name=${ROLE}" \
    -s 'description=Tài khoản dịch vụ integration-gateway (SVC-13) đẩy lô nhân sự về org-service (DV-13-06, HT-07)' >/dev/null
  echo "Đã tạo vai trò ${ROLE}"
fi
id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
if [[ -z "${id}" ]]; then
  # Bí mật truyền qua stdin (tệp JSON đọc từ "-f -") để không nằm trên dòng lệnh của tiến trình.
  printf '{"clientId":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"clientAuthenticatorType":"client-secret","secret":"%s"}' \
    "${CID}" "${INTEGRATION_GATEWAY_CLIENT_SECRET}" | kc create clients -r mission -f - >/dev/null
  echo "Đã tạo client ${CID}"
else
  printf '{"secret":"%s"}' "${INTEGRATION_GATEWAY_CLIENT_SECRET}" | kc update "clients/${id}" -r mission -f - >/dev/null
  echo "Client ${CID} đã có, đặt lại bí mật theo .env"
fi
kc add-roles -r mission --uusername "service-account-${CID}" --rolename "${ROLE}"
echo "Đã gán vai trò ${ROLE} cho service-account-${CID}"
scope="$(kc get client-scopes -r mission --fields id,name --format csv --noquotes | awk -F, '$2=="profile"{print $1}')"
for c in org-service "${CID}"; do
  cid="$(kc get clients -r mission -q clientId="${c}" --fields id --format csv --noquotes | head -1)"
  kc update "clients/${cid}/default-client-scopes/${scope}" -r mission >/dev/null
done
echo "Đã gắn phạm vi profile cho org-service và ${CID}"
