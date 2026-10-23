# Thăm dò cổng ra (bằng chứng SEC-36)

> Nguồn: TL-04 mục 9.5 "An ninh mạng" (SEC-32, SEC-36); INF-09 (`deploy/charts/cell-egress`); INF-19.
> Thành phần: `deploy/charts/security-cell/templates/egress-probe.yaml`, `files/scripts/tham-do-cong-ra.sh`, cảnh báo ở `rules/an-ninh-nen.yaml`.

Mỗi namespace vùng ứng dụng, dữ liệu, bảo mật và biên có CronJob `tham-do-cong-ra` chạy 30 phút một lần. Pod thăm dò là pod thường của namespace: chỉ có NetworkPolicy mặc định của cell-base cộng đường tới Pushgateway. Nó thử mở kết nối TCP thẳng tới các đích không có trong danh sách cổng ra (địa chỉ dành cho tài liệu RFC 5737 và tên miền ví dụ RFC 2606). Kết nối được là rò rỉ: `vt_egress_probe_reachable{namespace, target} = 1`. Mục tiêu: 0 luồng ra ngoài danh sách ở mọi namespace.

## Cảnh báo

**EgressProbeLeak (SOC).** Một namespace ra được Internet không qua egress gateway.

1. Xác nhận: `kubectl -n <ns> create job tdcr-tay --from=cronjob/tham-do-cong-ra` rồi `kubectl -n <ns> logs job/tdcr-tay`.
2. Kiểm tra chính sách: `kubectl get ciliumclusterwidenetworkpolicy chan-ra-ngoai-cum -o yaml` (cell-egress, `denyWorld`), `kubectl -n <ns> get networkpolicy`, `hubble observe --namespace <ns> --verdict FORWARDED --to-identity world`.
3. Nguyên nhân thường gặp: chính sách cho phép mới mở `0.0.0.0/0`, `exceptCIDRs` của cell quá rộng, Cilium trên node vừa khởi động lại chưa nạp chính sách.
4. Sửa qua Git (cell-base, cell-egress hoặc chart dịch vụ), không sửa tay. Trong lúc chờ, áp chính sách chặn tạm qua merge request khẩn.

**EgressProbeStale.** CronJob không chạy quá 1 giờ: xem `kubectl -n <ns> get jobs`, sự kiện lập lịch, Kyverno từ chối pod (ảnh, probe), NetworkPolicy `egress-probe-to-pushgateway`.

## Quay lui

Tắt thăm dò của một cell: `egressProbe.enabled: false` qua merge request. Không tắt để che cảnh báo rò rỉ.

## Kiểm chứng

Kịch bản trên kind: `deploy/environments/kind/kiem-thu-an-ninh.sh` bước 5 (chưa chạy trong INF-19). Script thăm dò đã chạy thử bằng busybox 1.37: đích mở trả 1, đích đóng trả 0.
