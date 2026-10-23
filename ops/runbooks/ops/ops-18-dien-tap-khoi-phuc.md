# OPS-18 · Diễn tập khôi phục

> **Truy vết:** TL-04 mục 11.9, 8.12 (hằng ngày tự động, hằng quý một cell, hằng năm toàn phần) · INF-17, INF-18 · CronJob `restore-check` của `deploy/charts/backup-cell` · DR runbook của QA-14, QA-15.
> **Chu kỳ:** ngày, quý, năm · **Chịu trách nhiệm:** DBA, IC · **Tự động hoá:** `restore-check`.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Cùng cơ chế đã có kịch bản kind: `deploy/environments/kind/kiem-thu-du-lieu.sh` (xem `../sao-luu-postgresql.md` mục 8).

## Điều kiện kích hoạt

Hằng ngày: đọc kết quả `restore-check` tự động. Hằng quý: diễn tập khôi phục một cell theo lịch. Hằng năm: diễn tập toàn phần. Cảnh báo `RestoreCheckStale` (8 ngày không có lần khôi phục thử thành công).

## Tác động

Không ảnh hưởng người dùng (khôi phục vào cụm tạm). Diễn tập không đạt nghĩa là RPO, RTO của DR chưa được chứng minh; OPS-17 coi đó là điều kiện chặn trước mốc hạn.

## Kiểm tra nhanh

```bash
kubectl get cronjob,job -n <namespace-backup> -l app.kubernetes.io/name=backup-cell --context cell-<cell>-<site>
kubectl logs job/<restore-check-gần-nhất> -n <namespace-backup>
```

```promql
max by (cell, cluster) (backup_restore_check_success)
```

## Các bước

1. **Hằng ngày:** người trực đọc chỉ số `backup_restore_check_success` mỗi cụm; giá trị 0 hoặc thiếu thì làm theo `../sao-luu-postgresql.md` mục 4 và [restore-check](../canh-bao/restore-check.md).
2. **Hằng quý:** IC chọn một cell, chạy kịch bản DR của QA-14 tương ứng trên cụm tạm, đo thời gian khôi phục so với RTO, so dữ liệu với RPO; ghi biên bản diễn tập.
3. **Hằng năm:** diễn tập toàn phần theo QA-15, gồm chuyển TTDL.
4. Ghi kết quả vào phiếu; hạng mục không đạt có người và hạn.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Chạy khôi phục thử ngay | (chưa có) | `kubectl create job --from=cronjob/<restore-check> restore-check-tay-<ngày> -n <namespace-backup>` |

## Kiểm sau khi làm

`backup_restore_check_success` bằng 1 cho mọi cụm trong 24 giờ qua; biên bản diễn tập quý, năm có thời gian đo và kết luận.

## Quay lui

Không áp dụng (khôi phục vào cụm tạm, xoá sau khi kiểm).

## Leo thang

Hai ngày liên tiếp khôi phục thử thất bại ở cùng cụm: gọi DBA cấp 2, kiểm toàn vẹn bản sao lưu ngay.

## Liên quan

`../sao-luu-postgresql.md`, `../sao-luu-kubernetes.md`, [backup](../canh-bao/backup.md), [restore-check](../canh-bao/restore-check.md).
