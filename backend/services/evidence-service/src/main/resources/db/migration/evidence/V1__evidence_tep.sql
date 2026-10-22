-- DV-08-01 · Schema evidence (SVC-08): siêu dữ liệu tệp trên Ceph RGW (TL-03 "Schema evidence (SVC-08)",
-- "Kho đối tượng Ceph"; ADR-13). Schema không lưu nội dung tệp; khoá đối tượng theo nội dung
-- t/<tenant>/sha256/<2 ký tự đầu>/<băm>. outbox_event, inbox_processed, inbox_aggregate_version,
-- idempotency_key đến từ db/common-business (NEN-032); hàm RLS từ db/common (NEN-033).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 46):
--   * object_meta và upload_session có đủ cột chung (TL-03 ghi "cột chung trừ period_bucket" cho object_meta,
--     không ghi cho upload_session); object_version và scan_result là bản ghi bất biến, chỉ có created_at,
--     created_by như từ điển, và svc_evidence bị thu hồi DELETE, TRUNCATE trên hai bảng này (suy luận, I5).
--   * Khoá ngoại và UQ đi kèm tenant_id như các schema nghiệp vụ; UQ (tenant_id, object_meta_id, version_no).
--   * Độ dài các cột varchar TL-03 không ghi: bucket 63 (giới hạn tên bucket S3), engine 32, engine_version 64.
--   * dedup_index.owners_sample kiểu uuid[] theo TL-03, tối đa 16 phần tử.

-- ---------------------------------------------------------------- object_meta
CREATE TABLE ${flyway:defaultSchema}.object_meta (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    owner_module varchar(16) NOT NULL,
    owner_ref_id uuid NOT NULL,
    file_name_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    media_type varchar(100) NOT NULL,
    classification varchar(4) NOT NULL,
    current_version_id uuid,
    retention_class varchar(16) NOT NULL,
    legal_hold boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_object_meta PRIMARY KEY (id),
    CONSTRAINT uq_object_meta__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_object_meta__owner_module CHECK (owner_module IN ('product', 'dossier', 'oversight')),
    CONSTRAINT ck_object_meta__classification CHECK (classification IN ('C1', 'C2', 'C3'))
);
CREATE INDEX ix_object_meta__owner ON ${flyway:defaultSchema}.object_meta (tenant_id, owner_module, owner_ref_id);

