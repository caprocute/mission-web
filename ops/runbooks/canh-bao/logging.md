# Fluent Bit bỏ bản ghi log

> **Truy vết:** TL-04 mục 10.5 · TL-04 nhật ký tập trung · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `FluentBitDropping` (critical: `fluentbit_output_dropped_records_total` tăng trong 5 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Một pod Fluent Bit không gửi được log tới đầu ra và đã bỏ bản ghi.

## Tác động

Mất log, có thể gồm log kiểm toán gửi SIEM; ảnh hưởng điều tra sự cố và tuân thủ. SEV-2.

## Kiểm tra nhanh

```bash
kubectl logs -n <namespace-logging> <pod-fluent-bit> --tail=100
kubectl top pod -n <namespace-logging>
```

## Các bước

1. **Nếu đầu ra (Loki, OpenSearch, SIEM) từ chối hoặc chậm:** kiểm sức khoẻ đầu ra, dung lượng.
2. **Nếu bộ đệm của Fluent Bit đầy:** kiểm cấu hình bộ đệm lưu đĩa; tăng tài nguyên qua kho GitOps.
3. **Nếu là log kiểm toán:** báo SOC; kiểm audit-archiver (`../audit-archiver.md`).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Bộ đếm bỏ bản ghi không tăng trong 30 phút; cảnh báo tự đóng.

## Quay lui

Revert thay đổi cấu hình qua kho GitOps.

## Leo thang

Mất log kiểm toán: gọi SOC.

## Liên quan

[tracing](tracing.md), `../audit-archiver.md`.
