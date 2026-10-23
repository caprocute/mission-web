-- =============================================================================
-- V6__wf_binding_nhap_theo_goi.sql
-- DV-06-08 · SVC-06 · TL-02b SCR-1504 ("Tham số được phát hành cùng Policy Pack") · TL-03 schema flowable.
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
--
-- Ghi chú lệch nguồn (wiki trang 14, mục DV-06-08):
--   * TL-03 đặt khoá wf_binding là (tenant_id, process_kind, subject_group_id, flow, period_type) và không có
--     cột phiên bản Policy Pack, trong khi hợp đồng đặt tham số dưới /policies/packs/{id}/versions/{v}. Bảng bổ
--     sung wf_binding_draft giữ tham số theo từng phiên bản gói; saga phát hành gói (CORE-05-10 bước 4) chép
--     sang wf_binding và nhớ dòng bị thay để gỡ khi bù trừ hoặc thu hồi. Không thêm version_no, status vào
--     wf_binding (BS-01, DV-06-09).
-- =============================================================================

SET search_path TO flowable;

CREATE TABLE flowable.wf_binding_draft (
  id                     uuid         NOT NULL,
  tenant_id              uuid         NOT NULL,
  policy_pack_version_id uuid         NOT NULL,
  process_kind           varchar(32)  NOT NULL,
  template_code          varchar(64)  NOT NULL,
  template_version_no    int          NOT NULL,
  scope_json             jsonb        NOT NULL,
  params_json            jsonb        NOT NULL,
  lint_json              jsonb        NOT NULL,
  tests_json             jsonb        NOT NULL,
  status                 varchar(12)  NOT NULL,
  wf_binding_id          uuid,
  replaced_json          jsonb,
  activated_at           timestamptz,
  created_at             timestamptz  NOT NULL DEFAULT now(),
  created_by             uuid         NOT NULL,
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  updated_by             uuid         NOT NULL,
  row_version            int          NOT NULL DEFAULT 1,
  CONSTRAINT pk_wf_binding_draft PRIMARY KEY (id),
  CONSTRAINT uq_wf_binding_draft__version_kind UNIQUE (tenant_id, policy_pack_version_id, process_kind),
  CONSTRAINT ck_wf_binding_draft__status CHECK (status IN ('NHAP', 'HIEU_LUC', 'NGUNG')),
  CONSTRAINT ck_wf_binding_draft__template_version CHECK (template_version_no >= 1),
  CONSTRAINT ck_wf_binding_draft__active CHECK ((status = 'HIEU_LUC') = (wf_binding_id IS NOT NULL))
);
COMMENT ON TABLE flowable.wf_binding_draft IS 'Tham số luồng duyệt theo phiên bản Policy Pack, chép sang wf_binding khi phát hành gói (bổ sung, DV-06-08)';
COMMENT ON COLUMN flowable.wf_binding_draft.id IS '{"cls":"C1","desc":"Mã bản ghi"}';
COMMENT ON COLUMN flowable.wf_binding_draft.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN flowable.wf_binding_draft.policy_pack_version_id IS '{"cls":"C1","desc":"Phiên bản Policy Pack sở hữu tham số (policy.policy_pack_version.id)"}';
COMMENT ON COLUMN flowable.wf_binding_draft.process_kind IS '{"cls":"C0","desc":"Loại nghiệp vụ"}';
COMMENT ON COLUMN flowable.wf_binding_draft.template_code IS '{"cls":"C0","desc":"Mã mẫu đã chọn"}';
COMMENT ON COLUMN flowable.wf_binding_draft.template_version_no IS '{"cls":"C0","desc":"Phiên bản mẫu đã chọn"}';
COMMENT ON COLUMN flowable.wf_binding_draft.scope_json IS '{"cls":"C1","desc":"Phạm vi áp dụng: luồng, khối đối tượng, loại kỳ, kỳ bắt đầu"}';
COMMENT ON COLUMN flowable.wf_binding_draft.params_json IS '{"cls":"C1","desc":"Tham số truyền vào tiến trình"}';
COMMENT ON COLUMN flowable.wf_binding_draft.lint_json IS '{"cls":"C0","desc":"Kết quả linter tham số lúc lưu"}';
COMMENT ON COLUMN flowable.wf_binding_draft.tests_json IS '{"cls":"C0","desc":"Kết quả bộ hồ sơ mẫu trên engine thử lúc lưu"}';
COMMENT ON COLUMN flowable.wf_binding_draft.status IS '{"cls":"C0","desc":"Trạng thái: nháp, hiệu lực, ngừng"}';
COMMENT ON COLUMN flowable.wf_binding_draft.wf_binding_id IS '{"cls":"C1","desc":"Dòng wf_binding đang mang tham số khi hiệu lực"}';
COMMENT ON COLUMN flowable.wf_binding_draft.replaced_json IS '{"cls":"C1","desc":"Giá trị cũ của dòng wf_binding bị thay, để gỡ khi bù trừ"}';
COMMENT ON COLUMN flowable.wf_binding_draft.activated_at IS '{"cls":"C1","desc":"Thời điểm chép sang wf_binding"}';
COMMENT ON COLUMN flowable.wf_binding_draft.created_at IS '{"cls":"C1","desc":"Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN flowable.wf_binding_draft.created_by IS '{"cls":"C1","desc":"Người hoặc tài khoản dịch vụ tạo bản ghi"}';
COMMENT ON COLUMN flowable.wf_binding_draft.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_binding_draft.updated_by IS '{"cls":"C1","desc":"Người cập nhật gần nhất"}';
COMMENT ON COLUMN flowable.wf_binding_draft.row_version IS '{"cls":"C1","desc":"Khoá lạc quan; ETag của API"}';
CREATE INDEX ix_wf_binding_draft__binding ON flowable.wf_binding_draft (tenant_id, wf_binding_id);
ALTER TABLE flowable.wf_binding_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE flowable.wf_binding_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_wf_binding_draft__tenant ON flowable.wf_binding_draft AS PERMISSIVE FOR ALL
  TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON flowable.wf_binding_draft FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON flowable.wf_binding_draft TO svc_flowable;
GRANT SELECT ON flowable.wf_binding_draft TO ro_flowable;
