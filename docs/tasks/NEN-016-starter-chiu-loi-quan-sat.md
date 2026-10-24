# NEN-016 · ADR-12/ADR-24 vt-platform-starter: Resilience4j chuẩn, timeout mặc định, OpenTelemetry, nhật ký ECS có che dữ liệu, nhật ký kiểm toán

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-016` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-009 |
| Truy vết | ADR-12, ADR-24 → TL-01 "Cấu hình Resilience4j chuẩn", "Timeout mặc định", "OpenTelemetry và lan truyền ngữ cảnh trace", "Log có cấu trúc", "Che dữ liệu nhạy cảm", "Tách nhật ký kiểm toán" → topic `sys.audit.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đóng gói vào thư viện nền cấu hình chịu lỗi và quan sát chuẩn, để mọi service Java giống nhau mà không tự chép YAML: Resilience4j (bulkhead → time limiter → circuit breaker → retry) với các instance đặt tên theo TL-01, bảng timeout mặc định, OpenTelemetry, nhật ký JSON theo ECS có che dữ liệu nhạy cảm, và kênh nhật ký kiểm toán tách khỏi nhật ký vận hành. Khi xong, mỗi lời gọi ra ngoài có giới hạn thời gian, mỗi dòng nhật ký có `trace.id` và `tenant.id`, và không dòng nào chứa token hay số định danh.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Độ tin cậy và khả năng chịu lỗi" (L2803–2902: YAML Resilience4j nguyên văn L2811–2855, bảng timeout L2863–2874, "Cô lập tài nguyên theo phụ thuộc"), chương "Quan sát và nhật ký" (L2923–3000: OTel, lấy mẫu, metric, XML Log4j2 L2957–2986, che dữ liệu, nhật ký kiểm toán); ADR-12, ADR-24.
- `docs/quy-dinh/BACKEND.md` mục 3 quy ước 9, 10.

## 3. Phạm vi công việc

- Gói `resilience`: tệp cấu hình mặc định nạp sẵn đúng YAML TL-01 L2811–2855 (circuit breaker `TIME_BASED` 30 s, tối thiểu 20 lời gọi, lỗi 50%, chậm 2 s/80%, mở 20 s, nửa mở 5; instance `esign-mysign`, `vneid`, `cbccvc`, `docgen`; time limiter 3 s; retry 3 lần 200 ms ×2 jitter 0,5 chỉ cho `IOException`/`TimeoutException`, bỏ `BusinessException`; bulkhead 25/50 ms) cho phép service ghi đè từng instance.
- Tiện ích `ResilientClient` bọc `RestClient` theo đúng thứ tự bulkhead → time limiter → circuit breaker → retry và chỉ cho retry thao tác idempotent (GET hoặc có `Idempotency-Key`); khi mạch mở trả lỗi chuẩn 503 kèm `Retry-After`.
- Timeout mặc định theo bảng TL-01: REST nội bộ 500 ms kết nối/3 s đọc, PostgreSQL 2 s lấy kết nối + `statement_timeout` 5 s, Valkey 200/300 ms (bỏ qua khi lỗi), Kafka `delivery.timeout.ms` 30 s, OpenBao 500 ms/2 s; luật ArchUnit cấm `Executors.newCachedThreadPool`/`newFixedThreadPool` ngoài thư viện nền.
- Gói `observability`: cấu hình OpenTelemetry (agent Java 2.x qua `JAVA_TOOL_OPTIONS`, thuộc tính tài nguyên `service.name`, `cell`), `@WithSpan` ở use case, metric `http.server.requests` với mốc 50/100/300/800/2.000 ms, cổng quản trị Actuator riêng; sqlcommenter gắn `traceparent` và `tenant` vào câu SQL.
- Nhật ký: Log4j2 `JsonTemplateLayout` theo `EcsLayout.json` thêm `service.name`, `trace.id`, `span.id`, `tenant.id`; `AsyncLogger`, chính sách đầy hàng đợi `Discard` từ mức INFO và metric đếm dòng bị bỏ; `RewritePolicy` che JWT (`eyJ...`), số định danh 12 chữ số, số điện thoại, email; cấm ghi thân yêu cầu, header `Authorization`, cookie.
- Nhật ký kiểm toán: `AuditLogger.record(AuditEvent)` cho sự kiện đăng nhập quản trị, đổi quyền, truy cập C2/C3, xuất dữ liệu, thao tác quản trị; ghi qua `OutboxWriter` lên `sys.audit.v1` khi có NEN-011, không ghi chung luồng nhật ký vận hành (suy luận về giao diện).
- Kiểm thử: circuit breaker mở sau ngưỡng và trả 503; retry không chạy với POST thiếu `Idempotency-Key`; mẫu nhật ký chứa JWT và số định danh bị che; mỗi dòng nhật ký trong yêu cầu thử có `trace.id` và `tenant.id`.

## 4. Ngoài phạm vi

- Collector, Tempo, Loki, Prometheus, SLO, cảnh báo: INF-14, INF-15.
- Ghi sổ cái lần giải mã C2: CORE-00-05; luồng `sys.audit.v1` tới SIEM: INF-19.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `resilience`, `observability`, `audit`, cấu hình Log4j2, kiểm thử)
- `backend/platform-bom/pom.xml` (chỉ thêm Resilience4j Spring Boot 3, OTel API, Log4j2 layout)
- `wiki/20-bao-mat-phia-may-chu.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh với các kiểm thử ở mục 3.
- So khớp cấu hình Resilience4j đã nạp với YAML TL-01 bằng kiểm thử đọc `CircuitBreakerRegistry`, `TimeLimiterRegistry`, `RetryRegistry`, `BulkheadRegistry`.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 20 (nếu đã có) mục "Nhật ký và che dữ liệu".

## 7. Báo cáo

Theo AGENTS.md mục 6.
