-- DV-12-03 · hàng đợi gửi kênh ngoài (EMAIL, SMS, PUSH) trên chính bảng notify.delivery trạng thái CHO, thay vì
-- thêm topic Kafka riêng mỗi kênh (suy luận, wiki/14): dòng delivery đã được ghi trong cùng giao dịch với
-- notification nên không mất việc, worker nhận việc bằng FOR UPDATE SKIP LOCKED nên nhiều pod chạy song song
-- không trùng, và độ dài hàng đợi mỗi kênh đếm được ngay để KEDA co giãn. Chỉ thêm cột, không đổi cột của TL-03.

ALTER TABLE notify.delivery
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN locked_until    timestamptz,
    ADD COLUMN last_error      varchar(64);

COMMENT ON COLUMN notify.delivery.next_attempt_at IS '{"cls":"C1","desc":"Thời điểm gửi hoặc thử lại kế tiếp (bổ sung DV-12-03)"}';
COMMENT ON COLUMN notify.delivery.locked_until IS '{"cls":"C1","desc":"Hạn giữ việc của worker (bổ sung DV-12-03)"}';
COMMENT ON COLUMN notify.delivery.last_error IS '{"cls":"C1","desc":"Mã lỗi lần gửi gần nhất, không chứa địa chỉ (bổ sung DV-12-03)"}';

DROP INDEX notify.ix_delivery__pending;
CREATE INDEX ix_delivery__pending ON notify.delivery (channel, next_attempt_at) WHERE status = 'CHO';

-- Chủ sở hữu (mig_notify, chạy hai hàm SECURITY DEFINER dưới đây) chỉ thấy và giữ được dòng đang chờ của kênh
-- ngoài ở mọi tenant; không đổi được trạng thái hay nội dung khác (cùng cách làm với rls_push_device__stale).
CREATE POLICY rls_delivery__queue ON notify.delivery AS PERMISSIVE FOR ALL TO CURRENT_USER
    USING (status = 'CHO' AND channel <> 'INAPP')
    WITH CHECK (status = 'CHO' AND channel <> 'INAPP');

-- Nhận tối đa p_limit việc đến hạn của một kênh ở mọi tenant và giữ trong p_lease. Hàm chạy quyền chủ sở hữu để
-- vượt RLS, nhưng chỉ trả khoá dòng; worker đọc và cập nhật dòng trong giao dịch đã đặt app.tenant_id.
CREATE FUNCTION notify.fn_claim_deliveries(p_channel varchar, p_limit int, p_lease interval)
    RETURNS TABLE (tenant_id uuid, created_day date, id uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF p_channel NOT IN ('PUSH', 'SMS', 'EMAIL') THEN
        RAISE EXCEPTION 'Kênh không hợp lệ: %', p_channel;
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
        RAISE EXCEPTION 'Số việc mỗi lần phải từ 1 đến 500, nhận %', p_limit;
    END IF;
    RETURN QUERY
    WITH due AS (
        SELECT d.created_day, d.id FROM notify.delivery d
         WHERE d.status = 'CHO' AND d.channel = p_channel AND d.next_attempt_at <= now()
           AND (d.locked_until IS NULL OR d.locked_until < now())
         ORDER BY d.next_attempt_at
         LIMIT p_limit
           FOR UPDATE SKIP LOCKED
    )
    UPDATE notify.delivery d SET locked_until = now() + p_lease
      FROM due WHERE d.created_day = due.created_day AND d.id = due.id
    RETURNING d.tenant_id, d.created_day, d.id;
END
$fn$;

-- Số việc đang chờ theo kênh ở mọi tenant, cho metric notify_delivery_pending (KEDA).
CREATE FUNCTION notify.fn_pending_counts()
    RETURNS TABLE (channel varchar, pending bigint, due bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT d.channel, count(*), count(*) FILTER (WHERE d.next_attempt_at <= now())
      FROM notify.delivery d WHERE d.status = 'CHO' AND d.channel <> 'INAPP' GROUP BY d.channel
$fn$;

REVOKE ALL ON FUNCTION notify.fn_claim_deliveries(varchar, int, interval) FROM PUBLIC;
REVOKE ALL ON FUNCTION notify.fn_pending_counts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notify.fn_claim_deliveries(varchar, int, interval) TO svc_notify;
GRANT EXECUTE ON FUNCTION notify.fn_pending_counts() TO svc_notify;
