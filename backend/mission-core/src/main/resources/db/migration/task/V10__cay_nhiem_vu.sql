-- CORE-02-28 · Cây nhiệm vụ nhiều bậc và chế độ phân công (BS-03 ADR-33 DC-04, DC-06; tasks.yaml nodeKind,
-- componentScore).
--
-- parent_task_id đã có từ V2. node_kind mặc định NHIEM_VU nên dữ liệu cũ giữ nguyên cây hai tầng. Cây không
-- vòng và độ sâu theo khuôn phân rã của hồ sơ được kiểm ở MOD-02 (khuôn đọc từ Policy Pack, không cố định
-- trong CSDL). component_score chỉ dùng khi hồ sơ đặt chế độ MOT_NGUOI_MOT_VIEC.

ALTER TABLE ${flyway:defaultSchema}.task
    ADD COLUMN node_kind varchar(16) NOT NULL DEFAULT 'NHIEM_VU',
    ADD COLUMN component_score numeric(6,3),
    ADD CONSTRAINT ck_task__node_kind CHECK (node_kind IN ('NHIEM_VU', 'KPI', 'CONG_VIEC', 'CONG_VIEC_CON')),
    ADD CONSTRAINT ck_task__component_score CHECK (component_score IS NULL OR component_score >= 0);

COMMENT ON COLUMN ${flyway:defaultSchema}.task.node_kind IS '{"cls":"C1","desc":"Loại nút trong cây phân rã NHIEM_VU, KPI, CONG_VIEC, CONG_VIEC_CON (BS-03 DC-04)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.component_score IS '{"cls":"C1","desc":"Điểm thành phần của việc con, tổng bằng điểm việc cha khi chế độ MOT_NGUOI_MOT_VIEC (BS-03 DC-06)"}';
