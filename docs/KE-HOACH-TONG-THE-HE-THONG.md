# KẾ HOẠCH TỔNG THỂ XÂY DỰNG TOÀN HỆ THỐNG MISSION

> Nguồn: baseline `00-co-so-thiet-ke-chung.md`; TL-01 (kiến trúc, chương 5–8, 12–18, 20, PL B); TL-02a, TL-02b (quy trình QT-00..QT-18 và các việc máy chủ BE-xx); TL-03 (CSDL, đồng bộ, chuyển đổi, lưu giữ, đối soát); TL-04 (WI, PERF, DR, SEC, OPS); TL-05 (định cỡ QM-1..QM-5, môi trường, ngưỡng NG-01..NG-14); BS-01 (giao diện cấu hình Flowable); BS-02 (API-first, ADR-32); `docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md`; sổ tay yêu cầu YC-PT-11. Các con số ước lượng thời gian là suy luận từ tốc độ thực tế của phân hệ web, được đánh dấu "(suy luận)".

## 1. Mục đích và cách đọc tài liệu

Tài liệu này là kế hoạch làm **trọn vẹn** nền tảng Mission tới khi đưa vào sử dụng ở quy mô quốc gia, không chỉ phân hệ web. Trước thời điểm lập kế hoạch, dự án mới có kế hoạch và mã nguồn cho phân hệ web (SVC-17), bộ hợp đồng OpenAPI, ma trận phân quyền và hạ tầng dev dùng chung; toàn bộ phần máy chủ (mission-core và mười lăm microservice), cơ sở dữ liệu, đồng bộ dữ liệu, tích hợp hệ ngoài, hạ tầng Kubernetes, CI/CD, kiểm thử tích hợp và hiệu năng, dự phòng thảm hoạ, vận hành, chuyển đổi dữ liệu từ hệ hiện hành, ứng dụng di động và tài liệu sử dụng đều chưa có kế hoạch hay mã. Kế hoạch này lấp toàn bộ khoảng trống đó.

Kế hoạch được chia thành 326 task nhỏ cho AI agent, cộng với 65 task web WEB-001..065 đã có. Mỗi task là một thẻ Markdown trong `docs/tasks/` theo mẫu `docs/tasks/_MAU-TASK-HE-THONG.md`, mở đầu bằng khối quy định chung giống hệt cách đang làm cho web, có phạm vi, tệp được phép sửa và tiêu chí hoàn thành kiểm chứng được. Danh mục đầy đủ, tầng phụ thuộc và đường găng tính tự động nằm ở `docs/tasks/DANH-MUC-TOAN-HE-THONG.md`, sinh từ `docs/tasks/danh-muc-he-thong.tsv` bằng `python3 scripts/sinh-danh-muc.py`. Người điều phối chỉ sửa tệp TSV rồi chạy lại script, không sửa tay danh mục.

Người đọc lần đầu nên đọc mục 2 (thế nào là xong), mục 3 (phạm vi và ma trận phủ), mục 7 (đường găng), mục 8 (chia luồng) và mục 10 (việc cần người dùng). Agent làm task chỉ cần đọc `AGENTS.md`, quy định riêng của phân hệ trong `docs/quy-dinh/` và thẻ task của mình.

## 2. Thế nào là "xong trọn vẹn hệ thống"

Hệ thống được coi là hoàn thành khi đồng thời thoả các điều kiện dưới đây. Đây là định nghĩa dùng để báo cáo tiến độ toàn hệ thống theo YC-PT-11: phần trăm hoàn thành luôn tính trên toàn bộ danh mục, không tính riêng phân hệ web.

