# CƠ SỞ THIẾT KẾ CHUNG (BASELINE) CHO BỘ BỐN TÀI LIỆU

> Tệp này là "nguồn sự thật" dùng chung cho bốn tài liệu TL-01 đến TL-04. Mọi mã định danh (ADR, SVC, MOD, EVT, QT, SCR, BR, NFR, SEC, tên schema, tên bảng, tên trạng thái) phải dùng đúng như dưới đây để các tài liệu tham chiếu chéo được với nhau. Khi một tài liệu cần thêm mã mới, mã mới phải nằm trong dải mã được cấp cho tài liệu đó và không được đổi nghĩa mã đã có.

## 1. Bộ tài liệu và quan hệ logic

| Mã | Tên tài liệu | Tệp nguồn | Trả lời câu hỏi |
|---|---|---|---|
| TL-01 | Thiết kế kiến trúc chi tiết | src/TL-01-thiet-ke-kien-truc.md | Hệ thống gồm những thành phần nào, vì sao chọn như vậy, đáp ứng checklist đánh giá ra sao |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện | src/TL-02-quy-trinh-nghiep-vu.md (ghép từ TL-02a, TL-02b) | Người dùng làm gì, theo trình tự nào, trên màn hình nào, quy tắc nghiệp vụ nào |
| TL-03 | Thiết kế cơ sở dữ liệu và đồng bộ dữ liệu | src/TL-03-thiet-ke-csdl.md | Dữ liệu nằm ở đâu, thuộc service nào, đồng bộ bằng cơ chế nào |
| TL-04 | Triển khai, hiệu năng, dự phòng và ATTT | src/TL-04-trien-khai-van-hanh.md | Chạy trên Kubernetes thế nào, co giãn, sao lưu, khôi phục, bảo mật theo checklist |

Chuỗi truy vết bắt buộc: **Quy trình (QT, TL-02) → Màn hình (SCR, TL-02) → API và module/service (MOD/SVC, TL-01) → Bảng dữ liệu và sự kiện (schema.bảng, EVT, TL-03) → Thành phần triển khai, chỉ tiêu năng lực và biện pháp ATTT (TL-04)**. Ngược lại, mỗi tiêu chí checklist (A1…L12) phải chỉ ra được mục cụ thể trong TL-01/TL-03/TL-04 đáp ứng nó.

## 2. Căn cứ đầu vào và các giả định đã chọn

1. **Bản đánh giá kiến trúc công nghệ 10/2026** (29 trang, 12 trục A–L, 110 tiêu chí, 64 vấn đề, lộ trình đợt 0a–3). Đây là "checklist đánh giá" mà các tài liệu phải đáp ứng đầy đủ.
2. **Báo cáo BGĐ 10/2026** (74 slide) – mô tả mục tiêu nền tảng: 8 nhóm tính năng, Policy Pack, OKR theo dự thảo Quy định mới, AI không quyết định, 7 lớp bảo vệ, cell-based, ba mô hình triển khai, 8 nhóm dữ liệu PL 4.
3. **Ba góp ý của lãnh đạo** (G1 giao diện riêng theo tenant với kiến trúc SDUI + Micro-frontends; G2 nghẽn engine BPMN khi hàng trăm, hàng nghìn tenant; G3 hiệu năng của 7 lớp bảo vệ và mã hoá dữ liệu mật). Mỗi góp ý có một ADR riêng (ADR-05, ADR-04, ADR-06) và một mục phân tích định lượng trong TL-01, được TL-04 kiểm chứng bằng kịch bản thử tải.
4. **Yêu cầu quy mô do người dùng đặt ra**: 6 triệu người dùng, 300.000 người dùng đồng thời (CCU), mở rộng nhanh, tối ưu trải nghiệm, dễ triển khai và phát triển.
5. **Giả định về "công nghệ thường dùng"** (suy ra từ hiện trạng 5 repo trong bản đánh giá vì không có tài liệu riêng): Java 21, Spring Boot 3.5, Angular 21 + Native Federation + PrimeNG, Redis, Elasticsearch, Log4j2, Jenkins, Kubernetes, Oracle. Thiết kế giữ nguyên ngôn ngữ, framework và cách làm quen thuộc; chỉ thay thành phần khi bản đánh giá đã chỉ ra lý do (Oracle → PostgreSQL theo E2; Redis → Valkey và Elasticsearch → OpenSearch vì thay đổi giấy phép; iText7 AGPL → docx4j/OpenPDF theo E6).
6. **Giả định về "biểu mẫu như dự án MRC"**: không tìm thấy mẫu MRC trong dự án, nên TL-02 dùng cấu trúc đặc tả quy trình phổ biến của các dự án CNTT khối nhà nước tại Viettel (thông tin chung → lưu đồ làn bơi → bảng bước → quy tắc nghiệp vụ → ngoại lệ → đặc tả màn hình gồm mockup, bảng trường, bảng chức năng, thông báo). Khi có mẫu MRC chính thức chỉ cần đổi khung trình bày, nội dung giữ nguyên.

## 3. Chỉ tiêu phi chức năng (NFR) – dùng thống nhất trong cả bốn tài liệu

| Mã | Chỉ tiêu | Giá trị thiết kế |
|---|---|---|
| NFR-01 | Quy mô | 6.000.000 tài khoản; 300.000 CCU toàn quốc; tối đa 5.000 tenant |
| NFR-02 | Thông lượng | 15.000 request/giây đỉnh thường xuyên tại cổng API; thiết kế chịu 30.000 request/giây khi đột biến ngày hạn nộp (hệ số 2); tỷ lệ đọc/ghi 80/20 |
| NFR-03 | Độ trễ | API đọc p95 ≤ 300 ms; API ghi p95 ≤ 800 ms; tải trang p95 ≤ 1 s; LCP ≤ 2,5 s trên 4G; tác vụ nặng chạy nền, trả mã theo dõi trong ≤ 200 ms |
| NFR-04 | Khả dụng | 99,9%/tháng cho mỗi cell SaaS; 99,95% cho vùng điều phối toàn cục |
| NFR-05 | Khôi phục | RPO ≤ 15 phút, RTO ≤ 4 giờ khi mất trung tâm dữ liệu; RPO ≈ 0, RTO ≤ 5 phút khi mất một node CSDL trong cùng trung tâm |
| NFR-06 | Đỉnh cuối kỳ | Tính lại toàn bộ điểm của một cell lớn (1,2 triệu hồ sơ) trong ≤ 2 giờ; sinh và ký số lô 100.000 phiếu trong ≤ 4 giờ |
| NFR-07 | Linh hoạt | Đưa tenant mới vào vận hành ≤ 1 ngày; phát hành Policy Pack mới ≤ 5 ngày; thay giao diện tenant (UI Pack) ≤ 1 ngày, không phát hành lại mã |
| NFR-08 | An toàn | Hồ sơ đề xuất cấp độ 4 (NĐ 85/2016, TT 12/2022, TCVN 11930:2017); OWASP ASVS 4.0.3 mức L2, mức L3 cho xác thực, phiên, mật mã |
| NFR-09 | Lưu giữ | Log ứng dụng 90 ngày nóng + 1 năm lạnh; nhật ký kiểm toán và sổ cái ≥ 10 năm (cần đối chiếu quy định lưu trữ hồ sơ cán bộ) |
| NFR-10 | Quan sát | ≥ 99% request có trace end-to-end; mọi API chính có SLI theo Four Golden Signals |
| NFR-11 | Trải nghiệm | WCAG 2.1 AA; chế độ "Đảng viên cao tuổi" (chữ ≥ 17 px, tương phản cao, ít bước); thao tác thường ngày ≤ 3 chạm |

