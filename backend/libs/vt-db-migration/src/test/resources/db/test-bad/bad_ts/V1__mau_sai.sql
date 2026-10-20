-- Mẫu SAI cho luật lint: ts
CREATE TABLE bad_ts.sample_row (id uuid NOT NULL, locked_at date, CONSTRAINT pk_sample_row PRIMARY KEY (id)); COMMENT ON COLUMN bad_ts.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_ts.sample_row.locked_at IS '{"cls":"C1"}';
