> Nguồn: baseline mục 6 (cell và thuê bao); TL-04 chương "Mô hình triển khai" (Nguyên tắc chung, ba mô hình, Topology hai trung tâm dữ liệu, Vùng điều phối toàn cục và các cell, GeoDNS và định tuyến tenant tới cell), chương "Thiết kế cụm Kubernetes cho một cell" (Phiên bản Kubernetes, Số cụm và lựa chọn gộp hay tách cụm dữ liệu, Node pool, Taint và ràng buộc phân bố, Namespace theo miền và vùng bảo mật, NetworkPolicy mặc định từ chối, Istio ambient và mTLS STRICT, Lưu trữ, Cổng vào APISIX), chương "Định cỡ năng lực" mục "Định cỡ PostgreSQL", chương "Cấu hình triển khai từng service" (Nguyên tắc cấu hình chung, Bảng cấu hình theo service), chương "An toàn thông tin" mục "Chuỗi cung ứng phần mềm", chương "Quy trình vận hành" mục "GitOps và phát hành theo đợt cell"; `_ghi-chu-TL-04.md` mục "Hạ tầng và topology"; TL-01 mục "SVC-05 mission-core" phần "Co giãn, chịu lỗi, bảo mật"; `docs/quy-dinh/HA-TANG.md`.

# Hạ tầng production và triển khai

Trang này tóm tắt cách nền tảng được triển khai ở môi trường thật theo TL-04, cho người viết Helm chart, manifest, pipeline và cho người viết mã máy chủ cần biết service của mình chạy ở đâu, được phép nói chuyện với ai và bị giới hạn tài nguyên thế nào. Hạ tầng dev bằng Docker Compose khác hẳn và được mô tả ở [trang 12](12-ha-tang-dev-va-trien-khai.md). Trang này không ghi tên miền, địa chỉ hay tài khoản thật; các ví dụ trong TL-04 dùng tên minh hoạ.

## Ba mô hình phân phối, một bộ chart

TL-04 đặt nguyên tắc một mã nguồn, một bộ Helm chart, ba cách phân phối: một nhánh `main`, một quy trình build sinh một tập ảnh container đã ký, và một bộ chart `vt-platform` gồm chart cha cùng chart con theo service và theo hạ tầng dùng chung trong cell. Khác biệt giữa ba mô hình chỉ nằm ở ba tệp giá trị và kênh phân phối gói.

Mô hình SaaS (`values-saas.yaml`) chạy trên Viettel Cloud, nhiều cell theo danh bạ SVC-03, lớp ứng dụng trên máy ảo co giãn được, lớp dữ liệu trên máy chủ vật lý có NVMe cục bộ gia nhập cùng cụm, bộ mật mã `standard`, egress chỉ qua egress gateway, cập nhật liên tục theo cell, hồ sơ năng lực `sizing: L`, `M` hoặc `S`. Mô hình đám mây riêng của tỉnh hoặc bộ (`values-private.yaml`) là một cell đơn trên hạ tầng của khách hàng, Rook-Ceph trong cụm khi khách hàng không có S3 tương thích, Argo CD tại chỗ đồng bộ từ bản nhân bản của kho Git, cập nhật theo quý do khách hàng duyệt. Mô hình cách ly cho lực lượng vũ trang và Công an (`values-airgap.yaml`) là một cell đơn với hồ sơ cứng hoá: tắt mọi kết nối ra ngoài (`egress.allowInternet: false`), bộ mật mã `crypto.provider: bcy` của Ban Cơ yếu, AI chạy tại chỗ, kênh cập nhật LTS chậm một chu kỳ (OPS-12), gói ngoại tuyến có ký số đi qua trạm kiểm định một chiều (SEC-45, SEC-46), hồ sơ `sizing: compact`. Pipeline CI chạy `helm template` với cả ba tệp values và kiểm kết quả bằng Kyverno CLI cùng bộ chính sách của cụm thật (SEC-28), nên một thay đổi làm hỏng bản cách ly bị phát hiện ngay lúc build. Hệ quả cho người viết mã: mọi khác biệt giữa các mô hình phải là tham số có kiểm thử, không được rẽ nhánh mã.

