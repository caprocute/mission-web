-- CORE-05-09 · QT-15 bước trình và phê duyệt phát hành Policy Pack (bảng bổ sung, suy luận, wiki 14).
-- TL-03 không có chỗ giữ kỳ hiệu lực và phạm vi áp dụng người soạn chọn khi gửi phê duyệt trong lúc
-- chờ ký; saga phát hành (CORE-05-10) đọc bảng này để sinh policy_binding. Mỗi phiên bản có nhiều
-- nhất một yêu cầu đang chờ.

CREATE TABLE ${flyway:defaultSchema}.publish_request (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    policy_pack_version_id uuid NOT NULL,
    period_id uuid NOT NULL,
    scopes jsonb NOT NULL,
    note text,
    status varchar(16) NOT NULL DEFAULT 'CHO_PHE_DUYET',
    decided_by uuid,
    decided_at timestamptz,
    decision_reason text,
    CONSTRAINT pk_publish_request PRIMARY KEY (id),
    CONSTRAINT fk_publish_request__policy_pack_version FOREIGN KEY (policy_pack_version_id) REFERENCES ${flyway:defaultSchema}.policy_pack_version (id),
    CONSTRAINT ck_publish_request__status CHECK (status IN ('CHO_PHE_DUYET', 'DA_DUYET', 'TU_CHOI')),
    CONSTRAINT ck_publish_request__decided CHECK (status = 'CHO_PHE_DUYET' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_publish_request__four_eyes CHECK (decided_by IS NULL OR status <> 'DA_DUYET' OR decided_by <> created_by)
);
CREATE UNIQUE INDEX ix_publish_request__pending ON ${flyway:defaultSchema}.publish_request (tenant_id, policy_pack_version_id) WHERE status = 'CHO_PHE_DUYET';
CREATE INDEX ix_publish_request__version ON ${flyway:defaultSchema}.publish_request (tenant_id, policy_pack_version_id, created_at);
COMMENT ON TABLE ${flyway:defaultSchema}.publish_request IS 'Yêu cầu phát hành phiên bản Policy Pack: kỳ hiệu lực, phạm vi và quyết định phê duyệt';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.created_at IS '{"cls": "C1", "desc": "Thời điểm gửi phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.created_by IS '{"cls": "C1", "desc": "Người gửi phê duyệt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.policy_pack_version_id IS '{"cls": "C1", "desc": "Phiên bản gửi phát hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.period_id IS '{"cls": "C1", "desc": "Kỳ hiệu lực (config.period, tham chiếu logic)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.scopes IS '{"cls": "C1", "desc": "Phạm vi áp dụng: luồng, nhóm đối tượng, đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.note IS '{"cls": "C1", "desc": "Ghi chú của người gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.status IS '{"cls": "C1", "desc": "Trạng thái yêu cầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.decided_by IS '{"cls": "C1", "desc": "Người phê duyệt hoặc từ chối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.decided_at IS '{"cls": "C1", "desc": "Thời điểm quyết định"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.publish_request.decision_reason IS '{"cls": "C1", "desc": "Ý kiến khi phê duyệt hoặc từ chối"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.publish_request');
