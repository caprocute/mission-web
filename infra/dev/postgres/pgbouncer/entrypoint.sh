#!/bin/sh
# =============================================================================
# NEN-020 · NEN-033: Sinh /tmp/userlist.txt động từ biến môi trường cho toàn bộ
# bộ tài khoản mig_<schema>, svc_<schema>, ro_<schema>, cdc_<schema> trước khi
# khởi chạy PgBouncer ở cổng 6432.
# =============================================================================
set -eu

ADMIN_USER="${POSTGRES_USER:-mission_admin}"
ADMIN_PASS="${POSTGRES_PASSWORD:-mission_dev_change_me}"
ROLE_PASS="${DB_ROLE_PASSWORD_DEFAULT:-${ADMIN_PASS}}"
KEYCLOAK_PASS="${KEYCLOAK_DB_PASSWORD:-keycloak_dev_change_me}"
USERLIST_FILE="/tmp/userlist.txt"

cat > "${USERLIST_FILE}" <<EOF
"${ADMIN_USER}" "${ADMIN_PASS}"
"keycloak" "${KEYCLOAK_PASS}"
"apicurio" "${ROLE_PASS}"
"cdc_debezium" "${ROLE_PASS}"
EOF

SCHEMAS="config policy okr task product appraisal dossier oversight org_replica modulith keyring org scoring evidence docgen notify integ registry esign ai apicurio flowable ledger"
for s in ${SCHEMAS}; do
  echo "\"mig_${s}\" \"${ROLE_PASS}\"" >> "${USERLIST_FILE}"
  echo "\"svc_${s}\" \"${ROLE_PASS}\"" >> "${USERLIST_FILE}"
  echo "\"ro_${s}\" \"${ROLE_PASS}\"" >> "${USERLIST_FILE}"
  echo "\"cdc_${s}\" \"${ROLE_PASS}\"" >> "${USERLIST_FILE}"
done

chmod 600 "${USERLIST_FILE}"
exec pgbouncer /etc/pgbouncer/pgbouncer.ini
