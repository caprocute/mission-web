-- CORE-01-14 · BS-03 ADR-33 DC-04 theo hợp đồng NEN-044: okr.yaml cho nhãn tử số, mẫu số tối đa 200 ký tự
-- (V6 đặt 100) nên nới cột; mốc nhận lại lần ghi nhận mới để đính chính ngày đạt (suy luận theo BR-03-03),
-- minh chứng giới hạn 200 ký tự kiểm ở tầng ứng dụng.

ALTER TABLE ${flyway:defaultSchema}.key_result ALTER COLUMN numerator_label TYPE varchar(200);
ALTER TABLE ${flyway:defaultSchema}.key_result ALTER COLUMN denominator_label TYPE varchar(200);
