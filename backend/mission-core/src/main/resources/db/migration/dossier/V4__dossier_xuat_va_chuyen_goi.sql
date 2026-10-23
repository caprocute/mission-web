-- CORE-06-08 · Xuất gói ZIP và gửi gói con diện Trung ương quản lý (QT-11 bước 14, BR-11-11, SCR-1102).
--
-- TL-03 không có bảng cho tác vụ xuất và lần gửi gói con; hai bảng bổ sung dưới đây là suy luận (wiki trang 14):
--   * package_export: tác vụ exportDossierPackage (202), trạng thái theo JobStatus chung, khoá ZIP trên kho đối
--     tượng; trạng thái đọc từ CSDL nên đúng trên mọi pod, pod dừng giữa chừng thì tác vụ định kỳ chạy lại.
--   * package_transfer: mỗi dòng là một gói con (một hồ sơ diện TW, một nơi nhận) gửi integration-gateway (SVC-13);
--     id là transferId gửi SVC-13 (khoá chống trùng phía SVC-13), batch_id là mã tác vụ của một lần gọi
--     createDossierPackageTransfer. Trạng thái CHO, DA_GUI, DA_XAC_NHAN, LOI chép từ SVC-13; CHUA_GUI (SVC-13 chưa
--     nhận) và HUY (nơi nhận chưa cấu hình) là của MOD-06. notified_attempts chống gửi trùng MSG-11-06.
-- Hai bảng không phân vùng theo kỳ (số dòng nhỏ: vài lần xuất, vài gói con cho mỗi gói).
--
-- fn_freeze_submitted_package: lần chuyển DA_NOP -> DA_LUU_TRU được ghi thêm transferred_to khi cột đang rỗng (gói con
-- diện TW đã có biên nhận); mọi cột khác vẫn khoá như V1.

-- ---------------------------------------------------------------- package_export
CREATE TABLE ${flyway:defaultSchema}.package_export (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_bucket int NOT NULL,
    dossier_package_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'CHO',
    object_key varchar(512),
    size_bytes bigint,
    file_count int,
    error_code varchar(64),
    completed_at timestamptz,
    CONSTRAINT pk_package_export PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_package_export__dossier_package FOREIGN KEY (period_bucket, tenant_id, dossier_package_id)
        REFERENCES ${flyway:defaultSchema}.dossier_package (period_bucket, tenant_id, id),
    CONSTRAINT ck_package_export__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI')),
    CONSTRAINT ck_package_export__done CHECK (status <> 'HOAN_TAT' OR (object_key IS NOT NULL
        AND completed_at IS NOT NULL))
);
CREATE INDEX ix_package_export__status ON ${flyway:defaultSchema}.package_export (tenant_id, status, updated_at);

