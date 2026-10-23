# OPS-05 · Quay lui tự động

> **Truy vết:** TL-04 mục 11.2 (Rollouts tự huỷ khi phân tích vi phạm, các đợt sau không bắt đầu) · INF-04 · AnalysisTemplate `slo-api` của `deploy/charts/vt-service`.
> **Chu kỳ:** khi vi phạm SLO · **Chịu trách nhiệm:** tự động, SRE xác nhận · **Tự động hoá:** Argo Rollouts.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/environments/kind/kiem-thu-gitops.sh` kiểm Rollout trả 5% lỗi bị huỷ và quay về bản ổn định.

## Điều kiện kích hoạt

AnalysisRun của canary chuyển `Failed`; Rollout chuyển `Degraded` với lý do `RolloutAborted`. Hoặc SRE quyết định huỷ tay khi canary có dấu hiệu xấu ngoài ba chỉ số của `slo-api`.

## Tác động

Khi huỷ, toàn bộ lưu lượng về bản ổn định ngay; pod canary giữ thêm 600 giây (`abortScaleDownDelaySeconds`) để điều tra. Người dùng ở 5% lưu lượng có thể đã gặp lỗi trong thời gian phân tích; mức sự cố thường SEV-3, SEV-2 nếu cảnh báo SLO nhanh đã bật.

## Kiểm tra nhanh

```bash
kubectl argo rollouts get rollout <service> -n <namespace>          # trạng thái Degraded, lý do huỷ
kubectl describe analysisrun <tên> -n <namespace>                   # chỉ số nào thất bại, giá trị đo
kubectl logs -n <namespace> -l role=canary --tail=200               # log của pod canary còn giữ
```

## Các bước

1. Xác nhận lưu lượng đã về bản ổn định: trọng số trong `ApisixRoute` của service là 0 cho canary (`kubectl get apisixroute <service> -n <namespace> -o yaml`).
2. Revert commit digest của service trong `deploy/environments/production/digests.yaml` (merge request có chữ ký) để Argo CD không đồng bộ lại bản lỗi và RollingSync không mở đợt sau.
3. Ghi phiếu sự cố: bản phát hành, cell, chỉ số thất bại, giá trị. Đội phát triển sửa và phát hành bản mới qua OPS-02 từ đầu.
4. **Nếu huỷ tay:** `kubectl argo rollouts abort <service> -n <namespace>`, rồi làm bước 2, 3.
5. **Nếu bản ổn định cũng lỗi** (lỗi không do bản mới): không revert digest; chuyển sổ tay cảnh báo SLO tương ứng.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Huỷ canary | (chưa có) | `kubectl argo rollouts abort <service> -n <namespace>` |
| Chạy lại sau khi đã sửa cùng digest (hiếm) | (chưa có) | `kubectl argo rollouts retry rollout <service> -n <namespace>` |
| Quay về revision trước | (chưa có) | revert digest trong kho GitOps (không dùng `kubectl argo rollouts undo` vì Argo CD sẽ đồng bộ đè) |

## Kiểm sau khi làm

Rollout `Healthy` trên digest cũ sau khi Argo CD đồng bộ commit revert; cảnh báo SLO của cell không bật; RollingSync không còn Application chờ đợt sau.

## Quay lui

Không áp dụng: đây là chính thao tác quay lui. Migration CSDL đã chạy ở `PreSync` vẫn giữ nguyên vì theo mẫu mở rộng, thu hẹp (OPS-09) bản cũ chạy được trên lược đồ mới.

## Leo thang

Gọi cấp 2 khi Rollout không về `Healthy` trên bản ổn định trong 15 phút, hoặc khi phân tích báo `Error` thay vì `Failed`.

## Liên quan

[OPS-03](ops-03-canary-phan-tich-slo.md), [OPS-09](ops-09-migration-csdl.md), [slo-availability](../canh-bao/slo-availability.md).
