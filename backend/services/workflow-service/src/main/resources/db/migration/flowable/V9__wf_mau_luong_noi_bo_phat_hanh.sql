-- =============================================================================
-- V9__wf_mau_luong_noi_bo_phat_hanh.sql
-- DV-06-09 · SVC-06 · BS-01 SCR-1505 "Thư viện và trình soạn mẫu luồng", mục "Thay đổi dữ liệu đề xuất
-- (bổ sung cho TL-03)", "Giới hạn để không ảnh hưởng mức tải 150 user task mỗi giây mỗi cell" · TL-03 bảng
-- flowable.wf_template, flowable.wf_binding · TL-01 G2 "Quy tắc quản trị chống mạng nhện" · ADR-04, ADR-16.
-- Migration chỉ thêm mới (expand/contract); không sửa V1..V6.
--
-- Ghi chú lệch nguồn (wiki trang 14, mục DV-06-09):
--   * BS-01 là đề xuất chưa ghép vào TL-03: mọi cột và bảng dưới đây đánh dấu "bổ sung BS-01" trong chú thích.
--   * Cột status của wf_template nới từ varchar(12) lên varchar(16) vì giá trị CHO_PHE_DUYET dài 13 ký tự.
--   * Ngoài bảng wf_template_rollout của BS-01, thêm ba bảng bổ sung của luồng B6: wf_template_test_run (lần
--     chạy bộ test trên engine thử, tác vụ dài 202 + Location), wf_template_release (merge request, chữ ký
--     cosign, người phê duyệt) và wf_code_host_command (hộp thư lệnh gửi hệ quản lý mã khi không dùng bộ
--     chuyển HTTP).
--   * Đếm số tenant đang dùng mẫu (tenantBindingCount) và lấy tham số của mọi wf_binding dùng mẫu để chạy test
--     là thao tác phạm vi nền tảng (scope N của SCR-1505). Chính sách RLS đọc chéo tenant chỉ mở khi giao dịch
--     tự đặt app.platform_scope = 'wf_template_usage', chỉ cho SELECT.
--   * saga_instance.saga_type thêm PHAT_HANH_MAU_LUONG cho saga phát hành cuốn chiếu (BS-01 "Triển khai lên cell").
-- =============================================================================

SET search_path TO flowable;

-- -----------------------------------------------------------------------------
-- flowable.wf_template: cột bổ sung BS-01, vòng đời mở rộng, bất biến từ HIEU_LUC, bốn mắt.
-- -----------------------------------------------------------------------------
ALTER TABLE flowable.wf_template ALTER COLUMN status TYPE varchar(16);
ALTER TABLE flowable.wf_template
  ADD COLUMN param_schema   jsonb,
  ADD COLUMN test_suite_ref varchar(128),
  ADD COLUMN approved_by    uuid,
  ADD COLUMN signature      bytea;
ALTER TABLE flowable.wf_template DROP CONSTRAINT ck_wf_template__status;
ALTER TABLE flowable.wf_template ADD CONSTRAINT ck_wf_template__status
  CHECK (status IN ('NHAP', 'DAT_KIEM_THU', 'CHO_PHE_DUYET', 'HIEU_LUC', 'NGUNG'));
-- Bốn mắt: người phê duyệt phát hành khác người soạn (BS-01 "Người dùng và quyền").
ALTER TABLE flowable.wf_template ADD CONSTRAINT ck_wf_template__four_eyes
  CHECK (approved_by IS NULL OR approved_by <> created_by);
-- Chữ ký cosign và người phê duyệt được ghi cùng lúc khi pipeline báo đã ký.
ALTER TABLE flowable.wf_template ADD CONSTRAINT ck_wf_template__signed
  CHECK ((approved_by IS NULL) = (signature IS NULL));
-- Mỗi mã mẫu chỉ có một phiên bản đang soạn, đang kiểm thử hoặc chờ phê duyệt (WORKFLOW-409-DRAFT-EXISTS).
CREATE UNIQUE INDEX ux_wf_template__dang_soan ON flowable.wf_template (template_code)
  WHERE status IN ('NHAP', 'DAT_KIEM_THU', 'CHO_PHE_DUYET');
