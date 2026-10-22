-- CORE-02-01 · Schema task (MOD-02) phần danh mục, chương trình và kế hoạch (TL-03 "Schema task (MOD-02)":
-- task.work_catalog, task.catalog_item, task.program, task.program_milestone, task.plan, task.plan_version,
-- task.plan_item). outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key đến từ db/common-business
-- (NEN-032); hàm RLS và phân vùng từ db/common (NEN-033).
--
-- Điểm lệch và lựa chọn (wiki trang 14):
--   * Khoá chính, khoá duy nhất và khoá ngoại của bảng theo kỳ chứa cả period_bucket và tenant_id (TL-03 ghi
--     (period_bucket, id)), vì ở CSDL pool phân vùng con chia HASH (tenant_id) (quy tắc 4 của vt-db-migration).
--   * Khoá ngoại giữa các bảng không phân vùng đi kèm tenant_id để không tham chiếu được dòng của tenant khác
--     (kiểm khoá ngoại không qua RLS; suy luận).
--   * work_catalog.status giữ CHECK theo TL-03 (NHAP, HIEU_LUC, NGUNG); API ánh xạ sang NHAP, AP_DUNG,
--     HET_HIEU_LUC của hợp đồng (CORE-02-03). catalog_item.status là cột bổ sung theo hợp đồng (AP_DUNG, NGUNG).
--   * Cột trùng nghĩa: version_no (API) = version (TL-03); base_score, default_group (API) đi cùng standard_score,
--     task_group (TL-03, NOT NULL), lớp ứng dụng ghi cả hai.
--   * unit dài 50 theo hợp đồng (TL-03 ghi varchar(32)); program.status dài 16 vì DANG_TRIEN_KHAI có 15 ký tự.
--   * Chỉ mục duy nhất một phần của TL-03 uq_plan_version__approved_p đặt tên ix_plan_version__approved_p theo
--     lint NEN-030 (chỉ mục phải bắt đầu bằng ix_).

-- ---------------------------------------------------------------- work_catalog
CREATE TABLE ${flyway:defaultSchema}.work_catalog (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    code varchar(50) NOT NULL,
    name varchar(255) NOT NULL,
    scope varchar(12) NOT NULL,
    position_id uuid,
    org_unit_id uuid,
    version int NOT NULL,
    source varchar(12) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    parent_catalog_id uuid,
    version_no int NOT NULL,
    CONSTRAINT pk_work_catalog PRIMARY KEY (id),
    CONSTRAINT uq_work_catalog__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_work_catalog__code_version UNIQUE (tenant_id, code, version),
    CONSTRAINT fk_work_catalog__work_catalog FOREIGN KEY (tenant_id, parent_catalog_id)
        REFERENCES ${flyway:defaultSchema}.work_catalog (tenant_id, id),
    CONSTRAINT ck_work_catalog__scope CHECK (scope IN ('VI_TRI', 'DON_VI', 'QUOC_GIA')),
    CONSTRAINT ck_work_catalog__source CHECK (source IN ('EXCEL', 'THU_VIEN_QG', 'TU_TAO', 'DI_TRU')),
    CONSTRAINT ck_work_catalog__status CHECK (status IN ('NHAP', 'HIEU_LUC', 'NGUNG')),
    CONSTRAINT ck_work_catalog__version CHECK (version >= 1 AND version_no = version),
    CONSTRAINT ck_work_catalog__not_self CHECK (parent_catalog_id IS NULL OR parent_catalog_id <> id)
);
CREATE INDEX ix_work_catalog__position ON ${flyway:defaultSchema}.work_catalog (tenant_id, position_id);
CREATE INDEX ix_work_catalog__org_unit ON ${flyway:defaultSchema}.work_catalog (tenant_id, org_unit_id);
CREATE INDEX ix_work_catalog__parent ON ${flyway:defaultSchema}.work_catalog (tenant_id, parent_catalog_id);

