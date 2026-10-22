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
# WEB-009: UI_PACK_PUBLIC_KEYS="keyId=SPKI_base64 keyId2=..." (khoá công khai EC P-256 của nền tảng).
# Chỉ nhận mã khoá và base64 hợp lệ để giá trị biến môi trường không chèn được JSON.
UI_PACK_KEYS_JSON=""
for kv in ${UI_PACK_PUBLIC_KEYS:-}; do
  k=${kv%%=*}; v=${kv#*=}
  if printf '%s' "$k" | grep -Eq '^[A-Za-z0-9._-]{1,64}$' && printf '%s' "$v" | grep -Eq '^[A-Za-z0-9+/]{40,1024}={0,2}$'; then
    UI_PACK_KEYS_JSON="$UI_PACK_KEYS_JSON${UI_PACK_KEYS_JSON:+, }\"$k\": \"$v\""
  else
    echo "Bỏ qua khoá UI Pack không hợp lệ: $k" >&2
  fi
done
# WEB-011: FARO_URL (nên cùng nguồn, ví dụ /faro/collect) và TELEMETRY_SAMPLE_RATIO (0..1, mặc định 0.1).
FARO=$(printf '%s' "${FARO_URL:-}" | sed -nE 's#^((https?://[A-Za-z0-9.:-]+)?/[A-Za-z0-9._/-]*)$#\1#p')
RATIO=$(printf '%s' "${TELEMETRY_SAMPLE_RATIO:-0.1}" | sed -nE 's#^(0(\.[0-9]+)?|1(\.0+)?)$#\1#p')
cat > $OUT/config.json <<JSON
{
  "cell": "${CELL:-DEV}",
  "tenant": "${TENANT:-default}",
  "apiBaseUrl": "${API_BASE_URL:-/api}",
  "uiPackBaseUrl": "${UI_PACK_BASE_URL:-/ui-packs}",
  "auth": { "enabled": ${AUTH_ENABLED:-false}, "issuer": "${AUTH_ISSUER:-}", "clientId": "${AUTH_CLIENT_ID:-mission-web}", "scope": "openid profile email" },
  "useMockApi": ${USE_MOCK_API:-true},
  "telemetry": { "faroUrl": "$FARO", "sampleRatio": ${RATIO:-0.1} },
  "uiPackPublicKeys": { $UI_PACK_KEYS_JSON }
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
CONNECT=$(csp_list ${API_BASE_URL:-} ${AUTH_ISSUER:-} ${UI_PACK_BASE_URL:-} $REMOTE_URLS ${FARO:-} ${CSP_CONNECT_SRC:-})
# shellcheck disable=SC2086
ANCESTORS=$(csp_list ${CSP_FRAME_ANCESTORS:-})
cat > "$CSP_DIR/csp-nguon.conf" <<NGINX
set \$csp_connect "$CONNECT";
set \$csp_assets "$ASSETS";
set \$csp_frame "$IDP";
set \$csp_ancestors "$ANCESTORS";
NGINX
