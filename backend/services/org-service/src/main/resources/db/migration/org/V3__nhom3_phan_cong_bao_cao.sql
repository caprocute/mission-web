-- Sinh từ đặc tả cột TL-03 "Schema org (SVC-04 org-service)" (dòng 1046–1328) và chương "Dữ liệu tổ chức
-- hai trục thời gian" (dòng 4020–4086). Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
-- Vai trò mig_org, svc_org, ro_org, cdc_org và schema org do bước khởi tạo cụm tạo (TL-03 "Không truy cập chéo và
-- quyền tối thiểu"); migration chạy bằng mig_org.

-- Nhóm 3 (T-DB-O3): phân công và quan hệ báo cáo, hàm truy vấn hai trục.

CREATE TABLE org.assignment (
  version_id       uuid NOT NULL,
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  person_id        uuid NOT NULL,
  org_unit_id      uuid NOT NULL,
  position_id      uuid,
  job_title_id     uuid NOT NULL,
  kind             varchar(16) NOT NULL,
  branch           varchar(12) NOT NULL,
  is_head          boolean NOT NULL DEFAULT false,
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
  CONSTRAINT pk_assignment PRIMARY KEY (version_id),
  CONSTRAINT ck_assignment__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_assignment__recorded CHECK (recorded_from < recorded_to),
  CONSTRAINT fk_assignment__org_change_order FOREIGN KEY (change_order_id) REFERENCES org.org_change_order (id),
  -- Một thực thể (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_assignment__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ),
  CONSTRAINT ck_assignment__kind CHECK (kind IN ('CHINH','KIEM_NHIEM','DIEU_DONG','BIET_PHAI','TAM_GIU')),
  CONSTRAINT ck_assignment__branch CHECK (branch IN ('DANG','CHINH_QUYEN')),
  -- Một người chỉ có một phân công CHÍNH trên mỗi nhánh tại mọi điểm của hai trục (BR-01-08)
  CONSTRAINT ex_assignment__one_primary EXCLUDE USING gist (
    tenant_id WITH =, person_id WITH =, branch WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ) WHERE (kind = 'CHINH')
);
COMMENT ON TABLE org.assignment IS 'Phân công (bổ nhiệm, kiêm nhiệm, điều động), hai trục thời gian';
COMMENT ON COLUMN org.assignment.version_id IS '{"cls": "C1", "pii": false, "desc": "Mã phiên bản dòng (khoá chính)"}';
COMMENT ON COLUMN org.assignment.id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể ổn định mà nơi khác tham chiếu"}';
COMMENT ON COLUMN org.assignment.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.assignment.person_id IS '{"cls": "C1", "pii": false, "desc": "Người được phân công (org.person.id)"}';
COMMENT ON COLUMN org.assignment.org_unit_id IS '{"cls": "C1", "pii": false, "desc": "Đơn vị công tác (org.org_unit.id)"}';
COMMENT ON COLUMN org.assignment.position_id IS '{"cls": "C1", "pii": false, "desc": "Vị trí việc làm (org.position.id)"}';
COMMENT ON COLUMN org.assignment.job_title_id IS '{"cls": "C1", "pii": false, "desc": "Chức danh (org.job_title.id)"}';
COMMENT ON COLUMN org.assignment.kind IS '{"cls": "C1", "pii": false, "desc": "Loại phân công"}';
COMMENT ON COLUMN org.assignment.branch IS '{"cls": "C1", "pii": false, "desc": "Nhánh của phân công"}';
COMMENT ON COLUMN org.assignment.is_head IS '{"cls": "C1", "pii": false, "desc": "Người đứng đầu (QĐ 11.2.4)"}';
COMMENT ON COLUMN org.assignment.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức cán bộ sinh ra phiên bản (org.org_change_order)"}';
COMMENT ON COLUMN org.assignment.valid_from IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày bắt đầu (bao gồm)"}';
COMMENT ON COLUMN org.assignment.valid_to IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày kết thúc (không bao gồm); infinity là còn hiệu lực"}';
COMMENT ON COLUMN org.assignment.recorded_from IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: hệ thống biết phiên bản từ thời điểm này"}';
COMMENT ON COLUMN org.assignment.recorded_to IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: phiên bản bị thay thế từ thời điểm này; infinity là hiểu biết hiện tại"}';
COMMENT ON COLUMN org.assignment.valid_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục hiệu lực, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.assignment.recorded_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục ghi nhận, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.assignment.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.assignment.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.assignment.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.assignment.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.assignment.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_assignment__person_current_p ON org.assignment (tenant_id, person_id, valid_from) WHERE recorded_to = 'infinity';
CREATE INDEX ix_assignment__unit_current_p ON org.assignment (tenant_id, org_unit_id, valid_from) WHERE recorded_to = 'infinity';
ALTER TABLE org.assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_assignment__tenant ON org.assignment AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.assignment FROM PUBLIC;
-- Bảng hai trục: không sửa cột nghiệp vụ, không xoá; chỉ đóng phiên bản (TL-03 chương 7)
GRANT SELECT, INSERT ON org.assignment TO svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.assignment TO svc_org;
GRANT SELECT ON org.assignment TO ro_org;

CREATE TABLE org.reporting_line (
  version_id       uuid NOT NULL,
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  subordinate_person_id uuid NOT NULL,
  superior_person_id uuid NOT NULL,
  line_type        varchar(16) NOT NULL,
  branch           varchar(12) NOT NULL,
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
  CONSTRAINT pk_reporting_line PRIMARY KEY (version_id),
  CONSTRAINT ck_reporting_line__valid CHECK (valid_from < valid_to),
  CONSTRAINT ck_reporting_line__recorded CHECK (recorded_from < recorded_to),
  CONSTRAINT fk_reporting_line__org_change_order FOREIGN KEY (change_order_id) REFERENCES org.org_change_order (id),
  -- Một thực thể (cùng id) không có hai phiên bản chồng lấn trên cả hai trục
  CONSTRAINT ex_reporting_line__version_no_overlap EXCLUDE USING gist (
    tenant_id WITH =, id WITH =,
    valid_period WITH &&, recorded_period WITH &&
  ),
  CONSTRAINT ck_reporting_line__line_type CHECK (line_type IN ('TRUC_TIEP','THAM_DINH','PHU_TRACH_LINH_VUC')),
  CONSTRAINT ck_reporting_line__branch CHECK (branch IN ('DANG','CHINH_QUYEN')),
  CONSTRAINT ck_reporting_line__not_self CHECK (superior_person_id <> subordinate_person_id),
  CONSTRAINT ex_reporting_line__one_superior EXCLUDE USING gist (
    tenant_id WITH =, subordinate_person_id WITH =, line_type WITH =, branch WITH =,
    valid_period WITH &&, recorded_period WITH &&
  )
);
COMMENT ON TABLE org.reporting_line IS 'Quan hệ báo cáo giữa hai người, hai trục thời gian (bảng bổ sung)';
COMMENT ON COLUMN org.reporting_line.version_id IS '{"cls": "C1", "pii": false, "desc": "Mã phiên bản dòng (khoá chính)"}';
COMMENT ON COLUMN org.reporting_line.id IS '{"cls": "C1", "pii": false, "desc": "Mã thực thể ổn định mà nơi khác tham chiếu"}';
COMMENT ON COLUMN org.reporting_line.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.reporting_line.subordinate_person_id IS '{"cls": "C1", "pii": false, "desc": "Người báo cáo"}';
COMMENT ON COLUMN org.reporting_line.superior_person_id IS '{"cls": "C1", "pii": false, "desc": "Cấp trên"}';
COMMENT ON COLUMN org.reporting_line.line_type IS '{"cls": "C1", "pii": false, "desc": "Loại quan hệ báo cáo"}';
COMMENT ON COLUMN org.reporting_line.branch IS '{"cls": "C1", "pii": false, "desc": "Nhánh"}';
COMMENT ON COLUMN org.reporting_line.change_order_id IS '{"cls": "C1", "pii": false, "desc": "Quyết định tổ chức cán bộ sinh ra phiên bản (org.org_change_order)"}';
COMMENT ON COLUMN org.reporting_line.valid_from IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày bắt đầu (bao gồm)"}';
COMMENT ON COLUMN org.reporting_line.valid_to IS '{"cls": "C1", "pii": false, "desc": "Trục hiệu lực: ngày kết thúc (không bao gồm); infinity là còn hiệu lực"}';
COMMENT ON COLUMN org.reporting_line.recorded_from IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: hệ thống biết phiên bản từ thời điểm này"}';
COMMENT ON COLUMN org.reporting_line.recorded_to IS '{"cls": "C1", "pii": false, "desc": "Trục ghi nhận: phiên bản bị thay thế từ thời điểm này; infinity là hiểu biết hiện tại"}';
COMMENT ON COLUMN org.reporting_line.valid_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục hiệu lực, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.reporting_line.recorded_period IS '{"cls": "C1", "pii": false, "desc": "Cột sinh trục ghi nhận, phục vụ ràng buộc loại trừ"}';
COMMENT ON COLUMN org.reporting_line.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.reporting_line.created_by IS '{"cls": "C1", "pii": false, "desc": "Mã người dùng (sub trong JWT) hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN org.reporting_line.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.reporting_line.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.reporting_line.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API; aggregate_version ghi vào outbox"}';
CREATE INDEX ix_reporting_line__subordinate_current_p ON org.reporting_line (tenant_id, subordinate_person_id) WHERE recorded_to = 'infinity';
ALTER TABLE org.reporting_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.reporting_line FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_reporting_line__tenant ON org.reporting_line AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.reporting_line FROM PUBLIC;
-- Bảng hai trục: không sửa cột nghiệp vụ, không xoá; chỉ đóng phiên bản (TL-03 chương 7)
GRANT SELECT, INSERT ON org.reporting_line TO svc_org;
GRANT UPDATE (recorded_to, updated_at, updated_by, row_version) ON org.reporting_line TO svc_org;
GRANT SELECT ON org.reporting_line TO ro_org;


-- Phân công tại ngày p_valid như đã biết tại thời điểm p_recorded (TL-03 "Mẫu truy vấn").
-- SECURITY INVOKER (mặc định) nên RLS của người gọi vẫn áp dụng.
CREATE FUNCTION org.assignment_asof(p_tenant uuid, p_valid date, p_recorded timestamptz)
RETURNS SETOF org.assignment LANGUAGE sql STABLE AS $$
  SELECT *
    FROM org.assignment a
   WHERE a.tenant_id = p_tenant
     AND a.valid_period @> p_valid
     AND a.recorded_period @> p_recorded
$$;
REVOKE ALL ON FUNCTION org.assignment_asof(uuid, date, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org.assignment_asof(uuid, date, timestamptz) TO svc_org, ro_org;

-- Cây con của p_root trên cây p_tree tại ngày p_valid như đã biết tại p_recorded (CTE đệ quy TL-03).
CREATE FUNCTION org.unit_subtree_asof(p_tenant uuid, p_tree varchar, p_root uuid, p_valid date, p_recorded timestamptz)
RETURNS TABLE (child_unit_id uuid, parent_unit_id uuid, depth int) LANGUAGE sql STABLE AS $$
  WITH RECURSIVE tree AS (
    SELECT r.child_unit_id, r.parent_unit_id, 1 AS depth
      FROM org.org_unit_relation r
     WHERE r.tenant_id = p_tenant AND r.tree = p_tree AND r.parent_unit_id = p_root
       AND r.relation_type = 'TRUC_THUOC'
       AND r.valid_period @> p_valid AND r.recorded_period @> p_recorded
    UNION ALL
    SELECT r.child_unit_id, r.parent_unit_id, t.depth + 1
      FROM org.org_unit_relation r JOIN tree t ON r.parent_unit_id = t.child_unit_id
     WHERE r.tenant_id = p_tenant AND r.tree = p_tree
       AND r.relation_type = 'TRUC_THUOC'
       AND r.valid_period @> p_valid AND r.recorded_period @> p_recorded
  )
  SELECT child_unit_id, parent_unit_id, depth FROM tree
$$;
REVOKE ALL ON FUNCTION org.unit_subtree_asof(uuid, varchar, uuid, date, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org.unit_subtree_asof(uuid, varchar, uuid, date, timestamptz) TO svc_org, ro_org;
