#!/usr/bin/env bash
# =============================================================================
# Tạo phân vùng theo kỳ cho mọi bảng phân vùng LIST (period_bucket) của CSDL dev (NEN-033, wiki trang 17):
# gọi <schema>.fn_create_period_partitions(bảng cha, bucket) cho các bucket của năm nay và năm sau
# (năm × 10 cho dữ liệu năm, năm × 10 + quý). Bản này chưa có bước tự tạo phân vùng khi mở kỳ, nên thiếu
# phân vùng thì mọi lệnh ghi của kỳ (nhiệm vụ, mục tiêu…) lỗi "no partition of relation". Hàm gọi lại không
# lỗi, chạy lại không tạo trùng. Chỉ dùng dev.
#
#   infra/dev/scripts/tao-phan-vung-ky.sh            # năm nay và năm sau
#   NAM="2026 2027 2028" infra/dev/scripts/tao-phan-vung-ky.sh
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
PG_SUPERPASS="${POSTGRES_PASSWORD:?Cần POSTGRES_PASSWORD trong infra/dev/.env}"
PG_DB="${POSTGRES_DB:-mission}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-mission-dev-postgres}"
NAM="${NAM:-$(date +%Y) $(($(date +%Y) + 1))}"

buckets=()
for y in ${NAM}; do
  for q in 0 1 2 3 4; do buckets+=("$((y * 10 + q))"); done
done
list="$(IFS=,; echo "${buckets[*]}")"

docker exec -i -e PGPASSWORD="${PG_SUPERPASS}" "${POSTGRES_CONTAINER}" \
  psql -q -v ON_ERROR_STOP=1 --username "${PG_SUPERUSER}" --dbname "${PG_DB}" <<SQL
DO \$\$
DECLARE
  r record;
  b int;
  n int;
  tong int := 0;
  bang int := 0;
BEGIN
  -- Bảng cha phân vùng LIST theo period_bucket ở schema có hàm tạo phân vùng của NEN-033
  FOR r IN
    SELECT nsp.nspname AS sch, p.oid::regclass AS parent
    FROM pg_class p
    JOIN pg_namespace nsp ON nsp.oid = p.relnamespace
    JOIN pg_partitioned_table pt ON pt.partrelid = p.oid
    JOIN pg_attribute a ON a.attrelid = p.oid AND a.attnum = pt.partattrs[0] AND a.attname = 'period_bucket'
    WHERE p.relkind = 'p' AND NOT p.relispartition
      AND to_regprocedure(format('%I.fn_create_period_partitions(regclass,integer,integer)', nsp.nspname)) IS NOT NULL
  LOOP
    bang := bang + 1;
    FOREACH b IN ARRAY ARRAY[${list}] LOOP
      EXECUTE format('SELECT %I.fn_create_period_partitions(%L::regclass, %s)', r.sch, r.parent, b) INTO n;
      tong := tong + n;
    END LOOP;
  END LOOP;
  RAISE NOTICE '% phân vùng mới cho % bảng, bucket ${list}', tong, bang;
END
\$\$;
SQL