1. **Chức năng.** Mọi quy trình QT-00..QT-18 và bổ sung BS-01 chạy được đầu cuối trên hệ thống thật (web, di động, máy chủ, cơ sở dữ liệu, sự kiện), không còn màn hình nào dùng dữ liệu giả lập. Bốn hành trình đầu cuối QA-04..QA-07 chạy xanh trên môi trường tích hợp.
2. **Phi chức năng.** Các chỉ tiêu NFR-01..NFR-11 được chứng minh bằng số đo: chiến dịch thử tải QA-12 trên CELL-PERF đạt PERF-01..PERF-26, tính lại 1,2 triệu hồ sơ một cell trong ≤ 2 giờ và ký lô 100.000 phiếu trong ≤ 4 giờ (NFR-06), diễn tập DR-01..DR-16 đạt RPO ≤ 15 phút, RTO ≤ 4 giờ (NFR-05), ≥ 99% request có trace (NFR-10), WCAG 2.1 AA và chế độ Đảng viên cao tuổi trên web và di động (NFR-11).
3. **An toàn thông tin.** Kiểm thử QA-13 đạt OWASP ASVS 4.0.3 mức L2 (L3 cho xác thực, phiên, mật mã), không còn lỗ hổng mức cao; hồ sơ đề xuất cấp độ 4 theo TCVN 11930:2017 (VH-07) được lập; chuỗi cung ứng phần mềm có ký ảnh, SBOM và provenance (INF-05, INF-07).
4. **Dữ liệu.** Policy Pack "quy định mới", bảng DMN và bộ ca kiểm thử vàng được nghiệp vụ ký (DL-01..DL-03); dữ liệu kỳ cũ của hệ hiện hành được chuyển đổi và niêm phong (DL-09..DL-11); các job đối soát R1–R8 chạy hằng đêm không lệch (DL-12).
5. **Vận hành.** Runbook OPS-01..OPS-26, quy trình trực và xử lý sự cố, cảnh báo theo ngân sách lỗi, sao lưu có kiểm tra khôi phục hằng ngày đều đã có và đã diễn tập (VH-01..VH-04, INF-15, INF-17).
6. **Đưa vào sử dụng.** Một tenant thí điểm chạy trọn một kỳ đánh giá (QA-16), có hướng dẫn sử dụng theo vai trò (VH-05), sau đó các tenant được chuyển theo nhóm và hệ hiện hành được tắt (LEG-11, VH-08). Đây là mốc cuối của đợt 3 trong lộ trình TL-01 và TL-04.

## 3. Phạm vi toàn hệ thống và ma trận phủ

### 3.1. Các nhóm task

Danh mục chia thành mười một nhóm theo tiền tố mã. Tiền tố được chọn để không trùng với các dải mã đã cấp cho tài liệu thiết kế (ADR, SVC, MOD, EVT, QT, SCR, BR, MSG, NFR, SEC, WI, PERF, DR, OPS), đúng quy tắc 2 của `AGENTS.md`.

| Tiền tố | Nội dung | Số task |
|---|---|---|
| `NEN-` | Nền móng: repo chung, wiki backend, khung Maven, sinh mã từ hợp đồng, Avro/AsyncAPI, thư viện nền `vt-platform-starter`, thư viện Go và Python, mẫu service, môi trường dev backend, nền CSDL (Flyway, vai trò, bảng dùng chung, RLS, PgBouncer, định tuyến cell), gói chính sách OPA, thư viện CEL | 29 |
| `CORE-00..08-` | mission-core (SVC-05): phần dùng chung và tám module MOD-01..MOD-08 | 106 |
| `DV-01..16-` | Mười lăm microservice SVC-01..SVC-04, SVC-06..SVC-16 | 97 |
| `DL-` | Nội dung nghiệp vụ và dữ liệu: Policy Pack, DMN, ca vàng, mẫu biểu, dữ liệu khởi tạo, dữ liệu thử quy mô, chuyển đổi Oracle, đối soát, lưu giữ | 13 |
| `INF-` | Hạ tầng: Helm, GitOps, pipeline, Harbor/Kyverno, cụm K8s, Kafka, CSDL, Ceph, OpenBao, quan sát, co giãn, sao lưu, bảo mật nền, vùng toàn cục, môi trường phi sản xuất, server dev, gói cách ly | 23 |
| `QA-` | Kiểm thử hợp đồng, đầu cuối, hiệu năng (G2, G3), ATTT, thử hỏng và DR, chấp nhận nghiệp vụ | 16 |
| `MOB-` | Ứng dụng di động Flutter (SVC-18) | 10 |
| `LEG-` | Hệ hiện hành: đợt 0a, 0b, 0c, chuyển đổi Strangler Fig, tắt hệ cũ | 11 |
| `VH-` | Vận hành và đưa vào sử dụng: runbook, trực, vòng đời tenant, hướng dẫn sử dụng, tài liệu quản trị, hồ sơ cấp độ 4 | 9 |
| `WEB-070..076` | Phần web còn thiếu so với toàn hệ thống (nối API thật, 429/503, traceparent, khám phá cell, cờ tính năng) | 7 |
| `CNH-` | Cá nhân hoá theo người dùng, **chờ người dùng chốt** | 5 |

