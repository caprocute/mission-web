-- CORE-07-01 · Schema oversight (MOD-07): khiếu nại và trả lời, mở lại kỳ bốn mắt, hậu kiểm, cảnh báo
-- bất thường và hành động xử lý (TL-03 "Schema oversight (MOD-07)", TL-02b QT-12 đến QT-14).
-- outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key đến từ db/common-business (NEN-032);
-- hàm RLS và phân vùng từ db/common (NEN-033).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 54):
--   * Khoá chính, khoá duy nhất, khoá ngoại chứa period_bucket và tenant_id như các schema nghiệp vụ khác
--     (TL-03 ghi PK (period_bucket, id)); UQ mã yêu cầu mở lại là (period_bucket, tenant_id, code).
--   * alert_action.action theo hợp đồng oversight.yaml (thêm KET_LUAN theo TL-02b SCR-1401 và TU_DONG); TL-03
--     chỉ có bốn giá trị. Cột bổ sung theo hợp đồng: alert_action.is_active (cờ chặn còn hiệu lực của hành
--     động tự động, BR-14-04) và alert_action.verdict.
--   * dek_id không khai khoá ngoại sang keyring.dek (lý do như product.acceptance); thay bằng CHECK "có cột
--     _enc thì có dek_id".
--   * complaint_response và reopen_approval chỉ thêm (REVOKE UPDATE, DELETE), suy luận từ BR-12-10 và bốn mắt.
--   * Trigger so approver_id với requested_by là lớp CSDL; điều kiện DA_DUYET (đủ phê duyệt, có second_approver)
--     kiểm ở mã trong cùng giao dịch với SELECT ... FOR UPDATE (TL-03).

