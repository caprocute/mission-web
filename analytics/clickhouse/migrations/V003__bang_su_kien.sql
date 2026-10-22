-- DV-15-01 · QT-14, QT-16 → SVC-15 · TL-03 "Bảng đích và mô hình sao".
-- Sáu bảng sự kiện, phân vùng theo period_bucket. Khoá sắp xếp chỉ gồm cột không đổi của dòng nguồn (tenant_id,
-- khoá cha, id) để bản ghi sửa và xoá của cùng một dòng gộp được với nhau; riêng fact_task_progress giữ đúng mẫu
-- TL-03 (tenant_id, org_unit_id, task_id, id) vì dòng tiến độ chỉ thêm, không sửa.
-- Cột tự do (tiêu đề, ghi chú, lý do) và cột C2 không đưa vào: phân tích chỉ cần mã, trạng thái và số đo.
-- org_unit_id, person_key của bảng không có cột đơn vị ở nguồn được tra từ từ điển ở V004 lúc nạp (suy luận).

CREATE TABLE {{db}}.fact_task ON CLUSTER '{{cluster}}'
(
  tenant_id         UUID,
  period_bucket     Int32,
  id                UUID,
  period_id         UUID,
  code              String,
  task_group        LowCardinality(String),
  is_key_task       UInt8,
  source            LowCardinality(String),
  plan_item_id      Nullable(UUID),
  program_id        Nullable(UUID),
  key_result_id     Nullable(UUID),
  parent_task_id    Nullable(UUID),
  assigner_key      Nullable(FixedString(32)),
  start_date        Nullable(Date32),
  due_date          Date32,
  extended_due_date Nullable(Date32),
  planned_quantity  Decimal(18, 4),
  done_quantity     Decimal(18, 4),
  difficulty        Nullable(Int16),
  standard_score    Nullable(Decimal(6, 3)),
  progress_pct      Decimal(6, 4),
  status            LowCardinality(String),
  is_overdue        UInt8,
  is_restricted     UInt8,
  delay_risk        LowCardinality(Nullable(String)),
  assigned_at       Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  completed_at      Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  created_at        DateTime64(3, 'Asia/Ho_Chi_Minh'),
  updated_at        DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version          UInt64,
  _is_deleted       UInt8,
  _source_ts        DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at      DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, id);

-- Bảng cầu nhiệm vụ ↔ đơn vị, người được giao (task.task_assignee, hai trục thời gian). Cube nối qua bảng này để
-- lọc nhiệm vụ theo đơn vị; từ điển dict_task_unit lấy đơn vị chủ trì hiện hành từ đây.
CREATE TABLE {{db}}.fact_task_assignee ON CLUSTER '{{cluster}}'
(
  tenant_id     UUID,
  period_bucket Int32,
  id            UUID,
  task_id       UUID,
  assignee_type LowCardinality(String),
  person_key    Nullable(FixedString(32)),
  org_unit_id   Nullable(UUID),
  role          LowCardinality(String),
  accepted_at   Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  removed_at    Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  valid_from    Date32,
  valid_to      Date32,
  _version      UInt64,
  _is_deleted   UInt8,
  _source_ts    DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at  DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, task_id, id);

CREATE TABLE {{db}}.fact_task_progress ON CLUSTER '{{cluster}}'
(
  tenant_id     UUID,
  period_bucket Int32,
  id            UUID,
  task_id       UUID,
  person_key    FixedString(32),   -- băm giả danh của người báo cáo (created_by)
  org_unit_id   UUID,
  progress_pct  Decimal(6, 4),
  done_quantity Nullable(Decimal(18, 4)),
  channel       LowCardinality(String),
  reported_at   DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version      UInt64,             -- __lsn
  _is_deleted   UInt8,
  _source_ts    DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at  DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, org_unit_id, task_id, id);

CREATE TABLE {{db}}.fact_evidence ON CLUSTER '{{cluster}}'
(
  tenant_id      UUID,
  period_bucket  Int32,
  id             UUID,
  period_id      UUID,
  deliverable_id UUID,
  task_id        UUID,
  org_unit_id    UUID,
  person_key     FixedString(32),
  current_status LowCardinality(String),
  source_type    LowCardinality(String),
  created_at     DateTime64(3, 'Asia/Ho_Chi_Minh'),
  updated_at     DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version       UInt64,
  _is_deleted    UInt8,
  _source_ts     DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at   DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, task_id, id);

