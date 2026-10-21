-- CORE-00-03 · Bản sao tổ chức org_replica (TL-03 "Thiết kế chi tiết các schema", bảng org_replica.*).
-- Chỉ svc_org_replica ghi; svc_ của tám module chỉ đọc. Không có cột C2.
-- valid_to không null: hiệu lực vô thời hạn lưu 'infinity' (sự kiện gửi null).

CREATE TABLE ${flyway:defaultSchema}.org_unit_v (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_to date NOT NULL DEFAULT 'infinity',
    code varchar(64) NOT NULL,
    name varchar(500) NOT NULL,
    unit_type varchar(24) NOT NULL,
    party_parent_id uuid,
    admin_parent_id uuid,
    subject_group_id uuid,
    source_version bigint NOT NULL,
    source_event_id uuid NOT NULL,
    replicated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_org_unit_v PRIMARY KEY (tenant_id, id, valid_from),
    CONSTRAINT ck_org_unit_v__valid_range CHECK (valid_to >= valid_from)
);
CREATE INDEX ix_org_unit_v__tenant_valid_to ON ${flyway:defaultSchema}.org_unit_v (tenant_id, id, valid_to);

CREATE TABLE ${flyway:defaultSchema}.person_v (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    id uuid NOT NULL,
    full_name varchar(200) NOT NULL,
    is_party_member boolean NOT NULL DEFAULT false,
    cbccvc_code varchar(32),
    employment_status varchar(16) NOT NULL,
    source_version bigint NOT NULL,
    source_event_id uuid NOT NULL,
    replicated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_person_v PRIMARY KEY (tenant_id, id)
);

CREATE TABLE ${flyway:defaultSchema}.assignment_v (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_to date NOT NULL DEFAULT 'infinity',
    person_id uuid NOT NULL,
    org_unit_id uuid NOT NULL,
    position_id uuid,
    job_title_id uuid NOT NULL,
    kind varchar(16) NOT NULL,
    branch varchar(16) NOT NULL,
    is_head boolean NOT NULL DEFAULT false,
    source_version bigint NOT NULL,
    source_event_id uuid NOT NULL,
    replicated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_assignment_v PRIMARY KEY (tenant_id, id, valid_from),
    CONSTRAINT ck_assignment_v__valid_range CHECK (valid_to >= valid_from)
);
CREATE INDEX ix_assignment_v__person_valid_to ON ${flyway:defaultSchema}.assignment_v (tenant_id, person_id, valid_to);
CREATE INDEX ix_assignment_v__unit_valid_to ON ${flyway:defaultSchema}.assignment_v (tenant_id, org_unit_id, valid_to);
CREATE INDEX ix_assignment_v__head_p ON ${flyway:defaultSchema}.assignment_v (tenant_id, org_unit_id) WHERE is_head;

CREATE TABLE ${flyway:defaultSchema}.unit_closure_v (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    tree varchar(12) NOT NULL,
    ancestor_id uuid NOT NULL,
    descendant_id uuid NOT NULL,
    depth integer NOT NULL,
    CONSTRAINT pk_unit_closure_v PRIMARY KEY (tenant_id, tree, ancestor_id, descendant_id),
    CONSTRAINT ck_unit_closure_v__tree CHECK (tree IN ('DANG', 'CHUYEN_MON')),
    CONSTRAINT ck_unit_closure_v__depth CHECK (depth >= 0)
);
CREATE INDEX ix_unit_closure_v__descendant ON ${flyway:defaultSchema}.unit_closure_v (tenant_id, tree, descendant_id);

CREATE TABLE ${flyway:defaultSchema}.replica_checkpoint (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    entity_type varchar(16) NOT NULL,
    last_event_id uuid NOT NULL,
    last_occurred_at timestamptz NOT NULL,
    last_applied_at timestamptz NOT NULL DEFAULT now(),
    lag_ms int NOT NULL,
    CONSTRAINT pk_replica_checkpoint PRIMARY KEY (tenant_id, entity_type),
    CONSTRAINT ck_replica_checkpoint__entity_type CHECK (entity_type IN ('ORG_UNIT', 'ASSIGNMENT', 'PERSON'))
);

CREATE TABLE ${flyway:defaultSchema}.reconcile_run (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    entity_type varchar(16) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    buckets_total int,
    buckets_mismatch int,
    rows_repaired int,
    status varchar(12) NOT NULL,
    CONSTRAINT pk_reconcile_run PRIMARY KEY (id),
    CONSTRAINT ck_reconcile_run__status CHECK (status IN ('DANG_CHAY', 'KHOP', 'DA_SUA', 'LOI'))
);
CREATE INDEX ix_reconcile_run__tenant_started ON ${flyway:defaultSchema}.reconcile_run (tenant_id, started_at);

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.org_unit_v');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.person_v');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.assignment_v');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.unit_closure_v');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.replica_checkpoint');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.reconcile_run');

