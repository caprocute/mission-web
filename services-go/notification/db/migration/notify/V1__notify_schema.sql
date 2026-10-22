-- DV-12-01 · SVC-12 notification-service · TL-03 "Schema notify (SVC-12)" (L3797-3854), "Inbox idempotent"
-- (L4269), "Thời hạn lưu giữ" (notify 90 ngày). CSDL dùng chung của cell (cell_shared), tách tenant bằng RLS.
-- Chạy bằng vai trò mig_notify (chủ sở hữu schema). notification và delivery phân vùng RANGE theo ngày;
-- phân vùng ngày được tạo trước và phân vùng quá 90 ngày bị xoá bởi notify.fn_ensure_day_partitions và
-- notify.fn_drop_expired_partitions. Hai hàm này làm đúng việc premake/retention của pg_partman
-- (TL-03 chọn pg_partman); viết bằng PL/pgSQL để chạy được cả trên ảnh PostgreSQL 17 không cài
-- pg_partman ở dev và kiểm thử (suy luận, ghi trong báo cáo DV-12-01).

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_notify') THEN
        CREATE ROLE svc_notify NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_notify') THEN
        CREATE ROLE ro_notify NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
END
$do$;

CREATE SCHEMA IF NOT EXISTS notify;
REVOKE ALL ON SCHEMA notify FROM PUBLIC;
GRANT USAGE ON SCHEMA notify TO svc_notify, ro_notify;

-- ─────────────────────────── notify.notification ───────────────────────────
CREATE TABLE notify.notification (
    created_day       date         NOT NULL,
    id                uuid         NOT NULL,
    tenant_id         uuid         NOT NULL,
    recipient_user_id uuid         NOT NULL,
    template_code     varchar(48)  NOT NULL,
    params            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    deep_link         varchar(512),
    priority          varchar(8)   NOT NULL,
    source_event_id   uuid         NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    read_at           timestamptz,
    CONSTRAINT pk_notification PRIMARY KEY (created_day, id),
    CONSTRAINT uq_notification__source UNIQUE (created_day, tenant_id, source_event_id, recipient_user_id),
    CONSTRAINT ck_notification__params_object CHECK (jsonb_typeof(params) = 'object')
) PARTITION BY RANGE (created_day);

CREATE INDEX ix_notification__recipient ON notify.notification (tenant_id, recipient_user_id, created_at DESC);
-- Đếm và lọc chưa đọc của hộp thông báo SCR-0002 (suy luận, phục vụ unreadCount).
CREATE INDEX ix_notification__unread ON notify.notification (tenant_id, recipient_user_id)
    WHERE read_at IS NULL;

COMMENT ON TABLE notify.notification IS 'Thông báo trong ứng dụng; giữ 90 ngày; nội dung không chứa C2';
COMMENT ON COLUMN notify.notification.created_day IS '{"cls":"C1","desc":"Ngày tạo, khoá phân vùng"}';
COMMENT ON COLUMN notify.notification.id IS '{"cls":"C1","desc":"Mã"}';
COMMENT ON COLUMN notify.notification.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN notify.notification.recipient_user_id IS '{"cls":"C1","desc":"Người nhận"}';
COMMENT ON COLUMN notify.notification.template_code IS '{"cls":"C0","desc":"Mẫu"}';
COMMENT ON COLUMN notify.notification.params IS '{"cls":"C1","desc":"Tham số (không chứa C2)"}';
COMMENT ON COLUMN notify.notification.deep_link IS '{"cls":"C1","desc":"Đường dẫn"}';
COMMENT ON COLUMN notify.notification.priority IS '{"cls":"C1","desc":"Mức ưu tiên"}';
COMMENT ON COLUMN notify.notification.source_event_id IS '{"cls":"C1","desc":"Sự kiện nguồn"}';
COMMENT ON COLUMN notify.notification.created_at IS '{"cls":"C1","desc":"Thời điểm tạo (suy luận từ chỉ mục của TL-03)"}';
COMMENT ON COLUMN notify.notification.read_at IS '{"cls":"C1","desc":"Đã đọc"}';