## Hai trung tâm dữ liệu, vùng điều phối toàn cục và cell

Nền tảng chạy trên TTDL chính tại Hoà Lạc và TTDL dự phòng phía Nam. Mỗi TTDL chia ba vùng sự cố độc lập về điện, làm mát và mạng lõi (`hl-a`, `hl-b`, `hl-c` và `sn-a`, `sn-b`, `sn-c`), là điều kiện để cụm Kubernetes, etcd, PostgreSQL có bản sao đồng bộ và Kafka hệ số nhân bản 3 sống sót khi mất trọn một vùng. Ở trạng thái thường, mọi cell nhận lưu lượng tại Hoà Lạc; phía Nam giữ bản sao ấm của từng cell, lớp dữ liệu chạy đủ kích thước và nhận dữ liệu liên tục (CNPG replica cluster, MirrorMaker 2, Ceph RGW multisite), lớp ứng dụng chạy tối thiểu. Dữ liệu nghiệp vụ sao chép bất đồng bộ sang phía Nam (cam kết RPO ≤ 15 phút, RTO ≤ 4 giờ theo NFR-05) vì độ trễ liên TTDL khoảng 15 đến 20 ms một chiều; sao chép đồng bộ chỉ dùng giữa các vùng sự cố trong một TTDL. Ngoại lệ duy nhất là CSDL danh bạ tenant của vùng điều phối toàn cục, được sao chép đồng bộ chéo TTDL. TL-04 đề xuất cân nhắc đặt primary của CELL-HCM ở phía Nam; tài liệu giữ mặc định Hoà Lạc cho tới khi có quyết định.

Vùng điều phối toàn cục chứa những thành phần mọi cell cần nhưng không có dữ liệu nghiệp vụ của tenant: danh bạ tenant SVC-03, bộ định tuyến toàn cục (GeoDNS và APISIX lớp ngoài), kho gốc Policy Pack và UI Pack có ký số, nguồn gốc CDN và kho phân tích quốc gia chỉ nhận số liệu tổng hợp. Nó chạy chủ động ở cả hai TTDL trên hai cụm riêng `global-hl`, `global-sn` với mục tiêu 99,95% (NFR-04). Mỗi TTDL còn có cụm quản trị `mgmt-hl`, `mgmt-sn` chứa Argo CD, Harbor, Cluster API và ngăn xếp quan sát. Tổng cộng hệ SaaS có 20 cụm (tám cell cộng hai cụm dùng chung, nhân hai TTDL), chưa kể staging và thử tải; CELL-DTW có cụm quản trị riêng trên hạ tầng của khối Đảng.

Cell được xếp ba hạng kích thước: hạng L gồm CELL-HCM (1,2 triệu tài khoản, 60.000 CCU thiết kế) và CELL-HN (1,0 triệu, 50.000 CCU), cả hai silo; hạng M gồm CELL-G1 đến CELL-G4 (bridge) và CELL-SH (pool), mỗi cell 40.000 CCU; hạng S là CELL-DTW (silo, 15.000 CCU); bản cách ly và đám mây riêng dùng hạng S hoặc compact. CCU thiết kế tính theo tỷ lệ 5%, tổng 325.000, dư khoảng 8% so với mục tiêu 300.000 vì các cell không chia sẻ tài nguyên.

Định tuyến có hai đường vào. Đường chính dùng tên miền riêng của tenant (bản ghi trong `registry.tenant_domain`): GeoDNS PowerDNS trả CNAME tới điểm vào của cell, điểm vào trả VIP của cell ở TTDL đang hoạt động. Đường phụ dùng tên miền chung tới APISIX lớp ngoài của vùng điều phối toàn cục, tra SVC-03 (bộ đệm 60 giây) rồi chuyển hướng 307 tới cell. Chỉ tên miền của vùng điều phối toàn cục được GeoDNS tự chuyển khi kiểm tra sức khoẻ thất bại; điểm vào của cell chỉ đổi khi chỉ huy sự cố đổi cờ `active_site` trong SVC-03 (DR-10). Token do Keycloak của cell phát hành mang claim `tenant` và `cell`, và APISIX của mỗi cell từ chối token có `cell` khác cell của mình. Tên claim tenant ở TL-04 (`tenant`) khác TL-01 (`tenant_id`) và TL-03 (`tid`), xem [trang 14](14-quyet-dinh-va-cau-hoi-mo.md).

