# SVC-12 notification-service

Dịch vụ thông báo của Mission viết bằng Go trên thư viện `vtplatform` (NEN-017). Bản này hiện thực DV-12-01: schema `notify`, consumer sự kiện cần báo và hộp thông báo trong ứng dụng của SCR-0002. Thông báo thời gian thực qua SSE (DV-12-02), kênh email, SMS, đẩy di động (DV-12-03) và thông báo hàng loạt (DV-12-04) làm ở các task sau.

## Luồng xử lý

Consumer group `notification-service` đọc năm topic: EVT-21 `notify.message.requested.v1` do mọi module phát qua outbox, cùng EVT-04 `task.task.assigned.v1`, EVT-07 `product.evidence.submitted.v1`, EVT-11 `appraisal.profile.stepcompleted.v1` và EVT-20 `analytics.anomaly.detected.v1`. Thân sự kiện là Avro nhị phân theo lược đồ trong `contracts/events/avro`; bản chép ở `internal/consume/schemas/` được kiểm thử so khớp với hợp đồng. Tiêu đề CloudEvents `id` và `tenantid` là bắt buộc. Với mỗi bản ghi, dịch vụ mở một giao dịch đặt `app.tenant_id` bằng `SET LOCAL`, ghi `notify.inbox_processed` (`ON CONFLICT DO NOTHING`), rồi trong cùng giao dịch ghi `notify.notification` và một dòng `notify.delivery` kênh `INAPP`. Mã thông báo được sinh tất định theo (sự kiện nguồn, người nhận), và ràng buộc duy nhất (`created_day`, `tenant_id`, `source_event_id`, `recipient_user_id`) là lớp chống trùng thứ hai.

Người nhận được xác định như sau. EVT-21 mang sẵn danh sách người nhận và mã mẫu. EVT-04 báo người chủ trì (mẫu `NV_DUOC_GIAO`) và người phối hợp (mẫu `NV_PHOI_HOP`). EVT-07 và EVT-11 chỉ mang người nộp hoặc người thực hiện bước, nên dịch vụ báo xác nhận cho chính người đó; người xử lý bước tiếp theo nhận thông báo qua EVT-21 do module nghiệp vụ phát (suy luận). EVT-20 không mang người nhận, nên dịch vụ báo cho những người đã bật nhóm thông báo `CANH_BAO_BAT_THUONG` trong `notify.channel_pref` và không báo cho đối tượng bị cảnh báo (suy luận).

Nội dung không chứa dữ liệu C2. Mẫu đánh dấu biến `{{ten|C2}}` hoặc dùng tên biến C2 đã biết (số định danh, điện thoại, email, điểm, xếp loại, nhận xét) bị từ chối khi nạp mẫu và khi dựng; sự kiện mang tham số C2 bị chuyển sang thư chết `<topic>.dlq.notification-service`. Lỗi tạm thời được thử lại bốn lần trong tiến trình trước khi chuyển thư chết.

## API

`GET /api/v1/notifications` (listNotifications) và `POST /api/v1/notifications/read` (markNotificationsRead) đúng `contracts/openapi/modules/platform.yaml`. Người gọi chỉ thấy thông báo của chính mình, xác định bằng claim `tenant_id` và `sub` của token đã được cổng API kiểm chữ ký. Tiêu đề và nội dung được dựng lúc đọc từ mẫu `INAPP` tiếng Việt, mẫu riêng của tenant thắng mẫu chung. DV-12-05 thêm `GET/POST /api/v1/notifications/devices`, `DELETE /api/v1/notifications/devices/{deviceId}` và `GET/PUT /api/v1/notifications/preferences` (ETag, If-Match). Mã thiết bị chỉ lưu dạng mã hoá phong bì kèm chỉ mục mù, không bao giờ trả ra API hay in vào nhật ký; một mã thiết bị chỉ thuộc một người dùng, người khác đăng ký cùng mã thì bản cũ bị thu hồi. Gói `internal/prefs` có bộ định tuyến `Plan` cho worker kênh ngoài của DV-12-03: kênh được bật theo nhóm thông báo, giờ yên lặng hoãn kênh ngoài trừ ưu tiên `CAO`, thiết bị chưa thu hồi đã giải mã trong bộ nhớ. Khi chưa đặt `CRYPTO_LOCAL_KEY` (khoá gốc base64 ≥ 32 byte, chỉ cho dev) thì năm thao tác này trả 503. Ngoài ra có `/healthz`, `/readyz` và `/metrics` (bộ đếm thông báo đã tạo, sự kiện trùng, thư chết).

