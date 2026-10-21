# Kiến trúc `mission-core` và các Microservice Đa Ngôn Ngữ (Java / Go / Python)

Tài liệu này tóm tắt kiến trúc thực thi phía máy chủ của nền tảng Mission theo **ADR-01 (Modular Monolith + Selective Microservices)**, **ADR-02 (Transactional Outbox + Debezium CDC)**, **TL-01 §3–§4** và **TL-03 (PostgreSQL 17 + PgBouncer + FORCE RLS)**.

---

## 1. Phân bổ dịch vụ theo ngôn ngữ thực thi

| Nhóm | Dịch vụ / Module | Ngôn ngữ & Nền tảng | Thư viện nền dùng chung |
|---|---|---|---|
| **Core Monolith** | `mission-core` (MOD-01..MOD-06: Identity, Org, Task, OKR/KPI, Cadre Evaluation, Report) | Java 21 + Spring Boot 3.4 | `backend/libs/vt-*` (`vt-kernel`, `vt-tenant`, `vt- persistence`, `vt-outbox`, `vt-auth`, `vt-observability`, `vt-audit`) |
| **Go Microservices** | `SVC-03 tenant-registry`, `SVC-09 ledger-service`, `SVC-12 notification-service` | Go 1.23+ | `services-go/vtplatform` (`tenant`, `db`, `outbox`, `inbox`, `problem`, `otel`, `logx`) |
| **Python Microservices** | `SVC-14 ai-analytics-service` (Early Warning & Anomaly Detection) | Python 3.12+ (FastAPI / AsyncIO) | `services-py/vtplatform` (`tenant`, `db`, `problem`, `otel`, `logging`, `kafka`) |

---

## 2. Quy ước bắt buộc cho Microservice Go (`services-go/vtplatform`)

Mọi microservice Go (`SVC-03`, `SVC-09`, `SVC-12`) bắt buộc nhập và sử dụng gói `vn.viettel.okrkpi/services-go/vtplatform` để đảm bảo hành vi đồng nhất 100% với `mission-core` Java:

### 2.1. Trích xuất Tenant từ JWT (`vtplatform/tenant`)
- Mọi tuyến HTTP nghiệp vụ phải bọc qua `tenant.Middleware(tenant.MiddlewareConfig{...})`.
- Mặc định đọc claim `tenant_id` (tương thích `NEN-010`), kiểm tra định dạng UUID chuẩn và lưu vào `context.Context`.
- Khi thiếu hoặc sai định dạng UUID, trả ngay HTTP `403 Forbidden` (`Content-Type: application/problem+json`, mã lỗi `COMMON-403-FORBIDDEN`), tuyệt đối không cho request chạm vào tầng CSDL.

### 2.2. Giao dịch PostgreSQL qua PgBouncer Transaction Pooling (`vtplatform/db`)
- Hệ thống vận hành PgBouncer ở chế độ `pool_mode = transaction` (TL-03 L706-734). Kết nối vật lý PostgreSQL được trả về pool ngay sau `COMMIT`/`ROLLBACK`.
- **Cấm tuyệt đối** đặt `SET app.tenant_id` ở cấp session hoặc gọi `set_config('app.tenant_id', ..., false)`. Bộ bọc `db.InTx` và `db.InTenantTx` tự động kiểm tra và từ chối mọi câu lệnh vi phạm (`db.ErrSessionTenantForbidden`).
- Mọi truy cập bảng có bật `FORCE ROW LEVEL SECURITY` phải nằm trong `db.InTx(ctx, pool, func(ctx context.Context, tx db.Tx) error { ... })`, hàm này tự động thực thi:
  ```sql
  SELECT set_config('app.tenant_id', $1, true)
  ```
  ở đầu mỗi transaction để phạm vi GUC chỉ tồn tại trong transaction hiện tại.