## Cụm Kubernetes của một cell

Nền tảng dùng Kubernetes theo chính sách n-1 (1.36 tại thời điểm viết TL-04), nâng một bản minor mỗi quý theo OPS-15, cùng các thành phần nền ghim phiên bản trong kho GitOps: Cilium (CNI, NetworkPolicy), Istio ambient, CloudNativePG, Strimzi (Kafka KRaft, Kafka Connect, MirrorMaker 2), KEDA, Kyverno, External Secrets Operator, cert-manager, TopoLVM, Ceph CSI, Argo CD và Argo Rollouts, Velero, Cluster API và Cluster Autoscaler.

TL-04 chọn gộp ứng dụng và dữ liệu trong một cụm mỗi cell thay vì tách cụm dữ liệu riêng, để không nhân đôi số control plane và giữ được NetworkPolicy chính xác tới từng cổng CSDL. Ba biện pháp bù: dữ liệu chạy trên node pool riêng có taint; đội ứng dụng không có quyền `get secrets` hay `exec` trong namespace dữ liệu, DBA chỉ thao tác qua PAM (SEC-29, SEC-42); nâng control plane lần lượt từng master, nâng kubelet node dữ liệu thủ công từng node sau khi chuyển primary CSDL đi.

Cụm của cell hạng L có chín node pool, mỗi pool một taint để chỉ đúng loại tải được xếp lên. Pool `system` chạy các operator và thành phần hệ thống. Pool `ingress` chạy APISIX và Coraza. Pool `app-general` (không taint) chạy mission-core, workflow API, iam, org, evidence, notification, integration, analytics-api, Cube, Superset, frontend origin. Pool `app-worker` chạy scoring, document, Gotenberg, ClamAV, workflow executor, search indexer, Kafka Connect. Pool `data-postgres` chạy `pg-core`, `pg-flowable`, `pg-platform`, `pg-iam` và PgBouncer trên máy vật lý NVMe. Pool `data-kafka` chạy Kafka, Apicurio, Valkey. Pool `data-search` chạy OpenSearch, ClickHouse. Pool `security-zone` chạy ledger-service, esign-service, ai-gateway, `pg-ledger`, `pg-secure`, OpenBao, và là pool duy nhất có card mạng vào phân đoạn HSM, ổ đĩa mã hoá LUKS mở khoá bằng Clevis và Tang. Pool `gpu` chạy vLLM và OCR ở HCM, HN. Pool dữ liệu và vùng bảo mật không co giãn tự động; ba pool ứng dụng co giãn bằng Cluster Autoscaler. Mọi Deployment từ hai replica trở lên khai báo `topologySpreadConstraints` theo vùng sự cố với `whenUnsatisfiable: DoNotSchedule` và theo node với `ScheduleAnyway`, kèm `matchLabelKeys: [pod-template-hash]`; CNPG và Strimzi rải instance theo vùng bằng cơ chế của operator.

## Namespace, NetworkPolicy và Istio

Namespace chia theo miền và vùng bảo mật, mỗi namespace có nhãn `vt.io/security-zone` mà NetworkPolicy, Kyverno và Istio dùng chung. Vùng biên là `edge` (APISIX). Vùng ứng dụng gồm `core` (mission-core, workflow-service), `identity` (Keycloak, org-service), `platform` (evidence, notification, integration-gateway, search indexer, frontend origin), `workers` (scoring, document, Gotenberg, ClamAV), `analytics`. Vùng bảo mật gồm `secure` (ledger, esign, ai-gateway, vLLM) và `secure-data` (`pg-ledger`, `pg-secure`, OpenBao). Vùng dữ liệu gồm `data-pg`, `data-kafka`, `data-search`. Ngoài ra có `observability` và `egress`. Namespace dữ liệu nằm ngoài mesh có chủ đích và dùng TLS gốc có kiểm chứng thư khách (PostgreSQL `clientcert=verify-full`, listener mTLS của Strimzi), tránh mã hoá hai lần trên đường dữ liệu thông lượng cao. Mọi namespace ứng dụng áp Pod Security Standards mức `restricted` ở cả `enforce`, `audit`, `warn`; chỉ `observability` và namespace CNI được miễn trừ có ghi danh trong Kyverno (SEC-26).

