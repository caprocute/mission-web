-- DV-04-07 · QT-01 bước 7, 8 · SCR-0104: bổ sung cho đồng bộ và đối soát nhân sự (bảng và cột bổ sung, suy luận;
-- TL-03 chỉ đặc tả org.sync_discrepancy và org.scim_sync_log). Lần chạy và lịch đồng bộ phía org-service được lưu ở
-- org.sync_run, org.sync_schedule vì org-service không đọc trực tiếp schema integ (TL-03 "Không truy cập chéo").

ALTER TABLE org.sync_discrepancy
  ADD COLUMN external_id        varchar(64),
  ADD COLUMN effective_from     date,
  ADD COLUMN matched_by         varchar(32),
  ADD COLUMN assignee_id        uuid,
  ADD COLUMN note               varchar(1000),
  ADD COLUMN source_fingerprint bytea;
COMMENT ON COLUMN org.sync_discrepancy.external_id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi ở nguồn (integ.mapping_xref.external_id)"}';
COMMENT ON COLUMN org.sync_discrepancy.effective_from IS '{"cls": "C1", "pii": false, "desc": "Ngày hiệu lực theo quyết định nguồn, dùng làm valid_from khi lấy theo nguồn"}';
COMMENT ON COLUMN org.sync_discrepancy.matched_by IS '{"cls": "C1", "pii": false, "desc": "Tiêu chí khớp người: CCCD_NGAY_SINH, ANH_XA, HO_TEN, KHONG_KHOP"}';
COMMENT ON COLUMN org.sync_discrepancy.assignee_id IS '{"cls": "C1", "pii": false, "desc": "Cán bộ được giao kiểm tra (org.person.id)"}';
COMMENT ON COLUMN org.sync_discrepancy.note IS '{"cls": "C1", "pii": false, "desc": "Ghi chú khi giao xử lý hoặc khi giải quyết"}';
COMMENT ON COLUMN org.sync_discrepancy.source_fingerprint IS '{"cls": "C1", "pii": false, "desc": "HMAC của giá trị nguồn để biết nguồn đã đổi khi đang giữ dữ liệu nội bộ; không đảo ngược được"}';
CREATE INDEX ix_sync_discrepancy__open ON org.sync_discrepancy (tenant_id, severity) WHERE resolution IS NULL;
CREATE INDEX ix_sync_discrepancy__subject ON org.sync_discrepancy (tenant_id, source_connector, external_id, field);

