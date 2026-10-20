-- Seed CHỈ dùng cho dev và kiểm thử (DV-03-01, suy luận: seed cho dev). Không đưa vào location Flyway của
-- staging/production. Cell và mô hình theo baseline mục 6; sức chứa thiết kế là giá trị mẫu (suy luận từ
-- ngưỡng 1,5 triệu tài khoản mỗi cell của TL-01 "Chuyển tenant giữa các cell"); URL là tên miền mẫu.
-- created_by/updated_by dùng mã tác nhân hệ thống cố định 00000000-0000-7000-8000-000000000000.
INSERT INTO registry.cell (id, code, name, primary_dc, standby_dc, tenancy_model, api_base_url, capacity_accounts, status, created_by, updated_by)
VALUES
  ('0192f1a2-0000-7c00-8a00-00000000c0d1', 'CELL-DTW', 'Cell khối các cơ quan Đảng Trung ương', 'HOALAC', 'PHIANAM', 'SILO',   'https://api.cell-dtw.okrkpi.vn', 500000,  'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c0a1', 'CELL-HCM', 'Cell Thành phố Hồ Chí Minh',             'PHIANAM', 'HOALAC',  'SILO',   'https://api.cell-hcm.okrkpi.vn', 1500000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c001', 'CELL-HN',  'Cell Hà Nội',                             'HOALAC', 'PHIANAM', 'SILO',   'https://api.cell-hn.okrkpi.vn',  1500000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c0b1', 'CELL-G1',  'Cell nhóm tỉnh 1',                        'HOALAC', 'PHIANAM', 'BRIDGE', 'https://api.cell-g1.okrkpi.vn',  1000000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c0b2', 'CELL-G2',  'Cell nhóm tỉnh 2',                        'HOALAC', 'PHIANAM', 'BRIDGE', 'https://api.cell-g2.okrkpi.vn',  1000000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c0b3', 'CELL-G3',  'Cell nhóm tỉnh 3',                        'PHIANAM', 'HOALAC', 'BRIDGE', 'https://api.cell-g3.okrkpi.vn',  1000000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c0b4', 'CELL-G4',  'Cell nhóm tỉnh 4',                        'PHIANAM', 'HOALAC', 'BRIDGE', 'https://api.cell-g4.okrkpi.vn',  1000000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000'),
  ('0192f1a2-0000-7c00-8a00-00000000c0c1', 'CELL-SH',  'Cell dùng chung cho đơn vị nhỏ',          'HOALAC', 'PHIANAM', 'POOL',   'https://api.cell-sh.okrkpi.vn',  1000000, 'HOAT_DONG', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000')
ON CONFLICT (code) DO NOTHING;
