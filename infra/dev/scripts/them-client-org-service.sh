#!/usr/bin/env bash
# =============================================================================
# QA: đồng bộ client org-service (tài khoản dịch vụ SVC-04) của realm mission trên Keycloak dev đang chạy với
# infra/dev/.env. Khi người dùng chọn phân công (PUT /api/v1/org/me/context, SCR-0001), org-service lấy token client
# credentials của client này rồi ghi ngữ cảnh vào thuộc tính tài khoản qua Admin API (KeycloakContextAdapter), nên cần
# bí mật khớp ORG_SERVICE_CLIENT_SECRET và vai trò realm-management view-users, manage-users. Realm chỉ nhập từ
# keycloak/realm-mission.json ở lần khởi động đầu (--import-realm bỏ qua realm đã có); .env sinh lại sau đó làm bí mật
# lệch, Keycloak báo invalid_client_credentials và mọi lần chọn phân công trả 503 ORG-503-INTEGRATION-UNAVAILABLE.
# Chạy lại không tạo trùng: client đã có thì chỉ đặt lại bí mật và vai trò.
#
#   ./infra/dev/scripts/them-client-org-service.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, ORG_SERVICE_CLIENT_SECRET từ infra/dev/.env; không in bí mật. Không cần
# khởi động lại org-service: adapter xin token mới ở lần gọi kế tiếp.
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
: "${ORG_SERVICE_CLIENT_SECRET:?Cần ORG_SERVICE_CLIENT_SECRET trong infra/dev/.env (scripts/gen-env.sh --bo-sung)}"
CID=org-service
ROLE=svc_org_service

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

if ! kc get "roles/${ROLE}" -r mission >/dev/null 2>&1; then
  kc create roles -r mission -s "name=${ROLE}" -s 'description=Tài khoản dịch vụ org-service (SVC-04)' >/dev/null
  echo "Đã tạo vai trò ${ROLE}"
fi
id="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
if [[ -z "${id}" ]]; then
  # Bí mật truyền qua stdin (tệp JSON đọc từ "-f -") để không nằm trên dòng lệnh của tiến trình.
  printf '{"clientId":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"clientAuthenticatorType":"client-secret","secret":"%s"}' \
    "${CID}" "${ORG_SERVICE_CLIENT_SECRET}" | kc create clients -r mission -f - >/dev/null
  echo "Đã tạo client ${CID}"
else
  printf '{"secret":"%s"}' "${ORG_SERVICE_CLIENT_SECRET}" | kc update "clients/${id}" -r mission -f - >/dev/null
  echo "Client ${CID} đã có, đặt lại bí mật theo .env"
fi
kc add-roles -r mission --uusername "service-account-${CID}" --rolename "${ROLE}"
kc add-roles -r mission --uusername "service-account-${CID}" --cclientid realm-management \
  --rolename view-users --rolename manage-users
echo "Đã gán ${ROLE}, realm-management view-users, manage-users cho service-account-${CID}"
cid="$(kc get clients -r mission -q clientId="${CID}" --fields id --format csv --noquotes | head -1)"
# "roles" đưa resource_access.realm-management vào token; thiếu nó Admin API trả 403 dù đã gán vai trò.
for name in profile roles mission-context; do
  scope="$(kc get client-scopes -r mission --fields id,name --format csv --noquotes | awk -F, -v n="${name}" '$2==n{print $1}')"
  [[ -n "${scope}" ]] && kc update "clients/${cid}/default-client-scopes/${scope}" -r mission >/dev/null
done
echo "Đã gắn phạm vi profile, roles, mission-context cho ${CID}"
