-- =============================================================================
-- V2__docgen_tem_phien_ban.sql
-- DV-11-04 · SVC-11 · QT-11 bước 10, BR-11-03 (tem phiên bản Policy Pack và mẫu biểu trên mọi tệp chính thức).
--
-- Cột bổ sung (suy luận, TL-03 "Schema docgen" chưa có): worker cần mã gói và phiên bản Policy Pack gắn với kỳ để
-- đóng tem vào tệp, mà các giá trị này chỉ có trong lệnh sinh của MOD-06. Tác vụ tạo trước DV-11-04 để trống.
-- =============================================================================

ALTER TABLE docgen.render_job
  ADD COLUMN package_id          uuid,
  ADD COLUMN policy_pack_code    varchar(64),
  ADD COLUMN policy_pack_version varchar(32);

CREATE INDEX ix_render_job__tenant_package ON docgen.render_job (tenant_id, package_id)
  WHERE package_id IS NOT NULL;

COMMENT ON COLUMN docgen.render_job.package_id IS '{"cls": "C1", "pii": false, "desc": "Gói hồ sơ (dossier.dossier_package) của lệnh sinh"}';
COMMENT ON COLUMN docgen.render_job.policy_pack_code IS '{"cls": "C1", "pii": false, "desc": "Mã Policy Pack gắn với kỳ, đóng tem vào tệp"}';
COMMENT ON COLUMN docgen.render_job.policy_pack_version IS '{"cls": "C1", "pii": false, "desc": "Phiên bản Policy Pack gắn với kỳ, đóng tem vào tệp"}';
