-- CORE-03-01 · Schema product (MOD-03): sản phẩm, minh chứng có phiên bản, nghiệm thu năm mức và khoá
-- tệp mật đã bọc (TL-03 "Schema product (MOD-03)", "DDL mẫu: product.evidence_version"; QT-07).
-- outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key đến từ db/common-business
-- (NEN-032); hàm RLS và phân vùng từ db/common (NEN-033). product.period_state và fn_guard_period_write
-- thuộc CORE-04-01. EVT-07, EVT-08 phát ở CORE-03-03, CORE-03-07.
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 45):
--   * Khoá chính, khoá duy nhất, khoá ngoại chứa period_bucket và tenant_id như schema task (TL-03 ghi
--     PK (period_bucket, id)); UQ số phiên bản theo DDL mẫu (period_bucket đứng đầu) thay cho từ điển.
--   * deliverable.status dài 20 vì DA_XAC_NHAN_MOT_PHAN có 20 ký tự (TL-03 ghi varchar(16)).
--   * acceptance.dek_id không khai khoá ngoại sang keyring.dek: mig_product không có quyền REFERENCES trên
--     keyring và keyring được migrate sau các schema nghiệp vụ; thay bằng CHECK có comment_enc thì có dek_id,
--     như org.sync_discrepancy. Giữ cả comment (C1) và comment_enc (C2) như TL-03 (câu hỏi mở).
--   * doc_number dài 100 theo hợp đồng; e2ee_key_wrap không phân vùng, cấu trúc theo task.e2ee_key_wrap.
--   * Thu hồi cả DELETE và TRUNCATE của svc_product trên bốn bảng nghiệp vụ.

