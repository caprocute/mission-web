-- DV-09-04: cột bổ sung cho ledger.verify_run để phục vụ API kiểm tra toàn vẹn (oversight.yaml
-- LedgerVerifyRun) và tác vụ dài 202 (/api/v1/jobs/{id}). TL-03 chỉ có các cột lõi; các cột dưới đây là
-- suy luận từ hợp đồng (chainPartition, sealsChecked, partitionsTotal, partitionsMatched, chain, alertId)
-- và từ quy ước Idempotency-Key, Job của baseline mục 13.
ALTER TABLE ledger.verify_run
  ADD COLUMN chain_partition    smallint,
  ADD COLUMN seals_checked      bigint      NOT NULL DEFAULT 0,
  ADD COLUMN partitions_total   int         NOT NULL DEFAULT 0,
  ADD COLUMN partitions_matched int         NOT NULL DEFAULT 0,
  ADD COLUMN result_json        jsonb,
  ADD COLUMN alert_id           uuid,
  ADD COLUMN status             varchar(16) NOT NULL DEFAULT 'CHO',
  ADD COLUMN status_detail      text,
  ADD COLUMN requested_by       uuid,
  ADD COLUMN idempotency_key    varchar(128),
  ADD COLUMN request_hash       bytea,
  ADD CONSTRAINT ck_verify_run__chain CHECK (chain_partition IS NULL OR chain_partition BETWEEN 0 AND 63),
  ADD CONSTRAINT ck_verify_run__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI', 'DA_HUY')),
  ADD CONSTRAINT ck_verify_run__parts CHECK (
    seals_checked >= 0 AND partitions_total >= 0 AND partitions_matched BETWEEN 0 AND partitions_total
  );

CREATE UNIQUE INDEX uq_verify_run__idempotency ON ledger.verify_run (tenant_id, requested_by, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_verify_run__kind_started ON ledger.verify_run (tenant_id, verify_kind, started_at DESC, id DESC);

COMMENT ON COLUMN ledger.verify_run.chain_partition IS '{"cls":"C1","type_exception":"Theo TL-03 dùng smallint cho chỉ số phân vùng chuỗi 0..63","desc":"Phân vùng chuỗi được kiểm; NULL là cả 64 phân vùng"}';
COMMENT ON COLUMN ledger.verify_run.seals_checked IS '{"cls":"C1","desc":"Số niêm phong đã đối chiếu"}';
COMMENT ON COLUMN ledger.verify_run.partitions_total IS '{"cls":"C1","desc":"Số phân vùng chuỗi đã kiểm"}';
COMMENT ON COLUMN ledger.verify_run.partitions_matched IS '{"cls":"C1","desc":"Số phân vùng chuỗi khớp hoàn toàn"}';
COMMENT ON COLUMN ledger.verify_run.result_json IS '{"cls":"C1","desc":"Các mắt chuỗi quanh vị trí sai (prev, hash, khớp) cho sơ đồ khối"}';
COMMENT ON COLUMN ledger.verify_run.alert_id IS '{"cls":"C1","desc":"Mã sự kiện EVT-20 mức Cao đã phát khi phá vỡ"}';
COMMENT ON COLUMN ledger.verify_run.status IS '{"cls":"C1","desc":"Trạng thái tác vụ: CHO, DANG_CHAY, HOAN_TAT, THAT_BAI, DA_HUY"}';
COMMENT ON COLUMN ledger.verify_run.status_detail IS '{"cls":"C1","desc":"Thông điệp kết quả hoặc lỗi của tác vụ"}';
COMMENT ON COLUMN ledger.verify_run.requested_by IS '{"cls":"C1","desc":"Người yêu cầu; NULL là lần chạy theo lịch"}';
COMMENT ON COLUMN ledger.verify_run.idempotency_key IS '{"cls":"C1","desc":"Idempotency-Key của yêu cầu tạo"}';
COMMENT ON COLUMN ledger.verify_run.request_hash IS '{"cls":"C1","desc":"SHA-256 thân yêu cầu chuẩn hoá để phát hiện dùng lại khoá với thân khác"}';
