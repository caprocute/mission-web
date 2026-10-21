-- DV-03-01 · SVC-03 tenant-registry · schema `registry` (toàn cục).
-- Nguồn: TL-03 "Schema registry (toàn cục, SVC-03)" (bảng tenant, cell, tenant_cell, tenant_domain, tenant_idp),
--        "Quy ước đặt tên", "Kiểu dữ liệu chuẩn"; baseline mục 6, 9; ADR-07.
-- Quy ước: Flyway chạy bằng vai trò mig_registry với defaultSchema = registry (NEN-030). Chỉ thêm mới.
-- Chú thích cột theo dạng JSON {"cls": "C0|C1"} để lint phân loại NEN-030 đọc được.
-- Schema registry không có RLS: chính nó định nghĩa tenant (TL-03), quyền giới hạn cho SVC-03.

CREATE SCHEMA IF NOT EXISTS registry;

-- Ràng buộc loại trừ trên (uuid WITH =, tstzrange WITH &&) cần btree_gist (extension tin cậy từ PG 13).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- registry.tenant
CREATE TABLE registry.tenant (
  id                uuid         NOT NULL,
  code              varchar(32)  NOT NULL,
  name              varchar(255) NOT NULL,
  sector            varchar(24)  NOT NULL,
  tenancy_model     varchar(8)   NOT NULL,
  status            varchar(24)  NOT NULL,
  contract_ref      varchar(100),
  contract_valid_to date,
  max_accounts      int          NOT NULL,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  created_by        uuid         NOT NULL,
  updated_at        timestamptz  NOT NULL DEFAULT now(),
  updated_by        uuid         NOT NULL,
  row_version       int          NOT NULL DEFAULT 0,
  onboarding_code   varchar(32),
  flows             varchar(12)[] NOT NULL,
  expected_accounts int,
  account_count     int          NOT NULL DEFAULT 0,
  kek_ref           varchar(200) NOT NULL,
  CONSTRAINT pk_tenant PRIMARY KEY (id),
  CONSTRAINT uq_tenant__code UNIQUE (code),
  CONSTRAINT uq_tenant__onboarding_code UNIQUE (onboarding_code),
  CONSTRAINT ck_tenant__code CHECK (code ~ '^[a-z0-9_]+$'),
  CONSTRAINT ck_tenant__sector CHECK (sector IN ('DANG', 'CHINH_QUYEN', 'SU_NGHIEP', 'TRUONG_HOC', 'BENH_VIEN', 'LLVT', 'DOANH_NGHIEP')),
  CONSTRAINT ck_tenant__tenancy_model CHECK (tenancy_model IN ('SILO', 'BRIDGE', 'POOL', 'AIRGAP')),
  CONSTRAINT ck_tenant__status CHECK (status IN ('KHOI_TAO', 'DANG_CAP_PHAT', 'DANG_CAU_HINH', 'SAN_SANG', 'DANG_VAN_HANH', 'TAM_NGUNG', 'DANG_CHUYEN_CELL', 'DA_CHAM_DUT')),
  CONSTRAINT ck_tenant__flows CHECK (flows <@ ARRAY['DANG', 'NHA_NUOC']::varchar(12)[]),
  CONSTRAINT ck_tenant__max_accounts CHECK (max_accounts > 0)
);

