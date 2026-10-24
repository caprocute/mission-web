-- SINH TỰ ĐỘNG bởi data/seed/da-tenant/sinh_seed.py từ data/seed/da-tenant/du_lieu.py, không sửa tay.
-- Bộ dữ liệu thử đa tenant cho dev (giả lập, YC-PT-13): ba tenant hư cấu của tỉnh giả định Phú Lâm
-- (khối Đảng, khối chính quyền, doanh nghiệp nhà nước) trên cell DEV dùng chung, mô hình pool:
-- tenant-registry, nhóm đối tượng, đơn vị và quan hệ, chức danh, cán bộ, phân công, căn cứ OKR,
-- danh mục công việc. Không có số định danh, ngày sinh, liên hệ (không có cột C2).
-- Nạp bằng tài khoản quản trị (bỏ qua RLS) sau migration của tenant-registry, org-service, mission-core:
--   ./infra/dev/scripts/seed-da-tenant.sh
-- Chạy lại không sinh bản ghi trùng (ON CONFLICT DO NOTHING trên khoá cố định).
BEGIN;

-- ===== Đảng bộ tỉnh Phú Lâm (e1000000-0000-4000-8000-000000000001) =====
INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, flows, kek_ref,
                             created_by, updated_by)
VALUES ('e1000000-0000-4000-8000-000000000001', 'dang_bo_tinh_phu_lam', 'Đảng bộ tỉnh Phú Lâm', 'DANG', 'POOL', 'DANG_VAN_HANH', 10000, ARRAY['DANG']::varchar(12)[], 'dev/gia-lap', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;
INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, tenancy_model,
                                  data_region, created_by, updated_by)
SELECT 'e1000000-0000-4000-8000-0000000000c2', 'e1000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000c1', 'mission', '2026-01-01T00:00:00+07:00', 'pool', 'VN', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
WHERE NOT EXISTS (SELECT 1 FROM registry.tenant_cell WHERE tenant_id = 'e1000000-0000-4000-8000-000000000001');
INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('e1000000-0000-4000-8000-0000000000a1', 'e1000000-0000-4000-8000-000000000001', 'K2', 'Cấp ủy, cơ quan Đảng cấp tỉnh', 'DANG', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;
INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('e1000000-0000-4000-8000-0000000000a2', 'e1000000-0000-4000-8000-000000000001', 'K3', 'Cấp ủy, tổ chức đảng cấp cơ sở', 'DANG', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;
INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, level, subject_group_id, flows,
                          valid_from, created_by, updated_by)
SELECT md5('org_unit:' || v.id)::uuid, v.id::uuid, 'e1000000-0000-4000-8000-000000000001', v.code, v.name, v.unit_type, v.level,
       v.sg::uuid, ARRAY['DANG']::varchar(12)[], '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e1000000-0000-4000-8000-000000000100', 'TU-PL', 'Tỉnh ủy Phú Lâm', 'TO_CHUC_DANG', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000101', 'VPTU', 'Văn phòng Tỉnh ủy', 'CO_QUAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000102', 'BTCTU', 'Ban Tổ chức Tỉnh ủy', 'CO_QUAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000103', 'UBKTTU', 'Ủy ban Kiểm tra Tỉnh ủy', 'CO_QUAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000104', 'BTGDVTU', 'Ban Tuyên giáo và Dân vận Tỉnh ủy', 'CO_QUAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000105', 'BTCTU-PCB', 'Phòng Cán bộ', 'PHONG_BAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000106', 'BTCTU-PTCDV', 'Phòng Tổ chức – Đảng viên', 'PHONG_BAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000107', 'VPTU-PTH', 'Phòng Tổng hợp', 'PHONG_BAN', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000108', 'DUCQD', 'Đảng ủy các cơ quan Đảng tỉnh', 'TO_CHUC_DANG', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-000000000109', 'CB-BTCTU', 'Chi bộ Ban Tổ chức Tỉnh ủy', 'CHI_BO', 'TINH', 'e1000000-0000-4000-8000-0000000000a1'),
  ('e1000000-0000-4000-8000-00000000010a', 'DUX-TL', 'Đảng ủy xã Tân Lập', 'TO_CHUC_DANG', 'XA', 'e1000000-0000-4000-8000-0000000000a2'),
  ('e1000000-0000-4000-8000-00000000010b', 'CB-DH', 'Chi bộ thôn Đông Hòa', 'CHI_BO', 'XA', 'e1000000-0000-4000-8000-0000000000a2')
) AS v(id, code, name, unit_type, level, sg)
ON CONFLICT DO NOTHING;
INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id,
                                   relation_type, valid_from, created_by, updated_by)
SELECT md5('org_unit_relation:' || r.tree || ':' || r.child)::uuid, md5('relation:' || r.tree || ':' || r.child)::uuid,
       'e1000000-0000-4000-8000-000000000001', r.tree, r.parent::uuid, r.child::uuid, 'TRUC_THUOC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000101', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000102', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000103', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000104', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000102', 'e1000000-0000-4000-8000-000000000105', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000102', 'e1000000-0000-4000-8000-000000000106', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000101', 'e1000000-0000-4000-8000-000000000107', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000108', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000108', 'DANG'),
  ('e1000000-0000-4000-8000-000000000108', 'e1000000-0000-4000-8000-000000000109', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000108', 'e1000000-0000-4000-8000-000000000109', 'DANG'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-00000000010a', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-00000000010a', 'DANG'),
  ('e1000000-0000-4000-8000-00000000010a', 'e1000000-0000-4000-8000-00000000010b', 'CHUYEN_MON'),
  ('e1000000-0000-4000-8000-00000000010a', 'e1000000-0000-4000-8000-00000000010b', 'DANG')
) AS r(parent, child, tree)
ON CONFLICT DO NOTHING;
INSERT INTO org.job_title (id, tenant_id, code, name, level, is_central_managed, valid_from, created_by,
                           updated_by)
