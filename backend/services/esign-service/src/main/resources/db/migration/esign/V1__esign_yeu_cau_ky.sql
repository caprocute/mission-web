-- =============================================================================
-- V1__esign_yeu_cau_ky.sql
-- DV-10-01 · SVC-10 · TL-03 "Schema esign (SVC-10)" (sign_request, sign_item, certificate_ref);
-- TL-03 "Cách trình bày và các bảng dùng chung" (outbox_event, inbox_processed, idempotency_key).
--
-- Khoá riêng của người ký không bao giờ vào CSDL: chỉ lưu yêu cầu, băm tài liệu, chữ ký nhận về và phần
-- công khai của chứng thư. outbox_event và idempotency_key dùng phân vùng DEFAULT như org-service (V4) và
-- workflow-service (V3) vì ảnh PostgreSQL kiểm thử chưa có pg_partman.
-- =============================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_esign') THEN
    CREATE ROLE svc_esign NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_esign') THEN
    CREATE ROLE ro_esign NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_esign') THEN
    CREATE ROLE cdc_esign NOLOGIN NOINHERIT;
  END IF;
END $$;

-- -----------------------------------------------------------------------------
-- esign.certificate_ref
-- -----------------------------------------------------------------------------
CREATE TABLE esign.certificate_ref (
  id                uuid          NOT NULL,
  tenant_id         uuid          NOT NULL,
  person_id         uuid          NOT NULL,
  cert_sha256       bytea         NOT NULL,
  subject_dn        varchar(512)  NOT NULL,
  issuer_dn         varchar(512)  NOT NULL,
  serial_no         varchar(64)   NOT NULL,
  not_before        timestamptz   NOT NULL,
  not_after         timestamptz   NOT NULL,
  cert_der          bytea         NOT NULL,
  last_ocsp_status  varchar(16),
  last_ocsp_at      timestamptz,
  CONSTRAINT pk_certificate_ref PRIMARY KEY (id),
  CONSTRAINT uq_certificate_ref__tenant_cert_sha256 UNIQUE (tenant_id, cert_sha256),
  CONSTRAINT ck_certificate_ref__cert_sha256 CHECK (octet_length(cert_sha256) = 32)
);
CREATE INDEX ix_certificate_ref__tenant_person ON esign.certificate_ref (tenant_id, person_id);
COMMENT ON TABLE esign.certificate_ref IS 'Chứng thư đã dùng hoặc đã đăng ký kèm kết quả OCSP lúc ký (chỉ phần công khai)';
COMMENT ON COLUMN esign.certificate_ref.id IS '{"cls": "C1", "pii": false, "desc": "Mã"}';
COMMENT ON COLUMN esign.certificate_ref.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN esign.certificate_ref.person_id IS '{"cls": "C1", "pii": false, "desc": "Chủ chứng thư"}';
COMMENT ON COLUMN esign.certificate_ref.cert_sha256 IS '{"cls": "C1", "pii": false, "desc": "Dấu vân tay SHA-256"}';
COMMENT ON COLUMN esign.certificate_ref.subject_dn IS '{"cls": "C1", "pii": false, "desc": "Chủ thể"}';
COMMENT ON COLUMN esign.certificate_ref.issuer_dn IS '{"cls": "C1", "pii": false, "desc": "Nơi cấp"}';
COMMENT ON COLUMN esign.certificate_ref.serial_no IS '{"cls": "C1", "pii": false, "desc": "Số hiệu"}';
COMMENT ON COLUMN esign.certificate_ref.not_before IS '{"cls": "C1", "pii": false, "desc": "Hiệu lực từ"}';
COMMENT ON COLUMN esign.certificate_ref.not_after IS '{"cls": "C1", "pii": false, "desc": "Hiệu lực đến"}';
COMMENT ON COLUMN esign.certificate_ref.cert_der IS '{"cls": "C1", "pii": false, "desc": "Chứng thư (công khai)"}';
COMMENT ON COLUMN esign.certificate_ref.last_ocsp_status IS '{"cls": "C1", "pii": false, "desc": "Kết quả OCSP gần nhất: GOOD, REVOKED, UNKNOWN"}';
COMMENT ON COLUMN esign.certificate_ref.last_ocsp_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm kiểm tra thu hồi gần nhất"}';

