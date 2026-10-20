> Nguồn: TL-01 chương "Chịu lỗi và tự phục hồi" (mục "Cấu hình Resilience4j chuẩn", "Timeout mặc định", "Cô lập tài nguyên theo phụ thuộc"), chương "Quan sát" (mục "Log có cấu trúc", "Che dữ liệu nhạy cảm", "Tách nhật ký kiểm toán", "OpenTelemetry và lan truyền ngữ cảnh trace"); ADR-12, ADR-24; TL-03 (topic `sys.audit.v1`); task NEN-016.

# Bảo mật phía máy chủ

Trang này gom các quy ước bảo mật mà thư viện nền `vt-platform-starter` áp sẵn cho mọi service Java (mission-core và microservice). Service chỉ cần phụ thuộc vào starter; ghi đè một giá trị mặc định phải có lý do ghi trong mã hoặc PR (ADR-24). Hiện trang mới có mục nhật ký và che dữ liệu do NEN-016 tạo; các mục khác (xác thực, RLS, mã hoá) bổ sung khi task tương ứng hoàn thành.

## Nhật ký và che dữ liệu

### Định dạng và nơi ghi

- Log4j2 với `JsonTemplateLayout` theo ECS (`EcsLayout.json`), ghi ra đầu ra chuẩn, không ghi tệp trong container. Cấu hình nằm trong `log4j2-spring.xml` của starter, Spring Boot tự nạp.
- Mỗi dòng có `@timestamp`, `log.level`, `log.logger`, `message`, `service.name` (lấy từ biến môi trường `OTEL_SERVICE_NAME`), `event.dataset` (`mission.app` hoặc `mission.audit`), và khi có ngữ cảnh: `trace.id`, `span.id`, `tenant.id`.
- `trace.id` của nhật ký, `traceId` của phản hồi lỗi RFC 9457 và trace trong Tempo là một giá trị: lấy từ span OpenTelemetry hiện hành, không có span thì lấy từ header `traceparent`, không có nữa thì sinh mới.
- `tenant.id` chỉ lấy từ nguồn đã kiểm (bean `TenantIdProvider`; NEN-010 cung cấp hiện thực đọc từ token). Starter mặc định không ghi tenant, để không bao giờ ghi một giá trị do client tự khai (suy luận).
- Ghi bất đồng bộ (AsyncLogger, LMAX Disruptor). Hàng đợi đầy thì bỏ dòng từ INFO trở xuống, giữ WARN và ERROR, và đếm số dòng bị bỏ ở metric `log4j2.events.discarded`; cần cảnh báo khi metric này tăng.
- Service phải loại `spring-boot-starter-logging` (Logback) khỏi các starter khác của Spring Boot; starter đã kéo `spring-boot-starter-log4j2`. Có cả hai thì Spring Boot báo xung đột SLF4J lúc khởi động.

### Che dữ liệu (hai lớp)

1. **Lớp một, không ghi ngay từ đầu.** Không ghi thân yêu cầu, header `Authorization`, cookie, dữ liệu C2/C3. Starter tắt các logger ghi chi tiết HTTP (`CommonsRequestLoggingFilter`, wire log của Apache HttpClient, `jdk.internal.httpclient.debug`) và đặt `spring.mvc.log-request-details=false`. Luật ArchUnit `PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS` cấm dùng bộ lọc ghi yêu cầu của Spring trong mã.
2. **Lớp hai, che khi lỡ ghi.** Mọi dòng đi qua RewritePolicy `<MissionRedaction/>` (lớp `RedactingRewritePolicy`) trước khi ra appender. Policy che thông điệp, giá trị MDC và thông điệp của ngoại lệ đính kèm (cả chuỗi nguyên nhân, giữ tên lớp và dấu vết ngăn xếp gốc). Các mẫu bị che:

| Mẫu | Thay bằng |
|---|---|
| Chuỗi JWT (`eyJ….….…`) | `[REDACTED:JWT]` |
| Token sau `Bearer` / `Basic` | `[REDACTED:TOKEN]` |
| Giá trị header `Cookie` / `Set-Cookie` | `[REDACTED:TOKEN]` |
| Số định danh 12 chữ số đứng riêng (CCCD) | `[REDACTED:ID]` |
| Số điện thoại Việt Nam (`0`, `84`, `+84` rồi 9 chữ số) | `[REDACTED:PHONE]` |
| Địa chỉ email | `[REDACTED:EMAIL]` |

