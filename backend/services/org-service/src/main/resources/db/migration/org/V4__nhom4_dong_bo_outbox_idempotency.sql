-- Sinh từ đặc tả cột TL-03 "Schema org (SVC-04 org-service)" (dòng 1046–1328) và chương "Dữ liệu tổ chức
-- hai trục thời gian" (dòng 4020–4086). Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
-- Vai trò mig_org, svc_org, ro_org, cdc_org và schema org do bước khởi tạo cụm tạo (TL-03 "Không truy cập chéo và
-- quyền tối thiểu"); migration chạy bằng mig_org.

-- Nhóm 4 (T-DB-O4): đồng bộ SCIM, đối soát, outbox, inbox, idempotency.
-- pg_partman không có trong ảnh postgres:17 chuẩn và không cài được trong môi trường kiểm thử; dùng phân vùng
-- khai báo gốc của PostgreSQL với hàm bảo trì SECURITY DEFINER thay cho partman.create_parent/run_maintenance
-- (điểm chờ: chuyển sang pg_partman khi ảnh CloudNativePG có tiện ích, giữ nguyên tên và lịch giữ dữ liệu).
-- sync_discrepancy.sync_run_id không có khoá ngoại vật lý tới scim_sync_log vì khoá chính bảng phân vùng phải chứa
-- received_at (suy luận, ghi vào wiki trang 14); giữ tham chiếu logic có chỉ mục.

CREATE TABLE org.scim_sync_log (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  source_system    varchar(32) NOT NULL,
  operation        varchar(8) NOT NULL,
  resource_type    varchar(8) NOT NULL,
  external_id      varchar(128) NOT NULL,
  internal_id      uuid,
  request_hash     bytea NOT NULL,
  status           varchar(12) NOT NULL,
  error_detail     text,
  received_at      timestamptz NOT NULL DEFAULT now(),
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  -- Khoá phân vùng phải nằm trong khoá chính (PostgreSQL)
  CONSTRAINT pk_scim_sync_log PRIMARY KEY (id, received_at),
  CONSTRAINT ck_scim_sync_log__operation CHECK (operation IN ('CREATE','REPLACE','PATCH','DELETE')),
  CONSTRAINT ck_scim_sync_log__resource_type CHECK (resource_type IN ('User','Group')),
  CONSTRAINT ck_scim_sync_log__status CHECK (status IN ('THANH_CONG','LOI','BO_QUA'))
) PARTITION BY RANGE (received_at);
COMMENT ON TABLE org.scim_sync_log IS 'Nhật ký đồng bộ SCIM, phân vùng tháng, giữ 13 tháng';
COMMENT ON COLUMN org.scim_sync_log.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.scim_sync_log.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.scim_sync_log.source_system IS '{"cls": "C1", "pii": false, "desc": "Nguồn, ví dụ CSDL_CBCCVC, HRM_TCT, IDP_KH"}';
COMMENT ON COLUMN org.scim_sync_log.operation IS '{"cls": "C1", "pii": false, "desc": "Thao tác SCIM 2.0 (RFC 7644)"}';
COMMENT ON COLUMN org.scim_sync_log.resource_type IS '{"cls": "C1", "pii": false, "desc": "Loại tài nguyên SCIM"}';
COMMENT ON COLUMN org.scim_sync_log.external_id IS '{"cls": "C1", "pii": false, "desc": "externalId của nguồn"}';
COMMENT ON COLUMN org.scim_sync_log.internal_id IS '{"cls": "C1", "pii": false, "desc": "Mã nội bộ sau ánh xạ"}';
COMMENT ON COLUMN org.scim_sync_log.request_hash IS '{"cls": "C1", "pii": false, "desc": "SHA-256 nội dung yêu cầu, chống xử lý trùng"}';
COMMENT ON COLUMN org.scim_sync_log.status IS '{"cls": "C1", "pii": false, "desc": "Kết quả"}';
COMMENT ON COLUMN org.scim_sync_log.error_detail IS '{"cls": "C1", "pii": false, "desc": "Mô tả lỗi theo SCIM error schema"}';
COMMENT ON COLUMN org.scim_sync_log.received_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm nhận; khoá phân vùng RANGE theo tháng"}';
COMMENT ON COLUMN org.scim_sync_log.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.scim_sync_log.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.scim_sync_log.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.scim_sync_log.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.scim_sync_log.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_scim_sync_log__external ON org.scim_sync_log (tenant_id, source_system, external_id);
CREATE INDEX ix_scim_sync_log__id ON org.scim_sync_log (tenant_id, id);
ALTER TABLE org.scim_sync_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.scim_sync_log FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_scim_sync_log__tenant ON org.scim_sync_log AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.scim_sync_log FROM PUBLIC;
GRANT SELECT, INSERT ON org.scim_sync_log TO svc_org;
GRANT SELECT ON org.scim_sync_log TO ro_org;

CREATE TABLE org.sync_discrepancy (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  person_id        uuid,
  field            varchar(64) NOT NULL,
  source_connector varchar(32) NOT NULL,
  source_value_enc bytea,
  current_value_enc bytea,
  dek_id           uuid,
  severity         varchar(8) NOT NULL,
  suggested_action varchar(32) NOT NULL,
  resolution       varchar(16),
  resolved_by      uuid,
  resolved_at      timestamptz,
  sync_run_id      uuid NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_sync_discrepancy PRIMARY KEY (id),
  CONSTRAINT ck_sync_discrepancy__severity CHECK (severity IN ('CAO','VUA','THAP')),
  CONSTRAINT ck_sync_discrepancy__resolution CHECK (resolution IN ('NHAN_NGUON','GIU_HIEN_TAI','SUA_TAY','BO_QUA')),
  CONSTRAINT ck_sync_discrepancy__dek CHECK (dek_id IS NOT NULL OR (source_value_enc IS NULL AND current_value_enc IS NULL))
);
COMMENT ON TABLE org.sync_discrepancy IS 'Lệch dữ liệu nhân sự giữa nguồn ngoài và nền tảng (bảng bổ sung, SCR-0104)';
COMMENT ON COLUMN org.sync_discrepancy.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.sync_discrepancy.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.sync_discrepancy.person_id IS '{"cls": "C1", "pii": false, "desc": "Nhân sự liên quan"}';
COMMENT ON COLUMN org.sync_discrepancy.field IS '{"cls": "C1", "pii": false, "desc": "Trường lệch, ví dụ org_unit_id, job_title_id"}';
COMMENT ON COLUMN org.sync_discrepancy.source_connector IS '{"cls": "C1", "pii": false, "desc": "Nguồn ngoài"}';
COMMENT ON COLUMN org.sync_discrepancy.source_value_enc IS '{"cls": "C2", "pii": true, "desc": "Giá trị phía nguồn, mã hoá trường"}';
COMMENT ON COLUMN org.sync_discrepancy.current_value_enc IS '{"cls": "C2", "pii": true, "desc": "Giá trị hiện tại, mã hoá trường"}';
COMMENT ON COLUMN org.sync_discrepancy.dek_id IS '{"cls": "C1", "pii": false, "desc": "Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN org.sync_discrepancy.severity IS '{"cls": "C1", "pii": false, "desc": "Mức nghiêm trọng"}';
COMMENT ON COLUMN org.sync_discrepancy.suggested_action IS '{"cls": "C1", "pii": false, "desc": "Đề xuất xử lý"}';
COMMENT ON COLUMN org.sync_discrepancy.resolution IS '{"cls": "C1", "pii": false, "desc": "Cách xử lý"}';
COMMENT ON COLUMN org.sync_discrepancy.resolved_by IS '{"cls": "C1", "pii": false, "desc": "Người xử lý"}';
COMMENT ON COLUMN org.sync_discrepancy.resolved_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm xử lý"}';
COMMENT ON COLUMN org.sync_discrepancy.sync_run_id IS '{"cls": "C1", "pii": false, "desc": "Lần đồng bộ phát hiện (tham chiếu logic org.scim_sync_log.id, không có khoá ngoại vật lý vì bảng đích phân vùng)"}';
COMMENT ON COLUMN org.sync_discrepancy.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.sync_discrepancy.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.sync_discrepancy.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.sync_discrepancy.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.sync_discrepancy.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_sync_discrepancy__person ON org.sync_discrepancy (tenant_id, person_id);
CREATE INDEX ix_sync_discrepancy__sync_run ON org.sync_discrepancy (tenant_id, sync_run_id);
ALTER TABLE org.sync_discrepancy ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.sync_discrepancy FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_sync_discrepancy__tenant ON org.sync_discrepancy AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.sync_discrepancy FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.sync_discrepancy TO svc_org;
GRANT SELECT ON org.sync_discrepancy TO ro_org;

CREATE TABLE org.outbox_event (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  aggregate_type   varchar(64) NOT NULL,
  aggregate_id     uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type       varchar(96) NOT NULL,
  topic            varchar(128) NOT NULL,
  event_key        varchar(80) NOT NULL,
  payload          bytea NOT NULL,
  schema_version   int NOT NULL,
  headers          jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_outbox_event PRIMARY KEY (id, created_at),
  CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$')
) PARTITION BY RANGE (created_at);
COMMENT ON TABLE org.outbox_event IS 'Hộp thư đi giao dịch (Transactional Outbox), phân vùng ngày, giữ 3 ngày';
COMMENT ON COLUMN org.outbox_event.id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện (eventId, UUIDv7)"}';
COMMENT ON COLUMN org.outbox_event.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant phát sự kiện"}';
COMMENT ON COLUMN org.outbox_event.aggregate_type IS '{"cls": "C1", "pii": false, "desc": "Loại thực thể gốc, ví dụ OrgUnit, Assignment"}';
COMMENT ON COLUMN org.outbox_event.aggregate_id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể gốc"}';
COMMENT ON COLUMN org.outbox_event.aggregate_version IS '{"cls": "C1", "pii": false, "desc": "row_version của thực thể sau thay đổi; chống sai thứ tự ở phía nhận"}';
COMMENT ON COLUMN org.outbox_event.event_type IS '{"cls": "C1", "pii": false, "desc": "Tên nghiệp vụ, ví dụ DonViDaThayDoi"}';
COMMENT ON COLUMN org.outbox_event.topic IS '{"cls": "C1", "pii": false, "desc": "Topic đích; Debezium định tuyến theo cột này"}';
COMMENT ON COLUMN org.outbox_event.event_key IS '{"cls": "C1", "pii": false, "desc": "Khoá Kafka tenantId:aggregateId"}';
COMMENT ON COLUMN org.outbox_event.payload IS '{"cls": "C1", "pii": false, "desc": "Nội dung sự kiện đã tuần tự hoá; không mang trường C2 dạng rõ"}';
COMMENT ON COLUMN org.outbox_event.schema_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản lược đồ sự kiện trong Apicurio"}';
COMMENT ON COLUMN org.outbox_event.headers IS '{"cls": "C1", "pii": false, "desc": "traceparent, actorId, policyVersion, classification, occurredAt"}';
COMMENT ON COLUMN org.outbox_event.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi; khoá phân vùng RANGE theo ngày"}';
ALTER TABLE org.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_outbox_event__tenant ON org.outbox_event AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.outbox_event FROM PUBLIC;
GRANT INSERT ON org.outbox_event TO svc_org;
GRANT SELECT ON org.outbox_event TO cdc_org;
GRANT SELECT ON org.outbox_event TO ro_org;

CREATE TABLE org.inbox_processed (
  event_id         uuid NOT NULL,
  consumer         varchar(100) NOT NULL,
  processed_at     timestamptz NOT NULL DEFAULT now(),
  tenant_id        uuid NOT NULL,
  source_topic     varchar(128) NOT NULL,
  kafka_partition  int NOT NULL,
  kafka_offset     bigint NOT NULL,
  CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
COMMENT ON TABLE org.inbox_processed IS 'Hộp thư đến idempotent (baseline mục 8)';
COMMENT ON COLUMN org.inbox_processed.event_id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện đã xử lý"}';
COMMENT ON COLUMN org.inbox_processed.consumer IS '{"cls": "C1", "pii": false, "desc": "Tên consumer"}';
COMMENT ON COLUMN org.inbox_processed.processed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm xử lý; job dọn xoá dòng cũ hơn 14 ngày"}';
COMMENT ON COLUMN org.inbox_processed.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant của sự kiện"}';
COMMENT ON COLUMN org.inbox_processed.source_topic IS '{"cls": "C1", "pii": false, "desc": "Topic nguồn, phục vụ chẩn đoán"}';
COMMENT ON COLUMN org.inbox_processed.kafka_partition IS '{"cls": "C1", "pii": false, "desc": "Phân vùng Kafka"}';
COMMENT ON COLUMN org.inbox_processed.kafka_offset IS '{"cls": "C1", "pii": false, "desc": "Offset Kafka"}';
CREATE INDEX ix_inbox_processed__processed_at ON org.inbox_processed (processed_at);
ALTER TABLE org.inbox_processed ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.inbox_processed FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_inbox_processed__tenant ON org.inbox_processed AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.inbox_processed FROM PUBLIC;
GRANT SELECT, INSERT ON org.inbox_processed TO svc_org;
GRANT SELECT ON org.inbox_processed TO ro_org;

CREATE TABLE org.inbox_aggregate_version (
  consumer         varchar(100) NOT NULL,
  aggregate_type   varchar(64) NOT NULL,
  aggregate_id     uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  last_version     bigint NOT NULL,
  last_event_id    uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_inbox_aggregate_version PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);
COMMENT ON TABLE org.inbox_aggregate_version IS 'Phiên bản cao nhất của thực thể gốc mà consumer đã áp dụng (bảng bổ sung)';
COMMENT ON COLUMN org.inbox_aggregate_version.consumer IS '{"cls": "C1", "pii": false, "desc": "Tên consumer"}';
COMMENT ON COLUMN org.inbox_aggregate_version.aggregate_type IS '{"cls": "C1", "pii": false, "desc": "Loại thực thể gốc"}';
COMMENT ON COLUMN org.inbox_aggregate_version.aggregate_id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể gốc"}';
COMMENT ON COLUMN org.inbox_aggregate_version.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN org.inbox_aggregate_version.last_version IS '{"cls": "C1", "pii": false, "desc": "aggregate_version cao nhất đã áp dụng"}';
COMMENT ON COLUMN org.inbox_aggregate_version.last_event_id IS '{"cls": "C1", "pii": false, "desc": "Sự kiện tương ứng"}';
COMMENT ON COLUMN org.inbox_aggregate_version.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật"}';
ALTER TABLE org.inbox_aggregate_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.inbox_aggregate_version FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_inbox_aggregate_version__tenant ON org.inbox_aggregate_version AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.inbox_aggregate_version FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.inbox_aggregate_version TO svc_org;
GRANT SELECT ON org.inbox_aggregate_version TO ro_org;

CREATE TABLE org.idempotency_key (
  tenant_id        uuid NOT NULL,
  actor_id         uuid NOT NULL,
  idem_key         varchar(64) NOT NULL,
  created_day      date NOT NULL DEFAULT current_date,
  request_hash     bytea NOT NULL,
  status           varchar(12) NOT NULL,
  response_code    smallint,
  response_body    jsonb,
  resource_ref     varchar(128),
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idempotency_key PRIMARY KEY (tenant_id, actor_id, idem_key, created_day),
  CONSTRAINT ck_idempotency_key__status CHECK (status IN ('DANG_XU_LY','XONG'))
) PARTITION BY RANGE (created_day);
COMMENT ON TABLE org.idempotency_key IS 'Khoá chống lặp API ghi, phân vùng ngày, giữ 48 giờ (bảng bổ sung)';
COMMENT ON COLUMN org.idempotency_key.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN org.idempotency_key.actor_id IS '{"cls": "C1", "pii": false, "desc": "Người gọi (sub của JWT)"}';
COMMENT ON COLUMN org.idempotency_key.idem_key IS '{"cls": "C1", "pii": false, "desc": "Giá trị tiêu đề Idempotency-Key"}';
COMMENT ON COLUMN org.idempotency_key.created_day IS '{"cls": "C1", "pii": false, "desc": "Ngày tạo; khoá phân vùng RANGE ngày"}';
COMMENT ON COLUMN org.idempotency_key.request_hash IS '{"cls": "C1", "pii": false, "desc": "SHA-256 của phương thức, đường dẫn và thân yêu cầu chuẩn hoá"}';
COMMENT ON COLUMN org.idempotency_key.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái xử lý"}';
COMMENT ON COLUMN org.idempotency_key.response_code IS '{"cls": "C1", "pii": false, "desc": "Mã HTTP đã trả"}';
COMMENT ON COLUMN org.idempotency_key.response_body IS '{"cls": "C1", "pii": false, "desc": "Phản hồi đã trả (không chứa trường C2)"}';
COMMENT ON COLUMN org.idempotency_key.resource_ref IS '{"cls": "C1", "pii": false, "desc": "Mã tài nguyên đã tạo"}';
COMMENT ON COLUMN org.idempotency_key.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm"}';
ALTER TABLE org.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_idempotency_key__tenant ON org.idempotency_key AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.idempotency_key FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.idempotency_key TO svc_org;
GRANT SELECT ON org.idempotency_key TO ro_org;


-- Phân vùng mặc định để không mất ghi khi bảo trì chậm; bảo trì tạo trước phân vùng nên phân vùng mặc định
-- thường rỗng.
CREATE TABLE org.scim_sync_log_default PARTITION OF org.scim_sync_log DEFAULT;
CREATE TABLE org.outbox_event_default PARTITION OF org.outbox_event DEFAULT;
CREATE TABLE org.idempotency_key_default PARTITION OF org.idempotency_key DEFAULT;

-- Bật RLS và thu quyền trên một phân vùng con (TL-03: chính sách bảng cha không áp khi truy vấn thẳng phân vùng).
CREATE FUNCTION org.fn_protect_partition(p_child text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, org AS $$
BEGIN
  EXECUTE format('REVOKE ALL ON org.%I FROM PUBLIC, svc_org, ro_org', p_child);
  EXECUTE format('ALTER TABLE org.%I ENABLE ROW LEVEL SECURITY', p_child);
  EXECUTE format('ALTER TABLE org.%I FORCE ROW LEVEL SECURITY', p_child);
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'org' AND tablename = p_child) THEN
    EXECUTE format('CREATE POLICY %I ON org.%I AS PERMISSIVE FOR ALL TO svc_org, ro_org '
                   'USING (tenant_id = current_setting(''app.tenant_id'', true)::uuid) '
                   'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
                   'rls_' || p_child || '__tenant', p_child);
  END IF;
END $$;
REVOKE ALL ON FUNCTION org.fn_protect_partition(text) FROM PUBLIC;

-- Bảo trì phân vùng: tạo trước p_premake ngày (outbox, idempotency) và 3 tháng (scim_sync_log); xoá phân vùng
-- quá hạn giữ: outbox 3 ngày, idempotency 48 giờ, scim_sync_log 13 tháng (TL-03). Gọi bằng CronJob hoặc lịch của
-- ứng dụng; svc_org chỉ có EXECUTE, không có DDL hay DELETE.
CREATE FUNCTION org.fn_maintain_partitions(p_premake int DEFAULT 7) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, org AS $$
DECLARE
  d date;
  m date;
  r record;
  child text;
BEGIN
  IF p_premake < 1 OR p_premake > 31 THEN
    RAISE EXCEPTION 'p_premake ngoài khoảng cho phép';
  END IF;
  FOR i IN -1 .. p_premake LOOP
    d := current_date + i;
    child := 'outbox_event_p' || to_char(d, 'YYYYMMDD');
    IF to_regclass('org.' || child) IS NULL THEN
      EXECUTE format('CREATE TABLE org.%I PARTITION OF org.outbox_event FOR VALUES FROM (%L) TO (%L)',
                     child, d::timestamptz, (d + 1)::timestamptz);
      PERFORM org.fn_protect_partition(child);
    END IF;
    child := 'idempotency_key_p' || to_char(d, 'YYYYMMDD');
    IF to_regclass('org.' || child) IS NULL THEN
      EXECUTE format('CREATE TABLE org.%I PARTITION OF org.idempotency_key FOR VALUES FROM (%L) TO (%L)',
                     child, d, d + 1);
      PERFORM org.fn_protect_partition(child);
    END IF;
  END LOOP;
  FOR i IN 0 .. 2 LOOP
    m := (date_trunc('month', current_date) + make_interval(months => i))::date;
    child := 'scim_sync_log_p' || to_char(m, 'YYYYMM');
    IF to_regclass('org.' || child) IS NULL THEN
      EXECUTE format('CREATE TABLE org.%I PARTITION OF org.scim_sync_log FOR VALUES FROM (%L) TO (%L)',
                     child, m::timestamptz, (m + interval '1 month')::timestamptz);
      PERFORM org.fn_protect_partition(child);
    END IF;
  END LOOP;
  FOR r IN
    SELECT c.relname
      FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
      JOIN pg_namespace n ON n.oid = p.relnamespace
     WHERE n.nspname = 'org' AND p.relname IN ('outbox_event', 'idempotency_key', 'scim_sync_log')
  LOOP
    IF (r.relname ~ '^outbox_event_p[0-9]{8}$'
          AND to_date(substr(r.relname, 15), 'YYYYMMDD') < current_date - 3)
       OR (r.relname ~ '^idempotency_key_p[0-9]{8}$'
          AND to_date(substr(r.relname, 18), 'YYYYMMDD') < current_date - 2)
       OR (r.relname ~ '^scim_sync_log_p[0-9]{6}$'
          AND to_date(substr(r.relname, 16) || '01', 'YYYYMMDD') < (date_trunc('month', current_date) - interval '13 months')::date)
    THEN
      EXECUTE format('DROP TABLE org.%I', r.relname);
    END IF;
  END LOOP;
END $$;
REVOKE ALL ON FUNCTION org.fn_maintain_partitions(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org.fn_maintain_partitions(int) TO svc_org;

-- Dọn inbox quá hạn theo lô (14 ngày); chạy với quyền chủ sở hữu nên svc_org không cần quyền DELETE.
CREATE FUNCTION org.fn_purge_inbox_processed(p_older_than interval DEFAULT interval '14 days', p_batch int DEFAULT 10000)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, org AS $$
DECLARE
  n bigint;
BEGIN
  IF p_older_than < interval '14 days' THEN
    RAISE EXCEPTION 'Không được dọn inbox mới hơn 14 ngày';
  END IF;
  WITH victim AS (
    SELECT consumer, event_id FROM org.inbox_processed
     WHERE processed_at < now() - p_older_than
     LIMIT p_batch
  )
  DELETE FROM org.inbox_processed i USING victim v
   WHERE i.consumer = v.consumer AND i.event_id = v.event_id;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
REVOKE ALL ON FUNCTION org.fn_purge_inbox_processed(interval, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org.fn_purge_inbox_processed(interval, int) TO svc_org;

SELECT org.fn_protect_partition('scim_sync_log_default');
SELECT org.fn_protect_partition('outbox_event_default');
SELECT org.fn_protect_partition('idempotency_key_default');
SELECT org.fn_maintain_partitions(7);
