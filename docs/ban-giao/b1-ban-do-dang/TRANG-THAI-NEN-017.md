# Trạng thái bàn giao NEN-017 (thư viện Go dùng chung)

> Ngày 20/10/2026. Luồng Claude B1 dừng task này theo YC-PT-15 của sổ tay yêu cầu (các task backend đã giao cho công cụ AI khác). Tệp này ghi phần đã làm, phần còn lại và bước tiếp theo để bên tiếp nhận làm tiếp. NEN-018 chưa bắt đầu (chưa có tệp nào).

## Đã làm (biên dịch được, `go vet ./...` sạch, `gofmt -l .` rỗng; CHƯA có kiểm thử nào ngoài kiểm thử đồng bộ hợp đồng)

Mô-đun `okrkpi.vn/vtplatform` (suy luận, theo miền `okrkpi.vn` của URI lỗi), `go 1.23.8`. Phụ thuộc đã ghim: pgx v5.7.6, franz-go v1.19.5, hamba/avro v2.30.0, OpenTelemetry v1.38.0 (otelhttp v0.63.0), google/uuid v1.6.0, yaml.v3 v3.0.1. Bản mới hơn của các thư viện này đòi Go 1.24 hoặc 1.25 nên không dùng.

| Gói | Nội dung |
|---|---|
| `internal/contractsync`, `internal/gencontracts`, `generate.go` | `go generate ./...` chép `error-codes.json` (NEN-009) và `contracts/events/catalog.yaml` + `avro/**` (NEN-007) vào mô-đun để nhúng; có kiểm thử so khớp từng byte với nguồn |
| `problem` | Danh mục mã lỗi (kiểm dạng, status, trùng như `ErrorCatalog.java`), loại lỗi chuẩn theo status (giữ nguyên chữ của `ProblemTypes.java`), `Details` đúng `ProblemDetails` của `common.yaml`, `Error` (tương đương `BusinessException`), `StatusError`, `Writer.Write`, `Recover`, `TraceID` (span OTel → `traceparent` → mã sinh) |
| `tenant` | Ngữ cảnh tenant trong `context.Context`, `Middleware` đọc claim (mặc định `tenant_id`, cấu hình được, hỗ trợ đường dẫn chấm), 403 `COMMON-403-OUT-OF-SCOPE` khi thiếu hoặc sai dạng UUID. Không tự kiểm chữ ký JWT: bộ xác thực phía trước gọi `tenant.WithClaims` |
| `db` | `InTx`, `InReadTx`, `InTxOptions`, `InTenantTx`: `BeginTx` rồi `select set_config('app.tenant_id', $1, true)`; thiếu tenant đặt chuỗi rỗng (cùng hành vi Java); không có API đặt tenant mức phiên |
| `events` | Nạp danh mục và lược đồ nhúng; tên header CloudEvents nhị phân `ce_*`; `FromKafka` đọc header (chấp nhận cả dạng không tiền tố, header `id` của Debezium, `apicurio.value.globalId` nhị phân 8 byte, header JSON `outbox_headers`); client Apicurio 3.x (API lõi v3: `POST /search/versions?canonical=true`, `GET /ids/globalIds/{id}`, có đệm); `MemoryRegistry` cho kiểm thử |
| `outbox` | `Writer.Append(ctx, tx, Envelope)` ghi đủ cột DDL NEN-032 (`created_at` lấy `now()` của CSDL), chặn C2/C3, chặn topic không có trong danh mục hoặc không đi qua outbox của schema đó, tuần tự hoá Avro theo lược đồ hợp đồng, registry lỗi thì trả lỗi để giao dịch huỷ; `headers` jsonb gồm `ce_*`, `traceparent`, `apicurio.value.globalId` |
| `inbox` | Consumer franz-go hai client (group `<consumer>` cho topic chính, `<consumer>.retry` cho topic retry), thứ tự TL-03, thử tại chỗ 3 lần (200 ms, 1 s, 5 s), retry 1/5/30 phút qua `<topic>.retry.<consumer>`, DLQ `<topic>.dlq.<consumer>` với header `vt.dlq.code`, `vt.dlq.reason` (đã che), `vt.source.*`; metric `vt.inbox.events` theo `outcome`, `vt.inbox.duration`; span consumer khôi phục từ `traceparent` |
| `otel` | `Setup` (resource `service.name`, `cell`; OTLP HTTP theo biến `OTEL_EXPORTER_OTLP_*`; propagator W3C + Baggage), `HTTP` bọc otelhttp |
| `logx` | `Redact` cùng quy tắc và thứ tự với `Redactor.java` (RE2 không có lookbehind nên ranh giới kiểm thủ công), handler slog JSON với `@timestamp`, `log.level`, `message`, `service.name`, `event.dataset`, `trace.id`, `span.id`, `tenant.id`, che mọi chuỗi và khoá nhạy cảm |

