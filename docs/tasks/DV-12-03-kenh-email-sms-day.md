# DV-12-03 · SVC-12 Kênh email, SMS, FCM, APNs có bulkhead, thử lại, tuỳ chọn kênh, mẫu thông báo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-12-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (kênh đẩy di động dùng thật từ Đợt 3 cùng ứng dụng Flutter) |
| Luồng đề xuất | B7 |
| Cỡ việc | L |
| Phụ thuộc | DV-12-01 |
| Truy vết | QT-05, QT-06, QT-09, QT-11, QT-14 (MSG-14-01 thông báo đẩy) → SVC-12 → bảng `notify.delivery`, `notify.channel_pref`, `notify.template` → nhận EVT-21 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Gửi thông báo qua bốn kênh ngoài ứng dụng là email, SMS (cổng SMS Viettel), FCM và APNs (hoặc kênh nội bộ ở bản cách ly), theo tuỳ chọn kênh và giờ yên lặng của người dùng, với worker riêng mỗi kênh có bulkhead, circuit breaker và hàng đợi thử lại có giới hạn. Khi xong, cổng SMS lỗi không làm chậm thông báo trong ứng dụng, và mọi lần gửi có trạng thái trong `delivery`.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-12 notification-service" (L1156–1168, kênh, co giãn, chịu lỗi), "Cấu hình Resilience4j chuẩn" (L2809, áp ý tương đương cho Go).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `notify.delivery` (L3824, `address_bidx` chỉ mục mù), `notify.channel_pref` (L3838), `notify.template` (L3847); "Chỉ mục mù" (L4160).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` bảng ba mô hình phân phối (L106–108) và SEC-36 (lưu lượng ra qua egress gateway).
- `00-co-so-thiet-ke-chung.md` mục 5 (SVC-12), mục 7 (EVT-21).
- Bản trích `extract/tl01-thanh-phan.md` mục C11.

## 3. Phạm vi công việc

- Viết giao diện `ChannelSender` và bốn hiện thực: email (SMTP có TLS), SMS (API cổng SMS Viettel), FCM (HTTP v1), APNs (HTTP/2, khoá token); thêm hiện thực kênh nội bộ cho bản cách ly bật bằng cấu hình (suy luận về hình thức kênh nội bộ).
- Định tuyến theo `channel_pref` (kênh theo nhóm thông báo, `quiet_from/to`): ngoài giờ yên lặng gửi ngay, trong giờ yên lặng hoãn trừ mức ưu tiên cao; mặc định khi người dùng chưa đặt lấy từ cấu hình tenant.
- Worker theo kênh đọc hàng đợi riêng (Kafka topic hoặc bảng `delivery` trạng thái `CHO`, chọn một cách và ghi lý do), bulkhead và circuit breaker riêng mỗi kênh, thử lại có giới hạn với backoff, quá lần thử thì `THAT_BAI`; xuất metric độ dài hàng đợi mỗi kênh cho KEDA.
- Địa chỉ nhận (số điện thoại, email) không lưu rõ trong `delivery`: chỉ `address_bidx` HMAC; địa chỉ thật lấy lúc gửi từ nguồn nhân sự đã giải mã trong bộ nhớ (suy luận nguồn), không ghi nhật ký.
- Mẫu theo kênh và `locale` trong `notify.template` (SMS ngắn, không dấu nếu cổng yêu cầu; email có tiêu đề); nội dung không chứa C2.
- Viết giả lập cho bốn kênh (máy chủ SMTP thử, HTTP giả lập SMS/FCM/APNs) để kiểm thử; bí mật kết nối chỉ ở OpenBao.
- Gợi ý tách nếu quá lớn: (a) khung worker, tuỳ chọn kênh, email; (b) SMS; (c) FCM, APNs.

## 4. Ngoài phạm vi

- Thông báo hàng loạt cuối kỳ và giới hạn tốc độ (DV-12-04); đăng ký thiết bị đẩy phía ứng dụng di động (task MOB).
- API cho người dùng tự đặt tuỳ chọn kênh: hợp đồng `platform.yaml` chưa có đường dẫn này; nếu cần, mở task sửa hợp đồng riêng.
- Nối và kiểm thử với cổng SMS Viettel, FCM và APNs thật khi có tài khoản thử; khi đó chỉ đổi cấu hình kết nối, không đổi mã.

## 5. Tệp được phép sửa

- `services-go/notification/**`
- `.env.example`, `infra/dev/.env.example` (tên biến, không có giá trị thật)

## 6. Tiêu chí hoàn thành

- `cd services-go/notification && go test ./...` xanh, gồm kiểm thử tích hợp với giả lập: cổng SMS trả lỗi liên tục thì mạch SMS mở, thông báo `INAPP` và email vẫn gửi đúng hạn; SMS xếp hàng và gửi lại khi cổng phục hồi.
- Kiểm thử chứng minh giờ yên lặng hoãn thông báo thường và không hoãn thông báo ưu tiên cao; không có số điện thoại hay email dạng rõ trong CSDL và nhật ký.
- Email gửi tới hộp thư thử (ví dụ Mailpit) và SMS, FCM, APNs gửi tới giả lập; địa chỉ cổng, tài khoản và khoá của từng kênh nằm trong cấu hình và giá trị triển khai (bí mật qua OpenBao), không nằm trong mã, nên đổi sang cổng thật chỉ đổi cấu hình kết nối, không đổi mã.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