-- ---------------------------------------------------------------- deliverable
CREATE TABLE ${flyway:defaultSchema}.deliverable (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    task_id uuid NOT NULL,
    name varchar(500) NOT NULL,
    deliverable_type varchar(12) NOT NULL,
    unit varchar(32) NOT NULL,
    quantity_planned numeric(18,4) NOT NULL,
    quantity_accepted numeric(18,4) NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'CHUA_CO',
    CONSTRAINT pk_deliverable PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT ck_deliverable__type CHECK (deliverable_type IN ('VAN_BAN', 'BAO_CAO', 'SO_LIEU', 'SU_KIEN', 'KHAC')),
    CONSTRAINT ck_deliverable__status CHECK (status IN ('CHUA_CO', 'DANG_THUC_HIEN', 'DA_XAC_NHAN_MOT_PHAN', 'DA_XAC_NHAN')),
    CONSTRAINT ck_deliverable__quantity CHECK (quantity_planned > 0 AND quantity_accepted >= 0)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_deliverable__task ON ${flyway:defaultSchema}.deliverable (tenant_id, task_id);

COMMENT ON TABLE ${flyway:defaultSchema}.deliverable IS 'Sản phẩm của nhiệm vụ (MOD-03)';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.task_id IS '{"cls":"C1","desc":"Nhiệm vụ (tham chiếu logic sang task.task)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.name IS '{"cls":"C1","desc":"Tên sản phẩm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.deliverable_type IS '{"cls":"C1","desc":"Loại sản phẩm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.unit IS '{"cls":"C0","desc":"Đơn vị tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.quantity_planned IS '{"cls":"C1","desc":"Số lượng kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.quantity_accepted IS '{"cls":"C1","desc":"Số lượng đã được xác nhận (đầu vào cấu phần A)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.deliverable.status IS '{"cls":"C1","desc":"Trạng thái tổng hợp"}';

-- ---------------------------------------------------------------- evidence
CREATE TABLE ${flyway:defaultSchema}.evidence (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    deliverable_id uuid NOT NULL,
    task_id uuid NOT NULL,
    title varchar(500) NOT NULL,
    current_version_id uuid,
    current_status varchar(20) NOT NULL DEFAULT 'NHAP',
    source_type varchar(12) NOT NULL,
    external_doc_ref varchar(200),
    CONSTRAINT pk_evidence PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_evidence__deliverable FOREIGN KEY (period_bucket, tenant_id, deliverable_id)
        REFERENCES ${flyway:defaultSchema}.deliverable (period_bucket, tenant_id, id),
    CONSTRAINT ck_evidence__current_status CHECK (current_status IN
        ('NHAP', 'DA_NOP', 'DANG_NGHIEM_THU', 'DA_XAC_NHAN', 'YEU_CAU_BO_SUNG', 'TU_CHOI')),
    CONSTRAINT ck_evidence__source_type CHECK (source_type IN ('TAI_LEN', 'VAN_BAN', 'HE_THONG'))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_evidence__task ON ${flyway:defaultSchema}.evidence (tenant_id, task_id);
CREATE INDEX ix_evidence__deliverable ON ${flyway:defaultSchema}.evidence (period_bucket, tenant_id, deliverable_id);

COMMENT ON TABLE ${flyway:defaultSchema}.evidence IS 'Minh chứng của sản phẩm, có nhiều phiên bản';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.deliverable_id IS '{"cls":"C1","desc":"Sản phẩm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.task_id IS '{"cls":"C1","desc":"Nhiệm vụ (dư thừa có chủ đích để truy vấn nhanh)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.title IS '{"cls":"C1","desc":"Tên minh chứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.current_version_id IS '{"cls":"C1","desc":"Phiên bản mới nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.current_status IS '{"cls":"C1","desc":"Trạng thái của phiên bản mới nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.source_type IS '{"cls":"C1","desc":"Nguồn minh chứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.external_doc_ref IS '{"cls":"C1","desc":"Văn bản bên ngoài làm minh chứng"}';

-- ---------------------------------------------------------------- evidence_version
CREATE TABLE ${flyway:defaultSchema}.evidence_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    evidence_id uuid NOT NULL,
    task_id uuid NOT NULL,
    version_no int NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'NHAP',
    object_id uuid,
    object_version_id uuid,
    content_sha256 bytea,
    size_bytes bigint,
    media_type varchar(127),
    file_name varchar(255),
    source varchar(20) NOT NULL,
    source_doc_ref varchar(200),
    ai_prefill boolean NOT NULL DEFAULT false,
    ai_suggestion_id uuid,
    user_confirmed_at timestamptz,
    scan_status varchar(12) NOT NULL DEFAULT 'CHO_QUET',
    is_duplicate_suspect boolean NOT NULL DEFAULT false,
    duplicate_of_id uuid,
    has_e2ee boolean NOT NULL DEFAULT false,
    submitted_by uuid,
    submitted_at timestamptz,
    accepted_at timestamptz,
    note varchar(2000),
    excluded_period_id uuid,
    reuse_justification varchar(1000),
    attested_at timestamptz,
    doc_number varchar(100),
    doc_date date,
    doc_summary varchar(2000),
    CONSTRAINT pk_evidence_version PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_evidence_version__evidence FOREIGN KEY (period_bucket, tenant_id, evidence_id)
        REFERENCES ${flyway:defaultSchema}.evidence (period_bucket, tenant_id, id),
    CONSTRAINT uq_evidence_version__no UNIQUE (period_bucket, tenant_id, evidence_id, version_no),
    CONSTRAINT ck_evidence_version__status CHECK (status IN
        ('NHAP', 'DA_NOP', 'DANG_NGHIEM_THU', 'DA_XAC_NHAN', 'YEU_CAU_BO_SUNG', 'TU_CHOI')),
    CONSTRAINT ck_evidence_version__source CHECK (source IN
        ('TAI_LEN', 'VAN_BAN_HE_THONG', 'OCR_DI_DONG', 'TICH_HOP')),
    CONSTRAINT ck_evidence_version__scan CHECK (scan_status IN ('CHO_QUET', 'SACH', 'NHIEM', 'LOI')),
    CONSTRAINT ck_evidence_version__hash CHECK (content_sha256 IS NULL OR length(content_sha256) = 32),
    CONSTRAINT ck_evidence_version__version_no CHECK (version_no > 0),
    CONSTRAINT ck_evidence_version__submitted CHECK (
        status = 'NHAP' OR (object_id IS NOT NULL AND content_sha256 IS NOT NULL
                            AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_evidence_version__ai_confirm CHECK (
        NOT ai_prefill OR status = 'NHAP' OR user_confirmed_at IS NOT NULL),
    CONSTRAINT ck_evidence_version__clean CHECK (
        status IN ('NHAP', 'DA_NOP', 'TU_CHOI') OR scan_status = 'SACH')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_evidence_version__sha ON ${flyway:defaultSchema}.evidence_version (tenant_id, content_sha256)
    WHERE content_sha256 IS NOT NULL;
CREATE INDEX ix_evidence_version__task ON ${flyway:defaultSchema}.evidence_version (tenant_id, task_id, status);
CREATE INDEX ix_evidence_version__review_p ON ${flyway:defaultSchema}.evidence_version (tenant_id, submitted_at)
    WHERE status IN ('DA_NOP', 'DANG_NGHIEM_THU');

COMMENT ON TABLE ${flyway:defaultSchema}.evidence_version IS 'Phiên bản minh chứng; bất biến sau khi nộp';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.evidence_id IS '{"cls":"C1","desc":"Minh chứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.version_no IS '{"cls":"C1","desc":"Số phiên bản (đóng băng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.status IS '{"cls":"C1","desc":"Trạng thái nghiệm thu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.object_id IS '{"cls":"C1","desc":"evidence.object_meta.id ở SVC-08 (tham chiếu logic), đóng băng sau nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.object_version_id IS '{"cls":"C1","desc":"Phiên bản đối tượng ở SVC-08, đóng băng sau nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.content_sha256 IS '{"cls":"C1","desc":"Mã băm SHA-256 của tệp (32 byte)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.size_bytes IS '{"cls":"C1","desc":"Kích thước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.media_type IS '{"cls":"C1","desc":"Kiểu MIME"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.file_name IS '{"cls":"C1","desc":"Tên tệp gốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.source IS '{"cls":"C1","desc":"Tải lên, văn bản từ hệ thống, OCR di động, tích hợp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.source_doc_ref IS '{"cls":"C1","desc":"Văn bản nguồn khi lấy từ hệ văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.ai_prefill IS '{"cls":"C1","desc":"Có trường do AI, OCR điền sẵn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.ai_suggestion_id IS '{"cls":"C1","desc":"Gợi ý AI tương ứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.user_confirmed_at IS '{"cls":"C1","desc":"Người dùng xác nhận các trường do AI điền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.scan_status IS '{"cls":"C1","desc":"Kết quả quét mã độc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.is_duplicate_suspect IS '{"cls":"C1","desc":"Nghi trùng theo băm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.duplicate_of_id IS '{"cls":"C1","desc":"Phiên bản trùng đầu tiên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.has_e2ee IS '{"cls":"C1","desc":"Tệp mã hoá đầu cuối (minh chứng của nhiệm vụ mật)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.submitted_by IS '{"cls":"C1","desc":"Người nộp (EVT-07)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.submitted_at IS '{"cls":"C1","desc":"Thời điểm nộp (EVT-07)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.accepted_at IS '{"cls":"C1","desc":"Thời điểm xác nhận (EVT-08)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.note IS '{"cls":"C1","desc":"Ghi chú của người nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.excluded_period_id IS '{"cls":"C1","desc":"Kỳ mà phiên bản không được tính (còn DA_NOP khi khoá kỳ)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.reuse_justification IS '{"cls":"C1","desc":"Giải trình khi dùng lại minh chứng của kỳ trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.attested_at IS '{"cls":"C1","desc":"Thời điểm cam kết tính xác thực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.doc_number IS '{"cls":"C1","desc":"Số, ký hiệu văn bản khi minh chứng là văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.doc_date IS '{"cls":"C1","desc":"Ngày văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version.doc_summary IS '{"cls":"C1","desc":"Trích yếu"}';

-- Phiên bản đã nộp bất biến (tiêu chí I5) và không huỷ được xác nhận (BR-07-01; TL-03 DDL mẫu).
CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_evidence_version() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF OLD.status <> 'NHAP' AND (
           NEW.object_id         IS DISTINCT FROM OLD.object_id OR
           NEW.object_version_id IS DISTINCT FROM OLD.object_version_id OR
           NEW.content_sha256    IS DISTINCT FROM OLD.content_sha256 OR
           NEW.size_bytes        IS DISTINCT FROM OLD.size_bytes OR
           NEW.version_no        IS DISTINCT FROM OLD.version_no) THEN
        RAISE EXCEPTION 'Phiên bản minh chứng đã nộp không được sửa nội dung; hãy tạo phiên bản mới'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'DA_XAC_NHAN' AND NEW.status <> 'DA_XAC_NHAN' THEN
        RAISE EXCEPTION 'Không được huỷ xác nhận phiên bản minh chứng; dùng quy trình hậu kiểm'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_evidence_version__freeze BEFORE UPDATE ON ${flyway:defaultSchema}.evidence_version
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_evidence_version();

-- ---------------------------------------------------------------- acceptance
CREATE TABLE ${flyway:defaultSchema}.acceptance (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    evidence_version_id uuid NOT NULL,
    task_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    decision varchar(20) NOT NULL,
    quality_level numeric(3,2),
    revision_cause varchar(32),
    is_objective_revision boolean NOT NULL DEFAULT false,
    revision_count smallint NOT NULL DEFAULT 0,
    comment varchar(4000),
    decided_at timestamptz NOT NULL,
    criteria_results jsonb NOT NULL DEFAULT '[]'::jsonb,
    comment_enc bytea,
    dek_id uuid,
    CONSTRAINT pk_acceptance PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_acceptance__evidence_version FOREIGN KEY (period_bucket, tenant_id, evidence_version_id)
        REFERENCES ${flyway:defaultSchema}.evidence_version (period_bucket, tenant_id, id),
    CONSTRAINT ck_acceptance__decision CHECK (decision IN ('DA_XAC_NHAN', 'YEU_CAU_BO_SUNG', 'TU_CHOI')),
    CONSTRAINT ck_acceptance__quality_level CHECK (quality_level IS NULL OR quality_level IN (1, 0.75, 0.5, 0.25, 0)),
    CONSTRAINT ck_acceptance__quality_required CHECK (decision <> 'DA_XAC_NHAN' OR quality_level IS NOT NULL),
    CONSTRAINT ck_acceptance__revision_cause CHECK (revision_cause IS NULL OR revision_cause IN
        ('THE_THUC', 'NOI_DUNG_KHONG_COT_LOI', 'NOI_DUNG_CO_BAN', 'SAI_SOT_NGHIEM_TRONG', 'SAI_CHU_TRUONG')),
    CONSTRAINT ck_acceptance__revision_count CHECK (revision_count >= 0),
    CONSTRAINT ck_acceptance__criteria CHECK (jsonb_typeof(criteria_results) = 'array'),
    CONSTRAINT ck_acceptance__dek CHECK (comment_enc IS NULL OR dek_id IS NOT NULL)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_acceptance__task ON ${flyway:defaultSchema}.acceptance (tenant_id, task_id);
CREATE INDEX ix_acceptance__evidence_version ON ${flyway:defaultSchema}.acceptance (period_bucket, tenant_id, evidence_version_id);

COMMENT ON TABLE ${flyway:defaultSchema}.acceptance IS 'Nghiệm thu năm mức của phiên bản minh chứng';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.evidence_version_id IS '{"cls":"C1","desc":"Phiên bản được nghiệm thu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.reviewer_id IS '{"cls":"C1","desc":"Người nghiệm thu, khác người nộp (BR-07-06)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.decision IS '{"cls":"C1","desc":"Quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.quality_level IS '{"cls":"C1","desc":"Mức chất lượng năm mức (PL 3 mục 2)","type_exception":"TL-03 chốt numeric(3,2) cho năm mức 1; 0,75; 0,5; 0,25; 0"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.revision_cause IS '{"cls":"C1","desc":"Nguyên nhân chỉnh sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.is_objective_revision IS '{"cls":"C1","desc":"Chỉnh sửa do nguyên nhân khách quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.revision_count IS '{"cls":"C1","desc":"Số lần chỉnh sửa (PL A4)","type_exception":"TL-03 chốt smallint"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.comment IS '{"cls":"C1","desc":"Ý kiến nghiệm thu về sản phẩm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.decided_at IS '{"cls":"C1","desc":"Thời điểm quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.criteria_results IS '{"cls":"C1","desc":"Kết quả từng tiêu chí chất lượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.comment_enc IS '{"cls":"C2","desc":"Nhận xét nghiệm thu (mã hoá phong bì)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';

-- Người nghiệm thu khác người nộp (BR-07-06, PL 4 V.1): so với evidence_version.submitted_by.
CREATE FUNCTION ${flyway:defaultSchema}.fn_check_acceptance_reviewer() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_submitter uuid;
BEGIN
    SELECT v.submitted_by INTO v_submitter
      FROM ${flyway:defaultSchema}.evidence_version v
     WHERE v.period_bucket = NEW.period_bucket
       AND v.tenant_id = NEW.tenant_id
       AND v.id = NEW.evidence_version_id;
    IF v_submitter IS NOT NULL AND v_submitter = NEW.reviewer_id THEN
        RAISE EXCEPTION 'Người nghiệm thu phải khác người nộp minh chứng (BR-07-06)'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_acceptance__reviewer';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_acceptance__reviewer BEFORE INSERT OR UPDATE OF reviewer_id, evidence_version_id
    ON ${flyway:defaultSchema}.acceptance
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_check_acceptance_reviewer();

-- ---------------------------------------------------------------- e2ee_key_wrap
CREATE TABLE ${flyway:defaultSchema}.e2ee_key_wrap (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    object_type varchar(16) NOT NULL DEFAULT 'EVIDENCE_FILE',
    object_id uuid NOT NULL,
    recipient_person_id uuid NOT NULL,
    recipient_key_fingerprint bytea NOT NULL,
    wrap_alg varchar(24) NOT NULL,
    wrapped_cek bytea NOT NULL,
    ephemeral_pub bytea,
    granted_by uuid NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_e2ee_key_wrap PRIMARY KEY (id),
    CONSTRAINT uq_e2ee_key_wrap__recipient UNIQUE (tenant_id, object_id, recipient_key_fingerprint),
    CONSTRAINT ck_e2ee_key_wrap__object_type CHECK (object_type IN ('EVIDENCE_FILE')),
    CONSTRAINT ck_e2ee_key_wrap__wrap_alg CHECK (wrap_alg IN ('ECDH-ES+A256KW', 'RSA-OAEP-256', 'BCY'))
);
CREATE INDEX ix_e2ee_key_wrap__recipient ON ${flyway:defaultSchema}.e2ee_key_wrap (tenant_id, recipient_person_id);

COMMENT ON TABLE ${flyway:defaultSchema}.e2ee_key_wrap IS 'Khoá nội dung tệp minh chứng mật đã bọc cho từng người nhận (ADR-06)';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.object_type IS '{"cls":"C1","desc":"Loại đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.object_id IS '{"cls":"C1","desc":"Phiên bản minh chứng có tệp mã hoá đầu cuối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.recipient_person_id IS '{"cls":"C1","desc":"Người nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.recipient_key_fingerprint IS '{"cls":"C1","desc":"Khoá công khai đã dùng (org.person_public_key.fingerprint)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.wrap_alg IS '{"cls":"C1","desc":"Thuật toán bọc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.wrapped_cek IS '{"cls":"C1","desc":"Khoá nội dung đã bọc cho người nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.ephemeral_pub IS '{"cls":"C1","desc":"Khoá công khai tạm thời (ECDH-ES)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.granted_by IS '{"cls":"C1","desc":"Người cấp quyền đọc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.revoked_at IS '{"cls":"C1","desc":"Thu hồi (chỉ ngăn tải bản mã về)"}';

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.deliverable');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.evidence');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.evidence_version');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.acceptance');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.e2ee_key_wrap');

REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.deliverable FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.evidence FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.evidence_version FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.acceptance FROM svc_${flyway:defaultSchema};
