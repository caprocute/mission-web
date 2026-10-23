# OPS-16 · Vá node

> **Truy vết:** TL-04 mục 11.8 · SEC-51 · Cluster API `deploy/clusters/cell-cluster` · kured trong `deploy/charts/security-cell` (`templates/kured.yaml`, khối `kured` của values).
> **Chu kỳ:** tháng; khẩn khi có vá nhân · **Chịu trách nhiệm:** đội nền tảng · **Tự động hoá:** Cluster API, kured.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/environments/kind/kiem-thu-an-ninh.sh` dựng security-cell (gồm kured) trên kind.

## Điều kiện kích hoạt

Ảnh node mới hằng tháng; hoặc CVE nhân hệ điều hành cần khởi động lại khẩn (tệp `/var/run/reboot-required` xuất hiện trên node sau khi vá gói).

## Tác động

Vá tháng thay node bằng MachineDeployment, như OPS-15. Vá nhân khẩn: kured khởi động lại từng node một (concurrency 1) trong khung giờ đêm theo giờ Việt Nam (`startTime` mặc định 1am), rút pod trước khi khởi động lại.

## Kiểm tra nhanh

```bash
kubectl -n <namespace-security> get ds kured                         # kured chạy trên mọi node
kubectl get nodes -o custom-columns=NAME:.metadata.name,IMAGE:.status.nodeInfo.osImage,KERNEL:.status.nodeInfo.kernelVersion
kubectl -n <namespace-security> logs ds/kured --tail=50              # node đang chờ, khoá khởi động lại
```

## Các bước

**Vá tháng:** đổi mẫu máy (ảnh node mới) trong values của cụm qua merge request; Cluster API thay worker `maxSurge: 1`, `maxUnavailable: 0`; với node dữ liệu làm bước chuyển primary của OPS-15.

**Vá nhân khẩn:**

1. Đẩy gói vá qua quy trình ảnh node; kured đọc sentinel và xếp lịch khởi động lại.
2. Theo dõi log kured; trong cửa sổ đóng băng hoặc gần mốc hạn, cân nhắc thu hẹp `rebootDays` qua merge request.
3. Node dữ liệu: DBA chuyển primary trước khung khởi động lại.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Tạm dừng kured trên cụm | (chưa có) | `kubectl -n <namespace-security> annotate ds kured weave.works/kured-node-lock='{"nodeID":"manual"}'` |
| Bỏ tạm dừng | (chưa có) | `kubectl -n <namespace-security> annotate ds kured weave.works/kured-node-lock-` |

## Kiểm sau khi làm

Mọi node có `kernelVersion` mới; không còn sentinel; cảnh báo Falco và `FalcoNodeMissing` không bật sau khi node trở lại.

## Quay lui

Vá tháng: trả MachineDeployment về mẫu máy cũ. Vá nhân: không quay lui; nếu node không lên lại, Cluster API thay node mới.

## Leo thang

Node không về `Ready` sau khởi động lại 15 phút, hoặc hai node cùng lúc không sẵn sàng: gọi cấp 2 nền tảng.

## Liên quan

[OPS-15](ops-15-nang-cap-kubernetes.md), `../an-ninh-kube-bench.md`, [runtime-security](../canh-bao/runtime-security.md).
