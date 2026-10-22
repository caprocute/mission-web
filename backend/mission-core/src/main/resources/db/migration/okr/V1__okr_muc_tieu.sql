-- CORE-01-01 · MOD-01 schema okr, phần căn cứ và mục tiêu (TL-03 "Schema okr (MOD-01)", bảng okr.basis,
-- okr.objective, okr.objective_basis, okr.objective_review; QT-02, QT-03).
-- Bảng theo kỳ PARTITION BY LIST (period_bucket); phân vùng tạo khi mở kỳ bằng fn_create_period_partitions
-- (NEN-033), migration không tạo sẵn phân vùng nào. Ở mô hình pool phân vùng còn chia HASH (tenant_id)
-- nên khoá chính, khoá duy nhất và khoá ngoại tới bảng theo kỳ có thêm tenant_id: PK (period_bucket,
-- tenant_id, id) thay cho PK (period_bucket, id) của TL-03 (wiki trang 14).
-- Bảng outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key do db/common-business
-- (NEN-032) tạo.

CREATE TABLE ${flyway:defaultSchema}.basis (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    basis_kind varchar(16) NOT NULL,
    doc_number varchar(64),
    title varchar(500) NOT NULL,
    issued_by_unit_id uuid,
    issued_on date,
    source_doc_ref varchar(200),
    parent_basis_id uuid,
    valid_from date,
    valid_to date,
    CONSTRAINT pk_basis PRIMARY KEY (id),
    CONSTRAINT fk_basis__basis_parent FOREIGN KEY (parent_basis_id) REFERENCES ${flyway:defaultSchema}.basis (id),
    CONSTRAINT ck_basis__kind CHECK (basis_kind IN ('NGHI_QUYET', 'KET_LUAN', 'CHI_THI', 'CHUONG_TRINH', 'KE_HOACH', 'CHI_TIEU_KTXH')),
    CONSTRAINT ck_basis__valid CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to)
);
CREATE INDEX ix_basis__tenant_kind ON ${flyway:defaultSchema}.basis (tenant_id, basis_kind, issued_on);
COMMENT ON TABLE ${flyway:defaultSchema}.basis IS 'Căn cứ: nghị quyết, kết luận, chương trình công tác';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.basis_kind IS '{"cls": "C0", "desc": "Loại căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.doc_number IS '{"cls": "C0", "desc": "Số, ký hiệu văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.title IS '{"cls": "C0", "desc": "Trích yếu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.issued_by_unit_id IS '{"cls": "C1", "desc": "Đơn vị ban hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.issued_on IS '{"cls": "C0", "desc": "Ngày ban hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.source_doc_ref IS '{"cls": "C1", "desc": "Tham chiếu văn bản ở hệ ngoài"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.parent_basis_id IS '{"cls": "C1", "desc": "Căn cứ cấp trên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.valid_from IS '{"cls": "C0", "desc": "Bắt đầu hiệu lực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.basis.valid_to IS '{"cls": "C0", "desc": "Hết hiệu lực"}';

CREATE TABLE ${flyway:defaultSchema}.objective (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket integer NOT NULL,
    owner_type varchar(8) NOT NULL,
    owner_unit_id uuid NOT NULL,
    owner_person_id uuid,
    code varchar(16) NOT NULL,
    title varchar(500) NOT NULL,
    description text,
    parent_objective_id uuid,
    tier smallint NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    progress numeric(6,4) NOT NULL DEFAULT 0,
    approved_by uuid,
    approved_at timestamptz,
    wf_process_instance_id varchar(64),
    ai_suggestion_id uuid,
    progress_pct numeric(6,3),
    over_limit_reason varchar(1000),
    version_no integer NOT NULL DEFAULT 1,
    ai_assisted boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_objective PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT ck_objective__owner_type CHECK (owner_type IN ('TAP_THE', 'CA_NHAN')),
    CONSTRAINT ck_objective__owner_person CHECK (owner_type <> 'CA_NHAN' OR owner_person_id IS NOT NULL),
    CONSTRAINT ck_objective__tier CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT ck_objective__status CHECK (status IN ('NHAP', 'CHO_DUYET', 'DA_DUYET', 'DONG')),
    CONSTRAINT ck_objective__progress CHECK (progress BETWEEN 0 AND 1),
    CONSTRAINT ck_objective__version_no CHECK (version_no >= 1)
) PARTITION BY LIST (period_bucket);
COMMENT ON TABLE ${flyway:defaultSchema}.objective IS 'Mục tiêu O của kỳ (QT-02)';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.period_id IS '{"cls": "C1", "desc": "Kỳ (tham chiếu logic config.period)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.period_bucket IS '{"cls": "C1", "desc": "Khoá phân vùng LIST: năm × 10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.owner_type IS '{"cls": "C1", "desc": "Chủ thể sở hữu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.owner_unit_id IS '{"cls": "C1", "desc": "Đơn vị (tập thể hoặc đơn vị của cá nhân)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.owner_person_id IS '{"cls": "C1", "desc": "Cá nhân"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.code IS '{"cls": "C0", "desc": "Mã hiển thị, ví dụ O1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.title IS '{"cls": "C1", "desc": "Nội dung mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.description IS '{"cls": "C1", "desc": "Diễn giải chuyển biến cần đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.parent_objective_id IS '{"cls": "C1", "desc": "Mục tiêu cấp trên (phân rã)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.tier IS '{"cls": "C1", "desc": "Tầng phân rã 1–3", "type_exception": "TL-03 bảng okr.objective ghi smallint"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.status IS '{"cls": "C1", "desc": "Trạng thái mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.progress IS '{"cls": "C1", "desc": "Tiến độ tổng hợp từ KR"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.approved_by IS '{"cls": "C1", "desc": "Người phê duyệt (sinh EVT-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.approved_at IS '{"cls": "C1", "desc": "Thời điểm phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.wf_process_instance_id IS '{"cls": "C1", "desc": "Tiến trình duyệt trong Flowable"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.ai_suggestion_id IS '{"cls": "C1", "desc": "Gợi ý AI đã được người dùng chấp nhận (tham chiếu logic ai.suggestion_log)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.progress_pct IS '{"cls": "C1", "desc": "Tiến độ phần trăm tính từ KR, cột trả qua API"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.over_limit_reason IS '{"cls": "C1", "desc": "Lý do khi vượt số mục tiêu khuyến nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.version_no IS '{"cls": "C1", "desc": "Lần trình duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective.ai_assisted IS '{"cls": "C1", "desc": "Nội dung chèn từ gợi ý AI"}';

CREATE INDEX ix_objective__owner_unit ON ${flyway:defaultSchema}.objective (tenant_id, owner_unit_id, period_id);
CREATE INDEX ix_objective__owner_person ON ${flyway:defaultSchema}.objective (tenant_id, owner_person_id, period_id) WHERE owner_person_id IS NOT NULL;
CREATE INDEX ix_objective__parent ON ${flyway:defaultSchema}.objective (tenant_id, parent_objective_id) WHERE parent_objective_id IS NOT NULL;

CREATE TABLE ${flyway:defaultSchema}.objective_basis (
    period_bucket integer NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    objective_id uuid NOT NULL,
    basis_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    CONSTRAINT pk_objective_basis PRIMARY KEY (period_bucket, tenant_id, objective_id, basis_id),
    CONSTRAINT fk_objective_basis__objective FOREIGN KEY (period_bucket, tenant_id, objective_id) REFERENCES ${flyway:defaultSchema}.objective (period_bucket, tenant_id, id),
    CONSTRAINT fk_objective_basis__basis FOREIGN KEY (basis_id) REFERENCES ${flyway:defaultSchema}.basis (id)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_objective_basis__basis ON ${flyway:defaultSchema}.objective_basis (tenant_id, basis_id);
COMMENT ON TABLE ${flyway:defaultSchema}.objective_basis IS 'Nối nhiều nhiều giữa mục tiêu và căn cứ (TL-02a)';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_basis.period_bucket IS '{"cls": "C1", "desc": "Khoá phân vùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_basis.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_basis.objective_id IS '{"cls": "C1", "desc": "Mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_basis.basis_id IS '{"cls": "C1", "desc": "Căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_basis.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_basis.created_by IS '{"cls": "C1", "desc": "Người tạo"}';

CREATE TABLE ${flyway:defaultSchema}.objective_review (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket integer NOT NULL,
    objective_id uuid NOT NULL,
    version_no integer NOT NULL,
    reviewer_id uuid NOT NULL,
    decision varchar(8) NOT NULL,
    comment varchar(4000),
    CONSTRAINT pk_objective_review PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_objective_review__objective FOREIGN KEY (period_bucket, tenant_id, objective_id) REFERENCES ${flyway:defaultSchema}.objective (period_bucket, tenant_id, id),
    CONSTRAINT uq_objective_review__round UNIQUE (period_bucket, tenant_id, objective_id, version_no, reviewer_id),
    CONSTRAINT ck_objective_review__decision CHECK (decision IN ('DUYET', 'TRA_LAI'))
) PARTITION BY LIST (period_bucket);
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.objective_review FROM svc_${flyway:defaultSchema};
CREATE FUNCTION ${flyway:defaultSchema}.fn_reject_append_only_change() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  RAISE EXCEPTION 'Bảng %.% chỉ được ghi thêm', TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END
$fn$;
CREATE TRIGGER tg_objective_review__append_only BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.objective_review
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_reject_append_only_change();
COMMENT ON TABLE ${flyway:defaultSchema}.objective_review IS 'Ý kiến người duyệt ở mỗi lần duyệt hoặc trả lại mục tiêu; chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.period_id IS '{"cls": "C1", "desc": "Kỳ (tham chiếu logic config.period)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.period_bucket IS '{"cls": "C1", "desc": "Khoá phân vùng LIST: năm × 10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.objective_id IS '{"cls": "C1", "desc": "Mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.version_no IS '{"cls": "C1", "desc": "Lần trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.reviewer_id IS '{"cls": "C1", "desc": "Người duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.decision IS '{"cls": "C1", "desc": "Quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.objective_review.comment IS '{"cls": "C1", "desc": "Ý kiến"}';
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.basis');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.objective');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.objective_basis');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.objective_review');

