#!/usr/bin/env bash
# =============================================================================
# Tạo phân vùng LIST theo period_bucket cho mọi bảng theo kỳ (okr, task, product, appraisal, dossier,
# oversight, scoring) ứng với các kỳ đã có trong config.period, bằng hàm chuẩn <schema>.fn_create_period_partitions
# (NEN-033). Thiếu phân vùng thì mọi lệnh ghi theo kỳ (tạo nhiệm vụ, kế hoạch, mục tiêu...) lỗi 500
# "no partition of relation ... found for row". Bản dev tạm thay cho job tạo trước phân vùng khi tạo kỳ
# (CORE-08-02 mới phát PeriodOpenedEvent, chưa có bên nhận nào tạo phân vùng). Chạy lại an toàn.
#
#   ./infra/dev/scripts/tao-phan-vung-ky.sh
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
docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD:?Cần POSTGRES_PASSWORD trong infra/dev/.env}" \
  "${POSTGRES_CONTAINER:-mission-dev-postgres}" psql -q -v ON_ERROR_STOP=1 -h 127.0.0.1 \
  -U "${POSTGRES_USER:-mission_admin}" -d "${POSTGRES_DB:-mission}" <<'SQL'
DO $$
DECLARE
  r record;
  b int;
  n int := 0;
BEGIN
  FOR b IN SELECT DISTINCT period_bucket FROM config.period LOOP
    FOR r IN
      SELECT ns.nspname AS sch, c.oid::regclass AS tbl
      FROM pg_class c
      JOIN pg_namespace ns ON ns.oid = c.relnamespace
      JOIN pg_partitioned_table pt ON pt.partrelid = c.oid
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = pt.partattrs[0]
      WHERE NOT c.relispartition AND pt.partstrat = 'l' AND a.attname = 'period_bucket'
        AND EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = ns.oid AND p.proname = 'fn_create_period_partitions')
      ORDER BY 1, 2
    LOOP
      EXECUTE format('SELECT %I.fn_create_period_partitions(%L::regclass, %s)', r.sch, r.tbl::text, b);
      n := n + 1;
    END LOOP;
  END LOOP;
  RAISE NOTICE 'Đã bảo đảm % cặp (bảng, kỳ) có phân vùng', n;
END
$$;
SQL
