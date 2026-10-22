#!/usr/bin/env bash
# DL-08 · Dựng PostgreSQL 17 cục bộ (Docker) và áp migration thật cho bộ sinh dữ liệu thử tải.
#
# Dùng đúng công cụ của NEN-030/NEN-032: DatabaseBootstrap (vai trò mig_/svc_/ro_/cdc_, pg_partman,
# app.tenancy_model) rồi MigrationRunner theo thứ tự schema; migration lấy thẳng từ thư mục
# src/main/resources của mission-core, evidence-service (không cần build các service). Schema org của
# org-service không dùng db/common (tự tạo outbox, xem PostgresSupport của org-service) nên được áp
# riêng bằng psql dưới quyền mig_org theo thứ tự phiên bản, rồi tới tệp lặp R__.
#
#   scripts/chuan-bi-csdl.sh            # dựng container datagen-pg ở cổng 55432, in DSN
#   DATAGEN_PG_PORT=55433 scripts/chuan-bi-csdl.sh
#   scripts/chuan-bi-csdl.sh --xoa      # xoá container
#
# Mật khẩu chỉ dùng cho container thử cục bộ, sinh ngẫu nhiên mỗi lần dựng, ghi ra .datagen-pg.env
# (đã nằm trong .gitignore qua mẫu .env*); không dùng cho môi trường dùng chung.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
BACKEND="$ROOT/backend"
NAME="${DATAGEN_PG_NAME:-datagen-pg}"
PORT="${DATAGEN_PG_PORT:-55432}"
IMAGE="${DATAGEN_PG_IMAGE:-postgres:17-alpine}"
DB=td_perf
SCHEMAS="keyring,config,task,product,evidence"
ENV_FILE="$HERE/../.datagen-pg.env"

if [[ "${1:-}" == "--xoa" ]]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -f "$ENV_FILE"
  exit 0
fi

PW="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
ADMIN_PW="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:5432" \
  -e POSTGRES_PASSWORD="$ADMIN_PW" -e POSTGRES_DB="$DB" "$IMAGE" \
  -c max_connections=200 -c shared_buffers=512MB -c max_wal_size=4GB >/dev/null
PARTMAN="$BACKEND/libs/vt-db-migration/src/test/resources/pg_partman"
for f in "$PARTMAN"/pg_partman.control "$PARTMAN"/pg_partman--*.sql; do
  docker cp "$f" "$NAME:/usr/local/share/postgresql/extension/$(basename "$f")"
done
for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -U postgres -d "$DB" >/dev/null 2>&1 && break
  sleep 1
done
sleep 2

CP_FILE="$(mktemp)"
mvn -q -f "$BACKEND/libs/vt-db-migration/pom.xml" dependency:build-classpath \
  -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=runtime >/dev/null
[[ -d "$BACKEND/libs/vt-db-migration/target/classes" ]] \
  || mvn -q -f "$BACKEND/pom.xml" -pl libs/vt-db-migration -am -DskipTests -Dspotbugs.skip install >/dev/null
CP="$BACKEND/libs/vt-db-migration/target/classes:$(cat "$CP_FILE")"
for m in mission-core services/org-service services/evidence-service; do
  CP="$CP:$BACKEND/$m/src/main/resources"
done
rm -f "$CP_FILE"

URL="jdbc:postgresql://127.0.0.1:$PORT/$DB"
ROLE_ENV=()
for s in ${SCHEMAS//,/ } config policy okr task product appraisal dossier oversight org_replica org scoring evidence docgen modulith keyring; do
  for p in MIG SVC RO CDC; do ROLE_ENV+=("${p}_${s^^}_PASSWORD=$PW"); done
done
env "${ROLE_ENV[@]}" PG_ADMIN_USER=postgres PG_ADMIN_PASSWORD="$ADMIN_PW" \
  java -cp "$CP" vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap \
  --db="$URL" --layout=td --tenancy=pool
env "${ROLE_ENV[@]}" java -cp "$CP" vn.viettel.okrkpi.platform.db.migration.MigrationRunner \
  --db="$URL" --schemas="$SCHEMAS"

ORG_DIR="$BACKEND/services/org-service/src/main/resources/db/migration/org"
for f in $(ls "$ORG_DIR"/V*__*.sql | sort -t V -k2 -n) "$ORG_DIR"/R__*.sql; do
  docker exec -i -e PGPASSWORD="$PW" "$NAME" psql -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U mig_org -d "$DB" \
    -c "SET search_path = org, public" -f - < "$f" >/dev/null
done

cat > "$ENV_FILE" <<ENV
DATAGEN_PG_HOST=127.0.0.1
DATAGEN_PG_PORT=$PORT
DATAGEN_PG_DB=$DB
DATAGEN_PG_ADMIN_USER=postgres
DATAGEN_PG_ADMIN_PASSWORD=$ADMIN_PW
DATAGEN_PG_ROLE_PASSWORD=$PW
ENV
echo "Đã áp migration ($SCHEMAS, org) vào $URL; thông tin kết nối ở $ENV_FILE"
