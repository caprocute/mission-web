-- DV-07-01 · SVC-07 scoring-service · Schema scoring (TL-03 "Schema scoring (SVC-07)", baseline mục 9):
-- tác vụ tính, tập đầu vào chuẩn hoá, kết quả và vết tính. outbox_event, inbox_processed,
-- inbox_aggregate_version, idempotency_key đến từ db/common-business (NEN-032); hàm RLS và phân vùng từ
-- db/common (NEN-033). Migration chạy bằng mig_scoring qua vt-db-migration (InitContainer), không qua
-- Flyway lúc ứng dụng chạy.
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 56):
--   * Khoá chính, khoá duy nhất, khoá ngoại chứa period_bucket và tenant_id như schema appraisal (TL-03 ghi
--     PK (period_bucket, id)), vì ở CSDL pool phân vùng con băm theo tenant_id.
--   * score_input_v, score_result, score_trace chỉ ghi thêm: svc_scoring không có UPDATE, DELETE, TRUNCATE
--     (TL-01 "Tính lại tái lập được"); tính lại tạo kết quả mới.
--   * score_trace.score_result_id chỉ có chỉ mục, không khai khoá ngoại (TL-03 ghi "Chỉ mục"), để ghi lô
--     40 dòng mỗi kết quả không phải kiểm khoá ngoại.
--   * Băm (input_hash, policy_content_hash, result_hash) là SHA-256 32 byte, có CHECK độ dài.

