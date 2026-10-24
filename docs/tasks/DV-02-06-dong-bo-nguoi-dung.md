# DV-02-06 · EVT-16 Consumer đồng bộ người dùng vào Keycloak qua Admin API

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-02-01, DV-04-03; NEN-012 (consumer chuẩn, inbox, DLQ) |
| Truy vết | QT-01 → EVT-16 NhanSuDaThayDoi (`org.person.changed.v1`, SVC-04 phát) → SVC-02 iam (Keycloak Admin REST, mạng quản trị) → bảng `org.person` (nguồn), `iam.inbox_processed` (suy luận); ADR-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tài khoản trong Keycloak luôn khớp với hồ sơ nhân sự ở org-service mà không ai phải tạo tay: khi org-service phát EVT-16, consumer tạo, cập nhật, vô hiệu hoá người dùng trong đúng Organization của tenant, với `sub` bằng `org.person.id`. Khi xong, cán bộ mới nhập từ nguồn nhân sự đăng nhập được, cán bộ nghỉ việc bị khoá đăng nhập và thu hồi phiên trong vài giây sau sự kiện.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-02 iam" đoạn "API, dữ liệu và sự kiện" (consumer EVT-16 gọi Admin API) và mục "Tích hợp hướng sự kiện" (dòng 1232; mẫu consumer dòng 1251–1270, quy ước DLQ dòng 1246 và 1281).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.person` (dòng 1159–1180: `id` = `sub` Keycloak, `employment_status`) và mục "Inbox idempotent" (dòng 4269).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-16, EVT-14 có SVC-02 là bên tiêu thụ) và mục 8.
- Lược đồ Avro EVT-16 trong `contracts/events/avro/` (do NEN-007 tạo).
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/04-ma-dinh-danh-va-truy-vet.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Dựng dịch vụ Java nhỏ (consumer Spring Kafka theo NEN-012) trong `backend/services/iam/` đọc EVT-16, khoá thông điệp `tenantId:aggregateId`, khử trùng bằng `inbox_processed` và bỏ sự kiện cũ theo phiên bản tổng hợp.
- Ánh xạ sự kiện sang Admin API: người mới → tạo người dùng với `id` bằng `org.person.id`, gắn vào Organization của tenant; đổi thông tin hiển thị → cập nhật; `employment_status` chấm dứt → vô hiệu hoá, thu hồi phiên; không đồng bộ trường C2 (số định danh, ngày sinh, liên hệ) sang Keycloak trừ email công vụ cần cho đăng nhập (suy luận, ghi rõ).
- Gọi Admin API qua client credentials riêng trong mạng quản trị, có Resilience4j (timeout 3 s, circuit breaker, thử lại có backoff); lỗi không phục hồi đưa vào DLQ theo NEN-012.
- Ghi chỉ số độ trễ đồng bộ (thời điểm sự kiện tới lúc Keycloak cập nhật) và số thông điệp DLQ qua OpenTelemetry.
- Kiểm thử Testcontainers Kafka + Apicurio + Keycloak: tạo, sửa, vô hiệu hoá; nhận trùng một sự kiện chỉ xử lý một lần; sự kiện cũ đến sau không ghi đè; Keycloak ngừng thì thông điệp được thử lại rồi vào DLQ.
- Điểm lệch cần lưu ý: tên DLQ khác nhau trong TL-01 (`<topic>.dlq` dòng 1246 và `.dlq.<consumer>` dòng 1281); dùng đúng quy ước mà NEN-012 đã chọn, không tự đặt.
- Ghi chú phạm vi: baseline mục 7 ghi SVC-02 cũng tiêu thụ EVT-14 (đơn vị thay đổi), nhưng khung kế hoạch chưa có hàng việc cho phần này; không làm trong task này, nêu trong báo cáo.

## 4. Ngoài phạm vi

- Phát EVT-16 ở org-service (DV-04-03); nhập nhân sự từ nguồn ngoài (DV-04-06, DV-04-07, DV-13-06).
- Gán vai trò nghiệp vụ theo `config.role_binding` (CORE-08-16).

## 5. Tệp được phép sửa

- `backend/services/iam/**`
- `backend/pom.xml` (chỉ thêm mô-đun con nếu cần)
- `deploy/charts/iam/**` (Deployment consumer, KEDA hoặc HPA theo values)
- `wiki/07-xac-thuc-phan-quyen.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/iam -am verify` chạy xanh với bốn nhóm kiểm thử ở mục 3; độ phủ dòng lớp `application` ≥ 80%.
- `helm lint deploy/charts/iam` không lỗi.
- Đo trên kiểm thử: độ trễ từ lúc phát EVT-16 tới khi Keycloak cập nhật p99 ≤ 5 giây (TL-03 mục ECST, p99 5 giây).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
