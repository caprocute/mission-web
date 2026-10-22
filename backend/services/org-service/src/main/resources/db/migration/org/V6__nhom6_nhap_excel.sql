-- DV-04-05 · QT-01, SCR-0103 "Nhập từ Excel": tác vụ nền nhập cây tổ chức (bảng bổ sung, TL-03 chưa có bảng job;
-- theo cách của tenant-registry DV-03-03, mỗi service tự lưu job của mình với lược đồ Job chung).
CREATE TABLE org.import_job (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  kind             varchar(24) NOT NULL DEFAULT 'NHAP_CAY_EXCEL',
  status           varchar(12) NOT NULL DEFAULT 'CHO',
  progress         int NOT NULL DEFAULT 0,
  message          varchar(500),
  file_name        varchar(255),
  file_content     bytea,
  file_sha256      bytea NOT NULL,
  effective_from   date NOT NULL,
  total_rows       int NOT NULL DEFAULT 0,
  created_units    int NOT NULL DEFAULT 0,
  error_count      int NOT NULL DEFAULT 0,
  errors           jsonb NOT NULL DEFAULT '[]'::jsonb,
  change_order_id  uuid,
  started_at       timestamptz,
  finished_at      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_import_job PRIMARY KEY (id),
  CONSTRAINT ck_import_job__kind CHECK (kind IN ('NHAP_CAY_EXCEL')),
  CONSTRAINT ck_import_job__status CHECK (status IN ('CHO','DANG_CHAY','HOAN_TAT','THAT_BAI','DA_HUY')),
  CONSTRAINT ck_import_job__progress CHECK (progress BETWEEN 0 AND 100)
);
COMMENT ON TABLE org.import_job IS 'Tác vụ nền nhập cây tổ chức từ Excel (bảng bổ sung DV-04-05, SCR-0103)';
COMMENT ON COLUMN org.import_job.id IS '{"cls": "C1", "pii": false, "desc": "Mã tác vụ, trả ở Location /api/v1/jobs/{id}"}';
COMMENT ON COLUMN org.import_job.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.import_job.kind IS '{"cls": "C1", "pii": false, "desc": "Loại tác vụ"}';
COMMENT ON COLUMN org.import_job.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái tác vụ theo JobStatus"}';
COMMENT ON COLUMN org.import_job.progress IS '{"cls": "C1", "pii": false, "desc": "Tiến độ phần trăm"}';
COMMENT ON COLUMN org.import_job.message IS '{"cls": "C1", "pii": false, "desc": "Mô tả bước đang chạy hoặc kết quả"}';
COMMENT ON COLUMN org.import_job.file_name IS '{"cls": "C1", "pii": false, "desc": "Tên tệp người dùng tải lên"}';
COMMENT ON COLUMN org.import_job.file_content IS '{"cls": "C1", "pii": false, "desc": "Nội dung tệp chờ xử lý; xoá về null khi tác vụ kết thúc"}';
COMMENT ON COLUMN org.import_job.file_sha256 IS '{"cls": "C1", "pii": false, "desc": "Băm SHA-256 của tệp"}';
COMMENT ON COLUMN org.import_job.effective_from IS '{"cls": "C1", "pii": false, "desc": "Ngày hiệu lực mặc định của các đơn vị nhập"}';
COMMENT ON COLUMN org.import_job.total_rows IS '{"cls": "C1", "pii": false, "desc": "Số dòng dữ liệu đã đọc"}';
COMMENT ON COLUMN org.import_job.created_units IS '{"cls": "C1", "pii": false, "desc": "Số đơn vị đã tạo"}';
COMMENT ON COLUMN org.import_job.error_count IS '{"cls": "C1", "pii": false, "desc": "Số lỗi theo dòng"}';
COMMENT ON COLUMN org.import_job.errors IS '{"cls": "C1", "pii": false, "desc": "Báo cáo lỗi: dòng, cột, lý do tiếng Việt"}';
COMMENT ON COLUMN org.import_job.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức gắn cho cả lần nhập"}';
COMMENT ON COLUMN org.import_job.started_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm bắt đầu xử lý"}';
COMMENT ON COLUMN org.import_job.finished_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm kết thúc"}';
COMMENT ON COLUMN org.import_job.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.import_job.created_by IS '{"cls": "C1", "pii": false, "desc": "Người tạo"}';
COMMENT ON COLUMN org.import_job.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.import_job.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.import_job.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan của dòng"}';
CREATE INDEX ix_import_job__created ON org.import_job (tenant_id, created_at DESC);
ALTER TABLE org.import_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.import_job FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_import_job__tenant ON org.import_job AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.import_job FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.import_job TO svc_org;
GRANT SELECT ON org.import_job TO ro_org;