### 3.1. Ước tính tải (Little's Law) – số liệu gốc mà TL-01 và TL-04 dùng chung

- Người dùng đồng thời L = 300.000; thời gian suy nghĩ trung bình giữa hai request của một phiên Z ≈ 20 s ⇒ λ = L / Z ≈ **15.000 request/giây**; hệ số đột biến 2 ⇒ **30.000 request/giây** thiết kế.
- Tỷ lệ đọc/ghi 80/20 ⇒ ghi ≈ 3.000 request/giây (đột biến 6.000); mỗi request ghi sinh trung bình 1,5 sự kiện ⇒ Kafka ≈ 4.500–9.000 sự kiện/giây toàn quốc.
- Phân bổ theo cell: cell lớn nhất (TP.HCM) ≈ 1,2 triệu tài khoản, 60.000 CCU ⇒ ≈ 3.000 request/giây (đột biến 6.000), ghi ≈ 600–1.200 request/giây.
- Luồng duyệt (BPMN): mỗi người ≈ 2 tiến trình/quý (duyệt kế hoạch + hồ sơ đánh giá), ≈ 8 user task ⇒ 12 triệu tiến trình, 48 triệu user task/quý; 30% dồn vào 3 ngày cuối, 8 giờ/ngày, hệ số giờ cao điểm 4 ⇒ ≈ 670 lần hoàn thành user task/giây toàn quốc, ≈ 133 lần/giây ở cell lớn (năng lực thiết kế 150/giây).
- Dung lượng minh chứng: 6 triệu × 4 quý × 8 tệp × 1 MB ≈ 192 TB/năm dữ liệu gốc (trước khử trùng theo SHA-256), Ceph erasure coding 4+2 ⇒ ≈ 290 TB thô/năm.

## 4. Quyết định kiến trúc (ADR) – danh mục chung