## Luồng thời gian thực (DV-12-02)

`GET /api/v1/notifications/stream` (streamNotifications) mở luồng SSE `text/event-stream` với ba loại sự kiện `notification`, `job` và `unread`; đầu luồng gửi `retry: 5000`, cứ 25 giây gửi một dòng chú thích giữ kết nối. Mỗi người dùng có một stream Valkey `t:<tenant>:notify:stream:<user>`; mã sự kiện SSE là mã phần tử stream. Stream chỉ bị cắt phần vừa ngoài 200 phần tử mới nhất vừa cũ hơn 10 phút, nên client nối lại với `Last-Event-ID` trong 10 phút luôn nhận đủ phần bị lỡ; mã quá cũ hoặc không hợp lệ thì nhận `unread` kèm `"reload": true` để tải lại hộp thông báo. Bên ghi thêm tín hiệu đánh thức vào một trong 16 stream bus `sys:notify:bus:<n>`; mỗi pod đọc chặn 16 bus theo lô và chỉ đọc stream của người dùng đang kết nối tại pod đó, nên thông báo sinh ở pod nào cũng tới đúng kết nối. Một người dùng mở tối đa 5 kết nối trên một pod, kết nối thứ sáu nhận 429; client không đọc kịp bị ngắt để nối lại. Khi tắt, pod ngừng nhận kết nối mới (503) và đóng dần kết nối trong 30 giây. Metric `notify_sse_connections` phục vụ HPA. Dịch vụ khác phát cập nhật tác vụ dài theo cùng định dạng với `rt.Publisher.Publish`.

## Dữ liệu và phân vùng

Migration ở `db/migration/notify/` chạy bằng vai trò `mig_notify`; ứng dụng dùng `svc_notify`, đọc báo cáo dùng `ro_notify`. `notification` và `delivery` phân vùng RANGE theo ngày; hàm `notify.fn_ensure_day_partitions` tạo trước phân vùng và `notify.fn_drop_expired_partitions` xoá phân vùng quá 90 ngày. Hai hàm này làm đúng phần tạo trước và giữ hạn của pg_partman mà TL-03 chọn, viết bằng PL/pgSQL để chạy được trên ảnh PostgreSQL 17 không có pg_partman; khi môi trường production cài pg_partman có thể chuyển sang `partman.create_parent` mà không đổi bảng. Job trong tiến trình chạy hai hàm này và `notify.fn_purge_inbox_processed` theo `PARTITION_MAINTAIN_INTERVAL` (mặc định 6 giờ).

Bộ mẫu chung nằm ở `data/seed/notify/mau-thong-bao-chung.json`; nạp bằng `notification-service seed-templates <tệp>` với `MIGRATION_DATABASE_URL` của `mig_notify`.

## Cấu hình

`DATABASE_URL` (bắt buộc, `svc_notify`), `READONLY_DATABASE_URL`, `CRYPTO_LOCAL_KEY`, `KAFKA_BROKERS`, `VALKEY_ADDRS` (danh sách cách bằng dấu phẩy, kèm `VALKEY_PASSWORD`; trống thì tắt luồng SSE, trả 503), `HTTP_ADDR` (mặc định `:8080`), `CELL_CODE`, `PARTITION_MAINTAIN_INTERVAL`, `SHUTDOWN_TIMEOUT`.

## Kiểm thử

```bash
cd services-go/notification
go vet ./... && go test ./...      # kiểm thử tích hợp cần Docker (PostgreSQL 17, Kafka confluent-local 7.5.0, Valkey 8)
ulimit -n 20000; NOTIFY_LOAD_CONNS=5000 go test ./internal/it -run TestStreamLoad -v   # kiểm thử tải SSE
govulncheck ./...
docker build -f notification/Dockerfile -t notification-service ..   # chạy từ services-go
```

Giới hạn đã biết: dịch vụ giải mã Avro bằng lược đồ đọc chép trong mã, chưa lấy lược đồ ghi từ Apicurio theo tiêu đề `apicurio.value.globalId`; khi lược đồ một sự kiện thêm trường mới, phải chép lại lược đồ trước khi bên phát đổi phiên bản.
