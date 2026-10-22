-- CORE-06-01 · Schema dossier (MOD-06): hội nghị kiểm điểm, góp ý, gói hồ sơ số, biểu mẫu đã sinh,
-- trường hợp đặc thù PL 6 và theo dõi khắc phục hạn chế (TL-03 "Schema dossier (MOD-06)", TL-02b QT-10, QT-11).
-- outbox_event, inbox_processed, inbox_aggregate_version, idempotency_key đến từ db/common-business (NEN-032);
-- hàm RLS và phân vùng từ db/common (NEN-033).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 52):
--   * Khoá chính, khoá duy nhất, khoá ngoại chứa period_bucket và tenant_id như schema task, product, appraisal
--     (TL-03 ghi PK (period_bucket, id)); UQ mã gói là (period_bucket, tenant_id, code) vì bảng phân vùng con
--     băm theo tenant_id ở mô hình pool, mã chứa năm nên vẫn duy nhất trong tenant.
--   * dossier_package lập theo đơn vị như hợp đồng dossiers.yaml (DossierPackageCreate có orgUnitId) và TL-02b:
--     cột bổ sung org_unit_id, UQ (period_bucket, tenant_id, org_unit_id, package_kind, person_id) NULLS NOT
--     DISTINCT; person_id có thể rỗng, chỉ điền cho gói con diện TW theo người (suy luận). TL-03 đặt UQ theo
--     người (person_id, package_kind).
--   * Cột bổ sung theo hợp đồng: dossier_package.version_no (phiên bản gói), review_meeting.location.
--   * dek_id không khai khoá ngoại sang keyring.dek (lý do như product.acceptance); thay bằng CHECK "có cột
--     _enc thì có dek_id".
--   * review_comment chỉ thêm (REVOKE UPDATE, DELETE), suy luận từ yêu cầu lưu vết biên bản.
--   * Gói DA_NOP, DA_LUU_TRU không sửa nội dung, chỉ được chuyển DA_NOP -> DA_LUU_TRU (BR-11-10).

