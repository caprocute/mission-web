#!/usr/bin/env bash
# =============================================================================
# Tạo trước phân vùng theo kỳ (period_bucket) cho mọi bảng nghiệp vụ trên CSDL dev (NEN-033, TL-03 "Phân vùng").
#
# Bảng nghiệp vụ phân vùng LIST theo period_bucket (năm*10 + quý, năm*10 cho dữ liệu năm) và không có phân vùng
# mặc định, nên chèn vào kỳ chưa có phân vùng lỗi "no partition of relation ... found for row". Hiện chưa có tác
# vụ nào gọi fn_create_period_partitions khi tạo kỳ (ghi trong data/seed/e2e/README.md), nên CSDL dev mới dựng
# không ghi được kế hoạch, nhiệm vụ hay hồ sơ nào. Script gọi hàm đó của từng schema cho mọi bảng cha phân vùng
# theo period_bucket, cho các năm NAM_TU..NAM_DEN (mặc định 2020, năm nhỏ nhất ràng buộc ck_period__bucket cho
# phép và là dải hành trình khoá kỳ QA-06 dùng, đến năm sau). Gọi lại an toàn.
#
#   ./infra/dev/scripts/tao-phan-vung-ky.sh
#   NAM_TU=2024 NAM_DEN=2027 ./infra/dev/scripts/tao-phan-vung-ky.sh
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
nam="$(date +%Y)"
NAM_TU="${NAM_TU:-2020}"
NAM_DEN="${NAM_DEN:-$((nam + 1))}"

docker exec -i mission-dev-postgres psql -v ON_ERROR_STOP=1 -q -At \
  -U "${POSTGRES_USER:-mission_admin}" -d "${POSTGRES_DB:-mission}" \
  -v nam_tu="${NAM_TU}" -v nam_den="${NAM_DEN}" <<'SQL'
SELECT set_config('tpv.nam_tu', :'nam_tu', false), set_config('tpv.nam_den', :'nam_den', false) \g /dev/null
DO $$
DECLARE
  r record;
  b int;
  moi int;
  tao int := 0;
BEGIN
  FOR r IN
    SELECT n.nspname AS sch, c.oid::regclass AS bang
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'p' AND NOT c.relispartition
       AND pg_get_partkeydef(c.oid) = 'LIST (period_bucket)'
       AND EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = n.oid AND p.proname = 'fn_create_period_partitions')
     ORDER BY 1, 2
  LOOP
    FOR b IN SELECT y * 10 + q FROM generate_series(current_setting('tpv.nam_tu')::int, current_setting('tpv.nam_den')::int) y, generate_series(0, 4) q LOOP
      EXECUTE format('SELECT %I.fn_create_period_partitions($1, $2)', r.sch) INTO STRICT moi USING r.bang, b;
      tao := tao + moi;
    END LOOP;
  END LOOP;
  RAISE NOTICE 'đã tạo % phân vùng mới', tao;
END
$$;
SQL
