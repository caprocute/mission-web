# Client chờ kết nối PgBouncer

> **Truy vết:** TL-04 mục 10.5 · TL-03 PgBouncer chế độ giao dịch · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `PgBouncerWaitingClients` (warning: hơn 50 client chờ kết nối, kéo dài 2 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Pool kết nối tới một CSDL đã dùng hết, client phải xếp hàng.

## Tác động

Request chậm hoặc quá hạn. SEV-3; SEV-2 nếu SLO vi phạm.

## Kiểm tra nhanh

```bash
kubectl get pooler -n <namespace> --context cell-<cell>-<site>
```

Trong bảng quản trị PgBouncer (`SHOW POOLS;`, `SHOW CLIENTS;`): `cl_waiting`, `sv_active`, `maxwait` theo CSDL. Truy vấn giữ giao dịch lâu trong `pg_stat_activity` (`state = 'idle in transaction'`).

## Các bước

1. **Nếu có giao dịch treo:** CSDL tự cắt sau `idle_in_transaction_session_timeout`; tìm dịch vụ giữ giao dịch lâu, báo đội phát triển.
2. **Nếu số pod ứng dụng tăng vượt ngân sách kết nối:** kiểm `deploy/capacity/kiem_ngan_sach_ket_noi.py`; không tăng `default_pool_size` khi chưa kiểm bất đẳng thức ngân sách.
3. **Nếu CSDL chậm:** [pg-cpu](pg-cpu.md).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

`cl_waiting` về 0; cảnh báo tự đóng.

## Quay lui

Revert thay đổi cấu hình pool (nếu có) qua kho GitOps.

## Leo thang

Kéo dài quá 15 phút kèm lỗi API: gọi DBA cấp 2.

## Liên quan

[pg-cpu](pg-cpu.md), [OPS-25](../ops/ops-25-ra-soat-nang-luc.md).
