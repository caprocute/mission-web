-- CORE-07-03 · Tiếp nhận, không thụ lý, rút khiếu nại (QT-12 bước 3, 4; SCR-1202, SCR-1201 "Rút khiếu nại").
-- TL-03 chỉ có handler_unit_id; hợp đồng Complaint có handler (người xử lý) và dòng thời gian. Bản này thêm
-- (suy luận, wiki trang 14):
--   * handler_person_id, accepted_at: người tiếp nhận và thời điểm; accept_deadline: hạn tiếp nhận 2 ngày làm
--     việc kể từ khi gửi (QT-12 bước 4), đặt cùng lúc với response_deadline.
--   * close_reason_code, close_reason, closed_at: lý do đóng khi không thụ lý (QUA_HAN, SAI_THAM_QUYEN,
--     TRUNG_NOI_DUNG) hoặc rút (RUT); KHONG_THU_LY và RUT tự sang DONG trong cùng giao dịch nên trạng thái
--     cuối là DONG, mã lý do cho biết nhánh nào.

ALTER TABLE ${flyway:defaultSchema}.complaint
    ADD COLUMN handler_person_id uuid,
    ADD COLUMN accept_deadline date,
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN close_reason_code varchar(16),
    ADD COLUMN close_reason varchar(2000),
    ADD COLUMN closed_at timestamptz,
    ADD CONSTRAINT ck_complaint__close_reason_code CHECK (close_reason_code IS NULL
        OR close_reason_code IN ('QUA_HAN', 'SAI_THAM_QUYEN', 'TRUNG_NOI_DUNG', 'RUT')),
    ADD CONSTRAINT ck_complaint__close_reason CHECK ((close_reason_code IS NULL) = (close_reason IS NULL)),
    ADD CONSTRAINT ck_complaint__accepted CHECK ((handler_person_id IS NULL) = (accepted_at IS NULL)),
    ADD CONSTRAINT ck_complaint__in_progress CHECK (status IS NULL
        OR status IN ('MOI', 'KHONG_THU_LY', 'RUT', 'DONG') OR handler_person_id IS NOT NULL);
CREATE INDEX ix_complaint__handler_person ON ${flyway:defaultSchema}.complaint (tenant_id, handler_person_id);

COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.handler_person_id IS '{"cls":"C1","desc":"Người xử lý đã tiếp nhận hoặc không thụ lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.accept_deadline IS '{"cls":"C1","desc":"Hạn tiếp nhận, 2 ngày làm việc kể từ khi gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.accepted_at IS '{"cls":"C1","desc":"Thời điểm tiếp nhận hoặc không thụ lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.close_reason_code IS '{"cls":"C1","desc":"Nhóm lý do đóng: không thụ lý hoặc rút"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.close_reason IS '{"cls":"C1","desc":"Lý do không thụ lý hoặc rút"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.complaint.closed_at IS '{"cls":"C1","desc":"Thời điểm đóng khiếu nại"}';
