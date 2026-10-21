-- DV-03-03 · SVC-03 tenant-registry · khoá chống ghi trùng, bản nháp khởi tạo tenant, tác vụ dài.
-- Nguồn: TL-03 "Bảng idempotency_key" (mẫu dùng chung mọi schema); TL-02a QT-01, SCR-0102 (các trường wizard
-- không có cột trong registry.tenant); contracts/openapi/modules/config.yaml (ConfigTenant, TenantKekMode,
-- TenantPolicyBinding, TenantStaffSource, TenantAdminContact), common.yaml (Job, JobStatus).
-- Hai bảng registry.tenant_onboarding và registry.job là bảng bổ sung (suy luận), cần đưa vào danh sách bổ
-- sung của TL-03 (_ghi-chu-TL-03.md) ở lần phát hành sau.

-- Tìm kiếm không phân biệt dấu cho listConfigTenants (q). unaccent là extension tin cậy (PG 13+).
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;

-- ---------------------------------------------------------------- registry.idempotency_key
-- Theo TL-03: PK (tenant_id, actor_id, idem_key, created_day), phân vùng ngày, giữ 48 giờ. Phân vùng ngày do
-- pg_partman tạo ở môi trường có extension (điểm chờ hạ tầng); phân vùng mặc định cho môi trường chưa có.
-- tenant_id là tenant của người gọi (claim tenant), hoặc 00000000-0000-0000-0000-000000000000 với tác nhân toàn
-- cục như quản trị nền tảng (suy luận: registry không có ngữ cảnh tenant RLS).
CREATE TABLE registry.idempotency_key (
  tenant_id     uuid         NOT NULL,
  actor_id      uuid         NOT NULL,
  idem_key      varchar(64)  NOT NULL,
  created_day   date         NOT NULL,
  request_hash  bytea        NOT NULL,
  status        varchar(12)  NOT NULL,
  response_code smallint,
  response_body jsonb,
  resource_ref  varchar(128),
  created_at    timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_idempotency_key PRIMARY KEY (tenant_id, actor_id, idem_key, created_day),
  CONSTRAINT ck_idempotency_key__status CHECK (status IN ('DANG_XU_LY', 'XONG'))
) PARTITION BY RANGE (created_day);
CREATE TABLE registry.idempotency_key_default PARTITION OF registry.idempotency_key DEFAULT;

COMMENT ON TABLE registry.idempotency_key IS 'Khoá chống ghi trùng 48 giờ (TL-03 idempotency_key)';
COMMENT ON COLUMN registry.idempotency_key.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.actor_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.idem_key IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.created_day IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.request_hash IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.status IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.response_code IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.response_body IS '{"cls":"C1","desc":"Phản hồi đã trả; trường C2 bị lược bỏ"}';
COMMENT ON COLUMN registry.idempotency_key.resource_ref IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.idempotency_key.created_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- registry.tenant_onboarding (bổ sung, suy luận)
-- Các trường wizard SCR-0102 chưa có cột trong TL-03: số đơn vị dự kiến (trường 4), vùng dữ liệu trước khi chọn
-- cell (trường 8), loại khoá (trường 9), Policy Pack gốc theo luồng (trường 10, bản nháp của
-- policy.policy_binding), UI Pack (trường 11), nguồn nhân sự (trường 13), đầu mối quản trị (QT-01 bước 1; C2,
-- mã hoá ứng dụng).
CREATE TABLE registry.tenant_onboarding (
  id                 uuid        NOT NULL,
  tenant_id          uuid        NOT NULL,
  expected_units     int,
  data_region        varchar(32),
  kek_mode           varchar(16) NOT NULL,
  policy_bindings    jsonb       NOT NULL DEFAULT '[]'::jsonb,
  ui_pack_version_id uuid,
  staff_sources      jsonb       NOT NULL DEFAULT '[]'::jsonb,
  admin_contact_enc  bytea,
  dek_id             uuid,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid        NOT NULL,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid        NOT NULL,
  row_version        int         NOT NULL DEFAULT 0,
  CONSTRAINT pk_tenant_onboarding PRIMARY KEY (id),
  CONSTRAINT fk_tenant_onboarding__tenant FOREIGN KEY (tenant_id) REFERENCES registry.tenant (id),
  CONSTRAINT uq_tenant_onboarding__tenant_id UNIQUE (tenant_id),
  CONSTRAINT ck_tenant_onboarding__kek_mode CHECK (kek_mode IN ('RIENG', 'DUNG_CHUNG_CELL')),
  CONSTRAINT ck_tenant_onboarding__expected_units CHECK (expected_units IS NULL OR expected_units > 0),
  CONSTRAINT ck_tenant_onboarding__admin_contact_dek CHECK ((admin_contact_enc IS NULL) = (dek_id IS NULL))
);

