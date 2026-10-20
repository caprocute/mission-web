-- Mẫu SAI cho luật lint: ck
CREATE TABLE bad_ck.sample_row (id uuid NOT NULL, status varchar(32), CONSTRAINT pk_sample_row PRIMARY KEY (id), CONSTRAINT status_check CHECK (status IN ('NHAP'))); COMMENT ON COLUMN bad_ck.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_ck.sample_row.status IS '{"cls":"C1"}';
