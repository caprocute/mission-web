#!/usr/bin/env bash
# NEN-006 · ADR-32: Gộp 11 hợp đồng OpenAPI 3.1 (contracts/openapi/modules/*.yaml)
# thành các tệp độc lập đã giải tham chiếu $ref trong contracts/dist/*.yaml
# trước khi openapi-generator-maven-plugin sinh mã Java.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CONTRACTS_DIR="${REPO_ROOT}/contracts"
DIST_DIR="${CONTRACTS_DIR}/dist"

MODULES=(
  appraisals
  config
  dossiers
  jobs
  okr
  org
  oversight
  platform
  policies
  products
  tasks
)

if [[ "${1:-}" == "--if-missing" ]]; then
  all_present=true
  for m in "${MODULES[@]}"; do
    if [[ ! -f "${DIST_DIR}/${m}.yaml" ]]; then
      all_present=false
      break
    fi
  done
  if [[ "${all_present}" == "true" ]]; then
    exit 0
  fi
fi

if [[ ! -d "${CONTRACTS_DIR}" ]]; then
  echo "Lỗi: Không tìm thấy thư mục contracts tại ${CONTRACTS_DIR}" >&2
  exit 1
fi

if [[ ! -d "${CONTRACTS_DIR}/node_modules" ]]; then
  echo "==> Cài đặt phụ thuộc cho contracts/ ..."
  npm --prefix "${CONTRACTS_DIR}" ci --no-audit --no-fund
fi

echo "==> Gộp 11 hợp đồng OpenAPI 3.1 vào ${DIST_DIR} ..."
npm --prefix "${CONTRACTS_DIR}" run bundle

for m in "${MODULES[@]}"; do
  if [[ ! -f "${DIST_DIR}/${m}.yaml" ]]; then
    echo "Lỗi: Thiếu tệp hợp đồng đã gộp ${DIST_DIR}/${m}.yaml" >&2
    exit 1
  fi
done

echo "==> Đã gộp thành công 11 hợp đồng OpenAPI tại ${DIST_DIR}."
