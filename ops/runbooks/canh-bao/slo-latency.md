# Request đọc, ghi chậm quá ngưỡng (SLO-READ, SLO-WRITE)

> **Truy vết:** TL-04 mục 10.5 · SLO đọc p95 300 ms, ghi p95 800 ms · luật `deploy/charts/observability-cell/rules/slo-api.yaml`.
> **Cảnh báo:** `SLOReadLatencyBurn` (critical: hơn 72% request đọc chậm hơn 300 ms trong 5 phút); `SLOWriteLatencyBurn` (critical: hơn 72% request ghi chậm hơn 800 ms trong 5 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Phần lớn request đọc hoặc ghi của cell chậm hơn ngưỡng SLO trong 5 phút liên tục.

## Tác động

Người dùng thấy màn hình chậm, thao tác ghi treo; INP và LCP xấu. Mặc định SEV-2.

## Kiểm tra nhanh

```promql
histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket{method="GET"}[5m])))
histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket{method=~"POST|PUT|PATCH|DELETE"}[5m])))
```

Dashboard "SLO theo cell" và dashboard CSDL (thời gian truy vấn, khoá chờ, kết nối PgBouncer chờ).

## Các bước

1. **Nếu chậm ngay sau phát hành:** OPS-05.
2. **Nếu CSDL chậm** (truy vấn chậm, khoá chờ, CPU primary cao): [pg-cpu](pg-cpu.md), [pgbouncer](pgbouncer.md); migration giữ khoá xem [OPS-09](../ops/ops-09-migration-csdl.md).
3. **Nếu ghi chậm do outbox hoặc Kafka:** [outbox](outbox.md), [kafka-lag](kafka-lag.md).
4. **Nếu do tải đỉnh:** kiểm số pod so với `maxReplicas` (NG-05), bật suy giảm theo `../degradation.md`.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem, đặt mức suy giảm | `vtctl -c <cell> degrade status`, `degrade pin <mức> --for <thời hạn>` | `../degradation.md` |

## Kiểm sau khi làm

p95 đọc dưới 300 ms và p95 ghi dưới 800 ms trong 15 phút; cảnh báo tự đóng.

## Quay lui

Bỏ mức suy giảm đã đặt; quay lui bản phát hành theo OPS-05.

## Leo thang

Kéo dài quá 30 phút sau khi đã suy giảm: gọi cấp 2 nền tảng và CSDL.

## Liên quan

[slo-availability](slo-availability.md), [capacity](capacity.md), [nang-bac](nang-bac.md).
