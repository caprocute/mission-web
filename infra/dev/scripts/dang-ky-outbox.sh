#!/usr/bin/env bash
# =============================================================================
# NEN-020 · TL-03 "Outbox và Debezium" · wiki 18. Đăng ký Debezium Outbox cho CSDL dev `mission`.
#
# Compose dev chỉ dựng Kafka Connect (Debezium) mà chưa có connector nào, nên sự kiện ghi vào
# <schema>.outbox_event không bao giờ lên Kafka (ví dụ EVT-18 ký xong không về mission-core, kế
# hoạch đứng ở CHO_DUYET; QA-05 HT-11). Script này, chạy lại được nhiều lần:
#   1. dựng jar SMT OutboxHeadersTransform (chỉ lớp shared.outbox.connect của vt-platform-starter)
#      vào infra/dev/debezium/vt-outbox-smt/, compose gắn vào /kafka/connect/vt-outbox-smt;
#   2. tạo hoặc cập nhật publication pub_outbox (publish_via_partition_root vì outbox_event phân
#      vùng) gồm mọi bảng <schema>.outbox_event hiện có, bằng tài khoản quản trị Postgres;
#   3. ghi cấu hình connector outbox-mission theo debezium-outbox-connector.json của starter
#      (tài khoản cdc_debezium, mật khẩu đọc qua EnvVarConfigProvider, không ghi vào cấu hình).
# Chỉ bắt sự kiện mới (snapshot.mode=never). Cần: set -a; . infra/dev/.env; set +a trước khi chạy.
# =============================================================================
set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_DIR="$(cd "${DEV_DIR}/../.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-mission-dev-postgres}"
CONNECT_CONTAINER="${CONNECT_CONTAINER:-mission-dev-debezium}"
DB_NAME="${POSTGRES_DB:-mission}"
CONNECTOR="${OUTBOX_CONNECTOR_NAME:-outbox-${DB_NAME}}"
SMT_DIR="${DEV_DIR}/debezium/vt-outbox-smt"

