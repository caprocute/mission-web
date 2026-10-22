-- CORE-04-01 · Bảng period_state và trigger chặn ghi dữ liệu kỳ đã khoá của schema product (TL-03 "Bảo vệ dữ liệu kỳ
-- đã khoá ở tầng CSDL"). Lớp kiểm tra thứ nhất là bộ đệm trạng thái kỳ trong ứng dụng (CORE-08-03); lớp này chặn
-- mọi đường ghi vòng qua ứng dụng. period_state là bản sao cục bộ của config.period.status, do consumer
-- PeriodStateListener cập nhật từ EVT-09 (DA_KHOA), EVT-22 (MO_LAI_HAU_KIEM), EVT-26 (MO).
--
-- Khác DDL mẫu: CHECK status theo baseline 9.1; bật RLS tenant như mọi bảng có tenant_id; điều kiện mở lại
-- dùng nullif(current_setting(...), '') vì sau SET LOCAL của giao dịch trước, biến phiên trả chuỗi rỗng chứ
-- không trả NULL. Trigger gắn BEFORE INSERT OR UPDATE vào mọi bảng có period_id của schema.

-- ---------------------------------------------------------------- period_state
CREATE TABLE ${flyway:defaultSchema}.period_state (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    period_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    source_event_id uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_period_state PRIMARY KEY (tenant_id, period_id),
    CONSTRAINT ck_period_state__status CHECK (status IN ('MO', 'DANG_KHOA', 'DA_KHOA', 'MO_LAI_HAU_KIEM'))
);

COMMENT ON TABLE ${flyway:defaultSchema}.period_state IS 'Bản sao cục bộ trạng thái kỳ để trigger chặn ghi kỳ đã khoá';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.status IS '{"cls":"C1","desc":"Trạng thái kỳ (baseline 9.1)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.source_event_id IS '{"cls":"C1","desc":"Sự kiện cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.updated_at IS '{"cls":"C1"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_state');
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.period_state FROM svc_${flyway:defaultSchema};

-- ---------------------------------------------------------------- fn_guard_period_write
CREATE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_status varchar(16);
BEGIN
    SELECT ps.status INTO v_status
      FROM ${flyway:defaultSchema}.period_state ps
     WHERE ps.tenant_id = NEW.tenant_id AND ps.period_id = NEW.period_id;
    IF v_status IN ('DANG_KHOA', 'DA_KHOA') THEN
        RAISE EXCEPTION 'Kỳ % đang khoá hoặc đã khoá, không được ghi', NEW.period_id
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF v_status = 'MO_LAI_HAU_KIEM'
       AND nullif(current_setting('app.reopen_request_id', true), '') IS NULL THEN
        RAISE EXCEPTION 'Kỳ đang mở lại hậu kiểm: chỉ ghi kèm mã yêu cầu mở lại đã được duyệt'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER tg_deliverable__period_guard BEFORE INSERT OR UPDATE ON ${flyway:defaultSchema}.deliverable
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_evidence__period_guard BEFORE INSERT OR UPDATE ON ${flyway:defaultSchema}.evidence
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_evidence_version__period_guard BEFORE INSERT OR UPDATE ON ${flyway:defaultSchema}.evidence_version
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_acceptance__period_guard BEFORE INSERT OR UPDATE ON ${flyway:defaultSchema}.acceptance
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
