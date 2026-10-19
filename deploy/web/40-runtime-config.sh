#!/bin/sh
# Sinh /config.json và federation.manifest.json từ biến môi trường khi container khởi động.
set -e
H=/usr/share/nginx/html
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
cat > $H/federation.manifest.json <<JSON
{ "feTask": "${FE_TASK_URL:-/remotes/fe-task}/remoteEntry.json" }
JSON
