-- NEN-033 · ADR-07, ADR-22 · TL-03 "Bảo mật theo dòng (Row-Level Security)".
-- Hàm chuẩn bật RLS cho một bảng có tenant_id: ENABLE + FORCE và chính sách rls_<bảng>__tenant
-- cho svc_<schema>, ro_<schema>. Migration của mọi schema gọi hàm này thay vì tự viết chính sách:
--   SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task');
--
-- Biểu thức dùng nullif(current_setting('app.tenant_id', true), '')::uuid theo TL-01 L2700-2712
-- (TL-03 L693 bỏ nullif): chuỗi rỗng do ConnectionPreparer đặt khi thiếu tenant không làm lỗi ép
-- kiểu mà cho NULL, tức "mặc định từ chối" (suy luận, NEN-033 mục 3).
-- Tên chính sách theo quy ước đặt tên CSDL của TL-03 (rls_<bảng>__tenant), không theo
-- "tenant_isolation" của TL-01.
CREATE FUNCTION ${flyway:defaultSchema}.fn_enable_tenant_rls(p_table regclass)
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_schema text;
    v_table  text;
    v_policy text;
    v_svc    text;
    v_ro     text;
BEGIN
    SELECT n.nspname, c.relname INTO v_schema, v_table
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = p_table;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute
                    WHERE attrelid = p_table AND attname = 'tenant_id' AND NOT attisdropped) THEN
        RAISE EXCEPTION 'Bảng %.% không có cột tenant_id, không bật RLS theo tenant được', v_schema, v_table;
    END IF;
    v_svc := 'svc_' || v_schema;
    v_ro  := 'ro_' || v_schema;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_svc)
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_ro) THEN
        RAISE EXCEPTION 'Thiếu vai trò % hoặc %; chạy DatabaseBootstrap (NEN-031) trước migration', v_svc, v_ro;
    END IF;
    v_policy := 'rls_' || v_table || '__tenant';
    EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', v_schema, v_table);
    EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', v_schema, v_table);
    IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = p_table AND polname = v_policy) THEN
        EXECUTE format(
            'CREATE POLICY %I ON %I.%I AS PERMISSIVE FOR ALL TO %I, %I'
            ' USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)'
            ' WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            v_policy, v_schema, v_table, v_svc, v_ro);
    END IF;
END;
$fn$;

-- Chỉ chủ sở hữu (mig_<schema>) dùng hàm này trong migration.
REVOKE EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_enable_tenant_rls(regclass) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_${flyway:defaultSchema}') THEN
        EXECUTE 'REVOKE EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_enable_tenant_rls(regclass) FROM svc_${flyway:defaultSchema}';
    END IF;
END;
$$;
