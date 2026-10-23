-- DL-12 · TL-03 "Đối soát và kiểm tra nhất quán": bảng reconcile_run của schema đích, cùng mẫu với
-- org_replica.reconcile_run và các schema của mission-core (wiki trang 14 mục DL-12). Job R4 (tính lại mẫu
-- 0,5% kết quả đã chốt) ghi một dòng cho mỗi tenant mỗi tuần.
CREATE TABLE ${flyway:defaultSchema}.reconcile_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    job varchar(32) NOT NULL,
    entity_type varchar(16) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    buckets_total int,
    buckets_mismatch int,
    rows_repaired int,
    status varchar(12) NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_reconcile_run PRIMARY KEY (id),
    CONSTRAINT ck_reconcile_run__status CHECK (status IN ('DANG_CHAY', 'KHOP', 'DA_SUA', 'LOI'))
);
CREATE INDEX ix_reconcile_run__tenant_job_status
    ON ${flyway:defaultSchema}.reconcile_run (tenant_id, job, status, started_at);

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.reconcile_run');

COMMENT ON TABLE ${flyway:defaultSchema}.reconcile_run IS 'Kết quả đối soát (DL-12 ghi)';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.id IS '{"cls":"C1","desc":"Mã lần đối soát"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.job IS '{"cls":"C1","desc":"Mã job đối soát R1..R7"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.entity_type IS '{"cls":"C1","desc":"Loại dữ liệu đối soát"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.started_at IS '{"cls":"C1","desc":"Bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.finished_at IS '{"cls":"C1","desc":"Kết thúc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.buckets_total IS '{"cls":"C1","desc":"Số nhóm đem so"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.buckets_mismatch IS '{"cls":"C1","desc":"Số nhóm lệch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.rows_repaired IS '{"cls":"C1","desc":"Số dòng đã phát lại hoặc sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.status IS '{"cls":"C1","desc":"Trạng thái đối soát"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.detail IS '{"cls":"C1","desc":"Chi tiết đối soát: phạm vi, nhóm lệch, kế hoạch phát lại"}';
