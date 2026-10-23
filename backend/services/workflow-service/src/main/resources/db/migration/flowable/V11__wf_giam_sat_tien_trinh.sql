-- =============================================================================
-- V11__wf_giam_sat_tien_trinh.sql
-- DV-06-10 · SVC-06 · BS-01 SCR-1506 "Giám sát tiến trình theo mẫu" · TL-01 SVC-06 (đọc bảng chiếu, không
-- truy vấn ACT_* lúc cao điểm) · TL-01 G2 (chuyển người duyệt khi điều động là uỷ quyền có lưu vết) · TL-03
-- "Schema org_replica" (bảng assignment_v).
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp. V7, V8 thuộc nhánh khác, V9 là DV-06-09.
--
-- Ghi chú lệch nguồn (ghi ở wiki trang 14, mục DV-06-10):
--   * wf_task_projection bổ sung process_instance_id, activity_id, template_version, process_started_at để
--     danh sách giám sát trả templateVersion, startedAt và bước hiện tại mà không nối ACT_* (bổ sung DV-06-10);
--     activity_name chỉ ghi khi tên bước trong BPMN là hằng (mẫu đóng gói đặt tên bằng biểu thức theo biến tieuDe).
--     Dòng đang có được điền một lần từ ACT_RU_* ngay trong migration (không phải lúc chạy).
--   * Quản trị nền tảng xem tổng hợp theo cell (scope N của SCR-1506.xem): chính sách RLS đọc chéo tenant chỉ
--     mở khi giao dịch tự đặt app.platform_scope = 'wf_instance_monitor', chỉ cho SELECT.
--   * org_replica.assignment_v theo đúng cột TL-03; V5 mới tạo org_unit_v. Dùng để kiểm người nhận khi chuyển
--     người xử lý có phân công còn hiệu lực ở đơn vị ứng viên. Consumer EVT-15 đổ dữ liệu chưa có (task sau).
-- =============================================================================

SET search_path TO flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_task_projection: cột bổ sung cho giám sát tiến trình (SCR-1506).
-- -----------------------------------------------------------------------------
ALTER TABLE flowable.wf_task_projection
  ADD COLUMN process_instance_id varchar(64),
  ADD COLUMN activity_id         varchar(255),
  ADD COLUMN activity_name       varchar(255),
  ADD COLUMN template_version    int,
  ADD COLUMN process_started_at  timestamptz;
ALTER TABLE flowable.wf_task_projection ADD CONSTRAINT ck_wf_task_projection__template_version
  CHECK (template_version IS NULL OR template_version >= 1);
COMMENT ON COLUMN flowable.wf_task_projection.process_instance_id IS '{"cls":"C1","desc":"Mã tiến trình ACT_RU_EXECUTION.PROC_INST_ID_ (bổ sung DV-06-10)"}';
COMMENT ON COLUMN flowable.wf_task_projection.activity_id IS '{"cls":"C0","desc":"Mã bước BPMN của user task, TASK_DEF_KEY_ (bổ sung DV-06-10)"}';
COMMENT ON COLUMN flowable.wf_task_projection.activity_name IS '{"cls":"C0","desc":"Tên bước trong định nghĩa BPMN khi tên là hằng, không phải biểu thức (bổ sung DV-06-10)"}';
COMMENT ON COLUMN flowable.wf_task_projection.template_version IS '{"cls":"C0","desc":"Phiên bản định nghĩa mẫu luồng đang chạy (bổ sung DV-06-10)"}';
COMMENT ON COLUMN flowable.wf_task_projection.process_started_at IS '{"cls":"C1","desc":"Thời điểm khởi tạo tiến trình (bổ sung DV-06-10)"}';

-- Điền một lần cho dòng đã có (chỉ trong migration; ứng dụng không đọc ACT_* khi liệt kê).
UPDATE flowable.wf_task_projection p
   SET process_instance_id = t.proc_inst_id_,
       activity_id         = t.task_def_key_,
       template_version    = d.version_,
       process_started_at  = e.start_time_
  FROM flowable.act_ru_task t
  LEFT JOIN flowable.act_re_procdef d ON d.id_ = t.proc_def_id_
  LEFT JOIN flowable.act_ru_execution e ON e.id_ = t.proc_inst_id_
 WHERE t.id_ = p.task_id;

