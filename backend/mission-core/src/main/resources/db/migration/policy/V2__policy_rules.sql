-- CORE-05-01 · MOD-05 schema policy, phần quy tắc, ca vàng và gắn phiên bản (TL-03 bảng
-- policy.policy_rule, policy.decision_table, policy.golden_test_case, policy.test_case_result,
-- policy.policy_binding).
-- policy_rule và decision_table: trigger từ chối UPDATE, DELETE trên dòng thuộc phiên bản đã đóng băng
-- (TL-03); chặn thêm cả INSERT vào phiên bản đã đóng băng vì content_hash đã ký bao trùm toàn bộ quy
-- tắc (suy luận).
-- policy_binding: khi is_frozen (kỳ đã khoá) không đổi được phiên bản, phạm vi, và không xoá được.

-- Phiên bản ở các trạng thái này có nội dung đóng băng (TL-03 fn_freeze_policy_version).
CREATE FUNCTION ${flyway:defaultSchema}.fn_reject_frozen_version_content() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
  v_frozen boolean;
BEGIN
  EXECUTE format('SELECT bool_or(status IN (''DAT_KIEM_THU'',''CHO_PHE_DUYET'',''DA_PHAT_HANH'',''NGUNG''))'
                 ' FROM %I.policy_pack_version WHERE id = ANY($1)', TG_TABLE_SCHEMA)
    INTO v_frozen
    USING ARRAY[CASE WHEN TG_OP <> 'INSERT' THEN OLD.policy_pack_version_id END,
                CASE WHEN TG_OP <> 'DELETE' THEN NEW.policy_pack_version_id END];
  IF coalesce(v_frozen, false) THEN
    RAISE EXCEPTION 'Phiên bản Policy Pack đã đóng băng nội dung, không % được %', TG_OP, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END
$fn$;

