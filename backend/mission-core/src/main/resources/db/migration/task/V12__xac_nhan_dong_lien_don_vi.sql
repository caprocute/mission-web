-- CORE-02-11 · Xác nhận dòng kế hoạch có đơn vị chủ trì khác đơn vị lập (QT-04 "Ngoại lệ và xử lý").
--
-- Bổ sung (wiki trang 14): TL-03 và tasks.yaml chưa có chỗ lưu việc đơn vị chủ trì nhận hay từ chối dòng.
-- Bảng riêng thay vì cột trên plan_item vì lưu nháp kế hoạch xoá và ghi lại toàn bộ dòng của phiên bản;
-- khoá theo (dòng, đơn vị chủ trì) nên đổi đơn vị chủ trì thì phải xác nhận lại. Dòng ở phiên bản sau thừa
-- hưởng xác nhận của dòng gốc (origin_item_id) cùng đơn vị chủ trì. Không phân vùng: mỗi kế hoạch chỉ có
-- vài dòng liên đơn vị.

CREATE TABLE ${flyway:defaultSchema}.plan_item_confirmation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_bucket int NOT NULL,
    plan_item_id uuid NOT NULL,
    lead_unit_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    reason varchar(1000),
    CONSTRAINT pk_plan_item_confirmation PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_plan_item_confirmation__item_unit UNIQUE (tenant_id, plan_item_id, lead_unit_id),
    CONSTRAINT ck_plan_item_confirmation__status CHECK (status IN ('DA_XAC_NHAN', 'TU_CHOI')),
    CONSTRAINT ck_plan_item_confirmation__reason CHECK (status <> 'TU_CHOI' OR length(reason) > 0)
);

COMMENT ON TABLE ${flyway:defaultSchema}.plan_item_confirmation IS 'Đơn vị chủ trì nhận hoặc từ chối dòng kế hoạch của đơn vị khác (bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.updated_by IS '{"cls":"C1","desc":"Người xác nhận hoặc từ chối gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.period_bucket IS '{"cls":"C1","desc":"Phân vùng kỳ của dòng kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.plan_item_id IS '{"cls":"C1","desc":"Dòng kế hoạch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.lead_unit_id IS '{"cls":"C1","desc":"Đơn vị chủ trì đã xác nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.status IS '{"cls":"C1","desc":"DA_XAC_NHAN hoặc TU_CHOI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.plan_item_confirmation.reason IS '{"cls":"C1","desc":"Lý do từ chối"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.plan_item_confirmation');
