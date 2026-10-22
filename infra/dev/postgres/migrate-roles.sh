#!/usr/bin/env bash
# =============================================================================
# NEN-020 · TL-03 "Tài khoản kết nối CSDL", "Ứng dụng không có quyền DDL",
# "Chiến lược phân vùng bảng lớn", ADR-02, ADR-04.
#
# Script bất biến (idempotent) khởi tạo hoặc nâng cấp:
#   - 4 CSDL: mission, keycloak, flowable, ledger;
#   - Tiện ích mở rộng pgcrypto (public) và pg_partman (schema partman);
#   - 21 schema trong CSDL mission (8 mô-đun mission-core, org_replica,
#     modulith, keyring, 4 microservice có dữ liệu tenant, 2 dịch vụ dùng chung,
#     cùng registry, esign, ai, apicurio);
#   - 4 tài khoản trên mỗi schema: mig_<schema>, svc_<schema>, ro_<schema>,
#     cdc_<schema> — toàn bộ đều NOBYPASSRLS, NOINHERIT, NOCREATEDB, NOCREATEROLE;
#   - Timeout bắt buộc cho svc_<schema>: statement_timeout=5s,
#     idle_in_transaction_session_timeout=10s, lock_timeout=2s;
#   - Ngoại lệ cấp quyền liên schema theo TL-03:
#       * 8 svc_<module> của mission-core chỉ có SELECT trên org_replica
#         (chỉ svc_org_replica mới có INSERT/UPDATE/DELETE);
#       * 8 svc_<module> của mission-core có SELECT/INSERT/UPDATE/DELETE trên
#         modulith (bảng modulith.event_publication);
#       * Mọi svc_<schema> nghiệp vụ có SELECT/INSERT trên keyring (bảng
#         keyring.dek; chỉ svc_keyring mới có UPDATE/DELETE).
#
# Cách chạy:
#   1) Tự động khi khởi tạo volume mới qua /docker-entrypoint-initdb.d/01-schemas.sh
#   2) Trên volume pg_data đã tồn tại (không cần xoá volume):
#        ./infra/dev/postgres/migrate-roles.sh
#      hoặc:
#        docker exec -i mission-dev-postgres /usr/local/bin/migrate-roles.sh
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

PG_SUPERUSER="${POSTGRES_USER:-mission_admin}"
PG_SUPERPASS="${POSTGRES_PASSWORD:-mission_dev_change_me}"
PG_DB="${POSTGRES_DB:-mission}"
ROLE_DEFAULT_PASS="${DB_ROLE_PASSWORD_DEFAULT:-${PG_SUPERPASS}}"
KEYCLOAK_PASS="${KEYCLOAK_DB_PASSWORD:-keycloak_dev_change_me}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-mission-dev-postgres}"

run_psql() {
  local target_db="$1"
  shift
  if [[ -n "${PG_LOCAL_EXEC:-}" ]] || [[ -f "/.dockerenv" ]]; then
    PGPASSWORD="${PG_SUPERPASS}" psql -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${target_db}" "$@"
  elif command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' "${POSTGRES_CONTAINER}" 2>/dev/null | grep -q true; then
    docker exec -i -e PGPASSWORD="${PG_SUPERPASS}" "${POSTGRES_CONTAINER}" \
      psql -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${target_db}" "$@"
  else
    PGPASSWORD="${PG_SUPERPASS}" psql -v ON_ERROR_STOP=1 \
      --host "${DB_HOST:-127.0.0.1}" \
      --port "${DB_PORT:-35432}" \
      --username "${PG_SUPERUSER}" \
      --dbname "${target_db}" "$@"
  fi
}

echo "==> [NEN-020] Đồng bộ CSDL, schema và bộ 4 tài khoản mig_/svc_/ro_/cdc_..."

# 1. Tạo các CSDL tách riêng (keycloak, flowable, ledger) và tài khoản toàn cục
run_psql "${PG_DB}" <<EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') THEN
    CREATE ROLE keycloak LOGIN PASSWORD '${KEYCLOAK_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  ELSE
    ALTER ROLE keycloak WITH LOGIN PASSWORD '${KEYCLOAK_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'apicurio') THEN
    CREATE ROLE apicurio LOGIN PASSWORD '${ROLE_DEFAULT_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  ELSE
    ALTER ROLE apicurio WITH LOGIN PASSWORD '${ROLE_DEFAULT_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_debezium') THEN
    CREATE ROLE cdc_debezium LOGIN PASSWORD '${ROLE_DEFAULT_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS REPLICATION;
  ELSE
    ALTER ROLE cdc_debezium WITH LOGIN PASSWORD '${ROLE_DEFAULT_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS REPLICATION;
  END IF;
