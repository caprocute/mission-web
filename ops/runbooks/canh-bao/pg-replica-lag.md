# Replica cluster PostgreSQL phía TTDL dự phòng trễ

> **Truy vết:** TL-04 mục 10.5 · TL-03 cụm PostgreSQL hai TTDL (CloudNativePG replica cluster) · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `CNPGReplicaClusterLag` (warning: trễ quá 5 phút; critical: quá 10 phút; kéo dài 2 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Cụm dự phòng ở TTDL thứ hai nhận WAL chậm so với primary.

## Tác động

Không ảnh hưởng người dùng ngay, nhưng RPO khi chuyển TTDL xấu đi tương ứng. Warning SEV-3, critical SEV-2.

## Kiểm tra nhanh

```bash
kubectl cnpg status <cụm-pg> -n <namespace> --context cell-<cell>-<site-dự-phòng>
kubectl get backup,scheduledbackup -n <namespace> --context cell-<cell>-<site-chính>
```

Dashboard CNPG: WAL lưu trữ (`cnpg_pg_stat_archiver_*`), băng thông giữa hai TTDL.

## Các bước

1. **Nếu lưu WAL ở primary trễ** (cũng bật [backup](backup.md)): xử lý lưu WAL trước, replica đọc WAL từ kho đối tượng.
2. **Nếu kho đối tượng giữa TTDL đồng bộ chậm:** [ceph-sync](ceph-sync.md).
3. **Nếu replica lỗi áp WAL:** đọc log pod replica; gọi DBA cấp 2.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Độ trễ dưới 5 phút; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Critical quá 30 phút: gọi DBA cấp 2, báo IC vì RPO của DR đang bị vi phạm.

## Liên quan

[backup](backup.md), [ceph-sync](ceph-sync.md), `../sao-luu-postgresql.md`.