SELECT j.id::uuid, 'e1000000-0000-4000-8000-000000000001', j.code, j.name, j.level, j.tw, '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e1000000-0000-4000-8000-000000000201', 'BI-THU-TU', 'Bí thư Tỉnh ủy', 1, true),
  ('e1000000-0000-4000-8000-000000000202', 'PBT-TT', 'Phó Bí thư Thường trực Tỉnh ủy', 2, true),
  ('e1000000-0000-4000-8000-000000000203', 'TRUONG-BAN', 'Trưởng ban', 3, false),
  ('e1000000-0000-4000-8000-000000000204', 'PHO-TRUONG-BAN', 'Phó Trưởng ban', 4, false),
  ('e1000000-0000-4000-8000-000000000205', 'CHANH-VP', 'Chánh Văn phòng Tỉnh ủy', 3, false),
  ('e1000000-0000-4000-8000-000000000206', 'PHO-CN-UBKT', 'Phó Chủ nhiệm Ủy ban Kiểm tra', 4, false),
  ('e1000000-0000-4000-8000-000000000207', 'TRUONG-PHONG', 'Trưởng phòng', 5, false),
  ('e1000000-0000-4000-8000-000000000208', 'CHUYEN-VIEN', 'Chuyên viên', 7, false),
  ('e1000000-0000-4000-8000-000000000209', 'BI-THU-DU-XA', 'Bí thư Đảng ủy xã', 4, false),
  ('e1000000-0000-4000-8000-00000000020a', 'BI-THU-CB', 'Bí thư chi bộ', 6, false),
  ('e1000000-0000-4000-8000-00000000020b', 'DANG-VIEN', 'Đảng viên', 8, false)
) AS j(id, code, name, level, tw)
ON CONFLICT DO NOTHING;
INSERT INTO org.person (version_id, id, tenant_id, full_name, is_party_member, employment_status,
                        valid_from, created_by, updated_by)
SELECT md5('person:' || p.id)::uuid, p.id::uuid, 'e1000000-0000-4000-8000-000000000001', p.name, p.dv, 'DANG_CONG_TAC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e1000000-0000-4000-8000-000000000301', 'Trần Quốc Hưng', true),
  ('e1000000-0000-4000-8000-000000000302', 'Lê Thị Thu Hà', true),
  ('e1000000-0000-4000-8000-000000000303', 'Phạm Minh Đức', true),
  ('e1000000-0000-4000-8000-000000000304', 'Hà Văn Tùng', true),
  ('e1000000-0000-4000-8000-000000000305', 'Võ Thị Ngọc Lan', true),
  ('e1000000-0000-4000-8000-000000000306', 'Đặng Quang Huy', true),
  ('e1000000-0000-4000-8000-000000000307', 'Bùi Thị Hồng Nhung', true),
  ('e1000000-0000-4000-8000-000000000308', 'Hoàng Văn Nam', true),
  ('e1000000-0000-4000-8000-000000000309', 'Ngô Thị Thanh Hương', true),
  ('e1000000-0000-4000-8000-00000000030a', 'Lý Văn Khánh', true),
  ('e1000000-0000-4000-8000-00000000030b', 'Trịnh Thị Mai', true),
  ('e1000000-0000-4000-8000-00000000030c', 'Đỗ Minh Tuấn', true),
  ('e1000000-0000-4000-8000-00000000030d', 'Mai Xuân Trường', true),
  ('e1000000-0000-4000-8000-00000000030e', 'Đinh Văn Lực', true),
  ('e1000000-0000-4000-8000-00000000030f', 'Nguyễn Thị Lan', true),
  ('e1000000-0000-4000-8000-000000000310', 'Trần Văn Bảy', true)
) AS p(id, name, dv)
ON CONFLICT DO NOTHING;
INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, branch,
                            is_head, valid_from, created_by, updated_by)
SELECT md5('assignment-version:' || a.kind || ':' || a.person || ':' || a.unit)::uuid,
       md5('assignment:' || a.kind || ':' || a.person || ':' || a.unit)::uuid, 'e1000000-0000-4000-8000-000000000001', a.person::uuid,
       a.unit::uuid, a.title::uuid, a.kind, a.branch, a.head, '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e1000000-0000-4000-8000-000000000301', 'e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000201', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-000000000302', 'e1000000-0000-4000-8000-000000000100', 'e1000000-0000-4000-8000-000000000202', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-000000000303', 'e1000000-0000-4000-8000-000000000102', 'e1000000-0000-4000-8000-000000000203', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-000000000304', 'e1000000-0000-4000-8000-000000000102', 'e1000000-0000-4000-8000-000000000204', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-000000000304', 'e1000000-0000-4000-8000-000000000109', 'e1000000-0000-4000-8000-00000000020a', 'KIEM_NHIEM', 'DANG', true),
  ('e1000000-0000-4000-8000-000000000305', 'e1000000-0000-4000-8000-000000000105', 'e1000000-0000-4000-8000-000000000207', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-000000000306', 'e1000000-0000-4000-8000-000000000105', 'e1000000-0000-4000-8000-000000000208', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-000000000307', 'e1000000-0000-4000-8000-000000000106', 'e1000000-0000-4000-8000-000000000208', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-000000000308', 'e1000000-0000-4000-8000-000000000101', 'e1000000-0000-4000-8000-000000000205', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-000000000309', 'e1000000-0000-4000-8000-000000000107', 'e1000000-0000-4000-8000-000000000207', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-00000000030a', 'e1000000-0000-4000-8000-000000000107', 'e1000000-0000-4000-8000-000000000208', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-00000000030b', 'e1000000-0000-4000-8000-000000000107', 'e1000000-0000-4000-8000-000000000208', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-00000000030c', 'e1000000-0000-4000-8000-000000000107', 'e1000000-0000-4000-8000-000000000208', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-00000000030d', 'e1000000-0000-4000-8000-000000000103', 'e1000000-0000-4000-8000-000000000206', 'CHINH', 'DANG', false),
  ('e1000000-0000-4000-8000-00000000030e', 'e1000000-0000-4000-8000-00000000010a', 'e1000000-0000-4000-8000-000000000209', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-00000000030f', 'e1000000-0000-4000-8000-00000000010b', 'e1000000-0000-4000-8000-00000000020a', 'CHINH', 'DANG', true),
  ('e1000000-0000-4000-8000-000000000310', 'e1000000-0000-4000-8000-00000000010b', 'e1000000-0000-4000-8000-00000000020b', 'CHINH', 'DANG', false)
) AS a(person, unit, title, kind, branch, head)
ON CONFLICT DO NOTHING;
INSERT INTO okr.basis (id, tenant_id, created_by, updated_by, basis_kind, doc_number, title,
                       issued_by_unit_id, issued_on, valid_from)
