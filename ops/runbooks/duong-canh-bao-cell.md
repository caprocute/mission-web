# Sổ tay vận hành: Đường cảnh báo từ cell lên Alertmanager cụm quản trị

> **Truy vết:** INF-15 · TL-04 mục 10.5 (Alertmanager gom theo `cell`, `alertname`, định tuyến critical tới gọi trực, warning tới kênh đội), OPS-13, OPS-14.
> **Thành phần:** chart `observability-cell` (`canhBaoQuanTri`, `templates/canh-bao-quan-tri.yaml`) ở mỗi cell; chart `observability-mgmt` (`alertmanager.congCell`, `templates/cong-canh-bao-cell.yaml`: ghostunnel kết thúc mTLS trước Service `alertmanager`) ở cụm quản trị `mgmt-hl`, `mgmt-sn`.
> **Chạy thử:** 23/10/2026 · B7 · máy kiểm thử cục bộ (`observability-mgmt/tests/kiem-thu-cong-cell.sh`: ghostunnel 1.9.2 trước Alertmanager thật); chưa chạy trên kind hay staging.

Prometheus của cell gửi cảnh báo tới hai nơi. Alertmanager trong cell chỉ chuyển cảnh báo `degrade_level` cho bộ điều khiển suy giảm (`suy-giam-ba-muc.md`). Mọi cảnh báo khác, trừ `severity=control`, đi qua HTTPS có chứng chỉ khách tới điểm vào `alertmanager-cong-cell` của cụm quản trị cùng site, rồi được định tuyến tới tuyến trực theo `deploy/charts/observability-mgmt/alertmanager/alertmanager.yaml`.

## Điều kiện kích hoạt

- Dựng cụm quản trị mới, thêm cell mới hoặc site mới (OPS-03): khai báo đường cảnh báo trước khi đồng bộ `observability-cell`, vì chart dừng render khi `canhBaoQuanTri.diaChi` trống.
- Chứng chỉ khách của cell hay chứng chỉ máy chủ của điểm vào sắp hết hạn (`CertificateExpiringSoon`), hoặc đổi CA (`openbao-pki.md`).
- Không thấy cảnh báo của một cell trên Alertmanager cụm quản trị dù luật đang kích hoạt ở cell.

## Tác động

Đường này đứt thì cảnh báo của cell không tới người trực: SEV-2 theo OPS-13 (mất khả năng phát hiện sự cố), dù dịch vụ người dùng chưa bị ảnh hưởng. Cắt tải tự động trong cell vẫn chạy vì đi qua Alertmanager của cell.

## Kiểm tra nhanh

1. Ở cell, Prometheus thấy Alertmanager cụm quản trị: `kubectl -n observability exec sts/prometheus-kps-prometheus -c prometheus -- wget -qO- localhost:9090/api/v1/alertmanagers` phải liệt kê `https://<host>:<cổng>/api/v2/alerts` trong `activeAlertmanagers`.
2. Ở cell, số thông báo gửi lỗi không tăng: `rate(prometheus_notifications_errors_total{alertmanager=~"https://.*"}[5m]) == 0`, `prometheus_notifications_dropped_total` không tăng.
3. Ở cụm quản trị, điểm vào sẵn sàng: `kubectl -n observability get deploy alertmanager-cong-cell` đủ bản, `kubectl -n observability logs deploy/alertmanager-cong-cell | grep -i "handshake\|denied"` không có lỗi của cell đang xét.
4. Ở cụm quản trị, cảnh báo của cell có mặt: `amtool --alertmanager.url=http://alertmanager.observability.svc:9093 alert query cell=<cell>` (chạy trong pod có amtool hoặc qua `kubectl port-forward`).

## Các bước

### Khai báo theo môi trường (lần đầu cho một cụm quản trị hoặc một cell)

1. **Chứng chỉ máy chủ của điểm vào.** Cấp cho tên DNS `<host>` (ví dụ dạng `alertmanager-cell.<site>.<miền nội bộ>`, tên thật theo quy hoạch DNS của môi trường) từ CA nội bộ, ghi vào OpenBao của cụm quản trị khoá `kv/mgmt/observability/alertmanager-cong-cell` ba thuộc tính `tls.crt`, `tls.key`, `ca.crt`. `ca.crt` là CA ký chứng chỉ khách của các cell (chuỗi CA gốc của `openbao-pki.md`).
2. **Values cụm quản trị** (values của Application `observability-mgmt` cho `mgmt-hl` hoặc `mgmt-sn`, qua merge request):
   ```yaml
   alertmanager:
     congCell:
       host: <host>
       cidrs: [<dải địa chỉ ra của các cell trong site>]
       service: { type: LoadBalancer, loadBalancerIP: <VIP nếu cần cố định> }
       # Bắt buộc (chart dừng render khi trống): ca.crt là CA gốc chung, phải lọc để chứng chỉ dịch vụ khác không gửi được.
       choPhep: { ou: [prometheus-cell] }
   ```
   Nếu khoá OpenBao hay tên ClusterSecretStore của cụm quản trị khác mặc định (`openbao-mgmt`), khai `alertmanager.congCell.tls.path` và `tls.secretStore`.
