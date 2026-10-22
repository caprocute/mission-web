-- CORE-04-01 · Schema appraisal (MOD-04): hồ sơ đánh giá, tiêu chí chung, tự đánh giá, ảnh chụp điểm,
-- bốn mức xếp loại, giải trình, quyết định hàng loạt, xếp loại năm và phân nhóm triển vọng
-- (TL-03 "Schema appraisal (MOD-04)", "DDL mẫu: appraisal.score_snapshot", "DDL mẫu: appraisal.rating_proposal";
-- baseline 9.1). outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key đến từ
-- db/common-business (NEN-032); hàm RLS và phân vùng từ db/common (NEN-033).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 50):
--   * Khoá chính, khoá duy nhất, khoá ngoại chứa period_bucket và tenant_id như schema task, product
--     (TL-03 ghi PK (period_bucket, id)).
--   * Hai chỉ mục duy nhất một phần của TL-03 (uq_score_snapshot__current_p, uq_rating_proposal__current_p)
--     mang tiền tố ix_ theo lint NEN-030; rating_proposal.is_current DEFAULT false theo luật cột cờ (TL-03
--     DEFAULT true), ứng dụng luôn ghi giá trị tường minh.
--   * dek_id không khai khoá ngoại sang keyring.dek (lý do như product.acceptance); thay bằng CHECK "có cột
--     _enc thì có dek_id".
--   * appraisal_profile thêm return_count (bổ sung, TL-03 không có) để đếm số lần trả lại bổ sung.
--   * annual_rating giữ cả relocation_proposed và reassign_flag như TL-03; mã dùng reassign_flag (TL-02b).
--   * prospect_group.group_code, suggested_group là C2 dạng rõ có kiểm soát: chỉ svc_appraisal SELECT được
--     hai cột; ro_appraisal chỉ có SELECT theo cột (cdc_appraisal vốn chỉ đọc outbox).
--   * Trigger chặn ghi bảng con khi hồ sơ DA_KHOA và trigger bất biến của score_snapshot, rating_proposal
--     là suy luận (TL-03 không có DDL mẫu); cho phép ghi khi phiên đặt app.reopen_request_id (hậu kiểm).


