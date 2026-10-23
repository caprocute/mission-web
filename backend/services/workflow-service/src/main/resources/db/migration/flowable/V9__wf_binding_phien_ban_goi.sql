-- =============================================================================
-- CORE-05-11 / DV-06-08 · TL-03 bảng điểm chờ hiện thực (chốt 23/10/2026): flowable.wf_binding có cột
-- policy_pack_version_id và cột này nằm trong khoá duy nhất (tenant_id, policy_pack_version_id,
-- process_kind, subject_group_id, flow, period_type). Dòng có sẵn lấy phiên bản từ scope_json
-- (kích hoạt của DV-06-08 đã ghi khoá policyPackVersionId); dòng nạp tay trước đó để NULL.
-- V8 dành cho DV-06-11.
-- =============================================================================

ALTER TABLE flowable.wf_binding ADD COLUMN policy_pack_version_id uuid;
COMMENT ON COLUMN flowable.wf_binding.policy_pack_version_id IS
  '{"cls":"C1","desc":"Phiên bản Policy Pack đã phát hành dòng tham số này; NULL với dòng nạp ngoài gói"}';

UPDATE flowable.wf_binding
   SET policy_pack_version_id = (scope_json ->> 'policyPackVersionId')::uuid
 WHERE scope_json ? 'policyPackVersionId'
   AND scope_json ->> 'policyPackVersionId' ~ '^[0-9a-fA-F-]{36}$';

ALTER TABLE flowable.wf_binding DROP CONSTRAINT uq_wf_binding__scope;
ALTER TABLE flowable.wf_binding ADD CONSTRAINT uq_wf_binding__scope UNIQUE NULLS NOT DISTINCT
  (tenant_id, policy_pack_version_id, process_kind, subject_group_id, flow, period_type);
