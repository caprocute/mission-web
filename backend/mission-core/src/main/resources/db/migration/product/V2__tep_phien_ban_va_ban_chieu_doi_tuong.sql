-- CORE-03-03 · Tệp của phiên bản minh chứng và bản chiếu đối tượng SVC-08 (QT-07, SCR-0701).
--
-- Điểm lệch và lựa chọn (wiki trang 14):
--   * Hợp đồng products.yaml cho một phiên bản nhiều tệp (EvidenceVersion.files) và đề xuất bảng con
--     product.evidence_version_file (suy luận); TL-03 chỉ có cột tệp trên evidence_version. Các cột tệp
--     của evidence_version giữ vai trò tổng hợp: object_id, content_sha256 của tệp đầu tiên, scan_status
--     gộp của mọi tệp.
--   * product.evidence_object là bản chiếu của evidence.object_meta do consumer sys.evidence.object.stored.v1
--     và evidence.object.scanned.v1 ghi; MOD-03 không đọc schema evidence của SVC-08.
--   * Hai topic không bảo đảm thứ tự với nhau: kết quả quét có thể đến trước sự kiện lưu, nên dòng bản chiếu
--     cho phép thiếu mã băm, kích thước, kiểu nội dung khi stored_at còn rỗng (chưa nhận sự kiện lưu).
--   * Không lưu tên tệp: sự kiện của SVC-08 không mang tên tệp (C2 ở evidence.object_meta.file_name_enc).

-- ---------------------------------------------------------------- evidence_object
CREATE TABLE ${flyway:defaultSchema}.evidence_object (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    object_version int NOT NULL,
    content_sha256 bytea,
    size_bytes bigint,
    media_type varchar(127),
    has_e2ee boolean NOT NULL DEFAULT false,
    scan_status varchar(12) NOT NULL DEFAULT 'CHO_QUET',
    stored_at timestamptz,
    CONSTRAINT pk_evidence_object PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_evidence_object__hash CHECK (content_sha256 IS NULL OR length(content_sha256) = 32),
    CONSTRAINT ck_evidence_object__stored CHECK (
        stored_at IS NULL OR (content_sha256 IS NOT NULL AND size_bytes IS NOT NULL AND media_type IS NOT NULL)),
    CONSTRAINT ck_evidence_object__scan CHECK (scan_status IN ('CHO_QUET', 'SACH', 'NHIEM', 'LOI')),
    CONSTRAINT ck_evidence_object__size CHECK (size_bytes >= 0)
);