SELECT b.id::uuid, 'e1000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', b.kind, b.num, b.title, 'e1000000-0000-4000-8000-000000000100', b.ngay::date, b.ngay::date
FROM (VALUES
  ('e1000000-0000-4000-8000-00000000b001', 'NGHI_QUYET', '05-NQ/TU', 'Nghị quyết của Ban Chấp hành Đảng bộ tỉnh về lãnh đạo thực hiện nhiệm vụ chính trị năm 2026', '2025-12-18'),
  ('e1000000-0000-4000-8000-00000000b002', 'KE_HOACH', '45-KH/TU', 'Kế hoạch kiểm điểm, đánh giá, xếp loại chất lượng tổ chức đảng, đảng viên và tập thể, cá nhân cán bộ lãnh đạo, quản lý năm 2026', '2026-10-06'),
  ('e1000000-0000-4000-8000-00000000b003', 'CHUONG_TRINH', '12-CTr/TU', 'Chương trình kiểm tra, giám sát của Ban Chấp hành Đảng bộ tỉnh năm 2026', '2026-01-12')
) AS b(id, kind, num, title, ngay)
ON CONFLICT (id) DO NOTHING;
INSERT INTO task.work_catalog (id, tenant_id, created_by, updated_by, code, name, scope, org_unit_id, version,
                               source, status, version_no)
VALUES ('e1000000-0000-4000-8000-00000000d001', 'e1000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'DM-CQTM-TU', 'Danh mục công việc các cơ quan tham mưu Tỉnh ủy', 'DON_VI', 'e1000000-0000-4000-8000-000000000100', 1, 'TU_TAO', 'HIEU_LUC', 1)
ON CONFLICT (id) DO NOTHING;
INSERT INTO task.catalog_item (id, tenant_id, created_by, updated_by, work_catalog_id, code, name,
                               product_name, unit, difficulty, standard_score, quality_standard,
                               evidence_requirement, task_group, standard_days, status)
SELECT i.id::uuid, 'e1000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'e1000000-0000-4000-8000-00000000d001', i.code, i.name, i.product, i.unit, i.difficulty, i.score, i.quality,
       i.evidence, i.task_group, i.days, 'AP_DUNG'
FROM (VALUES
  ('e1000000-0000-4000-8000-00000000d101', 'TU-01', 'Tham mưu xây dựng báo cáo định kỳ của Ban Thường vụ Tỉnh ủy', 'Báo cáo', 'Báo cáo', 3, 15.0, 'Đúng hướng dẫn, đủ số liệu, trình đúng hạn', 'Báo cáo đã trình ký', 'THEN_CHOT', 10),
  ('e1000000-0000-4000-8000-00000000d102', 'TU-02', 'Thẩm định hồ sơ nhân sự đề nghị bổ nhiệm, giới thiệu ứng cử', 'Tờ trình thẩm định', 'Hồ sơ', 4, 20.0, 'Đủ thành phần hồ sơ, đúng quy trình, có kết luận rõ', 'Tờ trình đã ký', 'THEN_CHOT', 15),
  ('e1000000-0000-4000-8000-00000000d103', 'TU-03', 'Hướng dẫn, kiểm tra việc kiểm điểm, xếp loại tổ chức đảng', 'Biên bản kiểm tra', 'Cuộc', 3, 15.0, 'Đúng kế hoạch, có biên bản và kiến nghị', 'Biên bản kiểm tra', 'THEN_CHOT', 20),
  ('e1000000-0000-4000-8000-00000000d104', 'TU-04', 'Tổng hợp tình hình kết nạp đảng viên của đảng bộ trực thuộc', 'Bảng tổng hợp', 'Báo cáo', 2, 8.0, 'Khớp số liệu báo cáo của cấp ủy trực thuộc', NULL, 'THUONG_XUYEN', 5),
  ('e1000000-0000-4000-8000-00000000d105', 'TU-05', 'Soạn thảo văn bản hành chính của cơ quan', 'Văn bản', 'Văn bản', 1, 5.0, 'Đúng thể thức theo Hướng dẫn 36-HD/VPTW', 'Văn bản đã phát hành', 'THUONG_XUYEN', 3),
  ('e1000000-0000-4000-8000-00000000d106', 'TU-06', 'Chuẩn bị tài liệu phục vụ hội nghị Ban Thường vụ Tỉnh ủy', 'Bộ tài liệu', 'Bộ', 3, 12.0, 'Đủ tài liệu, gửi đại biểu trước hai ngày làm việc', 'Danh mục tài liệu đã gửi', 'THEN_CHOT', 7)
) AS i(id, code, name, product, unit, difficulty, score, quality, evidence, task_group, days)
ON CONFLICT (id) DO NOTHING;

-- ===== Ủy ban nhân dân tỉnh Phú Lâm (e2000000-0000-4000-8000-000000000001) =====
INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, flows, kek_ref,
                             created_by, updated_by)
VALUES ('e2000000-0000-4000-8000-000000000001', 'ubnd_tinh_phu_lam', 'Ủy ban nhân dân tỉnh Phú Lâm', 'CHINH_QUYEN', 'POOL', 'DANG_VAN_HANH', 10000, ARRAY['NHA_NUOC']::varchar(12)[], 'dev/gia-lap', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;
INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, tenancy_model,
                                  data_region, created_by, updated_by)
