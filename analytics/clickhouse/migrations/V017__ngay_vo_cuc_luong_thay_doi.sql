-- DV-15-01 · TL-03 "CDC phân tích sang ClickHouse": bổ sung V016 cho ngày 'infinity' của bản ghi đọc từ luồng thay đổi.
-- V016 chỉ nhận -622191234 và -622191233, là giá trị Debezium gửi khi chụp ảnh ban đầu (đọc qua JDBC). Bản ghi đến
-- sau đó qua luồng thay đổi (pgoutput) mang mốc vô cực khác: -2147472692 (đo trên dev 28/10/2026 với
-- org_replica.org_unit_v.valid_to của đơn vị vừa cập nhật). V016 hiểu số này là một ngày ngoài miền Date32, nên mọi
-- đơn vị, phân công, kỳ được sửa sau ảnh chụp đều mất dòng valid_to = 2299-12-31, dim_org_scope chỉ còn đơn vị chưa
-- từng đổi và analytics-api trả 403 "Chưa có dữ liệu cây tổ chức của đơn vị công tác" (HT-39).
-- Không liệt kê thêm từng mốc: mọi số ngày nằm ngoài miền Date32 (trước 1900-01-01, tức nhỏ hơn -25567, hoặc từ
-- 120000 trở lên) là mốc vô cực do ép int32 và được coi là ngày lớn nhất của Date32. Cột ngày trong kho chỉ dùng mốc
-- vô cực dương ('infinity' cho valid_to, end_date); '-infinity' không dùng ở nguồn (suy luận từ TL-03, các cột
-- valid_from đều có giá trị thật). Mọi trường hợp khác giữ như V016.
-- Như V016, ClickHouse chép thân hàm vào materialized view lúc tạo, nên tám view nạp có gọi mission_cdc_date được tạo
-- lại nguyên văn. Bản ghi đã nạp sai cần connector chụp lại (infra/dev/scripts/noi-cdc-phan-tich.py --chup-lai ở dev).
CREATE OR REPLACE FUNCTION mission_cdc_date ON CLUSTER '{{cluster}}' AS (x) ->
  if(match(x, '^-?[0-9]+$'),
     if(toInt64(x) >= 120000 OR toInt64(x) < -25567,
        toDate32('2299-12-31'), addDays(toDate32('1970-01-01'), toInt32(x))),
     if(lower(x) = 'infinity', toDate32('2299-12-31'), toDate32OrNull(x)));

DROP VIEW IF EXISTS {{db}}.mv__config__period ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__config__period ON CLUSTER '{{cluster}}' TO {{db}}.dim_period AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, code, name, kind, flow,
       toUUIDOrNull(parent_period_id) AS parent_period_id,
       assumeNotNull(mission_cdc_date(start_date)) AS start_date, assumeNotNull(mission_cdc_date(end_date)) AS end_date,
       toInt32(period_bucket) AS period_bucket, status,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_config__period WHERE length(_error) = 0;

DROP VIEW IF EXISTS {{db}}.mv__org__subject_group ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__org__subject_group ON CLUSTER '{{cluster}}' TO {{db}}.dim_subject_group AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, code, name, flow,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_org__subject_group WHERE length(_error) = 0;

DROP VIEW IF EXISTS {{db}}.mv__org_replica__assignment_v ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__org_replica__assignment_v ON CLUSTER '{{cluster}}' TO {{db}}.lk_assignment AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       assumeNotNull(mission_cdc_key(person_id)) AS person_key, toUUID(org_unit_id) AS org_unit_id,
       toUUID(job_title_id) AS job_title_id, kind, branch, mission_cdc_bool(is_head) AS is_head,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted
FROM {{db}}.src_org_replica__assignment_v WHERE length(_error) = 0;

DROP VIEW IF EXISTS {{db}}.mv__org_replica__org_unit_v ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__org_replica__org_unit_v ON CLUSTER '{{cluster}}' TO {{db}}.dim_org_unit AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       code, name, unit_type, toUUIDOrNull(party_parent_id) AS party_parent_id,
       toUUIDOrNull(admin_parent_id) AS admin_parent_id, toUUIDOrNull(subject_group_id) AS subject_group_id,
       toInt64OrZero(source_version) AS source_version,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_org_replica__org_unit_v WHERE length(_error) = 0;

DROP VIEW IF EXISTS {{db}}.mv__policy__policy_pack_version ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__policy__policy_pack_version ON CLUSTER '{{cluster}}' TO {{db}}.dim_policy_version AS
SELECT toUUID(tenant_id) AS tenant_id, toUUID(id) AS id, toUUID(policy_pack_id) AS policy_pack_id, version, status,
       mission_cdc_date(effective_from) AS effective_from, mission_cdc_ts(published_at) AS published_at,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_policy__policy_pack_version WHERE length(_error) = 0;

DROP VIEW IF EXISTS {{db}}.mv__task__task ON CLUSTER '{{cluster}}' SYNC;

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

DROP VIEW IF EXISTS {{db}}.mv__task__task_assignee ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__task__task_assignee ON CLUSTER '{{cluster}}' TO {{db}}.fact_task_assignee AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUID(task_id) AS task_id, assignee_type, mission_cdc_key(person_id) AS person_key,
       toUUIDOrNull(org_unit_id) AS org_unit_id,
       multiIf(org_unit_id IS NOT NULL, org_unit_id, mission_cdc_key(person_id) IS NULL, NULL,
               dictGetOrNull('{{db}}.dict_person_unit', 'org_unit_id',
                             tuple(toUUID(tenant_id), toString(assumeNotNull(mission_cdc_key(person_id))))))
         AS resolved_unit_id,
       role, mission_cdc_ts(accepted_at) AS accepted_at, mission_cdc_ts(removed_at) AS removed_at,
       assumeNotNull(mission_cdc_date(valid_from)) AS valid_from, assumeNotNull(mission_cdc_date(valid_to)) AS valid_to,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_task__task_assignee WHERE length(_error) = 0;

DROP VIEW IF EXISTS {{db}}.mv__okr__key_result_fact ON CLUSTER '{{cluster}}' SYNC;

CREATE MATERIALIZED VIEW {{db}}.mv__okr__key_result_fact ON CLUSTER '{{cluster}}' TO {{db}}.fact_okr_key_result AS
SELECT toUUID(tenant_id) AS tenant_id, toInt32OrZero(period_bucket) AS period_bucket, toUUID(id) AS id,
       toUUIDOrZero(period_id) AS period_id, toUUID(objective_id) AS objective_id, metric_type, direction,
       toDecimal64OrNull(baseline_value, 4) AS baseline_value, toDecimal64OrZero(target_value, 4) AS target_value,
       toDecimal64OrZero(weight, 4) AS weight, ifNull(mission_cdc_date(due_date), toDate32('1970-01-01')) AS due_date,
       status,
       toUInt64(__lsn) AS _version, mission_cdc_deleted(__op, __deleted) AS _is_deleted,
       fromUnixTimestamp64Milli(toInt64(__source_ts_ms), 'Asia/Ho_Chi_Minh') AS _source_ts
FROM {{db}}.src_okr__key_result WHERE length(_error) = 0;
