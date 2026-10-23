#!/bin/sh
# Bước quét của CronJob quét vai trò CSDL (INF-27; SEC-58): chạy quet-vai-tro.sql trên mọi CSDL cho phép kết nối của
# một cụm CNPG và đếm kết nối quản trị (ket-noi-quan-tri.sql). Chỉ đọc danh mục hệ thống. Kết quả ghi vào $KET_QUA
# để container day-chi-so (Python) lọc ngoại lệ, ghi nhật ký ECS và đẩy chỉ số.
# Biến: PGHOST, PGUSER, PGPASSWORD, PGSSLROOTCERT (libpq), SCHEMAS, DICH_VU, DONG, PHA_KINH, DBA, HAN, SIEU, KET_QUA.
set -eu
: "${PGHOST:?thiếu PGHOST}"
: "${SCHEMAS:?thiếu SCHEMAS}"
KET_QUA=${KET_QUA:-/ket-qua}
SQL=${SQL:-/sql}
export PGPORT="${PGPORT:-5432}" PGSSLMODE="${PGSSLMODE:-verify-full}" PGAPPNAME=pam-quet-csdl PGCONNECT_TIMEOUT=10
mkdir -p "$KET_QUA"
: > "$KET_QUA/phat-hien.txt"
dbs=$(psql -X -At -d "${PGDATABASE:-postgres}" -c "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate ORDER BY 1")
so_db=0
for db in $dbs; do
  psql -X -q -d "$db" -v schemas="$SCHEMAS" -v dich_vu="$DICH_VU" -v dong="$DONG" -v pha_kinh="$PHA_KINH" \
    -v dba="$DBA" -v han="$HAN" -v sieu="$SIEU" -f "$SQL/quet-vai-tro.sql" | sed "s/^/$db|/" >> "$KET_QUA/phat-hien.txt"
  so_db=$((so_db + 1))
done
psql -X -q -d "${PGDATABASE:-postgres}" -v dba="$DBA" -v pha_kinh="$PHA_KINH" -f "$SQL/ket-noi-quan-tri.sql" > "$KET_QUA/ket-noi.txt"
echo "$so_db" > "$KET_QUA/so-csdl.txt"
echo "Đã quét $so_db CSDL trên $PGHOST: $(wc -l < "$KET_QUA/phat-hien.txt") dòng kết quả"
