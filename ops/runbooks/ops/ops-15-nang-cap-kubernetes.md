# OPS-15 · Nâng cấp Kubernetes

> **Truy vết:** TL-04 mục 11.8 (chính sách n-1, staging và `CELL-PERF` trước, `kubent` hoặc `pluto`, operator trước, control plane theo đợt cell, worker `maxSurge: 1`, `maxUnavailable: 0`) · INF Cluster API: `deploy/clusters/cell-cluster` (`kubernetesVersion`, `templates/pools.yaml`).
> **Chu kỳ:** quý, ngoài cửa sổ đóng băng · **Chịu trách nhiệm:** đội nền tảng · **Tự động hoá:** Cluster API.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Kiểm thử render của khuôn cụm: `deploy/clusters/cell-cluster/tests/chay-kiem-thu.sh`. Nâng một bản minor của cụm kind theo sổ tay (tiêu chí VH-02) chưa làm.

## Điều kiện kích hoạt

Upstream có minor mới, nên minor đang chạy không còn là n-1 (`kubernetesVersion` hiện `v1.36.0` trong `deploy/clusters/cell-cluster/values.yaml`). Thay đổi thường, qua CAB hằng tuần (OPS-19).

## Tác động

Mỗi lần thay một master hoặc một worker; pod được rút có kiểm soát. Node dữ liệu cần DBA chuyển primary trước. Sai API bị loại bỏ có thể làm Argo CD không áp được tài nguyên sau nâng cấp.

## Kiểm tra nhanh

```bash
kubectl get cluster,kubeadmcontrolplane,machinedeployment -A --context mgmt   # phiên bản, sẵn sàng
kubectl get nodes -o wide --context cell-<cell>-<site>                       # phiên bản kubelet từng node
kubectl cnpg status <cụm-pg> -n <namespace> --context cell-<cell>-<site>     # primary ở node nào
```

Kiểm API bị loại bỏ trên bản render của kho GitOps: `pluto detect-files -d <thư mục render>` hoặc `kubent` trên cụm staging. Hai công cụ này chưa cài trong ảnh công cụ của repo; cài bản phát hành chính thức trên máy trạm vận hành trước khi dùng.

## Các bước

1. Nâng cụm staging và `CELL-PERF`: đổi `kubernetesVersion` trong values của cụm (merge request), Cluster API thay master lần lượt, rồi MachineDeployment.
2. Chạy PERF hồi quy rút gọn và kiểm thử đầu cuối trên staging; chỉ đi tiếp khi đạt.
3. Kiểm API bị loại bỏ bằng `pluto` hoặc `kubent`; sửa chart trước khi nâng sản xuất.
4. Nâng các operator (CNPG, Strimzi, cert-manager, Argo Rollouts, Istio…) lên bản tương thích minor mới.
5. Nâng control plane cụm sản xuất theo đợt cell của OPS-02 (CELL-NB, CELL-G4 trước, CELL-HCM sau cùng), mỗi lần một master; chờ `KubeadmControlPlane` sẵn sàng.
6. Nâng worker bằng thay MachineDeployment với `maxSurge: 1`, `maxUnavailable: 0`.
7. **Với node dữ liệu:** trước khi node chứa primary bị rút, DBA chuyển primary: `kubectl cnpg promote <cụm-pg> <instance-trên-node-khác> -n <namespace>`; từng node một.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem tiến độ thay node | (chưa có) | `kubectl get machines -A --context mgmt` |
| Chuyển primary PostgreSQL | (chưa có) | `kubectl cnpg promote <cụm-pg> <instance> -n <namespace>` |

## Kiểm sau khi làm

Mọi node báo phiên bản mới; Application của cell `Synced`, `Healthy`; dashboard SLO của cell không xấu đi 24 giờ; cụm PostgreSQL có đủ replica đồng bộ.

## Quay lui

Control plane không hạ minor được. Trước bước 5, dừng ở staging và sửa; sau bước 5, xử lý tiến về phía trước. Worker: trả `MachineDeployment` về mẫu máy cũ (cùng minor của control plane được phép lệch theo chính sách skew của Kubernetes).

## Leo thang

Một master không sẵn sàng sau 30 phút, hoặc mất quorum etcd: SEV-1, gọi cấp 2 nền tảng ngay.

## Liên quan

[OPS-16](ops-16-va-node.md), [OPS-02](ops-02-phat-hanh-theo-dot-cell.md), `deploy/clusters/cell-cluster/`.
