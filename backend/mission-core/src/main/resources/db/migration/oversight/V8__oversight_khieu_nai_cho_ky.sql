-- MOD-07 · Mẫu "chốt trước, gọi sau" cho lệnh "Ký và gửi khiếu nại" (QT-12 bước 2, SCR-1201; wiki trang 14).
-- SVC-10 hỏi lại tài liệu cần ký của khiếu nại bằng kết nối khác ngay trong lời gọi tạo yêu cầu ký, khi mã yêu
-- cầu ký chưa có. Bản này thêm (suy luận) sign_pending_at: thời điểm nháp bắt đầu mở yêu cầu ký, ghi và commit
-- trong giao dịch riêng trước khi gọi SVC-10; gắn mã yêu cầu ký hoặc mọi lần cập nhật khiếu nại đều gỡ dấu.

ALTER TABLE ${flyway:defaultSchema}.complaint
    ADD COLUMN sign_pending_at timestamptz;

COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.sign_pending_at IS '{"cls":"C1","desc":"Thời điểm nháp bắt đầu mở yêu cầu ký ở SVC-10, chưa gắn mã yêu cầu ký"}';
