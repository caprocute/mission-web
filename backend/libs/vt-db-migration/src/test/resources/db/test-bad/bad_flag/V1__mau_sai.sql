-- Mẫu SAI cho luật lint: flag
CREATE TABLE bad_flag.sample_row (id uuid NOT NULL, is_active boolean, CONSTRAINT pk_sample_row PRIMARY KEY (id)); COMMENT ON COLUMN bad_flag.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_flag.sample_row.is_active IS '{"cls":"C1"}';
