-- CORE-02-10 · Điều chỉnh kế hoạch (QT-04 bước 9, BR-04-10): dòng của phiên bản trước bị bỏ ở phiên bản điều chỉnh
-- cùng lý do (SCR-0403 trường 3, PlanVersionInput.removedItems của tasks.yaml).
--
-- Bổ sung (wiki trang 14): TL-03 có change_reason cho dòng thêm, sửa (plan_item) và cho lần điều chỉnh (plan_version)
-- nhưng chưa có chỗ lưu lý do dòng bỏ, vì dòng bỏ không còn trong phiên bản mới. Mảng JSON
-- [{"originItemId": uuid, "changeReason": text}] trên phiên bản; trigger bất biến của V5 giữ cột này cố định khi
-- phiên bản đã duyệt.
ALTER TABLE ${flyway:defaultSchema}.plan_version ADD COLUMN removed_items jsonb;
ALTER TABLE ${flyway:defaultSchema}.plan_version ADD CONSTRAINT ck_plan_version__removed_items
    CHECK (removed_items IS NULL OR jsonb_typeof(removed_items) = 'array');
