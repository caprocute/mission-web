-- DV-15-01 · SVC-15/ADR-14: materialized view chuyển bản ghi Debezium đã làm phẳng sang bảng chiều, bảng sự kiện
-- và bảng tra cứu. _version = __lsn (tăng đơn điệu theo từng thay đổi trong một CSDL), _is_deleted từ __op/__deleted,
-- _source_ts từ __source_ts_ms để đo độ trễ CDC (_ingested_at − _source_ts).
-- Bản ghi giải mã lỗi đã được mv_err__* chuyển sang cdc_error nên ở đây chỉ nhận dòng không lỗi.

CREATE MATERIALIZED VIEW {{db}}.mv__org_replica__org_unit_v ON CLUSTER '{{cluster}}' TO {{db}}.dim_org_unit AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       code, name, unit_type, toUUIDOrNull(party_parent_id) AS party_parent_id,
       toUUIDOrNull(admin_parent_id) AS admin_parent_id, toUUIDOrNull(subject_group_id) AS subject_group_id,
       toInt64OrZero(source_version) AS source_version,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_org_replica__org_unit_v WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__org_replica__person_v ON CLUSTER '{{cluster}}' TO {{db}}.dim_person AS
SELECT toUUID(tenant_id) AS tenant_id, assumeNotNull(mission_cdc_key(id)) AS person_key,
       mission_cdc_bool(is_party_member) AS is_party_member, employment_status,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_org_replica__person_v WHERE length(_error) = 0 AND mission_cdc_key(id) IS NOT NULL;

