#!/usr/bin/env bash
# =============================================================================
# Tắt (hoặc bật lại) bước nhập mã OTP khi đăng nhập realm mission trên Keycloak dev đang chạy. Chỉ dùng cho môi
# trường dev theo yêu cầu của người phụ trách (24/10/2026); realm production giữ MFA theo SEC và wiki 07.
#
#   ./infra/dev/scripts/tat-otp-dev.sh          # tắt OTP
#   ./infra/dev/scripts/tat-otp-dev.sh --bat    # bật lại
#
# Khi tắt: bước auth-otp-form, direct-grant-validate-otp và các nhánh phụ OTP theo vai trò -> DISABLED; nhánh step-up
# aal2 thêm bước "Allow access" nên vẫn trả acr aal2 mà không hỏi mã. Khi bật: đưa về REQUIRED, CONDITIONAL và xoá
# bước "Allow access". Khoá OTP của tài khoản thử được giữ, script cũ gửi kèm mã OTP vẫn chạy.
# Khi tắt còn đặt acr mặc định aal2 cho client web (default.acr.values): đăng nhập thường đi qua nhánh aal2 (không hỏi
# mã) nên token mang acr aal2 ngay, tài khoản có vai trò AAL2 (quản trị, thẩm định, quyết định) không còn gặp hộp
# "Xác thực nhiều lớp" sau khi đăng nhập (BR-00-01). Thao tác yêu cầu bước lên còn mới (khoá kỳ, đổi quyền) vẫn hỏi
# lại khi quá hạn, bấm "Xác thực" là qua. Khi bật thì xoá thuộc tính này.
# Keycloak chỉ nhập keycloak/realm-mission.json ở lần khởi động đầu, nên thay đổi này tồn tại qua các lần triển khai
# lại; chỉ khi xoá dữ liệu Keycloak mới phải chạy lại script. Đọc KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD từ
# infra/dev/.env; không in mật khẩu.
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
REALM="${KC_REALM:-mission}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Cần KEYCLOAK_ADMIN_PASSWORD trong infra/dev/.env}"

MUC="DISABLED"
if [[ "${1:-}" == "--bat" ]]; then
  MUC="REQUIRED"
fi

kc() { docker exec -i "${KC}" /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://127.0.0.1:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null

BROWSER_FLOW="$(kc get "realms/${REALM}" --fields browserFlow | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["browserFlow"])')"
DIRECT_FLOW="$(kc get "realms/${REALM}" --fields directGrantFlow | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["directGrantFlow"])')"

doi=0
dat_muc() { # $1 flow_url, $2 id, $3 mức
  kc update "authentication/flows/${1}/executions" -r "${REALM}" -b "{\"id\":\"${2}\",\"requirement\":\"${3}\"}" >/dev/null
  doi=$((doi + 1))
}
url() { python3 -I -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

# Danh sách (id, mức) cần đổi, mỗi dòng "id mức". Bước nhập OTP: DISABLED khi tắt, REQUIRED khi bật. Nhánh phụ
# "<luồng> otp <vai trò>" chỉ gồm điều kiện vai trò, điều kiện LoA và một bước OTP: tắt riêng bước OTP làm nhánh
# không còn bước xác thực nên đăng nhập hỏng, vì vậy tắt cả nhánh (DISABLED) và bật lại về CONDITIONAL.
for flow in "${BROWSER_FLOW}" "${DIRECT_FLOW}"; do
  flow_url="$(url "${flow}")"
  doi_list="$(kc get "authentication/flows/${flow_url}/executions" -r "${REALM}" | python3 -I -c '
import json, sys
tat = sys.argv[1] == "DISABLED"
goc = sys.argv[2]
for e in json.load(sys.stdin):
    pid, ten, muc = e.get("providerId"), e.get("displayName", ""), e.get("requirement")
    if pid in ("auth-otp-form", "direct-grant-validate-otp"):
        can = "DISABLED" if tat else "REQUIRED"
    elif e.get("authenticationFlow") and ten.startswith(goc + " otp "):
        can = "DISABLED" if tat else "CONDITIONAL"
    else:
        continue
    if muc != can:
        print(e["id"], can)
' "${MUC}" "${BROWSER_FLOW}")"
  while read -r id can; do
    [[ -n "${id}" ]] && dat_muc "${flow_url}" "${id}" "${can}"
  done <<<"${doi_list}"
done

# Nhánh "<luồng> aal2" (step-up) chỉ có bước OTP: khi tắt OTP, thêm bước "Allow access" để nhánh vẫn đạt và token
# vẫn mang acr aal2 mà không hỏi mã; khi bật lại thì xoá bước này.
AAL2_URL="$(url "${BROWSER_FLOW} aal2")"
allow_id="$(kc get "authentication/flows/${AAL2_URL}/executions" -r "${REALM}" | python3 -I -c '
import json, sys
print(next((e["id"] for e in json.load(sys.stdin) if e.get("providerId") == "allow-access-authenticator"), ""))')"
if [[ "${MUC}" == "DISABLED" && -z "${allow_id}" ]]; then
  kc create "authentication/flows/${AAL2_URL}/executions/execution" -r "${REALM}" \
    -b '{"provider":"allow-access-authenticator"}' >/dev/null
  allow_id="$(kc get "authentication/flows/${AAL2_URL}/executions" -r "${REALM}" | python3 -I -c '
import json, sys
print(next(e["id"] for e in json.load(sys.stdin) if e.get("providerId") == "allow-access-authenticator"))')"
  dat_muc "${AAL2_URL}" "${allow_id}" "REQUIRED"
elif [[ "${MUC}" == "REQUIRED" && -n "${allow_id}" ]]; then
  kc delete "authentication/executions/${allow_id}" -r "${REALM}" >/dev/null
  doi=$((doi + 1))
fi
# acr mặc định của client web (BR-00-01): tắt OTP thì đăng nhập thẳng ở aal2; bật lại thì trả về aal1 như thiết kế.
WEB_CLIENT="${AUTH_CLIENT_ID:-mission-web}"
web_id="$(kc get clients -r "${REALM}" -q clientId="${WEB_CLIENT}" --fields id --format csv --noquotes | head -1)"
if [[ -n "${web_id}" ]]; then
  acr_hien="$(kc get "clients/${web_id}" -r "${REALM}" --fields attributes | python3 -I -c '
import json, sys
print(json.load(sys.stdin).get("attributes", {}).get("default.acr.values", ""))')"
  acr_can=""
  [[ "${MUC}" == "DISABLED" ]] && acr_can="aal2"
  if [[ "${acr_hien}" != "${acr_can}" ]]; then
    kc update "clients/${web_id}" -r "${REALM}" -s "attributes.\"default.acr.values\"=${acr_can}" >/dev/null
    doi=$((doi + 1))
  fi
fi
echo "Realm ${REALM}: đã đổi ${doi} bước (OTP $([[ "${MUC}" == DISABLED ]] && echo tắt || echo bật); luồng ${BROWSER_FLOW}, ${DIRECT_FLOW})"
