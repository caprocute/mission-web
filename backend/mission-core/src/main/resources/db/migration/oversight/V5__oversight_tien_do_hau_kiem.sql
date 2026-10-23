-- CORE-07-09 · Điều phối tính lại, sinh lại, ký lại sau EVT-22 (QT-13 bước 10 đến 12).
-- Bảng bổ sung (suy luận, wiki trang 14): TL-03 không có nơi lưu tiến độ từng hồ sơ trong phạm vi yêu cầu
-- mở lại. reopen_progress giữ một dòng cho mỗi (yêu cầu, hồ sơ): lần tính lại TINH_LAI_HAU_KIEM mới nhất
-- (EVT-10) và các biểu mẫu sinh lại, ký lại (EVT-17, EVT-18) trong forms_json
-- {"<form_instance_id>":{"code":"MAU_2","generatedAt":"…","signedAt":"…"}}. Trạng thái điều phối của cả
-- yêu cầu (hoàn tất, đã đề nghị khoá lại sớm, chưa hoàn tất hậu kiểm) nằm ở khoá followUp của
-- reopen_request.scope_json, theo TL-02b "đánh dấu trong dữ liệu mô tả".

CREATE TABLE ${flyway:defaultSchema}.reopen_progress (
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
    profile_id uuid NOT NULL,
    recomputed_at timestamptz,
    score_result_id uuid,
    forms_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    completed_at timestamptz,
    CONSTRAINT pk_reopen_progress PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_reopen_progress__profile UNIQUE (period_bucket, tenant_id, reopen_request_id, profile_id),
    CONSTRAINT fk_reopen_progress__reopen_request FOREIGN KEY (period_bucket, tenant_id, reopen_request_id)
        REFERENCES ${flyway:defaultSchema}.reopen_request (period_bucket, tenant_id, id),
    CONSTRAINT ck_reopen_progress__completed CHECK (completed_at IS NULL OR recomputed_at IS NOT NULL)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_reopen_progress__profile ON ${flyway:defaultSchema}.reopen_progress (tenant_id, profile_id);

COMMENT ON TABLE ${flyway:defaultSchema}.reopen_progress IS 'Tiến độ tính lại, sinh lại, ký lại từng hồ sơ trong phạm vi yêu cầu mở lại kỳ (bảng bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.reopen_request_id IS '{"cls":"C1","desc":"Yêu cầu mở lại kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.profile_id IS '{"cls":"C1","desc":"Hồ sơ đánh giá trong phạm vi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.recomputed_at IS '{"cls":"C1","desc":"Thời điểm tính lại hậu kiểm mới nhất (EVT-10)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.score_result_id IS '{"cls":"C1","desc":"Kết quả tính lại mới nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.forms_json IS '{"cls":"C1","desc":"Biểu mẫu sinh lại, ký lại (EVT-17, EVT-18)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reopen_progress.completed_at IS '{"cls":"C1","desc":"Thời điểm hồ sơ hoàn tất hậu kiểm"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.reopen_progress');
-- Tiến độ chỉ cập nhật, không xoá (truy vết).
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.reopen_progress FROM svc_${flyway:defaultSchema};
