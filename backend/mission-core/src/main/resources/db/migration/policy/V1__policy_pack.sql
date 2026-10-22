-- CORE-05-01 · MOD-05 schema policy, phần gói và phiên bản (TL-03 "Schema policy (MOD-05)",
-- "DDL mẫu: policy.policy_pack_version", bảng policy.policy_pack, policy.test_run).
-- Bất biến ở tầng CSDL: phiên bản từ DAT_KIEM_THU trở đi đóng băng manifest, content_hash, version
-- (tg_policy_pack_version__freeze); phát hành bắt buộc chữ ký, băm và người duyệt khác người soạn.
-- TL-02b cho sửa phiên bản DAT_KIEM_THU (sửa xong về NHAP, MSG-15-03): ứng dụng đổi riêng cột status
-- về NHAP trước (trigger cho qua) rồi mới sửa nội dung, trong cùng giao dịch (wiki trang 14).
-- Khoá ngoại vòng last_test_run_id ↔ test_run.policy_pack_version_id tạo sau cùng (suy luận).
-- Tên khoá ngoại theo lint fk_<bảng>__<bảng đích>: fk_policy_pack_version__pack của TL-03 thành
-- fk_policy_pack_version__policy_pack.

CREATE TABLE ${flyway:defaultSchema}.policy_pack (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    code varchar(100) NOT NULL,
    name varchar(255) NOT NULL,
    sector varchar(24) NOT NULL,
    flow varchar(12) NOT NULL,
    origin varchar(16) NOT NULL DEFAULT 'TENANT',
    parent_pack_id uuid,
    legal_basis text NOT NULL,
    CONSTRAINT pk_policy_pack PRIMARY KEY (id),
    CONSTRAINT uq_policy_pack__code UNIQUE (tenant_id, code),
    CONSTRAINT fk_policy_pack__policy_pack_parent FOREIGN KEY (parent_pack_id) REFERENCES ${flyway:defaultSchema}.policy_pack (id),
    CONSTRAINT ck_policy_pack__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_policy_pack__origin CHECK (origin IN ('GOC_TOAN_CUC', 'TENANT'))
);
COMMENT ON TABLE ${flyway:defaultSchema}.policy_pack IS 'Policy Pack: quy tắc chấm điểm là mã có phiên bản (ADR-19)';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.code IS '{"cls": "C0", "desc": "Mã gói, ví dụ khoi-dang/quy-dinh-moi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.name IS '{"cls": "C0", "desc": "Tên gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.sector IS '{"cls": "C0", "desc": "Nhóm khách hàng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.flow IS '{"cls": "C0", "desc": "Luồng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.origin IS '{"cls": "C1", "desc": "Gói gốc đồng bộ từ vùng điều phối hoặc gói riêng của tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.parent_pack_id IS '{"cls": "C1", "desc": "Gói được kế thừa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack.legal_basis IS '{"cls": "C0", "desc": "Văn bản căn cứ"}';

CREATE TABLE ${flyway:defaultSchema}.policy_pack_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_id uuid NOT NULL,
    version varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'NHAP',
    effective_from date,
    manifest jsonb NOT NULL,
    content_hash bytea,
    bundle_object_key varchar(512),
    golden_pass_rate numeric(6,4),
    last_test_run_id uuid,
    signature bytea,
    signer_cert_ref uuid,
    signed_at timestamptz,
    tsa_token bytea,
    approved_by uuid,
    approved_at timestamptz,
    published_at timestamptz,
    source_version_id uuid,
    change_note text NOT NULL,
    CONSTRAINT pk_policy_pack_version PRIMARY KEY (id),
    CONSTRAINT fk_policy_pack_version__policy_pack FOREIGN KEY (policy_pack_id) REFERENCES ${flyway:defaultSchema}.policy_pack (id),
    CONSTRAINT uq_policy_pack_version__ver UNIQUE (policy_pack_id, version),
    CONSTRAINT ck_policy_pack_version__status CHECK (status IN ('NHAP', 'DANG_KIEM_THU', 'DAT_KIEM_THU', 'CHO_PHE_DUYET', 'DA_PHAT_HANH', 'NGUNG')),
    CONSTRAINT ck_policy_pack_version__semver CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT ck_policy_pack_version__pass CHECK (status NOT IN ('DAT_KIEM_THU', 'CHO_PHE_DUYET', 'DA_PHAT_HANH') OR golden_pass_rate = 1),
    CONSTRAINT ck_policy_pack_version__signed CHECK (status <> 'DA_PHAT_HANH' OR (signature IS NOT NULL AND content_hash IS NOT NULL AND approved_by IS NOT NULL AND approved_by <> created_by))
);
CREATE INDEX ix_policy_pack_version__pack_status ON ${flyway:defaultSchema}.policy_pack_version (tenant_id, policy_pack_id, status);

CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_policy_version() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.status IN ('DAT_KIEM_THU','CHO_PHE_DUYET','DA_PHAT_HANH','NGUNG')
     AND (NEW.manifest IS DISTINCT FROM OLD.manifest
          OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
          OR NEW.version IS DISTINCT FROM OLD.version) THEN
    RAISE EXCEPTION 'Phiên bản Policy Pack % đã đóng băng nội dung', OLD.version
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_policy_pack_version__freeze BEFORE UPDATE ON ${flyway:defaultSchema}.policy_pack_version
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_policy_version();
COMMENT ON TABLE ${flyway:defaultSchema}.policy_pack_version IS 'Phiên bản Policy Pack; nội dung đóng băng từ khi đạt kiểm thử';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.policy_pack_id IS '{"cls": "C1", "desc": "Gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.version IS '{"cls": "C0", "desc": "Phiên bản ngữ nghĩa, ví dụ 2.1.0"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.status IS '{"cls": "C1", "desc": "Trạng thái phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.effective_from IS '{"cls": "C1", "desc": "Kỳ bắt đầu áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.manifest IS '{"cls": "C0", "desc": "Tham số, quy tắc làm tròn, nhãn hiển thị; đóng băng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.content_hash IS '{"cls": "C1", "desc": "SHA-256 dạng chuẩn hoá của quy tắc, DMN và manifest; đóng băng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.bundle_object_key IS '{"cls": "C1", "desc": "Gói đã ký trên Ceph"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.golden_pass_rate IS '{"cls": "C1", "desc": "Tỷ lệ ca vàng đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.last_test_run_id IS '{"cls": "C1", "desc": "Lần kiểm thử gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.signature IS '{"cls": "C1", "desc": "Chữ ký CAdES tách rời trên content_hash"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.signer_cert_ref IS '{"cls": "C1", "desc": "Chứng thư người ký (tham chiếu logic esign.certificate_ref)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.signed_at IS '{"cls": "C1", "desc": "Thời điểm ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.tsa_token IS '{"cls": "C1", "desc": "Dấu thời gian RFC 3161"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.approved_by IS '{"cls": "C1", "desc": "Người phê duyệt phát hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.approved_at IS '{"cls": "C1", "desc": "Thời điểm phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.published_at IS '{"cls": "C1", "desc": "Thời điểm phát hành (EVT-13)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.source_version_id IS '{"cls": "C1", "desc": "Phiên bản gốc toàn cục nếu nhân bản từ kho gốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_pack_version.change_note IS '{"cls": "C0", "desc": "Ghi chú thay đổi"}';

CREATE TABLE ${flyway:defaultSchema}.test_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_version_id uuid NOT NULL,
    engine varchar(12) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    total integer NOT NULL,
    passed integer NOT NULL,
    failed integer NOT NULL,
    result varchar(12) NOT NULL DEFAULT 'DANG_CHAY',
    report_object_key varchar(512),
    engine_match boolean NOT NULL DEFAULT false,
    property_result_json jsonb,
    cases_json jsonb,
    CONSTRAINT pk_test_run PRIMARY KEY (id),
    CONSTRAINT fk_test_run__policy_pack_version FOREIGN KEY (policy_pack_version_id) REFERENCES ${flyway:defaultSchema}.policy_pack_version (id),
    CONSTRAINT ck_test_run__engine CHECK (engine IN ('CEL_JAVA', 'CEL_JS')),
    CONSTRAINT ck_test_run__counts CHECK (passed >= 0 AND failed >= 0 AND passed + failed = total),
    CONSTRAINT ck_test_run__result CHECK (result IN ('DAT', 'KHONG_DAT', 'DANG_CHAY'))
);
CREATE INDEX ix_test_run__version ON ${flyway:defaultSchema}.test_run (tenant_id, policy_pack_version_id, started_at);
ALTER TABLE ${flyway:defaultSchema}.policy_pack_version
  ADD CONSTRAINT fk_policy_pack_version__test_run FOREIGN KEY (last_test_run_id)
  REFERENCES ${flyway:defaultSchema}.test_run (id) DEFERRABLE INITIALLY DEFERRED;
COMMENT ON TABLE ${flyway:defaultSchema}.test_run IS 'Một lần chạy bộ ca vàng trên một phiên bản';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản được kiểm thử"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.engine IS '{"cls": "C1", "desc": "Engine chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.started_at IS '{"cls": "C1", "desc": "Bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.finished_at IS '{"cls": "C1", "desc": "Kết thúc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.total IS '{"cls": "C1", "desc": "Tổng số ca"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.passed IS '{"cls": "C1", "desc": "Số ca đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.failed IS '{"cls": "C1", "desc": "Số ca không đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.result IS '{"cls": "C1", "desc": "Kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.report_object_key IS '{"cls": "C1", "desc": "Báo cáo chi tiết trên Ceph"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.engine_match IS '{"cls": "C1", "desc": "Kết quả Java và trình duyệt khớp nhau"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.property_result_json IS '{"cls": "C1", "desc": "Kết quả kiểm thử thuộc tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_run.cases_json IS '{"cls": "C1", "desc": "Tóm tắt từng ca"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.policy_pack');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.policy_pack_version');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.test_run');
