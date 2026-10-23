# Sổ tay vận hành: Sao lưu Kubernetes bằng snapshot etcd và Velero

> **Mã truy vết:** INF-17 · TL-04 chương 8 "Kubernetes: Velero và etcd", "Bảng lịch sao lưu" · SEC-31, SEC-44 · OPS-01, OPS-18 · DR-09.
> **Thành phần:** CronJob `chup-etcd` (namespace `kube-system`), `BackupStorageLocation` `bk-sn` và `Schedule` `cell-resources-6h`, `stateful-volumes-daily` (namespace `velero`) của chart `deploy/charts/backup-cell`; bucket `bk-cell-<mã>-k8s` (tiền tố `etcd/`, `velero/`) trên kho sao lưu `ceph-sn-bk`, Object Lock COMPLIANCE 30 ngày, vòng đời dọn sau 31 ngày.

## 1. Phạm vi

Phần lớn tài nguyên được dựng lại từ Git bằng Argo CD (OPS-01). Sao lưu chỉ giữ hai phần không có trong Git: etcd (tài nguyên operator tạo động, Secret do ESO tạo, Lease) và volume của thành phần có trạng thái ngoài CSDL (etcd của APISIX trong `ingress`, Harbor, ngăn xếp quan sát). Namespace `data-pg`, `data-kafka`, `data-search` không bao giờ được sao lưu volume bằng Velero (chart từ chối cấu hình như vậy): snapshot khối của CSDL đang chạy không nhất quán, các kho này có cơ chế riêng.

## 2. Snapshot etcd mỗi 6 giờ

CronJob `chup-etcd` chạy trên một master (hostNetwork, đọc chứng thư khách etcd ở `/etc/kubernetes/pki/etcd` chỉ đọc), làm: `etcdctl snapshot save`, `etcdutl snapshot status`, mã hoá `age` với recipient từ OpenBao `kv/platform/backup/etcd` (thuộc tính `age_recipient`, Secret `etcd-age-recipient`), `shred -u` tệp rõ, đẩy `s3://bk-cell-<mã>-k8s/etcd/<node>-<YYYYMMDDTHHMMZ>.db.age`. Snapshot chứa Secret nên là dữ liệu C2: khoá bí mật age chỉ nằm trong OpenBao, lấy ra khi khôi phục theo quy trình bốn mắt.

Cảnh báo `EtcdSnapshotMissing` (không có bản đạt trong 7 giờ): đọc `kubectl -n kube-system logs job/<job>`; lỗi thường gặp là chứng thư etcd đổi đường dẫn sau nâng cấp, recipient chưa có trong OpenBao (job dừng với "recipient age không hợp lệ"), hoặc emptyDir `/tam` vượt giới hạn khi etcd lớn hơn 20 GiB.

Khôi phục (DR-09, khi mất cả control plane): tải bản mới nhất, `age -d -i <khoá> -o etcd.db <tệp>.db.age`, `etcdutl snapshot status etcd.db`, rồi `etcdutl snapshot restore etcd.db --data-dir /var/lib/etcd-moi` trên từng master theo tài liệu nâng cấp của bản phân phối Kubernetes, dừng kube-apiserver trước khi đổi thư mục dữ liệu. Sau khôi phục, Argo CD đồng bộ lại toàn bộ ứng dụng; xoá tệp `etcd.db` rõ bằng `shred -u`.

## 3. Velero

Máy chủ Velero (chart `vmware-tanzu/velero`, plugin `velero-plugin-for-aws`, `node-agent` cho data mover) cài riêng vào namespace `velero`, không tạo vị trí lưu mặc định; chart `backup-cell` tạo `BackupStorageLocation` `bk-sn` (bucket `bk-cell-<mã>-k8s`, tiền tố `velero`, CA `vt-noi-bo-ca`, khoá `kv/cell-<mã>/backup/k8s` dạng tệp `cloud`) và hai lịch theo TL-04 (giờ UTC):

| Lịch | Giờ | Nội dung | TTL |
|---|---|---|---|
| `cell-resources-6h` | 00:15, 06:15, 12:15, 18:15 UTC | Mọi namespace trừ `kube-system`, `velero`; bỏ events, pods, replicasets; không chụp volume | 30 ngày |
| `stateful-volumes-daily` | 19:45 UTC (02:45 giờ Việt Nam) | `ingress`, `platform-harbor`, `observability`, snapshot CSI Ceph RBD kèm `snapshotMoveData` lên kho sao lưu | 14 ngày |

Khoá của cell không có quyền xoá, nên Velero không tự xoá được bản hết TTL: bản sao lưu chuyển sang trạng thái xoá lỗi trong `velero backup get`, và vòng đời bucket dọn dữ liệu sau 31 ngày. Đây là hành vi đã chọn (SEC-44), không phải sự cố; định kỳ xoá các đối tượng `Backup` đã hết TTL bằng `velero backup delete --confirm` để danh mục gọn (dữ liệu trên kho không bị ảnh hưởng).

Kiểm tra: `velero backup-location get` (trạng thái `Available`), `velero schedule get`, `velero backup get`. Khôi phục thử hằng tháng vào cụm staging: `velero restore create --from-backup <tên> --namespace-mappings observability:observability-thu`.
