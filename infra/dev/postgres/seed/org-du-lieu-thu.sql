-- =============================================================================
-- Dữ liệu thử của org-service (SVC-04) cho môi trường dev, khớp realm mission
-- (infra/dev/keycloak/realm-mission.json): tenant, đơn vị, cán bộ và phân công
-- của các tài khoản thử, để GET /api/v1/org/me trả hồ sơ và phân công.
--
-- Chỉ là dữ liệu giả lập (YC-PT-13, DL-07): tên người, tên đơn vị đều hư cấu,
-- không có số định danh, ngày sinh hay liên hệ (không có cột C2).
--
-- Mã cố định (realm dùng cùng giá trị):
--   tenant            d0000000-0000-4000-8000-000000000001 (claim tenant_id, tenant)
--   đơn vị            …0100 cơ quan, …0101 phong-tong-hop, …0102 ban-to-chuc,
--                     …0103 uy-ban-kiem-tra (claim unit_id)
--   cán bộ            …03xx = id người dùng Keycloak (claim sub)
--
-- Chạy sau khi migration org đã chạy, bằng tài khoản quản trị (bỏ qua RLS); script phát lại trạng thái
-- tổ chức sang bản sao org_replica của mission-core và workflow-service sau khi nạp:
--   ./infra/dev/scripts/seed-org.sh
-- Chạy lại nhiều lần không sinh bản ghi trùng (ON CONFLICT DO NOTHING trên khoá cố định).
-- =============================================================================
BEGIN;

-- created_by/updated_by là tác nhân hệ thống 00000000-0000-0000-0000-000000000000.

INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('d0000000-0000-4000-8000-0000000000a1', 'd0000000-0000-4000-8000-000000000001'::uuid, 'CCVC', 'Khối cán bộ, công chức, viên chức', 'CA_HAI',
        '2000-01-01'::date, '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
ON CONFLICT DO NOTHING;

INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, subject_group_id, flows, level,
                          valid_from, created_by, updated_by)
SELECT md5('org_unit:' || u.id)::uuid, u.id::uuid, 'd0000000-0000-4000-8000-000000000001'::uuid, u.code, u.name, u.unit_type,
       'd0000000-0000-4000-8000-0000000000a1'::uuid, '{NHA_NUOC}'::varchar[], 'TINH', '2000-01-01'::date, '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid
FROM (VALUES
  ('d0000000-0000-4000-8000-000000000100', 'SO-THU', 'Sở Thử nghiệm', 'CO_QUAN'),
  ('d0000000-0000-4000-8000-000000000101', 'PHONG-TH', 'Phòng Tổng hợp', 'PHONG_BAN'),
  ('d0000000-0000-4000-8000-000000000102', 'BAN-TC', 'Ban Tổ chức', 'PHONG_BAN'),
  ('d0000000-0000-4000-8000-000000000103', 'UBKT', 'Ủy ban Kiểm tra', 'PHONG_BAN')
) AS u(id, code, name, unit_type)
ON CONFLICT DO NOTHING;

INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id, relation_type,
                                   valid_from, created_by, updated_by)
SELECT md5('org_unit_relation:' || r.child)::uuid, md5('relation:' || r.child)::uuid, 'd0000000-0000-4000-8000-000000000001'::uuid, 'CHUYEN_MON',
       'd0000000-0000-4000-8000-000000000100'::uuid, r.child::uuid, 'TRUC_THUOC', '2000-01-01'::date, '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid
FROM (VALUES
  ('d0000000-0000-4000-8000-000000000101'),
  ('d0000000-0000-4000-8000-000000000102'),
  ('d0000000-0000-4000-8000-000000000103')
) AS r(child)
ON CONFLICT DO NOTHING;

INSERT INTO org.job_title (id, tenant_id, code, name, level, valid_from, created_by, updated_by)
SELECT j.id::uuid, 'd0000000-0000-4000-8000-000000000001'::uuid, j.code, j.name, j.level, '2000-01-01'::date, '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid
FROM (VALUES
  ('d0000000-0000-4000-8000-000000000201', 'GIAM-DOC', 'Giám đốc', 1),
  ('d0000000-0000-4000-8000-000000000202', 'TRUONG-PHONG', 'Trưởng phòng', 3),
  ('d0000000-0000-4000-8000-000000000203', 'CHUYEN-VIEN', 'Chuyên viên', 5),
  ('d0000000-0000-4000-8000-000000000204', 'KIEM-TRA-VIEN', 'Kiểm tra viên', 5)
) AS j(id, code, name, level)
ON CONFLICT DO NOTHING;