CREATE TABLE org.sync_run (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  connector        varchar(16) NOT NULL,
  status           varchar(24) NOT NULL DEFAULT 'DANG_CHAY',
  started_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz,
  records          int NOT NULL DEFAULT 0,
  changes          int NOT NULL DEFAULT 0,
  errors           int NOT NULL DEFAULT 0,
  dead_letters     int NOT NULL DEFAULT 0,
  reason           varchar(500),
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_sync_run PRIMARY KEY (id),
  CONSTRAINT ck_sync_run__connector CHECK (connector IN ('CBCCVC','DANG_VIEN','SCIM','HRM')),
  CONSTRAINT ck_sync_run__status CHECK (status IN ('DANG_CHAY','THANH_CONG','THANH_CONG_MOT_PHAN','LOI'))
);
COMMENT ON TABLE org.sync_run IS 'Lần đồng bộ nhân sự theo nguồn (bảng bổ sung DV-04-07, SCR-0104 trường 2)';
COMMENT ON COLUMN org.sync_run.id IS '{"cls": "C1", "pii": false, "desc": "Mã lần chạy (UUIDv7), trùng mã lần chạy ở integration-gateway"}';
COMMENT ON COLUMN org.sync_run.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.sync_run.connector IS '{"cls": "C1", "pii": false, "desc": "Nguồn đồng bộ (integ.connector.code)"}';
COMMENT ON COLUMN org.sync_run.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái lần chạy"}';
COMMENT ON COLUMN org.sync_run.started_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm bắt đầu"}';
COMMENT ON COLUMN org.sync_run.finished_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm kết thúc"}';
COMMENT ON COLUMN org.sync_run.records IS '{"cls": "C1", "pii": false, "desc": "Số bản ghi nguồn đã nhận"}';
COMMENT ON COLUMN org.sync_run.changes IS '{"cls": "C1", "pii": false, "desc": "Số bản ghi tạo phiên bản mới"}';
COMMENT ON COLUMN org.sync_run.errors IS '{"cls": "C1", "pii": false, "desc": "Số bản ghi lỗi"}';
COMMENT ON COLUMN org.sync_run.dead_letters IS '{"cls": "C1", "pii": false, "desc": "Số thông điệp đang nằm trong integ.dead_letter"}';
COMMENT ON COLUMN org.sync_run.reason IS '{"cls": "C1", "pii": false, "desc": "Lý do lỗi tiếng Việt cho MSG-01-14, không chứa dữ liệu C2"}';
COMMENT ON COLUMN org.sync_run.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.sync_run.created_by IS '{"cls": "C1", "pii": false, "desc": "Người hoặc tài khoản dịch vụ tạo lần chạy"}';
COMMENT ON COLUMN org.sync_run.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.sync_run.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.sync_run.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan"}';
CREATE UNIQUE INDEX uq_sync_run__running ON org.sync_run (tenant_id, connector) WHERE status = 'DANG_CHAY';
CREATE INDEX ix_sync_run__started ON org.sync_run (tenant_id, started_at DESC, id DESC);
ALTER TABLE org.sync_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.sync_run FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_sync_run__tenant ON org.sync_run AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.sync_run FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.sync_run TO svc_org;
GRANT SELECT ON org.sync_run TO ro_org;

CREATE TABLE org.sync_schedule (
  tenant_id        uuid NOT NULL,
  connector        varchar(16) NOT NULL,
  enabled          boolean NOT NULL DEFAULT true,
  cron             varchar(64) NOT NULL,
  set_version      int NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_sync_schedule PRIMARY KEY (tenant_id, connector),
  CONSTRAINT ck_sync_schedule__connector CHECK (connector IN ('CBCCVC','DANG_VIEN','SCIM','HRM'))
);
COMMENT ON TABLE org.sync_schedule IS 'Lịch đồng bộ nhân sự theo nguồn (bảng bổ sung DV-04-07, SCR-0104 "Lịch đồng bộ")';
COMMENT ON COLUMN org.sync_schedule.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.sync_schedule.connector IS '{"cls": "C1", "pii": false, "desc": "Nguồn đồng bộ"}';
COMMENT ON COLUMN org.sync_schedule.enabled IS '{"cls": "C1", "pii": false, "desc": "Lịch đang bật"}';
COMMENT ON COLUMN org.sync_schedule.cron IS '{"cls": "C1", "pii": false, "desc": "Biểu thức cron năm trường"}';
COMMENT ON COLUMN org.sync_schedule.set_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản của cả bộ lịch của tenant; ETag của API"}';
COMMENT ON COLUMN org.sync_schedule.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.sync_schedule.created_by IS '{"cls": "C1", "pii": false, "desc": "Người tạo"}';
COMMENT ON COLUMN org.sync_schedule.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.sync_schedule.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.sync_schedule.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan của dòng"}';
ALTER TABLE org.sync_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.sync_schedule FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_sync_schedule__tenant ON org.sync_schedule AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.sync_schedule FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.sync_schedule TO svc_org;
GRANT SELECT ON org.sync_schedule TO ro_org;

-- Bộ so khớp tra trùng họ tên (tình huống "hai hồ sơ trùng tên khác ngày sinh") trên phiên bản hiện hành.
CREATE INDEX ix_person__full_name_current_p ON org.person (tenant_id, full_name) WHERE recorded_to = 'infinity';