END
\$\$;

SELECT 'CREATE DATABASE keycloak OWNER keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec

SELECT 'CREATE DATABASE flowable'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'flowable')\gexec

SELECT 'CREATE DATABASE ledger'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ledger')\gexec
EOSQL

# 2. Hàm SQL tạo tiện ích mở rộng pgcrypto + pg_partman và bộ 4 tài khoản cho danh sách schema
bootstrap_database_schemas() {
  local db_name="$1"
  local schemas_csv="$2"
  local core_modules_csv="${3:-}"
  local tenant_schemas_csv="${4:-}"

  run_psql "${db_name}" <<EOSQL
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
CREATE SCHEMA IF NOT EXISTS partman;
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_partman') THEN
    CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;
  ELSE
    RAISE NOTICE 'pg_partman chưa được cài trên host PostgreSQL này; schema partman vẫn được tạo sẵn.';
  END IF;
END
\$\$;
-- DV-14-01: pgvector cho ai.doc_chunk; extension cần superuser nên cài ở đây, migration của mig_ai chỉ kiểm.
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
    CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;
  END IF;
END
\$\$;

REVOKE ALL ON DATABASE ${db_name} FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO PUBLIC;
REVOKE ALL ON SCHEMA partman FROM PUBLIC;

DO \$\$
DECLARE
  s text;
  m text;
  mig_role text;
  svc_role text;
  ro_role  text;
  cdc_role text;
  role_pass text := '${ROLE_DEFAULT_PASS}';
  all_schemas text[] := string_to_array('${schemas_csv}', ',');
  core_modules text[] := CASE
    WHEN '${core_modules_csv}' = '' THEN ARRAY[]::text[]
    ELSE string_to_array('${core_modules_csv}', ',')
  END;
  tenant_schemas text[] := CASE
    WHEN '${tenant_schemas_csv}' = '' THEN ARRAY[]::text[]
    ELSE string_to_array('${tenant_schemas_csv}', ',')
  END;
BEGIN
  FOREACH s IN ARRAY all_schemas LOOP
    mig_role := 'mig_' || s;
    svc_role := 'svc_' || s;
    ro_role  := 'ro_'  || s;
    cdc_role := 'cdc_' || s;

    -- 1) mig_<schema>: chủ sở hữu schema, chạy Flyway DDL
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = mig_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        mig_role, role_pass
      );
    ELSE
      EXECUTE format(
        'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        mig_role, role_pass
      );
    END IF;

    -- 2) svc_<schema>: tài khoản chạy ứng dụng (DML, bị áp RLS, khoá timeout)
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = svc_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        svc_role, role_pass
      );
    ELSE
      EXECUTE format(
        'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        svc_role, role_pass
      );
    END IF;
    EXECUTE format('ALTER ROLE %I SET statement_timeout = %L', svc_role, '5s');
    EXECUTE format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', svc_role, '10s');
    EXECUTE format('ALTER ROLE %I SET lock_timeout = %L', svc_role, '2s');
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', svc_role, '${db_name}', s);
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, partman, public', mig_role, '${db_name}', s);

    -- 3) ro_<schema>: tài khoản chỉ đọc (chẩn đoán, đối soát)
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ro_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        ro_role, role_pass
      );
    ELSE
      EXECUTE format(
        'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        ro_role, role_pass
      );
    END IF;
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', ro_role, '${db_name}', s);

    -- 4) cdc_<schema>: tài khoản Debezium đọc WAL + outbox_event
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = cdc_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS REPLICATION',
        cdc_role, role_pass
      );
    ELSE
      EXECUTE format(
        'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS REPLICATION',
        cdc_role, role_pass
      );
    END IF;
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', cdc_role, '${db_name}', s);

    -- Cấp quyền kết nối CSDL
    EXECUTE format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I, %I, %I, %I',
      '${db_name}', mig_role, svc_role, ro_role, cdc_role);

    -- Tạo schema thuộc sở hữu mig_<schema> và thu hồi quyền PUBLIC
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', s, mig_role);
    EXECUTE format('ALTER SCHEMA %I OWNER TO %I', s, mig_role);
    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', s);

    -- Cấp quyền partman cho mig_<schema> để tạo phân vùng bảng lớn
    EXECUTE format('GRANT USAGE, CREATE ON SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT ALL ON ALL TABLES IN SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT ALL ON ALL SEQUENCES IN SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT ALL ON ALL FUNCTIONS IN SCHEMA partman TO %I', mig_role);

    -- Cấp quyền trên đối tượng hiện có trong schema
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I, %I, %I, cdc_debezium', s, svc_role, ro_role, cdc_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', s, svc_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', s, svc_role);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO %I', s, svc_role);
    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I', s, ro_role);
    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I, cdc_debezium', s, cdc_role);

    -- Default privileges cho các bảng/sequence/hàm do mig_<schema> tạo qua Flyway
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
      mig_role, s, svc_role
    );
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I',
      mig_role, s, svc_role
    );
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT EXECUTE ON FUNCTIONS TO %I',
      mig_role, s, svc_role
    );
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT ON TABLES TO %I',
      mig_role, s, ro_role
    );
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT ON TABLES TO %I, cdc_debezium',
      mig_role, s, cdc_role
    );
  END LOOP;

  -- Ngoại lệ 1 (TL-03): 8 svc_<module> của mission-core chỉ có SELECT trên org_replica
  -- (chỉ svc_org_replica mới có INSERT/UPDATE/DELETE trên org_replica)
  FOREACH m IN ARRAY core_modules LOOP
    svc_role := 'svc_' || m;
    EXECUTE format('GRANT USAGE ON SCHEMA org_replica TO %I', svc_role);
    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA org_replica TO %I', svc_role);
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE mig_org_replica IN SCHEMA org_replica GRANT SELECT ON TABLES TO %I',
      svc_role
    );

    -- Ngoại lệ 2 (TL-03): 8 svc_<module> của mission-core có quyền đọc/ghi modulith.event_publication
    EXECUTE format('GRANT USAGE ON SCHEMA modulith TO %I', svc_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA modulith TO %I', svc_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA modulith TO %I', svc_role);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA modulith TO %I', svc_role);
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE mig_modulith IN SCHEMA modulith GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
      svc_role
    );
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE mig_modulith IN SCHEMA modulith GRANT USAGE, SELECT ON SEQUENCES TO %I',
      svc_role
    );
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE mig_modulith IN SCHEMA modulith GRANT EXECUTE ON FUNCTIONS TO %I',
      svc_role
    );
  END LOOP;

  -- Ngoại lệ 3 (TL-03): mọi svc_<schema> nghiệp vụ có SELECT, INSERT trên keyring (bảng keyring.dek);
  -- chỉ svc_keyring mới có UPDATE, DELETE trên keyring
  FOREACH m IN ARRAY tenant_schemas LOOP
    IF m <> 'keyring' THEN
      svc_role := 'svc_' || m;
      EXECUTE format('GRANT USAGE ON SCHEMA keyring TO %I', svc_role);
      EXECUTE format('GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA keyring TO %I', svc_role);
      EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE mig_keyring IN SCHEMA keyring GRANT SELECT, INSERT ON TABLES TO %I',
        svc_role
      );
    END IF;
  END LOOP;
