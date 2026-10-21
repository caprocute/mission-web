-- =============================================================================
-- SVC-09 Ledger Service (Sổ cái bất biến)
-- Migration V2: Quản lý phân vùng hai tầng, RLS theo tenant và phân quyền
-- các vai trò CSDL (mig_ledger, svc_ledger, ro_ledger, cdc_ledger).
-- Tham chiếu: TL-03 §2.8, §4, §12.2; TL-04 §3.6 (SEC-06).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Khởi tạo các vai trò CSDL (idempotent cho môi trường test/dev)
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mig_ledger') THEN
        CREATE ROLE mig_ledger NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_ledger') THEN
        CREATE ROLE svc_ledger NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_ledger') THEN
        CREATE ROLE ro_ledger NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_ledger') THEN
        CREATE ROLE cdc_ledger NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
END
$$;

ALTER ROLE svc_ledger SET statement_timeout = '5s';
ALTER ROLE svc_ledger SET idle_in_transaction_session_timeout = '10s';
ALTER ROLE svc_ledger SET lock_timeout = '2s';
ALTER ROLE svc_ledger SET search_path = ledger;

ALTER ROLE ro_ledger SET statement_timeout = '30s';
ALTER ROLE ro_ledger SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE ro_ledger SET lock_timeout = '2s';
ALTER ROLE ro_ledger SET search_path = ledger;

ALTER ROLE cdc_ledger SET statement_timeout = '60s';
ALTER ROLE cdc_ledger SET search_path = ledger;

