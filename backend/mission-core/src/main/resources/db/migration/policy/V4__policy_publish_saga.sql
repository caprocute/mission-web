-- CORE-05-10 · QT-15 ngày 4–5, EVT-13: trạng thái saga phát hành Policy Pack (bảng bổ sung, suy luận,
-- wiki 14). TL-03 flowable.saga_instance chưa có loại saga phát hành Policy Pack, nên MOD-05 điều phối
-- saga trong tiến trình và giữ trạng thái bền ở đây, cùng bộ trạng thái và cột như saga_instance /
-- saga_step. Mỗi dòng là một lần chạy (mã dòng là mã tác vụ POLICY_PUBLISH); context ghi các
-- policy_binding đã ghi và phiên bản cũ của chúng để bù trừ hoặc thu hồi.

CREATE TABLE ${flyway:defaultSchema}.publish_saga (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_version_id uuid NOT NULL,
    publish_request_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DANG_CHAY',
    current_step_no integer NOT NULL DEFAULT 1,
    step_code varchar(48) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(2000),
    context jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT pk_publish_saga PRIMARY KEY (id),
    CONSTRAINT fk_publish_saga__policy_pack_version FOREIGN KEY (policy_pack_version_id) REFERENCES ${flyway:defaultSchema}.policy_pack_version (id),
    CONSTRAINT fk_publish_saga__publish_request FOREIGN KEY (publish_request_id) REFERENCES ${flyway:defaultSchema}.publish_request (id),
    CONSTRAINT ck_publish_saga__status CHECK (status IN ('DANG_CHAY', 'CHO_SU_KIEN', 'HOAN_TAT', 'DANG_BU_TRU', 'DA_BU_TRU', 'LOI_CAN_XU_LY')),
    CONSTRAINT ck_publish_saga__step CHECK (current_step_no BETWEEN 1 AND 5),
    CONSTRAINT ck_publish_saga__finished CHECK (status NOT IN ('HOAN_TAT', 'DA_BU_TRU') OR finished_at IS NOT NULL)
);
CREATE UNIQUE INDEX ix_publish_saga__running ON ${flyway:defaultSchema}.publish_saga (tenant_id, policy_pack_version_id) WHERE status IN ('DANG_CHAY', 'CHO_SU_KIEN', 'DANG_BU_TRU');
CREATE INDEX ix_publish_saga__version ON ${flyway:defaultSchema}.publish_saga (tenant_id, policy_pack_version_id, started_at);
COMMENT ON TABLE ${flyway:defaultSchema}.publish_saga IS 'Lần chạy saga phát hành phiên bản Policy Pack: bước hiện tại, số lần thử, lỗi, gắn kỳ đã ghi';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.id IS '{"cls": "C1", "desc": "Mã lần chạy, cũng là mã tác vụ POLICY_PUBLISH"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.created_at IS '{"cls": "C1", "desc": "Thời điểm khởi chạy"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.created_by IS '{"cls": "C1", "desc": "Người khởi chạy (phê duyệt hoặc phát hành lại)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản đang phát hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.publish_request_id IS '{"cls": "C1", "desc": "Yêu cầu phát hành đã duyệt (kỳ, phạm vi)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.status IS '{"cls": "C1", "desc": "Trạng thái saga (bộ trạng thái của flowable.saga_instance)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.current_step_no IS '{"cls": "C1", "desc": "Bước hiện tại 1..5"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.step_code IS '{"cls": "C1", "desc": "Mã bước hiện tại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.attempts IS '{"cls": "C1", "desc": "Số lần thử của bước hiện tại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.last_error IS '{"cls": "C1", "desc": "Lỗi gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.context IS '{"cls": "C1", "desc": "Gắn kỳ đã ghi kèm phiên bản cũ, cờ đã kích hoạt luồng duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.started_at IS '{"cls": "C1", "desc": "Thời điểm bắt đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_saga.finished_at IS '{"cls": "C1", "desc": "Thời điểm kết thúc"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.publish_saga');