COMMENT ON TABLE ${flyway:defaultSchema}.work_catalog IS 'Danh mục công việc theo vị trí việc làm, có phiên bản (PL 1 Bảng 3)';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.code IS '{"cls":"C0","desc":"Mã danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.name IS '{"cls":"C0","desc":"Tên danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.scope IS '{"cls":"C0","desc":"Phạm vi: vị trí, đơn vị, quốc gia"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.position_id IS '{"cls":"C1","desc":"Vị trí việc làm gắn danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.org_unit_id IS '{"cls":"C1","desc":"Đơn vị sở hữu; đơn vị con kế thừa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.version IS '{"cls":"C0","desc":"Phiên bản danh mục (TL-03), luôn bằng version_no"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.source IS '{"cls":"C1","desc":"Nguồn: Excel, thư viện quốc gia, tự tạo, di trú"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.status IS '{"cls":"C1","desc":"Trạng thái theo TL-03; API ánh xạ sang WorkCatalogStatus"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.parent_catalog_id IS '{"cls":"C1","desc":"Danh mục cấp trên được kế thừa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.work_catalog.version_no IS '{"cls":"C1","desc":"Số phiên bản, cột chuẩn cho API (versionNo)"}';

-- ---------------------------------------------------------------- catalog_item
CREATE TABLE ${flyway:defaultSchema}.catalog_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    work_catalog_id uuid NOT NULL,
    code varchar(50) NOT NULL,
    name varchar(500) NOT NULL,
    product_name varchar(500) NOT NULL,
    unit varchar(50) NOT NULL,
    difficulty smallint NOT NULL,
    standard_score numeric(6,3) NOT NULL,
    quality_standard text NOT NULL,
    evidence_requirement text,
    task_group varchar(16) NOT NULL,
    data_source varchar(200),
    position_id uuid,
    default_group varchar(16),
    standard_days int,
    base_score numeric(6,3),
    status varchar(12) NOT NULL DEFAULT 'AP_DUNG',
    CONSTRAINT pk_catalog_item PRIMARY KEY (id),
    CONSTRAINT uq_catalog_item__code UNIQUE (tenant_id, work_catalog_id, code),
    CONSTRAINT fk_catalog_item__work_catalog FOREIGN KEY (tenant_id, work_catalog_id)
        REFERENCES ${flyway:defaultSchema}.work_catalog (tenant_id, id),
    CONSTRAINT ck_catalog_item__difficulty CHECK (difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_catalog_item__standard_score CHECK (standard_score BETWEEN 0 AND 100),
    CONSTRAINT ck_catalog_item__base_score CHECK (base_score IS NULL OR base_score BETWEEN 0 AND 100),
    CONSTRAINT ck_catalog_item__task_group CHECK (task_group IN ('THUONG_XUYEN', 'THEN_CHOT')),
    CONSTRAINT ck_catalog_item__default_group CHECK (default_group IS NULL OR default_group IN ('THUONG_XUYEN', 'THEN_CHOT')),
    CONSTRAINT ck_catalog_item__standard_days CHECK (standard_days IS NULL OR standard_days >= 1),
    CONSTRAINT ck_catalog_item__status CHECK (status IN ('AP_DUNG', 'NGUNG'))
);
CREATE INDEX ix_catalog_item__position ON ${flyway:defaultSchema}.catalog_item (tenant_id, position_id);

COMMENT ON TABLE ${flyway:defaultSchema}.catalog_item IS 'Mục công việc trong danh mục (PL 1 Bảng 3)';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.work_catalog_id IS '{"cls":"C1","desc":"Danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.code IS '{"cls":"C0","desc":"Mã công việc (Bảng 3)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.name IS '{"cls":"C0","desc":"Tên công việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.product_name IS '{"cls":"C0","desc":"Sản phẩm đầu ra"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.unit IS '{"cls":"C0","desc":"Đơn vị tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.difficulty IS '{"cls":"C0","desc":"Mức khó năm mức","type_exception":"smallint theo TL-03 cho mức 1-5"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.standard_score IS '{"cls":"C0","desc":"Điểm chuẩn (TL-03), bằng base_score khi có"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.quality_standard IS '{"cls":"C0","desc":"Tiêu chuẩn chất lượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.evidence_requirement IS '{"cls":"C0","desc":"Yêu cầu minh chứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.task_group IS '{"cls":"C0","desc":"Nhóm; một nhiệm vụ chỉ tính ở một nhóm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.data_source IS '{"cls":"C0","desc":"Nguồn dữ liệu đo (PL 4 nhóm 3)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.position_id IS '{"cls":"C1","desc":"Vị trí việc làm áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.default_group IS '{"cls":"C1","desc":"Nhóm mặc định, cột chuẩn cho API (defaultGroup)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.standard_days IS '{"cls":"C1","desc":"Thời gian chuẩn, ngày làm việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.base_score IS '{"cls":"C1","desc":"Điểm chuẩn gợi ý, cột chuẩn cho API (baseScore)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.status IS '{"cls":"C1","desc":"Trạng thái mục (bổ sung theo hợp đồng CatalogItemStatus)"}';

-- ---------------------------------------------------------------- program, program_milestone
CREATE TABLE ${flyway:defaultSchema}.program (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    code varchar(50) NOT NULL,
    name varchar(500) NOT NULL,
    program_kind varchar(12) NOT NULL,
    owner_unit_id uuid NOT NULL,
    parent_program_id uuid,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'NHAP',
    CONSTRAINT pk_program PRIMARY KEY (id),
    CONSTRAINT uq_program__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_program__code UNIQUE (tenant_id, code),
    CONSTRAINT fk_program__program FOREIGN KEY (tenant_id, parent_program_id)
        REFERENCES ${flyway:defaultSchema}.program (tenant_id, id),
    CONSTRAINT ck_program__program_kind CHECK (program_kind IN ('CHUONG_TRINH', 'DE_AN', 'GOI', 'DOT')),
    CONSTRAINT ck_program__status CHECK (status IN ('NHAP', 'DANG_TRIEN_KHAI', 'HOAN_THANH', 'DUNG')),
    CONSTRAINT ck_program__date_range CHECK (end_date >= start_date)
);
CREATE INDEX ix_program__owner_unit ON ${flyway:defaultSchema}.program (tenant_id, owner_unit_id);
CREATE INDEX ix_program__parent ON ${flyway:defaultSchema}.program (tenant_id, parent_program_id);

COMMENT ON TABLE ${flyway:defaultSchema}.program IS 'Chương trình, đề án, gói, đợt triển khai (bổ sung, PL 4 nhóm 4)';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.code IS '{"cls":"C0","desc":"Mã chương trình, đề án"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.name IS '{"cls":"C0","desc":"Tên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.program_kind IS '{"cls":"C0","desc":"Loại: chương trình, đề án, gói, đợt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.owner_unit_id IS '{"cls":"C1","desc":"Đơn vị chủ trì"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.parent_program_id IS '{"cls":"C1","desc":"Chương trình cha"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.start_date IS '{"cls":"C0","desc":"Ngày bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.end_date IS '{"cls":"C0","desc":"Ngày kết thúc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program.status IS '{"cls":"C1","desc":"Trạng thái"}';

CREATE TABLE ${flyway:defaultSchema}.program_milestone (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    program_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    due_date date NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'CHUA_DEN',
    achieved_at timestamptz,
    CONSTRAINT pk_program_milestone PRIMARY KEY (id),
    CONSTRAINT fk_program_milestone__program FOREIGN KEY (tenant_id, program_id)
        REFERENCES ${flyway:defaultSchema}.program (tenant_id, id),
    CONSTRAINT ck_program_milestone__status CHECK (status IN ('CHUA_DEN', 'DAT', 'TRE', 'HUY')),
    CONSTRAINT ck_program_milestone__achieved CHECK (status <> 'DAT' OR achieved_at IS NOT NULL)
);
CREATE INDEX ix_program_milestone__program_due ON ${flyway:defaultSchema}.program_milestone (tenant_id, program_id, due_date);

COMMENT ON TABLE ${flyway:defaultSchema}.program_milestone IS 'Mốc kiểm soát của chương trình (bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.program_id IS '{"cls":"C1","desc":"Chương trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.name IS '{"cls":"C0","desc":"Tên mốc kiểm soát"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.due_date IS '{"cls":"C0","desc":"Hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.program_milestone.achieved_at IS '{"cls":"C1","desc":"Thời điểm đạt"}';

-- ---------------------------------------------------------------- plan (theo kỳ)
CREATE TABLE ${flyway:defaultSchema}.plan (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    plan_type varchar(8) NOT NULL,
    owner_unit_id uuid NOT NULL,
    owner_person_id uuid,
    current_version_id uuid,
    CONSTRAINT pk_plan PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_plan__owner UNIQUE NULLS NOT DISTINCT (period_bucket, tenant_id, period_id, owner_unit_id, owner_person_id),
    CONSTRAINT ck_plan__plan_type CHECK (plan_type IN ('TAP_THE', 'CA_NHAN')),
    CONSTRAINT ck_plan__owner_person CHECK ((plan_type = 'CA_NHAN') = (owner_person_id IS NOT NULL))
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.plan IS 'Kế hoạch công tác quý của đơn vị hoặc cá nhân (PL 1 Bảng 1)';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.plan_type IS '{"cls":"C1","desc":"Kế hoạch tập thể hoặc cá nhân"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.owner_unit_id IS '{"cls":"C1","desc":"Đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.owner_person_id IS '{"cls":"C1","desc":"Cá nhân (kế hoạch cá nhân)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan.current_version_id IS '{"cls":"C1","desc":"Phiên bản hiện hành đã duyệt"}';

-- ---------------------------------------------------------------- plan_version (theo kỳ)
CREATE TABLE ${flyway:defaultSchema}.plan_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    plan_id uuid NOT NULL,
    version_no int NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    is_current boolean NOT NULL DEFAULT false,
    change_reason text,
    submitted_at timestamptz,
    approved_by uuid,
    approved_at timestamptz,
    return_reason text,
    content_hash bytea,
    sign_request_id uuid,
    wf_process_instance_id varchar(64),
    review_comment varchar(4000),
    CONSTRAINT pk_plan_version PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_plan_version__version_no UNIQUE (period_bucket, tenant_id, plan_id, version_no),
    CONSTRAINT fk_plan_version__plan FOREIGN KEY (period_bucket, tenant_id, plan_id)
        REFERENCES ${flyway:defaultSchema}.plan (period_bucket, tenant_id, id),
    CONSTRAINT ck_plan_version__status CHECK (status IN ('NHAP', 'CHO_DUYET', 'DA_DUYET', 'TRA_LAI')),
    CONSTRAINT ck_plan_version__version_no CHECK (version_no >= 1),
    CONSTRAINT ck_plan_version__change_reason CHECK (version_no = 1 OR btrim(coalesce(change_reason, '')) <> ''),
    CONSTRAINT ck_plan_version__current_approved CHECK (NOT is_current OR status = 'DA_DUYET'),
    CONSTRAINT ck_plan_version__approved CHECK (status <> 'DA_DUYET' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_plan_version__content_hash CHECK (content_hash IS NULL OR length(content_hash) = 32)
) PARTITION BY LIST (period_bucket);
CREATE UNIQUE INDEX ix_plan_version__approved_p ON ${flyway:defaultSchema}.plan_version (period_bucket, tenant_id, plan_id)
    WHERE status = 'DA_DUYET' AND is_current;

COMMENT ON TABLE ${flyway:defaultSchema}.plan_version IS 'Phiên bản kế hoạch; phiên bản đã duyệt bất biến (baseline 9.1)';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.plan_id IS '{"cls":"C1","desc":"Kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.version_no IS '{"cls":"C1","desc":"Số phiên bản, bắt đầu từ 1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.status IS '{"cls":"C1","desc":"Trạng thái baseline 9.1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.is_current IS '{"cls":"C1","desc":"Phiên bản đã duyệt đang hiện hành (bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.change_reason IS '{"cls":"C1","desc":"Lý do điều chỉnh, bắt buộc từ phiên bản 2"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.submitted_at IS '{"cls":"C1","desc":"Thời điểm trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.approved_by IS '{"cls":"C1","desc":"Người phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.approved_at IS '{"cls":"C1","desc":"Thời điểm phê duyệt (sinh EVT-03)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.return_reason IS '{"cls":"C1","desc":"Lý do trả lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.content_hash IS '{"cls":"C1","desc":"SHA-256 nội dung các dòng, dữ liệu được ký số"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.sign_request_id IS '{"cls":"C1","desc":"Tham chiếu logic esign.sign_request"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.wf_process_instance_id IS '{"cls":"C1","desc":"Tiến trình duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.review_comment IS '{"cls":"C1","desc":"Ý kiến duyệt hoặc trả lại"}';

-- ---------------------------------------------------------------- plan_item (theo kỳ)
CREATE TABLE ${flyway:defaultSchema}.plan_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    plan_version_id uuid NOT NULL,
    item_no int NOT NULL,
    catalog_item_id uuid,
    program_id uuid,
    key_result_id uuid,
    title varchar(500) NOT NULL,
    task_group varchar(16) NOT NULL,
    lead_unit_id uuid,
    lead_person_id uuid,
    co_unit_ids uuid[] NOT NULL DEFAULT '{}',
    planned_quantity numeric(18,4) NOT NULL,
    unit varchar(50) NOT NULL,
    difficulty smallint,
    standard_score numeric(6,3),
    due_date date NOT NULL,
    origin_item_id uuid,
    change_reason varchar(1000),
    co_assignees jsonb,
    recurrence varchar(32),
    CONSTRAINT pk_plan_item PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_plan_item__item_no UNIQUE (period_bucket, tenant_id, plan_version_id, item_no),
    CONSTRAINT fk_plan_item__plan_version FOREIGN KEY (period_bucket, tenant_id, plan_version_id)
        REFERENCES ${flyway:defaultSchema}.plan_version (period_bucket, tenant_id, id),
    CONSTRAINT ck_plan_item__one_lead CHECK (num_nonnulls(lead_unit_id, lead_person_id) = 1),
    CONSTRAINT ck_plan_item__task_group CHECK (task_group IN ('THUONG_XUYEN', 'THEN_CHOT')),
    CONSTRAINT ck_plan_item__item_no CHECK (item_no >= 1),
    CONSTRAINT ck_plan_item__planned_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_plan_item__difficulty CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_plan_item__standard_score CHECK (standard_score IS NULL OR standard_score BETWEEN 0 AND 100),
    CONSTRAINT ck_plan_item__co_assignees CHECK (co_assignees IS NULL OR jsonb_typeof(co_assignees) = 'array')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_plan_item__catalog_item ON ${flyway:defaultSchema}.plan_item (tenant_id, catalog_item_id, period_bucket);
CREATE INDEX ix_plan_item__key_result ON ${flyway:defaultSchema}.plan_item (tenant_id, key_result_id, period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.plan_item IS 'Dòng kế hoạch của một phiên bản';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.plan_version_id IS '{"cls":"C1","desc":"Phiên bản kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.item_no IS '{"cls":"C1","desc":"Số thứ tự"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.catalog_item_id IS '{"cls":"C1","desc":"Mục danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.program_id IS '{"cls":"C1","desc":"Chương trình (tầng 2)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.key_result_id IS '{"cls":"C1","desc":"KR đóng góp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.title IS '{"cls":"C1","desc":"Nội dung công việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.task_group IS '{"cls":"C1","desc":"Nhóm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.lead_unit_id IS '{"cls":"C1","desc":"Đơn vị chủ trì"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.lead_person_id IS '{"cls":"C1","desc":"Cá nhân chủ trì"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.co_unit_ids IS '{"cls":"C1","desc":"Đơn vị phối hợp","type_exception":"uuid[] theo TL-03"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.planned_quantity IS '{"cls":"C1","desc":"Khối lượng kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.unit IS '{"cls":"C0","desc":"Đơn vị tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.difficulty IS '{"cls":"C1","desc":"Mức khó","type_exception":"smallint theo TL-03 cho mức 1-5"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.standard_score IS '{"cls":"C1","desc":"Điểm chuẩn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.due_date IS '{"cls":"C1","desc":"Hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.origin_item_id IS '{"cls":"C1","desc":"Dòng tương ứng ở phiên bản trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.change_reason IS '{"cls":"C1","desc":"Lý do điều chỉnh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.co_assignees IS '{"cls":"C1","desc":"Người phối hợp dự kiến"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.recurrence IS '{"cls":"C1","desc":"Lặp lại (hằng tháng, hằng quý)"}';

-- ---------------------------------------------------------------- RLS (NEN-033)
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.work_catalog');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.catalog_item');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.program');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.program_milestone');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.plan');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.plan_version');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.plan_item');
