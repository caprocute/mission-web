-- Sinh từ đặc tả cột TL-03 "Schema org (SVC-04 org-service)" (dòng 1046–1328) và chương "Dữ liệu tổ chức
-- hai trục thời gian" (dòng 4020–4086). Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
-- Vai trò mig_org, svc_org, ro_org, cdc_org và schema org do bước khởi tạo cụm tạo (TL-03 "Không truy cập chéo và
-- quyền tối thiểu"); migration chạy bằng mig_org.

-- Nhóm 2 (T-DB-O2): nhân sự, liên lạc, khoá công khai, vị trí việc làm.
-- Cột dek_id tham chiếu keyring.dek: schema keyring thuộc NEN-032 chưa có, nên chưa đặt khoá ngoại vật lý
-- (điểm chờ nền móng; bổ sung bằng migration expand khi keyring có mặt).

CREATE TABLE org.person (
  version_id       uuid NOT NULL,
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  full_name        varchar(200) NOT NULL,
  gender           varchar(8),
  birth_date_enc   bytea,
  national_id_enc  bytea,
  national_id_bidx bytea,
  party_member_no_enc bytea,
  party_member_no_bidx bytea,
  is_party_member  boolean NOT NULL DEFAULT false,
  cbccvc_code      varchar(32),
  employment_status varchar(16) NOT NULL,
  dek_id           uuid,
  change_order_id  uuid,
  valid_from       date NOT NULL,
  valid_to         date NOT NULL DEFAULT 'infinity',
  recorded_from    timestamptz NOT NULL DEFAULT now(),
  recorded_to      timestamptz NOT NULL DEFAULT 'infinity',
  valid_period     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  recorded_period  tstzrange GENERATED ALWAYS AS (tstzrange(recorded_from, recorded_to, '[)')) STORED,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_person PRIMARY KEY (version_id),
  CONSTRAINT ck_person__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_person__recorded CHECK (recorded_from < recorded_to),
  CONSTRAINT fk_person__org_change_order FOREIGN KEY (change_order_id) REFERENCES org.org_change_order (id),
  -- Một thực thể (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_person__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ),
  CONSTRAINT ck_person__gender CHECK (gender IN ('NAM','NU','KHAC')),
  CONSTRAINT ck_person__employment_status CHECK (employment_status IN ('DANG_CONG_TAC','NGHI_HUU','THOI_VIEC','TAM_NGHI')),
  -- Cột _enc chỉ hợp lệ khi có khoá dữ liệu đi kèm
  CONSTRAINT ck_person__dek CHECK (dek_id IS NOT NULL OR (birth_date_enc IS NULL AND national_id_enc IS NULL AND party_member_no_enc IS NULL))
);
COMMENT ON TABLE org.person IS 'Nhân sự, hai trục thời gian; cột C2 mã hoá trường';
COMMENT ON COLUMN org.person.version_id IS '{"cls": "C1", "pii": false, "desc": "Mã phiên bản dòng (khoá chính)"}';
COMMENT ON COLUMN org.person.id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể ổn định mà nơi khác tham chiếu"}';
COMMENT ON COLUMN org.person.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.person.full_name IS '{"cls": "C1", "pii": true, "desc": "Họ và tên (dữ liệu cá nhân cơ bản, bảo vệ bằng RLS, ABAC)"}';
COMMENT ON COLUMN org.person.gender IS '{"cls": "C1", "pii": true, "desc": "Giới tính"}';
COMMENT ON COLUMN org.person.birth_date_enc IS '{"cls": "C2", "pii": true, "desc": "Ngày sinh, mã hoá trường"}';
COMMENT ON COLUMN org.person.national_id_enc IS '{"cls": "C2", "pii": true, "desc": "Số định danh cá nhân, mã hoá trường"}';
COMMENT ON COLUMN org.person.national_id_bidx IS '{"cls": "C1", "pii": false, "desc": "Chỉ mục mù HMAC số định danh để tra cứu chính xác khi đối soát"}';
COMMENT ON COLUMN org.person.party_member_no_enc IS '{"cls": "C2", "pii": true, "desc": "Số thẻ đảng viên, mã hoá trường"}';
COMMENT ON COLUMN org.person.party_member_no_bidx IS '{"cls": "C1", "pii": false, "desc": "Chỉ mục mù số thẻ đảng viên"}';
COMMENT ON COLUMN org.person.is_party_member IS '{"cls": "C1", "pii": false, "desc": "Là đảng viên (xác định luồng DANG)"}';
COMMENT ON COLUMN org.person.cbccvc_code IS '{"cls": "C1", "pii": false, "desc": "Mã công chức, viên chức trong CSDL quốc gia"}';
COMMENT ON COLUMN org.person.employment_status IS '{"cls": "C1", "pii": false, "desc": "Tình trạng công tác"}';
COMMENT ON COLUMN org.person.dek_id IS '{"cls": "C1", "pii": false, "desc": "Khoá dữ liệu dùng cho các cột _enc của phiên bản (keyring.dek)"}';
COMMENT ON COLUMN org.person.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức cán bộ sinh ra phiên bản (org.org_change_order)"}';
COMMENT ON COLUMN org.person.valid_from IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày bắt đầu (bao gồm)"}';
COMMENT ON COLUMN org.person.valid_to IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày kết thúc (không bao gồm); infinity là còn hiệu lực"}';
COMMENT ON COLUMN org.person.recorded_from IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: hệ thống biết phiên bản từ thời điểm này"}';
COMMENT ON COLUMN org.person.recorded_to IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: phiên bản bị thay thế từ thời điểm này; infinity là hiểu biết hiện tại"}';
COMMENT ON COLUMN org.person.valid_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục hiệu lực, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.person.recorded_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục ghi nhận, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.person.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.person.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.person.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.person.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.person.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_person__current_p ON org.person (tenant_id, id) WHERE recorded_to = 'infinity';
CREATE INDEX ix_person__national_id_bidx ON org.person (tenant_id, national_id_bidx);
ALTER TABLE org.person ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.person FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_person__tenant ON org.person AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.person FROM PUBLIC;
-- Bảng hai trục: không sửa cột nghiệp vụ, không xoá; chỉ đóng phiên bản (TL-03 chương 7)
GRANT SELECT, INSERT ON org.person TO svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.person TO svc_org;
GRANT SELECT ON org.person TO ro_org;

CREATE TABLE org.person_contact (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  person_id        uuid NOT NULL,
  phone_enc        bytea,
  phone_bidx       bytea,
  email_enc        bytea,
  email_bidx       bytea,
  dek_id           uuid NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_person_contact PRIMARY KEY (id),
  CONSTRAINT uq_person_contact__person UNIQUE (tenant_id, person_id)
);
COMMENT ON TABLE org.person_contact IS 'Thông tin liên lạc C2 tách khỏi org.person (bảng bổ sung)';
COMMENT ON COLUMN org.person_contact.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.person_contact.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.person_contact.person_id IS '{"cls": "C1", "pii": false, "desc": "Người (org.person.id)"}';
COMMENT ON COLUMN org.person_contact.phone_enc IS '{"cls": "C2", "pii": true, "desc": "Số điện thoại di động, mã hoá trường"}';
COMMENT ON COLUMN org.person_contact.phone_bidx IS '{"cls": "C1", "pii": false, "desc": "Chỉ mục mù số điện thoại"}';
COMMENT ON COLUMN org.person_contact.email_enc IS '{"cls": "C2", "pii": true, "desc": "Thư điện tử công vụ, mã hoá trường"}';
COMMENT ON COLUMN org.person_contact.email_bidx IS '{"cls": "C1", "pii": false, "desc": "Chỉ mục mù thư điện tử, dùng khi SCIM tra theo userName"}';
COMMENT ON COLUMN org.person_contact.dek_id IS '{"cls": "C1", "pii": false, "desc": "Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN org.person_contact.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.person_contact.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.person_contact.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.person_contact.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.person_contact.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_person_contact__email_bidx ON org.person_contact (tenant_id, email_bidx);
ALTER TABLE org.person_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.person_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_person_contact__tenant ON org.person_contact AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.person_contact FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.person_contact TO svc_org;
GRANT SELECT ON org.person_contact TO ro_org;

CREATE TABLE org.person_public_key (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  person_id        uuid NOT NULL,
  key_alg          varchar(24) NOT NULL,
  public_key_spki  bytea NOT NULL,
  fingerprint      bytea NOT NULL,
  source           varchar(12) NOT NULL,
  valid_from       timestamptz NOT NULL,
  valid_to         timestamptz NOT NULL,
  status           varchar(12) NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_person_public_key PRIMARY KEY (id),
  CONSTRAINT uq_person_public_key__fingerprint UNIQUE (tenant_id, fingerprint),
  CONSTRAINT ck_person_public_key__key_alg CHECK (key_alg IN ('ECDH-P256','RSA-OAEP-3072','BCY')),
  CONSTRAINT ck_person_public_key__source CHECK (source IN ('CHUNG_THU','THIET_BI')),
  CONSTRAINT ck_person_public_key__status CHECK (status IN ('HIEU_LUC','THU_HOI','HET_HAN'))
);
COMMENT ON TABLE org.person_public_key IS 'Khoá công khai của người dùng phục vụ mã hoá đầu cuối C3 (bảng bổ sung)';
COMMENT ON COLUMN org.person_public_key.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.person_public_key.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.person_public_key.person_id IS '{"cls": "C1", "pii": false, "desc": "Chủ khoá"}';
COMMENT ON COLUMN org.person_public_key.key_alg IS '{"cls": "C1", "pii": false, "desc": "Thuật toán khoá công khai"}';
COMMENT ON COLUMN org.person_public_key.public_key_spki IS '{"cls": "C0", "pii": false, "desc": "Khoá công khai dạng SubjectPublicKeyInfo DER"}';
COMMENT ON COLUMN org.person_public_key.fingerprint IS '{"cls": "C0", "pii": false, "desc": "SHA-256 của SPKI"}';
COMMENT ON COLUMN org.person_public_key.source IS '{"cls": "C1", "pii": false, "desc": "Lấy từ chứng thư số hoặc sinh trên thiết bị và được chứng thực"}';
COMMENT ON COLUMN org.person_public_key.valid_from IS '{"cls": "C1", "pii": false, "desc": "Hiệu lực khoá từ"}';
COMMENT ON COLUMN org.person_public_key.valid_to IS '{"cls": "C1", "pii": false, "desc": "Hiệu lực khoá đến"}';
COMMENT ON COLUMN org.person_public_key.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái khoá"}';
COMMENT ON COLUMN org.person_public_key.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.person_public_key.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.person_public_key.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.person_public_key.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.person_public_key.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_person_public_key__person ON org.person_public_key (tenant_id, person_id);
ALTER TABLE org.person_public_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.person_public_key FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_person_public_key__tenant ON org.person_public_key AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.person_public_key FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.person_public_key TO svc_org;
GRANT SELECT ON org.person_public_key TO ro_org;

CREATE TABLE org.position (
  version_id       uuid NOT NULL,
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  org_unit_id      uuid NOT NULL,
  code             varchar(50) NOT NULL,
  name             varchar(255) NOT NULL,
  is_leadership    boolean NOT NULL DEFAULT false,
  default_catalog_id uuid,
  headcount        smallint NOT NULL DEFAULT 1,
  change_order_id  uuid,
  valid_from       date NOT NULL,
  valid_to         date NOT NULL DEFAULT 'infinity',
  recorded_from    timestamptz NOT NULL DEFAULT now(),
  recorded_to      timestamptz NOT NULL DEFAULT 'infinity',
  valid_period     daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
  recorded_period  tstzrange GENERATED ALWAYS AS (tstzrange(recorded_from, recorded_to, '[)')) STORED,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_position PRIMARY KEY (version_id),
  CONSTRAINT ck_position__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_position__recorded CHECK (recorded_from < recorded_to),
  CONSTRAINT fk_position__org_change_order FOREIGN KEY (change_order_id) REFERENCES org.org_change_order (id),
  -- Một thực thể (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_position__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  )
);
COMMENT ON TABLE org.position IS 'Vị trí việc làm, hai trục thời gian';
COMMENT ON COLUMN org.position.version_id IS '{"cls": "C1", "pii": false, "desc": "Mã phiên bản dòng (khoá chính)"}';
COMMENT ON COLUMN org.position.id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể ổn định mà nơi khác tham chiếu"}';
COMMENT ON COLUMN org.position.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.position.org_unit_id IS '{"cls": "C1", "pii": false, "desc": "Đơn vị có vị trí (org.org_unit.id)"}';
COMMENT ON COLUMN org.position.code IS '{"cls": "C0", "pii": false, "desc": "Mã vị trí việc làm"}';
COMMENT ON COLUMN org.position.name IS '{"cls": "C0", "pii": false, "desc": "Tên vị trí việc làm"}';
COMMENT ON COLUMN org.position.is_leadership IS '{"cls": "C1", "pii": false, "desc": "Vị trí lãnh đạo, quản lý (đánh giá quý bắt buộc)"}';
COMMENT ON COLUMN org.position.default_catalog_id IS '{"cls": "C1", "pii": false, "desc": "Danh mục công việc chuẩn của vị trí (tham chiếu logic task.work_catalog.id)"}';
COMMENT ON COLUMN org.position.headcount IS '{"cls": "C1", "pii": false, "desc": "Số người theo đề án vị trí việc làm"}';
COMMENT ON COLUMN org.position.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức cán bộ sinh ra phiên bản (org.org_change_order)"}';
COMMENT ON COLUMN org.position.valid_from IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày bắt đầu (bao gồm)"}';
COMMENT ON COLUMN org.position.valid_to IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày kết thúc (không bao gồm); infinity là còn hiệu lực"}';
COMMENT ON COLUMN org.position.recorded_from IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: hệ thống biết phiên bản từ thời điểm này"}';
COMMENT ON COLUMN org.position.recorded_to IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: phiên bản bị thay thế từ thời điểm này; infinity là hiểu biết hiện tại"}';
COMMENT ON COLUMN org.position.valid_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục hiệu lực, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.position.recorded_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục ghi nhận, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.position.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.position.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.position.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.position.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.position.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_position__unit_current_p ON org.position (tenant_id, org_unit_id) WHERE recorded_to = 'infinity';
ALTER TABLE org.position ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.position FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_position__tenant ON org.position AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.position FROM PUBLIC;
-- Bảng hai trục: không sửa cột nghiệp vụ, không xoá; chỉ đóng phiên bản (TL-03 chương 7)
GRANT SELECT, INSERT ON org.position TO svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.position TO svc_org;
GRANT SELECT ON org.position TO ro_org;

