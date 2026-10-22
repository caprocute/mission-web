-- =============================================================================
-- V1__docgen_sinh_bieu_mau.sql
-- DV-11-01 · SVC-11 · TL-03 "Schema docgen (SVC-11)" (template, render_job, render_output);
-- TL-03 "Cách trình bày và các bảng dùng chung" (outbox_event, inbox_processed, idempotency_key).
--
-- render_job không phân vùng theo kỳ: TL-03 ghi PK (period_bucket, id) nhưng cũng ghi UQ request_event_id, mà
-- PostgreSQL buộc ràng buộc duy nhất của bảng phân vùng chứa khoá phân vùng; bảng giữ PK id (wiki trang 14).
-- form_instance_id và request_id là cột bổ sung để phát EVT-17 và gom các tác vụ của một lệnh sinh (suy luận).
-- outbox_event và idempotency_key dùng phân vùng DEFAULT như esign-service vì ảnh kiểm thử chưa có pg_partman.
-- =============================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_docgen') THEN
    CREATE ROLE svc_docgen NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_docgen') THEN
    CREATE ROLE ro_docgen NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_docgen') THEN
    CREATE ROLE cdc_docgen NOLOGIN NOINHERIT;
  END IF;
END $$;

-- -----------------------------------------------------------------------------
-- docgen.template (cột chung trừ period_bucket)
-- -----------------------------------------------------------------------------
CREATE TABLE docgen.template (
  id                   uuid           NOT NULL,
  tenant_id            uuid           NOT NULL,
  form_code            varchar(16)    NOT NULL,
  version_no           int            NOT NULL,
  subject_group_codes  varchar(16)[],
  object_key           varchar(512)   NOT NULL,
  content_sha256       bytea          NOT NULL,
  status               varchar(12)    NOT NULL,
  created_at           timestamptz    NOT NULL DEFAULT now(),
  created_by           uuid           NOT NULL,
  updated_at           timestamptz    NOT NULL DEFAULT now(),
  updated_by           uuid           NOT NULL,
  row_version          bigint         NOT NULL DEFAULT 1,
  CONSTRAINT pk_template PRIMARY KEY (id),
  CONSTRAINT uq_template__tenant_form_version UNIQUE (tenant_id, form_code, version_no),
  CONSTRAINT ck_template__status CHECK (status IN ('NHAP', 'HIEU_LUC', 'NGUNG')),
  CONSTRAINT ck_template__content_sha256 CHECK (octet_length(content_sha256) = 32),
  CONSTRAINT ck_template__version_no CHECK (version_no > 0)
);
COMMENT ON TABLE docgen.template IS 'Mẫu biểu mẫu DOCX theo phiên bản (tệp trên bucket templates)';
COMMENT ON COLUMN docgen.template.id IS '{"cls": "C0", "pii": false, "desc": "Mã"}';
COMMENT ON COLUMN docgen.template.tenant_id IS '{"cls": "C0", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN docgen.template.form_code IS '{"cls": "C0", "pii": false, "desc": "Mẫu"}';
COMMENT ON COLUMN docgen.template.version_no IS '{"cls": "C0", "pii": false, "desc": "Phiên bản"}';
COMMENT ON COLUMN docgen.template.subject_group_codes IS '{"cls": "C0", "pii": false, "desc": "Khối áp dụng; rỗng là mọi khối"}';
COMMENT ON COLUMN docgen.template.object_key IS '{"cls": "C0", "pii": false, "desc": "Khoá tệp mẫu"}';
COMMENT ON COLUMN docgen.template.content_sha256 IS '{"cls": "C0", "pii": false, "desc": "Băm tệp mẫu"}';
COMMENT ON COLUMN docgen.template.status IS '{"cls": "C0", "pii": false, "desc": "Trạng thái"}';
COMMENT ON COLUMN docgen.template.created_at IS '{"cls": "C0", "pii": false, "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN docgen.template.created_by IS '{"cls": "C0", "pii": false, "desc": "Người tạo"}';
COMMENT ON COLUMN docgen.template.updated_at IS '{"cls": "C0", "pii": false, "desc": "Thời điểm cập nhật"}';
COMMENT ON COLUMN docgen.template.updated_by IS '{"cls": "C0", "pii": false, "desc": "Người cập nhật"}';
COMMENT ON COLUMN docgen.template.row_version IS '{"cls": "C0", "pii": false, "desc": "Phiên bản dòng (khoá lạc quan)"}';

