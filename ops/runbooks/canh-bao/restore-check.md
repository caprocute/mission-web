# Không có lần khôi phục thử thành công trong 8 ngày

> **Truy vết:** TL-04 mục 10.5, 11.9 (OPS-18) · CronJob `restore-check` của `deploy/charts/backup-cell` · luật `deploy/charts/observability-mgmt/rules/dai-han.yaml`.
> **Cảnh báo:** `RestoreCheckStale` (warning: `backup_restore_check_success` không đạt 1 trong 8 ngày, hoặc không có số liệu).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Khôi phục thử tự động hằng ngày của một cụm thất bại liên tục hoặc không chạy.

## Tác động

Bản sao lưu chưa được chứng minh khôi phục được; OPS-17 coi là điều kiện chặn trước mốc hạn. SEV-3.

## Kiểm tra nhanh

```bash
kubectl get cronjob,job -n <namespace-backup> --context cell-<cell>-<site>
kubectl logs job/<restore-check-gần-nhất> -n <namespace-backup>
```

## Các bước

Làm theo `../sao-luu-postgresql.md` mục 4 (khôi phục thử hằng ngày): đọc log lần chạy, sửa nguyên nhân (quyền đọc kho, dung lượng cụm tạm, bản sao lưu hỏng), chạy lại tay.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Chạy lại ngay | (chưa có) | `kubectl create job --from=cronjob/<restore-check> restore-check-tay-<ngày> -n <namespace-backup>` |

## Kiểm sau khi làm

Lần chạy tay thành công, chỉ số bằng 1; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Bản sao lưu không khôi phục được: SEV-2, gọi DBA cấp 2 ngay.

## Liên quan

[backup](backup.md), [OPS-18](../ops/ops-18-dien-tap-khoi-phuc.md), `../sao-luu-postgresql.md`.
