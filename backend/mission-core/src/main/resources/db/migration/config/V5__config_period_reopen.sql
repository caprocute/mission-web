-- CORE-08-06 · Bản chiếu phạm vi mở lại hậu kiểm của kỳ (QT-13 bước 8, BR-13-05).
-- Nguồn sự thật là oversight.reopen_request của MOD-07; MOD-08 giữ bản chiếu nhận từ EVT-22 để nạp vào
-- đệm kiểm tra trạng thái kỳ và chạy timer khoá lại. Bảng bổ sung (suy luận), xem wiki trang 14.
CREATE TABLE ${flyway:defaultSchema}.period_reopen (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    scope varchar(12) NOT NULL,
    scope_ids uuid[] NOT NULL DEFAULT '{}',
    opened_at timestamptz NOT NULL,
    open_until timestamptz NOT NULL,
    relock_started_at timestamptz,
    relocked_at timestamptz,
    CONSTRAINT pk_period_reopen PRIMARY KEY (id),
    CONSTRAINT fk_period_reopen__period FOREIGN KEY (period_id) REFERENCES ${flyway:defaultSchema}.period (id),
    CONSTRAINT ck_period_reopen__scope CHECK (scope IN ('TOAN_KY', 'DON_VI', 'HO_SO')),
    CONSTRAINT ck_period_reopen__scope_ids CHECK (scope = 'TOAN_KY' OR cardinality(scope_ids) > 0),
    CONSTRAINT ck_period_reopen__open_until CHECK (open_until > opened_at)
);
-- Mỗi kỳ chỉ có một lần mở lại chưa khoá lại.
CREATE UNIQUE INDEX ix_period_reopen__period_open ON ${flyway:defaultSchema}.period_reopen (tenant_id, period_id)
  WHERE relocked_at IS NULL;
CREATE INDEX ix_period_reopen__due ON ${flyway:defaultSchema}.period_reopen (tenant_id, open_until)
  WHERE relocked_at IS NULL;
COMMENT ON TABLE ${flyway:defaultSchema}.period_reopen IS 'Bản chiếu phạm vi và thời hạn mở lại hậu kiểm của kỳ, nhận từ EVT-22 (suy luận)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.id IS '{"cls": "C1", "desc": "Mã yêu cầu mở lại (oversight.reopen_request.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.created_by IS '{"cls": "C1", "desc": "Người hoặc tài khoản dịch vụ tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.period_id IS '{"cls": "C1", "desc": "Kỳ được mở lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.scope IS '{"cls": "C1", "desc": "Phạm vi: TOAN_KY, DON_VI, HO_SO"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.scope_ids IS '{"cls": "C1", "desc": "Đơn vị hoặc hồ sơ trong phạm vi; rỗng khi TOAN_KY", "type_exception": "TL-03 oversight.reopen_request.scope_ids ghi uuid[]"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.opened_at IS '{"cls": "C1", "desc": "Thời điểm kỳ chuyển MO_LAI_HAU_KIEM"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.open_until IS '{"cls": "C1", "desc": "Hạn mở lại, quá hạn tự khoá lại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.relock_started_at IS '{"cls": "C1", "desc": "Thời điểm bắt đầu khoá lại (timer hoặc hậu kiểm xong)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_reopen.relocked_at IS '{"cls": "C1", "desc": "Thời điểm kỳ trở lại DA_KHOA"}';
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_reopen');
