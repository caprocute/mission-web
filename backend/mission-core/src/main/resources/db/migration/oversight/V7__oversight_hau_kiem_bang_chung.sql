-- CORE-07-09 · Đợt hậu kiểm có bằng chứng sổ cái (QT-13 bước 1, 13; SCR-1301). TL-03 post_audit chưa có căn cứ
-- khởi tạo và tệp bằng chứng, còn hợp đồng oversight.yaml có `basis` (createPostAudit) và `evidenceObjectIds`
-- (closePostAudit, tệp xuất từ `POST /ledger/exports` của SVC-09). Bản này thêm hai cột đó (suy luận, wiki trang 14).

ALTER TABLE ${flyway:defaultSchema}.post_audit
    ADD COLUMN basis varchar(200),
    ADD COLUMN evidence_object_ids uuid[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_post_audit__evidence CHECK (cardinality(evidence_object_ids) <= 20);
CREATE INDEX ix_post_audit__period ON ${flyway:defaultSchema}.post_audit (tenant_id, period_id, opened_at, id);

COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.basis IS '{"cls":"C1","desc":"Căn cứ khởi tạo (số quyết định, mã phản ánh, mã cảnh báo)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.post_audit.evidence_object_ids IS '{"cls":"C1","desc":"Tệp bằng chứng xuất từ sổ cái","type_exception":"Danh sách mã tệp như complaint.attachment_object_ids"}';
