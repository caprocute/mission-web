-- CORE-04-06 · Đầu vào tính điểm cho SVC-07 (QT-08 bước 2, QT-09, listAppraisalScoringInputs).
--
-- Cột bổ sung (wiki trang 14 mục 58):
--   * appraisal_profile.inputs_changed_at: thời điểm đầu vào tính điểm của người được giao đổi do một nhiệm
--     vụ kết thúc (listener TrangThaiNhiemVuDoi của MOD-02, TL-01 "Giao tiếp giữa module"). Không tăng
--     row_version vì không phải thay đổi của người dùng trên hồ sơ.
ALTER TABLE ${flyway:defaultSchema}.appraisal_profile ADD COLUMN inputs_changed_at timestamptz;
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.inputs_changed_at IS '{"cls":"C1","desc":"Lần cuối đầu vào tính điểm đổi do nhiệm vụ kết thúc"}';