Mỗi namespace bắt đầu bằng chính sách từ chối mọi lưu lượng vào và ra, rồi mở từng luồng cần thiết (SEC-32). Theo mẫu của TL-04, mission-core chỉ nhận từ APISIX ở `edge` (cổng 8080) và Prometheus (cổng quản trị 8081), chỉ ra được tới Pooler của `pg-core`, listener mTLS của Kafka và Valkey, và không có luồng mạng nào tới `secure`: mọi trao đổi với ledger-service đi qua Kafka. Hệ quả cho người viết mã: thêm một phụ thuộc mạng mới cho service nghĩa là phải sửa NetworkPolicy trong chart, và một service không thể vô tình nói chuyện với CSDL của service khác.

mTLS giữa service ứng dụng dùng Istio ambient (ADR-11): ztunnel trên mỗi node lo mTLS tầng 4 với danh tính SPIFFE, không có sidecar, tiết kiệm khoảng 0,2 đến 0,5 vCPU và 100 đến 200 MiB mỗi pod; độ trễ thêm ước tính 0,5 ms mỗi chặng (đo ở PERF-25). Chế độ STRICT bật cho toàn cụm (SEC-21), mỗi namespace có `AuthorizationPolicy` liệt kê danh tính được phép gọi vào. Chính sách tầng 7 theo đường dẫn và phương thức cần waypoint proxy, và chỉ bật cho `secure` và `egress`; ví dụ esign-service chỉ cho mission-core và workflow executor tạo yêu cầu ký.

## Lưu trữ và cổng vào

Lưu trữ có ba lớp. Volume NVMe cục bộ qua TopoLVM (`reclaimPolicy: Retain`) cho PostgreSQL, Kafka, OpenSearch, ClickHouse, vì các hệ này tự nhân bản và cần độ trễ ghi WAL dưới 100 micro giây. Ceph RBD qua Ceph CSI cho volume cần di chuyển giữa node (etcd của APISIX, OpenBao Raft, Loki, Tempo), nhân bản ba bản trên ba vùng, mã hoá phía client. Ceph RGW S3 cho minh chứng (bucket Object Lock chế độ compliance theo ADR-13), sản phẩm của document-service và kho sao lưu. Ceph vận hành ngoài cụm theo nhóm cell, mỗi cell một realm RGW riêng.

APISIX (SVC-01) là cổng vào duy nhất của cell, tối thiểu 3 replica trên pool `ingress`, nhận cấu hình tuyến từ GitOps qua APISIX Ingress Controller. Nó kết thúc TLS 1.3, chạy WAF Coraza với OWASP CRS (SEC-34), xác thực JWT qua JWKS của Keycloak trong cell và kiểm claim `cell`, giới hạn tốc độ theo người dùng, tenant và API, gắn header bảo mật (SEC-24). Thời gian chờ đọc 10 giây ở cổng là giới hạn trên cho mọi API đồng bộ; tác vụ dài phải trả 202 kèm mã theo dõi theo baseline mục 13.

## Mặc định an toàn của chart `vt-service`

