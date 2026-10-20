-- Mẫu SAI cho luật lint: ix
CREATE TABLE bad_ix.sample_row (id uuid NOT NULL, tenant_id uuid, CONSTRAINT pk_sample_row PRIMARY KEY (id)); CREATE INDEX idx_tenant ON bad_ix.sample_row (tenant_id); COMMENT ON COLUMN bad_ix.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_ix.sample_row.tenant_id IS '{"cls":"C1"}';
