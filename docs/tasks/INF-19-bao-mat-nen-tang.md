# INF-19 · SEC-22, SEC-30, SEC-43, SEC-50 Bảo mật nền: Falco, nhật ký kiểm toán API Kubernetes và luồng `sys.audit.v1` tới SIEM, Pod Security, chính sách egress

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
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

## 8. Bằng chứng chạy thật trên kind (24/10/2026, luồng B8)

Lần chạy dùng cụm gọn `vt-cell-kind` (`cluster-gon.yaml`: một control plane, một worker, kindnet, có nhật ký kiểm toán API) với cell-base đã cài. `deploy/environments/kind/kiem-thu-an-ninh.sh` dựng Kafka của cell thu nhỏ (chart kafka, không Connect, không Apicurio), `otel-siem` của chart `otel-collector` với chứng thư từ ClusterIssuer `openbao-pki-cell` (trên kind là CA tự ký cùng tên), Fluent Bit của `observability-cell` chỉ luồng kiểm toán (`values-kind-fluent-bit-audit.yaml`) và `security-cell` (`values-kind-an-ninh.yaml`). Kết quả lần cuối: 17 đạt, 0 hỏng.

| Tiêu chí | Kết quả |
|---|---|
| `falco --validate` | Quy tắc VT render từ chart hợp lệ cùng quy tắc mặc định của Falco 0.45 (cả trong `tests/chay-kiem-thu.sh`, 11/11, và trên kind). |
| Shell trong pod `core` → Falco → `sys.security.runtime.v1` | Falco không chạy được trên máy này (xem dưới). Thay vào đó gửi một cảnh báo đúng định dạng JSON của Falco cho quy tắc "VT shell trong container vùng ứng dụng hoặc dữ liệu" vào Falcosidekick: cảnh báo tới `sys.security.runtime.v1` qua OTLP/HTTP mTLS và `otel-siem`, rồi thành sự kiện ECS `falco.alert` trên `sys.siem.v1`. |
| Nhật ký kiểm toán API | `kubectl exec` vào `core/thu-shell` có bản ghi mức `RequestResponse` cho `pods/exec`; Fluent Bit đưa bản ghi vào `sys.audit.v1` bằng mTLS (`audit-writer`), `otel-siem` chuẩn hoá thành `kubernetes.audit` trên `sys.siem.v1`. |
| kube-bench, số FAIL thật | Benchmark `cis-1.10`. Control plane: master 11 FAIL, 50 PASS; node 2 FAIL, 16 PASS ở mỗi node. Kết quả có trên Pushgateway (`kube_bench_checks`). |
| `view-app` | Nhóm `oidc:platform-ops-dev` không đọc được Secret, không `pods/exec` được, đọc được pod. |
| Thăm dò cổng ra | 11 namespace, 22 phép thử, `vt_egress_probe_reachable` bằng 0 ở mọi phép thử. |

Các mục kube-bench FAIL và lý do trên kind (chạy lại cùng benchmark trong node để đọc tên mục): 1.1.12 thư mục dữ liệu etcd không thuộc `etcd:etcd` (kind chạy etcd bằng root); 1.2.5 thiếu `--kubelet-certificate-authority`, 1.2.17 thiếu `--audit-log-maxage`, 1.3.2 và 1.4.1 chưa đặt `--profiling=false` cho controller-manager, scheduler (cụm gọn dùng cấu hình kubeadm mặc định của kind, còn khuôn `cell-cluster` của cụm thật đặt đủ các cờ này); 5.1.1, 5.1.3, 5.1.5, 5.1.6 về `cluster-admin`, ký tự đại diện trong Role, ServiceAccount mặc định và token gắn tự động, do thành phần hệ thống của kind và các operator thử (Strimzi, cert-manager, CNPG) gây ra; 4.1.1, 4.1.9 quyền tệp dịch vụ và cấu hình kubelet của ảnh node kind là 644. Số mục trong Job (11 ở master) nhiều hơn khi chạy trong node (9) vì Job không có token API nên các mục nhóm 5 được chấm theo cấu hình tĩnh.

Lỗi phát hiện khi chạy thật và đã sửa:

1. `observability-cell`, `otel-collector` và `kiem-thu-quan-sat.sh` dùng broker `kafka-kafka-bootstrap`, trong khi chart kafka đặt tên cụm `kafka-cell` nên service là `kafka-cell-kafka-bootstrap` (ghi điểm lệch TL-04 ở wiki trang 14).
2. `observability-cell` thiếu NetworkPolicy cho Fluent Bit ra Kafka, nên default-deny của `observability` chặn luồng `sys.audit.v1`; thêm `fluent-bit-ra-kafka`.
3. Falcosidekick đặt CA và chứng thư máy khách OTLP qua `extraenvvars`, mà khoá đi qua viper nên bị đổi sang chữ thường; SDK OTLP không đọc, `otel-siem` từ chối với "certificate signed by unknown authority". Nay đặt ba biến `OTEL_EXPORTER_OTLP_LOGS_*` trên container, kiểm thử chart khẳng định điều này.
4. Job kube-bench không gắn token ServiceAccount nên kube-bench không biết phiên bản Kubernetes và lùi về benchmark của 1.18; nay ghim `kubeBench.benchmark: cis-1.10`.
5. `values-kind-an-ninh.yaml` trỏ ảnh về nguồn gốc (docker.io, ghcr) và số node kube-bench của cụm gọn; `values-kind-fluent-bit-audit.yaml` mới cho luồng kiểm toán.

Chưa chạy được và lý do: DaemonSet Falco không khởi động trong máy agent vì sandbox giới hạn cứng `RLIMIT_MEMLOCK` 8 MiB và không có `CAP_SYS_RESOURCE` ở tập giới hạn; libpman báo "unable to bump RLIMIT_MEMLOCK to RLIM_INFINITY" kể cả với `docker run --privileged`, dù nhân 6.18 có BTF. Trên node thường (có `CAP_SYS_RESOURCE`) script tự bật lại phần kiểm Falco thật (biến `FALCO=1`). Tetragon và kured không chạy vì cụm gọn không có Cilium và chỉ có một worker.
