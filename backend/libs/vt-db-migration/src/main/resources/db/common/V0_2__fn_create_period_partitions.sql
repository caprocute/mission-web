-- NEN-033 · ADR-07, ADR-22 · TL-03 "Phân vùng", "Bảo mật theo dòng" (đoạn phân vùng con L704),
-- "Ứng dụng không có quyền DDL" (L185), "Bố trí theo mô hình pool" (L667).
--
-- fn_create_period_partitions(p_parent, p_period_bucket [, p_hash_partitions])
--   Tạo phân vùng LIST cho một period_bucket (năm*10 + quý, năm*10 cho dữ liệu năm) của bảng cha
--   phân vùng LIST (period_bucket). Ở CSDL pool chia tiếp 16 phân vùng con HASH (tenant_id).
--   Trên mọi phân vùng mới: thu hồi quyền trực tiếp của vai trò ứng dụng, bật ENABLE + FORCE RLS và
--   chính sách rls_<phân vùng>__tenant như bảng cha (bảo vệ hai lớp). Gọi lại không lỗi, trả số
--   phân vùng mới tạo. Không có phân vùng mặc định: chèn kỳ chưa có phân vùng sẽ lỗi.
--
-- Giả định (TL-03 hẹn định nghĩa ở chương 14 nhưng không có; suy luận):
--   * Tên phân vùng: <bảng>_p<period_bucket>, phân vùng con băm: <bảng>_p<period_bucket>_h<nn>.
--   * Mô hình thuê bao đọc từ tham số MỨC CSDL app.tenancy_model trong pg_db_role_setting
--     (DatabaseBootstrap --tenancy=pool), không đọc biến phiên để vai trò ứng dụng không đổi được.
--     p_hash_partitions ghi đè: 0 = không chia băm, NULL = theo mô hình CSDL (pool 16, khác 0).
--   * Hàm SECURITY DEFINER do mig_<schema> sở hữu; chỉ nhận bảng cha thuộc chính schema này;
--     svc_<schema> chỉ có EXECUTE (quyền mặc định của NEN-031).
CREATE FUNCTION ${flyway:defaultSchema}.fn_create_period_partitions(
        p_parent regclass,
        p_period_bucket int,
        p_hash_partitions int DEFAULT NULL)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_schema   text;
    v_table    text;
    v_part     text;
    v_leaf     text;
    v_hash     int;
    v_model    text;
    v_year     int;
    v_quarter  int;
    v_created  int := 0;
    v_rel      text;
BEGIN
    SELECT n.nspname, c.relname INTO v_schema, v_table
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = p_parent;
    IF v_schema IS DISTINCT FROM '${flyway:defaultSchema}' THEN
        RAISE EXCEPTION 'Chỉ tạo phân vùng cho bảng thuộc schema ${flyway:defaultSchema}, nhận %.%', v_schema, v_table;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table pt
          JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
         WHERE pt.partrelid = p_parent AND pt.partstrat = 'l' AND pt.partnatts = 1
           AND a.attname = 'period_bucket') THEN
        RAISE EXCEPTION 'Bảng %.% không phân vùng LIST theo period_bucket', v_schema, v_table;
    END IF;
    v_year := p_period_bucket / 10;
    v_quarter := p_period_bucket % 10;
    IF p_period_bucket IS NULL OR v_year NOT BETWEEN 2000 AND 2999 OR v_quarter NOT BETWEEN 0 AND 4 THEN
        RAISE EXCEPTION 'period_bucket % không hợp lệ (năm*10 + quý 1..4, hoặc năm*10 cho kỳ năm)', p_period_bucket;
    END IF;

    IF p_hash_partitions IS NULL THEN
        SELECT split_part(cfg, '=', 2) INTO v_model
          FROM pg_db_role_setting s, unnest(s.setconfig) AS cfg
         WHERE s.setdatabase = (SELECT oid FROM pg_database WHERE datname = current_database())
           AND s.setrole = 0
           AND cfg LIKE 'app.tenancy_model=%';
        v_hash := CASE WHEN v_model = 'pool' THEN 16 ELSE 0 END;
    ELSE
        v_hash := p_hash_partitions;
    END IF;
    IF v_hash < 0 OR v_hash > 64 THEN
        RAISE EXCEPTION 'Số phân vùng băm % không hợp lệ (0..64)', v_hash;
    END IF;

    v_part := v_table || '_p' || p_period_bucket;
    IF to_regclass(format('%I.%I', v_schema, v_part)) IS NULL THEN
        IF v_hash > 0 THEN
            EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES IN (%s) PARTITION BY HASH (tenant_id)',
                           v_schema, v_part, v_schema, v_table, p_period_bucket);
        ELSE
            EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES IN (%s)',
                           v_schema, v_part, v_schema, v_table, p_period_bucket);
        END IF;
        v_created := v_created + 1;
    END IF;
    IF v_hash > 0 THEN
        FOR i IN 0 .. v_hash - 1 LOOP
            v_leaf := v_part || '_h' || lpad(i::text, 2, '0');
            IF to_regclass(format('%I.%I', v_schema, v_leaf)) IS NULL THEN
                EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
                               v_schema, v_leaf, v_schema, v_part, v_hash, i);
                v_created := v_created + 1;
            END IF;
        END LOOP;
    END IF;

    -- Bảo vệ hai lớp trên mọi phân vùng của kỳ (kể cả khi gọi lại để sửa phân vùng cũ).
    FOR v_rel IN
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = v_schema AND c.relkind IN ('r', 'p')
           AND (c.relname = v_part OR c.relname LIKE v_part || '\_h%')
    LOOP
        EXECUTE format('REVOKE ALL ON %I.%I FROM PUBLIC', v_schema, v_rel);
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_' || v_schema) THEN
            EXECUTE format('REVOKE ALL ON %I.%I FROM %I', v_schema, v_rel, 'svc_' || v_schema);
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_' || v_schema) THEN
            EXECUTE format('REVOKE ALL ON %I.%I FROM %I', v_schema, v_rel, 'ro_' || v_schema);
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_' || v_schema) THEN
            EXECUTE format('REVOKE ALL ON %I.%I FROM %I', v_schema, v_rel, 'cdc_' || v_schema);
        END IF;
        EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', v_schema, v_rel);
        EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', v_schema, v_rel);
        IF NOT EXISTS (SELECT 1 FROM pg_policy
                        WHERE polrelid = format('%I.%I', v_schema, v_rel)::regclass
                          AND polname = 'rls_' || v_rel || '__tenant') THEN
            EXECUTE format(
                'CREATE POLICY %I ON %I.%I AS PERMISSIVE FOR ALL'
                ' USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)'
                ' WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
                'rls_' || v_rel || '__tenant', v_schema, v_rel);
        END IF;
    END LOOP;
    RETURN v_created;
END;
$fn$;

REVOKE EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_create_period_partitions(regclass, int, int) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_${flyway:defaultSchema}') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_create_period_partitions(regclass, int, int) TO svc_${flyway:defaultSchema}';
    END IF;
END;
$$;
