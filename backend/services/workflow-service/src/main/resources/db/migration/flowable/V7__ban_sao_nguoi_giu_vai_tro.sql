-- =============================================================================
-- V7__ban_sao_nguoi_giu_vai_tro.sql
-- DV-06-04 · SVC-06 · bốn mẫu luồng phần 2 (duyet-ho-so-danh-gia, xu-ly-khieu-nai, mo-lai-ky-hai-nguoi,
-- duyet-phat-hanh-policy-pack) · TL-03 "Schema org_replica".
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp.
--
-- Ghi chú lệch nguồn (wiki trang 14, mục DV-06-04):
--   * Consumer EVT-11, EVT-13, EVT-18, EVT-25 dùng flowable.inbox_processed, flowable.inbox_aggregate_version
--     đã có từ V4.
--   * org_replica.role_holder_v là bảng bổ sung (suy luận): bản sao người giữ vai trò có hiệu lực theo đơn
--     vị (nguồn config.role_binding của MOD-08) để worker chọn người tách vai trò và ít việc nhất. Consumer
--     cập nhật bảng này thuộc task sau, như org_unit_v ở V5.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- org_replica.role_holder_v (bảng bổ sung): người giữ vai trò có hiệu lực tại đơn vị.
-- -----------------------------------------------------------------------------
CREATE TABLE org_replica.role_holder_v (
  tenant_id       uuid        NOT NULL,
  org_unit_id     uuid        NOT NULL,
  role_code       varchar(32) NOT NULL,
  user_id         uuid        NOT NULL,
  valid_from      date        NOT NULL,
  valid_to        date        NOT NULL DEFAULT 'infinity',
  source_version  bigint      NOT NULL,
  source_event_id uuid        NOT NULL,
  replicated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_role_holder_v PRIMARY KEY (tenant_id, org_unit_id, role_code, user_id, valid_from),
  CONSTRAINT ck_role_holder_v__valid CHECK (valid_from < valid_to)
);
COMMENT ON TABLE org_replica.role_holder_v IS 'Bản sao người giữ vai trò theo đơn vị của workflow-service (bảng bổ sung, nguồn config.role_binding)';
COMMENT ON COLUMN org_replica.role_holder_v.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org_replica.role_holder_v.org_unit_id IS '{"cls":"C1","desc":"Đơn vị nơi giữ vai trò"}';
COMMENT ON COLUMN org_replica.role_holder_v.role_code IS '{"cls":"C1","desc":"Vai trò baseline"}';
COMMENT ON COLUMN org_replica.role_holder_v.user_id IS '{"cls":"C1","desc":"Người giữ vai trò"}';
COMMENT ON COLUMN org_replica.role_holder_v.valid_from IS '{"cls":"C1","desc":"Bắt đầu hiệu lực"}';
COMMENT ON COLUMN org_replica.role_holder_v.valid_to IS '{"cls":"C1","desc":"Hết hiệu lực (không gồm ngày này)"}';
COMMENT ON COLUMN org_replica.role_holder_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN org_replica.role_holder_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN org_replica.role_holder_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
CREATE INDEX ix_role_holder_v__unit_role ON org_replica.role_holder_v (tenant_id, role_code, org_unit_id, valid_to);
ALTER TABLE org_replica.role_holder_v ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_replica.role_holder_v FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_role_holder_v__tenant ON org_replica.role_holder_v AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON org_replica.role_holder_v FROM PUBLIC;
GRANT SELECT ON org_replica.role_holder_v TO svc_flowable, ro_flowable;
