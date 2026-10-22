-- CORE-02-04 · Lần nhập danh mục công việc từ Excel (tasks.yaml CatalogImportJob; QT-04 "Ngoại lệ và xử lý",
-- SCR-0401 "Nhập từ Excel", MSG-04-02) và chỉ mục đếm mức sử dụng mục danh mục (CORE-02-03, wiki 14 mục 43 h).
--
-- Điểm lệch và lựa chọn (wiki trang 14):
--   * TL-03 không có bảng cho lần nhập; bảng bổ sung giữ trạng thái tác vụ 202 để mọi pod đọc được và để
--     getLatestCatalogImport trả lần nhập gần nhất. Dòng lỗi lưu jsonb để dựng lại tệp lỗi khi tải (không lưu tệp).
--   * error_rows chỉ chứa nội dung danh mục người dùng nhập (C0), không chứa dữ liệu cá nhân.

-- ---------------------------------------------------------------- catalog_import_job
CREATE TABLE ${flyway:defaultSchema}.catalog_import_job (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    work_catalog_id uuid NOT NULL,
    target_catalog_id uuid,
    file_name varchar(255) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'DANG_XU_LY',
    total_rows int NOT NULL DEFAULT 0,
    valid_rows int NOT NULL DEFAULT 0,
    error_groups jsonb NOT NULL DEFAULT '[]'::jsonb,
    error_rows jsonb NOT NULL DEFAULT '[]'::jsonb,
    failure_detail varchar(500),
    finished_at timestamptz,
    CONSTRAINT pk_catalog_import_job PRIMARY KEY (id),
    CONSTRAINT fk_catalog_import_job__work_catalog FOREIGN KEY (tenant_id, work_catalog_id)
        REFERENCES ${flyway:defaultSchema}.work_catalog (tenant_id, id),
    CONSTRAINT ck_catalog_import_job__status CHECK (status IN ('DANG_XU_LY', 'XONG', 'LOI')),
    CONSTRAINT ck_catalog_import_job__rows CHECK (total_rows >= 0 AND valid_rows BETWEEN 0 AND total_rows),
    CONSTRAINT ck_catalog_import_job__finished CHECK (status = 'DANG_XU_LY' OR finished_at IS NOT NULL),
    CONSTRAINT ck_catalog_import_job__groups CHECK (jsonb_typeof(error_groups) = 'array'),
    CONSTRAINT ck_catalog_import_job__error_rows CHECK (jsonb_typeof(error_rows) = 'array')
);
CREATE INDEX ix_catalog_import_job__catalog ON ${flyway:defaultSchema}.catalog_import_job (tenant_id, work_catalog_id, created_at);

COMMENT ON TABLE ${flyway:defaultSchema}.catalog_import_job IS 'Lần nhập danh mục công việc từ Excel, tác vụ 202 (bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.work_catalog_id IS '{"cls":"C1","desc":"Phiên bản danh mục người dùng nhập vào"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.target_catalog_id IS '{"cls":"C1","desc":"Phiên bản nhận mục (nháp khi nhập vào phiên bản đang áp dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.file_name IS '{"cls":"C0","desc":"Tên tệp Excel"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.status IS '{"cls":"C1","desc":"DANG_XU_LY, XONG, LOI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.total_rows IS '{"cls":"C1","desc":"Số dòng dữ liệu đã đọc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.valid_rows IS '{"cls":"C1","desc":"Số dòng đã nhập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.error_groups IS '{"cls":"C1","desc":"Nhóm lỗi theo lý do và loại THIEU, MO_HO, KHAC"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.error_rows IS '{"cls":"C0","desc":"Dòng lỗi kèm lý do để dựng tệp lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.failure_detail IS '{"cls":"C1","desc":"Lý do cả tệp không đọc được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_import_job.finished_at IS '{"cls":"C1","desc":"Thời điểm xong"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.catalog_import_job');

-- ---------------------------------------------------------------- chỉ mục đếm mức sử dụng mục danh mục
CREATE INDEX ix_task__catalog_item ON ${flyway:defaultSchema}.task (tenant_id, catalog_item_id)
    WHERE catalog_item_id IS NOT NULL;