END
\$\$;
EOSQL
}

# 3. Khởi tạo CSDL mission với 21 schema và ngoại lệ liên schema
MISSION_ALL_SCHEMAS="config,policy,okr,task,product,appraisal,dossier,oversight,org_replica,modulith,keyring,org,scoring,evidence,docgen,notify,integ,registry,esign,ai,apicurio"
MISSION_CORE_MODULES="config,policy,okr,task,product,appraisal,dossier,oversight"
MISSION_TENANT_SCHEMAS="config,policy,okr,task,product,appraisal,dossier,oversight,org_replica,modulith,org,scoring,evidence,docgen"

bootstrap_database_schemas "${PG_DB}" "${MISSION_ALL_SCHEMAS}" "${MISSION_CORE_MODULES}" "${MISSION_TENANT_SCHEMAS}"

# Tương thích ngược cho container Apicurio Registry trong docker-compose dev
run_psql "${PG_DB}" <<'EOSQL'
GRANT CONNECT, TEMPORARY ON DATABASE mission TO apicurio, cdc_debezium;
GRANT USAGE, CREATE ON SCHEMA apicurio TO apicurio;
GRANT ALL ON ALL TABLES IN SCHEMA apicurio TO apicurio;
GRANT ALL ON ALL SEQUENCES IN SCHEMA apicurio TO apicurio;
ALTER ROLE apicurio IN DATABASE mission SET search_path = apicurio, public;
EOSQL

# 4. Khởi tạo CSDL flowable (schema flowable) và ledger (schema ledger)
bootstrap_database_schemas "flowable" "flowable" "" ""
bootstrap_database_schemas "ledger" "ledger" "" ""

echo "==> [NEN-020] Hoàn tất khởi tạo schema, pg_partman và bộ tài khoản mig_/svc_/ro_/cdc_."
