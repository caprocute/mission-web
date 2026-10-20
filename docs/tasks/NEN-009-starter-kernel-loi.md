# NEN-009 · vt-platform-starter: nhân `kernel` và lỗi RFC 9457 theo bảng mã lỗi chuẩn

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-009` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-005 |
| Truy vết | ADR-24, HD-01 → TL-01 "Cấu trúc gói Java theo module" (gói `shared.kernel`, `shared.error`), "Định dạng lỗi chuẩn RFC 9457" → `contracts/openapi/common/common.yaml` (`ProblemDetails`, `FieldError`) → mọi SVC Java |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo hai thư viện nền đầu tiên mà mọi mã Java dựa vào: `vt-kernel` (kiểu giá trị thuần Java dùng trong lớp `domain`, không phụ thuộc Spring, JPA hay Jackson) và bộ khung `vt-platform-starter` (Spring Boot auto-configuration) với phần xử lý lỗi RFC 9457 chuẩn. Khi xong, mọi service trả lỗi `application/problem+json` đúng lược đồ `ProblemDetails` của hợp đồng, mã lỗi `<MODULE>-<HTTP>-<TÊN>` lấy từ một danh mục duy nhất sinh từ hợp đồng, không bao giờ lộ stack trace hay câu SQL.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Cấu trúc gói Java theo module" (L389–448), "Định dạng lỗi chuẩn RFC 9457" (L2904–2921), ADR-24 (L3622).
- `contracts/openapi/common/common.yaml` (`ProblemDetails`, `FieldError`, các `responses` 400/401/403/404/409/421/422/428/429/503); `contracts/openapi/README.md` mục "Lỗi".
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md` HD-01 (409 khi lệch phiên bản, 428 khi thiếu `If-Match`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Kiểu dữ liệu chuẩn" (UUIDv7 sinh ở ứng dụng bằng uuid-creator, L74–95).
- `docs/quy-dinh/BACKEND.md` mục 3 quy ước 2.

## 3. Phạm vi công việc

- Tạo mô-đun `backend/libs/vt-kernel/` (Java thuần): `TenantId`, `PeriodId`, `ActorId` dạng `record` bọc `UUID`; `Ids.newUuidV7()` dùng thư viện uuid-creator; giao diện `Clock` của hệ thống (thời gian UTC, thay được trong kiểm thử); `Result<T, E>` cho lớp `domain`; `Money` theo TL-01; `Classification` (C0..C3) dùng chung với annotation `@Classified` (NEN-015 dùng tiếp).
- Tạo mô-đun `backend/libs/vt-platform-starter/` với `AutoConfiguration.imports` và gói `error`: `@RestControllerAdvice` trả `ProblemDetail` mở rộng trường `code`, `msg`, `errors[]`, `traceId` đúng lược đồ hợp đồng; `type` là URI ổn định `https://okrkpi.vn/problems/<slug>`, `title` tiếng Việt.
- Ánh xạ chuẩn: lỗi kiểm dữ liệu Jakarta Validation → 400 hoặc 422 `COMMON-422-VALIDATION` có `errors[]` theo trường; `OptimisticLockingFailureException` và lệch `If-Match` → 409 `<MODULE>-409-VERSION-CONFLICT`; thiếu `If-Match` → 428; không có quyền → 403; ngoại lệ lạ → 500 chung, chỉ ghi chi tiết vào nhật ký kèm `traceId`.
- Sinh danh mục mã lỗi: script `backend/libs/vt-platform-starter/tools/extract-error-codes.mjs` (hoặc bước Maven) quét `contracts/openapi/**` lấy mọi mã dạng `<MODULE>-<HTTP>-<TÊN>` (hiện khoảng 179 mã) cùng mã MSG đi kèm nếu có, ghi ra tài nguyên `error-codes.json`; lớp `ErrorCatalog` nạp tệp này, ngoại lệ nghiệp vụ `BusinessException(code)` chỉ nhận mã có trong danh mục.
- Kiểm thử đơn vị đảm bảo mọi mã trong danh mục có HTTP status khớp phần số trong mã và không có mã trùng; kiểm thử ArchUnit (trong starter) cấm `vt-kernel` phụ thuộc gói `org.springframework..`, `jakarta.persistence..`, `com.fasterxml.jackson..`.
- Điểm lệch cần lưu ý: TL-02a mục "Thông báo, xác nhận và lỗi" dùng URI `https://okr.viettel.vn/errors/BR-xx-yy`, còn TL-01 L2904–2921 và hợp đồng dùng `https://okrkpi.vn/problems/<slug>`; task theo hợp đồng (API-first) và ghi vào báo cáo; mã BR liên quan đặt ở `msg` hoặc phần mở rộng, không thay `type`.
- Điểm lệch cần lưu ý: TL-01 đặt các gói này trong `shared` của mission-core, còn `_ghi-chu-TL-01.md` L43 gọi là thư viện nền `vt-platform-starter`; task tách thành thư viện để microservice Java dùng lại (suy luận), mission-core vẫn có thể có gói `shared` mỏng bọc lại.

## 4. Ngoài phạm vi

- Ngữ cảnh tenant: NEN-010; outbox: NEN-011; idempotency, ETag, phân trang, job: NEN-013; OPA: NEN-014; mã hoá: NEN-015; chịu lỗi, quan sát, nhật ký: NEN-016.

## 5. Tệp được phép sửa

- `backend/libs/vt-kernel/**`, `backend/libs/vt-platform-starter/**`
- `backend/libs/pom.xml`, `backend/pom.xml` (chỉ thêm mô-đun), `backend/platform-bom/pom.xml` (chỉ thêm uuid-creator)
- `backend/tools/build-smoke/**` (được xoá theo ghi chú của NEN-005)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-kernel,libs/vt-platform-starter -am verify` chạy xanh, độ phủ dòng ≥ 80%.
- Kiểm thử Web MVC: một controller thử ném lần lượt các lỗi chuẩn; phản hồi có `Content-Type: application/problem+json`, hợp lệ theo lược đồ `ProblemDetails` của `common.yaml` (kiểm bằng tiện ích của NEN-006 nếu đã có, hoặc JSON Schema), không chứa tên lớp Java hay SQL.
- UUID sinh ra là phiên bản 7 và tăng dần theo thời gian (kiểm thử đơn vị).
- Báo cáo cuối task theo AGENTS.md mục 6, ghi số mã lỗi trích được và các mã không ánh xạ được MSG.

## 7. Báo cáo

Theo AGENTS.md mục 6.
