-- Mẫu SAI cho luật lint: tg
CREATE TABLE bad_tg.sample_row (id uuid NOT NULL, CONSTRAINT pk_sample_row PRIMARY KEY (id)); CREATE FUNCTION bad_tg.fn_noop() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$; CREATE TRIGGER before_update BEFORE UPDATE ON bad_tg.sample_row FOR EACH ROW EXECUTE FUNCTION bad_tg.fn_noop(); COMMENT ON COLUMN bad_tg.sample_row.id IS '{"cls":"C1"}';