SELECT 'e2000000-0000-4000-8000-0000000000c2', 'e2000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000c1', 'mission', '2026-01-01T00:00:00+07:00', 'pool', 'VN', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
WHERE NOT EXISTS (SELECT 1 FROM registry.tenant_cell WHERE tenant_id = 'e2000000-0000-4000-8000-000000000001');
INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('e2000000-0000-4000-8000-0000000000a1', 'e2000000-0000-4000-8000-000000000001', 'K6', 'Chính quyền địa phương cấp tỉnh', 'NHA_NUOC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;
INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('e2000000-0000-4000-8000-0000000000a2', 'e2000000-0000-4000-8000-000000000001', 'K7', 'Chính quyền địa phương cấp xã', 'NHA_NUOC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;
INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('e2000000-0000-4000-8000-0000000000a3', 'e2000000-0000-4000-8000-000000000001', 'K8', 'Đơn vị sự nghiệp công lập', 'NHA_NUOC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;
INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, level, subject_group_id, flows,
                          valid_from, created_by, updated_by)
SELECT md5('org_unit:' || v.id)::uuid, v.id::uuid, 'e2000000-0000-4000-8000-000000000001', v.code, v.name, v.unit_type, v.level,
       v.sg::uuid, ARRAY['NHA_NUOC']::varchar(12)[], '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e2000000-0000-4000-8000-000000000100', 'UBND-PL', 'Ủy ban nhân dân tỉnh Phú Lâm', 'CO_QUAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000101', 'VPUBND', 'Văn phòng Ủy ban nhân dân tỉnh', 'CO_QUAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000102', 'VPUBND-PTH', 'Phòng Tổng hợp', 'PHONG_BAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000103', 'TTPVHCC', 'Trung tâm Phục vụ hành chính công tỉnh', 'DON_VI_SU_NGHIEP', 'TINH', 'e2000000-0000-4000-8000-0000000000a3'),
  ('e2000000-0000-4000-8000-000000000104', 'SNV', 'Sở Nội vụ', 'CO_QUAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000105', 'SNV-PCCVC', 'Phòng Công chức, viên chức', 'PHONG_BAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000106', 'STC', 'Sở Tài chính', 'CO_QUAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000107', 'STC-PQLNS', 'Phòng Quản lý ngân sách', 'PHONG_BAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000108', 'SKHCN', 'Sở Khoa học và Công nghệ', 'CO_QUAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-000000000109', 'SKHCN-PCDS', 'Phòng Chuyển đổi số', 'PHONG_BAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-00000000010a', 'TTT', 'Thanh tra tỉnh', 'CO_QUAN', 'TINH', 'e2000000-0000-4000-8000-0000000000a1'),
  ('e2000000-0000-4000-8000-00000000010b', 'UBNDX-TL', 'Ủy ban nhân dân xã Tân Lập', 'XA_PHUONG', 'XA', 'e2000000-0000-4000-8000-0000000000a2')
) AS v(id, code, name, unit_type, level, sg)
ON CONFLICT DO NOTHING;
INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id,
                                   relation_type, valid_from, created_by, updated_by)
SELECT md5('org_unit_relation:' || r.tree || ':' || r.child)::uuid, md5('relation:' || r.tree || ':' || r.child)::uuid,
       'e2000000-0000-4000-8000-000000000001', r.tree, r.parent::uuid, r.child::uuid, 'TRUC_THUOC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-000000000101', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000101', 'e2000000-0000-4000-8000-000000000102', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000101', 'e2000000-0000-4000-8000-000000000103', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-000000000104', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000104', 'e2000000-0000-4000-8000-000000000105', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-000000000106', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000106', 'e2000000-0000-4000-8000-000000000107', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-000000000108', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000108', 'e2000000-0000-4000-8000-000000000109', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-00000000010a', 'CHUYEN_MON'),
  ('e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-00000000010b', 'CHUYEN_MON')
) AS r(parent, child, tree)
ON CONFLICT DO NOTHING;
INSERT INTO org.job_title (id, tenant_id, code, name, level, is_central_managed, valid_from, created_by,
                           updated_by)
SELECT j.id::uuid, 'e2000000-0000-4000-8000-000000000001', j.code, j.name, j.level, j.tw, '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e2000000-0000-4000-8000-000000000201', 'CHU-TICH', 'Chủ tịch Ủy ban nhân dân tỉnh', 1, true),
  ('e2000000-0000-4000-8000-000000000202', 'PHO-CHU-TICH', 'Phó Chủ tịch Ủy ban nhân dân tỉnh', 2, true),
  ('e2000000-0000-4000-8000-000000000203', 'CHANH-VP', 'Chánh Văn phòng', 3, false),
  ('e2000000-0000-4000-8000-000000000204', 'GIAM-DOC-SO', 'Giám đốc Sở', 3, false),
  ('e2000000-0000-4000-8000-000000000205', 'PHO-GIAM-DOC-SO', 'Phó Giám đốc Sở', 4, false),
  ('e2000000-0000-4000-8000-000000000206', 'GIAM-DOC-TT', 'Giám đốc Trung tâm', 5, false),
  ('e2000000-0000-4000-8000-000000000207', 'TRUONG-PHONG', 'Trưởng phòng', 5, false),
  ('e2000000-0000-4000-8000-000000000208', 'CHUYEN-VIEN', 'Chuyên viên', 7, false),
  ('e2000000-0000-4000-8000-000000000209', 'VIEN-CHUC', 'Viên chức', 8, false),
  ('e2000000-0000-4000-8000-00000000020a', 'THANH-TRA-VIEN', 'Thanh tra viên', 7, false),
  ('e2000000-0000-4000-8000-00000000020b', 'CHU-TICH-XA', 'Chủ tịch Ủy ban nhân dân xã', 4, false),
  ('e2000000-0000-4000-8000-00000000020c', 'CONG-CHUC-XA', 'Công chức cấp xã', 8, false)
) AS j(id, code, name, level, tw)
ON CONFLICT DO NOTHING;
INSERT INTO org.person (version_id, id, tenant_id, full_name, is_party_member, employment_status,
                        valid_from, created_by, updated_by)
