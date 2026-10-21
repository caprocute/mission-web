-- DV-03-01 · SVC-03 tenant-registry · bảng bổ sung và vai trò CSDL.
-- Nguồn: TL-03 "Bảng registry.tenant_status_log (bổ sung)", "Bảng registry.tenant_quota (bổ sung)",
--        "Bảng outbox_event"; "Quy ước đặt tên" (vai trò svc_/ro_/mig_<schema>).
-- Vai trò mig_registry do bước bootstrap cụm tạo trước (NEN-031) và là vai trò chạy Flyway; tệp này chỉ tạo
-- svc_registry, ro_registry nếu chưa có (NOLOGIN; mật khẩu và LOGIN do CloudNativePG/External Secrets cấp),
-- vì vậy vai trò chạy migration cần CREATEROLE (điểm chờ NEN-031: chuyển phần vai trò sang bootstrap chung).

-- ---------------------------------------------------------------- registry.tenant_status_log (chỉ ghi thêm)
CREATE TABLE registry.tenant_status_log (
  id          uuid        NOT NULL,
  tenant_id   uuid        NOT NULL,
  from_status varchar(24),
  to_status   varchar(24) NOT NULL,
  reason      text        NOT NULL,
  actor_id    uuid        NOT NULL,
  changed_at  timestamptz NOT NULL,
  CONSTRAINT pk_tenant_status_log PRIMARY KEY (id),
  CONSTRAINT fk_tenant_status_log__tenant FOREIGN KEY (tenant_id) REFERENCES registry.tenant (id),
  CONSTRAINT ck_tenant_status_log__from_status CHECK (from_status IS NULL OR from_status IN ('KHOI_TAO', 'DANG_CAP_PHAT', 'DANG_CAU_HINH', 'SAN_SANG', 'DANG_VAN_HANH', 'TAM_NGUNG', 'DANG_CHUYEN_CELL', 'DA_CHAM_DUT')),
  CONSTRAINT ck_tenant_status_log__to_status CHECK (to_status IN ('KHOI_TAO', 'DANG_CAP_PHAT', 'DANG_CAU_HINH', 'SAN_SANG', 'DANG_VAN_HANH', 'TAM_NGUNG', 'DANG_CHUYEN_CELL', 'DA_CHAM_DUT')),
  CONSTRAINT ck_tenant_status_log__reason CHECK (length(reason) BETWEEN 1 AND 2000)
);
CREATE INDEX ix_tenant_status_log__tenant_id_changed_at ON registry.tenant_status_log (tenant_id, changed_at);

COMMENT ON TABLE registry.tenant_status_log IS 'Nhật ký đổi trạng thái tenant, chỉ ghi thêm (TL-03, bảng bổ sung)';
COMMENT ON COLUMN registry.tenant_status_log.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_status_log.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_status_log.from_status IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_status_log.to_status IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_status_log.reason IS '{"cls":"C1","desc":"Lý do, số văn bản"}';
COMMENT ON COLUMN registry.tenant_status_log.actor_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_status_log.changed_at IS '{"cls":"C1"}';

-- Chặn sửa, xoá ở mức CSDL, kể cả với chủ sở hữu bảng (lớp thứ hai sau việc không cấp quyền).
CREATE FUNCTION registry.fn_reject_tenant_status_log_change() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'registry.tenant_status_log chỉ cho ghi thêm' USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER tg_tenant_status_log__no_update
  BEFORE UPDATE OR DELETE ON registry.tenant_status_log
  FOR EACH ROW EXECUTE FUNCTION registry.fn_reject_tenant_status_log_change();
CREATE TRIGGER tg_tenant_status_log__no_truncate
  BEFORE TRUNCATE ON registry.tenant_status_log
  FOR EACH STATEMENT EXECUTE FUNCTION registry.fn_reject_tenant_status_log_change();

