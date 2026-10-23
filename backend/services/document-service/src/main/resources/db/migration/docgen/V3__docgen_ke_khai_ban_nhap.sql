-- =============================================================================
-- V3__docgen_ke_khai_ban_nhap.sql
-- DV-11-02, DV-11-03 · SVC-11 · QT-09 (SCR-0904 "Xuất Mẫu 3"), QT-11 (BR-11-02, BR-11-04), QT-13 (BR-13-08).
--
-- Cột bổ sung (suy luận, TL-03 "Schema docgen" chưa có):
--   template.manifest_key, manifest_sha256: tệp kê khai trường của mẫu (DL-05) nằm cạnh tệp DOCX trên bucket
--     templates, kèm bảng nhãn in ra biểu mẫu; worker trộn theo tag content control và khoá vùng số liệu.
--     Mẫu cũ không có kê khai vẫn trộn theo chỗ giữ dạng biến cũ.
--   render_job.is_draft: tác vụ sinh bản nháp ngoài gói hồ sơ (createDossierDraftForms), tệp mang dấu "Bản nháp".
--   render_job.reopen_request_id: lần sinh lại sau khi mở lại kỳ hậu kiểm; tệp mới tham chiếu yêu cầu mở lại,
--     không ghi đè render_output cũ (BR-13-08).
-- =============================================================================

ALTER TABLE docgen.template
  ADD COLUMN manifest_key    varchar(512),
  ADD COLUMN manifest_sha256 bytea,
  ADD CONSTRAINT ck_template__manifest
    CHECK ((manifest_key IS NULL) = (manifest_sha256 IS NULL)
           AND (manifest_sha256 IS NULL OR octet_length(manifest_sha256) = 32));

ALTER TABLE docgen.render_job
  ADD COLUMN is_draft          boolean NOT NULL DEFAULT false,
  ADD COLUMN reopen_request_id uuid;

CREATE INDEX ix_render_job__tenant_reopen ON docgen.render_job (tenant_id, reopen_request_id)
  WHERE reopen_request_id IS NOT NULL;

COMMENT ON COLUMN docgen.template.manifest_key IS '{"cls": "C1", "pii": false, "desc": "Khoá tệp kê khai trường của mẫu trên bucket templates"}';
COMMENT ON COLUMN docgen.template.manifest_sha256 IS '{"cls": "C1", "pii": false, "desc": "Băm SHA-256 của tệp kê khai trường"}';
COMMENT ON COLUMN docgen.render_job.is_draft IS '{"cls": "C1", "pii": false, "desc": "Bản nháp ngoài gói hồ sơ, tệp mang dấu Bản nháp"}';
COMMENT ON COLUMN docgen.render_job.reopen_request_id IS '{"cls": "C1", "pii": false, "desc": "Yêu cầu mở lại kỳ dẫn tới lần sinh lại (BR-13-08)"}';
