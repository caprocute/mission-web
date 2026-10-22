-- DV-15-08 · QT-14 dấu hiệu 3 "Mục tiêu đặt quá dễ" cần chỉ tiêu, giá trị xuất phát và chiều tốt của KR, mà V005 chỉ
-- đọc (id, objective_id) của okr.key_result cho từ điển dict_kr_unit. Kafka engine không ALTER được, nên tệp này dựng
-- lại bảng nguồn với thêm cột C1 (connector phân tích đã gửi đủ cột, không cột nào của okr.key_result bị loại), thêm
-- bảng fact_okr_key_result, và tạo lại view nạp lk_key_result như V006. Bảng nguồn giữ nhóm tiêu thụ cũ nên đọc tiếp từ
-- offset đã ghi; KR có trước khi áp tệp này chỉ vào fact_okr_key_result sau khi connector chụp lại okr.key_result
-- (incremental snapshot, ghi ở wiki/14).

CREATE TABLE {{db}}.fact_okr_key_result ON CLUSTER '{{cluster}}'
(
  tenant_id      UUID,
  period_bucket  Int32,
  id             UUID,
  period_id      UUID,
  objective_id   UUID,
  metric_type    LowCardinality(String),   -- SO_LUONG, TY_LE, CO_KHONG, CHI_SO
  direction      LowCardinality(String),   -- TANG, GIAM, DAT
  baseline_value Nullable(Decimal(18, 4)),
  target_value   Decimal(18, 4),
  weight         Decimal(6, 4),
  due_date       Date32,
  status         LowCardinality(String),
  _version       UInt64,
  _is_deleted    UInt8,
  _source_ts     DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at   DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY period_bucket
ORDER BY (tenant_id, id);

DROP VIEW IF EXISTS {{db}}.mv__okr__key_result ON CLUSTER '{{cluster}}' SYNC;
DROP VIEW IF EXISTS {{db}}.mv_err__okr__key_result ON CLUSTER '{{cluster}}' SYNC;
DROP TABLE IF EXISTS {{db}}.src_okr__key_result ON CLUSTER '{{cluster}}' SYNC;

CREATE TABLE {{db}}.src_okr__key_result ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `objective_id` String,
  `period_id` String,
  `period_bucket` String,
  `metric_type` String,
  `direction` String,
  `baseline_value` String,
  `target_value` String,
  `weight` String,
  `due_date` String,
  `status` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.okr.key_result',
         kafka_group_name = '{{kafka_group}}.okr.key_result',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE MATERIALIZED VIEW {{db}}.mv__okr__key_result ON CLUSTER '{{cluster}}' TO {{db}}.lk_key_result AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, toUUID(objective_id) AS objective_id,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted
FROM {{db}}.src_okr__key_result WHERE length(_error) = 0;

-- Chuyển đổi chịu được trường thiếu để một bản ghi lạ không chặn view nạp lk_key_result cùng khối.
CREATE MATERIALIZED VIEW {{db}}.mv__okr__key_result_fact ON CLUSTER '{{cluster}}' TO {{db}}.fact_okr_key_result AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32OrZero(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUIDOrZero(period_id) AS period_id, toUUID(objective_id) AS objective_id, metric_type, direction,
       toDecimal64OrNull(baseline_value, 4) AS baseline_value, toDecimal64OrZero(target_value, 4) AS target_value,
       toDecimal64OrZero(weight, 4) AS weight, ifNull(mission_cdc_date(due_date), toDate32('1970-01-01')) AS due_date,
       status,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_okr__key_result WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__okr__key_result ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_okr__key_result
WHERE length(_error) > 0;

GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, objective_id, metric_type, direction, baseline_value, target_value, weight, due_date, status)
  ON {{db}}.fact_okr_key_result TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_okr_key_result ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
