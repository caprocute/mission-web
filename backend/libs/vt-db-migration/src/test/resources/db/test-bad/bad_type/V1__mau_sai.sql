-- Mẫu SAI cho luật lint: type
CREATE TABLE bad_type.sample_row (id uuid NOT NULL, name varchar, CONSTRAINT pk_sample_row PRIMARY KEY (id)); COMMENT ON COLUMN bad_type.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_type.sample_row.name IS '{"cls":"C0"}';
