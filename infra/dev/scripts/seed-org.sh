#!/usr/bin/env bash
# =============================================================================
# Nạp dữ liệu thử của org-service (SVC-04) cho dev: tenant, đơn vị, cán bộ, phân công
# khớp các tài khoản thử của realm mission. Chạy sau migration org; chạy lại không
# sinh bản ghi trùng. Chỉ dữ liệu giả lập, xem postgres/seed/org-du-lieu-thu.sql.
#
#   ./infra/dev/scripts/seed-org.sh
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
SEED="${SCRIPT_DIR}/../postgres/seed/org-du-lieu-thu.sql"
PG_SUPERUSER="${POSTGRES_USER:-mission_admin}"
PG_SUPERPASS="${POSTGRES_PASSWORD:?Cần POSTGRES_PASSWORD trong infra/dev/.env}"
PG_DB="${POSTGRES_DB:-mission}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-mission-dev-postgres}"

# Tenant thử cũng phải có trong tenant-registry (cell DEV) để bước khám phá cell sau đăng nhập không trả 404.
# Thư viện căn cứ thử okr.basis của mission-core: bản này chưa có API ghi căn cứ, thiếu thì khung soạn mục tiêu
# (SCR-0202) không có lựa chọn "Căn cứ" bắt buộc; nạp sau migration okr của mission-core.
for seed in "${SEED}" "${SCRIPT_DIR}/../postgres/seed/registry-du-lieu-thu.sql" \
  "${SCRIPT_DIR}/../postgres/seed/okr-can-cu-thu.sql"; do
  if command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' "${POSTGRES_CONTAINER}" 2>/dev/null | grep -q true; then
    docker exec -i -e PGPASSWORD="${PG_SUPERPASS}" "${POSTGRES_CONTAINER}" \
      psql -q -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${PG_DB}" < "${seed}"
  else
    PGPASSWORD="${PG_SUPERPASS}" psql -q -v ON_ERROR_STOP=1 --host "${DB_HOST:-127.0.0.1}" --port "${POSTGRES_PORT:-35432}" \
      --username "${PG_SUPERUSER}" --dbname "${PG_DB}" < "${seed}"
  fi
done
echo "Đã nạp dữ liệu thử org-service, tenant-registry và căn cứ OKR cho tenant d0000000-0000-4000-8000-000000000001."