### 2.3. Transactional Outbox & Idempotent Inbox (`vtplatform/outbox`, `vtplatform/inbox`)
- **Outbox (`<schema>.outbox_event`)**: Sử dụng `outbox.Writer.Write(ctx, tx, env)` ngay bên trong callback của `db.InTx`. Khi transaction nghiệp vụ rollback, bản ghi outbox cũng rollback nguyên tử.
  - Chỉ chấp nhận `Classification` là `C0` hoặc `C1`. Dữ liệu `C2`/`C3` bị chặn ngay tại `Envelope.Validate()` (`outbox.ErrInvalidClassification`).
  - Cột `headers` (JSONB) chứa đầy đủ bộ header CloudEvents 1.0 (`ce_specversion`, `ce_id`, `ce_source`, `ce_type`, `ce_time`, `tenant_id`, `aggregate_version`, `schema_version`, `classification`, `traceparent`) khớp với cấu hình Debezium Outbox Event Router (`NEN-008`).
- **Inbox (`<schema>.inbox_processed` & `<schema>.inbox_aggregate_version`)**: Sử dụng `inbox.Guard.Process(ctx, pool, event, handler)`:
  1. Chèn `(consumer, event_id)` vào `<schema>.inbox_processed ON CONFLICT (consumer, event_id) DO NOTHING`. Nếu `RowsAffected == 0`, trả về `inbox.OutcomeDuplicateEvent` và bỏ qua handler.
  2. Nếu sự kiện có thông tin aggregate (`AggregateType`, `AggregateID`, `AggregateVersion >= 1`), cập nhật `<schema>.inbox_aggregate_version` với điều kiện `WHERE last_version < EXCLUDED.last_version`. Nếu `RowsAffected == 0` (sự kiện đến muộn/cũ hơn phiên bản đã xử lý), trả về `inbox.OutcomeStaleVersion` và bỏ qua handler.
  3. Thực thi `handler` trong cùng transaction; nếu `handler` trả lỗi, toàn bộ trạng thái inbox được rollback để Kafka consumer thử lại an toàn.

### 2.4. Phản hồi lỗi chuẩn RFC 9457 (`vtplatform/problem`)
- Danh mục mã lỗi `services-go/vtplatform/problem/error-codes.json` được nhúng trực tiếp qua `//go:embed` và đồng bộ tự động từ nguồn chuẩn `backend/libs/vt-kernel/src/main/resources/error-codes.json` (`NEN-009`) bằng lệnh `go run ./cmd/sync-error-codes`.
- Hàm `problem.SanitizeDetail` tự động loại bỏ thông tin nhạy cảm về cấu trúc bảng/cột SQL (`SQLSTATE`, `relation "..."`, stack trace) trước khi trả về client.

### 2.5. Quan sát & Bảo mật Nhật ký (`vtplatform/otel`, `vtplatform/logx`)
- `otel.Init` bắt buộc khai báo `service.name` và `cell`. Middleware `otel.Provider.HTTPMiddleware` trích xuất hoặc khởi tạo mới header W3C `traceparent`.
- `logx.NewLogger` xuất JSON một dòng kèm `trace.id`, `span.id`, `tenant.id`, `service.name`, `cell` ở cấp gốc và tự động che (`Scrub`) JWT Bearer token, số CCCD 12 số, số điện thoại Việt Nam và địa chỉ email.

## Khung `mission-core`: 8 DataSource, định tuyến đọc/ghi và bảo vệ 9 schema (CORE-00-01)

`backend/mission-core/` khởi tạo 8 pool HikariCP nghiệp vụ (`task=8, okr=4, appraisal=4, product=3, config=2, policy=1, dossier=1, oversight=1`, tổng 24 <= 35 kết nối/pod) cùng `orgReplicaDataSource`, gắn `prepareThreshold=0&ApplicationName=mission-core-<module>`, định tuyến đọc/ghi qua `ReadWriteRoutingDataSource` + `RecentWriteTracker` (ghim RW 2 giây sau khi ghi, lùi về RW khi trễ replica > 1000 ms) và kiểm tra `flyway_schema_history` của cả 9 schema qua `MissionCoreSchemaVersionGuard`.

