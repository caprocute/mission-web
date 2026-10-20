# INF-09 · ADR-11, SEC-32..SEC-36 Cụm Kubernetes của cell phần 2: Istio ambient mTLS STRICT, StorageClass Ceph RBD và NVMe cục bộ, egress gateway

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-08 |
| Truy vết | TL-04 mục 3.7, 3.8, 9.5 → ADR-11, ADR-13 → SEC-21, SEC-32, SEC-33, SEC-35, SEC-36, SEC-57 → PERF-25 (chi phí ztunnel) → tiêu chí C8, D8 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện lớp mạng và lưu trữ của cụm cell: Istio ambient với mTLS STRICT và `AuthorizationPolicy` theo danh tính SPIFFE, waypoint L7 cho vùng `secure` và `egress`, hai StorageClass `nvme-local` và `ceph-rbd` đúng tham số TL-04, và cổng ra có IP cố định chỉ cho phép tới danh sách đích được duyệt. Khi xong, trên kind một service ngoài danh sách không gọi được `esign-service`, lưu lượng giữa pod là mTLS, và pod ngoài namespace `egress` không ra được Internet.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Istio ambient và mTLS STRICT" (445–484, mẫu `esign-allow-callers`), "Lưu trữ: Ceph RBD và volume NVMe cục bộ" (486–532), "An ninh mạng" (2973–3020, bảng kiểm soát và ví dụ `CiliumNetworkPolicy` egress).
- Cùng tệp mục "Namespace theo miền và vùng bảo mật" (294–331) để biết namespace nào trong mesh, namespace dữ liệu ngoài mesh dùng TLS `verify-full`.
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 4 (ADR-11, ADR-13).
- Chart `cell-base` của INF-08, `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Viết values cài Istio ambient (ghim 1.27) và `PeerAuthentication default` ở `istio-system` với `mtls.mode: STRICT`; namespace ứng dụng gắn `istio.io/dataplane-mode: ambient`, namespace dữ liệu và `observability` ngoài mesh.
- Viết `AuthorizationPolicy` cho từng namespace ứng dụng liệt kê danh tính SPIFFE được gọi vào (L4 qua ztunnel), sinh từ values `networkPolicy.ingressFrom` của chart để hai lớp (NetworkPolicy và mesh) không lệch nhau (suy luận về cách sinh).
- Triển khai waypoint L7 cho `secure` và `egress`; viết `esign-allow-callers` đúng mẫu: chỉ `core/sa/mission-core` và `core/sa/workflow-executor` được POST `/internal/v1/sign-requests`, `/internal/v1/sign-batches`.
- Viết StorageClass `nvme-local` (TopoLVM, device class `nvme`, xfs, `WaitForFirstConsumer`, mở rộng được, `Retain`) và `ceph-rbd` (Ceph CSI, `clusterID`, pool theo cell, imageFeatures `layering,exclusive-lock,object-map,fast-diff`, ext4, `encrypted: "true"`, `encryptionKMSID: openbao-kms`, `Immediate`, `Delete`) dưới dạng values theo cell.
- Viết cổng ra: namespace `egress` có egress gateway IP cố định; `CiliumNetworkPolicy` dùng `toFQDNs` chỉ cho `egress`, danh sách đích đọc từ values (VNeID, MySign, TSA, LGSP, NDXP, vOffice, SMS, email; danh sách thật do SVC-13 cung cấp sau), mọi namespace khác chặn ra ngoài (SEC-36).
- Bật Hubble ghi luồng bị chặn để làm bằng chứng SEC-32 và SEC-36.
- Gợi ý tách: (a) Istio ambient, PeerAuthentication, AuthorizationPolicy, waypoint; (b) StorageClass; (c) egress gateway và chính sách FQDN.

## 4. Ngoài phạm vi

- Dựng cụm Ceph ngoài cụm và RGW (INF-12); OpenBao KMS cho mã hoá RBD (INF-13).
- Đo chi phí ztunnel theo PERF-25 (QA-10); Falco, audit (INF-19).

## 5. Tệp được phép sửa

- `deploy/charts/cell-base/**`, `deploy/charts/cell-mesh/**`, `deploy/charts/cell-storage/**`, `deploy/charts/cell-egress/**` (tên chart suy luận), `deploy/environments/kind/**`.
- `deploy/charts/vt-service/**` chỉ phần sinh `AuthorizationPolicy` từ values.
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` (kèm schema CRD Istio, Cilium) không lỗi.
- Trên kind có Cilium và Istio ambient: `istioctl x ztunnel-config workloads` cho thấy pod ứng dụng dùng HBONE; pod thử trong `core` không phải `mission-core` gọi `esign-service` bị từ chối; `mission-core` gọi được đúng hai đường dẫn cho phép.
- Pod thử ngoài `egress` không ra được Internet; qua egress gateway chỉ tới được đích trong danh sách thử; Hubble ghi luồng bị chặn.
- StorageClass `nvme-local` và `ceph-rbd` được kiểm bằng schema; trên kind dùng local-path thay thế để chạy PVC mẫu, báo cáo ghi rõ chưa kiểm được TopoLVM và Ceph CSI thật.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
