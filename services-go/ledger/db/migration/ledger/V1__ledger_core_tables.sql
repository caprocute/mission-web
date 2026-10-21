-- DV-09-01 · SVC-09 ledger-service · schema `ledger` (cụm pg-ledger độc lập).
-- Nguồn: TL-03 "Schema ledger (SVC-09 ledger-service, trên cụm pg-ledger độc lập)" (L3473-3715),
--        TL-01 "5.6. Kiến trúc sổ cái kiểm toán bất biến" (L2562-2630), ADR-09, ADR-31, SEC-09, NFR-09.
-- Lưu ý điểm lệch TL-01 vs TL-03 (đã ghi tại wiki/14-quyet-dinh-va-cau-hoi-mo.md):
--   Theo nguyên tắc TL-03 thắng về chi tiết CSDL, bảng `ledger.ledger_entry` giữ đầy đủ cột `metadata_hash`
--   và `entry_hash = SHA-256(prev_hash || payload_hash || metadata_hash)` theo TL-03 L3509-3511.

CREATE SCHEMA IF NOT EXISTS ledger;

-- ---------------------------------------------------------------- hàm chặn sửa, xoá, làm rỗng bảng bất biến
CREATE OR REPLACE FUNCTION ledger.fn_ledger_immutable()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  RAISE EXCEPTION 'ledger is append-only: % on %.% is prohibited',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END;
$fn$;

-- ---------------------------------------------------------------- ledger.ledger_entry
CREATE TABLE ledger.ledger_entry (
  tenant_id       uuid         NOT NULL,
  recorded_month  int          NOT NULL,
  chain_partition smallint     NOT NULL,
  seq_no          bigint       NOT NULL,
  id              uuid         NOT NULL,
  event_id        uuid         NOT NULL,
  event_type      varchar(64)  NOT NULL,
  aggregate_type  varchar(32)  NOT NULL,
  aggregate_id    uuid         NOT NULL,
  actor_id        uuid,
  actor_kind      varchar(8)   NOT NULL,
  occurred_at     timestamptz  NOT NULL,
  recorded_at     timestamptz  NOT NULL DEFAULT clock_timestamp(),
  payload_hash    bytea        NOT NULL,
  metadata        jsonb        NOT NULL,
  metadata_hash   bytea        NOT NULL,
  prev_hash       bytea        NOT NULL,
  entry_hash      bytea        NOT NULL,
  CONSTRAINT pk_ledger_entry PRIMARY KEY (tenant_id, recorded_month, chain_partition, seq_no),
  CONSTRAINT uq_ledger_entry__event UNIQUE (tenant_id, recorded_month, event_id),
  CONSTRAINT ck_ledger_entry__part CHECK (chain_partition BETWEEN 0 AND 63),
  CONSTRAINT ck_ledger_entry__hash CHECK (
    length(entry_hash) = 32
    AND length(prev_hash) = 32
    AND length(payload_hash) = 32
    AND length(metadata_hash) = 32
  ),
  CONSTRAINT ck_ledger_entry__actor CHECK (actor_kind IN ('NGUOI', 'DICH_VU', 'HE_THONG')),
  CONSTRAINT ck_ledger_entry__seq CHECK (seq_no >= 1),
  CONSTRAINT ck_ledger_entry__month CHECK (
    (recorded_month / 100) BETWEEN 2000 AND 2999
    AND (recorded_month % 100) BETWEEN 1 AND 12
  )
) PARTITION BY LIST (tenant_id);

CREATE INDEX ix_ledger_entry__aggregate ON ledger.ledger_entry (tenant_id, aggregate_id, recorded_at);
CREATE INDEX ix_ledger_entry__chain_seq ON ledger.ledger_entry (tenant_id, chain_partition, seq_no);

CREATE TRIGGER tg_ledger_entry__no_update
  BEFORE UPDATE OR DELETE ON ledger.ledger_entry
  FOR EACH ROW EXECUTE FUNCTION ledger.fn_ledger_immutable();

CREATE TRIGGER tg_ledger_entry__no_truncate
  BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.ledger_entry
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable();

