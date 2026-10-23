# Hàng thư chết có bản ghi mới

> **Truy vết:** TL-04 mục 10.5, 11.9 (OPS-24) · INF-26 `vtctl dlq` · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `DLQNotEmpty` (warning: offset của một topic `*.dlq.*` tăng trong 10 phút).
> **Chạy thử:** `vtctl dlq` có kiểm thử với Kafka thật qua Testcontainers (`services-go/vtctl`); chưa chạy theo sổ tay trên kind (giai đoạn này chỉ phát triển, YC-PT-19).

## Điều kiện kích hoạt

Một consumer đã thử lại 3 lần không xử lý được bản ghi và chuyển nó sang `<topic>.dlq.<consumer>`.

## Tác động

Sự kiện nghiệp vụ tương ứng chưa được áp cho consumer đó. SEV-3; xử lý hết trong một ngày làm việc, bốn giờ trong cửa sổ đóng băng.

## Kiểm tra nhanh

```bash
vtctl -e <env> -c <cell> dlq list
vtctl -e <env> -c <cell> dlq inspect --topic <topic> --consumer <consumer> --partition <p> --from <offset> --to <offset>
```

## Các bước

Làm theo [OPS-24](../ops/ops-24-xu-ly-thu-chet.md): phân loại lỗi dữ liệu, lỗi mã, không hợp lệ vĩnh viễn, rồi phát lại hoặc bỏ qua có duyệt.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Đếm, xem | `vtctl dlq list`, `vtctl dlq inspect` | `kafka-console-consumer --topic <topic>.dlq.<consumer>` |
| Phát lại | `vtctl dlq replay ... --confirm` | không làm tay |
| Bỏ qua có duyệt | `vtctl dlq skip ... --approver <người> --confirm` | không làm tay |

## Kiểm sau khi làm

`vtctl dlq list` báo 0 chưa xử lý cho consumer; cảnh báo không bật lại.

## Quay lui

Phát lại idempotent; bỏ qua có thể phát lại sau theo dải offset (OPS-24).

## Leo thang

DLQ tăng liên tục hoặc quá hạn xử lý: gọi chủ service.

## Liên quan

[OPS-24](../ops/ops-24-xu-ly-thu-chet.md), [kafka-lag](kafka-lag.md), `../phat-lai-outbox.md`.
