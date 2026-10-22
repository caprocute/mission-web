-- DV-15-01 · SVC-15 · TL-03 "Phân quyền phía đọc": người dùng cuối không truy vấn ClickHouse; Cube áp
-- queryRewrite theo tenant và đơn vị từ JWT. Ở ClickHouse có thêm hai lớp:
--  1. Quyền theo cột: vai trò chỉ được SELECT các cột liệt kê dưới đây (danh sách cho phép); bảng tra cứu lk_*,
--     bảng nguồn src_*, cdc_error và cột kỹ thuật _version, _is_deleted, _source_ts, _ingested_at không cấp.
--  2. Row policy theo tenant_id cho tài khoản kỹ thuật của Cube ở cell pool: Cube đặt SQL_tenant_id cho từng truy
--     vấn; không đặt thì không thấy dòng nào (fail closed). Job phát hiện bất thường chạy trên mọi tenant của cell
--     nên có row policy cho phép toàn bộ, chỉ đọc.
-- Mật khẩu do bộ áp migration thế vào từ biến môi trường lúc chạy, không ghi trong repo.

CREATE ROLE IF NOT EXISTS {{db}}_cube ON CLUSTER '{{cluster}}';
CREATE ROLE IF NOT EXISTS {{db}}_anomaly ON CLUSTER '{{cluster}}';

CREATE SETTINGS PROFILE IF NOT EXISTS {{db}}_doc ON CLUSTER '{{cluster}}'
  SETTINGS readonly = 2, max_execution_time = 60, max_memory_usage = 8000000000;

CREATE USER IF NOT EXISTS {{db}}_cube ON CLUSTER '{{cluster}}'
  IDENTIFIED WITH sha256_password BY '{{cube_password}}'
  DEFAULT ROLE {{db}}_cube SETTINGS PROFILE '{{db}}_doc';
CREATE USER IF NOT EXISTS {{db}}_anomaly ON CLUSTER '{{cluster}}'
  IDENTIFIED WITH sha256_password BY '{{anomaly_password}}'
  DEFAULT ROLE {{db}}_anomaly SETTINGS PROFILE '{{db}}_doc', readonly = 1;

GRANT ON CLUSTER '{{cluster}}' {{db}}_cube TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' {{db}}_anomaly TO {{db}}_anomaly;

GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, valid_from, valid_to, code, name, unit_type, party_parent_id, admin_parent_id, subject_group_id, source_version)
  ON {{db}}.dim_org_unit TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, person_key, is_party_member, employment_status)
  ON {{db}}.dim_person TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, code, name, kind, flow, parent_period_id, start_date, end_date, period_bucket, status)
  ON {{db}}.dim_period TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, code, name, flow, valid_from, valid_to)
  ON {{db}}.dim_subject_group TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, policy_pack_id, version, status, effective_from, published_at)
  ON {{db}}.dim_policy_version TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, code, task_group, is_key_task, source, plan_item_id, program_id, key_result_id, parent_task_id, assigner_key, start_date, due_date, extended_due_date, planned_quantity, done_quantity, difficulty, standard_score, progress_pct, status, is_overdue, is_restricted, delay_risk, assigned_at, completed_at, created_at, updated_at)
  ON {{db}}.fact_task TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, task_id, assignee_type, person_key, org_unit_id, role, accepted_at, removed_at, valid_from, valid_to)
  ON {{db}}.fact_task_assignee TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, task_id, person_key, org_unit_id, progress_pct, done_quantity, channel, reported_at)
  ON {{db}}.fact_task_progress TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, deliverable_id, task_id, org_unit_id, person_key, current_status, source_type, created_at, updated_at)
  ON {{db}}.fact_evidence TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, profile_id, person_key, org_unit_id, subject_group_id, flow, snapshot_kind, policy_pack_version_id, policy_version, total_score, regular_score, key_task_score, general_criteria_score, kpi_score, exceed_ratio, objective_ratio, general_criteria_level, auto_rating_code, is_current, computed_at)
  ON {{db}}.fact_score TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, profile_id, person_key, org_unit_id, subject_group_id, flow, level_kind, rating_code, score_snapshot_id, actor_key, actor_role, is_current, differs_from_prev, explanation_required, is_bulk_decision, is_signed, proposed_at)
  ON {{db}}.fact_rating TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, key_result_id, objective_id, org_unit_id, person_key, checkin_at, value, confidence, channel, status, verifier_key, verified_at)
  ON {{db}}.fact_okr_checkin TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, period_id, province_unit_id, task_group, n_task, n_completed, n_overdue, n_key_task, avg_progress_pct, computed_at)
  ON {{db}}.agg_task_tinh TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, period_id, province_unit_id, subject_group_id, flow, n_person, avg_total_score, p50_total_score, computed_at)
  ON {{db}}.agg_score_tinh TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, period_id, province_unit_id, subject_group_id, flow, n_person, n_htxsnv, n_httnv, n_htnv, n_khtnv, computed_at)
  ON {{db}}.agg_rating_tinh TO {{db}}_cube;

GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, valid_from, valid_to, code, name, unit_type, party_parent_id, admin_parent_id, subject_group_id, source_version)
  ON {{db}}.dim_org_unit TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, person_key, is_party_member, employment_status)
  ON {{db}}.dim_person TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, code, name, kind, flow, parent_period_id, start_date, end_date, period_bucket, status)
  ON {{db}}.dim_period TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, code, name, flow, valid_from, valid_to)
  ON {{db}}.dim_subject_group TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, id, policy_pack_id, version, status, effective_from, published_at)
  ON {{db}}.dim_policy_version TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, code, task_group, is_key_task, source, plan_item_id, program_id, key_result_id, parent_task_id, assigner_key, start_date, due_date, extended_due_date, planned_quantity, done_quantity, difficulty, standard_score, progress_pct, status, is_overdue, is_restricted, delay_risk, assigned_at, completed_at, created_at, updated_at)
  ON {{db}}.fact_task TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, task_id, assignee_type, person_key, org_unit_id, role, accepted_at, removed_at, valid_from, valid_to)
  ON {{db}}.fact_task_assignee TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, task_id, person_key, org_unit_id, progress_pct, done_quantity, channel, reported_at)
  ON {{db}}.fact_task_progress TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, deliverable_id, task_id, org_unit_id, person_key, current_status, source_type, created_at, updated_at)
  ON {{db}}.fact_evidence TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, profile_id, person_key, org_unit_id, subject_group_id, flow, snapshot_kind, policy_pack_version_id, policy_version, total_score, regular_score, key_task_score, general_criteria_score, kpi_score, exceed_ratio, objective_ratio, general_criteria_level, auto_rating_code, is_current, computed_at)
  ON {{db}}.fact_score TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, profile_id, person_key, org_unit_id, subject_group_id, flow, level_kind, rating_code, score_snapshot_id, actor_key, actor_role, is_current, differs_from_prev, explanation_required, is_bulk_decision, is_signed, proposed_at)
  ON {{db}}.fact_rating TO {{db}}_anomaly;
GRANT ON CLUSTER '{{cluster}}' SELECT(tenant_id, period_bucket, id, period_id, key_result_id, objective_id, org_unit_id, person_key, checkin_at, value, confidence, channel, status, verifier_key, verified_at)
  ON {{db}}.fact_okr_checkin TO {{db}}_anomaly;

CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.dim_org_unit ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.dim_person ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.dim_period ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.dim_subject_group ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.dim_policy_version ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_task ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_task_assignee ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_task_progress ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_evidence ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_score ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_rating ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.fact_okr_checkin ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.agg_task_tinh ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.agg_score_tinh ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;
CREATE ROW POLICY IF NOT EXISTS rp_tenant ON {{db}}.agg_rating_tinh ON CLUSTER '{{cluster}}'
  USING tenant_id = toUUIDOrZero(getSettingOrDefault('SQL_tenant_id', '')) TO {{db}}_cube;

CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.dim_org_unit ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.dim_person ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.dim_period ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.dim_subject_group ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.dim_policy_version ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_task ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_task_assignee ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_task_progress ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_evidence ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_score ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_rating ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
CREATE ROW POLICY IF NOT EXISTS rp_anomaly ON {{db}}.fact_okr_checkin ON CLUSTER '{{cluster}}'
  USING 1 TO {{db}}_anomaly;
