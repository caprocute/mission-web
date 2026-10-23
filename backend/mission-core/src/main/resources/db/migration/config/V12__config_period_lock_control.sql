-- CORE-08-05 (phần điều khiển) · Tạm dừng, chạy lại hồ sơ lỗi, đề nghị và duyệt huỷ saga chốt kỳ (QT-08,
-- SCR-0801, BR-08-02, BR-08-09, BR-08-10). TL-03 bảng config.period_lock_order chỉ có cancel_requested_by,
-- cancel_approved_by; các cột thời điểm, lý do huỷ và cờ tạm dừng là bổ sung cho hợp đồng
-- PeriodLockProgress.pauseRequested, PeriodLockCancelState (suy luận cột).
-- Bốn bảng chiếu mới (bổ sung, suy luận) phục vụ SCR-0801 trường 10, 11, 13: MOD-08 không đọc
-- scoring.score_job, flowable.saga_step hay ledger.ledger_entry của dịch vụ khác (ADR-02), nên giữ bản chiếu
-- được cập nhật từ phản hồi của saga; period_lock_command là hộp lệnh điều khiển chờ SVC-06 nhận, vì
-- platform.yaml chưa có thao tác điều khiển saga (DV-06-06, DV-06-07).

ALTER TABLE ${flyway:defaultSchema}.period_lock_order
    ADD COLUMN pause_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN pause_requested_by uuid,
    ADD COLUMN pause_requested_at timestamptz,
    ADD COLUMN cancel_requested_at timestamptz,
    ADD COLUMN cancel_reason varchar(2000),
    ADD COLUMN cancel_approved_at timestamptz,
    ADD CONSTRAINT ck_period_lock_order__cancel_approved CHECK (cancel_approved_by IS NULL OR cancel_requested_by IS NOT NULL);
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.pause_requested IS '{"cls": "C1", "desc": "Đã đặt cờ tạm dừng sau bước hiện tại (suy luận cột)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.pause_requested_by IS '{"cls": "C1", "desc": "Người đặt cờ tạm dừng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.pause_requested_at IS '{"cls": "C1", "desc": "Thời điểm đặt cờ tạm dừng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.cancel_requested_at IS '{"cls": "C1", "desc": "Thời điểm đề nghị huỷ và bù trừ (suy luận cột)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.cancel_reason IS '{"cls": "C1", "desc": "Lý do đề nghị huỷ và bù trừ (suy luận cột)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_order.cancel_approved_at IS '{"cls": "C1", "desc": "Thời điểm người duyệt thứ hai duyệt huỷ (suy luận cột)"}';

CREATE TABLE ${flyway:defaultSchema}.period_lock_batch (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    lock_order_id uuid NOT NULL,
    unit_id uuid,
    profile_count integer NOT NULL DEFAULT 0,
    status varchar(12) NOT NULL DEFAULT 'CHO',
    attempts integer NOT NULL DEFAULT 0,
    finished_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_period_lock_batch PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_batch__period_lock_order FOREIGN KEY (lock_order_id) REFERENCES ${flyway:defaultSchema}.period_lock_order (id),
    CONSTRAINT ck_period_lock_batch__status CHECK (status IN ('CHO', 'DANG_CHAY', 'XONG', 'LOI')),
    CONSTRAINT ck_period_lock_batch__counts CHECK (profile_count >= 0 AND attempts >= 0)
);
CREATE INDEX ix_period_lock_batch__order ON ${flyway:defaultSchema}.period_lock_batch (tenant_id, lock_order_id, id);
COMMENT ON TABLE ${flyway:defaultSchema}.period_lock_batch IS 'Bản chiếu lô tính điểm scoring.score_job của saga chốt kỳ cho SCR-0801 (bổ sung, suy luận)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.id IS '{"cls": "C1", "desc": "Mã lô (scoring.score_job.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.lock_order_id IS '{"cls": "C1", "desc": "Lệnh khoá kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.unit_id IS '{"cls": "C1", "desc": "Đơn vị của lô"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.profile_count IS '{"cls": "C1", "desc": "Số hồ sơ trong lô"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.status IS '{"cls": "C1", "desc": "Trạng thái lô: CHO, DANG_CHAY, XONG, LOI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.attempts IS '{"cls": "C1", "desc": "Số lần thử"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.finished_at IS '{"cls": "C1", "desc": "Thời điểm lô xong"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_batch.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật bản chiếu"}';

CREATE TABLE ${flyway:defaultSchema}.period_lock_failure (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    lock_order_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    person_id uuid,
    unit_id uuid,
    batch_id uuid,
    error varchar(500) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    last_attempt_at timestamptz,
    resolved_at timestamptz,
    CONSTRAINT pk_period_lock_failure PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_failure__period_lock_order FOREIGN KEY (lock_order_id) REFERENCES ${flyway:defaultSchema}.period_lock_order (id),
    CONSTRAINT uq_period_lock_failure__profile UNIQUE (lock_order_id, profile_id),
    CONSTRAINT ck_period_lock_failure__attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_period_lock_failure__open ON ${flyway:defaultSchema}.period_lock_failure (tenant_id, lock_order_id, profile_id)
  WHERE resolved_at IS NULL;
COMMENT ON TABLE ${flyway:defaultSchema}.period_lock_failure IS 'Bản chiếu hàng đợi hồ sơ lỗi của bước tính điểm saga chốt kỳ (bổ sung, suy luận)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.id IS '{"cls": "C1", "desc": "Mã dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.lock_order_id IS '{"cls": "C1", "desc": "Lệnh khoá kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.profile_id IS '{"cls": "C1", "desc": "Hồ sơ lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.person_id IS '{"cls": "C1", "desc": "Người được đánh giá (chỉ mã)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.unit_id IS '{"cls": "C1", "desc": "Đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.batch_id IS '{"cls": "C1", "desc": "Lô tính điểm gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.error IS '{"cls": "C1", "desc": "Mô tả lỗi cho người dùng, không chứa thông tin nội bộ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.attempts IS '{"cls": "C1", "desc": "Số lần thử"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.last_attempt_at IS '{"cls": "C1", "desc": "Lần thử gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_failure.resolved_at IS '{"cls": "C1", "desc": "Thời điểm hồ sơ ra khỏi hàng đợi lỗi"}';

CREATE TABLE ${flyway:defaultSchema}.period_lock_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    lock_order_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    kind varchar(24) NOT NULL,
    event varchar(1000) NOT NULL,
    evt_code varchar(8),
    actor_id uuid,
    ledger_entry_id uuid,
    CONSTRAINT pk_period_lock_event PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_event__period_lock_order FOREIGN KEY (lock_order_id) REFERENCES ${flyway:defaultSchema}.period_lock_order (id)
);
CREATE INDEX ix_period_lock_event__order ON ${flyway:defaultSchema}.period_lock_event (tenant_id, lock_order_id, occurred_at DESC, id DESC);
COMMENT ON TABLE ${flyway:defaultSchema}.period_lock_event IS 'Nhật ký sự kiện saga chốt kỳ cho SCR-0801 trường 13: hành động điều khiển do MOD-08 ghi và bản chiếu sổ cái của saga (bổ sung, suy luận)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.lock_order_id IS '{"cls": "C1", "desc": "Lệnh khoá kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.occurred_at IS '{"cls": "C1", "desc": "Thời điểm xảy ra"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.kind IS '{"cls": "C1", "desc": "Loại: hành động điều khiển hoặc bước saga"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.event IS '{"cls": "C1", "desc": "Mô tả sự kiện, tiếng Việt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.evt_code IS '{"cls": "C1", "desc": "Mã EVT nếu có"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.actor_id IS '{"cls": "C1", "desc": "Người thực hiện; rỗng khi do hệ thống"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_event.ledger_entry_id IS '{"cls": "C1", "desc": "Dòng sổ cái tương ứng khi đã có (ledger.ledger_entry.id)"}';

CREATE TABLE ${flyway:defaultSchema}.period_lock_command (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_order_id uuid NOT NULL,
    saga_id uuid NOT NULL,
    command varchar(16) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(12) NOT NULL DEFAULT 'CHO',
    delivered_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT pk_period_lock_command PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_command__period_lock_order FOREIGN KEY (lock_order_id) REFERENCES ${flyway:defaultSchema}.period_lock_order (id),
    CONSTRAINT ck_period_lock_command__command CHECK (command IN ('TAM_DUNG', 'TIEP_TUC', 'CHAY_LAI_LOI', 'CHAP_NHAN_LOI', 'BU_TRU')),
    CONSTRAINT ck_period_lock_command__status CHECK (status IN ('CHO', 'DANG_CHAY', 'HOAN_TAT', 'THAT_BAI'))
);
CREATE INDEX ix_period_lock_command__pending ON ${flyway:defaultSchema}.period_lock_command (tenant_id, created_at)
  WHERE delivered_at IS NULL;
COMMENT ON TABLE ${flyway:defaultSchema}.period_lock_command IS 'Hộp lệnh điều khiển saga chốt kỳ chờ SVC-06 nhận; mã dòng là mã tác vụ ở /api/v1/jobs/{id} (bổ sung, suy luận)';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.id IS '{"cls": "C1", "desc": "Mã lệnh, cũng là mã tác vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.created_at IS '{"cls": "C1", "desc": "Thời điểm ghi nhận lệnh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.created_by IS '{"cls": "C1", "desc": "Người ra lệnh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.lock_order_id IS '{"cls": "C1", "desc": "Lệnh khoá kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.saga_id IS '{"cls": "C1", "desc": "Saga chốt kỳ (flowable.saga_instance.id)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.command IS '{"cls": "C1", "desc": "Lệnh: TAM_DUNG, TIEP_TUC, CHAY_LAI_LOI, CHAP_NHAN_LOI, BU_TRU"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.payload IS '{"cls": "C1", "desc": "Tham số lệnh (danh sách hồ sơ chạy lại, ghi chú, người đề nghị huỷ với BU_TRU)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.status IS '{"cls": "C1", "desc": "Trạng thái tác vụ: CHO, DANG_CHAY, HOAN_TAT, THAT_BAI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.delivered_at IS '{"cls": "C1", "desc": "Thời điểm SVC-06 nhận lệnh"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.period_lock_command.finished_at IS '{"cls": "C1", "desc": "Thời điểm tác vụ kết thúc"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_lock_batch');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_lock_failure');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_lock_event');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.period_lock_command');
