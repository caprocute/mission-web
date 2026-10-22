-- CORE-08-16 · MOD-08 quản trị vai trò và uỷ quyền theo WEB-058 (đã duyệt 19/10/2026, phương án A):
-- config.role_binding là nguồn sự thật của vai trò. Trạng thái chờ duyệt là approved_by rỗng, không có
-- cột status; bổ sung cột vết cấp, duyệt, kết thúc (THU_HOI, TU_CHOI). Bảng mới config.delegation dùng
-- chung cho duyệt thay, nghiệm thu thay, check-in KR thay (BR-01-13 đến BR-01-18).

ALTER TABLE ${flyway:defaultSchema}.role_binding
    ADD COLUMN reason varchar(500),
    ADD COLUMN granted_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN ended_by uuid,
    ADD COLUMN ended_at timestamptz,
    ADD COLUMN end_kind varchar(8),
    ADD COLUMN end_reason varchar(500),
    ADD COLUMN expiry_emitted_at timestamptz,
    ADD CONSTRAINT ck_role_binding__end_kind CHECK (end_kind IS NULL OR end_kind IN ('THU_HOI', 'TU_CHOI')),
    ADD CONSTRAINT ck_role_binding__ended CHECK ((end_kind IS NULL) = (ended_at IS NULL)),
    ADD CONSTRAINT ck_role_binding__self_grant CHECK (granted_by <> person_id AND (approved_by IS NULL OR approved_by <> person_id));

COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.reason IS '{"cls": "C1", "desc": "Lý do, căn cứ cấp vai trò"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.granted_at IS '{"cls": "C1", "desc": "Thời điểm cấp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.approved_at IS '{"cls": "C1", "desc": "Thời điểm duyệt thứ hai"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.ended_by IS '{"cls": "C1", "desc": "Người thu hồi hoặc từ chối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.ended_at IS '{"cls": "C1", "desc": "Thời điểm thu hồi hoặc từ chối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.end_kind IS '{"cls": "C1", "desc": "Cách kết thúc trước hạn: THU_HOI, TU_CHOI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.end_reason IS '{"cls": "C1", "desc": "Lý do thu hồi hoặc từ chối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.role_binding.expiry_emitted_at IS '{"cls": "C1", "desc": "Cột kỹ thuật: thời điểm tác vụ hết hạn đã phát EVT-27 HET_HAN_VAI_TRO (chống phát lại)"}';

-- Tác vụ hết hạn và đồng bộ chỉ quét bản ghi chưa kết thúc.
CREATE INDEX ix_role_binding__open ON ${flyway:defaultSchema}.role_binding (tenant_id, valid_to)
  WHERE end_kind IS NULL AND expiry_emitted_at IS NULL;

CREATE TABLE ${flyway:defaultSchema}.delegation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    kind varchar(16) NOT NULL,
    delegator_id uuid NOT NULL,
    delegate_id uuid NOT NULL,
    org_unit_id uuid NOT NULL,
    subject_id uuid,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    ended_by uuid,
    ended_at timestamptz,
    end_reason varchar(500),
    expiry_emitted_at timestamptz,
    CONSTRAINT pk_delegation PRIMARY KEY (id),
    CONSTRAINT ck_delegation__kind CHECK (kind IN ('DUYET_THAY', 'NGHIEM_THU_THAY', 'CHECK_IN_THAY')),
    CONSTRAINT ck_delegation__valid CHECK (valid_from < valid_to),
    CONSTRAINT ck_delegation__self CHECK (delegator_id <> delegate_id),
    CONSTRAINT ck_delegation__subject CHECK (subject_id IS NULL OR kind = 'CHECK_IN_THAY'),
    CONSTRAINT ck_delegation__ended CHECK ((ended_at IS NULL) = (ended_by IS NULL))
);
COMMENT ON TABLE ${flyway:defaultSchema}.delegation IS 'Uỷ quyền khi vắng mặt: duyệt thay, nghiệm thu thay, check-in KR thay (bảng bổ sung TL-03 theo WEB-058)';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.created_by IS '{"cls": "C1", "desc": "Người khai báo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan, ETag, aggregate_version của outbox"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.kind IS '{"cls": "C1", "desc": "Loại uỷ quyền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.delegator_id IS '{"cls": "C1", "desc": "Người uỷ quyền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.delegate_id IS '{"cls": "C1", "desc": "Người nhận uỷ quyền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.org_unit_id IS '{"cls": "C1", "desc": "Đơn vị áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.subject_id IS '{"cls": "C1", "desc": "KR khi check-in thay; rỗng là mọi phiếu của người uỷ quyền tại đơn vị"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.valid_from IS '{"cls": "C1", "desc": "Bắt đầu hiệu lực"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.valid_to IS '{"cls": "C1", "desc": "Hết hiệu lực (bắt buộc, BR-01-16)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.reason IS '{"cls": "C1", "desc": "Lý do uỷ quyền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.ended_by IS '{"cls": "C1", "desc": "Người thu hồi trước hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.ended_at IS '{"cls": "C1", "desc": "Thời điểm thu hồi trước hạn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.end_reason IS '{"cls": "C1", "desc": "Lý do thu hồi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.delegation.expiry_emitted_at IS '{"cls": "C1", "desc": "Cột kỹ thuật: thời điểm tác vụ hết hạn đã phát EVT-27 HET_HAN_UY_QUYEN (chống phát lại)"}';
CREATE INDEX ix_delegation__delegate ON ${flyway:defaultSchema}.delegation (tenant_id, delegate_id, kind, valid_to);
CREATE INDEX ix_delegation__delegator ON ${flyway:defaultSchema}.delegation (tenant_id, delegator_id, kind, valid_to);
CREATE INDEX ix_delegation__unit ON ${flyway:defaultSchema}.delegation (tenant_id, org_unit_id, valid_to);

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.delegation');
