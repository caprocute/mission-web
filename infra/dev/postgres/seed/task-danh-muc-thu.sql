-- Danh mục công việc thử (task.work_catalog, task.catalog_item, MOD-02, QT-04, SCR-0401) của tenant thử
-- d0000000-0000-4000-8000-000000000001 cho dev: hợp đồng tasks.yaml chỉ có nhập mục vào danh mục đã có và tạo
-- phiên bản mới, không có thao tác tạo danh mục đầu tiên, nên tenant dev mới không có mục nào để chọn khi lập kế
-- hoạch (SCR-0402) hay giao việc (SCR-0501). Một danh mục cấp đơn vị của Sở Thử nghiệm đang hiệu lực, sáu mục
-- đang áp dụng, có mục 5 điểm (hành trình kế hoạch chọn mục nhỏ điểm nhất). TH-02, TH-03 gắn vị trí "Chuyên viên
-- tổng hợp" của canbo (org-du-lieu-thu.sql) để đăng ký nhiệm vụ theo vị trí (SCR-0503) có mục. Chỉ dữ liệu giả lập; chạy
-- lại không sinh bản ghi trùng. Nạp bởi scripts/seed-org.sh sau migration task của mission-core.
INSERT INTO task.work_catalog (id, tenant_id, created_by, updated_by, code, name, scope, org_unit_id, version, source,
                               status, version_no)
VALUES ('d0000000-0000-4000-8000-0000000d0001', 'd0000000-0000-4000-8000-000000000001',
        '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'DM-SO-THU',
        'Danh mục công việc Sở Thử nghiệm', 'DON_VI', 'd0000000-0000-4000-8000-000000000100', 1, 'TU_TAO',
        'HIEU_LUC', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO task.catalog_item (id, tenant_id, created_by, updated_by, work_catalog_id, code, name, product_name, unit,
                               difficulty, standard_score, quality_standard, evidence_requirement, task_group,
                               standard_days, status)
SELECT i.id::uuid, 'd0000000-0000-4000-8000-000000000001', '00000000-0000-7000-8000-000000000000',
       '00000000-0000-7000-8000-000000000000', 'd0000000-0000-4000-8000-0000000d0001', i.code, i.name, i.product,
       i.unit, i.difficulty, i.score, i.quality, i.evidence, i.task_group, i.days, 'AP_DUNG'
FROM (VALUES
  ('d0000000-0000-4000-8000-0000000d0101', 'TH-01', 'Tổng hợp báo cáo tình hình hoạt động tháng',
   'Báo cáo tháng', 'Báo cáo', 2, 10.0, 'Đúng mẫu, đủ số liệu của các phòng, gửi đúng hạn',
   'Bản báo cáo đã trình ký', 'THUONG_XUYEN', 5),
  ('d0000000-0000-4000-8000-0000000d0102', 'TH-02', 'Soạn thảo công văn trả lời đơn vị',
   'Công văn', 'Văn bản', 1, 5.0, 'Đúng thể thức, trả lời đủ nội dung đề nghị',
   'Công văn đã phát hành', 'THUONG_XUYEN', 3),
  ('d0000000-0000-4000-8000-0000000d0103', 'TH-03', 'Cập nhật cơ sở dữ liệu văn bản đến',
   'Sổ văn bản đến cập nhật', 'Lượt', 1, 5.0, 'Đủ trường thông tin, không trùng lặp',
   NULL, 'THUONG_XUYEN', 1),
  ('d0000000-0000-4000-8000-0000000d0104', 'TH-04', 'Xây dựng kế hoạch công tác quý của đơn vị',
   'Kế hoạch quý', 'Kế hoạch', 3, 20.0, 'Bám chỉ tiêu năm, có phân công và mốc thời gian',
   'Kế hoạch đã được phê duyệt', 'THEN_CHOT', 10),
  ('d0000000-0000-4000-8000-0000000d0105', 'TH-05', 'Tham mưu đề án chuyển đổi số nội bộ',
   'Dự thảo đề án', 'Đề án', 4, 30.0, 'Có khảo sát hiện trạng, giải pháp và dự toán',
   'Dự thảo đề án đã trình lãnh đạo', 'THEN_CHOT', 30),
  ('d0000000-0000-4000-8000-0000000d0106', 'TH-06', 'Tổ chức hội nghị sơ kết của đơn vị',
   'Hội nghị sơ kết', 'Hội nghị', 3, 15.0, 'Có kế hoạch, tài liệu và biên bản hội nghị',
   'Biên bản hội nghị', 'THEN_CHOT', 15)
) AS i(id, code, name, product, unit, difficulty, score, quality, evidence, task_group, days)
ON CONFLICT (id) DO NOTHING;

-- Mục đăng ký được theo vị trí việc làm (SCR-0503): chỉ gán khi còn trống, chạy lại không đổi gì.
UPDATE task.catalog_item
   SET position_id = 'd0000000-0000-4000-8000-000000000401', row_version = row_version + 1
 WHERE tenant_id = 'd0000000-0000-4000-8000-000000000001'
   AND id IN ('d0000000-0000-4000-8000-0000000d0102', 'd0000000-0000-4000-8000-0000000d0103')
   AND position_id IS NULL;
