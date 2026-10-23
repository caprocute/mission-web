-- =============================================================================
-- V13__org_replica_don_vi_nhan_su.sql
-- DV-06-10 (nối tiếp) · SVC-06 · TL-03 "Schema org_replica" (bảng org_unit_v, person_v) · wiki trang 18
-- "Chuyển trạng thái qua sự kiện (ECST)", EVT-14 org.unit.changed.v1, EVT-16 org.person.changed.v1.
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp. V8, V10 thuộc nhánh khác.
--
-- Ghi chú (wiki trang 14, mục DV-06-10):
--   * Consumer EVT-14 và phần đơn vị của org.bootstrap.v1 upsert org_replica.org_unit_v (V5) theo
--     source_version, nên svc_flowable cần INSERT, UPDATE (V5 chỉ cấp SELECT). Không cấp DELETE: đơn vị giải
--     thể biểu diễn bằng valid_to, sự kiện không mang cờ xoá.
--   * org_replica.person_v theo đúng cột TL-03 (không có cột C2). Consumer EVT-16 và phần nhân sự của
--     org.bootstrap.v1 điền bảng này. cbccvc_code để trống vì EVT-16 không mang trường này (suy luận).
--   * Chỉ mục phụ trên (tenant_id, valid_to) cho bước dò vòng trên cây hiện hành (phiên bản mở, valid_to
--     = 'infinity') khi áp EVT-14; không lập bảng bao đóng unit_closure_v như mission-core (suy luận: cây
--     chỉ được đi lên theo khoá chính khi phân giải cấp duyệt).
-- =============================================================================

GRANT INSERT, UPDATE ON org_replica.org_unit_v TO svc_flowable;

CREATE INDEX ix_org_unit_v__open ON org_replica.org_unit_v (tenant_id, id) WHERE valid_to = 'infinity';

-- -----------------------------------------------------------------------------
-- org_replica.person_v: bản sao nhân sự (TL-03 "Bảng org_replica.person_v").
-- -----------------------------------------------------------------------------
CREATE TABLE org_replica.person_v (
  tenant_id         uuid         NOT NULL,
  id                uuid         NOT NULL,
  full_name         varchar(200) NOT NULL,
  is_party_member   boolean      NOT NULL,
  cbccvc_code       varchar(32),
  employment_status varchar(16)  NOT NULL,
  source_version    bigint       NOT NULL,
  source_event_id   uuid         NOT NULL,
  replicated_at     timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_person_v PRIMARY KEY (tenant_id, id)
);
COMMENT ON TABLE org_replica.person_v IS 'Bản sao nhân sự của workflow-service (TL-03 schema org_replica), không có cột C2';
COMMENT ON COLUMN org_replica.person_v.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org_replica.person_v.id IS '{"cls":"C1","desc":"Mã người"}';
COMMENT ON COLUMN org_replica.person_v.full_name IS '{"cls":"C1","desc":"Họ và tên"}';
COMMENT ON COLUMN org_replica.person_v.is_party_member IS '{"cls":"C1","desc":"Đảng viên"}';
COMMENT ON COLUMN org_replica.person_v.cbccvc_code IS '{"cls":"C1","desc":"Mã CBCCVC"}';
COMMENT ON COLUMN org_replica.person_v.employment_status IS '{"cls":"C1","desc":"Tình trạng công tác"}';
COMMENT ON COLUMN org_replica.person_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN org_replica.person_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN org_replica.person_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
ALTER TABLE org_replica.person_v ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_replica.person_v FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_person_v__tenant ON org_replica.person_v AS PERMISSIVE FOR ALL TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON org_replica.person_v FROM PUBLIC;
GRANT SELECT ON org_replica.person_v TO svc_flowable, ro_flowable;
GRANT INSERT, UPDATE ON org_replica.person_v TO svc_flowable;
