-- Mẫu SAI cho luật lint: upper
CREATE TABLE bad_upper."SampleRow" (id uuid NOT NULL, CONSTRAINT "pk_SampleRow" PRIMARY KEY (id)); COMMENT ON COLUMN bad_upper."SampleRow".id IS '{"cls":"C1"}';
