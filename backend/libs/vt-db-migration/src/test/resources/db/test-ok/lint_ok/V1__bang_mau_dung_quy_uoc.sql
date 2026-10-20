-- Bộ migration mẫu ĐÚNG mọi luật lint của NEN-030 (dùng trong kiểm thử, không phải lược đồ thật).
CREATE TABLE lint_ok.sample_group (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    CONSTRAINT pk_sample_group PRIMARY KEY (id),
    CONSTRAINT uq_sample_group__code UNIQUE (tenant_id, code)
);

CREATE TABLE lint_ok.sample_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    sample_group_id uuid NOT NULL,
    status varchar(32) NOT NULL,
    score numeric(6,3),
    ratio numeric(6,4),
    target_value numeric(18,4),
    due_date date,
    validity tstzrange,
    payload jsonb,
    is_overdue boolean NOT NULL DEFAULT false,
    note_enc bytea,
    content_hash bytea,
    dek_id uuid,
    seq bigint NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sample_item PRIMARY KEY (id),
    CONSTRAINT fk_sample_item__sample_group FOREIGN KEY (sample_group_id) REFERENCES lint_ok.sample_group (id),
    CONSTRAINT ck_sample_item__status CHECK (status IN ('NHAP', 'DA_GIAO')),
    CONSTRAINT ex_sample_item__no_overlap EXCLUDE USING gist (validity WITH &&)
);
CREATE INDEX ix_sample_item__tenant_due ON lint_ok.sample_item (tenant_id, due_date);
CREATE INDEX ix_sample_item__overdue_p ON lint_ok.sample_item (tenant_id) WHERE is_overdue;

ALTER TABLE lint_ok.sample_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_sample_item__tenant ON lint_ok.sample_item
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE FUNCTION lint_ok.fn_touch_sample_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.row_version := OLD.row_version + 1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_sample_item__touch BEFORE UPDATE ON lint_ok.sample_item
    FOR EACH ROW EXECUTE FUNCTION lint_ok.fn_touch_sample_item();

-- Bảng phân vùng theo kỳ: đối tượng của phân vùng con do PostgreSQL sinh, lint bỏ qua.
CREATE TABLE lint_ok.sample_log (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    period_bucket int NOT NULL,
    happened_at timestamptz NOT NULL,
    CONSTRAINT pk_sample_log PRIMARY KEY (id, period_bucket)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_sample_log__tenant ON lint_ok.sample_log (tenant_id);
CREATE TABLE lint_ok.sample_log_p20271 PARTITION OF lint_ok.sample_log FOR VALUES IN (20271);

COMMENT ON COLUMN lint_ok.sample_group.id IS '{"cls":"C1","desc":"Định danh"}';
COMMENT ON COLUMN lint_ok.sample_group.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN lint_ok.sample_group.code IS '{"cls":"C0","desc":"Mã nhóm"}';
COMMENT ON COLUMN lint_ok.sample_item.id IS '{"cls":"C1","desc":"Định danh"}';
COMMENT ON COLUMN lint_ok.sample_item.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN lint_ok.sample_item.sample_group_id IS '{"cls":"C1","desc":"Nhóm"}';
COMMENT ON COLUMN lint_ok.sample_item.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN lint_ok.sample_item.score IS '{"cls":"C1","desc":"Điểm"}';
COMMENT ON COLUMN lint_ok.sample_item.ratio IS '{"cls":"C1","desc":"Tỷ lệ"}';
COMMENT ON COLUMN lint_ok.sample_item.target_value IS '{"cls":"C1","desc":"Chỉ tiêu"}';
COMMENT ON COLUMN lint_ok.sample_item.due_date IS '{"cls":"C1","desc":"Hạn"}';
COMMENT ON COLUMN lint_ok.sample_item.validity IS '{"cls":"C1","desc":"Hiệu lực"}';
COMMENT ON COLUMN lint_ok.sample_item.payload IS '{"cls":"C1","desc":"Tham số"}';
COMMENT ON COLUMN lint_ok.sample_item.is_overdue IS '{"cls":"C1","desc":"Quá hạn"}';
COMMENT ON COLUMN lint_ok.sample_item.note_enc IS '{"cls":"C2","pii":false,"desc":"Nhận xét, mã hoá trường"}';
COMMENT ON COLUMN lint_ok.sample_item.content_hash IS '{"cls":"C1","desc":"Băm"}';
COMMENT ON COLUMN lint_ok.sample_item.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu"}';
COMMENT ON COLUMN lint_ok.sample_item.seq IS '{"cls":"C1","desc":"Số thứ tự"}';
COMMENT ON COLUMN lint_ok.sample_item.row_version IS '{"cls":"C1","desc":"Khoá lạc quan"}';
COMMENT ON COLUMN lint_ok.sample_item.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';
COMMENT ON COLUMN lint_ok.sample_log.id IS '{"cls":"C1","desc":"Định danh"}';
COMMENT ON COLUMN lint_ok.sample_log.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN lint_ok.sample_log.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng"}';
COMMENT ON COLUMN lint_ok.sample_log.happened_at IS '{"cls":"C1","desc":"Thời điểm"}';
