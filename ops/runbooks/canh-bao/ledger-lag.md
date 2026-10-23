# Độ trễ ghi sổ cái (SLO-LEDGER)

> **Truy vết:** TL-04 mục 10.5 · SLO-LEDGER 30 giây · SVC-09 `services-go/ledger` · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `LedgerLagHigh` (warning: p99,9 độ trễ ghi sổ cái trên 30 giây, kéo dài 10 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Sự kiện nghiệp vụ cần ghi sổ cái tới ledger-service chậm.

## Tác động

Bằng chứng sổ cái của các thao tác gần đây chưa có; trong khoá kỳ, bước niêm phong chờ. SEV-3, SEV-2 trong tuần khoá kỳ.

## Kiểm tra nhanh

```bash
vtctl -e <env> -c <cell> consumer lag --group ledger-service
kubectl logs -n <namespace> deploy/ledger-service --tail=200 | grep -iE 'error|warn'
```

## Các bước

1. **Nếu lag consumer của ledger-service tăng:** [kafka-lag](kafka-lag.md); khởi động lại bằng `vtctl consumer restart` nếu treo.
2. **Nếu CSDL ledger chậm:** [pg-cpu](pg-cpu.md).
3. **Nếu outbox nguồn tồn đọng:** [outbox](outbox.md).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Lag | `vtctl -c <cell> consumer lag --group ledger-service` | `kafka-consumer-groups --describe` |
| Khởi động lại | `vtctl -c <cell> consumer restart --deployment ledger-service -n <namespace> --confirm` | `kubectl rollout restart` |

## Kiểm sau khi làm

p99,9 dưới 30 giây; cảnh báo tự đóng; kiểm tra đầu chuỗi kế tiếp đạt.

## Quay lui

Không áp dụng.

## Leo thang

Kéo dài quá 1 giờ: gọi SOC (chủ sổ cái) và cấp 2.

## Liên quan

[dr-14](dr-14.md), `../doi-soat-so-cai.md`, [OPS-22](../ops/ops-22-kiem-tra-chuoi-bam.md).