-- Giám sát theo tenant và theo cell xếp theo hạn rồi mã task (keyset), lọc thêm theo mẫu.
CREATE INDEX ix_wf_task_projection__giam_sat
  ON flowable.wf_task_projection (tenant_id, template_code, due_at, task_id);
CREATE INDEX ix_wf_task_projection__giam_sat_cell
  ON flowable.wf_task_projection (due_at, task_id);
CREATE INDEX ix_wf_task_projection__tien_trinh
  ON flowable.wf_task_projection (tenant_id, business_type, business_id);

-- Đọc chéo tenant phạm vi nền tảng (SCR-1506.xem scope N): chỉ SELECT, chỉ khi giao dịch tự đặt cờ phạm vi.
CREATE POLICY rls_wf_task_projection__nen_tang_doc ON flowable.wf_task_projection AS PERMISSIVE FOR SELECT
  TO svc_flowable
  USING (current_setting('app.platform_scope', true) = 'wf_instance_monitor');

-- -----------------------------------------------------------------------------
-- org_replica.assignment_v: bản sao phân công theo trục hiệu lực (TL-03 "Bảng org_replica.assignment_v").
-- -----------------------------------------------------------------------------
CREATE TABLE org_replica.assignment_v (
  tenant_id       uuid        NOT NULL,
  id              uuid        NOT NULL,
  valid_from      date        NOT NULL,
  valid_to        date        NOT NULL DEFAULT 'infinity',
  person_id       uuid        NOT NULL,
  org_unit_id     uuid        NOT NULL,
  position_id     uuid,
  job_title_id    uuid,
  kind            varchar(16) NOT NULL,
  branch          varchar(12) NOT NULL,
  is_head         boolean     NOT NULL DEFAULT false,
  source_version  bigint      NOT NULL,
  source_event_id uuid        NOT NULL,
  replicated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_assignment_v PRIMARY KEY (tenant_id, id, valid_from),
  CONSTRAINT ck_assignment_v__valid CHECK (valid_from < valid_to)
);
COMMENT ON TABLE org_replica.assignment_v IS 'Bản sao phân công của workflow-service để kiểm người nhận khi chuyển người xử lý (TL-03 schema org_replica)';
COMMENT ON COLUMN org_replica.assignment_v.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org_replica.assignment_v.id IS '{"cls":"C1","desc":"Mã phân công"}';
COMMENT ON COLUMN org_replica.assignment_v.valid_from IS '{"cls":"C1","desc":"Bắt đầu hiệu lực"}';
COMMENT ON COLUMN org_replica.assignment_v.valid_to IS '{"cls":"C1","desc":"Hết hiệu lực (không gồm ngày này)"}';
COMMENT ON COLUMN org_replica.assignment_v.person_id IS '{"cls":"C1","desc":"Người"}';
COMMENT ON COLUMN org_replica.assignment_v.org_unit_id IS '{"cls":"C1","desc":"Đơn vị"}';
COMMENT ON COLUMN org_replica.assignment_v.position_id IS '{"cls":"C1","desc":"Vị trí"}';
COMMENT ON COLUMN org_replica.assignment_v.job_title_id IS '{"cls":"C1","desc":"Chức danh"}';
COMMENT ON COLUMN org_replica.assignment_v.kind IS '{"cls":"C1","desc":"Loại phân công"}';
COMMENT ON COLUMN org_replica.assignment_v.branch IS '{"cls":"C1","desc":"Nhánh"}';
COMMENT ON COLUMN org_replica.assignment_v.is_head IS '{"cls":"C1","desc":"Người đứng đầu"}';
COMMENT ON COLUMN org_replica.assignment_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN org_replica.assignment_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN org_replica.assignment_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
CREATE INDEX ix_assignment_v__person ON org_replica.assignment_v (tenant_id, person_id, valid_to);
CREATE INDEX ix_assignment_v__unit ON org_replica.assignment_v (tenant_id, org_unit_id, valid_to);
CREATE INDEX ix_assignment_v__head ON org_replica.assignment_v (tenant_id, org_unit_id) WHERE is_head;
ALTER TABLE org_replica.assignment_v ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_replica.assignment_v FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_assignment_v__tenant ON org_replica.assignment_v AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON org_replica.assignment_v FROM PUBLIC;
GRANT SELECT ON org_replica.assignment_v TO svc_flowable, ro_flowable;
