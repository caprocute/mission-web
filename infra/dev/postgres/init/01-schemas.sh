#!/usr/bin/env bash
# =============================================================================
# NEN-020 · TL-03 "Tài khoản kết nối CSDL", "Ứng dụng không có quyền DDL",
# "Chiến lược phân vùng bảng lớn", ADR-02, ADR-04.
#
# Chạy tự động một lần khi volume pg_data mới khởi tạo qua
# /docker-entrypoint-initdb.d/01-schemas.sh (hoặc trong Testcontainers).
# Với volume pg_data đã tồn tại, chạy ./infra/dev/postgres/migrate-roles.sh.
# =============================================================================
set -euo pipefail

if [[ -x "/usr/local/bin/migrate-roles.sh" ]]; then
  PG_LOCAL_EXEC=1 /usr/local/bin/migrate-roles.sh
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -x "${SCRIPT_DIR}/../migrate-roles.sh" ]]; then
  PG_LOCAL_EXEC=1 "${SCRIPT_DIR}/../migrate-roles.sh"
  exit 0
fi

PG_SUPERUSER="${POSTGRES_USER:-mission_admin}"
PG_SUPERPASS="${POSTGRES_PASSWORD:-mission_dev_change_me}"
PG_DB="${POSTGRES_DB:-mission}"
ROLE_DEFAULT_PASS="${DB_ROLE_PASSWORD_DEFAULT:-${PG_SUPERPASS}}"
KEYCLOAK_PASS="${KEYCLOAK_DB_PASSWORD:-keycloak_dev_change_me}"

psql_exec() {
  local target_db="$1"
  shift
  PGPASSWORD="${PG_SUPERPASS}" psql -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${target_db}" "$@"
}

psql_exec "${PG_DB}" <<EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') THEN
    CREATE ROLE keycloak LOGIN PASSWORD '${KEYCLOAK_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'apicurio') THEN
    CREATE ROLE apicurio LOGIN PASSWORD '${ROLE_DEFAULT_PASS}'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_debezium') THEN
    CREATE ROLE cdc_debezium LOGIN PASSWORD '${ROLE_DEFAULT_PASS}'
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

bootstrap_db() {
  local db_name="$1"
  local schemas_csv="$2"
  local core_modules_csv="${3:-}"
  local tenant_schemas_csv="${4:-}"

  psql_exec "${db_name}" <<EOSQL
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
-- btree_gist cho ràng buộc EXCLUDE trộn uuid với khoảng thời gian (config.period, task, org); migration chạy bằng mig_<schema> trước khi DatabaseBootstrap (NEN-031) kịp cài.
CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA public;
CREATE SCHEMA IF NOT EXISTS partman;
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_partman') THEN
    CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;
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

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = mig_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        mig_role, role_pass
      );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = svc_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        svc_role, role_pass
      );
    END IF;
    EXECUTE format('ALTER ROLE %I SET statement_timeout = %L', svc_role, '5s');
    EXECUTE format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', svc_role, '10s');
    EXECUTE format('ALTER ROLE %I SET lock_timeout = %L', svc_role, '2s');
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', svc_role, '${db_name}', s);
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, partman, public', mig_role, '${db_name}', s);

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ro_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION',
        ro_role, role_pass
      );
    END IF;
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', ro_role, '${db_name}', s);

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = cdc_role) THEN
      EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS REPLICATION',
        cdc_role, role_pass
      );
    END IF;
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', cdc_role, '${db_name}', s);

    EXECUTE format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I, %I, %I, %I',
      '${db_name}', mig_role, svc_role, ro_role, cdc_role);
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', s, mig_role);
    EXECUTE format('ALTER SCHEMA %I OWNER TO %I', s, mig_role);
    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', s);
    EXECUTE format('GRANT USAGE, CREATE ON SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT ALL ON ALL TABLES IN SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT ALL ON ALL SEQUENCES IN SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT ALL ON ALL FUNCTIONS IN SCHEMA partman TO %I', mig_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I, %I, %I, cdc_debezium', s, svc_role, ro_role, cdc_role);
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

  FOREACH m IN ARRAY core_modules LOOP
    svc_role := 'svc_' || m;
    EXECUTE format('GRANT USAGE ON SCHEMA org_replica TO %I', svc_role);
    EXECUTE format(
      'ALTER DEFAULT PRIVILEGES FOR ROLE mig_org_replica IN SCHEMA org_replica GRANT SELECT ON TABLES TO %I',
      svc_role
    );
    EXECUTE format('GRANT USAGE ON SCHEMA modulith TO %I', svc_role);
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

  FOREACH m IN ARRAY tenant_schemas LOOP
    IF m <> 'keyring' THEN
      svc_role := 'svc_' || m;
      EXECUTE format('GRANT USAGE ON SCHEMA keyring TO %I', svc_role);
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

bootstrap_db "${PG_DB}" \
  "config,policy,okr,task,product,appraisal,dossier,oversight,org_replica,modulith,keyring,org,scoring,evidence,docgen,notify,integ,registry,esign,ai,apicurio" \
  "config,policy,okr,task,product,appraisal,dossier,oversight" \
  "config,policy,okr,task,product,appraisal,dossier,oversight,org_replica,modulith,org,scoring,evidence,docgen"

psql_exec "${PG_DB}" <<'EOSQL'
GRANT CONNECT, TEMPORARY ON DATABASE mission TO apicurio, cdc_debezium;
GRANT USAGE, CREATE ON SCHEMA apicurio TO apicurio;
ALTER ROLE apicurio IN DATABASE mission SET search_path = apicurio, public;
EOSQL

bootstrap_db "flowable" "flowable" "" ""
# V1 của workflow-service mở đầu bằng CREATE SCHEMA IF NOT EXISTS flowable; PostgreSQL kiểm quyền CREATE trên CSDL
# trước khi xét schema đã có, nên mig_flowable cần quyền này (chỉ CSDL flowable riêng của Flowable).
psql_exec "flowable" -c "GRANT CREATE ON DATABASE flowable TO mig_flowable" >/dev/null
# Debezium đọc flowable.outbox_event (vết saga sys.audit.v1, bản chiếu tiến độ khoá kỳ của MOD-08, QA-06).
psql_exec "flowable" -c "GRANT CONNECT ON DATABASE flowable TO cdc_debezium" >/dev/null
bootstrap_db "ledger" "ledger" "" ""