-- -----------------------------------------------------------------------------
-- docgen.render_job
-- -----------------------------------------------------------------------------
CREATE TABLE docgen.render_job (
  id                 uuid           NOT NULL,
  tenant_id          uuid           NOT NULL,
  template_id        uuid           NOT NULL,
  request_event_id   uuid           NOT NULL,
  request_id         uuid           NOT NULL,
  form_instance_id   uuid           NOT NULL,
  input_sha256       bytea          NOT NULL,
  status             varchar(12)    NOT NULL,
  attempts           int            NOT NULL DEFAULT 0,
  error              varchar(2000),
  created_at         timestamptz    NOT NULL DEFAULT now(),
  created_by         uuid           NOT NULL,
  updated_at         timestamptz    NOT NULL DEFAULT now(),
  updated_by         uuid           NOT NULL,
  row_version        bigint         NOT NULL DEFAULT 1,
  CONSTRAINT pk_render_job PRIMARY KEY (id),
  CONSTRAINT uq_render_job__request_event_id UNIQUE (request_event_id),
  CONSTRAINT fk_render_job__template FOREIGN KEY (template_id) REFERENCES docgen.template (id),
  CONSTRAINT ck_render_job__status CHECK (status IN ('CHO', 'DANG_SINH', 'XONG', 'LOI')),
  CONSTRAINT ck_render_job__input_sha256 CHECK (octet_length(input_sha256) = 32),
  CONSTRAINT ck_render_job__attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_render_job__tenant_request ON docgen.render_job (tenant_id, request_id);
-- Hàng đợi của worker sinh; độ dài hàng đợi là chỉ số co giãn của KEDA.
CREATE INDEX ix_render_job__cho ON docgen.render_job (created_at) WHERE status IN ('CHO', 'DANG_SINH');
COMMENT ON TABLE docgen.render_job IS 'Tác vụ sinh một biểu mẫu từ dữ liệu đã chuẩn hoá';
COMMENT ON COLUMN docgen.render_job.id IS '{"cls": "C1", "pii": false, "desc": "Mã"}';
COMMENT ON COLUMN docgen.render_job.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN docgen.render_job.template_id IS '{"cls": "C1", "pii": false, "desc": "Mẫu"}';
COMMENT ON COLUMN docgen.render_job.request_event_id IS '{"cls": "C1", "pii": false, "desc": "Sự kiện yêu cầu"}';
COMMENT ON COLUMN docgen.render_job.request_id IS '{"cls": "C1", "pii": false, "desc": "Lệnh sinh gom các tác vụ (mã tác vụ dài của API)"}';
COMMENT ON COLUMN docgen.render_job.form_instance_id IS '{"cls": "C1", "pii": false, "desc": "Biểu mẫu nguồn (dossier.form_instance)"}';
COMMENT ON COLUMN docgen.render_job.input_sha256 IS '{"cls": "C1", "pii": false, "desc": "Băm dữ liệu"}';
COMMENT ON COLUMN docgen.render_job.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái"}';
COMMENT ON COLUMN docgen.render_job.attempts IS '{"cls": "C1", "pii": false, "desc": "Số lần thử"}';
COMMENT ON COLUMN docgen.render_job.error IS '{"cls": "C1", "pii": false, "desc": "Lỗi"}';
COMMENT ON COLUMN docgen.render_job.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN docgen.render_job.created_by IS '{"cls": "C1", "pii": false, "desc": "Người tạo"}';
COMMENT ON COLUMN docgen.render_job.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật"}';
COMMENT ON COLUMN docgen.render_job.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật"}';
COMMENT ON COLUMN docgen.render_job.row_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản dòng (khoá lạc quan)"}';

-- -----------------------------------------------------------------------------
-- docgen.render_output
-- -----------------------------------------------------------------------------
CREATE TABLE docgen.render_output (
  id              uuid           NOT NULL,
  tenant_id       uuid           NOT NULL,
  render_job_id   uuid           NOT NULL,
  output_kind     varchar(8)     NOT NULL,
  object_key      varchar(512)   NOT NULL,
  pdf_sha256      bytea          NOT NULL,
  size_bytes      bigint         NOT NULL,
  pdfa_validated  boolean        NOT NULL,
  CONSTRAINT pk_render_output PRIMARY KEY (id),
  CONSTRAINT fk_render_output__render_job FOREIGN KEY (render_job_id) REFERENCES docgen.render_job (id),
  CONSTRAINT ck_render_output__output_kind CHECK (output_kind IN ('PDFA', 'DOCX')),
  CONSTRAINT ck_render_output__pdf_sha256 CHECK (octet_length(pdf_sha256) = 32),
  CONSTRAINT ck_render_output__size_bytes CHECK (size_bytes > 0)
);
CREATE INDEX ix_render_output__render_job ON docgen.render_output (render_job_id);
COMMENT ON TABLE docgen.render_output IS 'Tệp kết quả của tác vụ sinh kèm băm (EVT-17)';
COMMENT ON COLUMN docgen.render_output.id IS '{"cls": "C1", "pii": false, "desc": "Mã"}';
COMMENT ON COLUMN docgen.render_output.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN docgen.render_output.render_job_id IS '{"cls": "C1", "pii": false, "desc": "Tác vụ"}';
COMMENT ON COLUMN docgen.render_output.output_kind IS '{"cls": "C1", "pii": false, "desc": "Kiểu"}';
COMMENT ON COLUMN docgen.render_output.object_key IS '{"cls": "C1", "pii": false, "desc": "Khoá tệp trên bucket dossier"}';
COMMENT ON COLUMN docgen.render_output.pdf_sha256 IS '{"cls": "C1", "pii": false, "desc": "Băm SHA-256 của tệp"}';
COMMENT ON COLUMN docgen.render_output.size_bytes IS '{"cls": "C1", "pii": false, "desc": "Kích thước tệp"}';
COMMENT ON COLUMN docgen.render_output.pdfa_validated IS '{"cls": "C1", "pii": false, "desc": "Đã qua kiểm tra veraPDF"}';

-- -----------------------------------------------------------------------------
-- docgen.outbox_event (cùng cột với NEN-032 db/common-business V0_20)
-- -----------------------------------------------------------------------------
CREATE TABLE docgen.outbox_event (
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
CREATE TABLE docgen.outbox_event_default PARTITION OF docgen.outbox_event DEFAULT;
COMMENT ON TABLE docgen.outbox_event IS 'Hộp thư đi giao dịch (Transactional Outbox), phân vùng ngày, giữ 3 ngày';
COMMENT ON COLUMN docgen.outbox_event.id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện (eventId, UUIDv7)"}';
COMMENT ON COLUMN docgen.outbox_event.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant phát sự kiện"}';
COMMENT ON COLUMN docgen.outbox_event.aggregate_type IS '{"cls": "C1", "pii": false, "desc": "Loại thực thể gốc"}';
COMMENT ON COLUMN docgen.outbox_event.aggregate_id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể gốc"}';
COMMENT ON COLUMN docgen.outbox_event.aggregate_version IS '{"cls": "C1", "pii": false, "desc": "row_version của thực thể sau thay đổi"}';
COMMENT ON COLUMN docgen.outbox_event.event_type IS '{"cls": "C1", "pii": false, "desc": "Tên nghiệp vụ của sự kiện"}';
COMMENT ON COLUMN docgen.outbox_event.topic IS '{"cls": "C1", "pii": false, "desc": "Topic đích; Debezium định tuyến theo cột này"}';
COMMENT ON COLUMN docgen.outbox_event.event_key IS '{"cls": "C1", "pii": false, "desc": "Khoá Kafka tenantId:aggregateId"}';
COMMENT ON COLUMN docgen.outbox_event.payload IS '{"cls": "C1", "pii": false, "desc": "Nội dung Avro đã tuần tự hoá; không mang C2 dạng rõ"}';
COMMENT ON COLUMN docgen.outbox_event.schema_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản lược đồ trong Apicurio"}';
COMMENT ON COLUMN docgen.outbox_event.headers IS '{"cls": "C1", "pii": false, "desc": "traceparent, actorId, policyVersion, classification, occurredAt"}';
COMMENT ON COLUMN docgen.outbox_event.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi; khoá phân vùng"}';

-- -----------------------------------------------------------------------------
-- docgen.idempotency_key (cùng cột với NEN-032 db/common-business V0_22; giữ 48 giờ, HD-02)
-- -----------------------------------------------------------------------------
CREATE TABLE docgen.idempotency_key (
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
CREATE TABLE docgen.idempotency_key_default PARTITION OF docgen.idempotency_key DEFAULT;
COMMENT ON TABLE docgen.idempotency_key IS 'Khoá Idempotency-Key của lệnh ghi, giữ 48 giờ';
COMMENT ON COLUMN docgen.idempotency_key.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN docgen.idempotency_key.actor_id IS '{"cls": "C1", "pii": false, "desc": "Người hoặc dịch vụ gọi"}';
COMMENT ON COLUMN docgen.idempotency_key.idem_key IS '{"cls": "C1", "pii": false, "desc": "Giá trị header Idempotency-Key"}';
COMMENT ON COLUMN docgen.idempotency_key.created_day IS '{"cls": "C1", "pii": false, "desc": "Ngày tạo; khoá phân vùng"}';
COMMENT ON COLUMN docgen.idempotency_key.request_hash IS '{"cls": "C1", "pii": false, "desc": "SHA-256 của yêu cầu"}';
COMMENT ON COLUMN docgen.idempotency_key.status IS '{"cls": "C1", "pii": false, "desc": "DANG_XU_LY hoặc XONG"}';
COMMENT ON COLUMN docgen.idempotency_key.response_code IS '{"cls": "C1", "pii": false, "desc": "Mã HTTP đã trả"}';
COMMENT ON COLUMN docgen.idempotency_key.response_body IS '{"cls": "C1", "pii": false, "desc": "Phản hồi đã trả; không chứa C2"}';
COMMENT ON COLUMN docgen.idempotency_key.resource_ref IS '{"cls": "C1", "pii": false, "desc": "Tài nguyên đã tạo"}';
COMMENT ON COLUMN docgen.idempotency_key.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi"}';


-- -----------------------------------------------------------------------------
-- docgen.inbox_processed (baseline mục 8, cùng cột với org-service V4)
-- -----------------------------------------------------------------------------
CREATE TABLE docgen.inbox_processed (
  event_id         uuid          NOT NULL,
  consumer         varchar(100)  NOT NULL,
  processed_at     timestamptz   NOT NULL DEFAULT now(),
  tenant_id        uuid          NOT NULL,
  source_topic     varchar(128)  NOT NULL,
  kafka_partition  int           NOT NULL,
  kafka_offset     bigint        NOT NULL,
  CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON docgen.inbox_processed (processed_at);
COMMENT ON TABLE docgen.inbox_processed IS 'Hộp thư đến idempotent (baseline mục 8)';
COMMENT ON COLUMN docgen.inbox_processed.event_id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện đã xử lý"}';
COMMENT ON COLUMN docgen.inbox_processed.consumer IS '{"cls": "C1", "pii": false, "desc": "Tên consumer"}';
COMMENT ON COLUMN docgen.inbox_processed.processed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm xử lý; dọn sau 14 ngày"}';
COMMENT ON COLUMN docgen.inbox_processed.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant của sự kiện"}';
COMMENT ON COLUMN docgen.inbox_processed.source_topic IS '{"cls": "C1", "pii": false, "desc": "Topic nguồn"}';
COMMENT ON COLUMN docgen.inbox_processed.kafka_partition IS '{"cls": "C1", "pii": false, "desc": "Phân vùng Kafka"}';
COMMENT ON COLUMN docgen.inbox_processed.kafka_offset IS '{"cls": "C1", "pii": false, "desc": "Offset Kafka"}';

-- -----------------------------------------------------------------------------
-- Hàm cho worker sinh: liệt kê tác vụ đang chờ, cùng tác vụ đang sinh đã quá hạn thuê (worker dừng giữa
-- chừng), của mọi tenant (chỉ mã và tenant) để worker xử lý từng tác vụ trong ngữ cảnh tenant của nó với RLS
-- đầy đủ; đếm hàng đợi cho KEDA (suy luận, như dịch vụ ký số).
-- -----------------------------------------------------------------------------
CREATE FUNCTION docgen.fn_tac_vu_cho_sinh(p_limit int, p_lease_seconds bigint)
  RETURNS TABLE (id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = docgen, pg_temp
AS $$
  SELECT j.id, j.tenant_id FROM docgen.render_job j
   WHERE j.status = 'CHO'
      OR (j.status = 'DANG_SINH' AND j.updated_at < now() - make_interval(secs => p_lease_seconds))
   ORDER BY j.created_at
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION docgen.fn_tac_vu_cho_sinh(int, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION docgen.fn_tac_vu_cho_sinh(int, bigint) TO svc_docgen;

CREATE FUNCTION docgen.fn_do_dai_hang_doi()
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = docgen, pg_temp
AS $$
  SELECT count(*) FROM docgen.render_job WHERE status = 'CHO'
$$;
REVOKE ALL ON FUNCTION docgen.fn_do_dai_hang_doi() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION docgen.fn_do_dai_hang_doi() TO svc_docgen;

-- -----------------------------------------------------------------------------
-- RLS và quyền (TL-03 "Không truy cập chéo và quyền tối thiểu").
-- -----------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['template', 'render_job', 'render_output', 'outbox_event', 'inbox_processed',
                           'idempotency_key'] LOOP
    EXECUTE format('ALTER TABLE docgen.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE docgen.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY rls_%s__tenant ON docgen.%I AS PERMISSIVE FOR ALL TO svc_docgen, ro_docgen '
      || 'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t, t);
    EXECUTE format('REVOKE ALL ON docgen.%I FROM PUBLIC', t);
    EXECUTE format('GRANT SELECT ON docgen.%I TO ro_docgen', t);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON docgen.template TO svc_docgen;
GRANT SELECT, INSERT, UPDATE ON docgen.render_job TO svc_docgen;
GRANT SELECT, INSERT ON docgen.render_output TO svc_docgen;
GRANT INSERT ON docgen.outbox_event TO svc_docgen;
GRANT SELECT ON docgen.outbox_event TO cdc_docgen;
GRANT SELECT, INSERT ON docgen.inbox_processed TO svc_docgen;
GRANT SELECT, INSERT, UPDATE, DELETE ON docgen.idempotency_key TO svc_docgen;

-- Chủ sở hữu hàm (vai trò migration) cũng chịu FORCE RLS: cho phép đúng việc đọc tác vụ chưa xong để hàm quét
-- và hàm đếm hàng đợi hoạt động, không mở thêm quyền nào khác.
CREATE POLICY rls_render_job__quet_cho_sinh ON docgen.render_job AS PERMISSIVE FOR SELECT TO CURRENT_USER
  USING (status IN ('CHO', 'DANG_SINH'));