-- ---------------------------------------------------------------- appraisal_profile
CREATE TABLE ${flyway:defaultSchema}.appraisal_profile (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    person_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    org_unit_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    job_title_id uuid NOT NULL,
    subject_group_id uuid,
    is_head boolean NOT NULL DEFAULT false,
    is_leadership boolean NOT NULL DEFAULT false,
    policy_pack_version_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'CHUA_LAP',
    current_snapshot_id uuid,
    final_rating_code varchar(8),
    decided_at timestamptz,
    org_asof_recorded_at timestamptz,
    org_asof_valid_date date,
    appraiser_id uuid,
    decider_id uuid,
    wf_process_instance_id varchar(64),
    locked_at timestamptz,
    submitted_at timestamptz,
    return_count int NOT NULL DEFAULT 0,
    CONSTRAINT pk_appraisal_profile PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_appraisal_profile__person UNIQUE (period_bucket, tenant_id, period_id, person_id, flow),
    CONSTRAINT ck_appraisal_profile__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_appraisal_profile__status CHECK (status IN ('CHUA_LAP', 'TU_DANH_GIA', 'CHO_THAM_DINH',
        'DA_THAM_DINH', 'CHO_QUYET_DINH', 'DA_QUYET_DINH', 'DA_KY_SO', 'DA_KHOA', 'KHIEU_NAI')),
    CONSTRAINT ck_appraisal_profile__final_rating CHECK (final_rating_code IS NULL OR final_rating_code IN ('HTXSNV', 'HTTNV', 'HTNV', 'KHTNV')),
    CONSTRAINT ck_appraisal_profile__separation CHECK (decider_id <> appraiser_id AND appraiser_id <> person_id
        AND decider_id <> person_id),
    CONSTRAINT ck_appraisal_profile__locked CHECK (status <> 'DA_KHOA' OR locked_at IS NOT NULL),
    CONSTRAINT ck_appraisal_profile__return_count CHECK (return_count >= 0)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_appraisal_profile__org_unit ON ${flyway:defaultSchema}.appraisal_profile (tenant_id, org_unit_id, period_id);
CREATE INDEX ix_appraisal_profile__person ON ${flyway:defaultSchema}.appraisal_profile (tenant_id, person_id);
CREATE INDEX ix_appraisal_profile__status ON ${flyway:defaultSchema}.appraisal_profile (tenant_id, period_id, status);

COMMENT ON TABLE ${flyway:defaultSchema}.appraisal_profile IS 'Hồ sơ đánh giá của một người, một kỳ, một luồng (MOD-04)';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.person_id IS '{"cls":"C1","desc":"Người được đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.flow IS '{"cls":"C1","desc":"Luồng Đảng hoặc Nhà nước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.org_unit_id IS '{"cls":"C1","desc":"Đơn vị tại thời điểm lập hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.assignment_id IS '{"cls":"C1","desc":"Phân công chính làm căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.job_title_id IS '{"cls":"C1","desc":"Chức danh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.subject_group_id IS '{"cls":"C1","desc":"Khối đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.is_head IS '{"cls":"C1","desc":"Người đứng đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.is_leadership IS '{"cls":"C1","desc":"Lãnh đạo, quản lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.policy_pack_version_id IS '{"cls":"C1","desc":"Phiên bản quy tắc gắn khi lập hồ sơ, đóng băng khi kỳ khoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.status IS '{"cls":"C1","desc":"Trạng thái hồ sơ (baseline 9.1)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.current_snapshot_id IS '{"cls":"C1","desc":"Ảnh chụp điểm hiện hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.final_rating_code IS '{"cls":"C1","desc":"Mức quyết định cuối cùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.decided_at IS '{"cls":"C1","desc":"Thời điểm quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.org_asof_recorded_at IS '{"cls":"C1","desc":"Mốc trục ghi nhận của dữ liệu tổ chức tại lúc quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.org_asof_valid_date IS '{"cls":"C1","desc":"Mốc trục hiệu lực (thường là ngày cuối kỳ)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.appraiser_id IS '{"cls":"C1","desc":"Người thẩm định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.decider_id IS '{"cls":"C1","desc":"Người quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.wf_process_instance_id IS '{"cls":"C1","desc":"Tiến trình duyệt trong Flowable"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.locked_at IS '{"cls":"C1","desc":"Thời điểm khoá hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.submitted_at IS '{"cls":"C1","desc":"Thời điểm nộp tự đánh giá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.appraisal_profile.return_count IS '{"cls":"C1","desc":"Số lần trả lại bổ sung (bổ sung, BR trả lại tối đa theo tham số)"}';

-- ---------------------------------------------------------------- general_criteria_result
CREATE TABLE ${flyway:defaultSchema}.general_criteria_result (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    profile_id uuid NOT NULL,
    stage varchar(12) NOT NULL,
    criteria_code varchar(16) NOT NULL,
    compliance_ratio numeric(6,4) NOT NULL,
    level varchar(20) NOT NULL,
    met boolean NOT NULL,
    comment_enc bytea,
    dek_id uuid,
    CONSTRAINT pk_general_criteria_result PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_general_criteria_result__appraisal_profile FOREIGN KEY (period_bucket, tenant_id, profile_id)
        REFERENCES ${flyway:defaultSchema}.appraisal_profile (period_bucket, tenant_id, id),
    CONSTRAINT uq_general_criteria_result__criteria UNIQUE (period_bucket, tenant_id, profile_id, stage, criteria_code),
    CONSTRAINT ck_general_criteria_result__stage CHECK (stage IN ('TU_DANH_GIA', 'THAM_DINH')),
    CONSTRAINT ck_general_criteria_result__ratio CHECK (compliance_ratio BETWEEN 0 AND 1),
    CONSTRAINT ck_general_criteria_result__level CHECK (level IN ('THUC_HIEN_TOT', 'DAM_BAO', 'CO_BAN_DAM_BAO',
        'KHONG_DAM_BAO')),
    CONSTRAINT ck_general_criteria_result__dek CHECK (comment_enc IS NULL OR dek_id IS NOT NULL)
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.general_criteria_result IS 'Kết quả tiêu chí chung theo nhóm ở bước tự đánh giá và thẩm định';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.profile_id IS '{"cls":"C1","desc":"Hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.stage IS '{"cls":"C1","desc":"Bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.criteria_code IS '{"cls":"C0","desc":"Mã nhóm tiêu chí chung (cấu hình trong Policy Pack)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.compliance_ratio IS '{"cls":"C1","desc":"Tỷ lệ đáp ứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.level IS '{"cls":"C1","desc":"Mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.met IS '{"cls":"C1","desc":"Đạt hay không đạt nhóm tiêu chí"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.comment_enc IS '{"cls":"C2","desc":"Nhận xét"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.general_criteria_result.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';

-- ---------------------------------------------------------------- self_assessment
CREATE TABLE ${flyway:defaultSchema}.self_assessment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    profile_id uuid NOT NULL,
    version_no int NOT NULL,
    form_code varchar(64) NOT NULL,
    form_version varchar(32) NOT NULL,
    structured_data jsonb NOT NULL DEFAULT '{}'::jsonb,
    claims_json jsonb,
    content_enc bytea,
    strengths_enc bytea,
    weaknesses_enc bytea,
    remedial_plan_enc bytea,
    breakthrough_enc bytea,
    dek_id uuid,
    content_hash bytea,
    submitted_at timestamptz,
    sign_request_id uuid,
    signed_at timestamptz,
    CONSTRAINT pk_self_assessment PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_self_assessment__appraisal_profile FOREIGN KEY (period_bucket, tenant_id, profile_id)
        REFERENCES ${flyway:defaultSchema}.appraisal_profile (period_bucket, tenant_id, id),
    CONSTRAINT uq_self_assessment__version UNIQUE (period_bucket, tenant_id, profile_id, version_no),
    CONSTRAINT ck_self_assessment__version_no CHECK (version_no > 0),
    CONSTRAINT ck_self_assessment__hash CHECK (content_hash IS NULL OR length(content_hash) = 32),
    CONSTRAINT ck_self_assessment__structured CHECK (jsonb_typeof(structured_data) = 'object'),
    CONSTRAINT ck_self_assessment__dek CHECK (dek_id IS NOT NULL OR num_nonnulls(content_enc, strengths_enc,
        weaknesses_enc, remedial_plan_enc, breakthrough_enc) = 0),
    CONSTRAINT ck_self_assessment__submitted CHECK (submitted_at IS NULL OR content_hash IS NOT NULL),
    CONSTRAINT ck_self_assessment__signed CHECK (signed_at IS NULL OR sign_request_id IS NOT NULL)
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.self_assessment IS 'Phiếu tự đánh giá; mỗi lần nộp lại sau trả lại là một phiên bản';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.profile_id IS '{"cls":"C1","desc":"Hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.version_no IS '{"cls":"C1","desc":"Lần nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.form_code IS '{"cls":"C1","desc":"Biểu mẫu động đã dùng (config.form_schema)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.form_version IS '{"cls":"C1","desc":"Phiên bản biểu mẫu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.structured_data IS '{"cls":"C1","desc":"Số liệu có cấu trúc, không chứa văn bản tự do"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.claims_json IS '{"cls":"C1","desc":"Các mức tự nhận theo tiêu chí, không chứa văn bản tự do"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.content_enc IS '{"cls":"C2","desc":"Nội dung tự đánh giá tổng hợp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.strengths_enc IS '{"cls":"C2","desc":"Ưu điểm tự nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.weaknesses_enc IS '{"cls":"C2","desc":"Hạn chế, khuyết điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.remedial_plan_enc IS '{"cls":"C2","desc":"Hướng khắc phục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.breakthrough_enc IS '{"cls":"C2","desc":"Đột phá, sáng tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.content_hash IS '{"cls":"C1","desc":"Băm dạng chuẩn hoá của phiếu, nội dung được ký số"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.submitted_at IS '{"cls":"C1","desc":"Thời điểm nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký số"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.self_assessment.signed_at IS '{"cls":"C1","desc":"Thời điểm ký"}';

-- ---------------------------------------------------------------- score_snapshot
CREATE TABLE ${flyway:defaultSchema}.score_snapshot (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    profile_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    snapshot_kind varchar(20) NOT NULL,
    score_result_id uuid NOT NULL,
    policy_pack_version_id uuid NOT NULL,
    policy_content_hash bytea NOT NULL,
    policy_version varchar(32) NOT NULL,
    input_hash bytea NOT NULL,
    total_score numeric(6,3) NOT NULL,
    regular_score numeric(6,3),
    key_task_score numeric(6,3),
    general_criteria_score numeric(6,3),
    kpi_score numeric(6,3),
    exceed_ratio numeric(6,4),
    objective_ratio numeric(6,4),
    general_criteria_level varchar(20),
    auto_rating_code varchar(8) NOT NULL,
    rule_checks jsonb NOT NULL,
    downgrade_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
    group_scores_json jsonb NOT NULL,
    detail_json jsonb,
    computed_at timestamptz NOT NULL,
    snapshot_hash bytea NOT NULL,
    superseded_by_id uuid,
    CONSTRAINT pk_score_snapshot PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_score_snapshot__appraisal_profile FOREIGN KEY (period_bucket, tenant_id, profile_id)
        REFERENCES ${flyway:defaultSchema}.appraisal_profile (period_bucket, tenant_id, id),
    CONSTRAINT uq_score_snapshot__result UNIQUE (period_bucket, tenant_id, score_result_id),
    CONSTRAINT ck_score_snapshot__kind CHECK (snapshot_kind IN ('TAM_TINH', 'CHOT_KY', 'TINH_LAI_HAU_KIEM')),
    CONSTRAINT ck_score_snapshot__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_score_snapshot__score CHECK (total_score BETWEEN 0 AND 100),
    CONSTRAINT ck_score_snapshot__rating CHECK (auto_rating_code IN ('HTXSNV', 'HTTNV', 'HTNV', 'KHTNV')),
    CONSTRAINT ck_score_snapshot__hashes CHECK (length(input_hash) = 32 AND length(snapshot_hash) = 32
        AND length(policy_content_hash) = 32),
    CONSTRAINT ck_score_snapshot__nn CHECK (flow <> 'NHA_NUOC'
        OR (general_criteria_score IS NOT NULL AND kpi_score IS NOT NULL)),
    CONSTRAINT ck_score_snapshot__json CHECK (jsonb_typeof(downgrade_reasons) = 'array'),
    CONSTRAINT ck_score_snapshot__superseded CHECK (superseded_by_id IS NULL OR superseded_by_id <> id)
) PARTITION BY LIST (period_bucket);
-- Mỗi hồ sơ chỉ một ảnh chụp hiện hành (TL-03 uq_score_snapshot__current_p; tiền tố ix_ theo lint NEN-030).
CREATE UNIQUE INDEX ix_score_snapshot__current_p ON ${flyway:defaultSchema}.score_snapshot (period_bucket, tenant_id, profile_id)
    WHERE superseded_by_id IS NULL;
CREATE INDEX ix_score_snapshot__tenant_period ON ${flyway:defaultSchema}.score_snapshot (tenant_id, period_id, flow);

COMMENT ON TABLE ${flyway:defaultSchema}.score_snapshot IS 'Ảnh chụp bất biến của điểm do SVC-07 tính, kèm băm đầu vào và quy tắc';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.profile_id IS '{"cls":"C1","desc":"Hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.flow IS '{"cls":"C1","desc":"Luồng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.snapshot_kind IS '{"cls":"C1","desc":"Loại ảnh chụp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.score_result_id IS '{"cls":"C1","desc":"Kết quả tính ở SVC-07 (scoring.score_result)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.policy_pack_version_id IS '{"cls":"C1","desc":"Phiên bản quy tắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.policy_content_hash IS '{"cls":"C1","desc":"Băm nội dung Policy Pack"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.policy_version IS '{"cls":"C1","desc":"Nhãn phiên bản quy tắc đọc được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.input_hash IS '{"cls":"C1","desc":"SHA-256 của tập dữ liệu đầu vào chuẩn hoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.total_score IS '{"cls":"C1","desc":"Tổng điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.regular_score IS '{"cls":"C1","desc":"Điểm nhóm thường xuyên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.key_task_score IS '{"cls":"C1","desc":"Điểm nhóm then chốt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.general_criteria_score IS '{"cls":"C1","desc":"Luồng Nhà nước: phần 30 điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.kpi_score IS '{"cls":"C1","desc":"Luồng Nhà nước: phần 70 điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.exceed_ratio IS '{"cls":"C1","desc":"Tỷ lệ nhiệm vụ vượt mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.objective_ratio IS '{"cls":"C1","desc":"Tỷ lệ hoàn thành mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.general_criteria_level IS '{"cls":"C1","desc":"Mức tiêu chí chung"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.auto_rating_code IS '{"cls":"C1","desc":"Mức tính tự động, chỉ để tham khảo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.rule_checks IS '{"cls":"C1","desc":"Điều kiện của mức cao nhất có thể, đạt hay chưa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.downgrade_reasons IS '{"cls":"C1","desc":"Lý do hạ mức kèm điều khoản và phiên bản quy tắc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.group_scores_json IS '{"cls":"C1","desc":"Điểm theo nhóm và từng nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.detail_json IS '{"cls":"C1","desc":"Chi tiết tính để hiển thị vết tính điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.computed_at IS '{"cls":"C1","desc":"Thời điểm tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.snapshot_hash IS '{"cls":"C1","desc":"SHA-256 dạng chuẩn hoá của toàn bộ ảnh chụp, ghi vào sổ cái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.score_snapshot.superseded_by_id IS '{"cls":"C1","desc":"Ảnh chụp thay thế"}';

-- ---------------------------------------------------------------- batch_decision
CREATE TABLE ${flyway:defaultSchema}.batch_decision (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    decision_no varchar(64) NOT NULL,
    decision_date date NOT NULL,
    decider_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    profile_count int NOT NULL,
    profiles_hash bytea NOT NULL,
    sign_request_id uuid,
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    CONSTRAINT pk_batch_decision PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_batch_decision__decision_no UNIQUE (period_bucket, tenant_id, decision_no),
    CONSTRAINT ck_batch_decision__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_batch_decision__profile_count CHECK (profile_count BETWEEN 1 AND 1000),
    CONSTRAINT ck_batch_decision__hash CHECK (length(profiles_hash) = 32),
    CONSTRAINT ck_batch_decision__status CHECK (status IN ('NHAP', 'CHO_KY', 'DA_KY', 'LOI_MOT_PHAN', 'HUY')),
    CONSTRAINT ck_batch_decision__signed CHECK (status NOT IN ('DA_KY', 'LOI_MOT_PHAN') OR sign_request_id IS NOT NULL)
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.batch_decision IS 'Quyết định xếp loại hàng loạt, ký lô tới 1.000 hồ sơ (SCR-0904)';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.decision_no IS '{"cls":"C1","desc":"Số quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.decision_date IS '{"cls":"C1","desc":"Ngày quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.decider_id IS '{"cls":"C1","desc":"Người ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.flow IS '{"cls":"C1","desc":"Luồng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.profile_count IS '{"cls":"C1","desc":"Số hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.profiles_hash IS '{"cls":"C1","desc":"Băm danh sách (mã hồ sơ, mức) được ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký lô"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.batch_decision.status IS '{"cls":"C1","desc":"Trạng thái ký lô"}';

-- ---------------------------------------------------------------- rating_proposal
CREATE TABLE ${flyway:defaultSchema}.rating_proposal (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    profile_id uuid NOT NULL,
    level_kind varchar(12) NOT NULL,
    rating_code varchar(8) NOT NULL,
    score_snapshot_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    actor_role varchar(24) NOT NULL,
    is_current boolean NOT NULL DEFAULT false,
    differs_from_prev boolean NOT NULL DEFAULT false,
    explanation_required boolean NOT NULL DEFAULT false,
    is_bulk_decision boolean NOT NULL DEFAULT false,
    batch_decision_id uuid,
    reason_enc bytea,
    comment_enc bytea,
    dek_id uuid,
    sign_request_id uuid,
    signed_at timestamptz,
    proposed_at timestamptz NOT NULL,
    CONSTRAINT pk_rating_proposal PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_rating_proposal__appraisal_profile FOREIGN KEY (period_bucket, tenant_id, profile_id)
        REFERENCES ${flyway:defaultSchema}.appraisal_profile (period_bucket, tenant_id, id),
    CONSTRAINT fk_rating_proposal__score_snapshot FOREIGN KEY (period_bucket, tenant_id, score_snapshot_id)
        REFERENCES ${flyway:defaultSchema}.score_snapshot (period_bucket, tenant_id, id),
    CONSTRAINT fk_rating_proposal__batch_decision FOREIGN KEY (period_bucket, tenant_id, batch_decision_id)
        REFERENCES ${flyway:defaultSchema}.batch_decision (period_bucket, tenant_id, id),
    CONSTRAINT ck_rating_proposal__level_kind CHECK (level_kind IN ('TU_DE_XUAT', 'THAM_DINH', 'DE_XUAT', 'QUYET_DINH')),
    CONSTRAINT ck_rating_proposal__rating CHECK (rating_code IN ('HTXSNV', 'HTTNV', 'HTNV', 'KHTNV')),
    CONSTRAINT ck_rating_proposal__role CHECK (
        (level_kind = 'TU_DE_XUAT' AND actor_role = 'staff') OR
        (level_kind = 'THAM_DINH'  AND actor_role = 'appraiser') OR
        (level_kind = 'DE_XUAT'    AND actor_role IN ('manager', 'appraiser')) OR
        (level_kind = 'QUYET_DINH' AND actor_role = 'decider')),
    CONSTRAINT ck_rating_proposal__actor_is_human CHECK (actor_id = created_by),
    CONSTRAINT ck_rating_proposal__signed CHECK (level_kind NOT IN ('TU_DE_XUAT', 'THAM_DINH', 'QUYET_DINH')
        OR signed_at IS NULL OR sign_request_id IS NOT NULL),
    CONSTRAINT ck_rating_proposal__bulk CHECK (is_bulk_decision = (batch_decision_id IS NOT NULL)),
    CONSTRAINT ck_rating_proposal__bulk_decider CHECK (NOT is_bulk_decision OR level_kind = 'QUYET_DINH'),
    CONSTRAINT ck_rating_proposal__dek CHECK (dek_id IS NOT NULL OR num_nonnulls(reason_enc, comment_enc) = 0)
) PARTITION BY LIST (period_bucket);
-- Mỗi bước của hồ sơ chỉ một mức hiện hành (TL-03 uq_rating_proposal__current_p; tiền tố ix_ theo lint).
CREATE UNIQUE INDEX ix_rating_proposal__current_p ON ${flyway:defaultSchema}.rating_proposal
    (period_bucket, tenant_id, profile_id, level_kind) WHERE is_current;
CREATE INDEX ix_rating_proposal__decision ON ${flyway:defaultSchema}.rating_proposal
    (tenant_id, period_id, level_kind, rating_code) WHERE is_current;
CREATE INDEX ix_rating_proposal__batch ON ${flyway:defaultSchema}.rating_proposal (period_bucket, tenant_id, batch_decision_id)
    WHERE batch_decision_id IS NOT NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.rating_proposal IS 'Mức xếp loại do con người đưa ra ở bốn bước; sửa mức luôn tạo dòng mới';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.profile_id IS '{"cls":"C1","desc":"Hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.level_kind IS '{"cls":"C1","desc":"Loại mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.rating_code IS '{"cls":"C1","desc":"Mức xếp loại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.score_snapshot_id IS '{"cls":"C1","desc":"Ảnh chụp điểm làm căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.actor_id IS '{"cls":"C1","desc":"Người đưa ra mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.actor_role IS '{"cls":"C1","desc":"Vai trò của người đưa ra mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.is_current IS '{"cls":"C1","desc":"Dòng hiện hành của bước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.differs_from_prev IS '{"cls":"C1","desc":"Khác mức bước trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.explanation_required IS '{"cls":"C1","desc":"Bắt buộc giải trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.is_bulk_decision IS '{"cls":"C1","desc":"Quyết định hàng loạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.batch_decision_id IS '{"cls":"C1","desc":"Quyết định hàng loạt chứa dòng này"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.reason_enc IS '{"cls":"C2","desc":"Lý do đưa ra mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.comment_enc IS '{"cls":"C2","desc":"Nhận xét"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký số tại mốc pháp lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.signed_at IS '{"cls":"C1","desc":"Thời điểm ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_proposal.proposed_at IS '{"cls":"C1","desc":"Thời điểm đưa ra mức"}';

-- ---------------------------------------------------------------- rating_explanation
CREATE TABLE ${flyway:defaultSchema}.rating_explanation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    rating_proposal_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    explanation_type varchar(16) NOT NULL,
    auto_text varchar(2000),
    legal_ref varchar(200),
    system_level varchar(8),
    conditions_json jsonb,
    explanation_enc bytea,
    dek_id uuid,
    CONSTRAINT pk_rating_explanation PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_rating_explanation__rating_proposal FOREIGN KEY (period_bucket, tenant_id, rating_proposal_id)
        REFERENCES ${flyway:defaultSchema}.rating_proposal (period_bucket, tenant_id, id),
    CONSTRAINT fk_rating_explanation__appraisal_profile FOREIGN KEY (period_bucket, tenant_id, profile_id)
        REFERENCES ${flyway:defaultSchema}.appraisal_profile (period_bucket, tenant_id, id),
    CONSTRAINT ck_rating_explanation__type CHECK (explanation_type IN ('HA_MUC', 'KHAC_MUC_TRUOC', 'BAO_LUU')),
    CONSTRAINT ck_rating_explanation__system_level CHECK (system_level IS NULL OR system_level IN ('HTXSNV', 'HTTNV', 'HTNV', 'KHTNV')),
    CONSTRAINT ck_rating_explanation__dek CHECK (explanation_enc IS NULL OR dek_id IS NOT NULL)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_rating_explanation__profile ON ${flyway:defaultSchema}.rating_explanation (period_bucket, tenant_id, profile_id);
CREATE INDEX ix_rating_explanation__proposal ON ${flyway:defaultSchema}.rating_explanation (period_bucket, tenant_id, rating_proposal_id);

COMMENT ON TABLE ${flyway:defaultSchema}.rating_explanation IS 'Giải trình khi mức hạ hoặc khác mức bước trước';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.rating_proposal_id IS '{"cls":"C1","desc":"Mức được giải trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.profile_id IS '{"cls":"C1","desc":"Hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.explanation_type IS '{"cls":"C1","desc":"Loại giải trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.auto_text IS '{"cls":"C1","desc":"Câu giải thích do bộ máy quy tắc sinh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.legal_ref IS '{"cls":"C0","desc":"Điều khoản căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.system_level IS '{"cls":"C1","desc":"Mức hệ thống tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.conditions_json IS '{"cls":"C1","desc":"Điều kiện đạt hoặc chưa đạt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.explanation_enc IS '{"cls":"C2","desc":"Lời giải trình của người đưa ra mức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.rating_explanation.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';

-- ---------------------------------------------------------------- annual_rating
CREATE TABLE ${flyway:defaultSchema}.annual_rating (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    person_id uuid NOT NULL,
    flow varchar(12) NOT NULL,
    profile_id uuid NOT NULL,
    quarter_ratings varchar(8)[] NOT NULL DEFAULT '{}',
    has_khtnv_quarter boolean NOT NULL DEFAULT false,
    consecutive_khtnv boolean NOT NULL DEFAULT false,
    annual_score numeric(6,3),
    system_level varchar(8),
    rating_code varchar(8),
    block_reason varchar(32),
    relocation_proposed boolean NOT NULL DEFAULT false,
    reassign_flag boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_annual_rating PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_annual_rating__appraisal_profile FOREIGN KEY (period_bucket, tenant_id, profile_id)
        REFERENCES ${flyway:defaultSchema}.appraisal_profile (period_bucket, tenant_id, id),
    CONSTRAINT uq_annual_rating__person UNIQUE (period_bucket, tenant_id, person_id, flow),
    CONSTRAINT ck_annual_rating__bucket CHECK (period_bucket % 10 = 0),
    CONSTRAINT ck_annual_rating__flow CHECK (flow IN ('DANG', 'NHA_NUOC')),
    CONSTRAINT ck_annual_rating__quarters CHECK (cardinality(quarter_ratings) <= 4
        AND quarter_ratings <@ ARRAY['HTXSNV', 'HTTNV', 'HTNV', 'KHTNV']::varchar(8)[]),
    CONSTRAINT ck_annual_rating__rating CHECK (rating_code IS NULL OR rating_code IN ('HTXSNV', 'HTTNV', 'HTNV', 'KHTNV')),
    CONSTRAINT ck_annual_rating__system_level CHECK (system_level IS NULL OR system_level IN ('HTXSNV', 'HTTNV', 'HTNV', 'KHTNV')),
    CONSTRAINT ck_annual_rating__khtnv CHECK (NOT has_khtnv_quarter OR rating_code IS DISTINCT FROM 'HTXSNV')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_annual_rating__profile ON ${flyway:defaultSchema}.annual_rating (period_bucket, tenant_id, profile_id);

COMMENT ON TABLE ${flyway:defaultSchema}.annual_rating IS 'Xếp loại năm liên thông bốn quý (QĐ 15.5 đến 15.6)';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.person_id IS '{"cls":"C1","desc":"Người"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.flow IS '{"cls":"C1","desc":"Luồng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.profile_id IS '{"cls":"C1","desc":"Hồ sơ năm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.quarter_ratings IS '{"cls":"C1","desc":"Mức của các quý trong năm theo thứ tự","type_exception":"TL-03 chốt mảng varchar(8)[] tối đa bốn phần tử"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.has_khtnv_quarter IS '{"cls":"C1","desc":"Có quý không hoàn thành nhiệm vụ (chặn mức xuất sắc năm)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.consecutive_khtnv IS '{"cls":"C1","desc":"Hai quý liên tiếp không hoàn thành nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.annual_score IS '{"cls":"C1","desc":"Điểm năm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.system_level IS '{"cls":"C1","desc":"Mức năm hệ thống tính (tham khảo)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.rating_code IS '{"cls":"C1","desc":"Mức năm đã quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.block_reason IS '{"cls":"C1","desc":"Lý do chặn mức cao"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.relocation_proposed IS '{"cls":"C1","desc":"Đã đề xuất bố trí lại (trùng nghĩa reassign_flag, giữ theo TL-03)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.annual_rating.reassign_flag IS '{"cls":"C1","desc":"Cờ đề xuất bố trí lại (QĐ 15.6), cột được mã dùng"}';

-- ---------------------------------------------------------------- prospect_group
CREATE TABLE ${flyway:defaultSchema}.prospect_group (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    person_id uuid NOT NULL,
    group_code varchar(8) NOT NULL,
    suggested_group varchar(8),
    rank_in_group int,
    reason_enc bytea,
    training_plan_enc bytea,
    training_plan_ref varchar(200),
    dek_id uuid,
    decided_by uuid,
    decided_at timestamptz,
    CONSTRAINT pk_prospect_group PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_prospect_group__person UNIQUE (period_bucket, tenant_id, person_id),
    CONSTRAINT ck_prospect_group__bucket CHECK (period_bucket % 10 = 0),
    CONSTRAINT ck_prospect_group__group CHECK (group_code IN ('NHOM_1', 'NHOM_2', 'NHOM_3', 'NHOM_4')),
    CONSTRAINT ck_prospect_group__suggested CHECK (suggested_group IS NULL
        OR suggested_group IN ('NHOM_1', 'NHOM_2', 'NHOM_3', 'NHOM_4')),
    CONSTRAINT ck_prospect_group__rank CHECK (rank_in_group IS NULL OR rank_in_group > 0),
    CONSTRAINT ck_prospect_group__dek CHECK (dek_id IS NOT NULL OR num_nonnulls(reason_enc, training_plan_enc) = 0),
    CONSTRAINT ck_prospect_group__decided CHECK ((decided_by IS NULL) = (decided_at IS NULL))
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.prospect_group IS 'Phân nhóm triển vọng ở kỳ năm (QĐ 12.1.5, PL 5)';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.person_id IS '{"cls":"C1","desc":"Người"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.group_code IS '{"cls":"C2","desc":"Nhóm triển vọng","enc":false,"justification":"Thông tin quy hoạch cần lọc, đếm theo nhóm: không mã hoá trường, chỉ svc_appraisal có SELECT trên cột, ABAC chỉ cho cơ quan tổ chức cán bộ, CDC phân tích loại trừ (TL-03)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.suggested_group IS '{"cls":"C2","desc":"Nhóm do hệ thống gợi ý","enc":false,"justification":"Thông tin quy hoạch cần lọc, đếm theo nhóm: không mã hoá trường, chỉ svc_appraisal có SELECT trên cột, ABAC chỉ cho cơ quan tổ chức cán bộ, CDC phân tích loại trừ (TL-03)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.rank_in_group IS '{"cls":"C1","desc":"Thứ tự trong nhóm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.reason_enc IS '{"cls":"C2","desc":"Lý do xếp nhóm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.training_plan_enc IS '{"cls":"C2","desc":"Kế hoạch đào tạo, bồi dưỡng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.training_plan_ref IS '{"cls":"C1","desc":"Tham chiếu kế hoạch đào tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.decided_by IS '{"cls":"C1","desc":"Người quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_group.decided_at IS '{"cls":"C1","desc":"Thời điểm quyết định"}';

-- ---------------------------------------------------------------- bất biến

-- Ảnh chụp điểm bất biến; superseded_by_id chỉ điền một lần (TL-03 DDL mẫu, quyền cột bên dưới là lớp 1).
CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_score_snapshot() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF OLD.superseded_by_id IS NOT NULL AND NEW.superseded_by_id IS DISTINCT FROM OLD.superseded_by_id THEN
        RAISE EXCEPTION 'Ảnh chụp điểm đã bị thay thế, không đổi được ảnh chụp thay thế'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['superseded_by_id', 'updated_at', 'updated_by', 'row_version'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['superseded_by_id', 'updated_at', 'updated_by', 'row_version']) THEN
        RAISE EXCEPTION 'Ảnh chụp điểm là bất biến; hãy tạo ảnh chụp mới'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_score_snapshot__freeze BEFORE UPDATE ON ${flyway:defaultSchema}.score_snapshot
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_score_snapshot();

-- Mức đã đưa ra không sửa: chỉ được thôi hiện hành (true -> false), điền ký số và cờ giải trình.
CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_rating_proposal() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF NEW.is_current AND NOT OLD.is_current THEN
        RAISE EXCEPTION 'Không khôi phục được mức đã bị thay; hãy tạo mức mới'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['is_current', 'sign_request_id', 'signed_at', 'explanation_required',
                              'updated_at', 'updated_by', 'row_version'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['is_current', 'sign_request_id', 'signed_at',
                              'explanation_required', 'updated_at', 'updated_by', 'row_version']) THEN
        RAISE EXCEPTION 'Mức xếp loại đã đưa ra không được sửa; hãy tạo mức mới'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_rating_proposal__freeze BEFORE UPDATE ON ${flyway:defaultSchema}.rating_proposal
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_rating_proposal();

-- ---------------------------------------------------------------- khoá hồ sơ (lớp 2 ở CSDL, suy luận)

-- Hồ sơ DA_KHOA: chặn mọi ghi vào hồ sơ và bảng con, trừ khi phiên mang mã yêu cầu mở lại đã duyệt
-- (SET LOCAL app.reopen_request_id, CORE-07-08/09). nullif vì biến tuỳ chỉnh đã đặt một lần trong phiên
-- trả chuỗi rỗng chứ không trả NULL sau khi giao dịch kết thúc.
CREATE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_row    jsonb;
    v_status varchar(16);
BEGIN
    IF nullif(current_setting('app.reopen_request_id', true), '') IS NOT NULL THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    -- TG_TABLE_NAME là tên phân vùng con nên bảng hồ sơ được nhận qua tham số trigger; dòng đọc qua
    -- jsonb vì một hàm phục vụ bảy kiểu dòng khác nhau.
    IF TG_NARGS > 0 AND TG_ARGV[0] = 'profile' THEN
        v_status := OLD.status;
    ELSE
        v_row := to_jsonb(CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END);
        SELECT p.status INTO v_status
          FROM ${flyway:defaultSchema}.appraisal_profile p
         WHERE p.period_bucket = (v_row->>'period_bucket')::int
           AND p.tenant_id = (v_row->>'tenant_id')::uuid
           AND p.id = (v_row->>'profile_id')::uuid;
    END IF;
    IF v_status = 'DA_KHOA' THEN
        RAISE EXCEPTION 'Hồ sơ đánh giá đã khoá, không được ghi'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$fn$;
CREATE TRIGGER tg_appraisal_profile__lock_guard BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.appraisal_profile
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile('profile');
CREATE TRIGGER tg_general_criteria_result__lock_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.general_criteria_result
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile();
CREATE TRIGGER tg_self_assessment__lock_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.self_assessment
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile();
CREATE TRIGGER tg_score_snapshot__lock_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.score_snapshot
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile();
CREATE TRIGGER tg_rating_proposal__lock_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.rating_proposal
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile();
CREATE TRIGGER tg_rating_explanation__lock_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.rating_explanation
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile();
CREATE TRIGGER tg_annual_rating__lock_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.annual_rating
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_locked_profile();

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.appraisal_profile');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.general_criteria_result');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.self_assessment');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.score_snapshot');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.batch_decision');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.rating_proposal');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.rating_explanation');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.annual_rating');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.prospect_group');

-- Ảnh chụp điểm: chỉ cập nhật được cột thay thế và cột kỹ thuật (TL-03 DDL mẫu).
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.score_snapshot FROM svc_${flyway:defaultSchema};
GRANT UPDATE (superseded_by_id, updated_at, updated_by, row_version)
    ON ${flyway:defaultSchema}.score_snapshot TO svc_${flyway:defaultSchema};
-- Mức đề xuất và hồ sơ không xoá (truy vết).
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.appraisal_profile FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.self_assessment FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.batch_decision FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.rating_proposal FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.rating_explanation FROM svc_${flyway:defaultSchema};

-- Nhóm triển vọng: chỉ svc_appraisal đọc được group_code, suggested_group (TL-03).
REVOKE SELECT ON ${flyway:defaultSchema}.prospect_group FROM ro_${flyway:defaultSchema};
GRANT SELECT (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version, period_id,
              period_bucket, person_id, rank_in_group, reason_enc, training_plan_enc, training_plan_ref,
              dek_id, decided_by, decided_at)
    ON ${flyway:defaultSchema}.prospect_group TO ro_${flyway:defaultSchema};
