# INF-08 · TL-04 ch.3, SVC-01 Cụm Kubernetes của cell phần 1: node pool, taint, 13 namespace, NetworkPolicy mặc định từ chối, APISIX ingress

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (CELL-G4 và cụm đầu tiên) |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | – |
| Truy vết | TL-04 ch.3 (mục 3.2–3.6, 3.9), mục 6.8 → SVC-01 → SEC-24, SEC-26, SEC-32, SEC-34 → OPS-15 → tiêu chí A2, C7 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Mô tả dạng mã toàn bộ phần khung của một cụm cell theo TL-04 chương 3: danh mục cụm và node pool có taint, 13 namespace gắn nhãn vùng bảo mật và Pod Security, NetworkPolicy mặc định từ chối kèm luồng được phép, và cổng vào APISIX của cell với xác thực JWT, kiểm claim `cell`, WAF và giới hạn tốc độ. Khi xong, cùng một bộ manifest dựng được trên cụm kind ba node giả lập ba vùng, và sẵn sàng áp lên cụm thật khi được cấp.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Thiết kế cụm Kubernetes cho một cell": "Phiên bản Kubernetes và chính sách nâng cấp" (197–218), "Số cụm và lựa chọn gộp hay tách cụm dữ liệu" (220–232), "Node pool" (234–252), "Taint, toleration và ràng buộc phân bố theo vùng sự cố" (254–292), "Namespace theo miền và vùng bảo mật" (294–331), "NetworkPolicy mặc định từ chối" (333–443), "Cổng vào: Apache APISIX" (534–569).
- Cùng tệp mục "Cắt tải và giới hạn tốc độ ở APISIX" (1579–1647, plugin `vt-claims`, `ApisixPluginConfig protected-api`), "Co giãn node: Cluster Autoscaler trên Viettel Cloud" (1415–1500, phần Cluster API).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Số node của tầng ứng dụng" (278–297), "Máy chủ chuẩn" (209–231).
- `infra/dev/apisix/` (cấu hình dev hiện có), `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Viết manifest Cluster API cho cụm cell mẫu (`KubeadmControlPlane` 3 master trên 3 vùng, `MachineDeployment` theo pool và vùng) trong `deploy/clusters/cell-g4-hl/`, ghim Kubernetes 1.36 và các thành phần nền đúng bảng phiên bản TL-04 mục 3.1; phần provider Viettel Cloud để ở dạng mẫu chờ thông tin hạ tầng.
- Khai báo chín node pool, taint và nhãn đúng bảng TL-04 mục 3.3 (`vt.io/pool`, `vt.io/zone=secure`, `vt.io/rack`, `vt.io/pg-size`, `topology.kubernetes.io/zone`); pool dữ liệu và vùng bảo mật không co giãn.
- Viết chart `deploy/charts/cell-base` tạo 13 namespace (`edge`, `core`, `identity`, `platform`, `workers`, `analytics`, `secure`, `secure-data`, `data-pg`, `data-kafka`, `data-search`, `observability`, `egress`) với nhãn `vt.io/security-zone`, `vt.io/cell`, chế độ dataplane ambient và Pod Security `restricted` cho enforce, audit, warn; chỉ `observability` và namespace CNI được miễn trừ (SEC-26).
- Trong `cell-base`, mỗi namespace có `default-deny-all` (Ingress và Egress) và `allow-dns`; thêm luồng được phép theo mẫu TL-04 mục 3.6 (APISIX → mission-core 8080, observability → 8081, mission-core → Pooler `pg-core-rw`/`pg-core-ro` 5432, Kafka 9093, Valkey 6379), không có luồng `core` → `secure` (ADR-09); luồng `edge/apisix` → `platform/web-frontend` cho SVC-17.
- Viết chart `deploy/charts/api-gateway` cho APISIX trong cell: ≥ 3 replica trên pool `ingress`, etcd 3 node riêng, APISIX Ingress Controller, TLS 1.3, Coraza với OWASP CRS (SEC-34), header bảo mật (SEC-24), sinh `traceparent`, route mẫu `mission-core` (timeout đọc 10 s); không có route Strangler `/kpi-legacy/*` vì Mission là hệ mới độc lập, phần Strangler của ADR-20 không áp dụng.
- Viết plugin Lua `vt-claims` (đăng ký `vt_tenant`, `vt_sub`, từ chối JWT có claim `cell` khác cell) và `ApisixPluginConfig protected-api` (`openid-connect` `bearer_only`, `limit-count` 600/60 s theo tenant và người dùng trên Valkey, `limit-req`, 429 trả `application/problem+json` kèm `Retry-After: 30`).
- Điểm lệch cần lưu ý: (1) discovery OIDC trong TL-04 dùng realm `platform`, realm dev và hợp đồng `contracts/openapi/common/common.yaml` dùng `mission`; đặt tên realm thành tham số values, mặc định `mission` theo hợp đồng (API-first), ghi câu hỏi vào wiki 14. (2) MachineDeployment app-general 3/6 mỗi vùng cho 9/18 node, khác bảng pool 8/16; chọn 3 vùng × min 3 (ràng buộc cân bằng vùng), ghi lệch vào wiki 14.
- Gợi ý tách: (a) Cluster API và node pool; (b) `cell-base` namespace và NetworkPolicy; (c) APISIX, `vt-claims`, `protected-api`.

## 4. Ngoài phạm vi

- Istio ambient, StorageClass, egress gateway (INF-09); Falco, audit API (INF-19).
- Nối và kiểm thử trên cụm Kubernetes thật của Viettel Cloud khi được cấp (provider Cluster API, NIC 25 Gbps, pool vật lý); khi đó chỉ đổi giá trị cấu hình theo môi trường, không đổi manifest dùng chung.
- Bộ điều khiển suy giảm ba mức theo nhãn `vt.io/degrade-level` (đề xuất bổ sung hàng khung, xem báo cáo tổng); APISIX lớp ngoài và GeoDNS (INF-20).

## 5. Tệp được phép sửa

- `deploy/clusters/**`, `deploy/charts/cell-base/**`, `deploy/charts/api-gateway/**`, `deploy/environments/kind/**`.
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` và Kyverno CLI cho `cell-base` và `api-gateway` không lỗi; `clusterctl` hoặc `kubeconform` (schema CAPI) kiểm manifest Cluster API.
- Trên kind ba worker gắn nhãn `hl-a`, `hl-b`, `hl-c` có Cilium: pod thử trong `core` không gọi được `secure` và không ra Internet; DNS hoạt động; luồng APISIX → mission-core giả lập đi được.
- Kiểm thử APISIX trên kind: request không JWT bị 401; JWT ký bởi Keycloak thử có claim `cell` khác bị từ chối; vượt 600 request/60 s trả 429 đúng thân RFC 9457 và có `Retry-After`; payload SQLi mẫu bị Coraza chặn.
- Thông số riêng của cụm thật (provider, địa chỉ, kích thước pool) nằm trong `deploy/environments/<môi trường>/`, manifest dùng chung không chứa giá trị riêng của Viettel Cloud.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