COMMENT ON TABLE ${flyway:defaultSchema}.evidence_object IS 'Bản chiếu đối tượng minh chứng đã lưu ở SVC-08 (mã băm, kết quả quét)';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.id IS '{"cls":"C1","desc":"Đối tượng ở SVC-08 (evidence.object_meta.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.object_version IS '{"cls":"C1","desc":"Phiên bản đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.content_sha256 IS '{"cls":"C1","desc":"SHA-256 do SVC-08 tính khi nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.size_bytes IS '{"cls":"C1","desc":"Kích thước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.media_type IS '{"cls":"C1","desc":"Kiểu nội dung"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.has_e2ee IS '{"cls":"C1","desc":"Tệp mã hoá đầu cuối (C3)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.scan_status IS '{"cls":"C1","desc":"Kết quả quét mã độc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_object.stored_at IS '{"cls":"C1","desc":"Thời điểm SVC-08 lưu và khoá"}';

-- ---------------------------------------------------------------- evidence_version_file
CREATE TABLE ${flyway:defaultSchema}.evidence_version_file (
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
    ordinal smallint NOT NULL,
    object_id uuid NOT NULL,
    object_version int NOT NULL,
    content_sha256 bytea NOT NULL,
    scan_status varchar(12) NOT NULL DEFAULT 'CHO_QUET',
    is_duplicate_suspect boolean NOT NULL DEFAULT false,
    duplicate_of_id uuid,
    reuse_justification varchar(1000),
    CONSTRAINT pk_evidence_version_file PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_evidence_version_file__evidence_version FOREIGN KEY (period_bucket, tenant_id, evidence_version_id)
        REFERENCES ${flyway:defaultSchema}.evidence_version (period_bucket, tenant_id, id),
    CONSTRAINT uq_evidence_version_file__ordinal UNIQUE (period_bucket, tenant_id, evidence_version_id, ordinal),
    CONSTRAINT ck_evidence_version_file__hash CHECK (length(content_sha256) = 32),
    CONSTRAINT ck_evidence_version_file__reuse CHECK (
        reuse_justification IS NULL OR char_length(reuse_justification) BETWEEN 20 AND 1000),
    CONSTRAINT ck_evidence_version_file__scan CHECK (scan_status IN ('CHO_QUET', 'SACH', 'NHIEM', 'LOI')),
    CONSTRAINT ck_evidence_version_file__ordinal CHECK (ordinal BETWEEN 1 AND 20)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_evidence_version_file__sha ON ${flyway:defaultSchema}.evidence_version_file (tenant_id, content_sha256);
CREATE INDEX ix_evidence_version_file__object ON ${flyway:defaultSchema}.evidence_version_file (tenant_id, object_id);

COMMENT ON TABLE ${flyway:defaultSchema}.evidence_version_file IS 'Tệp của phiên bản minh chứng theo thứ tự hiển thị';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.evidence_version_id IS '{"cls":"C1","desc":"Phiên bản minh chứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.task_id IS '{"cls":"C1","desc":"Nhiệm vụ (để phát hiện trùng giữa các nhiệm vụ)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.ordinal IS '{"cls":"C1","desc":"Thứ tự hiển thị","type_exception":"Tối đa 20 tệp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.object_id IS '{"cls":"C1","desc":"Đối tượng ở SVC-08"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.object_version IS '{"cls":"C1","desc":"Phiên bản đối tượng khi gắn vào nháp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.content_sha256 IS '{"cls":"C1","desc":"Mã băm ghi nhận khi gắn tệp vào nháp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.scan_status IS '{"cls":"C1","desc":"Kết quả quét mã độc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.is_duplicate_suspect IS '{"cls":"C1","desc":"Trùng mã băm với minh chứng của nhiệm vụ khác (BR-07-03)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.duplicate_of_id IS '{"cls":"C1","desc":"Phiên bản minh chứng có tệp trùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.evidence_version_file.reuse_justification IS '{"cls":"C1","desc":"Giải trình dùng lại tệp trùng"}';

-- Tệp của phiên bản đã nộp bất biến (BR-07-01).
CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_evidence_version_file() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_status varchar(20);
BEGIN
    SELECT v.status INTO v_status
      FROM ${flyway:defaultSchema}.evidence_version v
     WHERE v.period_bucket = coalesce(NEW.period_bucket, OLD.period_bucket)
       AND v.tenant_id = coalesce(NEW.tenant_id, OLD.tenant_id)
       AND v.id = coalesce(NEW.evidence_version_id, OLD.evidence_version_id);
    -- Không thấy phiên bản (khác tenant theo RLS, hoặc chưa có): để RLS và khoá ngoại xử lý.
    IF v_status IS NOT NULL AND v_status <> 'NHAP' THEN
        RAISE EXCEPTION 'Tệp của phiên bản minh chứng đã nộp không được thay đổi'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN coalesce(NEW, OLD);
END
$fn$;
CREATE TRIGGER tg_evidence_version_file__freeze BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.evidence_version_file
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_evidence_version_file();

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.evidence_object');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.evidence_version_file');

REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.evidence_object FROM svc_${flyway:defaultSchema};
REVOKE TRUNCATE ON ${flyway:defaultSchema}.evidence_version_file FROM svc_${flyway:defaultSchema};
