-- =============================================================================
-- V3__saga_outbox_idempotency.sql
-- DV-06-05 · ADR-04 tầng 3 · TL-03 "Schema flowable và saga (SVC-06)" bảng saga_instance, saga_step;
-- TL-03 "Cách trình bày và các bảng dùng chung" (outbox_event, idempotency_key); baseline mục 8 cơ chế 5.
--
-- Bảng outbox_event và idempotency_key viết theo cùng DDL với db/common-business của NEN-032 nhưng dùng
-- phân vùng DEFAULT thay cho pg_partman, như org-service (V4): ảnh PostgreSQL kiểm thử chưa có pg_partman.
-- Khi CSDL workflow chạy bootstrap NEN-031 có pg_partman, chuyển quản lý phân vùng sang partman bằng một
-- migration mới (expand/contract), không sửa tệp này.
-- =============================================================================

-- Vai trò ứng dụng và vai trò chỉ đọc do bootstrap NEN-031 tạo; tạo bù khi chạy trên CSDL trống (kiểm thử).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_flowable') THEN
    CREATE ROLE svc_flowable NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_flowable') THEN
    CREATE ROLE ro_flowable NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_flowable') THEN
    CREATE ROLE cdc_flowable NOLOGIN NOINHERIT;
  END IF;
END $$;

-- -----------------------------------------------------------------------------
-- flowable.saga_instance
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.saga_instance (
  id                         uuid         NOT NULL,
  tenant_id                  uuid         NOT NULL,
  saga_type                  varchar(32)  NOT NULL,
  correlation_id             uuid         NOT NULL,
  status                     varchar(16)  NOT NULL,
  current_step_no            int          NOT NULL,
  point_of_no_return_passed  boolean      NOT NULL DEFAULT false,
  context                    jsonb        NOT NULL,
  started_at                 timestamptz  NOT NULL,
  finished_at                timestamptz,
  created_at                 timestamptz  NOT NULL DEFAULT now(),
  created_by                 uuid         NOT NULL,
  updated_at                 timestamptz  NOT NULL DEFAULT now(),
  updated_by                 uuid         NOT NULL,
  row_version                bigint       NOT NULL DEFAULT 1,
  CONSTRAINT pk_saga_instance PRIMARY KEY (id),
  CONSTRAINT uq_saga_instance__tenant_type_correlation UNIQUE (tenant_id, saga_type, correlation_id),
  CONSTRAINT ck_saga_instance__saga_type
    CHECK (saga_type IN ('CHOT_KY', 'MO_LAI_HAU_KIEM', 'TOT_NGHIEP_TENANT', 'DONG_GOI_HO_SO')),
  CONSTRAINT ck_saga_instance__status
    CHECK (status IN ('DANG_CHAY', 'CHO_SU_KIEN', 'HOAN_TAT', 'DANG_BU_TRU', 'DA_BU_TRU', 'LOI_CAN_XU_LY'))
);
-- Tra saga đang hoạt động theo loại để chặn saga thứ hai cho cùng khoá độc quyền (409) và quét thời hạn.
CREATE INDEX ix_saga_instance__tenant_type_status ON flowable.saga_instance (tenant_id, saga_type, status);
COMMENT ON TABLE flowable.saga_instance IS 'Saga điều phối tầng 3 (ADR-04): chốt kỳ, mở lại hậu kiểm, tốt nghiệp tenant, đóng gói hồ sơ';
COMMENT ON COLUMN flowable.saga_instance.id IS '{"cls": "C1", "pii": false, "desc": "Mã saga, cũng là mã tác vụ dài trả cho getJob"}';
COMMENT ON COLUMN flowable.saga_instance.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN flowable.saga_instance.saga_type IS '{"cls": "C1", "pii": false, "desc": "Loại saga"}';
COMMENT ON COLUMN flowable.saga_instance.correlation_id IS '{"cls": "C1", "pii": false, "desc": "Mã nghiệp vụ (lệnh khoá kỳ, yêu cầu mở lại)"}';
COMMENT ON COLUMN flowable.saga_instance.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái saga"}';
COMMENT ON COLUMN flowable.saga_instance.current_step_no IS '{"cls": "C1", "pii": false, "desc": "Bước hiện tại; khi bù trừ là bước đang hoàn tác"}';
COMMENT ON COLUMN flowable.saga_instance.point_of_no_return_passed IS '{"cls": "C1", "pii": false, "desc": "Đã qua điểm không quay lại"}';
COMMENT ON COLUMN flowable.saga_instance.context IS '{"cls": "C1", "pii": false, "desc": "Tham số (mã kỳ, danh sách đơn vị) và cờ điều khiển; không chứa C2"}';
COMMENT ON COLUMN flowable.saga_instance.started_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm khởi động"}';
COMMENT ON COLUMN flowable.saga_instance.finished_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm kết thúc"}';
COMMENT ON COLUMN flowable.saga_instance.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN flowable.saga_instance.created_by IS '{"cls": "C1", "pii": false, "desc": "Người hoặc dịch vụ khởi động"}';
COMMENT ON COLUMN flowable.saga_instance.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật"}';
COMMENT ON COLUMN flowable.saga_instance.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người hoặc dịch vụ cập nhật"}';
COMMENT ON COLUMN flowable.saga_instance.row_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản dòng, khoá lạc quan"}';

