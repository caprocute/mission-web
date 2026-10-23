-- Dữ liệu thử của tenant-registry (SVC-03) cho tenant thử của dev (DV-03-01, INF-22). Chỉ dùng ở dev.
--
-- Shell gọi GET /registry/resolve ngay sau đăng nhập để biết cell của tenant (TL-01 "Định tuyến tới cell").
-- Registry dev rỗng thì lời gọi trả 404 và trang ghi lỗi tải tài nguyên, nên hành trình chạy thật (QA-05, QA-06)
-- hỏng ở bước kiểm không có lỗi console. Dev chỉ có một cổng API nên cell CELL-DEV trỏ về chính gốc `/api` của
-- web: shell giữ nguyên gốc API sau khi tra cell. Mã tenant, mã cell là giá trị mẫu (suy luận).
-- Chạy lại an toàn (ON CONFLICT DO NOTHING); created_by/updated_by là tác nhân hệ thống cố định.
INSERT INTO registry.cell (id, code, name, primary_dc, standby_dc, tenancy_model, api_base_url, capacity_accounts,
                           status, created_by, updated_by)
VALUES ('d0000000-0000-4000-8000-00000000c0de', 'CELL-DEV', 'Cell dev dùng chung', 'TAICHO', NULL, 'POOL', '/api',
        100000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000')
ON CONFLICT (code) DO NOTHING;

INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, flows, kek_ref,
                             created_by, updated_by)
VALUES ('d0000000-0000-4000-8000-000000000001', 'thu_dev', 'Đơn vị thử nghiệm', 'DANG', 'POOL', 'DANG_VAN_HANH',
        1000, ARRAY['DANG', 'NHA_NUOC']::varchar(12)[], 'dev/khong-co-kek',
        '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, tenancy_model, data_region,
                                  created_by, updated_by)
SELECT 'd0000000-0000-4000-8000-0000000c0de1', 'd0000000-0000-4000-8000-000000000001',
       'd0000000-0000-4000-8000-00000000c0de', 'mission', '2026-01-01T00:00:00Z', 'pool', 'dev',
       '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'
WHERE NOT EXISTS (SELECT 1 FROM registry.tenant_cell WHERE tenant_id = 'd0000000-0000-4000-8000-000000000001');
