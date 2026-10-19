# MOB-09 · SVC-12 Thông báo đẩy FCM, APNs

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | S (≤ nửa ngày agent) |
| Phụ thuộc | MOB-01, DV-12-03 (bổ sung: một task sửa hợp đồng thêm API đăng ký thiết bị nhận thông báo đẩy, hiện chưa có trong khung) |
| Truy vết | SVC-12; QT-03 (thông báo "Check-in tuần"), QT-05 (MSG-05-14 khi EVT-04) → API `GET /api/v1/notifications` (`listNotifications`), `POST /api/v1/notifications/read` (`markNotificationsRead`); API đăng ký mã thiết bị: chưa có trong `contracts/` → SVC-12 → bảng `notify.notification`, `notify.delivery` (`channel` = `PUSH`), `notify.channel_pref` → EVT-21, EVT-04 |
| Trạng thái | Chưa bắt đầu (phần đăng ký thiết bị bị chặn tới khi hợp đồng có API tương ứng) |

## 1. Mục tiêu

Ứng dụng nhận thông báo đẩy qua FCM (Android) và APNs (iOS) cho các sự kiện như được giao việc mới (MSG-05-14) và nhắc check-in tuần, chạm vào thông báo mở thẳng đúng màn hình (để giữ ba chạm của SCR-0302, SCR-0504), và có danh sách thông báo trong ứng dụng đánh dấu đã đọc. Nội dung đẩy không chứa dữ liệu C2.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-12 notification-service" (dòng 1156: bốn kênh, FCM, APNs, kênh nội bộ bản cách ly).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, mục "Schema notify (SVC-12)" (`notify.notification.params` không chứa C2, `deep_link`, `notify.delivery.channel`, `notify.channel_pref` giờ yên lặng).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: SCR-0302 (đường vào từ thông báo), SCR-0504 (MSG-05-14).
- Hợp đồng `contracts/openapi/modules/platform.yaml` (nhóm `/notifications*`).

## 3. Phạm vi công việc

- Tích hợp FCM cho Android và APNs cho iOS (qua thư viện Flutter chọn và nêu lý do), xin quyền thông báo đúng thời điểm có giải thích bằng tiếng Việt.
- Đăng ký mã thiết bị với máy chủ: hợp đồng hiện chưa có API này và TL-03 chưa có bảng lưu mã thiết bị, nên agent không tự đặt đường dẫn; mở yêu cầu task sửa hợp đồng (kèm đề xuất lược đồ, chạy `openapi-diff`) và chỉ hiện thực phía ứng dụng sau lớp trừu tượng, tắt bằng cờ cho tới khi hợp đồng được gộp.
- Xử lý thông báo ở ba trạng thái ứng dụng (đang mở, chạy nền, đã đóng): đọc `deep_link` và điều hướng tới SCR-0302 (MOB-04) hoặc SCR-0504 (MOB-03) hoặc danh sách thông báo; đường dẫn lạ thì mở danh sách.
- Dựng danh sách thông báo trong ứng dụng từ `listNotifications` (phân trang con trỏ), đánh dấu đã đọc bằng `markNotificationsRead`.
- Bảo đảm nội dung hiển thị trên màn hình khoá chỉ là văn bản mẫu không chứa dữ liệu C2; với thông báo có nội dung nhạy cảm, chỉ hiện "Đồng chí có thông báo mới" (suy luận theo quy tắc `params` không chứa C2).
- Huỷ đăng ký mã thiết bị khi đăng xuất hoặc đổi tenant.

## 4. Ngoài phạm vi

- Gửi thông báo, mẫu, tuỳ chọn kênh phía máy chủ (DV-12-03); kênh nội bộ cho bản cách ly; SSE trong ứng dụng.

## 5. Tệp được phép sửa

- `mobile/lib/core/notifications/**`, `mobile/lib/features/notifications/**`, cấu hình nền tảng trong `mobile/android/**`, `mobile/ios/**` (không chứa khoá hay tệp cấu hình dịch vụ thật), `mobile/test/**`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh (bộ định tuyến liên kết sâu, xử lý ba trạng thái ứng dụng bằng thông điệp giả).
- Kiểm thử tích hợp với máy chủ giả lập: danh sách thông báo phân trang, đánh dấu đã đọc.
- Gửi thử thông báo tới trình giả lập (FCM trên Android; APNs trên iOS nếu có tài khoản nhà phát triển, nếu không ghi rõ) và chạm mở đúng màn hình.
- Báo cáo nêu trạng thái yêu cầu sửa hợp đồng đăng ký thiết bị; báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
