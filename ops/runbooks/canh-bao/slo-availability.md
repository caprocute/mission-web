# Tiêu ngân sách lỗi khả dụng (SLO-AVL)

> **Truy vết:** TL-04 mục 10.5 (cảnh báo đa cửa sổ theo tốc độ tiêu ngân sách) · SLO khả dụng 99,9% (ngân sách 0,1%) · luật `deploy/charts/observability-cell/rules/slo-api.yaml`.
> **Cảnh báo:** `SLOAvailabilityFastBurn` (critical: tỷ lệ lỗi 1 giờ và 5 phút vượt 14,4 lần ngân sách, kéo dài 2 phút); `SLOAvailabilitySlowBurn` (warning: 6 giờ và 30 phút vượt 6 lần, kéo dài 15 phút); `CNPGClusterNotHealthy` (critical: cụm PostgreSQL ở TTDL chính không còn primary nhận ghi quá 2 phút, luật `canh-bao-toi-thieu.yaml`).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Tỷ lệ request lỗi (5xx và quá hạn) của API cell tăng tới mức nếu giữ nguyên sẽ hết ngân sách lỗi tháng trong khoảng hai ngày (nhanh) hoặc năm ngày (chậm). `CNPGClusterNotHealthy` chỉ thẳng một nguyên nhân thường gặp của tiêu nhanh: cụm PostgreSQL mất primary, mọi lệnh ghi của cell lỗi trong khi đọc vẫn được một phần.

## Tác động

Người dùng của cell gặp lỗi khi thao tác. Nhanh: SEV-2 (SEV-1 nếu cell gần như không dùng được). Chậm: SEV-3, xử lý trong giờ làm việc.

## Kiểm tra nhanh

```promql
sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum by (service) (rate(http_server_requests_seconds_count[5m]))
```

```bash
argocd app list -l cell=<cell>                              # có bản phát hành mới vừa đồng bộ không
kubectl argo rollouts list rollouts -A --context cell-<cell>-<site>
vtctl -e <env> -c <cell> degrade status                     # mức suy giảm hiện hành
```

Dashboard "SLO theo cell": dịch vụ nào đóng góp lỗi, bắt đầu từ lúc nào; trace lỗi trên Tempo theo `service`.

Khi lỗi rơi vào lệnh ghi của mọi dịch vụ, hoặc `CNPGClusterNotHealthy` đang cháy, kiểm primary của cụm PostgreSQL:

```promql
# Mỗi cụm ở TTDL chính phải có đúng một instance in_recovery = 0 (primary)
cnpg_pg_replication_in_recovery{cluster!~".*-sn"}
min by (cluster) (cnpg_collector_up{cluster!~".*-sn"})       # 0: bộ thu số liệu không kết nối được PostgreSQL
```

```bash
kubectl cnpg status <cụm-pg> -n <namespace> --context cell-<cell>-<site>   # primary hiện tại, trạng thái chuyển primary
kubectl get pods -n <namespace> -l cnpg.io/cluster=<cụm-pg> -o wide       # instance nào mất, nằm trên node nào
```

## Các bước

1. **Nếu lệnh ghi lỗi toàn cell, hoặc `CNPGClusterNotHealthy` đang cháy (cụm PostgreSQL không còn primary):** đây là SEV-1 theo `ops/van-hanh/muc-su-co.md`; tuyên bố sự cố, gọi trưởng ca làm IC và gọi cấp 2 CSDL ngay, không đợi xử lý xong.
   1. Xem `kubectl cnpg status`: CNPG đang chuyển primary thì chờ tối đa 2 phút; chuyển xong thì kiểm dịch vụ `-rw` trỏ về instance mới và PgBouncer nhận kết nối ([pgbouncer](pgbouncer.md)).
   2. Chuyển không được (standby hỏng, đầy đĩa, trễ quá xa): cấp 2 CSDL xử lý standby theo sổ tay của cụm (mở rộng volume, dựng lại instance); không tự `promote` bằng tay khi chưa rào chắn primary cũ, để tránh hai primary cùng ghi.
   3. Trong lúc chờ, IC cân nhắc ghim mức suy giảm 3 có thời hạn (`vtctl degrade pin 3 --for 1h`) để dồn tài nguyên cho tuyến nộp, ký; không chuyển vùng sang phía Nam khi TTDL chính vẫn sống (DR-10 chỉ dành cho mất TTDL).
   4. Có primary mới: theo dõi `OutboxBacklog` giảm dần (Debezium tự xả), kiểm DLQ, rồi xác nhận không mất giao dịch đã trả 2xx bằng LSN cuối đã xác nhận.
2. **Nếu lỗi bắt đầu ngay sau một bản phát hành:** huỷ canary hoặc quay lui theo [OPS-05](../ops/ops-05-quay-lui-tu-dong.md).
3. **Nếu lỗi tập trung ở một phụ thuộc** (CSDL, Kafka, Keycloak, OpenBao): mở sổ tay của cảnh báo phụ thuộc đó ([pg-cpu](pg-cpu.md), [pgbouncer](pgbouncer.md), [kafka-lag](kafka-lag.md), [openbao](openbao.md)).
4. **Nếu do quá tải** (CPU, hàng đợi tăng cùng lưu lượng): bật suy giảm theo `../degradation.md` (mức 2 tắt chức năng không thiết yếu), kiểm KEDA và HPA đã tăng đủ.
5. **Nếu chỉ một tenant:** kiểm hạn mức `API_RPS` và lỗi dữ liệu của tenant; hạ mức sự cố xuống SEV-3.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem mức suy giảm | `vtctl -c <cell> degrade status` | `../degradation.md` mục 2 |
| Đặt mức suy giảm có thời hạn | `vtctl -c <cell> degrade pin 2 --for 1h --confirm` | `../suy-giam-ba-muc.md` mục 2 |
| Huỷ canary | (chưa có) | `kubectl argo rollouts abort <service> -n <namespace>` |

## Kiểm sau khi làm

Tỷ lệ lỗi 5 phút về dưới 0,1%; cảnh báo tự đóng; nếu đã ghim mức suy giảm thì bỏ ghim (`vtctl degrade clear --confirm`) và theo dõi thêm 30 phút.

## Quay lui

Bỏ mức suy giảm đã đặt; quay lui bản phát hành theo OPS-05.

## Leo thang

Cảnh báo nhanh kéo dài quá 30 phút, lỗi ở từ hai cell, hoặc cụm PostgreSQL mất primary: SEV-1, gọi IC; vai trò, nhịp thông báo và mẫu thông báo theo `ops/van-hanh/muc-su-co.md` và `ops/van-hanh/mau-thong-bao-su-co.md`.

## Liên quan

[slo-latency](slo-latency.md), [release-freeze](release-freeze.md), [pgbouncer](pgbouncer.md), [pg-replica-lag](pg-replica-lag.md), `ops/van-hanh/muc-su-co.md`, diễn tập `ops/dien-tap/2026-10-23-truc-sev1.md`, `../degradation.md`, [OPS-03](../ops/ops-03-canary-phan-tich-slo.md).
