# NEN-018 · SVC-14/SVC-15 Thư viện Python dùng chung cho ai-gateway và job phân tích

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-018` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | S |
| Phụ thuộc | NEN-007 |
| Truy vết | ADR-12, ADR-28 → SVC-14 ai-gateway (Python 3.12, FastAPI), SVC-15 analytics (job Python phát EVT-20) → schema `ai` → EVT-20, EVT-24, `sys.evidence.object.stored.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo gói Python dùng chung cho ai-gateway và các job phân tích viết bằng Python, gồm ngữ cảnh tenant, lỗi RFC 9457, OpenTelemetry và consumer Kafka có inbox, để hai nơi này theo đúng quy ước của hệ thống ngay từ đầu. Đây là thư viện nhỏ hơn bản Java vì Python chỉ dùng ở vùng bảo mật (AI) và phía đọc (phân tích), không ghi schema nghiệp vụ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục SVC-14 (L1184–1192), SVC-15 (L1194–1204), chương "Trí tuệ nhân tạo" (L3082–3120: ai-gateway năm bước, không C3, ghi `ai.suggestion_log`), "OpenTelemetry và lan truyền ngữ cảnh trace" (L2929–2935).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Đặt ngữ cảnh tenant an toàn qua PgBouncer" (L706–734: Python dùng asyncpg), "Schema ai (SVC-14)" (L3958–4014), "Danh mục lược đồ EVT-01 đến EVT-26" (EVT-20 không qua outbox).
- Kết quả NEN-007, NEN-009 (`error-codes.json`), NEN-012 (hành vi consumer Java).

## 3. Phạm vi công việc

- Tạo gói `services-py/vtplatform/` (Python 3.12, `pyproject.toml`) với mô-đun `tenant`, `db`, `problem`, `otel`, `kafka`, `logging`.
- `tenant` + `db`: dependency FastAPI đọc claim tenant từ JWT đã xác thực (tên claim cấu hình được), trả 403 Problem Details khi thiếu; `async with db.tenant_tx(pool, tenant_id)` mở giao dịch asyncpg và `select set_config('app.tenant_id', $1, true)`.
- `problem`: exception handler FastAPI trả `application/problem+json` đúng `ProblemDetails`, nạp `error-codes.json` của NEN-009; không trả traceback.
- `otel` + `logging`: khởi tạo OTel (auto-instrumentation FastAPI, asyncpg, httpx), nhật ký JSON có `trace.id`, `tenant.id`, bộ lọc che JWT, số định danh, số điện thoại, email; có thêm bộ che riêng cho văn bản gửi mô hình (guardrail C2 do SVC-14 tự làm, thư viện chỉ cung cấp hàm che chung).
- `kafka`: consumer giải tuần tự Avro theo lược đồ `contracts/events/avro`, inbox idempotent trong cùng giao dịch, thử lại rồi `<topic>.dlq.<consumer>`; producer tối giản cho EVT-20 (không qua outbox, `alertKey` tất định làm `event_id` theo TL-03) (suy luận về thư viện Kafka Python, ghi lựa chọn vào báo cáo).
- Kiểm thử `pytest` gồm kiểm thử đơn vị và kiểm thử tích hợp với Testcontainers Python (PostgreSQL, Kafka) chạy khi có Docker.

## 4. Ngoài phạm vi

- Khung ai-gateway và job phân tích cụ thể: nhóm DV-14, DV-15.

## 5. Tệp được phép sửa

- `services-py/vtplatform/**` (mới)
- `services-py/README.md` (mới, ngắn)

## 6. Tiêu chí hoàn thành

- `cd services-py/vtplatform && pytest` chạy xanh; `ruff check .` (hoặc công cụ lint đã chọn, ghi trong báo cáo) sạch; `pip-audit` không có lỗ hổng mức cao.
- Kiểm thử chứng minh hai tenant không đọc được dữ liệu của nhau qua `tenant_tx` trên bảng có RLS mẫu.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
