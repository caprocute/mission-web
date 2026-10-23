-- Dữ liệu thử của tenant-registry (SVC-03) cho dev: tenant thử của realm mission và seed org-service
-- (org-du-lieu-thu.sql) cùng nơi đặt hiện hành, để GET /api/v1/registry/resolve của web trả 200 thay vì 404.
-- Cell CELL-DEV có api_base_url rỗng: web giữ nguyên gốc API của config.json (APISIX dev), không đổi sang
-- tên miền cell mẫu. Chỉ dữ liệu giả lập; chạy lại không sinh bản ghi trùng. Nạp bởi scripts/seed-org.sh.
INSERT INTO registry.cell (id, code, name, primary_dc, standby_dc, tenancy_model, api_base_url, capacity_accounts, status,
                           created_by, updated_by)
VALUES ('0192f1a2-0000-7c00-8a00-00000000cde0', 'CELL-DEV', 'Cell dev dùng chung', 'HOALAC', 'PHIANAM', 'POOL', '',
        100000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000')
ON CONFLICT (code) DO NOTHING;

INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, created_by, updated_by, flows,
                             kek_ref)
VALUES ('d0000000-0000-4000-8000-000000000001', 'thu_dev', 'Đơn vị thử dev', 'CHINH_QUYEN', 'POOL', 'DANG_VAN_HANH',
        1000, '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000',
        ARRAY['DANG', 'NHA_NUOC']::varchar(12)[], 'tenant-d0000000-0000-4000-8000-000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, created_by, updated_by,
                                  tenancy_model, data_region)
SELECT 'd0000000-0000-4000-8000-0000000000c1', 'd0000000-0000-4000-8000-000000000001', c.id, 'mission',
       '2026-01-01T00:00:00+07:00', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000',
       'pool', 'VN'
FROM registry.cell c
WHERE c.code = 'CELL-DEV'
ON CONFLICT (id) DO NOTHING;
