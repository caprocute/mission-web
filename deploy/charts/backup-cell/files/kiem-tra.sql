-- Truy vấn kiểm một CSDL của cụm khôi phục thử (INF-17, restore-check.sh). Biến psql: kiem_fk (true|false), nguong,
-- ti_le. Chạy bằng postgres qua socket cục bộ của pod (siêu người dùng nên không bị RLS lọc dòng). Lỗi nào cũng
-- RAISE EXCEPTION để psql (ON_ERROR_STOP) trả mã khác 0.
SET statement_timeout = 0;
SET vt.kiem_fk = :'kiem_fk';
SET vt.nguong = :'nguong';
SET vt.ti_le = :'ti_le';
DO $$
DECLARE
  r record;
  v_dem bigint;
  v_mo_coi boolean;
  v_loi int := 0;
  v_fk int := 0;
  v_bang int := 0;
BEGIN
  -- 1. Ràng buộc khoá ngoại: không còn ràng buộc chưa xác thực, và không có dòng con mồ côi (MATCH SIMPLE: chỉ xét
  -- dòng có đủ cột khoá khác NULL). Ràng buộc kế thừa trên phân vùng (conparentid <> 0) đã được bảng cha bao.
  SELECT count(*) INTO v_dem FROM pg_constraint WHERE contype = 'f' AND NOT convalidated;
  IF v_dem > 0 THEN
    RAISE WARNING 'có % khoá ngoại chưa xác thực', v_dem;
    v_loi := v_loi + 1;
  END IF;
  IF current_setting('vt.kiem_fk')::boolean THEN
    FOR r IN
      SELECT c.conname, c.conrelid::regclass AS con, c.confrelid::regclass AS cha,
             (SELECT string_agg(format('c.%I', a.attname), ', ' ORDER BY k.n)
                FROM unnest(c.conkey) WITH ORDINALITY k(attnum, n)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS cot_con,
             (SELECT string_agg(format('p.%I', a.attname), ', ' ORDER BY k.n)
                FROM unnest(c.confkey) WITH ORDINALITY k(attnum, n)
                JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS cot_cha
      FROM pg_constraint c
      JOIN pg_namespace s ON s.oid = c.connamespace
      WHERE c.contype = 'f' AND c.convalidated AND c.conparentid = 0
        AND s.nspname NOT IN ('pg_catalog', 'information_schema')
    LOOP
      EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %s c WHERE (%s) IS NOT NULL AND NOT EXISTS (SELECT 1 FROM %s p WHERE (%s) = (%s)))',
        r.con, r.cot_con, r.cha, r.cot_cha, r.cot_con) INTO v_mo_coi;
      v_fk := v_fk + 1;
      IF v_mo_coi THEN
        RAISE WARNING 'khoá ngoại % của % có dòng không trỏ tới %', r.conname, r.con, r.cha;
        v_loi := v_loi + 1;
      END IF;
    END LOOP;
  END IF;
  -- 2. Đếm dòng so thống kê: bảng (lá) có reltuples từ ngưỡng trở lên phải còn ít nhất ti_le × reltuples dòng; chặn
  -- trường hợp bản nền hoặc WAL thiếu làm mất dữ liệu mà PostgreSQL vẫn khởi động được.
  FOR r IN
    SELECT c.oid::regclass AS bang, c.reltuples
    FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
    WHERE c.relkind = 'r' AND c.reltuples >= current_setting('vt.nguong')::real
      AND s.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  LOOP
    EXECUTE format('SELECT count(*) FROM %s', r.bang) INTO v_dem;
    v_bang := v_bang + 1;
    IF v_dem < r.reltuples * current_setting('vt.ti_le')::real THEN
      RAISE WARNING 'bảng % còn % dòng, thống kê ghi khoảng %', r.bang, v_dem, r.reltuples::bigint;
      v_loi := v_loi + 1;
    END IF;
  END LOOP;
  RAISE NOTICE 'CSDL %: % khoá ngoại, % bảng đối chiếu thống kê, % lỗi', current_database(), v_fk, v_bang, v_loi;
  IF v_loi > 0 THEN
    RAISE EXCEPTION 'CSDL % không đạt kiểm tra sau khôi phục (% lỗi)', current_database(), v_loi;
  END IF;
END
$$;
