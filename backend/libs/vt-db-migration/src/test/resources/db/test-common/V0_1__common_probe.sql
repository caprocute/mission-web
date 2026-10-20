-- Migration dùng chung mẫu (dải V0_x): tạo bảng trong schema đang chạy qua placeholder.
CREATE TABLE ${flyway:defaultSchema}.common_probe (
    id uuid NOT NULL,
    CONSTRAINT pk_common_probe PRIMARY KEY (id)
);
COMMENT ON COLUMN ${flyway:defaultSchema}.common_probe.id IS '{"cls":"C1","desc":"Định danh"}';
