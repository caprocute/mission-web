-- =============================================================================
-- V14__org_replica_do_tre_doi_soat.sql
-- DV-06-10 (nối tiếp) · DL-12 R1 · SVC-06 · TL-03 "Schema org_replica" (bảng bổ sung replica_checkpoint,
-- reconcile_run), "Đối soát và kiểm tra nhất quán" (băm theo 256 bucket) · wiki trang 18 (org_replica_lag_ms).
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp. V8, V10 thuộc nhánh khác.
--
-- Ghi chú (wiki trang 14, mục DV-06-10):
--   * org_replica.replica_checkpoint theo đúng cột TL-03: mốc sự kiện áp gần nhất và độ trễ theo tenant và loại
--     thực thể; consumer bản sao của workflow-service ghi mỗi sự kiện và xuất số đo org_replica_lag_ms.
--   * org_replica.reconcile_run theo cột TL-03 cộng hai cột job, detail của khung vt-reconcile (cùng mẫu
--     org_replica V3 của mission-core, scoring V2); job R1 ghi khi nhận KET_THUC của org.bootstrap.v1.
--   * workflow-service không giữ danh bạ tenant, nên job so cần biết tenant nào có lần chạy chờ: hàm
--     SECURITY DEFINER org_replica.fn_reconcile_pending_tenants chỉ trả mã tenant (C1) có lần chạy DANG_CHAY,
--     chạy bằng chủ sở hữu bảng; bảng bật FORCE RLS nên thêm chính sách riêng cho chủ sở hữu chỉ đọc dòng
--     DANG_CHAY (cùng cách V12 làm với inbox_processed). Không có tenant nào đọc được dữ liệu tenant khác.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- org_replica.replica_checkpoint (TL-03 "Bảng org_replica.replica_checkpoint (bổ sung)").
-- -----------------------------------------------------------------------------
CREATE TABLE org_replica.replica_checkpoint (
  tenant_id        uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
  entity_type      varchar(16) NOT NULL,
  last_event_id    uuid        NOT NULL,
  last_occurred_at timestamptz NOT NULL,
  last_applied_at  timestamptz NOT NULL DEFAULT now(),
  lag_ms           int         NOT NULL,
  CONSTRAINT pk_replica_checkpoint PRIMARY KEY (tenant_id, entity_type),
  CONSTRAINT ck_replica_checkpoint__entity_type CHECK (entity_type IN ('ORG_UNIT', 'ASSIGNMENT', 'PERSON')),
  CONSTRAINT ck_replica_checkpoint__lag CHECK (lag_ms >= 0)
);
COMMENT ON TABLE org_replica.replica_checkpoint IS 'Độ trễ áp dụng bản sao tổ chức của workflow-service theo loại thực thể (TL-03)';
COMMENT ON COLUMN org_replica.replica_checkpoint.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org_replica.replica_checkpoint.entity_type IS '{"cls":"C1","desc":"Loại thực thể"}';
COMMENT ON COLUMN org_replica.replica_checkpoint.last_event_id IS '{"cls":"C1","desc":"Sự kiện áp dụng gần nhất"}';
COMMENT ON COLUMN org_replica.replica_checkpoint.last_occurred_at IS '{"cls":"C1","desc":"Thời điểm phát của sự kiện đó"}';
COMMENT ON COLUMN org_replica.replica_checkpoint.last_applied_at IS '{"cls":"C1","desc":"Thời điểm áp dụng"}';
COMMENT ON COLUMN org_replica.replica_checkpoint.lag_ms IS '{"cls":"C1","desc":"Độ trễ từ lúc phát tới lúc áp dụng (ms)"}';
ALTER TABLE org_replica.replica_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_replica.replica_checkpoint FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_replica_checkpoint__tenant ON org_replica.replica_checkpoint AS PERMISSIVE FOR ALL
  TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON org_replica.replica_checkpoint FROM PUBLIC;
GRANT SELECT ON org_replica.replica_checkpoint TO svc_flowable, ro_flowable;
GRANT INSERT, UPDATE ON org_replica.replica_checkpoint TO svc_flowable;

