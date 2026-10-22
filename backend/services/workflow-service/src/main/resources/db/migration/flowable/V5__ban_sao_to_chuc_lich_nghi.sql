-- =============================================================================
-- V5__ban_sao_to_chuc_lich_nghi.sql
-- DV-06-03 · SVC-06 · TL-01 G2 "Ví dụ DMN chọn cấp duyệt" (phân giải scope bằng dữ liệu org_replica của
-- workflow-service, đổi hạn ngày làm việc theo lịch nghỉ của tenant) · TL-03 "Schema org_replica",
-- "Bảng config.holiday_calendar".
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
--
-- Ghi chú lệch nguồn (ghi ở wiki trang 14, mục luồng B6 DV-06-03):
--   * TL-03 đặt org_replica trong CSDL dữ liệu tenant của mission-core; TL-01 G2 ghi workflow-service phân
--     giải bằng "dữ liệu org_replica của workflow-service". CSDL engine là CSDL riêng (TL-03 "Database per
--     Service"), nên workflow-service giữ bản sao riêng: schema org_replica chỉ gồm org_unit_v với đúng cột
--     TL-03. Consumer EVT-14 (DonViDaThayDoi) cập nhật bảng này thuộc task sau (chưa làm ở DV-06-03).
--   * flowable.wf_holiday_calendar là bảng bổ sung, bản sao rút gọn của config.holiday_calendar (MOD-08).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS org_replica;
REVOKE ALL ON SCHEMA org_replica FROM PUBLIC;
GRANT USAGE ON SCHEMA org_replica TO svc_flowable, ro_flowable;

-- -----------------------------------------------------------------------------
-- org_replica.org_unit_v: bản sao đơn vị theo trục hiệu lực (TL-03 "Bảng org_replica.org_unit_v").
-- -----------------------------------------------------------------------------
CREATE TABLE org_replica.org_unit_v (
  tenant_id        uuid         NOT NULL,
  id               uuid         NOT NULL,
  valid_from       date         NOT NULL,
  valid_to         date         NOT NULL DEFAULT 'infinity',
  code             varchar(64)  NOT NULL,
  name             varchar(255) NOT NULL,
  unit_type        varchar(24)  NOT NULL,
  party_parent_id  uuid,
  admin_parent_id  uuid,
  subject_group_id uuid,
  source_version   bigint       NOT NULL,
  source_event_id  uuid         NOT NULL,
  replicated_at    timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_org_unit_v PRIMARY KEY (tenant_id, id, valid_from),
  CONSTRAINT ck_org_unit_v__valid CHECK (valid_from < valid_to)
);
COMMENT ON TABLE org_replica.org_unit_v IS 'Bản sao đơn vị của workflow-service để phân giải cấp duyệt (TL-03 schema org_replica)';
COMMENT ON COLUMN org_replica.org_unit_v.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org_replica.org_unit_v.id IS '{"cls":"C1","desc":"Mã đơn vị"}';
COMMENT ON COLUMN org_replica.org_unit_v.valid_from IS '{"cls":"C1","desc":"Bắt đầu hiệu lực"}';
COMMENT ON COLUMN org_replica.org_unit_v.valid_to IS '{"cls":"C1","desc":"Hết hiệu lực (không gồm ngày này)"}';
COMMENT ON COLUMN org_replica.org_unit_v.code IS '{"cls":"C0","desc":"Mã đơn vị"}';
COMMENT ON COLUMN org_replica.org_unit_v.name IS '{"cls":"C0","desc":"Tên đơn vị"}';
COMMENT ON COLUMN org_replica.org_unit_v.unit_type IS '{"cls":"C0","desc":"Loại đơn vị"}';
COMMENT ON COLUMN org_replica.org_unit_v.party_parent_id IS '{"cls":"C1","desc":"Cấp trên trực thuộc trên cây Đảng"}';
COMMENT ON COLUMN org_replica.org_unit_v.admin_parent_id IS '{"cls":"C1","desc":"Cấp trên trực thuộc trên cây chuyên môn"}';
COMMENT ON COLUMN org_replica.org_unit_v.subject_group_id IS '{"cls":"C1","desc":"Khối đối tượng"}';
COMMENT ON COLUMN org_replica.org_unit_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN org_replica.org_unit_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN org_replica.org_unit_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
CREATE INDEX ix_org_unit_v__valid ON org_replica.org_unit_v (tenant_id, id, valid_to);
ALTER TABLE org_replica.org_unit_v ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_replica.org_unit_v FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_org_unit_v__tenant ON org_replica.org_unit_v AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON org_replica.org_unit_v FROM PUBLIC;
GRANT SELECT ON org_replica.org_unit_v TO svc_flowable, ro_flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_holiday_calendar (bảng bổ sung): lịch nghỉ của tenant, bản sao của config.holiday_calendar.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_holiday_calendar (
  tenant_id     uuid        NOT NULL,
  calendar_date date        NOT NULL,
  day_kind      varchar(8)  NOT NULL,
  replicated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_wf_holiday_calendar PRIMARY KEY (tenant_id, calendar_date),
  CONSTRAINT ck_wf_holiday_calendar__day_kind CHECK (day_kind IN ('NGHI_LE', 'NGHI_BU', 'LAM_BU'))
);
COMMENT ON TABLE flowable.wf_holiday_calendar IS 'Bản sao lịch nghỉ của tenant để đổi hạn ngày làm việc (bổ sung, nguồn config.holiday_calendar)';
COMMENT ON COLUMN flowable.wf_holiday_calendar.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_holiday_calendar.calendar_date IS '{"cls":"C0","desc":"Ngày"}';
COMMENT ON COLUMN flowable.wf_holiday_calendar.day_kind IS '{"cls":"C0","desc":"Loại ngày: nghỉ lễ, nghỉ bù, làm bù"}';
COMMENT ON COLUMN flowable.wf_holiday_calendar.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
ALTER TABLE flowable.wf_holiday_calendar ENABLE ROW LEVEL SECURITY;
ALTER TABLE flowable.wf_holiday_calendar FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_wf_holiday_calendar__tenant ON flowable.wf_holiday_calendar AS PERMISSIVE FOR ALL
  TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON flowable.wf_holiday_calendar FROM PUBLIC;
GRANT SELECT ON flowable.wf_holiday_calendar TO svc_flowable, ro_flowable;
