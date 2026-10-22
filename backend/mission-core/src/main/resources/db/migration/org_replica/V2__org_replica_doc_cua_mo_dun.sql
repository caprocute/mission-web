-- CORE-04-02 · Chính sách đọc theo tenant cho svc_ của tám mô-đun mission-core.
--
-- TL-03 (L464) và RoleBootstrapPlan cấp SELECT trên org_replica cho mọi svc_<mô-đun>, nhưng chính sách RLS
-- do fn_enable_tenant_rls tạo chỉ áp cho svc_org_replica và ro_org_replica, nên với FORCE ROW LEVEL SECURITY
-- các mô-đun đọc trực tiếp (MOD-04 lập hồ sơ theo lô, hàng đợi thẩm định) thấy 0 dòng. Thêm chính sách chỉ
-- đọc, cùng điều kiện tenant (wiki trang 14 mục 59).
DO $do$
DECLARE
    v_table  text;
    v_roles  text;
BEGIN
    SELECT string_agg(quote_ident(r.rolname), ', ') INTO v_roles
      FROM pg_roles r
     WHERE r.rolname IN ('svc_config', 'svc_policy', 'svc_okr', 'svc_task', 'svc_product',
                         'svc_appraisal', 'svc_dossier', 'svc_oversight');
    IF v_roles IS NULL THEN
        RETURN;
    END IF;
    FOREACH v_table IN ARRAY ARRAY['org_unit_v', 'person_v', 'assignment_v', 'unit_closure_v'] LOOP
        EXECUTE format(
            'CREATE POLICY %I ON %I.%I AS PERMISSIVE FOR SELECT TO %s'
            ' USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            'rls_' || v_table || '__module_read', '${flyway:defaultSchema}', v_table, v_roles);
    END LOOP;
END
$do$;
