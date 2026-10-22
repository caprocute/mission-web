-- CORE-01-01 · MOD-01 schema okr, phần kết quả then chốt, liên kết và check-in (TL-03 bảng
-- okr.key_result, okr.okr_link, okr.kr_checkin; QT-02, QT-03).
-- Tổng trọng số KR của một mục tiêu ≤ 1 kiểm bằng constraint trigger hoãn tới commit (cách hiện thực
-- suy luận từ TL-03 "trigger hoãn"). okr_link có period_id nhưng TL-03 không ghi period_bucket nên
-- không phân vùng (wiki trang 14). kr_checkin chỉ ghi thêm: svc_okr chỉ INSERT, SELECT và UPDATE cột
-- status, verified_by, verified_at; thêm cột status vì check-in cần chuyển NHAP sang DA_GUI.

CREATE TABLE ${flyway:defaultSchema}.key_result (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket integer NOT NULL,
    objective_id uuid NOT NULL,
    code varchar(16) NOT NULL,
    title varchar(500) NOT NULL,
    metric_type varchar(12) NOT NULL,
    unit varchar(32) NOT NULL,
    baseline_value numeric(18,4),
    target_value numeric(18,4) NOT NULL,
    current_value numeric(18,4),
    direction varchar(8) NOT NULL,
    weight numeric(6,4) NOT NULL,
    due_date date NOT NULL,
    verifier_person_id uuid NOT NULL,
    data_source varchar(200),
    confidence varchar(12),
    progress numeric(6,4) NOT NULL DEFAULT 0,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    ai_suggestion_id uuid,
    formula_text varchar(1000),
    content_source varchar(12) NOT NULL DEFAULT 'HUMAN',
    ai_assisted boolean NOT NULL DEFAULT false,
    risk_flag varchar(12) NOT NULL DEFAULT 'BINH_THUONG',
    checkin_frequency varchar(8) NOT NULL,
    CONSTRAINT pk_key_result PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_key_result__objective FOREIGN KEY (period_bucket, tenant_id, objective_id) REFERENCES ${flyway:defaultSchema}.objective (period_bucket, tenant_id, id),
    CONSTRAINT ck_key_result__metric_type CHECK (metric_type IN ('SO_LUONG', 'TY_LE', 'CO_KHONG', 'CHI_SO')),
    CONSTRAINT ck_key_result__direction CHECK (direction IN ('TANG', 'GIAM', 'DAT')),
    CONSTRAINT ck_key_result__weight CHECK (weight BETWEEN 0 AND 1),
    CONSTRAINT ck_key_result__confidence CHECK (confidence IS NULL OR confidence IN ('CAO', 'TRUNG_BINH', 'THAP')),
    CONSTRAINT ck_key_result__progress CHECK (progress >= 0),
    CONSTRAINT ck_key_result__status CHECK (status IN ('NHAP', 'CHO_DUYET', 'DA_DUYET', 'DONG')),
    CONSTRAINT ck_key_result__content_source CHECK (content_source IN ('HUMAN', 'AI_ACCEPTED', 'AI_EDITED')),
    CONSTRAINT ck_key_result__risk_flag CHECK (risk_flag IN ('BINH_THUONG', 'THEO_DOI', 'RUI_RO')),
    CONSTRAINT ck_key_result__checkin_frequency CHECK (checkin_frequency IN ('TUAN', 'HAI_TUAN', 'THANG'))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_key_result__objective ON ${flyway:defaultSchema}.key_result (tenant_id, objective_id);
CREATE INDEX ix_key_result__verifier ON ${flyway:defaultSchema}.key_result (tenant_id, verifier_person_id, period_id);

-- Tổng trọng số các KR của một mục tiêu không vượt 1, kiểm ở thời điểm commit để cho phép phân bổ lại
-- trọng số nhiều KR trong cùng giao dịch.
CREATE FUNCTION ${flyway:defaultSchema}.fn_check_key_result_weight_sum() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
  v_sum numeric;
BEGIN
  EXECUTE format('SELECT coalesce(sum(weight), 0) FROM %I.key_result'
                 ' WHERE period_bucket = $1 AND tenant_id = $2 AND objective_id = $3', TG_TABLE_SCHEMA)
    INTO v_sum USING NEW.period_bucket, NEW.tenant_id, NEW.objective_id;
  IF v_sum > 1 THEN
    RAISE EXCEPTION 'Tổng trọng số kết quả then chốt của mục tiêu % là %, vượt 1', NEW.objective_id, v_sum
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END
$fn$;
CREATE CONSTRAINT TRIGGER tg_key_result__weight_sum AFTER INSERT OR UPDATE OF weight, objective_id
  ON ${flyway:defaultSchema}.key_result DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_check_key_result_weight_sum();
COMMENT ON TABLE ${flyway:defaultSchema}.key_result IS 'Kết quả then chốt KR của một mục tiêu';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.period_id IS '{"cls": "C1", "desc": "Kỳ (tham chiếu logic config.period)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.period_bucket IS '{"cls": "C1", "desc": "Khoá phân vùng LIST: năm × 10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.objective_id IS '{"cls": "C1", "desc": "Mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.code IS '{"cls": "C0", "desc": "Mã hiển thị, ví dụ KR 1.1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.title IS '{"cls": "C1", "desc": "Nội dung KR đo được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.metric_type IS '{"cls": "C1", "desc": "Loại đo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.unit IS '{"cls": "C0", "desc": "Đơn vị tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.baseline_value IS '{"cls": "C1", "desc": "Giá trị xuất phát"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.target_value IS '{"cls": "C1", "desc": "Chỉ tiêu cần đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.current_value IS '{"cls": "C1", "desc": "Giá trị hiện tại từ check-in gần nhất đã xác nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.direction IS '{"cls": "C1", "desc": "Chiều tốt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.weight IS '{"cls": "C1", "desc": "Trọng số trong mục tiêu; tổng ≤ 1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.due_date IS '{"cls": "C1", "desc": "Hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.verifier_person_id IS '{"cls": "C1", "desc": "Người xác nhận kết quả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.data_source IS '{"cls": "C1", "desc": "Nguồn số liệu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.confidence IS '{"cls": "C1", "desc": "Mức tự tin gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.progress IS '{"cls": "C1", "desc": "Tỷ lệ đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.status IS '{"cls": "C1", "desc": "Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.ai_suggestion_id IS '{"cls": "C1", "desc": "Gợi ý AI được chấp nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.formula_text IS '{"cls": "C1", "desc": "Công thức đo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.content_source IS '{"cls": "C1", "desc": "Nguồn nội dung"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.ai_assisted IS '{"cls": "C1", "desc": "Có dùng gợi ý AI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.risk_flag IS '{"cls": "C1", "desc": "Cờ rủi ro"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.key_result.checkin_frequency IS '{"cls": "C1", "desc": "Tần suất check-in"}';

CREATE TABLE ${flyway:defaultSchema}.okr_link (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    from_type varchar(12) NOT NULL,
    from_id uuid NOT NULL,
    to_type varchar(12) NOT NULL,
    to_id uuid NOT NULL,
    link_kind varchar(12) NOT NULL,
    contribution_weight numeric(6,4),
    CONSTRAINT pk_okr_link PRIMARY KEY (id),
    CONSTRAINT uq_okr_link__pair UNIQUE (tenant_id, from_type, from_id, to_type, to_id),
    CONSTRAINT ck_okr_link__from_type CHECK (from_type IN ('BASIS', 'OBJECTIVE', 'KEY_RESULT')),
    CONSTRAINT ck_okr_link__to_type CHECK (to_type IN ('OBJECTIVE', 'KEY_RESULT', 'PROGRAM', 'TASK')),
    CONSTRAINT ck_okr_link__link_kind CHECK (link_kind IN ('PHAN_RA', 'DONG_GOP', 'NGANG', 'CAN_CU'))
);
-- Chiều xuôi dùng chính chỉ mục của uq_okr_link__pair; chiều ngược cần chỉ mục riêng.
CREATE INDEX ix_okr_link__to ON ${flyway:defaultSchema}.okr_link (tenant_id, to_type, to_id);
COMMENT ON TABLE ${flyway:defaultSchema}.okr_link IS 'Liên kết đa hình giữa căn cứ, mục tiêu, KR, chương trình và nhiệm vụ; truy vết hai chiều';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.period_id IS '{"cls": "C1", "desc": "Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.from_type IS '{"cls": "C1", "desc": "Loại đầu nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.from_id IS '{"cls": "C1", "desc": "Mã đầu nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.to_type IS '{"cls": "C1", "desc": "Loại đầu đích"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.to_id IS '{"cls": "C1", "desc": "Mã đầu đích; PROGRAM, TASK là mã logic của schema task"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.link_kind IS '{"cls": "C1", "desc": "Loại liên kết"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.okr_link.contribution_weight IS '{"cls": "C1", "desc": "Mức đóng góp"}';

CREATE TABLE ${flyway:defaultSchema}.kr_checkin (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket integer NOT NULL,
    key_result_id uuid NOT NULL,
    checkin_at timestamptz NOT NULL DEFAULT now(),
    value numeric(18,4) NOT NULL,
    confidence varchar(12) NOT NULL,
    blocker_note varchar(2000),
    channel varchar(8) NOT NULL,
    idempotency_key varchar(64) NOT NULL,
    verified_by uuid,
    verified_at timestamptz,
    status varchar(8) NOT NULL DEFAULT 'NHAP',
    source_value numeric(18,4),
    source_fetched_at timestamptz,
    override_reason varchar(1000),
    highlights varchar(2000),
    CONSTRAINT pk_kr_checkin PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_kr_checkin__idempotency UNIQUE (tenant_id, idempotency_key, period_bucket),
    CONSTRAINT ck_kr_checkin__confidence CHECK (confidence IN ('CAO', 'TRUNG_BINH', 'THAP')),
    CONSTRAINT ck_kr_checkin__channel CHECK (channel IN ('WEB', 'MOBILE', 'TICH_HOP')),
    CONSTRAINT ck_kr_checkin__status CHECK (status IN ('NHAP', 'DA_GUI')),
    CONSTRAINT ck_kr_checkin__override_reason CHECK (source_value IS NULL OR value = source_value OR nullif(btrim(override_reason), '') IS NOT NULL)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_kr_checkin__key_result ON ${flyway:defaultSchema}.kr_checkin (tenant_id, key_result_id, checkin_at DESC);

-- Chỉ ghi thêm (TL-03): thu quyền mặc định rồi cấp lại đúng các cột được cập nhật.
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.kr_checkin FROM svc_${flyway:defaultSchema};
GRANT UPDATE (status, verified_by, verified_at, updated_at, updated_by, row_version) ON ${flyway:defaultSchema}.kr_checkin TO svc_${flyway:defaultSchema};

-- Check-in đã gửi không sửa nội dung, không quay về nháp và không xoá; chỉ ghi được xác nhận.
CREATE FUNCTION ${flyway:defaultSchema}.fn_check_kr_checkin_immutable() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.status <> 'DA_GUI' THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;
  IF TG_OP = 'DELETE'
     OR (to_jsonb(NEW) - ARRAY['verified_by','verified_at','updated_at','updated_by','row_version'])
        IS DISTINCT FROM
        (to_jsonb(OLD) - ARRAY['verified_by','verified_at','updated_at','updated_by','row_version']) THEN
    RAISE EXCEPTION 'Check-in đã gửi không sửa, xoá được'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_kr_checkin__immutable BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.kr_checkin
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_check_kr_checkin_immutable();
COMMENT ON TABLE ${flyway:defaultSchema}.kr_checkin IS 'Check-in của KR; mỗi lần là một dòng mới, chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.period_id IS '{"cls": "C1", "desc": "Kỳ (tham chiếu logic config.period)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.period_bucket IS '{"cls": "C1", "desc": "Khoá phân vùng LIST: năm × 10 + quý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.key_result_id IS '{"cls": "C1", "desc": "KR"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.checkin_at IS '{"cls": "C1", "desc": "Thời điểm check-in"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.value IS '{"cls": "C1", "desc": "Giá trị báo cáo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.confidence IS '{"cls": "C1", "desc": "Mức tự tin"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.blocker_note IS '{"cls": "C1", "desc": "Vướng mắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.channel IS '{"cls": "C1", "desc": "Kênh nhập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.idempotency_key IS '{"cls": "C1", "desc": "Chống trùng khi ứng dụng di động gửi lại bản nháp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.verified_by IS '{"cls": "C1", "desc": "Người xác nhận KR"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.verified_at IS '{"cls": "C1", "desc": "Thời điểm xác nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.status IS '{"cls": "C1", "desc": "Trạng thái check-in"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.source_value IS '{"cls": "C1", "desc": "Giá trị lấy từ nguồn tự động"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.source_fetched_at IS '{"cls": "C1", "desc": "Thời điểm lấy từ nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.override_reason IS '{"cls": "C1", "desc": "Lý do nhập khác nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin.highlights IS '{"cls": "C1", "desc": "Điểm nổi bật"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.key_result');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.okr_link');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.kr_checkin');
