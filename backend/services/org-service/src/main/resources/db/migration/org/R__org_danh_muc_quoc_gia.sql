-- DL-07: danh mục quốc gia của tenant hệ thống: 9 khối đối tượng (PL 2A) và chức danh (PL 2B).
-- SINH TỰ ĐỘNG từ data/seed/org/*.csv bằng data/seed/org/sinh_migration.py; không sửa tay.
-- Dữ liệu hiện là GIẢ LẬP (YC-PT-14, người dùng chấp nhận 22/10/2026); thay CSV khi có PL 2A, PL 2B.
-- Chạy bằng mig_org (chủ bảng). FORCE RLS chặn cả chủ bảng nên tạm tắt trong giao dịch của migration.
ALTER TABLE org.subject_group NO FORCE ROW LEVEL SECURITY;
ALTER TABLE org.job_title NO FORCE ROW LEVEL SECURITY;

INSERT INTO org.subject_group (id, tenant_id, code, name, flow, description, valid_from, valid_to, created_by, updated_by)
VALUES
  ('3baed8ab-f659-58d1-81d6-64cd4ba72322', '00000000-0000-0000-0000-000000000000'::uuid, 'K1', 'Cơ quan Đảng ở Trung ương', 'DANG', 'Ban, cơ quan của Đảng ở Trung ương và đơn vị sự nghiệp trực thuộc', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('e36dce98-4a46-579a-9d1c-3a3eff7be1fe', '00000000-0000-0000-0000-000000000000'::uuid, 'K2', 'Cấp ủy, cơ quan Đảng cấp tỉnh', 'DANG', 'Tỉnh ủy, thành ủy, đảng ủy trực thuộc Trung ương và các ban, cơ quan tham mưu', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('2a9a2895-ba61-51df-9c05-63593b4959be', '00000000-0000-0000-0000-000000000000'::uuid, 'K3', 'Cấp ủy, tổ chức đảng cấp cơ sở', 'DANG', 'Đảng ủy cấp xã, đảng bộ, chi bộ cơ sở', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('135a3962-7f4e-501a-b4f9-955f9a5405a1', '00000000-0000-0000-0000-000000000000'::uuid, 'K4', 'Mặt trận Tổ quốc và các tổ chức chính trị - xã hội', 'CA_HAI', 'Mặt trận Tổ quốc, đoàn thể chính trị - xã hội các cấp', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('4f8bee5f-e817-5c6e-9cdf-f46f7e0c73f1', '00000000-0000-0000-0000-000000000000'::uuid, 'K5', 'Bộ, cơ quan ngang bộ, cơ quan thuộc Chính phủ', 'NHA_NUOC', 'Cơ quan hành chính nhà nước ở Trung ương', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('05301d02-a8eb-519f-8bfe-394625cdd239', '00000000-0000-0000-0000-000000000000'::uuid, 'K6', 'Chính quyền địa phương cấp tỉnh', 'NHA_NUOC', 'Hội đồng nhân dân, Ủy ban nhân dân cấp tỉnh và các sở, ngành', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('7a4f80af-5216-5186-b3a4-68cb086a556c', '00000000-0000-0000-0000-000000000000'::uuid, 'K7', 'Chính quyền địa phương cấp xã', 'NHA_NUOC', 'Hội đồng nhân dân, Ủy ban nhân dân cấp xã', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('b4e07f48-b9e9-56dd-8b7c-6d1ca92cbd83', '00000000-0000-0000-0000-000000000000'::uuid, 'K8', 'Đơn vị sự nghiệp công lập', 'NHA_NUOC', 'Đơn vị sự nghiệp công lập trực thuộc cơ quan nhà nước', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('dd4e94ec-5fbf-58b8-aae1-57ba796b7eff', '00000000-0000-0000-0000-000000000000'::uuid, 'K9', 'Doanh nghiệp nhà nước', 'CA_HAI', 'Tập đoàn, tổng công ty và doanh nghiệp do Nhà nước nắm giữ vốn', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, flow = EXCLUDED.flow,
  description = EXCLUDED.description, valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to,
  updated_at = now(), updated_by = EXCLUDED.updated_by, row_version = org.subject_group.row_version + 1
WHERE (org.subject_group.name, org.subject_group.flow, org.subject_group.description,
       org.subject_group.valid_from, org.subject_group.valid_to)
  IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.flow, EXCLUDED.description, EXCLUDED.valid_from, EXCLUDED.valid_to);

INSERT INTO org.job_title (id, tenant_id, code, name, level, is_central_managed, subject_group_id, valid_from, valid_to, created_by, updated_by)
VALUES
  ('adc9b558-2d33-5d66-bc71-55bd2e97c565', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-01', 'Bí thư tỉnh ủy, thành ủy', 1, true, 'e36dce98-4a46-579a-9d1c-3a3eff7be1fe', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('a5e3119b-669c-5def-a114-708f38aaf6d5', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-02', 'Phó Bí thư thường trực tỉnh ủy, thành ủy', 2, true, 'e36dce98-4a46-579a-9d1c-3a3eff7be1fe', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('c69991d3-2e83-5f37-97db-87e535cf38f5', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-03', 'Trưởng ban của Đảng ở Trung ương', 1, true, '3baed8ab-f659-58d1-81d6-64cd4ba72322', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('5c950a69-2796-529a-b647-51d507eeed45', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-04', 'Phó Trưởng ban của Đảng ở Trung ương', 2, true, '3baed8ab-f659-58d1-81d6-64cd4ba72322', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('491b0b38-de11-5d65-8aca-a705b86c945b', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-05', 'Bộ trưởng, Thủ trưởng cơ quan ngang bộ', 1, true, '4f8bee5f-e817-5c6e-9cdf-f46f7e0c73f1', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('d70bfcc4-59e8-59ca-9e6b-e22dc668f54f', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-06', 'Thứ trưởng, Phó Thủ trưởng cơ quan ngang bộ', 2, true, '4f8bee5f-e817-5c6e-9cdf-f46f7e0c73f1', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('67b5b272-7bff-5692-8293-835bf512ca2c', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-07', 'Chủ tịch Ủy ban nhân dân cấp tỉnh', 1, true, '05301d02-a8eb-519f-8bfe-394625cdd239', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('88761a4b-aa17-5f21-9bfa-66d11dde5863', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-08', 'Chủ tịch Hội đồng nhân dân cấp tỉnh', 1, true, '05301d02-a8eb-519f-8bfe-394625cdd239', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('c7e16a18-6282-5077-ae7c-bd44be6c5630', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-09', 'Người đứng đầu tổ chức chính trị - xã hội ở Trung ương', 1, true, '135a3962-7f4e-501a-b4f9-955f9a5405a1', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('04355309-c67b-53d1-b811-a184e200bedb', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-TW-10', 'Chủ tịch Hội đồng thành viên tập đoàn kinh tế nhà nước', 1, true, 'dd4e94ec-5fbf-58b8-aae1-57ba796b7eff', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('272088e5-8b55-51ca-8abb-844ceae77239', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-01', 'Trưởng ban, cơ quan tham mưu của tỉnh ủy', 3, false, 'e36dce98-4a46-579a-9d1c-3a3eff7be1fe', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('ccfebe63-3359-5b4c-932a-ca45af2b3141', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-02', 'Bí thư đảng ủy cấp xã', 4, false, '2a9a2895-ba61-51df-9c05-63593b4959be', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('42143cb7-0276-5d2b-9caf-24690d077ec1', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-03', 'Phó Bí thư đảng ủy cấp xã', 5, false, '2a9a2895-ba61-51df-9c05-63593b4959be', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('3b16b2a9-5cd0-51df-91b5-e78ec501bfd2', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-04', 'Vụ trưởng và tương đương', 3, false, '4f8bee5f-e817-5c6e-9cdf-f46f7e0c73f1', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('4b50f44a-4550-5b20-ae41-dc24c23475f2', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-05', 'Phó Vụ trưởng và tương đương', 4, false, '4f8bee5f-e817-5c6e-9cdf-f46f7e0c73f1', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('218509ef-0607-5d69-952b-45cc11ffe145', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-06', 'Giám đốc sở và tương đương', 3, false, '05301d02-a8eb-519f-8bfe-394625cdd239', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('71b480c0-c648-5f79-a321-97c391f55035', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-07', 'Phó Giám đốc sở và tương đương', 4, false, '05301d02-a8eb-519f-8bfe-394625cdd239', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('38baaaf6-071a-5d7f-935f-764bec10da37', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-08', 'Chủ tịch Ủy ban nhân dân cấp xã', 4, false, '7a4f80af-5216-5186-b3a4-68cb086a556c', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('b02f1ce4-d3a2-5f68-ac0e-e3a62ce3b60b', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-09', 'Phó Chủ tịch Ủy ban nhân dân cấp xã', 5, false, '7a4f80af-5216-5186-b3a4-68cb086a556c', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('9f8413b1-0d70-5802-86e4-07e3efb2357a', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-10', 'Người đứng đầu đơn vị sự nghiệp công lập', 4, false, 'b4e07f48-b9e9-56dd-8b7c-6d1ca92cbd83', '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('5688a473-e043-5f8d-a382-aea4406749c0', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-11', 'Trưởng phòng và tương đương', 5, false, NULL, '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('117d23a4-6af2-5e43-b9ec-d1727ef85271', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-12', 'Phó Trưởng phòng và tương đương', 6, false, NULL, '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid),
  ('c2c301da-681c-5b0d-84ce-3a125164ba81', '00000000-0000-0000-0000-000000000000'::uuid, 'CD-13', 'Chuyên viên và tương đương', 7, false, NULL, '2026-01-01', 'infinity', '00000000-0000-0000-0000-000000000000'::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, level = EXCLUDED.level,
  is_central_managed = EXCLUDED.is_central_managed, subject_group_id = EXCLUDED.subject_group_id,
  valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to,
  updated_at = now(), updated_by = EXCLUDED.updated_by, row_version = org.job_title.row_version + 1
WHERE (org.job_title.name, org.job_title.level, org.job_title.is_central_managed, org.job_title.subject_group_id,
       org.job_title.valid_from, org.job_title.valid_to)
  IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.level, EXCLUDED.is_central_managed, EXCLUDED.subject_group_id,
                    EXCLUDED.valid_from, EXCLUDED.valid_to);

-- Mục đã bỏ khỏi tệp nguồn: khép hiệu lực, không xoá vì có thể đã được tham chiếu.
UPDATE org.job_title SET valid_to = GREATEST(current_date, valid_from + 1), updated_at = now(),
  updated_by = '00000000-0000-0000-0000-000000000000'::uuid, row_version = row_version + 1
WHERE tenant_id = '00000000-0000-0000-0000-000000000000'::uuid AND valid_to = 'infinity' AND code NOT IN ('CD-TW-01', 'CD-TW-02', 'CD-TW-03', 'CD-TW-04', 'CD-TW-05', 'CD-TW-06', 'CD-TW-07', 'CD-TW-08', 'CD-TW-09', 'CD-TW-10', 'CD-01', 'CD-02', 'CD-03', 'CD-04', 'CD-05', 'CD-06', 'CD-07', 'CD-08', 'CD-09', 'CD-10', 'CD-11', 'CD-12', 'CD-13');
UPDATE org.subject_group SET valid_to = GREATEST(current_date, valid_from + 1), updated_at = now(),
  updated_by = '00000000-0000-0000-0000-000000000000'::uuid, row_version = row_version + 1
WHERE tenant_id = '00000000-0000-0000-0000-000000000000'::uuid AND valid_to = 'infinity' AND code NOT IN ('K1', 'K2', 'K3', 'K4', 'K5', 'K6', 'K7', 'K8', 'K9');

ALTER TABLE org.subject_group FORCE ROW LEVEL SECURITY;
ALTER TABLE org.job_title FORCE ROW LEVEL SECURITY;
