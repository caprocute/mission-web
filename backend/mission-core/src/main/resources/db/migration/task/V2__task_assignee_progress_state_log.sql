-- CORE-02-02 · Schema task (MOD-02) phần nhiệm vụ (TL-03 "DDL mẫu: task.task" và các bảng task.task,
-- task_assignee, task_progress, task_blocker, task_change_request, task_source_doc, task_state_log).
-- Mọi bảng phân vùng LIST (period_bucket); phân vùng theo kỳ do fn_create_period_partitions tạo khi mở kỳ
-- (CORE-08-02), ở CSDL pool chia tiếp 16 phân vùng con HASH (tenant_id). EXCLUDE trộn uuid/varchar với khoảng
-- cần btree_gist, do DatabaseBootstrap (NEN-031) cài.
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 41):
--   * Khoá chính, khoá duy nhất, khoá ngoại và EXCLUDE chứa period_bucket và tenant_id như CORE-02-01
--     (TL-03 ghi PK (period_bucket, id)); vệ tinh nối task.task bằng khoá ngoại ba cột.
--   * Chỉ mục duy nhất một phần uq_task_assignee__one_lead_p đặt tên ix_task_assignee__one_lead_p theo lint
--     NEN-030, và chỉ xét dòng còn hiệu lực ghi nhận (recorded_to = infinity) để đính chính không bị chặn.
--   * valid_to, recorded_to NOT NULL DEFAULT 'infinity' như org-service (TL-03 cho phép NULL).
--   * task_change_request.request_type theo hợp đồng ChangeRequestType (GIA_HAN, DOI_SO_LUONG, DOI_SAN_PHAM,
--     TAM_DUNG, HUY), không theo CHECK của TL-03.
--   * task_source_doc: khoá chống trùng dùng (source_system, external_doc_id, paragraph_no) vì TL-03 ghi cột
--     doc_ref không có trong bảng.
--   * Chỉ tạo source, key_result_id (TL-03, hợp đồng); không tạo cột trùng nghĩa source_type, kr_id của TL-02a.
--   * unit dài 50 theo hợp đồng (TL-03 ghi varchar(32)).