-- -----------------------------------------------------------------------------
-- 2. Row-Level Security (RLS) bắt buộc trên các bảng gắn tenant_id
-- -----------------------------------------------------------------------------
ALTER TABLE ledger.ledger_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.ledger_entry FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_ledger_entry__tenant ON ledger.ledger_entry;
CREATE POLICY rls_ledger_entry__tenant ON ledger.ledger_entry
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ledger.chain_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.chain_head FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_chain_head__tenant ON ledger.chain_head;
CREATE POLICY rls_chain_head__tenant ON ledger.chain_head
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ledger.merkle_seal ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.merkle_seal FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_merkle_seal__tenant ON ledger.merkle_seal;
CREATE POLICY rls_merkle_seal__tenant ON ledger.merkle_seal
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ledger.verify_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.verify_run FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_verify_run__tenant ON ledger.verify_run;
CREATE POLICY rls_verify_run__tenant ON ledger.verify_run
    USING (
        tenant_id IS NULL
        OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        tenant_id IS NULL
        OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

ALTER TABLE ledger.archive_segment ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.archive_segment FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_archive_segment__tenant ON ledger.archive_segment;
CREATE POLICY rls_archive_segment__tenant ON ledger.archive_segment
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ledger.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.outbox_event FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_outbox_event__tenant ON ledger.outbox_event;
CREATE POLICY rls_outbox_event__tenant ON ledger.outbox_event
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ledger.outbox_event_default ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.outbox_event_default FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_outbox_event_default__tenant ON ledger.outbox_event_default;
CREATE POLICY rls_outbox_event_default__tenant ON ledger.outbox_event_default
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- 3. Hàm hỗ trợ tính tháng kế tiếp dạng YYYYMM
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger.fn_next_recorded_month(p_month integer)
RETURNS integer
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    v_year integer;
    v_mon  integer;
BEGIN
    v_year := p_month / 100;
    v_mon  := p_month % 100;
    IF v_year < 2000 OR v_year > 2999 OR v_mon < 1 OR v_mon > 12 THEN
        RAISE EXCEPTION 'Invalid recorded_month YYYYMM: %', p_month
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF v_mon = 12 THEN
        RETURN (v_year + 1) * 100 + 1;
    ELSE
        RETURN v_year * 100 + (v_mon + 1);
    END IF;
END;
$$;

-- -----------------------------------------------------------------------------
-- 4. Hàm quản lý phân vùng hai tầng: LIST (tenant_id) -> RANGE (recorded_month)
--    SECURITY DEFINER để svc_ledger gọi được khi phát sinh tenant mới hoặc
--    job định kỳ tạo trước phân vùng tháng.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger.fn_ensure_tenant_partitions(
    p_tenant_id  uuid,
    p_from_month integer,
    p_to_month   integer
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ledger, pg_catalog
AS $$
DECLARE
    v_tenant_hex  text;
    v_parent_part text;
    v_leaf_part   text;
    v_cur_month   integer;
    v_next_month  integer;
    v_created     integer := 0;
BEGIN
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'p_tenant_id must not be null'
            USING ERRCODE = 'null_value_not_allowed';
    END IF;
    IF p_from_month > p_to_month THEN
        RAISE EXCEPTION 'p_from_month (%) must be <= p_to_month (%)', p_from_month, p_to_month
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    v_tenant_hex  := replace(p_tenant_id::text, '-', '');
    v_parent_part := 'ledger_entry_t_' || v_tenant_hex;

    -- 4.1 Tạo phân vùng tầng 1 theo LIST (tenant_id) nếu chưa có
    IF to_regclass('ledger.' || v_parent_part) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE ledger.%I PARTITION OF ledger.ledger_entry FOR VALUES IN (%L) PARTITION BY RANGE (recorded_month)',
            v_parent_part,
            p_tenant_id
        );
        v_created := v_created + 1;
    END IF;

    -- Đảm bảo RLS và thu hồi quyền sửa/xoá trên phân vùng tầng 1
    EXECUTE format('ALTER TABLE ledger.%I ENABLE ROW LEVEL SECURITY', v_parent_part);
    EXECUTE format('ALTER TABLE ledger.%I FORCE ROW LEVEL SECURITY', v_parent_part);
    EXECUTE format(
        'DROP POLICY IF EXISTS %I ON ledger.%I',
        'rls_' || v_parent_part || '__tenant',
        v_parent_part
    );
    EXECUTE format(
        'CREATE POLICY %I ON ledger.%I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
        'rls_' || v_parent_part || '__tenant',
        v_parent_part
    );
    EXECUTE format(
        'REVOKE ALL ON TABLE ledger.%I FROM PUBLIC, svc_ledger, ro_ledger, cdc_ledger',
        v_parent_part
    );
    EXECUTE format(
        'REVOKE UPDATE, DELETE, TRUNCATE ON TABLE ledger.%I FROM mig_ledger',
        v_parent_part
    );
    EXECUTE format(
        'DROP TRIGGER IF EXISTS %I ON ledger.%I',
        'tg_' || v_parent_part || '__no_truncate',
        v_parent_part
    );
    EXECUTE format(
        'CREATE TRIGGER %I BEFORE TRUNCATE ON ledger.%I FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable()',
        'tg_' || v_parent_part || '__no_truncate',
        v_parent_part
    );

    -- 4.2 Tạo các phân vùng lá theo RANGE (recorded_month)
    v_cur_month := p_from_month;
    WHILE v_cur_month <= p_to_month LOOP
        v_next_month := ledger.fn_next_recorded_month(v_cur_month);
        v_leaf_part  := v_parent_part || '_m' || v_cur_month::text;

        IF to_regclass('ledger.' || v_leaf_part) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE ledger.%I PARTITION OF ledger.%I FOR VALUES FROM (%s) TO (%s)',
                v_leaf_part,
                v_parent_part,
                v_cur_month,
                v_next_month
            );
            v_created := v_created + 1;
        END IF;

        EXECUTE format('ALTER TABLE ledger.%I ENABLE ROW LEVEL SECURITY', v_leaf_part);
        EXECUTE format('ALTER TABLE ledger.%I FORCE ROW LEVEL SECURITY', v_leaf_part);
        EXECUTE format(
            'DROP POLICY IF EXISTS %I ON ledger.%I',
            'rls_' || v_leaf_part || '__tenant',
            v_leaf_part
        );
        EXECUTE format(
            'CREATE POLICY %I ON ledger.%I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            'rls_' || v_leaf_part || '__tenant',
            v_leaf_part
        );
        EXECUTE format(
            'REVOKE ALL ON TABLE ledger.%I FROM PUBLIC, svc_ledger, ro_ledger, cdc_ledger',
            v_leaf_part
        );
        EXECUTE format(
            'REVOKE UPDATE, DELETE, TRUNCATE ON TABLE ledger.%I FROM mig_ledger',
            v_leaf_part
        );
        EXECUTE format(
            'DROP TRIGGER IF EXISTS %I ON ledger.%I',
            'tg_' || v_leaf_part || '__no_truncate',
            v_leaf_part
        );
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE TRUNCATE ON ledger.%I FOR EACH STATEMENT EXECUTE FUNCTION ledger.fn_ledger_immutable()',
            'tg_' || v_leaf_part || '__no_truncate',
            v_leaf_part
        );

        v_cur_month := v_next_month;
    END LOOP;

    RETURN v_created;
END;
$$;