-- -----------------------------------------------------------------------------
-- flowable.saga_step
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.saga_step (
  id                 uuid           NOT NULL,
  tenant_id          uuid           NOT NULL,
  saga_instance_id   uuid           NOT NULL,
  step_no            int            NOT NULL,
  step_code          varchar(48)    NOT NULL,
  command_event_id   uuid,
  reply_event_id     uuid,
  compensation_code  varchar(48),
  status             varchar(16)    NOT NULL,
  attempts           int            NOT NULL DEFAULT 0,
  last_error         varchar(2000),
  started_at         timestamptz,
  finished_at        timestamptz,
  CONSTRAINT pk_saga_step PRIMARY KEY (id),
  CONSTRAINT fk_saga_step__saga_instance FOREIGN KEY (saga_instance_id) REFERENCES flowable.saga_instance (id),
  CONSTRAINT uq_saga_step__instance_step_no UNIQUE (saga_instance_id, step_no),
  CONSTRAINT ck_saga_step__status CHECK (status IN ('CHO', 'DANG_CHAY', 'XONG', 'LOI', 'DA_BU_TRU')),
  CONSTRAINT ck_saga_step__attempts CHECK (attempts >= 0)
);
COMMENT ON TABLE flowable.saga_step IS 'Từng bước của saga với lệnh, phản hồi, bù trừ và số lần thử';
COMMENT ON COLUMN flowable.saga_step.id IS '{"cls": "C1", "pii": false, "desc": "Mã bước"}';
COMMENT ON COLUMN flowable.saga_step.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN flowable.saga_step.saga_instance_id IS '{"cls": "C1", "pii": false, "desc": "Saga"}';
COMMENT ON COLUMN flowable.saga_step.step_no IS '{"cls": "C1", "pii": false, "desc": "Thứ tự bước, bắt đầu từ 1"}';
COMMENT ON COLUMN flowable.saga_step.step_code IS '{"cls": "C1", "pii": false, "desc": "Mã bước, ví dụ DAT_DANG_KHOA, NIEM_PHONG"}';
COMMENT ON COLUMN flowable.saga_step.command_event_id IS '{"cls": "C1", "pii": false, "desc": "Lệnh đã phát"}';
COMMENT ON COLUMN flowable.saga_step.reply_event_id IS '{"cls": "C1", "pii": false, "desc": "Phản hồi đã nhận"}';
COMMENT ON COLUMN flowable.saga_step.compensation_code IS '{"cls": "C1", "pii": false, "desc": "Hành động bù trừ đã đăng ký"}';
COMMENT ON COLUMN flowable.saga_step.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái bước"}';
COMMENT ON COLUMN flowable.saga_step.attempts IS '{"cls": "C1", "pii": false, "desc": "Số lần thử"}';
COMMENT ON COLUMN flowable.saga_step.last_error IS '{"cls": "C1", "pii": false, "desc": "Lỗi gần nhất, không chứa dữ liệu C2"}';
COMMENT ON COLUMN flowable.saga_step.started_at IS '{"cls": "C1", "pii": false, "desc": "Bắt đầu lần thử gần nhất"}';
COMMENT ON COLUMN flowable.saga_step.finished_at IS '{"cls": "C1", "pii": false, "desc": "Kết thúc"}';

