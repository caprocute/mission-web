-- DV-04-06 · QT-01 tác vụ nền, SVC-04 SCIM 2.0 (RFC 7643, RFC 7644): bảng ánh xạ tài nguyên SCIM của từng nguồn
-- sang người và đơn vị nội bộ (bảng bổ sung, TL-03 chỉ có org.scim_sync_log; suy luận, ghi ở wiki trang 14).
-- Mã "id" công bố cho client SCIM là org.scim_resource.id, ổn định theo cặp nguồn và externalId. Số định danh và
-- ngày sinh không nằm ở đây: chúng chỉ ghi vào org.person dạng chỉ mục mù và bản mã (BR-01-12).
CREATE TABLE org.scim_resource (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  source_system    varchar(32) NOT NULL,
  resource_type    varchar(8) NOT NULL,
  external_id      varchar(128) NOT NULL,
  internal_id      uuid NOT NULL,
  user_name        varchar(255),
  display_name     varchar(255),
  title_code       varchar(32),
  active           boolean NOT NULL DEFAULT true,
  deleted_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_scim_resource PRIMARY KEY (id),
  CONSTRAINT uq_scim_resource__external UNIQUE (tenant_id, source_system, resource_type, external_id),
  CONSTRAINT ck_scim_resource__resource_type CHECK (resource_type IN ('User','Group'))
);
COMMENT ON TABLE org.scim_resource IS 'Ánh xạ tài nguyên SCIM của từng nguồn sang người, đơn vị nội bộ (bảng bổ sung DV-04-06)';
COMMENT ON COLUMN org.scim_resource.id IS '{"cls": "C1", "pii": false, "desc": "Mã tài nguyên công bố cho client SCIM (UUIDv7)"}';
COMMENT ON COLUMN org.scim_resource.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.scim_resource.source_system IS '{"cls": "C1", "pii": false, "desc": "Nguồn SCIM (client đã đăng ký), ví dụ HRM_TCT, IDP_KH"}';
COMMENT ON COLUMN org.scim_resource.resource_type IS '{"cls": "C1", "pii": false, "desc": "Loại tài nguyên SCIM"}';
COMMENT ON COLUMN org.scim_resource.external_id IS '{"cls": "C1", "pii": false, "desc": "externalId của nguồn"}';
COMMENT ON COLUMN org.scim_resource.internal_id IS '{"cls": "C1", "pii": false, "desc": "Mã người (org.person.id) hoặc đơn vị (org.org_unit.id)"}';
COMMENT ON COLUMN org.scim_resource.user_name IS '{"cls": "C1", "pii": true, "desc": "userName của tài khoản ở nguồn"}';
COMMENT ON COLUMN org.scim_resource.display_name IS '{"cls": "C1", "pii": true, "desc": "Tên hiển thị ở nguồn"}';
COMMENT ON COLUMN org.scim_resource.title_code IS '{"cls": "C1", "pii": false, "desc": "Mã chức danh (org.job_title.code) theo thuộc tính title"}';
COMMENT ON COLUMN org.scim_resource.active IS '{"cls": "C1", "pii": false, "desc": "Thuộc tính active của SCIM"}';
COMMENT ON COLUMN org.scim_resource.deleted_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm nguồn gửi DELETE (vô hiệu hoá, không xoá vật lý)"}';
COMMENT ON COLUMN org.scim_resource.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.scim_resource.created_by IS '{"cls": "C1", "pii": false, "desc": "Tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.scim_resource.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.scim_resource.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.scim_resource.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; meta.version (ETag) của SCIM"}';
CREATE INDEX ix_scim_resource__user_name ON org.scim_resource (tenant_id, source_system, resource_type, user_name);
CREATE INDEX ix_scim_resource__internal ON org.scim_resource (tenant_id, internal_id);
ALTER TABLE org.scim_resource ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.scim_resource FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_scim_resource__tenant ON org.scim_resource AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.scim_resource FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.scim_resource TO svc_org;
GRANT SELECT ON org.scim_resource TO ro_org;
