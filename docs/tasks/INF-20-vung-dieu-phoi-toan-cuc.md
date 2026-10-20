# INF-20 · SVC-03, DR-13 Vùng điều phối toàn cục: cụm quản trị, PostgreSQL toàn cục, GeoDNS PowerDNS, `dns-sync`, CDN tài nguyên tĩnh

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-20` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (cụm quản trị và vùng điều phối Hoà Lạc cho CELL-G4); chạy chủ động hai TTDL và chuyển vùng GeoDNS hoàn thiện ở đợt 3 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-04 |
| Truy vết | TL-04 ch.2 (mục 2.6, 2.7) → SVC-01 (APISIX lớp ngoài), SVC-03 → API `GET /api/v1/registry/resolve`, `GET /api/v1/registry/cells` → bảng `registry.tenant_cell`, `registry.tenant_domain` → OPS-07, DR-10, DR-13 → NFR-04 (99,95% toàn cục) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng dạng mã vùng điều phối toàn cục và cụm quản trị theo TL-04 chương 2 và TL-05: hai cụm `global-hl`, `global-sn` chứa SVC-03, APISIX lớp ngoài, kho gốc Policy Pack và UI Pack, origin CDN; PostgreSQL toàn cục sao chép đồng bộ chéo TTDL; GeoDNS PowerDNS với bản ghi kiểm tra sức khoẻ; tác vụ `dns-sync` ghi bản ghi điểm vào cell theo cờ `active_site`. Khi xong, trên môi trường thử, tên miền chung chuyển sang VIP dự phòng sau ba lần kiểm tra lỗi, còn điểm vào cell chỉ đổi khi cờ `active_site` đổi, không tự chuyển.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Topology hai trung tâm dữ liệu" (133–145), "Vùng điều phối toàn cục và các cell" (147–164), "GeoDNS và định tuyến tenant tới cell" (166–193, mẫu `pdnsutil`), kịch bản DR-13 trong "Kịch bản DR" (2336–2429).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Vùng điều phối toàn cục và cụm quản trị" (508–524).
- `contracts/openapi/modules/platform.yaml` (`resolveTenantCell`, `listCells`), TL-03 chương schema `registry` (dòng 929–1045).
- `deploy/web/` và `wiki/03-giao-dien-theo-tenant.md` (origin CDN cho SVC-17 và UI Pack, vô hiệu cache theo EVT-23).

## 3. Phạm vi công việc

- Viết khai báo cụm `mgmt-hl`, `global-hl` (và bản `-sn`) bằng Cluster API, kích thước theo bậc TL-05 (QM-1 gộp cụm quản trị và vùng điều phối rút gọn trên 3 node, dùng lại cụm quản trị đợt 0b); ApplicationSet cho thành phần toàn cục.
- Viết values PostgreSQL toàn cục (CNPG) cho schema `registry` với bản sao chéo TTDL `synchronous_commit = remote_apply` (DR-13), là ngoại lệ duy nhất sao chép đồng bộ chéo TTDL theo TL-04 mục 2.5.
- Viết cấu hình PowerDNS Authoritative: bản ghi LUA `ifurlup` cho `app.danhgia.vn` (VIP Hoà Lạc ưu tiên, phía Nam dự phòng, `stringmatch='OK'`, `timeout=2`, `interval=10`, TTL 60); bản ghi A `cell-<x>.edge` TTL 60; bản ghi CNAME tenant TTL 300.
- Viết tác vụ `dns-sync` (CronJob hoặc controller nhỏ, ngôn ngữ Go theo nhóm SVC-03; suy luận): đọc cờ `active_site` của từng cell từ SVC-03 và ghi bản ghi A tương ứng; ghi nhật ký mỗi thay đổi; không có đường tự chuyển khi kiểm tra sức khoẻ cell lỗi (DR-10 cần người duyệt).
- Viết endpoint kiểm tra `/healthz/edge` của cell trên APISIX: trả OK chỉ khi APISIX nhận request, mission-core có ≥ 50% replica sẵn sàng và primary `pg-core` ở TTDL đó.
- Viết route APISIX lớp ngoài cho tên miền chung: tra `GET /api/v1/registry/resolve` (bộ nhớ đệm 60 s) theo mã đơn vị hoặc email công vụ, trả 307 tới URL của cell.
- Viết cấu hình origin CDN cho tài nguyên tĩnh của SVC-17 và UI Pack: tệp băm giữ 1 năm `immutable`, tệp điều khiển phiên bản 60 s (khớp `deploy/web/nginx.conf`), cơ chế vô hiệu cache theo khoá phiên bản khi có EVT-23.
- Gợi ý tách: (a) cụm quản trị và vùng điều phối; (b) PostgreSQL toàn cục; (c) PowerDNS, `dns-sync`, `/healthz/edge`; (d) APISIX lớp ngoài và CDN.

## 4. Ngoài phạm vi

- Mã nghiệp vụ SVC-03 (task DV-03); chuyển tenant giữa cell OPS-08 (DV-03-07, VH-08).
- Diễn tập DR-10, DR-13, DR-16 (QA-15).

## 5. Tệp được phép sửa

- `deploy/clusters/mgmt-*/**`, `deploy/clusters/global-*/**`, `deploy/charts/global-*/**` (PowerDNS, PostgreSQL toàn cục, APISIX lớp ngoài; tên suy luận), `deploy/argocd/**` phần toàn cục.
- `services-go/dns-sync/**` (suy luận về vị trí), `deploy/environments/kind/**`.
- `ops/runbooks/geodns.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` không lỗi; `go test ./...` xanh cho `dns-sync`.
- Trên kind với PowerDNS chạy container và hai VIP giả lập: tắt endpoint kiểm tra của VIP ưu tiên, `dig app.<miền thử>` trả VIP dự phòng sau tối đa 3 chu kỳ; đổi `active_site` trong dữ liệu thử của SVC-03 làm `dns-sync` đổi bản ghi A; tắt mission-core không làm đổi bản ghi cell.
- Route khám phá trả 307 đúng URL cell với mã đơn vị thử và trả lỗi chuẩn RFC 9457 khi không tìm thấy.
- Báo cáo cuối task theo AGENTS.md mục 6, ghi rõ phần chưa kiểm được vì chưa có hai TTDL thật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
