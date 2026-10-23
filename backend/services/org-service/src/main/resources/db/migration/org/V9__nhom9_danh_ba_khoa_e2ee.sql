-- CORE-07-06 phần A, D · ADR-27 · TL-01 "Quản lý khoá người nhận", "Khôi phục khi mất khoá"; TL-04 SEC-25.
-- Danh bạ khoá công khai có ký: chữ ký của khoá danh bạ tenant (OpenBao Transit, ECDSA P-256) trên chuỗi chuẩn của
-- bản ghi, để máy chủ không lặng lẽ thay khoá của ai đó mà không để lại dấu vết kiểm tra được. Khoá phục hồi của
-- tổ chức và nhật ký các lần phục hồi là bảng bổ sung (TL-03 chưa có; suy luận, ghi ở wiki trang 14). Máy chủ chỉ
-- giữ khoá công khai và nhật ký; khoá riêng phục hồi chia Shamir 3 trên 5 ở máy trạm cách ly, không đi qua đây.

ALTER TABLE org.person_public_key
  ADD COLUMN sig_key_version int NOT NULL,
  ADD COLUMN signature       bytea NOT NULL,
  ADD COLUMN signed_at       timestamptz NOT NULL,
  ADD COLUMN revoked_at      timestamptz,
  ADD COLUMN revoked_by      uuid,
  ADD COLUMN revoke_reason   varchar(1000),
  ADD CONSTRAINT ck_person_public_key__revoked CHECK ((status = 'THU_HOI') = (revoked_at IS NOT NULL));
COMMENT ON COLUMN org.person_public_key.sig_key_version IS '{"cls": "C0", "pii": false, "desc": "Phiên bản khoá ký của danh bạ tenant"}';
COMMENT ON COLUMN org.person_public_key.signature IS '{"cls": "C0", "pii": false, "desc": "Chữ ký ECDSA P-256 (DER) của danh bạ trên chuỗi chuẩn của bản ghi"}';
COMMENT ON COLUMN org.person_public_key.signed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ký"}';
COMMENT ON COLUMN org.person_public_key.revoked_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm thu hồi"}';
COMMENT ON COLUMN org.person_public_key.revoked_by IS '{"cls": "C1", "pii": false, "desc": "Người thu hồi (chủ khoá hoặc quản trị tenant)"}';
COMMENT ON COLUMN org.person_public_key.revoke_reason IS '{"cls": "C1", "pii": false, "desc": "Lý do thu hồi"}';

