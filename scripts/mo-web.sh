#!/usr/bin/env bash
# Lối tắt trong repo tới gói tools/mo-web (gói tự chứa để gửi người khác): nạp thông tin kết nối từ .env ở gốc repo
# rồi chạy tools/mo-web/mo-web.sh với cùng tham số.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${REPO_ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${REPO_ROOT}/.env"
  set +a
fi
exec bash "${REPO_ROOT}/tools/mo-web/mo-web.sh" "$@"
