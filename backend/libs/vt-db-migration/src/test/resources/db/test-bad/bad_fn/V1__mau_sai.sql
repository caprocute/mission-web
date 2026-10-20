-- Mẫu SAI cho luật lint: fn
CREATE FUNCTION bad_fn.create_partitions() RETURNS void LANGUAGE sql AS 'SELECT 1';
