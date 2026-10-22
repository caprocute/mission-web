-- Sinh bởi analytics/cdc/cdc_config_gen.py (DV-15-02). Không sửa tay; chạy lại công cụ.
-- Publication pub_analytics của CSDL tenant_data cho connector phân tích (slot dbz_cdc_tenant_data). Chạy bằng vai trò quản trị
-- CSDL, chạy lại được. publish_via_partition_root để thay đổi của phân vùng mang tên bảng cha (topic theo bảng cha).
-- REPLICA IDENTITY FULL để bản ghi xoá mang đủ cột khoá sắp xếp của bảng ClickHouse; đặt cho bảng cha và mọi phân
-- vùng hiện có; phân vùng tạo sau phải chạy lại tệp này (hoặc bảng mẫu pg_partman đặt sẵn).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'pub_analytics') THEN
    CREATE PUBLICATION pub_analytics WITH (publish = 'insert, update, delete', publish_via_partition_root = true);
  END IF;
END $$;
ALTER PUBLICATION pub_analytics SET (publish = 'insert, update, delete', publish_via_partition_root = true);
ALTER PUBLICATION pub_analytics SET TABLE task.task, task.task_assignee, task.task_progress, product.evidence, okr.objective, okr.key_result, okr.kr_checkin, appraisal.appraisal_profile, appraisal.score_snapshot, appraisal.rating_proposal, org_replica.org_unit_v, org_replica.person_v, org_replica.assignment_v, config.period, policy.policy_pack_version;
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT root.oid AS rel FROM unnest(ARRAY['task.task', 'task.task_assignee', 'task.task_progress', 'product.evidence', 'okr.objective', 'okr.key_result', 'okr.kr_checkin', 'appraisal.appraisal_profile', 'appraisal.score_snapshot', 'appraisal.rating_proposal', 'org_replica.org_unit_v', 'org_replica.person_v', 'org_replica.assignment_v', 'config.period', 'policy.policy_pack_version']::regclass[]) AS root(oid)
           UNION
           SELECT t.relid::regclass FROM unnest(ARRAY['task.task', 'task.task_assignee', 'task.task_progress', 'product.evidence', 'okr.objective', 'okr.key_result', 'okr.kr_checkin', 'appraisal.appraisal_profile', 'appraisal.score_snapshot', 'appraisal.rating_proposal', 'org_replica.org_unit_v', 'org_replica.person_v', 'org_replica.assignment_v', 'config.period', 'policy.policy_pack_version']::regclass[]) AS root(oid),
                  LATERAL pg_partition_tree(root.oid) AS t
  LOOP
    EXECUTE format('ALTER TABLE %s REPLICA IDENTITY FULL', r.rel);
  END LOOP;
END $$;
