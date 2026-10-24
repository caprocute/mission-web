#!/usr/bin/env bash
# =============================================================================
# Tắt (hoặc bật lại) bước nhập mã OTP khi đăng nhập realm mission trên Keycloak dev đang chạy. Chỉ dùng cho môi
# trường dev theo yêu cầu của người phụ trách (24/10/2026); realm production giữ MFA theo SEC và wiki 07.
#
#   ./infra/dev/scripts/tat-otp-dev.sh          # tắt OTP: auth-otp-form và direct-grant-validate-otp -> DISABLED
#   ./infra/dev/scripts/tat-otp-dev.sh --bat    # bật lại: các bước trên -> REQUIRED
#
# Chỉ đổi mức yêu cầu của bước nhập OTP. Điều kiện vai trò và mức LoA (aal1, aal2, step-up) giữ nguyên, nên luồng
# step-up vẫn trả acr aal2, chỉ không hỏi mã. Khoá OTP của tài khoản thử được giữ, script cũ gửi kèm mã OTP vẫn chạy.
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
for flow in "${BROWSER_FLOW}" "${DIRECT_FLOW}"; do
  flow_url="$(python3 -I -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "${flow}")"
  ids="$(kc get "authentication/flows/${flow_url}/executions" -r "${REALM}" | python3 -I -c '
import json, sys
muc = sys.argv[1]
for e in json.load(sys.stdin):
    if e.get("providerId") in ("auth-otp-form", "direct-grant-validate-otp") and e.get("requirement") != muc:
        print(e["id"])
' "${MUC}")"
  for id in ${ids}; do
    kc update "authentication/flows/${flow_url}/executions" -r "${REALM}" \
      -b "{\"id\":\"${id}\",\"requirement\":\"${MUC}\"}" >/dev/null
    doi=$((doi + 1))
  done
done
echo "Realm ${REALM}: đã đặt ${doi} bước nhập OTP thành ${MUC} (luồng ${BROWSER_FLOW}, ${DIRECT_FLOW})"
