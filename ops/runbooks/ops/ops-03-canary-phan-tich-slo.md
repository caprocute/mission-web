# OPS-03 · Canary với phân tích SLO

> **Truy vết:** TL-04 mục 11.2 (Rollout, AnalysisTemplate `slo-api`) · INF-04 · thư viện `deploy/charts/vt-service` (`_workload.tpl`, `_resources.tpl`, khối `sloAnalysis` trong `_defaults.tpl`).
> **Chu kỳ:** mỗi bản phát hành · **Chịu trách nhiệm:** quản lý phát hành · **Tự động hoá:** Argo Rollouts.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/environments/kind/kiem-thu-gitops.sh` kiểm bản tốt được thăng cấp và bản trả 5% lỗi bị huỷ.

## Điều kiện kích hoạt

Mỗi lần Application của service nhận lưu lượng người dùng đồng bộ digest mới (OPS-02).

## Tác động

Các bước canary: 5% lưu lượng, phân tích `slo-api` 15 lần đo cách 2 phút (30 phút), rồi 25%, 50% (mỗi bước dừng 15 phút) và 100%. Ngưỡng tuyệt đối: tỷ lệ lỗi 5xx < 0,2% (`failureLimit: 1`), p95 đọc < 300 ms, p95 ghi < 800 ms (`failureLimit: 2`).

## Kiểm tra nhanh

```bash
kubectl argo rollouts get rollout <service> -n <namespace> --watch
kubectl get analysisrun -n <namespace> -l rollouts-pod-template-hash --sort-by=.metadata.creationTimestamp
kubectl describe analysisrun <tên> -n <namespace>       # giá trị từng lần đo và lần đo thất bại
```

Truy vấn kiểm tay (Prometheus của cell), dùng đúng biểu thức của `slo-api`:

```promql
sum(rate(http_server_requests_seconds_count{service="<service>", role="canary", status=~"5.."}[2m]))
  / clamp_min(sum(rate(http_server_requests_seconds_count{service="<service>", role="canary"}[2m])), 1)
```

## Các bước

1. Theo dõi Rollout tới bước 100%.
2. **Nếu khung giờ thấp điểm không đủ mẫu** (lưu lượng canary dưới khoảng 40 request mỗi giây): tăng `count` của `sloAnalysis` trong values của service ở merge request riêng trước khi phát hành, hoặc phát hành trong giờ làm việc.
3. **Nếu phân tích đạt nhưng có dấu hiệu xấu ngoài ba chỉ số** (lỗi nghiệp vụ, log lỗi tăng): dừng tay `kubectl argo rollouts pause <service> -n <namespace>`, điều tra, rồi `abort` (OPS-05) hoặc `promote` tiếp.
4. **Nếu phân tích thất bại:** Rollouts tự huỷ; chuyển OPS-05.
5. **Thăng cấp thủ công** (chỉ khi quản lý phát hành quyết định bỏ qua bước dừng, có ghi phiếu): `kubectl argo rollouts promote <service> -n <namespace>`.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem canary | (chưa có) | `kubectl argo rollouts get rollout <service> -n <namespace>` |
| Tạm dừng | (chưa có) | `kubectl argo rollouts pause <service> -n <namespace>` |
| Thăng cấp | (chưa có) | `kubectl argo rollouts promote <service> -n <namespace>` |
| Huỷ | (chưa có) | `kubectl argo rollouts abort <service> -n <namespace>` |

## Kiểm sau khi làm

Rollout `Healthy` ở 100%, ReplicaSet cũ được thu nhỏ; AnalysisRun `Successful`; cảnh báo SLO của cell không bật.

## Quay lui

`kubectl argo rollouts abort` đưa toàn bộ lưu lượng về bản ổn định; sau đó revert digest trong kho GitOps để Argo CD không đồng bộ lại bản lỗi (OPS-05).

## Leo thang

Gọi cấp 2 nền tảng khi AnalysisRun báo `Error` (không truy vấn được Prometheus) thay vì `Failed`; khi đó canary không được bảo vệ.

## Liên quan

[OPS-02](ops-02-phat-hanh-theo-dot-cell.md), [OPS-05](ops-05-quay-lui-tu-dong.md), [slo-availability](../canh-bao/slo-availability.md), [slo-latency](../canh-bao/slo-latency.md).
