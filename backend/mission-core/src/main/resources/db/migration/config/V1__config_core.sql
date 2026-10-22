-- CORE-08-01 · MOD-08 schema config, phần lõi (TL-03 "Schema config (MOD-08)", "DDL mẫu: config.period",
-- bảng config.tenant_setting, config.period, config.period_status_log, config.holiday_calendar).
-- Bảng outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key của schema do phần dùng
-- chung nghiệp vụ (db/common-business, NEN-032) tạo; RLS bằng fn_enable_tenant_rls (NEN-033).
-- EXCLUDE trộn uuid/varchar với daterange cần btree_gist, do DatabaseBootstrap (NEN-031) cài.
-- Tên khoá ngoại theo quy ước lint fk_<bảng>__<bảng đích>: fk_period__parent của TL-03 thành
-- fk_period__period_parent.

CREATE TABLE ${flyway:defaultSchema}.tenant_setting (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    setting_key varchar(100) NOT NULL,
    scope_unit_id uuid,
    value jsonb NOT NULL,
    value_schema varchar(100) NOT NULL,
    description text,
    CONSTRAINT pk_tenant_setting PRIMARY KEY (id),
    CONSTRAINT uq_tenant_setting__key UNIQUE NULLS NOT DISTINCT (tenant_id, setting_key, scope_unit_id)
);
COMMENT ON TABLE ${flyway:defaultSchema}.tenant_setting IS 'Cấu hình theo tenant và đơn vị (MOD-08)';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.setting_key IS '{"cls": "C1", "desc": "Khoá cấu hình, ví dụ task.late.dayType"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.scope_unit_id IS '{"cls": "C1", "desc": "Đơn vị áp dụng; NULL là toàn tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.value IS '{"cls": "C1", "desc": "Giá trị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.value_schema IS '{"cls": "C1", "desc": "Mã JSON Schema kiểm tra giá trị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.tenant_setting.description IS '{"cls": "C0", "desc": "Ghi chú"}';

CREATE TABLE ${flyway:defaultSchema}.period (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    code varchar(32) NOT NULL,
    name varchar(100) NOT NULL,
    kind varchar(8) NOT NULL,
    flow varchar(12) NOT NULL,
    parent_period_id uuid,
    start_date date NOT NULL,
    end_date date NOT NULL,
    period_bucket integer NOT NULL,
    plan_deadline date,
    submission_deadline date,
    status varchar(16) NOT NULL DEFAULT 'MO',
    locked_at timestamptz,
    locked_by uuid,
    lock_saga_id uuid,
    reopen_count smallint NOT NULL DEFAULT 0,
    milestones jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_period PRIMARY KEY (id),
    CONSTRAINT uq_period__code UNIQUE (tenant_id, code, flow),
    CONSTRAINT fk_period__period_parent FOREIGN KEY (parent_period_id) REFERENCES ${flyway:defaultSchema}.period (id),
    CONSTRAINT ck_period__kind CHECK (kind IN ('THANG', 'QUY', 'NAM', 'NAM_HOC')),
    CONSTRAINT ck_period__flow CHECK (flow IN ('DANG', 'NHA_NUOC', 'CA_HAI')),
    CONSTRAINT ck_period__status CHECK (status IN ('MO', 'DANG_KHOA', 'DA_KHOA', 'MO_LAI_HAU_KIEM')),
    CONSTRAINT ck_period__dates CHECK (start_date <= end_date),
    CONSTRAINT ck_period__bucket CHECK (period_bucket BETWEEN 20200 AND 21004 AND period_bucket % 10 BETWEEN 0 AND 4),
    CONSTRAINT ck_period__locked CHECK (status <> 'DA_KHOA' OR locked_at IS NOT NULL),
    CONSTRAINT ck_period__reopen_count CHECK (reopen_count >= 0),
    CONSTRAINT ex_period__no_overlap EXCLUDE USING gist (tenant_id WITH =, kind WITH =, flow WITH =, daterange(start_date, end_date, '[]') WITH &&)
);
CREATE INDEX ix_period__tenant_status ON ${flyway:defaultSchema}.period (tenant_id, status, end_date);
CREATE INDEX ix_period__parent ON ${flyway:defaultSchema}.period (tenant_id, parent_period_id) WHERE parent_period_id IS NOT NULL;

-- Kỳ chỉ đi theo năm chuyển trạng thái hợp lệ của baseline mục 9.1 (TL-03 DDL mẫu).
CREATE FUNCTION ${flyway:defaultSchema}.fn_check_period_transition() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.status = NEW.status THEN RETURN NEW; END IF;
  IF (OLD.status, NEW.status) IN (('MO','DANG_KHOA'), ('DANG_KHOA','MO'),
                                  ('DANG_KHOA','DA_KHOA'), ('DA_KHOA','MO_LAI_HAU_KIEM'),
                                  ('MO_LAI_HAU_KIEM','DA_KHOA')) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'Chuyển trạng thái kỳ không hợp lệ: % -> %', OLD.status, NEW.status
    USING ERRCODE = 'check_violation';
END
$fn$;
CREATE TRIGGER tg_period__transition BEFORE UPDATE OF status ON ${flyway:defaultSchema}.period
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_check_period_transition();
COMMENT ON TABLE ${flyway:defaultSchema}.period IS 'Kỳ đánh giá; trạng thái kỳ quyết định quyền ghi dữ liệu thuộc kỳ (lớp bảo vệ thứ 5)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.code IS '{"cls": "C0", "desc": "Mã kỳ, ví dụ 2027-Q1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.name IS '{"cls": "C0", "desc": "Tên hiển thị, ví dụ Quý I/2027"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.kind IS '{"cls": "C1", "desc": "Loại kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.flow IS '{"cls": "C1", "desc": "Luồng áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.parent_period_id IS '{"cls": "C1", "desc": "Kỳ cha (quý thuộc năm, tháng thuộc quý)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.start_date IS '{"cls": "C1", "desc": "Ngày bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.end_date IS '{"cls": "C1", "desc": "Ngày cuối cùng, tính bao gồm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.period_bucket IS '{"cls": "C1", "desc": "Giá trị phân vùng năm × 10 + quý của mọi bảng thuộc kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.plan_deadline IS '{"cls": "C1", "desc": "Hạn duyệt kế hoạch quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.submission_deadline IS '{"cls": "C1", "desc": "Hạn nộp hồ sơ đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.status IS '{"cls": "C1", "desc": "Trạng thái kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.locked_at IS '{"cls": "C1", "desc": "Thời điểm khoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.locked_by IS '{"cls": "C1", "desc": "Người khoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.lock_saga_id IS '{"cls": "C1", "desc": "Saga chốt kỳ gần nhất (flowable.saga_instance.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.reopen_count IS '{"cls": "C1", "desc": "Số lần mở lại hậu kiểm", "type_exception": "TL-03 DDL mẫu config.period ghi smallint"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period.milestones IS '{"cls": "C1", "desc": "Các mốc của kỳ (TL-02a)"}';

CREATE TABLE ${flyway:defaultSchema}.period_status_log (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    period_id uuid NOT NULL,
    from_status varchar(16) NOT NULL,
    to_status varchar(16) NOT NULL,
    cause_type varchar(16) NOT NULL,
    cause_ref uuid NOT NULL,
    actor_id uuid NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_period_status_log PRIMARY KEY (id),
    CONSTRAINT fk_period_status_log__period FOREIGN KEY (period_id) REFERENCES ${flyway:defaultSchema}.period (id),
    CONSTRAINT ck_period_status_log__cause_type CHECK (cause_type IN ('SAGA_CHOT_KY', 'BU_TRU_SAGA', 'MO_LAI_HAU_KIEM', 'KHOA_LAI')),
    CONSTRAINT ck_period_status_log__from_status CHECK (from_status IN ('MO', 'DANG_KHOA', 'DA_KHOA', 'MO_LAI_HAU_KIEM')),
    CONSTRAINT ck_period_status_log__to_status CHECK (to_status IN ('MO', 'DANG_KHOA', 'DA_KHOA', 'MO_LAI_HAU_KIEM'))
);
CREATE INDEX ix_period_status_log__period ON ${flyway:defaultSchema}.period_status_log (tenant_id, period_id, changed_at);

-- Chỉ ghi thêm (TL-03): thu quyền UPDATE, DELETE của svc_<schema> mà quyền mặc định (NEN-031) đã cấp;
-- trigger chặn thêm ở mọi vai trò, phòng khi bootstrap chạy lại cấp bù quyền trên bảng đã có.
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.period_status_log FROM svc_${flyway:defaultSchema};
CREATE FUNCTION ${flyway:defaultSchema}.fn_reject_period_status_log_change() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  RAISE EXCEPTION 'Nhật ký chuyển trạng thái kỳ chỉ được ghi thêm'
    USING ERRCODE = 'insufficient_privilege';
END
$fn$;
CREATE TRIGGER tg_period_status_log__append_only BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.period_status_log
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_reject_period_status_log_change();
COMMENT ON TABLE ${flyway:defaultSchema}.period_status_log IS 'Nhật ký chuyển trạng thái kỳ, chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.id IS '{"cls": "C1", "desc": "Mã dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.period_id IS '{"cls": "C1", "desc": "Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.from_status IS '{"cls": "C1", "desc": "Trạng thái trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.to_status IS '{"cls": "C1", "desc": "Trạng thái sau"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.cause_type IS '{"cls": "C1", "desc": "Nguyên nhân chuyển"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.cause_ref IS '{"cls": "C1", "desc": "Mã saga hoặc mã oversight.reopen_request"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.actor_id IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_status_log.changed_at IS '{"cls": "C1", "desc": "Thời điểm chuyển"}';

CREATE TABLE ${flyway:defaultSchema}.holiday_calendar (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    calendar_date date NOT NULL,
    day_kind varchar(8) NOT NULL,
    name varchar(100) NOT NULL,
    scope varchar(8) NOT NULL,
    CONSTRAINT pk_holiday_calendar PRIMARY KEY (id),
    CONSTRAINT uq_holiday_calendar__date UNIQUE (tenant_id, calendar_date),
    CONSTRAINT ck_holiday_calendar__day_kind CHECK (day_kind IN ('NGHI_LE', 'NGHI_BU', 'LAM_BU')),
    CONSTRAINT ck_holiday_calendar__scope CHECK (scope IN ('QUOC_GIA', 'TENANT'))
);
COMMENT ON TABLE ${flyway:defaultSchema}.holiday_calendar IS 'Lịch ngày nghỉ, ngày làm bù để tính ngày làm việc';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.calendar_date IS '{"cls": "C0", "desc": "Ngày"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.day_kind IS '{"cls": "C0", "desc": "Loại ngày"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.name IS '{"cls": "C0", "desc": "Tên ngày lễ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.holiday_calendar.scope IS '{"cls": "C0", "desc": "Lịch quốc gia hoặc lịch riêng của tenant"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.tenant_setting');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_status_log');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.holiday_calendar');
