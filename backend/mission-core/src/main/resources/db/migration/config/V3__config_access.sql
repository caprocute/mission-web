-- CORE-08-01 · MOD-08 schema config, phần quyền và vận hành (TL-03 bảng config.feature_flag,
-- config.role_binding, config.period_lock_order, config.period_lock_step, config.dashboard_def).
-- CSDL chỉ chặn trường hợp hiển nhiên (tự duyệt gán vai trò, một người vừa lập vừa duyệt lệnh khoá kỳ);
-- tách vai trò PL 4 V.1 kiểm bằng OPA lúc thao tác.

CREATE TABLE ${flyway:defaultSchema}.feature_flag (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    flag_key varchar(100) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    rollout jsonb NOT NULL DEFAULT '{}'::jsonb,
    valid_from timestamptz,
    valid_to timestamptz,
    CONSTRAINT pk_feature_flag PRIMARY KEY (id),
    CONSTRAINT uq_feature_flag__key UNIQUE (tenant_id, flag_key),
    CONSTRAINT ck_feature_flag__valid CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from < valid_to)
);
COMMENT ON TABLE ${flyway:defaultSchema}.feature_flag IS 'Cờ tính năng theo tenant';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.flag_key IS '{"cls": "C1", "desc": "Mã cờ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.enabled IS '{"cls": "C1", "desc": "Bật hay tắt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.rollout IS '{"cls": "C1", "desc": "Quy tắc bật dần theo đơn vị, tỷ lệ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.valid_from IS '{"cls": "C1", "desc": "Bắt đầu hiệu lực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.feature_flag.valid_to IS '{"cls": "C1", "desc": "Hết hiệu lực"}';

CREATE TABLE ${flyway:defaultSchema}.role_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    person_id uuid NOT NULL,
    role_code varchar(32) NOT NULL,
    org_unit_id uuid NOT NULL,
    scope varchar(24) NOT NULL,
    flow varchar(12),
    valid_from timestamptz NOT NULL,
    valid_to timestamptz NOT NULL DEFAULT 'infinity',
    granted_by uuid NOT NULL,
    approved_by uuid,
    CONSTRAINT pk_role_binding PRIMARY KEY (id),
    CONSTRAINT ck_role_binding__role_code CHECK (role_code IN ('tenant_admin', 'policy_author', 'leader', 'manager', 'member', 'appraiser', 'decider', 'second_approver', 'inspector')),
    CONSTRAINT ck_role_binding__scope CHECK (scope IN ('DON_VI', 'DON_VI_VA_CAP_DUOI')),
    CONSTRAINT ck_role_binding__flow CHECK (flow IS NULL OR flow IN ('DANG', 'NHA_NUOC', 'CA_HAI')),
    CONSTRAINT ck_role_binding__valid CHECK (valid_from < valid_to),
    CONSTRAINT ck_role_binding__four_eyes CHECK (approved_by IS NULL OR approved_by <> granted_by)
);
CREATE INDEX ix_role_binding__person ON ${flyway:defaultSchema}.role_binding (tenant_id, person_id);
CREATE INDEX ix_role_binding__unit_role ON ${flyway:defaultSchema}.role_binding (tenant_id, org_unit_id, role_code);
COMMENT ON TABLE ${flyway:defaultSchema}.role_binding IS 'Gán vai trò hệ thống theo đơn vị và phạm vi ABAC';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.person_id IS '{"cls": "C1", "desc": "Người được gán"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.role_code IS '{"cls": "C1", "desc": "Vai trò hệ thống (baseline mục 10)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.org_unit_id IS '{"cls": "C1", "desc": "Đơn vị phạm vi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.scope IS '{"cls": "C1", "desc": "Phạm vi ABAC"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.flow IS '{"cls": "C1", "desc": "Giới hạn theo luồng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.valid_from IS '{"cls": "C1", "desc": "Bắt đầu hiệu lực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.valid_to IS '{"cls": "C1", "desc": "Hết hiệu lực; infinity là vô thời hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.granted_by IS '{"cls": "C1", "desc": "Người cấp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.approved_by IS '{"cls": "C1", "desc": "Người duyệt thứ hai cho vai trò nhạy cảm"}';

CREATE TABLE ${flyway:defaultSchema}.period_lock_order (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    approved_by uuid,
    approved_at timestamptz,
    started_by uuid,
    started_at timestamptz,
    basis_doc varchar(200) NOT NULL,
    saga_id uuid,
    idempotency_key varchar(64) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    cancel_requested_by uuid,
    cancel_approved_by uuid,
    CONSTRAINT pk_period_lock_order PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_order__period FOREIGN KEY (period_id) REFERENCES ${flyway:defaultSchema}.period (id),
    CONSTRAINT uq_period_lock_order__idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_period_lock_order__status CHECK (status IN ('NHAP', 'CHO_DUYET', 'DA_DUYET', 'DANG_CHAY', 'HOAN_TAT', 'DA_HUY')),
    CONSTRAINT ck_period_lock_order__four_eyes CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT ck_period_lock_order__cancel_four_eyes CHECK (cancel_approved_by IS NULL OR cancel_approved_by <> cancel_requested_by)
);
-- Mỗi kỳ chỉ có một lệnh khoá chưa huỷ (TL-03 UQ một phần).
CREATE UNIQUE INDEX ix_period_lock_order__period_active ON ${flyway:defaultSchema}.period_lock_order (tenant_id, period_id)
  WHERE status <> 'DA_HUY';
COMMENT ON TABLE ${flyway:defaultSchema}.period_lock_order IS 'Lệnh khoá kỳ do người có thẩm quyền lập và duyệt trước khi chạy saga chốt kỳ (QT-08)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.period_id IS '{"cls": "C1", "desc": "Kỳ cần khoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.requested_by IS '{"cls": "C1", "desc": "Người lập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.approved_by IS '{"cls": "C1", "desc": "Người duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.approved_at IS '{"cls": "C1", "desc": "Thời điểm duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.started_by IS '{"cls": "C1", "desc": "Người khởi chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.started_at IS '{"cls": "C1", "desc": "Thời điểm khởi chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.basis_doc IS '{"cls": "C1", "desc": "Văn bản căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.saga_id IS '{"cls": "C1", "desc": "Saga chốt kỳ (flowable.saga_instance.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.idempotency_key IS '{"cls": "C1", "desc": "Khoá chống khởi chạy trùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.status IS '{"cls": "C1", "desc": "Trạng thái lệnh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.cancel_requested_by IS '{"cls": "C1", "desc": "Người đề nghị huỷ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.cancel_approved_by IS '{"cls": "C1", "desc": "Người duyệt huỷ"}';

CREATE TABLE ${flyway:defaultSchema}.period_lock_step (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    lock_order_id uuid NOT NULL,
    step_no integer NOT NULL,
    name varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'CHO',
    started_at timestamptz,
    finished_at timestamptz,
    result_summary jsonb,
    compensation_action varchar(48),
    compensated_at timestamptz,
    CONSTRAINT pk_period_lock_step PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_step__period_lock_order FOREIGN KEY (lock_order_id) REFERENCES ${flyway:defaultSchema}.period_lock_order (id),
    CONSTRAINT uq_period_lock_step__step UNIQUE (lock_order_id, step_no),
    CONSTRAINT ck_period_lock_step__status CHECK (status IN ('CHO', 'DANG_CHAY', 'DAT', 'DAT_CO_CANH_BAO', 'LOI', 'DA_BU_TRU')),
    CONSTRAINT ck_period_lock_step__step_no CHECK (step_no >= 1)
);
COMMENT ON TABLE ${flyway:defaultSchema}.period_lock_step IS 'Bản chiếu từng bước saga chốt kỳ cho màn hình theo dõi; nguồn sự thật là flowable.saga_step';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.id IS '{"cls": "C1", "desc": "Mã"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.lock_order_id IS '{"cls": "C1", "desc": "Lệnh khoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.step_no IS '{"cls": "C1", "desc": "Thứ tự bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.name IS '{"cls": "C1", "desc": "Tên bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.status IS '{"cls": "C1", "desc": "Trạng thái bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.started_at IS '{"cls": "C1", "desc": "Bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.finished_at IS '{"cls": "C1", "desc": "Kết thúc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.result_summary IS '{"cls": "C1", "desc": "Tóm tắt kết quả (số hồ sơ, cảnh báo)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.compensation_action IS '{"cls": "C1", "desc": "Hành động bù trừ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_step.compensated_at IS '{"cls": "C1", "desc": "Thời điểm bù trừ"}';

CREATE TABLE ${flyway:defaultSchema}.dashboard_def (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    owner_user_id uuid NOT NULL,
    title varchar(255) NOT NULL,
    measures jsonb NOT NULL,
    dimensions jsonb NOT NULL,
    layout_json jsonb NOT NULL,
    share_scope varchar(12) NOT NULL DEFAULT 'RIENG',
    shared_unit_ids uuid[] NOT NULL DEFAULT '{}',
    CONSTRAINT pk_dashboard_def PRIMARY KEY (id),
    CONSTRAINT ck_dashboard_def__share_scope CHECK (share_scope IN ('RIENG', 'DON_VI', 'TENANT'))
);
CREATE INDEX ix_dashboard_def__owner ON ${flyway:defaultSchema}.dashboard_def (tenant_id, owner_user_id);
COMMENT ON TABLE ${flyway:defaultSchema}.dashboard_def IS 'Định nghĩa dashboard người dùng tự dựng (QT-16); dữ liệu hiển thị lấy từ SVC-15';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.owner_user_id IS '{"cls": "C1", "desc": "Người tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.title IS '{"cls": "C1", "desc": "Tên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.measures IS '{"cls": "C1", "desc": "Chỉ số theo tên trong mô hình Cube"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.dimensions IS '{"cls": "C1", "desc": "Chiều theo tên trong mô hình Cube"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.layout_json IS '{"cls": "C1", "desc": "Bố cục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.share_scope IS '{"cls": "C1", "desc": "Phạm vi chia sẻ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dashboard_def.shared_unit_ids IS '{"cls": "C1", "desc": "Đơn vị được xem", "type_exception": "TL-03 bảng config.dashboard_def ghi uuid[]"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.feature_flag');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.role_binding');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_lock_order');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_lock_step');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.dashboard_def');
