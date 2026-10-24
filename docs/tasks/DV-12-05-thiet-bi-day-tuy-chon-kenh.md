# DV-12-05 · SVC-12 Đăng ký thiết bị nhận thông báo đẩy và tuỳ chọn kênh: bảng `notify`, API

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-12-05` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | S |
| Phụ thuộc | DV-12-01 (dịch vụ, schema `notify`), NEN-043 (thao tác `/notifications/devices*`, `/notifications/preferences`); DV-12-03 (kênh FCM, APNs dùng mã thiết bị) (bổ sung) |
| Truy vết | QT-00 (SCR-0002), QT-03, QT-05 (thông báo đẩy trên di động) → API đề xuất trong NEN-043: `POST/GET /api/v1/notifications/devices` (`registerPushDevice`, `listMyPushDevices`), `DELETE /api/v1/notifications/devices/{deviceId}` (`unregisterPushDevice`), `GET/PUT /api/v1/notifications/preferences` (`getNotificationPreferences`, `updateNotificationPreferences`) → SVC-12 → bảng `notify.channel_pref`, bảng bổ sung lưu thiết bị (suy luận) → `notify.delivery` (`channel` = `PUSH`) |
| Trạng thái | Chưa bắt đầu (chờ NEN-043 gộp) |

## 1. Mục tiêu

Ứng dụng di động (MOB-09) cần đăng ký mã thiết bị để nhận thông báo đẩy, và người dùng cần tự chọn kênh nhận theo nhóm thông báo cùng giờ yên lặng; TL-03 có `notify.channel_pref` nhưng chưa có bảng mã thiết bị, hợp đồng chưa có API (bổ sung ở NEN-043). Task này làm phía notification-service: lưu thiết bị có mã hoá mã thiết bị, API đăng ký và huỷ, API đọc ghi tuỳ chọn kênh, và nối vào bộ định tuyến kênh của DV-12-03. Khi xong, một cán bộ đăng nhập trên điện thoại nhận được thông báo đẩy, tắt được nhóm thông báo không cần, và đăng xuất thì thiết bị không còn nhận.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema notify (SVC-12)" (L3797–3854: `channel_pref` PK (`tenant_id`, `user_id`, `category`), `channels[]`, `quiet_from/to`; `delivery.channel` có `PUSH`, `address_bidx`), "Thời hạn lưu giữ" (L4622).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "SVC-12 notification-service" (L1156–1168), "SVC-18 mobile-app" (L1220–1226).
- `contracts/openapi/modules/platform.yaml` sau NEN-043 (năm thao tác ở trên).
- Thẻ `docs/tasks/DV-12-01-khung-notification-consumer.md`, `DV-12-03-kenh-email-sms-day.md` (định tuyến theo `channel_pref`, giờ yên lặng), MOB-09, `NEN-043-sua-hop-dong-nen-tang.md`.

## 3. Phạm vi công việc

- Migration bổ sung (chỉ thêm, đánh dấu "bổ sung (suy luận)", ghi `wiki/14`) bảng thiết bị trong schema `notify`: `id`, `tenant_id`, `user_id`, `platform` CHECK IN (`FCM`, `APNS`), mã thiết bị mã hoá phong bì (C2) kèm chỉ mục mù để chống trùng, `app_version`, `locale`, `last_seen_at`, `revoked_at`; UQ (`tenant_id`, chỉ mục mù mã thiết bị); RLS theo tenant.
- Hiện thực `registerPushDevice` (Idempotency-Key): một mã thiết bị chỉ thuộc một người dùng tại một thời điểm (đăng ký lại bởi người khác thì chuyển chủ và thu hồi bản cũ); `listMyPushDevices` không bao giờ trả mã thiết bị; `unregisterPushDevice` chỉ chính chủ; ứng dụng gọi huỷ khi đăng xuất.
- Hiện thực `getNotificationPreferences`, `updateNotificationPreferences` (If-Match): nhóm thông báo lấy từ danh mục mẫu của DV-12-01; không cho tắt kênh `INAPP` của nhóm bắt buộc (thông báo pháp lý như hạn khiếu nại, kết quả xếp loại, suy luận, danh sách để người phụ trách duyệt); giờ yên lặng hợp lệ trong ngày, mặc định lấy từ cấu hình tenant.
- Nối với DV-12-03: kênh `PUSH` gửi tới mọi thiết bị chưa thu hồi của người nhận; FCM, APNs báo mã không hợp lệ thì tự đặt `revoked_at`; thiết bị không thấy quá 90 ngày thì thu hồi (suy luận).
- Nội dung đẩy không chứa C2 (chỉ "Có việc mới cần xem" kèm đường dẫn), đúng nguyên tắc của DV-12-01.
- Kiểm thử Testcontainers PostgreSQL 17: đăng ký, đăng ký lại cùng mã bởi người khác, huỷ; tuỳ chọn tắt nhóm làm bộ định tuyến bỏ kênh đó; hai tenant không thấy thiết bị của nhau; không có mã thiết bị dạng rõ trong CSDL và nhật ký.

## 4. Ngoài phạm vi

- Gửi thật qua FCM, APNs và bulkhead (DV-12-03); phía ứng dụng di động (MOB-09); màn hình tuỳ chọn trên web (WEB, nhóm CNH nếu gộp vào tuỳ chọn cá nhân).

## 5. Tệp được phép sửa

- `services-go/notification/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/notification && go vet ./... && go test ./...` xanh gồm kiểm thử tích hợp mục 3; `govulncheck ./...` không lỗi mức cao.
- Kiểm thử hợp đồng cho năm `operationId` khớp `platform.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu tên bảng bổ sung và trạng thái duyệt của người phụ trách CSDL.

## 7. Báo cáo

Theo AGENTS.md mục 6.
