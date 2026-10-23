-- CORE-02-16: đổi tên khoá ngoại của incoming_doc_claim theo quy ước fk_<bảng>__<cột> (TaskMigrationConventionsIT).
-- V17 đã gộp main nên đổi tên bằng migration mới thay vì sửa V17.
ALTER TABLE ${flyway:defaultSchema}.incoming_doc_claim
    RENAME CONSTRAINT fk_incoming_doc_claim__doc TO fk_incoming_doc_claim__incoming_doc;
