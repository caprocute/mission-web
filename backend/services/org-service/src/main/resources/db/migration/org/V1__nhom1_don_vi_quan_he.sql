-- Sinh từ đặc tả cột TL-03 "Schema org (SVC-04 org-service)" (dòng 1046–1328) và chương "Dữ liệu tổ chức
-- hai trục thời gian" (dòng 4020–4086). Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
-- Vai trò mig_org, svc_org, ro_org, cdc_org và schema org do bước khởi tạo cụm tạo (TL-03 "Không truy cập chéo và
-- quyền tối thiểu"); migration chạy bằng mig_org.

-- Nhóm 1 (T-DB-O1): tiện ích, quyết định tổ chức, khối đối tượng, chức danh, đơn vị, quan hệ đơn vị.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

GRANT USAGE ON SCHEMA org TO svc_org, ro_org, cdc_org;

CREATE TABLE org.org_change_order (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  order_no         varchar(64) NOT NULL,
  order_date       date NOT NULL,
  kind             varchar(16) NOT NULL,
  effective_on     date NOT NULL,
  signed_doc_ref   varchar(200),
  payload          jsonb NOT NULL,
  status           varchar(12) NOT NULL DEFAULT 'NHAP',
  applied_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_org_change_order PRIMARY KEY (id),
  CONSTRAINT uq_org_change_order__order_no UNIQUE (tenant_id, order_no),
  CONSTRAINT ck_org_change_order__kind CHECK (kind IN ('BO_NHIEM','DIEU_DONG','KIEM_NHIEM','SAP_NHAP','CHIA_TACH','GIAI_THE','THOI_VIEC','DINH_CHINH')),
  CONSTRAINT ck_org_change_order__status CHECK (status IN ('NHAP','DA_AP_DUNG','DA_HUY'))
);
COMMENT ON TABLE org.org_change_order IS 'Quyết định tổ chức cán bộ, căn cứ của mỗi phiên bản hai trục (bảng bổ sung TL-03)';
COMMENT ON COLUMN org.org_change_order.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.org_change_order.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.org_change_order.order_no IS '{"cls": "C1", "pii": false, "desc": "Số quyết định"}';
COMMENT ON COLUMN org.org_change_order.order_date IS '{"cls": "C1", "pii": false, "desc": "Ngày ký quyết định"}';
COMMENT ON COLUMN org.org_change_order.kind IS '{"cls": "C1", "pii": false, "desc": "Loại quyết định"}';
COMMENT ON COLUMN org.org_change_order.effective_on IS '{"cls": "C1", "pii": false, "desc": "Ngày hiệu lực"}';
COMMENT ON COLUMN org.org_change_order.signed_doc_ref IS '{"cls": "C1", "pii": false, "desc": "Tham chiếu văn bản đã ký (mã ở hệ văn bản qua integ.mapping_xref)"}';
COMMENT ON COLUMN org.org_change_order.payload IS '{"cls": "C1", "pii": false, "desc": "Nội dung có cấu trúc của quyết định (đơn vị, người bị tác động); không chứa dữ liệu C2"}';
COMMENT ON COLUMN org.org_change_order.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái áp dụng"}';
COMMENT ON COLUMN org.org_change_order.applied_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm hệ thống áp dụng (bằng recorded_from của các phiên bản sinh ra)"}';
COMMENT ON COLUMN org.org_change_order.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.org_change_order.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.org_change_order.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.org_change_order.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.org_change_order.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
ALTER TABLE org.org_change_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.org_change_order FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_org_change_order__tenant ON org.org_change_order AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.org_change_order FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.org_change_order TO svc_org;
GRANT SELECT ON org.org_change_order TO ro_org;

CREATE TABLE org.subject_group (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  code             varchar(16) NOT NULL,
  name             varchar(255) NOT NULL,
  flow             varchar(12) NOT NULL,
  description      text,
  valid_from       date NOT NULL,
  valid_to         date NOT NULL DEFAULT 'infinity',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_subject_group PRIMARY KEY (id),
  CONSTRAINT uq_subject_group__code UNIQUE (tenant_id, code),
  CONSTRAINT ck_subject_group__flow CHECK (flow IN ('DANG','NHA_NUOC','CA_HAI')),
  CONSTRAINT ck_subject_group__valid CHECK (valid_from < valid_to)
);
COMMENT ON TABLE org.subject_group IS 'Chín khối đối tượng đánh giá theo PL 2A';
COMMENT ON COLUMN org.subject_group.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.subject_group.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.subject_group.code IS '{"cls": "C0", "pii": false, "desc": "Mã khối đối tượng, K1 đến K9 theo PL 2A"}';
COMMENT ON COLUMN org.subject_group.name IS '{"cls": "C0", "pii": false, "desc": "Tên khối đối tượng"}';
COMMENT ON COLUMN org.subject_group.flow IS '{"cls": "C1", "pii": false, "desc": "Luồng áp dụng"}';
COMMENT ON COLUMN org.subject_group.description IS '{"cls": "C0", "pii": false, "desc": "Mô tả phạm vi"}';
COMMENT ON COLUMN org.subject_group.valid_from IS '{"cls": "C0", "pii": false, "desc": "Hiệu lực từ (một trục thời gian)"}';
COMMENT ON COLUMN org.subject_group.valid_to IS '{"cls": "C0", "pii": false, "desc": "Hiệu lực đến (không bao gồm)"}';
COMMENT ON COLUMN org.subject_group.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.subject_group.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.subject_group.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.subject_group.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.subject_group.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
ALTER TABLE org.subject_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.subject_group FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_subject_group__tenant ON org.subject_group AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.subject_group FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.subject_group TO svc_org;
GRANT SELECT ON org.subject_group TO ro_org;

CREATE TABLE org.job_title (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  code             varchar(32) NOT NULL,
  name             varchar(255) NOT NULL,
  level            smallint NOT NULL,
  is_central_managed boolean NOT NULL DEFAULT false,
  subject_group_id uuid,
  valid_from       date NOT NULL,
  valid_to         date NOT NULL DEFAULT 'infinity',
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_job_title PRIMARY KEY (id),
  CONSTRAINT uq_job_title__code UNIQUE (tenant_id, code),
  CONSTRAINT fk_job_title__subject_group FOREIGN KEY (subject_group_id) REFERENCES org.subject_group (id),
  CONSTRAINT ck_job_title__valid CHECK (valid_from < valid_to)
);
COMMENT ON TABLE org.job_title IS 'Danh mục chức danh';
COMMENT ON COLUMN org.job_title.id IS '{"cls": "C1", "pii": false, "desc": "Mã bản ghi (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN org.job_title.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.job_title.code IS '{"cls": "C0", "pii": false, "desc": "Mã chức danh"}';
COMMENT ON COLUMN org.job_title.name IS '{"cls": "C0", "pii": false, "desc": "Tên chức danh"}';
COMMENT ON COLUMN org.job_title.level IS '{"cls": "C0", "pii": false, "desc": "Cấp chức danh, phục vụ sắp xếp"}';
COMMENT ON COLUMN org.job_title.is_central_managed IS '{"cls": "C0", "pii": false, "desc": "Thuộc 40 chức danh diện Trung ương quản lý (PL 2B)"}';
COMMENT ON COLUMN org.job_title.subject_group_id IS '{"cls": "C1", "pii": false, "desc": "Khối đối tượng mặc định"}';
COMMENT ON COLUMN org.job_title.valid_from IS '{"cls": "C0", "pii": false, "desc": "Hiệu lực danh mục từ (một trục thời gian)"}';
COMMENT ON COLUMN org.job_title.valid_to IS '{"cls": "C0", "pii": false, "desc": "Hiệu lực danh mục đến (không bao gồm)"}';
COMMENT ON COLUMN org.job_title.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.job_title.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.job_title.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.job_title.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.job_title.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
ALTER TABLE org.job_title ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.job_title FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_job_title__tenant ON org.job_title AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.job_title FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON org.job_title TO svc_org;
GRANT SELECT ON org.job_title TO ro_org;

CREATE TABLE org.org_unit (
  version_id       uuid NOT NULL,
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  code             varchar(50) NOT NULL,
  name             varchar(255) NOT NULL,
  short_name       varchar(100),
  unit_type        varchar(24) NOT NULL,
  subject_group_id uuid,
  is_collective_subject boolean NOT NULL DEFAULT true,
  flows            varchar(12)[] NOT NULL,
  level            varchar(12) NOT NULL,
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
  CONSTRAINT pk_org_unit PRIMARY KEY (version_id),
  CONSTRAINT ck_org_unit__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_org_unit__recorded CHECK (recorded_from < recorded_to),
  CONSTRAINT fk_org_unit__org_change_order FOREIGN KEY (change_order_id) REFERENCES org.org_change_order (id),
  -- Một thực thể (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_org_unit__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ),
  CONSTRAINT ck_org_unit__unit_type CHECK (unit_type IN ('TO_CHUC_DANG','CHI_BO','CO_QUAN','DON_VI_SU_NGHIEP','PHONG_BAN','XA_PHUONG','DOANH_NGHIEP')),
  CONSTRAINT fk_org_unit__subject_group FOREIGN KEY (subject_group_id) REFERENCES org.subject_group (id)
);
COMMENT ON TABLE org.org_unit IS 'Đơn vị tổ chức, hai trục thời gian';
COMMENT ON COLUMN org.org_unit.version_id IS '{"cls": "C1", "pii": false, "desc": "Mã phiên bản dòng (khoá chính)"}';
COMMENT ON COLUMN org.org_unit.id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể ổn định mà nơi khác tham chiếu"}';
COMMENT ON COLUMN org.org_unit.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.org_unit.code IS '{"cls": "C0", "pii": false, "desc": "Mã đơn vị nội bộ"}';
COMMENT ON COLUMN org.org_unit.name IS '{"cls": "C0", "pii": false, "desc": "Tên đơn vị"}';
COMMENT ON COLUMN org.org_unit.short_name IS '{"cls": "C0", "pii": false, "desc": "Tên viết tắt"}';
COMMENT ON COLUMN org.org_unit.unit_type IS '{"cls": "C0", "pii": false, "desc": "Loại đơn vị"}';
COMMENT ON COLUMN org.org_unit.subject_group_id IS '{"cls": "C1", "pii": false, "desc": "Khối đối tượng theo PL 2A (org.subject_group)"}';
COMMENT ON COLUMN org.org_unit.is_collective_subject IS '{"cls": "C1", "pii": false, "desc": "Đơn vị có được đánh giá như tập thể không"}';
COMMENT ON COLUMN org.org_unit.flows IS '{"cls": "C1", "pii": false, "desc": "Luồng áp dụng cho đơn vị (TL-02a)"}';
COMMENT ON COLUMN org.org_unit.level IS '{"cls": "C1", "pii": false, "desc": "Cấp (TW, tỉnh, xã)"}';
COMMENT ON COLUMN org.org_unit.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức cán bộ sinh ra phiên bản (org.org_change_order)"}';
COMMENT ON COLUMN org.org_unit.valid_from IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày bắt đầu (bao gồm)"}';
COMMENT ON COLUMN org.org_unit.valid_to IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày kết thúc (không bao gồm); infinity là còn hiệu lực"}';
COMMENT ON COLUMN org.org_unit.recorded_from IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: hệ thống biết phiên bản từ thời điểm này"}';
COMMENT ON COLUMN org.org_unit.recorded_to IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: phiên bản bị thay thế từ thời điểm này; infinity là hiểu biết hiện tại"}';
COMMENT ON COLUMN org.org_unit.valid_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục hiệu lực, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.org_unit.recorded_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục ghi nhận, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.org_unit.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.org_unit.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.org_unit.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.org_unit.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.org_unit.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_org_unit__current_p ON org.org_unit (tenant_id, id) WHERE recorded_to = 'infinity';
CREATE INDEX ix_org_unit__code ON org.org_unit (tenant_id, code);
ALTER TABLE org.org_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.org_unit FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_org_unit__tenant ON org.org_unit AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.org_unit FROM PUBLIC;
-- Bảng hai trục: không sửa cột nghiệp vụ, không xoá; chỉ đóng phiên bản (TL-03 chương 7)
GRANT SELECT, INSERT ON org.org_unit TO svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.org_unit TO svc_org;
GRANT SELECT ON org.org_unit TO ro_org;

CREATE TABLE org.org_unit_relation (
  version_id       uuid NOT NULL,
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  tree             varchar(12) NOT NULL,
  parent_unit_id   uuid NOT NULL,
  child_unit_id    uuid NOT NULL,
  relation_type    varchar(12) NOT NULL,
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
  CONSTRAINT pk_org_unit_relation PRIMARY KEY (version_id),
  CONSTRAINT ck_org_unit_relation__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_org_unit_relation__recorded CHECK (recorded_from < recorded_to),
  CONSTRAINT fk_org_unit_relation__org_change_order FOREIGN KEY (change_order_id) REFERENCES org.org_change_order (id),
  -- Một thực thể (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_org_unit_relation__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ),
  CONSTRAINT ck_org_unit_relation__tree CHECK (tree IN ('DANG','CHUYEN_MON')),
  CONSTRAINT ck_org_unit_relation__relation_type CHECK (relation_type IN ('TRUC_THUOC','CHI_DAO','PHOI_HOP')),
  CONSTRAINT ck_org_unit_relation__not_self CHECK (child_unit_id <> parent_unit_id),
  -- Mỗi đơn vị có đúng một cấp trên trực thuộc trên mỗi cây tại mọi điểm của hai trục
  CONSTRAINT ex_org_unit_relation__one_parent EXCLUDE USING gist (
    tenant_id WITH =, tree WITH =, child_unit_id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ) WHERE (relation_type = 'TRUC_THUOC')
);
COMMENT ON TABLE org.org_unit_relation IS 'Quan hệ cha con giữa đơn vị trên hai cây song song';
COMMENT ON COLUMN org.org_unit_relation.version_id IS '{"cls": "C1", "pii": false, "desc": "Mã phiên bản dòng (khoá chính)"}';
COMMENT ON COLUMN org.org_unit_relation.id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể ổn định mà nơi khác tham chiếu"}';
COMMENT ON COLUMN org.org_unit_relation.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.org_unit_relation.tree IS '{"cls": "C1", "pii": false, "desc": "Cây tổ chức: cây Đảng hoặc cây chuyên môn"}';
COMMENT ON COLUMN org.org_unit_relation.parent_unit_id IS '{"cls": "C1", "pii": false, "desc": "Đơn vị cấp trên (org.org_unit.id)"}';
COMMENT ON COLUMN org.org_unit_relation.child_unit_id IS '{"cls": "C1", "pii": false, "desc": "Đơn vị cấp dưới (org.org_unit.id)"}';
COMMENT ON COLUMN org.org_unit_relation.relation_type IS '{"cls": "C1", "pii": false, "desc": "Loại quan hệ"}';
COMMENT ON COLUMN org.org_unit_relation.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức cán bộ sinh ra phiên bản (org.org_change_order)"}';
COMMENT ON COLUMN org.org_unit_relation.valid_from IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày bắt đầu (bao gồm)"}';
COMMENT ON COLUMN org.org_unit_relation.valid_to IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày kết thúc (không bao gồm); infinity là còn hiệu lực"}';
COMMENT ON COLUMN org.org_unit_relation.recorded_from IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: hệ thống biết phiên bản từ thời điểm này"}';
COMMENT ON COLUMN org.org_unit_relation.recorded_to IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: phiên bản bị thay thế từ thời điểm này; infinity là hiểu biết hiện tại"}';
COMMENT ON COLUMN org.org_unit_relation.valid_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục hiệu lực, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.org_unit_relation.recorded_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục ghi nhận, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.org_unit_relation.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.org_unit_relation.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.org_unit_relation.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.org_unit_relation.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.org_unit_relation.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
-- Phục vụ CTE đệ quy đi xuống cây (suy luận: TL-03 chỉ nêu EX theo child_unit_id)
CREATE INDEX ix_org_unit_relation__parent_current_p ON org.org_unit_relation (tenant_id, tree, parent_unit_id) WHERE recorded_to = 'infinity';
CREATE INDEX ix_org_unit_relation__child_current_p ON org.org_unit_relation (tenant_id, tree, child_unit_id) WHERE recorded_to = 'infinity';
ALTER TABLE org.org_unit_relation ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.org_unit_relation FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_org_unit_relation__tenant ON org.org_unit_relation AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.org_unit_relation FROM PUBLIC;
-- Bảng hai trục: không sửa cột nghiệp vụ, không xoá; chỉ đóng phiên bản (TL-03 chương 7)
GRANT SELECT, INSERT ON org.org_unit_relation TO svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.org_unit_relation TO svc_org;
GRANT SELECT ON org.org_unit_relation TO ro_org;


-- Bỏ dấu tiếng Việt để tìm đơn vị không phân biệt dấu mà không cần tiện ích unaccent (không có trong danh sách
-- tiện ích được phép của TL-03). Hàm IMMUTABLE, chỉ dùng trong truy vấn đọc.
CREATE FUNCTION org.fn_fold_vi(p text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS $$
  SELECT lower(translate(p,
    'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ',
    'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyydAAAAAAAAAAAAAAAAAEEEEEEEEEEEIIIIIOOOOOOOOOOOOOOOOOUUUUUUUUUUUYYYYYD'))
$$;
REVOKE ALL ON FUNCTION org.fn_fold_vi(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org.fn_fold_vi(text) TO svc_org, ro_org;