SELECT md5('person:' || p.id)::uuid, p.id::uuid, 'e2000000-0000-4000-8000-000000000001', p.name, p.dv, 'DANG_CONG_TAC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e2000000-0000-4000-8000-000000000301', 'Lê Hoàng Phúc', true),
  ('e2000000-0000-4000-8000-000000000302', 'Nguyễn Thị Kim Oanh', true),
  ('e2000000-0000-4000-8000-000000000303', 'Trương Công Danh', true),
  ('e2000000-0000-4000-8000-000000000304', 'Phan Thị Hải Yến', true),
  ('e2000000-0000-4000-8000-000000000305', 'Vũ Đức Anh', false),
  ('e2000000-0000-4000-8000-000000000306', 'Châu Thị Ánh', true),
  ('e2000000-0000-4000-8000-000000000307', 'Lâm Văn Tài', false),
  ('e2000000-0000-4000-8000-000000000308', 'Đoàn Văn Hiển', true),
  ('e2000000-0000-4000-8000-000000000309', 'Cao Minh Tâm', true),
  ('e2000000-0000-4000-8000-00000000030a', 'Huỳnh Thị Thảo', true),
  ('e2000000-0000-4000-8000-00000000030b', 'Phạm Văn Lộc', true),
  ('e2000000-0000-4000-8000-00000000030c', 'Hồ Thị Bích Ngọc', true),
  ('e2000000-0000-4000-8000-00000000030d', 'Lương Quốc Bảo', true),
  ('e2000000-0000-4000-8000-00000000030e', 'Tạ Thị Minh Châu', true),
  ('e2000000-0000-4000-8000-00000000030f', 'Kiều Văn Sơn', false),
  ('e2000000-0000-4000-8000-000000000310', 'Mạc Thị Phương', true),
  ('e2000000-0000-4000-8000-000000000311', 'Quách Văn Hải', true),
  ('e2000000-0000-4000-8000-000000000312', 'Lã Văn Toàn', true),
  ('e2000000-0000-4000-8000-000000000313', 'Ninh Thị Hoa', false),
  ('e2000000-0000-4000-8000-000000000314', 'Đỗ Thành Long', false),
  ('e2000000-0000-4000-8000-000000000315', 'Tô Thị Hạnh', false)
) AS p(id, name, dv)
ON CONFLICT DO NOTHING;
INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, branch,
                            is_head, valid_from, created_by, updated_by)
SELECT md5('assignment-version:' || a.kind || ':' || a.person || ':' || a.unit)::uuid,
       md5('assignment:' || a.kind || ':' || a.person || ':' || a.unit)::uuid, 'e2000000-0000-4000-8000-000000000001', a.person::uuid,
       a.unit::uuid, a.title::uuid, a.kind, a.branch, a.head, '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e2000000-0000-4000-8000-000000000301', 'e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-000000000201', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000302', 'e2000000-0000-4000-8000-000000000100', 'e2000000-0000-4000-8000-000000000202', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000303', 'e2000000-0000-4000-8000-000000000101', 'e2000000-0000-4000-8000-000000000203', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000304', 'e2000000-0000-4000-8000-000000000102', 'e2000000-0000-4000-8000-000000000207', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000305', 'e2000000-0000-4000-8000-000000000102', 'e2000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000306', 'e2000000-0000-4000-8000-000000000103', 'e2000000-0000-4000-8000-000000000206', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000307', 'e2000000-0000-4000-8000-000000000103', 'e2000000-0000-4000-8000-000000000209', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000308', 'e2000000-0000-4000-8000-000000000104', 'e2000000-0000-4000-8000-000000000204', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000309', 'e2000000-0000-4000-8000-000000000105', 'e2000000-0000-4000-8000-000000000207', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-00000000030a', 'e2000000-0000-4000-8000-000000000105', 'e2000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-00000000030b', 'e2000000-0000-4000-8000-000000000106', 'e2000000-0000-4000-8000-000000000204', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-00000000030c', 'e2000000-0000-4000-8000-000000000106', 'e2000000-0000-4000-8000-000000000205', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-00000000030d', 'e2000000-0000-4000-8000-000000000107', 'e2000000-0000-4000-8000-000000000207', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-00000000030e', 'e2000000-0000-4000-8000-000000000107', 'e2000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-00000000030f', 'e2000000-0000-4000-8000-000000000107', 'e2000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-00000000030f', 'e2000000-0000-4000-8000-000000000109', 'e2000000-0000-4000-8000-000000000208', 'KIEM_NHIEM', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000310', 'e2000000-0000-4000-8000-000000000109', 'e2000000-0000-4000-8000-000000000207', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000311', 'e2000000-0000-4000-8000-00000000010a', 'e2000000-0000-4000-8000-00000000020a', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000312', 'e2000000-0000-4000-8000-00000000010b', 'e2000000-0000-4000-8000-00000000020b', 'CHINH', 'CHINH_QUYEN', true),
  ('e2000000-0000-4000-8000-000000000313', 'e2000000-0000-4000-8000-00000000010b', 'e2000000-0000-4000-8000-00000000020c', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000314', 'e2000000-0000-4000-8000-000000000102', 'e2000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false),
  ('e2000000-0000-4000-8000-000000000315', 'e2000000-0000-4000-8000-000000000102', 'e2000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false)
) AS a(person, unit, title, kind, branch, head)
ON CONFLICT DO NOTHING;
INSERT INTO okr.basis (id, tenant_id, created_by, updated_by, basis_kind, doc_number, title,
                       issued_by_unit_id, issued_on, valid_from)
SELECT b.id::uuid, 'e2000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', b.kind, b.num, b.title, 'e2000000-0000-4000-8000-000000000100', b.ngay::date, b.ngay::date
FROM (VALUES
  ('e2000000-0000-4000-8000-00000000b001', 'CHI_TIEU_KTXH', '2950/QĐ-UBND', 'Quyết định giao chỉ tiêu kế hoạch phát triển kinh tế - xã hội và dự toán ngân sách tỉnh năm 2026', '2025-12-24'),
  ('e2000000-0000-4000-8000-00000000b002', 'KE_HOACH', '215/KH-UBND', 'Kế hoạch cải cách hành chính và chuyển đổi số tỉnh Phú Lâm năm 2026', '2026-01-20'),
  ('e2000000-0000-4000-8000-00000000b003', 'CHI_THI', '03/CT-UBND', 'Chỉ thị về siết chặt kỷ luật, kỷ cương hành chính trong thực thi công vụ', '2026-02-16'),
  ('e2000000-0000-4000-8000-00000000b004', 'KE_HOACH', '402/KH-UBND', 'Kế hoạch đánh giá, xếp loại chất lượng cán bộ, công chức, viên chức năm 2026', '2026-10-02')
) AS b(id, kind, num, title, ngay)
ON CONFLICT (id) DO NOTHING;
INSERT INTO task.work_catalog (id, tenant_id, created_by, updated_by, code, name, scope, org_unit_id, version,
                               source, status, version_no)