COMMENT ON TABLE registry.tenant IS 'Danh bạ tenant toàn cục (TL-03 registry.tenant)';
COMMENT ON COLUMN registry.tenant.id IS '{"cls":"C1","desc":"Mã tenant, dùng làm tenant_id ở mọi cell"}';
COMMENT ON COLUMN registry.tenant.code IS '{"cls":"C0","desc":"Mã ngắn, dùng trong URN và tên CSDL td_<code>"}';
COMMENT ON COLUMN registry.tenant.name IS '{"cls":"C0","desc":"Tên khách hàng"}';
COMMENT ON COLUMN registry.tenant.sector IS '{"cls":"C1","desc":"Nhóm khách hàng"}';
COMMENT ON COLUMN registry.tenant.tenancy_model IS '{"cls":"C1","desc":"Mô hình thuê bao"}';
COMMENT ON COLUMN registry.tenant.status IS '{"cls":"C1","desc":"Trạng thái vòng đời tenant"}';
COMMENT ON COLUMN registry.tenant.contract_ref IS '{"cls":"C1","desc":"Số hợp đồng"}';
COMMENT ON COLUMN registry.tenant.contract_valid_to IS '{"cls":"C1","desc":"Ngày hết hạn hợp đồng"}';
COMMENT ON COLUMN registry.tenant.max_accounts IS '{"cls":"C1","desc":"Hạn mức tài khoản theo hợp đồng"}';
COMMENT ON COLUMN registry.tenant.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant.row_version IS '{"cls":"C1","desc":"Khoá lạc quan, ETag"}';
COMMENT ON COLUMN registry.tenant.onboarding_code IS '{"cls":"C1","desc":"Mã hồ sơ khởi tạo ONB-yyyy-nnnn"}';
COMMENT ON COLUMN registry.tenant.flows IS '{"cls":"C1","desc":"Luồng đánh giá"}';
COMMENT ON COLUMN registry.tenant.expected_accounts IS '{"cls":"C1","desc":"Số tài khoản dự kiến"}';
COMMENT ON COLUMN registry.tenant.account_count IS '{"cls":"C1","desc":"Số tài khoản hiện có, đồng bộ từ cell"}';
COMMENT ON COLUMN registry.tenant.kek_ref IS '{"cls":"C1","desc":"Tên KEK của tenant trong OpenBao Transit"}';

-- ---------------------------------------------------------------- registry.cell
CREATE TABLE registry.cell (
  id                uuid         NOT NULL,
  code              varchar(16)  NOT NULL,
  name              varchar(255) NOT NULL,
  primary_dc        varchar(16)  NOT NULL,
  standby_dc        varchar(16),
  tenancy_model     varchar(8)   NOT NULL,
  api_base_url      varchar(255) NOT NULL,
  capacity_accounts int          NOT NULL,
  status            varchar(16)  NOT NULL,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  created_by        uuid         NOT NULL,
  updated_at        timestamptz  NOT NULL DEFAULT now(),
  updated_by        uuid         NOT NULL,
  row_version       int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_cell PRIMARY KEY (id),
  CONSTRAINT uq_cell__code UNIQUE (code),
  CONSTRAINT ck_cell__primary_dc CHECK (primary_dc IN ('HOALAC', 'PHIANAM', 'TAICHO')),
  CONSTRAINT ck_cell__tenancy_model CHECK (tenancy_model IN ('SILO', 'BRIDGE', 'POOL', 'AIRGAP')),
  CONSTRAINT ck_cell__status CHECK (status IN ('HOAT_DONG', 'BAO_TRI', 'NGUNG'))
);

COMMENT ON TABLE registry.cell IS 'Cell của nền tảng (TL-03 registry.cell, baseline mục 6)';
COMMENT ON COLUMN registry.cell.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.code IS '{"cls":"C0","desc":"Mã cell theo baseline mục 6"}';
COMMENT ON COLUMN registry.cell.name IS '{"cls":"C0"}';
COMMENT ON COLUMN registry.cell.primary_dc IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.standby_dc IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.tenancy_model IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.api_base_url IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.capacity_accounts IS '{"cls":"C1","desc":"Sức chứa thiết kế"}';
COMMENT ON COLUMN registry.cell.status IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.cell.row_version IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- registry.tenant_cell
CREATE TABLE registry.tenant_cell (
  id            uuid        NOT NULL,
  tenant_id     uuid        NOT NULL,
  cell_id       uuid        NOT NULL,
  database_name varchar(63) NOT NULL,
  valid_from    timestamptz NOT NULL,
  valid_to      timestamptz NOT NULL DEFAULT 'infinity',
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid        NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid        NOT NULL,
  row_version   int         NOT NULL DEFAULT 0,
  tenancy_model varchar(8)  NOT NULL,
  data_region   varchar(32) NOT NULL,
  CONSTRAINT pk_tenant_cell PRIMARY KEY (id),
  CONSTRAINT fk_tenant_cell__tenant FOREIGN KEY (tenant_id) REFERENCES registry.tenant (id),
  CONSTRAINT fk_tenant_cell__cell FOREIGN KEY (cell_id) REFERENCES registry.cell (id),
  CONSTRAINT ck_tenant_cell__tenancy_model CHECK (tenancy_model IN ('silo', 'bridge', 'pool')),
  CONSTRAINT ck_tenant_cell__valid_range CHECK (valid_from < valid_to),
  CONSTRAINT ex_tenant_cell__no_overlap EXCLUDE USING gist (
    tenant_id WITH =,
    tstzrange(valid_from, valid_to) WITH &&
  )
);
CREATE INDEX ix_tenant_cell__cell_id ON registry.tenant_cell (cell_id);

