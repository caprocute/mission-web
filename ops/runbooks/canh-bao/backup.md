# Thiếu bản sao lưu nền hoặc lưu WAL trễ

> **Truy vết:** TL-04 mục 10.5 · INF-17 Barman Cloud · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `BackupMissing` (critical: không có bản sao lưu nền trong 26 giờ, hoặc lưu WAL trễ quá 5 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay (giai đoạn này chỉ phát triển, YC-PT-19). Kịch bản `BackupMissing` giả lập trên kind theo tiêu chí VH-02 chưa làm; kiểm thử luật cảnh báo có trong `deploy/charts/observability-cell/rules/tests/canh-bao-toi-thieu.test.yaml`.

## Điều kiện kích hoạt

Bản sao lưu nền hằng ngày hoặc lưu WAL liên tục của một cụm PostgreSQL bị ngắt.

## Tác động

RPO không còn được bảo đảm; mất dữ liệu nếu có sự cố đúng lúc này. SEV-2.

## Kiểm tra nhanh

```bash
kubectl get backup -n <namespace> --sort-by=.metadata.creationTimestamp | tail -5
kubectl cnpg status <cụm-pg> -n <namespace>          # mục "Continuous Backup status", WAL lưu cuối
```

## Các bước

Làm theo `../sao-luu-postgresql.md` mục 3 (cảnh báo `BackupMissing`): kiểm quyền và dung lượng kho đối tượng, chứng chỉ, chạy lại bản sao lưu nền.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Chạy sao lưu nền ngay | (chưa có) | `kubectl cnpg backup <cụm-pg> -n <namespace>` |

## Kiểm sau khi làm

Có bản sao lưu nền mới `completed`; WAL lưu cuối dưới 5 phút; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Không khôi phục được lưu WAL trong 1 giờ: SEV-1, gọi DBA cấp 2.

## Liên quan

`../sao-luu-postgresql.md`, [restore-check](restore-check.md), [OPS-18](../ops/ops-18-dien-tap-khoi-phuc.md).
