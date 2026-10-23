-- =============================================================================
-- Dữ liệu thử của tenant-registry (SVC-03) cho dev: một cell dùng chung và tenant thử của realm mission
-- (khớp claim tenant_id của tài khoản thử và org-du-lieu-thu.sql), để bước khám phá cell sau đăng nhập
-- (GET /api/v1/registry/resolve, QT-00) trả cell thay vì 404. Gốc API của cell là `/api` cùng nguồn
-- với web qua APISIX dev. Chỉ dữ liệu giả lập; chạy lại không sinh bản ghi trùng.
-- =============================================================================
BEGIN;
INSERT INTO registry.cell (id, code, name, primary_dc, tenancy_model, api_base_url, capacity_accounts, status,
                           created_by, updated_by)
VALUES ('d0000000-0000-4000-8000-0000000000c1', 'DEV', 'Cell dev dùng chung', 'HOALAC', 'POOL', '/api', 100000,
        'HOAT_DONG', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, flows, kek_ref,
                             created_by, updated_by)
VALUES ('d0000000-0000-4000-8000-000000000001', 'thu_dev', 'Đơn vị thử nghiệm (dev)', 'CHINH_QUYEN', 'POOL',
        'DANG_VAN_HANH', 10000, ARRAY['DANG', 'NHA_NUOC']::varchar(12)[], 'dev/gia-lap',
        '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, tenancy_model, data_region,
                                  created_by, updated_by)
SELECT 'd0000000-0000-4000-8000-0000000000c2', 'd0000000-0000-4000-8000-000000000001',
       'd0000000-0000-4000-8000-0000000000c1', 'mission', '2026-01-01T00:00:00+07:00', 'pool', 'VN',
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
WHERE NOT EXISTS (SELECT 1 FROM registry.tenant_cell WHERE tenant_id = 'd0000000-0000-4000-8000-000000000001');
COMMIT;