-- -----------------------------------------------------------------------------
-- esign.sign_request (cột chung trừ period_bucket)
-- -----------------------------------------------------------------------------
CREATE TABLE esign.sign_request (
  id                uuid          NOT NULL,
  tenant_id         uuid          NOT NULL,
  signer_person_id  uuid          NOT NULL,
  purpose           varchar(32)   NOT NULL,
  source_module     varchar(32)   NOT NULL,
  source_ref_id     uuid          NOT NULL,
  provider          varchar(16)   NOT NULL,
  is_batch          boolean       NOT NULL,
  provider_txn_id   varchar(128),
  status            varchar(12)   NOT NULL,
  expires_at        timestamptz   NOT NULL,
  completed_at      timestamptz,
  created_at        timestamptz   NOT NULL DEFAULT now(),
  created_by        uuid          NOT NULL,
  updated_at        timestamptz   NOT NULL DEFAULT now(),
  updated_by        uuid          NOT NULL,
  row_version       bigint        NOT NULL DEFAULT 1,
  CONSTRAINT pk_sign_request PRIMARY KEY (id),
  CONSTRAINT ck_sign_request__purpose CHECK (purpose IN ('TU_DANH_GIA', 'THAM_DINH', 'QUYET_DINH', 'BIEN_BAN',
    'GOI_HO_SO', 'PHE_DUYET_MO_LAI', 'PHAT_HANH_POLICY')),
  CONSTRAINT ck_sign_request__provider CHECK (provider IN ('MYSIGN', 'VIETTEL_CA', 'CA_CHINH_PHU', 'BAN_CO_YEU')),
  CONSTRAINT ck_sign_request__status CHECK (status IN ('TAO', 'CHO_XAC_NHAN', 'DA_KY', 'TU_CHOI', 'HET_HAN', 'LOI'))
);
CREATE INDEX ix_sign_request__tenant_signer ON esign.sign_request (tenant_id, signer_person_id, id DESC);
-- Một người ký chỉ có một yêu cầu đang chờ cho cùng bản ghi nguồn (ESIGN-409-REQUEST-PENDING).
CREATE UNIQUE INDEX uq_sign_request__dang_cho ON esign.sign_request
  (tenant_id, source_module, source_ref_id, signer_person_id) WHERE status IN ('TAO', 'CHO_XAC_NHAN');
-- Worker thăm dò quét yêu cầu đang chờ xác nhận.
CREATE INDEX ix_sign_request__cho_xac_nhan ON esign.sign_request (expires_at) WHERE status = 'CHO_XAC_NHAN';
COMMENT ON TABLE esign.sign_request IS 'Yêu cầu ký của một người tại một mốc pháp lý, có thể ký lô nhiều tài liệu';
COMMENT ON COLUMN esign.sign_request.id IS '{"cls": "C1", "pii": false, "desc": "Mã"}';
COMMENT ON COLUMN esign.sign_request.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN esign.sign_request.signer_person_id IS '{"cls": "C1", "pii": false, "desc": "Người ký"}';
COMMENT ON COLUMN esign.sign_request.purpose IS '{"cls": "C1", "pii": false, "desc": "Mốc pháp lý"}';
COMMENT ON COLUMN esign.sign_request.source_module IS '{"cls": "C1", "pii": false, "desc": "Module của bản ghi nghiệp vụ yêu cầu ký"}';
COMMENT ON COLUMN esign.sign_request.source_ref_id IS '{"cls": "C1", "pii": false, "desc": "Bản ghi nghiệp vụ yêu cầu ký"}';
COMMENT ON COLUMN esign.sign_request.provider IS '{"cls": "C1", "pii": false, "desc": "Nhà cung cấp"}';
COMMENT ON COLUMN esign.sign_request.is_batch IS '{"cls": "C1", "pii": false, "desc": "Ký lô"}';
COMMENT ON COLUMN esign.sign_request.provider_txn_id IS '{"cls": "C1", "pii": false, "desc": "Mã giao dịch phía nhà cung cấp"}';
COMMENT ON COLUMN esign.sign_request.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái"}';
COMMENT ON COLUMN esign.sign_request.expires_at IS '{"cls": "C1", "pii": false, "desc": "Hạn xác nhận trên thiết bị"}';
COMMENT ON COLUMN esign.sign_request.completed_at IS '{"cls": "C1", "pii": false, "desc": "Hoàn tất (EVT-18)"}';
COMMENT ON COLUMN esign.sign_request.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN esign.sign_request.created_by IS '{"cls": "C1", "pii": false, "desc": "Người tạo"}';
COMMENT ON COLUMN esign.sign_request.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật"}';
COMMENT ON COLUMN esign.sign_request.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật"}';
COMMENT ON COLUMN esign.sign_request.row_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản dòng (khoá lạc quan)"}';