COMMENT ON TABLE ledger.ledger_entry IS 'Bản ghi sổ cái kiểm toán bất biến theo chuỗi băm 64 phân vùng mỗi tenant (TL-03 ledger.ledger_entry)';
COMMENT ON COLUMN ledger.ledger_entry.tenant_id IS '{"cls":"C1","desc":"Mã tenant sở hữu bản ghi sổ cái"}';
COMMENT ON COLUMN ledger.ledger_entry.recorded_month IS '{"cls":"C1","desc":"Tháng ghi nhận dạng YYYYMM"}';
COMMENT ON COLUMN ledger.ledger_entry.chain_partition IS '{"cls":"C1","type_exception":"Theo TL-03 dùng smallint cho chỉ số phân vùng chuỗi 0..63","desc":"Phân vùng chuỗi băm 0..63"}';
COMMENT ON COLUMN ledger.ledger_entry.seq_no IS '{"cls":"C1","desc":"Số thứ tự tăng đơn điệu không khoảng trống trong (tenant_id, chain_partition)"}';
COMMENT ON COLUMN ledger.ledger_entry.id IS '{"cls":"C1","desc":"Định danh bản ghi sổ cái (UUIDv7)"}';
COMMENT ON COLUMN ledger.ledger_entry.event_id IS '{"cls":"C1","desc":"Định danh sự kiện gốc để chống ghi trùng"}';
COMMENT ON COLUMN ledger.ledger_entry.event_type IS '{"cls":"C1","desc":"Loại sự kiện hoặc thao tác kiểm toán"}';
COMMENT ON COLUMN ledger.ledger_entry.aggregate_type IS '{"cls":"C1","desc":"Loại đối tượng nghiệp vụ"}';
COMMENT ON COLUMN ledger.ledger_entry.aggregate_id IS '{"cls":"C1","desc":"Định danh đối tượng nghiệp vụ"}';
COMMENT ON COLUMN ledger.ledger_entry.actor_id IS '{"cls":"C1","desc":"Định danh người hoặc tác nhân thực hiện"}';
COMMENT ON COLUMN ledger.ledger_entry.actor_kind IS '{"cls":"C1","desc":"Loại tác nhân: NGUOI, DICH_VU, HE_THONG"}';
COMMENT ON COLUMN ledger.ledger_entry.occurred_at IS '{"cls":"C1","desc":"Thời điểm sự kiện nghiệp vụ xảy ra"}';
COMMENT ON COLUMN ledger.ledger_entry.recorded_at IS '{"cls":"C1","desc":"Thời điểm ghi vào sổ cái"}';
COMMENT ON COLUMN ledger.ledger_entry.payload_hash IS '{"cls":"C1","desc":"SHA-256 32 byte của payload chuẩn hoá RFC 8785 + NFC"}';
COMMENT ON COLUMN ledger.ledger_entry.metadata IS '{"cls":"C1","desc":"Siêu dữ liệu kiểm toán C1 (không chứa C2/C3)"}';
COMMENT ON COLUMN ledger.ledger_entry.metadata_hash IS '{"cls":"C1","desc":"SHA-256 32 byte của metadata chuẩn hoá RFC 8785 + NFC"}';
COMMENT ON COLUMN ledger.ledger_entry.prev_hash IS '{"cls":"C1","desc":"entry_hash của bản ghi seq_no - 1 (32 byte 0 cho bản ghi đầu chuỗi)"}';
COMMENT ON COLUMN ledger.ledger_entry.entry_hash IS '{"cls":"C1","desc":"SHA-256(prev_hash || payload_hash || metadata_hash)"}';

-- ---------------------------------------------------------------- ledger.chain_head
CREATE TABLE ledger.chain_head (
  tenant_id          uuid        NOT NULL,
  chain_partition    smallint    NOT NULL,
  last_seq_no        bigint      NOT NULL DEFAULT 0,
  last_hash          bytea       NOT NULL,
  last_sealed_seq_no bigint      NOT NULL DEFAULT 0,
  is_locked          boolean     NOT NULL DEFAULT false,
  locked_reason      text,
  updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT pk_chain_head PRIMARY KEY (tenant_id, chain_partition),
  CONSTRAINT ck_chain_head__part CHECK (chain_partition BETWEEN 0 AND 63),
  CONSTRAINT ck_chain_head__seq CHECK (
    last_seq_no >= 0
    AND last_sealed_seq_no >= 0
    AND last_sealed_seq_no <= last_seq_no
  ),
  CONSTRAINT ck_chain_head__hash CHECK (length(last_hash) = 32)
) WITH (fillfactor = 50);

