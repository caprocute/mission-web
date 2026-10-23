-- CORE-03-05 · BR-07-09 · SCR-0702: ô do AI điền mà người nộp đã sửa, lưu từ lúc lưu nháp để khi
-- xác nhận nộp ghi sổ cái danh sách ô giữ nguyên và ô đã sửa. TL-03 chưa có cột này (lệch ghi ở
-- wiki trang 14); mảng JSON tên ô theo hợp đồng `aiFieldsEdited` (docNumber, docDate, docSummary, taskId).
ALTER TABLE ${flyway:defaultSchema}.evidence_version
    ADD COLUMN ai_fields_edited jsonb;
ALTER TABLE ${flyway:defaultSchema}.evidence_version
    ADD CONSTRAINT ck_evidence_version__ai_fields_edited
    CHECK (ai_fields_edited IS NULL OR (ai_prefill AND jsonb_typeof(ai_fields_edited) = 'array'));

COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.ai_fields_edited IS '{"cls":"C1","desc":"Ô do AI điền người nộp đã sửa (BR-07-09)"}';
