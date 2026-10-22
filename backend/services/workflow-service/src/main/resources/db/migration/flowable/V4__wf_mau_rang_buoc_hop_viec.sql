-- =============================================================================
-- V4__wf_mau_rang_buoc_hop_viec.sql
-- DV-06-02 · SVC-06 · TL-03 "Schema flowable và saga (SVC-06)" (từ điển dữ liệu wf_template, wf_binding,
-- wf_task_projection) · TL-01 SVC-06 (hộp việc đọc bảng phẳng) · TL-03 "Bảng outbox_event", "Bảng
-- inbox_processed và inbox_aggregate_version", "Bảng idempotency_key" (NEN-032).
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
--
-- Ghi chú lệch nguồn (ghi ở wiki trang 14, mục luồng B6):
--   * Tên cột chiếu theo TL-03 (assignee_id, candidate_role) thay cho TL-01 (assignee, candidate_key).
--   * wf_task_projection bổ sung row_version (REV_ của ACT_RU_TASK, trả trong rowVersion và nhận lại ở
--     If-Match) và template_code (mã mẫu, trường templateCode của hợp đồng) để hộp việc không phải đọc ACT_*.
--   * wf_task_batch là bảng bổ sung lưu tác vụ duyệt hàng loạt (202 + Location) và kết quả từng mục.
--   * Schema flowable không thuộc DatabaseLayout của vt-db-migration (CSDL riêng của engine), nên bảng kỹ
--     thuật outbox, inbox, idempotency viết trực tiếp ở đây theo đúng DDL của NEN-032; phân vùng dùng phân
--     vùng khai báo gốc kèm hàm bảo trì thay cho pg_partman (ảnh postgres:17 không có pg_partman).
-- =============================================================================

SET search_path TO flowable;

-- -----------------------------------------------------------------------------
-- Quyền của tài khoản ứng dụng trên bảng engine (Flowable cần DML trên ACT_*, FLW_*).
-- Bảng ACT_* không bật RLS: engine và async executor làm việc xuyên tenant theo cột TENANT_ID_.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables
            WHERE schemaname = 'flowable'
              AND (tablename LIKE 'act\_%' OR tablename LIKE 'flw\_%')
  LOOP
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON flowable.%I TO svc_flowable', r.tablename);
    EXECUTE format('GRANT SELECT ON flowable.%I TO ro_flowable', r.tablename);
  END LOOP;
