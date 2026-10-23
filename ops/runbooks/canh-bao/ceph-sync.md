# Đồng bộ multisite Ceph chậm

> **Truy vết:** TL-04 mục 10.5 · Ceph RGW multisite hai TTDL · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `CephSyncBehind` (warning: đối tượng chưa đồng bộ lâu nhất quá 600 giây ở một vùng).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Đối tượng (minh chứng, bản sao lưu, biểu mẫu) ghi ở vùng chính chưa sang vùng dự phòng.

## Tác động

RPO của kho đối tượng xấu đi; replica PostgreSQL đọc WAL qua kho cũng có thể trễ. SEV-3.

## Kiểm tra nhanh

```bash
radosgw-admin sync status                           # trong pod công cụ Ceph của vùng
radosgw-admin sync error list | head
```

## Các bước

Làm theo `../ceph-multisite.md` mục "Cảnh báo": kiểm kết nối giữa hai vùng, lỗi đồng bộ theo bucket, tải ghi đột biến.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

`radosgw-admin sync status` báo theo kịp; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Chậm quá 1 giờ hoặc lỗi đồng bộ lặp lại: gọi cấp 2 lưu trữ.

## Liên quan

`../ceph-multisite.md`, [pg-replica-lag](pg-replica-lag.md).
