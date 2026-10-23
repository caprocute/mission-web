# OPS-02 · Phát hành theo đợt cell

> **Truy vết:** TL-04 mục 11.1, 11.2 (bốn đợt, CELL-G4 trước, ổn định ≥ 24 giờ ngày làm việc giữa đợt) · INF-04 · `rollingSync` trong `deploy/argocd/values.yaml`.
> **Chu kỳ:** mỗi bản phát hành · **Chịu trách nhiệm:** quản lý phát hành · **Tự động hoá:** Argo CD RollingSync.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/environments/kind/kiem-thu-gitops.sh` kiểm cùng cơ chế trên hai cell giả lập kind-a (đợt 1), kind-b (đợt 2).

## Điều kiện kích hoạt

Bước `vtGitopsBump` của pipeline (INF-05) đã ghi digest mới vào `deploy/environments/production/digests.yaml` và merge request đã được gộp, ngoài cửa sổ đóng băng (OPS-06) và khi cell chưa tiêu quá 50% ngân sách lỗi trước ngày 15 (`release-freeze`).

## Tác động

Thứ tự đợt: 0 CELL-NB, 1 CELL-G4, 2 CELL-G1..G3 và CELL-SH (tối đa một nửa cùng lúc), 3 CELL-HN và CELL-DTW, 4 CELL-HCM. CELL-DTW do Argo CD riêng quản lý nên quản lý phát hành mở đợt đó trên Argo CD của DTW. Lỗi lộ ở đợt sớm chỉ ảnh hưởng cell nhỏ.

## Kiểm tra nhanh

```bash
argocd appset get okr-<service>                        # bước RollingSync hiện tại, Application đang chờ
argocd app list -l vt.io/service=<service> -o wide     # digest đang chạy theo cell
kubectl argo rollouts get rollout <service> -n <namespace> --context cell-<cell>-<site>
```

Dashboard: "SLO theo cell" (tỷ lệ lỗi, p95 đọc, ghi) của cell vừa phát hành, so với trước phát hành.

## Các bước

1. Xác nhận không có cửa sổ đóng băng: `argocd proj windows list okr-apps`.
2. Theo dõi đợt 0 và đợt 1: mỗi Application đồng bộ, Rollout chạy canary theo OPS-03. Chỉ khi mọi Rollout của đợt `Healthy` mới coi đợt xong.
3. Ghi thời điểm xong đợt vào phiếu phát hành. Giữ ổn định tối thiểu 24 giờ ngày làm việc trước khi đợt sau bắt đầu; RollingSync chuyển bước khi bước trước `Healthy`, nên quản lý phát hành giữ đợt sau bằng cách chưa gộp merge request mở đợt (đổi `releaseWave` hoặc digest theo môi trường) hoặc đồng bộ tay từng đợt.
4. Lặp lại cho đợt 2, 3, 4. Với đợt 2, RollingSync tự giới hạn 50% Application cùng lúc.
5. Sau đợt 4, tính năng mới vẫn tắt; bật theo tenant ở OPS-04.

**Nếu canary của một đợt bị huỷ:** dừng các đợt sau (không đồng bộ tiếp), xử lý theo OPS-05.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem tiến độ đợt | (chưa có) | `argocd appset get okr-<service>` |
| Đồng bộ tay một Application của đợt | (chưa có) | `argocd app sync <service>-<cell>-<site>` |
| Xem canary | (chưa có) | `kubectl argo rollouts get rollout <service> -n <namespace>` |

## Kiểm sau khi làm

Mọi Application của service mang digest mới, `Synced`, `Healthy`; không có AnalysisRun `Failed`; SLO của các cell không xấu đi sau 24 giờ.

## Quay lui

Revert commit digest trong `deploy/environments/production/digests.yaml` (merge request có chữ ký). Argo CD đồng bộ lại theo cùng thứ tự đợt. Migration CSDL không quay lui; xem OPS-09.

## Leo thang

Gọi cấp 2 nền tảng khi RollingSync kẹt ở một bước quá 1 giờ mà Rollout không lỗi; gọi IC khi lỗi lộ ở từ hai cell.

## Liên quan

[OPS-03](ops-03-canary-phan-tich-slo.md), [OPS-05](ops-05-quay-lui-tu-dong.md), [OPS-06](ops-06-dong-bang-thay-doi.md), [release-freeze](../canh-bao/release-freeze.md).