VALUES ('e2000000-0000-4000-8000-00000000d001', 'e2000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'DM-UBND-PL', 'Danh mục công việc khối văn phòng và sở, ngành tỉnh', 'DON_VI', 'e2000000-0000-4000-8000-000000000100', 1, 'TU_TAO', 'HIEU_LUC', 1)
ON CONFLICT (id) DO NOTHING;
INSERT INTO task.catalog_item (id, tenant_id, created_by, updated_by, work_catalog_id, code, name,
                               product_name, unit, difficulty, standard_score, quality_standard,
                               evidence_requirement, task_group, standard_days, status)
SELECT i.id::uuid, 'e2000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'e2000000-0000-4000-8000-00000000d001', i.code, i.name, i.product, i.unit, i.difficulty, i.score, i.quality,
       i.evidence, i.task_group, i.days, 'AP_DUNG'
FROM (VALUES
  ('e2000000-0000-4000-8000-00000000d101', 'CQ-01', 'Tham mưu báo cáo tình hình kinh tế - xã hội hằng tháng', 'Báo cáo', 'Báo cáo', 3, 15.0, 'Đủ chỉ tiêu, khớp số liệu thống kê, trình đúng hạn', 'Báo cáo đã trình ký', 'THEN_CHOT', 7),
  ('e2000000-0000-4000-8000-00000000d102', 'CQ-02', 'Thẩm định dự toán ngân sách của đơn vị dự toán cấp tỉnh', 'Báo cáo thẩm định', 'Hồ sơ', 4, 20.0, 'Đúng định mức, đủ thuyết minh, có ý kiến xử lý', 'Báo cáo thẩm định đã ký', 'THEN_CHOT', 15),
  ('e2000000-0000-4000-8000-00000000d103', 'CQ-03', 'Giải quyết hồ sơ thủ tục hành chính', 'Hồ sơ đã trả kết quả', 'Hồ sơ', 1, 2.0, 'Trả kết quả đúng hạn, không yêu cầu bổ sung quá một lần', NULL, 'THUONG_XUYEN', 3),
  ('e2000000-0000-4000-8000-00000000d104', 'CQ-04', 'Xây dựng dự thảo quyết định, kế hoạch trình Ủy ban nhân dân tỉnh', 'Dự thảo văn bản', 'Văn bản', 3, 15.0, 'Đúng thẩm quyền, thể thức, có tờ trình kèm theo', 'Tờ trình và dự thảo đã trình', 'THEN_CHOT', 10),
  ('e2000000-0000-4000-8000-00000000d105', 'CQ-05', 'Cập nhật cơ sở dữ liệu cán bộ, công chức, viên chức', 'Dữ liệu đã cập nhật', 'Lượt', 2, 5.0, 'Đủ trường bắt buộc, khớp hồ sơ gốc', NULL, 'THUONG_XUYEN', 5),
  ('e2000000-0000-4000-8000-00000000d106', 'CQ-06', 'Tổ chức cuộc họp chuyên đề của lãnh đạo tỉnh', 'Thông báo kết luận', 'Cuộc', 2, 8.0, 'Có giấy mời, tài liệu và thông báo kết luận', 'Thông báo kết luận đã phát hành', 'THUONG_XUYEN', 5),
  ('e2000000-0000-4000-8000-00000000d107', 'CQ-07', 'Triển khai nền tảng số dùng chung của tỉnh', 'Hạng mục hoàn thành', 'Hạng mục', 4, 25.0, 'Đạt yêu cầu kỹ thuật, có biên bản nghiệm thu', 'Biên bản nghiệm thu', 'THEN_CHOT', 30)
) AS i(id, code, name, product, unit, difficulty, score, quality, evidence, task_group, days)
ON CONFLICT (id) DO NOTHING;

-- ===== Công ty TNHH MTV Cấp nước Phú Lâm (e3000000-0000-4000-8000-000000000001) =====
INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, flows, kek_ref,
                             created_by, updated_by)
VALUES ('e3000000-0000-4000-8000-000000000001', 'cap_nuoc_phu_lam', 'Công ty TNHH MTV Cấp nước Phú Lâm', 'DOANH_NGHIEP', 'POOL', 'DANG_VAN_HANH', 10000, ARRAY['NHA_NUOC']::varchar(12)[], 'dev/gia-lap', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;
INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, tenancy_model,
                                  data_region, created_by, updated_by)
SELECT 'e3000000-0000-4000-8000-0000000000c2', 'e3000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000c1', 'mission', '2026-01-01T00:00:00+07:00', 'pool', 'VN', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
WHERE NOT EXISTS (SELECT 1 FROM registry.tenant_cell WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001');
INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)
VALUES ('e3000000-0000-4000-8000-0000000000a1', 'e3000000-0000-4000-8000-000000000001', 'K9', 'Doanh nghiệp nhà nước', 'CA_HAI', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;
INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, level, subject_group_id, flows,
                          valid_from, created_by, updated_by)
SELECT md5('org_unit:' || v.id)::uuid, v.id::uuid, 'e3000000-0000-4000-8000-000000000001', v.code, v.name, v.unit_type, v.level,
       v.sg::uuid, ARRAY['NHA_NUOC']::varchar(12)[], '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e3000000-0000-4000-8000-000000000100', 'CNPL', 'Công ty TNHH MTV Cấp nước Phú Lâm', 'DOANH_NGHIEP', 'TINH', 'e3000000-0000-4000-8000-0000000000a1'),
  ('e3000000-0000-4000-8000-000000000101', 'P-TCHC', 'Phòng Tổ chức – Hành chính', 'PHONG_BAN', 'TINH', 'e3000000-0000-4000-8000-0000000000a1'),
  ('e3000000-0000-4000-8000-000000000102', 'P-KHKT', 'Phòng Kế hoạch – Kỹ thuật', 'PHONG_BAN', 'TINH', 'e3000000-0000-4000-8000-0000000000a1'),
  ('e3000000-0000-4000-8000-000000000103', 'P-TCKT', 'Phòng Tài chính – Kế toán', 'PHONG_BAN', 'TINH', 'e3000000-0000-4000-8000-0000000000a1'),
  ('e3000000-0000-4000-8000-000000000104', 'P-KDDV', 'Phòng Kinh doanh – Dịch vụ khách hàng', 'PHONG_BAN', 'TINH', 'e3000000-0000-4000-8000-0000000000a1'),
  ('e3000000-0000-4000-8000-000000000105', 'XNCN1', 'Xí nghiệp Cấp nước số 1', 'DOANH_NGHIEP', 'TINH', 'e3000000-0000-4000-8000-0000000000a1'),
  ('e3000000-0000-4000-8000-000000000106', 'NMN-DS', 'Nhà máy nước Đông Sơn', 'DOANH_NGHIEP', 'TINH', 'e3000000-0000-4000-8000-0000000000a1')
) AS v(id, code, name, unit_type, level, sg)
ON CONFLICT DO NOTHING;
INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id,
                                   relation_type, valid_from, created_by, updated_by)
