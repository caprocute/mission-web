-- CORE-07-04 · Giải trình, kết luận và văn bản trả lời khiếu nại (QT-12 bước 5 đến 10; SCR-1202). TL-03 chỉ có
-- outcome cho khiếu nại, còn complaint_response chỉ thêm và bản ghi KET_LUAN bắt buộc sign_request_id. Bản này
-- thêm (suy luận, wiki trang 14):
--   * complaint: bản kết luận người xử lý ghi trước khi người có thẩm quyền ký (nội dung văn bản trả lời mã hoá
--     C2), yêu cầu mở lại kỳ thi hành điều chỉnh, yêu cầu ký văn bản trả lời ở SVC-10 và thời điểm ký xong.
--     Bản ghi KET_LUAN của complaint_response chỉ được thêm khi mở yêu cầu ký, nên ràng buộc chỉ thêm của V1
--     giữ nguyên.
--   * complaint_response: bên được yêu cầu giải trình (người hoặc đơn vị), hạn giải trình, yêu cầu mà một giải
--     trình trả lời và tệp kèm, theo ExplanationParty, ComplaintResponseRecord của oversight.yaml.
-- Số phiên bản V4, V5 đã có ở nhánh khác của MOD-07 nên bản này lấy V6.

ALTER TABLE ${flyway:defaultSchema}.complaint
    ADD COLUMN conclusion_resolution varchar(20),
    ADD COLUMN conclusion_adjustments_json jsonb,
    ADD COLUMN conclusion_enc bytea,
    ADD COLUMN conclusion_dek_id uuid,
    ADD COLUMN conclusion_doc_ref varchar(200),
    ADD COLUMN no_explanation_note varchar(2000),
    ADD COLUMN concluded_by uuid,
    ADD COLUMN concluded_at timestamptz,
    ADD COLUMN reopen_request_id uuid,
    ADD COLUMN response_sign_request_id uuid,
    ADD COLUMN responded_at timestamptz,
    ADD CONSTRAINT ck_complaint__conclusion_resolution CHECK (conclusion_resolution IS NULL
        OR conclusion_resolution IN ('GIU_NGUYEN', 'CHAP_NHAN_MOT_PHAN', 'CHAP_NHAN')),
    ADD CONSTRAINT ck_complaint__conclusion CHECK (conclusion_resolution IS NULL OR (conclusion_enc IS NOT NULL
        AND conclusion_dek_id IS NOT NULL AND concluded_by IS NOT NULL AND concluded_at IS NOT NULL)),
    ADD CONSTRAINT ck_complaint__conclusion_adjustments CHECK (conclusion_adjustments_json IS NULL
        OR jsonb_typeof(conclusion_adjustments_json) = 'array'),
    ADD CONSTRAINT ck_complaint__response_sign CHECK (response_sign_request_id IS NULL
        OR conclusion_resolution IS NOT NULL);
CREATE INDEX ix_complaint__response_sign_request ON ${flyway:defaultSchema}.complaint (tenant_id, response_sign_request_id);

COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.conclusion_resolution IS '{"cls":"C1","desc":"Kết quả xử lý của kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.conclusion_adjustments_json IS '{"cls":"C1","desc":"Điều chỉnh đề nghị của kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.conclusion_enc IS '{"cls":"C2","desc":"Nội dung văn bản trả lời của kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.conclusion_dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu của nội dung kết luận (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.conclusion_doc_ref IS '{"cls":"C1","desc":"Số văn bản trả lời sinh từ mẫu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.no_explanation_note IS '{"cls":"C1","desc":"Ghi chú kết luận không qua giải trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.concluded_by IS '{"cls":"C1","desc":"Người ghi kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.concluded_at IS '{"cls":"C1","desc":"Thời điểm ghi kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.reopen_request_id IS '{"cls":"C1","desc":"Yêu cầu mở lại kỳ thi hành kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.response_sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký văn bản trả lời ở SVC-10"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.responded_at IS '{"cls":"C1","desc":"Thời điểm văn bản trả lời được ký"}';

ALTER TABLE ${flyway:defaultSchema}.complaint_response
    ADD COLUMN party_kind varchar(8),
    ADD COLUMN party_id uuid,
    ADD COLUMN due_date date,
    ADD COLUMN request_id uuid,
    ADD COLUMN attachment_object_ids uuid[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_complaint_response__party_kind CHECK (party_kind IS NULL OR party_kind IN ('NGUOI', 'DON_VI')),
    ADD CONSTRAINT ck_complaint_response__request CHECK (record_kind <> 'YEU_CAU' OR (party_kind IS NOT NULL
        AND party_id IS NOT NULL AND due_date IS NOT NULL)),
    ADD CONSTRAINT ck_complaint_response__explanation CHECK (record_kind <> 'GIAI_TRINH' OR request_id IS NOT NULL);
CREATE INDEX ix_complaint_response__due ON ${flyway:defaultSchema}.complaint_response (tenant_id, record_kind, due_date);

COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.party_kind IS '{"cls":"C1","desc":"Bên được yêu cầu giải trình: người hoặc đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.party_id IS '{"cls":"C1","desc":"Người hoặc đơn vị được yêu cầu giải trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.due_date IS '{"cls":"C1","desc":"Hạn giải trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.request_id IS '{"cls":"C1","desc":"Yêu cầu giải trình được trả lời"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.attachment_object_ids IS '{"cls":"C1","desc":"Tệp kèm ở SVC-08","type_exception":"Danh sách mã tệp kèm như complaint.attachment_object_ids"}';
