-- NEN-032 · ADR-23 · TL-03 "Schema modulith và bảng event_publication" (L894-908),
-- TL-01 "Giao tiếp giữa module" (L553-597).
-- Event Publication Registry của Spring Modulith 1.4 (lược đồ JDBC chuẩn cho PostgreSQL) cộng
-- tenant_id và RLS. Bảng thuộc khung ứng dụng: svc_ của tám mô-đun mission-core có DML (NEN-031),
-- nên chính sách tenant áp cho PUBLIC; quyền truy cập do GRANT giới hạn.
-- Cấu hình mission-core (CORE-00-02):
--   spring.modulith.events.jdbc.schema=modulith
--   spring.modulith.events.jdbc.schema-initialization.enabled=false   (Flyway tạo bảng)
--   spring.modulith.events.republish-outstanding-events-on-restart=true
-- tenant_id lấy mặc định từ app.tenant_id của giao dịch phát sự kiện vì Spring Modulith không biết
-- cột này. Lưu ý cho CORE-00-02 (suy luận): việc phát lại sự kiện tồn đọng khi khởi động chạy không
-- có ngữ cảnh tenant nên RLS che hết dòng; cần phát lại theo từng tenant.
CREATE TABLE modulith.event_publication (
    id               uuid         NOT NULL,
    tenant_id        uuid         NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    listener_id      varchar(512) NOT NULL,
    event_type       varchar(512) NOT NULL,
    serialized_event text         NOT NULL,
    publication_date timestamptz  NOT NULL,
    completion_date  timestamptz,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
-- Spring Modulith đánh dấu hoàn tất theo (listener_id, serialized_event) và tìm bản ghi tồn đọng.
CREATE INDEX ix_event_publication__listener_id_completion_date
    ON modulith.event_publication (listener_id, completion_date);
CREATE INDEX ix_event_publication__publication_date_incomplete
    ON modulith.event_publication (publication_date) WHERE completion_date IS NULL;
CREATE INDEX ix_event_publication__serialized_event
    ON modulith.event_publication USING hash (serialized_event);

COMMENT ON COLUMN modulith.event_publication.id IS '{"cls":"C1","desc":"Mã lần phát"}';
COMMENT ON COLUMN modulith.event_publication.tenant_id IS '{"cls":"C1","desc":"Tenant, mặc định theo app.tenant_id của giao dịch"}';
COMMENT ON COLUMN modulith.event_publication.listener_id IS '{"cls":"C1","desc":"Bộ lắng nghe đích"}';
COMMENT ON COLUMN modulith.event_publication.event_type IS '{"cls":"C1","desc":"Lớp sự kiện"}';
COMMENT ON COLUMN modulith.event_publication.serialized_event IS '{"cls":"C1","desc":"Sự kiện tuần tự hoá JSON, không chứa trường C2 dạng rõ"}';
COMMENT ON COLUMN modulith.event_publication.publication_date IS '{"cls":"C1","desc":"Thời điểm phát"}';
COMMENT ON COLUMN modulith.event_publication.completion_date IS '{"cls":"C1","desc":"Thời điểm xử lý xong"}';

ALTER TABLE modulith.event_publication ENABLE ROW LEVEL SECURITY;
ALTER TABLE modulith.event_publication FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_event_publication__tenant ON modulith.event_publication AS PERMISSIVE FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- Job dọn (không theo tenant) chỉ thấy bản ghi đã hoàn tất quá một ngày, không chèn sửa được.
CREATE POLICY rls_event_publication__purge ON modulith.event_publication AS PERMISSIVE FOR ALL
    TO mig_modulith
    USING (completion_date IS NOT NULL AND completion_date < now() - interval '1 day')
    WITH CHECK (false);

-- Xoá một lô bản ghi đã hoàn tất cũ hơn p_older_than (mặc định 7 ngày theo TL-03 L908), trả số dòng
-- đã xoá; bên gọi lặp tới khi kết quả nhỏ hơn p_batch_size. Bản ghi chưa hoàn tất không bao giờ bị
-- xoá (cảnh báo tồn đọng quá 15 phút là việc của giám sát, ngoài phạm vi).
CREATE FUNCTION modulith.fn_purge_event_publication(
        p_older_than interval DEFAULT interval '7 days',
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
        RAISE EXCEPTION 'Thời gian giữ phải từ 1 ngày trở lên, nhận %', p_older_than;
    END IF;
    IF p_batch_size IS NULL OR p_batch_size NOT BETWEEN 1 AND 50000 THEN
        RAISE EXCEPTION 'Cỡ lô phải từ 1 đến 50000, nhận %', p_batch_size;
    END IF;
    DELETE FROM modulith.event_publication
     WHERE ctid IN (SELECT ctid FROM modulith.event_publication
                     WHERE completion_date < now() - p_older_than
                     LIMIT p_batch_size);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END
$fn$;
REVOKE EXECUTE ON FUNCTION modulith.fn_purge_event_publication(interval, int) FROM PUBLIC;