COMMENT ON TABLE ledger.chain_head IS 'Đầu chuỗi băm hiện hành của từng phân vùng (tenant_id, chain_partition) (TL-03 ledger.chain_head)';
COMMENT ON COLUMN ledger.chain_head.tenant_id IS '{"cls":"C1","desc":"Mã tenant"}';
COMMENT ON COLUMN ledger.chain_head.chain_partition IS '{"cls":"C1","type_exception":"Theo TL-03 dùng smallint cho chỉ số phân vùng chuỗi 0..63","desc":"Phân vùng chuỗi băm 0..63"}';
COMMENT ON COLUMN ledger.chain_head.last_seq_no IS '{"cls":"C1","desc":"Số thứ tự bản ghi mới nhất của phân vùng"}';
COMMENT ON COLUMN ledger.chain_head.last_hash IS '{"cls":"C1","desc":"entry_hash mới nhất của phân vùng (32 byte 0 khi chưa có bản ghi)"}';
COMMENT ON COLUMN ledger.chain_head.last_sealed_seq_no IS '{"cls":"C1","desc":"Số thứ tự bản ghi cao nhất đã được niêm phong Merkle"}';
COMMENT ON COLUMN ledger.chain_head.is_locked IS '{"cls":"C1","desc":"Cờ khoá phân vùng khi phát hiện đứt chuỗi băm (BR-13-11)"}';
COMMENT ON COLUMN ledger.chain_head.locked_reason IS '{"cls":"C1","desc":"Lý do khoá phân vùng hoặc tham chiếu biên bản sự cố"}';
COMMENT ON COLUMN ledger.chain_head.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật đầu chuỗi gần nhất"}';

-- ---------------------------------------------------------------- ledger.cell_seal
CREATE TABLE ledger.cell_seal (
  id                  uuid         NOT NULL,
  cell_code           varchar(16)  NOT NULL,
  window_start        timestamptz  NOT NULL,
  window_end          timestamptz  NOT NULL,
  cell_root_hash      bytea        NOT NULL,
  tenant_count        int          NOT NULL,
  total_entries       bigint       NOT NULL,
  signer_key_id       varchar(128) NOT NULL,
  signature           bytea        NOT NULL,
  signer_cert_sha256  bytea        NOT NULL,
  rfc3161_tst         bytea        NOT NULL,
  prev_cell_seal_id   uuid,
  prev_cell_root_hash bytea        NOT NULL,
  sealed_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT pk_cell_seal PRIMARY KEY (id),
  CONSTRAINT fk_cell_seal__cell_seal FOREIGN KEY (prev_cell_seal_id) REFERENCES ledger.cell_seal (id),
  CONSTRAINT uq_cell_seal__window UNIQUE (cell_code, window_start, window_end),
  CONSTRAINT ck_cell_seal__root CHECK (
    length(cell_root_hash) = 32
    AND length(prev_cell_root_hash) = 32
    AND length(signer_cert_sha256) = 32
  ),
  CONSTRAINT ck_cell_seal__window CHECK (window_start < window_end),
  CONSTRAINT ck_cell_seal__counts CHECK (tenant_count >= 0 AND total_entries >= 0)
);

CREATE TRIGGER tg_cell_seal__no_update
  BEFORE UPDATE OR DELETE ON ledger.cell_seal
  FOR EACH ROW EXECUTE FUNCTION ledger.fn_ledger_immutable();

CREATE TRIGGER tg_cell_seal__no_truncate
  BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.cell_seal
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable();