CREATE TABLE org.recovery_key (
  id               uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  key_alg          varchar(24) NOT NULL,
  public_key_spki  bytea NOT NULL,
  fingerprint      bytea NOT NULL,
  threshold        smallint NOT NULL,
  custodian_ids    uuid[] NOT NULL,
  ceremony_ref     varchar(200) NOT NULL,
  status           varchar(12) NOT NULL,
  valid_from       timestamptz NOT NULL,
  valid_to         timestamptz,
  sig_key_version  int NOT NULL,
  signature        bytea NOT NULL,
  signed_at        timestamptz NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid NOT NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid NOT NULL,
  row_version      int NOT NULL DEFAULT 0,
  CONSTRAINT pk_recovery_key PRIMARY KEY (id),
  CONSTRAINT uq_recovery_key__fingerprint UNIQUE (tenant_id, fingerprint),
  CONSTRAINT ck_recovery_key__key_alg CHECK (key_alg IN ('ECDH-P256','RSA-OAEP-3072')),
  CONSTRAINT ck_recovery_key__fingerprint CHECK (octet_length(fingerprint) = 32),
  CONSTRAINT ck_recovery_key__custodians CHECK (cardinality(custodian_ids) = 5),
  CONSTRAINT ck_recovery_key__threshold CHECK (threshold = 3),
  CONSTRAINT ck_recovery_key__status CHECK (status IN ('HIEU_LUC','THU_HOI')),
  CONSTRAINT ck_recovery_key__valid_to CHECK ((status = 'THU_HOI') = (valid_to IS NOT NULL))
);
COMMENT ON TABLE org.recovery_key IS 'Khoá công khai phục hồi của tổ chức cho dữ liệu C3, chia Shamir 3 trên 5 (bảng bổ sung CORE-07-06)';
COMMENT ON COLUMN org.recovery_key.id IS '{"cls": "C1", "pii": false, "desc": "Mã khoá (UUIDv7); là mã người nhận đại diện trong bản bọc khoá nội dung"}';
COMMENT ON COLUMN org.recovery_key.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.recovery_key.key_alg IS '{"cls": "C1", "pii": false, "desc": "Thuật toán khoá công khai"}';
COMMENT ON COLUMN org.recovery_key.public_key_spki IS '{"cls": "C0", "pii": false, "desc": "Khoá công khai dạng SubjectPublicKeyInfo DER"}';
COMMENT ON COLUMN org.recovery_key.fingerprint IS '{"cls": "C0", "pii": false, "desc": "SHA-256 của SPKI"}';
COMMENT ON COLUMN org.recovery_key.threshold IS '{"cls": "C1", "pii": false, "desc": "Số mảnh tối thiểu để phục hồi"}';
COMMENT ON COLUMN org.recovery_key.custodian_ids IS '{"cls": "C1", "pii": false, "desc": "Năm cán bộ giữ mảnh khoá (org.person.id)"}';
COMMENT ON COLUMN org.recovery_key.ceremony_ref IS '{"cls": "C1", "pii": false, "desc": "Số biên bản tạo và giao mảnh khoá"}';
COMMENT ON COLUMN org.recovery_key.status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái khoá"}';
COMMENT ON COLUMN org.recovery_key.valid_from IS '{"cls": "C1", "pii": false, "desc": "Hiệu lực từ"}';
COMMENT ON COLUMN org.recovery_key.valid_to IS '{"cls": "C1", "pii": false, "desc": "Thời điểm bị thay bằng khoá mới"}';
COMMENT ON COLUMN org.recovery_key.sig_key_version IS '{"cls": "C0", "pii": false, "desc": "Phiên bản khoá ký của danh bạ tenant"}';
COMMENT ON COLUMN org.recovery_key.signature IS '{"cls": "C0", "pii": false, "desc": "Chữ ký ECDSA P-256 (DER) của danh bạ trên chuỗi chuẩn của bản ghi"}';
COMMENT ON COLUMN org.recovery_key.signed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ký"}';
COMMENT ON COLUMN org.recovery_key.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm tạo bản ghi theo đồng hồ CSDL"}';
COMMENT ON COLUMN org.recovery_key.created_by IS '{"cls": "C1", "pii": false, "desc": "Quản trị tenant khai báo khoá"}';
COMMENT ON COLUMN org.recovery_key.updated_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN org.recovery_key.updated_by IS '{"cls": "C1", "pii": false, "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN org.recovery_key.row_version IS '{"cls": "C1", "pii": false, "desc": "Khoá lạc quan; ETag của API"}';
CREATE UNIQUE INDEX uq_recovery_key__active ON org.recovery_key (tenant_id) WHERE status = 'HIEU_LUC';
ALTER TABLE org.recovery_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.recovery_key FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_recovery_key__tenant ON org.recovery_key AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.recovery_key FROM PUBLIC;
GRANT SELECT, INSERT ON org.recovery_key TO svc_org;
GRANT UPDATE (status, valid_to, updated_at, updated_by, row_version) ON org.recovery_key TO svc_org;
GRANT SELECT ON org.recovery_key TO ro_org;

