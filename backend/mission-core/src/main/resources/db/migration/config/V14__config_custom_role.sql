-- CORE-08-18 · BS-04 ADR-34 mục 4: vai trò tuỳ chỉnh theo tenant, thu hẹp một vai trò hệ thống (BR-01-23, BR-01-24).
-- Bảng bổ sung (TL-03 chưa có). Mỗi vai trò là một realm role Keycloak cùng mã `tc_…` và được đẩy sang OPA ở
-- data.tenant_roles; cấp cho người dùng vẫn qua config.role_binding nên CHECK mã vai trò được nới cho mã `tc_`.
CREATE TABLE ${flyway:defaultSchema}.custom_role (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    code varchar(32) NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(500),
    base_role varchar(32) NOT NULL,
    permissions jsonb NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'HIEU_LUC',
    retired_at timestamptz,
    retired_by uuid,
    retire_reason varchar(500),
    CONSTRAINT pk_custom_role PRIMARY KEY (id),
    CONSTRAINT uq_custom_role__code UNIQUE (code),
    CONSTRAINT uq_custom_role__name UNIQUE (tenant_id, name),
    CONSTRAINT ck_custom_role__code CHECK (code ~ '^tc_[a-z0-9_]{3,29}$'),
    CONSTRAINT ck_custom_role__base_role CHECK (base_role IN ('leader', 'manager', 'member', 'appraiser', 'inspector')),
    CONSTRAINT ck_custom_role__status CHECK (status IN ('HIEU_LUC', 'NGUNG')),
    CONSTRAINT ck_custom_role__permissions CHECK (jsonb_typeof(permissions) = 'object'),
    CONSTRAINT ck_custom_role__retired CHECK (status <> 'NGUNG' OR retired_at IS NOT NULL)
);
CREATE INDEX ix_custom_role__tenant_status ON ${flyway:defaultSchema}.custom_role (tenant_id, status);

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.custom_role');

COMMENT ON TABLE ${flyway:defaultSchema}.custom_role IS 'Vai trò tuỳ chỉnh của tenant, thu hẹp một vai trò hệ thống (CORE-08-18, BS-04)';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.created_by IS '{"cls": "C1", "desc": "Người tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.code IS '{"cls": "C1", "desc": "Mã vai trò, cũng là realm role Keycloak: tc_<8 ký tự đầu mã tenant>_<mã ngắn>; duy nhất toàn cell"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.name IS '{"cls": "C1", "desc": "Tên vai trò hiển thị, duy nhất trong tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.description IS '{"cls": "C1", "desc": "Mô tả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.base_role IS '{"cls": "C1", "desc": "Vai trò hệ thống gốc; vai trò tuỳ chỉnh kế thừa AAL2, mức dữ liệu và ràng buộc của vai trò này"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.permissions IS '{"cls": "C1", "desc": "Khoá chức năng của ma trận -> phạm vi (N, Tn, Cd, Dv, Gn, B); tập con quyền của vai trò gốc (BR-01-23)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.status IS '{"cls": "C1", "desc": "HIEU_LUC hoặc NGUNG"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.retired_at IS '{"cls": "C1", "desc": "Thời điểm ngừng sử dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.retired_by IS '{"cls": "C1", "desc": "Người ngừng sử dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.custom_role.retire_reason IS '{"cls": "C1", "desc": "Lý do ngừng sử dụng"}';

-- config.role_binding nhận thêm mã vai trò tuỳ chỉnh (BR-01-23); vai trò hệ thống giữ nguyên danh sách cũ.
ALTER TABLE ${flyway:defaultSchema}.role_binding DROP CONSTRAINT ck_role_binding__role_code;
ALTER TABLE ${flyway:defaultSchema}.role_binding
    ADD CONSTRAINT ck_role_binding__role_code
    CHECK (role_code IN ('tenant_admin', 'policy_author', 'leader', 'manager', 'member', 'appraiser', 'decider', 'second_approver', 'inspector')
           OR role_code ~ '^tc_[a-z0-9_]{3,29}$');