-- ---------------------------------------------------------------- score_job
CREATE TABLE ${flyway:defaultSchema}.score_job (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    job_kind varchar(20) NOT NULL,
    scope jsonb NOT NULL,
    policy_pack_version_id uuid NOT NULL,
    trigger_event_id uuid,
    total_items int NOT NULL DEFAULT 0,
    done_items int NOT NULL DEFAULT 0,
    failed_items int NOT NULL DEFAULT 0,
    status varchar(12) NOT NULL DEFAULT 'CHO',
    started_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT pk_score_job PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT ck_score_job__job_kind CHECK (job_kind IN ('TAM_TINH', 'CHOT_KY', 'TINH_LAI_HAU_KIEM',
        'KIEM_TRA_TAI_LAP')),
    CONSTRAINT ck_score_job__status CHECK (status IN ('CHO', 'DANG_CHAY', 'XONG', 'LOI')),
    CONSTRAINT ck_score_job__items CHECK (total_items >= 0 AND done_items >= 0 AND failed_items >= 0
        AND done_items + failed_items <= total_items)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_score_job__period_status ON ${flyway:defaultSchema}.score_job (tenant_id, period_id, status);
-- Một sự kiện kích hoạt chỉ sinh một tác vụ (consumer EVT-09, EVT-22 xử lý lại không tạo trùng).
CREATE UNIQUE INDEX ix_score_job__trigger_event ON ${flyway:defaultSchema}.score_job
    (period_bucket, tenant_id, trigger_event_id) WHERE trigger_event_id IS NOT NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.score_job IS 'Tác vụ tính điểm theo lô hoặc theo yêu cầu (SVC-07)';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.id IS '{"cls":"C1","desc":"Mã tác vụ (UUIDv7)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.created_by IS '{"cls":"C1","desc":"Người hoặc dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.updated_by IS '{"cls":"C1","desc":"Người cập nhật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.row_version IS '{"cls":"C1","desc":"Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.period_id IS '{"cls":"C1","desc":"Kỳ đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.job_kind IS '{"cls":"C1","desc":"Loại tác vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.scope IS '{"cls":"C1","desc":"Phạm vi (đơn vị, danh sách người)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.policy_pack_version_id IS '{"cls":"C1","desc":"Phiên bản quy tắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.trigger_event_id IS '{"cls":"C1","desc":"Sự kiện kích hoạt (EVT-09, EVT-22)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.total_items IS '{"cls":"C1","desc":"Tổng số hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.done_items IS '{"cls":"C1","desc":"Số hồ sơ đã tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.failed_items IS '{"cls":"C1","desc":"Số hồ sơ lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.status IS '{"cls":"C1","desc":"Trạng thái tác vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.started_at IS '{"cls":"C1","desc":"Bắt đầu chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_job.finished_at IS '{"cls":"C1","desc":"Kết thúc"}';

-- ---------------------------------------------------------------- score_input_v
CREATE TABLE ${flyway:defaultSchema}.score_input_v (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    period_bucket int NOT NULL,
    person_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    input_hash bytea NOT NULL,
    input_doc jsonb NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_score_input_v PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_score_input_v__input_hash UNIQUE (period_bucket, tenant_id, input_hash),
    CONSTRAINT ck_score_input_v__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_score_input_v__input_hash CHECK (octet_length(input_hash) = 32)
) PARTITION BY LIST (period_bucket);
-- Nén TOAST lz4 (TL-03); phân vùng tạo sau kế thừa cài đặt nén của bảng cha.
ALTER TABLE ${flyway:defaultSchema}.score_input_v ALTER COLUMN input_doc SET COMPRESSION lz4;
CREATE INDEX ix_score_input_v__person ON ${flyway:defaultSchema}.score_input_v (tenant_id, person_id, flow);

COMMENT ON TABLE ${flyway:defaultSchema}.score_input_v IS 'Tập đầu vào chuẩn hoá RFC 8785 của từng người, dùng lại khi trùng băm';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.id IS '{"cls":"C1","desc":"Mã tập đầu vào"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.person_id IS '{"cls":"C1","desc":"Người được tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.flow IS '{"cls":"C1","desc":"Luồng đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.input_hash IS '{"cls":"C1","desc":"SHA-256 dạng chuẩn hoá RFC 8785 + NFC của tập đầu vào"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.input_doc IS '{"cls":"C1","desc":"Tập đầu vào chuẩn hoá; không chứa văn bản C2"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_input_v.captured_at IS '{"cls":"C1","desc":"Thời điểm chụp"}';

-- ---------------------------------------------------------------- score_result
CREATE TABLE ${flyway:defaultSchema}.score_result (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    score_job_id uuid NOT NULL,
    person_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    profile_id uuid NOT NULL,
    input_hash bytea NOT NULL,
    policy_pack_version_id uuid NOT NULL,
    policy_content_hash bytea NOT NULL,
    engine_version varchar(32) NOT NULL,
    result_doc jsonb NOT NULL,
    result_hash bytea NOT NULL,
    status varchar(8) NOT NULL,
    computed_at timestamptz NOT NULL,
    CONSTRAINT pk_score_result PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_score_result__score_job FOREIGN KEY (period_bucket, tenant_id, score_job_id)
        REFERENCES ${flyway:defaultSchema}.score_job (period_bucket, tenant_id, id),
    CONSTRAINT ck_score_result__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_score_result__status CHECK (status IN ('OK', 'LOI')),
    CONSTRAINT ck_score_result__hashes CHECK (octet_length(input_hash) = 32
        AND octet_length(policy_content_hash) = 32 AND octet_length(result_hash) = 32)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_score_result__profile ON ${flyway:defaultSchema}.score_result (tenant_id, profile_id, computed_at);
CREATE INDEX ix_score_result__person ON ${flyway:defaultSchema}.score_result (tenant_id, person_id, flow);
CREATE INDEX ix_score_result__score_job ON ${flyway:defaultSchema}.score_result (tenant_id, score_job_id);

COMMENT ON TABLE ${flyway:defaultSchema}.score_result IS 'Kết quả tính điểm của một hồ sơ, chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.id IS '{"cls":"C1","desc":"Mã kết quả (UUIDv7)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.created_at IS '{"cls":"C1","desc":"Thời điểm ghi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.created_by IS '{"cls":"C1","desc":"Tài khoản dịch vụ ghi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.updated_at IS '{"cls":"C1","desc":"Bằng created_at (không cập nhật)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.updated_by IS '{"cls":"C1","desc":"Bằng created_by (không cập nhật)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.row_version IS '{"cls":"C1","desc":"Luôn 0; aggregate_version của EVT-10"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.period_id IS '{"cls":"C1","desc":"Kỳ đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.score_job_id IS '{"cls":"C1","desc":"Tác vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.person_id IS '{"cls":"C1","desc":"Người được tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.flow IS '{"cls":"C1","desc":"Luồng đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.profile_id IS '{"cls":"C1","desc":"Hồ sơ đánh giá (appraisal.appraisal_profile)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.input_hash IS '{"cls":"C1","desc":"Băm tập đầu vào (score_input_v.input_hash)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.policy_pack_version_id IS '{"cls":"C1","desc":"Phiên bản quy tắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.policy_content_hash IS '{"cls":"C1","desc":"Băm nội dung gói quy tắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.engine_version IS '{"cls":"C1","desc":"Phiên bản bộ máy tính (ảnh container)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.result_doc IS '{"cls":"C1","desc":"Kết quả đầy đủ: điểm thành phần, mức tự động, điều kiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.result_hash IS '{"cls":"C1","desc":"SHA-256 dạng chuẩn hoá của result_doc, dùng để so khi tính lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.status IS '{"cls":"C1","desc":"OK hoặc LOI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_result.computed_at IS '{"cls":"C1","desc":"Thời điểm tính"}';

-- ---------------------------------------------------------------- score_trace
CREATE TABLE ${flyway:defaultSchema}.score_trace (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    period_bucket int NOT NULL,
    score_result_id uuid NOT NULL,
    step_no int NOT NULL,
    rule_code varchar(64) NOT NULL,
    legal_ref varchar(200),
    inputs jsonb NOT NULL,
    output jsonb NOT NULL,
    explanation varchar(1000),
    CONSTRAINT pk_score_trace PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_score_trace__step UNIQUE (period_bucket, tenant_id, score_result_id, step_no),
    CONSTRAINT ck_score_trace__step_no CHECK (step_no >= 1)
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.score_trace IS 'Vết tính từng bước của một kết quả (nguồn màn hình vì sao có điểm này), chỉ ghi thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.id IS '{"cls":"C1","desc":"Mã bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.score_result_id IS '{"cls":"C1","desc":"Kết quả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.step_no IS '{"cls":"C1","desc":"Thứ tự bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.rule_code IS '{"cls":"C0","desc":"Quy tắc áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.legal_ref IS '{"cls":"C0","desc":"Điều khoản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.inputs IS '{"cls":"C1","desc":"Giá trị vào của bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.output IS '{"cls":"C1","desc":"Giá trị ra của bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_trace.explanation IS '{"cls":"C1","desc":"Câu giải thích"}';

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_job');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_input_v');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_result');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_trace');

-- Đầu vào, kết quả và vết tính chỉ ghi thêm; tác vụ không xoá (truy vết).
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_input_v FROM svc_${flyway:defaultSchema};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_result FROM svc_${flyway:defaultSchema};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_trace FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_job FROM svc_${flyway:defaultSchema};