3. **Bản ghi DNS** `<host>` trỏ VIP của Service `alertmanager-cong-cell` (`kubectl -n observability get svc alertmanager-cong-cell`; chú thích `vt.io/ten-dns` ghi tên cần khai).
4. **Chứng chỉ khách của cell.** Cấp cho Prometheus của cell một chứng chỉ có `OU=prometheus-cell` (khớp `choPhep`), `extendedKeyUsage=clientAuth`, ghi vào OpenBao của cell khoá `kv/cell-<cell>/observability/alertmanager-quan-tri` ba thuộc tính `ca.crt` (CA ký chứng chỉ máy chủ ở bước 1), `tls.crt`, `tls.key`.
5. **Values của cell** theo site, `deploy/cells/<cell>/values-<site>.yaml` (site `hl` gửi `mgmt-hl`, site `sn` gửi `mgmt-sn`):
   ```yaml
   observability-cell:
     canhBaoQuanTri:
       diaChi: ["<host>:9443"]
       cidrs: [<dải VIP của điểm vào>]
       tls: { serverName: <host> }
   ```
6. Đồng bộ Application `observability-mgmt` trước, rồi `observability-cell` của từng cell (`argocd app sync`).

### Nếu cảnh báo của một cell không tới

1. `activeAlertmanagers` thiếu địa chỉ cụm quản trị: Secret `prometheus-alertmanager-quan-tri` chưa có hoặc values chưa đồng bộ; kiểm Application `observability-cell` của cell.
2. Lỗi `x509` ở Prometheus: `tls.serverName` khác SAN của chứng chỉ máy chủ, hoặc `ca.crt` phía cell sai; kiểm bằng `openssl s_client -connect <host>:9443 -servername <host> -cert tls.crt -key tls.key -CAfile ca.crt` từ máy có quyền.
3. ghostunnel ghi `denied` hoặc bắt tay hỏng: chứng chỉ khách hết hạn, CA khác, hoặc không khớp `choPhep`.
4. Hết thời gian kết nối: NetworkPolicy (`canhBaoQuanTri.cidrs` phía cell, `alertmanager.congCell.cidrs` phía cụm quản trị) hoặc tường lửa giữa cell và cụm quản trị; `externalTrafficPolicy: Local` giữ IP nguồn, nên dải phải là địa chỉ ra thật của node cell.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Lệnh tương đương |
|---|---|---|
| Xem Alertmanager mà Prometheus cell đang gửi | chưa có | `kubectl -n observability exec sts/prometheus-kps-prometheus -c prometheus -- wget -qO- localhost:9090/api/v1/alertmanagers` |
| Xem cảnh báo của cell ở cụm quản trị | chưa có | `amtool alert query cell=<cell>` |
| Kiểm cấu hình và định tuyến trước khi gộp | chưa có | `deploy/charts/observability-cell/tests/chay-kiem-thu.sh`, `deploy/charts/observability-mgmt/tests/chay-kiem-thu.sh` |

## Kiểm sau khi làm

Gửi một cảnh báo thử từ cell: đặt tạm một PrometheusRule `vt-thu-duong-canh-bao` với `expr: vector(1)`, `labels: { severity: warning, doi: sre }` qua merge request; trong vòng 2 phút `amtool alert query alertname=<tên> cell=<cell>` ở cụm quản trị thấy cảnh báo và kênh nhắn tin SRE nhận thông báo; gỡ luật, cảnh báo chuyển `resolved`.

## Quay lui

Đặt `observability-cell.canhBaoQuanTri.enabled: false` và `kube-prometheus-stack.prometheus.prometheusSpec.secrets: []` cho cell (Prometheus chỉ còn gửi Alertmanager trong cell), hoặc `alertmanager.congCell.enabled: false` ở cụm quản trị. Khi quay lui, cell mất đường cảnh báo tới người trực: chỉ làm trong lúc xử lý sự cố và theo dõi bằng dashboard Grafana qua Thanos Query.

## Leo thang

Đường cảnh báo của một cell đứt quá 15 phút: gọi IC, mức SEV-2 (OPS-13). Lỗi CA hay chứng chỉ: gọi đội quản trị khoá (`openbao-pki.md`).

## Liên quan

- [suy-giam-ba-muc.md](suy-giam-ba-muc.md): Alertmanager trong cell và bộ điều khiển suy giảm.
- [openbao-pki.md](openbao-pki.md): chuỗi CA nội bộ, cấp và gia hạn chứng chỉ.
- `deploy/charts/observability-mgmt/alertmanager/alertmanager.yaml`: định tuyến tới tuyến trực.
- `wiki/12-ha-tang-dev-va-trien-khai.md` mục quan sát.
