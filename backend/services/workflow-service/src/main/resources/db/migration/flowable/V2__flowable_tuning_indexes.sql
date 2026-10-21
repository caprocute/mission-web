-- =============================================================================
-- V2__flowable_tuning_indexes.sql
-- Chỉ mục bổ sung và fillfactor tối ưu cập nhật HOT cho bảng động cơ Flowable 7
-- Tham chiếu: TL-01 chương 8 mục 8.7, TL-03 mục 6.13.1, DV-06-01
-- =============================================================================

SET search_path TO flowable;

-- Chỉ mục ghép phục vụ lọc hộp việc theo tenant + người được giao và thời điểm tạo
CREATE INDEX IF NOT EXISTS ix_act_ru_task__tenant_assignee
  ON flowable.act_ru_task (tenant_id_, assignee_);

CREATE INDEX IF NOT EXISTS ix_act_ru_task__tenant_create_time
  ON flowable.act_ru_task (tenant_id_, create_time_);

-- Chỉ mục bổ sung tra cứu biến tiến trình theo (PROC_INST_ID_, NAME_) theo TL-03 mục 6.13.1
CREATE INDEX IF NOT EXISTS ix_act_ru_variable__proc_inst_name
  ON flowable.act_ru_variable (proc_inst_id_, name_);

-- Đặt fillfactor = 70 để PostgreSQL thực hiện HOT update khi cột REV_ tăng liên tục
ALTER TABLE flowable.act_ru_execution SET (fillfactor = 70);
ALTER TABLE flowable.act_ru_task SET (fillfactor = 70);