END $$;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA flowable TO svc_flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_template: mẫu BPMN theo phiên bản (TL-03). Mẫu dùng chung do nền tảng soạn (BS-01) nên mọi
-- tenant đọc được (dữ liệu C0); chỉ ghi được dòng của tenant đang đặt trong app.tenant_id.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_template (
  id                     uuid         NOT NULL,
  tenant_id              uuid         NOT NULL,
  template_code          varchar(64)  NOT NULL,
  version_no             int          NOT NULL,
  bpmn_xml               text         NOT NULL,
  bpmn_sha256            bytea        NOT NULL,
  flowable_deployment_id varchar(64),
  policy_pack_version_id uuid,
  status                 varchar(12)  NOT NULL,
  created_at             timestamptz  NOT NULL DEFAULT now(),
  created_by             uuid         NOT NULL,
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  updated_by             uuid         NOT NULL,
  row_version            int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_wf_template PRIMARY KEY (id),
  CONSTRAINT uq_wf_template__code_version UNIQUE (tenant_id, template_code, version_no),
  CONSTRAINT ck_wf_template__status CHECK (status IN ('NHAP', 'HIEU_LUC', 'NGUNG')),
  CONSTRAINT ck_wf_template__version_no CHECK (version_no >= 1),
  CONSTRAINT ck_wf_template__sha256 CHECK (octet_length(bpmn_sha256) = 32)
);
COMMENT ON TABLE flowable.wf_template IS 'Mẫu quy trình BPMN theo phiên bản, gắn Policy Pack (TL-03 schema flowable)';
COMMENT ON COLUMN flowable.wf_template.id IS '{"cls":"C1","desc":"Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN flowable.wf_template.tenant_id IS '{"cls":"C1","desc":"Tenant sở hữu; mẫu dùng chung thuộc tenant nền tảng"}';
COMMENT ON COLUMN flowable.wf_template.template_code IS '{"cls":"C0","desc":"Mã mẫu, ví dụ duyet-okr, duyet-ke-hoach-quy"}';
COMMENT ON COLUMN flowable.wf_template.version_no IS '{"cls":"C0","desc":"Phiên bản"}';
COMMENT ON COLUMN flowable.wf_template.bpmn_xml IS '{"cls":"C0","desc":"Định nghĩa BPMN 2.0"}';
COMMENT ON COLUMN flowable.wf_template.bpmn_sha256 IS '{"cls":"C0","desc":"Băm SHA-256 nội dung BPMN"}';
COMMENT ON COLUMN flowable.wf_template.flowable_deployment_id IS '{"cls":"C0","desc":"Mã triển khai trong Flowable"}';
COMMENT ON COLUMN flowable.wf_template.policy_pack_version_id IS '{"cls":"C1","desc":"Gói quy tắc tương ứng"}';
COMMENT ON COLUMN flowable.wf_template.status IS '{"cls":"C0","desc":"Trạng thái"}';
COMMENT ON COLUMN flowable.wf_template.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_template.created_by IS '{"cls":"C1","desc":"Người hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN flowable.wf_template.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template.updated_by IS '{"cls":"C1","desc":"Người cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template.row_version IS '{"cls":"C1","desc":"Khoá lạc quan; ETag của API"}';
ALTER TABLE flowable.wf_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE flowable.wf_template FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_wf_template__read ON flowable.wf_template AS PERMISSIVE FOR SELECT
  TO svc_flowable, ro_flowable USING (true);
CREATE POLICY rls_wf_template__tenant ON flowable.wf_template AS PERMISSIVE FOR ALL TO svc_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON flowable.wf_template FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON flowable.wf_template TO svc_flowable;
GRANT SELECT ON flowable.wf_template TO ro_flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_binding: gắn mẫu với tenant, khối đối tượng, luồng, loại kỳ kèm tham số (TL-03).
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_binding (
  id               uuid         NOT NULL,
  tenant_id        uuid         NOT NULL,
  process_kind     varchar(32)  NOT NULL,
  subject_group_id uuid,
  flow             varchar(12),
  period_type      varchar(8),
  wf_template_id   uuid         NOT NULL,
  scope_json       jsonb,
  params_json      jsonb,
  created_at       timestamptz  NOT NULL DEFAULT now(),
  created_by       uuid         NOT NULL,
  updated_at       timestamptz  NOT NULL DEFAULT now(),
  updated_by       uuid         NOT NULL,
  row_version      int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_wf_binding PRIMARY KEY (id),
  CONSTRAINT uq_wf_binding__scope UNIQUE NULLS NOT DISTINCT
    (tenant_id, process_kind, subject_group_id, flow, period_type),
  CONSTRAINT fk_wf_binding__wf_template FOREIGN KEY (wf_template_id) REFERENCES flowable.wf_template (id)
);
COMMENT ON TABLE flowable.wf_binding IS 'Gắn mẫu luồng với khối đối tượng, luồng, loại kỳ của tenant (TL-03 schema flowable)';
COMMENT ON COLUMN flowable.wf_binding.id IS '{"cls":"C1","desc":"Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN flowable.wf_binding.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_binding.process_kind IS '{"cls":"C0","desc":"Loại nghiệp vụ"}';
COMMENT ON COLUMN flowable.wf_binding.subject_group_id IS '{"cls":"C1","desc":"Khối đối tượng"}';
COMMENT ON COLUMN flowable.wf_binding.flow IS '{"cls":"C1","desc":"Luồng Đảng hoặc Nhà nước"}';
COMMENT ON COLUMN flowable.wf_binding.period_type IS '{"cls":"C1","desc":"Loại kỳ"}';
COMMENT ON COLUMN flowable.wf_binding.wf_template_id IS '{"cls":"C1","desc":"Mẫu áp dụng"}';
COMMENT ON COLUMN flowable.wf_binding.scope_json IS '{"cls":"C1","desc":"Phạm vi áp dụng mở rộng (TL-02b)"}';
COMMENT ON COLUMN flowable.wf_binding.params_json IS '{"cls":"C1","desc":"Tham số truyền vào tiến trình"}';
COMMENT ON COLUMN flowable.wf_binding.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_binding.created_by IS '{"cls":"C1","desc":"Người hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN flowable.wf_binding.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_binding.updated_by IS '{"cls":"C1","desc":"Người cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_binding.row_version IS '{"cls":"C1","desc":"Khoá lạc quan; ETag của API"}';
CREATE INDEX ix_wf_binding__wf_template ON flowable.wf_binding (tenant_id, wf_template_id);
ALTER TABLE flowable.wf_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE flowable.wf_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_wf_binding__tenant ON flowable.wf_binding AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON flowable.wf_binding FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON flowable.wf_binding TO svc_flowable;
GRANT SELECT ON flowable.wf_binding TO ro_flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_task_projection: bản chiếu phẳng của user task đang chờ (hộp việc), cập nhật trong cùng giao
-- dịch với ACT_RU_TASK bởi FlowableEventListener (TL-03, TL-01 SVC-06). Tiêu đề chỉ mang dữ liệu C0/C1.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_task_projection (
  task_id           varchar(64)  NOT NULL,
  tenant_id         uuid         NOT NULL,
  assignee_id       uuid,
  candidate_role    varchar(32),
  candidate_unit_id uuid,
  business_type     varchar(32)  NOT NULL,
  business_id       uuid         NOT NULL,
  template_code     varchar(64),
  title             varchar(255) NOT NULL,
  period_id         uuid,
  due_at            timestamptz,
  created_at        timestamptz  NOT NULL,
  row_version       int          NOT NULL DEFAULT 1,
  CONSTRAINT pk_wf_task_projection PRIMARY KEY (task_id)
);
COMMENT ON TABLE flowable.wf_task_projection IS 'Bản chiếu phi chuẩn hoá của user task đang chờ, phục vụ hộp việc (TL-03 schema flowable)';
COMMENT ON COLUMN flowable.wf_task_projection.task_id IS '{"cls":"C1","desc":"Mã ACT_RU_TASK.ID_"}';
COMMENT ON COLUMN flowable.wf_task_projection.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_task_projection.assignee_id IS '{"cls":"C1","desc":"Người được giao"}';
COMMENT ON COLUMN flowable.wf_task_projection.candidate_role IS '{"cls":"C1","desc":"Vai trò ứng viên"}';
COMMENT ON COLUMN flowable.wf_task_projection.candidate_unit_id IS '{"cls":"C1","desc":"Đơn vị ứng viên"}';
COMMENT ON COLUMN flowable.wf_task_projection.business_type IS '{"cls":"C1","desc":"Loại đối tượng nghiệp vụ"}';
COMMENT ON COLUMN flowable.wf_task_projection.business_id IS '{"cls":"C1","desc":"Mã đối tượng nghiệp vụ"}';
COMMENT ON COLUMN flowable.wf_task_projection.template_code IS '{"cls":"C0","desc":"Mã mẫu luồng (bổ sung so với TL-03, trường templateCode của hợp đồng)"}';
COMMENT ON COLUMN flowable.wf_task_projection.title IS '{"cls":"C1","desc":"Tiêu đề hiển thị, chỉ dữ liệu C0/C1"}';
COMMENT ON COLUMN flowable.wf_task_projection.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN flowable.wf_task_projection.due_at IS '{"cls":"C1","desc":"Hạn"}';
COMMENT ON COLUMN flowable.wf_task_projection.created_at IS '{"cls":"C1","desc":"Thời điểm tạo user task"}';
COMMENT ON COLUMN flowable.wf_task_projection.row_version IS '{"cls":"C1","desc":"REV_ của ACT_RU_TASK (bổ sung so với TL-03), trả trong rowVersion và nhận ở If-Match"}';
CREATE INDEX ix_wf_task_projection__assignee ON flowable.wf_task_projection (tenant_id, assignee_id, due_at);
CREATE INDEX ix_wf_task_projection__candidate
  ON flowable.wf_task_projection (tenant_id, candidate_role, candidate_unit_id, due_at);
ALTER TABLE flowable.wf_task_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE flowable.wf_task_projection FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_wf_task_projection__tenant ON flowable.wf_task_projection AS PERMISSIVE FOR ALL
  TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON flowable.wf_task_projection FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON flowable.wf_task_projection TO svc_flowable;
GRANT SELECT ON flowable.wf_task_projection TO ro_flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_task_batch (bảng bổ sung): tác vụ duyệt hàng loạt trả 202 + Location /api/v1/jobs/{id} và
-- kết quả từng mục (BatchItemResult) của batchCompleteWorkflowTasks.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_task_batch (
  id           uuid         NOT NULL,
  tenant_id    uuid         NOT NULL,
  decision     varchar(8)   NOT NULL,
  status       varchar(12)  NOT NULL,
  total_items  int          NOT NULL,
  done_items   int          NOT NULL DEFAULT 0,
  failed_items int          NOT NULL DEFAULT 0,
  results_json jsonb        NOT NULL DEFAULT '[]'::jsonb,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  created_by   uuid         NOT NULL,
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  updated_by   uuid         NOT NULL,
  row_version  int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_wf_task_batch PRIMARY KEY (id),
  CONSTRAINT ck_wf_task_batch__decision CHECK (decision IN ('DUYET', 'TRA_LAI')),
  CONSTRAINT ck_wf_task_batch__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI')),
  CONSTRAINT ck_wf_task_batch__items CHECK (total_items BETWEEN 1 AND 200 AND done_items + failed_items <= total_items)
);
COMMENT ON TABLE flowable.wf_task_batch IS 'Tác vụ duyệt hàng loạt (bảng bổ sung so với TL-03, DV-06-02)';
COMMENT ON COLUMN flowable.wf_task_batch.id IS '{"cls":"C1","desc":"Mã tác vụ, cũng là id của Job"}';
COMMENT ON COLUMN flowable.wf_task_batch.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_task_batch.decision IS '{"cls":"C0","desc":"Quyết định áp cho mọi mục"}';
COMMENT ON COLUMN flowable.wf_task_batch.status IS '{"cls":"C0","desc":"Trạng thái theo JobStatus"}';
COMMENT ON COLUMN flowable.wf_task_batch.total_items IS '{"cls":"C0","desc":"Số mục"}';
COMMENT ON COLUMN flowable.wf_task_batch.done_items IS '{"cls":"C0","desc":"Số mục đã hoàn thành"}';
COMMENT ON COLUMN flowable.wf_task_batch.failed_items IS '{"cls":"C0","desc":"Số mục lỗi"}';
COMMENT ON COLUMN flowable.wf_task_batch.results_json IS '{"cls":"C1","desc":"Kết quả từng mục dạng BatchItemResult, không chứa ý kiến"}';
COMMENT ON COLUMN flowable.wf_task_batch.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_task_batch.created_by IS '{"cls":"C1","desc":"Người khởi tạo, chỉ người này xem được Job"}';
COMMENT ON COLUMN flowable.wf_task_batch.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_task_batch.updated_by IS '{"cls":"C1","desc":"Người cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_task_batch.row_version IS '{"cls":"C1","desc":"Khoá lạc quan"}';
ALTER TABLE flowable.wf_task_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE flowable.wf_task_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_wf_task_batch__tenant ON flowable.wf_task_batch AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON flowable.wf_task_batch FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON flowable.wf_task_batch TO svc_flowable;
GRANT SELECT ON flowable.wf_task_batch TO ro_flowable;

-- -----------------------------------------------------------------------------
-- Bảng kỹ thuật dùng chung của schema nghiệp vụ (NEN-032): outbox_event và idempotency_key đã tạo ở
-- V3 (DV-06-05, cùng cột); ở đây thêm inbox_processed, inbox_aggregate_version theo vt-db-migration V0_21,
-- RLS cho phân vùng mặc định và hàm bảo trì phân vùng.
-- -----------------------------------------------------------------------------

CREATE TABLE flowable.inbox_processed (
  event_id        uuid         NOT NULL,
  consumer        varchar(100) NOT NULL,
  processed_at    timestamptz  NOT NULL DEFAULT now(),
  tenant_id       uuid         NOT NULL,
  source_topic    varchar(128) NOT NULL,
  kafka_partition int          NOT NULL,
  kafka_offset    bigint       NOT NULL,
  CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON flowable.inbox_processed (processed_at);
COMMENT ON COLUMN flowable.inbox_processed.event_id IS '{"cls":"C1","desc":"Mã sự kiện đã xử lý"}';
COMMENT ON COLUMN flowable.inbox_processed.consumer IS '{"cls":"C1","desc":"Tên consumer"}';
COMMENT ON COLUMN flowable.inbox_processed.processed_at IS '{"cls":"C1","desc":"Thời điểm xử lý; dọn sau 14 ngày"}';
COMMENT ON COLUMN flowable.inbox_processed.tenant_id IS '{"cls":"C1","desc":"Tenant của sự kiện"}';
COMMENT ON COLUMN flowable.inbox_processed.source_topic IS '{"cls":"C1","desc":"Topic nguồn"}';
COMMENT ON COLUMN flowable.inbox_processed.kafka_partition IS '{"cls":"C1","desc":"Phân vùng Kafka"}';
COMMENT ON COLUMN flowable.inbox_processed.kafka_offset IS '{"cls":"C1","desc":"Offset Kafka"}';

CREATE TABLE flowable.inbox_aggregate_version (
  consumer       varchar(100) NOT NULL,
  aggregate_type varchar(64)  NOT NULL,
  aggregate_id   uuid         NOT NULL,
  tenant_id      uuid         NOT NULL,
  last_version   bigint       NOT NULL,
  last_event_id  uuid         NOT NULL,
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_inbox_aggregate_version PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);
COMMENT ON COLUMN flowable.inbox_aggregate_version.consumer IS '{"cls":"C1","desc":"Tên consumer"}';
COMMENT ON COLUMN flowable.inbox_aggregate_version.aggregate_type IS '{"cls":"C1","desc":"Loại thực thể gốc"}';
COMMENT ON COLUMN flowable.inbox_aggregate_version.aggregate_id IS '{"cls":"C1","desc":"Mã thực thể gốc"}';
COMMENT ON COLUMN flowable.inbox_aggregate_version.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN flowable.inbox_aggregate_version.last_version IS '{"cls":"C1","desc":"aggregate_version cao nhất đã áp dụng"}';
COMMENT ON COLUMN flowable.inbox_aggregate_version.last_event_id IS '{"cls":"C1","desc":"Sự kiện tương ứng"}';
COMMENT ON COLUMN flowable.inbox_aggregate_version.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật"}';


-- RLS cho bảng kỹ thuật và mọi phân vùng (chính sách bảng cha không áp khi truy vấn thẳng phân vùng).
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['outbox_event_default', 'inbox_processed', 'inbox_aggregate_version',
                           'idempotency_key_default']
  LOOP
    EXECUTE format('ALTER TABLE flowable.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE flowable.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY %I ON flowable.%I AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable '
                   'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                   'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
                   'rls_' || t || '__tenant', t);
    EXECUTE format('REVOKE ALL ON flowable.%I FROM PUBLIC', t);
    EXECUTE format('GRANT SELECT ON flowable.%I TO ro_flowable', t);
  END LOOP;
END $$;
GRANT SELECT, INSERT ON flowable.inbox_processed TO svc_flowable;
GRANT SELECT, INSERT, UPDATE ON flowable.inbox_aggregate_version TO svc_flowable;

-- -----------------------------------------------------------------------------
-- Bảo trì phân vùng ngày của outbox_event (giữ 3 ngày) và idempotency_key (giữ 48 giờ, HD-02): tạo trước
-- p_premake ngày, gỡ phân vùng quá hạn. Gọi bằng CronJob hoặc lịch của ứng dụng; svc_flowable chỉ có EXECUTE.
-- -----------------------------------------------------------------------------
CREATE FUNCTION flowable.fn_maintain_partitions(p_premake int DEFAULT 7) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, flowable AS $$
DECLARE
  d     date;
  child text;
  r     record;
BEGIN
  IF p_premake < 1 OR p_premake > 31 THEN
    RAISE EXCEPTION 'p_premake ngoài khoảng 1..31: %', p_premake;
  END IF;
  FOR i IN 0 .. p_premake LOOP
    d := current_date + i;
    child := 'outbox_event_p' || to_char(d, 'YYYYMMDD');
    IF to_regclass('flowable.' || child) IS NULL THEN
      EXECUTE format('CREATE TABLE flowable.%I PARTITION OF flowable.outbox_event FOR VALUES FROM (%L) TO (%L)',
                     child, d::timestamptz, (d + 1)::timestamptz);
      PERFORM flowable.fn_protect_partition(child);
    END IF;
    child := 'idempotency_key_p' || to_char(d, 'YYYYMMDD');
    IF to_regclass('flowable.' || child) IS NULL THEN
      EXECUTE format('CREATE TABLE flowable.%I PARTITION OF flowable.idempotency_key FOR VALUES FROM (%L) TO (%L)',
                     child, d, d + 1);
      PERFORM flowable.fn_protect_partition(child);
    END IF;
  END LOOP;
  FOR r IN SELECT c.relname
             FROM pg_inherits i
             JOIN pg_class c ON c.oid = i.inhrelid
             JOIN pg_class p ON p.oid = i.inhparent
             JOIN pg_namespace n ON n.oid = p.relnamespace
            WHERE n.nspname = 'flowable'
              AND ((p.relname = 'outbox_event'
                    AND c.relname ~ '^outbox_event_p[0-9]{8}$'
                    AND to_date(substring(c.relname from '[0-9]{8}$'), 'YYYYMMDD') < current_date - 3)
                OR (p.relname = 'idempotency_key'
                    AND c.relname ~ '^idempotency_key_p[0-9]{8}$'
                    AND to_date(substring(c.relname from '[0-9]{8}$'), 'YYYYMMDD') < current_date - 2))
  LOOP
    EXECUTE format('DROP TABLE flowable.%I', r.relname);
  END LOOP;
END $$;

CREATE FUNCTION flowable.fn_protect_partition(p_child text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, flowable AS $$
BEGIN
  EXECUTE format('REVOKE ALL ON flowable.%I FROM PUBLIC', p_child);
  EXECUTE format('ALTER TABLE flowable.%I ENABLE ROW LEVEL SECURITY', p_child);
  EXECUTE format('ALTER TABLE flowable.%I FORCE ROW LEVEL SECURITY', p_child);
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'flowable' AND tablename = p_child) THEN
    EXECUTE format('CREATE POLICY %I ON flowable.%I AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable '
                   'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                   'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
                   'rls_' || p_child || '__tenant', p_child);
  END IF;
END $$;
REVOKE ALL ON FUNCTION flowable.fn_protect_partition(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION flowable.fn_maintain_partitions(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION flowable.fn_maintain_partitions(int) TO svc_flowable;
SELECT flowable.fn_maintain_partitions(7);