## Còn lại

1. Kiểm thử đơn vị: `problem` (dạng phản hồi so với lược đồ, 403/500 không lộ chi tiết), `logx` (đối chiếu các ca của `RedactionTest.java`), `tenant` (403 khi thiếu claim), `events` (nạp đủ 34 topic), `outbox.Build` (C2/C3, topic lạ, header), `inbox` (phân loại lỗi, đọc header).
2. Kiểm thử tích hợp bằng testcontainers-go v0.38.0 (bản cuối còn `go 1.23`; ryuk 0.12.0 đã có sẵn trong Docker cục bộ): PostgreSQL `postgres:17-alpine` + pg_partman (chép `backend/libs/vt-db-migration/src/test/resources/pg_partman/*`), bootstrap vai trò `mig_/svc_/ro_/cdc_<schema>` như `RoleBootstrapPlan.java`, áp `db/common/*.sql` và `db/common-business/*.sql` (thay `${flyway:defaultSchema}`); PgBouncer `mirror.gcr.io/edoburu/pgbouncer:v1.23.1-p3` với `pool_mode=transaction`, `default_pool_size=1`, `max_prepared_statements=200`; Kafka `mirror.gcr.io/apache/kafka:3.9.1`; Apicurio `apicurio/apicurio-registry:3.3.3`. Kịch bản: cách ly tenant qua PgBouncer; outbox rollback không để lại dòng; inbox bỏ trùng và bỏ phiên bản cũ; lỗi tạm thời → retry → thành công; lỗi vĩnh viễn → DLQ.
3. Đối chiếu Debezium (tiêu chí 6.3): ảnh `mirror.gcr.io/debezium/connect:2.7.3.Final` có sẵn; cần `wal_level=logical`, publication `FOR TABLE <schema>.outbox_event WITH (publish_via_partition_root = true)`, dòng `host replication` trong `pg_hba.conf`; viết `testdata/debezium-outbox-connector.json`.
4. `govulncheck ./...`, `services-go/README.md`, báo cáo cuối task.

## Điểm lệch cần bên tiếp nhận và NEN-011/NEN-012 thống nhất

- Cấu hình connector mẫu TL-03 đặt header từ các cột `event_id`, `trace_parent`, `classification`, `schema_id`, nhưng DDL NEN-032 chỉ có cột `id` và gom các giá trị kia vào jsonb `headers`. Debezium EventRouter 2.7 chỉ đặt được cột thành header, không tách jsonb thành nhiều header (đã kiểm `EventRouterConfigDefinition`). Cần chọn: thêm SMT tách jsonb, hoặc đặt cả cột `headers` thành một header (thư viện Go đã đọc được header `outbox_headers`, tên này là suy luận).
- Tên header: thẻ NEN-011/NEN-012 viết `tenantid`, `aggregateversion`, `actorid` không tiền tố; TL-03 và CloudEvents Kafka binding dùng `ce_tenantid`, `ce_type`, `ce_classification`. Thư viện Go ghi dạng `ce_*` và đọc được cả hai dạng.
- `ce_type`: README hợp đồng sự kiện ghi `type` là tên topic; cấu hình TL-03 đặt `event_type` (DDL: tên nghiệp vụ) vào `ce_type`. Thư viện Go ghi `ce_type` = topic, `event_type` = tên nghiệp vụ (ví dụ `SoCaiDaNiemPhong`).
- Thân Avro trong `payload` là Avro nhị phân thuần (không có byte định danh lược đồ ở đầu), global id đi ở header `apicurio.value.globalId` dạng số thập phân (suy luận theo cấu hình connector TL-03). Serde Apicurio Java chế độ header ghi global id dạng 8 byte nhị phân: consumer Go đọc được cả hai dạng; NEN-011 cần chốt.
- TL-01 L1246 ghi DLQ `<topic>.dlq` và lặp 5 lần; TL-03 ghi `<topic>.dlq.<consumer>`, 3 lần rồi topic retry. Thư viện theo TL-03.
- TL-03 mẫu `INSERT inbox_processed` có cột `event_type`; DDL NEN-032 không có. Thư viện theo DDL.

## Lệnh

```bash
cd services-go/vtplatform
go generate ./...        # chép lại hợp đồng khi contracts/ hoặc error-codes.json đổi
go vet ./... && go test ./...
```