| Mã | Quyết định | Ghi chú gắn checklist / góp ý |
|---|---|---|
| ADR-01 | Kiến trúc lai theo từng phần: lõi nghiệp vụ Modular Monolith (Spring Modulith), microservice cho điểm nóng và vùng bảo mật, Cell-based Architecture khi mở rộng, không áp một kiểu cho toàn hệ thống | A1, A2, A3 |
| ADR-02 | PostgreSQL 17 (CloudNativePG trên Kubernetes, node pool CSDL riêng, NVMe cục bộ) thay Oracle cho nền tảng mới; mỗi module/service một schema và một tài khoản CSDL | A2, E2, K1 |
| ADR-03 | Apache Kafka + Transactional Outbox (Debezium Outbox Event Router) + Inbox idempotent; Apicurio Registry quản lý schema sự kiện | A4, F3, K4 |
| ADR-04 | **Phân ba tầng điều phối luồng** (góp ý G2): (1) máy trạng thái khai báo trong mã cho vòng đời khối lượng lớn (nhiệm vụ, minh chứng, check-in) – không qua BPMN; (2) Flowable 7 BPMN cho luồng duyệt của con người, engine đặt riêng theo cell, định nghĩa theo mẫu + tham số chứ không nhân bản theo tenant; (3) saga điều phối cho chốt kỳ, phát hành Policy Pack, các bước nặng chạy bằng external worker | A1, F8, E3 |
| ADR-05 | **Giao diện theo tenant bốn lớp, áp dụng có chọn lọc** (góp ý G1): Design Token theo tenant (áp dụng toàn bộ); SDUI có giới hạn cho bề mặt cấu hình được (biểu mẫu, trang chi tiết, bố cục trang chủ, dashboard) với danh mục component được phép; Dynamic Form = Formly + JSON Schema + CEL (cel-js trên trình duyệt, cel-java trên máy chủ là nguồn sự thật); Micro-frontend plugin (Native Federation) cho module chuyên ngành. UI Pack có phiên bản, tách khỏi phần tính điểm của Policy Pack | A8, K6, L1, NFR-07, NFR-11 |
| ADR-06 | **Phân loại dữ liệu 4 mức và mật mã theo mức** (góp ý G3): C0 công khai; C1 nội bộ (dữ liệu thường); C2 nhạy cảm (dữ liệu cá nhân, nhận xét đánh giá, khiếu nại, góp ý kiểm điểm; điểm số và mức xếp loại là C1 được bảo vệ bằng RLS, ABAC và sổ cái) mã hoá trường bằng envelope encryption; C3 hạn chế/mật mã hoá đầu cuối tại client. Bí mật nhà nước theo Luật Bảo vệ BMNN 2018 chỉ xử lý ở bản cài cách ly với mật mã Ban Cơ yếu | D8, D12, E5, I5 |
| ADR-07 | Cell-based Architecture, ba mô hình thuê bao: silo (cell riêng), bridge (CSDL riêng mỗi tenant trong cụm dùng chung), pool (bảng chung + tenant_id + Row-Level Security) | K5, E2, C7 |
| ADR-08 | Keycloak 26 đặt theo cell, dùng tính năng Organizations (không dùng realm-per-tenant vì không co giãn tới hàng nghìn tenant); liên kết VNeID và IdP khách hàng qua identity brokering; OIDC Authorization Code + PKCE, state, nonce | L2, L3, K2, D2, D5 |
| ADR-09 | Sổ cái toàn vẹn: chuỗi băm phân vùng (64 phân vùng/tenant), niêm phong gốc Merkle mỗi giờ, ký số + dấu thời gian RFC 3161, bản neo gửi bên thứ ba; ghi qua outbox nên không nằm trên đường găng của request | I1, I2, I8, A6 |
| ADR-10 | Web: Angular 21 + Native Federation + PrimeNG (đúng stack đội ngũ, thay cho React nêu ở slide 37); di động: Flutter | A8, L1 |
| ADR-11 | mTLS giữa service bằng Istio ambient mode (ztunnel, không sidecar) để giảm chi phí hiệu năng | D8, C |
| ADR-12 | Quan sát: OpenTelemetry (agent Java, SDK Go/Python) → OTel Collector → Tempo (trace), Prometheus + Thanos (metric), Loki (log), Grafana; Faro cho RUM | B1–B8, G1–G7 |
| ADR-13 | Kho đối tượng Ceph RGW (S3) bật Object Lock chế độ compliance cho minh chứng | E4, I5, C8 |
| ADR-14 | Kho phân tích ClickHouse + lớp ngữ nghĩa Cube + Apache Superset nhúng (CQRS phía đọc) | A5, H7 |
| ADR-15 | Tìm kiếm toàn văn OpenSearch (thay Elasticsearch vì giấy phép) | E6 |
| ADR-16 | Chuỗi cung ứng: Jenkins (CI, giữ theo đội ngũ) + Argo CD (GitOps) + Helm; Harbor; ký image bằng cosign; Kyverno chỉ cho chạy image đã ký; SBOM CycloneDX; Trivy, Semgrep, gitleaks, Dependency-Track | C9, C10, J2, J8, D1, D10 |
| ADR-17 | Bí mật và khoá: OpenBao (nhánh MPL của Vault) + HSM (PKCS#11) cho KEK; External Secrets Operator; không bí mật trong Git | D1, D8 |
| ADR-18 | Cache Valkey (nhánh BSD của Redis) theo cell, khoá có tiền tố `{tenant}:{module}:`, không cache dữ liệu C2/C3 dạng rõ | E5 |
| ADR-19 | Bộ máy quy tắc: CEL (cel-java) cho công thức, DMN 1.4 (Flowable DMN) cho bảng xếp loại, Policy Pack có phiên bản, test case vàng, ký số | H4, H8, I6, J6 |
| ADR-20 | Strangler Fig từ hệ hiện tại: APISIX định tuyến theo đường dẫn giữa vOffice cũ và nền tảng mới; Anti-Corruption Layer trong integration-gateway | A1, K3, K8 |

## 5. Danh mục thành phần (service) – mỗi cell triển khai một bộ, trừ khi ghi "toàn cục"

| Mã | Tên triển khai | Vai trò | Công nghệ | Kiểu | CSDL / lưu trữ sở hữu | Co giãn |
|---|---|---|---|---|---|---|
| SVC-01 | api-gateway | Cổng API, định tuyến strangler, giới hạn tốc độ theo người dùng/tenant/API, WAF (Coraza), xác thực JWT qua JWKS | Apache APISIX 3.x | Hạ tầng | etcd | HPA theo CPU, RPS |
| SVC-02 | iam | Định danh, SSO, MFA, VNeID, IdP khách hàng | Keycloak 26 (+ extension VNeID, Organizations) | Hạ tầng | PostgreSQL schema `iam` (CSDL riêng) | HPA |
| SVC-03 | tenant-registry (toàn cục) | Danh bạ tenant → cell, tên miền, mô hình thuê bao, trạng thái hợp đồng, khám phá IdP | Go 1.23 | Microservice | PostgreSQL toàn cục schema `registry` | HPA |
| SVC-04 | org-service | Cây tổ chức hai trục thời gian, nhân sự, vị trí việc làm, chức danh, kiêm nhiệm; SCIM 2.0; dùng chung với hệ điều hành văn bản | Java 21, Spring Boot 3.5 | Microservice | schema `org` | HPA |
| SVC-05 | mission-core | Lõi nghiệp vụ Modular Monolith gồm 8 module MOD-01…MOD-08 | Java 21, Spring Boot 3.5, Spring Modulith, ArchUnit | Modular Monolith | 8 schema theo module + `org_replica` | HPA theo CPU và độ trễ p95 |
| SVC-06 | workflow-service | Engine luồng duyệt BPMN/DMN tầng 2 và saga tầng 3 | Flowable 7 (embedded trong Spring Boot), external worker | Microservice | CSDL riêng `flowable` | HPA (API) + KEDA (async executor) |
| SVC-07 | scoring-service | Tính điểm KPI, tổng hợp, xếp loại đề xuất theo Policy Pack; tính dần theo sự kiện và tính lại hàng loạt cuối kỳ | Java 21, Spring Boot, cel-java, Flowable DMN | Microservice worker | schema `scoring` | KEDA theo độ trễ consumer Kafka |
| SVC-08 | evidence-service | Lưu trữ minh chứng: tải lên dạng luồng, SHA-256, Object Lock, quét mã độc, gọi OCR, khử trùng | Java 21, Spring Boot WebFlux; Ceph RGW; ClamAV | Microservice | schema `evidence` + bucket S3 | HPA + KEDA (quét/OCR) |
| SVC-09 | ledger-service | Sổ cái chỉ-ghi, chuỗi băm phân vùng, niêm phong Merkle, TSA, công cụ kiểm tra | Go 1.23 | Microservice (vùng bảo mật) | CSDL riêng `ledger` (cụm riêng) | Số replica = số phân vùng consumer |
| SVC-10 | esign-service | Ký số tại mốc pháp lý; bộ kết nối MySign, Viettel-CA, CA chuyên dùng Chính phủ, Ban Cơ yếu; ký lô | Java 21, Spring Boot; PKCS#11/HSM | Microservice (vùng bảo mật) | schema `esign` | HPA + KEDA |
| SVC-11 | document-service | Sinh Mẫu 1A–1D, 2, 3, bảng chấm điểm, gói hồ sơ PDF/A | Java 21, docx4j, OpenPDF, Gotenberg | Microservice worker | schema `docgen` + bucket | KEDA |
| SVC-12 | notification-service | Thông báo đẩy, SMS, email, in-app; SSE/WebSocket thời gian thực | Go 1.23 | Microservice | schema `notify` + Valkey Streams | HPA + KEDA |
| SVC-13 | integration-gateway | Cổng tích hợp và Anti-Corruption Layer: vOffice, iOffice, EdXML/X-Road, LGSP/NDXP, CSDL CBCCVC, CSDL đảng viên, HRM; OpenAPI, AsyncAPI, CloudEvents | Java 21, Spring Boot, Apache Camel | Microservice | schema `integ` | HPA |
| SVC-14 | ai-gateway | Cổng LLM chỉ đọc, RAG, OCR, gắn nhãn AI, rào chắn | Python 3.12, FastAPI; vLLM; pgvector | Microservice (vùng bảo mật, GPU) | schema `ai` | HPA theo hàng đợi suy luận |
| SVC-15 | analytics | CQRS phía đọc: CDC → ClickHouse, Cube, Superset nhúng, analytics-api; job phát hiện 8 dấu hiệu bất thường | ClickHouse, Cube, Superset, Python | Nền tảng dữ liệu | ClickHouse `analytics_<cell>` | Theo shard ClickHouse |
| SVC-16 | search | Tìm kiếm toàn văn nhiệm vụ, văn bản, danh mục (không chứa C2/C3) | OpenSearch 2.x + indexer (Kafka consumer) | Hạ tầng | Chỉ mục OpenSearch | Theo shard |
| SVC-17 | web-frontend | Shell Angular 21 + remote Native Federation + plugin; phục vụ tĩnh qua CDN/nginx | Angular 21, PrimeNG, Formly, cel-js | Frontend | — | CDN |
| SVC-18 | mobile-app | Ứng dụng Flutter cho cán bộ | Flutter 3 | Frontend | SQLite cục bộ (bản nháp, mã hoá) | — |

Hạ tầng dùng chung trong cell (không phải service nghiệp vụ): Kafka (KRaft) + Apicurio Registry + Debezium (Kafka Connect), PostgreSQL (CloudNativePG) + PgBouncer, Valkey Cluster, Ceph RGW, OpenSearch, ClickHouse, OpenBao, Istio ambient, OTel Collector, Fluent Bit.

### 5.1. Các module của mission-core (SVC-05)

| Mã | Module (gói Java) | Nhóm tính năng BGĐ | Schema | Trách nhiệm chính |
|---|---|---|---|---|
| MOD-01 | `okr` | 1. Mục tiêu và OKR | `okr` | Căn cứ, mục tiêu O, KR, phân rã ba tầng, liên kết ngang, check-in, mức tự tin |
| MOD-02 | `task` | 2. Giao việc và KPI | `task` | Danh mục công việc (Bảng 3), kế hoạch quý có phiên bản (Bảng 1), nhiệm vụ, chủ trì/phối hợp, tiến độ, điểm nghẽn, gia hạn |
| MOD-03 | `product` | 3. Sản phẩm, minh chứng | `product` | Sản phẩm của nhiệm vụ, phiên bản minh chứng (tham chiếu đối tượng ở SVC-08), nghiệm thu chất lượng 5 mức |
| MOD-04 | `appraisal` | 4. Đánh giá, xếp loại | `appraisal` | Hồ sơ đánh giá theo luồng Đảng/Nhà nước, tiêu chí chung, 4 mức đề xuất, giải trình, xếp loại năm, phân nhóm triển vọng |
| MOD-05 | `policy` | 8. Quản trị (Policy Pack) | `policy` | Kho Policy Pack, phiên bản, test case vàng, phê duyệt phát hành, cung cấp gói cho SVC-07 |
| MOD-06 | `dossier` | 5. Kiểm điểm, hồ sơ số | `dossier` | Hội nghị kiểm điểm, góp ý, biên bản, gói hồ sơ quý/năm, 13 trường hợp đặc thù |
| MOD-07 | `oversight` | 6. Giám sát, hậu kiểm | `oversight` | Khiếu nại, giải trình, hậu kiểm, mở lại kỳ hai người duyệt, xử lý cảnh báo bất thường |
| MOD-08 | `config` | 8. Quản trị nền tảng | `config` | Cấu hình tenant, kỳ đánh giá, lịch ngày nghỉ, UI Pack, biểu mẫu động, cờ tính năng |

Nhóm 7 (Phân tích, dashboard) nằm ở SVC-15; trợ lý AI ở SVC-14. Module giao tiếp nội bộ qua interface công bố (`*.api` package) hoặc sự kiện ứng dụng Spring Modulith được xuất ra Kafka qua outbox; ArchUnit chặn truy cập chéo package `internal`.

### 5.2. Frontend

Shell `fe-shell` (Angular 21) nạp remote theo manifest của tenant: `fe-okr`, `fe-task`, `fe-evidence`, `fe-appraisal`, `fe-dossier`, `fe-oversight`, `fe-dashboard`, `fe-config-studio`, `fe-admin`; plugin chuyên ngành: `fe-plugin-yte`, `fe-plugin-giaoduc`, `fe-plugin-llvt`. Thư viện dùng chung: `@vt/design-system` (preset PrimeNG + token), `@vt/sdui-renderer`, `@vt/form-engine` (Formly + JSON Schema + cel-js), `@vt/auth` (OIDC PKCE), `@vt/telemetry` (OTel web + Faro).

## 6. Mô hình cell và thuê bao

| Mã cell | Phạm vi | Mô hình thuê bao | Quy mô dự kiến |
|---|---|---|---|
| CELL-DTW | Đảng ủy các cơ quan Đảng Trung ương (19 đảng bộ) | silo, hạ tầng riêng khối Đảng, HSM riêng | ≈ 0,3 triệu tài khoản |
| CELL-HCM | TP. Hồ Chí Minh | silo | ≈ 1,2 triệu |
| CELL-HN | Hà Nội (HanoiWork) | silo | ≈ 1,0 triệu |
| CELL-G1…G4 | Nhóm tỉnh (mỗi cell 7–9 tỉnh) | bridge: CSDL riêng mỗi tỉnh trong cụm PostgreSQL của cell | ≈ 0,6–0,8 triệu mỗi cell |
| CELL-SH | Xã, chi bộ, đơn vị nhỏ, doanh nghiệp | pool: bảng chung + `tenant_id` + RLS | ≈ 0,8 triệu, tới hàng nghìn tenant |
| Bản cách ly | LLVT, Công an | cell đơn đóng gói Helm ngoại tuyến | theo đơn vị |

Hai trung tâm dữ liệu: chính tại Hoà Lạc, dự phòng phía Nam. Vùng điều phối toàn cục (global control plane) gồm tenant-registry (SVC-03), bộ định tuyến toàn cục (APISIX lớp ngoài + GeoDNS), kho Policy Pack/UI Pack gốc (đồng bộ xuống cell), CDN tài nguyên tĩnh, kho phân tích quốc gia chỉ nhận dữ liệu tổng hợp.

## 7. Danh mục sự kiện (EVT)

Quy ước: topic `<miền>.<thực-thể>.<sự-kiện>.v1` trong Kafka của từng cell; khoá thông điệp `tenantId:aggregateId`; nội bộ dùng Avro qua Apicurio, ra ngoài qua SVC-13 dùng CloudEvents JSON; mọi sự kiện mang `eventId` (UUIDv7), `tenantId`, `occurredAt`, `actorId`, `traceparent`, `policyVersion` (nếu có), `classification`.

| Mã | Tên sự kiện (tên nghiệp vụ) | Topic | Phát bởi | Tiêu thụ chính |
|---|---|---|---|---|
| EVT-01 | MucTieuDaDuyet | okr.objective.approved.v1 | MOD-01 | SVC-15, SVC-09, MOD-02 |
| EVT-02 | KrDaCheckIn | okr.keyresult.checkedin.v1 | MOD-01 | SVC-15, SVC-12, SVC-09 |
| EVT-03 | KeHoachDaDuyet | task.plan.approved.v1 | MOD-02 | SVC-09, SVC-15, SVC-12 |
| EVT-04 | NhiemVuDaGiao | task.task.assigned.v1 | MOD-02 | SVC-12, SVC-15, SVC-16, SVC-09 |
| EVT-05 | TienDoDaCapNhat | task.task.progressed.v1 | MOD-02 | SVC-15, SVC-09 |
| EVT-06 | TrangThaiNhiemVuDoi | task.task.statuschanged.v1 | MOD-02 | SVC-07, SVC-12, SVC-15 |
| EVT-07 | MinhChungDaNop | product.evidence.submitted.v1 | MOD-03 | SVC-12, SVC-15, SVC-09 |
| EVT-08 | MinhChungDaXacNhan | product.evidence.accepted.v1 | MOD-03 | SVC-07 (tính dần), SVC-09, SVC-15 |
| EVT-09 | KyDaKhoa | config.period.locked.v1 | MOD-08 (qua saga SVC-06) | SVC-07, MOD-04, SVC-09, SVC-12 |
| EVT-10 | DiemDaTinh | scoring.score.computed.v1 | SVC-07 | MOD-04, SVC-15 |
| EVT-11 | HoSoDanhGiaChuyenBuoc | appraisal.profile.stepcompleted.v1 | MOD-04 | SVC-12, SVC-09, SVC-15 |
| EVT-12 | XepLoaiDaQuyetDinh | appraisal.rating.decided.v1 | MOD-04 | MOD-06, SVC-09, SVC-13, SVC-15 |
| EVT-13 | PolicyPackDaPhatHanh | policy.pack.published.v1 | MOD-05 | SVC-07, SVC-06, SVC-17 (UI), SVC-09 |
| EVT-14 | DonViDaThayDoi | org.unit.changed.v1 | SVC-04 | MOD-* (`org_replica`), SVC-02, SVC-15 |
| EVT-15 | PhanCongDaThayDoi | org.assignment.changed.v1 | SVC-04 | MOD-02, MOD-04, `org_replica` |
| EVT-16 | NhanSuDaThayDoi | org.person.changed.v1 | SVC-04 | SVC-02, `org_replica` |
| EVT-17 | HoSoSoDaSinh | dossier.form.generated.v1 | SVC-11 | MOD-06, SVC-10 |
| EVT-18 | KySoDaHoanTat | esign.signature.completed.v1 | SVC-10 | MOD-04, MOD-06, MOD-02, SVC-09 |
| EVT-19 | SoCaiDaNiemPhong | ledger.seal.created.v1 | SVC-09 | SVC-13 (gửi bản neo) |
| EVT-20 | DauHieuBatThuong | analytics.anomaly.detected.v1 | SVC-15 | MOD-07, SVC-12 |
| EVT-21 | YeuCauThongBao | notify.message.requested.v1 | mọi module | SVC-12 |
| EVT-22 | KyDaMoLaiHauKiem | oversight.period.reopened.v1 | MOD-07 | MOD-08, SVC-07, SVC-09 |
| EVT-23 | UiPackDaPhatHanh | config.uipack.published.v1 | MOD-08 | SVC-17 (vô hiệu cache CDN) |
| EVT-24 | VanBanChiDaoDaNhan | integ.document.received.v1 | SVC-13 | MOD-02, SVC-14 |
| EVT-25 | KhieuNaiDaGui | oversight.complaint.filed.v1 | MOD-07 | SVC-06, SVC-12, SVC-09 |
| EVT-26 | KyDaHuyKhoa (saga bù trừ về MO) | config.period.unlocked.v1 | MOD-08 (qua saga SVC-06) | SVC-07, MOD-04, SVC-09, SVC-12 |

## 8. Cơ chế đồng bộ dữ liệu (dùng thống nhất)

1. **Transactional Outbox + Debezium**: mỗi schema có bảng `outbox_event`; ghi dữ liệu và sự kiện trong cùng giao dịch; Debezium đọc WAL và đẩy lên topic. Đảm bảo at-least-once.
2. **Inbox idempotent**: mỗi consumer có bảng `inbox_processed(event_id, consumer, processed_at)`; khoá chính chống xử lý trùng.
3. **Event-carried State Transfer**: dữ liệu tổ chức/nhân sự từ SVC-04 được nhân bản vào schema `org_replica` của mission-core và các service cần đọc; không gọi đồng bộ để đọc dữ liệu tổ chức trên đường găng.
4. **CDC phân tích**: Debezium → Kafka → ClickHouse (Kafka engine + materialized view); chỉ cột C0/C1 và cột C2 đã giả danh hoá.
5. **Saga điều phối** (SVC-06): chốt kỳ, phát hành Policy Pack, ký lô; mỗi bước có hành động bù trừ.
6. **REST đồng bộ có Resilience4j** chỉ cho truy vấn cần dữ liệu tức thời giữa service (timeout 3 s, circuit breaker, bulkhead).
7. **Giữa các cell**: không chia sẻ CSDL; chỉ (a) đồng bộ danh bạ tenant toàn cục, (b) MirrorMaker 2 đẩy sự kiện tổng hợp/ẩn danh lên kho phân tích quốc gia, (c) điều động cán bộ liên cell qua gói chuyển hồ sơ có ký số do SVC-13 xử lý.
8. **Với hệ ngoài** (vOffice, iOffice, CSDL CBCCVC…): chỉ qua SVC-13 (ACL), dùng bảng `integ.mapping_xref` ánh xạ mã ngoài ↔ mã nội bộ.

## 9. Dữ liệu cốt lõi theo schema (tên bảng chuẩn – TL-02 tham chiếu, TL-03 chi tiết hoá)

Cột chung mọi bảng nghiệp vụ: `id uuid` (UUIDv7), `tenant_id uuid`, `created_at`, `created_by`, `updated_at`, `updated_by`, `row_version int` (optimistic lock). Cột C2 mã hoá có hậu tố `_enc bytea` kèm `dek_id`. Bảng phân vùng theo kỳ có `period_id`.

- **registry** (toàn cục): `tenant`, `cell`, `tenant_cell`, `tenant_domain`, `tenant_idp`.
- **iam**: bảng Keycloak (không sửa).
- **org** (SVC-04, hai trục thời gian `valid_from/valid_to`, `recorded_from/recorded_to`): `org_unit`, `org_unit_relation`, `person`, `position` (vị trí việc làm), `job_title`, `assignment` (bổ nhiệm, kiêm nhiệm, điều động), `subject_group` (9 khối đối tượng PL 2A), `scim_sync_log`.
- **org_replica** (trong mission-core): `org_unit_v`, `person_v`, `assignment_v`.
- **config** (MOD-08): `tenant_setting`, `period` (kỳ đánh giá), `holiday_calendar`, `ui_pack`, `ui_pack_version`, `form_schema`, `feature_flag`, `role_binding`.
- **policy** (MOD-05): `policy_pack`, `policy_pack_version`, `policy_rule`, `decision_table`, `golden_test_case`, `test_run`, `policy_binding` (gắn phiên bản cho tenant/khối/kỳ).
- **okr** (MOD-01): `basis` (căn cứ), `objective`, `key_result`, `okr_link`, `kr_checkin`.
- **task** (MOD-02): `work_catalog`, `catalog_item`, `plan`, `plan_version`, `plan_item`, `task`, `task_assignee`, `task_progress`, `task_blocker`, `task_change_request`, `task_source_doc`, `task_state_log`.
- **product** (MOD-03): `deliverable`, `evidence`, `evidence_version`, `acceptance`.
- **appraisal** (MOD-04): `appraisal_profile`, `general_criteria_result`, `self_assessment`, `score_snapshot`, `rating_proposal`, `rating_explanation`, `annual_rating`, `prospect_group`.
- **dossier** (MOD-06): `review_meeting`, `review_comment`, `dossier_package`, `form_instance`, `special_case`.
- **oversight** (MOD-07): `complaint`, `complaint_response`, `reopen_request`, `reopen_approval`, `post_audit`, `anomaly_alert`, `alert_action`.
- **flowable** (SVC-06): bảng `ACT_*` của Flowable + `wf_template`, `wf_binding`, `wf_task_projection`.
- **scoring** (SVC-07): `score_job`, `score_result`, `score_trace`.
- **evidence** (SVC-08): `object_meta`, `object_version`, `dedup_index`, `scan_result`.
- **ledger** (SVC-09): `ledger_entry`, `merkle_seal`, `anchor_receipt`, `verify_run`.
- **esign** (SVC-10): `sign_request`, `sign_item`, `certificate_ref`.
- **docgen** (SVC-11): `template`, `render_job`, `render_output`.
- **notify** (SVC-12): `notification`, `delivery`, `channel_pref`, `template`.
- **integ** (SVC-13): `connector`, `inbox_message`, `mapping_xref`, `sync_cursor`, `dead_letter`.
- **ai** (SVC-14): `doc_chunk` (pgvector), `suggestion_log`, `guardrail_event`.
- Mọi schema có `outbox_event` và `inbox_processed`.

### 9.1. Trạng thái chuẩn

- Kỳ đánh giá `config.period.status`: `MO` → `DANG_KHOA` → `DA_KHOA` → (`MO_LAI_HAU_KIEM` → `DA_KHOA`).
- Kế hoạch `task.plan_version.status`: `NHAP` → `CHO_DUYET` → `DA_DUYET` | `TRA_LAI`; điều chỉnh tạo phiên bản mới.
- Nhiệm vụ `task.task.status`: `NHAP` → `DA_GIAO` → `DANG_THUC_HIEN` → `CHO_NGHIEM_THU` → `HOAN_THANH`; nhánh `TAM_DUNG`, `HUY`; cờ `qua_han`.
- Minh chứng `product.evidence_version.status`: `NHAP` → `DA_NOP` → `DANG_NGHIEM_THU` → `DA_XAC_NHAN` | `YEU_CAU_BO_SUNG` | `TU_CHOI`. Chỉ `DA_XAC_NHAN` được tính điểm.
- Mục tiêu `okr.objective.status`: `NHAP` → `CHO_DUYET` → `DA_DUYET` → `DONG`.
- Hồ sơ đánh giá `appraisal.appraisal_profile.status`: `CHUA_LAP` → `TU_DANH_GIA` → `CHO_THAM_DINH` → `DA_THAM_DINH` → `CHO_QUYET_DINH` → `DA_QUYET_DINH` → `DA_KY_SO` → `DA_KHOA`; nhánh `KHIEU_NAI`.
- Policy Pack `policy.policy_pack_version.status`: `NHAP` → `DANG_KIEM_THU` → `DAT_KIEM_THU` → `CHO_PHE_DUYET` → `DA_PHAT_HANH` → `NGUNG`.
- Mức xếp loại: `HTXSNV`, `HTTNV`, `HTNV`, `KHTNV`. Mức chất lượng nghiệm thu: 1 / 0,75 / 0,5 / 0,25 / 0. Luồng: `DANG` (100 điểm) và `NHA_NUOC` (30/70).

## 10. Tác nhân (AC)

| Mã | Tác nhân | Vai trò hệ thống (Keycloak/OPA) |
|---|---|---|
| AC-01 | Quản trị nền tảng (Viettel) | `platform_admin` |
| AC-02 | Quản trị tenant | `tenant_admin` |
| AC-03 | Quản trị nghiệp vụ / BA cấu hình (cơ quan tổ chức) | `policy_author` |
| AC-04 | Lãnh đạo, người đứng đầu | `leader` |
| AC-05 | Cán bộ quản lý trực tiếp (giao việc, nghiệm thu) | `manager` |
| AC-06 | Cán bộ, công chức, viên chức, đảng viên (người thực hiện) | `member` |
| AC-07 | Cơ quan tổ chức cán bộ (thẩm định) | `appraiser` |
| AC-08 | Cấp có thẩm quyền quyết định | `decider` |
| AC-09 | Người duyệt thứ hai (four-eyes) | `second_approver` |
| AC-10 | Cơ quan kiểm tra, giám sát | `inspector` |
| AC-11 | Hệ thống ngoài (vOffice, iOffice, CSDL CBCCVC, VNeID, MySign…) | client credentials |
| AC-12 | Trợ lý AI (chỉ đọc, không có quyền ghi) | `ai_readonly` |

Phân quyền: RBAC theo vai trò + ABAC theo cây tổ chức (đơn vị, cấp dưới), luồng (`DANG`/`NHA_NUOC`), lớp dữ liệu (C0–C3) qua Open Policy Agent; tách vai trò theo PL 4 V.1 (khởi tạo ≠ nghiệm thu ≠ thẩm định ≠ quyết định ≠ khoá kỳ đối với cùng một hồ sơ).

## 11. Danh mục quy trình (QT) và màn hình (SCR)

Tài liệu TL-02a phụ trách QT-00 → QT-08; TL-02b phụ trách QT-09 → QT-18.

| QT | Tên quy trình | Màn hình |
|---|---|---|
| QT-00 | Đăng nhập, chọn đơn vị và vai trò | SCR-0001 Đăng nhập (SSO, VNeID, MFA); SCR-0002 Trang chủ "Việc của tôi hôm nay" |
| QT-01 | Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự | SCR-0101 Danh sách tenant; SCR-0102 Khởi tạo tenant (wizard); SCR-0103 Cây tổ chức có lịch sử; SCR-0104 Đồng bộ và đối soát nhân sự |
| QT-02 | Lập và phê duyệt mục tiêu, OKR | SCR-0201 Cây mục tiêu OKR; SCR-0202 Tạo/sửa O và KR; SCR-0203 Phê duyệt OKR |
| QT-03 | Check-in KR | SCR-0301 Check-in KR (desktop); SCR-0302 Check-in trên di động |
| QT-04 | Lập và phê duyệt kế hoạch công tác quý | SCR-0401 Thư viện danh mục công việc; SCR-0402 Lập kế hoạch quý; SCR-0403 Phê duyệt kế hoạch (so sánh phiên bản) |
| QT-05 | Giao việc, đăng ký nhiệm vụ, nhận nhiệm vụ từ văn bản | SCR-0501 Giao việc; SCR-0502 Tạo nhiệm vụ từ văn bản chỉ đạo; SCR-0503 Việc của tôi (desktop); SCR-0504 Việc của tôi (di động) |
| QT-06 | Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn/điều chỉnh | SCR-0601 Chi tiết nhiệm vụ; SCR-0602 Đề nghị gia hạn, điều chỉnh |
| QT-07 | Nộp và nghiệm thu sản phẩm, minh chứng | SCR-0701 Nộp minh chứng; SCR-0702 Nộp minh chứng trên di động (OCR); SCR-0703 Nghiệm thu chất lượng 5 mức |
| QT-08 | Khoá kỳ (saga chốt kỳ) | SCR-0801 Quản lý kỳ và tiến trình khoá kỳ |
| QT-09 | Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý | SCR-0901 Phiếu tự đánh giá; SCR-0902 Thẩm định; SCR-0903 Phiếu xếp loại có giải thích; SCR-0904 Quyết định xếp loại hàng loạt và ký số |
| QT-10 | Xếp loại năm, liên thông quý–năm, phân nhóm triển vọng | SCR-1001 Xếp loại năm; SCR-1002 Phân nhóm triển vọng |
| QT-11 | Kiểm điểm và hồ sơ số | SCR-1101 Hội nghị kiểm điểm; SCR-1102 Gói hồ sơ số và ký số |
| QT-12 | Khiếu nại, giải trình | SCR-1201 Gửi khiếu nại; SCR-1202 Xử lý khiếu nại |
| QT-13 | Hậu kiểm, mở lại kỳ (hai người duyệt) | SCR-1301 Yêu cầu mở lại kỳ; SCR-1302 Tra cứu sổ cái và kiểm tra toàn vẹn |
| QT-14 | Cảnh báo bất thường | SCR-1401 Trung tâm cảnh báo |
| QT-15 | Soạn, kiểm thử và phát hành Policy Pack | SCR-1501 Config Studio – công thức; SCR-1502 Bảng quyết định xếp loại; SCR-1503 Chạy test case vàng; SCR-1504 Cấu hình luồng duyệt (mẫu + tham số) |
| QT-16 | Bảng điều hành và báo cáo | SCR-1601 Bảng điều hành lãnh đạo; SCR-1602 Trình dựng dashboard; SCR-1603 Đi xuống tới minh chứng |
| QT-17 | Trợ lý AI | SCR-1701 Trợ lý AI soạn OKR và soát KR |
| QT-18 | Cấu hình giao diện tenant (UI Pack) | SCR-1801 Cấu hình giao diện tenant; SCR-1802 Trình dựng biểu mẫu động; SCR-1803 Chế độ Đảng viên cao tuổi (di động) |

## 12. Dải mã cấp cho từng tài liệu

- TL-01: ADR-01…ADR-40, SEC-xx không dùng (để TL-04), API theo quy ước `/api/v1/<module>/...`.
- TL-02a: BR-00-xx … BR-08-xx, MSG-00-xx … MSG-08-xx. TL-02b: BR-09-xx … BR-18-xx, MSG-09-xx … MSG-18-xx.
- TL-03: tên bảng như mục 9 (được thêm bảng mới, không đổi tên bảng đã có); chỉ mục, phân vùng, RLS.
- TL-04: SEC-01…SEC-99 (biện pháp ATTT), OPS-01…OPS-99 (quy trình vận hành), PERF-01…PERF-99 (kịch bản thử tải), DR-01…DR-20 (kịch bản khôi phục).

## 13. Quy ước API

REST JSON, OpenAPI 3.1, tiền tố `/api/v1/<module>`: `okr`, `tasks`, `plans`, `catalog`, `products`, `evidences`, `appraisals`, `policies`, `dossiers`, `oversight`, `config`, `periods`, `org`, `files`, `ledger`, `esign`, `notifications`, `analytics`, `ai`, `ui`. Ghi có header `Idempotency-Key`; cập nhật có `If-Match` (ETag = row_version) → 409 khi xung đột; danh sách phân trang theo con trỏ, `limit` tối đa 200; lỗi theo RFC 9457 (Problem Details); tác vụ dài trả 202 + `Location: /api/v1/jobs/{id}`.

## 14. Lập trường thiết kế đã chốt cho ba góp ý G1, G2, G3

### G1. Giao diện riêng theo tenant (ADR-05)
Kết luận: **áp dụng được, nhưng có chọn lọc theo lớp**, không biến toàn bộ frontend thành "render engine thuần tuý".
- Lớp 1 Design Token theo tenant: áp dụng toàn bộ. Token (màu, chữ, khoảng cách, bo góc, biểu tượng, logo) lưu trong `config.ui_pack_version` dạng JSON theo chuẩn W3C Design Tokens, biên dịch thành CSS custom properties + preset PrimeNG; phục vụ qua CDN với ETag theo phiên bản; chế độ "Đảng viên cao tuổi" là một bộ token phụ (chữ lớn, tương phản cao) cộng cờ "ít bước" bật luồng rút gọn.
- Lớp 2 SDUI: chỉ áp dụng cho "bề mặt cấu hình được": bố cục trang chủ và widget, trang chi tiết hồ sơ/nhiệm vụ (nhóm trường, thứ tự tab), biểu mẫu nghiệp vụ, bố cục dashboard. Màn hình tương tác nặng (cây OKR, Config Studio, thẩm định so sánh, bảng điều hành có drill-down) vẫn viết mã, chỉ nhận token và cấu hình nhẹ. Schema bố cục chỉ được tham chiếu component trong danh mục cho phép (component registry có phiên bản), không chứa HTML/JS tuỳ ý. Lý do: kiểm thử, khả năng truy cập, hiệu năng và an toàn (XSS) của màn hình nghiệp vụ lõi không được phụ thuộc vào cấu hình của từng tenant.
- Lớp 3 Dynamic Form: Formly (Angular) + JSON Schema; biểu thức CEL trong Policy Pack/UI Pack được kiểm tra trên trình duyệt bằng thư viện cel-js (thông dịch JavaScript, không cần biên dịch sang Wasm vì biểu thức kiểm tra biểu mẫu ngắn, < 0,1 ms/biểu thức); máy chủ luôn kiểm tra lại bằng cel-java và là nguồn sự thật. Bộ test case dùng chung chạy trên cả hai engine trong CI để bảo đảm cùng kết quả.
- Lớp 4 Micro-frontend plugin: Native Federation; plugin chuyên ngành (Y tế, Giáo dục, LLVT) được nạp theo manifest của tenant, image/bundle được ký và quét như service; plugin chỉ gọi API công bố.
- UI Pack có phiên bản riêng, gắn với Policy Pack bằng tham chiếu (phiên bản biểu mẫu phải khớp phiên bản quy tắc), nhưng thay đổi thuần giao diện không phải chạy lại test case tính điểm; phát hành ≤ 1 ngày (NFR-07).
- Ngân sách hiệu năng: shell + design system ≤ 250 KB gzip; tải UI Pack ≤ 30 KB, được cache ở CDN và IndexedDB; thời gian dựng biểu mẫu 50 trường ≤ 50 ms.

### G2. Nghẽn engine BPMN khi hàng trăm, hàng nghìn tenant (ADR-04)
Kết luận: **engine chỉ thành nút thắt khi bị dùng sai chỗ**; thiết kế tránh bằng ba biện pháp:
1. Không đưa vòng đời khối lượng lớn vào BPMN. Nhiệm vụ, minh chứng, check-in (hàng chục nghìn thay đổi/giây lúc cao điểm) dùng máy trạng thái khai báo trong mission-core (bảng chuyển trạng thái do Policy Pack tham số hoá, lưu `task.task_state_log`). BPMN chỉ dùng cho luồng duyệt của con người (kế hoạch, OKR, hồ sơ đánh giá, khiếu nại, mở lại kỳ, phát hành Policy Pack) ≈ 670 user task/giây toàn quốc, ≈ 133/giây ở cell lớn nhất (thiết kế 150/giây).
2. Không có "một engine cho cả nước": mỗi cell một workflow-service với CSDL riêng; trong cell, engine chạy nhiều replica không trạng thái, async executor co giãn bằng KEDA; lịch sử ghi ở mức `audit` và chuyển bất đồng bộ sang ClickHouse; bảng runtime chỉ chứa tiến trình đang chạy.
3. Chống "mạng nhện": định nghĩa luồng là **mẫu (template) + tham số**, không nhân bản BPMN theo tenant. Số định nghĩa = số mẫu × số phiên bản (≈ 20–40 mẫu), không tỷ lệ với số tenant. Cấp duyệt, người duyệt, thời hạn lấy từ DMN/tham số `flowable.wf_binding` theo tenant/khối. Quy tắc quản trị: tối đa 1 tầng call activity, không gọi tiến trình chéo tenant, không gọi đồng bộ dịch vụ ngoài trong service task (dùng external worker + sự kiện), mỗi mẫu có bộ kiểm thử và linter.
Phương án thay thế đã cân nhắc: Camunda 8/Zeebe (giấy phép thương mại từ bản 8.6 cho môi trường sản xuất), Temporal (mạnh cho saga kỹ thuật nhưng không có ký pháp BPMN cho nghiệp vụ đọc), Netflix Conductor (OSS duy trì cộng đồng). Chọn Flowable 7 (Apache 2.0) nhúng trong Spring Boot.

### G3. Hiệu năng của 7 lớp bảo vệ và mã hoá dữ liệu mật (ADR-06, ADR-09)
Nguyên tắc: **lớp bảo vệ nào có chi phí lớn thì đưa ra khỏi đường găng của request hoặc chỉ chạy ở mốc pháp lý tần suất thấp**.
| Lớp | Đặt ở đâu | Chi phí ước tính |
|---|---|---|
| 1. Nhật ký chuỗi băm | Bất đồng bộ qua outbox → ledger-service; 64 phân vùng chuỗi/tenant; niêm phong Merkle mỗi giờ | +0,2–0,5 ms cho một dòng outbox trong giao dịch; SHA-256 trên bản ghi 1 KB ≈ 2 µs |
| 2. Ký số mốc pháp lý | Chỉ ở 4 mốc (duyệt kế hoạch, tự đánh giá, thẩm định, quyết định); bất đồng bộ, ký lô | 1–3 s mỗi lần ký từ xa nhưng ngoài đường găng; ≈ 0,1 lần ký/người/tuần |
| 3. Minh chứng chỉ-ghi | SHA-256 tính dạng luồng khi tải lên; Object Lock | ≈ 15–25 ms cho tệp 10 MB; quét mã độc bất đồng bộ |
| 4. Hai trục thời gian | Chỉ bảng tổ chức/phân công (tần suất ghi thấp) | ghi gấp ≈ 2 lần trên tập dữ liệu nhỏ |
| 5. Khoá kỳ, hai người duyệt | Kiểm tra trạng thái kỳ trong bộ nhớ đệm khi ghi | < 0,1 ms |
| 6. Chống làm thay | MFA bước lên (step-up) chỉ ở thao tác quan trọng; phát hiện đăng nhập bất thường bất đồng bộ | không tác động request thường |
| 7. Tách quyền kỹ thuật | Mã hoá trường C2 bằng AES-256-GCM với DEK lưu đệm 15 phút; HSM chỉ để mở DEK | ≈ 1–3 µs/trường; gọi HSM ≈ 5 ms nhưng < 1 lần/15 phút/tenant/pod |
Mã hoá theo lớp dữ liệu: C1 chỉ mã hoá ổ đĩa (LUKS/Ceph) + TLS 1.3/mTLS; C2 thêm mã hoá trường (envelope encryption, KEK trong HSM qua OpenBao Transit, DEK theo tenant theo tháng) cho số định danh, ngày sinh, nhận xét đánh giá, nội dung khiếu nại; điểm số và mức xếp loại không mã hoá trường (để tính toán, phân tích) mà được bảo vệ bằng RLS + ABAC + sổ cái; C3 mã hoá đầu cuối tại client (WebCrypto, AES-256-GCM cho nội dung, khoá nội dung bọc bằng khoá công khai ECDH P-256/RSA-OAEP của từng người nhận lấy từ chứng thư), máy chủ chỉ lưu bản mã, không tìm kiếm toàn văn, không đưa vào phân tích hay AI, chỉ có chỉ mục mù (HMAC) trên vài trường mô tả. Bản quốc phòng thay bộ mật mã bằng thuật toán của Ban Cơ yếu qua lớp trừu tượng `CryptoProvider`.
Ngân sách độ trễ phía máy chủ cho thao tác "cập nhật tiến độ" (p95): cổng API + WAF 3 ms, xác thực JWT 0,3 ms, OPA 0,5 ms, mTLS ztunnel 0,5 ms, logic 10 ms, giao dịch CSDL + outbox 8 ms, mã hoá trường 0,05 ms ⇒ ≈ 25 ms, còn dư nhiều so với NFR-03 800 ms. Đây là ước tính thiết kế; TL-04 đặt kịch bản PERF đo thực tế trên môi trường thử.

## 15. Bổ sung sau triển khai đợt 1 (23/10/2026)

Mục này được thêm khi rà soát bộ tài liệu theo thực tế triển khai trên nhánh `main`. Các mục 1 đến 14 ở trên giữ nguyên; mục này chỉ liệt kê những mã đã được cấp thêm trong các tài liệu bổ sung và trong quá trình triển khai, cùng các con số đã được hiểu thống nhất. Không mã nào ở đây thay nghĩa mã cũ. Chi tiết của từng điểm lệch nằm ở phụ lục "Cập nhật sau triển khai đợt 1" của từng TL.

### 15.1. Quyết định kiến trúc bổ sung

| Mã | Quyết định | Nguồn |
|---|---|---|
| ADR-32 | Hợp đồng API trước: mọi API công khai dưới `/api/v1` mô tả trước bằng OpenAPI 3.1 trong `contracts/openapi/`; hai phía sinh mã từ hợp đồng; phần mở rộng `x-mission` để truy vết | BS-02, TL-01 Phụ lục A và F |
| ADR-33 | Hồ sơ tập khách hàng trên một lõi chung: lõi cung cấp đại lượng thô qua sáu điểm cắm DC-01 đến DC-06, khác biệt giữa các tập khách hàng nằm trong cấu hình có phiên bản | BS-03, TL-01 Phụ lục A và G |

### 15.2. Sự kiện, màn hình, quy tắc bổ sung

| Mã | Nội dung | Nguồn |
|---|---|---|
| EVT-27 | VaiTroDaThayDoi, topic `config.access.changed.v1`, MOD-08 phát, MOD-08, SVC-02 và OPA tiêu thụ | `contracts/events/catalog.yaml`, WEB-058 |
| SCR-0105 đến SCR-0107 | Vai trò theo đơn vị; hồ sơ quyền của một người; uỷ quyền khi vắng mặt | `docs/phan-quyen/ma-tran.json` |
| BR-01-13 đến BR-01-18, MSG-01-16 đến MSG-01-18 | Quy tắc và thông điệp của ba màn hình trên | `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md` |
| SCR-1505, SCR-1506 | Thư viện, trình soạn mẫu luồng và giám sát tiến trình theo mẫu (nội bộ nền tảng); vai trò `wf_template_author` | BS-01, TL-01 Phụ lục E |

Danh mục sự kiện ở mục 7 có thêm hai người tiêu thụ theo mã hiện tại: SVC-16 tiêu thụ EVT-06 và EVT-24 để dựng chỉ mục tìm kiếm (theo mã, chờ xác nhận).

### 15.3. Con số và quy ước được hiểu thống nhất

Quy mô định cỡ dùng trong kế hoạch triển khai là 6,5 triệu tài khoản và 300.000 người dùng đồng thời (NFR-01 trong quy định của repo); mục 2 và các ước tính ở mục 3.1 vẫn tính với 6 triệu và chưa được tính lại. Go dùng bản 1.25 thay cho 1.23. Thư viện frontend dùng chung có thêm `@vt/api-client` và `@vt/e2ee` ngoài danh sách ở mục 5.2. Quy ước API ở mục 13 có thêm tiền tố `/registry` của SVC-03 và tiền tố `/internal/v1/...` cho lời gọi giữa các service; xung đột phiên bản trả 409, riêng giao diện SCIM trả 412 theo chuẩn. Theo YC-PT-19, các chỉ tiêu NFR liên quan tới hiệu năng chưa được đo ở tải cao trong giai đoạn này.
