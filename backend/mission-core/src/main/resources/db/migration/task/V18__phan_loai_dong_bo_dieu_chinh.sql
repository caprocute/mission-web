-- CORE-02-10 · Bổ sung COMMENT phân loại cho cột removed_items do V15 thêm (quy ước TaskMigrationConventionsIT:
-- mọi cột có COMMENT JSON mang "cls"). Không sửa V15 vì đã lên main.
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_version.removed_items IS '{"cls":"C1","desc":"Dòng của phiên bản trước bị bỏ ở lần điều chỉnh kèm lý do, mảng [{originItemId, changeReason}] (QT-04 bước 9, BR-04-10)"}';