-- -----------------------------------------------------------------------------
-- org_replica.reconcile_run (TL-03 "Bảng org_replica.reconcile_run (bổ sung)", cột job, detail của DL-12).
-- -----------------------------------------------------------------------------
CREATE TABLE org_replica.reconcile_run (
  id               uuid        NOT NULL,
  tenant_id        uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
  job              varchar(32) NOT NULL DEFAULT 'R1_BAN_SAO_TO_CHUC',
  entity_type      varchar(16) NOT NULL,
  started_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz,
  buckets_total    int,
  buckets_mismatch int,
  rows_repaired    int,
  status           varchar(12) NOT NULL,
  detail           jsonb       NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT pk_reconcile_run PRIMARY KEY (id),
  CONSTRAINT ck_reconcile_run__status CHECK (status IN ('DANG_CHAY', 'KHOP', 'DA_SUA', 'LOI'))
);
CREATE INDEX ix_reconcile_run__tenant_job_status
  ON org_replica.reconcile_run (tenant_id, job, status, started_at);
CREATE INDEX ix_reconcile_run__cho_so
  ON org_replica.reconcile_run (job, started_at) WHERE status = 'DANG_CHAY';
COMMENT ON TABLE org_replica.reconcile_run IS 'Kết quả đối soát bản sao tổ chức của workflow-service (DL-12 R1 ghi)';
COMMENT ON COLUMN org_replica.reconcile_run.id IS '{"cls":"C1","desc":"Mã lần đối soát"}';
COMMENT ON COLUMN org_replica.reconcile_run.tenant_id IS '{"cls":"C1","desc":"Tenant; khoá của chính sách RLS"}';
COMMENT ON COLUMN org_replica.reconcile_run.job IS '{"cls":"C1","desc":"Mã job đối soát R1..R7"}';
COMMENT ON COLUMN org_replica.reconcile_run.entity_type IS '{"cls":"C1","desc":"Loại thực thể"}';
COMMENT ON COLUMN org_replica.reconcile_run.started_at IS '{"cls":"C1","desc":"Bắt đầu"}';
COMMENT ON COLUMN org_replica.reconcile_run.finished_at IS '{"cls":"C1","desc":"Kết thúc"}';
COMMENT ON COLUMN org_replica.reconcile_run.buckets_total IS '{"cls":"C1","desc":"Số nhóm băm"}';
COMMENT ON COLUMN org_replica.reconcile_run.buckets_mismatch IS '{"cls":"C1","desc":"Số nhóm lệch"}';
COMMENT ON COLUMN org_replica.reconcile_run.rows_repaired IS '{"cls":"C1","desc":"Số dòng đã phát lại"}';
COMMENT ON COLUMN org_replica.reconcile_run.status IS '{"cls":"C1","desc":"Trạng thái đối soát"}';
COMMENT ON COLUMN org_replica.reconcile_run.detail IS '{"cls":"C1","desc":"Chi tiết đối soát: phạm vi, bucket lệch, kế hoạch phát lại"}';
ALTER TABLE org_replica.reconcile_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_replica.reconcile_run FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_reconcile_run__tenant ON org_replica.reconcile_run AS PERMISSIVE FOR ALL
  TO svc_flowable, ro_flowable
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- Chủ sở hữu bảng (hàm SECURITY DEFINER bên dưới) chỉ đọc dòng đang chờ so, không ghi.
CREATE POLICY rls_reconcile_run__cho_so ON org_replica.reconcile_run AS PERMISSIVE FOR SELECT TO CURRENT_USER
  USING (status = 'DANG_CHAY');
REVOKE ALL ON org_replica.reconcile_run FROM PUBLIC;
GRANT SELECT ON org_replica.reconcile_run TO svc_flowable, ro_flowable;
GRANT INSERT, UPDATE ON org_replica.reconcile_run TO svc_flowable;

-- Tenant có lần chạy của p_job còn DANG_CHAY và bắt đầu trước p_before (đã qua thời gian lắng); chỉ trả mã
-- tenant, không trả nội dung lần chạy. svc_flowable chỉ có EXECUTE.
CREATE FUNCTION org_replica.fn_reconcile_pending_tenants(
        p_job varchar,
        p_before timestamptz,
        p_limit int DEFAULT 500)
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT DISTINCT r.tenant_id
      FROM org_replica.reconcile_run r
     WHERE r.job = p_job
       AND r.status = 'DANG_CHAY'
       AND r.started_at < p_before
     LIMIT greatest(1, least(coalesce(p_limit, 500), 5000));
$fn$;
REVOKE EXECUTE ON FUNCTION org_replica.fn_reconcile_pending_tenants(varchar, timestamptz, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org_replica.fn_reconcile_pending_tenants(varchar, timestamptz, int) TO svc_flowable;
