#!/usr/bin/env bash
# =============================================================================
# Kiểm tra sức khoẻ các dịch vụ hạ tầng dev đang chạy (hỗ trợ cả chạy trực tiếp
# trên máy chủ Docker hoặc qua cổng hầm SSH từ máy trạm).
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

HOST="${DEV_HOST:-127.0.0.1}"

check() {
  local name="$1"; shift
  printf "  %-24s " "${name}"
  if "$@" >/dev/null 2>&1; then
    echo "OK"
  else
    echo "CHƯA CHẠY / LỖI"
  fi
}

echo "==> Trạng thái container mission-dev (nếu chạy trên máy chủ Docker):"
if command -v docker >/dev/null 2>&1; then
  docker ps --filter "name=mission-dev-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" || true
else
  echo "  (không có docker CLI cục bộ; kiểm tra qua cổng mạng ${HOST})"
fi
echo

echo "==> Kiểm tra đầu cuối (profile core):"
if command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' mission-dev-postgres 2>/dev/null | grep -q true; then
  check "PostgreSQL 17 (35432)"     docker exec mission-dev-postgres pg_isready -U "${POSTGRES_USER:-mission_admin}" -d "${POSTGRES_DB:-mission}"
else
  check "PostgreSQL 17 (35432)"     bash -c "</dev/tcp/${HOST}/${POSTGRES_PORT:-35432}"
fi
check "PgBouncer (35433)"         bash -c "</dev/tcp/${HOST}/${PGBOUNCER_PORT:-35433}"
if command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' mission-dev-valkey 2>/dev/null | grep -q true; then
  check "Valkey 8 (35379)"          docker exec mission-dev-valkey valkey-cli -a "${VALKEY_PASSWORD:-}" ping
else
  check "Valkey 8 (35379)"          bash -c "</dev/tcp/${HOST}/${VALKEY_PORT:-35379}"
fi
check "Keycloak 26 (35080)"       curl -fsS "http://${HOST}:${KEYCLOAK_PORT:-35080}/realms/mission"

echo
echo "==> Kiểm tra đầu cuối (profile security):"
check "OPA 1.x (35181)"           curl -fsS "http://${HOST}:${OPA_PORT:-35181}/health"
check "OpenBao 2.x (35820)"       curl -fsS "http://${HOST}:${OPENBAO_PORT:-35820}/v1/sys/health"

echo
echo "==> Kiểm tra đầu cuối (profile events / search / obs — chỉ xanh khi bật profile):"
check "Apicurio 3.x (35081)"      curl -fsS "http://${HOST}:${APICURIO_PORT:-35081}/apis/registry/v3/system/info"
check "Debezium Connect (35083)"  curl -fsS "http://${HOST}:${DEBEZIUM_PORT:-35083}/connectors"
check "MinIO (35900)"             curl -fsS "http://${HOST}:${MINIO_API_PORT:-35900}/minio/health/live"
check "OpenSearch (35200)"        curl -fsS "http://${HOST}:${OPENSEARCH_PORT:-35200}/_cluster/health"
check "Grafana (35300)"           curl -fsS "http://${HOST}:${GRAFANA_PORT:-35300}/api/health"
check "Prometheus (35990)"        curl -fsS "http://${HOST}:${PROMETHEUS_PORT:-35990}/-/healthy"
check "Tempo (35320)"             curl -fsS "http://${HOST}:${TEMPO_PORT:-35320}/ready"
check "Loki (35310)"              curl -fsS "http://${HOST}:${LOKI_PORT:-35310}/ready"
