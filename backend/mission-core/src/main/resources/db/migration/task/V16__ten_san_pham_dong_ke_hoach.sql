-- TL-03 bổ sung 23/10/2026 · QT-04 · SCR-0402 trường 11: tên sản phẩm của dòng kế hoạch, lưu
-- `productName` của hợp đồng (tasks.yaml PlanItemInput, PlanItemRef). Cho phép NULL: dòng ghi trước
-- khi có cột đọc từ `catalog_item.product_name` rồi tới `title`. Độ dài 500 theo hợp đồng và
-- `catalog_item.product_name` (TL-03 ghi varchar(255); lệch ghi ở wiki trang 14).
ALTER TABLE ${flyway:defaultSchema}.plan_item
    ADD COLUMN product_name varchar(500);

COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.product_name IS '{"cls":"C1","desc":"Tên sản phẩm của dòng kế hoạch"}';
