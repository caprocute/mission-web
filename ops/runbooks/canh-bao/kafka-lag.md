# Lag consumer Kafka tăng liên tục

> **Truy vết:** TL-04 mục 10.5 · TL-01 quy ước topic `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>` · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `KafkaConsumerLagGrowing` (warning: lag tăng liên tục trong 15 phút và trên 100.000, kéo dài 5 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Một nhóm consumer đọc chậm hơn tốc độ ghi của topic.

## Tác động

Chức năng phụ thuộc sự kiện chậm (điểm, thông báo, bản sao tổ chức, tìm kiếm). SEV-3; SEV-2 nếu là consumer trên đường găng của khoá kỳ.

## Kiểm tra nhanh

```bash
vtctl -e <env> -c <cell> consumer lag --group <consumergroup>
kubectl get pods -n <namespace> -l app.kubernetes.io/name=<service>     # pod có khởi động lại liên tục không
kubectl logs -n <namespace> deploy/<service> --tail=200 | grep -i error
```

## Các bước

1. **Nếu consumer lỗi lặp lại một bản ghi:** bản ghi đó phải vào retry rồi DLQ sau 3 lần; nếu không, lỗi mã ở consumer, gọi chủ service.
2. **Nếu consumer treo:** khởi động lại bằng `vtctl consumer restart`.
3. **Nếu tải tăng thật** (tốc độ ghi tăng): kiểm KEDA tăng replica (giới hạn bởi số phân vùng).
4. **Nếu broker có vấn đề** (ISR thiếu, đĩa đầy): gọi cấp 2 Kafka; xem NG-08 ở [nang-bac](nang-bac.md).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Lag | `vtctl -c <cell> consumer lag --group <group>` | `kafka-consumer-groups --describe --group <group>` |
| Khởi động lại | `vtctl -c <cell> consumer restart --deployment <tên> -n <namespace> --confirm` | `kubectl rollout restart deployment/<tên> -n <namespace>` |

## Kiểm sau khi làm

Lag giảm đều và về mức nền; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Lag vẫn tăng sau khi khởi động lại và tăng replica: gọi chủ service và cấp 2 Kafka.

## Liên quan

[dlq](dlq.md), [scoring-lag](scoring-lag.md), [OPS-24](../ops/ops-24-xu-ly-thu-chet.md).
