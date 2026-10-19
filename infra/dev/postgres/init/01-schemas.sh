#!/bin/bash
# Khởi tạo CSDL dev theo ADR-02: mỗi module/service một schema và một tài khoản riêng.
# Chạy một lần khi thư mục dữ liệu PostgreSQL còn trống.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE ROLE keycloak LOGIN PASSWORD '${KEYCLOAK_DB_PASSWORD}';
CREATE DATABASE keycloak OWNER keycloak;
CREATE DATABASE mission;
CREATE DATABASE flowable;
CREATE DATABASE ledger;
SQL

# Schema thuộc database mission: 8 module của mission-core (MOD-01..08) + org_replica
# và các service dùng chung cụm ở dev (production tách cụm theo TL-04 mục 4.5).
SCHEMAS="okr task product appraisal policy dossier oversight config org_replica org registry scoring evidence esign docgen notify integ ai apicurio"

for s in $SCHEMAS; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname mission <<SQL
CREATE ROLE ${s} LOGIN PASSWORD '${MISSION_DB_PASSWORD}';
CREATE SCHEMA ${s} AUTHORIZATION ${s};
REVOKE ALL ON SCHEMA ${s} FROM PUBLIC;
ALTER ROLE ${s} SET search_path = ${s};
SQL
done

# flowable (SVC-06) và ledger (SVC-09) là CSDL riêng theo baseline
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE ROLE flowable LOGIN PASSWORD '${MISSION_DB_PASSWORD}';
ALTER DATABASE flowable OWNER TO flowable;
CREATE ROLE ledger LOGIN PASSWORD '${MISSION_DB_PASSWORD}';
ALTER DATABASE ledger OWNER TO ledger;
-- tài khoản cho Debezium đọc WAL (outbox), chỉ có quyền replication + đọc
CREATE ROLE debezium LOGIN REPLICATION PASSWORD '${MISSION_DB_PASSWORD}';
GRANT CONNECT ON DATABASE mission TO debezium;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname mission -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
echo "Đã tạo schema: $SCHEMAS"