Lớp hai chỉ là lưới an toàn: mẫu không nhận diện được (họ tên, địa chỉ, nội dung đánh giá) vẫn lọt nếu lớp một bị vi phạm. Số 12 chữ số chỉ bị che khi không liền chữ, số hay dấu gạch, để mã vết hex và UUID không bị che nhầm. `Redactor` dùng được trực tiếp cho `toString()` của kiểu mang dữ liệu C2.

### Bẫy kỹ thuật

- Plugin Log4j2 tự viết (`@Plugin`) chỉ được phát hiện nhờ bộ xử lý annotation của `log4j-core`, sinh ra tệp `META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat` trong jar. Thiếu tệp này thì `<MissionRedaction/>` bị bỏ qua, nhật ký không được che mà chỉ có cảnh báo lúc khởi động. Kiểm tra lại khi đổi cấu hình trình biên dịch.
- Kiểm thử bắt đầu ra bằng `OutputCaptureExtension` cần `follow="true"` trên appender Console. Vì ghi bất đồng bộ, kiểm thử phải chờ dòng xuất hiện (Awaitility), không đọc ngay sau lời gọi.
- MDC và ngữ cảnh OpenTelemetry không tự đi theo sang luồng khác. `ResilientClient` mang cả hai sang luồng ảo chạy lời gọi ra ngoài; mã tự dùng executor phải tự làm việc này.
- Giới hạn thời gian của câu lệnh SQL dùng timeout phía JDBC (`jakarta.persistence.query.timeout` 5 s) và timeout giao dịch 5 s, không dùng `SET statement_timeout` theo phiên, để chạy được qua PgBouncer chế độ transaction (suy luận).

### Nhật ký kiểm toán

- Kênh riêng, không đi qua logger vận hành: đăng nhập quản trị, đổi quyền, truy cập dữ liệu C2/C3, xuất dữ liệu, thao tác quản trị, thao tác ở mốc pháp lý. Mã nghiệp vụ gọi `AuditLogger.record(AuditEvent)`. Sự kiện truy cập dữ liệu nhạy cảm bắt buộc ghi mức C2 hoặc C3, và sự kiện chỉ mang định danh đối tượng, không mang dữ liệu dạng rõ.
- `DefaultAuditLogger` điền mã sự kiện UUIDv7, thời điểm UTC và mã vết, che thuộc tính khớp mẫu nhạy cảm, rồi chuyển sự kiện cho `AuditSink`.
- Đích đến chuẩn là outbox lên topic `sys.audit.v1` (NEN-011 khai báo bean `AuditSink`). Trước khi có NEN-011, starter dùng `LoggingAuditSink`: mỗi sự kiện là một dòng JSON trên logger `OKRKPI_AUDIT`, ra appender riêng với `event.dataset=mission.audit`. Kênh tạm này không bền như outbox và starter cảnh báo lúc khởi động. Tên các loại sự kiện (`AuditEventType`) là suy luận, chờ lược đồ Avro của NEN-007.

### Chịu lỗi và cổng quản trị (liên quan an toàn vận hành)

- Cấu hình Resilience4j chép nguyên văn TL-01, nạp với độ ưu tiên thấp nhất. Kiểm thử `Resilience4jDefaultsTest` đối chiếu từng khoá của registry với khối YAML trong TL-01. Lời gọi ra ngoài đi qua `ResilientClientFactory`/`ResilientClient`. Phụ thuộc không dùng được thì trả 503 kèm `Retry-After`, nội dung phản hồi không lộ tên phụ thuộc.
- Actuator chạy ở cổng quản trị riêng (`MANAGEMENT_PORT`, mặc định 8081) và chỉ mở `health`, `info`, `prometheus`. Cổng này không được đưa ra Ingress công khai (suy luận).
- Luật ArchUnit `PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS` cấm `Executors.newCachedThreadPool` và `Executors.newFixedThreadPool`. Service gắn luật này vào kiểm thử kiến trúc của mình.

## Liên quan

- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
