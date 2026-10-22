-- DV-15-01 · SVC-15/ADR-14 · TL-03 "Đường đi dữ liệu": bảng Kafka engine đọc topic cdc.<db>.<schema>.<table>
-- do connector Debezium phân tích (DV-15-02) ghi. Hợp đồng bản ghi mà connector phải giữ (suy luận, ghi ở
-- analytics/clickhouse/README.md): JsonConverter không kèm lược đồ; ExtractNewRecordState với
-- add.fields=op,lsn,source.ts_ms và delete.handling.mode=rewrite (sinh __op, __lsn, __source_ts_ms, __deleted),
-- drop.tombstones=true; decimal.handling.mode=string; bảng nguồn REPLICA IDENTITY FULL để bản ghi xoá mang đủ
-- cột khoá sắp xếp. Mọi cột đọc dạng String; cột có COMMENT 'gia_danh' phải đến dưới dạng băm SHA-256 có muối
-- theo tenant (column.mask.hash), materialized view đổi sang FixedString(32).
-- Kafka engine chỉ bắt đầu đọc khi có materialized view gắn vào, nên tạo bảng ở đây chưa tiêu thụ offset nào;
-- view nạp dữ liệu và view bản ghi lỗi nằm ở V006.
-- Chỉ liệt kê cột cần cho phân tích; trường thừa trong bản ghi bị bỏ qua (input_format_skip_unknown_fields).

