-- Mẫu SAI cho luật lint: c2
CREATE TABLE bad_c2.sample_row (id uuid NOT NULL, comment text, CONSTRAINT pk_sample_row PRIMARY KEY (id)); COMMENT ON COLUMN bad_c2.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_c2.sample_row.comment IS '{"cls":"C2","desc":"Nhận xét"}';
