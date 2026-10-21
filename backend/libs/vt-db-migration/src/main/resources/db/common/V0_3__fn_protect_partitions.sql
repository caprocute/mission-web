-- NEN-032 · ADR-07 · TL-03 "Bảo mật theo dòng" L704 (bảo vệ hai lớp trên phân vùng con).
-- Bảo vệ mọi phân vùng con (mọi tầng, gồm phân vùng mặc định) của một bảng cha trong chính schema:
-- thu hồi quyền trực tiếp của svc_, ro_, cdc_ và PUBLIC (quyền mặc định của NEN-031 tự cấp DML cho
-- svc_ trên bảng mới do mig_ tạo, kể cả phân vùng do pg_partman tạo), rồi bật ENABLE + FORCE RLS
-- và chính sách rls_<phân vùng>__tenant nếu bảng có tenant_id. Truy cập hợp lệ luôn đi qua bảng
-- cha; quyền trên bảng cha đủ cho PostgreSQL đọc ghi phân vùng con.
-- Dùng cho bảng do pg_partman quản lý (outbox_event, idempotency_key, notify.*...): migration gọi
-- sau partman.create_parent, và fn_run_partition_maintenance gọi lại sau mỗi lần bảo trì.
-- Hàm thường (không SECURITY DEFINER), chỉ chủ sở hữu mig_<schema> gọi; gọi lại không lỗi; trả số
-- phân vùng con đã xử lý.
CREATE FUNCTION ${flyway:defaultSchema}.fn_protect_partitions(p_parent regclass)
    RETURNS int
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_schema     text := '${flyway:defaultSchema}';
    v_rel        text;
    v_has_tenant boolean;
    v_count      int := 0;
    v_role       text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.oid = p_parent AND n.nspname = v_schema AND c.relkind = 'p') THEN
        RAISE EXCEPTION 'Bảng % không phải bảng phân vùng của schema %', p_parent, v_schema;
    END IF;
    v_has_tenant := EXISTS (SELECT 1 FROM pg_attribute
                             WHERE attrelid = p_parent AND attname = 'tenant_id' AND NOT attisdropped);
    FOR v_rel IN
        SELECT c.relname
          FROM pg_partition_tree(p_parent) t
          JOIN pg_class c ON c.oid = t.relid
         WHERE t.level > 0 AND c.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('REVOKE ALL ON %I.%I FROM PUBLIC', v_schema, v_rel);
        FOREACH v_role IN ARRAY ARRAY['svc_', 'ro_', 'cdc_'] LOOP
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role || v_schema) THEN
                EXECUTE format('REVOKE ALL ON %I.%I FROM %I', v_schema, v_rel, v_role || v_schema);
            END IF;
        END LOOP;
        IF v_has_tenant THEN
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
        END IF;
        v_count := v_count + 1;
    END LOOP;
    RETURN v_count;
END
$fn$;

REVOKE EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_protect_partitions(regclass) FROM PUBLIC;
DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_${flyway:defaultSchema}') THEN
        EXECUTE format('REVOKE EXECUTE ON FUNCTION %I.fn_protect_partitions(regclass) FROM %I',
                       '${flyway:defaultSchema}', 'svc_${flyway:defaultSchema}');
    END IF;
END
$do$;
