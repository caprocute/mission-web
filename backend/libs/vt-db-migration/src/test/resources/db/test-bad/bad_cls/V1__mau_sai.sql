-- Mẫu SAI cho luật lint: cls
CREATE TABLE bad_cls.sample_row (id uuid NOT NULL, code varchar(10), CONSTRAINT pk_sample_row PRIMARY KEY (id)); COMMENT ON COLUMN bad_cls.sample_row.id IS '{"cls":"C1"}'; COMMENT ON COLUMN bad_cls.sample_row.code IS 'Mã không có phân loại';