COMMENT ON TABLE ledger.cell_seal IS 'Niêm phong Merkle cấp cell mỗi giờ kèm chữ ký HSM và dấu thời gian RFC 3161 (TL-03 ledger.cell_seal)';
COMMENT ON COLUMN ledger.cell_seal.id IS '{"cls":"C1","desc":"Định danh niêm phong cấp cell"}';
COMMENT ON COLUMN ledger.cell_seal.cell_code IS '{"cls":"C0","desc":"Mã cell được niêm phong"}';
COMMENT ON COLUMN ledger.cell_seal.window_start IS '{"cls":"C1","desc":"Thời điểm bắt đầu khung giờ niêm phong"}';
COMMENT ON COLUMN ledger.cell_seal.window_end IS '{"cls":"C1","desc":"Thời điểm kết thúc khung giờ niêm phong"}';
COMMENT ON COLUMN ledger.cell_seal.cell_root_hash IS '{"cls":"C1","desc":"Gốc Merkle SHA-256 32 byte của toàn cell"}';
COMMENT ON COLUMN ledger.cell_seal.tenant_count IS '{"cls":"C1","desc":"Số lượng tenant có bản ghi trong khung giờ"}';
COMMENT ON COLUMN ledger.cell_seal.total_entries IS '{"cls":"C1","desc":"Tổng số bản ghi sổ cái được niêm phong"}';
COMMENT ON COLUMN ledger.cell_seal.signer_key_id IS '{"cls":"C1","desc":"Định danh khoá HSM ký niêm phong"}';
COMMENT ON COLUMN ledger.cell_seal.signature IS '{"cls":"C1","desc":"Chữ ký số trên cell_root_hash"}';
COMMENT ON COLUMN ledger.cell_seal.signer_cert_sha256 IS '{"cls":"C1","desc":"Băm SHA-256 32 byte của chứng thư khoá niêm phong"}';
COMMENT ON COLUMN ledger.cell_seal.rfc3161_tst IS '{"cls":"C1","desc":"Token dấu thời gian RFC 3161"}';
COMMENT ON COLUMN ledger.cell_seal.prev_cell_seal_id IS '{"cls":"C1","desc":"Định danh bản niêm phong cell liền trước"}';
COMMENT ON COLUMN ledger.cell_seal.prev_cell_root_hash IS '{"cls":"C1","desc":"cell_root_hash liền trước (32 byte 0 cho lần đầu)"}';
COMMENT ON COLUMN ledger.cell_seal.sealed_at IS '{"cls":"C1","desc":"Thời điểm hoàn tất niêm phong"}';

-- ---------------------------------------------------------------- ledger.merkle_seal
CREATE TABLE ledger.merkle_seal (
  id               uuid        NOT NULL,
  tenant_id        uuid        NOT NULL,
  cell_seal_id     uuid        NOT NULL,
  window_start     timestamptz NOT NULL,
  window_end       timestamptz NOT NULL,
  seq_ranges       jsonb       NOT NULL,
  leaf_count       bigint      NOT NULL,
  tenant_root_hash bytea       NOT NULL,
  inclusion_proof  jsonb       NOT NULL,
  prev_seal_id     uuid,
  prev_root_hash   bytea       NOT NULL,
  sealed_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT pk_merkle_seal PRIMARY KEY (id),
  CONSTRAINT fk_merkle_seal__cell_seal FOREIGN KEY (cell_seal_id) REFERENCES ledger.cell_seal (id),
  CONSTRAINT fk_merkle_seal__merkle_seal FOREIGN KEY (prev_seal_id) REFERENCES ledger.merkle_seal (id),
  CONSTRAINT uq_merkle_seal__window UNIQUE (tenant_id, window_start, window_end),
  CONSTRAINT ck_merkle_seal__root CHECK (
    length(tenant_root_hash) = 32
    AND length(prev_root_hash) = 32
  ),
  CONSTRAINT ck_merkle_seal__window CHECK (window_start < window_end),
  CONSTRAINT ck_merkle_seal__leaf_count CHECK (leaf_count >= 0)
);

CREATE INDEX ix_merkle_seal__cell_seal ON ledger.merkle_seal (cell_seal_id);

CREATE TRIGGER tg_merkle_seal__no_update
  BEFORE UPDATE OR DELETE ON ledger.merkle_seal
  FOR EACH ROW EXECUTE FUNCTION ledger.fn_ledger_immutable();

CREATE TRIGGER tg_merkle_seal__no_truncate
  BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.merkle_seal
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable();

