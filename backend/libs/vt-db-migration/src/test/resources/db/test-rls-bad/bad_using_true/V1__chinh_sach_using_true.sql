-- Migration mẫu có ENABLE + FORCE RLS nhưng viết sai chính sách USING (true) để kiểm thử âm lớp 2 (NEN-034 mục 6).
CREATE TABLE bad_using_true.sample_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    title varchar(100) NOT NULL,
    CONSTRAINT pk_sample_record PRIMARY KEY (id)
);

ALTER TABLE bad_using_true.sample_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE bad_using_true.sample_record FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_sample_record__tenant ON bad_using_true.sample_record AS PERMISSIVE FOR ALL
    USING (true)
    WITH CHECK (true);
