-- CORE-02-20 · Điểm nghẽn của nhiệm vụ (QT-06, SCR-0504, SCR-0601, tasks.yaml TaskBlocker).
--
-- Bổ sung (wiki trang 14): hợp đồng TaskBlocker bắt buộc code (dạng BN-<đuôi mã nhiệm vụ>-NN) và
-- causeClaimed (KHACH_QUAN, CHU_QUAN) mà TL-03 chưa có cột. Dòng cũ (nếu có) để NULL; dịch vụ luôn ghi
-- hai cột khi báo mới. Cột C1, không chứa dữ liệu cá nhân.

ALTER TABLE ${flyway:defaultSchema}.task_blocker
    ADD COLUMN code varchar(32),
    ADD COLUMN cause_claimed varchar(12),
    ADD CONSTRAINT ck_task_blocker__cause_claimed CHECK (cause_claimed IS NULL OR cause_claimed IN ('KHACH_QUAN', 'CHU_QUAN'));

COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.code IS '{"cls":"C1","desc":"Mã điểm nghẽn BN-<đuôi mã nhiệm vụ>-NN (bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_blocker.cause_claimed IS '{"cls":"C1","desc":"Người báo tự nhận nguyên nhân khách quan hay chủ quan (bổ sung)"}';
