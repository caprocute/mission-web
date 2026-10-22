# SVC-12 notification-service

Dịch vụ thông báo của Mission viết bằng Go trên thư viện `vtplatform` (NEN-017). Bản này hiện thực DV-12-01: schema `notify`, consumer sự kiện cần báo và hộp thông báo trong ứng dụng của SCR-0002. Thông báo thời gian thực qua SSE (DV-12-02), kênh email, SMS, đẩy di động (DV-12-03) và thông báo hàng loạt (DV-12-04) làm ở các task sau.

## Luồng xử lý

Consumer group `notification-service` đọc năm topic: EVT-21 `notify.message.requested.v1` do mọi module phát qua outbox, cùng EVT-04 `task.task.assigned.v1`, EVT-07 `product.evidence.submitted.v1`, EVT-11 `appraisal.profile.stepcompleted.v1` và EVT-20 `analytics.anomaly.detected.v1`. Thân sự kiện là Avro nhị phân theo lược đồ trong `contracts/events/avro`; bản chép ở `internal/consume/schemas/` được kiểm thử so khớp với hợp đồng. Tiêu đề CloudEvents `id` và `tenantid` là bắt buộc. Với mỗi bản ghi, dịch vụ mở một giao dịch đặt `app.tenant_id` bằng `SET LOCAL`, ghi `notify.inbox_processed` (`ON CONFLICT DO NOTHING`), rồi trong cùng giao dịch ghi `notify.notification` và một dòng `notify.delivery` kênh `INAPP`. Mã thông báo được sinh tất định theo (sự kiện nguồn, người nhận), và ràng buộc duy nhất (`created_day`, `tenant_id`, `source_event_id`, `recipient_user_id`) là lớp chống trùng thứ hai.

Người nhận được xác định như sau. EVT-21 mang sẵn danh sách người nhận và mã mẫu. EVT-04 báo người chủ trì (mẫu `NV_DUOC_GIAO`) và người phối hợp (mẫu `NV_PHOI_HOP`). EVT-07 và EVT-11 chỉ mang người nộp hoặc người thực hiện bước, nên dịch vụ báo xác nhận cho chính người đó; người xử lý bước tiếp theo nhận thông báo qua EVT-21 do module nghiệp vụ phát (suy luận). EVT-20 không mang người nhận, nên dịch vụ báo cho những người đã bật nhóm thông báo `CANH_BAO_BAT_THUONG` trong `notify.channel_pref` và không báo cho đối tượng bị cảnh báo (suy luận).

Nội dung không chứa dữ liệu C2. Mẫu đánh dấu biến `{{ten|C2}}` hoặc dùng tên biến C2 đã biết (số định danh, điện thoại, email, điểm, xếp loại, nhận xét) bị từ chối khi nạp mẫu và khi dựng; sự kiện mang tham số C2 bị chuyển sang thư chết `<topic>.dlq.notification-service`. Lỗi tạm thời được thử lại bốn lần trong tiến trình trước khi chuyển thư chết.

## API

`GET /api/v1/notifications` (listNotifications) và `POST /api/v1/notifications/read` (markNotificationsRead) đúng `contracts/openapi/modules/platform.yaml`. Người gọi chỉ thấy thông báo của chính mình, xác định bằng claim `tenant_id` và `sub` của token đã được cổng API kiểm chữ ký. Tiêu đề và nội dung được dựng lúc đọc từ mẫu `INAPP` tiếng Việt, mẫu riêng của tenant thắng mẫu chung. Ngoài ra có `/healthz`, `/readyz` và `/metrics` (bộ đếm thông báo đã tạo, sự kiện trùng, thư chết).

## Dữ liệu và phân vùng

Migration ở `db/migration/notify/` chạy bằng vai trò `mig_notify`; ứng dụng dùng `svc_notify`, đọc báo cáo dùng `ro_notify`. `notification` và `delivery` phân vùng RANGE theo ngày; hàm `notify.fn_ensure_day_partitions` tạo trước phân vùng và `notify.fn_drop_expired_partitions` xoá phân vùng quá 90 ngày. Hai hàm này làm đúng phần tạo trước và giữ hạn của pg_partman mà TL-03 chọn, viết bằng PL/pgSQL để chạy được trên ảnh PostgreSQL 17 không có pg_partman; khi môi trường production cài pg_partman có thể chuyển sang `partman.create_parent` mà không đổi bảng. Job trong tiến trình chạy hai hàm này và `notify.fn_purge_inbox_processed` theo `PARTITION_MAINTAIN_INTERVAL` (mặc định 6 giờ).

Bộ mẫu chung nằm ở `data/seed/notify/mau-thong-bao-chung.json`; nạp bằng `notification-service seed-templates <tệp>` với `MIGRATION_DATABASE_URL` của `mig_notify`.

## Cấu hình

`DATABASE_URL` (bắt buộc, `svc_notify`), `READONLY_DATABASE_URL`, `KAFKA_BROKERS`, `HTTP_ADDR` (mặc định `:8080`), `CELL_CODE`, `PARTITION_MAINTAIN_INTERVAL`, `SHUTDOWN_TIMEOUT`.

## Kiểm thử

```bash
cd services-go/notification
go vet ./... && go test ./...      # kiểm thử tích hợp cần Docker (PostgreSQL 17, Kafka confluent-local 7.5.0)
govulncheck ./...
docker build -f notification/Dockerfile -t notification-service ..   # chạy từ services-go
```

Giới hạn đã biết: dịch vụ giải mã Avro bằng lược đồ đọc chép trong mã, chưa lấy lược đồ ghi từ Apicurio theo tiêu đề `apicurio.value.globalId`; khi lược đồ một sự kiện thêm trường mới, phải chép lại lược đồ trước khi bên phát đổi phiên bản.
