-- NEN-032 · ADR-03 · TL-03 "Bảng inbox_processed và inbox_aggregate_version" (L824-873).
-- Hộp thư đến idempotent và phiên bản aggregate đã áp của mọi schema nghiệp vụ (TL-03 L766 áp cho
-- mọi schema; DDL mẫu của TL-03 viết cho appraisal). inbox_processed không phân vùng để khoá
-- (consumer, event_id) bảo đảm trên toàn bảng; dọn dòng cũ hơn 14 ngày bằng fn_purge_inbox_processed.
CREATE TABLE ${flyway:defaultSchema}.inbox_processed (
    event_id        uuid         NOT NULL,
    consumer        varchar(100) NOT NULL,
    processed_at    timestamptz  NOT NULL DEFAULT now(),
    tenant_id       uuid         NOT NULL,
    source_topic    varchar(128) NOT NULL,
    kafka_partition int          NOT NULL,
    kafka_offset    bigint       NOT NULL,
    CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON ${flyway:defaultSchema}.inbox_processed (processed_at);

COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.event_id IS '{"cls":"C1","desc":"Mã sự kiện đã xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.consumer IS '{"cls":"C1","desc":"Tên consumer"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.processed_at IS '{"cls":"C1","desc":"Thời điểm xử lý; dọn sau 14 ngày"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.tenant_id IS '{"cls":"C1","desc":"Tenant của sự kiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.source_topic IS '{"cls":"C1","desc":"Topic nguồn, phục vụ chẩn đoán"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.kafka_partition IS '{"cls":"C1","desc":"Phân vùng Kafka"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_processed.kafka_offset IS '{"cls":"C1","desc":"Offset Kafka"}';

CREATE TABLE ${flyway:defaultSchema}.inbox_aggregate_version (
    consumer       varchar(100) NOT NULL,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    tenant_id      uuid         NOT NULL,
    last_version   bigint       NOT NULL,
    last_event_id  uuid         NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_aggregate_version PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);

COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.consumer IS '{"cls":"C1","desc":"Tên consumer"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.aggregate_type IS '{"cls":"C1","desc":"Loại thực thể gốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.aggregate_id IS '{"cls":"C1","desc":"Mã thực thể gốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.last_version IS '{"cls":"C1","desc":"aggregate_version cao nhất đã áp dụng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.last_event_id IS '{"cls":"C1","desc":"Sự kiện tương ứng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_aggregate_version.updated_at IS '{"cls":"C1","desc":"Thời điểm cập nhật"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.inbox_processed');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.inbox_aggregate_version');

-- Job dọn chạy không theo tenant nên cần thấy dòng của mọi tenant; chủ sở hữu mig_<schema> bị FORCE
-- RLS chặn, nên có chính sách riêng chỉ mở dòng đã cũ hơn một ngày và cấm chèn, sửa. Chỉ hàm
-- SECURITY DEFINER dưới đây dùng chính sách này (suy luận).
DO $do$
BEGIN
    EXECUTE format(
        'CREATE POLICY rls_inbox_processed__purge ON %I.inbox_processed AS PERMISSIVE FOR ALL TO %I'
        ' USING (processed_at < now() - interval ''1 day'') WITH CHECK (false)',
        '${flyway:defaultSchema}', 'mig_${flyway:defaultSchema}');
END
$do$;

-- Xoá một lô dòng inbox_processed cũ hơn p_older_than (mặc định 14 ngày, tối thiểu 1 ngày), trả số
-- dòng đã xoá. Mỗi lần gọi là một lô trong giao dịch của bên gọi; ứng dụng hoặc CronJob gọi lặp tới
-- khi kết quả nhỏ hơn p_batch_size (lịch chạy ngoài phạm vi NEN-032). svc_<schema> có EXECUTE.
CREATE FUNCTION ${flyway:defaultSchema}.fn_purge_inbox_processed(
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
    DELETE FROM ${flyway:defaultSchema}.inbox_processed
     WHERE ctid IN (SELECT ctid FROM ${flyway:defaultSchema}.inbox_processed
                     WHERE processed_at < now() - p_older_than
                     LIMIT p_batch_size);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END
$fn$;
REVOKE EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_purge_inbox_processed(interval, int) FROM PUBLIC;