### 3.2. Ma trận phủ theo thành phần thiết kế

Bảng dưới trả lời câu hỏi "thành phần nào của thiết kế được task nào làm", theo yêu cầu YC-PT-11. Một thành phần chỉ được coi là xong khi mọi task ở cột cuối đã gộp.

| Thành phần thiết kế | Task phụ trách |
|---|---|
| SVC-01 api-gateway (APISIX, WAF, giới hạn tốc độ, lớp ngoài toàn cục) | DV-01-01..04, INF-08 |
| SVC-02 iam (Keycloak, MFA, step-up, VNeID, đồng bộ người dùng) | DV-02-01..06, WEB-056 (realm dev) |
| SVC-03 tenant-registry (Go, toàn cục, saga cấp phát, chuyển cell) | DV-03-01..07, NEN-036 |
| SVC-04 org-service (cây hai trục thời gian, phân công, SCIM, đối soát) | DV-04-01..07 |
| SVC-05 mission-core khung chung | CORE-00-01..05, NEN-009..016, NEN-019 |
| MOD-01 okr | CORE-01-01..13 |
| MOD-02 task | CORE-02-01..23 |
| MOD-03 product | CORE-03-01..08 |
| MOD-04 appraisal | CORE-04-01..12 |
| MOD-05 policy | CORE-05-01..10, NEN-041, DL-01..04 |
| MOD-06 dossier | CORE-06-01..08 |
| MOD-07 oversight | CORE-07-01..11 |
| MOD-08 config (kỳ, lịch, khoá kỳ, cờ, UI Pack, registry, biểu mẫu, plugin) | CORE-08-01..16, DL-06, DL-07 |
| SVC-06 workflow-service (Flowable, BPMN/DMN, saga, BS-01) | DV-06-01..11 |
| SVC-07 scoring-service | DV-07-01..08 |
| SVC-08 evidence-service | DV-08-01..05 |
| SVC-09 ledger-service | DV-09-01..05 |
| SVC-10 esign-service | DV-10-01..06 |
| SVC-11 document-service | DV-11-01..04, DL-05 |
| SVC-12 notification-service | DV-12-01..04 |
| SVC-13 integration-gateway | DV-13-01..11 |
| SVC-14 ai-gateway | DV-14-01..06 |
| SVC-15 analytics | DV-15-01..11 |
| SVC-16 search | DV-16-01..02 |
| SVC-17 web-frontend | WEB-001..065 (kế hoạch web), WEB-070..076 |
| SVC-18 mobile-app | MOB-01..10 |
| Hợp đồng API và sự kiện (ADR-32) | WEB-063..065 (đã có), NEN-006..008, QA-02 |
| CSDL dùng chung, đa khách thuê, RLS, phân vùng, định tuyến cell | NEN-030..036, INF-11 |
| Phân quyền RBAC/ABAC phía máy chủ | NEN-014, NEN-040, CORE-08-16, DV-02-03 |
| Mật mã ứng dụng, C2, E2EE C3, khoá | NEN-015, CORE-00-05, CORE-07-06, INF-13, DV-10-03 |
| G1 giao diện theo tenant, phía máy chủ | CORE-08-08..15, DL-06; phía web WEB-004, 009, 010, 050, 051 |
| G2 nút cổ chai Flowable khi nhiều tenant | DV-06-01, DV-06-11, QA-09 |
| G3 hiệu năng bảy lớp bảo vệ và mã hoá | NEN-015, QA-10 |
| Hạ tầng K8s, lưu trữ, mạng, service mesh | INF-08..13, INF-20 |
| CI/CD, chuỗi cung ứng, GitOps | INF-04..07, INF-23 |
| Quan sát, SLO, cảnh báo, co giãn | INF-14..16 |
| Sao lưu và dự phòng thảm hoạ | INF-17, INF-18, QA-14, QA-15 |
| Bảo mật vận hành (Falco, kiểm toán, SIEM) | INF-19, QA-13, VH-07 |
| Kiểm thử tích hợp và đầu cuối | QA-01..07, DV-13-11, NEN-034, NEN-035 |
| Hiệu năng và định cỡ | INF-03, DL-08, QA-08..12 |
| Chuyển đổi dữ liệu, đối soát, lưu giữ | DL-09..13 |
| Hệ hiện hành và Strangler Fig | LEG-01..11 |
| Vận hành, tài liệu, đào tạo, đưa vào sử dụng | VH-01..09, NEN-002..004 |
| Cá nhân hoá theo người dùng (chờ chốt) | CNH-01..05 |

