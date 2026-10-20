# INF-19 · SEC-22, SEC-30, SEC-43, SEC-50 Bảo mật nền: Falco, nhật ký kiểm toán API Kubernetes và luồng `sys.audit.v1` tới SIEM, Pod Security, chính sách egress

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-19` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-09 |
| Truy vết | TL-04 mục 9.3, 9.5, 9.7 → SEC-22, SEC-26, SEC-27, SEC-29, SEC-30, SEC-31, SEC-36, SEC-43, SEC-50, SEC-51, SEC-56 → topic `sys.audit.v1`, `sys.security.runtime.v1`, `sys.siem.v1` → tiêu chí D6, D9 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện lớp bảo mật nền của cụm theo TL-04 chương 9: nhật ký kiểm toán API Kubernetes theo chính sách phân mức, phát hiện bất thường lúc chạy bằng Falco và Tetragon, luồng sự kiện an ninh chuẩn hoá về SIEM qua Kafka, Pod Security `restricted` có kiểm tra CIS định kỳ, RBAC tối thiểu, và kiểm chứng chính sách egress. Khi xong, mở shell trong một container ứng dụng trên kind sinh cảnh báo Falco đi tới topic an ninh, và mọi thao tác `pods/exec` được ghi đầy đủ trong nhật ký kiểm toán.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Bảo mật nền tảng Kubernetes" (2603–2864: PSS và kube-bench 2605–2645, KMS v2 và etcd 2647–2663, audit policy 2665–2691, RBAC và PAM 2693–2703, Falco và kured 2858–2860), "An ninh mạng" (2973–3020), "Tuân thủ TCVN 11930:2017 cấp độ 4" (3140–3171, đoạn SIEM 3169).
- Cùng tệp mục "Log: Fluent Bit có bộ đệm đĩa" (3333–3441, đầu ra `sys.audit.v1`).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-04.md` dòng 56 (topic `sys.siem.v1`, `sys.security.runtime.v1` do TL-04 tự thêm).
- Chart `cell-base` (INF-08), `cell-egress` (INF-09), Fluent Bit (INF-14).

## 3. Phạm vi công việc

- Viết tệp chính sách audit API server đúng TL-04: `None` cho đọc của kube-proxy, apiserver và leases; `Metadata` cho secrets, configmaps; `RequestResponse` cho RBAC, `pods/exec|attach|portforward`, `serviceaccounts/token`, CR CNPG và Strimzi khi ghi; `Metadata` cho mọi ghi khác; cấu hình API server (`anonymous-auth=false`, `profiling=false`, `encryption-provider-config` KMS v2 qua OpenBao Transit, TLS ≥ 1.2) trong `KubeadmControlPlane` của INF-08.
- Viết values Falco (eBPF) với quy tắc riêng: shell trong container vùng app/data, ghi ngoài `/tmp`, kết nối ra không qua `egress`, đọc `/var/run/secrets`; Tetragon chặn `ptrace` ở `secure`; Falcosidekick đẩy sang Kafka `sys.security.runtime.v1`, mức Critical gửi kênh SOC.
- Viết bộ chuẩn hoá ECS và luồng `sys.siem.v1` (mTLS, một chiều) gom nguồn: audit Kubernetes, sự kiện Keycloak `iam.auth.event.v1`, quyết định từ chối của OPA, Falco, WAF, OpenBao audit, PAM, `sys.audit.v1`; tài liệu danh sách quy tắc phát hiện tối thiểu của TL-04 (đăng nhập thất bại nhiều, phá kính, đổi RBAC, ảnh không ký bị từ chối, shell trong container, xuất > 1.000 hồ sơ/giờ, `decrypt` Transit tăng đột biến, chuỗi băm đứt) để SOC cấu hình phía SIEM.
- Viết Job kube-bench hằng tuần đẩy kết quả vào Prometheus (mục tiêu 0 FAIL); cấu hình kured 01:00–05:00 tôn trọng PDB và cửa sổ đóng băng.
- Viết RBAC theo TL-04: `view-app`, `operate-app`, `debug-app`, `view-data`, `operate-data`, `platform-admin` (ba vai trò sau chỉ cấp qua PAM có thời hạn 4 giờ), đăng nhập `kubectl` qua OIDC Keycloak vận hành có MFA.
- Viết kiểm thử chính sách egress: Job định kỳ từ mỗi namespace thử kết nối ra ngoài và báo về metric (bằng chứng SEC-36).

## 4. Ngoài phạm vi

- Vận hành SIEM và SOC 24/7 (dịch vụ bên ngoài, SEC-43; chỉ cung cấp luồng và quy tắc).
- PAM (công cụ ghi phiên) và quy trình phá kính DBA (SEC-58) — cần hệ thống PAM thật, ghi trong báo cáo.

## 5. Tệp được phép sửa

- `deploy/charts/security-cell/**` (Falco, Tetragon, kube-bench, kured, RBAC; tên suy luận), `deploy/clusters/**` phần audit policy và cấu hình API server, `deploy/charts/otel-collector/**` hoặc Fluent Bit chỉ phần luồng SIEM.
- `ops/runbooks/an-ninh-*.md`, `deploy/environments/kind/**`, `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform` không lỗi; `falco --validate` đạt cho tệp quy tắc.
- Trên kind có Falco: `kubectl exec` mở shell trong pod `core` sinh sự kiện Falco và thông điệp tới topic `sys.security.runtime.v1` của Kafka thử; nhật ký kiểm toán API có bản ghi `RequestResponse` cho thao tác đó.
- kube-bench chạy trên kind và kết quả có trên Prometheus (ghi số FAIL thật và lý do các mục không áp dụng cho kind).
- Người dùng có vai trò `view-app` không đọc được Secret; Job kiểm egress báo 0 luồng ra ngoài danh sách.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
