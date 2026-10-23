-- CORE-02-27 · Danh mục kế thừa bốn cấp, khung chỉnh điểm chuẩn và mức hệ số (BS-03 ADR-33 DC-01, DC-02;
-- tasks.yaml CatalogScope, scoreAdjustMin/Max, coefficientLevels, axisCode).
--
-- Phạm vi kế thừa hệ thống → khối → tỉnh → đơn vị: thêm KHOI, TINH. score_adjust_min/max là tỷ lệ chỉnh điểm
-- chuẩn mà dòng cấp dưới cùng mã được phép so với dòng này. Lõi chỉ lưu mức của từng loại hệ số
-- (coefficient_levels, khoá là mã loại do Policy Pack khai báo), không lưu giá trị; mặc định '{}' nên dữ liệu
-- cũ đọc được như trước.

ALTER TABLE ${flyway:defaultSchema}.work_catalog
    DROP CONSTRAINT ck_work_catalog__scope,
    ADD CONSTRAINT ck_work_catalog__scope CHECK (scope IN ('VI_TRI', 'DON_VI', 'QUOC_GIA', 'KHOI', 'TINH'));

ALTER TABLE ${flyway:defaultSchema}.catalog_item
    ADD COLUMN score_adjust_min numeric(6,4),
    ADD COLUMN score_adjust_max numeric(6,4),
    ADD COLUMN coefficient_levels jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN axis_code varchar(16),
    ADD CONSTRAINT ck_catalog_item__score_adjust_min CHECK (score_adjust_min IS NULL OR score_adjust_min BETWEEN -1 AND 0),
    ADD CONSTRAINT ck_catalog_item__score_adjust_max CHECK (score_adjust_max IS NULL OR score_adjust_max BETWEEN 0 AND 10),
    ADD CONSTRAINT ck_catalog_item__coefficient_levels CHECK (jsonb_typeof(coefficient_levels) = 'object');

ALTER TABLE ${flyway:defaultSchema}.plan_item
    ADD COLUMN coefficient_levels jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN axis_code varchar(16),
    ADD CONSTRAINT ck_plan_item__coefficient_levels CHECK (jsonb_typeof(coefficient_levels) = 'object');

ALTER TABLE ${flyway:defaultSchema}.task
    ADD COLUMN coefficient_levels jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN axis_code varchar(16),
    ADD CONSTRAINT ck_task__coefficient_levels CHECK (jsonb_typeof(coefficient_levels) = 'object');

COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.score_adjust_min IS '{"cls":"C1","desc":"Tỷ lệ chỉnh điểm chuẩn tối thiểu của dòng cấp dưới so với dòng này (BS-03 DC-02)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.score_adjust_max IS '{"cls":"C1","desc":"Tỷ lệ chỉnh điểm chuẩn tối đa của dòng cấp dưới so với dòng này (BS-03 DC-02)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.coefficient_levels IS '{"cls":"C1","desc":"Mức của từng loại hệ số mở, khoá là mã loại trong Policy Pack (BS-03 DC-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.catalog_item.axis_code IS '{"cls":"C1","desc":"Mã trục công việc (BS-03 DC-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.coefficient_levels IS '{"cls":"C1","desc":"Mức hệ số sao chép từ mục danh mục (BS-03 DC-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item.axis_code IS '{"cls":"C1","desc":"Mã trục sao chép từ mục danh mục (BS-03 DC-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.coefficient_levels IS '{"cls":"C1","desc":"Mức hệ số sao chép từ mục danh mục khi soạn nhiệm vụ (BS-03 DC-01)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.axis_code IS '{"cls":"C1","desc":"Mã trục sao chép từ mục danh mục (BS-03 DC-01)"}';
