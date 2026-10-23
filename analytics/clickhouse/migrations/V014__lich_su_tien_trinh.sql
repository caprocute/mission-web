-- DV-06-11 · SVC-06/G2 · TL-01 "Lịch sử ACT_HI_* được Debezium đọc sang ClickHouse trước khi dọn" · TL-03 "CDC phân
-- tích sang ClickHouse": lịch sử tiến trình Flowable của CSDL flowable (topic {{topic_workflow}}.flowable.act_hi_*) vào
-- ba bảng sự kiện cho báo cáo thời gian xử lý theo cấp và phát hiện miễn trừ lặp lại. Connector chỉ gửi cột C1 không
-- định danh (phân loại ở backend/services/workflow-service/.../V8__phan_loai_cot_lich_su_cdc.sql); người xử lý, văn bản
-- tự do không vào kho.
-- Job dọn lịch sử của Flowable xoá dòng ACT_HI_* sau 400 ngày; bản ghi xoá đó không được coi là xoá ở kho (view nạp bỏ
-- __op = 'd'), vì kho là nơi giữ lịch sử dài hạn. Cột thời gian của Flowable là timestamp không múi giờ, Debezium gửi
-- số micro giây; engine ghi theo giờ UTC của JVM nên đổi thẳng sang mốc tuyệt đối (suy luận).

CREATE OR REPLACE FUNCTION mission_cdc_micros ON CLUSTER '{{cluster}}' AS (x) ->
  if(match(x, '^-?[0-9]+$'), fromUnixTimestamp64Micro(toInt64(x), 'Asia/Ho_Chi_Minh'),
     parseDateTime64BestEffortOrNull(x, 3, 'Asia/Ho_Chi_Minh'));

-- Mã định nghĩa của Flowable có dạng <khoá>:<phiên bản>:<mã>; khoá là mã mẫu luồng.
CREATE OR REPLACE FUNCTION mission_flowable_def_key ON CLUSTER '{{cluster}}' AS (x) ->
  splitByChar(':', x)[1];

CREATE TABLE {{db}}.fact_process_instance ON CLUSTER '{{cluster}}'
(
  tenant_id                  UUID,
  id                         String,
  process_key                LowCardinality(String),
  process_definition_id      String,
  business_key               String,
  business_type              LowCardinality(String),
  business_id                Nullable(UUID),
  start_activity_id          LowCardinality(String),
  end_activity_id            LowCardinality(Nullable(String)),
  super_process_instance_id  Nullable(String),
  business_status            LowCardinality(Nullable(String)),
  start_time                 DateTime64(3, 'Asia/Ho_Chi_Minh'),
  end_time                   Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  duration_ms                Nullable(Int64),
  _version                   UInt64,
  _is_deleted                UInt8,
  _source_ts                 DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at               DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY toYYYYMM(start_time)
ORDER BY (tenant_id, id);

CREATE TABLE {{db}}.fact_process_task ON CLUSTER '{{cluster}}'
(
  tenant_id            UUID,
  id                   String,
  process_instance_id  Nullable(String),
  process_key          LowCardinality(String),
  task_def_key         LowCardinality(String),
  name                 Nullable(String),
  state                LowCardinality(Nullable(String)),
  category             LowCardinality(Nullable(String)),
  priority             Nullable(Int32),
  start_time           DateTime64(3, 'Asia/Ho_Chi_Minh'),
  claim_time           Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  in_progress_time     Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  end_time             Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  due_date             Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  duration_ms          Nullable(Int64),
  _version             UInt64,
  _is_deleted          UInt8,
  _source_ts           DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at         DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY toYYYYMM(start_time)
ORDER BY (tenant_id, id);

CREATE TABLE {{db}}.fact_process_activity ON CLUSTER '{{cluster}}'
(
  tenant_id            UUID,
  id                   String,
  process_instance_id  String,
  process_key          LowCardinality(String),
  activity_id          LowCardinality(String),
  activity_type        LowCardinality(String),
  activity_name        Nullable(String),
  task_id              Nullable(String),
  call_process_instance_id Nullable(String),
  transaction_order    Nullable(Int32),
  start_time           DateTime64(3, 'Asia/Ho_Chi_Minh'),
  end_time             Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  duration_ms          Nullable(Int64),
  _version             UInt64,
  _is_deleted          UInt8,
  _source_ts           DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at         DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
PARTITION BY toYYYYMM(start_time)
ORDER BY (tenant_id, id);

CREATE TABLE {{db}}.src_flowable__act_hi_procinst ON CLUSTER '{{cluster}}'
(
  `id_` String,
  `proc_inst_id_` String,
  `business_key_` String,
  `proc_def_id_` String,
  `start_time_` String,
  `end_time_` String,
  `duration_` String,
  `start_act_id_` String,
  `end_act_id_` String,
  `super_process_instance_id_` String,
  `tenant_id_` String,
  `business_status_` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_workflow}}.flowable.act_hi_procinst',
         kafka_group_name = '{{kafka_group}}.flowable.act_hi_procinst',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_flowable__act_hi_taskinst ON CLUSTER '{{cluster}}'
(
  `id_` String,
  `proc_def_id_` String,
  `task_def_key_` String,
  `proc_inst_id_` String,
  `state_` String,
  `name_` String,
  `start_time_` String,
  `in_progress_time_` String,
  `claim_time_` String,
  `end_time_` String,
  `duration_` String,
  `priority_` String,
  `due_date_` String,
  `category_` String,
  `tenant_id_` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_workflow}}.flowable.act_hi_taskinst',
         kafka_group_name = '{{kafka_group}}.flowable.act_hi_taskinst',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_flowable__act_hi_actinst ON CLUSTER '{{cluster}}'
(
  `id_` String,
  `proc_def_id_` String,
  `proc_inst_id_` String,
  `act_id_` String,
  `task_id_` String,
  `call_proc_inst_id_` String,
  `act_name_` String,
  `act_type_` String,
  `start_time_` String,
  `end_time_` String,
  `transaction_order_` String,
  `duration_` String,
  `tenant_id_` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_workflow}}.flowable.act_hi_actinst',
         kafka_group_name = '{{kafka_group}}.flowable.act_hi_actinst',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

