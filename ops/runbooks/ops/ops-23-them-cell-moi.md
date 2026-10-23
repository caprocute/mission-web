# OPS-23 · Thêm cell mới

> **Truy vết:** TL-04 mục 11.9 (ngưỡng tách cell 1,5 triệu tài khoản hoặc 75.000 CCU hoặc 300 user task mỗi giây; sáu bước; mục tiêu năm ngày làm việc) · TL-05 NG-01, NG-02 · `deploy/cells/<cell>/cell.yaml` · `deploy/clusters/cell-cluster` · `deploy/argocd`.
> **Chu kỳ:** theo nhu cầu · **Chịu trách nhiệm:** đội nền tảng · **Tự động hoá:** Cluster API, ApplicationSet.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/argocd/tests/chay-kiem-thu.sh` kiểm ApplicationSet sinh đủ Application khi thêm `cell.yaml`.

## Điều kiện kích hoạt

Rà soát năng lực (OPS-25) dự báo một cell chạm 80% một ngưỡng tách trong hai quý tới (cảnh báo NG-01, NG-02, NG-04), hoặc có tenant silo mới. Thay đổi thường, qua CAB tuần.

## Tác động

Không ảnh hưởng người dùng cho tới khi chuyển tenant vào bằng OPS-08.

## Kiểm tra nhanh

```bash
ls deploy/cells/                                   # tên cell đã có
vtctl -e <env> cell list                           # cell đã đăng ký trong SVC-03
```

## Các bước

1. Khai báo cell: tạo `deploy/cells/<tên>/cell.yaml` (theo mẫu `deploy/cells/g4/cell.yaml`: `name`, `displayName`, `sizing`, `tenancy`, `primarySite`, `standbySite`, `releaseWave`, `argocd`, `silos`) và `values.yaml` riêng của cell; merge request có chữ ký.
2. Cluster API dựng cụm ở hai TTDL: thêm cụm theo khuôn `deploy/clusters/cell-cluster` (đặt tên `cell-<tên>-<site>` như `destinationNameFormat`), đăng ký cụm với Argo CD của cụm quản trị.
3. ApplicationSet tự sinh mọi Application cho cell mới; theo dõi theo OPS-01 tới khi `Healthy`.
4. Tạo realm RGW, KEK của cell trong OpenBao, Keycloak realm (theo `../openbao-van-hanh.md` mục "Khởi tạo một cụm mới", `../ceph-multisite.md`).
5. Chạy PERF-01 và PERF-03 rút gọn (giai đoạn đo tải, YC-PT-19).
6. Đăng ký cell trong SVC-03 và chuyển tenant vào bằng OPS-08.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Kiểm cell đã đăng ký | `vtctl cell list` | `GET /api/v1/registry/cells` |
| Xem Application của cell | (chưa có) | `argocd app list -l cell=<tên>` |

## Kiểm sau khi làm

Mọi Application của cell `Synced`, `Healthy` ở hai site; cell có trong danh sách `vtctl cell list`; kiểm thử khói của tenant thử đạt.

## Quay lui

Trước khi chuyển tenant: xoá `cell.yaml` làm ApplicationSet xoá Application; xoá cụm qua Cluster API. Sau khi có tenant: chuyển tenant ra trước (OPS-08).

## Leo thang

Cụm không dựng được ở một TTDL sau hai lần: gọi cấp 2 nền tảng và nhà cung cấp hạ tầng.

## Liên quan

[OPS-01](ops-01-gitops-applicationset.md), [OPS-08](ops-08-chuyen-tenant-giua-cell.md), [OPS-25](ops-25-ra-soat-nang-luc.md), [nang-bac](../canh-bao/nang-bac.md).
