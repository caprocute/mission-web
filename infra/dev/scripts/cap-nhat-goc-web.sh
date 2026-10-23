#!/usr/bin/env bash
# =============================================================================
# Thêm gốc web (APISIX dev và WEB_ORIGIN) vào redirect URI và web origin của client mission-web trong realm
# mission của Keycloak dev đang chạy. Realm mới nhập từ keycloak/realm-mission.json đã có các gốc này; script
# dành cho Keycloak đã nhập realm từ trước (Keycloak bỏ qua tệp nhập khi realm đã tồn tại), khi web chuyển sang
# phục vụ sau APISIX (dịch vụ `web` của docker-compose.yml). Chạy lại không thêm trùng.
#
#   ./infra/dev/scripts/cap-nhat-goc-web.sh
#
# Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, APISIX_PORT, WEB_ORIGIN từ infra/dev/.env; không in bí mật.
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
PORT="${APISIX_PORT:-35000}"
ORIGINS=("http://127.0.0.1:${PORT}" "http://localhost:${PORT}")
[[ -n "${WEB_ORIGIN:-}" ]] && ORIGINS+=("${WEB_ORIGIN%/}")

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

id="$(kc get clients -r mission -q clientId=mission-web --fields id --format csv --noquotes | head -1)"
[[ -n "${id}" ]] || { echo "Không thấy client mission-web trong realm mission" >&2; exit 1; }

# Ghép danh sách hiện có với gốc mới, giữ thứ tự, bỏ trùng; kcadm nhận mảng JSON qua -s.
cur="$(kc get "clients/${id}" -r mission --fields redirectUris,webOrigins)"
read -r redirects origins < <(CUR="${cur}" python3 - ${ORIGINS[@]+"${ORIGINS[@]}"} <<'PY'
import json, os, sys
c = json.loads(os.environ["CUR"])
def merge(old, new):
    out = list(old)
    for v in new:
        if v not in out:
            out.append(v)
    return json.dumps(out, separators=(",", ":"))
new = sys.argv[1:]
print(merge(c.get("redirectUris", []), [o + "/*" for o in new]),
      merge(c.get("webOrigins", []), new))
PY
)
kc update "clients/${id}" -r mission -s "redirectUris=${redirects}" -s "webOrigins=${origins}"
echo "Client mission-web nhận gốc web: ${ORIGINS[*]-}"
