-- CORE-05-12 · BS-03 ADR-33 "Lưu trữ (bổ sung TL-03, schema policy)": hồ sơ tập khách hàng có phiên
-- bản, khung tham số kế thừa có giới hạn (DC-02) và cột ghim phiên bản hồ sơ trong policy_binding.
-- Phiên bản hồ sơ dùng cùng tập trạng thái và cùng ràng buộc "đã phát hành phải có chữ ký, người
-- duyệt khác người soạn" như policy_pack_version. Nội dung (manifest, khung tham số, content_hash,
-- version) đóng băng từ CHO_PHE_DUYET: hợp đồng cho sửa phiên bản DAT_KIEM_THU và đưa về NHAP.
-- tenant_tags (ap_dung_cho.tenant_tags của bản kê) là cột bổ sung theo hợp đồng (suy luận).

CREATE TABLE ${flyway:defaultSchema}.customer_profile (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    code varchar(100) NOT NULL,
    name varchar(255) NOT NULL,
    is_simulated boolean NOT NULL DEFAULT false,
    legal_basis jsonb NOT NULL DEFAULT '[]'::jsonb,
    tenant_tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT pk_customer_profile PRIMARY KEY (id),
    CONSTRAINT uq_customer_profile__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_customer_profile__code CHECK (code ~ '^[a-z0-9][a-z0-9/_-]*$')
);
COMMENT ON TABLE ${flyway:defaultSchema}.customer_profile IS 'Hồ sơ tập khách hàng: bản kê trỏ tới phiên bản các thành phần cấu hình (BS-03 ADR-33)';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.code IS '{"cls": "C0", "desc": "Mã hồ sơ, duy nhất trong tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.name IS '{"cls": "C0", "desc": "Tên hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.is_simulated IS '{"cls": "C0", "desc": "Hồ sơ giả lập theo dự thảo chưa ban hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.legal_basis IS '{"cls": "C0", "desc": "Danh sách văn bản căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile.tenant_tags IS '{"cls": "C0", "desc": "Thẻ tenant mà hồ sơ áp dụng"}';

CREATE TABLE ${flyway:defaultSchema}.customer_profile_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    customer_profile_id uuid NOT NULL,
    version varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'NHAP',
    manifest jsonb NOT NULL,
    content_hash bytea,
    signature bytea,
    signer_cert_ref uuid,
    signed_at timestamptz,
    tsa_token bytea,
    approved_by uuid,
    approved_at timestamptz,
    published_at timestamptz,
    change_note text NOT NULL,
    CONSTRAINT pk_customer_profile_version PRIMARY KEY (id),
    CONSTRAINT fk_customer_profile_version__customer_profile FOREIGN KEY (customer_profile_id) REFERENCES ${flyway:defaultSchema}.customer_profile (id),
    CONSTRAINT uq_customer_profile_version__ver UNIQUE (customer_profile_id, version),
    CONSTRAINT ck_customer_profile_version__status CHECK (status IN ('NHAP', 'DANG_KIEM_THU', 'DAT_KIEM_THU', 'CHO_PHE_DUYET', 'DA_PHAT_HANH', 'NGUNG')),
    CONSTRAINT ck_customer_profile_version__semver CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT ck_customer_profile_version__hash CHECK (status NOT IN ('DAT_KIEM_THU', 'CHO_PHE_DUYET', 'DA_PHAT_HANH') OR content_hash IS NOT NULL),
    CONSTRAINT ck_customer_profile_version__signed CHECK (status <> 'DA_PHAT_HANH' OR (signature IS NOT NULL AND content_hash IS NOT NULL AND approved_by IS NOT NULL AND approved_by <> created_by))
);
CREATE INDEX ix_customer_profile_version__profile_status ON ${flyway:defaultSchema}.customer_profile_version (tenant_id, customer_profile_id, status);

CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_customer_profile_version() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.status IN ('CHO_PHE_DUYET','DA_PHAT_HANH','NGUNG')
     AND (NEW.manifest IS DISTINCT FROM OLD.manifest
          OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
          OR NEW.version IS DISTINCT FROM OLD.version) THEN
    RAISE EXCEPTION 'Phiên bản hồ sơ tập khách hàng % đã đóng băng nội dung', OLD.version
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_customer_profile_version__freeze BEFORE UPDATE ON ${flyway:defaultSchema}.customer_profile_version
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_customer_profile_version();
COMMENT ON TABLE ${flyway:defaultSchema}.customer_profile_version IS 'Phiên bản hồ sơ tập khách hàng; vòng đời và ràng buộc ký như phiên bản Policy Pack';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.created_by IS '{"cls": "C1", "desc": "Người soạn phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.customer_profile_id IS '{"cls": "C1", "desc": "Hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.version IS '{"cls": "C0", "desc": "Phiên bản ngữ nghĩa, ví dụ 0.1.0"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.status IS '{"cls": "C1", "desc": "Trạng thái phiên bản, cùng tập giá trị với policy_pack_version.status"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.manifest IS '{"cls": "C0", "desc": "Bản kê: tham chiếu thành phần và cờ điểm cắm; đóng băng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.content_hash IS '{"cls": "C1", "desc": "SHA-256 dạng chuẩn hoá của bản kê và khung tham số; đóng băng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.signature IS '{"cls": "C1", "desc": "Chữ ký CAdES tách rời trên content_hash"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.signer_cert_ref IS '{"cls": "C1", "desc": "Chứng thư người ký (tham chiếu logic esign.certificate_ref)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.signed_at IS '{"cls": "C1", "desc": "Thời điểm ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.tsa_token IS '{"cls": "C1", "desc": "Dấu thời gian RFC 3161"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.approved_by IS '{"cls": "C1", "desc": "Người phê duyệt phát hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.approved_at IS '{"cls": "C1", "desc": "Thời điểm phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.published_at IS '{"cls": "C1", "desc": "Thời điểm phát hành (EVT-13)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.customer_profile_version.change_note IS '{"cls": "C0", "desc": "Ghi chú thay đổi"}';

CREATE TABLE ${flyway:defaultSchema}.param_constraint (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    customer_profile_version_id uuid NOT NULL,
    param_key varchar(128) NOT NULL,
    can_toggle boolean NOT NULL DEFAULT false,
    can_change_levels boolean NOT NULL DEFAULT false,
    min_value numeric(18,4),
    max_value numeric(18,4),
    tighten_direction varchar(8) NOT NULL DEFAULT 'KHONG',
    allowed_scopes jsonb NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT pk_param_constraint PRIMARY KEY (id),
    CONSTRAINT fk_param_constraint__customer_profile_version FOREIGN KEY (customer_profile_version_id) REFERENCES ${flyway:defaultSchema}.customer_profile_version (id),
    CONSTRAINT uq_param_constraint__key UNIQUE (customer_profile_version_id, param_key),
    CONSTRAINT ck_param_constraint__direction CHECK (tighten_direction IN ('TANG', 'GIAM', 'KHONG')),
    CONSTRAINT ck_param_constraint__range CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
);
COMMENT ON TABLE ${flyway:defaultSchema}.param_constraint IS 'Khung tham số mà cấp dưới được phép chỉnh (BS-03 DC-02)';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.customer_profile_version_id IS '{"cls": "C1", "desc": "Phiên bản hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.param_key IS '{"cls": "C0", "desc": "Khoá tham số, ví dụ tham_so.tru_moi_ngay_cham"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.can_toggle IS '{"cls": "C0", "desc": "Được bật tắt tham số"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.can_change_levels IS '{"cls": "C0", "desc": "Được đổi số mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.min_value IS '{"cls": "C0", "desc": "Giá trị tối thiểu cho phép"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.max_value IS '{"cls": "C0", "desc": "Giá trị tối đa cho phép"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.tighten_direction IS '{"cls": "C0", "desc": "Chiều được phép siết so với cấp trên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.param_constraint.allowed_scopes IS '{"cls": "C0", "desc": "Các cấp được phép chỉnh (KHOI, TINH, DON_VI)"}';

ALTER TABLE ${flyway:defaultSchema}.policy_binding
  ADD COLUMN customer_profile_version_id uuid,
  ADD CONSTRAINT fk_policy_binding__customer_profile_version FOREIGN KEY (customer_profile_version_id) REFERENCES ${flyway:defaultSchema}.customer_profile_version (id);
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.customer_profile_version_id IS '{"cls": "C1", "desc": "Phiên bản hồ sơ tập khách hàng áp cho kỳ; NULL với dữ liệu trước BS-03"}';

-- Kỳ đã khoá đóng băng cả phiên bản hồ sơ đã ghim (BS-03 "Bản kê hồ sơ").
CREATE OR REPLACE FUNCTION ${flyway:defaultSchema}.fn_reject_frozen_binding_change() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.is_frozen AND (TG_OP = 'DELETE'
      OR NEW.policy_pack_version_id IS DISTINCT FROM OLD.policy_pack_version_id
      OR NEW.customer_profile_version_id IS DISTINCT FROM OLD.customer_profile_version_id
      OR NEW.period_id IS DISTINCT FROM OLD.period_id
      OR NEW.flow IS DISTINCT FROM OLD.flow
      OR NEW.subject_group_id IS DISTINCT FROM OLD.subject_group_id
      OR NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id) THEN
    RAISE EXCEPTION 'Gắn Policy Pack của kỳ đã khoá không đổi được'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END
$fn$;

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.customer_profile');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.customer_profile_version');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.param_constraint');