-- -----------------------------------------------------------------------------
-- flowable.outbox_event (cùng cột với NEN-032 db/common-business V0_20)
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.outbox_event (
  id                uuid         NOT NULL,
  tenant_id         uuid         NOT NULL,
  aggregate_type    varchar(64)  NOT NULL,
  aggregate_id      uuid         NOT NULL,
  aggregate_version bigint       NOT NULL,
  event_type        varchar(96)  NOT NULL,
  topic             varchar(128) NOT NULL,
  event_key         varchar(80)  NOT NULL,
  payload           bytea        NOT NULL,
  schema_version    int          NOT NULL,
  headers           jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_outbox_event PRIMARY KEY (id, created_at),
  CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+(\.[a-z]+){1,3}\.v[0-9]+$')
) PARTITION BY RANGE (created_at);
CREATE TABLE flowable.outbox_event_default PARTITION OF flowable.outbox_event DEFAULT;
COMMENT ON TABLE flowable.outbox_event IS 'Hộp thư đi giao dịch (Transactional Outbox), phân vùng ngày, giữ 3 ngày';
COMMENT ON COLUMN flowable.outbox_event.id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện (eventId, UUIDv7)"}';
COMMENT ON COLUMN flowable.outbox_event.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant phát sự kiện"}';
COMMENT ON COLUMN flowable.outbox_event.aggregate_type IS '{"cls": "C1", "pii": false, "desc": "Loại thực thể gốc"}';
COMMENT ON COLUMN flowable.outbox_event.aggregate_id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể gốc"}';
COMMENT ON COLUMN flowable.outbox_event.aggregate_version IS '{"cls": "C1", "pii": false, "desc": "row_version của thực thể sau thay đổi"}';
COMMENT ON COLUMN flowable.outbox_event.event_type IS '{"cls": "C1", "pii": false, "desc": "Tên nghiệp vụ của sự kiện"}';
COMMENT ON COLUMN flowable.outbox_event.topic IS '{"cls": "C1", "pii": false, "desc": "Topic đích; Debezium định tuyến theo cột này"}';
COMMENT ON COLUMN flowable.outbox_event.event_key IS '{"cls": "C1", "pii": false, "desc": "Khoá Kafka tenantId:aggregateId"}';
COMMENT ON COLUMN flowable.outbox_event.payload IS '{"cls": "C1", "pii": false, "desc": "Nội dung Avro đã tuần tự hoá; không mang C2 dạng rõ"}';
COMMENT ON COLUMN flowable.outbox_event.schema_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản lược đồ trong Apicurio"}';
COMMENT ON COLUMN flowable.outbox_event.headers IS '{"cls": "C1", "pii": false, "desc": "traceparent, actorId, policyVersion, classification, occurredAt"}';
COMMENT ON COLUMN flowable.outbox_event.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi; khoá phân vùng"}';

-- -----------------------------------------------------------------------------
-- flowable.idempotency_key (cùng cột với NEN-032 db/common-business V0_22; giữ 48 giờ, HD-02)
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.idempotency_key (
  tenant_id     uuid         NOT NULL,
  actor_id      uuid         NOT NULL,
  idem_key      varchar(64)  NOT NULL,
  created_day   date         NOT NULL DEFAULT current_date,
  request_hash  bytea        NOT NULL,
  status        varchar(12)  NOT NULL,
  response_code smallint,
  response_body jsonb,
  resource_ref  varchar(128),
  created_at    timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_idempotency_key PRIMARY KEY (tenant_id, actor_id, idem_key, created_day),
  CONSTRAINT ck_idempotency_key__status CHECK (status IN ('DANG_XU_LY', 'XONG'))
) PARTITION BY RANGE (created_day);
CREATE TABLE flowable.idempotency_key_default PARTITION OF flowable.idempotency_key DEFAULT;
COMMENT ON TABLE flowable.idempotency_key IS 'Khoá Idempotency-Key của lệnh ghi, giữ 48 giờ';
COMMENT ON COLUMN flowable.idempotency_key.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN flowable.idempotency_key.actor_id IS '{"cls": "C1", "pii": false, "desc": "Người hoặc dịch vụ gọi"}';
COMMENT ON COLUMN flowable.idempotency_key.idem_key IS '{"cls": "C1", "pii": false, "desc": "Giá trị header Idempotency-Key"}';
COMMENT ON COLUMN flowable.idempotency_key.created_day IS '{"cls": "C1", "pii": false, "desc": "Ngày tạo; khoá phân vùng"}';
COMMENT ON COLUMN flowable.idempotency_key.request_hash IS '{"cls": "C1", "pii": false, "desc": "SHA-256 của yêu cầu"}';
COMMENT ON COLUMN flowable.idempotency_key.status IS '{"cls": "C1", "pii": false, "desc": "DANG_XU_LY hoặc XONG"}';
COMMENT ON COLUMN flowable.idempotency_key.response_code IS '{"cls": "C1", "pii": false, "desc": "Mã HTTP đã trả"}';
COMMENT ON COLUMN flowable.idempotency_key.response_body IS '{"cls": "C1", "pii": false, "desc": "Phản hồi đã trả; không chứa C2"}';
COMMENT ON COLUMN flowable.idempotency_key.resource_ref IS '{"cls": "C1", "pii": false, "desc": "Tài nguyên đã tạo"}';
COMMENT ON COLUMN flowable.idempotency_key.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi"}';

-- -----------------------------------------------------------------------------
-- RLS và quyền (TL-03 "Không truy cập chéo và quyền tối thiểu").
-- -----------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['saga_instance', 'saga_step', 'outbox_event', 'idempotency_key'] LOOP
    EXECUTE format('ALTER TABLE flowable.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE flowable.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY rls_%s__tenant ON flowable.%I AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable '
      || 'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t, t);
    EXECUTE format('REVOKE ALL ON flowable.%I FROM PUBLIC', t);
    EXECUTE format('GRANT SELECT ON flowable.%I TO ro_flowable', t);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON flowable.saga_instance TO svc_flowable;
GRANT SELECT, INSERT, UPDATE ON flowable.saga_step TO svc_flowable;
GRANT INSERT ON flowable.outbox_event TO svc_flowable;
GRANT SELECT ON flowable.outbox_event TO cdc_flowable;
GRANT SELECT, INSERT, UPDATE, DELETE ON flowable.idempotency_key TO svc_flowable;
