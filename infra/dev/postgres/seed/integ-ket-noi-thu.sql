-- =============================================================================
-- Kết nối nguồn nhân sự giả lập của tenant thử (HT-07, YC-PT-13, DV-13-06): CSDL CBCCVC và CSDL đảng viên trỏ tới
-- container hr-gia-lap (infra/dev/hr-gia-lap), cùng bảng ánh xạ mã nguồn sang đơn vị, chức danh, cán bộ của
-- org-du-lieu-thu.sql. Không có bí mật: nguồn giả lập không đòi mã thông báo (secret_path không có tệp).
--
-- Chạy sau migration integ và org-du-lieu-thu.sql, bằng tài khoản quản trị (bỏ qua RLS): ./infra/dev/scripts/seed-org.sh
-- Chạy lại nhiều lần không sinh bản ghi trùng (ON CONFLICT DO NOTHING trên khoá cố định).
-- =============================================================================
BEGIN;

INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code, protocol, endpoint, secret_path,
                             direction, schedule_cron, enabled)
VALUES
  ('d0000000-0000-4000-8000-0000000c0001', 'd0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   '00000000-0000-0000-0000-000000000000', 'CBCCVC', 'REST', 'http://hr-gia-lap:8080', 'dev/hr-gia-lap/cbccvc', 'VAO',
   NULL, true),
  ('d0000000-0000-4000-8000-0000000c0002', 'd0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   '00000000-0000-0000-0000-000000000000', 'DANG_VIEN', 'REST', 'http://hr-gia-lap:8080', 'dev/hr-gia-lap/dang-vien',
   'VAO', NULL, true)
ON CONFLICT DO NOTHING;

-- Mã ở nguồn → mã trong hệ thống. DON_VI: mã định danh cơ quan; CHUC_DANH: mã chức vụ; NHAN_SU: mã hồ sơ, mã đảng viên.
-- HS-0901 cố ý không có ánh xạ cán bộ (hồ sơ mới chưa có trong tổ chức) để đối soát có việc.
INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type, external_id, internal_id, valid_from, source_note)
SELECT md5('xref:' || x.connector || ':' || x.kind || ':' || x.ext)::uuid, 'd0000000-0000-4000-8000-000000000001'::uuid,
       x.connector::uuid, x.kind, x.ext, x.internal::uuid, '2000-01-01'::date, 'Dữ liệu thử dev (HT-07)'
FROM (VALUES
  ('d0000000-0000-4000-8000-0000000c0001', 'DON_VI', 'H99.01', 'd0000000-0000-4000-8000-000000000100'),
  ('d0000000-0000-4000-8000-0000000c0001', 'DON_VI', 'H99.01.01', 'd0000000-0000-4000-8000-000000000101'),
  ('d0000000-0000-4000-8000-0000000c0001', 'DON_VI', 'H99.01.02', 'd0000000-0000-4000-8000-000000000102'),
  ('d0000000-0000-4000-8000-0000000c0001', 'DON_VI', 'H99.01.03', 'd0000000-0000-4000-8000-000000000103'),
  ('d0000000-0000-4000-8000-0000000c0001', 'CHUC_DANH', 'GIAM-DOC', 'd0000000-0000-4000-8000-000000000201'),
  ('d0000000-0000-4000-8000-0000000c0001', 'CHUC_DANH', 'TRUONG-PHONG', 'd0000000-0000-4000-8000-000000000202'),
  ('d0000000-0000-4000-8000-0000000c0001', 'CHUC_DANH', 'CHUYEN-VIEN', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-0000000c0001', 'CHUC_DANH', 'KIEM-TRA-VIEN', 'd0000000-0000-4000-8000-000000000204'),
  ('d0000000-0000-4000-8000-0000000c0001', 'NHAN_SU', 'HS-0305', 'd0000000-0000-4000-8000-000000000305'),
  ('d0000000-0000-4000-8000-0000000c0001', 'NHAN_SU', 'HS-0306', 'd0000000-0000-4000-8000-000000000306'),
  ('d0000000-0000-4000-8000-0000000c0001', 'NHAN_SU', 'HS-0307', 'd0000000-0000-4000-8000-000000000307'),
  ('d0000000-0000-4000-8000-0000000c0001', 'NHAN_SU', 'HS-0311', 'd0000000-0000-4000-8000-000000000311'),
  ('d0000000-0000-4000-8000-0000000c0002', 'NHAN_SU', 'DV-0305', 'd0000000-0000-4000-8000-000000000305'),
  ('d0000000-0000-4000-8000-0000000c0002', 'NHAN_SU', 'DV-0306', 'd0000000-0000-4000-8000-000000000306')
) AS x(connector, kind, ext, internal)
ON CONFLICT DO NOTHING;

COMMIT;