CREATE MATERIALIZED VIEW {{db}}.mv__config__period ON CLUSTER '{{cluster}}' TO {{db}}.dim_period AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, code, name, kind, flow,
       toUUIDOrNull(parent_period_id) AS parent_period_id,
       assumeNotNull(mission_cdc_date(start_date)) AS start_date, assumeNotNull(mission_cdc_date(end_date)) AS end_date,
       toInt32(period_bucket) AS period_bucket, status,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_config__period WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__org__subject_group ON CLUSTER '{{cluster}}' TO {{db}}.dim_subject_group AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, code, name, flow,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_org__subject_group WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__policy__policy_pack_version ON CLUSTER '{{cluster}}' TO {{db}}.dim_policy_version AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, toUUID(policy_pack_id) AS policy_pack_id, version, status,
       mission_cdc_date(effective_from) AS effective_from, mission_cdc_ts(published_at) AS published_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_policy__policy_pack_version WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__task__task ON CLUSTER '{{cluster}}' TO {{db}}.fact_task AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(period_id) AS period_id, code, task_group, mission_cdc_bool(is_key_task) AS is_key_task, source,
       toUUIDOrNull(plan_item_id) AS plan_item_id, toUUIDOrNull(program_id) AS program_id,
       toUUIDOrNull(key_result_id) AS key_result_id, toUUIDOrNull(parent_task_id) AS parent_task_id,
       mission_cdc_key(assigner_id) AS assigner_key,
       mission_cdc_date(start_date) AS start_date, assumeNotNull(mission_cdc_date(due_date)) AS due_date,
       mission_cdc_date(extended_due_date) AS extended_due_date,
       toDecimal64OrZero(planned_quantity, 4) AS planned_quantity, toDecimal64OrZero(done_quantity, 4) AS done_quantity,
       toInt16OrNull(difficulty) AS difficulty, toDecimal64OrNull(standard_score, 3) AS standard_score,
       toDecimal64OrZero(progress_pct, 4) AS progress_pct, status,
       mission_cdc_bool(is_overdue) AS is_overdue, mission_cdc_bool(is_restricted) AS is_restricted,
       nullIf(delay_risk, '') AS delay_risk,
       mission_cdc_ts(assigned_at) AS assigned_at, mission_cdc_ts(completed_at) AS completed_at,
       assumeNotNull(mission_cdc_ts(created_at)) AS created_at, assumeNotNull(mission_cdc_ts(updated_at)) AS updated_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_task__task WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__task__task_assignee ON CLUSTER '{{cluster}}' TO {{db}}.fact_task_assignee AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(task_id) AS task_id, assignee_type, mission_cdc_key(person_id) AS person_key,
       toUUIDOrNull(org_unit_id) AS org_unit_id, role,
       mission_cdc_ts(accepted_at) AS accepted_at, mission_cdc_ts(removed_at) AS removed_at,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_task__task_assignee WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__task__task_progress ON CLUSTER '{{cluster}}' TO {{db}}.fact_task_progress AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(task_id) AS task_id, assumeNotNull(mission_cdc_key(created_by)) AS person_key,
       dictGetOrDefault('{{db}}.dict_task_unit', 'org_unit_id', tuple(toUUID(task_id)),
                        toUUID('00000000-0000-0000-0000-000000000000')) AS org_unit_id,
       toDecimal64OrZero(progress_pct, 4) AS progress_pct, toDecimal64OrNull(done_quantity, 4) AS done_quantity,
       channel, assumeNotNull(mission_cdc_ts(reported_at)) AS reported_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_task__task_progress WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__product__evidence ON CLUSTER '{{cluster}}' TO {{db}}.fact_evidence AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(period_id) AS period_id, toUUID(deliverable_id) AS deliverable_id, toUUID(task_id) AS task_id,
       dictGetOrDefault('{{db}}.dict_task_unit', 'org_unit_id', tuple(toUUID(task_id)),
                        toUUID('00000000-0000-0000-0000-000000000000')) AS org_unit_id,
       assumeNotNull(mission_cdc_key(created_by)) AS person_key, current_status, source_type,
       assumeNotNull(mission_cdc_ts(created_at)) AS created_at, assumeNotNull(mission_cdc_ts(updated_at)) AS updated_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_product__evidence WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__okr__kr_checkin ON CLUSTER '{{cluster}}' TO {{db}}.fact_okr_checkin AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(period_id) AS period_id, toUUID(key_result_id) AS key_result_id,
       dictGetOrNull('{{db}}.dict_kr_unit', 'objective_id', tuple(toUUID(key_result_id))) AS objective_id,
       dictGetOrDefault('{{db}}.dict_kr_unit', 'org_unit_id', tuple(toUUID(key_result_id)),
                        toUUID('00000000-0000-0000-0000-000000000000')) AS org_unit_id,
       assumeNotNull(mission_cdc_key(created_by)) AS person_key,
       assumeNotNull(mission_cdc_ts(checkin_at)) AS checkin_at, toDecimal64OrZero(value, 4) AS value,
       confidence, channel, status, mission_cdc_key(verified_by) AS verifier_key,
       mission_cdc_ts(verified_at) AS verified_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_okr__kr_checkin WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__appraisal__score_snapshot ON CLUSTER '{{cluster}}' TO {{db}}.fact_score AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(period_id) AS period_id, toUUID(profile_id) AS profile_id,
       toFixedString(dictGetOrDefault('{{db}}.dict_profile', 'person_key', tuple(toUUID(profile_id)), ''), 32)
         AS person_key,
       dictGetOrDefault('{{db}}.dict_profile', 'org_unit_id', tuple(toUUID(profile_id)),
                        toUUID('00000000-0000-0000-0000-000000000000')) AS org_unit_id,
       dictGetOrNull('{{db}}.dict_profile', 'subject_group_id', tuple(toUUID(profile_id))) AS subject_group_id,
       flow, snapshot_kind, toUUID(policy_pack_version_id) AS policy_pack_version_id, policy_version,
       toDecimal64OrZero(total_score, 3) AS total_score, toDecimal64OrNull(regular_score, 3) AS regular_score,
       toDecimal64OrNull(key_task_score, 3) AS key_task_score,
       toDecimal64OrNull(general_criteria_score, 3) AS general_criteria_score,
       toDecimal64OrNull(kpi_score, 3) AS kpi_score, toDecimal64OrNull(exceed_ratio, 4) AS exceed_ratio,
       toDecimal64OrNull(objective_ratio, 4) AS objective_ratio,
       nullIf(general_criteria_level, '') AS general_criteria_level, auto_rating_code,
       toUInt8(superseded_by_id = '') AS is_current, assumeNotNull(mission_cdc_ts(computed_at)) AS computed_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_appraisal__score_snapshot WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__appraisal__rating_proposal ON CLUSTER '{{cluster}}' TO {{db}}.fact_rating AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(period_id) AS period_id, toUUID(profile_id) AS profile_id,
       toFixedString(dictGetOrDefault('{{db}}.dict_profile', 'person_key', tuple(toUUID(profile_id)), ''), 32)
         AS person_key,
       dictGetOrDefault('{{db}}.dict_profile', 'org_unit_id', tuple(toUUID(profile_id)),
                        toUUID('00000000-0000-0000-0000-000000000000')) AS org_unit_id,
       dictGetOrNull('{{db}}.dict_profile', 'subject_group_id', tuple(toUUID(profile_id))) AS subject_group_id,
       dictGetOrDefault('{{db}}.dict_profile', 'flow', tuple(toUUID(profile_id)), '') AS flow,
       level_kind, rating_code, toUUID(score_snapshot_id) AS score_snapshot_id,
       assumeNotNull(mission_cdc_key(actor_id)) AS actor_key, actor_role,
       mission_cdc_bool(is_current) AS is_current, mission_cdc_bool(differs_from_prev) AS differs_from_prev,
       mission_cdc_bool(explanation_required) AS explanation_required,
       mission_cdc_bool(is_bulk_decision) AS is_bulk_decision, toUInt8(signed_at != '') AS is_signed,
       assumeNotNull(mission_cdc_ts(proposed_at)) AS proposed_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_appraisal__rating_proposal WHERE length(_error) = 0;