CREATE INDEX ix_wf_template__code_version ON flowable.wf_template (template_code, version_no);
COMMENT ON COLUMN flowable.wf_template.status IS '{"cls":"C0","desc":"Trạng thái: NHAP, DAT_KIEM_THU, CHO_PHE_DUYET, HIEU_LUC, NGUNG (mở rộng bổ sung BS-01)"}';
COMMENT ON COLUMN flowable.wf_template.param_schema IS '{"cls":"C0","desc":"Khai báo tham số dạng JSON Schema, sinh biểu mẫu SCR-1504 (bổ sung BS-01)"}';
COMMENT ON COLUMN flowable.wf_template.test_suite_ref IS '{"cls":"C0","desc":"Mã commit của bộ test trong repo mẫu luồng (bổ sung BS-01)"}';
COMMENT ON COLUMN flowable.wf_template.approved_by IS '{"cls":"C1","desc":"Người phê duyệt phát hành, khác người soạn (bổ sung BS-01)"}';
COMMENT ON COLUMN flowable.wf_template.signature IS '{"cls":"C0","desc":"Chữ ký cosign của gói mẫu do pipeline ghi (bổ sung BS-01)"}';

-- Phiên bản từ HIEU_LUC trở đi không sửa nội dung (BS-01 "Vòng đời phiên bản"); chỉ được chuyển HIEU_LUC sang
-- NGUNG và ghi mã triển khai. Ứng dụng đã chặn trước (WORKFLOW-409-TEMPLATE-IMMUTABLE); trigger là lớp phòng vệ.
CREATE OR REPLACE FUNCTION flowable.fn_wf_template_bat_bien() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('HIEU_LUC', 'NGUNG') THEN
    IF NEW.template_code IS DISTINCT FROM OLD.template_code
       OR NEW.version_no IS DISTINCT FROM OLD.version_no
       OR NEW.bpmn_xml IS DISTINCT FROM OLD.bpmn_xml
       OR NEW.bpmn_sha256 IS DISTINCT FROM OLD.bpmn_sha256
       OR NEW.param_schema IS DISTINCT FROM OLD.param_schema
       OR NEW.test_suite_ref IS DISTINCT FROM OLD.test_suite_ref
       OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
       OR NEW.signature IS DISTINCT FROM OLD.signature
       OR (OLD.status = 'NGUNG' AND NEW.status <> 'NGUNG')
       OR (OLD.status = 'HIEU_LUC' AND NEW.status NOT IN ('HIEU_LUC', 'NGUNG')) THEN
      RAISE EXCEPTION 'Phiên bản mẫu luồng % đã phát hành, không sửa được', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tg_wf_template__bat_bien BEFORE UPDATE ON flowable.wf_template
  FOR EACH ROW EXECUTE FUNCTION flowable.fn_wf_template_bat_bien();

-- -----------------------------------------------------------------------------
-- flowable.wf_binding: version_no, status (bổ sung BS-01) để thể hiện trạng thái nháp mà SCR-1504 nhắc tới.
-- Dòng hiện có là tham số đang áp dụng nên mặc định HIEU_LUC.
-- -----------------------------------------------------------------------------
ALTER TABLE flowable.wf_binding
  ADD COLUMN version_no int         NOT NULL DEFAULT 1,
  ADD COLUMN status     varchar(12) NOT NULL DEFAULT 'HIEU_LUC';
ALTER TABLE flowable.wf_binding ADD CONSTRAINT ck_wf_binding__version_no CHECK (version_no >= 1);
ALTER TABLE flowable.wf_binding ADD CONSTRAINT ck_wf_binding__status
  CHECK (status IN ('NHAP', 'HIEU_LUC', 'NGUNG'));
COMMENT ON COLUMN flowable.wf_binding.version_no IS '{"cls":"C0","desc":"Phiên bản của bộ tham số (bổ sung BS-01)"}';
COMMENT ON COLUMN flowable.wf_binding.status IS '{"cls":"C0","desc":"Trạng thái: NHAP, HIEU_LUC, NGUNG (bổ sung BS-01)"}';

-- Đọc chéo tenant phạm vi nền tảng (SCR-1505 scope N): chỉ SELECT, chỉ khi giao dịch tự đặt cờ phạm vi.
CREATE POLICY rls_wf_binding__nen_tang_doc ON flowable.wf_binding AS PERMISSIVE FOR SELECT TO svc_flowable
  USING (current_setting('app.platform_scope', true) = 'wf_template_usage');