COMMENT ON TABLE registry.tenant_cell IS 'Gắn tenant vào cell có thời gian hiệu lực (TL-03 registry.tenant_cell)';
COMMENT ON COLUMN registry.tenant_cell.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.cell_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.database_name IS '{"cls":"C1","desc":"CSDL dữ liệu tenant, ví dụ td_hcm, td_pool"}';
COMMENT ON COLUMN registry.tenant_cell.valid_from IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.valid_to IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_cell.tenancy_model IS '{"cls":"C1","desc":"Mô hình trong lần gắn cell (chữ thường theo TL-03)"}';
COMMENT ON COLUMN registry.tenant_cell.data_region IS '{"cls":"C1","desc":"Vùng đặt dữ liệu"}';

-- ---------------------------------------------------------------- registry.tenant_domain
CREATE TABLE registry.tenant_domain (
  id           uuid         NOT NULL,
  tenant_id    uuid         NOT NULL,
  domain       varchar(253) NOT NULL,
  kind         varchar(8)   NOT NULL,
  tls_cert_ref varchar(200),
  verified_at  timestamptz,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  created_by   uuid         NOT NULL,
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  updated_by   uuid         NOT NULL,
  row_version  int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_tenant_domain PRIMARY KEY (id),
  CONSTRAINT fk_tenant_domain__tenant FOREIGN KEY (tenant_id) REFERENCES registry.tenant (id),
  CONSTRAINT uq_tenant_domain__domain UNIQUE (domain),
  CONSTRAINT ck_tenant_domain__kind CHECK (kind IN ('PRIMARY', 'ALIAS', 'EMBED'))
);
CREATE INDEX ix_tenant_domain__tenant_id ON registry.tenant_domain (tenant_id);

COMMENT ON TABLE registry.tenant_domain IS 'Tên miền của tenant (TL-03 registry.tenant_domain)';
COMMENT ON COLUMN registry.tenant_domain.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.domain IS '{"cls":"C0"}';
COMMENT ON COLUMN registry.tenant_domain.kind IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.tls_cert_ref IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.verified_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_domain.row_version IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- registry.tenant_idp
CREATE TABLE registry.tenant_idp (
  id            uuid         NOT NULL,
  tenant_id     uuid         NOT NULL,
  idp_alias     varchar(64)  NOT NULL,
  protocol      varchar(8)   NOT NULL,
  issuer        varchar(255) NOT NULL,
  is_vneid      boolean      NOT NULL DEFAULT false,
  email_domains text[]       NOT NULL DEFAULT '{}',
  status        varchar(16)  NOT NULL,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  created_by    uuid         NOT NULL,
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  updated_by    uuid         NOT NULL,
  row_version   int          NOT NULL DEFAULT 0,
  CONSTRAINT pk_tenant_idp PRIMARY KEY (id),
  CONSTRAINT fk_tenant_idp__tenant FOREIGN KEY (tenant_id) REFERENCES registry.tenant (id),
  CONSTRAINT uq_tenant_idp__tenant_id_idp_alias UNIQUE (tenant_id, idp_alias),
  CONSTRAINT ck_tenant_idp__protocol CHECK (protocol IN ('OIDC', 'SAML')),
  CONSTRAINT ck_tenant_idp__status CHECK (status IN ('HOAT_DONG', 'TAM_DUNG'))
);

COMMENT ON TABLE registry.tenant_idp IS 'IdP liên kết của tenant (TL-03 registry.tenant_idp)';
COMMENT ON COLUMN registry.tenant_idp.id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.idp_alias IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.protocol IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.issuer IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.is_vneid IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.email_domains IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.status IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN registry.tenant_idp.row_version IS '{"cls":"C1"}';