COMMENT ON TABLE ${flyway:defaultSchema}.org_unit_v IS 'Bản sao đơn vị theo khoảng hiệu lực (EVT-14)';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.id IS '{"cls":"C1","desc":"Mã đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.valid_from IS '{"cls":"C1","desc":"Bắt đầu hiệu lực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.valid_to IS '{"cls":"C1","desc":"Hết hiệu lực; infinity là vô thời hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.code IS '{"cls":"C0","desc":"Mã đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.name IS '{"cls":"C0","desc":"Tên đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.unit_type IS '{"cls":"C0","desc":"Loại đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.party_parent_id IS '{"cls":"C1","desc":"Cấp trên trên cây Đảng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.admin_parent_id IS '{"cls":"C1","desc":"Cấp trên trên cây chuyên môn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.subject_group_id IS '{"cls":"C1","desc":"Khối đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.org_unit_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
COMMENT ON TABLE ${flyway:defaultSchema}.person_v IS 'Bản sao nhân sự (EVT-16)';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.id IS '{"cls":"C1","desc":"Mã người"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.full_name IS '{"cls":"C1","desc":"Họ và tên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.is_party_member IS '{"cls":"C1","desc":"Đảng viên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.cbccvc_code IS '{"cls":"C1","desc":"Mã CBCCVC"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.employment_status IS '{"cls":"C1","desc":"Tình trạng công tác"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.person_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
COMMENT ON TABLE ${flyway:defaultSchema}.assignment_v IS 'Bản sao phân công theo khoảng hiệu lực (EVT-15)';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.id IS '{"cls":"C1","desc":"Mã phân công"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.valid_from IS '{"cls":"C1","desc":"Bắt đầu hiệu lực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.valid_to IS '{"cls":"C1","desc":"Hết hiệu lực; infinity là vô thời hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.person_id IS '{"cls":"C1","desc":"Người"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.org_unit_id IS '{"cls":"C1","desc":"Đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.position_id IS '{"cls":"C1","desc":"Vị trí việc làm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.job_title_id IS '{"cls":"C1","desc":"Chức danh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.kind IS '{"cls":"C1","desc":"Loại phân công"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.branch IS '{"cls":"C1","desc":"Nhánh: CHINH hoặc PHU"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.is_head IS '{"cls":"C1","desc":"Người đứng đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.source_version IS '{"cls":"C1","desc":"Phiên bản ở nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.assignment_v.replicated_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
COMMENT ON TABLE ${flyway:defaultSchema}.unit_closure_v IS 'Bao đóng cây tổ chức hiện hành theo từng cây';
COMMENT ON COLUMN ${flyway:defaultSchema}.unit_closure_v.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.unit_closure_v.tree IS '{"cls":"C1","desc":"Cây Đảng hoặc chuyên môn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.unit_closure_v.ancestor_id IS '{"cls":"C1","desc":"Tổ tiên, kể cả chính nó"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.unit_closure_v.descendant_id IS '{"cls":"C1","desc":"Hậu duệ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.unit_closure_v.depth IS '{"cls":"C1","desc":"Khoảng cách"}';
COMMENT ON TABLE ${flyway:defaultSchema}.replica_checkpoint IS 'Độ trễ áp dụng theo loại thực thể';
COMMENT ON COLUMN ${flyway:defaultSchema}.replica_checkpoint.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.replica_checkpoint.entity_type IS '{"cls":"C1","desc":"Loại thực thể"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.replica_checkpoint.last_event_id IS '{"cls":"C1","desc":"Sự kiện áp dụng gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.replica_checkpoint.last_occurred_at IS '{"cls":"C1","desc":"Thời điểm phát của sự kiện đó"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.replica_checkpoint.last_applied_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.replica_checkpoint.lag_ms IS '{"cls":"C1","desc":"Độ trễ từ lúc phát tới lúc áp dụng"}';
COMMENT ON TABLE ${flyway:defaultSchema}.reconcile_run IS 'Kết quả đối soát hằng đêm (DL-12 ghi)';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.id IS '{"cls":"C1","desc":"Mã lần đối soát"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.entity_type IS '{"cls":"C1","desc":"Loại thực thể"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.started_at IS '{"cls":"C1","desc":"Bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.finished_at IS '{"cls":"C1","desc":"Kết thúc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.buckets_total IS '{"cls":"C1","desc":"Số nhóm băm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.buckets_mismatch IS '{"cls":"C1","desc":"Số nhóm lệch"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.rows_repaired IS '{"cls":"C1","desc":"Số dòng đã sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.reconcile_run.status IS '{"cls":"C1","desc":"Trạng thái đối soát"}';
