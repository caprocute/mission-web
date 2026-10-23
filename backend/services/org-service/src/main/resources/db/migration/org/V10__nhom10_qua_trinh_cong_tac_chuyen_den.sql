-- DV-13-10 · TL-03 "Đồng bộ giữa các cell", luồng thứ ba: "cell nhận ... nhập vào SVC-04 và MOD-06 của tenant đích".
-- Quá trình công tác của cán bộ điều động liên cell, nhận từ cell nguồn. Bảng bổ sung (TL-03 chưa có; suy luận, ghi ở
-- wiki trang 14 mục DV-13-10): đơn vị và chức danh của tenant nguồn không có trong cây của tenant đích nên không ghi
-- thành org.assignment; giữ tài liệu JSON của cell nguồn (mã, tên đơn vị và chức danh, loại, khoảng hiệu lực, số
-- quyết định) làm lý lịch công tác. Người được chuyển ghi bằng mã ở tenant nguồn; gắn với người ở tenant đích làm
-- khi hồ sơ nhân sự của người đó có ở tenant đích. Chỉ thêm, không sửa, không xoá (truy vết).
CREATE TABLE org.transferred_work_history (
  id                  uuid NOT NULL,
  tenant_id           uuid NOT NULL,
  package_id          uuid NOT NULL,
  part_name           varchar(64) NOT NULL,
  source_tenant_code  varchar(64) NOT NULL,
  source_person_id    uuid NOT NULL,
  content             jsonb NOT NULL,
  content_sha256      bytea NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid NOT NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid NOT NULL,
  row_version         int NOT NULL DEFAULT 0,
  CONSTRAINT pk_transferred_work_history PRIMARY KEY (id),
  CONSTRAINT uq_transferred_work_history__part UNIQUE (tenant_id, package_id, part_name),
  CONSTRAINT ck_transferred_work_history__sha256 CHECK (octet_length(content_sha256) = 32)
);
COMMENT ON TABLE org.transferred_work_history IS 'Quá trình công tác nhận từ cell khác khi điều động liên cell (bảng bổ sung DV-13-10)';
COMMENT ON COLUMN org.transferred_work_history.id IS '{"cls": "C1", "pii": false, "desc": "Mã dòng"}';
COMMENT ON COLUMN org.transferred_work_history.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant đích; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.transferred_work_history.package_id IS '{"cls": "C1", "pii": false, "desc": "Mã gói chuyển do cell nguồn cấp"}';
COMMENT ON COLUMN org.transferred_work_history.part_name IS '{"cls": "C1", "pii": false, "desc": "Tên phần trong gói"}';
COMMENT ON COLUMN org.transferred_work_history.source_tenant_code IS '{"cls": "C1", "pii": false, "desc": "Mã tenant nguồn"}';
COMMENT ON COLUMN org.transferred_work_history.source_person_id IS '{"cls": "C1", "pii": false, "desc": "Mã cán bộ ở tenant nguồn"}';
COMMENT ON COLUMN org.transferred_work_history.content IS '{"cls": "C1", "pii": false, "desc": "Quá trình công tác: đơn vị, chức danh, loại phân công, khoảng hiệu lực, số quyết định"}';
COMMENT ON COLUMN org.transferred_work_history.content_sha256 IS '{"cls": "C0", "pii": false, "desc": "SHA-256 nội dung nhận, khớp danh mục gói"}';
COMMENT ON COLUMN org.transferred_work_history.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm nhập theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.transferred_work_history.created_by IS '{"cls": "C1", "pii": false, "desc": "Chủ thể nhập"}';
COMMENT ON COLUMN org.transferred_work_history.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.transferred_work_history.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.transferred_work_history.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan"}';
CREATE INDEX ix_transferred_work_history__person ON org.transferred_work_history (tenant_id, source_person_id);
ALTER TABLE org.transferred_work_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.transferred_work_history FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_transferred_work_history__tenant ON org.transferred_work_history AS PERMISSIVE FOR ALL
  TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.transferred_work_history FROM PUBLIC;
GRANT SELECT, INSERT ON org.transferred_work_history TO svc_org;
GRANT SELECT ON org.transferred_work_history TO ro_org;
