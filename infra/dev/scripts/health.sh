#!/bin/bash
# Kiểm tra nhanh trạng thái hạ tầng dev sau khi `docker compose up`.
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
echo
check() { printf '%-12s ' "$1"; if eval "$2" >/dev/null 2>&1; then echo OK; else echo "CHƯA SẴN SÀNG"; fi; }
check postgres  "docker compose exec -T postgres pg_isready -U ${PG_SUPERUSER:-postgres}"
check valkey    "docker compose exec -T valkey valkey-cli -a '$VALKEY_PASSWORD' ping"
check keycloak  "curl -fsS http://127.0.0.1:${KEYCLOAK_PORT:-35080}/realms/mission/.well-known/openid-configuration"
check minio     "curl -fsS http://127.0.0.1:${MINIO_API_PORT:-35900}/minio/health/live"
check kafka     "docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"
check apicurio  "curl -fsS http://127.0.0.1:${APICURIO_PORT:-35081}/apis/registry/v3/system/info"
check connect   "curl -fsS http://127.0.0.1:${CONNECT_PORT:-35083}/connectors"
check opensearch "curl -fsS http://127.0.0.1:${OPENSEARCH_PORT:-35920}"
check clickhouse "curl -fsS http://127.0.0.1:${CLICKHOUSE_HTTP_PORT:-35123}/ping"
check grafana   "curl -fsS http://127.0.0.1:${GRAFANA_PORT:-35300}/api/health"
check gateway   "curl -fsS -o /dev/null http://127.0.0.1:${GATEWAY_PORT:-35000}/"
