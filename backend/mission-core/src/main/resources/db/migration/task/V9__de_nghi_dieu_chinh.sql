-- CORE-02-21 · Đề nghị gia hạn, điều chỉnh (QT-06 bước 5, SCR-0602, tasks.yaml ChangeRequest).
--
-- Mỗi nhiệm vụ chỉ có một đề nghị cùng loại đang chờ duyệt (TL-02a "Trạng thái đối tượng"). Chỉ mục duy
-- nhất một phần chặn cả hai yêu cầu gửi đồng thời; đặt tên ix_..._p theo lint NEN-030 như
-- ix_task_assignee__one_lead_p, có period_bucket vì bảng phân vùng.

CREATE UNIQUE INDEX ix_task_change_request__one_pending_p
    ON ${flyway:defaultSchema}.task_change_request (tenant_id, task_id, request_type, period_bucket)
    WHERE status = 'CHO_DUYET';
