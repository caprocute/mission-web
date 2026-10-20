-- Mẫu SAI cho luật lint: uq
CREATE TABLE bad_uq.sample_row (id uuid NOT NULL, code varchar(10), CONSTRAINT pk_sample_row PRIMARY KEY (id), CONSTRAINT sample_row_code_key UNIQUE (code)); COMMENT ON COLUMN bad_uq.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_uq.sample_row.code IS '{"cls":"C0"}';
