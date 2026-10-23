# OPS-01 · GitOps: ApplicationSet theo cell

> **Truy vết:** TL-04 mục 11.1, 11.2 · NFR-07 · INF-04 · chart `deploy/argocd` (ApplicationSet `okr-<service>`, AppProject `okr-apps`, `okr-nen-tang`).
> **Chu kỳ:** liên tục · **Chịu trách nhiệm:** đội nền tảng · **Tự động hoá:** Argo CD.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Kiểm thử tự động của cùng cơ chế: `deploy/argocd/tests/chay-kiem-thu.sh` (render và kiểm ApplicationSet) và `deploy/environments/kind/kiem-thu-gitops.sh` (Argo CD thật trên kind).

## Điều kiện kích hoạt

Có service mới cần chạy trong mọi cell, có cell mới (OPS-23), hoặc một Application báo `OutOfSync`, `Degraded` kéo dài sau khi gộp merge request vào kho GitOps.

## Tác động

ApplicationSet sinh một Application cho mỗi cặp cell × TTDL. Sai cấu hình ở đây có thể làm mọi cell nhận cùng một lỗi, nên mọi thay đổi đi qua merge request có chữ ký GPG; Argo CD từ chối commit không ký. Sự cố mặc định SEV-3; nếu Application của dịch vụ đang phục vụ bị `Degraded` ở một cell trở lên thì theo SEV của cảnh báo SLO.

## Kiểm tra nhanh

```bash
argocd appset list
argocd appset get okr-<service>                       # bộ sinh, bước RollingSync, Application đã sinh
argocd app list -l vt.io/service=<service>            # trạng thái đồng bộ và sức khoẻ theo cell, site
argocd app get <service>-<cell>-<site>                # tài nguyên lệch, lỗi đồng bộ gần nhất
```

Kho GitOps là chính repo này với gốc `deploy/`: `deploy/cells/<cell>/cell.yaml` (hạng, đợt phát hành, Argo CD quản lý), `deploy/charts/<service>/` (values chung), `deploy/environments/<môi trường>/digests.yaml` (digest ảnh, do bước `vtGitopsBump` của INF-05 ghi).

## Các bước

**Nếu thêm service mới vào ApplicationSet:**

1. Thêm phần tử service vào danh sách ApplicationSet trong `deploy/argocd/values.yaml` (tên, project `okr-apps` hoặc `okr-nen-tang`, chart con của `vt-platform`).
2. Chạy `deploy/argocd/tests/chay-kiem-thu.sh` ở máy của mình hoặc để CI chạy; kiểm thử đối chiếu danh sách service với dependency của `vt-platform` và render đủ Application cho mọi cell.
3. Mở merge request có chữ ký, người thứ hai của đội nền tảng duyệt, gộp. Argo CD của cụm quản trị tự đồng bộ chart `deploy/argocd` (ứng dụng gốc `deploy/argocd/ung-dung-goc.yaml`).
4. Theo dõi các Application mới đi qua RollingSync theo OPS-02.

**Nếu một Application lệch hoặc lỗi sau khi gộp:**

1. Đọc `argocd app get` để biết tài nguyên lệch. Lệch ở trường bộ điều khiển suy giảm ghi lúc chạy (`ignoreDifferences` trong `deploy/argocd/values.yaml`) là bình thường.
2. Lỗi render (`ComparisonError`) thì sửa values trong merge request mới; không sửa tay tài nguyên trên cụm.
3. Lỗi đồng bộ do quyền (`not permitted in project`) thì kiểm namespace và loại tài nguyên trong AppProject.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem cell đã đăng ký | `vtctl -e <env> cell list` | `argocd cluster list` |
| Xem Application theo service | (chưa có) | `argocd app list -l vt.io/service=<service>` |
| Đồng bộ một Application | (chưa có) | `argocd app sync <service>-<cell>-<site>` (chỉ vai trò `release-manager`) |

## Kiểm sau khi làm

Mọi Application của service ở `Synced` và `Healthy`; `argocd appset get okr-<service>` không còn điều kiện lỗi; dashboard SLO của cell không đổi.

## Quay lui

Revert merge request trong kho GitOps rồi để Argo CD đồng bộ lại. Với service vừa thêm, xoá phần tử khỏi danh sách làm ApplicationSet xoá Application; tài nguyên có dữ liệu (PVC, CSDL) giữ lại vì chính sách xoá của chart, cần DBA xác nhận trước khi dọn.

## Leo thang

Gọi cấp 2 nền tảng khi Application lỗi ở hơn một cell hoặc Argo CD của cụm quản trị không đồng bộ được chính nó.

## Liên quan

[OPS-02](ops-02-phat-hanh-theo-dot-cell.md), [OPS-23](ops-23-them-cell-moi.md), `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/19-ha-tang-production-va-trien-khai.md`.