-- Ảnh chụp điểm (appraisal.score_snapshot); is_current = 1 khi chưa bị ảnh chụp mới thay (superseded_by_id rỗng).
CREATE TABLE {{db}}.fact_score ON CLUSTER '{{cluster}}'
(
  tenant_id              UUID,
  period_bucket          Int32,
  id                     UUID,
  period_id              UUID,
  profile_id             UUID,
  person_key             FixedString(32),
  org_unit_id            UUID,
  subject_group_id       Nullable(UUID),
  flow                   LowCardinality(String),
  snapshot_kind          LowCardinality(String),
  policy_pack_version_id UUID,
  policy_version         String,
  total_score            Decimal(6, 3),
  regular_score          Nullable(Decimal(6, 3)),
  key_task_score         Nullable(Decimal(6, 3)),
  general_criteria_score Nullable(Decimal(6, 3)),
  kpi_score              Nullable(Decimal(6, 3)),
  exceed_ratio           Nullable(Decimal(6, 4)),
  objective_ratio        Nullable(Decimal(6, 4)),
  general_criteria_level LowCardinality(Nullable(String)),
  auto_rating_code       LowCardinality(String),
  is_current             UInt8,
  computed_at            DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version               UInt64,
  _is_deleted            UInt8,
  _source_ts             DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at           DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, profile_id, id);

-- Mức xếp loại từng cấp (appraisal.rating_proposal). reason_enc, comment_enc là C2 nên không vào kho phân tích.
CREATE TABLE {{db}}.fact_rating ON CLUSTER '{{cluster}}'
(
  tenant_id            UUID,
  period_bucket        Int32,
  id                   UUID,
  period_id            UUID,
  profile_id           UUID,
  person_key           FixedString(32),
  org_unit_id          UUID,
  subject_group_id     Nullable(UUID),
  flow                 LowCardinality(String),
  level_kind           LowCardinality(String),
  rating_code          LowCardinality(String),
  score_snapshot_id    UUID,
  actor_key            FixedString(32),
  actor_role           LowCardinality(String),
  is_current           UInt8,
  differs_from_prev    UInt8,
  explanation_required UInt8,
  is_bulk_decision     UInt8,
  is_signed            UInt8,
  proposed_at          DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version             UInt64,
  _is_deleted          UInt8,
  _source_ts           DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at         DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, profile_id, id);

CREATE TABLE {{db}}.fact_okr_checkin ON CLUSTER '{{cluster}}'
(
  tenant_id     UUID,
  period_bucket Int32,
  id            UUID,
  period_id     UUID,
  key_result_id UUID,
  objective_id  Nullable(UUID),
  org_unit_id   UUID,
  person_key    FixedString(32),
  checkin_at    DateTime64(3, 'Asia/Ho_Chi_Minh'),
  value         Decimal(18, 4),
  confidence    LowCardinality(String),
  channel       LowCardinality(String),
  status        LowCardinality(String),
  verifier_key  Nullable(FixedString(32)),
  verified_at   Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  _version      UInt64,
  _is_deleted   UInt8,
  _source_ts    DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at  DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, key_result_id, id);

-- Bảng tra cứu nội bộ (suy luận): giữ trạng thái cuối của dòng nguồn chỉ cần để gắn đơn vị, người cho bảng sự kiện.
-- Không cấp cho Cube; chỉ tài khoản nạp từ điển đọc được.
CREATE TABLE {{db}}.lk_assignment ON CLUSTER '{{cluster}}'
(
  tenant_id    UUID,
  id           UUID,
  valid_from   Date32,
  valid_to     Date32,
  person_key   FixedString(32),
  org_unit_id  UUID,
  job_title_id UUID,
  kind         LowCardinality(String),
  branch       LowCardinality(String),
  is_head      UInt8,
  _version     UInt64,
  _is_deleted  UInt8
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id, valid_from);

CREATE TABLE {{db}}.lk_objective ON CLUSTER '{{cluster}}'
(
  tenant_id     UUID,
  id            UUID,
  owner_unit_id UUID,
  _version      UInt64,
  _is_deleted   UInt8
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id);

CREATE TABLE {{db}}.lk_key_result ON CLUSTER '{{cluster}}'
(
  tenant_id    UUID,
  id           UUID,
  objective_id UUID,
  _version     UInt64,
  _is_deleted  UInt8
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id);

CREATE TABLE {{db}}.lk_appraisal_profile ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  id               UUID,
  period_bucket    Int32,
  person_key       FixedString(32),
  org_unit_id      UUID,
  subject_group_id Nullable(UUID),
  flow             LowCardinality(String),
  _version         UInt64,
  _is_deleted      UInt8
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id);

-- Bản ghi CDC không giải mã được (kafka_handle_error_mode = 'stream'), để tra cứu thay vì chặn cả partition.
CREATE TABLE {{db}}.cdc_error ON CLUSTER '{{cluster}}'
(
  topic        LowCardinality(String),
  partition    UInt64,
  offset       UInt64,
  error        String,
  raw_size     UInt32,
  _ingested_at DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedMergeTree
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (topic, partition, offset)
TTL toDateTime(_ingested_at) + INTERVAL 30 DAY;