CREATE TABLE ${flyway:defaultSchema}.policy_rule (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_version_id uuid NOT NULL,
    rule_key varchar(64) NOT NULL,
    expression text NOT NULL,
    result_type varchar(12) NOT NULL,
    rounding jsonb NOT NULL,
    legal_ref varchar(200) NOT NULL,
    eval_order smallint NOT NULL,
    params_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    overridable boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_policy_rule PRIMARY KEY (id),
    CONSTRAINT fk_policy_rule__policy_pack_version FOREIGN KEY (policy_pack_version_id) REFERENCES ${flyway:defaultSchema}.policy_pack_version (id),
    CONSTRAINT uq_policy_rule__key UNIQUE (policy_pack_version_id, rule_key),
    CONSTRAINT ck_policy_rule__result_type CHECK (result_type IN ('DECIMAL', 'BOOLEAN', 'STRING'))
);
CREATE TRIGGER tg_policy_rule__freeze BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.policy_rule
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_reject_frozen_version_content();
COMMENT ON TABLE ${flyway:defaultSchema}.policy_rule IS 'Quy tắc CEL của một phiên bản Policy Pack';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.rule_key IS '{"cls": "C0", "desc": "Tên quy tắc, ví dụ kpi_nhiem_vu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.expression IS '{"cls": "C0", "desc": "Biểu thức CEL; đóng băng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.result_type IS '{"cls": "C0", "desc": "Kiểu kết quả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.rounding IS '{"cls": "C0", "desc": "Số chữ số và chế độ làm tròn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.legal_ref IS '{"cls": "C0", "desc": "Điều khoản căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.eval_order IS '{"cls": "C0", "desc": "Thứ tự đánh giá", "type_exception": "TL-03 bảng policy.policy_rule ghi smallint"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.params_json IS '{"cls": "C0", "desc": "Tham số của quy tắc (TL-02b)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_rule.overridable IS '{"cls": "C0", "desc": "Tenant được ghi đè tham số"}';

CREATE TABLE ${flyway:defaultSchema}.decision_table (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_version_id uuid NOT NULL,
    table_key varchar(64) NOT NULL,
    dmn_xml text NOT NULL,
    hit_policy varchar(12) NOT NULL,
    dmn_hash bytea NOT NULL,
    legal_ref varchar(200) NOT NULL,
    rules_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    explanation_templates_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_decision_table PRIMARY KEY (id),
    CONSTRAINT fk_decision_table__policy_pack_version FOREIGN KEY (policy_pack_version_id) REFERENCES ${flyway:defaultSchema}.policy_pack_version (id),
    CONSTRAINT uq_decision_table__key UNIQUE (policy_pack_version_id, table_key),
    CONSTRAINT ck_decision_table__hit_policy CHECK (hit_policy IN ('FIRST', 'PRIORITY', 'UNIQUE'))
);
CREATE TRIGGER tg_decision_table__freeze BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.decision_table
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_reject_frozen_version_content();
COMMENT ON TABLE ${flyway:defaultSchema}.decision_table IS 'Bảng quyết định DMN của một phiên bản Policy Pack';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.table_key IS '{"cls": "C0", "desc": "Khoá bảng, ví dụ xep_loai"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.dmn_xml IS '{"cls": "C0", "desc": "Định nghĩa DMN 1.4; đóng băng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.hit_policy IS '{"cls": "C0", "desc": "Chính sách khớp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.dmn_hash IS '{"cls": "C1", "desc": "Băm của định nghĩa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.legal_ref IS '{"cls": "C0", "desc": "Điều khoản căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.rules_json IS '{"cls": "C0", "desc": "Các dòng quy tắc dạng JSON"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.decision_table.explanation_templates_json IS '{"cls": "C0", "desc": "Mẫu câu giải thích theo từng dòng"}';

CREATE TABLE ${flyway:defaultSchema}.golden_test_case (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_id uuid NOT NULL,
    case_code varchar(64) NOT NULL,
    description text NOT NULL,
    input jsonb NOT NULL,
    expected jsonb NOT NULL,
    legal_ref varchar(200),
    source varchar(12) NOT NULL,
    approved_by_customer uuid,
    approved_at timestamptz,
    signature bytea,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    approved_by uuid,
    CONSTRAINT pk_golden_test_case PRIMARY KEY (id),
    CONSTRAINT fk_golden_test_case__policy_pack FOREIGN KEY (policy_pack_id) REFERENCES ${flyway:defaultSchema}.policy_pack (id),
    CONSTRAINT uq_golden_test_case__code UNIQUE (policy_pack_id, case_code),
    CONSTRAINT ck_golden_test_case__source CHECK (source IN ('KHACH_HANG', 'BA', 'VAN_BAN')),
    CONSTRAINT ck_golden_test_case__status CHECK (status IN ('NHAP', 'DA_DUYET', 'NGUNG'))
);
COMMENT ON TABLE ${flyway:defaultSchema}.golden_test_case IS 'Ca kiểm thử vàng gắn với Policy Pack, khách hàng ký duyệt';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.policy_pack_id IS '{"cls": "C1", "desc": "Gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.case_code IS '{"cls": "C0", "desc": "Mã ca, ví dụ BTC-V3-017"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.description IS '{"cls": "C0", "desc": "Mô tả tình huống"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.input IS '{"cls": "C1", "desc": "Dữ liệu đầu vào giả lập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.expected IS '{"cls": "C1", "desc": "Kết quả mong đợi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.legal_ref IS '{"cls": "C0", "desc": "Điều khoản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.source IS '{"cls": "C1", "desc": "Nguồn ca"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.approved_by_customer IS '{"cls": "C1", "desc": "Người đại diện khách hàng ký duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.approved_at IS '{"cls": "C1", "desc": "Thời điểm ký duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.signature IS '{"cls": "C1", "desc": "Chữ ký số trên input và expected"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.status IS '{"cls": "C1", "desc": "Trạng thái ca"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.golden_test_case.approved_by IS '{"cls": "C1", "desc": "Người duyệt ca kiểm thử"}';

CREATE TABLE ${flyway:defaultSchema}.test_case_result (
    test_run_id uuid NOT NULL,
    golden_test_case_id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    passed boolean NOT NULL,
    actual jsonb NOT NULL,
    diff jsonb,
    CONSTRAINT pk_test_case_result PRIMARY KEY (test_run_id, golden_test_case_id),
    CONSTRAINT fk_test_case_result__test_run FOREIGN KEY (test_run_id) REFERENCES ${flyway:defaultSchema}.test_run (id),
    CONSTRAINT fk_test_case_result__golden_test_case FOREIGN KEY (golden_test_case_id) REFERENCES ${flyway:defaultSchema}.golden_test_case (id)
);
CREATE INDEX ix_test_case_result__case ON ${flyway:defaultSchema}.test_case_result (tenant_id, golden_test_case_id);
COMMENT ON TABLE ${flyway:defaultSchema}.test_case_result IS 'Kết quả từng ca trong một lần chạy kiểm thử';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_case_result.test_run_id IS '{"cls": "C1", "desc": "Lần chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_case_result.golden_test_case_id IS '{"cls": "C1", "desc": "Ca kiểm thử"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_case_result.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_case_result.passed IS '{"cls": "C1", "desc": "Đạt hay không"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_case_result.actual IS '{"cls": "C1", "desc": "Kết quả thực tế"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.test_case_result.diff IS '{"cls": "C1", "desc": "Khác biệt so với mong đợi"}';

CREATE TABLE ${flyway:defaultSchema}.policy_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_version_id uuid NOT NULL,
    period_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    subject_group_id uuid,
    org_unit_id uuid,
    is_frozen boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_policy_binding PRIMARY KEY (id),
    CONSTRAINT fk_policy_binding__policy_pack_version FOREIGN KEY (policy_pack_version_id) REFERENCES ${flyway:defaultSchema}.policy_pack_version (id),
    CONSTRAINT uq_policy_binding__scope UNIQUE NULLS NOT DISTINCT (tenant_id, period_id, flow, subject_group_id, org_unit_id),
    CONSTRAINT ck_policy_binding__flow CHECK (flow IN ('DANG', 'NHA_NUOC', 'CA_HAI'))
);
CREATE INDEX ix_policy_binding__version ON ${flyway:defaultSchema}.policy_binding (tenant_id, policy_pack_version_id);

-- Kỳ đã khoá giữ nguyên phiên bản (TL-03): khi is_frozen chỉ được bỏ đóng băng (mở lại hậu kiểm),
-- không đổi phiên bản, phạm vi, và không xoá.
CREATE FUNCTION ${flyway:defaultSchema}.fn_reject_frozen_binding_change() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.is_frozen AND (TG_OP = 'DELETE'
      OR NEW.policy_pack_version_id IS DISTINCT FROM OLD.policy_pack_version_id
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
CREATE TRIGGER tg_policy_binding__frozen BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.policy_binding
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_reject_frozen_binding_change();
COMMENT ON TABLE ${flyway:defaultSchema}.policy_binding IS 'Gắn phiên bản Policy Pack cho tổ hợp tenant, kỳ, luồng, khối đối tượng, đơn vị';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.period_id IS '{"cls": "C1", "desc": "Kỳ áp dụng (tham chiếu logic config.period)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.flow IS '{"cls": "C1", "desc": "Luồng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.subject_group_id IS '{"cls": "C1", "desc": "Khối đối tượng; NULL là mọi khối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.org_unit_id IS '{"cls": "C1", "desc": "Đơn vị phạm vi; NULL là toàn tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.policy_binding.is_frozen IS '{"cls": "C1", "desc": "Đặt true khi kỳ chuyển DA_KHOA"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.policy_rule');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.decision_table');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.golden_test_case');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.test_case_result');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.policy_binding');