Mọi service ứng dụng dùng chart con dựng trên chart thư viện `vt-service`, sinh Deployment hoặc Rollout, Service, ServiceAccount, ConfigMap, ExternalSecret, PodDisruptionBudget, NetworkPolicy, HPA hoặc ScaledObject, ServiceMonitor và PrometheusRule. Kyverno (SEC-28) là lưới an toàn thứ hai: Deployment thiếu probe hoặc thiếu requests, limits bị từ chối khi Argo CD đồng bộ. Quy ước bắt buộc cho service Java gồm bảy điểm. Ảnh nền JRE 21 distroless chạy bằng UID 10001, hệ thống tệp gốc chỉ đọc, chỉ `/tmp` là `emptyDir` tối đa 1 GiB (SEC-49). Heap đặt bằng `-XX:MaxRAMPercentage=70`, không bao giờ dùng `-Xmx` cố định. Service API cần độ trễ đuôi (mission-core, workflow API, org-service, integration-gateway) dùng ZGC thế hệ, service xử lý lô (scoring, document, workflow executor) dùng G1. Bộ nhớ yêu cầu bằng bộ nhớ giới hạn, CPU giới hạn cao hơn yêu cầu khoảng 30%. Luồng ảo bật cho service API, giới hạn đồng thời thật đặt bằng pool kết nối và bulkhead. Ba probe trên cổng quản trị 8081: `startupProbe` và `livenessProbe` gọi `/actuator/health/liveness` (liveness không kiểm CSDL), `readinessProbe` gọi `/actuator/health/readiness` có kiểm pool CSDL, Kafka producer và trạng thái nạp Policy Pack. Tắt êm bằng `server.shutdown=graceful`, `timeout-per-shutdown-phase=25s`, `preStop` chờ 5 giây, `terminationGracePeriodSeconds: 40`. Service Go dùng ảnh distroless static, `GOMEMLIMIT` bằng 80% giới hạn bộ nhớ; ai-gateway chạy Uvicorn với số worker bằng số vCPU giới hạn.

TL-04 dựng ảnh bằng BuildKit không root trong Jenkins, trong khi `backend/pom.xml` (NEN-005) và thẻ INF-05 dùng Jib; INF-05 chọn Jib và ghi điểm lệch ở trang 14.

## Bảng cấu hình theo service

Bảng dưới tóm tắt cấu hình chuẩn cho cell hạng L ở TL-04 mục "Bảng cấu hình theo service". Cell hạng M dùng cùng requests, limits với `maxReplicas` nhỏ hơn khoảng 30%; cell hạng S và bản compact có `minReplicas` 2 hoặc 3. Các service gọi OPA (mission-core, org-service, evidence-service, workflow-api, document-service) có thêm container `opa` cùng pod (100m CPU, 128 MiB yêu cầu, 256 MiB giới hạn) không tính trong bảng.

| Service | min / max replica | CPU req / lim | Bộ nhớ (req = lim) | Runtime | Phát hành |
|---|---|---|---|---|---|
| SVC-01 api-gateway | 3 / 8 | 2 / 4 | 2 GiB | APISIX | RollingUpdate |
| SVC-02 iam | 3 / 8 | 2 / 3 | 3 GiB | G1 | RollingUpdate |
| SVC-03 tenant-registry | 3 / 6 | 0,5 / 1 | 512 MiB | Go | RollingUpdate |
| SVC-04 org-service | 3 / 6 | 1 / 2 | 3 GiB | ZGC | RollingUpdate |
| SVC-05 mission-core | 6 / 24 | 3 / 4 | 8 GiB | ZGC thế hệ | Argo Rollouts canary |
| SVC-06 workflow API | 3 / 8 | 2 / 3 | 6 GiB | ZGC | Argo Rollouts canary |
| SVC-06 workflow executor | 2 / 10 | 2 / 3 | 4 GiB | G1 | RollingUpdate |
| SVC-07 scoring-service | 2 / 24 | 2 / 3 | 4 GiB | G1 | RollingUpdate |
| SVC-08 evidence API | 3 / 8 | 2 / 3 | 3 GiB | WebFlux, ZGC | RollingUpdate |
| SVC-09 ledger-service | 6 / 12 | 1 / 2 | 1 GiB | Go | RollingUpdate, một pod mỗi lần |
| SVC-10 esign-service | 2 / 6 | 1 / 2 | 2 GiB | G1 | RollingUpdate |
| SVC-11 document-service | 2 / 20 | 1 / 2 | 3 GiB | G1 | RollingUpdate |
| SVC-12 notification | 4 / 10 | 2 / 2 | 2 GiB | Go | RollingUpdate, đóng SSE dần 30 s |
| SVC-13 integration-gateway | 3 / 6 | 1 / 2 | 3 GiB | ZGC | RollingUpdate |
| SVC-14 ai-gateway | 2 / 6 | 1 / 2 | 2 GiB | Python 3.12, Uvicorn | RollingUpdate |
| SVC-15 analytics-api | 2 / 6 | 1 / 2 | 2 GiB | JVM | RollingUpdate |
| SVC-16 search indexer | 2 / 6 | 1 / 2 | 2 GiB | G1 | RollingUpdate |
| SVC-17 web-frontend origin | 2 / 4 | 0,25 / 0,5 | 256 MiB | nginx không root | RollingUpdate |