CREATE TABLE org.e2ee_recovery_log (
  id                   uuid NOT NULL,
  tenant_id            uuid NOT NULL,
  recovery_key_id      uuid NOT NULL,
  object_type          varchar(16) NOT NULL,
  object_id            uuid NOT NULL,
  new_recipient_id     uuid NOT NULL,
  new_key_fingerprint  bytea NOT NULL,
  custodian_ids        uuid[] NOT NULL,
  reason               varchar(2000) NOT NULL,
  ceremony_ref         varchar(200) NOT NULL,
  performed_at         timestamptz NOT NULL,
  sig_key_version      int NOT NULL,
  signature            bytea NOT NULL,
  signed_at            timestamptz NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid NOT NULL,
  CONSTRAINT pk_e2ee_recovery_log PRIMARY KEY (id),
  CONSTRAINT fk_e2ee_recovery_log__recovery_key FOREIGN KEY (recovery_key_id) REFERENCES org.recovery_key (id),
  CONSTRAINT ck_e2ee_recovery_log__object_type CHECK (object_type IN ('TASK_CONTENT','EVIDENCE_FILE')),
  CONSTRAINT ck_e2ee_recovery_log__fingerprint CHECK (octet_length(new_key_fingerprint) = 32),
  CONSTRAINT ck_e2ee_recovery_log__custodians CHECK (cardinality(custodian_ids) BETWEEN 3 AND 5)
);
COMMENT ON TABLE org.e2ee_recovery_log IS 'Nhật ký các lần phục hồi tổ chức khoá nội dung C3, chỉ thêm (bảng bổ sung CORE-07-06)';
COMMENT ON COLUMN org.e2ee_recovery_log.id IS '{"cls": "C1", "pii": false, "desc": "Mã lần phục hồi (UUIDv7)"}';
COMMENT ON COLUMN org.e2ee_recovery_log.tenant_id IS '{"cls": "C1", "pii": false, "desc": "Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org.e2ee_recovery_log.recovery_key_id IS '{"cls": "C1", "pii": false, "desc": "Khoá phục hồi đã dùng"}';
COMMENT ON COLUMN org.e2ee_recovery_log.object_type IS '{"cls": "C1", "pii": false, "desc": "Loại đối tượng có khoá nội dung được bọc lại"}';
COMMENT ON COLUMN org.e2ee_recovery_log.object_id IS '{"cls": "C1", "pii": false, "desc": "Mã nhiệm vụ hoặc phiên bản minh chứng"}';
COMMENT ON COLUMN org.e2ee_recovery_log.new_recipient_id IS '{"cls": "C1", "pii": false, "desc": "Người nhận khoá nội dung bọc lại"}';
COMMENT ON COLUMN org.e2ee_recovery_log.new_key_fingerprint IS '{"cls": "C0", "pii": false, "desc": "Vân tay khoá thiết bị mới (org.person_public_key.fingerprint)"}';
COMMENT ON COLUMN org.e2ee_recovery_log.custodian_ids IS '{"cls": "C1", "pii": false, "desc": "Người giữ mảnh có mặt"}';
COMMENT ON COLUMN org.e2ee_recovery_log.reason IS '{"cls": "C1", "pii": false, "desc": "Lý do phục hồi"}';
COMMENT ON COLUMN org.e2ee_recovery_log.ceremony_ref IS '{"cls": "C1", "pii": false, "desc": "Số biên bản phục hồi"}';
COMMENT ON COLUMN org.e2ee_recovery_log.performed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm phục hồi ở máy trạm cách ly"}';
COMMENT ON COLUMN org.e2ee_recovery_log.sig_key_version IS '{"cls": "C0", "pii": false, "desc": "Phiên bản khoá ký của danh bạ tenant"}';
COMMENT ON COLUMN org.e2ee_recovery_log.signature IS '{"cls": "C0", "pii": false, "desc": "Chữ ký ECDSA P-256 (DER) của danh bạ trên chuỗi chuẩn của bản ghi"}';
COMMENT ON COLUMN org.e2ee_recovery_log.signed_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ký"}';
COMMENT ON COLUMN org.e2ee_recovery_log.created_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm ghi nhật ký"}';
COMMENT ON COLUMN org.e2ee_recovery_log.created_by IS '{"cls": "C1", "pii": false, "desc": "Quản trị tenant ghi nhật ký"}';
CREATE INDEX ix_e2ee_recovery_log__object ON org.e2ee_recovery_log (tenant_id, object_id);
CREATE INDEX ix_e2ee_recovery_log__created ON org.e2ee_recovery_log (tenant_id, created_at DESC, id DESC);
ALTER TABLE org.e2ee_recovery_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.e2ee_recovery_log FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_e2ee_recovery_log__tenant ON org.e2ee_recovery_log AS PERMISSIVE FOR ALL TO svc_org, ro_org
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE ALL ON org.e2ee_recovery_log FROM PUBLIC;
GRANT SELECT, INSERT ON org.e2ee_recovery_log TO svc_org;
GRANT SELECT ON org.e2ee_recovery_log TO ro_org;
