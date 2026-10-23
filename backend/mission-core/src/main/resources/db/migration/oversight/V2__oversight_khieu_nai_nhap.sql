-- CORE-07-02 · Khiếu nại nháp và ký số khi gửi (QT-12 bước 1, 2; SCR-1201).
-- Hợp đồng oversight.yaml cho lưu nháp trước khi gửi (status null, kiểm đủ trường chỉ khi gửi) nhưng V1 theo TL-03
-- bắt buộc status, filed_at, response_deadline, handler_unit_id, content_enc và dek_id. Bản này (wiki trang 14):
--   * Bỏ NOT NULL của các cột chỉ có khi gửi; CHECK "đã có trạng thái thì đủ thời điểm gửi, hạn trả lời, đơn vị xử
--     lý và nội dung" giữ ràng buộc của TL-03 cho mọi khiếu nại đã gửi.
--   * Thêm code (KN-yyyy-nnnn, hợp đồng ComplaintSummary.code, suy luận), reserve_opinion (cờ SCR-1201 trường 7,
--     suy luận) và sign_request_id (yêu cầu ký nội dung ở SVC-10, như reopen_approval.sign_request_id).
--   * dek_id thay bằng CHECK "có cột _enc thì có dek_id" như post_audit.

ALTER TABLE ${flyway:defaultSchema}.complaint
    ALTER COLUMN status DROP NOT NULL,
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN filed_at DROP NOT NULL,
    ALTER COLUMN response_deadline DROP NOT NULL,
    ALTER COLUMN handler_unit_id DROP NOT NULL,
    ALTER COLUMN content_enc DROP NOT NULL,
    ALTER COLUMN dek_id DROP NOT NULL,
    ADD COLUMN code varchar(16),
    ADD COLUMN reserve_opinion boolean NOT NULL DEFAULT false,
    ADD COLUMN sign_request_id uuid,
    ADD CONSTRAINT ck_complaint__submitted CHECK (status IS NULL OR (filed_at IS NOT NULL
        AND response_deadline IS NOT NULL AND handler_unit_id IS NOT NULL AND content_enc IS NOT NULL)),
    ADD CONSTRAINT ck_complaint__dek CHECK ((content_enc IS NULL AND request_text_enc IS NULL
        AND reserve_opinion_enc IS NULL) OR dek_id IS NOT NULL),
    ADD CONSTRAINT uq_complaint__code UNIQUE (period_bucket, tenant_id, code);
CREATE INDEX ix_complaint__sign_request ON ${flyway:defaultSchema}.complaint (tenant_id, sign_request_id);

COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.code IS '{"cls":"C1","desc":"Mã khiếu nại KN-yyyy-nnnn (định dạng kiểm ở mã)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.reserve_opinion IS '{"cls":"C1","desc":"Bảo lưu ý kiến nếu không được chấp nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký nội dung khiếu nại ở SVC-10"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.status IS '{"cls":"C1","desc":"Trạng thái khiếu nại; null khi còn nháp"}';