## 4. Hiện trạng tại thời điểm lập kế hoạch

Theo git trên nhánh chính, phân hệ web đã gộp khoảng 41 trên 65 task: nền tảng shell và remote, đăng nhập Keycloak, client API sinh từ hợp đồng, giả lập API, ma trận phân quyền áp vào shell và remote, cùng phần lớn màn hình OKR, kế hoạch, giao việc, minh chứng, nghiệm thu, khoá kỳ, tự đánh giá, thẩm định, khiếu nại, mở lại kỳ và giao diện Flowable của BS-01. Bộ hợp đồng OpenAPI 3.1 đã đủ (361 thao tác, 16 chủ sở hữu, trong đó 264 thuộc mission-core) và là nguồn sự thật cho mọi API. Hạ tầng dev dùng chung chạy bằng Docker Compose project `mission-dev` trên server dev, có PostgreSQL một CSDL `mission` với vai trò theo schema và Keycloak realm `mission` khớp ma trận phân quyền. Phía máy chủ chưa có dòng mã nào; chưa có hợp đồng sự kiện Avro, chưa có cụm Kubernetes, chưa có pipeline cho backend, chưa có quyền truy cập hệ hiện hành.

## 5. Quyết định tổ chức repo

Repo `mission-web` trở thành **repo chung của toàn hệ thống** (monorepo). Lý do: hợp đồng OpenAPI và sự kiện dùng chung cho web, di động và máy chủ nên đặt cùng chỗ giúp một thay đổi hợp đồng và phần hiện thực đi trong cùng một PR, chạy chung một bộ kiểm tra tương thích; dự án đang dùng một bộ quy định `AGENTS.md`, một wiki và một thread điều phối gộp nhánh, nên tách repo sẽ nhân đôi quy định và công điều phối mà không được lợi gì ở quy mô hiện tại. Nếu sau này cần tách theo đội vận hành, cấu trúc thư mục dưới đây cho phép tách mà không đổi mã. Tên repo trên GitHub có thể đổi thành `mission` khi người dùng muốn; đó là thao tác của người dùng, không ảnh hưởng kế hoạch.

```
mission-web/                     (repo chung toàn hệ thống)
├── AGENTS.md, CLAUDE.md          quy định chung, áp cho mọi phân hệ
├── docs/
│   ├── KE-HOACH-TONG-THE-HE-THONG.md     (tài liệu này)
│   ├── KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md
│   ├── quy-dinh/                 BACKEND.md, HA-TANG.md, DI-DONG.md
│   ├── phan-quyen/
│   └── tasks/                    WEB-*, NEN-*, CORE-*, DV-*, DL-*, INF-*, QA-*, MOB-*, LEG-*, VH-*, CNH-*
├── contracts/  openapi/ (đã có), events/avro/, asyncapi/
├── frontend/                     Angular (đã có)
├── backend/                      Maven đa mô-đun: libs/vt-platform-starter, libs/vt-cel, mission-core, services/<java>
├── services-go/                  tenant-registry, ledger, notification
├── services-py/                  ai-gateway, job phân tích
├── analytics/                    ClickHouse, Cube, Superset
├── mobile/                       Flutter
├── data/                         policy-pack, seed, golden
├── deploy/                       charts/, environments/, policies/ (Kyverno), web/ (đã có)
├── infra/                        dev/ (đã có), perf/, airgap/
├── ops/runbooks/
└── wiki/                         thêm trang 16–20 cho máy chủ, hạ tầng
```

Quy định chung giữ ở `AGENTS.md`; mỗi phân hệ có thêm một tệp quy định riêng ngắn trong `docs/quy-dinh/` (đã viết trong nhánh này). Script `scripts/agent-task.sh` in quy định chung, quy định riêng của phân hệ theo tiền tố mã và nội dung thẻ thành một prompt duy nhất cho agent.

## 6. Giai đoạn và mốc

