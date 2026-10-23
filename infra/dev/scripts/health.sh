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

echo
echo "==> Kiểm tra đầu cuối (profile backend, INF-22 — chỉ xanh khi bật profile):"
# APISIX trả 401 cho /api/v1/* không token: coi là đang chạy khi có phản hồi HTTP.
check "APISIX dev (35000)"        bash -c "[[ \$(curl -s -o /dev/null -w '%{http_code}' 'http://${HOST}:${APISIX_PORT:-35000}/api/v1/periods') != 000 ]]"
# Cổng quản trị của mission-core chỉ mở trên loopback của máy chủ.
check "mission-core readiness (35101)" curl -fsS "http://127.0.0.1:${MISSION_CORE_MGMT_PORT:-35101}/actuator/health/readiness"
# Service khác: trạng thái healthcheck của container khi có docker CLI, nếu không thì thử cổng TCP.
backend_check() {
  local label="$1" container="$2" port="$3"
  if command -v docker >/dev/null 2>&1 && docker inspect "${container}" >/dev/null 2>&1; then
    check "${label}" bash -c "[[ \$(docker inspect -f '{{.State.Health.Status}}' '${container}') == healthy ]]"
  else
    check "${label}" bash -c "</dev/tcp/${HOST}/${port}"
  fi
}
backend_check "mission-core (35100)"         mission-dev-mission-core         "${MISSION_CORE_PORT:-35100}"
backend_check "tenant-registry (35110)"      mission-dev-tenant-registry      "${TENANT_REGISTRY_PORT:-35110}"
backend_check "org-service (35111)"          mission-dev-org-service          "${ORG_SERVICE_PORT:-35111}"
backend_check "workflow-service (35112)"     mission-dev-workflow-service     "${WORKFLOW_SERVICE_PORT:-35112}"
backend_check "scoring-service (35113)"      mission-dev-scoring-service      "${SCORING_SERVICE_PORT:-35113}"
backend_check "document-service (35114)"     mission-dev-document-service     "${DOCUMENT_SERVICE_PORT:-35114}"
backend_check "evidence-service (35115)"     mission-dev-evidence-service     "${EVIDENCE_SERVICE_PORT:-35115}"
backend_check "esign-service (35116)"        mission-dev-esign-service        "${ESIGN_SERVICE_PORT:-35116}"
backend_check "integration-gateway (35117)"  mission-dev-integration-gateway  "${INTEGRATION_GATEWAY_PORT:-35117}"
backend_check "analytics-api (35118)"        mission-dev-analytics-api        "${ANALYTICS_API_PORT:-35118}"
backend_check "search (35119)"               mission-dev-search               "${SEARCH_SERVICE_PORT:-35119}"
backend_check "ledger-service (35120)"       mission-dev-ledger-service       "${LEDGER_SERVICE_PORT:-35120}"
backend_check "notification-service (35121)" mission-dev-notification-service "${NOTIFICATION_SERVICE_PORT:-35121}"
