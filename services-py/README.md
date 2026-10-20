# `services-py` — Thư viện nền & Microservice Python

Thư mục `services-py/` chứa thư viện dùng chung `vtplatform` và microservice phân tích/AI viết bằng Python trong nền tảng Mission:

- **SVC-14 (`ai-analytics-service`)**: Dịch vụ cảnh báo sớm, phát hiện dấu hiệu bất thường (`EVT-20 DauHieuBatThuong`) và gợi ý thông minh.

## Cấu trúc `services-py/vtplatform`

| Mô-đun (Module) | Chức năng | Tham chiếu thiết kế |
|---|---|---|
| `vtplatform.tenant` | Middleware ASGI/FastAPI (`TenantMiddleware`, `fastapi_tenant_dependency`) trích xuất claim `tenant_id` (tùy biến tên claim) từ JWT đã xác thực, kiểm tra UUID chuẩn và trả về `403 Forbidden` (`application/problem+json`, `COMMON-403-FORBIDDEN`) khi thiếu hoặc sai định dạng. | ADR-01, ADR-05, NEN-010 |
| `vtplatform.db` | Quản lý giao dịch bất đồng bộ (`in_tx`, `in_tenant_tx`) an toàn với PgBouncer `pool_mode = transaction`: tự động thực thi `SELECT set_config('app.tenant_id', $1, true)` (`SET LOCAL`) ở đầu mỗi transaction và chặn tuyệt đối `SET app.tenant_id` cấp session (`SessionTenantForbiddenError`). | TL-03 L706-734, NEN-003 |
| `vtplatform.problem` | Chuẩn hóa phản hồi lỗi HTTP theo RFC 9457 (`ProblemDetails`, `ProblemException`), nạp danh mục mã lỗi chuẩn `error-codes.json` đồng bộ từ `NEN-009`, và tự động lọc bỏ tên bảng/cột SQL, stack trace khỏi `detail`. | Baseline §13, NEN-009 |
| `vtplatform.otel` | Khởi tạo OpenTelemetry (`service.name`, `cell`), lan truyền ngữ cảnh W3C `traceparent`/`tracestate` qua ASGI middleware và Kafka header. | TL-01 L2929-2935, NEN-011 |
| `vtplatform.logging` | Bộ định dạng `JSONLogFormatter` xuất JSON một dòng kèm `trace.id`, `span.id`, `tenant.id`, `service.name`, `cell` ở cấp gốc và tự động che (`scrub_text`) JWT Bearer token, số CCCD 12 số, số điện thoại Việt Nam và địa chỉ email. | TL-04 SEC, NEN-011 |
| `vtplatform.kafka` | Cung cấp `InboxGuard` (consumer idempotent qua `<schema>.inbox_processed` và kiểm tra phiên bản qua `<schema>.inbox_aggregate_version`) và `DirectProducer` cho `SVC-14` phát trực tiếp sự kiện `EVT-20` (`analytics.anomaly.detected.v1`) không qua DB outbox, sử dụng `event_id` (`ce_id`) UUIDv5 tất định sinh từ `(tenant_id, alertKey)` để consumer hạ lưu khử trùng lặp qua `inbox_processed`, đồng thời chặn phân loại `C2`/`C3`. | ADR-02, TL-01 §4.3, NEN-007 |

## Lệnh phát triển & kiểm thử

```bash
cd services-py/vtplatform

# Đồng bộ danh mục mã lỗi từ backend/libs/vt-kernel (NEN-009)
python3 -m vtplatform.sync_error_codes

# Chạy toàn bộ kiểm thử đơn vị và tích hợp
python3 -m unittest discover -s tests -v
```