Giai đoạn bám lộ trình đợt 0a–3 của TL-01 chương 13 và TL-04, nhưng được viết lại theo cách làm bằng agent: một mốc đạt khi mọi task của mốc đã gộp và tiêu chí kiểm chứng của mốc chạy xanh.

| Mốc | Nội dung | Task chính | Tiêu chí đạt |
|---|---|---|---|
| M0 – Nền móng | Repo chung, khung Maven, sinh mã từ hợp đồng, Avro, thư viện nền, nền CSDL, OPA, CEL, môi trường dev backend, pipeline Java, chart thư viện | NEN-*, INF-01, INF-05 | mission-core khung chạy được, kiểm thử rò rỉ tenant lớp CSDL xanh, pipeline chạy đủ cổng |
| M1 – Định danh và tổ chức (đợt 1) | Keycloak production, tenant-registry, org-service, cổng API, tích hợp nền, `org_replica` | DV-01..04, DV-13-01..02, CORE-00-01..03 | Khởi tạo một tenant đầu cuối trong ≤ 1 ngày (NFR-07) trên môi trường tích hợp |
| M2 – Cấu hình và Policy Pack | Kỳ, lịch, khoá kỳ, UI Pack và biểu mẫu (G1), Policy Pack có ca vàng | CORE-08, CORE-05, DL-01..04, DL-06 | Phát hành một Policy Pack và một UI Pack có ký, ca vàng xanh |
| M3 – Nghiệp vụ lõi (đợt 2) | OKR, kế hoạch, giao việc, tiến độ, minh chứng, nghiệm thu, Flowable, tính điểm, ký số, sổ cái, tài liệu, thông báo | CORE-01..03, DV-06..12 | Hành trình QA-04, QA-05 xanh với backend thật; web WEB-070 tắt giả lập |
| M4 – Đánh giá và giám sát | Tự đánh giá, thẩm định, quyết định hàng loạt, xếp loại năm, kiểm điểm, hồ sơ số, khiếu nại, mở lại kỳ, cảnh báo, phân tích, AI, tìm kiếm | CORE-04, CORE-06, CORE-07, DV-14..16, WEB-071, WEB-072 | QA-06, QA-07 xanh; saga chốt kỳ có bù trừ |
| M5 – Hạ tầng production và hiệu năng | K8s cell, dữ liệu, quan sát, co giãn, sao lưu, GitOps, thử tải, ATTT | INF-08..21, QA-08..13 | PERF-01..26 đạt trên CELL-PERF; ASVS L2 |
| M6 – Dự phòng, di động, chuyển đổi | DR, di động, chuyển dữ liệu Oracle, vận hành | QA-14..15, MOB-*, DL-09..13, VH-01..07 | DR-01..16 đạt; ứng dụng di động phát hành nội bộ |
| M7 – Đưa vào sử dụng (đợt 3) | Tenant thí điểm một kỳ, chuyển tenant theo nhóm, tắt hệ cũ | QA-16, VH-08, LEG-10..11 | Một kỳ đánh giá thật chạy trọn; hệ cũ tắt |

Nhóm LEG đợt 0a–0c (vá khẩn và ổn định hệ hiện hành) chạy song song với M0–M2 ngay khi người dùng cấp quyền truy cập hệ cũ, vì đó là rủi ro an ninh đang tồn tại chứ không phụ thuộc hệ mới.

## 7. Đường găng

Script tính đường găng theo trọng số cỡ việc (S = 0,5; M = 1; L = 2). Đường dài nhất gồm 23 task:

NEN-001 → NEN-005 → NEN-030 → NEN-031 → NEN-033 → NEN-010 → NEN-019 → CORE-00-01 → CORE-00-02 → CORE-05-01 → CORE-05-02 → CORE-05-03 → CORE-08-13 → CORE-08-14 → CORE-04-04 → CORE-04-05 → CORE-04-07 → CORE-04-08 → CORE-04-10 → QA-06 → QA-07 → QA-16 → VH-08.

