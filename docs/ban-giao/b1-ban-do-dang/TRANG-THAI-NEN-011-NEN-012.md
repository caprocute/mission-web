# Trạng thái bàn giao NEN-011 và NEN-012 (dừng theo YC-PT-15)

> Ngày 20/10/2026. Luồng B1 (Claude) dừng hai task này vì sổ tay yêu cầu bổ sung YC-PT-15: các task backend đã được giao cho công cụ AI khác. Nhánh `feat/NEN-011-outbox` (tạo từ `claude/b1-nen-mong-backend-8pacfm`, đầu bf4063f) giữ phần đã làm; chưa gộp vào nhánh nào, chưa đẩy lên remote. Bên tiếp nhận quyết định giữ, sửa hay bỏ.

## NEN-011 · OutboxWriter, EventEnvelope, tuần tự hoá Avro qua Apicurio

**Đã làm (biên dịch được, 28 kiểm thử đơn vị xanh; chưa chạy `verify` đầy đủ):**

- `platform-bom/pom.xml`: thêm `io.apicurio:apicurio-registry-avro-serde-kafka:${apicurio-registry.version}` (3.3.3).
- `vt-platform-starter/pom.xml`: phụ thuộc `vt-events`, serde Apicurio, `spring-jdbc`; `connect-api` scope `provided`; kiểm thử: `vt-db-migration` (+ test-jar), Testcontainers `junit-jupiter`, `kafka`, `postgresql`.
- Gói `vn.viettel.okrkpi.shared.outbox`:
  - `EventEnvelope` (record; `of(topic, tenantId, aggregateType, aggregateId, aggregateVersion, payload, classification)`, `withActor`, `withPolicyVersion`, `withOccurredAt`, `eventKey()` = `tenantId:aggregateId`): kiểm topic theo cùng biểu thức CHECK của NEN-032, từ chối C2/C3 (`SensitiveEventException`), kiểm độ dài cột, kiểm `aggregateVersion` khớp trường cùng tên trong thân Avro, mã sự kiện UUIDv7.
  - `OutboxWriter.append(EventEnvelope)`: `@Transactional(MANDATORY)` và tự kiểm `TransactionSynchronizationManager.isActualTransactionActive()` (ném `IllegalTransactionStateException` cả khi không có proxy); chèn đủ 11 cột TL-03 vào `<okrkpi.outbox.schema>.outbox_event` bằng `JdbcTemplate`; `headers` jsonb gồm `traceparent` (W3C từ OpenTelemetry hiện hành), `actorid`, `tenantid`, `classification`, `policyversion` (khi có), `aggregateversion`, `ce_type`, `occurredat`, `apicurio.value.globalId`.
  - `ApicurioPayloadSerializer`: `AvroSerializer` của Apicurio 3.x, tra lược đồ theo nội dung trong artifact `<topic>-value` (không tự đăng ký, `canonicalize=true`, adapter HTTP JDK), đệm kết quả theo (topic, lược đồ); lỗi registry → `OutboxSerializationException`, giao dịch thất bại.
  - `OutboxProperties` (`okrkpi.outbox.schema`, `okrkpi.outbox.registry.url`, `okrkpi.outbox.registry.properties`), `OutboxAutoConfiguration` (đã thêm vào `AutoConfiguration.imports`), `OutboxHeaders` (tên tiêu đề).
  - `connect/OutboxHeadersTransform`: SMT Kafka Connect chạy sau Debezium EventRouter, tách tiêu đề `vt_outbox_headers` (cột jsonb) thành từng tiêu đề byte; `apicurio.value.globalId` ghi 8 byte big-endian cho serde Apicurio đọc.
- Kiểm thử đơn vị: `EventEnvelopeTest` (13), `OutboxWriterTest` (8), `OutboxHeadersTransformTest` (3), `OutboxAutoConfigurationTest` (4).

**Còn lại:**

