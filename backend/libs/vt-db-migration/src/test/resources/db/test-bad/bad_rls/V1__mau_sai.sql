-- Mẫu SAI cho luật lint: rls
CREATE TABLE bad_rls.sample_row (id uuid NOT NULL, tenant_id uuid, CONSTRAINT pk_sample_row PRIMARY KEY (id)); ALTER TABLE bad_rls.sample_row ENABLE ROW LEVEL SECURITY; CREATE POLICY tenant_isolation ON bad_rls.sample_row USING (true); COMMENT ON COLUMN bad_rls.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_rls.sample_row.tenant_id IS '{"cls":"C1"}';
