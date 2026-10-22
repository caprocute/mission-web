-- CORE-02-14 · Soạn và giao nhiệm vụ (QT-05, SCR-0501, tasks.yaml TaskDraft).
--
-- Bổ sung (wiki trang 14): TL-02a bảng trường SCR-0501 đề xuất các cột priority, product_name,
-- evidence_required, quality_criteria trên task.task mà TL-03 chưa có; hợp đồng TaskDraft cần thêm căn
-- cứ của nhiệm vụ phát sinh (BR-05-09), văn bản nguồn ghi tay và đơn vị giao việc (listUnitTasks lọc theo
-- đơn vị). Mọi cột là C0/C1, không chứa dữ liệu cá nhân.

ALTER TABLE ${flyway:defaultSchema}.task
    ADD COLUMN org_unit_id uuid,
    ADD COLUMN priority varchar(12) NOT NULL DEFAULT 'TRUNG_BINH',
    ADD COLUMN product_name varchar(500),
    ADD COLUMN evidence_required varchar(1000),
    ADD COLUMN quality_criteria varchar(2000),
    ADD COLUMN basis varchar(1000),
    ADD COLUMN source_doc jsonb,
    ADD CONSTRAINT ck_task__priority CHECK (priority IN ('CAO', 'TRUNG_BINH', 'THAP')),
    ADD CONSTRAINT ck_task__source_doc CHECK (source_doc IS NULL OR jsonb_typeof(source_doc) = 'object');

CREATE INDEX ix_task__org_unit ON ${flyway:defaultSchema}.task (tenant_id, org_unit_id, period_bucket)
    WHERE org_unit_id IS NOT NULL;

COMMENT ON COLUMN ${flyway:defaultSchema}.task.org_unit_id IS '{"cls":"C1","desc":"Đơn vị giao việc (bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.priority IS '{"cls":"C1","desc":"Mức ưu tiên CAO, TRUNG_BINH, THAP (TL-02a SCR-0501, bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.product_name IS '{"cls":"C0","desc":"Sản phẩm đầu ra (TL-02a SCR-0501, bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.evidence_required IS '{"cls":"C0","desc":"Minh chứng yêu cầu (TL-02a SCR-0501, bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.quality_criteria IS '{"cls":"C0","desc":"Tiêu chuẩn chất lượng (TL-02a SCR-0501, bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.basis IS '{"cls":"C1","desc":"Căn cứ của nhiệm vụ phát sinh ngoài kế hoạch, BR-05-09 (bổ sung)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task.source_doc IS '{"cls":"C1","desc":"Số và trích yếu văn bản nguồn ghi tay (bổ sung)"}';