1. Kiểm thử tích hợp `OutboxDebeziumIT` (chưa viết): PostgreSQL `TestPostgres` + `wal_level=logical`, bootstrap `DatabaseBootstrap` + migrate schema `task`; `CREATE PUBLICATION pub_outbox FOR TABLE task.outbox_event WITH (publish_via_partition_root = true)`; Kafka (`mirror.gcr.io/apache/kafka:3.9.1`), Apicurio `apicurio/apicurio-registry:3.3.3` (đăng ký `TienDoDaCapNhat.avsc` vào `default/task.task.progressed.v1-value`), Debezium Connect `mirror.gcr.io/debezium/connect:3.0.0.Final` (quay.io bị chặn; Docker Hub chỉ còn tới 3.0.0.Final) với jar plugin đóng từ lớp `OutboxHeadersTransform` chép vào `/kafka/connect/vt-outbox-smt/`; worker cần `CONNECT_CONNECTOR_CLIENT_CONFIG_OVERRIDE_POLICY=All` cho `producer.override.*`.
2. Cấu hình connector tham chiếu trong `src/test/resources/debezium/` (chưa viết): như TL-03 nhưng `table.field.event.id=id` (TL-03 mẫu ghi `event_id`), `table.fields.additional.placement=headers:header:vt_outbox_headers`, thêm transform `OutboxHeadersTransform`, `header.converter=ByteArrayConverter`, `topic.prefix` (bắt buộc từ Debezium 2), kiểm `snapshot.mode=never` còn được Debezium 3.0 nhận không (có thể phải dùng `no_data`).
3. Kịch bản: commit → thông điệp trên `task.task.progressed.v1`, khoá `tenantId:aggregateId`, đủ tiêu đề, giải tuần tự được bằng `AvroKafkaDeserializer`; rollback → không có thông điệp; Apicurio không phản hồi → giao dịch thất bại; đo p50/p99 trên 1.000 sự kiện.
4. Chạy `./mvnw -B -ntp -pl libs/vt-platform-starter -am verify` (Spotless, JaCoCo, SpotBugs) và toàn backend; commit chính thức `NEN-011: ...`.

**Rủi ro, điểm lệch cần ghi vào báo cáo:**

- Không thêm `HikariCP` vào classpath kiểm thử của starter: khi có `spring-jdbc` + Hikari, các `@SpringBootTest` sẵn có (NEN-009/016) sẽ bật `DataSourceAutoConfiguration` mà không có URL và hỏng. Kiểm thử tích hợp nên tự khai `DataSource` (ví dụ `PGSimpleDataSource`).
- `sys.audit.v1` (ba đoạn) bị CHECK `ck_outbox_event__topic` (bốn đoạn) và `EventEnvelope` từ chối; `AuditEvent.tenantId` có thể rỗng (thao tác liên tenant) trong khi `outbox_event.tenant_id` NOT NULL + RLS. Vì vậy chưa khai bean `AuditSink` ghi outbox; starter giữ `LoggingAuditSink`. Đề xuất: migration bổ sung (task NEN-032 tiếp theo) nới CHECK thành `^[a-z]+(\.[a-z]+){1,2}\.v[0-9]+$` hoặc đổi topic kiểm toán thành bốn đoạn (sửa hợp đồng sự kiện qua task riêng), và chốt tenant cho kiểm toán liên tenant.
- TL-01 mục "Outbox" dùng `aggregate_key`, `schema_id`, `occurred_at`; theo TL-03 L787–819 (`aggregate_id`, `aggregate_version`, `event_key`, `schema_version`, `created_at`). TL-03 gọi thành phần là `OutboxEventPublisher`, thẻ gọi `OutboxWriter`; theo thẻ.
- Cấu hình connector mẫu TL-03 đặt tiêu đề từ cột `trace_parent`, `classification`, `schema_id` không có trong DDL, nên cần SMT `OutboxHeadersTransform`; INF-10 phải đóng gói SMT thành plugin Debezium (đề xuất mô-đun riêng nhỏ, chỉ chứa lớp này).
- `ce_type` theo mẫu TL-03 là `event_type` (tên nghiệp vụ), trong khi `contracts/events/README.md` ghi `type` = tên topic.
- Registry treo (không từ chối kết nối) có thể giữ giao dịch tới hết thời gian chờ của client HTTP; chỉ gặp ở lần phát đầu tiên mỗi lớp sự kiện nhờ đệm.

## NEN-012 · consumer Kafka chuẩn với inbox idempotent, thử lại, DLQ

**Chưa bắt đầu.** Nhánh `feat/NEN-012-consumer` chưa tạo. Phụ thuộc NEN-011 (tên tiêu đề ở `OutboxHeaders`; mã sự kiện ở tiêu đề `id` do EventRouter đặt; global id lược đồ ở `apicurio.value.globalId` 8 byte, đọc bằng `AvroKafkaDeserializer` với `apicurio.registry.headers.enabled=true`, `apicurio.registry.use-id=globalId`). Ghi chú cho bên tiếp nhận: chèn `inbox_processed` phải đủ các cột NOT NULL của NEN-032 (`tenant_id`, `source_topic`, `kafka_partition`, `kafka_offset`; câu SQL mẫu TL-03 L4278 ghi cột `event_type` không có trong DDL); dọn inbox dùng hàm `fn_purge_inbox_processed` của NEN-032.
