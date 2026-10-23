-- MOD-07 · Mẫu "chốt trước, gọi sau" cho lệnh "Ký và gửi khiếu nại" (QT-12 bước 2, SCR-1201; wiki trang 14).
-- SVC-10 hỏi lại tài liệu cần ký của khiếu nại bằng kết nối khác ngay trong lời gọi tạo yêu cầu ký, khi mã yêu
-- cầu ký chưa có. Bản này thêm (suy luận) sign_pending_at: thời điểm nháp bắt đầu mở yêu cầu ký, ghi và commit
-- trong giao dịch riêng trước khi gọi SVC-10; gắn mã yêu cầu ký hoặc mọi lần cập nhật khiếu nại đều gỡ dấu.
-- Cùng mẫu cho lệnh "Ký văn bản trả lời" (QT-12 bước 9, SCR-1202, CORE-07-04): response_signer_id là người có
-- thẩm quyền đang mở yêu cầu ký văn bản trả lời, khi bản ghi KET_LUAN (bắt buộc có sign_request_id) chưa có.

ALTER TABLE ${flyway:defaultSchema}.complaint
    ADD COLUMN sign_pending_at timestamptz,
    ADD COLUMN response_signer_id uuid;

COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.sign_pending_at IS '{"cls":"C1","desc":"Thời điểm nháp bắt đầu mở yêu cầu ký ở SVC-10, chưa gắn mã yêu cầu ký"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.response_signer_id IS '{"cls":"C1","desc":"Người có thẩm quyền đang mở yêu cầu ký văn bản trả lời, chưa gắn mã yêu cầu ký"}';
