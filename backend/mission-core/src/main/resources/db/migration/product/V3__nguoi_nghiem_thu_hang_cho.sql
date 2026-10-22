-- CORE-03-06 · QT-07 · SCR-0703: người nghiệm thu được chỉ định của minh chứng, ghi lúc nộp từ
-- nhiệm vụ (người nghiệm thu, thiếu thì người giao), để lập hàng chờ nghiệm thu `reviewer=me` của
-- listEvidences mà không gọi chéo MOD-02 cho từng dòng. Quyền mở nghiệm thu vẫn kiểm theo nhiệm vụ
-- hiện hành ở MOD-02 (BR-07-06); cột này chỉ phục vụ lọc danh sách (suy luận, wiki 14).

ALTER TABLE ${flyway:defaultSchema}.evidence ADD COLUMN reviewer_id uuid;

CREATE INDEX ix_evidence__reviewer ON ${flyway:defaultSchema}.evidence (tenant_id, reviewer_id, id)
    WHERE reviewer_id IS NOT NULL;

COMMENT ON COLUMN ${flyway:defaultSchema}.evidence.reviewer_id IS '{"cls":"C1","desc":"Người nghiệm thu được chỉ định lúc nộp (hàng chờ nghiệm thu)"}';
