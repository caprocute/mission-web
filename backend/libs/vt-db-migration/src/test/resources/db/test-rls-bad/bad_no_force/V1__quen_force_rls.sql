-- Migration mẫu quên FORCE ROW LEVEL SECURITY để kiểm thử âm lớp 1 (NEN-034 mục 6).
CREATE TABLE bad_no_force.sample_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    title varchar(100) NOT NULL,
    CONSTRAINT pk_sample_record PRIMARY KEY (id)
);

ALTER TABLE bad_no_force.sample_record ENABLE ROW LEVEL SECURITY;
-- Cố tình thiếu: ALTER TABLE bad_no_force.sample_record FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_sample_record__tenant ON bad_no_force.sample_record AS PERMISSIVE FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
