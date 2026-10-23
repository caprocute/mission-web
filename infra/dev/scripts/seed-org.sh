#!/usr/bin/env bash
# =============================================================================
# Nạp dữ liệu thử của org-service (SVC-04) cho dev: tenant, đơn vị, cán bộ, phân công
# khớp các tài khoản thử của realm mission, cùng nơi đặt của tenant thử trong tenant-registry
# (postgres/seed/registry-tenant-thu.sql) và thư viện căn cứ thử của mission-core (okr-can-cu-thu.sql).
# Chạy sau migration org; chạy lại không
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

REGISTRY_SEED="${SCRIPT_DIR}/../postgres/seed/registry-tenant-thu.sql"
BASIS_SEED="${SCRIPT_DIR}/../postgres/seed/okr-can-cu-thu.sql"
if command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' "${POSTGRES_CONTAINER}" 2>/dev/null | grep -q true; then
  run_psql() {
    docker exec -i -e PGPASSWORD="${PG_SUPERPASS}" "${POSTGRES_CONTAINER}" \
      psql -q -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${PG_DB}" "$@"
  }
else
  run_psql() {
    PGPASSWORD="${PG_SUPERPASS}" psql -q -v ON_ERROR_STOP=1 --host "${DB_HOST:-127.0.0.1}" --port "${POSTGRES_PORT:-35432}" \
      --username "${PG_SUPERUSER}" --dbname "${PG_DB}" "$@"
  }
fi
run_psql < "${SEED}"
# Nơi đặt của tenant thử trong tenant-registry (khi schema registry đã migrate), để web phân giải được cell.
if [[ "$(run_psql -tA -c "select to_regclass('registry.tenant_cell') is not null")" == "t" ]]; then
  run_psql < "${REGISTRY_SEED}"
  echo "Đã nạp nơi đặt của tenant thử vào tenant-registry (cell CELL-DEV)."
fi
# Thư viện căn cứ thử của mission-core (khi schema okr đã migrate), để khung soạn mục tiêu có căn cứ để chọn.
if [[ "$(run_psql -tA -c "select to_regclass('okr.basis') is not null")" == "t" ]]; then
  run_psql < "${BASIS_SEED}"
  echo "Đã nạp thư viện căn cứ thử (okr.basis)."
fi
echo "Đã nạp dữ liệu thử org-service cho tenant d0000000-0000-4000-8000-000000000001."