-- Bảng tra cứu nội bộ.
CREATE MATERIALIZED VIEW {{db}}.mv__org_replica__assignment_v ON CLUSTER '{{cluster}}' TO {{db}}.lk_assignment AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       assumeNotNull(mission_cdc_key(person_id)) AS person_key, toUUID(org_unit_id) AS org_unit_id,
       toUUID(job_title_id) AS job_title_id, kind, branch, mission_cdc_bool(is_head) AS is_head,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted
FROM {{db}}.src_org_replica__assignment_v WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__okr__objective ON CLUSTER '{{cluster}}' TO {{db}}.lk_objective AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, toUUID(owner_unit_id) AS owner_unit_id,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted
FROM {{db}}.src_okr__objective WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__okr__key_result ON CLUSTER '{{cluster}}' TO {{db}}.lk_key_result AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, toUUID(objective_id) AS objective_id,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted
FROM {{db}}.src_okr__key_result WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW {{db}}.mv__appraisal__appraisal_profile ON CLUSTER '{{cluster}}' TO {{db}}.lk_appraisal_profile AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, toInt32(period_bucket) AS period_bucket,
       assumeNotNull(mission_cdc_key(person_id)) AS person_key, toUUID(org_unit_id) AS org_unit_id,
       toUUIDOrNull(subject_group_id) AS subject_group_id, flow,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted
FROM {{db}}.src_appraisal__appraisal_profile WHERE length(_error) = 0;

-- Bản ghi giải mã lỗi của từng nguồn (tạo sau view nạp dữ liệu để không dòng hợp lệ nào bị đọc mà không nạp).
CREATE MATERIALIZED VIEW {{db}}.mv_err__task__task ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_task__task
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__task__task_assignee ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_task__task_assignee
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__task__task_progress ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_task__task_progress
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__product__evidence ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_product__evidence
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__okr__objective ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_okr__objective
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__okr__key_result ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_okr__key_result
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__okr__kr_checkin ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_okr__kr_checkin
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__appraisal__appraisal_profile ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_appraisal__appraisal_profile
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__appraisal__score_snapshot ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_appraisal__score_snapshot
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__appraisal__rating_proposal ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_appraisal__rating_proposal
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__org_replica__org_unit_v ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_org_replica__org_unit_v
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__org_replica__person_v ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_org_replica__person_v
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__org_replica__assignment_v ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_org_replica__assignment_v
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__config__period ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_config__period
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__policy__policy_pack_version ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_policy__policy_pack_version
WHERE length(_error) > 0;

CREATE MATERIALIZED VIEW {{db}}.mv_err__org__subject_group ON CLUSTER '{{cluster}}'
TO {{db}}.cdc_error AS
SELECT _topic AS topic, _partition AS partition, _offset AS offset, _error AS error,
       toUInt32(length(_raw_message)) AS raw_size
FROM {{db}}.src_org__subject_group
WHERE length(_error) > 0;
