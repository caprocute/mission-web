# Độ đầy đủ trace dưới 99% (NFR-10)

> **Truy vết:** TL-04 mục 10.5 · NFR-10 · OpenTelemetry Collector (`deploy/charts/otel-collector`) · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `TraceCompletenessLow` (warning: `trace_completeness_ratio` dưới 0,99 trong 1 giờ).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Tỷ lệ trace có đủ ngữ cảnh lan truyền giữa các dịch vụ giảm.

## Tác động

Điều tra sự cố khó hơn; vi phạm NFR-10. SEV-4, SEV-3 nếu kéo dài.

## Kiểm tra nhanh

```bash
kubectl logs -n <namespace> deploy/<otel-collector> --tail=100 | grep -iE 'drop|refused|error'
```

Tìm dịch vụ có tỷ lệ span mồ côi tăng (trace không có span cha) trên Tempo, thường là dịch vụ vừa phát hành.

## Các bước

1. **Nếu một dịch vụ vừa phát hành mất lan truyền ngữ cảnh:** báo đội phát triển (header `traceparent`, ngữ cảnh qua Kafka).
2. **Nếu collector bỏ span** (quá tải, giới hạn bộ nhớ): tăng tài nguyên collector qua kho GitOps.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Tỷ lệ trở lại ≥ 99% trong 1 giờ; cảnh báo tự đóng.

## Quay lui

Revert cấu hình collector.

## Leo thang

Kéo dài quá một ngày: báo đội nền tảng quan sát.

## Liên quan

[logging](logging.md).
