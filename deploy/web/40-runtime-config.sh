#!/bin/sh
# Sinh /config.json và federation.manifest.json từ biến môi trường khi container khởi động.
# Mỗi remote trong /etc/mission-web/remotes.tsv (khoá, biến URL, project; sinh lúc build từ
# frontend/tools/remotes/remotes.json) nhận URL từ biến tương ứng, ví dụ FE_TASK_URL,
# FE_OKR_URL, FE_CONFIG_STUDIO_URL; mặc định là /remotes/<project> cùng nguồn với shell.
set -e
H=/usr/share/nginx/html
REMOTES=${REMOTES_TSV:-/etc/mission-web/remotes.tsv}
# RUNTIME_DIR: nơi ghi hai tệp cấu hình; chart đặt /tmp/runtime vì hệ thống tệp gốc chỉ đọc.
OUT=${RUNTIME_DIR:-$H}
mkdir -p "$OUT"
cat > $OUT/config.json <<JSON
{
  "cell": "${CELL:-DEV}",
  "tenant": "${TENANT:-default}",
  "apiBaseUrl": "${API_BASE_URL:-/api}",
  "uiPackBaseUrl": "${UI_PACK_BASE_URL:-/ui-packs}",
  "auth": { "enabled": ${AUTH_ENABLED:-false}, "issuer": "${AUTH_ISSUER:-}", "clientId": "${AUTH_CLIENT_ID:-mission-web}", "scope": "openid profile email" },
  "useMockApi": ${USE_MOCK_API:-true}
}
JSON
{
  printf '{'
  sep=''
  while IFS="$(printf '\t')" read -r key var project; do
    [ -n "$key" ] || continue
    url=$(printenv "$var" || true)
    printf '%s\n  "%s": "%s/remoteEntry.json"' "$sep" "$key" "${url:-/remotes/$project}"
    sep=','
  done < "$REMOTES"
  printf '\n}\n'
} > $OUT/federation.manifest.json

# WEB-014: nguồn ngoài được phép trong CSP của index.html. Lấy origin của API, IdP, UI Pack và từng
# remote khác nguồn; CSP_CONNECT_SRC, CSP_FRAME_ANCESTORS thêm nguồn tuỳ ý (cách nhau bằng dấu cách).
# nginx include tệp này (glob, thiếu tệp vẫn chạy với chính sách chỉ cùng nguồn).
CSP_DIR=${CSP_DIR:-/tmp/runtime}
mkdir -p "$CSP_DIR"
origin() {
  printf '%s' "$1" | sed -nE 's#^((https?|wss?)://[^/?#]*).*#\1#p'
}
csp_list() {
  # in danh sách origin không trùng, mỗi mục có dấu cách đứng trước; bỏ mục không phải URL tuyệt đối
  for u in "$@"; do
    case "$u" in
      http://*|https://*|ws://*|wss://*) origin "$u"; printf '\n' ;;
    esac
  done | sort -u | sed -nE 's#^((https?|wss?)://[A-Za-z0-9.:-]+)$# \1#p' | tr -d '\n'
}
REMOTE_URLS=$(while IFS="$(printf '\t')" read -r key var project; do [ -n "$key" ] && printenv "$var"; done < "$REMOTES" || true)
# shellcheck disable=SC2086
ASSETS=$(csp_list $REMOTE_URLS ${UI_PACK_BASE_URL:-})
IDP=$(csp_list ${AUTH_ISSUER:-})
# shellcheck disable=SC2086
CONNECT=$(csp_list ${API_BASE_URL:-} ${AUTH_ISSUER:-} ${UI_PACK_BASE_URL:-} $REMOTE_URLS ${CSP_CONNECT_SRC:-})
# shellcheck disable=SC2086
ANCESTORS=$(csp_list ${CSP_FRAME_ANCESTORS:-})
cat > "$CSP_DIR/csp-nguon.conf" <<NGINX
set \$csp_connect "$CONNECT";
set \$csp_assets "$ASSETS";
set \$csp_frame "$IDP";
set \$csp_ancestors "$ANCESTORS";
NGINX
