-- Mẫu SAI cho luật lint: pk
CREATE TABLE bad_pk.sample_row (id uuid NOT NULL, CONSTRAINT sample_row_pkey PRIMARY KEY (id)); COMMENT ON COLUMN bad_pk.sample_row.id IS '{"cls":"C1"}';
