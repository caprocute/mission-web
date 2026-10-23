# Kiểm tra CIS bằng kube-bench và vá nhân bằng kured

> Nguồn: TL-04 mục 9.3 "Kiểm tra CIS (SEC-27)", "Vá hệ điều hành node (SEC-51)", "Đóng băng thay đổi" (OPS-06); INF-19.
> Thành phần: `deploy/charts/security-cell/templates/kube-bench.yaml`, `kured.yaml`, `rules/an-ninh-nen.yaml`.

## kube-bench hằng tuần

CronJob `kube-bench-master` (mọi master) và `kube-bench-node` (mẫu node worker, mỗi pod một node khác nhau) chạy 02:00 Chủ nhật giờ Việt Nam trong `platform-privileged`. Kết quả JSON được đổi thành chỉ số `kube_bench_checks{result="pass|fail|warn|info", node, target}` và `kube_bench_last_run_timestamp_seconds`, đẩy lên Pushgateway của cell. Mục tiêu: 0 FAIL ở control plane và worker; mỗi mục WARN có giải trình trong bảng dưới.

**KubeBenchFail.** Xem chi tiết: `kubectl -n platform-privileged logs job/<job> -c kube-bench` hoặc chạy tay `kubectl -n platform-privileged create job kb-tay --from=cronjob/kube-bench-master`. Sửa ở nguồn: cờ API server, controller manager, etcd trong `deploy/clusters/cell-cluster/templates/cluster.yaml`; cấu hình kubelet trong `KubeadmConfigTemplate` của pool; quyền tệp trong ảnh node. Không sửa tay trên node (node bất biến, thay bằng Cluster API).

**KubeBenchStale.** Không có kết quả quá 8 ngày: Job không lập lịch được (thiếu node để rải anti-affinity, `activeDeadlineSeconds` 3600), Pushgateway mất chỉ số khi khởi động lại (chạy tay Job), hoặc NetworkPolicy `kube-bench-to-pushgateway`.

### Mục WARN và không áp dụng có giải trình

| Mục CIS | Lý do |
|---|---|
| 1.2.x `--anonymous-auth` (worker join) | Cụm đặt `anonymous-auth=false` theo TL-04; kubeadm join của worker và kiểm tra sức khoẻ của cân bằng tải cần đọc ẩn danh `cluster-info` và `/readyz`, phải kiểm lại khi dựng cụm thật (suy luận) |
| 5.x (chính sách) | Kyverno và Pod Security thực thi; kube-bench chỉ báo WARN vì kiểm tra thủ công |
| Mục chạy trên kind | kind không có KMS, OIDC, `anonymous-auth=false`; số FAIL thật trên kind ghi trong báo cáo khi chạy `deploy/environments/kind/kiem-thu-an-ninh.sh` |

## kured

kured chỉ dùng cho bản vá nhân khẩn; vá thường kỳ là thay ảnh node hằng tháng qua Cluster API. kured khởi động lại từng node một (`--concurrency=1`) trong khung 01:00–05:00 giờ Việt Nam, rút pod qua Eviction API nên PDB được tôn trọng (`--force-reboot=false`: hết `drain-timeout` 30 phút mà PDB vẫn chặn thì bỏ lượt, thử lại chu kỳ sau).

Không khởi động lại khi Prometheus có cảnh báo `VtCuaSoDongBang` đang kích hoạt (hai cửa sổ đóng băng cố định của OPS-06: 24 tháng cuối quý tới hết ngày 10 tháng sau, 20/1 tới hết 04/2). Cửa sổ bổ sung theo kỳ đánh giá của tenant (`syncWindowsBoSung` của Argo CD) chưa được kured đọc; trong các cửa sổ đó người trực tạm dừng kured bằng khoá giả `kubectl -n platform-privileged annotate ds kured weave.works/kured-node-lock='{"nodeID":"manual"}'` và gỡ khoá khi hết cửa sổ.

### Lỗ hổng CVSS ≥ 9 trong cửa sổ đóng băng

1. CAB khẩn duyệt; mở merge request đặt `kured.boQuaDongBang: true` trong values của cell.
2. Sau khi gộp, Argo CD đồng bộ (sync tay được phép cho release-manager trong cửa sổ đóng băng); tạo `/var/run/reboot-required` trên node qua bản vá.
3. Theo dõi `kubectl get nodes -w` và chỉ số SLO; xong thì mở merge request trả `boQuaDongBang: false`.

## Quay lui

Rollback Application `security-cell`. Dừng kured khẩn: `kubectl -n platform-privileged annotate ds kured weave.works/kured-node-lock='{"nodeID":"manual"}' --overwrite`.