-- ---------------------------------------------------------------- task
CREATE TABLE ${flyway:defaultSchema}.task (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    code varchar(32) NOT NULL,
    title varchar(500) NOT NULL,
    description text,
    task_group varchar(16) NOT NULL,
    is_key_task boolean NOT NULL DEFAULT false,
    source varchar(16) NOT NULL,
    plan_item_id uuid,
    program_id uuid,
    catalog_item_id uuid,
    key_result_id uuid,
    parent_task_id uuid,
    assigner_id uuid NOT NULL,
    assigned_at timestamptz,
    acceptor_id uuid,
    start_date date,
    due_date date NOT NULL,
    extended_due_date date,
    score_due_date date,
    milestones jsonb,
    planned_quantity numeric(18,4) NOT NULL DEFAULT 1,
    done_quantity numeric(18,4) NOT NULL DEFAULT 0,
    unit varchar(50) NOT NULL,
    difficulty smallint,
    standard_score numeric(6,3),
    progress_pct numeric(6,4) NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'NHAP',
    is_overdue boolean NOT NULL DEFAULT false,
    is_restricted boolean NOT NULL DEFAULT false,
    delay_risk varchar(8),
    dup_fingerprint bytea NOT NULL,
    state_machine_version varchar(32) NOT NULL,
    completed_at timestamptz,
    cancelled_reason varchar(1000),
    CONSTRAINT pk_task PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_task__code UNIQUE (period_bucket, tenant_id, code),
    CONSTRAINT ck_task__status CHECK (status IN ('NHAP', 'DA_GIAO', 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU', 'HOAN_THANH', 'TAM_DUNG', 'HUY')),
    CONSTRAINT ck_task__group CHECK (task_group IN ('THUONG_XUYEN', 'THEN_CHOT')),
    CONSTRAINT ck_task__source CHECK (source IN ('KE_HOACH', 'VAN_BAN', 'DANG_KY', 'DOT_XUAT')),
    CONSTRAINT ck_task__progress CHECK (progress_pct BETWEEN 0 AND 1),
    CONSTRAINT ck_task__quantity CHECK (planned_quantity > 0 AND done_quantity >= 0),
    CONSTRAINT ck_task__difficulty CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_task__score CHECK (standard_score IS NULL OR standard_score BETWEEN 0 AND 100),
    CONSTRAINT ck_task__dates CHECK (start_date IS NULL OR start_date <= due_date),
    CONSTRAINT ck_task__extended CHECK (extended_due_date IS NULL OR extended_due_date >= due_date),
    CONSTRAINT ck_task__done CHECK (status <> 'HOAN_THANH' OR completed_at IS NOT NULL),
    CONSTRAINT ck_task__delay_risk CHECK (delay_risk IS NULL OR delay_risk IN ('THAP', 'VUA', 'CAO')),
    CONSTRAINT ck_task__milestones CHECK (milestones IS NULL OR jsonb_typeof(milestones) = 'array'),
    CONSTRAINT ck_task__not_self CHECK (parent_task_id IS NULL OR parent_task_id <> id)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task__tenant_due_p ON ${flyway:defaultSchema}.task (tenant_id, due_date)
    WHERE status IN ('DA_GIAO', 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU');
CREATE INDEX ix_task__plan_item ON ${flyway:defaultSchema}.task (tenant_id, plan_item_id);
CREATE INDEX ix_task__key_result ON ${flyway:defaultSchema}.task (tenant_id, key_result_id) WHERE key_result_id IS NOT NULL;
CREATE INDEX ix_task__program ON ${flyway:defaultSchema}.task (tenant_id, program_id) WHERE program_id IS NOT NULL;
CREATE INDEX ix_task__dup ON ${flyway:defaultSchema}.task (tenant_id, period_id, dup_fingerprint);
CREATE INDEX ix_task__overdue_p ON ${flyway:defaultSchema}.task (tenant_id, due_date) WHERE is_overdue;
CREATE INDEX ix_task__parent ON ${flyway:defaultSchema}.task (tenant_id, parent_task_id) WHERE parent_task_id IS NOT NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.task IS 'Nhiệm vụ (PL 4 nhóm 5); vòng đời theo máy trạng thái khai báo (ADR-04 tầng 1)';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.code IS '{"cls":"C1","desc":"Mã nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.title IS '{"cls":"C1","desc":"Tiêu đề; nhiệm vụ mật là chuỗi che"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.description IS '{"cls":"C1","desc":"Mô tả; NULL với nhiệm vụ mật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.task_group IS '{"cls":"C1","desc":"Thường xuyên hoặc then chốt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.is_key_task IS '{"cls":"C1","desc":"Nhiệm vụ then chốt (điều kiện HTTNV)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.source IS '{"cls":"C1","desc":"Nguồn: kế hoạch, văn bản, đăng ký, đột xuất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.plan_item_id IS '{"cls":"C1","desc":"Dòng kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.program_id IS '{"cls":"C1","desc":"Chương trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.catalog_item_id IS '{"cls":"C1","desc":"Mục danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.key_result_id IS '{"cls":"C1","desc":"KR đóng góp (tham chiếu logic okr.key_result)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.parent_task_id IS '{"cls":"C1","desc":"Nhiệm vụ cha"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.assigner_id IS '{"cls":"C1","desc":"Người giao"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.assigned_at IS '{"cls":"C1","desc":"Thời điểm giao (EVT-04)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.acceptor_id IS '{"cls":"C1","desc":"Người nghiệm thu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.start_date IS '{"cls":"C1","desc":"Ngày bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.due_date IS '{"cls":"C1","desc":"Hạn thực hiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.extended_due_date IS '{"cls":"C1","desc":"Hạn sau gia hạn được duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.score_due_date IS '{"cls":"C1","desc":"Hạn tính hệ số tiến độ C"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.milestones IS '{"cls":"C1","desc":"Mốc trung gian"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.planned_quantity IS '{"cls":"C1","desc":"Khối lượng kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.done_quantity IS '{"cls":"C1","desc":"Khối lượng đã hoàn thành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.unit IS '{"cls":"C0","desc":"Đơn vị tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.difficulty IS '{"cls":"C1","desc":"Mức khó","type_exception":"smallint theo TL-03 cho mức 1-5"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.standard_score IS '{"cls":"C1","desc":"Điểm chuẩn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.progress_pct IS '{"cls":"C1","desc":"Tiến độ 0 đến 1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.status IS '{"cls":"C1","desc":"Trạng thái baseline 9.1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.is_overdue IS '{"cls":"C1","desc":"Cờ quá hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.is_restricted IS '{"cls":"C1","desc":"Nhiệm vụ mật, nội dung ở task_secret_content"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.delay_risk IS '{"cls":"C1","desc":"Nguy cơ trễ hạn do hệ thống đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.dup_fingerprint IS '{"cls":"C1","desc":"Băm chống trùng: tiêu đề chuẩn hoá, đơn vị tính, chủ trì"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.state_machine_version IS '{"cls":"C1","desc":"Phiên bản bảng chuyển trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.completed_at IS '{"cls":"C1","desc":"Thời điểm hoàn thành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.cancelled_reason IS '{"cls":"C1","desc":"Lý do huỷ"}';

-- ---------------------------------------------------------------- task_assignee
CREATE TABLE ${flyway:defaultSchema}.task_assignee (
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
    assignee_type varchar(8) NOT NULL,
    person_id uuid,
    org_unit_id uuid,
    role varchar(8) NOT NULL,
    accepted_at timestamptz,
    removed_at timestamptz,
    valid_from date NOT NULL,
    valid_to date NOT NULL DEFAULT 'infinity',
    recorded_from timestamptz NOT NULL DEFAULT now(),
    recorded_to timestamptz NOT NULL DEFAULT 'infinity',
    valid_period daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
    recorded_period tstzrange GENERATED ALWAYS AS (tstzrange(recorded_from, recorded_to, '[)')) STORED,
    CONSTRAINT pk_task_assignee PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_task_assignee__task FOREIGN KEY (period_bucket, tenant_id, task_id) REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_assignee__assignee_type CHECK (assignee_type IN ('CA_NHAN', 'DON_VI')),
    CONSTRAINT ck_task_assignee__role CHECK (role IN ('CHU_TRI', 'PHOI_HOP')),
    CONSTRAINT ck_task_assignee__target CHECK ((assignee_type = 'CA_NHAN' AND person_id IS NOT NULL) OR (assignee_type = 'DON_VI' AND org_unit_id IS NOT NULL)),
    CONSTRAINT ck_task_assignee__valid CHECK (valid_from < valid_to),
    CONSTRAINT ck_task_assignee__recorded CHECK (recorded_from < recorded_to),
    CONSTRAINT ex_task_assignee__no_overlap EXCLUDE USING gist (period_bucket WITH =, tenant_id WITH =, task_id WITH =, person_id WITH =, role WITH =, valid_period WITH &&, recorded_period WITH &&)
) PARTITION BY LIST (period_bucket);
CREATE UNIQUE INDEX ix_task_assignee__one_lead_p ON ${flyway:defaultSchema}.task_assignee (period_bucket, tenant_id, task_id)
    WHERE role = 'CHU_TRI' AND removed_at IS NULL AND recorded_to = 'infinity';
CREATE INDEX ix_task_assignee__person ON ${flyway:defaultSchema}.task_assignee (tenant_id, person_id, period_bucket);
CREATE INDEX ix_task_assignee__org_unit ON ${flyway:defaultSchema}.task_assignee (tenant_id, org_unit_id, period_bucket);
CREATE INDEX ix_task_assignee__task ON ${flyway:defaultSchema}.task_assignee (tenant_id, task_id, period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.task_assignee IS 'Người, đơn vị thực hiện nhiệm vụ; hai trục thời gian (TL-01 task.assignment)';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.assignee_type IS '{"cls":"C1","desc":"Người hay đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.person_id IS '{"cls":"C1","desc":"Người thực hiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.org_unit_id IS '{"cls":"C1","desc":"Đơn vị thực hiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.role IS '{"cls":"C1","desc":"Chủ trì hoặc phối hợp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.accepted_at IS '{"cls":"C1","desc":"Thời điểm nhận việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.removed_at IS '{"cls":"C1","desc":"Thời điểm rút khỏi nhiệm vụ (giao lại)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.valid_from IS '{"cls":"C1","desc":"Trục hiệu lực: từ ngày"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.valid_to IS '{"cls":"C1","desc":"Trục hiệu lực: đến ngày (không gồm)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.recorded_from IS '{"cls":"C1","desc":"Trục ghi nhận: từ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.recorded_to IS '{"cls":"C1","desc":"Trục ghi nhận: đến"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.valid_period IS '{"cls":"C1","desc":"Khoảng hiệu lực (cột sinh)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_assignee.recorded_period IS '{"cls":"C1","desc":"Khoảng ghi nhận (cột sinh)"}';

-- ---------------------------------------------------------------- task_progress
CREATE TABLE ${flyway:defaultSchema}.task_progress (
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
    reported_at timestamptz NOT NULL,
    progress_pct numeric(6,4) NOT NULL,
    done_quantity numeric(18,4),
    note varchar(2000),
    channel varchar(8) NOT NULL,
    idempotency_key varchar(64) NOT NULL,
    CONSTRAINT pk_task_progress PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_task_progress__idempotency_key UNIQUE (period_bucket, tenant_id, idempotency_key),
    CONSTRAINT fk_task_progress__task FOREIGN KEY (period_bucket, tenant_id, task_id) REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_progress__progress CHECK (progress_pct BETWEEN 0 AND 1),
    CONSTRAINT ck_task_progress__done_quantity CHECK (done_quantity IS NULL OR done_quantity >= 0),
    CONSTRAINT ck_task_progress__channel CHECK (channel IN ('WEB', 'MOBILE', 'TICH_HOP'))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task_progress__task_reported ON ${flyway:defaultSchema}.task_progress (tenant_id, task_id, reported_at DESC);

COMMENT ON TABLE ${flyway:defaultSchema}.task_progress IS 'Báo cáo tiến độ nhiệm vụ, chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.reported_at IS '{"cls":"C1","desc":"Thời điểm báo cáo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.progress_pct IS '{"cls":"C1","desc":"Tiến độ báo cáo 0 đến 1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.done_quantity IS '{"cls":"C1","desc":"Khối lượng đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.note IS '{"cls":"C1","desc":"Ghi chú (C1 theo TL-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.channel IS '{"cls":"C1","desc":"Kênh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.idempotency_key IS '{"cls":"C1","desc":"Chống gửi trùng"}';

-- ---------------------------------------------------------------- task_blocker
CREATE TABLE ${flyway:defaultSchema}.task_blocker (
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
    cause_category varchar(16) NOT NULL,
    cause_detail varchar(2000) NOT NULL,
    impact_level varchar(12) NOT NULL,
    handler_person_id uuid,
    resolve_by date,
    status varchar(16) NOT NULL DEFAULT 'MO',
    resolution varchar(2000),
    resolved_at timestamptz,
    escalated_to_person_id uuid,
    CONSTRAINT pk_task_blocker PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_task_blocker__task FOREIGN KEY (period_bucket, tenant_id, task_id) REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_blocker__cause_category CHECK (cause_category IN ('NGUON_LUC', 'PHOI_HOP', 'THU_TUC', 'KHACH_QUAN', 'KHAC')),
    CONSTRAINT ck_task_blocker__impact_level CHECK (impact_level IN ('CAO', 'TRUNG_BINH', 'THAP')),
    CONSTRAINT ck_task_blocker__status CHECK (status IN ('MO', 'DANG_XU_LY', 'DA_GIAI_QUYET', 'DONG')),
    CONSTRAINT ck_task_blocker__resolved CHECK (status <> 'DA_GIAI_QUYET' OR resolved_at IS NOT NULL)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task_blocker__task ON ${flyway:defaultSchema}.task_blocker (tenant_id, task_id, period_bucket);
CREATE INDEX ix_task_blocker__open_p ON ${flyway:defaultSchema}.task_blocker (tenant_id, resolve_by) WHERE status IN ('MO', 'DANG_XU_LY');

COMMENT ON TABLE ${flyway:defaultSchema}.task_blocker IS 'Điểm nghẽn, vướng mắc của nhiệm vụ (PL 4 nhóm 6)';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.cause_category IS '{"cls":"C1","desc":"Nhóm nguyên nhân"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.cause_detail IS '{"cls":"C1","desc":"Nguyên nhân cụ thể"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.impact_level IS '{"cls":"C1","desc":"Mức ảnh hưởng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.handler_person_id IS '{"cls":"C1","desc":"Người xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.resolve_by IS '{"cls":"C1","desc":"Hạn xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.resolution IS '{"cls":"C1","desc":"Kết quả xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.resolved_at IS '{"cls":"C1","desc":"Thời điểm giải quyết"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.escalated_to_person_id IS '{"cls":"C1","desc":"Cấp trên được báo"}';

-- ---------------------------------------------------------------- task_change_request
CREATE TABLE ${flyway:defaultSchema}.task_change_request (
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
    request_type varchar(24) NOT NULL,
    proposed_due_date date,
    proposed_quantity numeric(18,4),
    reason varchar(2000) NOT NULL,
    is_objective_reason boolean NOT NULL DEFAULT false,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    decided_by uuid,
    decided_at timestamptz,
    decision_note varchar(2000),
    cause_claimed varchar(16),
    cause_decided varchar(16),
    blocker_id uuid,
    approver_id uuid,
    attachment_ids uuid[] NOT NULL DEFAULT '{}',
    new_values jsonb NOT NULL DEFAULT '{}',
    CONSTRAINT pk_task_change_request PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_task_change_request__task FOREIGN KEY (period_bucket, tenant_id, task_id) REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_change_request__request_type CHECK (request_type IN ('GIA_HAN', 'DOI_SO_LUONG', 'DOI_SAN_PHAM', 'TAM_DUNG', 'HUY')),
    CONSTRAINT ck_task_change_request__status CHECK (status IN ('NHAP', 'CHO_DUYET', 'DA_DUYET', 'TU_CHOI', 'DA_RUT')),
    CONSTRAINT ck_task_change_request__four_eyes CHECK (decided_by IS NULL OR decided_by <> created_by),
    CONSTRAINT ck_task_change_request__decided CHECK (status NOT IN ('DA_DUYET', 'TU_CHOI') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_task_change_request__cause_claimed CHECK (cause_claimed IS NULL OR cause_claimed IN ('CHU_QUAN', 'KHACH_QUAN')),
    CONSTRAINT ck_task_change_request__cause_decided CHECK (cause_decided IS NULL OR cause_decided IN ('CHU_QUAN', 'KHACH_QUAN')),
    CONSTRAINT ck_task_change_request__extend CHECK (request_type <> 'GIA_HAN' OR proposed_due_date IS NOT NULL),
    CONSTRAINT ck_task_change_request__new_values CHECK (jsonb_typeof(new_values) = 'object')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task_change_request__task ON ${flyway:defaultSchema}.task_change_request (tenant_id, task_id, period_bucket);
CREATE INDEX ix_task_change_request__pending_p ON ${flyway:defaultSchema}.task_change_request (tenant_id, approver_id) WHERE status = 'CHO_DUYET';

COMMENT ON TABLE ${flyway:defaultSchema}.task_change_request IS 'Đề nghị gia hạn, điều chỉnh, tạm dừng, huỷ nhiệm vụ';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.request_type IS '{"cls":"C1","desc":"Loại đề nghị (theo hợp đồng ChangeRequestType)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.proposed_due_date IS '{"cls":"C1","desc":"Hạn đề nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.proposed_quantity IS '{"cls":"C1","desc":"Khối lượng đề nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.reason IS '{"cls":"C1","desc":"Lý do"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.is_objective_reason IS '{"cls":"C1","desc":"Lý do khách quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.decided_by IS '{"cls":"C1","desc":"Người quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.decided_at IS '{"cls":"C1","desc":"Thời điểm quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.decision_note IS '{"cls":"C1","desc":"Ý kiến"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.cause_claimed IS '{"cls":"C1","desc":"Nguyên nhân người đề nghị khai"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.cause_decided IS '{"cls":"C1","desc":"Nguyên nhân người duyệt kết luận (hệ số C)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.blocker_id IS '{"cls":"C1","desc":"Vướng mắc liên quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.approver_id IS '{"cls":"C1","desc":"Người duyệt được chỉ định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.attachment_ids IS '{"cls":"C1","desc":"Tệp kèm","type_exception":"uuid[] theo TL-03"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_change_request.new_values IS '{"cls":"C1","desc":"Giá trị đề nghị (hạn, tỷ trọng, mục tiêu)"}';

-- ---------------------------------------------------------------- task_source_doc
CREATE TABLE ${flyway:defaultSchema}.task_source_doc (
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
    source_system varchar(12) NOT NULL,
    external_doc_id varchar(128),
    doc_number varchar(64),
    doc_date date,
    excerpt varchar(1000),
    received_event_id uuid,
    ai_extracted boolean NOT NULL DEFAULT false,
    paragraph_no int,
    CONSTRAINT pk_task_source_doc PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_task_source_doc__paragraph UNIQUE (period_bucket, tenant_id, source_system, external_doc_id, paragraph_no),
    CONSTRAINT fk_task_source_doc__task FOREIGN KEY (period_bucket, tenant_id, task_id) REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_source_doc__source_system CHECK (source_system IN ('VOFFICE', 'IOFFICE', 'EDXML', 'LGSP', 'THU_CONG')),
    CONSTRAINT ck_task_source_doc__paragraph_no CHECK (paragraph_no IS NULL OR paragraph_no >= 1)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task_source_doc__task ON ${flyway:defaultSchema}.task_source_doc (tenant_id, task_id, period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.task_source_doc IS 'Văn bản nguồn giao nhiệm vụ (BR-05-07)';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng: năm*10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.source_system IS '{"cls":"C1","desc":"Hệ nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.external_doc_id IS '{"cls":"C1","desc":"Mã văn bản ở hệ nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.doc_number IS '{"cls":"C0","desc":"Số, ký hiệu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.doc_date IS '{"cls":"C0","desc":"Ngày văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.excerpt IS '{"cls":"C1","desc":"Trích yếu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.received_event_id IS '{"cls":"C1","desc":"EVT-24 đã sinh nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.ai_extracted IS '{"cls":"C1","desc":"Nhiệm vụ do AI trích, đã được người dùng xác nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.paragraph_no IS '{"cls":"C1","desc":"Đoạn văn bản giao việc"}';

-- ---------------------------------------------------------------- task_state_log
CREATE TABLE ${flyway:defaultSchema}.task_state_log (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    task_id uuid NOT NULL,
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    transition varchar(48) NOT NULL,
    actor_id uuid NOT NULL,
    reason varchar(2000),
    before_after jsonb NOT NULL,
    state_machine_version varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_task_state_log PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_task_state_log__task FOREIGN KEY (period_bucket, tenant_id, task_id) REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_state_log__to_status CHECK (to_status IN ('NHAP', 'DA_GIAO', 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU', 'HOAN_THANH', 'TAM_DUNG', 'HUY')),
    CONSTRAINT ck_task_state_log__from_status CHECK (from_status IS NULL OR from_status IN ('NHAP', 'DA_GIAO', 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU', 'HOAN_THANH', 'TAM_DUNG', 'HUY')),
    CONSTRAINT ck_task_state_log__before_after CHECK (jsonb_typeof(before_after) = 'object')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task_state_log__task_occurred ON ${flyway:defaultSchema}.task_state_log (tenant_id, task_id, occurred_at);

COMMENT ON TABLE ${flyway:defaultSchema}.task_state_log IS 'Nhật ký chuyển trạng thái nhiệm vụ, chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.id IS '{"cls":"C1","desc":"Mã dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.task_id IS '{"cls":"C1","desc":"Nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.from_status IS '{"cls":"C1","desc":"Trạng thái trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.to_status IS '{"cls":"C1","desc":"Trạng thái sau"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.transition IS '{"cls":"C1","desc":"Tên chuyển, ví dụ GIAO, NOP_NGHIEM_THU"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.actor_id IS '{"cls":"C1","desc":"Người thực hiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.reason IS '{"cls":"C1","desc":"Lý do"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.before_after IS '{"cls":"C1","desc":"Giá trị trước và sau (PL 4 III.2)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.state_machine_version IS '{"cls":"C1","desc":"Phiên bản quy tắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_state_log.occurred_at IS '{"cls":"C1","desc":"Thời điểm"}';
-- ---------------------------------------------------------------- RLS (NEN-033), bảng chỉ ghi thêm
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_assignee');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_progress');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_blocker');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_change_request');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_source_doc');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_state_log');

REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.task_state_log FROM svc_${flyway:defaultSchema};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.task_progress FROM svc_${flyway:defaultSchema};