-- -----------------------------------------------------------------------------
-- flowable.wf_template_rollout (bổ sung BS-01): tiến độ triển khai một phiên bản mẫu lên từng cell.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_template_rollout (
  id                     uuid         NOT NULL,
  tenant_id              uuid         NOT NULL,
  wf_template_id         uuid         NOT NULL,
  cell_code              varchar(32)  NOT NULL,
  seq_no                 int          NOT NULL,
  flowable_deployment_id varchar(64),
  status                 varchar(16)  NOT NULL,
  deployed_at            timestamptz,
  previous_version_no    int,
  saga_instance_id       uuid,
  last_error             varchar(500),
  created_at             timestamptz  NOT NULL DEFAULT now(),
  created_by             uuid         NOT NULL,
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  updated_by             uuid         NOT NULL,
  row_version            int          NOT NULL DEFAULT 1,
  CONSTRAINT pk_wf_template_rollout PRIMARY KEY (id),
  CONSTRAINT uq_wf_template_rollout__template_cell UNIQUE (wf_template_id, cell_code),
  CONSTRAINT fk_wf_template_rollout__wf_template FOREIGN KEY (wf_template_id) REFERENCES flowable.wf_template (id),
  CONSTRAINT ck_wf_template_rollout__status CHECK (status IN ('CHO', 'DA_TRIEN_KHAI', 'LOI', 'DA_BU_TRU')),
  CONSTRAINT ck_wf_template_rollout__seq_no CHECK (seq_no >= 1)
);
COMMENT ON TABLE flowable.wf_template_rollout IS 'Tiến độ triển khai phiên bản mẫu luồng lên engine từng cell (bổ sung BS-01)';
COMMENT ON COLUMN flowable.wf_template_rollout.id IS '{"cls":"C1","desc":"Mã bản ghi"}';
COMMENT ON COLUMN flowable.wf_template_rollout.tenant_id IS '{"cls":"C1","desc":"Tenant nền tảng sở hữu mẫu; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_template_rollout.wf_template_id IS '{"cls":"C1","desc":"Phiên bản mẫu được triển khai"}';
COMMENT ON COLUMN flowable.wf_template_rollout.cell_code IS '{"cls":"C0","desc":"Mã cell theo thứ tự cuốn chiếu cấu hình"}';
COMMENT ON COLUMN flowable.wf_template_rollout.seq_no IS '{"cls":"C0","desc":"Thứ tự cuốn chiếu (bổ sung DV-06-09)"}';
COMMENT ON COLUMN flowable.wf_template_rollout.flowable_deployment_id IS '{"cls":"C0","desc":"Mã triển khai trên engine của cell"}';
COMMENT ON COLUMN flowable.wf_template_rollout.status IS '{"cls":"C0","desc":"Trạng thái: CHO, DA_TRIEN_KHAI, LOI, DA_BU_TRU"}';
COMMENT ON COLUMN flowable.wf_template_rollout.deployed_at IS '{"cls":"C1","desc":"Thời điểm triển khai xong"}';
COMMENT ON COLUMN flowable.wf_template_rollout.previous_version_no IS '{"cls":"C0","desc":"Phiên bản mặc định trước đó trên cell, đặt lại khi bù trừ (bổ sung DV-06-09)"}';
COMMENT ON COLUMN flowable.wf_template_rollout.saga_instance_id IS '{"cls":"C1","desc":"Saga phát hành điều phối (bổ sung DV-06-09)"}';
COMMENT ON COLUMN flowable.wf_template_rollout.last_error IS '{"cls":"C1","desc":"Lỗi gần nhất, không chứa dữ liệu C2 (bổ sung DV-06-09)"}';
COMMENT ON COLUMN flowable.wf_template_rollout.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_template_rollout.created_by IS '{"cls":"C1","desc":"Người hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN flowable.wf_template_rollout.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template_rollout.updated_by IS '{"cls":"C1","desc":"Người cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template_rollout.row_version IS '{"cls":"C1","desc":"Khoá lạc quan"}';
CREATE INDEX ix_wf_template_rollout__tenant_template ON flowable.wf_template_rollout (tenant_id, wf_template_id, seq_no);

-- -----------------------------------------------------------------------------
-- flowable.wf_template_test_run (bổ sung DV-06-09): một lần chạy bộ test và thử tải rút gọn trên engine thử.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_template_test_run (
  id                     uuid         NOT NULL,
  tenant_id              uuid         NOT NULL,
  wf_template_id         uuid         NOT NULL,
  bpmn_sha256            bytea        NOT NULL,
  status                 varchar(12)  NOT NULL,
  total_cases            int          NOT NULL DEFAULT 0,
  passed_cases           int          NOT NULL DEFAULT 0,
  binding_count          int          NOT NULL DEFAULT 0,
  max_sql_per_completion int          NOT NULL DEFAULT 0,
  sql_budget             int          NOT NULL,
  results_json           jsonb        NOT NULL DEFAULT '[]'::jsonb,
  created_at             timestamptz  NOT NULL DEFAULT now(),
  created_by             uuid         NOT NULL,
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  updated_by             uuid         NOT NULL,
  row_version            int          NOT NULL DEFAULT 1,
  CONSTRAINT pk_wf_template_test_run PRIMARY KEY (id),
  CONSTRAINT fk_wf_template_test_run__wf_template FOREIGN KEY (wf_template_id) REFERENCES flowable.wf_template (id),
  CONSTRAINT ck_wf_template_test_run__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI')),
  CONSTRAINT ck_wf_template_test_run__sha256 CHECK (octet_length(bpmn_sha256) = 32)
);
COMMENT ON TABLE flowable.wf_template_test_run IS 'Lần chạy bộ test kịch bản và thử tải rút gọn của mẫu trên engine thử (bổ sung DV-06-09)';
COMMENT ON COLUMN flowable.wf_template_test_run.id IS '{"cls":"C1","desc":"Mã lần chạy, cũng là mã tác vụ dài của getJob"}';
COMMENT ON COLUMN flowable.wf_template_test_run.tenant_id IS '{"cls":"C1","desc":"Tenant nền tảng; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_template_test_run.wf_template_id IS '{"cls":"C1","desc":"Phiên bản mẫu được kiểm thử"}';
COMMENT ON COLUMN flowable.wf_template_test_run.bpmn_sha256 IS '{"cls":"C0","desc":"Băm nội dung BPMN đã kiểm thử"}';
COMMENT ON COLUMN flowable.wf_template_test_run.status IS '{"cls":"C0","desc":"Trạng thái: CHO, DANG_CHAY, HOAN_TAT, THAT_BAI"}';
COMMENT ON COLUMN flowable.wf_template_test_run.total_cases IS '{"cls":"C0","desc":"Số ca đã chạy (kịch bản nhân bộ tham số)"}';
COMMENT ON COLUMN flowable.wf_template_test_run.passed_cases IS '{"cls":"C0","desc":"Số ca đạt"}';
COMMENT ON COLUMN flowable.wf_template_test_run.binding_count IS '{"cls":"C0","desc":"Số bộ tham số wf_binding đã dùng"}';
COMMENT ON COLUMN flowable.wf_template_test_run.max_sql_per_completion IS '{"cls":"C0","desc":"Số câu SQL lớn nhất của một lần hoàn thành user task"}';
COMMENT ON COLUMN flowable.wf_template_test_run.sql_budget IS '{"cls":"C0","desc":"Ngân sách câu SQL mỗi lần hoàn thành (BS-01: 20)"}';
COMMENT ON COLUMN flowable.wf_template_test_run.results_json IS '{"cls":"C0","desc":"Kết quả từng ca; không chứa dữ liệu nghiệp vụ"}';
COMMENT ON COLUMN flowable.wf_template_test_run.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_template_test_run.created_by IS '{"cls":"C1","desc":"Người yêu cầu chạy test"}';
COMMENT ON COLUMN flowable.wf_template_test_run.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template_test_run.updated_by IS '{"cls":"C1","desc":"Người hoặc dịch vụ cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template_test_run.row_version IS '{"cls":"C1","desc":"Khoá lạc quan"}';
CREATE INDEX ix_wf_template_test_run__template ON flowable.wf_template_test_run (tenant_id, wf_template_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- flowable.wf_template_release (bổ sung DV-06-09): merge request vào repo mẫu luồng và kết quả ký của pipeline.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_template_release (
  id                  uuid         NOT NULL,
  tenant_id           uuid         NOT NULL,
  wf_template_id      uuid         NOT NULL,
  merge_request_ref   varchar(128) NOT NULL,
  merge_request_url   varchar(512),
  status              varchar(12)  NOT NULL,
  submitted_by        uuid         NOT NULL,
  approved_by         uuid,
  saga_instance_id    uuid,
  signature           bytea,
  pipeline_ref        varchar(128),
  signed_at           timestamptz,
  created_at          timestamptz  NOT NULL DEFAULT now(),
  created_by          uuid         NOT NULL,
  updated_at          timestamptz  NOT NULL DEFAULT now(),
  updated_by          uuid         NOT NULL,
  row_version         int          NOT NULL DEFAULT 1,
  CONSTRAINT pk_wf_template_release PRIMARY KEY (id),
  CONSTRAINT uq_wf_template_release__template UNIQUE (wf_template_id),
  CONSTRAINT fk_wf_template_release__wf_template FOREIGN KEY (wf_template_id) REFERENCES flowable.wf_template (id),
  CONSTRAINT ck_wf_template_release__status CHECK (status IN ('MO', 'DA_DUYET', 'DA_KY', 'DA_AP_DUNG')),
  CONSTRAINT ck_wf_template_release__four_eyes CHECK (approved_by IS NULL OR approved_by <> submitted_by),
  CONSTRAINT ck_wf_template_release__signed CHECK ((status IN ('DA_KY', 'DA_AP_DUNG')) = (signature IS NOT NULL))
);
COMMENT ON TABLE flowable.wf_template_release IS 'Đề nghị phát hành mẫu luồng: merge request, phê duyệt, chữ ký cosign của pipeline (bổ sung DV-06-09)';
COMMENT ON COLUMN flowable.wf_template_release.id IS '{"cls":"C1","desc":"Mã bản ghi"}';
COMMENT ON COLUMN flowable.wf_template_release.tenant_id IS '{"cls":"C1","desc":"Tenant nền tảng; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_template_release.wf_template_id IS '{"cls":"C1","desc":"Phiên bản mẫu đề nghị phát hành"}';
COMMENT ON COLUMN flowable.wf_template_release.merge_request_ref IS '{"cls":"C0","desc":"Mã merge request trong hệ quản lý mã"}';
COMMENT ON COLUMN flowable.wf_template_release.merge_request_url IS '{"cls":"C0","desc":"Đường dẫn merge request"}';
COMMENT ON COLUMN flowable.wf_template_release.status IS '{"cls":"C0","desc":"Trạng thái: MO, DA_DUYET, DA_KY, DA_AP_DUNG"}';
COMMENT ON COLUMN flowable.wf_template_release.submitted_by IS '{"cls":"C1","desc":"Người đề nghị phát hành"}';
COMMENT ON COLUMN flowable.wf_template_release.approved_by IS '{"cls":"C1","desc":"Người phê duyệt, khác người đề nghị"}';
COMMENT ON COLUMN flowable.wf_template_release.saga_instance_id IS '{"cls":"C1","desc":"Saga phát hành cuốn chiếu"}';
COMMENT ON COLUMN flowable.wf_template_release.signature IS '{"cls":"C0","desc":"Chữ ký cosign của gói mẫu do pipeline báo"}';
COMMENT ON COLUMN flowable.wf_template_release.pipeline_ref IS '{"cls":"C0","desc":"Mã lần chạy pipeline đã ký"}';
COMMENT ON COLUMN flowable.wf_template_release.signed_at IS '{"cls":"C1","desc":"Thời điểm pipeline báo đã ký"}';
COMMENT ON COLUMN flowable.wf_template_release.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_template_release.created_by IS '{"cls":"C1","desc":"Người hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN flowable.wf_template_release.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template_release.updated_by IS '{"cls":"C1","desc":"Người hoặc dịch vụ cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_template_release.row_version IS '{"cls":"C1","desc":"Khoá lạc quan"}';
CREATE INDEX ix_wf_template_release__status ON flowable.wf_template_release (tenant_id, status);

-- -----------------------------------------------------------------------------
-- flowable.wf_code_host_command (bổ sung DV-06-09): hộp thư lệnh gửi hệ quản lý mã (mở, duyệt merge request) cho
-- bộ chuyển mặc định; tác vụ đồng bộ với repo mẫu luồng đọc và đánh dấu đã xử lý.
-- -----------------------------------------------------------------------------
CREATE TABLE flowable.wf_code_host_command (
  id                uuid         NOT NULL,
  tenant_id         uuid         NOT NULL,
  wf_template_id    uuid         NOT NULL,
  command           varchar(16)  NOT NULL,
  merge_request_ref varchar(128) NOT NULL,
  payload_json      jsonb        NOT NULL,
  status            varchar(12)  NOT NULL DEFAULT 'CHO',
  processed_at      timestamptz,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  created_by        uuid         NOT NULL,
  CONSTRAINT pk_wf_code_host_command PRIMARY KEY (id),
  CONSTRAINT uq_wf_code_host_command__template_command UNIQUE (wf_template_id, command),
  CONSTRAINT ck_wf_code_host_command__command CHECK (command IN ('MO_MR', 'DUYET_MR')),
  CONSTRAINT ck_wf_code_host_command__status CHECK (status IN ('CHO', 'DA_XU_LY'))
);
COMMENT ON TABLE flowable.wf_code_host_command IS 'Hộp thư lệnh gửi hệ quản lý mã của repo mẫu luồng (bổ sung DV-06-09)';
COMMENT ON COLUMN flowable.wf_code_host_command.id IS '{"cls":"C1","desc":"Mã lệnh"}';
COMMENT ON COLUMN flowable.wf_code_host_command.tenant_id IS '{"cls":"C1","desc":"Tenant nền tảng; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_code_host_command.wf_template_id IS '{"cls":"C1","desc":"Phiên bản mẫu"}';
COMMENT ON COLUMN flowable.wf_code_host_command.command IS '{"cls":"C0","desc":"Lệnh: MO_MR (mở merge request), DUYET_MR (duyệt và gộp)"}';
COMMENT ON COLUMN flowable.wf_code_host_command.merge_request_ref IS '{"cls":"C0","desc":"Mã merge request"}';
COMMENT ON COLUMN flowable.wf_code_host_command.payload_json IS '{"cls":"C0","desc":"Nội dung lệnh: nhánh, tệp BPMN, khai báo tham số, bộ test"}';
COMMENT ON COLUMN flowable.wf_code_host_command.status IS '{"cls":"C0","desc":"Trạng thái: CHO, DA_XU_LY"}';
COMMENT ON COLUMN flowable.wf_code_host_command.processed_at IS '{"cls":"C1","desc":"Thời điểm tác vụ đồng bộ xử lý xong"}';
COMMENT ON COLUMN flowable.wf_code_host_command.created_at IS '{"cls":"C1","desc":"Thời điểm tạo lệnh"}';
COMMENT ON COLUMN flowable.wf_code_host_command.created_by IS '{"cls":"C1","desc":"Người hoặc dịch vụ tạo lệnh"}';

-- -----------------------------------------------------------------------------
-- RLS và quyền cho bốn bảng mới (cùng mẫu với wf_binding_draft của V6).
-- -----------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['wf_template_rollout', 'wf_template_test_run', 'wf_template_release',
                           'wf_code_host_command']
  LOOP
    EXECUTE format('ALTER TABLE flowable.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE flowable.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY %I ON flowable.%I AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable '
      'USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      'rls_' || t || '__tenant', t);
    EXECUTE format('REVOKE ALL ON flowable.%I FROM PUBLIC', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON flowable.%I TO svc_flowable', t);
    EXECUTE format('GRANT SELECT ON flowable.%I TO ro_flowable', t);
  END LOOP;
END $$;

-- -----------------------------------------------------------------------------
-- flowable.saga_instance: loại saga phát hành mẫu luồng cuốn chiếu.
-- -----------------------------------------------------------------------------
ALTER TABLE flowable.saga_instance DROP CONSTRAINT ck_saga_instance__saga_type;
ALTER TABLE flowable.saga_instance ADD CONSTRAINT ck_saga_instance__saga_type
  CHECK (saga_type IN ('CHOT_KY', 'MO_LAI_HAU_KIEM', 'TOT_NGHIEP_TENANT', 'DONG_GOI_HO_SO',
                       'PHAT_HANH_MAU_LUONG'));
