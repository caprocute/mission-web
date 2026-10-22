-- CORE-01-03 · SCR-0202 lưu nháp tự động: hợp đồng okr.yaml (OkrKeyResult.targetValue,
-- OkrKeyResultInput) cho phép KR còn NHAP chưa có chỉ tiêu; máy chủ chặn trình khi thiếu (BR-02-04).
-- Hạn KR vắng thì máy chủ đặt ngày cuối kỳ nên due_date vẫn NOT NULL.

ALTER TABLE ${flyway:defaultSchema}.key_result ALTER COLUMN target_value DROP NOT NULL;
ALTER TABLE ${flyway:defaultSchema}.key_result
    ADD CONSTRAINT ck_key_result__target_draft CHECK (status = 'NHAP' OR target_value IS NOT NULL);