COMMENT ON TABLE ledger.merkle_seal IS 'Niêm phong Merkle cấp tenant kèm bằng chứng nằm trong cây cell (TL-03 ledger.merkle_seal)';
COMMENT ON COLUMN ledger.merkle_seal.id IS '{"cls":"C1","desc":"Định danh niêm phong cấp tenant"}';
COMMENT ON COLUMN ledger.merkle_seal.tenant_id IS '{"cls":"C1","desc":"Mã tenant"}';
COMMENT ON COLUMN ledger.merkle_seal.cell_seal_id IS '{"cls":"C1","desc":"Tham chiếu niêm phong cấp cell"}';
COMMENT ON COLUMN ledger.merkle_seal.window_start IS '{"cls":"C1","desc":"Thời điểm bắt đầu khung giờ niêm phong"}';
COMMENT ON COLUMN ledger.merkle_seal.window_end IS '{"cls":"C1","desc":"Thời điểm kết thúc khung giờ niêm phong"}';
COMMENT ON COLUMN ledger.merkle_seal.seq_ranges IS '{"cls":"C1","desc":"Dải seq_no theo từng phân vùng chuỗi trong khung giờ"}';
COMMENT ON COLUMN ledger.merkle_seal.leaf_count IS '{"cls":"C1","desc":"Số lượng lá trong cây Merkle của tenant"}';
COMMENT ON COLUMN ledger.merkle_seal.tenant_root_hash IS '{"cls":"C1","desc":"Gốc Merkle SHA-256 32 byte của tenant"}';
COMMENT ON COLUMN ledger.merkle_seal.inclusion_proof IS '{"cls":"C1","desc":"Đường đi chứng minh từ tenant_root_hash lên cell_root_hash"}';
COMMENT ON COLUMN ledger.merkle_seal.prev_seal_id IS '{"cls":"C1","desc":"Định danh bản niêm phong tenant liền trước"}';
COMMENT ON COLUMN ledger.merkle_seal.prev_root_hash IS '{"cls":"C1","desc":"tenant_root_hash liền trước (32 byte 0 cho lần đầu)"}';
COMMENT ON COLUMN ledger.merkle_seal.sealed_at IS '{"cls":"C1","desc":"Thời điểm niêm phong"}';

-- ---------------------------------------------------------------- ledger.anchor_receipt
CREATE TABLE ledger.anchor_receipt (
  id            uuid        NOT NULL,
  cell_seal_id  uuid        NOT NULL,
  anchor_target varchar(32) NOT NULL,
  receipt       bytea       NOT NULL,
  anchored_at   timestamptz NOT NULL,
  CONSTRAINT pk_anchor_receipt PRIMARY KEY (id),
  CONSTRAINT fk_anchor_receipt__cell_seal FOREIGN KEY (cell_seal_id) REFERENCES ledger.cell_seal (id),
  CONSTRAINT uq_anchor_receipt__cell_target UNIQUE (cell_seal_id, anchor_target),
  CONSTRAINT ck_anchor_receipt__target CHECK (
    anchor_target IN ('CELL_KHAC', 'KHO_VPTW', 'TSA_DOC_LAP', 'OBJECT_LOCK')
  )
);

CREATE TRIGGER tg_anchor_receipt__no_update
  BEFORE UPDATE OR DELETE ON ledger.anchor_receipt
  FOR EACH ROW EXECUTE FUNCTION ledger.fn_ledger_immutable();

CREATE TRIGGER tg_anchor_receipt__no_truncate
  BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.anchor_receipt
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable();

COMMENT ON TABLE ledger.anchor_receipt IS 'Biên nhận neo gốc niêm phong cell ra ngoài cụm (TL-03 ledger.anchor_receipt)';
COMMENT ON COLUMN ledger.anchor_receipt.id IS '{"cls":"C1","desc":"Định danh biên nhận neo"}';
COMMENT ON COLUMN ledger.anchor_receipt.cell_seal_id IS '{"cls":"C1","desc":"Niêm phong cell được neo"}';
COMMENT ON COLUMN ledger.anchor_receipt.anchor_target IS '{"cls":"C1","desc":"Đích neo: CELL_KHAC, KHO_VPTW, TSA_DOC_LAP, OBJECT_LOCK"}';
COMMENT ON COLUMN ledger.anchor_receipt.receipt IS '{"cls":"C1","desc":"Dữ liệu biên nhận từ đích neo"}';
COMMENT ON COLUMN ledger.anchor_receipt.anchored_at IS '{"cls":"C1","desc":"Thời điểm neo thành công"}';

