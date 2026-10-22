-- CNH-02 · Tuỳ chọn cá nhân và bộ lọc đã lưu của người dùng (NFR-11, ADR-05; đề xuất CNH-01 mục 3,
-- docs/de-xuat/de-xuat-ca-nhan-hoa.md). TL-03 chưa có hai bảng này (suy luận, wiki trang 14).
-- Đổi so với phác thảo DDL của đề xuất để khớp quy ước lint và các bảng config sẵn có:
-- version_no thành row_version (khoá lạc quan chung), smallint thành integer, id sinh ở ứng dụng,
-- RLS theo tenant bằng fn_enable_tenant_rls; giới hạn "chính chủ" do dịch vụ lọc user_id và OPA
-- phạm vi Bản thân (B) đảm nhận, không đặt thêm biến phiên app.current_user_id.

CREATE TABLE ${flyway:defaultSchema}.user_preference (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    user_id uuid NOT NULL,
    pref_key varchar(100) NOT NULL,
    value jsonb NOT NULL,
    value_schema_version integer NOT NULL DEFAULT 1,
    CONSTRAINT pk_user_preference PRIMARY KEY (id),
    CONSTRAINT uq_user_preference__key UNIQUE (tenant_id, user_id, pref_key),
    CONSTRAINT ck_user_preference__pref_key CHECK (
        pref_key ~ '^(ui\.display|ui\.home\.layout|ui\.dashboard\.default|ui\.table\.SCR-[0-9]{4}|notify\.channel_pref)$'),
    CONSTRAINT ck_user_preference__value_object CHECK (jsonb_typeof(value) = 'object'),
    CONSTRAINT ck_user_preference__value_size CHECK (octet_length(value::text) <= 16384),
    CONSTRAINT ck_user_preference__schema_version CHECK (value_schema_version >= 1)
);
COMMENT ON TABLE ${flyway:defaultSchema}.user_preference IS 'Tuỳ chọn trình bày cá nhân của người dùng (CNH-01, NFR-11)';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.created_by IS '{"cls": "C1", "desc": "Người tạo (chính chủ)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.user_id IS '{"cls": "C1", "desc": "Chủ tuỳ chọn (core.account.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.pref_key IS '{"cls": "C1", "desc": "Khoá tuỳ chọn, ví dụ ui.display, ui.table.SCR-0201"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.value IS '{"cls": "C1", "desc": "Giá trị theo user-preference.schema.json, tối đa 16 KB"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.user_preference.value_schema_version IS '{"cls": "C1", "desc": "Phiên bản lược đồ của giá trị"}';
CREATE INDEX ix_user_preference__user ON ${flyway:defaultSchema}.user_preference (tenant_id, user_id);

CREATE TABLE ${flyway:defaultSchema}.saved_filter (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    user_id uuid NOT NULL,
    screen_code varchar(32) NOT NULL,
    filter_name varchar(120) NOT NULL,
    criteria jsonb NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    display_order integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_saved_filter PRIMARY KEY (id),
    CONSTRAINT uq_saved_filter__name UNIQUE (tenant_id, user_id, screen_code, filter_name),
    CONSTRAINT ck_saved_filter__screen_code CHECK (screen_code ~ '^SCR-[0-9]{4}$'),
    CONSTRAINT ck_saved_filter__criteria_object CHECK (jsonb_typeof(criteria) = 'object'),
    CONSTRAINT ck_saved_filter__criteria_size CHECK (octet_length(criteria::text) <= 8192),
    CONSTRAINT ck_saved_filter__display_order CHECK (display_order BETWEEN 0 AND 19)
);
COMMENT ON TABLE ${flyway:defaultSchema}.saved_filter IS 'Bộ lọc danh sách đã đặt tên của người dùng (CNH-01)';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.created_by IS '{"cls": "C1", "desc": "Người tạo (chính chủ)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.user_id IS '{"cls": "C1", "desc": "Chủ bộ lọc (core.account.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.screen_code IS '{"cls": "C1", "desc": "Mã màn hình danh sách áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.filter_name IS '{"cls": "C1", "desc": "Tên gợi nhớ do người dùng đặt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.criteria IS '{"cls": "C1", "desc": "Tiêu chí lọc chỉ gồm mã và khoảng giá trị, không có văn bản tự do"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.is_default IS '{"cls": "C1", "desc": "Bộ lọc tự áp khi mở màn hình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.saved_filter.display_order IS '{"cls": "C1", "desc": "Thứ tự trên thanh lọc nhanh (0..19)"}';
CREATE UNIQUE INDEX ix_saved_filter__one_default ON ${flyway:defaultSchema}.saved_filter (tenant_id, user_id, screen_code) WHERE is_default;
CREATE INDEX ix_saved_filter__lookup ON ${flyway:defaultSchema}.saved_filter (tenant_id, user_id, screen_code, display_order);

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.user_preference');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.saved_filter');
