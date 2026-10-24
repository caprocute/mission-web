-- QA-03 · Tên chức danh của phân công trong bản sao (bổ sung hợp đồng EVT-15: trường tuỳ chọn jobTitleName), để
-- màn hình chọn người (người xác nhận KR, người duyệt) hiện chức danh mà không gọi đồng bộ sang SVC-04. Cột không có
-- trong TL-03 (wiki trang 14, mục 18a). Sự kiện cũ không mang tên thì để NULL; org-republish điền lại.
ALTER TABLE ${flyway:defaultSchema}.assignment_v
    ADD COLUMN job_title_name varchar(255);

COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.job_title_name IS '{"cls":"C0","desc":"Tên chức danh (org.job_title.name) lúc phát sự kiện"}';
