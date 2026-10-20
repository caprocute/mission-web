# DV-12-01 · SVC-12/EVT-21 Khung notification-service Go, migration schema `notify`, consumer sự kiện cần báo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-12-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | NEN-017; NEN-030, NEN-031 (quy ước migration và vai trò CSDL) đã gộp (bổ sung) |
| Truy vết | QT-00 (SCR-0002) → API `GET /api/v1/notifications`, `POST /api/v1/notifications/read` → SVC-12 → bảng `notify.notification`, `notify.delivery`, `notify.channel_pref`, `notify.template`, `notify.inbox_processed` → nhận EVT-21 `notify.message.requested.v1`, EVT-04, EVT-07, EVT-11, EVT-20 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng notification-service bằng Go trên thư viện của NEN-017, tạo schema `notify` có phân vùng theo ngày giữ 90 ngày, và consumer idempotent nhận EVT-21 cùng các sự kiện nghiệp vụ cần báo để sinh thông báo trong ứng dụng từ mẫu. Khi xong, hộp thông báo của người dùng (SCR-0002) đọc được qua API thật, mỗi sự kiện nguồn sinh đúng một thông báo cho mỗi người nhận dù Kafka giao lại.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-12 notification-service" (L1156–1168).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema notify (SVC-12)" (L3797–3854) với từ điển bốn bảng; "Inbox idempotent" (L4269); "Thời hạn lưu giữ" (L4622, notify 90 ngày).
- `00-co-so-thiet-ke-chung.md` mục 7 (EVT-04, EVT-07, EVT-11, EVT-20, EVT-21).
- `contracts/openapi/modules/platform.yaml` `listNotifications`, `markNotificationsRead`, lược đồ `Notification`, `NotificationUnreadEvent`.
- Bản trích `extract/tl01-thanh-phan.md` mục C11, `extract/tl03-phan2.md` mục A10.

## 3. Phạm vi công việc

- Tạo `services-go/notification/` theo mẫu Go của NEN-017 (`cmd/notification-service`, `internal/{store,consume,render,api}`), probe, metric, Dockerfile không root.
- Viết migration tạo `notification` (PK (`created_day`,`id`); ix (`tenant_id`,`recipient_user_id`,`created_at DESC`); UQ (`created_day`,`tenant_id`,`source_event_id`,`recipient_user_id`); `template_code`, `params`, `deep_link`, `priority`, `read_at`), `delivery` (CHECK `channel` IN (`INAPP`,`PUSH`,`SMS`,`EMAIL`); `address_bidx`; CHECK `status` IN (`CHO`,`DA_GUI`,`THAT_BAI`,`BO_QUA`)), `channel_pref` (PK (`tenant_id`,`user_id`,`category`), `channels[]`, `quiet_from/to`), `template` (UQ NULLS NOT DISTINCT (`tenant_id`,`template_code`,`channel`,`locale`)), `inbox_processed`; RLS theo tenant; pg_partman phân vùng ngày giữ 90 ngày cho `notification` và `delivery`.
- Viết consumer franz-go cho EVT-21 và EVT-04, EVT-07, EVT-11, EVT-20: kiểm inbox `ON CONFLICT DO NOTHING` trong cùng giao dịch ghi thông báo, xác định người nhận từ dữ liệu sự kiện, dựng nội dung từ `notify.template` (mẫu riêng của tenant ưu tiên, `tenant_id` NULL là mẫu chung), ghi `notification` và `delivery` kênh `INAPP`.
- Đảm bảo nội dung không chứa C2 (ví dụ "Có hồ sơ cần thẩm định" kèm đường dẫn); kiểm thử chặn mẫu có tham số đánh dấu C2.
- Hiện thực `listNotifications` (phân trang con trỏ, lọc chưa đọc) và `markNotificationsRead` (đánh dấu theo danh sách hoặc tất cả) đúng hợp đồng; không phát sự kiện nghiệp vụ nào.
- Nạp bộ mẫu chung tiếng Việt cho các sự kiện đã nêu vào `data/seed/notify/` (nội dung theo văn phong MSG của TL-02, không in mã kỹ thuật).

## 4. Ngoài phạm vi

- SSE thời gian thực (DV-12-02); kênh email, SMS, đẩy di động (DV-12-03); thông báo hàng loạt (DV-12-04).
- Các module phát EVT-21 (mỗi task CORE tự phát qua outbox).

## 5. Tệp được phép sửa

- `services-go/notification/**`
- `data/seed/notify/**`

## 6. Tiêu chí hoàn thành

- `cd services-go/notification && go vet ./... && go test ./...` xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL 17 + Kafka: gửi một EVT-21 ba lần tạo đúng một thông báo cho mỗi người nhận; hai tenant không thấy thông báo của nhau.
- Kiểm thử hợp đồng: phản hồi `listNotifications`, `markNotificationsRead` khớp `platform.yaml`.
- `govulncheck ./...` không có lỗi mức cao; phân vùng ngày được tạo trước và phân vùng quá 90 ngày bị xoá trong kiểm thử job.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
