#!/bin/sh
# Sinh /config.json và federation.manifest.json từ biến môi trường khi container khởi động.
# Mỗi remote trong /etc/mission-web/remotes.tsv (khoá, biến URL, project; sinh lúc build từ
# frontend/tools/remotes/remotes.json) nhận URL từ biến tương ứng, ví dụ FE_TASK_URL,
# FE_OKR_URL, FE_CONFIG_STUDIO_URL; mặc định là /remotes/<project> cùng nguồn với shell.
set -e
H=/usr/share/nginx/html
REMOTES=${REMOTES_TSV:-/etc/mission-web/remotes.tsv}
cat > $H/config.json <<JSON
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
} > $H/federation.manifest.json