CREATE TABLE {{db}}.src_task__task ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `code` String,
  `task_group` String,
  `is_key_task` String,
  `source` String,
  `plan_item_id` String,
  `program_id` String,
  `key_result_id` String,
  `parent_task_id` String,
  `assigner_id` String COMMENT 'gia_danh',
  `assigned_at` String,
  `start_date` String,
  `due_date` String,
  `extended_due_date` String,
  `planned_quantity` String,
  `done_quantity` String,
  `difficulty` String,
  `standard_score` String,
  `progress_pct` String,
  `status` String,
  `is_overdue` String,
  `is_restricted` String,
  `delay_risk` String,
  `completed_at` String,
  `created_at` String,
  `updated_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.task.task',
         kafka_group_name = '{{kafka_group}}.task.task',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_task__task_assignee ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_bucket` String,
  `task_id` String,
  `assignee_type` String,
  `person_id` String COMMENT 'gia_danh',
  `org_unit_id` String,
  `role` String,
  `accepted_at` String,
  `removed_at` String,
  `valid_from` String,
  `valid_to` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.task.task_assignee',
         kafka_group_name = '{{kafka_group}}.task.task_assignee',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_task__task_progress ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_bucket` String,
  `task_id` String,
  `created_by` String COMMENT 'gia_danh',
  `reported_at` String,
  `progress_pct` String,
  `done_quantity` String,
  `channel` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.task.task_progress',
         kafka_group_name = '{{kafka_group}}.task.task_progress',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_product__evidence ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `deliverable_id` String,
  `task_id` String,
  `created_by` String COMMENT 'gia_danh',
  `current_status` String,
  `source_type` String,
  `created_at` String,
  `updated_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.product.evidence',
         kafka_group_name = '{{kafka_group}}.product.evidence',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_okr__objective ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `owner_type` String,
  `owner_unit_id` String,
  `owner_person_id` String COMMENT 'gia_danh',
  `parent_objective_id` String,
  `tier` String,
  `status` String,
  `progress` String,
  `approved_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.okr.objective',
         kafka_group_name = '{{kafka_group}}.okr.objective',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_okr__key_result ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `objective_id` String,
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

CREATE TABLE {{db}}.src_okr__kr_checkin ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `key_result_id` String,
  `created_by` String COMMENT 'gia_danh',
  `checkin_at` String,
  `value` String,
  `confidence` String,
  `channel` String,
  `status` String,
  `verified_by` String COMMENT 'gia_danh',
  `verified_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.okr.kr_checkin',
         kafka_group_name = '{{kafka_group}}.okr.kr_checkin',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_appraisal__appraisal_profile ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `person_id` String COMMENT 'gia_danh',
  `org_unit_id` String,
  `job_title_id` String,
  `subject_group_id` String,
  `flow` String,
  `is_head` String,
  `is_leadership` String,
  `status` String,
  `final_rating_code` String,
  `submitted_at` String,
  `decided_at` String,
  `locked_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.appraisal.appraisal_profile',
         kafka_group_name = '{{kafka_group}}.appraisal.appraisal_profile',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_appraisal__score_snapshot ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `profile_id` String,
  `flow` String,
  `snapshot_kind` String,
  `policy_pack_version_id` String,
  `policy_version` String,
  `total_score` String,
  `regular_score` String,
  `key_task_score` String,
  `general_criteria_score` String,
  `kpi_score` String,
  `exceed_ratio` String,
  `objective_ratio` String,
  `general_criteria_level` String,
  `auto_rating_code` String,
  `superseded_by_id` String,
  `computed_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.appraisal.score_snapshot',
         kafka_group_name = '{{kafka_group}}.appraisal.score_snapshot',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_appraisal__rating_proposal ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `period_id` String,
  `period_bucket` String,
  `profile_id` String,
  `level_kind` String,
  `rating_code` String,
  `score_snapshot_id` String,
  `actor_id` String COMMENT 'gia_danh',
  `actor_role` String,
  `is_current` String,
  `differs_from_prev` String,
  `explanation_required` String,
  `is_bulk_decision` String,
  `signed_at` String,
  `proposed_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.appraisal.rating_proposal',
         kafka_group_name = '{{kafka_group}}.appraisal.rating_proposal',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_org_replica__org_unit_v ON CLUSTER '{{cluster}}'
(
  `tenant_id` String,
  `id` String,
  `valid_from` String,
  `valid_to` String,
  `code` String,
  `name` String,
  `unit_type` String,
  `party_parent_id` String,
  `admin_parent_id` String,
  `subject_group_id` String,
  `source_version` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.org_replica.org_unit_v',
         kafka_group_name = '{{kafka_group}}.org_replica.org_unit_v',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_org_replica__person_v ON CLUSTER '{{cluster}}'
(
  `tenant_id` String,
  `id` String COMMENT 'gia_danh',
  `is_party_member` String,
  `employment_status` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.org_replica.person_v',
         kafka_group_name = '{{kafka_group}}.org_replica.person_v',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_org_replica__assignment_v ON CLUSTER '{{cluster}}'
(
  `tenant_id` String,
  `id` String,
  `valid_from` String,
  `valid_to` String,
  `person_id` String COMMENT 'gia_danh',
  `org_unit_id` String,
  `job_title_id` String,
  `kind` String,
  `branch` String,
  `is_head` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.org_replica.assignment_v',
         kafka_group_name = '{{kafka_group}}.org_replica.assignment_v',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_org_replica__unit_closure_v ON CLUSTER '{{cluster}}'
(
  `tenant_id` String,
  `tree` String,
  `ancestor_id` String,
  `descendant_id` String,
  `depth` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.org_replica.unit_closure_v',
         kafka_group_name = '{{kafka_group}}.org_replica.unit_closure_v',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_config__period ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `code` String,
  `name` String,
  `kind` String,
  `flow` String,
  `parent_period_id` String,
  `start_date` String,
  `end_date` String,
  `period_bucket` String,
  `status` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.config.period',
         kafka_group_name = '{{kafka_group}}.config.period',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_policy__policy_pack_version ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `policy_pack_id` String,
  `version` String,
  `status` String,
  `effective_from` String,
  `published_at` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_core}}.policy.policy_pack_version',
         kafka_group_name = '{{kafka_group}}.policy.policy_pack_version',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;

CREATE TABLE {{db}}.src_org__subject_group ON CLUSTER '{{cluster}}'
(
  `id` String,
  `tenant_id` String,
  `code` String,
  `name` String,
  `flow` String,
  `valid_from` String,
  `valid_to` String,
  `__op` String,
  `__lsn` String,
  `__source_ts_ms` String,
  `__deleted` String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '{{kafka_brokers}}',
         kafka_topic_list = '{{topic_org}}.org.subject_group',
         kafka_group_name = '{{kafka_group}}.org.subject_group',
         kafka_format = 'JSONEachRow',
         kafka_num_consumers = {{kafka_consumers}},
         kafka_handle_error_mode = 'stream',
         kafka_flush_interval_ms = {{kafka_flush_ms}},
         input_format_skip_unknown_fields = 1;
