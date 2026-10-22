-- CORE-08-01 · MOD-08 schema config, phần giao diện theo tenant (TL-03 bảng config.ui_pack,
-- config.ui_pack_version, config.form_schema, config.component_registry; ADR-05).
-- Bộ cột trùng nghĩa của TL-03 được chọn theo tên trường trong contracts/openapi/modules/config.yaml
-- (API-first, wiki trang 14): ui_pack_version giữ tokens, layouts, policy_pack_version_id (bỏ
-- tokens_json, layout_json, policy_pack_ref); form_schema giữ form_key, schema_json, ui_json (bỏ
-- form_code, json_schema, ui_schema). assets_json không có cột trùng nên giữ tên TL-03.
-- FK vòng ui_pack.current_version_id tạo sau bảng phiên bản, DEFERRABLE (suy luận) để phát hành
-- phiên bản đầu và gắn vào gói trong cùng giao dịch.

CREATE TABLE ${flyway:defaultSchema}.ui_pack (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    current_version_id uuid,
    CONSTRAINT pk_ui_pack PRIMARY KEY (id),
    CONSTRAINT uq_ui_pack__code UNIQUE (tenant_id, code)
);
COMMENT ON TABLE ${flyway:defaultSchema}.ui_pack IS 'UI Pack của tenant';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.code IS '{"cls": "C0", "desc": "Mã UI Pack"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.name IS '{"cls": "C0", "desc": "Tên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack.current_version_id IS '{"cls": "C1", "desc": "Phiên bản đang phát hành"}';

CREATE TABLE ${flyway:defaultSchema}.ui_pack_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    ui_pack_id uuid NOT NULL,
    version varchar(32) NOT NULL,
    tokens jsonb NOT NULL,
    layouts jsonb NOT NULL,
    assets_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    component_registry_version varchar(32) NOT NULL,
    policy_pack_version_id uuid,
    bundle_object_key varchar(512),
    bundle_sha256 bytea,
    signature bytea,
    status varchar(16) NOT NULL DEFAULT 'NHAP',
    published_at timestamptz,
    elder_mode boolean NOT NULL DEFAULT false,
    few_steps boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_ui_pack_version PRIMARY KEY (id),
    CONSTRAINT uq_ui_pack_version__version UNIQUE (ui_pack_id, version),
    CONSTRAINT fk_ui_pack_version__ui_pack FOREIGN KEY (ui_pack_id) REFERENCES ${flyway:defaultSchema}.ui_pack (id),
    CONSTRAINT ck_ui_pack_version__status CHECK (status IN ('NHAP', 'DANG_KIEM_TRA', 'CHO_DUYET', 'DA_PHAT_HANH', 'THAY_THE')),
    CONSTRAINT ck_ui_pack_version__sha256 CHECK (bundle_sha256 IS NULL OR octet_length(bundle_sha256) = 32)
);
CREATE INDEX ix_ui_pack_version__pack_status ON ${flyway:defaultSchema}.ui_pack_version (tenant_id, ui_pack_id, status);
ALTER TABLE ${flyway:defaultSchema}.ui_pack
  ADD CONSTRAINT fk_ui_pack__ui_pack_version FOREIGN KEY (current_version_id)
  REFERENCES ${flyway:defaultSchema}.ui_pack_version (id) DEFERRABLE INITIALLY DEFERRED;
