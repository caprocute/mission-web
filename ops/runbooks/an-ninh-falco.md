# Phát hiện bất thường lúc chạy: Falco, Falcosidekick, Tetragon

> Nguồn: TL-04 mục 9.3 "Phát hiện bất thường lúc chạy (SEC-50)", "Pod Security Standards (SEC-26)"; INF-19.
> Thành phần: `deploy/charts/security-cell` (Falco, Falcosidekick, chính sách Tetragon), luồng SIEM ở `an-ninh-soc-quy-tac.md`.

Falco 0.45 chạy DaemonSet trên mọi node (namespace `platform-privileged`) với trình điều khiển eBPF hiện đại ở chế độ ít đặc quyền (không `privileged`, chỉ capability BPF, PERFMON, SYS_RESOURCE, SYS_PTRACE). Bộ quy tắc mặc định của Falco cộng bốn quy tắc riêng ở `files/falco/vt-rules.yaml`:

| Quy tắc | Mức | Ý nghĩa |
|---|---|---|
| VT shell trong container vùng ứng dụng hoặc dữ liệu | CRITICAL | Shell mở bằng `kubectl exec`, có tty, hoặc đưa vào từ ngoài trong namespace ứng dụng, dữ liệu, bảo mật, biên |
| VT ghi tệp ngoài /tmp trong container | ERROR | Mở tệp để ghi ngoài `/tmp`, emptyDir, tệp ảo; vùng dữ liệu được ghi thư mục dữ liệu của operator |
| VT kết nối ra ngoài không qua cổng ra | ERROR | Kết nối tới địa chỉ ngoài mạng nội bộ của cell từ pod không thuộc namespace `egress` |
| VT đọc /var/run/secrets bởi tiến trình phụ | ERROR | Tiến trình khác tiến trình chính (vpid 1) đọc token, chứng thư gắn vào pod |

Falcosidekick nhận mọi cảnh báo từ mức NOTICE, đẩy OTLP/HTTP mTLS tới otel-siem, nơi ghi Kafka `sys.security.runtime.v1` rồi chuẩn hoá sang `sys.siem.v1`. Mức CRITICAL gửi thêm webhook trực SOC (địa chỉ trong Secret `falcosidekick-soc`, đi qua egress gateway). Cảnh báo Prometheus `FalcoCritical` (observability-cell) gọi SOC qua Alertmanager như kênh thứ hai.

Tetragon chặn `ptrace` trong namespace `secure` (TracingPolicyNamespaced `chan-ptrace`, hành động Override trả EPERM). Agent Tetragon cài cùng Cilium, ngoài chart này.

## Cảnh báo

**FalcoCritical, sự kiện shell trong container.** Mở phiên PAM đang có (`an-ninh-pam-rbac.md`); khớp người, pod, thời điểm thì ghi nhận là thao tác hợp lệ. Không khớp: coi là xâm nhập. Gắn nhãn cách ly cho pod, chụp thông tin (`kubectl get pod -o yaml`, nhật ký container), báo SOC, không xoá pod trước khi đội điều tra đồng ý. Xoá pod sau khi điều tra để ReplicaSet dựng pod sạch từ ảnh đã ký.

**Ghi ngoài /tmp.** Thường do ảnh mới ghi bộ đệm vào thư mục chưa gắn emptyDir; xác nhận với đội phát triển và sửa chart (thêm volume), không nới quy tắc. Ghi vào thư mục hệ thống (`/bin`, `/usr`, `/etc`) là dấu hiệu xâm nhập.

**Kết nối ra ngoài không qua cổng ra.** Cilium đã chặn ở lớp mạng; xem `hubble observe --verdict DROPPED --from-pod <ns>/<pod>`. Ứng dụng cần gọi đối tác thì phải đi qua egress gateway (`HTTPS_PROXY`) và đích phải có trong danh sách của `cell-egress`.

**Đọc /var/run/secrets.** Xác định tiến trình (`proc.cmdline`); tiến trình hợp lệ của ảnh bên thứ ba khai vào `falco.secretReaders` qua merge request có duyệt bảo mật.

**FalcoNodeMissing.** Pod Falco không sẵn sàng trên một số node: `kubectl -n platform-privileged describe pod -l app.kubernetes.io/name=falco`. Lỗi nạp eBPF thường do nhân cũ (cần ≥ 5.8 có BTF) hoặc thiếu capability.

**FalcosidekickDauRaLoi.** Đầu ra `otlplogs` lỗi: kiểm tra chứng thư `falcosidekick-otlp-tls` (cert-manager), pod otel-siem, NetworkPolicy `falcosidekick-egress`. Đầu ra `webhook` lỗi: Secret `falcosidekick-soc`, đích SOC trong danh sách cổng ra.

## Thay đổi quy tắc

1. Sửa `files/falco/vt-rules.yaml` hoặc `values.yaml` (danh sách namespace, đường dẫn được ghi, mạng nội bộ).
2. Chạy `deploy/charts/security-cell/tests/chay-kiem-thu.sh`: `falco --validate` và `--dry-run` phải đạt không cảnh báo.
3. Merge request có duyệt bảo mật; Argo CD đồng bộ, DaemonSet cuốn chiếu từng node.

## Quay lui

Rollback Application `security-cell` trong Argo CD. Không tắt Falco để giảm nhiễu; nhiễu được xử lý bằng sửa quy tắc qua Git.

## Kiểm chứng trên cụm

`kubectl -n core exec <pod> -- sh -c true` phải sinh sự kiện "Shell trong container" trong nhật ký Falco, thông điệp ở `sys.security.runtime.v1`, và bản ghi `RequestResponse` cho `pods/exec` trong nhật ký kiểm toán API. Kịch bản: `deploy/environments/kind/kiem-thu-an-ninh.sh` (chưa chạy trên kind trong INF-19).