-- -----------------------------------------------------------------------------
-- 5. Hàm quét toàn bộ phân vùng tenant hiện có và tạo trước phân vùng tháng
--    hiện tại + tháng kế tiếp.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger.fn_ensure_upcoming_month_partitions(
    p_current_month integer,
    p_next_month    integer
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ledger, pg_catalog
AS $$
DECLARE
    v_rec        record;
    v_tenant_id uuid;
    v_total     integer := 0;
BEGIN
    FOR v_rec IN
        SELECT c.relname AS part_name
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = 'ledger'
           AND p.relname = 'ledger_entry'
           AND c.relname LIKE 'ledger_entry_t_%'
         ORDER BY c.relname
    LOOP
        v_tenant_id := substring(v_rec.part_name FROM '^ledger_entry_t_([0-9a-f]{32})$')::uuid;
        v_total := v_total + ledger.fn_ensure_tenant_partitions(v_tenant_id, p_current_month, p_next_month);
    END LOOP;

    RETURN v_total;
END;
$$;

-- -----------------------------------------------------------------------------
-- 6. Chuyển quyền sở hữu về mig_ledger và phân quyền chặt chẽ theo ma trận
--    SEC-06 / TL-03 §12.2
-- -----------------------------------------------------------------------------
ALTER SCHEMA ledger OWNER TO mig_ledger;
ALTER TABLE ledger.ledger_entry OWNER TO mig_ledger;
ALTER TABLE ledger.chain_head OWNER TO mig_ledger;
ALTER TABLE ledger.cell_seal OWNER TO mig_ledger;
ALTER TABLE ledger.merkle_seal OWNER TO mig_ledger;
ALTER TABLE ledger.anchor_receipt OWNER TO mig_ledger;
ALTER TABLE ledger.verify_run OWNER TO mig_ledger;
ALTER TABLE ledger.archive_segment OWNER TO mig_ledger;
ALTER TABLE ledger.outbox_event OWNER TO mig_ledger;
ALTER TABLE ledger.outbox_event_default OWNER TO mig_ledger;
ALTER FUNCTION ledger.fn_ledger_immutable() OWNER TO mig_ledger;
ALTER FUNCTION ledger.fn_next_recorded_month(integer) OWNER TO mig_ledger;
ALTER FUNCTION ledger.fn_ensure_tenant_partitions(uuid, integer, integer) OWNER TO mig_ledger;
ALTER FUNCTION ledger.fn_ensure_upcoming_month_partitions(integer, integer) OWNER TO mig_ledger;

REVOKE ALL ON SCHEMA ledger FROM PUBLIC;
GRANT USAGE ON SCHEMA ledger TO mig_ledger, svc_ledger, ro_ledger, cdc_ledger;

REVOKE ALL ON ALL TABLES IN SCHEMA ledger FROM PUBLIC, svc_ledger, ro_ledger, cdc_ledger;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ledger FROM PUBLIC, svc_ledger, ro_ledger, cdc_ledger;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC, svc_ledger, ro_ledger, cdc_ledger;

-- Quyền cho svc_ledger (append-only trên các bảng bất biến, cập nhật trạng thái trên chain_head/verify_run/archive_segment)
GRANT SELECT, INSERT ON TABLE
    ledger.ledger_entry,
    ledger.cell_seal,
    ledger.merkle_seal,
    ledger.anchor_receipt,
    ledger.outbox_event,
    ledger.outbox_event_default
TO svc_ledger;

GRANT SELECT, INSERT, UPDATE ON TABLE
    ledger.chain_head,
    ledger.verify_run,
    ledger.archive_segment
TO svc_ledger;

-- Quyền cho ro_ledger (chỉ đọc các bảng nghiệp vụ sổ cái)
GRANT SELECT ON TABLE
    ledger.ledger_entry,
    ledger.chain_head,
    ledger.cell_seal,
    ledger.merkle_seal,
    ledger.anchor_receipt,
    ledger.verify_run,
    ledger.archive_segment
TO ro_ledger;

-- Quyền cho cdc_ledger (chỉ đọc ledger_entry và outbox_event phục vụ CDC/archiver)
GRANT SELECT ON TABLE
    ledger.ledger_entry,
    ledger.outbox_event,
    ledger.outbox_event_default
TO cdc_ledger;

-- Quyền thực thi các hàm quản lý phân vùng và tiện ích
GRANT EXECUTE ON FUNCTION ledger.fn_next_recorded_month(integer) TO mig_ledger, svc_ledger, ro_ledger;
GRANT EXECUTE ON FUNCTION ledger.fn_ensure_tenant_partitions(uuid, integer, integer) TO mig_ledger, svc_ledger;
GRANT EXECUTE ON FUNCTION ledger.fn_ensure_upcoming_month_partitions(integer, integer) TO mig_ledger, svc_ledger;

-- Thu hồi vĩnh viễn quyền UPDATE, DELETE, TRUNCATE trên các bảng bất biến từ các role ứng dụng/CDC/đọc.
-- Với mig_ledger (owner): thu hồi DELETE, TRUNCATE và giữ ACL UPDATE trên cell_seal/merkle_seal để
-- trigger kiểm tra khoá ngoại nội bộ của PostgreSQL (SELECT ... FOR KEY SHARE) hoạt động khi INSERT,
-- trong khi mọi câu lệnh UPDATE/DELETE/TRUNCATE trực tiếp của mig_ledger đều bị chặn bởi trigger
-- tg_*__no_update / tg_*__no_truncate với mã lỗi 42501 (insufficient_privilege).
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE
    ledger.ledger_entry,
    ledger.cell_seal,
    ledger.merkle_seal,
    ledger.anchor_receipt,
    ledger.outbox_event,
    ledger.outbox_event_default
FROM PUBLIC, svc_ledger, ro_ledger, cdc_ledger;

REVOKE UPDATE, DELETE, TRUNCATE ON TABLE
    ledger.ledger_entry,
    ledger.anchor_receipt,
    ledger.outbox_event,
    ledger.outbox_event_default
FROM mig_ledger;

REVOKE DELETE, TRUNCATE ON TABLE
    ledger.cell_seal,
    ledger.merkle_seal
FROM mig_ledger;