COMMENT ON TABLE ${flyway:defaultSchema}.object_meta IS 'Đối tượng logic: một tài liệu người dùng thấy';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.owner_module IS '{"cls":"C1","desc":"Module sở hữu (product, dossier, oversight)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.owner_ref_id IS '{"cls":"C1","desc":"Bản ghi nghiệp vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.file_name_enc IS '{"cls":"C2","desc":"Tên tệp gốc (mã hoá phong bì)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.media_type IS '{"cls":"C1","desc":"Kiểu MIME đã xác minh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.classification IS '{"cls":"C1","desc":"Phân loại tệp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.current_version_id IS '{"cls":"C1","desc":"Phiên bản hiện hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.retention_class IS '{"cls":"C1","desc":"Lớp lưu giữ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.legal_hold IS '{"cls":"C1","desc":"Đang giữ pháp lý"}';

-- ---------------------------------------------------------------- object_version
CREATE TABLE ${flyway:defaultSchema}.object_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    object_meta_id uuid NOT NULL,
    version_no int NOT NULL,
    sha256 bytea NOT NULL,
    size_bytes bigint NOT NULL,
    bucket varchar(63) NOT NULL,
    object_key varchar(512) NOT NULL,
    ceph_version_id varchar(64) NOT NULL,
    lock_mode varchar(12),
    lock_until timestamptz,
    encrypted_dek bytea,
    scan_status varchar(12) NOT NULL DEFAULT 'CHO_QUET',
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    CONSTRAINT pk_object_version PRIMARY KEY (id),
    CONSTRAINT uq_object_version__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_object_version__no UNIQUE (tenant_id, object_meta_id, version_no),
    CONSTRAINT fk_object_version__object_meta FOREIGN KEY (tenant_id, object_meta_id)
        REFERENCES ${flyway:defaultSchema}.object_meta (tenant_id, id),
    CONSTRAINT ck_object_version__version_no CHECK (version_no > 0),
    CONSTRAINT ck_object_version__sha256 CHECK (length(sha256) = 32),
    CONSTRAINT ck_object_version__size CHECK (size_bytes >= 0),
    CONSTRAINT ck_object_version__lock_mode CHECK (lock_mode IS NULL OR lock_mode IN ('COMPLIANCE', 'GOVERNANCE')),
    CONSTRAINT ck_object_version__lock CHECK ((lock_mode IS NULL) = (lock_until IS NULL)),
    CONSTRAINT ck_object_version__scan_status CHECK (scan_status IN ('CHO_QUET', 'SACH', 'NHIEM', 'LOI'))
);
CREATE INDEX ix_object_version__sha256 ON ${flyway:defaultSchema}.object_version (tenant_id, sha256);

COMMENT ON TABLE ${flyway:defaultSchema}.object_version IS 'Phiên bản nội dung của đối tượng, trỏ tới khoá vật lý trên Ceph';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.object_meta_id IS '{"cls":"C1","desc":"Đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.version_no IS '{"cls":"C1","desc":"Phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.sha256 IS '{"cls":"C1","desc":"Băm nội dung (32 byte)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.size_bytes IS '{"cls":"C1","desc":"Kích thước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.bucket IS '{"cls":"C1","desc":"Bucket trên Ceph (ev-<cell>-<tenant>)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.object_key IS '{"cls":"C1","desc":"Khoá vật lý t/<tenant>/sha256/<ab>/<băm>"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.ceph_version_id IS '{"cls":"C1","desc":"Mã phiên bản Ceph (Object Lock yêu cầu versioning)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.lock_mode IS '{"cls":"C1","desc":"Chế độ khoá đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.lock_until IS '{"cls":"C1","desc":"Hạn khoá đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.encrypted_dek IS '{"cls":"C1","desc":"DEK của tệp (SSE-C do SVC-08 quản lý), đã bọc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.scan_status IS '{"cls":"C1","desc":"Trạng thái quét"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.created_by IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- dedup_index
CREATE TABLE ${flyway:defaultSchema}.dedup_index (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    sha256 bytea NOT NULL,
    object_key varchar(512) NOT NULL,
    ref_count int NOT NULL DEFAULT 0,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    owners_sample uuid[] NOT NULL DEFAULT '{}',
    CONSTRAINT pk_dedup_index PRIMARY KEY (tenant_id, sha256),
    CONSTRAINT ck_dedup_index__sha256 CHECK (length(sha256) = 32),
    CONSTRAINT ck_dedup_index__ref_count CHECK (ref_count >= 0),
    CONSTRAINT ck_dedup_index__owners CHECK (cardinality(owners_sample) <= 16)
);

COMMENT ON TABLE ${flyway:defaultSchema}.dedup_index IS 'Chỉ mục băm khử trùng trong tenant và tín hiệu minh chứng trùng';
COMMENT ON COLUMN ${flyway:defaultSchema}.dedup_index.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dedup_index.sha256 IS '{"cls":"C1","desc":"Băm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dedup_index.object_key IS '{"cls":"C1","desc":"Khoá vật lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dedup_index.ref_count IS '{"cls":"C1","desc":"Số phiên bản đang tham chiếu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dedup_index.first_seen_at IS '{"cls":"C1","desc":"Lần đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dedup_index.owners_sample IS '{"cls":"C1","desc":"Tối đa 16 người sở hữu đầu tiên","type_exception":"TL-03 chốt uuid[]"}';

-- ---------------------------------------------------------------- scan_result
CREATE TABLE ${flyway:defaultSchema}.scan_result (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    object_version_id uuid NOT NULL,
    engine varchar(32) NOT NULL,
    engine_version varchar(64) NOT NULL,
    verdict varchar(12) NOT NULL,
    detail jsonb,
    scanned_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_scan_result PRIMARY KEY (id),
    CONSTRAINT fk_scan_result__object_version FOREIGN KEY (tenant_id, object_version_id)
        REFERENCES ${flyway:defaultSchema}.object_version (tenant_id, id),
    CONSTRAINT ck_scan_result__verdict CHECK (verdict IN ('SACH', 'NHIEM', 'NGHI_NGO', 'LOI'))
);
CREATE INDEX ix_scan_result__object_version ON ${flyway:defaultSchema}.scan_result (tenant_id, object_version_id);

COMMENT ON TABLE ${flyway:defaultSchema}.scan_result IS 'Kết quả quét mã độc và kiểm tra định dạng';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.object_version_id IS '{"cls":"C1","desc":"Phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.engine IS '{"cls":"C1","desc":"Bộ quét"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.engine_version IS '{"cls":"C1","desc":"Phiên bản chữ ký của bộ quét"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.verdict IS '{"cls":"C1","desc":"Kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.detail IS '{"cls":"C1","desc":"Chi tiết"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.scan_result.scanned_at IS '{"cls":"C1","desc":"Thời điểm quét"}';

-- ---------------------------------------------------------------- upload_session
CREATE TABLE ${flyway:defaultSchema}.upload_session (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    user_id uuid NOT NULL,
    rgw_upload_id varchar(128) NOT NULL,
    declared_sha256 bytea NOT NULL,
    declared_size bigint NOT NULL,
    parts_done int NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'MO',
    CONSTRAINT pk_upload_session PRIMARY KEY (id),
    CONSTRAINT ck_upload_session__declared_sha256 CHECK (length(declared_sha256) = 32),
    CONSTRAINT ck_upload_session__declared_size CHECK (declared_size > 0),
    CONSTRAINT ck_upload_session__parts_done CHECK (parts_done >= 0),
    CONSTRAINT ck_upload_session__status CHECK (status IN ('MO', 'HOAN_TAT', 'HUY'))
);
CREATE INDEX ix_upload_session__expires_at ON ${flyway:defaultSchema}.upload_session (expires_at);

COMMENT ON TABLE ${flyway:defaultSchema}.upload_session IS 'Phiên tải lên nhiều phần qua URL ký trước (bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.user_id IS '{"cls":"C1","desc":"Người tải"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.rgw_upload_id IS '{"cls":"C1","desc":"Mã multipart của RGW"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.declared_sha256 IS '{"cls":"C1","desc":"Băm khai báo trước, kiểm lại khi hoàn tất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.declared_size IS '{"cls":"C1","desc":"Kích thước khai báo trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.parts_done IS '{"cls":"C1","desc":"Số phần đã xong"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.expires_at IS '{"cls":"C1","desc":"Hết hạn (job dọn huỷ multipart dở dang)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.status IS '{"cls":"C1","desc":"Trạng thái"}';

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.object_meta');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.object_version');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.dedup_index');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.scan_result');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.upload_session');

REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.object_version FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.scan_result FROM svc_${flyway:defaultSchema};
