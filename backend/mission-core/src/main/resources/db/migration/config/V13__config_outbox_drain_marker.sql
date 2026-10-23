-- CORE-08-05 (TL v1.1, chốt 23/10/2026) · Khi kỳ chuyển MO → DANG_KHOA, MOD-08 ghi dòng outbox kỹ thuật
-- period.drain.marker trong cùng giao dịch (TL-03 "Saga điều phối chốt kỳ", bước 2; TL-02 bảng điểm lệch QT-08
-- bước 1). Tên topic này không có hậu tố phiên bản nên CHECK của db/common-business V0_24 từ chối; nới riêng
-- cho đúng tên marker ở bảng outbox của schema config, không mở rộng quy ước chung.
ALTER TABLE ${flyway:defaultSchema}.outbox_event DROP CONSTRAINT ck_outbox_event__topic;
ALTER TABLE ${flyway:defaultSchema}.outbox_event
    ADD CONSTRAINT ck_outbox_event__topic
    CHECK (topic ~ '^[a-z]+(\.[a-z]+){1,3}\.v[0-9]+$' OR topic = 'period.drain.marker');
