# Độ trễ tính điểm (SLO-SCORE)

> **Truy vết:** TL-04 mục 10.5 · SLO-SCORE 60 giây từ minh chứng đã xác nhận tới điểm đã tính · SVC-07 scoring-service · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `ScoringLagHigh` (warning: p99 độ trễ tính điểm trên 60 giây trong 10 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Điểm tạm tính hiện trên màn hình chậm hơn mục tiêu sau khi minh chứng được nghiệm thu.

## Tác động

Người dùng thấy điểm cũ; trong tuần khoá kỳ, độ trễ kéo dài làm chậm bước tính điểm hàng loạt. SEV-3, SEV-2 trong tuần khoá kỳ.

## Kiểm tra nhanh

```bash
vtctl -e <env> -c <cell> consumer lag --group scoring-service       # lag của consumer tính điểm
vtctl -e <env> -c <cell> dlq list                                   # sự kiện tính điểm vào DLQ
kubectl get scaledobject -n <namespace> | grep scoring              # KEDA có tăng worker không
```

## Các bước

1. **Nếu lag consumer tăng:** [kafka-lag](kafka-lag.md); kiểm KEDA đã tăng worker theo số lô chờ.
2. **Nếu có bản ghi DLQ của scoring-service:** [OPS-24](../ops/ops-24-xu-ly-thu-chet.md).
3. **Nếu đang khoá kỳ:** bước tính điểm hàng loạt chiếm worker; theo dõi `../saga-chot-ky.md`, không khởi động lại worker giữa chừng.
4. **Nếu worker treo** (lag tăng nhưng pod không xử lý): `vtctl consumer restart --deployment scoring-service -n <namespace> --confirm`.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Lag consumer | `vtctl -c <cell> consumer lag --group scoring-service` | `kafka-consumer-groups --describe --group <group>` |
| Khởi động lại consumer | `vtctl -c <cell> consumer restart --deployment <tên> -n <namespace> --confirm` | `kubectl rollout restart deployment/<tên> -n <namespace>` |

## Kiểm sau khi làm

p99 độ trễ dưới 60 giây; lag consumer về mức nền; cảnh báo tự đóng.

## Quay lui

Khởi động lại consumer không cần quay lui (xử lý idempotent qua inbox).

## Leo thang

Kéo dài quá 1 giờ trong tuần khoá kỳ: SEV-2, gọi chủ service scoring.

## Liên quan

[OPS-21](../ops/ops-21-tinh-lai-doi-chieu-diem.md), [kafka-lag](kafka-lag.md), `../saga-chot-ky.md`.
