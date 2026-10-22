-- DV-15-09 · QT-14 dấu hiệu 8 (miễn trừ khách quan lặp lại, gộp luật BR-06-12) và bước 10 (tự đóng khi hết dấu hiệu).
-- 1. Nguồn task.task_change_request (QT-06): đề nghị gia hạn đã duyệt và nguyên nhân người duyệt kết luận; mã người
--    đề nghị và người duyệt đi qua giả danh, cột lý do, ý kiến tự do không đọc.
-- 2. anomaly_run: mọi dòng mà các luật phát hiện trong một lượt chạy (kể cả cảnh báo đã có), để biết cảnh báo nào
--    không còn được phát hiện; giữ 30 ngày.
-- 3. anomaly_out thêm resolved_at (lượt chạy không còn phát hiện) và resolution_published_at (đã phát tín hiệu hết
--    dấu hiệu cho MOD-07).

CREATE TABLE {{db}}.fact_task_change_request ON CLUSTER '{{cluster}}'
(
  tenant_id           UUID,
  period_bucket       Int32,
  id                  UUID,
  period_id           UUID,
  task_id             UUID,
  request_type        LowCardinality(String),
  status              LowCardinality(String),
  is_objective_reason UInt8,
  cause_claimed       LowCardinality(Nullable(String)),
  cause_decided       LowCardinality(Nullable(String)),
  requester_key       FixedString(32),             -- băm giả danh created_by
  decider_key         Nullable(FixedString(32)),   -- băm giả danh decided_by
  decided_at          Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  _version            UInt64,
  _is_deleted         UInt8,
  _source_ts          DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at        DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, id);

CREATE TABLE {{db}}.src_task__task_change_request ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `task_id` String,
  `request_type` String,
  `status` String,
  `is_objective_reason` String,
  `cause_claimed` String,
  `cause_decided` String,
  `created_by` String COMMENT 'gia_danh',
  `decided_by` String COMMENT 'gia_danh',
  `decided_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.task.task_change_request',
         kafka_group_name = '{{kafka_group}}.task.task_change_request',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE MATERIALIZED VIEW {{db}}.mv__task__task_change_request ON CLUSTER '{{cluster}}'
TO {{db}}.fact_task_change_request AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(period_id) AS period_id, toUUID(task_id) AS task_id, request_type, status,
       mission_cdc_bool(is_objective_reason) AS is_objective_reason, nullIf(cause_claimed, '') AS cause_claimed,
       nullIf(cause_decided, '') AS cause_decided, assumeNotNull(mission_cdc_key(created_by)) AS requester_key,
       mission_cdc_key(decided_by) AS decider_key, mission_cdc_ts(decided_at) AS decided_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_task__task_change_request WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__task__task_change_request ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_task__task_change_request
WHERE length(_error) > 0;

CREATE TABLE {{db}}.anomaly_run ON CLUSTER '{{cluster}}'
(
  tenant_id      UUID,
  alert_key      String,
  signal_code    LowCardinality(String),
  severity       LowCardinality(String),
  subject_type   LowCardinality(String),
  subject_id     UUID,
  period_id      Nullable(UUID),
  evidence_chain String,
  model_version  String,
  detected_at    DateTime64(3, 'Asia/Ho_Chi_Minh'),
  run_id         UUID,
  published_at   Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  _version       UInt64
)
ENGINE = ReplicatedMergeTree
PARTITION BY toYYYYMM(detected_at)
ORDER BY (tenant_id, run_id, alert_key)
TTL toDateTime(detected_at) + INTERVAL 30 DAY;

ALTER TABLE {{db}}.anomaly_out ON CLUSTER '{{cluster}}'
  ADD COLUMN IF NOT EXISTS resolved_at Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  ADD COLUMN IF NOT EXISTS resolution_published_at Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh'));

GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, task_id, request_type, status, is_objective_reason, cause_claimed, cause_decided, requester_key, decider_key, decided_at)
  ON {{db}}.fact_task_change_request TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_task_change_request ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT, INSERT ON {{db}}.anomaly_run TO {{db}}_anomaly;
