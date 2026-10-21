-- CORE-00-05 · TL-03 L4470: topic kỹ thuật như sys.audit.v1 (ba đoạn) và sys.evidence.object.stored.v1
-- (năm đoạn) phải đi qua outbox, nhưng ck_outbox_event__topic của V0_20 chỉ nhận đúng bốn đoạn.
-- Nới thành 3..5 đoạn, đoạn cuối là phiên bản v<n> (quyết định mặc định của B1, ghi ở wiki trang 14;
-- marker saga không phiên bản period.drain.marker vẫn bị từ chối, chờ chốt riêng).
ALTER TABLE ${flyway:defaultSchema}.outbox_event DROP CONSTRAINT ck_outbox_event__topic;
ALTER TABLE ${flyway:defaultSchema}.outbox_event
    ADD CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+(\.[a-z]+){1,3}\.v[0-9]+$');