Ý nghĩa thực tế của đường này là: nền CSDL (vai trò, RLS, PgBouncer) và khung mission-core phải xong sớm nhất, vì mọi module đứng trên nó; Policy Pack (MOD-05) là nút thắt thứ hai, vì giao việc, tính điểm, tự đánh giá đều đọc tham số và quy tắc từ đó; phiếu tự đánh giá và quyết định xếp loại hàng loạt (MOD-04) là chuỗi dài nhất của nghiệp vụ. Ngoài đường tính bằng số, còn ba đường găng "mềm" do phụ thuộc bên ngoài: ký số thật qua MySign hoặc HSM (DV-10-02, DV-10-03), môi trường thử các hệ ngoài vOffice, NDXP, VNeID (DV-13-03..07, DV-02-04), và nghiệp vụ ký Policy Pack cùng ca vàng (DL-01..03). Nếu các đầu vào này đến muộn, chúng sẽ trở thành đường găng thật; vì vậy mục 10 đề nghị người dùng xử lý sớm.

Để rút ngắn đường găng, kế hoạch làm ba việc. Thứ nhất, luồng B1 chỉ làm nền móng và được khởi động đầu tiên với ưu tiên tuyệt đối cho chuỗi NEN-005 → NEN-033 → NEN-019. Thứ hai, các microservice dùng mẫu service (NEN-019) và nhà cung cấp giả lập (SoftHSM, MySign giả, đối tác giả lập Pact) để không phải chờ môi trường thật. Thứ ba, các module mission-core khác nhau chạy ở các luồng khác nhau ngay sau CORE-00-02, chỉ đồng bộ qua cổng `api` và sự kiện đã khai báo trong hợp đồng.

## 8. Đề xuất chia luồng song song

Kế hoạch đề xuất mười một luồng phía hệ thống, cộng các luồng web hiện có. Mỗi luồng là một thread riêng với một worktree riêng, giống cách đang chạy năm luồng web; thread điều phối hiện tại vẫn là nơi duy nhất gộp vào nhánh chính và cập nhật `docs/tasks/README.md`.

| Luồng | Phạm vi | Số task | Khối lượng |
|---|---|---|---|
| DP | NEN-001 (repo chung, làm ngay trong nhánh kế hoạch này) | 1 | 0,5 |
| B1 | Nền móng backend và CSDL (NEN, trừ NEN-001, NEN-035, NEN-041) | 26 | 28,5 |
| B2 | Cấu hình, Policy Pack, OKR (CORE-00 khung, CORE-08, CORE-05, CORE-01, CNH máy chủ) | 45 | 50 |
| B3 | Kế hoạch, giao việc, minh chứng (CORE-02, CORE-03, DV-08) | 37 | 43 |
| B4 | Đánh giá, hồ sơ số, giám sát, tính điểm (CORE-04, CORE-06, CORE-07, DV-07) | 40 | 47,5 |
| B5 | Định danh, tổ chức, tenant, cổng API, tích hợp (DV-01..04, DV-13) | 35 | 44,5 |
| B6 | Quy trình Flowable, sổ cái, ký số, tài liệu (DV-06, DV-09, DV-10, DV-11) | 26 | 38 |
| B7 | Thông báo, tìm kiếm, phân tích, AI (DV-12, DV-16, DV-15, DV-14) | 23 | 27,5 |
| B8 | Hạ tầng, CI/CD, quan sát, dự phòng (INF) | 23 | 37 |
| B9 | Kiểm thử tích hợp, hiệu năng, ATTT, DR (QA, NEN-035, DL-08) | 18 | 26,5 |
| B10 | Di động (MOB) | 10 | 10,5 |
| B11 | Dữ liệu nghiệp vụ, hệ cũ, vận hành (DL, LEG, VH) | 32 | 41 |
| W | Web còn thiếu (WEB-070..076, CNH web) giao cho các luồng web hiện có | 10 | 10,5 |

Không nên khởi động cả mười một luồng cùng lúc, vì phần lớn luồng nghiệp vụ phải chờ khung mission-core. Đề xuất khởi động theo ba đợt:

1. **Đợt khởi động 1 (ngay):** B1 nền móng, B8 hạ tầng (INF-01, INF-05, INF-08 trên kind, INF-14), B5 phần không cần mission-core (DV-02-01, DV-03-01..03, DV-04-01..03), B6 phần khung (DV-06-01, DV-06-05, DV-09-01, DV-10-01, DV-11-01) và B11 phần nội dung không phụ thuộc mã (DL-05, LEG-01 khi có quyền). Các luồng này chỉ cần NEN-005 và NEN-017/019, nên chạy được gần như ngay.
2. **Đợt khởi động 2 (khi CORE-00-02 gộp):** B2, B3, B4, B7 và B9 (QA-01, QA-02). Lúc này khung module, outbox, RLS và mẫu service đã có, các luồng nghiệp vụ không giẫm lên nhau.
3. **Đợt khởi động 3 (khi M3 đạt):** B10 di động, phần nối API thật của web (WEB-070..072) và các kịch bản hiệu năng, DR.

