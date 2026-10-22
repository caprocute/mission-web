-- CORE-01-14 · BS-03 ADR-33 DC-04: kiểu đo mở và kiểu mốc. KR thêm kiểu đo MOC (mốc có thời hạn),
-- chiều TOI_DA (không vượt quá) và nhãn tử số, mẫu số cho KR tỷ lệ; bảng bổ sung okr.kr_milestone
-- lưu các mốc (cả mốc trung gian), ngày đạt và minh chứng. Lõi chỉ ghi ngày đạt; số ngày làm việc
-- chậm tính lúc đọc theo lịch của MOD-08, cách quy đổi ra tỷ lệ hoàn thành nằm trong gói.
-- Bốn kiểu đo và ba chiều cũ giữ nguyên.

ALTER TABLE ${flyway:defaultSchema}.key_result DROP CONSTRAINT ck_key_result__metric_type;
ALTER TABLE ${flyway:defaultSchema}.key_result
    ADD CONSTRAINT ck_key_result__metric_type CHECK (metric_type IN ('SO_LUONG', 'TY_LE', 'CO_KHONG', 'CHI_SO', 'MOC'));
ALTER TABLE ${flyway:defaultSchema}.key_result DROP CONSTRAINT ck_key_result__direction;
ALTER TABLE ${flyway:defaultSchema}.key_result
    ADD CONSTRAINT ck_key_result__direction CHECK (direction IN ('TANG', 'GIAM', 'DAT', 'TOI_DA'));
ALTER TABLE ${flyway:defaultSchema}.key_result ADD COLUMN numerator_label varchar(100);
ALTER TABLE ${flyway:defaultSchema}.key_result ADD COLUMN denominator_label varchar(100);
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.numerator_label IS '{"cls": "C1", "desc": "Nhãn tử số khi check-in KR tỷ lệ (bổ sung BS-03 DC-04)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.denominator_label IS '{"cls": "C1", "desc": "Nhãn mẫu số khi check-in KR tỷ lệ (bổ sung BS-03 DC-04)"}';

CREATE TABLE ${flyway:defaultSchema}.kr_milestone (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    key_result_id uuid NOT NULL,
    seq integer NOT NULL,
    due_date date NOT NULL,
    recognition_condition varchar(1000),
    achieved_at timestamptz,
    evidence_ref varchar(500),
    CONSTRAINT pk_kr_milestone PRIMARY KEY (id),
    CONSTRAINT uq_kr_milestone__seq UNIQUE (tenant_id, key_result_id, seq),
    CONSTRAINT ck_kr_milestone__seq CHECK (seq >= 1)
);
COMMENT ON TABLE ${flyway:defaultSchema}.kr_milestone IS 'Mốc có thời hạn của KR kiểu MOC, gồm mốc trung gian (bổ sung BS-03 DC-04, CORE-01-14)';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.key_result_id IS '{"cls": "C1", "desc": "KR kiểu MOC (tham chiếu logic okr.key_result)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.seq IS '{"cls": "C1", "desc": "Thứ tự mốc trong KR, từ 1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.due_date IS '{"cls": "C1", "desc": "Hạn của mốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.recognition_condition IS '{"cls": "C1", "desc": "Điều kiện công nhận đạt mốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.achieved_at IS '{"cls": "C1", "desc": "Thời điểm ghi nhận đạt mốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_milestone.evidence_ref IS '{"cls": "C1", "desc": "Tham chiếu minh chứng đạt mốc"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.kr_milestone');
