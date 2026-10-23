-- TL-03 bổ sung 23/10/2026 · QT-07 · SCR-0703 · CORE-03-08: nghiệm thu thay theo uỷ quyền
-- (config.delegation loại NGHIEM_THU_THAY). Lưu người được nghiệm thu thay và uỷ quyền đã dùng để màn
-- hình và báo cáo hiện nhãn "nghiệm thu thay"; trước đây hai giá trị chỉ có trong nhật ký kiểm toán.
-- `reviewer_id` vẫn là người thực hiện. Tham chiếu logic, không khoá ngoại xuyên schema.
ALTER TABLE ${flyway:defaultSchema}.acceptance
    ADD COLUMN on_behalf_of uuid,
    ADD COLUMN delegation_id uuid;
ALTER TABLE ${flyway:defaultSchema}.acceptance
    ADD CONSTRAINT ck_acceptance__on_behalf_of
    CHECK ((on_behalf_of IS NULL) = (delegation_id IS NULL) AND (on_behalf_of IS NULL OR on_behalf_of <> reviewer_id));

COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.on_behalf_of IS '{"cls":"C1","desc":"Người nghiệm thu được chỉ định mà người thực hiện nghiệm thu thay"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.acceptance.delegation_id IS '{"cls":"C1","desc":"Uỷ quyền nghiệm thu thay đã dùng (tham chiếu logic config.delegation)"}';