Khi một luồng hết việc, người điều phối chuyển task ở cùng tầng từ luồng đang chậm sang, dựa trên cột "Tầng" trong danh mục.

## 9. Cổng chất lượng

Mọi task phải qua cổng chất lượng của phân hệ mình trước khi gộp, ghi trong quy định riêng ở `docs/quy-dinh/`. Tóm tắt: với máy chủ, `mvn verify` gồm kiểm thử đơn vị, Testcontainers, ArchUnit và Spring Modulith, kiểm thử hợp đồng với OpenAPI, `openapi-diff` không phá tương thích, độ phủ ≥ 80% lớp domain và application, quét bảo mật không lỗi cao; với hạ tầng, `helm lint`, `helm template`, `kubeconform`, Kyverno chế độ kiểm thử và cài thử trên kind/k3d; với di động, `flutter analyze` và `flutter test`; với web, giữ nguyên tiêu chí của kế hoạch web. Ở mức mốc, mốc M3 trở đi yêu cầu hành trình đầu cuối tương ứng chạy xanh trên môi trường tích hợp có backend thật (QA-03), đúng yêu cầu kiểm thử với hệ thống thật trong sổ tay (YC-PT-08).

## 10. Phụ thuộc bên ngoài và việc cần người dùng quyết định

Phần lớn kế hoạch agent tự làm được, nhưng một số đầu vào chỉ người dùng hoặc tổ chức của người dùng cung cấp được. Các task bị ảnh hưởng đã ghi trạng thái "Bị chặn bởi bên ngoài" hoặc "Chờ người dùng chốt" trong thẻ, và vẫn làm trước được phần giả lập.

| Đầu vào cần có | Task bị chặn | Cách làm tạm trong lúc chờ |
|---|---|---|
| Quyền truy cập bốn repo, Oracle và hạ tầng của hệ hiện hành | LEG-01..11, DL-09..11 | Không có cách làm tạm; đây là rủi ro an ninh đang tồn tại (S01–S05) |
| Môi trường thử vOffice, iOffice, Trục liên thông, NDXP/LGSP, CSDL đảng viên, VNeID | DV-13-03..07, DV-02-04 | Đối tác giả lập và kiểm thử hợp đồng Pact (DV-13-11) |
| Tài khoản thử MySign, Viettel-CA, CA chuyên dùng, thư viện Ban Cơ yếu, HSM | DV-10-02..05, INF-13 | SoftHSM và nhà cung cấp ký giả lập |
| Nghiệp vụ xác nhận công thức, DMN, ca vàng, mẫu biểu chính thức | DL-01..03, DL-05 | Bản nháp theo QĐ 366 và NĐ 335/2025 do agent soạn để nghiệp vụ duyệt |
| Cụm Kubernetes thật (Viettel Cloud), trung tâm dữ liệu thứ hai, CELL-PERF, GPU | INF-08..21, QA-12, QA-15, DV-14-01 | kind/k3d và Docker Compose; mô hình AI nhỏ chạy CPU cho dev |
| Cổng SMS, tài khoản đẩy FCM/APNs, tài khoản cửa hàng ứng dụng | DV-12-03, MOB-09, MOB-10 | Kênh giả lập ghi ra hộp thư thử |
| Tenant và người dùng thật cho thí điểm | QA-16, VH-08 | Không có cách làm tạm |
| Quyết định làm tính năng cá nhân hoá theo người dùng | CNH-01..05 | Không làm cho tới khi người dùng chốt |
| Duyệt đề xuất quản trị vai trò và uỷ quyền của WEB-058 | CORE-08-16 | Chưa làm phần lưu phía máy chủ |
| Chốt mô hình CSDL dev (một CSDL `mission` nhiều schema như hiện tại hay CSDL riêng theo service như TL-04) | NEN-020, NEN-031 | Mặc định giữ một CSDL nhiều schema ở dev, CSDL riêng theo cụm ở production đúng TL-04 (suy luận) |

## 11. Rủi ro chính

