-- CORE-08-13: biểu mẫu động gắn vào UI Pack và kết quả bộ test gần nhất của biểu mẫu (QT-18,
-- SCR-1802, BR-18-05, BR-18-07). TL-03 chưa có hai cột này (suy luận, wiki trang 14):
-- ui_pack_version.forms_json giữ danh sách {formKey, version} đã gắn; form_schema.last_test_run giữ
-- kết quả bộ test kèm băm phần tính điểm (schema_json, cel_rules) lúc chạy, để đổi thuần trình bày
-- không buộc chạy lại test.
ALTER TABLE ${flyway:defaultSchema}.ui_pack_version
    ADD COLUMN forms_json jsonb NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.forms_json IS '{"cls": "C0", "desc": "Biểu mẫu động gắn vào phiên bản (formKey, version)"}';

ALTER TABLE ${flyway:defaultSchema}.form_schema
    ADD COLUMN last_test_run jsonb;
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.last_test_run IS '{"cls": "C0", "desc": "Kết quả bộ test gần nhất kèm băm phần tính điểm"}';
