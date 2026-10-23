-- DL-12 · TL-03 "Đối soát và kiểm tra nhất quán": mở rộng org_replica.reconcile_run (expand-only) cho khung đối
-- soát dùng chung. job phân biệt các job R1–R7 khi cùng ghi một bảng; detail giữ phạm vi, băm mong đợi theo
-- bucket, bucket lệch và kế hoạch phát lại (chỉ mã và số, không dữ liệu C2, C3). Hai cột không có trong TL-03
-- (wiki trang 14, mục DL-12).
ALTER TABLE ${flyway:defaultSchema}.reconcile_run
    ADD COLUMN job varchar(32) NOT NULL DEFAULT 'R1_BAN_SAO_TO_CHUC',
    ADD COLUMN detail jsonb NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX ix_reconcile_run__tenant_job_status
    ON ${flyway:defaultSchema}.reconcile_run (tenant_id, job, status, started_at);

COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.job IS '{"cls":"C1","desc":"Mã job đối soát R1..R7"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.detail IS '{"cls":"C1","desc":"Chi tiết đối soát: phạm vi, bucket lệch, kế hoạch phát lại"}';