-- ---------------------------------------------------------------- ledger.verify_run
CREATE TABLE ledger.verify_run (
  id              uuid        NOT NULL,
  tenant_id       uuid,
  verify_kind     varchar(16) NOT NULL,
  range_from      timestamptz NOT NULL,
  range_to        timestamptz NOT NULL,
  entries_checked bigint      NOT NULL,
  mismatches      int         NOT NULL,
  first_mismatch  jsonb,
  started_at      timestamptz NOT NULL,
  finished_at     timestamptz,
  CONSTRAINT pk_verify_run PRIMARY KEY (id),
  CONSTRAINT ck_verify_run__kind CHECK (
    verify_kind IN ('DAU_CHUOI', 'NIEM_PHONG', 'TOAN_BO', 'LUU_TRU')
  ),
  CONSTRAINT ck_verify_run__range CHECK (range_from <= range_to),
  CONSTRAINT ck_verify_run__counts CHECK (entries_checked >= 0 AND mismatches >= 0)
);

CREATE INDEX ix_verify_run__tenant_started ON ledger.verify_run (tenant_id, started_at DESC);

COMMENT ON TABLE ledger.verify_run IS 'Kết quả lần chạy kiểm tra toàn vẹn sổ cái (TL-03 ledger.verify_run)';
COMMENT ON COLUMN ledger.verify_run.id IS '{"cls":"C1","desc":"Định danh lần chạy kiểm tra toàn vẹn"}';
COMMENT ON COLUMN ledger.verify_run.tenant_id IS '{"cls":"C1","desc":"Mã tenant (NULL khi kiểm tra cấp cell)"}';
COMMENT ON COLUMN ledger.verify_run.verify_kind IS '{"cls":"C1","desc":"Loại kiểm tra: DAU_CHUOI, NIEM_PHONG, TOAN_BO, LUU_TRU"}';
COMMENT ON COLUMN ledger.verify_run.range_from IS '{"cls":"C1","desc":"Đầu khoảng thời gian kiểm tra"}';
COMMENT ON COLUMN ledger.verify_run.range_to IS '{"cls":"C1","desc":"Cuối khoảng thời gian kiểm tra"}';
COMMENT ON COLUMN ledger.verify_run.entries_checked IS '{"cls":"C1","desc":"Số lượng bản ghi đã kiểm tra"}';
COMMENT ON COLUMN ledger.verify_run.mismatches IS '{"cls":"C1","desc":"Số lượng sai lệch phát hiện"}';
COMMENT ON COLUMN ledger.verify_run.first_mismatch IS '{"cls":"C1","desc":"Chi tiết sai lệch đầu tiên nếu có"}';
COMMENT ON COLUMN ledger.verify_run.started_at IS '{"cls":"C1","desc":"Thời điểm bắt đầu kiểm tra"}';
COMMENT ON COLUMN ledger.verify_run.finished_at IS '{"cls":"C1","desc":"Thời điểm kết thúc kiểm tra"}';

-- ---------------------------------------------------------------- ledger.archive_segment
CREATE TABLE ledger.archive_segment (
  id             uuid         NOT NULL,
  tenant_id      uuid         NOT NULL,
  recorded_month int          NOT NULL,
  seq_ranges     jsonb        NOT NULL,
  object_key     varchar(512) NOT NULL,
  file_sha256    bytea        NOT NULL,
  row_count      bigint       NOT NULL,
  lock_until     date         NOT NULL,
  verified_at    timestamptz  NOT NULL,
  detached_at    timestamptz,
  CONSTRAINT pk_archive_segment PRIMARY KEY (id),
  CONSTRAINT uq_archive_segment__tenant_month UNIQUE (tenant_id, recorded_month),
  CONSTRAINT ck_archive_segment__hash CHECK (length(file_sha256) = 32),
  CONSTRAINT ck_archive_segment__rows CHECK (row_count >= 0)
);

