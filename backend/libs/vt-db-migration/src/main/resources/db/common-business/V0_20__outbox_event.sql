-- NEN-032 · ADR-03 · TL-03 "Bảng outbox_event" (L768-822), "Transactional Outbox và Debezium" (L4259).
-- Hộp thư đi giao dịch của mọi schema nghiệp vụ: ứng dụng chỉ chèn, Debezium đọc WAL.
-- Phân vùng RANGE theo ngày bằng pg_partman (tạo trước 7 ngày, giữ 3 ngày, gỡ hẳn phân vùng cũ);
-- không chỉ mục phụ. Yêu cầu: bootstrap (NEN-031) đã cài pg_partman vào schema partman và cấp quyền
-- cho mig_<schema>.
CREATE TABLE ${flyway:defaultSchema}.outbox_event (
    id                uuid         NOT NULL,
    tenant_id         uuid         NOT NULL,
    aggregate_type    varchar(64)  NOT NULL,
    aggregate_id      uuid         NOT NULL,
    aggregate_version bigint       NOT NULL,
    event_type        varchar(96)  NOT NULL,
    topic             varchar(128) NOT NULL,
    event_key         varchar(80)  NOT NULL,
    payload           bytea        NOT NULL,
    schema_version    int          NOT NULL,
    headers           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_event PRIMARY KEY (id, created_at),
    CONSTRAINT ck_outbox_event__topic CHECK (topic ~ '^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$')
) PARTITION BY RANGE (created_at);

COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.id IS '{"cls":"C1","desc":"Mã sự kiện eventId (UUIDv7)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.tenant_id IS '{"cls":"C1","desc":"Tenant phát sự kiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.aggregate_type IS '{"cls":"C1","desc":"Loại thực thể gốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.aggregate_id IS '{"cls":"C1","desc":"Mã thực thể gốc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.aggregate_version IS '{"cls":"C1","desc":"row_version của thực thể sau thay đổi, chống sai thứ tự phía nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.event_type IS '{"cls":"C1","desc":"Tên nghiệp vụ của sự kiện"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.topic IS '{"cls":"C1","desc":"Topic đích, Debezium định tuyến theo cột này"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.event_key IS '{"cls":"C1","desc":"Khoá Kafka tenantId:aggregateId"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.payload IS '{"cls":"C1","desc":"Avro nhị phân (serde Apicurio); trường C2 trong payload đã ở dạng bản mã"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.schema_version IS '{"cls":"C1","desc":"Phiên bản lược đồ Avro trong Apicurio"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.headers IS '{"cls":"C1","desc":"traceparent, actorId, policyVersion, classification, occurredAt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbox_event.created_at IS '{"cls":"C1","desc":"Thời điểm ghi, khoá phân vùng theo ngày"}';

SELECT partman.create_parent(
    p_parent_table => '${flyway:defaultSchema}.outbox_event',
    p_control      => 'created_at',
    p_interval     => '1 day',
    p_premake      => 7,
    p_jobmon       => false);
-- infinite_time_partitions: tạo trước phân vùng theo thời gian thực, không theo dữ liệu mới nhất,
-- vì FORCE RLS che dòng khỏi mig_<schema> nên pg_partman không thấy dữ liệu (suy luận).
UPDATE partman.part_config
   SET retention = '3 days', retention_keep_table = false, infinite_time_partitions = true
 WHERE parent_table = '${flyway:defaultSchema}.outbox_event';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.outbox_event');
SELECT ${flyway:defaultSchema}.fn_protect_partitions('${flyway:defaultSchema}.outbox_event');

-- cdc_<schema> chỉ SELECT bảng cha, dùng cho chụp ảnh ban đầu khi tạo connector (TL-03 L822);
-- connector chạy snapshot.mode = never và đọc WAL, không chịu RLS.
DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_${flyway:defaultSchema}') THEN
        EXECUTE format('GRANT SELECT ON %I.outbox_event TO %I',
                       '${flyway:defaultSchema}', 'cdc_${flyway:defaultSchema}');
    END IF;
END
$do$;
