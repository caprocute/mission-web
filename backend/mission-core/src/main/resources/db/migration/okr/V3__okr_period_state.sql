-- CORE-04-01 · Lớp bảo vệ thứ hai ở CSDL cho dữ liệu kỳ đã khoá của schema okr (TL-03 "Bảo vệ dữ liệu
-- kỳ đã khoá ở tầng CSDL"). okr.period_state là bản sao rất nhỏ của config.period.status, do consumer
-- EVT-09, EVT-22, EVT-26 của module cập nhật (PeriodStateProjection); trigger tra bảng trước mỗi lần ghi.
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 50):
--   * Trigger gắn vào mọi bảng có period_id của schema và chặn cả DELETE (TL-03 mẫu chỉ có task.task và
--     INSERT task.task_progress). Danh sách bảng: objective, objective_review, key_result, okr_link, kr_checkin.
--   * Kiểm app.reopen_request_id bằng nullif(..., '') vì biến tuỳ chỉnh đã đặt trong phiên trả chuỗi rỗng
--     sau khi giao dịch kết thúc (mẫu TL-03 chỉ so IS NULL).
--   * updated_at là thời điểm sự kiện nguồn để consumer bỏ qua sự kiện đến muộn; thêm CHECK trạng thái.

CREATE TABLE ${flyway:defaultSchema}.period_state (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    period_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    source_event_id uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_period_state PRIMARY KEY (tenant_id, period_id),
    CONSTRAINT ck_period_state__status CHECK (status IN ('MO', 'DANG_KHOA', 'DA_KHOA', 'MO_LAI_HAU_KIEM'))
);

COMMENT ON TABLE ${flyway:defaultSchema}.period_state IS 'Bản sao trạng thái kỳ để trigger chặn ghi dữ liệu kỳ đã khoá';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.period_id IS '{"cls":"C1","desc":"Kỳ (config.period)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.status IS '{"cls":"C1","desc":"Trạng thái kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.source_event_id IS '{"cls":"C1","desc":"Sự kiện đã cập nhật dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_state.updated_at IS '{"cls":"C1","desc":"Thời điểm của sự kiện nguồn"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_state');
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.period_state FROM svc_${flyway:defaultSchema};

CREATE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_row    record;
    v_status varchar(16);
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT ps.status INTO v_status
      FROM ${flyway:defaultSchema}.period_state ps
     WHERE ps.tenant_id = v_row.tenant_id AND ps.period_id = v_row.period_id;
    IF v_status IN ('DANG_KHOA', 'DA_KHOA') THEN
        RAISE EXCEPTION 'Kỳ % đang khoá hoặc đã khoá, không được ghi', v_row.period_id
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF v_status = 'MO_LAI_HAU_KIEM'
       AND nullif(current_setting('app.reopen_request_id', true), '') IS NULL THEN
        RAISE EXCEPTION 'Kỳ đang mở lại hậu kiểm: chỉ ghi kèm mã yêu cầu mở lại đã được duyệt'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN v_row;
END
$fn$;

CREATE TRIGGER tg_objective__period_guard BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.objective
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_objective_review__period_guard BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.objective_review
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_key_result__period_guard BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.key_result
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_okr_link__period_guard BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.okr_link
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
CREATE TRIGGER tg_kr_checkin__period_guard BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.kr_checkin
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();
