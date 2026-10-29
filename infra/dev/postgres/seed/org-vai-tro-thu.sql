-- =============================================================================
-- Vai trò theo đơn vị (config.role_binding) của 14 tài khoản thử thuộc tenant d0000000-0000-4000-8000-000000000001
-- (Sở Thử nghiệm). config.role_binding là nguồn sự thật của vai trò (WEB-058 phương án A): tài khoản chỉ có realm
-- role thì không khai uỷ quyền được (SCR-0105, SCR-0107) và đối soát vai trò có thể gỡ realm role. Cách đặt giống bộ
-- dữ liệu đa tenant (data/seed/da-tenant/sinh_seed.py): vai trò toàn tenant ở đơn vị gốc kèm cấp dưới; lãnh đạo ở
-- đơn vị mình kèm cấp dưới, thẩm định ở cấp Sở kèm cấp dưới; các vai trò khác ở đơn vị mình. Vai trò nhạy cảm (decider, second_approver,
-- policy_author) có người duyệt kỹ thuật thứ hai (bốn mắt approved_by <> granted_by). Tài khoản nentang là quản trị
-- nền tảng, không thuộc tenant nên không có vai trò ở đây. Chạy lại không sinh bản ghi trùng. Chỉ dữ liệu giả lập.
-- =============================================================================
-- thamdinh2 từng có vai trò member ở Ban Tổ chức (102); chuyển sang Phòng Tổng hợp (101) theo phân công mới (HT-33).
UPDATE config.role_binding SET org_unit_id = 'd0000000-0000-4000-8000-000000000101', updated_at = now()
WHERE tenant_id = 'd0000000-0000-4000-8000-000000000001' AND person_id = 'd0000000-0000-4000-8000-000000000315'
  AND role_code = 'member' AND org_unit_id = 'd0000000-0000-4000-8000-000000000102' AND end_kind IS NULL;

INSERT INTO config.role_binding (id, tenant_id, created_by, updated_by, person_id, role_code, org_unit_id,
                                 scope, valid_from, granted_by, granted_at, approved_by, approved_at, reason)
SELECT md5('role-binding:' || r.person || ':' || r.role || ':' || r.unit)::uuid, 'd0000000-0000-4000-8000-000000000001',
       '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', r.person::uuid,
       r.role, r.unit::uuid, r.scope, '2026-01-01', '00000000-0000-7000-8000-000000000000', '2026-01-01', r.duyet,
       CASE WHEN r.duyet IS NULL THEN NULL ELSE '2026-01-01'::timestamptz END,
       'Dữ liệu thử Sở Thử nghiệm: vai trò theo phân công'
FROM (VALUES
  -- quantri, quantri2: quản trị tenant (toàn tenant), soạn chính sách ở Phòng Tổng hợp
  ('d0000000-0000-4000-8000-000000000302', 'tenant_admin', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000302', 'policy_author', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', '00000000-0000-7000-8000-000000000001'::uuid),
  ('d0000000-0000-4000-8000-000000000303', 'tenant_admin', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000303', 'policy_author', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', '00000000-0000-7000-8000-000000000001'::uuid),
  -- chinhsach
  ('d0000000-0000-4000-8000-000000000304', 'policy_author', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', '00000000-0000-7000-8000-000000000001'::uuid),
  -- lanhdao, quanly, canbo (Phòng Tổng hợp)
  ('d0000000-0000-4000-8000-000000000305', 'member', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000305', 'leader', 'd0000000-0000-4000-8000-000000000101', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000306', 'member', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000306', 'manager', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000307', 'member', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', NULL::uuid),
  -- thamdinh, quyetdinh, duyet2 (Ban Tổ chức)
  ('d0000000-0000-4000-8000-000000000308', 'member', 'd0000000-0000-4000-8000-000000000102', 'DON_VI', NULL::uuid),
  -- Ban Tổ chức thẩm định cán bộ toàn Sở: vai trò thẩm định giao ở cấp Sở kèm cấp dưới (QA-06, wiki 14)
  ('d0000000-0000-4000-8000-000000000308', 'appraiser', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000309', 'decider', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', '00000000-0000-7000-8000-000000000001'::uuid),
  -- thamdinh2: người thẩm định thứ hai, tiếp nhận khiếu nại khi thamdinh đã thẩm định hồ sơ (tách vai trò, HT-33)
  ('d0000000-0000-4000-8000-000000000315', 'member', 'd0000000-0000-4000-8000-000000000101', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000315', 'appraiser', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  -- quyetdinh2: người quyết định thứ hai, duyệt lượt một yêu cầu mở lại kỳ khi quyetdinh bị bốn mắt loại (HT-34)
  ('d0000000-0000-4000-8000-000000000314', 'member', 'd0000000-0000-4000-8000-000000000102', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000314', 'decider', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', '00000000-0000-7000-8000-000000000001'::uuid),
  ('d0000000-0000-4000-8000-000000000310', 'second_approver', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', '00000000-0000-7000-8000-000000000001'::uuid),
  -- kiemtra (Ủy ban Kiểm tra)
  ('d0000000-0000-4000-8000-000000000311', 'member', 'd0000000-0000-4000-8000-000000000103', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000311', 'inspector', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  -- giamdocso, phogiamdocso (cấp Sở)
  ('d0000000-0000-4000-8000-000000000312', 'member', 'd0000000-0000-4000-8000-000000000100', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000312', 'leader', 'd0000000-0000-4000-8000-000000000100', 'DON_VI_VA_CAP_DUOI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000313', 'member', 'd0000000-0000-4000-8000-000000000100', 'DON_VI', NULL::uuid),
  ('d0000000-0000-4000-8000-000000000313', 'manager', 'd0000000-0000-4000-8000-000000000100', 'DON_VI', NULL::uuid)
) AS r(person, role, unit, scope, duyet)
WHERE NOT EXISTS (SELECT 1 FROM config.role_binding b WHERE b.tenant_id = 'd0000000-0000-4000-8000-000000000001'
                  AND b.person_id = r.person::uuid AND b.role_code = r.role AND b.org_unit_id = r.unit::uuid
                  AND b.end_kind IS NULL);
