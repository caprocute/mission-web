# Quy định riêng cho phần máy chủ (mission-core, microservice Go/Java/Python)

> Tệp này bổ sung `AGENTS.md` cho mọi task có mã `NEN-`, `CORE-`, `DV-`, `DL-` và các task `QA-` đụng tới mã máy chủ. `AGENTS.md` vẫn là quy định gốc; khi hai nơi khác nhau, `AGENTS.md` thắng ở quy tắc chung (bám thiết kế, giữ mã, phạm vi, bí mật, báo cáo trung thực), tệp này thắng ở chi tiết kỹ thuật phía máy chủ. Nguồn: TL-01 chương 5, 6, 12, 16, 17, PL B; TL-03; TL-04 chương 9; BS-02 (ADR-32 API-first).

## 1. Stack đã chốt, không tự đổi

Lõi nghiệp vụ `mission-core` là một khối nguyên khối theo mô-đun (modular monolith) viết bằng Java 21, Spring Boot 3.5 và Spring Modulith 1.4, gói gốc `vn.viettel.okrkpi`, công cụ build Maven đa mô-đun. Mỗi mô-đun MOD-01..MOD-08 là một gói con với bốn lớp `api` (cổng công khai cho mô-đun khác), `application`, `domain`, `infrastructure` và gói `internal` không được mô-đun khác truy cập. Ma trận phụ thuộc giữa các mô-đun lấy đúng theo TL-01 mục mô-đun (config ← policy ← okr ← task ← product ← appraisal; dossier và oversight chỉ đọc qua sự kiện hoặc cổng `api`), và được kiểm bằng ArchUnit cùng `ApplicationModules.verify()` trong kiểm thử.

Microservice dùng ngôn ngữ đã định trong baseline: Java 21 + Spring Boot cho các dịch vụ nghiệp vụ tách ra (ký số, tài liệu, tích hợp, quy trình Flowable 7), Go 1.23 cho `tenant-registry`, `ledger`, `notification`, Python 3.12 + FastAPI cho `ai-gateway`. Hạ tầng dữ liệu: PostgreSQL 17 (CloudNativePG trên K8s), PgBouncer chế độ transaction, Kafka + Debezium cho outbox, Apicurio Registry với Avro, Valkey, OpenSearch, ClickHouse, Ceph RGW có Object Lock, OpenBao. Không thêm thư viện lớn hay đổi công cụ ngoài danh sách này; muốn thêm phải nêu lý do trong PR và được người phụ trách duyệt.

## 2. API-first (ADR-32)

Hợp đồng OpenAPI 3.1 trong `contracts/openapi/` là nguồn sự thật cho mọi API HTTP. Mã máy chủ sinh giao diện bằng openapi-generator (Spring, `interfaceOnly=true`) rồi viết lớp hiện thực; không được viết controller lệch hợp đồng. Khi mã và hợp đồng lệch nhau, sửa mã, không sửa hợp đồng (YC-PT-09); nếu hợp đồng thật sự sai, mở task sửa hợp đồng riêng và chạy `openapi-diff` để chứng minh không phá tương thích. Hợp đồng sự kiện nằm ở `contracts/events/` (Avro + AsyncAPI) và đăng ký vào Apicurio với chế độ tương thích BACKWARD.

## 3. Quy ước bắt buộc trong mã máy chủ

1. Mọi lệnh ghi nhận `Idempotency-Key` và lưu khoá 48 giờ; mọi cập nhật nhận `If-Match` và trả 409 khi phiên bản lệch (quyết định HD đã chốt, không dùng 412).
2. Lỗi trả theo RFC 9457 (`application/problem+json`) với `type` trỏ tới mã MSG trong TL-02; không lộ stack trace hay câu SQL.
3. Phân trang con trỏ, `limit` tối đa 200. Tác vụ dài trả 202 kèm `Location` tới tài nguyên tác vụ.
4. Mọi thay đổi trạng thái nghiệp vụ ghi vào bảng outbox trong cùng giao dịch; không gửi Kafka trực tiếp từ mã nghiệp vụ. Bên nhận dùng bảng inbox để khử trùng lặp.
5. Dữ liệu đa khách thuê: mọi bảng nghiệp vụ có `tenant_id`, bật RLS, đặt `SET LOCAL app.tenant_id` ở đầu giao dịch qua thư viện nền; không truy vấn vượt tenant trừ API của vai trò liên tenant đã ghi trong ma trận phân quyền.
6. Quyền: kiểm tra vai trò bằng token Keycloak, kiểm tra phạm vi dữ liệu bằng OPA (ABAC) theo `wiki/15-phan-quyen.md` và `wiki/15a-ma-tran-phan-quyen.md`; không tự đặt danh sách vai trò trong mã.
7. Giữ nguyên tên schema, bảng, cột, trạng thái và mã EVT như TL-03 và baseline. Migration dùng Flyway, chỉ thêm mới (expand/contract), không sửa migration đã gộp.
8. Dữ liệu mức C2 mã hoá cột hoặc mã hoá ứng dụng theo TL-03; khoá lấy từ OpenBao, không đặt khoá trong mã hay cấu hình.
9. Nhật ký có cấu trúc JSON, kèm `traceparent`, `tenant_id`, mã tác nhân AC; không ghi dữ liệu C2/C3 hay token vào nhật ký.
10. Đo lường bằng OpenTelemetry (trace, metric, log) qua thư viện nền, không tự dựng exporter.

## 4. Kiểm thử và tiêu chí hoàn thành mặc định

Một task máy chủ chỉ hoàn thành khi `mvn -q verify` (hoặc `go test ./...`, `pytest`) của mô-đun hay dịch vụ bị đụng tới chạy xanh, gồm kiểm thử đơn vị, kiểm thử tích hợp bằng Testcontainers (PostgreSQL, Kafka, Keycloak khi cần), kiểm thử kiến trúc ArchUnit/Modulith, kiểm thử hợp đồng (Spring Cloud Contract hoặc so khớp phản hồi với OpenAPI) và kiểm tra `openapi-diff` không phá tương thích. Mã mới đạt độ phủ dòng tối thiểu 80% cho lớp `domain` và `application`. Quét bảo mật (SpotBugs + Find Security Bugs, `govulncheck`, `pip-audit`) không có lỗi mức cao. Báo cáo cuối task theo `AGENTS.md` mục 6, ghi lệnh đã chạy và kết quả thật.

## 5. Môi trường

Kiểm thử trong phiên đám mây dùng Testcontainers hoặc Docker Compose cục bộ; không cần và không được dùng server dev cho kiểm thử đơn vị. Việc triển khai lên server dev chỉ làm trong task có ghi rõ, qua Docker Compose project `mission-dev`, không đụng container khác.

## Liên quan

- `AGENTS.md`, `docs/KE-HOACH-TONG-THE-HE-THONG.md`, `docs/quy-dinh/HA-TANG.md`
