-- Mẫu SAI cho luật lint: ex
CREATE TABLE bad_ex.sample_row (id uuid NOT NULL, validity tstzrange, CONSTRAINT pk_sample_row PRIMARY KEY (id), CONSTRAINT no_overlap EXCLUDE USING gist (validity WITH &&)); COMMENT ON COLUMN bad_ex.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_ex.sample_row.validity IS '{"cls":"C1"}';