-- -----------------------------------------------------------------------------
-- esign.sign_item
-- -----------------------------------------------------------------------------
CREATE TABLE esign.sign_item (
  id                  uuid          NOT NULL,
  tenant_id           uuid          NOT NULL,
  sign_request_id     uuid          NOT NULL,
  document_kind       varchar(24)   NOT NULL,
  document_ref        varchar(512)  NOT NULL,
  document_sha256     bytea         NOT NULL,
  signature_format    varchar(12)   NOT NULL,
  signature_value     bytea,
  signed_object_key   varchar(512),
  certificate_ref_id  uuid,
  signed_at           timestamptz,
  CONSTRAINT pk_sign_item PRIMARY KEY (id),
  CONSTRAINT fk_sign_item__sign_request FOREIGN KEY (sign_request_id) REFERENCES esign.sign_request (id),
  CONSTRAINT fk_sign_item__certificate_ref FOREIGN KEY (certificate_ref_id) REFERENCES esign.certificate_ref (id),
  CONSTRAINT ck_sign_item__signature_format CHECK (signature_format IN ('PADES_LTA', 'CADES_T', 'JWS')),
  CONSTRAINT ck_sign_item__document_sha256 CHECK (octet_length(document_sha256) = 32)
);
CREATE INDEX ix_sign_item__sign_request ON esign.sign_item (sign_request_id);
COMMENT ON TABLE esign.sign_item IS 'Từng tài liệu của yêu cầu ký: băm được ký và chữ ký nhận về';
COMMENT ON COLUMN esign.sign_item.id IS '{"cls": "C1", "pii": false, "desc": "Mã"}';
COMMENT ON COLUMN esign.sign_item.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN esign.sign_item.sign_request_id IS '{"cls": "C1", "pii": false, "desc": "Yêu cầu"}';
COMMENT ON COLUMN esign.sign_item.document_kind IS '{"cls": "C1", "pii": false, "desc": "Loại tài liệu (PDF/A, JSON chuẩn hoá)"}';
COMMENT ON COLUMN esign.sign_item.document_ref IS '{"cls": "C1", "pii": false, "desc": "Khoá đối tượng hoặc mã bản ghi"}';
COMMENT ON COLUMN esign.sign_item.document_sha256 IS '{"cls": "C1", "pii": false, "desc": "Băm được ký"}';
COMMENT ON COLUMN esign.sign_item.signature_format IS '{"cls": "C1", "pii": false, "desc": "Định dạng chữ ký"}';
COMMENT ON COLUMN esign.sign_item.signature_value IS '{"cls": "C1", "pii": false, "desc": "Chữ ký CMS/CAdES, PAdES hoặc JWS đã nhận"}';
COMMENT ON COLUMN esign.sign_item.signed_object_key IS '{"cls": "C1", "pii": false, "desc": "Tệp đã nhúng chữ ký"}';
COMMENT ON COLUMN esign.sign_item.certificate_ref_id IS '{"cls": "C1", "pii": false, "desc": "Chứng thư"}';
COMMENT ON COLUMN esign.sign_item.signed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ký"}';