SELECT md5('org_unit_relation:' || r.tree || ':' || r.child)::uuid, md5('relation:' || r.tree || ':' || r.child)::uuid,
       'e3000000-0000-4000-8000-000000000001', r.tree, r.parent::uuid, r.child::uuid, 'TRUC_THUOC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000101', 'CHUYEN_MON'),
  ('e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000102', 'CHUYEN_MON'),
  ('e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000103', 'CHUYEN_MON'),
  ('e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000104', 'CHUYEN_MON'),
  ('e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000105', 'CHUYEN_MON'),
  ('e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000106', 'CHUYEN_MON')
) AS r(parent, child, tree)
ON CONFLICT DO NOTHING;
INSERT INTO org.job_title (id, tenant_id, code, name, level, is_central_managed, valid_from, created_by,
                           updated_by)
SELECT j.id::uuid, 'e3000000-0000-4000-8000-000000000001', j.code, j.name, j.level, j.tw, '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e3000000-0000-4000-8000-000000000201', 'CT-HDTV', 'Chủ tịch Hội đồng thành viên', 1, false),
  ('e3000000-0000-4000-8000-000000000202', 'TONG-GIAM-DOC', 'Tổng Giám đốc', 2, false),
  ('e3000000-0000-4000-8000-000000000203', 'PHO-TONG-GIAM-DOC', 'Phó Tổng Giám đốc', 3, false),
  ('e3000000-0000-4000-8000-000000000204', 'KIEM-SOAT-VIEN', 'Kiểm soát viên', 3, false),
  ('e3000000-0000-4000-8000-000000000205', 'TRUONG-PHONG', 'Trưởng phòng', 4, false),
  ('e3000000-0000-4000-8000-000000000206', 'GIAM-DOC-XN', 'Giám đốc Xí nghiệp', 4, false),
  ('e3000000-0000-4000-8000-000000000207', 'TO-TRUONG', 'Tổ trưởng', 6, false),
  ('e3000000-0000-4000-8000-000000000208', 'CHUYEN-VIEN', 'Chuyên viên', 6, false),
  ('e3000000-0000-4000-8000-000000000209', 'NHAN-VIEN', 'Nhân viên', 7, false),
  ('e3000000-0000-4000-8000-00000000020a', 'CONG-NHAN', 'Công nhân vận hành', 8, false)
) AS j(id, code, name, level, tw)
ON CONFLICT DO NOTHING;
INSERT INTO org.person (version_id, id, tenant_id, full_name, is_party_member, employment_status,
                        valid_from, created_by, updated_by)
SELECT md5('person:' || p.id)::uuid, p.id::uuid, 'e3000000-0000-4000-8000-000000000001', p.name, p.dv, 'DANG_CONG_TAC', '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e3000000-0000-4000-8000-000000000301', 'Hoàng Minh Quân', true),
  ('e3000000-0000-4000-8000-000000000302', 'Nguyễn Văn Thắng', true),
  ('e3000000-0000-4000-8000-000000000303', 'Phan Thị Thùy Dung', true),
  ('e3000000-0000-4000-8000-000000000304', 'Lê Văn Cường', true),
  ('e3000000-0000-4000-8000-000000000305', 'Đỗ Thị Thanh Tâm', true),
  ('e3000000-0000-4000-8000-000000000306', 'Vương Quốc Việt', false),
  ('e3000000-0000-4000-8000-000000000307', 'Trần Thị Mỹ Linh', true),
  ('e3000000-0000-4000-8000-000000000308', 'Phạm Hoài Nam', false),
  ('e3000000-0000-4000-8000-000000000309', 'Lê Thị Ngọc Trâm', false),
  ('e3000000-0000-4000-8000-00000000030a', 'Bạch Văn Hùng', true),
  ('e3000000-0000-4000-8000-00000000030b', 'Tống Văn Định', true),
  ('e3000000-0000-4000-8000-00000000030c', 'Nguyễn Văn Hậu', false),
  ('e3000000-0000-4000-8000-00000000030d', 'Đặng Văn Tư', false),
  ('e3000000-0000-4000-8000-00000000030e', 'Lưu Thị Hồng', false)
) AS p(id, name, dv)
ON CONFLICT DO NOTHING;
INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, branch,
                            is_head, valid_from, created_by, updated_by)
SELECT md5('assignment-version:' || a.kind || ':' || a.person || ':' || a.unit)::uuid,
       md5('assignment:' || a.kind || ':' || a.person || ':' || a.unit)::uuid, 'e3000000-0000-4000-8000-000000000001', a.person::uuid,
       a.unit::uuid, a.title::uuid, a.kind, a.branch, a.head, '2000-01-01', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