COMMENT ON TABLE ${flyway:defaultSchema}.ui_pack_version IS 'Phiên bản UI Pack: Design Token W3C, bố cục SDUI, gói đã biên dịch';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.ui_pack_id IS '{"cls": "C1", "desc": "UI Pack"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.version IS '{"cls": "C0", "desc": "Phiên bản ngữ nghĩa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.tokens IS '{"cls": "C0", "desc": "Design Token chuẩn W3C, gồm bộ token chế độ người cao tuổi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.layouts IS '{"cls": "C0", "desc": "Bố cục SDUI, chỉ tham chiếu component trong danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.assets_json IS '{"cls": "C0", "desc": "Tài nguyên (logo, phông)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.component_registry_version IS '{"cls": "C0", "desc": "Phiên bản danh mục component được phép"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản Policy Pack đi kèm (tham chiếu logic policy.policy_pack_version)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.bundle_object_key IS '{"cls": "C1", "desc": "Khoá đối tượng Ceph của gói đã biên dịch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.bundle_sha256 IS '{"cls": "C1", "desc": "Băm gói, dùng làm ETag và integrity"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.signature IS '{"cls": "C1", "desc": "Chữ ký số của gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.status IS '{"cls": "C1", "desc": "Trạng thái phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.published_at IS '{"cls": "C1", "desc": "Thời điểm phát hành (EVT-23)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.elder_mode IS '{"cls": "C0", "desc": "Chế độ chữ lớn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.few_steps IS '{"cls": "C0", "desc": "Chế độ ít bước"}';

CREATE TABLE ${flyway:defaultSchema}.form_schema (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    form_key varchar(64) NOT NULL,
    version varchar(32) NOT NULL,
    schema_json jsonb NOT NULL,
    ui_json jsonb NOT NULL,
    cel_rules jsonb NOT NULL DEFAULT '[]'::jsonb,
    policy_pack_version_id uuid,
    status varchar(16) NOT NULL DEFAULT 'NHAP',
    CONSTRAINT pk_form_schema PRIMARY KEY (id),
    CONSTRAINT uq_form_schema__key_version UNIQUE (tenant_id, form_key, version),
    CONSTRAINT ck_form_schema__status CHECK (status IN ('NHAP', 'DA_PHAT_HANH', 'NGUNG'))
);
COMMENT ON TABLE ${flyway:defaultSchema}.form_schema IS 'Biểu mẫu động: JSON Schema, cấu hình Formly, quy tắc CEL';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.form_key IS '{"cls": "C0", "desc": "Khoá biểu mẫu, ví dụ phieu-tu-danh-gia-quy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.version IS '{"cls": "C0", "desc": "Phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.schema_json IS '{"cls": "C0", "desc": "JSON Schema 2020-12 có x-cel và x-classification"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.ui_json IS '{"cls": "C0", "desc": "Cấu hình trường Formly, gợi ý hiển thị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.cel_rules IS '{"cls": "C0", "desc": "Quy tắc CEL chạy trên cel-js và cel-java"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản Policy Pack gắn kèm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_schema.status IS '{"cls": "C1", "desc": "Trạng thái biểu mẫu"}';

CREATE TABLE ${flyway:defaultSchema}.component_registry (
    id uuid NOT NULL,
    registry_version varchar(16) NOT NULL,
    component_key varchar(64) NOT NULL,
    props_schema jsonb NOT NULL,
    allowed_classification varchar(4) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'HIEU_LUC',
    CONSTRAINT pk_component_registry PRIMARY KEY (id),
    CONSTRAINT uq_component_registry__key UNIQUE (registry_version, component_key),
    CONSTRAINT ck_component_registry__classification CHECK (allowed_classification IN ('C0', 'C1', 'C2', 'C3')),
    CONSTRAINT ck_component_registry__status CHECK (status IN ('HIEU_LUC', 'NGUNG'))
);
COMMENT ON TABLE ${flyway:defaultSchema}.component_registry IS 'Danh mục component SDUI được phép theo phiên bản; dùng chung nền tảng, không có tenant';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.id IS '{"cls": "C0", "desc": "Mã"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.registry_version IS '{"cls": "C0", "desc": "Phiên bản registry"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.component_key IS '{"cls": "C0", "desc": "Khoá component"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.props_schema IS '{"cls": "C0", "desc": "JSON Schema của thuộc tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.allowed_classification IS '{"cls": "C0", "desc": "Mức phân loại cao nhất component được hiển thị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.component_registry.status IS '{"cls": "C0", "desc": "Trạng thái"}';

-- Seed ví dụ (suy luận): một phiên bản registry lấy tên và props schema của component web hiện có
-- (frontend/projects/sdui-renderer builtin-components.ts, fe-shell widgets/shell-widgets.ts).
INSERT INTO ${flyway:defaultSchema}.component_registry (id, registry_version, component_key, props_schema, allowed_classification, status)
VALUES
    ('01927a08-0000-7000-8000-000000000001', '2026.3', 'vt.layout.grid', '{"type":"object","additionalProperties":false,"properties":{"columns":{"type":"object"}}}'::jsonb, 'C1', 'HIEU_LUC'),
    ('01927a08-0000-7000-8000-000000000002', '2026.3', 'vt.widget.myTasksToday', '{"type":"object","additionalProperties":false,"properties":{"limit":{"type":"integer","minimum":1,"maximum":20}}}'::jsonb, 'C1', 'HIEU_LUC'),
    ('01927a08-0000-7000-8000-000000000003', '2026.3', 'vt.widget.okrProgress', '{"type":"object","additionalProperties":false,"properties":{"scope":{"type":"string","enum":["me","unit"]}}}'::jsonb, 'C1', 'HIEU_LUC'),
    ('01927a08-0000-7000-8000-000000000004', '2026.3', 'vt.widget.pendingApprovals', '{"type":"object","additionalProperties":false,"properties":{}}'::jsonb, 'C1', 'HIEU_LUC'),
    ('01927a08-0000-7000-8000-000000000005', '2026.3', 'vt.widget.announcement', '{"type":"object","additionalProperties":false,"properties":{"i18nKey":{"type":"string","pattern":"^[a-z0-9.]+$"}}}'::jsonb, 'C1', 'HIEU_LUC');

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.ui_pack');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.ui_pack_version');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.form_schema');
