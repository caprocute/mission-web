-- =============================================================================
-- V12__org_replica_phan_cong_inbox.sql
-- DV-06-10 (nối tiếp) · SVC-06 · TL-03 "Schema org_replica" (bảng assignment_v), "Inbox idempotent"
-- (dọn inbox_processed sau 14 ngày) · wiki trang 18 "Chuyển trạng thái qua sự kiện (ECST)".
-- Migration chỉ thêm mới (expand/contract); không sửa sau khi đã gộp. V10 thuộc nhánh B6 khác.
--
-- Ghi chú (wiki trang 14, mục DV-06-10):
--   * Consumer EVT-15 org.assignment.changed.v1 và org.bootstrap.v1 của workflow-service upsert
--     org_replica.assignment_v theo source_version, nên svc_flowable cần INSERT, UPDATE (V11 chỉ cấp SELECT).
--     Không cấp DELETE: phân công kết thúc biểu diễn bằng valid_to, sự kiện không mang cờ xoá.
--   * V4 tạo flowable.inbox_processed theo vt-db-migration V0_21 nhưng chưa có hàm dọn; job dọn của thư viện
--     nền (InboxPurgeJob) gọi flowable.fn_purge_inbox_processed khi bật inbox, nên thêm hàm theo đúng V0_21.
--     Hàm SECURITY DEFINER chạy bằng chủ sở hữu bảng (tài khoản di trú) mà bảng bật FORCE RLS, nên thêm chính
--     sách riêng cho chủ sở hữu chỉ thấy dòng cũ hơn một ngày và cấm chèn, sửa (như V0_21).
-- =============================================================================

SET search_path TO flowable;

GRANT INSERT, UPDATE ON org_replica.assignment_v TO svc_flowable;

CREATE POLICY rls_inbox_processed__purge ON flowable.inbox_processed AS PERMISSIVE FOR ALL TO CURRENT_USER
  USING (processed_at < now() - interval '1 day') WITH CHECK (false);

-- Xoá một lô dòng inbox_processed cũ hơn p_older_than (mặc định 14 ngày, tối thiểu 1 ngày), trả số dòng đã
-- xoá; bên gọi lặp tới khi kết quả nhỏ hơn p_batch_size. svc_flowable chỉ có EXECUTE.
CREATE FUNCTION flowable.fn_purge_inbox_processed(
        p_older_than interval DEFAULT interval '14 days',
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
    DELETE FROM flowable.inbox_processed
     WHERE ctid IN (SELECT ctid FROM flowable.inbox_processed
                     WHERE processed_at < now() - p_older_than
                     LIMIT p_batch_size);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END
$fn$;
REVOKE EXECUTE ON FUNCTION flowable.fn_purge_inbox_processed(interval, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION flowable.fn_purge_inbox_processed(interval, int) TO svc_flowable;
