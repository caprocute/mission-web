-- CORE-08-05 · Thời điểm dự kiến khởi chạy của lệnh khoá kỳ (hợp đồng PeriodLockOrder.plannedLockAt,
-- "suy luận cột"); TL-03 bảng config.period_lock_order chưa có cột này (wiki trang 14).
ALTER TABLE ${flyway:defaultSchema}.period_lock_order ADD COLUMN planned_lock_at timestamptz;
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.planned_lock_at IS '{"cls": "C1", "desc": "Thời điểm dự kiến khởi chạy saga chốt kỳ (suy luận)"}';
