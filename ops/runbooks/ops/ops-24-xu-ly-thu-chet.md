# OPS-24 · Xử lý thư chết (DLQ)

> **Truy vết:** TL-04 mục 11.9 (topic `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>`, ba loại lỗi, phát lại qua `<topic>.replay.<consumer>`, hết DLQ trong một ngày làm việc, bốn giờ trong cửa sổ đóng băng) · INF-26 `vtctl dlq` · cảnh báo `DLQNotEmpty`.
> **Chu kỳ:** liên tục · **Chịu trách nhiệm:** chủ service · **Tự động hoá:** công cụ phát lại `vtctl`.
> **Chạy thử:** lệnh `vtctl dlq` có kiểm thử với Kafka thật qua Testcontainers (`VTCTL_KAFKA_IT=1 go test ./internal/dlq/` trong `services-go/vtctl`); chưa chạy theo sổ tay trên kind với một topic thử (giai đoạn này chỉ phát triển, YC-PT-19).

## Điều kiện kích hoạt

Cảnh báo `DLQNotEmpty` (có bản ghi mới trong `*.dlq.*` trong 10 phút), hoặc rà soát đầu ca thấy DLQ còn bản ghi chưa xử lý.

## Tác động

Mỗi consumer có DLQ riêng nên một consumer lỗi không chặn consumer khác của cùng topic. Bản ghi trong DLQ là sự kiện nghiệp vụ chưa được áp (ví dụ điểm chưa tính lại, thông báo chưa gửi).

## Kiểm tra nhanh

```bash
vtctl -e <env> -c <cell> dlq list                                              # DLQ còn bản ghi chưa xử lý
vtctl -e <env> -c <cell> dlq inspect --topic <topic> --consumer <consumer> ... # header lý do x-dlq-*
```

## Các bước

Phân loại theo header lý do và nội dung:

1. **Lỗi dữ liệu** (dữ liệu nguồn sai, thiếu tham chiếu): sửa dữ liệu nguồn qua nghiệp vụ hoặc phát lại sự kiện nguồn (`../phat-lai-outbox.md`), rồi phát lại DLQ.
2. **Lỗi mã** (ngoại lệ trong consumer): đội phát triển sửa, phát hành qua OPS-02, rồi phát lại DLQ.
3. **Không hợp lệ vĩnh viễn** (lược đồ sai, sự kiện của tenant đã chấm dứt): bỏ qua có duyệt; người duyệt khác người chạy, người chạy vừa xác thực bậc 2.

Phát lại (chạy thử trước, rồi `--confirm`):

```bash
vtctl -e <env> -c <cell> dlq replay --topic <topic> --consumer <consumer> \
  --partition <p> --from <offset> --to <offset> --reason "<phiếu>"
vtctl -e <env> -c <cell> dlq replay ... --confirm
```

Bỏ qua có duyệt:

```bash
vtctl -e <env> -c <cell> dlq skip --topic <topic> --consumer <consumer> --partition <p> --to <offset> --approver <người duyệt> --confirm
```

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Đếm DLQ | `vtctl dlq list` | `kafka-consumer-groups --describe --group dlq-replayer` |
| Xem bản ghi | `vtctl dlq inspect` | `kafka-console-consumer --topic <topic>.dlq.<consumer> --property print.headers=true` |
| Phát lại | `vtctl dlq replay` | không làm tay (cần giữ khoá, thân Avro, header CloudEvents) |
| Bỏ qua có duyệt | `vtctl dlq skip` | không làm tay |

## Kiểm sau khi làm

`vtctl dlq list` báo 0 bản ghi chưa xử lý cho consumer; consumer gốc áp sự kiện phát lại (header `ce_replayed=true` trong log); cảnh báo `DLQNotEmpty` không bật lại trong 10 phút.

## Quay lui

Phát lại idempotent ở consumer (inbox), nên phát lại trùng không gây hại. Bỏ qua chỉ đẩy vị trí của nhóm `dlq-replayer`, bản ghi vẫn còn trong topic tới hết thời gian lưu giữ, có thể phát lại sau bằng dải offset.

## Leo thang

DLQ của topic nghiệp vụ còn bản ghi sau một ngày làm việc (bốn giờ trong cửa sổ đóng băng), hoặc DLQ tăng liên tục: gọi chủ service và cấp 2.

## Liên quan

[dlq](../canh-bao/dlq.md), [kafka-lag](../canh-bao/kafka-lag.md), `../phat-lai-outbox.md`, `services-go/vtctl/README.md`.
