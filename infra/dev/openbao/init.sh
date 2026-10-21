#!/bin/sh
# =============================================================================
# NEN-020 · ADR-06 · TL-04 mục 6 "Bảo vệ dữ liệu" (mã hoá phong bì C2 qua OpenBao).
#
# Script bất biến (idempotent) khởi tạo OpenBao 2.x chế độ dev:
#   - Bật secrets engine transit/ và kv-v2 tại secret/
#   - Tạo khoá KEK mẫu cho mã hoá phong bì DEK (kek-default, kek-tenant-demo)
#   - Tạo khoá HMAC cho chỉ mục mù (blind-index-default)
#   - Ghi cấu hình mẫu vào secret/data/mission/dev-bootstrap
#
# Hỗ trợ chạy trong container openbao-init (qua HTTP API với curl hoặc bao CLI)
# hoặc chạy trực tiếp từ máy trạm qua cổng hầm SSH (OPENBAO_ADDR).
# =============================================================================
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
fi

BAO_ADDR="${OPENBAO_ADDR:-${BAO_ADDR:-http://127.0.0.1:${OPENBAO_PORT:-35820}}}"
BAO_TOKEN="${OPENBAO_DEV_ROOT_TOKEN:-${BAO_TOKEN:-mission_openbao_dev_token}}"
export BAO_ADDR BAO_TOKEN

echo "==> [NEN-020] Chờ OpenBao sẵn sàng tại ${BAO_ADDR}..."
attempt=0
while [ "${attempt}" -lt 30 ]; do
  if command -v bao >/dev/null 2>&1; then
    if bao status >/dev/null 2>&1; then
      break
    fi
  elif command -v curl >/dev/null 2>&1; then
    if curl -fsS "${BAO_ADDR}/v1/sys/health" >/dev/null 2>&1; then
      break
    fi
  elif command -v wget >/dev/null 2>&1; then
    if wget -qO- "${BAO_ADDR}/v1/sys/health" >/dev/null 2>&1; then
      break
    fi
  fi
  attempt=$((attempt + 1))
  sleep 1
done

if [ "${attempt}" -ge 30 ]; then
  echo "LỖI: OpenBao tại ${BAO_ADDR} không phản hồi sau 30 giây." >&2
  exit 1
fi

bao_post() {
  path="$1"
  payload="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -X POST "${BAO_ADDR}/v1/${path}" \
      -H "X-Vault-Token: ${BAO_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${payload}" >/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -qO- --header="X-Vault-Token: ${BAO_TOKEN}" \
      --header="Content-Type: application/json" \
      --post-data="${payload}" \
      "${BAO_ADDR}/v1/${path}" >/dev/null
  else
    echo "LỖI: Cần curl hoặc wget để gọi HTTP API OpenBao." >&2
    exit 1
  fi
}

echo "==> [NEN-020] Bật secrets engine transit/ và secret/ (kv-v2)..."
if command -v bao >/dev/null 2>&1; then
  bao secrets list -format=json | grep -q '"transit/"' || bao secrets enable -path=transit transit
  bao secrets list -format=json | grep -q '"secret/"' || bao secrets enable -path=secret -version=2 kv

  echo "==> [NEN-020] Tạo khoá Transit KEK (kek-default, kek-tenant-demo, blind-index-default)..."
  bao write -f transit/keys/kek-default type=aes256-gcm96 exportable=false
  bao write -f transit/keys/kek-tenant-demo type=aes256-gcm96 exportable=false
  bao write -f transit/keys/blind-index-default type=aes256-gcm96 exportable=false

  echo "==> [NEN-020] Ghi khóa mẫu kv-v2 tại secret/mission/dev-bootstrap..."
  bao kv put secret/mission/dev-bootstrap \
    environment="dev" \
    default_kek="kek-default" \
    blind_index_key="blind-index-default"
else
  # Chạy qua HTTP API chuẩn khi gọi từ máy trạm không cài bao CLI
  mounts="$(curl -fsS -H "X-Vault-Token: ${BAO_TOKEN}" "${BAO_ADDR}/v1/sys/mounts")"
  echo "${mounts}" | grep -q '"transit/"' || \
    bao_post "sys/mounts/transit" '{"type":"transit"}'
  echo "${mounts}" | grep -q '"secret/"' || \
    bao_post "sys/mounts/secret" '{"type":"kv","options":{"version":"2"}}'

  bao_post "transit/keys/kek-default" '{"type":"aes256-gcm96","exportable":false}'
  bao_post "transit/keys/kek-tenant-demo" '{"type":"aes256-gcm96","exportable":false}'
  bao_post "transit/keys/blind-index-default" '{"type":"aes256-gcm96","exportable":false}'
  bao_post "secret/data/mission/dev-bootstrap" \
    '{"data":{"environment":"dev","default_kek":"kek-default","blind_index_key":"blind-index-default"}}'
fi

echo "==> [NEN-020] OpenBao dev đã sẵn sàng (transit/keys/kek-default, kek-tenant-demo, blind-index-default)."