-- ─────────────────────────── notify.delivery ───────────────────────────
CREATE TABLE notify.delivery (
    created_day     date         NOT NULL,
    id              uuid         NOT NULL,
    tenant_id       uuid         NOT NULL,
    notification_id uuid         NOT NULL,
    channel         varchar(8)   NOT NULL,
    address_bidx    bytea,
    status          varchar(12)  NOT NULL,
    provider_msg_id varchar(128),
    attempts        int          NOT NULL DEFAULT 0,
    sent_at         timestamptz,
    CONSTRAINT pk_delivery PRIMARY KEY (created_day, id),
    CONSTRAINT ck_delivery__channel CHECK (channel IN ('INAPP', 'PUSH', 'SMS', 'EMAIL')),
    CONSTRAINT ck_delivery__status CHECK (status IN ('CHO', 'DA_GUI', 'THAT_BAI', 'BO_QUA')),
    CONSTRAINT ck_delivery__attempts CHECK (attempts >= 0)
) PARTITION BY RANGE (created_day);

CREATE INDEX ix_delivery__notification ON notify.delivery (tenant_id, notification_id);
-- Worker kênh ngoài (DV-12-03) lấy việc đang chờ theo kênh (suy luận).
CREATE INDEX ix_delivery__pending ON notify.delivery (channel, created_day) WHERE status = 'CHO';

COMMENT ON COLUMN notify.delivery.created_day IS '{"cls":"C1","desc":"Ngày tạo, khoá phân vùng"}';
COMMENT ON COLUMN notify.delivery.id IS '{"cls":"C1","desc":"Mã"}';
COMMENT ON COLUMN notify.delivery.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN notify.delivery.notification_id IS '{"cls":"C1","desc":"Thông báo"}';
COMMENT ON COLUMN notify.delivery.channel IS '{"cls":"C1","desc":"Kênh"}';
COMMENT ON COLUMN notify.delivery.address_bidx IS '{"cls":"C1","desc":"Chỉ mục mù của địa chỉ (số điện thoại, email)"}';
COMMENT ON COLUMN notify.delivery.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN notify.delivery.provider_msg_id IS '{"cls":"C1","desc":"Mã phía nhà cung cấp"}';
COMMENT ON COLUMN notify.delivery.attempts IS '{"cls":"C1","desc":"Số lần"}';
COMMENT ON COLUMN notify.delivery.sent_at IS '{"cls":"C1","desc":"Thời điểm"}';

-- ─────────────────────────── notify.channel_pref ───────────────────────────
CREATE TABLE notify.channel_pref (
    tenant_id  uuid          NOT NULL,
    user_id    uuid          NOT NULL,
    category   varchar(32)   NOT NULL,
    channels   varchar(8)[]  NOT NULL,
    quiet_from time,
    quiet_to   time,
    CONSTRAINT pk_channel_pref PRIMARY KEY (tenant_id, user_id, category),
    CONSTRAINT ck_channel_pref__channels CHECK (channels <@ ARRAY['INAPP', 'PUSH', 'SMS', 'EMAIL']::varchar(8)[]),
    CONSTRAINT ck_channel_pref__quiet CHECK ((quiet_from IS NULL) = (quiet_to IS NULL))
);

COMMENT ON COLUMN notify.channel_pref.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN notify.channel_pref.user_id IS '{"cls":"C1","desc":"Người dùng"}';
COMMENT ON COLUMN notify.channel_pref.category IS '{"cls":"C0","desc":"Nhóm thông báo"}';
COMMENT ON COLUMN notify.channel_pref.channels IS '{"cls":"C1","desc":"Kênh được bật"}';
COMMENT ON COLUMN notify.channel_pref.quiet_from IS '{"cls":"C1","desc":"Giờ yên lặng từ"}';
COMMENT ON COLUMN notify.channel_pref.quiet_to IS '{"cls":"C1","desc":"Giờ yên lặng đến"}';

-- ─────────────────────────── notify.template ───────────────────────────
CREATE TABLE notify.template (
    id            uuid         NOT NULL,
    tenant_id     uuid,
    template_code varchar(48)  NOT NULL,
    channel       varchar(8)   NOT NULL,
    locale        varchar(8)   NOT NULL,
    subject       text         NOT NULL,
    body          text         NOT NULL,
    CONSTRAINT pk_template PRIMARY KEY (id),
    CONSTRAINT uq_template__key UNIQUE NULLS NOT DISTINCT (tenant_id, template_code, channel, locale),
    CONSTRAINT ck_template__channel CHECK (channel IN ('INAPP', 'PUSH', 'SMS', 'EMAIL'))
);

