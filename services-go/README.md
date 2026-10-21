# `services-go` — Thư viện nền & Microservice Go

Thư mục `services-go/` chứa thư viện dùng chung `vtplatform` (`vn.viettel.okrkpi/services-go/vtplatform`) và các microservice viết bằng Go 1.23+ trong nền tảng Mission:

- **SVC-03 (`tenant-registry`)**: Quản lý định tuyến cell, cấu hình đơn vị thuê bao (tenant) và vòng đời schema/pool.
- **SVC-09 (`ledger-service`)**: Sổ cái bất biến (Merkle log / hash chain) và đối soát chữ ký số (C3 metadata).
- **SVC-12 (`notification-service`)**: Điều phối thông báo đa kênh (WebPush, Email, SMS, Zalo/OTT) theo sự kiện Kafka.

## Cấu trúc `services-go/vtplatform`

| Gói (Package) | Chức năng | Tham chiếu thiết kế |
|---|---|---|
| `tenant` | Middleware `net/http` trích xuất claim `tenant_id` (tùy biến tên claim) từ JWT đã xác thực, kiểm tra định dạng UUID chuẩn và trả về `403 Forbidden` theo RFC 9457 (`COMMON-403-FORBIDDEN`) nếu thiếu hoặc sai định dạng. | ADR-01, ADR-05, NEN-010 |
| `db` | Quản lý giao dịch PostgreSQL an toàn với PgBouncer `pool_mode = transaction`: hàm `InTx` và `InTenantTx` tự động gọi `SELECT set_config('app.tenant_id', $1, true)` (`SET LOCAL`) ngay đầu transaction. Chặn tuyệt đối câu lệnh `SET app.tenant_id` cấp session (`ErrSessionTenantForbidden`). | TL-03 L706-734, NEN-003 |
| `outbox` | Ghi sự kiện `Envelope` vào bảng `<schema>.outbox_event` trong cùng transaction nghiệp vụ (`db.Tx`), đính kèm đầy đủ header CloudEvents 1.0 (`ce_specversion`, `ce_id`, `ce_source`, `ce_type`, `ce_time`, `tenant_id`, `aggregate_version`, `schema_version`, `classification`, `traceparent`). Chỉ cho phép dữ liệu phân loại `C0`/`C1`; từ chối `C2`/`C3`. | ADR-02, TL-01 §4.3, NEN-007 |
| `inbox` | Bảo vệ consumer Kafka idempotent qua bảng `<schema>.inbox_processed` (`ON CONFLICT (consumer, event_id) DO NOTHING`) và kiểm tra thứ tự phiên bản aggregate qua bảng `<schema>.inbox_aggregate_version` (`WHERE last_version < EXCLUDED.last_version`) trong cùng transaction nghiệp vụ. | ADR-02, TL-01 §4.3, NEN-007 |
| `problem` | Chuẩn hóa phản hồi lỗi HTTP `application/problem+json` theo RFC 9457, nhúng (`//go:embed`) danh mục mã lỗi chuẩn `error-codes.json` đồng bộ từ `NEN-009`, và tự động lọc bỏ tên bảng/cột SQL, stack trace khỏi trường `detail`. | Baseline §13, NEN-009 |
| `otel` | Khởi tạo OpenTelemetry (`service.name`, `cell`), lan truyền ngữ cảnh W3C `traceparent`/`tracestate` qua HTTP và Kafka header, ghi nhận span và bộ đếm metric. | TL-01 L2929-2935, NEN-011 |
| `logx` | Bộ xử lý `log/slog` định dạng JSON tự động gắn `trace.id`, `span.id`, `tenant.id`, `service.name`, `cell` ở cấp gốc và che (scrub) PII/bí mật (JWT Bearer token, số CCCD 12 chữ số, số điện thoại Việt Nam, địa chỉ email). | TL-04 SEC, NEN-011 |

## Lệnh phát triển & kiểm thử

```bash
cd services-go/vtplatform

# Đồng bộ danh mục mã lỗi từ backend/libs/vt-kernel (NEN-009)
go run ./cmd/sync-error-codes

# Kiểm tra định dạng, phân tích tĩnh và chạy toàn bộ kiểm thử
gofmt -l .
go vet ./...
go test -v -cover ./...
```
