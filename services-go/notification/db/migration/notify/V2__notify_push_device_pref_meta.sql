-- DV-12-05 · bổ sung (suy luận), ghi ở wiki/14: TL-03 "Schema notify" chưa có bảng thiết bị nhận thông báo đẩy và
-- chưa có phiên bản cho tuỳ chọn kênh, trong khi platform.yaml (NEN-043) có registerPushDevice và
-- updateNotificationPreferences kèm If-Match. Chỉ thêm bảng, không đổi bảng của TL-03.

-- Thiết bị nhận thông báo đẩy. Mã thiết bị là C2: lưu bản mã phong bì (định dạng TL-03:
-- 0x01 || dek_ref 16B || nonce 12B || ciphertext || tag 16B, AAD `tenant|notify.push_device.token_enc|id`)
-- kèm chỉ mục mù HMAC-SHA-256 cắt 16 byte để chống trùng; không có cột bản rõ.
CREATE TABLE notify.push_device (
    id            uuid          NOT NULL,
    tenant_id     uuid          NOT NULL,
    user_id       uuid          NOT NULL,
    platform      varchar(8)    NOT NULL,
    device_name   varchar(120)  NOT NULL,
    app_version   varchar(32),
    locale        varchar(8),
    token_enc     bytea         NOT NULL,
    dek_id        uuid          NOT NULL,
    token_bidx    bytea         NOT NULL,
    registered_at timestamptz   NOT NULL DEFAULT now(),
    last_seen_at  timestamptz   NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    CONSTRAINT pk_push_device PRIMARY KEY (id),
    CONSTRAINT ck_push_device__platform CHECK (platform IN ('FCM', 'APNS', 'WEB_PUSH')),
    CONSTRAINT ck_push_device__bidx CHECK (octet_length(token_bidx) = 16)
);
-- Một mã thiết bị chỉ thuộc một người dùng tại một thời điểm: duy nhất trong các dòng chưa thu hồi.
CREATE UNIQUE INDEX uq_push_device__token ON notify.push_device (tenant_id, token_bidx) WHERE revoked_at IS NULL;
CREATE INDEX ix_push_device__user ON notify.push_device (tenant_id, user_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE notify.push_device IS 'Bổ sung (suy luận) DV-12-05: thiết bị nhận thông báo đẩy';
COMMENT ON COLUMN notify.push_device.id IS '{"cls":"C1","desc":"Mã thiết bị đăng ký"}';
COMMENT ON COLUMN notify.push_device.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN notify.push_device.user_id IS '{"cls":"C1","desc":"Người dùng"}';
COMMENT ON COLUMN notify.push_device.platform IS '{"cls":"C0","desc":"Nền tảng đẩy"}';
COMMENT ON COLUMN notify.push_device.device_name IS '{"cls":"C1","desc":"Tên thiết bị"}';
COMMENT ON COLUMN notify.push_device.app_version IS '{"cls":"C0","desc":"Phiên bản ứng dụng"}';
COMMENT ON COLUMN notify.push_device.locale IS '{"cls":"C0","desc":"Ngôn ngữ"}';
COMMENT ON COLUMN notify.push_device.token_enc IS '{"cls":"C2","desc":"Mã thiết bị, mã hoá phong bì"}';
COMMENT ON COLUMN notify.push_device.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu đã dùng"}';
COMMENT ON COLUMN notify.push_device.token_bidx IS '{"cls":"C1","desc":"Chỉ mục mù của mã thiết bị"}';
COMMENT ON COLUMN notify.push_device.registered_at IS '{"cls":"C1","desc":"Thời điểm đăng ký"}';
COMMENT ON COLUMN notify.push_device.last_seen_at IS '{"cls":"C1","desc":"Lần cuối thấy thiết bị"}';
COMMENT ON COLUMN notify.push_device.revoked_at IS '{"cls":"C1","desc":"Thời điểm thu hồi"}';

-- Phiên bản và cờ giờ yên lặng của tuỳ chọn kênh theo người dùng (ETag/If-Match); giờ yên lặng vẫn lưu trên
-- từng dòng notify.channel_pref như TL-03.
CREATE TABLE notify.channel_pref_meta (
    tenant_id           uuid        NOT NULL,
    user_id             uuid        NOT NULL,
    quiet_hours_enabled boolean     NOT NULL DEFAULT false,
    row_version         bigint      NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_channel_pref_meta PRIMARY KEY (tenant_id, user_id)
);
COMMENT ON TABLE notify.channel_pref_meta IS 'Bổ sung (suy luận) DV-12-05: phiên bản tuỳ chọn kênh theo người dùng';
COMMENT ON COLUMN notify.channel_pref_meta.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN notify.channel_pref_meta.user_id IS '{"cls":"C1","desc":"Người dùng"}';
COMMENT ON COLUMN notify.channel_pref_meta.quiet_hours_enabled IS '{"cls":"C1","desc":"Bật giờ yên lặng"}';
COMMENT ON COLUMN notify.channel_pref_meta.row_version IS '{"cls":"C1","desc":"Phiên bản"}';
COMMENT ON COLUMN notify.channel_pref_meta.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật"}';

ALTER TABLE notify.push_device ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.push_device FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_push_device__tenant ON notify.push_device
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE notify.channel_pref_meta ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.channel_pref_meta FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_channel_pref_meta__tenant ON notify.channel_pref_meta
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Thu hồi thiết bị không thấy quá p_days ngày (mặc định 90, suy luận) ở mọi tenant; job duy trì gọi.
CREATE POLICY rls_push_device__stale ON notify.push_device AS PERMISSIVE FOR ALL TO CURRENT_USER
    USING (last_seen_at < now() - interval '1 day')
    WITH CHECK (revoked_at IS NOT NULL AND last_seen_at < now() - interval '1 day');

CREATE FUNCTION notify.fn_revoke_stale_devices(p_days int DEFAULT 90)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_count int;
BEGIN
    IF p_days IS NULL OR p_days < 1 THEN
        RAISE EXCEPTION 'Số ngày phải từ 1 trở lên, nhận %', p_days;
    END IF;
    UPDATE notify.push_device SET revoked_at = now()
     WHERE revoked_at IS NULL AND last_seen_at < now() - make_interval(days => p_days);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$fn$;

GRANT SELECT, INSERT, UPDATE ON notify.push_device, notify.channel_pref_meta TO svc_notify;
GRANT SELECT ON notify.channel_pref_meta TO ro_notify;
REVOKE EXECUTE ON FUNCTION notify.fn_revoke_stale_devices(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_revoke_stale_devices(int) TO svc_notify;
