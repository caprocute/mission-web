-- CORE-01-12 · QT-03 "Chủ sở hữu KR vắng mặt dài ngày", SCR-0301: uỷ quyền check-in thay
-- (setKrCheckinDelegate, revokeKrCheckinDelegate của okr.yaml). Bảng bổ sung, TL-03 chưa có;
-- mỗi lần giao là một dòng, thu hồi ghi revoked_at nên giữ được lịch sử giao quyền. Mỗi KR chỉ có
-- một uỷ quyền chưa thu hồi. Check-in thay không cần cột riêng: kr_checkin.created_by là người
-- thực hiện thực tế, khác chủ sở hữu KR thì là check-in thay.

CREATE TABLE ${flyway:defaultSchema}.kr_checkin_delegate (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    key_result_id uuid NOT NULL,
    delegate_person_id uuid NOT NULL,
    valid_until date,
    reason varchar(500),
    revoked_at timestamptz,
    revoked_by uuid,
    CONSTRAINT pk_kr_checkin_delegate PRIMARY KEY (id),
    CONSTRAINT ck_kr_checkin_delegate__revoked CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);
CREATE UNIQUE INDEX ix_kr_checkin_delegate__active_kr ON ${flyway:defaultSchema}.kr_checkin_delegate (tenant_id, key_result_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_kr_checkin_delegate__person ON ${flyway:defaultSchema}.kr_checkin_delegate (tenant_id, delegate_person_id) WHERE revoked_at IS NULL;
COMMENT ON TABLE ${flyway:defaultSchema}.kr_checkin_delegate IS 'Uỷ quyền check-in thay chủ sở hữu KR (bổ sung, CORE-01-12); thu hồi ghi revoked_at, không xoá';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.id IS '{"cls": "C1", "desc": "Mã dòng (UUIDv7 sinh ở ứng dụng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.created_at IS '{"cls": "C1", "desc": "Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.created_by IS '{"cls": "C1", "desc": "Người giao quyền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.key_result_id IS '{"cls": "C1", "desc": "KR được uỷ quyền check-in (tham chiếu logic okr.key_result)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.delegate_person_id IS '{"cls": "C1", "desc": "Người được check-in thay"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.valid_until IS '{"cls": "C1", "desc": "Hạn hiệu lực (hết ngày này); rỗng là tới hết kỳ của KR"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.reason IS '{"cls": "C1", "desc": "Lý do uỷ quyền"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.revoked_at IS '{"cls": "C1", "desc": "Thời điểm thu hồi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.kr_checkin_delegate.revoked_by IS '{"cls": "C1", "desc": "Người thu hồi"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.kr_checkin_delegate');
