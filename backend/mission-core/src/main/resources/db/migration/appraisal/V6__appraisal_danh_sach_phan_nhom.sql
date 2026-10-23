-- CORE-04-12 · Danh sách phân nhóm triển vọng của một đơn vị trong kỳ năm (QT-10 bước 8, 9; SCR-1002).
--
-- Bảng bổ sung (TL-03 chưa có, wiki trang 14 mục CORE-04-12): hợp đồng saveProspectGroupBoard,
-- submitProspectGroupBoard có trạng thái của cả danh sách (ProspectBoardStatus NHAP, DA_TRINH, DA_KY),
-- phiên bản của cả danh sách (rowVersion trong If-Match) và lý do tập thể không thông qua, nhưng
-- appraisal.prospect_group chỉ có dòng theo người. Giá trị trạng thái lấy đúng enum của hợp đồng, không đặt
-- mã mới. board_version tăng mỗi lần tập thể không thông qua (TL-02b: "trình lại tạo một phiên bản mới").
CREATE TABLE ${flyway:defaultSchema}.prospect_board (
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
    status varchar(12) NOT NULL DEFAULT 'NHAP',
    board_version int NOT NULL DEFAULT 1,
    submitted_by uuid,
    submitted_at timestamptz,
    process_instance_id varchar(64),
    rejection_reason_enc bytea,
    dek_id uuid,
    sign_request_id uuid,
    signed_at timestamptz,
    CONSTRAINT pk_prospect_board PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_prospect_board__unit UNIQUE (period_bucket, tenant_id, org_unit_id),
    CONSTRAINT ck_prospect_board__bucket CHECK (period_bucket % 10 = 0),
    CONSTRAINT ck_prospect_board__status CHECK (status IN ('NHAP', 'DA_TRINH', 'DA_KY')),
    CONSTRAINT ck_prospect_board__version CHECK (board_version > 0),
    CONSTRAINT ck_prospect_board__submitted CHECK (status = 'NHAP' OR submitted_at IS NOT NULL),
    CONSTRAINT ck_prospect_board__signed CHECK (status <> 'DA_KY' OR (sign_request_id IS NOT NULL AND signed_at IS NOT NULL)),
    CONSTRAINT ck_prospect_board__dek CHECK (dek_id IS NOT NULL OR rejection_reason_enc IS NULL)
) PARTITION BY LIST (period_bucket);

CREATE INDEX ix_prospect_board__sign_request ON ${flyway:defaultSchema}.prospect_board (tenant_id, sign_request_id);

COMMENT ON TABLE ${flyway:defaultSchema}.prospect_board IS 'Danh sách phân nhóm triển vọng của đơn vị trong kỳ năm: trạng thái, phiên bản, trình và ký (SCR-1002)';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.period_id IS '{"cls":"C1","desc":"Kỳ năm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng theo kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.org_unit_id IS '{"cls":"C1","desc":"Đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.status IS '{"cls":"C1","desc":"Trạng thái danh sách (ProspectBoardStatus của hợp đồng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.board_version IS '{"cls":"C1","desc":"Phiên bản danh sách, tăng khi tập thể không thông qua"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.submitted_by IS '{"cls":"C1","desc":"Người trình tập thể"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.submitted_at IS '{"cls":"C1","desc":"Thời điểm trình"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.process_instance_id IS '{"cls":"C1","desc":"Tiến trình thông qua tập thể ở workflow-service"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.rejection_reason_enc IS '{"cls":"C2","desc":"Lý do tập thể không thông qua lần trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.sign_request_id IS '{"cls":"C1","desc":"Yêu cầu ký danh sách ở esign-service"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.prospect_board.signed_at IS '{"cls":"C1","desc":"Thời điểm ký xong"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.prospect_board');
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.prospect_board FROM svc_${flyway:defaultSchema};
