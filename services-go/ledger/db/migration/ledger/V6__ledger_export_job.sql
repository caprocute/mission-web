-- DV-09-05: tác vụ xuất gói bằng chứng sổ cái (oversight.yaml createLedgerExport, 202 + /api/v1/jobs/{id}).
-- TL-03 không có bảng cho tác vụ xuất; bảng này là suy luận từ hợp đồng và quy ước Idempotency-Key, Job của
-- baseline mục 13. Gói nằm ở kho đối tượng, bảng chỉ giữ khoá đối tượng và băm của gói.
CREATE TABLE ledger.export_job (
  id                    uuid         NOT NULL,
  tenant_id             uuid         NOT NULL,
  requested_by          uuid         NOT NULL,
  idempotency_key       varchar(128) NOT NULL,
  request_hash          bytea        NOT NULL,
  filter_json           jsonb        NOT NULL,
  include_verify_run_id uuid,
  status                varchar(16)  NOT NULL DEFAULT 'CHO',
  status_detail         text,
  object_key            varchar(512),
  bundle_sha256         bytea,
  size_bytes            bigint,
  entry_count           bigint       NOT NULL DEFAULT 0,
  created_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  finished_at           timestamptz,
  CONSTRAINT pk_export_job PRIMARY KEY (id),
  CONSTRAINT uq_export_job__idempotency UNIQUE (tenant_id, requested_by, idempotency_key),
  CONSTRAINT ck_export_job__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI')),
  CONSTRAINT ck_export_job__hash CHECK (bundle_sha256 IS NULL OR length(bundle_sha256) = 32),
  CONSTRAINT ck_export_job__counts CHECK (entry_count >= 0 AND (size_bytes IS NULL OR size_bytes >= 0))
);

ALTER TABLE ledger.export_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.export_job FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_export_job__tenant ON ledger.export_job
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ledger.export_job OWNER TO mig_ledger;
GRANT SELECT, INSERT, UPDATE ON TABLE ledger.export_job TO svc_ledger;
GRANT SELECT ON TABLE ledger.export_job TO ro_ledger;

COMMENT ON TABLE ledger.export_job IS 'Tác vụ xuất gói bằng chứng sổ cái (DV-09-05, suy luận từ hợp đồng)';
COMMENT ON COLUMN ledger.export_job.id IS '{"cls":"C1","desc":"Mã tác vụ xuất, cũng là mã Job"}';
COMMENT ON COLUMN ledger.export_job.tenant_id IS '{"cls":"C1","desc":"Mã tenant"}';
COMMENT ON COLUMN ledger.export_job.requested_by IS '{"cls":"C1","desc":"Người yêu cầu xuất"}';
COMMENT ON COLUMN ledger.export_job.idempotency_key IS '{"cls":"C1","desc":"Idempotency-Key của yêu cầu tạo"}';
COMMENT ON COLUMN ledger.export_job.request_hash IS '{"cls":"C1","desc":"SHA-256 thân yêu cầu để phát hiện dùng lại khoá với thân khác"}';
COMMENT ON COLUMN ledger.export_job.filter_json IS '{"cls":"C1","desc":"Phạm vi lọc của gói: đối tượng, loại sự kiện, khoảng thời gian"}';
COMMENT ON COLUMN ledger.export_job.include_verify_run_id IS '{"cls":"C1","desc":"Lần kiểm tra toàn vẹn đính kèm; NULL là lần gần nhất"}';
COMMENT ON COLUMN ledger.export_job.status IS '{"cls":"C1","desc":"Trạng thái tác vụ: CHO, DANG_CHAY, HOAN_TAT, THAT_BAI"}';
COMMENT ON COLUMN ledger.export_job.status_detail IS '{"cls":"C1","desc":"Thông điệp kết quả hoặc lỗi"}';
COMMENT ON COLUMN ledger.export_job.object_key IS '{"cls":"C1","desc":"Khoá đối tượng của gói trong kho đối tượng"}';
COMMENT ON COLUMN ledger.export_job.bundle_sha256 IS '{"cls":"C1","desc":"SHA-256 của tệp gói"}';
COMMENT ON COLUMN ledger.export_job.size_bytes IS '{"cls":"C1","desc":"Kích thước gói (byte)"}';
COMMENT ON COLUMN ledger.export_job.entry_count IS '{"cls":"C1","desc":"Số bản ghi trong phạm vi lọc đã xuất"}';
COMMENT ON COLUMN ledger.export_job.created_at IS '{"cls":"C1","desc":"Thời điểm tạo tác vụ"}';
COMMENT ON COLUMN ledger.export_job.finished_at IS '{"cls":"C1","desc":"Thời điểm kết thúc tác vụ"}';