-- Cán bộ và phân công chính của từng tài khoản thử (tài khoản nentang là quản trị nền tảng, không thuộc tenant
-- nào nên không có hồ sơ cán bộ).
CREATE TEMP TABLE seed_person (id uuid, username text, full_name text, unit_id uuid, title_id uuid) ON COMMIT DROP;
INSERT INTO seed_person VALUES
  ('d0000000-0000-4000-8000-000000000302', 'quantri', 'Hoàng Văn Quản Trị',
   'd0000000-0000-4000-8000-000000000101', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-000000000303', 'quantri2', 'Lê Văn Hai',
   'd0000000-0000-4000-8000-000000000101', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-000000000304', 'chinhsach', 'Đỗ Thị Chính Sách',
   'd0000000-0000-4000-8000-000000000101', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-000000000305', 'lanhdao', 'Lê Văn Lãnh Đạo',
   'd0000000-0000-4000-8000-000000000101', 'd0000000-0000-4000-8000-000000000201'),
  ('d0000000-0000-4000-8000-000000000306', 'quanly', 'Trần Thị Quản Lý',
   'd0000000-0000-4000-8000-000000000101', 'd0000000-0000-4000-8000-000000000202'),
  ('d0000000-0000-4000-8000-000000000307', 'canbo', 'Nguyễn Văn Cán Bộ',
   'd0000000-0000-4000-8000-000000000101', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-000000000308', 'thamdinh', 'Phạm Thị Thẩm Định',
   'd0000000-0000-4000-8000-000000000102', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-000000000309', 'quyetdinh', 'Vũ Văn Quyết Định',
   'd0000000-0000-4000-8000-000000000102', 'd0000000-0000-4000-8000-000000000202'),
  ('d0000000-0000-4000-8000-000000000310', 'duyet2', 'Bùi Thị Duyệt Hai',
   'd0000000-0000-4000-8000-000000000102', 'd0000000-0000-4000-8000-000000000203'),
  ('d0000000-0000-4000-8000-000000000311', 'kiemtra', 'Ngô Văn Kiểm Tra',
   'd0000000-0000-4000-8000-000000000103', 'd0000000-0000-4000-8000-000000000204');

-- Tài khoản thử thuộc diện đánh giá là đảng viên giả lập: luồng DANG chỉ lập hồ sơ cho đảng viên, còn luồng
-- NHA_NUOC giữ CHUA_LAP tới khi EVT-10 mang điểm thành phần (wiki trang 14 mục 59), nên hành trình QT-09 (QA-06)
-- trên dev đi theo luồng DANG.
CREATE TEMP TABLE seed_party_member (username text) ON COMMIT DROP;
INSERT INTO seed_party_member VALUES ('canbo'), ('quanly'), ('lanhdao'), ('thamdinh'), ('kiemtra');

INSERT INTO org.person (version_id, id, tenant_id, full_name, is_party_member, employment_status, valid_from,
                        created_by, updated_by)
SELECT md5('person:' || p.id)::uuid, p.id, 'd0000000-0000-4000-8000-000000000001'::uuid, p.full_name,
       p.username IN (SELECT username FROM seed_party_member), 'DANG_CONG_TAC', '2000-01-01'::date,
       '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid
FROM seed_person p
ON CONFLICT DO NOTHING;

-- CSDL dev đã nạp trước khi có cột trên: đánh dấu lại, chỉ đổi dòng còn false (chạy lại không đổi gì).
UPDATE org.person o
   SET is_party_member = true
  FROM seed_person p
 WHERE o.tenant_id = 'd0000000-0000-4000-8000-000000000001'::uuid AND o.id = p.id
   AND p.username IN (SELECT username FROM seed_party_member) AND NOT o.is_party_member;

INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, branch,
                            valid_from, created_by, updated_by)
SELECT md5('assignment-version:' || p.id)::uuid, md5('assignment:' || p.id)::uuid, 'd0000000-0000-4000-8000-000000000001'::uuid, p.id, p.unit_id,
       p.title_id, 'CHINH', 'CHINH_QUYEN', '2000-01-01'::date, '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid
FROM seed_person p
ON CONFLICT DO NOTHING;

COMMIT;