-- ---------------------------------------------------------------- complaint
CREATE TABLE ${flyway:defaultSchema}.complaint (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    complainant_person_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    kind varchar(12) NOT NULL,
    target_stage varchar(12) NOT NULL,
    subject_line varchar(255) NOT NULL,
    targets_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    content_enc bytea NOT NULL,
    request_text_enc bytea,
    reserve_opinion_enc bytea,
    dek_id uuid NOT NULL,
    attachment_object_ids uuid[] NOT NULL DEFAULT '{}',
    filed_at timestamptz NOT NULL,
    response_deadline date NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'MOI',
    handler_unit_id uuid NOT NULL,
    outcome varchar(12),
    wf_process_instance_id varchar(64),
    CONSTRAINT pk_complaint PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT ck_complaint__kind CHECK (kind IN ('KHIEU_NAI', 'GIAI_TRINH', 'BAO_LUU')),
    CONSTRAINT ck_complaint__target_stage CHECK (target_stage IN ('THAM_DINH', 'DE_XUAT', 'QUYET_DINH')),
    CONSTRAINT ck_complaint__status CHECK (status IN ('MOI', 'DA_TIEP_NHAN', 'DANG_GIAI_TRINH', 'CHO_KET_LUAN',
        'DA_TRA_LOI', 'DANG_THI_HANH', 'KHONG_THU_LY', 'RUT', 'DONG')),
    CONSTRAINT ck_complaint__outcome CHECK (outcome IN ('GIU_NGUYEN', 'DIEU_CHINH', 'BAO_LUU')),
    CONSTRAINT ck_complaint__deadline CHECK (response_deadline >= (filed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_complaint__complainant ON ${flyway:defaultSchema}.complaint (tenant_id, complainant_person_id);
CREATE INDEX ix_complaint__profile ON ${flyway:defaultSchema}.complaint (period_bucket, tenant_id, profile_id);
CREATE INDEX ix_complaint__handler ON ${flyway:defaultSchema}.complaint (tenant_id, handler_unit_id, status);

COMMENT ON TABLE ${flyway:defaultSchema}.complaint IS 'Khiếu nại, giải trình, bảo lưu ý kiến về kết quả đánh giá (QT-12)';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.complainant_person_id IS '{"cls":"C1","desc":"Người khiếu nại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.profile_id IS '{"cls":"C1","desc":"Hồ sơ đánh giá (appraisal.appraisal_profile)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.kind IS '{"cls":"C1","desc":"Loại: khiếu nại, giải trình, bảo lưu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.target_stage IS '{"cls":"C1","desc":"Bước bị khiếu nại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.subject_line IS '{"cls":"C1","desc":"Tiêu đề chung, không chứa nội dung nhạy cảm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.targets_json IS '{"cls":"C1","desc":"Các nội dung bị khiếu nại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.content_enc IS '{"cls":"C2","desc":"Nội dung khiếu nại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.request_text_enc IS '{"cls":"C2","desc":"Yêu cầu cụ thể"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.reserve_opinion_enc IS '{"cls":"C2","desc":"Ý kiến bảo lưu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.attachment_object_ids IS '{"cls":"C1","desc":"Tệp kèm ở SVC-08","type_exception":"Danh sách mã tệp kèm theo TL-03"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.filed_at IS '{"cls":"C1","desc":"Thời điểm gửi (EVT-25)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.response_deadline IS '{"cls":"C1","desc":"Hạn trả lời"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.status IS '{"cls":"C1","desc":"Trạng thái khiếu nại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.handler_unit_id IS '{"cls":"C1","desc":"Đơn vị xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.outcome IS '{"cls":"C1","desc":"Kết quả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.wf_process_instance_id IS '{"cls":"C1","desc":"Tiến trình xử lý"}';

-- ---------------------------------------------------------------- complaint_response
CREATE TABLE ${flyway:defaultSchema}.complaint_response (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    complaint_id uuid NOT NULL,
    record_kind varchar(12) NOT NULL,
    responder_id uuid NOT NULL,
    response_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    response_doc_ref varchar(200),
    responded_at timestamptz NOT NULL,
    sign_request_id uuid,
    outcome varchar(12),
    adjustment_json jsonb,
    CONSTRAINT pk_complaint_response PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_complaint_response__complaint FOREIGN KEY (period_bucket, tenant_id, complaint_id)
        REFERENCES ${flyway:defaultSchema}.complaint (period_bucket, tenant_id, id),
    CONSTRAINT ck_complaint_response__record_kind CHECK (record_kind IN ('YEU_CAU', 'GIAI_TRINH', 'KET_LUAN', 'BAO_LUU')),
    CONSTRAINT ck_complaint_response__outcome CHECK (outcome IN ('GIU_NGUYEN', 'DIEU_CHINH', 'BAO_LUU')),
    CONSTRAINT ck_complaint_response__adjustment CHECK (adjustment_json IS NULL OR (outcome = 'DIEU_CHINH'
        AND jsonb_typeof(adjustment_json) = 'object')),
    CONSTRAINT ck_complaint_response__conclusion CHECK (record_kind <> 'KET_LUAN' OR (outcome IS NOT NULL
        AND sign_request_id IS NOT NULL))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_complaint_response__complaint ON ${flyway:defaultSchema}.complaint_response (period_bucket, tenant_id, complaint_id);

COMMENT ON TABLE ${flyway:defaultSchema}.complaint_response IS 'Bản ghi xử lý khiếu nại: yêu cầu, giải trình, kết luận, bảo lưu; chỉ thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.complaint_id IS '{"cls":"C1","desc":"Khiếu nại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.record_kind IS '{"cls":"C1","desc":"Loại bản ghi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.responder_id IS '{"cls":"C1","desc":"Người trả lời"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.response_enc IS '{"cls":"C2","desc":"Nội dung trả lời bằng văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.response_doc_ref IS '{"cls":"C1","desc":"Văn bản trả lời đã ban hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.responded_at IS '{"cls":"C1","desc":"Thời điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.sign_request_id IS '{"cls":"C1","desc":"Ký số"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.outcome IS '{"cls":"C1","desc":"Kết quả"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint_response.adjustment_json IS '{"cls":"C1","desc":"Điều chỉnh mức hoặc điểm nếu có"}';

-- ---------------------------------------------------------------- reopen_request
CREATE TABLE ${flyway:defaultSchema}.reopen_request (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    code varchar(32) NOT NULL,
    scope varchar(12) NOT NULL,
    scope_ids uuid[] NOT NULL DEFAULT '{}',
    scope_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    reason varchar(4000) NOT NULL,
    basis_ref varchar(200) NOT NULL,
    audit_kind varchar(16) NOT NULL,
    requested_by uuid NOT NULL,
    approvals_required smallint NOT NULL DEFAULT 2,
    status varchar(16) NOT NULL DEFAULT 'CHO_DUYET_1',
    open_until timestamptz NOT NULL,
    executed_at timestamptz,
    relocked_at timestamptz,
    CONSTRAINT pk_reopen_request PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_reopen_request__code UNIQUE (period_bucket, tenant_id, code),
    CONSTRAINT ck_reopen_request__scope CHECK (scope IN ('TOAN_KY', 'DON_VI', 'HO_SO')),
    CONSTRAINT ck_reopen_request__scope_ids CHECK (scope = 'TOAN_KY' OR cardinality(scope_ids) > 0),
    CONSTRAINT ck_reopen_request__approvals CHECK (approvals_required >= 2),
    CONSTRAINT ck_reopen_request__status CHECK (status IN ('CHO_DUYET_1', 'CHO_DUYET_2', 'DA_DUYET', 'TU_CHOI',
        'DA_THUC_HIEN', 'DA_DONG')),
    CONSTRAINT ck_reopen_request__executed CHECK (status NOT IN ('DA_THUC_HIEN', 'DA_DONG') OR executed_at IS NOT NULL
        OR status = 'DA_DONG'),
    CONSTRAINT ck_reopen_request__relocked CHECK (relocked_at IS NULL OR (executed_at IS NOT NULL
        AND relocked_at >= executed_at))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_reopen_request__status ON ${flyway:defaultSchema}.reopen_request (tenant_id, period_id, status);

COMMENT ON TABLE ${flyway:defaultSchema}.reopen_request IS 'Yêu cầu mở lại kỳ đã khoá để hậu kiểm, cần hai phê duyệt (bốn mắt)';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.code IS '{"cls":"C1","desc":"Mã yêu cầu MLK-yyyy-nnn (định dạng kiểm ở mã)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.scope IS '{"cls":"C1","desc":"Phạm vi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.scope_ids IS '{"cls":"C1","desc":"Đơn vị hoặc hồ sơ","type_exception":"Danh sách mã phạm vi chuẩn hoá để lọc theo TL-03"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.scope_json IS '{"cls":"C1","desc":"Phạm vi chi tiết"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.reason IS '{"cls":"C1","desc":"Lý do"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.basis_ref IS '{"cls":"C1","desc":"Căn cứ (kết luận kiểm tra, quyết định hậu kiểm)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.audit_kind IS '{"cls":"C1","desc":"Loại hậu kiểm làm căn cứ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.requested_by IS '{"cls":"C1","desc":"Người yêu cầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.approvals_required IS '{"cls":"C1","desc":"Số phê duyệt cần","type_exception":"TL-03 chốt smallint cho số phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.status IS '{"cls":"C1","desc":"Trạng thái yêu cầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.open_until IS '{"cls":"C1","desc":"Hạn mở lại, quá hạn tự khoá lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.executed_at IS '{"cls":"C1","desc":"Thời điểm kỳ chuyển sang mở lại hậu kiểm (EVT-22)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_request.relocked_at IS '{"cls":"C1","desc":"Thời điểm khoá lại"}';

-- ---------------------------------------------------------------- reopen_approval
CREATE TABLE ${flyway:defaultSchema}.reopen_approval (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    reopen_request_id uuid NOT NULL,
    approver_id uuid NOT NULL,
    approver_role varchar(24) NOT NULL,
    decision varchar(8) NOT NULL,
    mfa_acr varchar(32) NOT NULL,
    decided_at timestamptz NOT NULL,
    sign_request_id uuid NOT NULL,
    comment varchar(2000),
    CONSTRAINT pk_reopen_approval PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_reopen_approval__reopen_request FOREIGN KEY (period_bucket, tenant_id, reopen_request_id)
        REFERENCES ${flyway:defaultSchema}.reopen_request (period_bucket, tenant_id, id),
    CONSTRAINT uq_reopen_approval__approver UNIQUE (period_bucket, tenant_id, reopen_request_id, approver_id),
    CONSTRAINT ck_reopen_approval__role CHECK (approver_role IN ('decider', 'second_approver')),
    CONSTRAINT ck_reopen_approval__decision CHECK (decision IN ('DONG_Y', 'TU_CHOI'))
) PARTITION BY LIST (period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.reopen_approval IS 'Phê duyệt yêu cầu mở lại kỳ, qua xác thực bước lên và ký số; chỉ thêm';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.reopen_request_id IS '{"cls":"C1","desc":"Yêu cầu mở lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.approver_id IS '{"cls":"C1","desc":"Người phê duyệt, khác người yêu cầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.approver_role IS '{"cls":"C1","desc":"Vai trò phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.decision IS '{"cls":"C1","desc":"Quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.mfa_acr IS '{"cls":"C1","desc":"Mức xác thực của phiên bước lên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.decided_at IS '{"cls":"C1","desc":"Thời điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.sign_request_id IS '{"cls":"C1","desc":"Ký số phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_approval.comment IS '{"cls":"C1","desc":"Ý kiến người duyệt"}';

-- ---------------------------------------------------------------- post_audit
CREATE TABLE ${flyway:defaultSchema}.post_audit (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    audit_kind varchar(16) NOT NULL,
    target_type varchar(16) NOT NULL,
    target_id uuid NOT NULL,
    inspector_id uuid NOT NULL,
    findings_enc bytea,
    dek_id uuid,
    conclusion varchar(16),
    reopen_request_id uuid,
    opened_at timestamptz NOT NULL,
    closed_at timestamptz,
    CONSTRAINT pk_post_audit PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_post_audit__reopen_request FOREIGN KEY (period_bucket, tenant_id, reopen_request_id)
        REFERENCES ${flyway:defaultSchema}.reopen_request (period_bucket, tenant_id, id),
    CONSTRAINT ck_post_audit__audit_kind CHECK (audit_kind IN ('THEO_KE_HOACH', 'DOT_XUAT', 'THEO_PHAN_ANH')),
    CONSTRAINT ck_post_audit__target_type CHECK (target_type IN ('HO_SO', 'DON_VI', 'NHIEM_VU', 'MINH_CHUNG')),
    CONSTRAINT ck_post_audit__conclusion CHECK (conclusion IN ('KHONG_SAI_LECH', 'HUY_KET_QUA', 'DANH_GIA_LAI')),
    CONSTRAINT ck_post_audit__dek CHECK ((findings_enc IS NULL) OR dek_id IS NOT NULL),
    CONSTRAINT ck_post_audit__closed CHECK (closed_at IS NULL OR (closed_at >= opened_at AND conclusion IS NOT NULL))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_post_audit__target ON ${flyway:defaultSchema}.post_audit (tenant_id, target_type, target_id);

COMMENT ON TABLE ${flyway:defaultSchema}.post_audit IS 'Hậu kiểm theo kế hoạch, đột xuất hoặc theo phản ánh (QĐ 15.3)';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.audit_kind IS '{"cls":"C1","desc":"Loại hậu kiểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.target_type IS '{"cls":"C1","desc":"Đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.target_id IS '{"cls":"C1","desc":"Mã đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.inspector_id IS '{"cls":"C1","desc":"Người kiểm tra"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.findings_enc IS '{"cls":"C2","desc":"Phát hiện, kết luận chi tiết"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.conclusion IS '{"cls":"C1","desc":"Kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.reopen_request_id IS '{"cls":"C1","desc":"Yêu cầu mở lại sinh từ hậu kiểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.opened_at IS '{"cls":"C1","desc":"Thời điểm mở"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.closed_at IS '{"cls":"C1","desc":"Thời điểm đóng"}';

-- ---------------------------------------------------------------- anomaly_alert
CREATE TABLE ${flyway:defaultSchema}.anomaly_alert (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    signal_code varchar(24) NOT NULL,
    severity varchar(8) NOT NULL,
    subject_type varchar(12) NOT NULL,
    subject_id uuid NOT NULL,
    evidence_chain jsonb NOT NULL,
    detected_at timestamptz NOT NULL,
    source_event_id uuid NOT NULL,
    model_version varchar(32) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'MOI',
    CONSTRAINT pk_anomaly_alert PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_anomaly_alert__source_event UNIQUE (tenant_id, source_event_id, period_bucket),
    CONSTRAINT ck_anomaly_alert__signal CHECK (signal_code IN ('TU_CHAM_CAO', 'CAO_BANG', 'MUC_TIEU_DE',
        'MINH_CHUNG_TRUNG', 'CAP_NHAT_DON', 'SUA_SAU_KHOA', 'CHE_NHO', 'MIEN_TRU_LAP')),
    CONSTRAINT ck_anomaly_alert__severity CHECK (severity IN ('CAO', 'VUA', 'THAP')),
    CONSTRAINT ck_anomaly_alert__subject_type CHECK (subject_type IN ('CA_NHAN', 'DON_VI', 'NGUOI_DUYET')),
    CONSTRAINT ck_anomaly_alert__status CHECK (status IN ('MOI', 'DANG_XEM', 'HOP_LE', 'DA_GIAO_KIEM_TRA', 'CO_VI_PHAM',
        'KHONG_VI_PHAM', 'TU_DONG_DONG', 'DONG')),
    CONSTRAINT ck_anomaly_alert__evidence CHECK (jsonb_typeof(evidence_chain) = 'array')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_anomaly_alert__subject ON ${flyway:defaultSchema}.anomaly_alert (tenant_id, subject_id);
CREATE INDEX ix_anomaly_alert__status ON ${flyway:defaultSchema}.anomaly_alert (tenant_id, period_id, status, severity);

COMMENT ON TABLE ${flyway:defaultSchema}.anomaly_alert IS 'Cảnh báo bất thường nhận từ EVT-20, kèm chuỗi bằng chứng';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.signal_code IS '{"cls":"C1","desc":"Dấu hiệu bất thường"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.severity IS '{"cls":"C1","desc":"Mức nghiêm trọng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.subject_type IS '{"cls":"C1","desc":"Loại đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.subject_id IS '{"cls":"C1","desc":"Mã đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.evidence_chain IS '{"cls":"C1","desc":"Danh sách mã bằng chứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.detected_at IS '{"cls":"C1","desc":"Thời điểm phát hiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.source_event_id IS '{"cls":"C1","desc":"Sự kiện EVT-20 nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.model_version IS '{"cls":"C1","desc":"Phiên bản quy tắc hoặc mô hình phát hiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anomaly_alert.status IS '{"cls":"C1","desc":"Trạng thái cảnh báo"}';

-- ---------------------------------------------------------------- alert_action
CREATE TABLE ${flyway:defaultSchema}.alert_action (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    anomaly_alert_id uuid NOT NULL,
    action varchar(20) NOT NULL,
    assigned_to uuid,
    verdict varchar(16),
    note varchar(4000),
    is_active boolean NOT NULL DEFAULT false,
    acted_at timestamptz NOT NULL,
    CONSTRAINT pk_alert_action PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_alert_action__anomaly_alert FOREIGN KEY (period_bucket, tenant_id, anomaly_alert_id)
        REFERENCES ${flyway:defaultSchema}.anomaly_alert (period_bucket, tenant_id, id),
    CONSTRAINT ck_alert_action__action CHECK (action IN ('XAC_NHAN_HOP_LE', 'GIAO_KIEM_TRA', 'KET_LUAN', 'GHI_CHU',
        'DONG', 'TU_DONG')),
    CONSTRAINT ck_alert_action__verdict CHECK (verdict IN ('CO_VI_PHAM', 'KHONG_VI_PHAM')),
    CONSTRAINT ck_alert_action__assigned CHECK (action <> 'GIAO_KIEM_TRA' OR assigned_to IS NOT NULL),
    CONSTRAINT ck_alert_action__concluded CHECK ((action = 'KET_LUAN') = (verdict IS NOT NULL)),
    CONSTRAINT ck_alert_action__active CHECK (NOT is_active OR action = 'TU_DONG')
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_alert_action__alert ON ${flyway:defaultSchema}.alert_action (period_bucket, tenant_id, anomaly_alert_id);

COMMENT ON TABLE ${flyway:defaultSchema}.alert_action IS 'Hành động xử lý cảnh báo bất thường';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.anomaly_alert_id IS '{"cls":"C1","desc":"Cảnh báo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.action IS '{"cls":"C1","desc":"Hành động"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.assigned_to IS '{"cls":"C1","desc":"Người được giao kiểm tra"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.verdict IS '{"cls":"C1","desc":"Kết luận khi hành động là kết luận (bổ sung theo hợp đồng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.note IS '{"cls":"C1","desc":"Ghi chú"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.is_active IS '{"cls":"C1","desc":"Cờ chặn còn hiệu lực của hành động tự động (bổ sung theo hợp đồng, BR-14-04)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.alert_action.acted_at IS '{"cls":"C1","desc":"Thời điểm"}';

-- ---------------------------------------------------------------- bốn mắt (lớp CSDL)

-- Người phê duyệt phải khác người yêu cầu (TL-03 reopen_approval); UQ ở trên bảo đảm hai người khác nhau.
CREATE FUNCTION ${flyway:defaultSchema}.fn_check_reopen_approver() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_requested_by uuid;
BEGIN
    SELECT r.requested_by INTO v_requested_by
      FROM ${flyway:defaultSchema}.reopen_request r
     WHERE r.period_bucket = NEW.period_bucket
       AND r.tenant_id = NEW.tenant_id
       AND r.id = NEW.reopen_request_id;
    IF v_requested_by IS NOT DISTINCT FROM NEW.approver_id THEN
        RAISE EXCEPTION 'Người phê duyệt phải khác người yêu cầu mở lại'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_reopen_approval__approver BEFORE INSERT OR UPDATE ON ${flyway:defaultSchema}.reopen_approval
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_check_reopen_approver();

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.complaint');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.complaint_response');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.reopen_request');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.reopen_approval');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.post_audit');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.anomaly_alert');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.alert_action');

-- Bản ghi trả lời khiếu nại và phê duyệt mở lại chỉ thêm (BR-12-10, bốn mắt).
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.complaint_response FROM svc_${flyway:defaultSchema};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.reopen_approval FROM svc_${flyway:defaultSchema};
-- Khiếu nại, yêu cầu mở lại, hậu kiểm và cảnh báo không xoá (truy vết).
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.complaint FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.reopen_request FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.post_audit FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.anomaly_alert FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.alert_action FROM svc_${flyway:defaultSchema};