COMMENT ON COLUMN notify.template.id IS '{"cls":"C0","desc":"Mã"}';
COMMENT ON COLUMN notify.template.tenant_id IS '{"cls":"C0","desc":"NULL là mẫu dùng chung của nền tảng"}';
COMMENT ON COLUMN notify.template.template_code IS '{"cls":"C0","desc":"Mã mẫu"}';
COMMENT ON COLUMN notify.template.channel IS '{"cls":"C0","desc":"Kênh"}';
COMMENT ON COLUMN notify.template.locale IS '{"cls":"C0","desc":"Ngôn ngữ"}';
COMMENT ON COLUMN notify.template.subject IS '{"cls":"C0","desc":"Tiêu đề có biến"}';
COMMENT ON COLUMN notify.template.body IS '{"cls":"C0","desc":"Nội dung có biến"}';

-- ─────────────────────────── notify.inbox_processed ───────────────────────────
-- Cùng cấu trúc V0_21__inbox.sql (NEN-032) để thư viện vtplatform/inbox (NEN-017) dùng chung.
CREATE TABLE notify.inbox_processed (
    event_id        uuid         NOT NULL,
    consumer        varchar(100) NOT NULL,
    processed_at    timestamptz  NOT NULL DEFAULT now(),
    tenant_id       uuid         NOT NULL,
    source_topic    varchar(128) NOT NULL,
    kafka_partition int          NOT NULL,
    kafka_offset    bigint       NOT NULL,
    CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON notify.inbox_processed (processed_at);

CREATE TABLE notify.inbox_aggregate_version (
    consumer       varchar(100) NOT NULL,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    tenant_id      uuid         NOT NULL,
    last_version   bigint       NOT NULL,
    last_event_id  uuid         NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_aggregate_version PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);

-- ─────────────────────────── RLS theo tenant ───────────────────────────
ALTER TABLE notify.notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.notification FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_notification__tenant ON notify.notification
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE notify.delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_delivery__tenant ON notify.delivery
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE notify.channel_pref ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.channel_pref FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_channel_pref__tenant ON notify.channel_pref
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Mẫu chung (tenant_id NULL) đọc được ở mọi tenant; tenant chỉ ghi mẫu riêng của mình. Mẫu chung do
-- mig_notify nạp từ data/seed/notify, có chính sách riêng chỉ cho dòng tenant_id NULL.
ALTER TABLE notify.template ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.template FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_template__tenant ON notify.template
    USING (tenant_id IS NULL OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY rls_template__global_seed ON notify.template AS PERMISSIVE FOR ALL TO CURRENT_USER
    USING (tenant_id IS NULL) WITH CHECK (tenant_id IS NULL);

ALTER TABLE notify.inbox_processed ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.inbox_processed FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_inbox_processed__tenant ON notify.inbox_processed
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE notify.inbox_aggregate_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.inbox_aggregate_version FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_inbox_aggregate_version__tenant ON notify.inbox_aggregate_version
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ─────────────────────────── Phân vùng ngày ───────────────────────────
-- Tạo phân vùng ngày [p_from, p_from + p_days) cho notification và delivery nếu chưa có; trả số phân vùng
-- mới. Phân vùng thừa hưởng RLS của bảng cha khi truy cập qua bảng cha; svc_notify không có quyền trực
-- tiếp trên phân vùng.
CREATE FUNCTION notify.fn_ensure_day_partitions(p_from date, p_days int DEFAULT 7)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_day     date;
    v_parent  text;
    v_name    text;
    v_created int := 0;
BEGIN
    IF p_from IS NULL OR p_days IS NULL OR p_days NOT BETWEEN 1 AND 120 THEN
        RAISE EXCEPTION 'Số ngày tạo trước phải từ 1 đến 120, nhận %', p_days;
    END IF;
    FOR i IN 0 .. p_days - 1 LOOP
        v_day := p_from + i;
        FOREACH v_parent IN ARRAY ARRAY['notification', 'delivery'] LOOP
            v_name := v_parent || '_p' || to_char(v_day, 'YYYYMMDD');
            IF to_regclass('notify.' || v_name) IS NULL THEN
                EXECUTE format(
                    'CREATE TABLE notify.%I PARTITION OF notify.%I FOR VALUES FROM (%L) TO (%L)',
                    v_name, v_parent, v_day, v_day + 1);
                v_created := v_created + 1;
            END IF;
        END LOOP;
    END LOOP;
    RETURN v_created;
END
$fn$;

-- Xoá phân vùng ngày có cận trên không muộn hơn current_date - p_retention_days (mặc định 90 ngày,
-- tối thiểu 1 ngày) của notification và delivery; trả số phân vùng đã xoá.
CREATE FUNCTION notify.fn_drop_expired_partitions(p_retention_days int DEFAULT 90, p_today date DEFAULT current_date)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    r         record;
    v_dropped int := 0;
    v_cutoff  date;
BEGIN
    IF p_retention_days IS NULL OR p_retention_days < 1 THEN
        RAISE EXCEPTION 'Thời gian giữ phải từ 1 ngày trở lên, nhận %', p_retention_days;
    END IF;
    v_cutoff := p_today - p_retention_days;
    FOR r IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = 'notify'
           AND p.relname IN ('notification', 'delivery')
           AND c.relname ~ '_p[0-9]{8}$'
           AND to_date(right(c.relname, 8), 'YYYYMMDD') + 1 <= v_cutoff
    LOOP
        EXECUTE format('DROP TABLE notify.%I', r.relname);
        v_dropped := v_dropped + 1;
    END LOOP;
    RETURN v_dropped;
END
$fn$;

-- Dọn inbox_processed cũ hơn p_older_than (mặc định 14 ngày) theo lô; như fn_purge_inbox_processed
-- của NEN-032. Chạy không theo tenant nên dùng SECURITY DEFINER và chính sách dọn riêng.
CREATE POLICY rls_inbox_processed__purge ON notify.inbox_processed AS PERMISSIVE FOR ALL TO CURRENT_USER
    USING (processed_at < now() - interval '1 day') WITH CHECK (false);

CREATE FUNCTION notify.fn_purge_inbox_processed(p_older_than interval DEFAULT interval '14 days',
                                                p_batch_size int DEFAULT 5000)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_deleted int;
BEGIN
    IF p_older_than IS NULL OR p_older_than < interval '1 day' THEN
        RAISE EXCEPTION 'Thời gian giữ inbox phải từ 1 ngày trở lên, nhận %', p_older_than;
    END IF;
    IF p_batch_size IS NULL OR p_batch_size NOT BETWEEN 1 AND 50000 THEN
        RAISE EXCEPTION 'Cỡ lô phải từ 1 đến 50000, nhận %', p_batch_size;
    END IF;
    DELETE FROM notify.inbox_processed
     WHERE ctid IN (SELECT ctid FROM notify.inbox_processed
                     WHERE processed_at < now() - p_older_than
                     LIMIT p_batch_size);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END
$fn$;

-- ─────────────────────────── Quyền ───────────────────────────
GRANT SELECT, INSERT, UPDATE ON notify.notification, notify.delivery TO svc_notify;
GRANT SELECT, INSERT, UPDATE, DELETE ON notify.channel_pref, notify.template TO svc_notify;
GRANT SELECT, INSERT ON notify.inbox_processed TO svc_notify;
GRANT SELECT, INSERT, UPDATE ON notify.inbox_aggregate_version TO svc_notify;
GRANT SELECT ON notify.notification, notify.delivery, notify.channel_pref, notify.template TO ro_notify;

REVOKE EXECUTE ON FUNCTION notify.fn_ensure_day_partitions(date, int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION notify.fn_drop_expired_partitions(int, date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION notify.fn_purge_inbox_processed(interval, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_ensure_day_partitions(date, int) TO svc_notify;
GRANT EXECUTE ON FUNCTION notify.fn_drop_expired_partitions(int, date) TO svc_notify;
GRANT EXECUTE ON FUNCTION notify.fn_purge_inbox_processed(interval, int) TO svc_notify;

-- Phân vùng khởi đầu: 3 ngày trước tới 7 ngày sau ngày chạy migration.
SELECT notify.fn_ensure_day_partitions(current_date - 3, 11);
