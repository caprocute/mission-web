# DV-02-05 · QT-00 Event listener Keycloak đẩy `iam.auth.event.v1`, báo truy cập bất thường, thu hồi phiên

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-02-01, NEN-007 |
| Truy vết | QT-00 → SCR-0001 → `POST /api/v1/config/security/report-suspicious` (`reportSuspiciousAccess`, owner MOD-08) → SVC-02 iam → topic kỹ thuật `iam.auth.event.v1`, EVT-21 `notify.message.requested.v1`; BR-00-05, BR-00-06, BR-00-08; MSG-00-02, MSG-00-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mọi sự kiện đăng nhập, đăng nhập thất bại, khoá tài khoản, đổi MFA, đăng xuất của Keycloak được đẩy lên Kafka để phân tích, SOC và sổ cái dùng; người dùng thấy đăng nhập lạ có thể bấm "Báo cáo truy cập bất thường" để thu hồi mọi phiên và buộc đổi mật khẩu; phiên bị thu hồi bị cổng từ chối ngay. Khi xong, khoá tài khoản sinh thông báo MSG-00-02, thiết bị mới sinh MSG-00-08, và mỗi sự kiện đăng nhập mang ngữ cảnh phiên để sổ cái ghi (BR-00-08).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00: báo truy cập bất thường (dòng 375, 412), sự kiện (dòng 474), BR-00-05, BR-00-06, BR-00-08.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-02 iam" đoạn "API, dữ liệu và sự kiện" và mục "Tích hợp hướng sự kiện" (dòng 1232; quy ước topic `sys.` dòng 1281).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` dòng 2862 (danh sách phiên bị thu hồi trong Valkey, APISIX kiểm).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-21) và mục 8.
- Hợp đồng: `contracts/openapi/modules/config.yaml` thao tác `reportSuspiciousAccess`.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0001), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết `EventListenerProvider` SPI cho Keycloak: chuyển sự kiện người dùng và quản trị (LOGIN, LOGIN_ERROR, LOGOUT, UPDATE_TOTP, REMOVE_TOTP, khoá do brute force…) thành bản ghi Avro có `eventId` UUIDv7, `tenantId`, `occurredAt`, `actorId`, `traceparent`, `classification`, không chứa mật khẩu, OTP hay token.
- Gửi bản ghi lên topic kỹ thuật bằng hàng đợi bền cục bộ và gửi bất đồng bộ để Keycloak không chậm khi Kafka chậm (outbox trong CSDL `iam` không áp dụng được vì bảng Keycloak không được sửa; ghi rõ cách chọn).
- Sinh yêu cầu thông báo EVT-21 cho khoá tài khoản (MSG-00-02) và thiết bị mới (MSG-00-08) từ sự kiện tương ứng.
- Hiện thực `reportSuspiciousAccess` trong MOD-08 theo hợp đồng: gọi Admin API thu hồi mọi phiên và refresh token của người dùng, đặt hành động bắt buộc đổi mật khẩu, phát EVT-21 cho quản trị tenant qua outbox, ghi sổ cái.
- Viết consumer nhỏ ghi danh sách phiên bị thu hồi vào Valkey (khoá có tiền tố tenant, hết hạn bằng thời hạn refresh token) và bổ sung kiểm tra danh sách này ở plugin `vt-claims` của APISIX (TL-04 dòng 2862).
- Kiểm thử: Testcontainers Keycloak + Kafka + Apicurio cho đăng nhập sai tạo sự kiện đúng lược đồ; tích hợp cho báo bất thường làm token cũ bị từ chối ở bước làm mới.
- Điểm lệch cần lưu ý: tên topic `iam.auth.event.v1` (TL-01 SVC-02) không theo ví dụ tiền tố `sys.` của topic kỹ thuật (TL-01 dòng 1281); giữ `iam.auth.event.v1` như TL-01 và TL-04 cùng ghi, nêu trong wiki trang 14.

## 4. Ngoài phạm vi

- Quy tắc SIEM phát hiện đăng nhập bất thường (task INF bảo mật); phát hiện bất thường nghiệp vụ (DV-15).
- Mã khôi phục một lần khi mất SmartOTP: hợp đồng chưa có API.

## 5. Tệp được phép sửa

- `backend/services/iam/**` (event listener SPI, consumer danh sách thu hồi)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**` (chỉ lớp hiện thực `reportSuspiciousAccess`) và kiểm thử tương ứng
- `contracts/events/avro/` (chỉ thêm lược đồ sự kiện kỹ thuật `iam.auth.event.v1` nếu NEN-007 chưa có)
- `deploy/charts/iam/**`, `deploy/charts/api-gateway/**` (chỉ phần kiểm danh sách thu hồi trong `vt-claims`)
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/iam,mission-core -am verify` chạy xanh, gồm Testcontainers Keycloak, Kafka, Apicurio; lược đồ Avro đăng ký với chế độ BACKWARD.
- Đo trên kiểm thử: thời gian đăng nhập không tăng quá 20 ms khi Kafka ngừng (suy luận ngưỡng, ghi lại kết quả đo thật).
- Sau báo bất thường, refresh token cũ bị từ chối và request mang access token cũ bị APISIX chặn trong thời gian sống còn lại.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
