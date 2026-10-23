#!/usr/bin/env bash
# =============================================================================
# Thêm gốc web (WEB_ORIGIN, mặc định http://127.0.0.1:35000 là APISIX dev phục vụ web và /api cùng nguồn) vào
# redirect URI và web origin của client mission-web trong realm mission của Keycloak dev đang chạy. Realm nhập
# từ keycloak/realm-mission.json chỉ lấy WEB_ORIGIN lúc nhập lần đầu; Keycloak đã nhập realm với gốc khác
# (ví dụ http://localhost:4200) thì đăng nhập từ web trong compose báo "Invalid parameter: redirect_uri".
# Chạy lại không thêm trùng.
#
#   ./infra/dev/scripts/them-goc-web.sh                 # dùng WEB_ORIGIN trong infra/dev/.env
#   WEB_ORIGIN=http://<máy chủ dev>:35000 ./infra/dev/scripts/them-goc-web.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD từ infra/dev/.env; không in bí mật.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
GOC_YEU_CAU="${WEB_ORIGIN:-}"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi
GOC="${GOC_YEU_CAU:-${WEB_ORIGIN:-http://127.0.0.1:35000}}"
GOC="${GOC%/}"
[[ "${GOC}" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || { echo "WEB_ORIGIN không hợp lệ: ${GOC}" >&2; exit 2; }
KC="${KEYCLOAK_CONTAINER:-mission-dev-keycloak}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Cần KEYCLOAK_ADMIN_PASSWORD trong infra/dev/.env}"

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

id="$(kc get clients -r mission -q clientId=mission-web --fields id --format csv --noquotes | head -1)"
[[ -n "${id}" ]] || { echo "Không thấy client mission-web trong realm mission" >&2; exit 1; }
json="$(kc get "clients/${id}" -r mission --fields redirectUris,webOrigins)"
moi="$(printf '%s' "${json}" | python3 -c '
import json, sys
goc = sys.argv[1]
c = json.load(sys.stdin)
r, w = c.get("redirectUris", []), c.get("webOrigins", [])
doi = False
if goc + "/*" not in r: r.append(goc + "/*"); doi = True
if goc not in w: w.append(goc); doi = True
print(json.dumps({"redirectUris": r, "webOrigins": w}) if doi else "")
' "${GOC}")"
if [[ -z "${moi}" ]]; then
  echo "Client mission-web đã có gốc ${GOC}"
else
  printf '%s' "${moi}" | kc update "clients/${id}" -r mission -f - >/dev/null
  echo "Đã thêm ${GOC} vào redirect URI và web origin của client mission-web"
fi