COMMENT ON TABLE ${flyway:defaultSchema}.package_export IS 'Tác vụ xuất gói hồ sơ thành tệp ZIP kèm manifest (bổ sung, CORE-06-08)';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.id IS '{"cls":"C1","desc":"Mã tác vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.created_by IS '{"cls":"C1","desc":"Người yêu cầu xuất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng của gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.dossier_package_id IS '{"cls":"C1","desc":"Gói được xuất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.status IS '{"cls":"C1","desc":"Trạng thái tác vụ (JobStatus)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.object_key IS '{"cls":"C1","desc":"Tệp ZIP trên kho đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.size_bytes IS '{"cls":"C1","desc":"Kích thước tệp ZIP"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.file_count IS '{"cls":"C1","desc":"Số tệp biểu mẫu trong ZIP"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.error_code IS '{"cls":"C1","desc":"Mã lỗi khi thất bại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_export.completed_at IS '{"cls":"C1","desc":"Thời điểm kết thúc"}';

-- ---------------------------------------------------------------- package_transfer
CREATE TABLE ${flyway:defaultSchema}.package_transfer (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_bucket int NOT NULL,
    dossier_package_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    person_id uuid NOT NULL,
    destination varchar(8) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'CHUA_GUI',
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    ack_ref varchar(128),
    acknowledged_at timestamptz,
    last_error varchar(500),
    notified_attempts int NOT NULL DEFAULT 0,
    CONSTRAINT pk_package_transfer PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_package_transfer__dossier_package FOREIGN KEY (period_bucket, tenant_id, dossier_package_id)
        REFERENCES ${flyway:defaultSchema}.dossier_package (period_bucket, tenant_id, id),
    CONSTRAINT ck_package_transfer__destination CHECK (destination IN ('BTC_TW', 'VPTW')),
    CONSTRAINT ck_package_transfer__status CHECK (status IN ('CHUA_GUI', 'CHO', 'DA_GUI', 'DA_XAC_NHAN', 'LOI',
        'HUY')),
    CONSTRAINT ck_package_transfer__attempts CHECK (attempts >= 0 AND notified_attempts >= 0),
    CONSTRAINT ck_package_transfer__acknowledged CHECK (status <> 'DA_XAC_NHAN' OR acknowledged_at IS NOT NULL)
);
CREATE INDEX ix_package_transfer__package ON ${flyway:defaultSchema}.package_transfer (tenant_id, dossier_package_id,
    destination);
CREATE INDEX ix_package_transfer__batch ON ${flyway:defaultSchema}.package_transfer (tenant_id, batch_id);
CREATE INDEX ix_package_transfer__open ON ${flyway:defaultSchema}.package_transfer (tenant_id, updated_at)
    WHERE status IN ('CHUA_GUI', 'CHO', 'DA_GUI', 'LOI');

COMMENT ON TABLE ${flyway:defaultSchema}.package_transfer IS 'Gói con hồ sơ diện TW gửi qua EdXML (bổ sung, CORE-06-08, QĐ Điều 17)';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.id IS '{"cls":"C1","desc":"Mã lần gửi (transferId của SVC-13)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.created_by IS '{"cls":"C1","desc":"Người yêu cầu gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng của gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.dossier_package_id IS '{"cls":"C1","desc":"Gói chính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.batch_id IS '{"cls":"C1","desc":"Mã tác vụ của lần yêu cầu gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.profile_id IS '{"cls":"C1","desc":"Hồ sơ diện TW trong gói con"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.person_id IS '{"cls":"C1","desc":"Chủ hồ sơ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.destination IS '{"cls":"C1","desc":"Nơi nhận (BTC_TW, VPTW)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.status IS '{"cls":"C1","desc":"Trạng thái gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.attempts IS '{"cls":"C1","desc":"Số lần SVC-13 đã gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.next_attempt_at IS '{"cls":"C1","desc":"Lần thử lại kế tiếp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.ack_ref IS '{"cls":"C1","desc":"Mã biên nhận của bên nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.acknowledged_at IS '{"cls":"C1","desc":"Thời điểm có biên nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.last_error IS '{"cls":"C1","desc":"Lý do lỗi gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.package_transfer.notified_attempts IS '{"cls":"C1","desc":"Lần lỗi đã thông báo MSG-11-06"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.package_export');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.package_transfer');
-- Tác vụ và lần gửi là vết kiểm toán: không xoá.
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.package_export FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.package_transfer FROM svc_${flyway:defaultSchema};

-- ---------------------------------------------------------------- khoá gói đã nộp
CREATE OR REPLACE FUNCTION ${flyway:defaultSchema}.fn_freeze_submitted_package() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF OLD.status NOT IN ('DA_NOP', 'DA_LUU_TRU') THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'DA_NOP' AND NEW.status = 'DA_LUU_TRU'
       AND (OLD.transferred_to IS NULL OR NEW.transferred_to IS NOT DISTINCT FROM OLD.transferred_to)
       AND (to_jsonb(NEW) - ARRAY['status', 'updated_at', 'updated_by', 'row_version', 'transferred_to'])
           = (to_jsonb(OLD) - ARRAY['status', 'updated_at', 'updated_by', 'row_version', 'transferred_to']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Gói hồ sơ đã nộp, không được sửa'
        USING ERRCODE = 'object_not_in_prerequisite_state';
END
$fn$;
