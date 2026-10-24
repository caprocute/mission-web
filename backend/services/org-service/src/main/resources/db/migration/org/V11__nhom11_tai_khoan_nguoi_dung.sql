-- DV-04-09 · BS-04 ADR-34 mục 2: tài khoản đăng nhập của cán bộ do quản trị tạo trong Mission (SCR-0108, SCR-0109).
-- Bảng bổ sung (TL-03 chưa có): Keycloak giữ thông tin xác thực và thư điện tử (C2, không chép sang CSDL nghiệp vụ);
-- bảng này giữ tên đăng nhập, trạng thái và dấu vết khoá, đặt lại mật khẩu để liệt kê, lọc theo đơn vị mà không gọi
-- Keycloak cho từng người. Khoá chính là mã cán bộ (org.person.id), cũng là id người dùng Keycloak. Tài khoản đã có
-- trên Keycloak trước khi có bảng (dữ liệu thử nạp sẵn) được ghi bù ở lần đọc đầu với nguồn NAP_SAN.
CREATE TABLE org.user_account (
  tenant_id          uuid NOT NULL,
  person_id          uuid NOT NULL,
  username           varchar(64) NOT NULL,
  status             varchar(16) NOT NULL DEFAULT 'HOAT_DONG',
  locked_at          timestamptz,
  locked_by          uuid,
  lock_reason        varchar(500),
  password_reset_at  timestamptz,
  password_reset_by  uuid,
  source             varchar(16) NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid NOT NULL,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid NOT NULL,
  row_version        int NOT NULL DEFAULT 0,
  CONSTRAINT pk_user_account PRIMARY KEY (tenant_id, person_id),
  CONSTRAINT uq_user_account__username UNIQUE (tenant_id, username),
  CONSTRAINT ck_user_account__username CHECK (username ~ '^[a-z][a-z0-9._-]{2,63}$'),
  CONSTRAINT ck_user_account__status CHECK (status IN ('HOAT_DONG','KHOA')),
  CONSTRAINT ck_user_account__source CHECK (source IN ('QUAN_TRI','DONG_BO','NAP_SAN')),
  CONSTRAINT ck_user_account__lock CHECK (status <> 'KHOA' OR (locked_at IS NOT NULL AND lock_reason IS NOT NULL))
);
COMMENT ON TABLE org.user_account IS 'Tài khoản đăng nhập của cán bộ quản trị trong Mission (bảng bổ sung DV-04-09, BS-04)';
COMMENT ON COLUMN org.user_account.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.user_account.person_id IS '{"cls": "C1", "pii": false, "desc": "Mã cán bộ (org.person.id), trùng id người dùng Keycloak"}';
COMMENT ON COLUMN org.user_account.username IS '{"cls": "C1", "pii": true, "desc": "Tên đăng nhập, không đổi sau khi tạo (BR-01-19)"}';
COMMENT ON COLUMN org.user_account.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái tài khoản: HOAT_DONG, KHOA"}';
COMMENT ON COLUMN org.user_account.locked_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm khoá gần nhất"}';
COMMENT ON COLUMN org.user_account.locked_by IS '{"cls": "C1", "pii": false, "desc": "Người khoá gần nhất"}';
COMMENT ON COLUMN org.user_account.lock_reason IS '{"cls": "C1", "pii": false, "desc": "Lý do khoá (BR-01-21)"}';
COMMENT ON COLUMN org.user_account.password_reset_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm quản trị đặt lại mật khẩu gần nhất"}';
COMMENT ON COLUMN org.user_account.password_reset_by IS '{"cls": "C1", "pii": false, "desc": "Người đặt lại mật khẩu gần nhất"}';
COMMENT ON COLUMN org.user_account.source IS '{"cls": "C1", "pii": false, "desc": "Nguồn tạo: QUAN_TRI (trong ứng dụng), DONG_BO (đồng bộ ngoài), NAP_SAN (có sẵn trên Keycloak)"}';
COMMENT ON COLUMN org.user_account.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.user_account.created_by IS '{"cls": "C1", "pii": false, "desc": "Chủ thể tạo bản ghi"}';
COMMENT ON COLUMN org.user_account.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.user_account.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.user_account.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API (kết hợp với phiên bản hồ sơ cán bộ)"}';
CREATE INDEX ix_user_account__status ON org.user_account (tenant_id, status);
ALTER TABLE org.user_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.user_account FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_user_account__tenant ON org.user_account AS PERMISSIVE FOR ALL
  TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.user_account FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.user_account TO svc_org;
GRANT SELECT ON org.user_account TO ro_org;
