-- Bảng mẫu theo kỳ cho kiểm thử RLS và phân vùng (NEN-033), không phải lược đồ thật.
-- Khoá chính gồm tenant_id vì ở pool bảng được chia tiếp HASH (tenant_id) và PostgreSQL bắt
-- khoá duy nhất của bảng phân vùng chứa mọi cột khoá phân vùng (xem README, mục RLS và phân vùng).
CREATE TABLE rls_demo.sample_task (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    period_bucket int NOT NULL,
    title varchar(100) NOT NULL,
    CONSTRAINT pk_sample_task PRIMARY KEY (period_bucket, tenant_id, id)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_sample_task__tenant_title ON rls_demo.sample_task (tenant_id, title);

COMMENT ON COLUMN rls_demo.sample_task.id IS '{"cls":"C1","desc":"Định danh"}';
COMMENT ON COLUMN rls_demo.sample_task.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN rls_demo.sample_task.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng kỳ"}';
COMMENT ON COLUMN rls_demo.sample_task.title IS '{"cls":"C1","desc":"Tên"}';

SELECT rls_demo.fn_enable_tenant_rls('rls_demo.sample_task');
-- Gọi lại không lỗi (idempotent).
SELECT rls_demo.fn_enable_tenant_rls('rls_demo.sample_task');
