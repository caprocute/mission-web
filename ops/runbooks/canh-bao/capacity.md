# Năng lực dự phòng node pool dưới 15%

> **Truy vết:** TL-04 mục 10.5 · TL-05 NG-06 · Cluster Autoscaler · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `NodeCapacityHeadroomLow` (warning: CPU còn trống theo request của một pool dưới 15%, kéo dài 30 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Tổng CPU request của pod gần bằng CPU cấp phát được của node pool.

## Tác động

Pod mới (tăng theo KEDA, thay node) có thể không xếp được, nhất là ở đỉnh cuối kỳ. SEV-3; SEV-2 trong tuần mốc hạn.

## Kiểm tra nhanh

```bash
kubectl get nodes -l vt.io/pool=<pool> -o wide
kubectl get pods -A --field-selector status.phase=Pending
kubectl -n kube-system logs deploy/cluster-autoscaler --tail=100
```

## Các bước

1. **Nếu Cluster Autoscaler đã chạm giới hạn trên:** nâng giới hạn pool trong values của cụm (merge request), kiểm hợp đồng giữ chỗ (NG-06).
2. **Nếu autoscaler không thêm node được** (thiếu tài nguyên hạ tầng): gọi cấp 2 nền tảng.
3. **Nếu request của một dịch vụ tăng bất thường:** kiểm bản phát hành gần nhất.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Dự phòng trên 15%; không còn pod `Pending`; cảnh báo tự đóng.

## Quay lui

Revert thay đổi giới hạn pool.

## Leo thang

Có pod `Pending` của dịch vụ người dùng trong tuần mốc hạn: SEV-2, gọi cấp 2 nền tảng.

## Liên quan

[nang-bac](nang-bac.md), [OPS-25](../ops/ops-25-ra-soat-nang-luc.md), [OPS-17](../ops/ops-17-chuan-bi-truoc-moc-han.md).