COMMENT ON TABLE ledger.archive_segment IS 'Phân đoạn sổ cái đã chuyển sang lưu trữ lạnh Object Lock (TL-03 ledger.archive_segment)';
COMMENT ON COLUMN ledger.archive_segment.id IS '{"cls":"C1","desc":"Định danh phân đoạn lưu trữ"}';
COMMENT ON COLUMN ledger.archive_segment.tenant_id IS '{"cls":"C1","desc":"Mã tenant"}';
COMMENT ON COLUMN ledger.archive_segment.recorded_month IS '{"cls":"C1","desc":"Tháng ghi nhận dạng YYYYMM"}';
COMMENT ON COLUMN ledger.archive_segment.seq_ranges IS '{"cls":"C1","desc":"Dải seq_no theo từng phân vùng chuỗi trong phân đoạn"}';
COMMENT ON COLUMN ledger.archive_segment.object_key IS '{"cls":"C1","desc":"Đường dẫn tệp Parquet trên kho đối tượng"}';
COMMENT ON COLUMN ledger.archive_segment.file_sha256 IS '{"cls":"C1","desc":"Băm SHA-256 32 byte của tệp Parquet"}';
COMMENT ON COLUMN ledger.archive_segment.row_count IS '{"cls":"C1","desc":"Tổng số bản ghi trong phân đoạn"}';
COMMENT ON COLUMN ledger.archive_segment.lock_until IS '{"cls":"C1","desc":"Ngày hết hạn khoá WORM COMPLIANCE (tối thiểu 10 năm)"}';
COMMENT ON COLUMN ledger.archive_segment.verified_at IS '{"cls":"C1","desc":"Thời điểm kiểm chứng tệp lưu trữ trước khi tách phân vùng"}';
COMMENT ON COLUMN ledger.archive_segment.detached_at IS '{"cls":"C1","desc":"Thời điểm tách phân vùng khỏi bảng nóng"}';

-- ---------------------------------------------------------------- ledger.outbox_event
CREATE TABLE ledger.outbox_event (
  id                uuid         NOT NULL,
  tenant_id         uuid         NOT NULL,
  aggregate_type    varchar(64)  NOT NULL,
  aggregate_id      uuid         NOT NULL,
  aggregate_version bigint       NOT NULL,
  event_type        varchar(96)  NOT NULL,
  topic             varchar(128) NOT NULL,
  event_key         varchar(80)  NOT NULL,
  payload           bytea        NOT NULL,
  schema_version    int          NOT NULL,
  headers           jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_outbox_event PRIMARY KEY (id, created_at),
  CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$')
) PARTITION BY RANGE (created_at);

CREATE TABLE ledger.outbox_event_default PARTITION OF ledger.outbox_event DEFAULT;

COMMENT ON TABLE ledger.outbox_event IS 'Hộp thư đi giao dịch của SVC-09 phát EVT-19, EVT-20 (TL-03 outbox_event)';
COMMENT ON COLUMN ledger.outbox_event.id IS '{"cls":"C1","desc":"Định danh sự kiện outbox"}';
COMMENT ON COLUMN ledger.outbox_event.tenant_id IS '{"cls":"C1","desc":"Mã tenant"}';
COMMENT ON COLUMN ledger.outbox_event.aggregate_type IS '{"cls":"C1","desc":"Loại đối tượng phát sự kiện"}';
COMMENT ON COLUMN ledger.outbox_event.aggregate_id IS '{"cls":"C1","desc":"Định danh đối tượng phát sự kiện"}';
COMMENT ON COLUMN ledger.outbox_event.aggregate_version IS '{"cls":"C1","desc":"Phiên bản đơn điệu của đối tượng"}';
COMMENT ON COLUMN ledger.outbox_event.event_type IS '{"cls":"C1","desc":"Mã sự kiện (EVT-19, EVT-20)"}';
COMMENT ON COLUMN ledger.outbox_event.topic IS '{"cls":"C1","desc":"Tên topic Kafka đích"}';
COMMENT ON COLUMN ledger.outbox_event.event_key IS '{"cls":"C1","desc":"Khoá phân vùng Kafka"}';
COMMENT ON COLUMN ledger.outbox_event.payload IS '{"cls":"C1","desc":"Payload sự kiện C0/C1"}';
COMMENT ON COLUMN ledger.outbox_event.schema_version IS '{"cls":"C1","desc":"Phiên bản lược đồ sự kiện"}';
COMMENT ON COLUMN ledger.outbox_event.headers IS '{"cls":"C1","desc":"Header CloudEvents dạng JSONB"}';
COMMENT ON COLUMN ledger.outbox_event.created_at IS '{"cls":"C1","desc":"Thời điểm ghi outbox"}';
