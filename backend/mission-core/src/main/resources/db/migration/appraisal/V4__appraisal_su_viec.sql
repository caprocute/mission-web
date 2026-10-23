-- CORE-04-13 · Sự việc đã có kết luận của cấp có thẩm quyền (BS-03 ADR-33 DC-05; appraisals.yaml
-- createAppraisalIncident, listAppraisalIncidents, getAppraisalIncident). Điều kiện loại trừ của Policy Pack đọc
-- bảng này qua đầu vào tính điểm (listAppraisalScoringInputs, trường incidents).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục "đầu vào điểm công việc, phạm vi phụ trách, sự việc"):
--   * Không phân vùng theo kỳ: hợp đồng cho phép bỏ trống kỳ (sự việc gắn với người, kỳ suy ra từ ngày kết luận),
--     nên khoá chính là (tenant_id, id) như các bảng không theo kỳ của TL-03.
--   * org_unit_id (bổ sung, suy luận) là đơn vị của hồ sơ đánh giá của người tại lúc ghi, để lọc phạm vi đơn vị
--     khi đọc danh sách; TL-03 chưa có DDL của bảng này.
--   * Loại sự việc và tác động là mã theo danh mục của hồ sơ tập khách hàng, không ràng buộc CHECK tập giá trị
--     trong lõi (YC-TK-11): lõi không chứa giá trị riêng của khách hàng.
--   * Sự việc không sửa, không xoá (truy vết); ghi nhầm thì ghi sự việc mới.

CREATE TABLE ${flyway:defaultSchema}.incident (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    person_id uuid NOT NULL,
    period_id uuid,
    org_unit_id uuid NOT NULL,
    incident_type varchar(32) NOT NULL,
    concluded_on date NOT NULL,
    document_no varchar(64) NOT NULL,
    document_ref varchar(200),
    impact varchar(32),
    summary_enc bytea,
    dek_id uuid,
    CONSTRAINT pk_incident PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_incident__type CHECK (incident_type ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_incident__impact CHECK (impact IS NULL OR impact ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_incident__dek CHECK (summary_enc IS NULL OR dek_id IS NOT NULL)
);
CREATE INDEX ix_incident__person ON ${flyway:defaultSchema}.incident (tenant_id, person_id, concluded_on);
CREATE INDEX ix_incident__period ON ${flyway:defaultSchema}.incident (tenant_id, period_id) WHERE period_id IS NOT NULL;
CREATE INDEX ix_incident__unit ON ${flyway:defaultSchema}.incident (tenant_id, org_unit_id);

COMMENT ON TABLE ${flyway:defaultSchema}.incident IS 'Sự việc đã có kết luận của cấp có thẩm quyền, đầu vào của điều kiện loại trừ (BS-03 DC-05)';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.id IS '{"cls":"C1","desc":"Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.created_by IS '{"cls":"C1","desc":"Người ghi sự việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.person_id IS '{"cls":"C1","desc":"Người có sự việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.period_id IS '{"cls":"C1","desc":"Kỳ áp dụng; trống thì theo ngày kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.org_unit_id IS '{"cls":"C1","desc":"Đơn vị của hồ sơ đánh giá của người khi ghi, dùng lọc phạm vi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.incident_type IS '{"cls":"C1","desc":"Mã loại sự việc theo danh mục của hồ sơ tập khách hàng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.concluded_on IS '{"cls":"C1","desc":"Ngày kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.document_no IS '{"cls":"C1","desc":"Số văn bản kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.document_ref IS '{"cls":"C1","desc":"Tham chiếu văn bản đã lưu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.impact IS '{"cls":"C1","desc":"Mã tác động theo danh mục của hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.summary_enc IS '{"cls":"C2","desc":"Tóm tắt kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incident.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.incident');
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.incident FROM svc_${flyway:defaultSchema};
