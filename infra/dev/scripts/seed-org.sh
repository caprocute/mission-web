#!/usr/bin/env bash
# =============================================================================
# Nạp dữ liệu thử của org-service (SVC-04) cho dev: tenant, đơn vị, cán bộ, phân công
# khớp các tài khoản thử của realm mission. Chạy sau migration org; chạy lại không
# sinh bản ghi trùng. Chỉ dữ liệu giả lập, xem postgres/seed/org-du-lieu-thu.sql.
#
# Nạp SQL thẳng vào schema org không phát sự kiện, nên sau khi nạp script chạy lệnh phát lại của org-service
# (`org-republish`, hồ sơ Spring `republish`, ops/runbooks/org-republish.md) lên topic org.bootstrap.v1 để bản
# sao org_replica của mission-core và workflow-service có dữ liệu. Bỏ qua bước này bằng SEED_ORG_REPUBLISH=0
# (ví dụ khi org-service chưa dựng ảnh hoặc Kafka chưa chạy).
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

nap_sql() {
  if command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' "${POSTGRES_CONTAINER}" 2>/dev/null | grep -q true; then
    docker exec -i -e PGPASSWORD="${PG_SUPERPASS}" "${POSTGRES_CONTAINER}" \
      psql -q -At -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${PG_DB}" < "$1"
  else
    PGPASSWORD="${PG_SUPERPASS}" psql -q -v ON_ERROR_STOP=1 --host "${DB_HOST:-127.0.0.1}" --port "${POSTGRES_PORT:-35432}" \
      -At --username "${PG_SUPERUSER}" --dbname "${PG_DB}" < "$1"
  fi
}
nap_sql "${SEED}"
echo "Đã nạp dữ liệu thử org-service cho tenant d0000000-0000-4000-8000-000000000001."
# Cell và tenant thử trong tenant-registry để shell tra được cell sau đăng nhập (registry-du-lieu-thu.sql).
# Bảng registry do migration của tenant-registry tạo (deploy-backend.sh), nên chỉ nạp khi đã có.
if [[ "$(nap_sql <(echo "SELECT to_regclass('registry.tenant_cell') IS NOT NULL") 2>/dev/null | tr -d ' ')" == *t* ]]; then
  nap_sql "${SCRIPT_DIR}/../postgres/seed/registry-du-lieu-thu.sql"
  echo "Đã nạp cell CELL-DEV và tenant thử vào tenant-registry."
else
  echo "Chưa có schema registry (chưa triển khai tenant-registry): bỏ qua dữ liệu thử của registry." >&2
fi

# Phát lại toàn bộ trạng thái tổ chức của tenant thử (DV-04-08). Cần ảnh mission-dev/org-service:dev, Kafka và
# Apicurio trong mạng mission-net; hồ sơ republish đọc APICURIO_REGISTRY_URL như địa chỉ API v3.
TENANT_THU="d0000000-0000-4000-8000-000000000001"
if [[ "${SEED_ORG_REPUBLISH:-1}" != "1" ]]; then
  echo "Bỏ qua phát lại sang org_replica (SEED_ORG_REPUBLISH=${SEED_ORG_REPUBLISH})."
  exit 0
fi
if ! command -v docker >/dev/null 2>&1 || ! docker image inspect mission-dev/org-service:dev >/dev/null 2>&1; then
  echo "Chưa có ảnh mission-dev/org-service:dev: chạy deploy-backend.sh org-service rồi chạy lại script này." >&2
  exit 1
fi
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
docker compose -p mission-dev --project-directory "${INFRA_DIR}" -f "${INFRA_DIR}/docker-compose.yml" \
  --env-file "${ENV_FILE}" --profile backend run --rm --no-deps -T \
  -e SPRING_PROFILES_ACTIVE=republish \
  -e APICURIO_REGISTRY_URL=http://apicurio:8080/apis/registry/v3 \
  org-service "--tenant=${TENANT_THU}" --operator=seed-org
echo "Đã phát lại trạng thái tổ chức của tenant ${TENANT_THU} lên org.bootstrap.v1."
