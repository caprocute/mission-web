-- Dữ liệu thử của kiểm thử vtctl (INF-26): một tenant bridge đang vận hành ở CELL-G1 (cell của seed dev). Giá trị
-- giả, mã tenant thử; tác nhân hệ thống cố định như seed cell dev. Chạy lại được.
INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, created_by, updated_by, flows, kek_ref)
VALUES ('0192f1a2-3b4c-7d5e-8f60-718293a4b5c6', 'thu_van_hanh', 'Tỉnh thử vận hành', 'CHINH_QUYEN', 'BRIDGE', 'DANG_VAN_HANH',
        5000, '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', ARRAY['NHA_NUOC']::varchar(12)[],
        'kv/cell-g1/kek/thu_van_hanh')
ON CONFLICT (id) DO NOTHING;
INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, created_by, updated_by, tenancy_model, data_region)
VALUES ('0192f1a2-3b4c-7d5e-8f60-0000000000c1', '0192f1a2-3b4c-7d5e-8f60-718293a4b5c6', '0192f1a2-0000-7c00-8a00-00000000c0b1',
        'td_thu', now() - interval '1 day', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000',
        'bridge', 'HOALAC')
ON CONFLICT (id) DO NOTHING;