-- Bản ghi xoá (__op = 'd') là job dọn lịch sử của engine, không phải xoá nghiệp vụ: không nạp. Tiến trình không gắn
-- tenant (mẫu chung chưa ràng buộc) có tenant_id_ rỗng, nạp với tenant toàn 0.
CREATE MATERIALIZED VIEW {{db}}.mv__flowable__act_hi_procinst ON CLUSTER '{{cluster}}' TO {{db}}.fact_process_instance AS
SELECT toUUIDOrZero(tenant_id_) AS tenant_id, id_ AS id, mission_flowable_def_key(proc_def_id_) AS process_key,
       proc_def_id_ AS process_definition_id, business_key_ AS business_key,
       splitByChar(':', business_key_)[1] AS business_type,
       toUUIDOrNull(arrayElement(splitByChar(':', business_key_), 2)) AS business_id,
       start_act_id_ AS start_activity_id, nullIf(end_act_id_, '') AS end_activity_id,
       nullIf(super_process_instance_id_, '') AS super_process_instance_id,
       nullIf(business_status_, '') AS business_status,
       assumeNotNull(mission_cdc_micros(start_time_)) AS start_time, mission_cdc_micros(end_time_) AS end_time,
       toInt64OrNull(duration_) AS duration_ms,
       toUInt64(__lsn) AS _version, toUInt8(0) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_flowable__act_hi_procinst WHERE length(_error) = 0 AND __op != 'd';

CREATE MATERIALIZED VIEW {{db}}.mv__flowable__act_hi_taskinst ON CLUSTER '{{cluster}}' TO {{db}}.fact_process_task AS
SELECT toUUIDOrZero(tenant_id_) AS tenant_id, id_ AS id, nullIf(proc_inst_id_, '') AS process_instance_id,
       mission_flowable_def_key(proc_def_id_) AS process_key, task_def_key_ AS task_def_key,
       nullIf(name_, '') AS name, nullIf(state_, '') AS state, nullIf(category_, '') AS category,
       toInt32OrNull(priority_) AS priority,
       assumeNotNull(mission_cdc_micros(start_time_)) AS start_time, mission_cdc_micros(claim_time_) AS claim_time,
       mission_cdc_micros(in_progress_time_) AS in_progress_time, mission_cdc_micros(end_time_) AS end_time,
       mission_cdc_micros(due_date_) AS due_date, toInt64OrNull(duration_) AS duration_ms,
       toUInt64(__lsn) AS _version, toUInt8(0) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_flowable__act_hi_taskinst WHERE length(_error) = 0 AND __op != 'd';

CREATE MATERIALIZED VIEW {{db}}.mv__flowable__act_hi_actinst ON CLUSTER '{{cluster}}' TO {{db}}.fact_process_activity AS
SELECT toUUIDOrZero(tenant_id_) AS tenant_id, id_ AS id, proc_inst_id_ AS process_instance_id,
       mission_flowable_def_key(proc_def_id_) AS process_key, act_id_ AS activity_id, act_type_ AS activity_type,
       nullIf(act_name_, '') AS activity_name, nullIf(task_id_, '') AS task_id,
       nullIf(call_proc_inst_id_, '') AS call_process_instance_id,
       toInt32OrNull(transaction_order_) AS transaction_order,
       assumeNotNull(mission_cdc_micros(start_time_)) AS start_time, mission_cdc_micros(end_time_) AS end_time,
       toInt64OrNull(duration_) AS duration_ms,
       toUInt64(__lsn) AS _version, toUInt8(0) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_flowable__act_hi_actinst WHERE length(_error) = 0 AND __op != 'd';

CREATE MATERIALIZED VIEW {{db}}.mv_err__flowable__act_hi_procinst ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_flowable__act_hi_procinst
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__flowable__act_hi_taskinst ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_flowable__act_hi_taskinst
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__flowable__act_hi_actinst ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_flowable__act_hi_actinst
WHERE length(_error) > 0;
