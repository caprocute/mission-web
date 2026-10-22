-- CORE-02-26 · Dự báo nguy cơ trễ hạn do SVC-15 gửi về qua setTaskDelayRisk (QT-06 bước 2, SCR-0601
-- "Dự báo trễ hạn", tasks.yaml DelayRisk).
--
-- Bổ sung (wiki trang 14): TL-03 chỉ có cột delay_risk, còn lược đồ DelayRisk của hợp đồng có thêm
-- neededWorkingDays và explanation. Hai cột thêm vào giữ phần giải thích và thời điểm tính để từ chối
-- kết quả cũ hơn. Cả hai là C1, không chứa dữ liệu cá nhân.

ALTER TABLE ${flyway:defaultSchema}.task
    ADD COLUMN delay_risk_detail jsonb,
    ADD COLUMN delay_risk_at timestamptz;

COMMENT ON COLUMN ${flyway:defaultSchema}.task.delay_risk_detail IS '{"cls":"C1","desc":"Giải thích dự báo trễ hạn: số ngày làm việc cần thêm, lời giải thích, tiến độ làm căn cứ (bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.delay_risk_at IS '{"cls":"C1","desc":"Thời điểm bộ dự báo tính kết quả đang lưu (bổ sung)"}';