TL-04 còn liệt kê các thành phần phụ: ClamAV scanner (2 / 12), Gotenberg (2 / 24, CPU yêu cầu bằng giới hạn), vLLM (2 / 4 theo GPU, chiến lược `Recreate`), Cube (3 / 8), Superset (2 / 4). ledger-service có tối thiểu 6 replica vì số replica gắn với số phân vùng consumer của `sys.ledger.append.v1` (12 phân vùng, mỗi pod 2), và chỉ co giãn khi độ trễ consumer vượt ngưỡng kéo dài. Con số của mission-core lệch với TL-01: TL-01 ghi tối đa 40 replica, mỗi replica 2 vCPU, 4 GiB, giới hạn 4 vCPU, 4 GiB; TL-04 ghi tối đa 24 replica, 3 / 4 vCPU, 8 GiB. Đề xuất theo TL-04 vì tài liệu định cỡ mới hơn và cùng mức chi tiết (trang 14).

## GitOps và phát hành

Chuỗi cung ứng theo TL-04 mục "Chuỗi cung ứng phần mềm": merge request cần hai người duyệt; Jenkins build, chạy kiểm thử đơn vị và tích hợp Testcontainers, bộ kiểm thử vàng của Policy Pack (phải đạt 100%), quét mã (Semgrep, gitleaks, Trivy), dựng ảnh, sinh SBOM, quét ảnh, ký ảnh và chứng thực bằng cosign với khoá trong OpenBao Transit (SEC-37, SEC-38); ảnh đẩy lên Harbor theo digest, không dùng thẻ `latest` (SEC-55), và Jenkins mở commit cập nhật digest trong kho GitOps. Argo CD chỉ đồng bộ từ commit có ký, mỗi `AppProject` giới hạn namespace đích và loại tài nguyên, và không ai có quyền `sync` thủ công lên sản xuất ngoài vai trò phát hành. Kyverno kiểm chữ ký ảnh lúc tạo pod.

Kho GitOps gồm `clusters/<cụm>/`, `cells/<cell>/cell.yaml` (hạng, TTDL chính, tenant silo, đợt phát hành), `apps/<service>/` và `envs/<môi trường>/` chứa digest ảnh. ApplicationSet dùng bộ sinh ma trận cell × service, chiến lược `RollingSync` đồng bộ theo nhãn `wave` để phát hành theo đợt cell (OPS-01, OPS-02); thứ tự đợt là CELL-G4, rồi G1 đến G3 và SH, rồi HN và DTW, cuối cùng HCM. Trong mỗi đợt, Argo Rollouts chạy canary qua APISIX: 5% lưu lượng sang phiên bản mới trong 30 phút với phân tích SLO tự động, rồi tăng dần theo `canarySteps: [5, 25, 50, 100]`; vi phạm SLO thì tự huỷ và đưa lưu lượng về bản cũ, các đợt sau không bắt đầu (OPS-03, OPS-05). Tính năng mới vẫn tắt sau khi triển khai và được bật theo tenant bằng cờ tính năng (OPS-04, OpenFeature và flagd). Argo CD có cửa sổ đóng băng trước các mốc hạn sinh tự động từ `config.period` (OPS-06). Migration CSDL đi theo expand and contract (OPS-09), chạy bằng Job Flyway như ở [trang 17](17-thiet-ke-csdl-va-da-khach-thue.md).

## Liên quan

- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
- [Thiết kế CSDL và đa khách thuê](17-thiet-ke-csdl-va-da-khach-thue.md)
- [Sự kiện và đồng bộ dữ liệu](18-su-kien-va-dong-bo-du-lieu.md)
- [Bảo mật phía máy chủ](20-bao-mat-phia-may-chu.md)
- [Kiến trúc mission-core và microservice](21-kien-truc-mission-core-va-microservice.md)
- `docs/quy-dinh/HA-TANG.md`