starter_jar() {
  local j
  for j in "${STARTER_JAR:-}" \
    "${REPO_DIR}"/backend/libs/vt-platform-starter/target/vt-platform-starter-*.jar \
    "${HOME}"/.m2/repository/vn/viettel/okrkpi/vt-platform-starter/*/vt-platform-starter-*.jar; do
    [[ -n "${j}" && -f "${j}" && "${j}" != *-sources.jar && "${j}" != *-tests.jar ]] && { echo "${j}"; return; }
  done
  echo "LỖI: không thấy jar vt-platform-starter (chạy mvnw -pl libs/vt-platform-starter install hoặc đặt STARTER_JAR)" >&2
  exit 1
}

echo "==> [1/3] Dựng jar SMT OutboxHeadersTransform"
JAR="$(starter_jar)"
mkdir -p "${SMT_DIR}"
python3 -I - "${JAR}" "${SMT_DIR}/vt-outbox-smt.jar" "${SMT_DIR}/debezium-outbox-connector.json" <<'PY'
import sys, zipfile
src, out, cfg = sys.argv[1:4]
prefix = "vn/viettel/okrkpi/shared/outbox/connect/"
with zipfile.ZipFile(src) as z, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as o:
    names = [n for n in z.namelist() if n.startswith(prefix) and n.endswith(".class")]
    if not names:
        sys.exit("LỖI: jar starter không có " + prefix)
    for n in names:
        o.writestr(n, z.read(n))
    open(cfg, "wb").write(z.read("vn/viettel/okrkpi/shared/outbox/debezium-outbox-connector.json"))
PY

if ! docker exec "${CONNECT_CONTAINER}" test -f /kafka/connect/vt-outbox-smt/vt-outbox-smt.jar; then
  echo "LỖI: ${CONNECT_CONTAINER} chưa thấy jar SMT; tạo lại container: docker compose up -d debezium" >&2
  exit 1
fi
# Connect chỉ quét plugin lúc khởi động: jar mới (hoặc lần đầu) thì khởi động lại để nạp.
if ! docker exec "${CONNECT_CONTAINER}" curl -fsS localhost:8083/connector-plugins?connectorsOnly=false \
  | grep -q OutboxHeadersTransform; then
  echo "    khởi động lại ${CONNECT_CONTAINER} để nạp SMT"
  docker restart "${CONNECT_CONTAINER}" >/dev/null
  for _ in $(seq 60); do
    docker exec "${CONNECT_CONTAINER}" curl -fsS localhost:8083/connectors >/dev/null 2>&1 && break
    sleep 3
  done
fi

echo "==> [2/3] Publication pub_outbox cho mọi <schema>.outbox_event"
TABLES="$(docker exec -i "${PG_CONTAINER}" sh -c 'psql -U "$POSTGRES_USER" -d "$1" -Atq' _ "${DB_NAME}" <<'SQL'
SELECT string_agg(format('%I.%I', n.nspname, c.relname), ',' ORDER BY n.nspname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relname = 'outbox_event' AND c.relkind IN ('p', 'r') AND NOT c.relispartition;
SQL
)"
[[ -n "${TABLES}" ]] || { echo "LỖI: không thấy bảng outbox_event nào trong ${DB_NAME}" >&2; exit 1; }
docker exec -i "${PG_CONTAINER}" sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -q' _ "${DB_NAME}" <<SQL
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'pub_outbox') THEN
    EXECUTE 'ALTER PUBLICATION pub_outbox SET TABLE ${TABLES}';
  ELSE
    EXECUTE 'CREATE PUBLICATION pub_outbox FOR TABLE ${TABLES} WITH (publish_via_partition_root = true)';
  END IF;
END
\$\$;
SQL
echo "    ${TABLES}"

echo "==> [3/3] Connector ${CONNECTOR}"
CONFIG="$(python3 -I - "${SMT_DIR}/debezium-outbox-connector.json" "${DB_NAME}" "${TABLES}" <<'PY'
import json, sys
path, db, tables = sys.argv[1:4]
c = json.load(open(path))["config"]
c.update({
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "cdc_debezium",
    "database.password": "${env:PG_CDC_PASSWORD}",
    "database.dbname": db,
    "topic.prefix": "dbz-outbox-" + db,
    "slot.name": "dbz_outbox_" + db,
    "table.include.list": tables,
})
# Dev không có bảng public.debezium_heartbeat, và bản ghi nhịp tim (STRUCT) không qua EventRouter
# nên ByteArrayConverter từ chối ("Invalid schema type ... STRUCT"): tắt nhịp tim ở dev.
c.pop("heartbeat.action.query", None)
c["heartbeat.interval.ms"] = "0"
print(json.dumps(c))
PY
)"
docker exec -i "${CONNECT_CONTAINER}" curl -fsS -X PUT -H 'Content-Type: application/json' \
  --data-binary @- "localhost:8083/connectors/${CONNECTOR}/config" <<<"${CONFIG}" >/dev/null
for _ in $(seq 20); do
  STATE="$(docker exec "${CONNECT_CONTAINER}" curl -fsS "localhost:8083/connectors/${CONNECTOR}/status" \
    | python3 -I -c 'import json,sys; s=json.load(sys.stdin); print(s["connector"]["state"], *[t["state"] for t in s["tasks"]])' || true)"
  [[ "${STATE}" == "RUNNING RUNNING" ]] && break
  sleep 3
done
echo "    trạng thái: ${STATE:-không rõ}"
[[ "${STATE}" == "RUNNING RUNNING" ]]
# Vết saga của workflow-service (sys.audit.v1, gồm tiến độ saga chốt kỳ mà MOD-08 chiếu sang
# config.period_lock_step) nằm ở flowable.outbox_event của CSDL flowable riêng: đăng ký thêm
# connector outbox-flowable (QA-06).
if [[ "${DB_NAME}" == "mission" && "${OUTBOX_SKIP_FLOWABLE:-}" != "1" ]]; then
  POSTGRES_DB=flowable OUTBOX_CONNECTOR_NAME=outbox-flowable "$0"
fi