Rủi ro lớn nhất không nằm ở khối lượng mã mà ở các đầu vào bên ngoài nêu trên: nếu quyền truy cập hệ cũ, môi trường thử hệ ngoài và tài khoản ký số đến muộn, các task liên quan sẽ hoàn thành phần giả lập nhưng không thể đạt tiêu chí "chạy với hệ thật". Rủi ro thứ hai là các điểm lệch giữa tài liệu thiết kế phía máy chủ (ví dụ hậu tố DLQ, tiền tố topic `sys.`, một số bảng `period_state` chưa có trong từ điển dữ liệu, mô hình CSDL theo tenant ở TL-03 và sáu cụm ở TL-04); task NEN-004 gom các điểm này vào wiki trang 14 và đề xuất chốt trước khi các luồng nghiệp vụ chạy. Rủi ro thứ ba là xung đột khi nhiều luồng cùng sửa một tệp dùng chung (POM cha, `infra/dev`, Helm values); quy tắc chỉ sửa tệp trong phạm vi task và việc thread điều phối gộp tuần tự giảm rủi ro này, và các tệp dùng chung được giao cho đúng một luồng chủ (B1 cho POM và thư viện nền, B8 cho chart và môi trường).

## 12. Phần web còn thiếu so với toàn hệ thống

Kế hoạch web hiện tại đúng phạm vi của nó, nhưng khi nhìn toàn hệ thống thì thiếu bảy việc, đã thêm thành WEB-070..076: nối các màn hình với API thật và tắt giả lập theo module (ba task, theo nhóm màn hình), xử lý 429 và 503 có `Retry-After` khi máy chủ cắt tải, gắn `traceparent` vào mọi lời gọi để đạt NFR-10, khám phá cell khi đăng nhập (mã 421 và chuyển hướng) theo ADR-07, và đọc cờ tính năng từ MOD-08. Ngoài ra, các task G1 phía web WEB-004, WEB-009, WEB-050, WEB-051 chưa bắt đầu, WEB-010 chưa đạt ngân sách 50 ms và bộ nạp UI Pack chưa kiểm chữ ký; các việc này đã có thẻ trong kế hoạch web, chỉ cần ưu tiên khi CORE-08-11 (ký và phát hành UI Pack phía máy chủ) gộp. Nhóm CNH-03..05 là phần web của cá nhân hoá, chờ người dùng chốt.

## 13. Ước lượng thời gian (suy luận)

Tổng khối lượng là khoảng 405 đơn vị quy đổi (S = 0,5; M = 1; L = 2) cho 326 task. Phân hệ web đã gộp khoảng 41 task trong khoảng một ngày làm việc với năm luồng song song, tức mỗi luồng khoảng tám task một ngày với task cỡ S và M. Task máy chủ và hạ tầng nặng hơn task màn hình vì phải chạy Testcontainers và kiểm thử tích hợp, nên giả định tốc độ bằng một nửa. Với mười một luồng chạy đủ, phần agent tự làm được ước khoảng 7–10 ngày làm việc liên tục, trong đó đường găng chiếm khoảng 4–5 ngày (suy luận). Con số này **không** tính thời gian chờ các đầu vào bên ngoài ở mục 10, và không tính kỳ thí điểm QA-16, vốn kéo dài bằng một kỳ đánh giá thật. Nếu chạy ít luồng hơn, thời gian tăng gần tỷ lệ nghịch với số luồng ở giai đoạn giữa.

## 14. Báo cáo tiến độ toàn hệ thống

Theo YC-PT-11, mọi báo cáo tiến độ nêu tỷ lệ task đã gộp trên toàn danh mục (326 task hệ thống cộng 65 task web), tách theo mốc M0–M7 và theo luồng, kèm danh sách task đang bị chặn bởi bên ngoài. Thread điều phối cập nhật cột trạng thái trong `docs/tasks/danh-muc-he-thong.tsv` (cột Ghi chú) hoặc `docs/tasks/README.md` sau mỗi lần gộp và chạy lại `scripts/sinh-danh-muc.py`.

## Liên quan

- `AGENTS.md`, `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`, `docs/quy-dinh/DI-DONG.md`
- `docs/tasks/DANH-MUC-TOAN-HE-THONG.md`, `docs/tasks/_MAU-TASK-HE-THONG.md`
- `docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`
