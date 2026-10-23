# Sổ tay vận hành: GeoDNS và vùng điều phối toàn cục

> **Mã truy vết:** INF-20 · TL-04 mục 2.5, 2.6 "GeoDNS và định tuyến tenant tới cell" · DR-10, DR-13, DR-16 · OPS-07.
> **Thành phần:** PowerDNS Authoritative 4.9 (chart `deploy/charts/global-dns`, namespace `dns`), `dns-sync` (mã `services-go/dns-sync`), `edge-health` trả `/healthz/edge` (chart `deploy/charts/edge-health`), cụm `pg-registry` (chart `deploy/charts/global-pg`, namespace `registry-db`), origin CDN (chart `deploy/charts/global-cdn-origin`). Argo CD dựng tất cả qua ApplicationSet `okr-toan-cuc-*` (`deploy/argocd/templates/toan-cuc.yaml`) trên hai cụm `global-hl`, `global-sn`.

## 1. Ba loại bản ghi và ai ghi chúng

| Bản ghi | Kiểu, TTL | Ai ghi | Chuyển vùng |
|---|---|---|---|
| `app.danhgia.vn` (tên miền chung) | LUA `ifurlup`, 60 s | Job `pdns-vung` sau mỗi lần đồng bộ Argo CD | Tự động: VIP Hoà Lạc trước, VIP phía Nam khi Hoà Lạc trả khác `OK` ba lần liên tiếp (chu kỳ 10 s) |
| `cell-<x>.edge.danhgia.vn` (điểm vào cell) | A, 60 s | `dns-sync`, theo TTDL đang hoạt động của cell trong SVC-03 | **Không bao giờ tự động** (DR-10): chỉ đổi khi người trực đổi TTDL của cell |
| Tên miền tenant | CNAME, 300 s | Quy trình cấp phát OPS-07 (tenant-registry) | Theo bản ghi cell mà nó trỏ tới |

`/healthz/edge` trả `OK` chỉ khi APISIX lớp ngoài nhận được request, tenant-registry có ít nhất một nửa replica sẵn sàng và primary của `pg-registry` nằm ở TTDL đó; mọi trường hợp khác trả `FAIL` (lý do chỉ ghi ở nhật ký `edge-health`). Vì vậy, khi primary của `pg-registry` chuyển sang phía Nam, tên miền chung cũng tự đi theo.

## 2. Kiểm tra nhanh

```bash
dig +short @ns1.danhgia.vn app.danhgia.vn A          # VIP đang được trả
dig +short @ns1.danhgia.vn cell-g4.edge.danhgia.vn A # điểm vào cell
curl -s https://app.danhgia.vn/healthz/edge --resolve app.danhgia.vn:443:<VIP>   # OK hoặc FAIL của từng VIP
kubectl -n edge logs deploy/edge-health | grep edge_health_fail | tail
kubectl -n dns logs deploy/dns-sync | grep -E 'dns_sync_changed|dns_sync_round_failed' | tail
kubectl -n registry-db get cluster pg-registry-hl pg-registry-sn   # primary, số instance sẵn sàng
```

Cảnh báo `PgRegistryCrossSiteSyncLost` nghĩa là kênh WAL liên TTDL đứt: danh sách đồng bộ rơi về replica cùng TTDL, vẫn RPO 0 trong TTDL nhưng mất RPO 0 chéo TTDL. Không chuyển vùng `pg-registry` khi cảnh báo này đang bật trừ khi IC chấp nhận mất dữ liệu.

## 3. Chuyển vùng cell (DR-10)

1. IC quyết định chuyển cell; làm theo sổ tay chuyển vùng của cell (Patroni/CNPG của cell, Ceph multisite) cho tới khi TTDL phía Nam phục vụ được.
2. Đặt TTDL đang hoạt động của cell sang TTDL dự phòng: cột `registry.cell.active_dc` (trường `activeDc` của `RegistryCell`, NULL nghĩa là `primary_dc`; ràng buộc chỉ cho phép `primary_dc` hoặc `standby_dc`). Hợp đồng chưa có API ghi cờ này, nên người trực chạy `UPDATE registry.cell SET active_dc = 'PHIANAM' WHERE code = 'CELL-<X>'` bằng vai trò quản trị của `pg-registry` theo quy trình bốn mắt; quay về (DR-16) thì đặt lại `NULL`.
3. Trong vòng một chu kỳ (`INTERVAL`, mặc định 15 s) `dns-sync` ghi bản ghi `cell-<x>.edge` mới và ghi nhật ký `dns_sync_changed` với giá trị cũ, mới. Người dùng thấy cell mới sau tối đa 60 s (TTL) cộng bộ đệm của resolver.
4. Kiểm: `dig` ở mục 2 trả VIP phía Nam; ghi thời điểm vào biên bản sự cố.

Khi SVC-03 không trả lời, `dns-sync` không ghi gì (giữ bản ghi cũ). Đó là thiết kế: mất registry không được làm đổi điểm vào cell.

## 4. Chuyển vùng `pg-registry` (DR-13, DR-16)

Chuyển có kế hoạch (DR-16) hoặc sau sự cố khi IC duyệt (DR-13, RTO ≤ 15 phút):

1. Kiểm cả hai cụm đang khoẻ và không có `PgRegistryCrossSiteSyncLost` (có kế hoạch), hoặc xác nhận TTDL Hoà Lạc đã mất hẳn (sự cố).
2. Sửa `primarySite` trong `deploy/environments/global/global-pg/values.yaml` **và** `deploy/environments/global/global-dns/values.yaml` trong cùng một commit có chữ ký, gộp vào `main`. Argo CD đổi `spec.replica.primary` ở cả hai cụm (CNPG hạ cấp primary cũ rồi nâng cấp cụm kia), đồng thời chuyển Job `pdns-vung` và `dns-sync` sang TTDL mới.
3. `edge-health` ở Hoà Lạc chuyển sang `FAIL`, PowerDNS trả VIP phía Nam cho `app.danhgia.vn` sau khoảng 30 s.
4. Kiểm: `kubectl -n registry-db get cluster` ở cả hai cụm, `curl` tới `/healthz/edge` của từng VIP, một lần ghi thử qua SVC-03.

## 5. Ghi đè bằng tay khi PowerDNS kiểm sức khoẻ sai

Chỉ dùng khi IC duyệt. Thay bản ghi `app` bằng bản ghi A tĩnh, rồi khôi phục bằng cách đồng bộ lại Application `global-dns-global-<site>` (Job `pdns-vung` ghi lại bản ghi LUA):

```bash
kubectl -n dns exec deploy/pdns -- pdnsutil replace-rrset danhgia.vn app A 60 <VIP>
argocd app sync global-dns-global-hl
```

## 6. Kiểm thử

`deploy/charts/global-dns/tests/chay-kiem-thu.sh` (lint, kubeconform hai TTDL, bất biến `pg-registry` và GeoDNS, bốn cụm mgmt/global, `go test`), `deploy/charts/global-dns/tests/thu-docker.sh` (PowerDNS thật: chuyển và trả VIP; `dns-sync` thật với SVC-03 giả: đổi TTDL, giữ nguyên khi registry hỏng), `deploy/argocd/tests/chay-kiem-thu.sh` (ApplicationSet vùng toàn cục). Chưa kiểm được trên hai TTDL thật: độ trễ ghi của `remote_apply` và thời gian chuyển vùng CNPG phải đo ở diễn tập DR.
