-- DV-12-04 · thông báo hàng loạt cuối kỳ (bổ sung, suy luận, wiki/14). EVT-21 chưa có cờ hàng loạt nên sự kiện có từ
-- BULK_THRESHOLD người nhận trở lên (ưu tiên khác CAO) được ghi thành một đợt notify.bulk_job thay vì ghi ngay mọi
-- thông báo, để consumer không bị chặn và thông báo thường vẫn đến đúng hạn. Bộ mở đợt tách đợt thành lô theo
-- vòng giữa các tenant (mỗi vòng một lô cho mỗi tenant), nên tenant nhỏ không chờ tenant lớn. Dòng delivery của
-- đợt mang batch_id; worker gửi việc thường trước, việc hàng loạt sau và chịu giới hạn tốc độ theo kênh.

CREATE TABLE notify.bulk_job (
    tenant_id     uuid          NOT NULL,
    id            uuid          NOT NULL,
    template_code varchar(48)   NOT NULL,
    params        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    deep_link     varchar(512),
    priority      varchar(8)    NOT NULL,
    recipients    uuid[]        NOT NULL,
    total         int           NOT NULL,
    done          int           NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL,
    locked_until  timestamptz,
    finished_at   timestamptz,
    CONSTRAINT pk_bulk_job PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_bulk_job__done CHECK (done BETWEEN 0 AND total)
);
CREATE INDEX ix_bulk_job__open ON notify.bulk_job (tenant_id, created_at) WHERE finished_at IS NULL;

COMMENT ON TABLE notify.bulk_job IS 'Đợt thông báo hàng loạt chờ tách lô (bổ sung DV-12-04)';
COMMENT ON COLUMN notify.bulk_job.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN notify.bulk_job.id IS '{"cls":"C1","desc":"Mã đợt, bằng mã sự kiện nguồn"}';
COMMENT ON COLUMN notify.bulk_job.template_code IS '{"cls":"C0","desc":"Mẫu"}';
COMMENT ON COLUMN notify.bulk_job.params IS '{"cls":"C1","desc":"Tham số mẫu, không chứa C2"}';
COMMENT ON COLUMN notify.bulk_job.deep_link IS '{"cls":"C1","desc":"Đường dẫn"}';
COMMENT ON COLUMN notify.bulk_job.priority IS '{"cls":"C1","desc":"Ưu tiên"}';
COMMENT ON COLUMN notify.bulk_job.recipients IS '{"cls":"C1","desc":"Người nhận"}';
COMMENT ON COLUMN notify.bulk_job.total IS '{"cls":"C1","desc":"Tổng số người nhận"}';
COMMENT ON COLUMN notify.bulk_job.done IS '{"cls":"C1","desc":"Số người nhận đã tách lô"}';
COMMENT ON COLUMN notify.bulk_job.created_at IS '{"cls":"C1","desc":"Thời điểm"}';
COMMENT ON COLUMN notify.bulk_job.locked_until IS '{"cls":"C1","desc":"Hạn giữ của bộ mở đợt"}';
COMMENT ON COLUMN notify.bulk_job.finished_at IS '{"cls":"C1","desc":"Thời điểm tách xong"}';

ALTER TABLE notify.bulk_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.bulk_job FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_bulk_job__tenant ON notify.bulk_job
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY rls_bulk_job__claim ON notify.bulk_job AS PERMISSIVE FOR ALL TO CURRENT_USER
    USING (finished_at IS NULL) WITH CHECK (finished_at IS NULL);
GRANT SELECT, INSERT, UPDATE ON notify.bulk_job TO svc_notify;
GRANT SELECT ON notify.bulk_job TO ro_notify;

ALTER TABLE notify.delivery ADD COLUMN batch_id uuid;
COMMENT ON COLUMN notify.delivery.batch_id IS '{"cls":"C1","desc":"Đợt hàng loạt (bổ sung DV-12-04)"}';
DROP INDEX notify.ix_delivery__pending;
CREATE INDEX ix_delivery__pending ON notify.delivery (channel, (batch_id IS NOT NULL), next_attempt_at)
    WHERE status = 'CHO';

-- Nhận đợt chưa tách xong, mỗi tenant một đợt cũ nhất, giữ trong p_lease.
CREATE FUNCTION notify.fn_claim_bulk_jobs(p_limit int, p_lease interval)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    WITH c AS (
        SELECT DISTINCT ON (b.tenant_id) b.tenant_id, b.id FROM notify.bulk_job b
         WHERE b.finished_at IS NULL AND (b.locked_until IS NULL OR b.locked_until < now())
         ORDER BY b.tenant_id, b.created_at
         LIMIT p_limit
    )
    UPDATE notify.bulk_job b SET locked_until = now() + p_lease
      FROM c WHERE b.tenant_id = c.tenant_id AND b.id = c.id
       AND (b.locked_until IS NULL OR b.locked_until < now())
    RETURNING b.tenant_id, b.id
$fn$;

-- Nhận việc gửi: p_bulk false là việc thường, true là việc của đợt hàng loạt.
DROP FUNCTION notify.fn_claim_deliveries(varchar, int, interval);
CREATE FUNCTION notify.fn_claim_deliveries(p_channel varchar, p_limit int, p_lease interval, p_bulk boolean)
    RETURNS TABLE (tenant_id uuid, created_day date, id uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF p_channel NOT IN ('PUSH', 'SMS', 'EMAIL') THEN
        RAISE EXCEPTION 'Kênh không hợp lệ: %', p_channel;
    END IF;
    IF p_limit IS NULL OR p_limit < 0 OR p_limit > 500 THEN
        RAISE EXCEPTION 'Số việc mỗi lần phải từ 0 đến 500, nhận %', p_limit;
    END IF;
    RETURN QUERY
    WITH due AS (
        SELECT d.created_day, d.id FROM notify.delivery d
         WHERE d.status = 'CHO' AND d.channel = p_channel AND (d.batch_id IS NOT NULL) = p_bulk
           AND d.next_attempt_at <= now() AND (d.locked_until IS NULL OR d.locked_until < now())
         ORDER BY d.next_attempt_at
         LIMIT p_limit
           FOR UPDATE SKIP LOCKED
    )
    UPDATE notify.delivery d SET locked_until = now() + p_lease
      FROM due WHERE d.created_day = due.created_day AND d.id = due.id
    RETURNING d.tenant_id, d.created_day, d.id;
END
$fn$;

DROP FUNCTION notify.fn_pending_counts();
CREATE FUNCTION notify.fn_pending_counts()
    RETURNS TABLE (channel varchar, pending bigint, due bigint, bulk_pending bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT d.channel, count(*), count(*) FILTER (WHERE d.next_attempt_at <= now()),
           count(*) FILTER (WHERE d.batch_id IS NOT NULL)
      FROM notify.delivery d WHERE d.status = 'CHO' AND d.channel <> 'INAPP' GROUP BY d.channel
$fn$;

REVOKE ALL ON FUNCTION notify.fn_claim_bulk_jobs(int, interval) FROM PUBLIC;
REVOKE ALL ON FUNCTION notify.fn_claim_deliveries(varchar, int, interval, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION notify.fn_pending_counts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_claim_bulk_jobs(int, interval) TO svc_notify;
GRANT EXECUTE ON FUNCTION notify.fn_claim_deliveries(varchar, int, interval, boolean) TO svc_notify;
GRANT EXECUTE ON FUNCTION notify.fn_pending_counts() TO svc_notify;