-- ---------------------------------------------------------------- review_meeting
CREATE TABLE ${flyway:defaultSchema}.review_meeting (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    org_unit_id uuid NOT NULL,
    meeting_type varchar(16) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    location varchar(500),
    draft_sent_at timestamptz,
    chair_person_id uuid NOT NULL,
    secretary_person_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DU_KIEN',
    minutes_object_key varchar(512),
    minutes_sha256 bytea,
    sign_request_id uuid,
    meeting_at timestamptz,
    subject_person_id uuid,
    participants_json jsonb,
    vote_json jsonb,
    CONSTRAINT pk_review_meeting PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT ck_review_meeting__type CHECK (meeting_type IN ('KIEM_DIEM_QUY', 'KIEM_DIEM_NAM', 'CHUYEN_DE')),
    CONSTRAINT ck_review_meeting__status CHECK (status IN ('DU_KIEN', 'DA_GUI_DU_THAO', 'DA_HOP', 'DA_LAP_BIEN_BAN',
        'DA_KY')),
    CONSTRAINT ck_review_meeting__minutes_sha256 CHECK (minutes_sha256 IS NULL OR octet_length(minutes_sha256) = 32),
    CONSTRAINT ck_review_meeting__draft CHECK (status = 'DU_KIEN' OR draft_sent_at IS NOT NULL),
    CONSTRAINT ck_review_meeting__signed CHECK (status <> 'DA_KY' OR (sign_request_id IS NOT NULL
        AND minutes_sha256 IS NOT NULL))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_review_meeting__org_unit ON ${flyway:defaultSchema}.review_meeting (tenant_id, org_unit_id, period_id);

COMMENT ON TABLE ${flyway:defaultSchema}.review_meeting IS 'Hội nghị kiểm điểm của tập thể (QĐ Điều 6 đến 7)';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.org_unit_id IS '{"cls":"C1","desc":"Tập thể tổ chức hội nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.meeting_type IS '{"cls":"C1","desc":"Loại hội nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.scheduled_at IS '{"cls":"C1","desc":"Thời điểm họp dự kiến"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.location IS '{"cls":"C1","desc":"Địa điểm (bổ sung theo hợp đồng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.draft_sent_at IS '{"cls":"C1","desc":"Thời điểm gửi dự thảo (kiểm tra đủ 3 ngày làm việc)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.chair_person_id IS '{"cls":"C1","desc":"Chủ trì"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.secretary_person_id IS '{"cls":"C1","desc":"Thư ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.status IS '{"cls":"C1","desc":"Trạng thái hội nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.minutes_object_key IS '{"cls":"C1","desc":"Biên bản trên kho đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.minutes_sha256 IS '{"cls":"C1","desc":"Băm SHA-256 của biên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký biên bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.meeting_at IS '{"cls":"C1","desc":"Thời điểm họp thực tế"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.subject_person_id IS '{"cls":"C1","desc":"Người được kiểm điểm khi hội nghị dành cho một người"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.participants_json IS '{"cls":"C1","desc":"Thành phần dự họp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_meeting.vote_json IS '{"cls":"C1","desc":"Kết quả biểu quyết"}';

-- ---------------------------------------------------------------- review_comment
CREATE TABLE ${flyway:defaultSchema}.review_comment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    meeting_id uuid NOT NULL,
    subject_person_id uuid NOT NULL,
    commenter_person_id uuid NOT NULL,
    comment_kind varchar(12) NOT NULL,
    kind varchar(12) NOT NULL,
    phase varchar(16) NOT NULL,
    comment_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    remediation_due date,
    CONSTRAINT pk_review_comment PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_review_comment__review_meeting FOREIGN KEY (period_bucket, tenant_id, meeting_id)
        REFERENCES ${flyway:defaultSchema}.review_meeting (period_bucket, tenant_id, id),
    CONSTRAINT ck_review_comment__comment_kind CHECK (comment_kind IN ('GOP_Y', 'TU_KIEM_DIEM', 'KET_LUAN')),
    CONSTRAINT ck_review_comment__kind CHECK (kind IN ('UU_DIEM', 'HAN_CHE', 'KIEN_NGHI')),
    CONSTRAINT ck_review_comment__phase CHECK (phase IN ('TRUOC_HOI_NGHI', 'HOI_NGHI'))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_review_comment__meeting ON ${flyway:defaultSchema}.review_comment (period_bucket, tenant_id, meeting_id);
CREATE INDEX ix_review_comment__subject ON ${flyway:defaultSchema}.review_comment (tenant_id, subject_person_id);

COMMENT ON TABLE ${flyway:defaultSchema}.review_comment IS 'Góp ý, tự kiểm điểm và kết luận tại hội nghị; chỉ thêm, không sửa';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.meeting_id IS '{"cls":"C1","desc":"Hội nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.subject_person_id IS '{"cls":"C1","desc":"Người được góp ý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.commenter_person_id IS '{"cls":"C1","desc":"Người góp ý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.comment_kind IS '{"cls":"C1","desc":"Loại: góp ý, tự kiểm điểm, kết luận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.kind IS '{"cls":"C1","desc":"Loại nội dung: ưu điểm, hạn chế, kiến nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.phase IS '{"cls":"C1","desc":"Giai đoạn: trước hội nghị hoặc tại hội nghị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.comment_enc IS '{"cls":"C2","desc":"Nội dung góp ý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.review_comment.remediation_due IS '{"cls":"C1","desc":"Hạn khắc phục hạn chế"}';

-- ---------------------------------------------------------------- dossier_package
CREATE TABLE ${flyway:defaultSchema}.dossier_package (
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
    org_unit_id uuid NOT NULL,
    person_id uuid,
    package_kind varchar(8) NOT NULL,
    version_no int NOT NULL DEFAULT 1,
    manifest jsonb NOT NULL DEFAULT '[]'::jsonb,
    manifest_hash bytea,
    package_sha256 bytea,
    object_key varchar(512),
    status varchar(16) NOT NULL DEFAULT 'DANG_LAP',
    deadline date,
    submitted_at timestamptz,
    transferred_to varchar(8),
    sign_request_id uuid,
    CONSTRAINT pk_dossier_package PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_dossier_package__code UNIQUE (period_bucket, tenant_id, code),
    CONSTRAINT uq_dossier_package__unit UNIQUE NULLS NOT DISTINCT (period_bucket, tenant_id, org_unit_id,
        package_kind, person_id),
    CONSTRAINT ck_dossier_package__kind CHECK (package_kind IN ('QUY', 'NAM', 'DIEN_TW')),
    CONSTRAINT ck_dossier_package__status CHECK (status IN ('DANG_LAP', 'DANG_SINH', 'CHO_KY', 'DA_KY', 'DA_NOP',
        'DA_LUU_TRU')),
    CONSTRAINT ck_dossier_package__transferred_to CHECK (transferred_to IN ('BTC_TW', 'VPTW')),
    CONSTRAINT ck_dossier_package__version_no CHECK (version_no >= 1),
    CONSTRAINT ck_dossier_package__manifest_hash CHECK (manifest_hash IS NULL OR octet_length(manifest_hash) = 32),
    CONSTRAINT ck_dossier_package__package_sha256 CHECK (package_sha256 IS NULL OR octet_length(package_sha256) = 32),
    CONSTRAINT ck_dossier_package__submitted CHECK (status NOT IN ('DA_NOP', 'DA_LUU_TRU') OR (submitted_at IS NOT NULL
        AND package_sha256 IS NOT NULL))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_dossier_package__status ON ${flyway:defaultSchema}.dossier_package (tenant_id, period_id, status);

COMMENT ON TABLE ${flyway:defaultSchema}.dossier_package IS 'Gói hồ sơ số của đơn vị trong kỳ, ký số và nộp cấp trên (QĐ Điều 17)';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.code IS '{"cls":"C1","desc":"Mã gói DP-yyyy-{Q/N}-nnnn (định dạng kiểm ở mã)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.org_unit_id IS '{"cls":"C1","desc":"Đơn vị lập gói (bổ sung theo hợp đồng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.person_id IS '{"cls":"C1","desc":"Chủ hồ sơ khi gói thuộc một cá nhân (gói con diện TW)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.package_kind IS '{"cls":"C1","desc":"Loại gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.version_no IS '{"cls":"C1","desc":"Phiên bản gói (bổ sung theo hợp đồng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.manifest IS '{"cls":"C1","desc":"Danh sách biểu mẫu, tệp kèm và băm từng tệp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.manifest_hash IS '{"cls":"C1","desc":"Băm danh mục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.package_sha256 IS '{"cls":"C1","desc":"Băm gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.object_key IS '{"cls":"C1","desc":"Gói trên kho đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.status IS '{"cls":"C1","desc":"Trạng thái gói (TL-02b QT-11)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.deadline IS '{"cls":"C1","desc":"Hạn nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.submitted_at IS '{"cls":"C1","desc":"Thời điểm nộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.transferred_to IS '{"cls":"C1","desc":"Nơi chuyển hồ sơ diện TW"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký gói"}';

-- ---------------------------------------------------------------- form_instance
CREATE TABLE ${flyway:defaultSchema}.form_instance (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    dossier_package_id uuid,
    profile_id uuid NOT NULL,
    form_code varchar(16) NOT NULL,
    template_version varchar(32) NOT NULL,
    data_snapshot_hash bytea NOT NULL,
    render_job_id uuid,
    object_key varchar(512),
    pdf_sha256 bytea,
    status varchar(12) NOT NULL DEFAULT 'CHO_SINH',
    signed_at timestamptz,
    CONSTRAINT pk_form_instance PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_form_instance__dossier_package FOREIGN KEY (period_bucket, tenant_id, dossier_package_id)
        REFERENCES ${flyway:defaultSchema}.dossier_package (period_bucket, tenant_id, id),
    CONSTRAINT ck_form_instance__form_code CHECK (form_code IN ('MAU_1A', 'MAU_1B', 'MAU_1C', 'MAU_1D', 'MAU_2', 'MAU_3',
        'BANG_CHAM_DIEM')),
    CONSTRAINT ck_form_instance__status CHECK (status IN ('CHO_SINH', 'DA_SINH', 'DA_KY', 'LOI')),
    CONSTRAINT ck_form_instance__data_snapshot_hash CHECK (octet_length(data_snapshot_hash) = 32),
    CONSTRAINT ck_form_instance__pdf_sha256 CHECK (pdf_sha256 IS NULL OR octet_length(pdf_sha256) = 32),
    CONSTRAINT ck_form_instance__rendered CHECK (status NOT IN ('DA_SINH', 'DA_KY') OR (object_key IS NOT NULL
        AND pdf_sha256 IS NOT NULL)),
    CONSTRAINT ck_form_instance__signed CHECK (status <> 'DA_KY' OR signed_at IS NOT NULL)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_form_instance__profile ON ${flyway:defaultSchema}.form_instance (period_bucket, tenant_id, profile_id);
CREATE INDEX ix_form_instance__package ON ${flyway:defaultSchema}.form_instance (period_bucket, tenant_id, dossier_package_id);

COMMENT ON TABLE ${flyway:defaultSchema}.form_instance IS 'Biểu mẫu đã sinh từ hồ sơ đánh giá, kèm băm dữ liệu đầu vào và băm tệp';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.dossier_package_id IS '{"cls":"C1","desc":"Gói hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.profile_id IS '{"cls":"C1","desc":"Hồ sơ đánh giá (appraisal.appraisal_profile)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.form_code IS '{"cls":"C0","desc":"Mẫu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.template_version IS '{"cls":"C1","desc":"Phiên bản mẫu ở docgen.template"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.data_snapshot_hash IS '{"cls":"C1","desc":"Băm dữ liệu đầu vào khi sinh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.render_job_id IS '{"cls":"C1","desc":"Tác vụ sinh ở SVC-11"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.object_key IS '{"cls":"C1","desc":"Tệp PDF/A"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.pdf_sha256 IS '{"cls":"C1","desc":"Băm tệp (EVT-17)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.status IS '{"cls":"C1","desc":"Trạng thái biểu mẫu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.signed_at IS '{"cls":"C1","desc":"Thời điểm ký (EVT-18)"}';

-- ---------------------------------------------------------------- special_case
CREATE TABLE ${flyway:defaultSchema}.special_case (
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
    case_code varchar(32) NOT NULL,
    detected_by varchar(8) NOT NULL,
    detail_enc bytea,
    dek_id uuid,
    effect varchar(24) NOT NULL,
    effective_from date,
    effective_to date,
    legal_ref varchar(100) NOT NULL,
    confirmed_by uuid,
    confirmed_at timestamptz,
    CONSTRAINT pk_special_case PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT ck_special_case__detected_by CHECK (detected_by IN ('TU_DONG', 'THU_CONG')),
    CONSTRAINT ck_special_case__effect CHECK (effect IN ('KHONG_XEP_LOAI', 'XEP_LOAI_TAI_DON_VI_CU', 'BAO_LUU',
        'XEP_LOAI_BINH_THUONG', 'CHO_KET_LUAN')),
    CONSTRAINT ck_special_case__dek CHECK (detail_enc IS NULL OR dek_id IS NOT NULL),
    CONSTRAINT ck_special_case__range CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_special_case__confirmed CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_special_case__person ON ${flyway:defaultSchema}.special_case (tenant_id, person_id, period_bucket);

COMMENT ON TABLE ${flyway:defaultSchema}.special_case IS 'Trường hợp đặc thù PL 6 ảnh hưởng tới việc xếp loại';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.person_id IS '{"cls":"C1","desc":"Người"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.case_code IS '{"cls":"C1","desc":"Mã trường hợp theo danh mục 13 trường hợp trong Policy Pack (kiểm ở mã)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.detected_by IS '{"cls":"C1","desc":"Hệ thống tự phát hiện hay người nhập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.detail_enc IS '{"cls":"C2","desc":"Chi tiết"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.effect IS '{"cls":"C1","desc":"Hệ quả với việc xếp loại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.effective_from IS '{"cls":"C1","desc":"Áp dụng từ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.effective_to IS '{"cls":"C1","desc":"Áp dụng đến"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.legal_ref IS '{"cls":"C0","desc":"Căn cứ, ví dụ PL 6 mục 3"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.confirmed_by IS '{"cls":"C1","desc":"Người xác nhận của cơ quan tổ chức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.special_case.confirmed_at IS '{"cls":"C1","desc":"Thời điểm xác nhận"}';

-- ---------------------------------------------------------------- remediation_item
CREATE TABLE ${flyway:defaultSchema}.remediation_item (
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
    meeting_id uuid NOT NULL,
    review_comment_id uuid,
    content_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    due_period_id uuid,
    status varchar(16) NOT NULL DEFAULT 'MO',
    verified_by uuid,
    verified_at timestamptz,
    CONSTRAINT pk_remediation_item PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT fk_remediation_item__review_meeting FOREIGN KEY (period_bucket, tenant_id, meeting_id)
        REFERENCES ${flyway:defaultSchema}.review_meeting (period_bucket, tenant_id, id),
    CONSTRAINT fk_remediation_item__review_comment FOREIGN KEY (period_bucket, tenant_id, review_comment_id)
        REFERENCES ${flyway:defaultSchema}.review_comment (period_bucket, tenant_id, id),
    CONSTRAINT ck_remediation_item__status CHECK (status IN ('MO', 'DANG_KHAC_PHUC', 'DA_KHAC_PHUC', 'CHUA_KHAC_PHUC')),
    CONSTRAINT ck_remediation_item__verified CHECK (status NOT IN ('DA_KHAC_PHUC', 'CHUA_KHAC_PHUC')
        OR (verified_by IS NOT NULL AND verified_at IS NOT NULL))
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_remediation_item__person ON ${flyway:defaultSchema}.remediation_item (tenant_id, person_id, status);

COMMENT ON TABLE ${flyway:defaultSchema}.remediation_item IS 'Hạn chế cần khắc phục rút ra từ biên bản kiểm điểm, theo dõi qua các kỳ (bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.person_id IS '{"cls":"C1","desc":"Người phải khắc phục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.meeting_id IS '{"cls":"C1","desc":"Hội nghị nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.review_comment_id IS '{"cls":"C1","desc":"Góp ý nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.content_enc IS '{"cls":"C2","desc":"Nội dung hạn chế và yêu cầu khắc phục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.due_period_id IS '{"cls":"C1","desc":"Kỳ phải hoàn thành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.status IS '{"cls":"C1","desc":"Trạng thái khắc phục"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.verified_by IS '{"cls":"C1","desc":"Người xác nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.remediation_item.verified_at IS '{"cls":"C1","desc":"Thời điểm xác nhận"}';

-- ---------------------------------------------------------------- gói đã nộp (BR-11-10)

-- Gói DA_NOP hoặc DA_LUU_TRU không sửa, không xoá; chỉ được chuyển DA_NOP -> DA_LUU_TRU (đổi trạng thái
-- và cột kỹ thuật). Trigger trên bảng cha áp cho mọi phân vùng con.
CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_submitted_package() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF OLD.status NOT IN ('DA_NOP', 'DA_LUU_TRU') THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'DA_NOP' AND NEW.status = 'DA_LUU_TRU'
       AND (to_jsonb(NEW) - ARRAY['status', 'updated_at', 'updated_by', 'row_version'])
           = (to_jsonb(OLD) - ARRAY['status', 'updated_at', 'updated_by', 'row_version']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Gói hồ sơ đã nộp, không được sửa'
        USING ERRCODE = 'object_not_in_prerequisite_state';
END
$fn$;
CREATE TRIGGER tg_dossier_package__freeze BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.dossier_package
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_submitted_package();

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.review_meeting');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.review_comment');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.dossier_package');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.form_instance');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.special_case');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.remediation_item');

-- Góp ý là vết của biên bản: chỉ thêm (suy luận từ yêu cầu lưu vết).
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.review_comment FROM svc_${flyway:defaultSchema};
-- Gói hồ sơ không xoá (truy vết nộp, lưu trữ).
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.dossier_package FROM svc_${flyway:defaultSchema};