FROM (VALUES
  ('e3000000-0000-4000-8000-000000000301', 'e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000201', 'CHINH', 'CHINH_QUYEN', true),
  ('e3000000-0000-4000-8000-000000000302', 'e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000202', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-000000000303', 'e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000203', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-000000000304', 'e3000000-0000-4000-8000-000000000100', 'e3000000-0000-4000-8000-000000000204', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-000000000305', 'e3000000-0000-4000-8000-000000000101', 'e3000000-0000-4000-8000-000000000205', 'CHINH', 'CHINH_QUYEN', true),
  ('e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000101', 'e3000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-000000000307', 'e3000000-0000-4000-8000-000000000104', 'e3000000-0000-4000-8000-000000000205', 'CHINH', 'CHINH_QUYEN', true),
  ('e3000000-0000-4000-8000-000000000308', 'e3000000-0000-4000-8000-000000000104', 'e3000000-0000-4000-8000-000000000209', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-000000000309', 'e3000000-0000-4000-8000-000000000104', 'e3000000-0000-4000-8000-000000000209', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-00000000030a', 'e3000000-0000-4000-8000-000000000102', 'e3000000-0000-4000-8000-000000000205', 'CHINH', 'CHINH_QUYEN', true),
  ('e3000000-0000-4000-8000-00000000030b', 'e3000000-0000-4000-8000-000000000105', 'e3000000-0000-4000-8000-000000000206', 'CHINH', 'CHINH_QUYEN', true),
  ('e3000000-0000-4000-8000-00000000030c', 'e3000000-0000-4000-8000-000000000105', 'e3000000-0000-4000-8000-000000000207', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-00000000030d', 'e3000000-0000-4000-8000-000000000105', 'e3000000-0000-4000-8000-00000000020a', 'CHINH', 'CHINH_QUYEN', false),
  ('e3000000-0000-4000-8000-00000000030e', 'e3000000-0000-4000-8000-000000000101', 'e3000000-0000-4000-8000-000000000208', 'CHINH', 'CHINH_QUYEN', false)
) AS a(person, unit, title, kind, branch, head)
ON CONFLICT DO NOTHING;
INSERT INTO okr.basis (id, tenant_id, created_by, updated_by, basis_kind, doc_number, title,
                       issued_by_unit_id, issued_on, valid_from)
SELECT b.id::uuid, 'e3000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', b.kind, b.num, b.title, 'e3000000-0000-4000-8000-000000000100', b.ngay::date, b.ngay::date
FROM (VALUES
  ('e3000000-0000-4000-8000-00000000b001', 'KE_HOACH', '120/KH-CNPL', 'Kế hoạch sản xuất kinh doanh và đầu tư phát triển năm 2026 của Công ty', '2025-12-26'),
  ('e3000000-0000-4000-8000-00000000b002', 'NGHI_QUYET', '08/NQ-HĐTV', 'Nghị quyết của Hội đồng thành viên giao chỉ tiêu kế hoạch năm 2026', '2026-01-08'),
  ('e3000000-0000-4000-8000-00000000b003', 'CHUONG_TRINH', '35/CTr-CNPL', 'Chương trình giảm thất thoát, thất thu nước sạch giai đoạn 2026 – 2027', '2026-03-02')
) AS b(id, kind, num, title, ngay)
ON CONFLICT (id) DO NOTHING;
INSERT INTO task.work_catalog (id, tenant_id, created_by, updated_by, code, name, scope, org_unit_id, version,
                               source, status, version_no)
VALUES ('e3000000-0000-4000-8000-00000000d001', 'e3000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'DM-CNPL', 'Danh mục công việc Công ty Cấp nước Phú Lâm', 'DON_VI', 'e3000000-0000-4000-8000-000000000100', 1, 'TU_TAO', 'HIEU_LUC', 1)
ON CONFLICT (id) DO NOTHING;
INSERT INTO task.catalog_item (id, tenant_id, created_by, updated_by, work_catalog_id, code, name,
                               product_name, unit, difficulty, standard_score, quality_standard,
                               evidence_requirement, task_group, standard_days, status)
SELECT i.id::uuid, 'e3000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'e3000000-0000-4000-8000-00000000d001', i.code, i.name, i.product, i.unit, i.difficulty, i.score, i.quality,
       i.evidence, i.task_group, i.days, 'AP_DUNG'
FROM (VALUES
  ('e3000000-0000-4000-8000-00000000d101', 'DN-01', 'Lắp đặt đồng hồ, đấu nối cấp nước cho khách hàng mới', 'Điểm đấu nối', 'Điểm', 1, 2.0, 'Đúng thiết kế, niêm chì đồng hồ, khách hàng ký biên bản', 'Biên bản nghiệm thu đấu nối', 'THUONG_XUYEN', 2),
  ('e3000000-0000-4000-8000-00000000d102', 'DN-02', 'Khắc phục sự cố rò rỉ, vỡ ống trên mạng lưới', 'Sự cố đã khắc phục', 'Sự cố', 2, 4.0, 'Khắc phục trong 4 giờ, hoàn trả mặt bằng', 'Ảnh trước và sau khi khắc phục', 'THUONG_XUYEN', 1),
  ('e3000000-0000-4000-8000-00000000d103', 'DN-03', 'Ghi chỉ số, phát hành hóa đơn tiền nước', 'Hóa đơn', 'Kỳ', 1, 3.0, 'Đúng lịch ghi, sai sót dưới 0,5%', NULL, 'THUONG_XUYEN', 5),
  ('e3000000-0000-4000-8000-00000000d104', 'DN-04', 'Kiểm định, thay thế đồng hồ nước đến hạn', 'Đồng hồ đã thay', 'Chiếc', 2, 3.0, 'Đồng hồ thay mới có tem kiểm định còn hạn', 'Danh sách đồng hồ đã thay', 'THUONG_XUYEN', 10),
  ('e3000000-0000-4000-8000-00000000d105', 'DN-05', 'Lập báo cáo tỷ lệ thất thoát, thất thu nước sạch', 'Báo cáo', 'Báo cáo', 3, 12.0, 'Số liệu theo vùng cấp nước, có phân tích nguyên nhân', 'Báo cáo đã trình', 'THEN_CHOT', 7),
  ('e3000000-0000-4000-8000-00000000d106', 'DN-06', 'Thực hiện dự án cải tạo mạng lưới cấp nước', 'Hạng mục hoàn thành', 'Hạng mục', 4, 25.0, 'Đạt yêu cầu kỹ thuật, có biên bản nghiệm thu hạng mục', 'Biên bản nghiệm thu', 'THEN_CHOT', 60),
  ('e3000000-0000-4000-8000-00000000d107', 'DN-07', 'Giải quyết phản ánh, khiếu nại của khách hàng', 'Phản ánh đã xử lý', 'Phản ánh', 1, 2.0, 'Phản hồi khách hàng trong 48 giờ', 'Phiếu xử lý có xác nhận của khách hàng', 'THUONG_XUYEN', 2)
) AS i(id, code, name, product, unit, difficulty, score, quality, evidence, task_group, days)
ON CONFLICT (id) DO NOTHING;

COMMIT;
