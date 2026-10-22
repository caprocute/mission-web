-- DV-02-02 · TL-03 L4470: sổ cái đổi ngữ cảnh (BR-00-08) đi qua outbox lên topic kỹ thuật sys.audit.v1 (ba đoạn),
-- nhưng ck_outbox_event__topic của V4 chỉ nhận bốn đoạn. Nới thành 3..5 đoạn như V0_24 của vt-db-migration.
ALTER TABLE org.outbox_event DROP CONSTRAINT ck_outbox_event__topic;
ALTER TABLE org.outbox_event
    ADD CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+(\.[a-z]+){1,3}\.v[0-9]+$');