COMMENT ON TABLE registry.tenant_onboarding IS 'Bản nháp wizard khởi tạo tenant SCR-0102 (bảng bổ sung, suy luận)';
COMMENT ON COLUMN registry.tenant_onboarding.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.expected_units IS '{"cls":"C1","desc":"Số đơn vị dự kiến"}';
COMMENT ON COLUMN registry.tenant_onboarding.data_region IS '{"cls":"C1","desc":"Vùng lưu dữ liệu đã chọn"}';
COMMENT ON COLUMN registry.tenant_onboarding.kek_mode IS '{"cls":"C1","desc":"Khoá riêng hoặc dùng chung của cell"}';
COMMENT ON COLUMN registry.tenant_onboarding.policy_bindings IS '{"cls":"C1","desc":"Policy Pack gốc theo luồng (nháp)"}';
COMMENT ON COLUMN registry.tenant_onboarding.ui_pack_version_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.staff_sources IS '{"cls":"C1","desc":"Nguồn nhân sự"}';
COMMENT ON COLUMN registry.tenant_onboarding.admin_contact_enc IS '{"cls":"C2","desc":"Đầu mối quản trị tenant, mã hoá ứng dụng"}';
COMMENT ON COLUMN registry.tenant_onboarding.dek_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_onboarding.row_version IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- registry.job (bổ sung, suy luận)
-- Tác vụ dài của SVC-03 (xuất danh sách tenant, 202 + Location: /api/v1/jobs/{id}); trạng thái theo JobStatus.
CREATE TABLE registry.job (
  id                  uuid         NOT NULL,
  job_type            varchar(32)  NOT NULL,
  status              varchar(16)  NOT NULL,
  progress            smallint     NOT NULL DEFAULT 0,
  message             text,
  params              jsonb        NOT NULL DEFAULT '{}'::jsonb,
  result_name         varchar(200),
  result_content_type varchar(100),
  result_data         bytea,
  result_location     varchar(512),
  problem             jsonb,
  created_at          timestamptz  NOT NULL DEFAULT now(),
  created_by          uuid         NOT NULL,
  updated_at          timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_job PRIMARY KEY (id),
  CONSTRAINT ck_job__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI', 'DA_HUY')),
  CONSTRAINT ck_job__progress CHECK (progress BETWEEN 0 AND 100)
);
CREATE INDEX ix_job__created_by ON registry.job (created_by);

COMMENT ON TABLE registry.job IS 'Tác vụ dài của SVC-03 (bảng bổ sung, suy luận)';
COMMENT ON COLUMN registry.job.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.job_type IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.status IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.progress IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.message IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.params IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.result_name IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.result_content_type IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.result_data IS '{"cls":"C1","desc":"Tệp kết quả (danh sách tenant, không chứa C2)"}';
COMMENT ON COLUMN registry.job.result_location IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.problem IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.job.updated_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- quyền
REVOKE ALL ON registry.idempotency_key, registry.tenant_onboarding, registry.job FROM PUBLIC;
-- Khoá chống ghi trùng: dọn khoá quá 48 giờ bằng DELETE khi chưa có pg_partman.
GRANT SELECT, INSERT, UPDATE, DELETE ON registry.idempotency_key TO svc_registry;
GRANT SELECT, INSERT, UPDATE ON registry.tenant_onboarding, registry.job TO svc_registry;
GRANT SELECT ON registry.tenant_onboarding TO ro_registry;
