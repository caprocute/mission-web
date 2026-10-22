-- CORE-04-04 · Phiếu tự đánh giá (QT-09, SCR-0901, BR-09-05, BR-09-12, BR-09-15).
--
-- Cột bổ sung (wiki trang 14 mục 54):
--   * self_assessment.reason_enc giữ giải trình mức tự đề xuất của bản nháp; khi nhận EVT-18 nội dung được
--     mã hoá lại vào rating_proposal.reason_enc của mức TU_DE_XUAT (AAD theo dòng đích).
--   * appraisal_profile.is_self_assessment_missing: quá hạn không tự đánh giá, chuyển thẩm định với mức tự
--     đề xuất trống (QT-09 ngoại lệ 3, trường noSelfAssessment của hợp đồng).
--   * Mức tự đề xuất, tiêu chí chung tự nhận và mã minh chứng đột phá của bản nháp nằm trong structured_data
--     (không có văn bản tự do), không thêm cột.
ALTER TABLE ${flyway:defaultSchema}.self_assessment ADD COLUMN reason_enc bytea;
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.reason_enc IS '{"cls":"C2","desc":"Giải trình mức tự đề xuất khác mức tham khảo (bản nháp)"}';
ALTER TABLE ${flyway:defaultSchema}.self_assessment DROP CONSTRAINT ck_self_assessment__dek;
ALTER TABLE ${flyway:defaultSchema}.self_assessment ADD CONSTRAINT ck_self_assessment__dek CHECK (dek_id IS NOT NULL
    OR num_nonnulls(content_enc, strengths_enc, weaknesses_enc, remedial_plan_enc, breakthrough_enc, reason_enc) = 0);

ALTER TABLE ${flyway:defaultSchema}.appraisal_profile
    ADD COLUMN is_self_assessment_missing boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.is_self_assessment_missing IS '{"cls":"C1","desc":"Quá hạn không tự đánh giá, chuyển thẩm định với mức tự đề xuất trống"}';

CREATE INDEX ix_self_assessment__sign_request ON ${flyway:defaultSchema}.self_assessment (tenant_id, sign_request_id);