-- -----------------------------------------------------------------------------
-- esign.outbox_event (cùng cột với NEN-032 db/common-business V0_20)
-- -----------------------------------------------------------------------------
CREATE TABLE esign.outbox_event (
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
CREATE TABLE esign.outbox_event_default PARTITION OF esign.outbox_event DEFAULT;
COMMENT ON TABLE esign.outbox_event IS 'Hộp thư đi giao dịch (Transactional Outbox), phân vùng ngày, giữ 3 ngày';
COMMENT ON COLUMN esign.outbox_event.id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện (eventId, UUIDv7)"}';
COMMENT ON COLUMN esign.outbox_event.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant phát sự kiện"}';
COMMENT ON COLUMN esign.outbox_event.aggregate_type IS '{"cls": "C1", "pii": false, "desc": "Loại thực thể gốc"}';
COMMENT ON COLUMN esign.outbox_event.aggregate_id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể gốc"}';
COMMENT ON COLUMN esign.outbox_event.aggregate_version IS '{"cls": "C1", "pii": false, "desc": "row_version của thực thể sau thay đổi"}';
COMMENT ON COLUMN esign.outbox_event.event_type IS '{"cls": "C1", "pii": false, "desc": "Tên nghiệp vụ của sự kiện"}';
COMMENT ON COLUMN esign.outbox_event.topic IS '{"cls": "C1", "pii": false, "desc": "Topic đích; Debezium định tuyến theo cột này"}';
COMMENT ON COLUMN esign.outbox_event.event_key IS '{"cls": "C1", "pii": false, "desc": "Khoá Kafka tenantId:aggregateId"}';
COMMENT ON COLUMN esign.outbox_event.payload IS '{"cls": "C1", "pii": false, "desc": "Nội dung Avro đã tuần tự hoá; không mang C2 dạng rõ"}';
COMMENT ON COLUMN esign.outbox_event.schema_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản lược đồ trong Apicurio"}';
COMMENT ON COLUMN esign.outbox_event.headers IS '{"cls": "C1", "pii": false, "desc": "traceparent, actorId, policyVersion, classification, occurredAt"}';
COMMENT ON COLUMN esign.outbox_event.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi; khoá phân vùng"}';

-- -----------------------------------------------------------------------------
-- esign.idempotency_key (cùng cột với NEN-032 db/common-business V0_22; giữ 48 giờ, HD-02)
-- -----------------------------------------------------------------------------
CREATE TABLE esign.idempotency_key (
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
CREATE TABLE esign.idempotency_key_default PARTITION OF esign.idempotency_key DEFAULT;
COMMENT ON TABLE esign.idempotency_key IS 'Khoá Idempotency-Key của lệnh ghi, giữ 48 giờ';
COMMENT ON COLUMN esign.idempotency_key.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant"}';
COMMENT ON COLUMN esign.idempotency_key.actor_id IS '{"cls": "C1", "pii": false, "desc": "Người hoặc dịch vụ gọi"}';
COMMENT ON COLUMN esign.idempotency_key.idem_key IS '{"cls": "C1", "pii": false, "desc": "Giá trị header Idempotency-Key"}';
COMMENT ON COLUMN esign.idempotency_key.created_day IS '{"cls": "C1", "pii": false, "desc": "Ngày tạo; khoá phân vùng"}';
COMMENT ON COLUMN esign.idempotency_key.request_hash IS '{"cls": "C1", "pii": false, "desc": "SHA-256 của yêu cầu"}';
COMMENT ON COLUMN esign.idempotency_key.status IS '{"cls": "C1", "pii": false, "desc": "DANG_XU_LY hoặc XONG"}';
COMMENT ON COLUMN esign.idempotency_key.response_code IS '{"cls": "C1", "pii": false, "desc": "Mã HTTP đã trả"}';
COMMENT ON COLUMN esign.idempotency_key.response_body IS '{"cls": "C1", "pii": false, "desc": "Phản hồi đã trả; không chứa C2"}';
COMMENT ON COLUMN esign.idempotency_key.resource_ref IS '{"cls": "C1", "pii": false, "desc": "Tài nguyên đã tạo"}';
COMMENT ON COLUMN esign.idempotency_key.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi"}';


-- -----------------------------------------------------------------------------
-- esign.inbox_processed (baseline mục 8, cùng cột với org-service V4)
-- -----------------------------------------------------------------------------
CREATE TABLE esign.inbox_processed (
  event_id         uuid          NOT NULL,
  consumer         varchar(100)  NOT NULL,
  processed_at     timestamptz   NOT NULL DEFAULT now(),
  tenant_id        uuid          NOT NULL,
  source_topic     varchar(128)  NOT NULL,
  kafka_partition  int           NOT NULL,
  kafka_offset     bigint        NOT NULL,
  CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON esign.inbox_processed (processed_at);
COMMENT ON TABLE esign.inbox_processed IS 'Hộp thư đến idempotent (baseline mục 8)';
COMMENT ON COLUMN esign.inbox_processed.event_id IS '{"cls": "C1", "pii": false, "desc": "Mã sự kiện đã xử lý"}';
COMMENT ON COLUMN esign.inbox_processed.consumer IS '{"cls": "C1", "pii": false, "desc": "Tên consumer"}';
COMMENT ON COLUMN esign.inbox_processed.processed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm xử lý; dọn sau 14 ngày"}';
COMMENT ON COLUMN esign.inbox_processed.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant của sự kiện"}';
COMMENT ON COLUMN esign.inbox_processed.source_topic IS '{"cls": "C1", "pii": false, "desc": "Topic nguồn"}';
COMMENT ON COLUMN esign.inbox_processed.kafka_partition IS '{"cls": "C1", "pii": false, "desc": "Phân vùng Kafka"}';
COMMENT ON COLUMN esign.inbox_processed.kafka_offset IS '{"cls": "C1", "pii": false, "desc": "Offset Kafka"}';

-- -----------------------------------------------------------------------------
-- Hàm cho worker thăm dò: liệt kê yêu cầu đang chờ xác nhận của mọi tenant (chỉ mã và tenant), để worker
-- xử lý từng yêu cầu trong ngữ cảnh tenant của nó với RLS đầy đủ (suy luận).
-- -----------------------------------------------------------------------------
CREATE FUNCTION esign.fn_yeu_cau_cho_xac_nhan(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = esign, pg_temp
AS $$
  SELECT r.id, r.tenant_id FROM esign.sign_request r
   WHERE r.status = 'CHO_XAC_NHAN'
   ORDER BY r.expires_at
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION esign.fn_yeu_cau_cho_xac_nhan(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION esign.fn_yeu_cau_cho_xac_nhan(int) TO svc_esign;

-- -----------------------------------------------------------------------------
-- RLS và quyền (TL-03 "Không truy cập chéo và quyền tối thiểu").
-- -----------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['certificate_ref', 'sign_request', 'sign_item', 'outbox_event', 'inbox_processed',
                           'idempotency_key'] LOOP
    EXECUTE format('ALTER TABLE esign.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE esign.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY rls_%s__tenant ON esign.%I AS PERMISSIVE FOR ALL TO svc_esign, ro_esign '
      || 'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t, t);
    EXECUTE format('REVOKE ALL ON esign.%I FROM PUBLIC', t);
    EXECUTE format('GRANT SELECT ON esign.%I TO ro_esign', t);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON esign.certificate_ref TO svc_esign;
GRANT SELECT, INSERT, UPDATE ON esign.sign_request TO svc_esign;
GRANT SELECT, INSERT, UPDATE ON esign.sign_item TO svc_esign;
GRANT INSERT ON esign.outbox_event TO svc_esign;
GRANT SELECT ON esign.outbox_event TO cdc_esign;
GRANT SELECT, INSERT ON esign.inbox_processed TO svc_esign;
GRANT SELECT, INSERT, UPDATE, DELETE ON esign.idempotency_key TO svc_esign;

-- Chủ sở hữu hàm (vai trò migration) cũng chịu FORCE RLS: cho phép đúng việc đọc yêu cầu đang chờ để hàm
-- quét của worker hoạt động, không mở thêm quyền nào khác.
CREATE POLICY rls_sign_request__quet_cho_xac_nhan ON esign.sign_request AS PERMISSIVE FOR SELECT TO CURRENT_USER
  USING (status = 'CHO_XAC_NHAN');