-- ---------------------------------------------------------------- registry.tenant_quota
CREATE TABLE registry.tenant_quota (
  id             uuid        NOT NULL,
  tenant_id      uuid        NOT NULL,
  quota_key      varchar(48) NOT NULL,
  limit_value    bigint      NOT NULL,
  soft_limit_pct smallint    NOT NULL DEFAULT 85,
  used_value     bigint      NOT NULL DEFAULT 0,
  effective_from date        NOT NULL,
  effective_to   date,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid        NOT NULL,
  CONSTRAINT pk_tenant_quota PRIMARY KEY (id),
  CONSTRAINT fk_tenant_quota__tenant FOREIGN KEY (tenant_id) REFERENCES registry.tenant (id),
  CONSTRAINT uq_tenant_quota__tenant_id_quota_key UNIQUE (tenant_id, quota_key),
  CONSTRAINT ck_tenant_quota__quota_key CHECK (quota_key IN ('API_RPS', 'STORAGE_GB', 'ACCOUNTS', 'AI_TOKENS_MONTH', 'SIGN_PER_DAY', 'EXPORT_PER_DAY'))
);

COMMENT ON TABLE registry.tenant_quota IS 'Hạn mức tài nguyên theo tenant (TL-03, bảng bổ sung)';
COMMENT ON COLUMN registry.tenant_quota.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.quota_key IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.limit_value IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.soft_limit_pct IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.used_value IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.effective_from IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.effective_to IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_quota.updated_by IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- registry.outbox_event
-- Theo mẫu TL-03 "Bảng outbox_event". Schema registry không dùng RLS nên không có chính sách rls_.
-- Phân vùng ngày do pg_partman tạo ở môi trường có extension (điểm chờ hạ tầng); ở đây có phân vùng
-- mặc định để ghi được ngay khi chưa có pg_partman (ảnh postgres:17 chuẩn không kèm pg_partman).
CREATE TABLE registry.outbox_event (
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
  CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$')
) PARTITION BY RANGE (created_at);
CREATE TABLE registry.outbox_event_default PARTITION OF registry.outbox_event DEFAULT;

COMMENT ON TABLE registry.outbox_event IS 'Hộp thư đi giao dịch của SVC-03 (TL-03 outbox_event)';
COMMENT ON COLUMN registry.outbox_event.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.aggregate_type IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.aggregate_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.aggregate_version IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.event_type IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.topic IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.event_key IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.payload IS '{"cls":"C1","desc":"Payload sự kiện; không chứa trường C2"}';
COMMENT ON COLUMN registry.outbox_event.schema_version IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.headers IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.outbox_event.created_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- vai trò và quyền tối thiểu
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_registry') THEN
    CREATE ROLE svc_registry NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_registry') THEN
    CREATE ROLE ro_registry NOLOGIN NOINHERIT NOBYPASSRLS;
  END IF;
END
$$;

ALTER ROLE svc_registry SET statement_timeout = '5s';
ALTER ROLE svc_registry SET idle_in_transaction_session_timeout = '10s';
ALTER ROLE svc_registry SET search_path = registry;
ALTER ROLE ro_registry SET search_path = registry;

REVOKE ALL ON SCHEMA registry FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA registry FROM PUBLIC;
GRANT USAGE ON SCHEMA registry TO svc_registry, ro_registry;

-- svc_registry: chỉ DML cần thiết, không DDL (không sở hữu đối tượng, không có CREATE trên schema).
GRANT SELECT, INSERT, UPDATE ON registry.tenant, registry.cell, registry.tenant_cell, registry.tenant_quota TO svc_registry;
GRANT SELECT, INSERT, UPDATE, DELETE ON registry.tenant_domain, registry.tenant_idp TO svc_registry;
-- Nhật ký trạng thái: chỉ đọc và ghi thêm.
GRANT SELECT, INSERT ON registry.tenant_status_log TO svc_registry;
-- Outbox: chỉ ghi thêm; Debezium đọc WAL bằng vai trò cdc_registry riêng (ngoài phạm vi).
GRANT INSERT ON registry.outbox_event TO svc_registry;

-- ro_registry: chỉ đọc danh bạ (không đọc outbox).
GRANT SELECT ON registry.tenant, registry.cell, registry.tenant_cell, registry.tenant_domain,
  registry.tenant_idp, registry.tenant_status_log, registry.tenant_quota TO ro_registry;
