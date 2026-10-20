---
title: "Triển khai hệ thống: hiệu năng, co giãn, sao lưu dự phòng và an toàn thông tin trên Kubernetes"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Mã tài liệu TL-04 · Phiên bản 1.0 · 10/2026"
toc-title: "Mục lục"
---

# Thông tin tài liệu {-}

## Kiểm soát phiên bản {-}

| Phiên bản | Ngày | Người thực hiện | Nội dung thay đổi |
|---|---|---|---|
| 0.1 | 12/10/2026 | Nhóm kiến trúc hạ tầng và vận hành | Dàn ý, mô hình triển khai, định cỡ sơ bộ |
| 0.5 | 16/10/2026 | Nhóm kiến trúc hạ tầng và vận hành | Bổ sung co giãn, thử tải, sao lưu và khôi phục, an toàn thông tin |
| 1.0 | 19/10/2026 | Nhóm kiến trúc hạ tầng và vận hành | Hoàn thiện ma trận truy vết, kế hoạch theo lộ trình, trình kiến trúc sư trưởng |

## Tài liệu liên quan {-}

| Mã | Tên tài liệu | Quan hệ với TL-04 |
|---|---|---|
| TL-01 | Thiết kế kiến trúc chi tiết | Cung cấp danh mục thành phần SVC, MOD, các quyết định ADR và phân tích định lượng G1, G2, G3 mà TL-04 kiểm chứng bằng kịch bản thử tải |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện | Cung cấp quy trình QT và màn hình SCR; TL-04 dùng để xây kịch bản tải theo hành vi người dùng thật |
| TL-03 | Thiết kế cơ sở dữ liệu và đồng bộ dữ liệu | Cung cấp schema, bảng, phân vùng, RLS, sự kiện EVT; TL-04 định cỡ cụm CSDL, Kafka và sao lưu theo đó |
| TL-04 | Triển khai hệ thống: hiệu năng, co giãn, sao lưu dự phòng và an toàn thông tin trên Kubernetes | Tài liệu này |
| Đầu vào 1 | Đánh giá kiến trúc công nghệ, 10/2026 (110 tiêu chí, 64 vấn đề, lộ trình đợt 0a–3) | Checklist mà TL-04 phải đáp ứng cho các trục B, C, D, E, F, G, J |
| Đầu vào 2 | Báo cáo Ban Giám đốc "Nền tảng đánh giá cán bộ theo OKR và KPI", 10/2026 | Mục tiêu nền tảng, ba mô hình triển khai, kiến trúc cell, yêu cầu phi chức năng slide 39–45 |

# Giới thiệu

## Mục đích của tài liệu

Tài liệu này mô tả cách đưa nền tảng đánh giá cán bộ theo OKR và KPI vào vận hành trên Kubernetes ở quy mô 6 triệu tài khoản và 300.000 người dùng đồng thời (CCU). Nếu TL-01 trả lời câu hỏi "hệ thống gồm những thành phần nào và vì sao chọn như vậy", thì TL-04 trả lời câu hỏi tiếp theo mà đội vận hành và hội đồng thẩm định sẽ đặt ra: các thành phần đó chạy ở đâu, cần bao nhiêu tài nguyên, tự co giãn ra sao khi đến ngày hạn nộp hồ sơ, được sao lưu và khôi phục thế nào khi mất một trung tâm dữ liệu, và được bảo vệ bằng những biện pháp an toàn thông tin cụ thể nào để đáp ứng hồ sơ đề xuất cấp độ 4.

Tài liệu được viết ở mức "triển khai được". Mỗi quyết định đi kèm cấu hình mẫu (YAML, Helm values, Jenkinsfile, cấu hình APISIX, Fluent Bit), lệnh vận hành và con số định cỡ có ghi rõ cách tính. Những con số chưa thể đo trên hệ thống thật được ghi là "giả định" hoặc "ước tính", và mỗi giả định quan trọng đều có một kịch bản thử tải PERF tương ứng để xác nhận hoặc điều chỉnh trước khi đưa vào sản xuất. Cách làm này xuất phát từ bài học của bản đánh giá kiến trúc: năng lực đo được hiện nay là 50 người dùng đồng thời, thấp hơn tải đỉnh ước tính khoảng 1.400 lần, nên mọi tuyên bố về năng lực của nền tảng mới phải được chứng minh bằng số đo chứ không chỉ bằng thiết kế.

## Phạm vi

TL-04 bao trùm toàn bộ vòng đời vận hành của nền tảng trong ba mô hình triển khai mà Báo cáo Ban Giám đốc đã chốt (slide 22, 40): SaaS trên Viettel Cloud, đám mây riêng của tỉnh hoặc bộ, và bản cài cách ly mạng cho lực lượng vũ trang, Công an. Cả ba mô hình được dựng từ cùng một nhánh mã nguồn và cùng một bộ Helm chart, khác nhau ở tệp giá trị cấu hình (values) và cách phân phối gói.

Các nội dung thuộc phạm vi gồm: mô hình triển khai hai trung tâm dữ liệu và kiến trúc cell; thiết kế cụm Kubernetes cho từng cell; định cỡ năng lực cho từng cell và toàn quốc; cấu hình triển khai từng service; cơ chế co giãn tự động; chương trình thử tải và tiêu chí đạt; sao lưu, dự phòng và các kịch bản khôi phục; biện pháp an toàn thông tin theo checklist trục D và yêu cầu cấp độ 4; ngăn xếp quan sát và quản lý log; quy trình phát hành và vận hành; kế hoạch triển khai theo lộ trình đợt 0a đến đợt 3; và ma trận truy vết từ tiêu chí checklist tới biện pháp và bằng chứng nghiệm thu.

Tài liệu không đặc tả lại logic nghiệp vụ (thuộc TL-02), cấu trúc bảng chi tiết (thuộc TL-03) hay lập luận lựa chọn kiến trúc ứng dụng (thuộc TL-01). Khi cần các thông tin đó, tài liệu dẫn chiếu rõ mục tương ứng.

## Cách đọc tài liệu và quan hệ với chuỗi truy vết

Bộ bốn tài liệu tuân theo chuỗi truy vết bắt buộc: quy trình (QT) dẫn tới màn hình (SCR), màn hình gọi API thuộc module hoặc service (MOD, SVC), API ghi vào bảng dữ liệu và phát sự kiện (schema.bảng, EVT), và cuối cùng các thành phần đó được triển khai với chỉ tiêu năng lực và biện pháp an toàn thông tin cụ thể trong TL-04. Vì vậy, mọi service trong TL-04 được gọi bằng mã SVC-xx của baseline, mọi chỉ tiêu được gọi bằng mã NFR-xx, và TL-04 tự cấp bốn dải mã của riêng mình:

| Dải mã | Ý nghĩa | Số mã đã dùng trong tài liệu |
|---|---|---|
| SEC-01…SEC-99 | Biện pháp an toàn thông tin | SEC-01 đến SEC-60 |
| OPS-01…OPS-99 | Quy trình vận hành | OPS-01 đến OPS-26 |
| PERF-01…PERF-99 | Kịch bản thử tải và thử hỏng | PERF-01 đến PERF-26 |
| DR-01…DR-20 | Kịch bản khôi phục sau sự cố | DR-01 đến DR-16 |

Người đọc thuộc đội vận hành nên đọc theo thứ tự chương 2, 3, 5, 6, 10, 11. Người thẩm định an toàn thông tin nên đọc chương 9 và chương 13. Người phụ trách năng lực và chi phí nên đọc chương 4 và chương 7. Kiến trúc sư trưởng có thể bắt đầu từ chương 13 để thấy toàn cảnh mức đáp ứng checklist, sau đó đi vào từng mục được dẫn chiếu.

## Thuật ngữ và chữ viết tắt

| Thuật ngữ | Giải thích |
|---|---|
| Cell | Một đơn vị triển khai độc lập gồm đủ ứng dụng, CSDL, Kafka, kho tệp và khoá mã hoá riêng, phục vụ một nhóm tenant; sự cố trong một cell không lan sang cell khác (Cell-based Architecture) |
| Tenant | Một khách hàng logic (tỉnh, đảng bộ, cơ quan, doanh nghiệp) có cấu hình, dữ liệu và khoá riêng |
| Vùng điều phối toàn cục (global control plane) | Các thành phần dùng chung giữa các cell: danh bạ tenant SVC-03, bộ định tuyến toàn cục, kho gốc Policy Pack và UI Pack, CDN, kho phân tích quốc gia |
| TTDL | Trung tâm dữ liệu; nền tảng dùng TTDL chính Hoà Lạc và TTDL dự phòng phía Nam |
| Vùng sự cố (failure zone) | Một miền điện, làm mát và mạng độc lập trong cùng TTDL, gắn nhãn `topology.kubernetes.io/zone` |
| Node pool | Nhóm node Kubernetes cùng cấu hình phần cứng và cùng mục đích, phân biệt bằng nhãn và taint |
| HPA | Bộ tự co giãn ngang của Kubernetes (Horizontal Pod Autoscaler), dùng API `autoscaling/v2` |
| KEDA | Bộ co giãn hướng sự kiện (Kubernetes Event-driven Autoscaling), co giãn theo độ trễ consumer Kafka, theo lịch và theo truy vấn Prometheus |
| PDB | Ngân sách gián đoạn pod (PodDisruptionBudget) |
| CNPG | CloudNativePG, operator vận hành PostgreSQL trên Kubernetes |
| PITR | Khôi phục về một thời điểm (Point-in-Time Recovery) |
| RPO, RTO | Lượng dữ liệu tối đa có thể mất (Recovery Point Objective) và thời gian tối đa để khôi phục dịch vụ (Recovery Time Objective) |
| SLI, SLO | Chỉ số mức dịch vụ (Service Level Indicator) và mục tiêu mức dịch vụ (Service Level Objective) |
| Ngân sách lỗi (error budget) | Phần thời gian hoặc số request được phép vi phạm SLO trong một chu kỳ, bằng 1 trừ SLO |
| GitOps | Cách vận hành trong đó trạng thái mong muốn của hệ thống được khai báo trong Git và được đồng bộ tự động (OpenGitOps) |
| SBOM | Danh mục thành phần phần mềm (Software Bill of Materials), định dạng CycloneDX |
| SLSA | Khung bảo đảm chuỗi cung ứng phần mềm (Supply-chain Levels for Software Artifacts) v1.0 |
| PAM | Quản lý truy cập đặc quyền (Privileged Access Management), có ghi hình phiên |
| HSM | Thiết bị an toàn phần cứng giữ khoá mật mã (Hardware Security Module), truy cập qua PKCS#11 |
| KEK, DEK | Khoá mã hoá khoá (Key Encryption Key) nằm trong HSM và khoá mã hoá dữ liệu (Data Encryption Key) theo tổ hợp tenant, tháng và mục đích (TL-01) |
| WORM | Ghi một lần, đọc nhiều lần (Write Once Read Many), thực hiện bằng Object Lock chế độ compliance |
| 3-2-1-1-0 | Nguyên tắc sao lưu: 3 bản sao, 2 loại phương tiện, 1 bản ở nơi khác, 1 bản bất biến hoặc ngoại tuyến, 0 lỗi khi kiểm tra khôi phục |
| C0, C1, C2, C3 | Bốn mức phân loại dữ liệu theo ADR-06: công khai, nội bộ, nhạy cảm, hạn chế |
| B1…B8, C1…C10, D1…D12, E1…E8, F1…F11, G1…G7, J1…J9 | Tiêu chí của các trục checklist trong bản đánh giá kiến trúc (quan sát, triển khai, an toàn, công nghệ, chịu lỗi, nhật ký, kiểm thử). Để tránh nhầm với ba góp ý của lãnh đạo, TL-04 luôn viết "góp ý G1, G2, G3" khi nói về góp ý và "tiêu chí G1…G7" khi nói về trục nhật ký |
| S01…S16, C01…C06, B01…B03, G01…G03 | Mã vấn đề cụ thể mà bản đánh giá phát hiện ở hệ hiện tại (hai chữ số sau chữ cái), phân biệt với mã tiêu chí (một chữ số) |
| RPS | Số request mỗi giây tại cổng API |
| p95, p99 | Phân vị thứ 95, 99 của độ trễ |

# Mô hình triển khai

## Nguyên tắc chung: một mã nguồn, một bộ Helm chart, ba cách phân phối

Báo cáo Ban Giám đốc đặt yêu cầu "một mã nguồn cho ba cách cài" (slide 39) và nhấn mạnh rằng bản quốc phòng "không rẽ nhánh mã nguồn" (slide 53). Yêu cầu này có hệ quả trực tiếp tới cách đóng gói triển khai: nếu mỗi mô hình có một bộ manifest riêng, các bộ manifest sẽ trôi dần khỏi nhau sau vài quý, lỗi sửa ở bản SaaS không tự đến bản cách ly, và chi phí kiểm định an ninh tăng theo số biến thể. Vì vậy TL-04 chọn nguyên tắc: một nhánh mã `main`, một quy trình build duy nhất sinh ra một tập ảnh container đã ký, và một bộ Helm chart tên `vt-platform` dùng chung cho cả ba mô hình. Sự khác biệt giữa các mô hình chỉ nằm ở ba tệp giá trị cấu hình và ở kênh phân phối gói.

![Hình 2.1. Ba mô hình triển khai được dựng từ cùng một nhánh mã, cùng một tập ảnh đã ký và cùng một bộ Helm chart; khác biệt nằm ở tệp values và kênh phân phối](tk-ba-mo-hinh.png){width=16cm}

Hình 2.1 cho thấy chuỗi đi từ mã nguồn tới ba mô hình. Jenkins build và ký ảnh một lần, đẩy vào Harbor trung tâm. Bộ chart `vt-platform` gồm một chart cha và các chart con theo service (SVC-01 đến SVC-17) và theo hạ tầng dùng chung trong cell (PostgreSQL qua CNPG, Kafka qua Strimzi, Valkey, OpenSearch, ClickHouse, OpenBao, ngăn xếp quan sát). Với mô hình SaaS, Argo CD trong cụm quản trị kéo trực tiếp từ kho Git trung tâm. Với đám mây riêng, kho Git được nhân bản về hạ tầng của khách hàng và Argo CD tại chỗ đồng bộ từ đó. Với bản cách ly, ảnh, chart, SBOM và chữ ký được đóng thành gói ngoại tuyến có ký số, đi qua trạm kiểm định một chiều trước khi được nạp vào Harbor nội bộ.

Ba tệp values khác nhau ở những điểm sau:

| Tham số | values-saas.yaml | values-private.yaml | values-airgap.yaml |
|---|---|---|---|
| `global.deploymentModel` | `saas` | `private` | `airgap` |
| Số cell trong một cài đặt | nhiều, theo danh bạ SVC-03 | một cell, một hoặc vài tenant | một cell theo đơn vị |
| Kho đối tượng | Ceph RGW do đội hạ tầng vận hành bằng cephadm | Rook-Ceph trong cụm hoặc S3 tương thích của khách hàng | Rook-Ceph trong cụm |
| Bộ mật mã (`crypto.provider`) | `standard` (AES-256-GCM, ECDSA P-256, RSA ≥ 2048) | `standard` | `bcy` (thuật toán Ban Cơ yếu qua lớp `CryptoProvider`) |
| Egress ra Internet | chỉ qua egress gateway tới danh sách đích | theo chính sách của khách hàng | tắt hoàn toàn (`egress.allowInternet: false`) |
| Định danh, ký số | VNeID, MySign, Viettel-CA, CA chuyên dùng Chính phủ | như SaaS hoặc IdP của khách hàng | hạ tầng định danh, chứng thư chuyên dùng |
| AI (SVC-14) | vLLM trên GPU của cell | tuỳ chọn | mô hình chạy tại chỗ, không gọi ra ngoài |
| Kênh cập nhật | liên tục theo cell (OPS-02) | theo quý, do khách hàng duyệt | LTS chậm một chu kỳ (OPS-12) |
| Hồ sơ năng lực | `sizing: L`, `M`, `S` theo cell | `sizing: S` hoặc `M` | `sizing: compact` (cho phép 3 node, một vùng sự cố) |

Việc giữ một bộ chart buộc mọi khác biệt phải được biểu diễn thành tham số có kiểm thử. Pipeline CI chạy `helm template` với cả ba tệp values và kiểm tra kết quả bằng Kyverno CLI cùng bộ chính sách dùng cho cụm thật (SEC-28), nhờ đó một thay đổi làm hỏng bản cách ly bị phát hiện ngay ở bản build, không phải chờ đến lúc kiểm định của Bộ Quốc phòng.

## Mô hình 1: SaaS trên Viettel Cloud

Mô hình SaaS phục vụ phần lớn khách hàng: các tỉnh, thành phố, khối Đảng ở Trung ương, cấp xã và chi bộ, đơn vị sự nghiệp và doanh nghiệp. Khách hàng được chia vào các cell theo bảng ở baseline mục 6, và mỗi cell là một bộ triển khai đầy đủ trên một cụm Kubernetes riêng (chương 3). Viettel vận hành toàn bộ hạ tầng, chịu trách nhiệm SLO 99,9% mỗi tháng cho mỗi cell và 99,95% cho vùng điều phối toàn cục (NFR-04).

Hạ tầng của mô hình SaaS được chia thành hai lớp. Lớp ứng dụng chạy trên máy ảo của Viettel Cloud, nhờ đó node pool ứng dụng co giãn được bằng Cluster Autoscaler (chương 6). Lớp dữ liệu chạy trên máy chủ vật lý có ổ NVMe cục bộ được gia nhập cùng cụm Kubernetes, vì PostgreSQL, Kafka, OpenSearch và ClickHouse cần độ trễ đĩa thấp và ổn định mà máy ảo dùng chung không bảo đảm được. Kho đối tượng Ceph được vận hành tách khỏi cụm Kubernetes, theo nhóm cell, như mô tả ở mục 3.8.

## Mô hình 2: đám mây riêng của tỉnh hoặc bộ

Một số khách hàng (tỉnh có trung tâm dữ liệu riêng, bộ ngành, bệnh viện lớn) yêu cầu dữ liệu nằm trên hạ tầng do họ quản lý. Khi đó nền tảng được cài thành một cell đơn trên cụm Kubernetes của khách hàng hoặc trên cụm do Viettel dựng tại hạ tầng của khách hàng. Cell này dùng hồ sơ `sizing: S` hoặc `M`, chạy Rook-Ceph trong cụm nếu khách hàng không có kho S3 tương thích, và giữ nguyên toàn bộ biện pháp an toàn thông tin của bản SaaS.

Điểm khác biệt chính về vận hành là quyền kiểm soát phát hành. Argo CD đặt tại chỗ và đồng bộ từ một bản nhân bản của kho Git cấu hình; đội vận hành của khách hàng duyệt từng lần cập nhật phiên bản. Cell đám mây riêng vẫn có thể đăng ký vào danh bạ tenant toàn cục SVC-03 để người dùng điều động liên cell được nhận diện, nhưng không gửi dữ liệu nghiệp vụ ra ngoài; chỉ số liệu tổng hợp ẩn danh được gửi lên kho phân tích quốc gia nếu khách hàng đồng ý bằng văn bản.

## Mô hình 3: cài cách ly cho lực lượng vũ trang, Công an

Bản cách ly phục vụ các đơn vị có dữ liệu thuộc bí mật quân sự hoặc bí mật nhà nước theo Luật Bảo vệ bí mật nhà nước 2018, nên chạy trên mạng nội bộ không kết nối Internet và dùng mật mã của Ban Cơ yếu Chính phủ (ADR-06). Về kỹ thuật, đây vẫn là một cell đơn dựng từ cùng chart, nhưng với hồ sơ cứng hoá: tắt mọi kết nối ra ngoài, tắt các bộ kết nối không dùng (VNeID, MySign, LGSP), bật `crypto.provider: bcy`, chạy mô hình AI tại chỗ trên GPU riêng, và tắt ứng dụng di động công khai bằng cờ tính năng nếu đơn vị yêu cầu.

Về quy trình, bản cách ly nhận cập nhật theo kênh LTS chậm hơn lõi chung một chu kỳ để kiểm định (BGĐ slide 55). Gói cập nhật là một tệp lưu trữ OCI chứa ảnh, chart, SBOM, chữ ký cosign và tệp kê khai có ký số; gói đi qua trạm kiểm định một chiều do cơ quan chuyên trách của Bộ Quốc phòng vận hành, sau đó được nạp vào Harbor nội bộ. Chi tiết đóng gói và kiểm tra được mô tả ở mục 9.9 (SEC-45, SEC-46) và quy trình quản lý thay đổi bản LTS ở OPS-12.

## Topology hai trung tâm dữ liệu

Nền tảng chạy trên hai trung tâm dữ liệu: TTDL chính tại Hoà Lạc và TTDL dự phòng phía Nam (baseline mục 6). Mỗi TTDL được chia thành ba vùng sự cố độc lập về nguồn điện, làm mát và đường mạng lõi, ký hiệu `hl-a`, `hl-b`, `hl-c` ở Hoà Lạc và `sn-a`, `sn-b`, `sn-c` ở phía Nam. Ba vùng sự cố là điều kiện để một cụm Kubernetes, một cụm etcd, một cụm PostgreSQL có bản sao đồng bộ và một cụm Kafka hệ số nhân bản 3 vẫn hoạt động khi mất trọn một vùng.

![Hình 2.2. Topology hai trung tâm dữ liệu, vùng điều phối toàn cục và các cell; mỗi cell có bản sao ấm ở TTDL phía Nam](tk-topology-hai-ttdl.png){width=16cm}

Hình 2.2 thể hiện bố trí tổng thể. Ở trạng thái bình thường, mọi cell nhận lưu lượng tại Hoà Lạc. TTDL phía Nam giữ bản sao ấm (warm standby) của từng cell: lớp dữ liệu chạy đủ kích thước và nhận dữ liệu liên tục (CNPG replica cluster nhận WAL, MirrorMaker 2 nhân bản topic Kafka, Ceph RGW multisite nhân bản đối tượng), còn lớp ứng dụng chạy ở mức tối thiểu (mỗi Deployment 1 replica, node pool ứng dụng ở kích thước nhỏ nhất) để sẵn sàng tăng lên khi chuyển vùng. Cách bố trí này cân bằng giữa chi phí và RTO: một bản sao nóng chủ động hai nơi (active-active) cho dữ liệu nghiệp vụ có ràng buộc pháp lý chặt sẽ đòi hỏi giải quyết xung đột ghi giữa hai TTDL, điều không phù hợp với yêu cầu nhất quán của hồ sơ đánh giá (TL-01, ADR-01); trong khi bản sao nguội (chỉ có sao lưu) không đạt được RTO 4 giờ khi phải khôi phục hàng chục terabyte.

Độ trễ mạng giữa Hoà Lạc và phía Nam khoảng 15–20 ms một chiều (giả định theo khoảng cách địa lý, cần đo trên đường truyền thực tế). Với độ trễ này, sao chép đồng bộ dữ liệu nghiệp vụ giữa hai TTDL sẽ cộng ít nhất 30–40 ms vào mỗi giao dịch ghi; ở tốc độ 1.200 giao dịch ghi mỗi giây của cell lớn nhất, chi phí này làm tăng số kết nối bận và độ trễ đuôi một cách đáng kể. Vì vậy dữ liệu nghiệp vụ được sao chép bất đồng bộ sang phía Nam (RPO vài giây, cam kết ≤ 15 phút theo NFR-05), còn sao chép đồng bộ chỉ dùng trong cùng TTDL giữa các vùng sự cố (RPO ≈ 0 khi mất một node CSDL). Ngoại lệ duy nhất là CSDL danh bạ tenant của vùng điều phối toàn cục, nơi tần suất ghi rất thấp (vài lần mỗi giờ) nên được phép sao chép đồng bộ chéo TTDL.

Cell CELL-DTW chạy trên hạ tầng riêng của khối Đảng với HSM riêng. Về topology, CELL-DTW cũng có bản chính và bản dự phòng tại hai địa điểm, nhưng đường sao chép đi trên kênh truyền riêng do khối Đảng quản lý, và cụm quản trị (Argo CD, Harbor, quan sát) của CELL-DTW cũng tách riêng, không dùng chung cụm quản trị của các cell SaaS khác.

Một đề xuất cần chủ đầu tư quyết định là vị trí primary của CELL-HCM. Phần lớn người dùng của cell này ở phía Nam, nên đặt primary ở TTDL phía Nam và standby ở Hoà Lạc giảm độ trễ mạng khoảng 20–30 ms mỗi lượt đi về (ước tính theo khoảng cách hơn 1.000 km cáp quang) và chia tải đều hơn giữa hai TTDL. Đổi lại, quy trình DR của CELL-HCM sẽ ngược chiều với các cell khác. Thiết kế của TL-04 cho phép điều này mà không đổi chart, vì vai trò primary của mỗi cell được khai báo trong `cells/<cell>/cell.yaml` (mục 11.2); tài liệu này giữ mặc định Hoà Lạc là primary cho mọi cell cho tới khi có quyết định.

## Vùng điều phối toàn cục và các cell

Vùng điều phối toàn cục chứa những thành phần mà mọi cell cần nhưng không chứa dữ liệu nghiệp vụ của tenant. Đó là danh bạ tenant SVC-03 (ánh xạ tenant sang cell, tên miền, mô hình thuê bao, IdP), bộ định tuyến toàn cục gồm GeoDNS và APISIX lớp ngoài, kho gốc Policy Pack và UI Pack có ký số được đồng bộ xuống cell, nguồn gốc (origin) của CDN tài nguyên tĩnh, và kho phân tích quốc gia chỉ nhận dữ liệu tổng hợp. Vùng điều phối toàn cục chạy chủ động ở cả hai TTDL trên hai cụm Kubernetes riêng (`global-hl`, `global-sn`), vì nó là điểm vào của người dùng khi đăng nhập qua tên miền chung và phải đạt 99,95%.

Các cell được tổ chức theo baseline mục 6 và được phân thành ba hạng kích thước để thuận tiện định cỡ và vận hành:

| Hạng | Cell | Mô hình thuê bao | Tài khoản | CCU thiết kế | Ghi chú |
|---|---|---|---|---|---|
| L | CELL-HCM | silo | 1,2 triệu | 60.000 | Cell lớn nhất, chuẩn định cỡ |
| L | CELL-HN | silo | 1,0 triệu | 50.000 | HanoiWork |
| M | CELL-G1, G2, G3, G4 | bridge, CSDL riêng mỗi tỉnh | 0,6–0,8 triệu mỗi cell | 40.000 mỗi cell | Định cỡ theo cận trên 0,8 triệu |
| M | CELL-SH | pool, bảng chung + `tenant_id` + RLS | 0,8 triệu | 40.000 | Tới hàng nghìn tenant nhỏ |
| S | CELL-DTW | silo, hạ tầng riêng | 0,3 triệu | 15.000 | HSM riêng, cụm quản trị riêng |
| S hoặc compact | Bản cách ly, đám mây riêng | silo | theo đơn vị | theo đơn vị | Một cell mỗi cài đặt |

CCU thiết kế của mỗi cell được tính bằng tỷ lệ chung 5% (300.000 CCU trên 6 triệu tài khoản). Tổng CCU thiết kế của các cell SaaS là 60.000 + 50.000 + 4 × 40.000 + 40.000 + 15.000 = 325.000, cao hơn mục tiêu toàn quốc 300.000 khoảng 8%. Phần dư này là chủ ý: phân bố người dùng giữa các cell nhóm tỉnh không đều, và một cell phải chịu được tải đỉnh của chính nó mà không trông vào phần dư của cell khác, vì giữa các cell không chia sẻ tài nguyên.

Danh bạ `registry.tenant_cell` là nguồn sự thật cho việc tenant thuộc cell nào. Khi một tenant tăng trưởng vượt sức chứa của mô hình pool (ví dụ một phường sáp nhập thành đơn vị lớn), quy trình chuyển tenant giữa cell OPS-08 cho phép chuyển sang cell bridge hoặc silo mà không đổi tên miền của tenant.

## GeoDNS và định tuyến tenant tới cell

Định tuyến phải thoả mãn hai yêu cầu có phần mâu thuẫn. Thứ nhất, request nghiệp vụ nên đi thẳng tới cell của tenant, không qua một tầng trung gian toàn cục, để vùng điều phối toàn cục không trở thành nút thắt hoặc điểm lỗi chung của cả nước. Thứ hai, người dùng chưa biết mình thuộc cell nào (ví dụ người dùng ứng dụng di động lần đầu, hoặc cán bộ của một xã truy cập qua tên miền chung) vẫn phải vào được hệ thống.

Thiết kế giải quyết bằng hai đường vào. Đường thứ nhất, cũng là đường chính, dùng tên miền riêng của tenant. Mỗi tenant có bản ghi trong `registry.tenant_domain`, và GeoDNS (PowerDNS Authoritative với bản ghi LUA có kiểm tra sức khoẻ) trả về CNAME tới điểm vào của cell, ví dụ `quanhuyen1.danhgia.vn` trỏ tới `cell-sh.edge.danhgia.vn`. Điểm vào của cell lại là một bản ghi có kiểm tra sức khoẻ trả về địa chỉ VIP của cell ở TTDL đang hoạt động. Khi chuyển vùng (DR-10), chỉ cần đổi bản ghi `cell-sh.edge` sang VIP phía Nam; thời gian sống (TTL) 60 giây bảo đảm phần lớn trình duyệt và ứng dụng di động chuyển theo trong vài phút.

Đường thứ hai dùng tên miền chung `app.danhgia.vn`, trỏ tới APISIX lớp ngoài của vùng điều phối toàn cục. Người dùng nhập mã đơn vị hoặc địa chỉ thư điện tử công vụ; APISIX lớp ngoài tra SVC-03 (có bộ nhớ đệm 60 giây) để biết tenant và cell, rồi trả về chuyển hướng 307 tới URL của cell. Sau bước khám phá này, mọi request tiếp theo đi thẳng tới cell. Ứng dụng di động lưu URL của cell sau lần khám phá đầu tiên và chỉ khám phá lại khi nhận mã lỗi chỉ định "tenant đã chuyển cell".

![Hình 2.3. Trình tự định tuyến: đường tên miền riêng của tenant đi thẳng tới cell; đường tên miền chung qua bước khám phá tenant ở vùng điều phối toàn cục](tk-dinh-tuyen-tenant.png){width=16cm}

Hình 2.3 minh hoạ hai đường. Điểm cần lưu ý là token JWT do Keycloak của cell phát hành mang claim `tenant` và `cell`; APISIX của mỗi cell từ chối token có claim `cell` khác với cell của mình. Biện pháp này ngăn một token hợp lệ ở cell này bị dùng để gọi API ở cell khác, kể cả khi hai cell cùng nằm trong một TTDL.

Cấu hình bản ghi GeoDNS dưới đây là ví dụ cho PowerDNS. Tên miền chung của vùng điều phối toàn cục dùng bản ghi LUA `ifurlup` để chỉ trả về VIP đang khoẻ: danh sách đầu tiên là VIP ưu tiên (Hoà Lạc), danh sách thứ hai chỉ được dùng khi toàn bộ danh sách đầu tiên không khoẻ. Điểm vào của cell là bản ghi A thường, do tác vụ đồng bộ `dns-sync` ghi lại theo cờ `active_site` của cell trong SVC-03.

```bash
# Vùng điều phối toàn cục: tự chuyển sang phía Nam khi kiểm tra sức khoẻ thất bại
pdnsutil add-record danhgia.vn app LUA 60 \
  "A \"ifurlup('https://app.danhgia.vn/healthz/edge', {{'203.0.113.10'}, {'198.51.100.10'}}, {stringmatch='OK', timeout=2, interval=10})\""

# Điểm vào của cell SH: bản ghi A do dns-sync ghi theo active_site, không tự chuyển
pdnsutil replace-rrset danhgia.vn cell-sh.edge A 60 203.0.113.21

# Bản ghi của tenant chỉ là CNAME tới điểm vào của cell, do OPS-07 tạo tự động
pdnsutil add-record danhgia.vn quanhuyen1 CNAME 300 cell-sh.edge.danhgia.vn.
pdnsutil rectify-zone danhgia.vn
```

Kiểm tra sức khoẻ `/healthz/edge` của cell không chỉ trả về trạng thái của APISIX mà tổng hợp ba điều kiện: APISIX nhận request, mission-core có ít nhất một nửa số replica sẵn sàng, và primary của `pg-core` đang ở TTDL đó. Điều kiện thứ ba ngăn GeoDNS gửi lưu lượng tới một TTDL mà CSDL ở đó vẫn là bản sao chỉ đọc. Việc tự động chuyển GeoDNS sang phía Nam chỉ được bật cho vùng điều phối toàn cục; với cell, quyết định chuyển vùng là quyết định có người duyệt (DR-10), nên bản ghi của cell được cấu hình để trả về VIP phía Nam chỉ sau khi chỉ huy sự cố đổi cờ `active_site` trong SVC-03, và quy trình đồng bộ bản ghi đọc cờ này.

# Thiết kế cụm Kubernetes cho một cell

## Phiên bản Kubernetes và chính sách nâng cấp

Nền tảng dùng Kubernetes bản minor n-1 so với bản mới nhất do cộng đồng phát hành, tại thời điểm viết tài liệu là Kubernetes 1.36. Chính sách n-1 cân bằng giữa hai rủi ro: bản mới nhất thường còn lỗi ở các tính năng vừa lên GA và chưa được các operator (CNPG, Strimzi, KEDA, Istio) kiểm thử đầy đủ, còn bản quá cũ sẽ hết hỗ trợ vá bảo mật sau khoảng 14 tháng và vi phạm tiêu chí E1 (thành phần còn được hỗ trợ ít nhất 12 tháng). Mỗi quý, đội nền tảng chạy nâng cấp một bản minor theo OPS-15: nâng cụm staging, chạy bộ PERF hồi quy rút gọn, sau đó nâng lần lượt cụm quản trị, cụm vùng điều phối toàn cục và các cell theo thứ tự đợt phát hành giống phát hành ứng dụng (CELL-G4 trước, CELL-HCM sau cùng). Không nâng cấp trong cửa sổ đóng băng trước mốc hạn (OPS-06).

Các thành phần nền đi kèm cụm và phiên bản tối thiểu được chốt như sau. Các phiên bản cụ thể được ghim trong kho GitOps và cập nhật theo cùng quy trình.

| Thành phần | Vai trò | Phiên bản tối thiểu | Giấy phép |
|---|---|---|---|
| Kubernetes | Điều phối | 1.36 (n-1) | Apache 2.0 |
| Cilium | CNI, NetworkPolicy, mã hoá node-node tuỳ chọn | 1.18 | Apache 2.0 |
| Istio ambient | mTLS giữa service bằng ztunnel, waypoint cho chính sách L7 (ADR-11) | 1.27 | Apache 2.0 |
| CloudNativePG + plugin Barman Cloud | PostgreSQL 17, sao lưu và PITR (ADR-02) | 1.27 | Apache 2.0 |
| Strimzi | Kafka KRaft, Kafka Connect (Debezium), MirrorMaker 2 | 0.47 | Apache 2.0 |
| KEDA | Co giãn theo sự kiện và lịch | 2.17 | Apache 2.0 |
| Kyverno | Kiểm soát tiếp nhận (admission), xác minh chữ ký ảnh | 1.15 | Apache 2.0 |
| External Secrets Operator | Nạp bí mật từ OpenBao (ADR-17) | 0.19 | Apache 2.0 |
| cert-manager | Cấp và xoay chứng thư nội bộ | 1.18 | Apache 2.0 |
| TopoLVM | Volume cục bộ trên NVMe cho CSDL | 0.36 | Apache 2.0 |
| Ceph CSI | Volume RBD dùng chung | 3.14 | Apache 2.0 |
| Argo CD, Argo Rollouts | GitOps, phát hành canary | 3.1, 1.8 | Apache 2.0 |
| Velero | Sao lưu tài nguyên Kubernetes | 1.16 | Apache 2.0 |
| Cluster API, Cluster Autoscaler | Vòng đời cụm, co giãn node | 1.10, 1.33 | Apache 2.0 |

## Số cụm và lựa chọn gộp hay tách cụm dữ liệu

Mỗi cell có một cụm Kubernetes ứng dụng ở mỗi TTDL. Câu hỏi thiết kế cần chốt là dữ liệu (PostgreSQL, Kafka, OpenSearch, ClickHouse) nên chạy trong chính cụm đó trên node pool riêng, hay chạy trên một cụm Kubernetes riêng dành cho dữ liệu.

Phương án tách cụm dữ liệu có ưu điểm là nâng cấp control plane của cụm ứng dụng không động tới CSDL, và quyền quản trị cụm dữ liệu có thể giao cho một nhóm nhỏ hơn. Nhược điểm của nó lại nặng hơn trong bối cảnh này. Thứ nhất, số control plane phải vận hành tăng gấp đôi: 8 cell SaaS ở hai TTDL đã là 16 cụm, tách dữ liệu thành 32 cụm, chưa kể cụm quản trị và cụm vùng điều phối toàn cục. Thứ hai, ứng dụng phải gọi CSDL và Kafka qua ranh giới cụm, nên cần khám phá dịch vụ đa cụm, chứng thư chéo cụm và đường mạng riêng, đồng thời mất khả năng dùng NetworkPolicy trong cùng cụm để giới hạn chính xác pod nào được nói chuyện với cổng 5432 của cụm CSDL nào. Thứ ba, các operator như CNPG và Strimzi tạo ra Service và Secret ngay trong cụm; khi tách cụm, ứng dụng phải nhận những thông tin này qua một kênh đồng bộ khác, làm tăng số bước có thể lỗi khi chuyển vùng.

TL-04 chọn **gộp ứng dụng và dữ liệu trong một cụm mỗi cell**, với ba biện pháp bù đắp cho nhược điểm của phương án gộp. Một là dữ liệu chạy trên node pool riêng có taint, nên không pod ứng dụng nào được xếp lên node CSDL và ngược lại. Hai là quyền quản trị các namespace dữ liệu được tách bằng RBAC: đội ứng dụng không có quyền `get secrets` hay `exec` trong namespace `data-*`, và đội DBA chỉ thao tác qua PAM (SEC-29, SEC-42). Ba là nâng cấp control plane được thực hiện theo kiểu lần lượt từng master, không làm khởi động lại pod trên worker; nâng cấp kubelet trên node dữ liệu được thực hiện thủ công từng node một, có chuyển primary CSDL đi trước (OPS-15). Ranh giới sự cố quan trọng nhất của nền tảng là ranh giới cell, không phải ranh giới giữa ứng dụng và dữ liệu, nên gộp trong một cụm không làm tăng phạm vi ảnh hưởng của sự cố so với thiết kế cell.

Ngoài các cụm cell, mỗi TTDL có thêm hai cụm dùng chung: cụm quản trị `mgmt-hl` (`mgmt-sn` ở phía Nam) chứa Argo CD, Harbor, Cluster API, Thanos Query và Store, Tempo, Loki, Grafana, Alertmanager; và cụm `global-hl` (`global-sn`) chứa vùng điều phối toàn cục. Như vậy hệ SaaS có 2 × (8 + 2) = 20 cụm, cộng cụm staging và cụm thử tải. CELL-DTW có cụm quản trị riêng như đã nêu ở mục 2.5.

![Hình 3.1. Cấu trúc một cụm Kubernetes của cell và các node pool](tk-cum-k8s-cell.png){width=16cm}

Hình 3.1 cho thấy cấu trúc của một cụm cell hạng L như CELL-HCM. Control plane gồm 3 master đặt trên ba vùng sự cố, mỗi master có một thành viên etcd. Phía dưới là chín node pool có taint riêng: lưu lượng đi từ pool ingress vào pool ứng dụng, pool ứng dụng gọi xuống các pool dữ liệu trong cùng cụm, còn các thành phần có yêu cầu bảo mật cao (sổ cái, ký số, OpenBao) chạy trong pool security-zone có đường mạng riêng tới HSM. Ceph và HSM nằm ngoài cụm và được chia sẻ theo nhóm cell như mô tả ở mục 3.8. Số node ghi trong hình là cấu hình của cell hạng L ở trạng thái thường; mục 3.3 và chương 4 nêu số node của từng hạng.

## Node pool

Node pool được thiết kế theo nguyên tắc mỗi loại tải có một pool với phần cứng phù hợp và một taint để chỉ tải đó được xếp lên. Bảng dưới đây áp dụng cho cell hạng L (CELL-HCM); cell hạng M và S dùng cùng cấu trúc với số node nhỏ hơn theo chương 4.

| Node pool | Phần cứng mỗi node | Số node (thường / đỉnh) | Taint | Tải chạy trên pool |
|---|---|---|---|---|
| system | VM 8 vCPU, 32 GiB | 3 / 3 | `CriticalAddonsOnly=true:NoSchedule` | CoreDNS, Cilium operator, Kyverno, ESO, cert-manager, các operator, KEDA, Prometheus |
| ingress | VM 16 vCPU, 32 GiB, NIC 25 Gbps | 3 / 6 | `vt.io/pool=ingress:NoSchedule` | APISIX + Coraza, etcd của APISIX |
| app-general | VM 16 vCPU, 64 GiB | 8 / 16 | không taint (pool mặc định) | mission-core, workflow API, iam, org, evidence, notification, integration, analytics-api, Cube, Superset, frontend origin |
| app-worker | VM 16 vCPU, 64 GiB | 4 / 16 | `vt.io/pool=worker:NoSchedule` | scoring, document, Gotenberg, ClamAV, workflow executor, search indexer, Kafka Connect |
| data-postgres | Máy vật lý 48 vCPU, 384 GiB, 4 × 3,84 TB NVMe (3 node) và 32 vCPU, 128 GiB, 2 × 3,84 TB NVMe (3 node) | 6 / 6 | `vt.io/pool=postgres:NoSchedule` | pg-core, pg-flowable, pg-platform, pg-iam, PgBouncer |
| data-kafka | Máy vật lý 16 vCPU, 64 GiB, 2 × 3,84 TB NVMe | 6 / 6 | `vt.io/pool=kafka:NoSchedule` | Kafka broker và controller, Apicurio, Valkey Cluster |
| data-search | Máy vật lý 16 vCPU, 64 GiB, 2 × 3,84 TB NVMe | 7 / 7 | `vt.io/pool=search:NoSchedule` | OpenSearch, ClickHouse, ClickHouse Keeper |
| security-zone | Máy vật lý 32 vCPU, 128 GiB, 2 × 1,92 TB NVMe, NIC riêng vào mạng HSM | 3 / 3 | `vt.io/zone=secure:NoSchedule` | ledger-service, esign-service, ai-gateway, pg-ledger, pg-secure, OpenBao |
| gpu | Máy vật lý 64 vCPU, 1 TiB, 8 GPU 80 GB HBM | 1 / 1 (HCM, HN) | `nvidia.com/gpu=present:NoSchedule` | vLLM, OCR |

Pool dữ liệu và pool vùng bảo mật không co giãn tự động, vì thêm một node CSDL là một quyết định năng lực có kế hoạch, kèm di chuyển dữ liệu. Pool ingress, app-general và app-worker co giãn bằng Cluster Autoscaler trong khoảng thường đến đỉnh (chương 6).

Node pool vùng bảo mật có hai đặc điểm riêng. Một là chỉ node trong pool này có card mạng vào phân đoạn mạng của HSM, nên dù một pod ngoài vùng bảo mật có lọt qua NetworkPolicy, nó vẫn không có đường mạng vật lý tới HSM. Hai là ổ đĩa của node vùng bảo mật được mã hoá LUKS với khoá giải mã do máy chủ Tang trong vùng quản trị cấp theo cơ chế mã hoá ràng buộc mạng (Network-Bound Disk Encryption, Clevis và Tang), nên ổ đĩa bị tháo ra khỏi TTDL không giải mã được.

## Taint, toleration và ràng buộc phân bố theo vùng sự cố

Mỗi node được gắn nhãn chuẩn `topology.kubernetes.io/zone` (ví dụ `hl-a`) và nhãn bổ sung `vt.io/rack` cho tủ máy. Mọi Deployment có từ 2 replica trở lên khai báo `topologySpreadConstraints` theo vùng sự cố với `whenUnsatisfiable: DoNotSchedule` và theo node với `ScheduleAnyway`. Ràng buộc thứ nhất bảo đảm mất một vùng sự cố không làm mất quá một phần ba số replica, đáp ứng trực tiếp tiêu chí C7 và vấn đề C01 của bản đánh giá (hai replica cùng ghim một node). Ràng buộc thứ hai rải pod trên nhiều node trong cùng vùng khi có thể, nhưng không chặn việc xếp lịch khi cụm đang co giãn.

Đoạn cấu hình dưới đây là phần dùng chung trong chart `vt-platform` cho một service chạy trên pool worker; tham số `{{ .Values.name }}` được thay bằng tên service.

```yaml
spec:
  template:
    spec:
      tolerations:
        - key: vt.io/pool
          operator: Equal
          value: worker
          effect: NoSchedule
      nodeSelector:
        vt.io/pool: worker
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: scoring-service
          matchLabelKeys:
            - pod-template-hash
        - maxSkew: 2
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: scoring-service
          matchLabelKeys:
            - pod-template-hash
```

Trường `matchLabelKeys` với `pod-template-hash` làm cho ràng buộc chỉ tính các pod cùng phiên bản ReplicaSet, nhờ đó trong lúc rollout, pod của phiên bản mới cũng được rải đều theo vùng chứ không dồn vào vùng đang có ít pod cũ nhất.

Với CSDL, ràng buộc được áp bằng cơ chế của operator. CNPG dùng `spec.affinity.topologyKey: topology.kubernetes.io/zone` và `enablePodAntiAffinity: true` với `podAntiAffinityType: required` để ba instance của một cụm PostgreSQL nằm ở ba vùng khác nhau. Strimzi dùng `rack.topologyKey: topology.kubernetes.io/zone`, vừa rải broker theo vùng vừa bật nhận diện rack của Kafka để ba bản sao của mỗi phân vùng nằm ở ba vùng.

## Namespace theo miền và vùng bảo mật

Namespace được chia theo hai chiều: miền nghiệp vụ và vùng bảo mật. Mỗi namespace có nhãn `vt.io/security-zone` để NetworkPolicy, Kyverno và Istio dùng chung một nguồn phân loại.

| Namespace | Vùng bảo mật | Thành phần | Chế độ Istio |
|---|---|---|---|
| `edge` | biên | APISIX, Coraza, etcd của APISIX | ambient, cổng vào nhận mTLS từ APISIX tới backend |
| `core` | ứng dụng | mission-core (SVC-05), workflow-service API và executor (SVC-06) | ambient |
| `identity` | ứng dụng | Keycloak (SVC-02), org-service (SVC-04) | ambient |
| `platform` | ứng dụng | evidence (SVC-08), notification (SVC-12), integration-gateway (SVC-13), search indexer (SVC-16), frontend origin (SVC-17) | ambient |
| `workers` | ứng dụng | scoring (SVC-07), document (SVC-11), Gotenberg, ClamAV | ambient |
| `analytics` | ứng dụng | analytics-api, Cube, Superset (SVC-15) | ambient |
| `secure` | bảo mật | ledger (SVC-09), esign (SVC-10), ai-gateway (SVC-14), vLLM | ambient + waypoint |
| `secure-data` | bảo mật | pg-ledger, pg-secure, OpenBao | ngoài mesh, TLS gốc |
| `data-pg` | dữ liệu | pg-core, pg-flowable, pg-platform, pg-iam, Pooler | ngoài mesh, TLS gốc |
| `data-kafka` | dữ liệu | Kafka, Kafka Connect (Debezium), Apicurio, Valkey | ngoài mesh, TLS gốc |
| `data-search` | dữ liệu | OpenSearch, ClickHouse | ngoài mesh, TLS gốc |
| `observability` | hạ tầng | OTel Collector, Fluent Bit, Prometheus | ngoài mesh |
| `egress` | ra ngoài | Egress gateway | ambient + waypoint |

Các namespace dữ liệu được để ngoài mesh có chủ đích. PostgreSQL, Kafka, Valkey, OpenSearch và ClickHouse đều có TLS gốc do operator quản lý, xác thực bằng chứng thư khách (PostgreSQL `clientcert=verify-full`, Kafka listener mTLS của Strimzi). Nếu đưa chúng vào mesh, mỗi gói tin trên đường dữ liệu có thông lượng cao nhất sẽ bị mã hoá hai lần, đồng thời luồng sao chép giữa các instance CSDL do operator thiết lập sẽ phụ thuộc thêm vào ztunnel. Việc giữ TLS gốc cho tầng dữ liệu vẫn đáp ứng yêu cầu "mTLS giữa service" của cấp độ 4, vì mọi kết nối tới tầng dữ liệu đều là TLS hai chiều có kiểm tra chứng thư.

Mọi namespace ứng dụng gắn nhãn Pod Security Standards mức `restricted` ở cả ba chế độ (`enforce`, `audit`, `warn`). Chỉ namespace `observability` (Fluent Bit cần đọc `/var/log` của node) và namespace hệ thống của CNI được miễn trừ có ghi danh trong chính sách Kyverno (SEC-26).

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: core
  labels:
    vt.io/security-zone: app
    vt.io/cell: hcm
    istio.io/dataplane-mode: ambient
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

## NetworkPolicy mặc định từ chối

Mỗi namespace bắt đầu với một chính sách từ chối mọi lưu lượng vào và ra, sau đó mở từng luồng cần thiết. Cách làm này biến sơ đồ luồng mạng thành mã có thể rà soát trong Git, và một service mới không tự động nói chuyện được với CSDL của service khác. Đây là biện pháp hạ tầng để bảo đảm tiêu chí A2 (không service nào đọc, ghi trực tiếp bảng của service khác) ngay cả khi có sai sót cấu hình ở tầng ứng dụng.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: core
spec:
  podSelector: {}
  policyTypes: ["Ingress", "Egress"]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-dns
  namespace: core
spec:
  podSelector: {}
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - { protocol: UDP, port: 53 }
        - { protocol: TCP, port: 53 }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mission-core-ingress-from-gateway
  namespace: core
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: mission-core
  policyTypes: ["Ingress"]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: edge
          podSelector:
            matchLabels:
              app.kubernetes.io/name: apisix
      ports:
        - { protocol: TCP, port: 8080 }
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: observability
      ports:
        - { protocol: TCP, port: 8081 }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mission-core-egress-to-data
  namespace: core
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: mission-core
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: data-pg
          podSelector:
            matchLabels:
              cnpg.io/poolerName: pg-core-rw
      ports:
        - { protocol: TCP, port: 5432 }
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: data-pg
          podSelector:
            matchLabels:
              cnpg.io/poolerName: pg-core-ro
      ports:
        - { protocol: TCP, port: 5432 }
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: data-kafka
          podSelector:
            matchLabels:
              strimzi.io/cluster: kafka-cell
      ports:
        - { protocol: TCP, port: 9093 }
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: data-kafka
          podSelector:
            matchLabels:
              app.kubernetes.io/name: valkey
      ports:
        - { protocol: TCP, port: 6379 }
```

Chính sách mẫu cho thấy mission-core chỉ được vào từ APISIX ở namespace `edge` (cổng 8080) và từ Prometheus ở namespace `observability` (cổng quản trị 8081), chỉ được ra tới Pooler của `pg-core` (không tới `pg-flowable` hay `pg-ledger`), tới listener mTLS của Kafka và tới Valkey. Mission-core không có luồng mạng tới namespace `secure`: mọi trao đổi với ledger-service đi qua Kafka (outbox), đúng nguyên tắc của ADR-09 rằng sổ cái không nằm trên đường găng của request.

## Istio ambient và mTLS STRICT

Theo ADR-11, mTLS giữa các service ứng dụng dùng Istio ở chế độ ambient. Trong chế độ này, mỗi node chạy một ztunnel đảm nhiệm mTLS ở tầng 4 cho mọi pod của namespace đã gắn nhãn `istio.io/dataplane-mode: ambient`, không cần sidecar trong từng pod. So với chế độ sidecar, ambient tiết kiệm khoảng 0,2–0,5 vCPU và 100–200 MiB bộ nhớ mỗi pod (ước tính theo cấu hình sidecar Envoy mặc định); với khoảng 150 pod ứng dụng mỗi cell lớn, phần tiết kiệm này tương đương 30–75 vCPU. Độ trễ tăng thêm của ztunnel được baseline ước tính 0,5 ms mỗi chặng và được đo trong PERF-25.

Chính sách mTLS được đặt ở chế độ STRICT cho toàn cụm, sau đó mỗi namespace có `AuthorizationPolicy` liệt kê danh tính SPIFFE được phép gọi vào. Chính sách ở tầng 4 (theo danh tính, cổng) do ztunnel thực thi; chính sách ở tầng 7 (theo đường dẫn, phương thức HTTP) cần waypoint proxy và chỉ được bật cho namespace `secure` và `egress`, nơi cần kiểm soát chi tiết theo đường dẫn API.

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: istio-system
spec:
  mtls:
    mode: STRICT
---
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: esign-allow-callers
  namespace: secure
spec:
  targetRefs:
    - kind: Service
      group: ""
      name: esign-service
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - cluster.local/ns/core/sa/mission-core
              - cluster.local/ns/core/sa/workflow-executor
      to:
        - operation:
            methods: ["POST"]
            paths: ["/internal/v1/sign-requests", "/internal/v1/sign-batches"]
```

Chính sách trên dùng `targetRefs` tới Service để được thực thi tại waypoint của namespace `secure`. Nó chỉ cho phép mission-core và workflow executor tạo yêu cầu ký và lô ký; mọi đường dẫn khác của esign-service, kể cả đường dẫn quản trị, bị từ chối từ mọi nguồn trong cụm.

## Lưu trữ: Ceph RBD và volume NVMe cục bộ

Nền tảng dùng ba lớp lưu trữ, mỗi lớp phục vụ một loại dữ liệu với yêu cầu khác nhau về độ trễ, độ bền và khả năng di chuyển.

Lớp thứ nhất là volume NVMe cục bộ qua TopoLVM, dùng cho PostgreSQL, Kafka, OpenSearch và ClickHouse. Các hệ này tự nhân bản dữ liệu ở tầng ứng dụng (sao chép luồng của PostgreSQL, hệ số nhân bản 3 của Kafka, bản sao chỉ mục của OpenSearch, bản sao bảng của ClickHouse), nên không cần lớp lưu trữ bên dưới nhân bản thêm. Đĩa cục bộ cho độ trễ ghi WAL dưới 100 micro giây, so với 1–3 ms của volume mạng, và đây là khác biệt quyết định tới thông lượng ghi của `pg-core` ở giờ cao điểm. TopoLVM quản lý dung lượng theo nhóm volume LVM trên từng node và tích hợp với bộ xếp lịch, nên pod CSDL chỉ được xếp lên node còn đủ dung lượng.

Lớp thứ hai là Ceph RBD qua Ceph CSI, dùng cho volume cần di chuyển được giữa các node: etcd của APISIX, OpenBao (Raft), Loki và Tempo ở cụm quản trị, vùng làm việc tạm của Gotenberg. Pool RBD dùng nhân bản 3 bản sao trên ba vùng sự cố.

Lớp thứ ba là Ceph RGW với API S3, dùng cho minh chứng (SVC-08), sản phẩm sinh ra của document-service (SVC-11), kho sao lưu và khối dữ liệu dài hạn của Thanos, Loki, Tempo. Tệp minh chứng nằm trong bucket bật Object Lock chế độ compliance theo ADR-13, đáp ứng tiêu chí C8, E4 và I5.

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: nvme-local
provisioner: topolvm.io
parameters:
  topolvm.io/device-class: nvme
  csi.storage.k8s.io/fstype: xfs
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
reclaimPolicy: Retain
---
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: ceph-rbd
provisioner: rbd.csi.ceph.com
parameters:
  clusterID: ceph-hl-1
  pool: k8s-rbd-hcm
  imageFeatures: layering,exclusive-lock,object-map,fast-diff
  csi.storage.k8s.io/fstype: ext4
  csi.storage.k8s.io/provisioner-secret-name: csi-rbd-provisioner
  csi.storage.k8s.io/provisioner-secret-namespace: ceph-csi
  csi.storage.k8s.io/node-stage-secret-name: csi-rbd-node
  csi.storage.k8s.io/node-stage-secret-namespace: ceph-csi
  encrypted: "true"
  encryptionKMSID: openbao-kms
volumeBindingMode: Immediate
allowVolumeExpansion: true
reclaimPolicy: Delete
```

`reclaimPolicy: Retain` cho volume NVMe là chủ ý: khi một PVC của CSDL bị xoá nhầm, volume vẫn còn trên đĩa để khôi phục. Volume RBD bật mã hoá ở phía client với khoá do OpenBao quản lý, bổ sung cho mã hoá LUKS ở ổ đĩa node.

Ceph không chạy trong cụm của từng cell. Đội hạ tầng vận hành cụm Ceph bằng cephadm theo nhóm cell: cụm `ceph-hl-1` phục vụ CELL-HCM và CELL-HN, cụm `ceph-hl-2` phục vụ CELL-G1 đến G4 và CELL-SH, và CELL-DTW có cụm Ceph riêng trên hạ tầng khối Đảng. Mỗi cell có một realm RGW riêng (realm, zonegroup, zone, người dùng, khoá và chính sách sao chép riêng), nên dù chung phần cứng, cấu hình multisite và khoá truy cập của các cell tách hẳn nhau. Việc gom Ceph theo nhóm cell thay vì một cụm Ceph mỗi cell là thoả hiệp về chi phí: một cụm Ceph erasure coding 4+2 cần tối thiểu 8 máy chủ lưu trữ để có biên an toàn khi mất một máy, nên chia nhỏ tới mức một cụm mỗi cell sẽ làm số máy chủ lưu trữ tăng gấp ba trong khi dung lượng mỗi cụm còn nhỏ. Ghi chú về đề xuất này được nêu trong tệp ghi chú của TL-04.

## Cổng vào: Apache APISIX

APISIX (SVC-01) là cổng vào duy nhất của cell. Nó chạy trên node pool `ingress` với tối thiểu 3 replica (một mỗi vùng sự cố), lưu cấu hình trong etcd 3 node của chính nó, và nhận cấu hình tuyến từ kho GitOps qua APISIX Ingress Controller (CRD `ApisixRoute`, `ApisixPluginConfig`). Các chức năng mà APISIX đảm nhiệm ở tầng hạ tầng gồm: kết thúc TLS 1.3 (chỉ cho phép TLS 1.2 với bộ mã AEAD cho thiết bị cũ có danh sách), tường lửa ứng dụng web bằng Coraza với bộ quy tắc OWASP CRS (SEC-34), xác thực JWT qua JWKS của Keycloak trong cell và kiểm tra claim `cell`, giới hạn tốc độ theo người dùng, tenant và API (mục 6.8), gắn header bảo mật (SEC-24), sinh và truyền `traceparent` W3C (B2), và định tuyến strangler giữa hệ cũ và nền tảng mới (ADR-20).

```yaml
apiVersion: apisix.apache.org/v2
kind: ApisixRoute
metadata:
  name: mission-core
  namespace: edge
spec:
  http:
    - name: tasks-api
      match:
        hosts: ["*.danhgia.vn"]
        paths: ["/api/v1/tasks*", "/api/v1/plans*", "/api/v1/okr*", "/api/v1/evidences*", "/api/v1/appraisals*"]
      backends:
        - serviceName: mission-core
          servicePort: 8080
          resolveGranularity: service
      plugin_config_name: protected-api
      timeout:
        connect: 2s
        send: 10s
        read: 10s
    - name: legacy-voffice
      match:
        hosts: ["*.danhgia.vn"]
        paths: ["/kpi-legacy/*"]
      backends:
        - serviceName: legacy-voffice-proxy
          servicePort: 80
      plugin_config_name: protected-api
```

Thời gian chờ đọc 10 giây ở cổng là giới hạn trên cho mọi API đồng bộ; tác vụ dài phải trả 202 kèm mã theo dõi (NFR-03, baseline mục 13), nên không API nghiệp vụ nào cần chờ lâu hơn. Giới hạn này cũng đáp ứng tiêu chí F2 ở tầng cổng: không có lời gọi nào chờ vô hạn.

# Định cỡ năng lực

## Phương pháp định cỡ

Định cỡ năng lực đi theo bốn bước. Bước một quy đổi chỉ tiêu người dùng (NFR-01) thành lưu lượng tại cổng API bằng định luật Little, đúng như baseline mục 3.1. Bước hai phân bổ lưu lượng cho từng cell theo tỷ lệ tài khoản, rồi phân bổ lưu lượng của cell cho từng service theo cơ cấu request quan sát được từ hành vi người dùng trong TL-02. Bước ba chia lưu lượng của mỗi service cho thông lượng một pod (giá trị giả định, sẽ được hiệu chuẩn bằng PERF-02) để ra số pod, rồi cộng thêm dự phòng mất một vùng sự cố. Bước bốn suy ra tải lên tầng dữ liệu (giao dịch ghi, sự kiện Kafka, dung lượng lưu trữ) và định cỡ từng hệ dữ liệu, có kiểm tra ngược ràng buộc về số kết nối CSDL.

Mọi con số trong chương này thuộc một trong ba loại, được ghi rõ ở từng bảng. "Số baseline" lấy từ baseline mục 3 và đã được bốn tài liệu thống nhất. "Giả định" là giá trị đầu vào chưa đo được, chọn ở mức thận trọng theo kinh nghiệm với cùng ngăn xếp Java 21, Spring Boot 3.5 và PostgreSQL 17. "Ước tính" là kết quả tính từ số baseline và giả định. Không có con số nào trong chương này là số đo; số đo sẽ có sau khi chạy các kịch bản PERF ở chương 7, và bảng định cỡ được cập nhật theo kết quả đo trước mỗi đợt mở rộng.

## Từ chỉ tiêu người dùng tới lưu lượng mỗi cell

Theo baseline mục 3.1, với L = 300.000 người dùng đồng thời và thời gian suy nghĩ trung bình Z ≈ 20 giây giữa hai request, lưu lượng toàn quốc là λ = L / Z ≈ 15.000 request/giây; hệ số đột biến 2 cho ngày hạn nộp đưa lưu lượng thiết kế lên 30.000 request/giây. Tỷ lệ đọc/ghi 80/20 cho 3.000 request ghi mỗi giây ở mức thường và 6.000 ở mức đột biến.

Bảng sau áp dụng cùng công thức cho từng cell. CCU thiết kế lấy tỷ lệ 5% số tài khoản; cell nhóm tỉnh được định cỡ theo cận trên 0,8 triệu tài khoản.

| Cell | Tài khoản | CCU thiết kế | RPS thường | RPS đột biến | Ghi đột biến (RPS) | Sự kiện Kafka nghiệp vụ đột biến (/s) | Hoàn thành user task đỉnh ước tính (/s) |
|---|---|---|---|---|---|---|---|
| CELL-HCM | 1,2 triệu | 60.000 | 3.000 | 6.000 | 1.200 | 1.800 | 134 |
| CELL-HN | 1,0 triệu | 50.000 | 2.500 | 5.000 | 1.000 | 1.500 | 112 |
| CELL-G1…G4 (mỗi cell) | 0,8 triệu | 40.000 | 2.000 | 4.000 | 800 | 1.200 | 89 |
| CELL-SH | 0,8 triệu | 40.000 | 2.000 | 4.000 | 800 | 1.200 | 89 |
| CELL-DTW | 0,3 triệu | 15.000 | 750 | 1.500 | 300 | 450 | 34 |
| Tổng (thiết kế) | 6,5 triệu | 325.000 | 16.250 | 32.500 | 6.500 | 9.750 | 725 |

Cột sự kiện Kafka dùng hệ số 1,5 sự kiện mỗi request ghi theo baseline. Tổng tài khoản 6,5 triệu cao hơn 6 triệu của NFR-01 vì mỗi cell được định cỡ với dư địa tăng trưởng. Cột user task quy tỷ lệ của baseline (khoảng 670 lần hoàn thành mỗi giây cho 6 triệu tài khoản) theo số tài khoản của từng cell: CELL-HCM là 1,2 / 6 × 670 ≈ 134 lần mỗi giây (ước tính), cao hơn dải 80–110 mà baseline ghi cho cell lớn. TL-04 thống nhất với TL-01: năng lực thiết kế của workflow-service ở cell lớn là 150 user task mỗi giây, và khi một cell cần vượt 300 user task mỗi giây thì tách engine theo cell con thay vì tiếp tục tăng tài nguyên. Tổng 725 lần mỗi giây là trường hợp mọi cell cùng đạt đỉnh trong một giờ, bi quan hơn con số 670 của baseline. Kịch bản PERF-11 kiểm chứng ở 150 lần mỗi giây và đẩy tới 300 lần mỗi giây, tức ngưỡng tách engine.

## Phân bổ lưu lượng cho từng service và số pod

Lưu lượng tại cổng API của một cell được phân bổ cho các service theo cơ cấu dưới đây. Cơ cấu này là giả định dựa trên các quy trình QT-00 đến QT-18 trong TL-02: phần lớn thao tác của cán bộ (xem việc của tôi, cập nhật tiến độ, nộp minh chứng, check-in KR, tự đánh giá) đi vào mission-core; luồng duyệt đi vào workflow-service; xem bảng điều hành đi vào analytics. Tài nguyên tĩnh (shell, remote, UI Pack) do CDN phục vụ nên không tính vào lưu lượng cổng.

Thông lượng một pod là giả định có cách tính. Ví dụ với mission-core, mỗi request đọc tốn khoảng 8 ms CPU (giải mã JWT đã được APISIX kiểm, gọi OPA chạy cùng pod qua localhost, truy vấn qua Hibernate, tuần tự hoá JSON) và mỗi request ghi tốn khoảng 20 ms CPU (thêm kiểm tra quy tắc, mã hoá trường C2, ghi outbox). Với tỷ lệ 80/20, chi phí trung bình là 0,8 × 8 + 0,2 × 20 = 10,4 ms CPU mỗi request. Một pod được cấp 3 vCPU yêu cầu và giới hạn 4 vCPU, với ngưỡng HPA 85% của yêu cầu (2,55 vCPU), xử lý được khoảng 2,55 / 0,0104 ≈ 245 request/giây; làm tròn thành 250 request/giây mỗi pod.

| Service | Tỷ lệ lưu lượng | RPS đột biến ở CELL-HCM | Thông lượng mỗi pod (giả định) | Pod cần ở đột biến | min / max replicas |
|---|---|---|---|---|---|
| SVC-01 api-gateway | 100% | 6.000 | 1.500 (2 vCPU, có WAF Coraza) | 4 | 3 / 8 |
| SVC-05 mission-core | 65% | 3.900 | 250 | 16 | 6 / 24 |
| SVC-04 org-service | 5% | 300 | 400 | 1 | 3 / 6 |
| SVC-02 iam (Keycloak) | 3% + đăng nhập 100/s | 180 | 50 đăng nhập/s, 500 làm mới token/s | 3 | 3 / 8 |
| SVC-06 workflow-service API | 5% | 300 | 150 | 2 | 3 / 8 |
| SVC-08 evidence-service API | 4% | 240 | 120 (luồng tệp) | 2 | 3 / 8 |
| SVC-15 analytics-api + Cube | 5% | 300 | 100 (có pre-aggregation) | 3 | 3 / 8 |
| SVC-16 search API | 3% | 180 | 300 | 1 | 2 / 4 |
| SVC-12 notification (API, SSE) | 2% + 60.000 kết nối | 120 | 15.000 kết nối SSE | 4 | 4 / 10 |
| SVC-13 integration-gateway | 2% | 120 | 200 | 1 | 3 / 6 |
| SVC-14 ai-gateway | 0,5% | 30 | 20 (chờ vLLM) | 2 | 2 / 6 |
| Khác: manifest UI Pack, trạng thái job | 5,5% | 330 | phục vụ từ cache APISIX và mission-core | 0 | – |

Cột min/max replicas không chỉ chia đơn giản. Giá trị min bảo đảm mỗi vùng sự cố có ít nhất một pod (min ≥ 3), riêng mission-core có min 6 để mất một vùng vẫn còn 4 pod, đủ cho lưu lượng giờ thấp điểm. Giá trị max bằng số pod cần ở đột biến nhân 1,5, để khi mất một vùng sự cố trong lúc đột biến, hai vùng còn lại vẫn có đủ chỗ cho số pod cần thiết sau khi bộ xếp lịch chuyển pod. Max của mission-core còn bị chặn bởi ngân sách kết nối CSDL (mục 4.7), và giá trị 24 nằm trong ngân sách đó.

Các service xử lý bất đồng bộ không định cỡ theo RPS mà theo thông lượng hàng đợi và theo chỉ tiêu NFR-06.

| Service | Tải định cỡ | Cách tính | min / max replicas |
|---|---|---|---|
| SVC-07 scoring-service | Tính lại 1,2 triệu hồ sơ trong ≤ 2 giờ (NFR-06) | 1.200.000 / 7.200 s ≈ 167 hồ sơ/s; một pod 2 vCPU xử lý ≈ 50 hồ sơ/s (giả định: 40 ms CPU, 120 ms chờ I/O mỗi hồ sơ, 16 luồng ảo song song); cần 4 pod, mục tiêu hoàn thành trong 1 giờ để có dư nên cần 8 pod | 2 / 24 (topic đầu vào 48 phân vùng) |
| SVC-11 document-service + Gotenberg | Sinh 100.000 phiếu PDF/A trong ≤ 4 giờ (NFR-06) | 100.000 × 1,5 s CPU ≈ 150.000 s CPU; chia 14.400 s ≈ 10,4 vCPU; hiệu suất 50% nên cần ≈ 21 vCPU, tức 11 pod Gotenberg 2 vCPU | document 2 / 20, Gotenberg 2 / 24 |
| SVC-10 esign-service | Ký lô 100.000 phiếu trong ≤ 4 giờ | 7 chữ ký/s trung bình; ký lô tối đa 1.000 phiếu mỗi phiên ký theo TL-01, tức 100 phiên; mỗi lần ký từ xa 1–3 s | 2 / 6 |
| SVC-06 workflow executor | Job bất đồng bộ của Flowable: hẹn giờ, service task gửi sự kiện | Năng lực thiết kế 150 user task/s sinh ≈ 300 job/s; một pod 2 vCPU xử lý ≈ 80 job/s (giả định), 4 pod ở đỉnh | 2 / 10 |
| SVC-09 ledger-service | 1.800 sự kiện/s đỉnh | Một pod Go 1 vCPU xử lý ≈ 2.000 bản ghi chuỗi băm/s (SHA-256 1 KB ≈ 2 µs, chi phí chính là ghi lô vào CSDL); số replica bằng số phân vùng consumer | 6 / 12 (topic 12 phân vùng) |
| ClamAV scanner (thuộc SVC-08) | 8 tệp × 1,2 triệu người × 30% trong 3 ngày cuối | ≈ 2.880.000 tệp / 86.400 s ≈ 33 tệp/s đỉnh với hệ số giờ cao điểm; một pod quét ≈ 10 tệp 1 MB/s | 2 / 12 |
| SVC-16 search indexer | Sự kiện EVT-04, EVT-24 và CDC danh mục | ≈ 500 tài liệu/s đỉnh, một pod ≈ 1.000 tài liệu/s theo lô | 2 / 6 |

## Tài nguyên tính toán và số node

Từ số pod và cấu hình requests/limits ở chương 5, tổng tài nguyên của từng node pool được suy ra như sau cho cell hạng L. Mỗi node app-general 16 vCPU còn khoảng 15 vCPU khả dụng sau khi trừ phần của kubelet, ztunnel, Fluent Bit và OTel Collector; mission-core yêu cầu 3 vCPU và 8 GiB nên mỗi node chứa 4 pod mission-core, hoặc tổ hợp tương đương của các service khác.

| Node pool | CELL-HCM thường | CELL-HCM đỉnh | vCPU đỉnh | RAM đỉnh |
|---|---|---|---|---|
| system | 3 | 3 | 24 | 96 GiB |
| ingress | 3 | 6 | 96 | 192 GiB |
| app-general | 8 | 16 | 256 | 1.024 GiB |
| app-worker | 4 | 16 | 256 | 1.024 GiB |
| data-postgres | 6 | 6 | 240 | 1.536 GiB |
| data-kafka | 6 | 6 | 96 | 384 GiB |
| data-search | 7 | 7 | 112 | 448 GiB |
| security-zone | 3 | 3 | 96 | 384 GiB |
| gpu | 1 | 1 | 64 + 8 GPU | 1.024 GiB |
| Tổng | 41 node | 64 node | 1.240 vCPU | ≈ 6,0 TiB |

Bảng tổng hợp theo hạng cell và toàn quốc dưới đây là ước tính. Cell hạng M dùng cùng cấu trúc với pool ứng dụng nhỏ hơn khoảng 30% và pool dữ liệu nhỏ hơn; cell hạng S (CELL-DTW) dùng ba node cho mỗi pool dữ liệu.

| Hạng | Số cell | Node thường / đỉnh mỗi cell | vCPU thường / đỉnh mỗi cell | RAM đỉnh mỗi cell | GPU mỗi cell |
|---|---|---|---|---|---|
| L (HCM) | 1 | 41 / 64 | 872 / 1.240 | 6,0 TiB | 8 |
| L (HN) | 1 | 40 / 60 | 856 / 1.176 | 5,7 TiB | 8 |
| M (G1…G4, SH) | 5 | 33 / 49 | 640 / 896 | 4,0 TiB | 4 |
| S (DTW) | 1 | 24 / 31 | 352 / 464 | 1,9 TiB | 4 |
| Cụm vùng điều phối toàn cục và cụm quản trị | 2 mỗi TTDL | 18 / 18 | 520 / 520 | 2,0 TiB | 0 |
| Toàn quốc TTDL Hoà Lạc | 8 cell + 2 cụm | 288 / 418 | 5.800 / 7.880 | ≈ 36 TiB | 40 |
| TTDL phía Nam (bản sao ấm) | 8 cell + 2 cụm | ≈ 200 | ≈ 4.560 | ≈ 24 TiB | 0 (AI tạm ngừng khi chuyển vùng) |

TTDL phía Nam ở trạng thái bình thường chạy đủ pool dữ liệu (để nhận bản sao) và pool ứng dụng ở mức tối thiểu. Khi chuyển vùng, pool ứng dụng phải tăng lên mức đỉnh của cell bị chuyển, nên cần hợp đồng giữ chỗ năng lực (capacity reservation) trên Viettel Cloud phía Nam khoảng 2.000 vCPU máy ảo, đủ cho hai cell hạng L ở mức đỉnh hoặc toàn bộ cell ở mức thường. Trong chế độ chuyển vùng, trợ lý AI (SVC-14) tạm ngừng vì không giữ GPU dự phòng; đây là suy giảm có chủ ý, vì AI chỉ hỗ trợ và không nằm trên đường găng của quy trình đánh giá.

## Định cỡ PostgreSQL

Mỗi cell có sáu cụm PostgreSQL do CNPG quản lý, chia theo ranh giới sở hữu dữ liệu của baseline mục 5 và theo vùng bảo mật. Việc chia cụm theo service thay vì dồn tất cả vào một cụm có hai mục đích: cô lập tải (truy vấn nặng của Flowable không tranh CPU với giao dịch của mission-core) và cô lập quyền (cụm `pg-ledger` nằm trong vùng bảo mật, DBA của cụm ứng dụng không có quyền trên đó).

| Cụm | Chứa | Số instance | vCPU / RAM mỗi instance (HCM) | Dung lượng dữ liệu năm 1 (ước tính) | Volume |
|---|---|---|---|---|---|
| pg-core | 8 schema của mission-core + `org_replica` | 3 (primary, standby đồng bộ, standby bất đồng bộ đọc) | 32 vCPU / 256 GiB | ≈ 2 TB | data 6 TB, WAL 1 TB NVMe |
| pg-flowable | `flowable` (SVC-06) | 3 | 16 vCPU / 64 GiB | ≈ 400 GB runtime + lịch sử 90 ngày | data 2 TB, WAL 500 GB |
| pg-platform | `org`, `scoring`, `evidence`, `docgen`, `notify`, `integ` (mỗi service một database và tài khoản) | 3 | 16 vCPU / 64 GiB | ≈ 1,2 TB | data 3 TB, WAL 500 GB |
| pg-iam | `iam` (Keycloak) | 3 | 4 vCPU / 16 GiB | ≈ 50 GB | data 200 GB |
| pg-ledger | `ledger` (SVC-09), vùng bảo mật | 3 | 8 vCPU / 32 GiB | ≈ 600 GB | data 2 TB, WAL 300 GB |
| pg-secure | `esign`, `ai` (pgvector), vùng bảo mật | 3 | 8 vCPU / 64 GiB | ≈ 300 GB | data 1 TB |

Dung lượng `pg-core` được ước tính như sau: mỗi người dùng mỗi quý sinh khoảng 200 dòng nghiệp vụ (nhiệm vụ, tiến độ, phiên bản minh chứng, check-in, hồ sơ đánh giá) với kích thước trung bình 1 KB, nhân hai cho chỉ mục, tức 400 KB mỗi người mỗi quý; với 1,2 triệu người là 480 GB mỗi quý, khoảng 2 TB mỗi năm. Các bảng phân vùng theo kỳ (`period_id`) cho phép tách phân vùng của các kỳ đã khoá quá hai năm sang tablespace trên đĩa dung lượng lớn hơn hoặc xuất ra Parquet trên S3 có Object Lock, nên volume 6 TB đủ cho ba năm đầu. Dung lượng `pg-ledger` được ước tính với 200 bản ghi sổ cái mỗi người mỗi quý, 400 byte mỗi bản ghi, cộng chỉ mục, khoảng 600 GB mỗi năm; bản ghi sổ cái phải lưu tối thiểu 10 năm (NFR-09), nên phân vùng quá ba năm được xuất ra kho lưu trữ dài hạn kèm gốc Merkle đã niêm phong để vẫn kiểm chứng được.

Yêu cầu IOPS của `pg-core` ở giờ đột biến được ước tính từ 1.200 giao dịch ghi mỗi giây, mỗi giao dịch ghi khoảng 6 trang dữ liệu và chỉ mục cộng một lần đồng bộ WAL: khoảng 8.000 IOPS ghi ngẫu nhiên sau khi tính hiệu ứng gộp của checkpoint, cộng 4.000 IOPS đọc khi bộ nhớ đệm trượt. Một ổ NVMe doanh nghiệp đạt trên 200.000 IOPS ghi ngẫu nhiên 4 KB, nên đĩa không phải ràng buộc; ràng buộc thật là CPU và số kết nối hoạt động, được xử lý ở mục 4.7. Lượng WAL sinh ra ở đột biến khoảng 1.200 × 8 KB ≈ 10 MB/s, ở mức trung bình ngày khoảng 1 MB/s, tức khoảng 86 GB WAL mỗi ngày trước nén.

Cấu hình tham số chính của `pg-core` ở cell hạng L được khai báo trong CR của CNPG như sau.

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: pg-core
  namespace: data-pg
spec:
  instances: 3
  imageName: harbor.vt.local/platform/postgresql:17.6-standard-bookworm@sha256:REPLACE_WITH_DIGEST
  primaryUpdateStrategy: supervised
  postgresql:
    synchronous:
      method: any
      number: 1
      dataDurability: required
    parameters:
      max_connections: "400"
      shared_buffers: "64GB"
      effective_cache_size: "192GB"
      work_mem: "32MB"
      maintenance_work_mem: "4GB"
      max_wal_size: "64GB"
      min_wal_size: "8GB"
      wal_compression: "lz4"
      checkpoint_timeout: "15min"
      checkpoint_completion_target: "0.9"
      random_page_cost: "1.1"
      effective_io_concurrency: "256"
      max_parallel_workers_per_gather: "4"
      archive_timeout: "60s"
      idle_in_transaction_session_timeout: "60s"
      statement_timeout: "30s"
      lock_timeout: "5s"
      log_min_duration_statement: "1000"
      auto_explain.log_min_duration: "2000"
      track_io_timing: "on"
      ssl_min_protocol_version: "TLSv1.3"
    pg_hba:
      - hostssl all all 0.0.0.0/0 cert
  resources:
    requests:
      cpu: "30"
      memory: "240Gi"
    limits:
      cpu: "32"
      memory: "240Gi"
  storage:
    storageClass: nvme-local
    size: 6Ti
  walStorage:
    storageClass: nvme-local
    size: 1Ti
  affinity:
    enablePodAntiAffinity: true
    podAntiAffinityType: required
    topologyKey: topology.kubernetes.io/zone
    nodeSelector:
      vt.io/pool: postgres
      vt.io/pg-size: large
    tolerations:
      - key: vt.io/pool
        operator: Equal
        value: postgres
        effect: NoSchedule
  monitoring:
    enablePodMonitor: true
```

Tham số `statement_timeout = 30s` và `lock_timeout = 5s` đáp ứng khuyến nghị của bản đánh giá ở trục F (truy vấn 30 giây thay cho 600 giây hiện nay) và ngăn một truy vấn treo giữ khoá lâu. Tham số `archive_timeout = 60s` bảo đảm ngay cả khi lưu lượng ghi thấp, một đoạn WAL vẫn được đẩy lên kho sao lưu ít nhất mỗi phút, là cơ sở của RPO ≤ 15 phút khi mất TTDL (chương 8). Khối `synchronous` với `dataDurability: required` yêu cầu mỗi giao dịch được xác nhận bởi ít nhất một standby trước khi trả về, cho RPO ≈ 0 khi mất primary trong cùng TTDL (NFR-05).

Với cell bridge (CELL-G1 đến G4), mỗi tỉnh có một database riêng trong cụm `pg-core` và `pg-platform` của cell, theo ADR-07. Một cụm có 7–9 database, mỗi database có 8 schema của mission-core. Điều này nhân số pool kết nối ở PgBouncer theo số database, nên ngân sách kết nối ở mục 4.7 phải tính theo từng database, và tham số `max_db_connections` của PgBouncer được đặt cho từng database để một tỉnh có tải đột biến không chiếm hết kết nối của các tỉnh khác.

## Định cỡ Kafka, Valkey, OpenSearch, ClickHouse

**Kafka.** Mỗi cell có một cụm Kafka chế độ KRaft do Strimzi quản lý. Ở CELL-HCM, lưu lượng vào cụm ở đột biến gồm 1.800 sự kiện nghiệp vụ mỗi giây và khoảng 7.200 bản ghi CDC mỗi giây cho kho phân tích (mỗi giao dịch ghi thay đổi trung bình 6 dòng), tổng khoảng 9.000 thông điệp mỗi giây, kích thước trung bình 1,5 KB, tức khoảng 13,5 MB/s. Với hệ số nhân bản 3, cụm ghi khoảng 40 MB/s xuống đĩa; mỗi topic có trung bình 4 nhóm consumer nên lưu lượng đọc khoảng 54 MB/s. Đây là mức tải nhỏ so với năng lực một broker trên NVMe (hàng trăm MB/s), nên số broker được quyết định bởi yêu cầu sẵn sàng và số phân vùng chứ không bởi thông lượng: 6 broker (hai mỗi vùng sự cố) và 3 controller KRaft tách riêng.

Dung lượng được tính với lưu lượng trung bình ngày bằng 25% đỉnh, tức 3,4 MB/s hay khoảng 290 GB mỗi ngày trước nhân bản. Thời gian lưu giữ bình quân có trọng số khoảng 5 ngày (topic nghiệp vụ 7 ngày, topic CDC 3 ngày, topic hàng đợi lỗi DLQ 30 ngày nhưng nhỏ), nhân hệ số nhân bản 3 được khoảng 4,4 TB, sau nén zstd với hệ số khoảng 2 còn 2,2 TB. Mỗi broker dùng một ổ NVMe 3,84 TB cho dữ liệu Kafka, tổng 23 TB, dư khoảng 10 lần để chịu được tình huống consumer dừng nhiều ngày hoặc phát lại dữ liệu sau sự cố. Ổ NVMe thứ hai trên node data-kafka dành cho Valkey và cho mở rộng JBOD sau này.

| Nhóm topic | Ví dụ | Phân vùng | Lưu giữ | Ghi chú |
|---|---|---|---|---|
| Nghiệp vụ lưu lượng cao | `task.task.progressed.v1`, `task.task.statuschanged.v1`, `okr.keyresult.checkedin.v1` | 24 | 7 ngày | khoá `tenantId:aggregateId` giữ thứ tự theo bản ghi |
| Đầu vào tính điểm | `product.evidence.accepted.v1`, `config.period.locked.v1`, `scoring.recompute.requested.v1` | 48 | 7 ngày | cho phép tới 48 consumer khi KEDA co giãn scoring |
| Nghiệp vụ lưu lượng thấp | `policy.pack.published.v1`, `oversight.complaint.filed.v1` | 6 | 30 ngày | |
| Đầu vào sổ cái | `sys.ledger.append.v1` | 12 | 14 ngày | đủ cho phát lại khi khôi phục sổ cái |
| CDC phân tích | `cdc.core.task.task`, `cdc.core.appraisal.score_snapshot` | 12 | 3 ngày | chỉ cột C0, C1 và C2 đã giả danh hoá |
| Hệ thống: kiểm toán, xác thực, lưu đối tượng, cấp tenant | `sys.audit.v1`, `iam.auth.event.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1` | 12 | 7 ngày | `sys.audit.v1` và `iam.auth.event.v1` đẩy sang SIEM và kho Object Lock |
| Hàng đợi lỗi | `<topic>.dlq.<consumer>`, thử lại `<topic>.retry.<consumer>` | 3 | 30 ngày | quy ước đặt tên theo TL-01; OPS-24 xử lý |

Tổng số phân vùng của một cell khoảng 1.500–2.500 tính cả bản sao, trong giới hạn khuyến nghị của KRaft. Tham số cụm: `min.insync.replicas=2`, `acks=all` ở producer, `unclean.leader.election.enable=false`, `default.replication.factor=3`, nhờ đó mất một vùng sự cố không mất sự kiện đã xác nhận.

**Valkey.** Mỗi cell có một Valkey Cluster 3 shard, mỗi shard một primary và một replica ở vùng sự cố khác, mỗi node 4 vCPU và 16 GiB (`maxmemory 10gb`, chính sách `allkeys-lfu`). Tập dữ liệu nóng của CELL-HCM gồm quyền và phạm vi tổ chức của người dùng đang hoạt động (khoảng 60.000 × 4 KB = 240 MB ở một thời điểm, khoảng 2–3 GB cho toàn bộ người dùng trong ngày), trạng thái kỳ đánh giá, Policy Pack và UI Pack đã biên dịch, bộ đếm giới hạn tốc độ của APISIX và Valkey Streams của notification-service; tổng ước tính 8–12 GB, nằm trong sức chứa 30 GB. Thông lượng đỉnh ước tính 50.000 lệnh mỗi giây trên toàn cụm, thấp hơn nhiều năng lực một shard. Theo ADR-18, khoá có tiền tố `{tenant}:{module}:` và không chứa dữ liệu C2 hoặc C3 dạng rõ.

**OpenSearch.** CELL-HCM có 3 node quản lý cụm (2 vCPU, 4 GiB) và 3 node dữ liệu (8 vCPU, 32 GiB, heap 16 GiB, 2 TB NVMe mỗi node). Số tài liệu được ước tính khoảng 36 triệu nhiệm vụ mỗi quý cộng văn bản và danh mục, khoảng 150 triệu tài liệu mỗi năm, 2 KB mỗi tài liệu, 300 GB dữ liệu chính mỗi năm, 600 GB khi tính một bản sao. Chỉ mục được chia theo năm (`tasks-hcm-2027`) với 6 shard chính, và chỉ mục của các năm cũ được chuyển sang trạng thái chỉ đọc, gộp segment để giảm tài nguyên.

**ClickHouse.** CELL-HCM có cụm 2 shard × 2 bản sao, mỗi node 16 vCPU, 64 GiB, 3,84 TB NVMe, cộng 3 node ClickHouse Keeper (2 vCPU, 4 GiB). Lượng dữ liệu CDC khoảng 2 TB thô mỗi năm, sau nén cột còn khoảng 200–400 GB. Cube chạy 3–8 pod với lớp tổng hợp trước (pre-aggregation) lưu trong ClickHouse, nên phần lớn truy vấn dashboard trả về trong vài chục mili giây.

## Định cỡ pool kết nối CSDL

Bản đánh giá ghi nhận vấn đề F01: mỗi pod service Nhiệm vụ hiện mở tới 500 kết nối Oracle, nên co giãn ngang lên 10 pod sẽ đẩy 5.000 kết nối vào CSDL (bảng ở trục C). Nguyên tắc định cỡ của nền tảng mới là: tổng số kết nối mà mọi pod của mọi service có thể mở khi đạt `maxReplicas` không được vượt giới hạn của tầng gom kết nối, và tổng số kết nối thật tới PostgreSQL không được vượt `max_connections` trừ phần dự trữ. Hai bất đẳng thức sau được kiểm tra tự động trong CI của kho GitOps mỗi khi có thay đổi `maxReplicas` hoặc kích thước pool.

Bất đẳng thức phía client (kết nối từ ứng dụng tới PgBouncer):

$$\sum_{s} \left( \text{maxReplicas}_s \times \text{pool}_s \right) + C_{\text{job}} + C_{\text{admin}} \le N_{\text{pooler}} \times \text{max\_client\_conn}$$

Bất đẳng thức phía máy chủ (kết nối từ PgBouncer tới PostgreSQL):

$$N_{\text{pooler}} \times \sum_{(db,user)} \text{default\_pool\_size}_{db,user} + C_{\text{direct}} + R \le \text{max\_connections}$$

trong đó $N_{\text{pooler}}$ là số instance PgBouncer (mỗi instance mở pool riêng tới PostgreSQL), $C_{\text{direct}}$ là các kết nối không qua PgBouncer (Debezium, bản sao, công cụ sao lưu), và $R$ là phần dự trữ cho quản trị viên và giám sát. Ngoài hai bất đẳng thức cứng, số kết nối hoạt động đồng thời tới PostgreSQL nên nằm trong khoảng 2–4 lần số vCPU của primary để tránh tranh chấp khoá và chuyển ngữ cảnh.

Ví dụ áp dụng cho `pg-core` của CELL-HCM. Mission-core dùng một DataSource cho mỗi module (mỗi module một tài khoản CSDL theo ADR-02), tổng 24 kết nối mỗi pod: `task` 8, `okr` 4, `appraisal` 4, `product` 3, `config` 2, `policy` 1, `dossier` 1, `oversight` 1. PgBouncer chạy chế độ gom theo giao dịch (`pool_mode = transaction`) với `max_prepared_statements = 200` để vẫn dùng được câu lệnh chuẩn bị sẵn của trình điều khiển JDBC.

| Đại lượng | Giá trị | Ghi chú |
|---|---|---|
| maxReplicas mission-core | 24 | mục 4.3 |
| Pool mỗi pod | 24 | tổng 8 DataSource theo module |
| Kết nối client của mission-core | 24 × 24 = 576 | |
| Job migration Flyway, tác vụ đối soát hằng đêm | 20 | $C_{\text{job}}$ |
| Truy cập quản trị qua PAM | 10 | $C_{\text{admin}}$ |
| Tổng phía client | 606 | ≤ 3 × 1.000 = 3.000, đạt |
| `default_pool_size` mỗi (db, user) | task 14, okr 6, appraisal 6, product 5, config 3, policy 2, dossier 2, oversight 2 | tổng 40 mỗi instance |
| Kết nối PgBouncer tới PostgreSQL | 3 × 40 = 120 | |
| Debezium, bản sao, sao lưu, giám sát | 20 | $C_{\text{direct}}$ |
| Dự trữ | 30 | $R$ |
| Tổng phía máy chủ | 170 | ≤ `max_connections` 400, đạt |
| Kết nối hoạt động tối đa so với vCPU | 120 / 32 ≈ 3,75 | trong khoảng 2–4 lần vCPU |

Kết quả cho thấy ràng buộc thật của mission-core ở CELL-HCM không nằm ở số kết nối mà ở CPU của primary. Kiểm tra ngược theo CPU: mỗi pod mission-core ở 250 request/giây sinh khoảng 1.000 câu lệnh SQL mỗi giây (4 câu mỗi request), trong đó khoảng 40% (các truy vấn danh sách, báo cáo nhẹ) được chuyển sang standby đọc qua Pooler `pg-core-ro`; mỗi câu lệnh tốn trung bình 0,6 ms CPU trên primary. Với primary 32 vCPU ở mức sử dụng 70%, năng lực là 32 × 0,7 / 0,0006 ≈ 37.000 câu lệnh mỗi giây; mỗi pod đặt lên primary khoảng 600 câu lệnh mỗi giây, nên số pod tối đa theo CPU của primary là khoảng 61. Giá trị `maxReplicas = 24` vì vậy an toàn với biên gấp hơn hai lần, và đây chính là điều tiêu chí C5 yêu cầu: "maxReplicas tính theo SLO và giới hạn CSDL".

Với các service khác, cùng phương pháp cho các giới hạn sau ở CELL-HCM: workflow-service API và executor dùng chung `pg-flowable` với pool 10 mỗi pod, tối đa 8 + 10 = 18 pod, 180 kết nối client và 3 × 60 kết nối máy chủ; scoring-service dùng pool 8 mỗi pod tới database `scoring` trên `pg-platform`, tối đa 24 pod, 192 kết nối client. Bảng đầy đủ được sinh tự động từ tệp values và lưu cùng báo cáo CI của kho GitOps.

## Định cỡ kho đối tượng Ceph

Theo baseline mục 3.1, dung lượng minh chứng toàn quốc khoảng 192 TB dữ liệu gốc mỗi năm (6 triệu người × 4 quý × 8 tệp × 1 MB) trước khử trùng, và khoảng 290 TB thô mỗi năm khi lưu bằng erasure coding 4+2 (hệ số 1,5). Phân bổ theo nhóm cụm Ceph như sau, với cell nhóm tỉnh tính theo cận trên 0,8 triệu tài khoản nên tổng hơi cao hơn baseline:

| Cụm Ceph | Cell phục vụ | Tài khoản | Minh chứng thô năm 1 (EC 4+2) | Dung lượng thô cần lắp năm 1 (ngưỡng đầy 75%) | Ước tính sau 3 năm |
|---|---|---|---|---|---|
| ceph-hl-1 | CELL-HCM, CELL-HN | 2,2 triệu | ≈ 106 TB | ≈ 160 TB (bao gồm pool RBD, sản phẩm sinh ra, khối quan sát) | ≈ 420 TB |
| ceph-hl-2 | CELL-G1…G4, CELL-SH | 4,0 triệu | ≈ 194 TB | ≈ 290 TB | ≈ 780 TB |
| ceph-dtw | CELL-DTW | 0,3 triệu | ≈ 15 TB | ≈ 30 TB | ≈ 60 TB |
| Kho sao lưu phía Nam | mọi cell | – | – | ≈ 200 TB (EC 4+2) | ≈ 400 TB |

Mỗi cụm Ceph lưu minh chứng có tối thiểu 8 máy chủ lưu trữ trên 3 vùng sự cố (miền lỗi CRUSH là host, có quy tắc phân bố theo vùng), mỗi máy 12 ổ HDD 20 TB cho dữ liệu và 2 ổ NVMe cho BlueStore DB/WAL và chỉ mục bucket. Với cấu hình này, `ceph-hl-2` năm đầu cần khoảng 8 máy × 240 TB = 1,9 PB thô nếu lắp đầy, nên thực tế chỉ lắp 4 ổ mỗi máy (640 TB thô) và bổ sung ổ theo quý. Hệ số khử trùng theo SHA-256 (tiêu chí H3 về cảnh báo minh chứng trùng) chưa được tính vào bảng; nếu đo được tỷ lệ trùng 10–20% như thường gặp ở minh chứng là văn bản dùng lại, dung lượng thực tế sẽ thấp hơn tương ứng.

Cụm Ceph ở TTDL phía Nam có cùng dung lượng với cụm Hoà Lạc tương ứng, vì RGW multisite nhân bản toàn bộ đối tượng. Kho sao lưu phía Nam là cụm riêng, không phải cùng cụm với vùng thứ hai của RGW multisite, để một lỗi cấu hình multisite không thể xoá đồng thời bản chính và bản sao lưu (chương 8).

## Định cỡ GPU cho vLLM

Trợ lý AI (SVC-14) chỉ đọc và không nằm trên đường găng, nên được định cỡ theo mức sử dụng dự kiến chứ không theo đỉnh của toàn hệ thống. Giả định: ở giờ cao điểm, 1% CCU đang dùng trợ lý (600 phiên ở CELL-HCM), mỗi phiên gửi một yêu cầu mỗi phút, mỗi yêu cầu có khoảng 1.500 token đầu vào (gồm đoạn văn bản truy hồi RAG) và 300 token đầu ra. Như vậy CELL-HCM cần khoảng 10 yêu cầu mỗi giây, 3.000 token đầu ra mỗi giây và 15.000 token đầu vào mỗi giây.

Với mô hình tiếng Việt cỡ 30 tỷ tham số lượng tử hoá FP8 chạy trên vLLM, một GPU 80 GB đạt khoảng 1.500–2.500 token đầu ra mỗi giây ở kích thước lô 32–64 (giả định, đo lại trong PERF-26). Cần 2 GPU cho tải trên và 2 GPU dự phòng cho đột biến và bảo trì. Mô hình lớn 120 tỷ tham số của Viettel AI (BGĐ slide 61) dùng cho tác vụ phức tạp như soạn nháp OKR từ văn bản dài chạy song song tensor trên 4 GPU. OCR dùng chung GPU với mô hình nhỏ theo cơ chế chia GPU nhiều instance (MIG) hoặc chia thời gian. Kết quả: cell hạng L có một node 8 GPU; cell hạng M và S có một node 4 GPU, chỉ chạy mô hình 30 tỷ tham số và OCR. Toàn quốc khoảng 40 GPU. KEDA co giãn số replica vLLM theo chỉ số `vllm:num_requests_waiting` trong giới hạn số GPU của node, và ai-gateway trả 503 kèm `Retry-After` khi hàng đợi vượt 200 yêu cầu (mục 6.9).

# Cấu hình triển khai từng service

## Nguyên tắc cấu hình chung

Mọi service ứng dụng được triển khai bằng chart con dựng trên một chart thư viện chung `vt-service` (Helm library chart). Chart thư viện sinh ra Deployment (hoặc Rollout của Argo Rollouts cho service phát hành canary), Service, ServiceAccount, ConfigMap, ExternalSecret, PodDisruptionBudget, NetworkPolicy, ScaledObject hoặc HorizontalPodAutoscaler, ServiceMonitor và PrometheusRule. Nhờ dùng chung một chart thư viện, các yêu cầu của trục C được áp dụng một lần cho mọi service thay vì phải nhớ áp từng nơi: manifest riêng cho từng service (C2), probe đủ ba loại (C3), requests/limits và heap hợp lý (C4), co giãn tự động (C5), không trạng thái (C6), PDB và phân bố theo vùng (C7). Chính sách Kyverno ở SEC-28 đóng vai trò lưới an toàn thứ hai: Deployment nào thiếu probe hoặc thiếu requests/limits bị từ chối ngay khi Argo CD đồng bộ.

Các quy ước bắt buộc cho service Java:

1. Ảnh nền là JRE 21 tối giản (distroless Java 21 hoặc Eclipse Temurin JRE 21 bản chỉ có runtime), chạy bằng người dùng không phải root với UID 10001, hệ thống tệp gốc chỉ đọc, chỉ có thư mục `/tmp` là volume `emptyDir` giới hạn 1 GiB. Điều này đáp ứng tiêu chí C1 và khắc phục vấn đề ảnh nền Maven, `chmod 777` mà bản đánh giá đã nêu.
2. Bộ nhớ heap được đặt theo tỷ lệ phần trăm giới hạn container bằng `-XX:MaxRAMPercentage=70`, không bao giờ dùng `-Xmx` cố định. Với giới hạn 8 GiB, heap tối đa là 5,6 GiB, phần còn lại 2,4 GiB dành cho metaspace, bộ nhớ ngoài heap của Netty, ngăn xếp luồng, bộ đệm mã JIT và OTel agent. Khắc phục trực tiếp vấn đề C03 (heap 8 GiB bằng giới hạn container, dẫn tới OOMKill).
3. Bộ thu gom rác là ZGC thế hệ (`-XX:+UseZGC -XX:+ZGenerational`) cho service API có yêu cầu độ trễ đuôi (mission-core, workflow API, org-service, integration-gateway), vì thời gian dừng của ZGC dưới 1 ms không phụ thuộc kích thước heap. Service xử lý lô (scoring, document, workflow executor) dùng G1 (`-XX:+UseG1GC -XX:MaxGCPauseMillis=200`) vì ưu tiên thông lượng.
4. Bộ nhớ yêu cầu bằng bộ nhớ giới hạn (`requests.memory = limits.memory`) để pod thuộc lớp chất lượng dịch vụ ổn định và không bị chọn trục xuất trước khi node thiếu bộ nhớ. CPU giới hạn cao hơn CPU yêu cầu khoảng 30% để chịu được giai đoạn khởi động và JIT.
5. Luồng ảo của Java 21 được bật (`spring.threads.virtual.enabled=true`) cho service API; giới hạn đồng thời thật được đặt bằng pool kết nối CSDL và Bulkhead của Resilience4j, không bằng số luồng Tomcat.
6. Ba probe dùng nhóm health của Spring Boot Actuator trên cổng quản trị 8081: `startupProbe` gọi `/actuator/health/liveness` với ngưỡng chờ tới 150 giây cho JVM khởi động và nạp Policy Pack; `livenessProbe` gọi `/actuator/health/liveness`, chỉ phản ánh trạng thái nội tại của tiến trình, không kiểm tra CSDL để tránh việc CSDL chậm làm khởi động lại hàng loạt pod; `readinessProbe` gọi `/actuator/health/readiness`, có kiểm tra pool kết nối CSDL, kết nối Kafka producer và trạng thái nạp Policy Pack.
7. Tắt êm (graceful shutdown): `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=25s`, cộng `preStop` chờ 5 giây để APISIX và kube-proxy kịp rút pod khỏi danh sách đích; `terminationGracePeriodSeconds: 40`.

Service Go (tenant-registry, ledger-service, notification-service) dùng ảnh distroless static, `GOMEMLIMIT` bằng 80% giới hạn bộ nhớ và `GOMAXPROCS` theo giới hạn CPU. Service Python (ai-gateway) chạy Uvicorn với số worker bằng số vCPU giới hạn.

## Bảng cấu hình theo service

Bảng dưới đây là cấu hình chuẩn cho cell hạng L. Cell hạng M dùng cùng requests/limits với `maxReplicas` nhỏ hơn khoảng 30%; cell hạng S và bản cài compact có `minReplicas` 2 (một vùng sự cố) hoặc 3.

| Service | min / max | CPU req / lim | Bộ nhớ req = lim | JVM / runtime | Probe startup / readiness | PDB | Chiến lược phát hành |
|---|---|---|---|---|---|---|---|
| SVC-01 api-gateway | 3 / 8 | 2 / 4 | 2 GiB | APISIX, 4 worker | TCP 9080 / `/apisix/status` | minAvailable 2 | RollingUpdate, maxUnavailable 0 |
| SVC-02 iam | 3 / 8 | 2 / 3 | 3 GiB | MaxRAMPercentage=70, G1 | `/health/started` / `/health/ready` | minAvailable 2 | RollingUpdate, cache Infinispan phân tán |
| SVC-03 tenant-registry | 3 / 6 | 0,5 / 1 | 512 MiB | Go, GOMEMLIMIT 400 MiB | `/healthz` / `/readyz` | minAvailable 2 | RollingUpdate |
| SVC-04 org-service | 3 / 6 | 1 / 2 | 3 GiB | MaxRAMPercentage=70, ZGC | 150 s / actuator | minAvailable 2 | RollingUpdate |
| SVC-05 mission-core | 6 / 24 | 3 / 4 | 8 GiB | MaxRAMPercentage=70, ZGC thế hệ | 150 s / actuator | maxUnavailable 1 | Argo Rollouts canary |
| SVC-06 workflow API | 3 / 8 | 2 / 3 | 6 GiB | MaxRAMPercentage=70, ZGC | 180 s / actuator | minAvailable 2 | Argo Rollouts canary |
| SVC-06 workflow executor | 2 / 10 | 2 / 3 | 4 GiB | MaxRAMPercentage=70, G1 | 180 s / actuator | maxUnavailable 1 | RollingUpdate |
| SVC-07 scoring-service | 2 / 24 | 2 / 3 | 4 GiB | MaxRAMPercentage=70, G1 | 120 s / actuator | maxUnavailable 25% | RollingUpdate |
| SVC-08 evidence API | 3 / 8 | 2 / 3 | 3 GiB | WebFlux, MaxRAMPercentage=65, ZGC | 120 s / actuator | minAvailable 2 | RollingUpdate |
| SVC-08 ClamAV scanner | 2 / 12 | 1 / 2 | 3 GiB | clamd + bộ chuyển Kafka | 300 s nạp chữ ký / TCP 3310 | maxUnavailable 25% | RollingUpdate |
| SVC-09 ledger-service | 6 / 12 | 1 / 2 | 1 GiB | Go, GOMEMLIMIT 800 MiB | `/healthz` / `/readyz` | maxUnavailable 1 | RollingUpdate, một pod mỗi lần |
| SVC-10 esign-service | 2 / 6 | 1 / 2 | 2 GiB | MaxRAMPercentage=70, G1 | 120 s / actuator kèm kiểm tra HSM | minAvailable 1 | RollingUpdate |
| SVC-11 document-service | 2 / 20 | 1 / 2 | 3 GiB | MaxRAMPercentage=70, G1 | 120 s / actuator | maxUnavailable 25% | RollingUpdate |
| SVC-11 Gotenberg | 2 / 24 | 2 / 2 | 3 GiB | LibreOffice, Chromium | `/health` / `/health` | maxUnavailable 25% | RollingUpdate |
| SVC-12 notification | 4 / 10 | 2 / 2 | 2 GiB | Go, GOMEMLIMIT 1,6 GiB | `/healthz` / `/readyz` | maxUnavailable 1 | RollingUpdate, đóng SSE dần trong 30 s |
| SVC-13 integration-gateway | 3 / 6 | 1 / 2 | 3 GiB | MaxRAMPercentage=70, ZGC | 150 s / actuator | minAvailable 2 | RollingUpdate |
| SVC-14 ai-gateway | 2 / 6 | 1 / 2 | 2 GiB | Python 3.12, Uvicorn 2 worker | `/healthz` / `/readyz` | minAvailable 1 | RollingUpdate |
| SVC-14 vLLM | 2 / 4 (theo GPU) | 8 / 16 | 64 GiB + 1 GPU | vLLM, FP8 | 600 s nạp mô hình / `/health` | maxUnavailable 1 | Recreate từng instance |
| SVC-15 analytics-api | 2 / 6 | 1 / 2 | 2 GiB | MaxRAMPercentage=70 | 90 s / actuator | minAvailable 1 | RollingUpdate |
| SVC-15 Cube | 3 / 8 | 2 / 4 | 4 GiB | Node.js | `/readyz` / `/readyz` | minAvailable 2 | RollingUpdate |
| SVC-15 Superset | 2 / 4 | 1 / 2 | 3 GiB | Gunicorn | `/health` / `/health` | minAvailable 1 | RollingUpdate |
| SVC-16 search indexer | 2 / 6 | 1 / 2 | 2 GiB | MaxRAMPercentage=70, G1 | 90 s / actuator | maxUnavailable 1 | RollingUpdate |
| SVC-17 web-frontend origin | 2 / 4 | 0,25 / 0,5 | 256 MiB | nginx không root | `/healthz` / `/healthz` | minAvailable 1 | RollingUpdate |

Những điểm cần giải thích thêm trong bảng. Các service gọi kiểm tra phân quyền (mission-core, org-service, evidence-service, workflow-api, document-service) có thêm container `opa` trong cùng pod theo TL-01 (ADR-21), với yêu cầu 100m CPU và 128 MiB, giới hạn 256 MiB; tài nguyên trong bảng chỉ tính container ứng dụng, phần của OPA đã được cộng vào định cỡ node ở chương 4 như một khoản dự phòng 5%. ledger-service có `minReplicas` 6 thay vì 3 vì số replica gắn với số phân vùng consumer của topic `sys.ledger.append.v1` (12 phân vùng, mỗi pod nhận 2 phân vùng ở trạng thái thường); thay đổi số pod gây tái cân bằng nhóm consumer, nên ledger-service chỉ co giãn khi độ trễ consumer vượt ngưỡng kéo dài và phát hành mỗi lần một pod. vLLM dùng chiến lược `Recreate` từng instance vì mỗi instance chiếm trọn GPU; không đủ GPU trống để chạy song song pod cũ và mới. Gotenberg có CPU yêu cầu bằng giới hạn vì chuyển đổi tài liệu là tác vụ thuần CPU, cần năng lực dự đoán được để tính thời gian hoàn thành lô NFR-06.

## Helm values mẫu cho mission-core

Tệp values dưới đây là phần riêng của mission-core trong `values-saas.yaml` cho CELL-HCM. Các khoá ở mức trên cùng do chart thư viện `vt-service` diễn giải; những gì không khai báo lấy giá trị mặc định an toàn của chart (không root, hệ thống tệp chỉ đọc, bỏ mọi capability).

```yaml
mission-core:
  image:
    repository: harbor.vt.local/platform/mission-core
    digest: sha256:REPLACE_WITH_DIGEST_FROM_CI
  workload:
    kind: Rollout
  replicas:
    min: 6
    max: 24
  resources:
    requests:
      cpu: "3"
      memory: 8Gi
    limits:
      cpu: "4"
      memory: 8Gi
  jvm:
    options: >-
      -XX:MaxRAMPercentage=70
      -XX:InitialRAMPercentage=50
      -XX:+UseZGC -XX:+ZGenerational
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof
      -Djava.security.egd=file:/dev/urandom
      -javaagent:/otel/opentelemetry-javaagent.jar
  env:
    SPRING_PROFILES_ACTIVE: "saas,cell"
    VT_CELL_ID: "hcm"
    SPRING_THREADS_VIRTUAL_ENABLED: "true"
    OTEL_SERVICE_NAME: "mission-core"
    OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-agent.observability.svc:4317"
    OTEL_RESOURCE_ATTRIBUTES: "deployment.environment=prod,vt.cell=hcm"
    OTEL_TRACES_SAMPLER: "parentbased_always_on"
  datasources:
    pooler: pg-core-rw.data-pg.svc
    readPooler: pg-core-ro.data-pg.svc
    database: core
    modules:
      task:      { pool: 8, secretRef: pg-core-task }
      okr:       { pool: 4, secretRef: pg-core-okr }
      appraisal: { pool: 4, secretRef: pg-core-appraisal }
      product:   { pool: 3, secretRef: pg-core-product }
      config:    { pool: 2, secretRef: pg-core-config }
      policy:    { pool: 1, secretRef: pg-core-policy }
      dossier:   { pool: 1, secretRef: pg-core-dossier }
      oversight: { pool: 1, secretRef: pg-core-oversight }
    hikari:
      connectionTimeoutMs: 2000
      maxLifetimeMs: 1500000
      idleTimeoutMs: 300000
      leakDetectionThresholdMs: 20000
  resilience:
    httpClient:
      connectTimeout: 2s
      readTimeout: 3s
    bulkhead:
      orgService: { maxConcurrentCalls: 50, maxWaitDuration: 100ms }
      evidenceService: { maxConcurrentCalls: 30, maxWaitDuration: 100ms }
  probes:
    startup:   { path: /actuator/health/liveness,  port: 8081, periodSeconds: 5, failureThreshold: 30 }
    liveness:  { path: /actuator/health/liveness,  port: 8081, periodSeconds: 10, failureThreshold: 3, timeoutSeconds: 2 }
    readiness: { path: /actuator/health/readiness, port: 8081, periodSeconds: 5, failureThreshold: 2, timeoutSeconds: 2 }
  pdb:
    maxUnavailable: 1
  scaling:
    engine: keda
    cpuUtilization: 85
    rpsPerPod: 220
    preScaleWindows: deadline-default
  rollout:
    canarySteps: [5, 25, 50, 100]
    analysisTemplate: slo-api
  secrets:
    - name: pg-core-task
      remoteKey: kv/cells/hcm/pg-core/task
    - name: kafka-mission-core
      remoteKey: pki/cells/hcm/kafka/mission-core
  networkPolicy:
    egressTo: [pg-core, kafka, valkey, org-service, evidence-service, opa-bundle]
```

Mục `datasources.modules` biểu diễn quyết định một tài khoản CSDL cho mỗi module (ADR-02) ở tầng triển khai: mỗi module có một Secret riêng do External Secrets Operator đồng bộ từ OpenBao, và tổng các giá trị `pool` (24) là đầu vào cho phép kiểm tra ngân sách kết nối ở mục 4.7. Mục `resilience` đặt timeout HTTP 2 giây kết nối và 3 giây đọc, nằm trong khuyến nghị 3–5 giây của bản đánh giá (trục F), cùng Bulkhead riêng cho từng phụ thuộc (F5).

## Helm values mẫu cho scoring-service

Scoring-service là worker tiêu thụ Kafka, co giãn bằng KEDA theo độ trễ consumer và theo lịch mốc hạn. Phần values khác biệt chủ yếu ở cấu hình consumer và chiến lược co giãn.

```yaml
scoring-service:
  image:
    repository: harbor.vt.local/platform/scoring-service
    digest: sha256:REPLACE_WITH_DIGEST_FROM_CI
  workload:
    kind: Deployment
  nodePool: worker
  replicas:
    min: 2
    max: 24
  resources:
    requests:
      cpu: "2"
      memory: 4Gi
    limits:
      cpu: "3"
      memory: 4Gi
  jvm:
    options: >-
      -XX:MaxRAMPercentage=70
      -XX:+UseG1GC -XX:MaxGCPauseMillis=200
      -XX:+ExitOnOutOfMemoryError
      -javaagent:/otel/opentelemetry-javaagent.jar
  env:
    SPRING_PROFILES_ACTIVE: "saas,cell,worker"
    VT_CELL_ID: "hcm"
    SPRING_KAFKA_CONSUMER_GROUP_ID: "scoring-service"
    SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS: "200"
    SPRING_KAFKA_CONSUMER_PROPERTIES_MAX_POLL_INTERVAL_MS: "300000"
    SPRING_KAFKA_CONSUMER_PROPERTIES_PARTITION_ASSIGNMENT_STRATEGY: "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"
    SPRING_KAFKA_LISTENER_CONCURRENCY: "2"
    VT_SCORING_RECOMPUTE_PARALLELISM: "16"
  datasources:
    pooler: pg-platform-rw.data-pg.svc
    database: scoring
    pool: 8
  probes:
    startup:   { path: /actuator/health/liveness,  port: 8081, periodSeconds: 5, failureThreshold: 24 }
    liveness:  { path: /actuator/health/liveness,  port: 8081, periodSeconds: 10, failureThreshold: 3 }
    readiness: { path: /actuator/health/readiness, port: 8081, periodSeconds: 10, failureThreshold: 3 }
  pdb:
    maxUnavailable: 25%
  scaling:
    engine: keda
    kafka:
      bootstrapServers: kafka-cell-kafka-bootstrap.data-kafka.svc:9093
      consumerGroup: scoring-service
      topics:
        - product.evidence.accepted.v1
        - config.period.locked.v1
        - scoring.recompute.requested.v1
      lagThreshold: 500
      activationLagThreshold: 10
    preScaleWindows: deadline-default
    preScaleReplicas: 8
  terminationGracePeriodSeconds: 120
```

Bộ gán phân vùng `CooperativeStickyAssignor` giảm gián đoạn khi KEDA thêm hoặc bớt pod: chỉ các phân vùng cần chuyển mới bị thu hồi, các consumer khác tiếp tục xử lý. Thời gian kết thúc 120 giây cho phép một pod đang tính lại một lô hồ sơ hoàn tất lô hiện tại và xác nhận offset trước khi dừng. Kết hợp với Inbox idempotent (baseline mục 8), nếu pod bị dừng đột ngột thì các sự kiện chưa xác nhận sẽ được xử lý lại mà không tạo kết quả trùng.

## Manifest mẫu: Deployment, HPA và PDB

Để người đọc thấy chính xác kết quả mà chart thư viện sinh ra, phần này trình bày manifest đã được dựng (`helm template`) của org-service, một service dùng HPA thuần (không cần lịch tăng trước mốc hạn nên không cần KEDA). Mission-core dùng cùng khuôn nhưng với `kind: Rollout` và ScaledObject ở mục 6.4.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: org-service
  namespace: identity
  labels:
    app.kubernetes.io/name: org-service
    app.kubernetes.io/part-of: vt-platform
    vt.io/cell: hcm
spec:
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 0
  selector:
    matchLabels:
      app.kubernetes.io/name: org-service
  template:
    metadata:
      labels:
        app.kubernetes.io/name: org-service
        vt.io/cell: hcm
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8081"
        prometheus.io/path: /actuator/prometheus
    spec:
      serviceAccountName: org-service
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: 40
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile:
          type: RuntimeDefault
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: org-service
          matchLabelKeys: ["pod-template-hash"]
      containers:
        - name: app
          image: harbor.vt.local/platform/org-service@sha256:REPLACE_WITH_DIGEST_FROM_CI
          imagePullPolicy: IfNotPresent
          ports:
            - { name: http, containerPort: 8080 }
            - { name: mgmt, containerPort: 8081 }
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError -javaagent:/otel/opentelemetry-javaagent.jar"
            - name: SPRING_PROFILES_ACTIVE
              value: "saas,cell"
            - name: OTEL_SERVICE_NAME
              value: org-service
          envFrom:
            - secretRef:
                name: org-service-db
          resources:
            requests: { cpu: "1", memory: 3Gi }
            limits:   { cpu: "2", memory: 3Gi }
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
            periodSeconds: 5
            failureThreshold: 30
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
            periodSeconds: 10
            timeoutSeconds: 2
            failureThreshold: 3
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            periodSeconds: 5
            timeoutSeconds: 2
            failureThreshold: 2
          lifecycle:
            preStop:
              sleep:
                seconds: 5
          volumeMounts:
            - { name: tmp, mountPath: /tmp }
      volumes:
        - name: tmp
          emptyDir:
            sizeLimit: 1Gi
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: org-service
  namespace: identity
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: org-service
  minReplicas: 3
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 75
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 85
    - type: Pods
      pods:
        metric:
          name: http_server_requests_per_second
        target:
          type: AverageValue
          averageValue: "350"
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0
      selectPolicy: Max
      policies:
        - { type: Percent, value: 100, periodSeconds: 30 }
        - { type: Pods, value: 2, periodSeconds: 30 }
    scaleDown:
      stabilizationWindowSeconds: 600
      selectPolicy: Min
      policies:
        - { type: Percent, value: 20, periodSeconds: 120 }
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: org-service
  namespace: identity
spec:
  minAvailable: 2
  unhealthyPodEvictionPolicy: AlwaysAllow
  selector:
    matchLabels:
      app.kubernetes.io/name: org-service
```

Ba chi tiết trong manifest có lý do riêng. Một là ảnh được tham chiếu theo digest chứ không theo tag, đáp ứng vấn đề C06 (dùng tag `:latest`) và là điều kiện để Kyverno xác minh chữ ký đúng ảnh đã được ký (SEC-28, SEC-55). Hai là chỉ số bộ nhớ trong HPA đặt ngưỡng 85% chỉ để phát hiện tình huống bất thường; với JVM có heap cố định theo tỷ lệ, bộ nhớ không phải tín hiệu co giãn tốt, nên CPU và RPS mỗi pod mới là hai chỉ số chính. Ba là `unhealthyPodEvictionPolicy: AlwaysAllow` cho phép rút node để bảo trì ngay cả khi một pod đang không sẵn sàng; nếu không có cài đặt này, một pod lỗi kéo dài có thể chặn vô thời hạn việc rút node vì PDB tính nó vào số pod đang thiếu.

# Cơ chế co giãn tự động

## Tổng quan vòng điều khiển co giãn

Co giãn tự động của nền tảng là một vòng điều khiển nhiều tầng. Tầng pod quyết định mỗi service cần bao nhiêu replica, dựa trên tín hiệu tải. Tầng node quyết định cụm cần bao nhiêu máy ảo để chứa các replica đó. Tầng bảo vệ đặt giới hạn trên cho cả hai tầng kia để việc co giãn không chuyển tình trạng quá tải từ ứng dụng sang CSDL, và khi đã chạm giới hạn thì cắt bớt tải ở cổng một cách có kiểm soát thay vì để hệ thống sụp đổ. Bản đánh giá ghi nhận hiện trạng 0 HPA và 0 KEDA (tiêu chí C5, vấn đề C02), nên toàn bộ chương này là phần xây mới.

![Hình 6.1. Vòng điều khiển co giãn: tín hiệu đo đi vào HPA và KEDA, thiếu node thì Cluster Autoscaler thêm máy ảo, giới hạn trên được chặn bởi ngân sách kết nối CSDL và cắt tải ở APISIX](tk-vong-co-gian.png){width=16cm}

Hình 6.1 cho thấy năm loại tín hiệu: CPU và bộ nhớ từ metrics-server; RPS mỗi pod và độ trễ p95 từ Prometheus qua Prometheus Adapter; độ trễ consumer Kafka; lịch mốc hạn; số job Flowable đến hạn. Ba loại sau đi qua KEDA, vì KEDA có sẵn bộ chuyển đổi (scaler) cho Kafka, cron và Prometheus. KEDA không thay thế HPA mà sinh ra một HPA bên dưới và cấp chỉ số ngoài cho nó, nên mọi service, dù co giãn bằng HPA thuần hay bằng KEDA, cuối cùng đều chịu cùng một thuật toán của HPA với cùng cấu hình `behavior`. Một quy tắc bắt buộc đi kèm là mỗi Deployment chỉ có một bộ co giãn: service nào cần lịch tăng trước mốc hạn thì dùng ScaledObject có cả trigger CPU, không đồng thời có HPA riêng, vì hai HPA cùng điều khiển một Deployment sẽ tranh nhau đặt số replica.

## HPA autoscaling/v2 với chỉ số tài nguyên và chỉ số tuỳ chỉnh

HPA `autoscaling/v2` tính số replica mong muốn cho từng chỉ số bằng công thức `desired = ceil(current × currentValue / targetValue)` và lấy giá trị lớn nhất giữa các chỉ số. Với service API, ba chỉ số được dùng:

1. CPU sử dụng so với CPU yêu cầu, ngưỡng 75–85% tuỳ service. Đây là tín hiệu nhanh và ổn định nhất cho service Java đã định cỡ yêu cầu CPU theo thông lượng.
2. RPS mỗi pod, lấy từ chỉ số `http_server_requests_seconds_count` của Micrometer qua Prometheus Adapter. Tín hiệu này phản ứng trước CPU khi lưu lượng tăng đột ngột, vì JIT và bộ nhớ đệm làm CPU tăng chậm hơn lưu lượng trong vài chục giây đầu.
3. Độ trễ p95 chỉ dùng làm tín hiệu phụ cho mission-core dưới dạng chỉ số ngoài. Độ trễ không phải tín hiệu co giãn tốt khi đứng một mình: nếu độ trễ tăng vì CSDL chậm, thêm pod sẽ đẩy thêm tải vào CSDL và làm tình hình xấu hơn. Vì vậy chỉ số này được nhân với điều kiện "CPU trung bình trên 60%", nghĩa là chỉ khi pod thực sự bận thì độ trễ cao mới dẫn tới tăng pod.

Cấu hình Prometheus Adapter chuyển bộ đếm request của Micrometer thành chỉ số mỗi pod:

```yaml
rules:
  custom:
    - seriesQuery: 'http_server_requests_seconds_count{namespace!="",pod!=""}'
      resources:
        overrides:
          namespace: { resource: namespace }
          pod: { resource: pod }
      name:
        matches: "^http_server_requests_seconds_count$"
        as: "http_server_requests_per_second"
      metricsQuery: 'sum(rate(<<.Series>>{<<.LabelMatchers>>,uri!~"/actuator.*"}[1m])) by (<<.GroupBy>>)'
  external:
    - seriesQuery: 'http_server_requests_seconds_bucket{namespace="core",service="mission-core"}'
      resources:
        overrides:
          namespace: { resource: namespace }
      name:
        as: "mission_core_p95_when_busy_ms"
      metricsQuery: |
        (
          histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{namespace="core",service="mission-core",uri!~"/actuator.*"}[2m])) by (le)) * 1000
        )
        and on()
        (
          avg(rate(container_cpu_usage_seconds_total{namespace="core",container="app",pod=~"mission-core-.*"}[2m]))
          / avg(kube_pod_container_resource_requests{namespace="core",container="app",pod=~"mission-core-.*",resource="cpu"}) > 0.6
        )
```

Phần `behavior` của HPA được chuẩn hoá cho mọi service API: tăng không có cửa sổ ổn định (`stabilizationWindowSeconds: 0`) với tốc độ tối đa gấp đôi hoặc thêm 4 pod mỗi 30 giây, giảm với cửa sổ ổn định 10 phút và không quá 20% mỗi 2 phút. Tính bất đối xứng này phản ánh chi phí bất đối xứng của hai loại sai lầm: tăng thừa vài pod trong vài phút chỉ tốn tài nguyên, còn giảm sớm ngay trước một đợt lưu lượng mới sẽ làm vi phạm SLO, và pod Java cần 60–90 giây để khởi động và làm nóng JIT.

## KEDA theo độ trễ consumer Kafka

Các service worker (scoring, document, ClamAV scanner của evidence, notification, search indexer) co giãn theo độ trễ consumer (consumer lag) của nhóm consumer trên các topic đầu vào. Độ trễ là số thông điệp đã được ghi vào topic nhưng chưa được nhóm consumer xác nhận; nó đo trực tiếp lượng việc tồn đọng, nên là tín hiệu đúng hơn CPU cho tải dạng hàng đợi (Queue-based Load Leveling, tiêu chí E3, F8).

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: scoring-service
  namespace: workers
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: scoring-service
  minReplicaCount: 2
  maxReplicaCount: 24
  pollingInterval: 15
  cooldownPeriod: 600
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleUp:
          stabilizationWindowSeconds: 0
          policies:
            - { type: Pods, value: 6, periodSeconds: 30 }
        scaleDown:
          stabilizationWindowSeconds: 900
          policies:
            - { type: Percent, value: 25, periodSeconds: 120 }
  triggers:
    - type: kafka
      name: lag-evidence-accepted
      metadata:
        bootstrapServers: kafka-cell-kafka-bootstrap.data-kafka.svc:9093
        consumerGroup: scoring-service
        topic: product.evidence.accepted.v1
        lagThreshold: "500"
        activationLagThreshold: "10"
        offsetResetPolicy: earliest
        allowIdleConsumers: "false"
        limitToPartitionsWithLag: "true"
        tls: enable
      authenticationRef:
        name: kafka-tls-scoring
    - type: kafka
      name: lag-recompute
      metadata:
        bootstrapServers: kafka-cell-kafka-bootstrap.data-kafka.svc:9093
        consumerGroup: scoring-service
        topic: scoring.recompute.requested.v1
        lagThreshold: "200"
        activationLagThreshold: "1"
        tls: enable
      authenticationRef:
        name: kafka-tls-scoring
    - type: cpu
      metricType: Utilization
      metadata:
        value: "80"
---
apiVersion: keda.sh/v1alpha1
kind: TriggerAuthentication
metadata:
  name: kafka-tls-scoring
  namespace: workers
spec:
  secretTargetRef:
    - { parameter: tls, name: kafka-scoring-tls, key: tls }
    - { parameter: ca, name: kafka-scoring-tls, key: ca.crt }
    - { parameter: cert, name: kafka-scoring-tls, key: user.crt }
    - { parameter: key, name: kafka-scoring-tls, key: user.key }
```

Ngưỡng `lagThreshold: 500` có nghĩa là KEDA đặt mục tiêu mỗi pod có không quá 500 thông điệp tồn đọng; với tốc độ xử lý khoảng 50 hồ sơ mỗi giây mỗi pod, độ trễ xử lý của một sự kiện "minh chứng đã xác nhận" lúc đỉnh vào khoảng 10 giây, đủ cho yêu cầu "tính dần theo sự kiện" (BGĐ slide 40). `allowIdleConsumers: "false"` ngăn KEDA tạo nhiều pod hơn số phân vùng (pod thừa sẽ không nhận phân vùng nào), và `limitToPartitionsWithLag` làm cho số pod tính theo số phân vùng thực sự có tồn đọng.

Khi người dùng kích hoạt tính lại toàn bộ một kỳ (ví dụ sau khi phát hành Policy Pack sửa lỗi công thức), saga ở SVC-06 ghi 1,2 triệu yêu cầu tính lại lên topic `scoring.recompute.requested.v1` theo lô. Độ trễ trên topic này tăng vọt, KEDA tăng scoring-service từ 2 lên 24 pod trong khoảng 2 phút (6 pod mỗi 30 giây), Cluster Autoscaler thêm node worker nếu cần, và lô 1,2 triệu hồ sơ được xử lý trong khoảng 1 giờ ở 24 pod, hoặc dưới 2 giờ nếu bị giới hạn ở 12 pod. Đây là kịch bản PERF-19.

## KEDA theo lịch: tăng trước các mốc hạn

Tải của nền tảng có tính mùa vụ rất rõ và biết trước: báo cáo quý dồn vào tuần cuối quý và trước ngày 10 tháng đầu quý kế tiếp, hồ sơ năm dồn vào trước ngày 01/02 (BGĐ slide 39). Co giãn phản ứng thuần tuý (theo CPU, theo độ trễ) luôn đi sau tải vài phút, và trong vài phút đó người dùng thấy hệ thống chậm đúng lúc họ cần nộp hồ sơ. Vì vậy nền tảng tăng tài nguyên trước theo lịch: trong các cửa sổ mốc hạn, số replica tối thiểu của các service chịu tải chính được nâng lên mức đủ cho lưu lượng thường của ngày hạn, và co giãn phản ứng chỉ phải xử lý phần đột biến bên trên.

KEDA cron scaler được kích hoạt khi thời điểm hiện tại nằm giữa biểu thức `start` và `end`, và trả về `desiredReplicas`. Vì một trigger cron chỉ biểu diễn được một khoảng lặp lại, mỗi cửa sổ mốc hạn được biểu diễn bằng một trigger áp dụng cho các ngày trong khoảng, từ 06:00 đến 23:00 mỗi ngày. Bảng cửa sổ mặc định (`deadline-default`) như sau, giờ theo múi `Asia/Ho_Chi_Minh`:

| Cửa sổ | Ngày | Lý do | Mức tăng trước |
|---|---|---|---|
| Tuần cuối quý | 24–31 của tháng 3, 6, 9, 12 | Cập nhật tiến độ, nộp minh chứng dồn cuối kỳ | mission-core 16 pod, scoring 8, evidence 6 |
| Trước hạn báo cáo quý | 1–10 của tháng 1, 4, 7, 10 | Tự đánh giá, thẩm định, quyết định xếp loại quý trước ngày 10 | mission-core 16 pod, workflow API 6, executor 6, document 8 |
| Trước hạn hồ sơ năm | 20–31 tháng 1 và ngày 1 tháng 2 | Xếp loại năm, hồ sơ năm trước 01/02 | mission-core 18 pod, workflow 8, document 12, Gotenberg 12 |

ScaledObject của mission-core kết hợp trigger CPU, trigger RPS mỗi pod qua Prometheus và các trigger cron. Vì HPA lấy giá trị lớn nhất giữa các trigger, trong cửa sổ mốc hạn số pod không bao giờ dưới mức tăng trước, còn ngoài cửa sổ thì trigger cron trả về 0 và không ảnh hưởng.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: mission-core
  namespace: core
spec:
  scaleTargetRef:
    apiVersion: argoproj.io/v1alpha1
    kind: Rollout
    name: mission-core
  minReplicaCount: 6
  maxReplicaCount: 24
  pollingInterval: 15
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleUp:
          stabilizationWindowSeconds: 0
          selectPolicy: Max
          policies:
            - { type: Percent, value: 100, periodSeconds: 30 }
            - { type: Pods, value: 4, periodSeconds: 30 }
        scaleDown:
          stabilizationWindowSeconds: 600
          policies:
            - { type: Percent, value: 20, periodSeconds: 120 }
  triggers:
    - type: cpu
      metricType: Utilization
      metadata:
        value: "85"
    - type: prometheus
      name: rps-per-pod
      metadata:
        serverAddress: http://prometheus-operated.observability.svc:9090
        query: sum(rate(http_server_requests_seconds_count{namespace="core",service="mission-core",uri!~"/actuator.*"}[1m]))
        threshold: "220"
        activationThreshold: "50"
    - type: cron
      name: cuoi-quy
      metadata:
        timezone: Asia/Ho_Chi_Minh
        start: "0 6 24-31 3,6,9,12 *"
        end: "0 23 24-31 3,6,9,12 *"
        desiredReplicas: "16"
    - type: cron
      name: truoc-ngay-10-dau-quy
      metadata:
        timezone: Asia/Ho_Chi_Minh
        start: "0 6 1-10 1,4,7,10 *"
        end: "0 23 1-10 1,4,7,10 *"
        desiredReplicas: "16"
    - type: cron
      name: ho-so-nam-thang-1
      metadata:
        timezone: Asia/Ho_Chi_Minh
        start: "0 6 20-31 1 *"
        end: "0 23 20-31 1 *"
        desiredReplicas: "18"
    - type: cron
      name: ho-so-nam-ngay-1-2
      metadata:
        timezone: Asia/Ho_Chi_Minh
        start: "0 6 1 2 *"
        end: "0 23 1 2 *"
        desiredReplicas: "18"
```

Trigger Prometheus dùng giá trị tổng RPS của service với `threshold: 220`; với scaler Prometheus, KEDA mặc định coi ngưỡng là giá trị trung bình mỗi pod (kiểu `AverageValue`), nên số pod mong muốn bằng tổng RPS chia 220. Ngưỡng 220 thấp hơn thông lượng giả định 250 khoảng 12% để có biên trước khi đạt ngưỡng CPU.

Lịch mốc hạn không cố định mãi: một tỉnh có thể đặt hạn nộp khác, và Quy định mới có thể đổi mốc. Vì vậy các cửa sổ được sinh từ bảng `config.period` (MOD-08) của từng cell: hằng ngày, một tác vụ trong kho GitOps đọc hạn nộp của các kỳ đang mở qua API `/api/v1/periods`, sinh lại tệp `prescale-windows.yaml` của cell và mở yêu cầu hợp nhất (merge request) để người trực vận hành duyệt. Cửa sổ `deadline-default` ở trên là mức sàn luôn có hiệu lực.

## Co giãn node: Cluster Autoscaler trên Viettel Cloud

Khi HPA tăng số replica mà các node hiện có không đủ tài nguyên yêu cầu, pod mới ở trạng thái `Pending`. Cluster Autoscaler phát hiện pod Pending, mô phỏng xem node pool nào có thể chứa nó, và tăng số node của pool đó. Karpenter là lựa chọn phổ biến trên các đám mây công cộng nhưng chưa có provider cho Viettel Cloud, nên nền tảng dùng Cluster Autoscaler với provider Cluster API: mỗi node pool là một `MachineDeployment` của Cluster API, với provider hạ tầng là OpenStack (CAPO) nếu Viettel Cloud cung cấp API tương thích OpenStack, hoặc provider riêng do đội hạ tầng Viettel Cloud duy trì. Giới hạn của pool được khai báo bằng chú thích trên `MachineDeployment`.

```yaml
apiVersion: cluster.x-k8s.io/v1beta1
kind: MachineDeployment
metadata:
  name: hcm-app-general-hl-a
  namespace: cell-hcm
  annotations:
    cluster.x-k8s.io/cluster-api-autoscaler-node-group-min-size: "3"
    cluster.x-k8s.io/cluster-api-autoscaler-node-group-max-size: "6"
    capacity.cluster-autoscaler.kubernetes.io/cpu: "16"
    capacity.cluster-autoscaler.kubernetes.io/memory: "64Gi"
spec:
  clusterName: cell-hcm
  replicas: 3
  selector:
    matchLabels: {}
  template:
    metadata:
      labels:
        vt.io/pool: app-general
        topology.kubernetes.io/zone: hl-a
    spec:
      clusterName: cell-hcm
      version: v1.36.2
      failureDomain: hl-a
      bootstrap:
        configRef:
          apiVersion: bootstrap.cluster.x-k8s.io/v1beta1
          kind: KubeadmConfigTemplate
          name: hcm-app-general
      infrastructureRef:
        apiVersion: infrastructure.cluster.x-k8s.io/v1beta1
        kind: OpenStackMachineTemplate
        name: hcm-app-general-16c64g
```

Mỗi node pool co giãn được tách thành ba `MachineDeployment`, một cho mỗi vùng sự cố, với tham số `--balance-similar-node-groups=true` của Cluster Autoscaler để số node của ba vùng tăng đều nhau. Nếu chỉ có một `MachineDeployment` trải trên ba vùng, Cluster Autoscaler không biết node mới sẽ nằm ở vùng nào và có thể không thoả được ràng buộc phân bố theo vùng của pod Pending.

Thời gian từ lúc pod Pending tới lúc node mới sẵn sàng trên máy ảo Viettel Cloud ước tính 3–5 phút (tạo máy ảo, khởi động, gia nhập cụm, kéo ảnh). Để không phải chờ khoảng thời gian này trong đột biến, nền tảng dùng hai kỹ thuật bổ sung. Một là pod giữ chỗ (overprovisioning): mỗi pool app-general và app-worker có một Deployment `capacity-reservation` gồm các pod `pause` yêu cầu tổng cộng tương đương 2 node, chạy ở `PriorityClass` có độ ưu tiên âm. Khi pod thật cần chỗ, bộ xếp lịch trục xuất pod giữ chỗ ngay lập tức; pod giữ chỗ chuyển sang Pending và kích hoạt Cluster Autoscaler thêm node, nên luôn có sẵn một khoảng đệm 2 node. Hai là trong các cửa sổ mốc hạn, ScaledObject theo lịch đã nâng số replica từ 06:00, nên node đã được thêm từ sáng sớm trước khi người dùng vào hệ thống.

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata:
  name: capacity-reservation
value: -10
globalDefault: false
preemptionPolicy: Never
description: "Pod giữ chỗ, bị trục xuất đầu tiên khi pod thật cần tài nguyên"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: capacity-reservation-app-general
  namespace: kube-system
spec:
  replicas: 6
  selector:
    matchLabels: { app: capacity-reservation, pool: app-general }
  template:
    metadata:
      labels: { app: capacity-reservation, pool: app-general }
    spec:
      priorityClassName: capacity-reservation
      terminationGracePeriodSeconds: 0
      nodeSelector:
        vt.io/pool: app-general
      securityContext:
        runAsNonRoot: true
        runAsUser: 65535
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: pause
          image: harbor.vt.local/k8s/pause@sha256:REPLACE_WITH_DIGEST
          resources:
            requests: { cpu: "5", memory: 20Gi }
            limits:   { cpu: "5", memory: 20Gi }
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
```

## Co giãn workflow-service và async executor của Flowable

Góp ý G2 lo ngại engine BPMN trở thành nút thắt khi số tenant lên hàng trăm, hàng nghìn. TL-01 (mục phân tích G2) đã chỉ ra ba biện pháp ở tầng kiến trúc; TL-04 bổ sung biện pháp ở tầng triển khai: tách workflow-service thành hai Deployment chạy cùng một ảnh nhưng với hai hồ sơ cấu hình khác nhau.

Deployment thứ nhất, `workflow-api`, phục vụ REST cho màn hình (danh sách việc chờ duyệt, hoàn thành user task, xem lịch sử) và tắt async executor (`flowable.async-executor-activate=false`). Nó co giãn bằng HPA theo CPU và RPS như service API khác. Deployment thứ hai, `workflow-executor`, không nhận request HTTP nào ngoài cổng quản trị, bật async executor và chỉ làm việc với các job bất đồng bộ: bộ hẹn giờ (nhắc hạn, quá hạn tự chuyển cấp), service task bất đồng bộ (phát sự kiện, gọi external worker) và các bước saga. Việc tách đôi làm cho hai loại tải không tranh nhau: một đợt tồn đọng job hẹn giờ lúc nửa đêm không làm chậm thao tác duyệt của người dùng, và ngược lại.

Async executor của Flowable lấy job đến hạn bằng cách khoá dòng trong bảng `ACT_RU_JOB` và `ACT_RU_TIMER_JOB` theo lô. Cấu hình chính của `workflow-executor`:

```yaml
flowable:
  async-executor-activate: true
  process:
    async:
      executor:
        core-pool-size: 16
        max-pool-size: 16
        queue-capacity: 512
        max-async-jobs-due-per-acquisition: 64
        max-timer-jobs-per-acquisition: 64
        default-async-job-acquire-wait-time: 2s
        default-timer-job-acquire-wait-time: 5s
        async-job-lock-time: 5m
        timer-lock-time: 5m
        global-acquire-lock-enabled: true
  history-level: audit
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
```

`max-pool-size` 16 luồng mỗi pod đi kèm pool kết nối 10: mỗi luồng giữ kết nối chỉ trong thời gian thực thi một job ngắn, nên 10 kết nối đủ cho 16 luồng mà không làm luồng phải chờ lâu. Tuỳ chọn khoá thu nhận toàn cục (`global-acquire-lock-enabled`) làm cho tại mỗi thời điểm chỉ một pod thực hiện truy vấn thu nhận job trong một chu kỳ, tránh hiện tượng nhiều pod cùng quét bảng job và tranh khoá dòng khi số pod tăng; đây là cấu hình được Flowable khuyến nghị cho triển khai nhiều instance.

`workflow-executor` co giãn bằng KEDA theo số job đến hạn chưa được thu nhận. Chỉ số này do workflow-service tự xuất bằng Micrometer từ một truy vấn đếm có giới hạn chạy mỗi 15 giây trên bản sao đọc của `pg-flowable`:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: workflow-executor
  namespace: core
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: workflow-executor
  minReplicaCount: 2
  maxReplicaCount: 10
  cooldownPeriod: 600
  triggers:
    - type: prometheus
      name: flowable-due-jobs
      metadata:
        serverAddress: http://prometheus-operated.observability.svc:9090
        query: max(flowable_async_jobs_due_unlocked{namespace="core"})
        threshold: "400"
        activationThreshold: "50"
    - type: cpu
      metricType: Utilization
      metadata:
        value: "75"
```

Với ngưỡng 400 job đến hạn mỗi pod và năng lực khoảng 80 job mỗi giây mỗi pod, thời gian chờ của một job ở trạng thái tồn đọng ổn định khoảng 5 giây. Ở năng lực thiết kế 150 user task mỗi giây (khoảng 300 job mỗi giây), cần khoảng 4 pod; ở ngưỡng tách engine 300 user task mỗi giây cần khoảng 8 pod. Giới hạn trên 10 pod được chọn theo ngân sách kết nối của `pg-flowable` (mục 4.7) và theo kết quả dự kiến của PERF-11: nếu đo thấy CPU của `pg-flowable` trên 70% khi executor ở 10 pod, nghĩa là nút thắt đã nằm ở CSDL và tăng pod không giúp được; khi đó biện pháp đúng là giảm tần suất ghi lịch sử hoặc tách cell, không phải tăng executor.

## Bảo vệ CSDL khi co giãn

Nguyên tắc xuyên suốt là co giãn ứng dụng không được làm quá tải CSDL. Nguyên tắc này được thực thi bằng bốn lớp.

Lớp thứ nhất là `maxReplicas` của mọi service có kết nối CSDL được tính từ ngân sách kết nối và ngân sách CPU của cụm CSDL như mục 4.7. Bất đẳng thức ngân sách được kiểm tra bằng một bước CI trong kho GitOps: bước này dựng toàn bộ values của cell, tính tổng `maxReplicas × pool` theo từng cụm Pooler và so với `max_client_conn` cùng `default_pool_size × N_pooler` so với `max_connections`. Merge request làm vi phạm bất đẳng thức bị chặn.

Lớp thứ hai là PgBouncer ở chế độ giao dịch với `default_pool_size` theo từng cặp database và người dùng, `reserve_pool_size` nhỏ (2–3) và `query_wait_timeout = 5`. Khi mọi kết nối máy chủ đều bận, client phải chờ trong hàng đợi của PgBouncer tối đa 5 giây rồi nhận lỗi, thay vì mở thêm kết nối tới PostgreSQL.

Lớp thứ ba là pool HikariCP của ứng dụng có `connectionTimeout` 2 giây. Nếu không lấy được kết nối trong 2 giây, request thất bại nhanh với lỗi 503 kèm `Retry-After`, và chỉ số `hikaricp_connections_pending` được cảnh báo ở chương 10.

Lớp thứ tư là truy vấn đọc nặng được chuyển sang bản sao đọc qua Pooler `pg-core-ro`, và truy vấn tổng hợp cho dashboard đi qua ClickHouse (CQRS, tiêu chí A5). Như vậy khi lưu lượng xem bảng điều hành tăng đột biến vào sáng thứ Hai, primary của `pg-core` không bị ảnh hưởng.

## Cắt tải và giới hạn tốc độ ở APISIX

Giới hạn tốc độ ở cổng (tiêu chí F4) có ba mục đích: bảo vệ hệ thống khỏi một người dùng hoặc một tenant dùng quá mức (vô tình do lỗi tích hợp, hoặc cố ý); bảo đảm công bằng giữa các tenant trong cell dùng chung CELL-SH; và cắt tải có kiểm soát khi toàn cell gần bão hoà. APISIX cung cấp ba plugin phù hợp: `limit-count` (đếm theo cửa sổ thời gian, có thể dùng chung bộ đếm qua Valkey Cluster), `limit-req` (thùng rò, làm mượt tốc độ, đếm cục bộ mỗi instance) và `limit-conn` (giới hạn số kết nối đồng thời).

Khoá giới hạn cần biết tenant và người dùng. Một plugin nhỏ của nền tảng (`vt-claims`, viết bằng Lua, độ ưu tiên cao hơn các plugin giới hạn) đọc JWT đã được plugin `openid-connect` kiểm chữ ký qua JWKS, và đăng ký hai biến `vt_tenant`, `vt_sub` bằng `core.ctx.register_var` để các plugin sau dùng làm khoá. Cấu hình plugin dùng chung cho các tuyến API được bảo vệ:

```yaml
apiVersion: apisix.apache.org/v2
kind: ApisixPluginConfig
metadata:
  name: protected-api
  namespace: edge
spec:
  plugins:
    - name: openid-connect
      enable: true
      config:
        discovery: https://sso.cell-hcm.danhgia.vn/realms/platform/.well-known/openid-configuration
        bearer_only: true
        use_jwks: true
        client_id: apisix-gateway
        set_userinfo_header: false
    - name: vt-claims
      enable: true
      config:
        required_cell: hcm
    - name: limit-count
      enable: true
      config:
        count: 600
        time_window: 60
        key_type: var_combination
        key: "$vt_tenant $vt_sub"
        policy: redis-cluster
        redis_cluster_nodes:
          - valkey-0.valkey-headless.data-kafka.svc:6379
          - valkey-1.valkey-headless.data-kafka.svc:6379
          - valkey-2.valkey-headless.data-kafka.svc:6379
        redis_cluster_name: apisix-ratelimit
        redis_cluster_ssl: true
        redis_cluster_ssl_verify: true
        rejected_code: 429
        rejected_msg: '{"type":"https://danhgia.vn/problems/rate-limited","title":"Vượt giới hạn tần suất","status":429}'
        show_limit_quota_header: true
        allow_degradation: true
    - name: limit-req
      enable: true
      config:
        rate: 400
        burst: 200
        key_type: var
        key: vt_tenant
        rejected_code: 429
        nodelay: true
        allow_degradation: true
    - name: response-rewrite
      enable: true
      config:
        vars:
          - ["status", "==", 429]
        headers:
          set:
            Retry-After: "30"
            Content-Type: "application/problem+json"
```

Giới hạn người dùng 600 request mỗi phút (10 request mỗi giây) cao hơn khoảng 200 lần mức trung bình của một người dùng thật (1 request mỗi 20 giây), nên không ảnh hưởng người dùng bình thường, kể cả khi một màn hình phát 20 request song song lúc tải. Giới hạn tenant ở `limit-req` là giới hạn cục bộ mỗi instance APISIX; với 6 instance ở đỉnh, giới hạn hiệu dụng của một tenant là khoảng 2.400 request mỗi giây cộng phần burst. Giá trị này được đặt theo tenant lớn nhất trong cell và được ghi đè cho từng tenant theo hợp đồng trong CELL-SH, nơi một xã nhỏ chỉ được cấp vài chục request mỗi giây. Tuỳ chọn `allow_degradation: true` cho phép APISIX tiếp tục phục vụ (bỏ qua giới hạn) nếu Valkey không truy cập được, vì làm sập toàn bộ cổng chỉ vì mất bộ đếm là hậu quả nặng hơn việc tạm thời không giới hạn.

Ngoài giới hạn theo người dùng và tenant, các tuyến tốn kém có giới hạn riêng theo API: xuất báo cáo Excel, tải gói hồ sơ, trợ lý AI và tìm kiếm toàn văn có `limit-count` thấp hơn (ví dụ 10 lần xuất mỗi phút mỗi người dùng). Tải lên minh chứng có giới hạn kích thước request 50 MB bằng `client-control` (`max_body_size`), khắc phục vấn đề F06 (giới hạn upload sai đơn vị 20 GB).

## Hành vi khi bão hoà

Dù định cỡ cẩn thận, vẫn có thể có tình huống tải vượt năng lực: một sự kiện ngoài dự kiến (ví dụ văn bản chỉ đạo yêu cầu toàn bộ đảng viên cập nhật trong một ngày), hoặc một phần hệ thống suy giảm (mất một vùng sự cố đúng lúc đỉnh). Nguyên tắc ứng xử là suy giảm có thứ tự ưu tiên, giữ chức năng quan trọng nhất và từ chối có kiểm soát phần còn lại, không để toàn hệ thống chậm đều rồi sập (tiêu chí F4, CWE-400).

Thứ tự ưu tiên được định nghĩa thành ba mức suy giảm, mỗi mức được bật bằng một nhãn `vt.io/degrade-level` trên `ApisixPluginConfig` do Alertmanager gọi webhook của bộ điều khiển suy giảm, và người trực vận hành có thể bật hoặc tắt bằng tay theo runbook OPS-14.

| Mức | Điều kiện kích hoạt | Hành động | Chức năng còn giữ |
|---|---|---|---|
| 0 Bình thường | Mọi SLO trong ngưỡng | Không | Toàn bộ |
| 1 Suy giảm nhẹ | mission-core ở `maxReplicas` quá 5 phút và p95 đọc trên 300 ms | Tắt trợ lý AI, tìm kiếm toàn văn trả kết quả rút gọn, bảng điều hành chỉ đọc từ pre-aggregation, xuất báo cáo chuyển sang hàng đợi | Mọi thao tác ghi nghiệp vụ, luồng duyệt, nộp minh chứng |
| 2 Suy giảm vừa | Tỷ lệ lỗi 5xx trên 1% hoặc Hikari pending trên 0 quá 2 phút | Thêm: tạm dừng tác vụ nền không gấp (đồng bộ tích hợp, tính lại hàng loạt không thuộc kỳ đang đóng), giới hạn tenant giảm 30% | Thao tác thuộc kỳ đang mở hạn: tiến độ, minh chứng, tự đánh giá, duyệt |
| 3 Bảo vệ | CSDL primary CPU trên 90% quá 2 phút | Thêm: chỉ nhận request ghi của các API trong danh sách ưu tiên, các request khác nhận 503 kèm `Retry-After: 60`, frontend hiển thị thông báo xếp hàng | Nộp và ký hồ sơ đang trong hạn |

Mọi phản hồi từ chối đều dùng mã 429 (người dùng hoặc tenant vượt hạn mức) hoặc 503 (hệ thống quá tải) kèm header `Retry-After` và thân lỗi theo RFC 9457. Frontend (`@vt/auth`, `@vt/telemetry`) và ứng dụng di động tôn trọng `Retry-After`, thử lại với lùi thời gian theo cấp số nhân kèm nhiễu ngẫu nhiên (exponential backoff with jitter), và giữ bản nháp cục bộ để người dùng không mất nội dung đã nhập. Mỗi lần chuyển mức suy giảm được ghi vào nhật ký vận hành, thông báo trên kênh trực, và nếu mức 2 trở lên kéo dài quá 15 phút thì sự cố được nâng thành SEV-2 theo OPS-13.

# Hiệu năng

## SLO và ngân sách lỗi

Các chỉ tiêu NFR-03 và NFR-04 được chuyển thành SLO đo được, mỗi SLO có chỉ số SLI định nghĩa chính xác bằng truy vấn Prometheus, cửa sổ đo và ngân sách lỗi. SLI được đo tại APISIX của cell (gần người dùng nhất mà vẫn nằm trong hệ thống), trừ SLI về trải nghiệm trang được đo bằng RUM phía trình duyệt (Grafana Faro, tiêu chí B8).

| Mã SLO | SLI | Mục tiêu | Cửa sổ | Ngân sách lỗi |
|---|---|---|---|---|
| SLO-AVL | Tỷ lệ request API không lỗi 5xx và không bị cắt tải 503 | 99,9% mỗi cell; 99,95% vùng điều phối toàn cục | 30 ngày | 43 phút tương đương mỗi tháng mỗi cell |
| SLO-READ | Tỷ lệ request đọc (GET) có độ trễ ≤ 300 ms | ≥ 95% | 30 ngày | 5% request đọc được phép chậm hơn 300 ms |
| SLO-WRITE | Tỷ lệ request ghi (POST, PUT, PATCH, DELETE) có độ trễ ≤ 800 ms | ≥ 95% | 30 ngày | 5% |
| SLO-ASYNC | Tỷ lệ API tác vụ nặng trả 202 kèm mã theo dõi trong ≤ 200 ms | ≥ 99% | 30 ngày | 1% |
| SLO-PAGE | Thời gian tải trang (tới khi dữ liệu chính hiển thị) ≤ 1 s, đo RUM | ≥ 95% lượt tải | 30 ngày | 5% |
| SLO-LCP | Largest Contentful Paint ≤ 2,5 s trên mạng 4G, đo RUM | ≥ 75% lượt tải (theo chuẩn Core Web Vitals) | 28 ngày | 25% |
| SLO-SCORE | Độ trễ từ sự kiện "minh chứng đã xác nhận" tới "điểm đã tính" (EVT-08 tới EVT-10) ≤ 60 s | ≥ 99% | 30 ngày | 1% |
| SLO-LEDGER | Độ trễ từ ghi outbox tới bản ghi có trong chuỗi băm ≤ 30 s | ≥ 99,9% | 30 ngày | 0,1% |

Cách viết SLO-READ và SLO-WRITE dưới dạng "tỷ lệ request nhanh hơn ngưỡng" thay vì "p95 ≤ ngưỡng" là có chủ ý. Hai cách tương đương về nghĩa, nhưng dạng tỷ lệ cho phép cộng dồn qua thời gian và tính tốc độ tiêu ngân sách lỗi (burn rate), là cơ sở của cảnh báo đa cửa sổ ở chương 10. Truy vấn SLI của SLO-READ:

```promql
sum(rate(apisix_http_latency_bucket{type="request", le="300", route=~"api-.*", method="GET"}[5m]))
/
sum(rate(apisix_http_latency_count{type="request", route=~"api-.*", method="GET"}[5m]))
```

Ngân sách lỗi được dùng làm công cụ ra quyết định, không chỉ làm báo cáo. Khi một cell đã tiêu quá 50% ngân sách lỗi của tháng trước ngày 15, mọi phát hành tính năng cho cell đó tạm dừng, chỉ cho phép bản sửa lỗi và bản vá an ninh, cho tới khi tốc độ tiêu ngân sách trở lại bình thường (OPS-19). Khi ngân sách còn dồi dào, đội phát triển được phép phát hành nhanh hơn, ví dụ rút ngắn bước canary.

## Kỹ thuật tối ưu ở từng tầng

Hiệu năng của nền tảng không đến từ một kỹ thuật đơn lẻ mà từ việc mỗi tầng loại bỏ phần việc không cần thiết trước khi request tới tầng sau. Bảng dưới đây tóm tắt các kỹ thuật theo tầng, kèm mức đóng góp ước tính và cách kiểm chứng.

| Tầng | Kỹ thuật | Tác dụng ước tính | Kiểm chứng |
|---|---|---|---|
| Trình duyệt, di động | Shell và design system ≤ 250 KB gzip, remote tải lười theo tuyến, UI Pack ≤ 30 KB lưu IndexedDB, Service Worker cho tài nguyên tĩnh | LCP ≤ 2,5 s trên 4G, lượt truy cập lại gần như không tải tĩnh | PERF-21, Lighthouse CI |
| CDN | Tài nguyên tĩnh và UI Pack phục vụ từ CDN Viettel với ETag theo phiên bản, vô hiệu hoá theo EVT-23 | Loại khoảng 60–70% số request HTTP khỏi cổng API | Tỷ lệ cache hit CDN ≥ 95% |
| Giao thức | HTTP/2 giữa trình duyệt và APISIX, nén Brotli cho JSON lớn hơn 1 KB, giữ kết nối tới backend | Giảm số kết nối TLS, giảm 70–80% kích thước JSON danh sách | Đo kích thước phản hồi trong PERF-01 |
| Cổng API | Kiểm JWT bằng JWKS lưu đệm, không gọi Keycloak mỗi request; cache phản hồi ngắn (5 s) cho manifest và trạng thái kỳ | Kiểm JWT ≈ 0,3 ms thay vì 5–10 ms gọi introspection | PERF-25 |
| Ứng dụng | Luồng ảo Java 21, ZGC; quyền và phạm vi tổ chức lưu đệm Valkey; Policy Pack biên dịch sẵn trong bộ nhớ; phân trang theo con trỏ, `limit` ≤ 200 | Logic xử lý ≈ 10 ms mỗi request | PERF-02 |
| Dữ liệu tổ chức | Event-carried State Transfer vào `org_replica`, không gọi đồng bộ SVC-04 trên đường găng | Bỏ một chặng mạng và một điểm phụ thuộc mỗi request | Trace trong PERF-01 không có chặng tới org-service |
| CSDL | Bản sao đọc cho truy vấn danh sách; chỉ mục bao phủ cho "việc của tôi"; phân vùng theo kỳ; PgBouncer giao dịch | Primary chỉ chịu ghi và đọc cần nhất quán mạnh | CPU primary ≤ 60% ở PERF-03 |
| Phân tích | CQRS: dashboard đọc ClickHouse qua Cube với pre-aggregation, không truy vấn CSDL nghiệp vụ | Truy vấn dashboard 20–100 ms, không tải lên `pg-core` | PERF-24 |
| Tác vụ nặng | Hàng đợi Kafka cho tính điểm, sinh biểu mẫu, ký lô; API trả 202 trong ≤ 200 ms | Tách tải nặng khỏi đường găng | SLO-ASYNC, PERF-19, PERF-20 |
| Tính điểm | Tính dần theo sự kiện, chỉ tính lại hồ sơ bị ảnh hưởng; tính lại toàn kỳ theo lô | Đỉnh cuối kỳ không dồn toàn bộ việc tính vào một thời điểm | PERF-19 |
| Lớp bảo vệ | Sổ cái, ký số, quét mã độc đều ngoài đường găng (G3) | Tổng chi phí trên đường găng ≈ 1–2 ms | PERF-14 đến PERF-18, PERF-25 |

## Môi trường và công cụ thử tải

Chương trình thử tải dùng k6 làm công cụ chính vì kịch bản viết bằng JavaScript dễ đọc, đưa vào repo cùng mã (tiêu chí J7), và chạy phân tán trên Kubernetes bằng k6-operator. Gatling được dùng cho các luồng nghiệp vụ nhiều bước có trạng thái phức tạp, cụ thể là chuỗi duyệt BPMN nhiều cấp (lập kế hoạch, trình duyệt, trả lại, trình lại, duyệt, ký) trong PERF-11, nơi mô hình phiên người dùng của Gatling biểu diễn tự nhiên hơn.

Môi trường thử tải là một cell riêng `CELL-PERF` dựng từ cùng chart với hồ sơ `sizing: L`, trên phần cứng cùng loại với CELL-HCM, đặt ở TTDL Hoà Lạc. Dữ liệu thử được sinh bằng bộ sinh dữ liệu tổng hợp theo phân phối của CELL-HCM: 1,2 triệu tài khoản, cây tổ chức 5 cấp với khoảng 12.000 đơn vị, 36 triệu nhiệm vụ trải trên 4 quý, 10 triệu phiên bản minh chứng (tệp giả kích thước theo phân phối log-chuẩn, trung vị 600 KB), 1,2 triệu tiến trình Flowable đang chạy. Không dùng dữ liệu thật của khách hàng trong môi trường thử tải (SEC-52).

Bộ sinh tải chạy trong cụm `perf-gen` tách khỏi `CELL-PERF` để tài nguyên của bộ sinh tải không lẫn với hệ thống được đo. Để tạo 30.000 request mỗi giây cho kịch bản toàn quốc PERF-03, cần khoảng 20 pod k6 mỗi pod 4 vCPU (một pod k6 phát được khoảng 2.000–3.000 request mỗi giây với kịch bản có kiểm tra phản hồi). Token truy cập của người dùng ảo được tạo trước bằng luồng client credentials của một client thử tải riêng trong Keycloak của `CELL-PERF`, có thời hạn 2 giờ, lưu trong tệp dữ liệu nạp vào `SharedArray`; luồng đăng nhập thật được đo riêng trong PERF-23 để không trộn chi phí đăng nhập vào mọi kịch bản.

```yaml
apiVersion: k6.io/v1alpha1
kind: TestRun
metadata:
  name: perf-03-dinh-cuoi-ky
  namespace: perf-gen
spec:
  parallelism: 20
  separate: true
  script:
    configMap:
      name: perf-scripts
      file: perf-03.js
  arguments: --tag testid=PERF-03 --out experimental-prometheus-rw
  runner:
    image: harbor.vt.local/perf/k6@sha256:REPLACE_WITH_DIGEST
    env:
      - name: K6_PROMETHEUS_RW_SERVER_URL
        value: http://prometheus-operated.observability.svc:9090/api/v1/write
      - name: BASE_URL
        value: https://perf.cell-perf.danhgia.vn
    resources:
      requests: { cpu: "4", memory: 8Gi }
      limits:   { cpu: "4", memory: 8Gi }
```

## Danh mục kịch bản thử tải PERF

Danh mục dưới đây là chương trình thử tải tối thiểu. Các kịch bản PERF-01 đến PERF-10 là thử tải và thử hỏng chung; PERF-11 đến PERF-13 kiểm chứng góp ý G2; PERF-14 đến PERF-18 và PERF-25 kiểm chứng góp ý G3; PERF-19 và PERF-20 kiểm chứng NFR-06; các kịch bản còn lại kiểm chứng những thành phần có hồ sơ tải riêng.

| Mã | Tên | Loại | Tải | Thời lượng | Tiêu chí đạt chính |
|---|---|---|---|---|---|
| PERF-01 | Tải thường của một cell | Load | 3.000 RPS (CELL-HCM thường), cơ cấu theo mục 4.3 | 1 giờ | SLO-READ, SLO-WRITE đạt; lỗi < 0,1%; CPU primary `pg-core` ≤ 50% |
| PERF-02 | Hiệu chuẩn thông lượng một pod | Saturation | Tăng dần tới khi p95 vượt ngưỡng, 1 pod mission-core | 30 phút mỗi service | Ghi lại RPS mỗi pod tại p95 = 300 ms đọc; cập nhật bảng mục 4.3 nếu lệch > 15% |
| PERF-03 | Đỉnh cuối kỳ toàn quốc 300.000 CCU | Load đỉnh | 30.000 RPS tổng trên `CELL-PERF` nhân bản theo tỷ lệ, hoặc 6.000 RPS trên một cell hạng L | 2 giờ | SLO đạt ở 6.000 RPS một cell; không cắt tải mức 2 trở lên |
| PERF-04 | Đột biến ngày hạn | Spike | Từ 3.000 lên 6.000 RPS trong 60 giây, giữ 20 phút, về 3.000 | 40 phút | p95 đọc ≤ 300 ms sau tối đa 3 phút kể từ đầu đột biến; lỗi < 0,5% trong 3 phút đầu |
| PERF-05 | Chạy bền | Soak | 3.000 RPS liên tục, có tác vụ nền hằng đêm | 72 giờ | Không rò bộ nhớ (heap sau GC tăng < 5%), không tăng dần độ trễ, không tồn đọng Kafka tăng dần, phình bảng (bloat) < 20% |
| PERF-06 | Tìm điểm gãy | Stress | Tăng 500 RPS mỗi 5 phút từ 3.000 tới khi lỗi > 5% | tới khi gãy | Xác định điểm gãy ≥ 9.000 RPS (1,5 lần đột biến); hệ thống tự phục hồi trong ≤ 10 phút sau khi giảm tải; thành phần gãy đầu tiên được ghi nhận |
| PERF-07 | Thử hỏng: tắt node | Chaos | PERF-01 nền, tắt ngẫu nhiên 1 node app-general mỗi 10 phút | 1 giờ | Lỗi < 0,1%; không vi phạm SLO-AVL; pod được xếp lại trong ≤ 2 phút |
| PERF-08 | Thử hỏng: mất broker Kafka | Chaos | PERF-01 nền, dừng 1 broker rồi 1 broker ở vùng khác | 1 giờ | 0 sự kiện mất (đối soát outbox với consumer); độ trễ SLO-SCORE vẫn đạt |
| PERF-09 | Thử hỏng: chuyển primary CSDL | Chaos | PERF-01 nền, xoá pod primary `pg-core` | 30 phút | Chuyển primary trong ≤ 30 s; lỗi ghi chỉ trong cửa sổ chuyển; RPO = 0 (đối soát số giao dịch đã xác nhận) |
| PERF-10 | Thử hỏng: mất vùng sự cố | Chaos | PERF-03 ở 4.000 RPS, cách ly mạng toàn bộ vùng `hl-c` | 1 giờ | Dịch vụ tiếp tục; p95 đọc ≤ 500 ms trong 10 phút đầu, ≤ 300 ms sau đó; RPO = 0 |
| PERF-11 | Góp ý G2: thông lượng engine Flowable | Load | 150 lần hoàn thành user task/giây, tăng tới 300/giây | 2 giờ | Xem mục 7.5 |
| PERF-12 | Góp ý G2: 1.000 tenant, 30 mẫu luồng | Load | Phân bố Zipf trên 1.000 tenant, 30 mẫu, 4 phiên bản mỗi mẫu | 1 giờ | Xem mục 7.5 |
| PERF-13 | Góp ý G2: kích thước bảng runtime và chạy bền | Soak | 1,2 triệu tiến trình đang chạy, 150 task/giây | 24 giờ | Xem mục 7.5 |
| PERF-14 | Góp ý G3: mã hoá trường C2 | So sánh bật/tắt | PERF-01 với mã hoá trường bật và tắt | 2 × 30 phút | Xem mục 7.6 |
| PERF-15 | Góp ý G3: mã hoá đầu cuối C3 phía client | Đo trên thiết bị | Trình duyệt, điện thoại tầm trung và cấp thấp | – | Xem mục 7.6 |
| PERF-16 | Góp ý G3: outbox tới sổ cái | Load | 1.800 sự kiện/giây, niêm phong mỗi giờ và mỗi 5 phút | 2 giờ 30 phút | Xem mục 7.6 |
| PERF-17 | Góp ý G3: ký lô | Batch | 100.000 mục ký | tới khi xong | Xem mục 7.6 |
| PERF-18 | Góp ý G3: tải lên và quét minh chứng 10 MB | Load | 33 tệp/giây, tệp 1–10 MB | 1 giờ | Xem mục 7.6 |
| PERF-19 | NFR-06: tính lại 1,2 triệu hồ sơ | Batch | 1,2 triệu yêu cầu tính lại | tới khi xong | Hoàn tất ≤ 2 giờ, mục tiêu ≤ 1 giờ; kết quả trùng khớp 100% với lần tính trước (tiêu chí I6) |
| PERF-20 | NFR-06: sinh và ký 100.000 phiếu | Batch | 100.000 phiếu Mẫu 1A–3 | tới khi xong | Hoàn tất ≤ 4 giờ; 100% phiếu có chữ ký hợp lệ và dấu thời gian |
| PERF-21 | Hiệu năng frontend | RUM tổng hợp | Lighthouse CI và WebPageTest, mạng 4G mô phỏng | mỗi bản build | LCP ≤ 2,5 s, shell ≤ 250 KB gzip, dựng biểu mẫu 50 trường ≤ 50 ms |
| PERF-22 | Kết nối thời gian thực | Load | 60.000 kết nối SSE, 500 thông báo/giây | 1 giờ | Độ trễ phân phát p95 ≤ 2 s; mất kết nối được nối lại ≤ 10 s |
| PERF-23 | Đăng nhập dồn | Spike | 100 đăng nhập/giây, 500 làm mới token/giây | 30 phút | Đăng nhập p95 ≤ 1,5 s (không tính thời gian người dùng nhập OTP); lỗi < 0,1% |
| PERF-24 | Bảng điều hành và phân tích | Load | 300 truy vấn dashboard/giây | 1 giờ | p95 ≤ 1 s; CPU `pg-core` không đổi so với PERF-01 |
| PERF-25 | Góp ý G3: chi phí toàn chuỗi bảy lớp bảo vệ | So sánh | API "cập nhật tiến độ" với từng lớp bật dần | 7 × 15 phút | Xem mục 7.6 |
| PERF-26 | Suy luận AI | Load | 10 yêu cầu/giây, 1.500 token vào, 300 token ra | 1 giờ | Thời gian tới token đầu tiên p95 ≤ 2 s; thông lượng ≥ 3.000 token ra/giây mỗi cell hạng L |

Lịch chạy: PERF-01, PERF-02, PERF-21 chạy tự động mỗi lần phát hành vào staging (rút gọn 15 phút). Toàn bộ danh mục chạy trước khi một cell mới vào vận hành và mỗi quý một lần, ít nhất 3 tuần trước tuần cuối quý, để còn thời gian khắc phục trước cửa sổ đóng băng (OPS-06). Bản đánh giá yêu cầu "thử tải dạng spike trước mỗi kỳ hạn" (trục E, khuyến nghị 1), nên PERF-04 và PERF-03 là bắt buộc trong lần chạy hằng quý.

## Kịch bản riêng cho góp ý G2: engine luồng BPMN

Góp ý G2 đặt câu hỏi cụ thể: với hàng trăm, hàng nghìn tenant, engine BPMN có thành nút thắt không, và các định nghĩa luồng có thành "mạng nhện" không. TL-01 trả lời bằng phân tích định lượng và ba biện pháp kiến trúc. TL-04 biến câu trả lời đó thành ba phép đo có tiêu chí đạt rõ ràng, để kết luận dựa trên số đo chứ không chỉ dựa trên lập luận.

**PERF-11: thông lượng engine.** Kịch bản Gatling mô phỏng người duyệt ở CELL-HCM: mỗi người dùng ảo mở danh sách việc chờ duyệt (đọc từ `flowable.wf_task_projection`, không truy vấn trực tiếp `ACT_RU_TASK`), mở chi tiết, hoàn thành user task với biến quyết định. Tải bắt đầu ở 150 lần hoàn thành mỗi giây (năng lực thiết kế của cell lớn theo TL-01, cao hơn ước tính 134 lần mỗi giây của CELL-HCM), giữ 30 phút, sau đó tăng mỗi 10 phút thêm 50 lần mỗi giây tới 300 (ngưỡng tách engine theo TL-01, gấp đôi năng lực thiết kế). Các đại lượng đo gồm: số lần hoàn thành user task mỗi giây đạt được; độ trễ p50, p95, p99 của API hoàn thành; độ trễ p95 của API danh sách việc chờ; số job bất đồng bộ đến hạn chưa thu nhận; CPU, IOPS và số kết nối hoạt động của `pg-flowable`; số lần xung đột khoá lạc quan của Flowable (`FlowableOptimisticLockingException`).

Tiêu chí đạt của PERF-11:

1. Ở 150 lần mỗi giây: p95 hoàn thành user task ≤ 500 ms, p95 danh sách việc chờ ≤ 300 ms, tỷ lệ lỗi < 0,1%, CPU `pg-flowable` ≤ 50%.
2. Ở 300 lần mỗi giây: p95 hoàn thành ≤ 800 ms (vẫn trong NFR-03), job đến hạn tồn đọng < 1.000 và được xử lý hết trong ≤ 60 giây sau khi tải giảm, CPU `pg-flowable` ≤ 75%.
3. Tỷ lệ xung đột khoá lạc quan < 0,5% số lần hoàn thành; xung đột được thử lại tự động và không trả lỗi cho người dùng.
4. Không thành phần nào ngoài `pg-flowable` và `workflow-executor` chạm giới hạn tài nguyên; nếu điểm gãy nằm ở `pg-flowable` dưới 300 lần mỗi giây, ngưỡng tách engine của TL-01 phải được hạ xuống bằng 80% điểm gãy đo được, và biện pháp dự phòng của TL-01 (tách engine theo cell con hoặc giảm lượng ghi lịch sử) được áp dụng sớm hơn.

**PERF-12: 1.000 tenant, 30 mẫu luồng.** Kịch bản này kiểm chứng biện pháp chống "mạng nhện": định nghĩa luồng là mẫu cộng tham số, không nhân bản BPMN theo tenant. Dữ liệu thử gồm 1.000 tenant trong cell pool `CELL-SH` mô phỏng, 30 mẫu luồng (duyệt kế hoạch, duyệt OKR, hồ sơ đánh giá theo luồng Đảng, theo luồng Nhà nước, khiếu nại, mở lại kỳ, phát hành Policy Pack, và các biến thể theo khối) với 4 phiên bản mỗi mẫu, và 1.000 bản ghi `flowable.wf_binding` gắn tham số cấp duyệt, người duyệt, thời hạn cho từng tenant. Lưu lượng phân bố theo luật Zipf với hệ số 1,1 (vài tenant lớn chiếm phần lớn lưu lượng, nhiều tenant nhỏ có lưu lượng thấp), đúng đặc điểm của cell dùng chung.

Tiêu chí đạt của PERF-12:

1. Số dòng trong `ACT_RE_PROCDEF` bằng số mẫu nhân số phiên bản (≤ 120), không tăng theo số tenant. Phép kiểm tra là đếm trực tiếp sau khi khởi tạo 1.000 tenant.
2. Bộ nhớ đệm định nghĩa tiến trình (`process-definition-cache-limit` = 200) có tỷ lệ trúng ≥ 99,9%; không có lần nạp lại định nghĩa từ CSDL trong giờ đo sau 5 phút làm nóng.
3. Tra tham số theo tenant từ `wf_binding` và bảng DMN p95 ≤ 5 ms.
4. Thông lượng và độ trễ ở cùng mức tải tổng như PERF-11 không chênh quá 10% so với chạy cùng tải trên 10 tenant. Điều kiện này chứng minh số tenant không phải biến số ảnh hưởng tới hiệu năng engine.
5. Bộ kiểm tra cấu trúc (linter) của 30 mẫu báo 0 vi phạm quy tắc quản trị của ADR-04: tối đa một tầng call activity, không gọi tiến trình chéo tenant, không service task đồng bộ gọi hệ thống ngoài.

**PERF-13: kích thước bảng runtime và chạy bền.** Kịch bản nạp trước 1,2 triệu tiến trình đang chạy (mức cuối quý ở CELL-HCM: mỗi người một tiến trình đánh giá đang mở), rồi chạy 150 lần hoàn thành mỗi giây trong 24 giờ, đồng thời khởi tạo tiến trình mới với cùng tốc độ để số tiến trình đang chạy ổn định. Các đại lượng theo dõi: số dòng và kích thước (gồm chỉ mục) của `ACT_RU_EXECUTION`, `ACT_RU_TASK`, `ACT_RU_VARIABLE`, `ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, `ACT_HI_*`; tỷ lệ phình bảng; thời gian autovacuum; độ trễ theo thời gian.

Tiêu chí đạt của PERF-13:

1. Với 1,2 triệu tiến trình đang chạy, kích thước runtime tổng (`ACT_RU_*` gồm chỉ mục) ≤ 60 GB, nằm vừa trong `shared_buffers` và bộ đệm hệ điều hành của `pg-flowable` (64 GiB RAM), nhờ quy tắc chỉ lưu khoá nghiệp vụ trong biến tiến trình, không lưu tài liệu.
2. Trung bình biến mỗi tiến trình ≤ 10; không có biến kiểu tuần tự hoá lớn hơn 4 KB (kiểm tra bằng truy vấn trên `ACT_RU_VARIABLE` và `ACT_GE_BYTEARRAY`).
3. p95 hoàn thành user task ở giờ thứ 24 không tăng quá 10% so với giờ thứ nhất.
4. Bảng lịch sử `ACT_HI_*` ở mức `audit` tăng tuyến tính theo số task hoàn thành, và tác vụ dọn lịch sử chuyển dữ liệu quá 90 ngày sang ClickHouse rồi xoá trên PostgreSQL giữ kích thước `ACT_HI_*` ổn định.

## Kịch bản riêng cho góp ý G3: chi phí của bảy lớp bảo vệ và mã hoá

Góp ý G3 cho rằng chưa phân tích rõ hiệu năng của bảy lớp bảo vệ và các giải pháp mã hoá. Baseline mục 14 đã nêu nguyên tắc "lớp nào tốn kém thì đưa ra khỏi đường găng" và ước tính chi phí từng lớp; ngân sách độ trễ phía máy chủ cho thao tác "cập nhật tiến độ" được ước tính khoảng 25 ms. Các kịch bản dưới đây đo từng lớp bằng phương pháp bật, tắt có đối chứng: cùng một tải, cùng một dữ liệu, chỉ khác lớp bảo vệ đang được đo, mỗi cấu hình chạy hai lần và lấy kết quả kém hơn.

**PERF-14: mã hoá trường C2 (lớp 7).** Chạy PERF-01 rút gọn 30 phút với cờ `vt.crypto.field-encryption.enabled` bật và tắt trên `CELL-PERF`. Trong cấu hình bật, các cột C2 (số định danh, ngày sinh, nhận xét đánh giá, nội dung khiếu nại) được mã hoá AES-256-GCM với DEK theo tổ hợp tenant, tháng và mục đích (TL-01), DEK được mở bằng OpenBao Transit (KEK trong HSM) và lưu đệm 15 phút trong bộ nhớ của pod. Tiêu chí đạt: chênh lệch p95 của API ghi có trường C2 ≤ 1 ms; chênh lệch CPU của mission-core ≤ 3%; tỷ lệ trúng bộ đệm DEK ≥ 99,9%; số lời gọi mở DEK tới OpenBao ≤ 1 lần mỗi 15 phút cho mỗi DEK đang dùng ở mỗi pod (đếm từ nhật ký kiểm toán của OpenBao); p95 lời gọi mở DEK ≤ 20 ms. Kịch bản phụ đo tình huống xấu nhất: khởi động lại đồng loạt 24 pod mission-core của một cell có 1.000 tenant (CELL-SH), với trung bình 3 mục đích mã hoá đang dùng mỗi tenant tạo tối đa 24 × 1.000 × 3 = 72.000 lời gọi mở DEK trong vài phút; tiêu chí là OpenBao và HSM không quá tải (p95 ≤ 100 ms) nhờ cơ chế nạp DEK lười theo tenant khi có request đầu tiên.

**PERF-15: mã hoá đầu cuối C3 phía client.** Đo trực tiếp trên thiết bị, vì chi phí nằm ở trình duyệt và điện thoại chứ không ở máy chủ. Bộ thiết bị tham chiếu gồm máy tính văn phòng phổ biến (Core i5 thế hệ 10, Chrome bản ổn định), điện thoại Android tầm trung và Android cấp thấp (ví dụ chip phân khúc phổ thông, 3 GB RAM), iPhone đời 3 năm trước. Phép đo: mã hoá AES-256-GCM bằng WebCrypto cho nội dung 100 KB, 1 MB, 10 MB; bọc khoá nội dung cho 1, 5, 20 người nhận bằng ECDH P-256 (dẫn xuất khoá rồi bọc AES-KW) và RSA-OAEP 2048; giải mã phía người nhận. Tiêu chí đạt: mã hoá 1 MB ≤ 50 ms trên máy tính và điện thoại tầm trung, ≤ 150 ms trên điện thoại cấp thấp; bọc khoá cho 20 người nhận ≤ 100 ms trên điện thoại tầm trung; tổng thời gian thao tác "gửi nội dung mật 1 MB cho 5 người" tăng thêm ≤ 300 ms so với gửi không mã hoá. Kết quả cũng xác nhận máy chủ không nhận bản rõ: phép thử chặn bắt lưu lượng giữa APISIX và mission-core chỉ thấy bản mã.

**PERF-16: outbox tới sổ cái (lớp 1).** Chạy 1.800 sự kiện mỗi giây (đỉnh ghi CELL-HCM) trong 2 giờ, đo hai nhóm đại lượng. Nhóm thứ nhất ở đường găng: chênh lệch p95 của API ghi khi bảng `outbox_event` có và không có dòng bổ sung cho sổ cái; tiêu chí ≤ 1 ms. Nhóm thứ hai ở phía sau: độ trễ từ lúc giao dịch xác nhận tới lúc bản ghi có trong `ledger.ledger_entry` (SLO-LEDGER, tiêu chí p99 ≤ 5 s, p99,9 ≤ 30 s); thời gian niêm phong (cây Merkle hai cấp, một chữ ký và một dấu thời gian RFC 3161 cho gốc cell theo TL-01) ≤ 60 s ở chu kỳ 1 giờ và ≤ 20 s ở chu kỳ 5 phút của 3 ngày cuối kỳ; chạy thêm 30 phút với chu kỳ 5 phút để xác nhận độ trễ của API ghi không đổi; công cụ kiểm tra chuỗi băm (`verify_run`) kiểm tra lại toàn bộ bản ghi của 2 giờ thử trong ≤ 5 phút và phát hiện đúng vị trí khi cố ý sửa một dòng trực tiếp trong CSDL.

**PERF-17: ký lô (lớp 2).** Gửi 100 phiên ký lô, mỗi phiên tối đa 1.000 phiếu theo giới hạn của TL-01 (ADR-31), tổng 100.000 phiếu (mỗi phiếu là một băm SHA-256 của PDF/A đã sinh trong PERF-20) qua esign-service với bộ kết nối giả lập MySign có độ trễ 1–3 s mỗi lần ký từ xa, và bộ kết nối HSM nội bộ cho dấu thời gian. Tiêu chí đạt: hoàn tất ≤ 4 giờ (NFR-06), mục tiêu ≤ 1 giờ; 100% chữ ký kiểm tra hợp lệ bằng công cụ độc lập; khi bộ kết nối trả lỗi 10% ngẫu nhiên, saga thử lại với lùi thời gian và không ký trùng (đối soát `esign.sign_item` theo khoá idempotent); HSM không vượt 50% năng lực thao tác ký mỗi giây.

**PERF-18: tải lên và quét minh chứng 10 MB (lớp 3).** Mô phỏng 33 tệp mỗi giây (đỉnh của CELL-HCM), kích thước theo phân phối thực tế, trong đó 10% là tệp 10 MB. Đo: thời gian tải lên p95 qua mạng giả lập 50 Mbps; phần thời gian tăng thêm do tính SHA-256 dạng luồng trong evidence-service (tiêu chí ≤ 25 ms cho tệp 10 MB, khớp ước tính baseline 15–25 ms); thời gian từ tải lên xong tới có kết quả quét ClamAV p95 ≤ 60 s; bộ nhớ của evidence-service không tăng theo kích thước tệp (xác nhận xử lý dạng luồng, tiêu chí F6); tệp chứa chuỗi kiểm thử EICAR bị đánh dấu nhiễm và không bao giờ chuyển sang trạng thái tính điểm được; việc ghi đè hoặc xoá đối tượng trong bucket Object Lock bị RGW từ chối.

**PERF-25: chi phí toàn chuỗi.** Đo API "cập nhật tiến độ" (`PATCH /api/v1/tasks/{id}/progress`) ở 600 request ghi mỗi giây, bật dần từng lớp theo bảy bước: (1) tối thiểu không mTLS, không OPA, không mã hoá trường, không outbox sổ cái; (2) thêm mTLS ztunnel; (3) thêm WAF Coraza; (4) thêm kiểm tra OPA; (5) thêm mã hoá trường C2; (6) thêm outbox sổ cái; (7) thêm kiểm tra khoá kỳ và ghi `task_state_log`. Kết quả là bảng phân rã độ trễ p50 và p95 theo lớp, lấy từ trace của Tempo (mỗi lớp là một span) và từ chênh lệch giữa các bước. Tiêu chí đạt: tổng chi phí tăng thêm của bước 2 đến bước 7 so với bước 1 ≤ 10 ms ở p95; tổng độ trễ phía máy chủ p95 ở bước 7 ≤ 50 ms (gấp đôi ước tính 25 ms của baseline, còn cách xa ngưỡng 800 ms của NFR-03). Bảng kết quả này là câu trả lời định lượng cho góp ý G3 và được đưa vào phụ lục báo cáo thử tải gửi lãnh đạo.

## Script k6 mẫu

Script dưới đây là kịch bản PERF-04 (đột biến ngày hạn). Nó dùng bộ thực thi `ramping-arrival-rate` để điều khiển tốc độ đến của request (mô hình hệ thống mở, đúng với người dùng thật không chờ nhau), trộn các thao tác theo cơ cấu mục 4.3, gửi `Idempotency-Key` và `If-Match` cho thao tác ghi theo quy ước API của baseline mục 13, và đặt ngưỡng đạt theo NFR-03.

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { uuidv4, randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL;
const users = new SharedArray('users', () => JSON.parse(open('./data/users-hcm.json')));
const conflicts = new Counter('write_conflicts_409');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 3000,
      timeUnit: '1s',
      preAllocatedVUs: 4000,
      maxVUs: 12000,
      stages: [
        { target: 3000, duration: '5m' },
        { target: 6000, duration: '1m' },
        { target: 6000, duration: '20m' },
        { target: 3000, duration: '2m' },
        { target: 3000, duration: '10m' },
      ],
    },
  },
  thresholds: {
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_duration{kind:write}': ['p(95)<800'],
    'http_req_duration{kind:async}': ['p(95)<200'],
    http_req_failed: ['rate<0.005'],
    checks: ['rate>0.995'],
  },
};

function headers(u, extra) {
  return Object.assign({
    Authorization: `Bearer ${u.token}`,
    Accept: 'application/json',
    'Accept-Encoding': 'br, gzip',
    traceparent: `00-${uuidv4().replace(/-/g, '')}-${uuidv4().replace(/-/g, '').slice(0, 16)}-01`,
  }, extra || {});
}

function myTasks(u) {
  const r = http.get(`${BASE}/api/v1/tasks?assignee=me&status=DANG_THUC_HIEN&limit=50`,
    { headers: headers(u), tags: { kind: 'read', name: 'GET /tasks?assignee=me' } });
  check(r, { 'tasks 200': (x) => x.status === 200 });
  return r.status === 200 ? r.json('items') : [];
}

function updateProgress(u, task) {
  const etag = http.get(`${BASE}/api/v1/tasks/${task.id}`,
    { headers: headers(u), tags: { kind: 'read', name: 'GET /tasks/{id}' } }).headers['Etag'];
  const body = JSON.stringify({ percent: randomIntBetween(10, 100), note: 'Cập nhật từ PERF-04' });
  const r = http.patch(`${BASE}/api/v1/tasks/${task.id}/progress`, body, {
    headers: headers(u, { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4(), 'If-Match': etag }),
    tags: { kind: 'write', name: 'PATCH /tasks/{id}/progress' },
  });
  if (r.status === 409) conflicts.add(1);
  check(r, { 'progress 200/409': (x) => x.status === 200 || x.status === 409 });
}

function submitEvidenceMeta(u, task) {
  const r = http.post(`${BASE}/api/v1/evidences`, JSON.stringify({
    taskId: task.id, fileRef: `perf/${uuidv4()}`, sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  }), {
    headers: headers(u, { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() }),
    tags: { kind: 'write', name: 'POST /evidences' },
  });
  check(r, { 'evidence 201': (x) => x.status === 201 });
}

function requestScore(u) {
  const r = http.post(`${BASE}/api/v1/appraisals/${u.profileId}/score-preview`, null, {
    headers: headers(u, { 'Idempotency-Key': uuidv4() }),
    tags: { kind: 'async', name: 'POST /appraisals/{id}/score-preview' },
  });
  check(r, { 'score 202': (x) => x.status === 202 && x.headers['Location'] !== undefined });
}

export default function () {
  const u = users[randomIntBetween(0, users.length - 1)];
  const p = Math.random();
  if (p < 0.55) {
    myTasks(u);
  } else if (p < 0.80) {
    const tasks = myTasks(u);
    if (tasks.length > 0) updateProgress(u, tasks[randomIntBetween(0, tasks.length - 1)]);
  } else if (p < 0.92) {
    const tasks = myTasks(u);
    if (tasks.length > 0) submitEvidenceMeta(u, tasks[0]);
  } else if (p < 0.97) {
    http.get(`${BASE}/api/v1/okr?owner=me&period=current`,
      { headers: headers(u), tags: { kind: 'read', name: 'GET /okr' } });
  } else {
    requestScore(u);
  }
}
```

Script không đo tải lên tệp nhị phân (thuộc PERF-18) và không gọi API của workflow-service (thuộc PERF-11 bằng Gatling), để mỗi kịch bản đo đúng một hồ sơ tải. Header `traceparent` được tạo ở phía bộ sinh tải để mỗi request thử tải có trace riêng, nhờ đó khi một request vượt ngưỡng, người phân tích tra được ngay trace tương ứng trong Tempo (tiêu chí B1).

Kịch bản Gatling cho PERF-11 viết bằng Java DSL, mô phỏng chuỗi duyệt hai cấp có nhánh trả lại:

```java
public class Perf11ApprovalSimulation extends Simulation {
  HttpProtocolBuilder http = http.baseUrl(System.getenv("BASE_URL"))
      .acceptHeader("application/json").header("Authorization", "Bearer #{token}");
  FeederBuilder<Object> approvers = jsonFile("data/approvers-hcm.json").circular();

  ScenarioBuilder approve = scenario("PERF-11 duyet ke hoach")
      .feed(approvers)
      .exec(http("GET danh sach cho duyet").get("/api/v1/plans/approvals?limit=20")
          .check(jsonPath("$.items[0].taskId").saveAs("taskId")))
      .pause(Duration.ofSeconds(2), Duration.ofSeconds(8))
      .randomSwitch().on(
          percent(90.0).then(exec(http("POST duyet").post("/api/v1/plans/approvals/#{taskId}/complete")
              .header("Idempotency-Key", session -> java.util.UUID.randomUUID().toString())
              .body(StringBody("{\"decision\":\"DA_DUYET\"}")).asJson().check(status().is(200)))),
          percent(10.0).then(exec(http("POST tra lai").post("/api/v1/plans/approvals/#{taskId}/complete")
              .header("Idempotency-Key", session -> java.util.UUID.randomUUID().toString())
              .body(StringBody("{\"decision\":\"TRA_LAI\",\"reason\":\"Bổ sung sản phẩm\"}")).asJson()
              .check(status().is(200)))));

  {
    setUp(approve.injectOpen(
        constantUsersPerSec(150).during(Duration.ofMinutes(30)),
        incrementUsersPerSec(50).times(3).eachLevelLasting(Duration.ofMinutes(10))
            .startingFrom(150)))
      .protocols(http)
      .assertions(
          details("POST duyet").responseTime().percentile(95.0).lte(800),
          global().failedRequests().percent().lt(0.1));
  }
}
```

## Tiêu chí đạt theo NFR-03 và báo cáo kết quả

Một lần thử tải được coi là đạt khi thoả đồng thời các điều kiện chung sau, ngoài tiêu chí riêng của từng kịch bản: API đọc p95 ≤ 300 ms; API ghi p95 ≤ 800 ms; API tác vụ nặng trả 202 p95 ≤ 200 ms; tỷ lệ lỗi (5xx và hết thời gian chờ) < 0,1% ở tải thường và < 0,5% trong 3 phút đầu của đột biến; không có tồn đọng Kafka tăng không giới hạn; CPU primary của mọi cụm PostgreSQL ≤ 70% ở tải đỉnh; không có pod bị OOMKill; không có cảnh báo mức nghiêm trọng (critical) nào ngoài các cảnh báo do chính kịch bản thử hỏng gây ra.

Báo cáo kết quả theo mẫu cố định, lưu trong repo `perf-results` cùng mã kịch bản và cấu hình môi trường (phiên bản ảnh theo digest, tệp values), để mọi kết quả tái lập được. Nội dung báo cáo gồm: mục tiêu và giả thuyết; cấu hình môi trường và dữ liệu; biểu đồ RPS, p50, p95, p99, tỷ lệ lỗi theo thời gian; bảng tài nguyên (CPU, bộ nhớ, IOPS, kết nối) theo thành phần; năm trace chậm nhất có phân tích; thành phần chạm giới hạn đầu tiên; so sánh với lần chạy trước; kết luận đạt hoặc không đạt theo từng tiêu chí; hạng mục khắc phục có người chịu trách nhiệm và hạn. Kết quả của PERF-02 được dùng để cập nhật bảng thông lượng mỗi pod ở mục 4.3, và nếu lệch quá 15% thì mọi giá trị `maxReplicas` và ngân sách kết nối ở mục 4.7 được tính lại.

Chỉ số theo dõi "Phiên đồng thời đạt SLO (p95 ≤ 1 s)" của bản đánh giá được báo cáo trực tiếp từ chương trình này: đợt 0c phải đạt 500 phiên (đo trên hệ hiện tại), đợt 3 phải đạt 70.000 phiên, và mục tiêu nền tảng của TL-04 là 300.000 phiên trên toàn bộ các cell (NFR-01), với điều kiện p95 chặt hơn của NFR-03.

# Sao lưu dự phòng và phục hồi thảm hoạ

## Mục tiêu và nguyên tắc 3-2-1-1-0

Chương này thiết kế chi tiết hai chỉ tiêu NFR-05 (mất một TTDL: RPO ≤ 15 phút, RTO ≤ 4 giờ; mất một node CSDL: RPO ≈ 0, RTO ≤ 5 phút) và NFR-09 (lưu nhật ký kiểm toán và sổ cái ít nhất 10 năm). Nó cũng khắc phục trực tiếp vấn đề C05 của bản đánh giá ("không Helm, GitOps; không kế hoạch DR (RPO, RTO)"), nhóm yêu cầu "Sao lưu dự phòng" của TCVN 11930 mà bản đánh giá ghi trạng thái "chưa xác định, không có kế hoạch DR trong repo" và ánh xạ vào mã C5, cùng yêu cầu "sao lưu bất biến 3-2-1" của cấp độ 4. Các tiêu chí còn lại của trục C (C1 đến C10, về ảnh container, manifest, probe, co giãn, GitOps, chuỗi cung ứng) được xử lý ở chương 3, 5, 6, 9 và 11.

Cần phân biệt hai cơ chế thường bị gộp chung. **Nhân bản** (replication) giữ bản sao gần như tức thời ở nơi khác để chống mất phần cứng hoặc mất TTDL; nó không chống được lỗi logic, vì một lệnh `DELETE` sai hoặc mã độc tống tiền cũng được nhân bản ngay lập tức. **Sao lưu** (backup) giữ các bản chụp theo thời điểm, có thể quay về quá khứ, và phải nằm ngoài tầm với của kẻ tấn công đã chiếm quyền hệ thống chính. Hệ thống cần cả hai, và chương này thiết kế chúng như hai lớp độc lập.

Nguyên tắc sao lưu áp dụng là **3-2-1-1-0**, mở rộng từ yêu cầu "sao lưu 3-2-1 bất biến" của cấp độ 4 nêu trong bản đánh giá:

| Thành phần | Ý nghĩa | Cách đáp ứng trong TL-04 |
|---|---|---|
| 3 | Ít nhất ba bản dữ liệu: bản chính và hai bản sao lưu | Bản chính ở Hoà Lạc; bản sao lưu ở kho S3 phía Nam; bản sao ngoại tuyến hằng tháng |
| 2 | Trên hai loại phương tiện hoặc hai hệ lưu trữ độc lập | Ceph của cell (NVMe, RBD) và cụm Ceph sao lưu riêng ở phía Nam (HDD dung lượng lớn), cộng băng LTO hoặc kho tách mạng |
| 1 | Một bản ở địa điểm khác | Kho sao lưu đặt ở TTDL phía Nam, cách Hoà Lạc hơn 1.000 km |
| 1 | Một bản bất biến hoặc tách mạng | Bucket S3 bật Object Lock chế độ compliance; bản ngoại tuyến hằng tháng không có kết nối mạng thường trực |
| 0 | Không lỗi khi kiểm tra khôi phục | Mỗi bản sao lưu được kiểm tra tự động, và diễn tập khôi phục định kỳ phải thành công (OPS-18) |

Kho sao lưu phía Nam là một cụm Ceph riêng (`ceph-sn-bk`), không dùng chung với Ceph phục vụ ứng dụng của các cell ở phía Nam. Cụm này có miền quản trị riêng: tài khoản quản trị Ceph của kho sao lưu không trùng với tài khoản quản trị của bất kỳ cụm ứng dụng nào, và chỉ truy cập được qua PAM (SEC-42). Mỗi cell có một tài khoản S3 riêng trên kho sao lưu với quyền ghi vào bucket của cell đó, và không có quyền xoá phiên bản đối tượng. Mọi bucket sao lưu bật Object Lock compliance với thời gian giữ mặc định 35 ngày cho bản sao lưu thường và 10 năm cho bản sao lưu nhật ký kiểm toán và sổ cái. Ở chế độ compliance, không ai, kể cả tài khoản root của RGW, rút ngắn được thời gian giữ, nên một kẻ tấn công đã chiếm được khoá truy cập S3 hay quyền quản trị cụm ứng dụng cũng không xoá được bản sao lưu (SEC-44).

![Hình 8.1. Luồng sao lưu và nhân bản của một cell](tk-sao-luu.png){width=16cm}

Hình 8.1 cho thấy các luồng dữ liệu đi từ một cell ở Hoà Lạc sang phía Nam. Các mũi tên tới "Ceph S3 kho sao lưu" là sao lưu có phiên bản và bất biến; các mũi tên tới "CNPG replica cluster", "Kafka dự phòng" và "Ceph RGW zone thứ hai" là nhân bản liên tục phục vụ chuyển vùng nhanh. Kho sao lưu tiếp tục được chép ra bản ngoại tuyến hằng tháng. Riêng gốc Merkle của sổ cái được neo ra ngoài tới một cơ quan giám sát độc lập, tạo bằng chứng toàn vẹn không phụ thuộc vào bất kỳ bản sao nào do nền tảng tự giữ.

## PostgreSQL: CloudNativePG và Barman Cloud

Mỗi cụm PostgreSQL của cell (`pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure`) có ba lớp bảo vệ dữ liệu. Lớp thứ nhất là nhân bản đồng bộ trong TTDL: mỗi cụm có một primary, một standby đồng bộ và một standby bất đồng bộ trên ba vùng sự cố khác nhau, với `synchronous` cấu hình `method: any, number: 1, dataDurability: required`. Một giao dịch chỉ được xác nhận khi WAL đã ghi trên ít nhất một standby, nên mất một node CSDL không mất giao dịch đã xác nhận (RPO = 0), và CNPG tự promote standby trong khoảng 10–30 giây (RTO ≤ 5 phút). Lớp thứ hai là nhân bản bất đồng bộ sang phía Nam bằng replica cluster của CNPG. Lớp thứ ba là sao lưu theo thời điểm bằng plugin Barman Cloud ghi vào kho S3 phía Nam, cho phép phục hồi tới một thời điểm bất kỳ (PITR) trong 35 ngày.

Từ CNPG 1.26, sao lưu ra kho đối tượng được tách sang plugin `plugin-barman-cloud` theo giao diện CNPG-I, cấu hình bằng CRD `ObjectStore`. Cấu hình kho sao lưu của `pg-core` CELL-HCM:

```yaml
apiVersion: barmancloud.cnpg.io/v1
kind: ObjectStore
metadata:
  name: bk-sn-pg-core
  namespace: data-pg
spec:
  retentionPolicy: "35d"
  configuration:
    destinationPath: s3://bk-cell-hcm-pg/pg-core
    endpointURL: https://s3-bk.sn.vt.local
    endpointCA:
      name: vt-internal-ca
      key: ca.crt
    s3Credentials:
      accessKeyId:     { name: bk-sn-s3-pg, key: ACCESS_KEY_ID }
      secretAccessKey: { name: bk-sn-s3-pg, key: ACCESS_SECRET_KEY }
    wal:
      compression: zstd
      maxParallel: 8
      encryption: AES256
    data:
      compression: zstd
      jobs: 4
      encryption: AES256
      immediateCheckpoint: false
  instanceSidecarConfiguration:
    resources:
      requests: { cpu: "500m", memory: 512Mi }
      limits:   { cpu: "2",    memory: 1Gi }
```

Khoá truy cập `bk-sn-s3-pg` được ESO đồng bộ từ OpenBao, đường dẫn `kv/cell-hcm/backup/pg` (SEC-10); khoá chỉ có quyền `PutObject`, `GetObject`, `ListBucket` trên bucket của cell, không có `DeleteObject` hay `PutBucketObjectLockConfiguration`. Việc dọn bản sao lưu quá 35 ngày không do CNPG thực hiện bằng lệnh xoá mà do vòng đời (lifecycle) của bucket, chạy dưới tài khoản quản trị kho sao lưu và chỉ có hiệu lực sau khi Object Lock hết hạn. Vì vậy `retentionPolicy` trong `ObjectStore` chỉ dùng để CNPG đánh dấu bản nào hết hạn trong danh mục; nếu CNPG cố xoá, RGW từ chối và CNPG ghi cảnh báo, không ảnh hưởng tới sao lưu mới.

Cụm `pg-core` khai báo plugin trong phần `spec` (bổ sung cho manifest ở mục 4.5) và lịch sao lưu nền hằng ngày:

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: pg-core
  namespace: data-pg
spec:
  # các trường đã nêu ở mục 4.5 được giữ nguyên
  plugins:
    - name: barman-cloud.cloudnative-pg.io
      isWALArchiver: true
      parameters:
        barmanObjectName: bk-sn-pg-core
  postgresql:
    parameters:
      archive_timeout: "60s"
---
apiVersion: postgresql.cnpg.io/v1
kind: ScheduledBackup
metadata:
  name: pg-core-daily
  namespace: data-pg
spec:
  schedule: "0 30 1 * * *"
  backupOwnerReference: self
  cluster:
    name: pg-core
  method: plugin
  target: prefer-standby
  pluginConfiguration:
    name: barman-cloud.cloudnative-pg.io
```

Tham số `archive_timeout: 60s` buộc PostgreSQL đóng đoạn WAL ít nhất mỗi phút kể cả khi ít ghi, nên trong trường hợp xấu nhất mất cả hai TTDL thì kho sao lưu chỉ thiếu tối đa khoảng 1 phút WAL. Sao lưu nền chạy lúc 01:30, sau tác vụ tính lại điểm hằng đêm (OPS-21), trên standby để không tải lên primary. Kích thước sao lưu nền của `pg-core` CELL-HCM ước tính khoảng 1,1 TB dữ liệu (mục 4.5) nén zstd còn khoảng 350 GB; với băng thông liên TTDL dành cho sao lưu 2 Gbps và 4 luồng song song, mỗi bản nền mất khoảng 350 × 8 / 2 ≈ 1.400 giây, tức khoảng 25 phút (ước tính). WAL sinh ra ở CELL-HCM vào giờ cao điểm khoảng 30–50 MB mỗi phút (ước tính theo 1.800 thao tác ghi mỗi giây, trung bình 300–450 byte WAL mỗi thao tác sau khi tính cả chỉ mục), nén còn khoảng 10–15 MB, nhỏ so với băng thông.

Replica cluster ở phía Nam được khai báo bằng cùng một manifest với tham số `replica` khác nhau giữa hai TTDL, theo cơ chế chuyển vùng có điều phối (distributed topology) của CNPG. Khi cả hai cụm cùng khai báo hai nguồn ngoài và trường `replica.primary` chỉ tên cụm đang là primary, việc đổi giá trị này ở cả hai phía là thao tác chuyển vùng có kế hoạch (switchover) không mất dữ liệu: cụm cũ hạ cấp, ghi WAL cuối, rồi cụm mới promote.

```yaml
# values-cell-hcm-sn.yaml, phần CNPG của pg-core tại TTDL phía Nam
spec:
  replica:
    self: pg-core-sn
    primary: pg-core-hl
    source: pg-core-hl
  externalClusters:
    - name: pg-core-hl
      connectionParameters:
        host: pg-core-rw.data-pg.cell-hcm-hl.vt.local
        user: streaming_replica
        dbname: postgres
        sslmode: verify-full
      sslKey:      { name: pg-core-replication, key: tls.key }
      sslCert:     { name: pg-core-replication, key: tls.crt }
      sslRootCert: { name: pg-core-ca-hl,       key: ca.crt }
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters:
          barmanObjectName: bk-sn-pg-core
          serverName: pg-core
    - name: pg-core-sn
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters:
          barmanObjectName: bk-sn-pg-core-sn
          serverName: pg-core
```

Replica cluster phía Nam nhận WAL qua luồng trực tiếp; khi luồng gián đoạn, nó tự lấy WAL từ kho sao lưu. Độ trễ nhân bản bình thường dưới 1 giây; cảnh báo `CNPGReplicaClusterLag` được kích hoạt khi độ trễ vượt 5 phút và nâng mức nghiêm trọng khi vượt 10 phút, vì lúc đó RPO 15 phút có nguy cơ bị vi phạm. Debezium kết nối tới primary qua slot logic. Để khi chuyển primary trong TTDL Debezium không mất slot, PostgreSQL 17 với `sync_replication_slots = on` và `failover = true` trên slot được dùng (CNPG hỗ trợ qua `replicationSlots.highAvailability`). Khi chuyển vùng sang TTDL khác, slot không đi theo; Debezium tạo slot mới và kết hợp với cơ chế giữ dòng outbox 72 giờ để không mất sự kiện (mục 8.10).

## Kafka: MirrorMaker 2

Kafka không phải nguồn sự thật của dữ liệu nghiệp vụ, vì mọi sự kiện quan trọng đều có trong bảng outbox của CSDL nguồn trước khi lên Kafka (ADR-02, mẫu outbox). Tuy vậy, mất Kafka cùng TTDL làm mất các sự kiện chưa được tiêu thụ và vị trí đọc (offset) của consumer, nên cần nhân bản. Strimzi `KafkaMirrorMaker2` chạy ở cụm phía Nam, kéo dữ liệu từ cụm Hoà Lạc theo mô hình chủ động, thụ động (active, passive). Cấu hình `IdentityReplicationPolicy` giữ nguyên tên topic để khi chuyển vùng, ứng dụng không phải đổi tên topic; vì vậy chỉ được nhân bản một chiều tại một thời điểm, và chiều nhân bản được đảo khi chuyển vùng.

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaMirrorMaker2
metadata:
  name: mm2-hl-to-sn
  namespace: data-kafka
spec:
  version: 4.0.0
  replicas: 3
  target:
    alias: sn
    bootstrapServers: kafka-sn-kafka-bootstrap.data-kafka.svc:9093
    tls:
      trustedCertificates:
        - secretName: kafka-sn-cluster-ca-cert
          pattern: "*.crt"
    authentication:
      type: tls
      certificateAndKey: { secretName: mm2-sn-user, certificate: user.crt, key: user.key }
  mirrors:
    - source:
        alias: hl
        bootstrapServers: kafka-hl-bootstrap.cell-hcm-hl.vt.local:9093
        tls:
          trustedCertificates:
            - secretName: kafka-hl-cluster-ca-cert
              pattern: "*.crt"
        authentication:
          type: tls
          certificateAndKey: { secretName: mm2-hl-user, certificate: user.crt, key: user.key }
      topicsPattern: "^(okr|task|product|config|scoring|appraisal|policy|org|dossier|esign|ledger|analytics|notify|oversight|integ|sys|iam)\\..*"
      topicsExcludePattern: ".*\\.(retry|replay)\\..*"
      groupsPattern: ".*"
      sourceConnector:
        tasksMax: 24
        config:
          replication.policy.class: org.apache.kafka.connect.mirror.IdentityReplicationPolicy
          replication.factor: 3
          offset-syncs.topic.replication.factor: 3
          sync.topic.acls.enabled: "false"
          refresh.topics.interval.seconds: 60
      checkpointConnector:
        tasksMax: 4
        config:
          replication.policy.class: org.apache.kafka.connect.mirror.IdentityReplicationPolicy
          checkpoints.topic.replication.factor: 3
          sync.group.offsets.enabled: "true"
          sync.group.offsets.interval.seconds: 30
          emit.checkpoints.interval.seconds: 30
  resources:
    requests: { cpu: "2", memory: 4Gi }
    limits:   { cpu: "4", memory: 4Gi }
```

Hai giới hạn của MirrorMaker 2 được tính đến trong kịch bản DR. Thứ nhất, nhân bản là bất đồng bộ, nên vài giây sự kiện cuối có thể chưa sang phía Nam. Thứ hai, offset của consumer được đồng bộ mỗi 30 giây, nên sau chuyển vùng một số sự kiện sẽ được tiêu thụ lại. Cả hai giới hạn được xử lý bằng thiết kế ứng dụng thay vì bằng hạ tầng: mọi consumer xử lý idempotent theo `eventId` (bảng `processed_event` của từng service, baseline mục 13), nên tiêu thụ lại là vô hại; và sự kiện chưa kịp sang phía Nam được phát lại từ bảng outbox của CSDL nguồn, bảng này đã được nhân bản bằng WAL với RPO tốt hơn nhiều so với Kafka.

Topic `sys.audit.v1` có thêm một đường đi riêng không qua MirrorMaker 2: consumer `audit-archiver` ghi từng lô 5 phút ra tệp Parquet có chữ ký vào bucket Object Lock 10 năm của kho sao lưu. Nhờ vậy nhật ký kiểm toán có bản sao bất biến trong vòng 5 phút kể cả khi Kafka phía Nam gặp sự cố.

## Ceph: multisite RGW và Object Lock

Minh chứng (C2, kho đối tượng của SVC-08), biểu mẫu đã ký (SVC-11, SVC-10) và bản xuất báo cáo nằm trong Ceph RGW. Mỗi cell có một realm RGW riêng với hai zone: zone master ở TTDL đang hoạt động, zone thứ hai ở TTDL còn lại, đồng bộ bất đồng bộ đối tượng và siêu dữ liệu. Realm theo cell (thay vì một realm cho cả nhóm cell dùng chung cụm Ceph) để có thể chuyển vùng từng cell độc lập, đúng nguyên tắc ranh giới sự cố theo cell.

```bash
# Khởi tạo realm và zonegroup cho CELL-HCM trên cụm ceph-hl-1
radosgw-admin realm create --rgw-realm=cell-hcm --default
radosgw-admin zonegroup create --rgw-zonegroup=cell-hcm-zg --rgw-realm=cell-hcm \
  --endpoints=https://s3.cell-hcm.hl.vt.local --master --default
radosgw-admin zone create --rgw-zonegroup=cell-hcm-zg --rgw-zone=cell-hcm-hl \
  --endpoints=https://s3.cell-hcm.hl.vt.local --master --default \
  --access-key="$SYNC_AK" --secret="$SYNC_SK"
radosgw-admin user create --uid=sync-cell-hcm --display-name="sync cell-hcm" \
  --access-key="$SYNC_AK" --secret="$SYNC_SK" --system
radosgw-admin period update --commit

# Trên cụm Ceph phía Nam: kéo realm và tạo zone thứ hai
radosgw-admin realm pull --rgw-realm=cell-hcm --url=https://s3.cell-hcm.hl.vt.local \
  --access-key="$SYNC_AK" --secret="$SYNC_SK"
radosgw-admin zone create --rgw-zonegroup=cell-hcm-zg --rgw-zone=cell-hcm-sn \
  --endpoints=https://s3.cell-hcm.sn.vt.local --access-key="$SYNC_AK" --secret="$SYNC_SK"
radosgw-admin period update --commit

# Kiểm tra trạng thái đồng bộ
radosgw-admin sync status --rgw-zone=cell-hcm-sn
```

Biến `SYNC_AK`, `SYNC_SK` được lấy từ OpenBao tại thời điểm chạy bằng `bao kv get -field=...`, không ghi vào tệp hay lịch sử lệnh. Bucket minh chứng bật versioning và Object Lock ngay khi tạo (Object Lock chỉ bật được lúc tạo bucket):

```bash
aws --endpoint-url https://s3.cell-hcm.hl.vt.local s3api create-bucket \
  --bucket ev-cell-hcm --object-lock-enabled-for-bucket
aws --endpoint-url https://s3.cell-hcm.hl.vt.local s3api put-object-lock-configuration \
  --bucket ev-cell-hcm --object-lock-configuration \
  '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Years":10}}}'
```

Minh chứng giữ 10 năm ở chế độ compliance, vì minh chứng là căn cứ của kết quả đánh giá cán bộ và có thể bị khiếu nại hoặc thanh tra sau nhiều năm. Hệ quả là dữ liệu minh chứng chỉ tăng, không giảm: với 192 TB minh chứng mỗi năm (baseline 3.1), sau 10 năm khoảng 1,9 PB dữ liệu thực, tức khoảng 2,9 PB dung lượng thô mỗi năm tích luỹ theo hệ số 1,5 của erasure coding 4+2 (mục 4.8 đã định cỡ). Yêu cầu xoá dữ liệu cá nhân theo Nghị định 13/2023/NĐ-CP được xử lý bằng xoá mật mã (crypto-shredding): xoá DEK của đối tượng trong OpenBao làm bản mã còn lại trong Ceph không giải mã được, mà không phải xoá đối tượng bị khoá. Cơ chế này đã được mô tả ở baseline mục 9 và được kiểm tra trong DR-12.

Đồng bộ multisite có độ trễ bình thường dưới 1 phút với đối tượng nhỏ, nhưng tệp lớn hoặc đợt tải lên dồn cuối kỳ có thể làm độ trễ tăng. Chỉ số `ceph_rgw_sync_behind_shards` và tuổi của đối tượng cũ nhất chưa đồng bộ được giám sát; cảnh báo khi tuổi vượt 10 phút. Ngoài multisite (nhân bản), bucket minh chứng còn được sao lưu theo ngày sang kho sao lưu bằng `rclone sync --backup-dir` chạy dưới tài khoản chỉ đọc nguồn, đáp ứng yêu cầu "bản sao lưu nằm ngoài miền quản trị của hệ chính".

## OpenSearch, ClickHouse, Valkey

OpenSearch và ClickHouse là kho dữ liệu dẫn xuất (CQRS), dựng lại được từ CSDL nguồn và Kafka, nên mục tiêu sao lưu là rút ngắn thời gian dựng lại chứ không phải bảo toàn dữ liệu duy nhất. OpenSearch đăng ký kho snapshot S3 trỏ tới kho sao lưu phía Nam, chụp hằng ngày bằng Snapshot Management, giữ 14 bản:

```json
PUT _plugins/_sm/policies/daily-to-sn
{
  "description": "Snapshot hằng ngày sang kho sao lưu phía Nam",
  "creation": { "schedule": { "cron": { "expression": "0 2 * * *", "timezone": "Asia/Ho_Chi_Minh" } } },
  "deletion": { "schedule": { "cron": { "expression": "0 4 * * *", "timezone": "Asia/Ho_Chi_Minh" } } ,
                "condition": { "max_count": 14, "min_count": 7 } },
  "snapshot_config": { "repository": "bk-sn", "indices": "okr-*,mission-*,evidence-*,org-*", "include_global_state": false }
}
```

Dựng lại chỉ mục OpenSearch của CELL-HCM từ đầu bằng cách đọc lại CSDL nguồn mất khoảng 6–8 giờ (ước tính: khoảng 60 triệu tài liệu, 2.500–3.000 tài liệu mỗi giây mỗi luồng nạp với 4 luồng), vượt RTO 4 giờ; khôi phục từ snapshot rồi bù phần chênh từ Kafka mất khoảng 1 giờ. Vì vậy snapshot là cần thiết dù dữ liệu là dẫn xuất. Trong lúc chỉ mục chưa phục hồi xong, chức năng tìm kiếm toàn văn hạ cấp (mức suy giảm 2, mục 6.9), các chức năng nghiệp vụ chính vẫn hoạt động vì chúng đọc CSDL.

ClickHouse dùng lệnh `BACKUP` gốc ra S3, sao lưu đầy đủ chủ nhật và gia tăng hằng ngày:

```sql
BACKUP DATABASE analytics TO S3('https://s3-bk.sn.vt.local/bk-cell-hcm-ch/full-2026-10-18', '<ak>', '<sk>')
SETTINGS compression_method = 'zstd', async = true;

BACKUP DATABASE analytics TO S3('https://s3-bk.sn.vt.local/bk-cell-hcm-ch/incr-2026-10-19', '<ak>', '<sk>')
SETTINGS base_backup = S3('https://s3-bk.sn.vt.local/bk-cell-hcm-ch/full-2026-10-18', '<ak>', '<sk>'), async = true;
```

Trong cấu hình thật, khoá S3 không viết trong câu lệnh mà được khai báo trong `named_collections` của ClickHouse, nạp từ Secret do ESO đồng bộ; câu lệnh trên chỉ minh hoạ cú pháp. Valkey chỉ chứa bộ nhớ đệm, khoá chống gửi trùng và bộ đếm giới hạn tốc độ, nên không sao lưu; sau khi mất Valkey, các bộ nhớ đệm tự nạp lại và bộ đếm giới hạn tốc độ khởi đầu từ 0, chấp nhận được.

## Kubernetes: Velero và etcd

Trạng thái mong muốn của cụm nằm trong Git (GitOps, OPS-01), nên phần lớn tài nguyên Kubernetes được dựng lại bằng Argo CD chứ không bằng sao lưu. Hai phần không nằm trong Git cần sao lưu riêng. Thứ nhất là etcd, chứa các tài nguyên do operator tạo động (trạng thái CNPG, chứng thư cert-manager, Lease) và các Secret do ESO tạo ra. Thứ hai là volume của các thành phần có trạng thái không có cơ chế sao lưu riêng, như etcd của APISIX và dữ liệu của Harbor.

Snapshot etcd chạy mỗi 6 giờ trên master bằng `etcdutl`, mã hoá bằng khoá lấy từ OpenBao, ghi lên kho sao lưu và giữ 30 ngày. Vì etcd chứa Secret (đã được mã hoá ở mức lưu trữ bằng KMS, SEC-31), snapshot được xử lý như dữ liệu C2.

```bash
ETCDCTL_API=3 etcdctl --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key snapshot save /var/backups/etcd-$(date +%Y%m%dT%H%M).db
etcdutl snapshot status /var/backups/etcd-*.db -w table
age -r "$(bao kv get -field=age_recipient kv/platform/backup/etcd)" \
  -o /var/backups/etcd.db.age /var/backups/etcd-*.db
aws --endpoint-url https://s3-bk.sn.vt.local s3 cp /var/backups/etcd.db.age \
  s3://bk-cell-hcm-k8s/etcd/$(hostname)-$(date +%Y%m%dT%H%M).db.age
shred -u /var/backups/etcd-*.db
```

Velero sao lưu tài nguyên theo namespace và volume của thành phần có trạng thái không thuộc CSDL bằng snapshot CSI của Ceph RBD kèm di chuyển dữ liệu (data mover) lên kho sao lưu. Namespace dữ liệu `data-pg`, `data-kafka`, `data-search` bị loại khỏi sao lưu volume của Velero, vì sao lưu tệp dữ liệu PostgreSQL hay Kafka đang chạy bằng snapshot khối không bảo đảm nhất quán; chúng đã có cơ chế riêng ở các mục trên.

```yaml
apiVersion: velero.io/v1
kind: Schedule
metadata:
  name: cell-resources-6h
  namespace: velero
spec:
  schedule: "15 */6 * * *"
  template:
    includedNamespaces: ["*"]
    excludedNamespaces: ["kube-system", "velero"]
    excludedResources: ["events", "events.events.k8s.io", "pods", "replicasets"]
    snapshotVolumes: false
    ttl: 720h0m0s
    storageLocation: bk-sn
---
apiVersion: velero.io/v1
kind: Schedule
metadata:
  name: stateful-volumes-daily
  namespace: velero
spec:
  schedule: "45 2 * * *"
  template:
    includedNamespaces: ["ingress", "platform-harbor", "observability"]
    snapshotMoveData: true
    defaultVolumesToFsBackup: false
    ttl: 336h0m0s
    storageLocation: bk-sn
```

## OpenBao, HSM và khoá

Sao lưu khoá là nội dung nhạy cảm nhất của chương này, vì mất khoá thì mọi bản sao lưu đã mã hoá trở thành vô dụng, còn lộ khoá thì mọi cơ chế mã hoá trở thành vô nghĩa. Thiết kế tách hai phần.

Phần thứ nhất là trạng thái của OpenBao (Raft). OpenBao chạy 5 node trong pool security-zone, tự mở khoá (auto-unseal) bằng HSM qua PKCS#11, nên khoá gốc (root key) của OpenBao không bao giờ tồn tại dưới dạng rõ ngoài HSM. Snapshot Raft chạy mỗi giờ bằng `bao operator raft snapshot save`, nội dung snapshot đã được mã hoá bằng khoá của chính OpenBao. Snapshot được ghi lên kho sao lưu phía Nam, giữ 90 ngày. Khôi phục một snapshot chỉ có ích khi có HSM chứa khoá mở tương ứng.

Phần thứ hai là khoá trong HSM: KEK theo tenant, khoá ký của sổ cái, khoá dấu thời gian, khoá mở OpenBao. HSM ở Hoà Lạc và HSM ở phía Nam được ghép trong cùng một miền bảo mật (security domain) của nhà sản xuất, nên khoá được nhân bản giữa hai HSM ở dạng đã bọc bằng khoá miền, không bao giờ ở dạng rõ. Bản sao lưu của miền bảo mật được giữ trên thẻ thông minh theo cơ chế M trong N (3 trong 5 thẻ), cất trong két tại hai địa điểm khác nhau, người giữ thẻ là cán bộ của khách hàng (không phải của đội vận hành), và mọi lần dùng thẻ đều có biên bản. Quy trình này đáp ứng yêu cầu "khoá theo khách hàng trong HSM" của cấp độ 4 và tiêu chí D8.

## Neo sổ cái ra ngoài

Theo ADR-09 và cách làm chi tiết của TL-01 (ADR-26), ledger-service (SVC-09) dựng cây Merkle hai cấp: gốc của từng tenant là lá của gốc cell. Mỗi giờ, cell chỉ cần một chữ ký bằng khoá trong HSM và một dấu thời gian RFC 3161 cho gốc cell; trong 3 ngày cuối kỳ đánh giá, chu kỳ niêm phong rút xuống 5 phút, tức 288 chữ ký và dấu thời gian mỗi ngày mỗi cell, vẫn rất nhỏ so với năng lực HSM. Bản ghi niêm phong được ghi vào `ledger.merkle_seal` (liên kết theo khoảng `seq` của từng chuỗi). Hằng ngày, bản neo tổng hợp các gốc đã niêm phong được gửi tới ít nhất hai bên độc lập ngoài nền tảng: một dịch vụ dấu thời gian của tổ chức chứng thực bên ngoài, và một hệ thống lưu trữ của cơ quan giám sát độc lập (ví dụ đơn vị thanh tra của chính khách hàng); OPS-22 kiểm tra cả hai bên đã xác nhận nhận được. Neo ngoài làm cho việc sửa sổ cái không thể che giấu: kể cả khi kẻ tấn công kiểm soát toàn bộ nền tảng và cả bản sao lưu, gốc Merkle ở điểm neo vẫn cho phép phát hiện sửa đổi. Bản neo còn là cơ sở của kịch bản DR-14: khi chuỗi băm bị phát hiện đứt, đội vận hành xác định được khoảng thời gian nào còn tin cậy được bằng cách đối chiếu với bản neo.

## Bảng lịch sao lưu

Bảng dưới đây gom toàn bộ lịch sao lưu của một cell. Cột RPO cho biết lượng dữ liệu tối đa có thể mất nếu phải khôi phục từ chính bản sao lưu này (không tính nhân bản).

| Đối tượng | Cơ chế | Tần suất | Giữ | Bất biến | RPO khi khôi phục từ sao lưu | Kiểm tra tự động |
|---|---|---|---|---|---|---|
| PostgreSQL (6 cụm) | Barman Cloud, nền + WAL | Nền hằng ngày 01:30; WAL liên tục, `archive_timeout` 60 s | 35 ngày, bản cuối tháng 12 tháng | Object Lock 35 ngày, bản tháng 1 năm | ≤ 1 phút | Khôi phục thử hằng ngày một cụm luân phiên vào cụm cách ly |
| `pg-ledger`, `pg-secure` | Như trên, thêm bản cuối năm | Như trên | 10 năm cho bản cuối năm | Object Lock 10 năm | ≤ 1 phút | Như trên, kèm `verify_run` chuỗi băm |
| Kafka | MirrorMaker 2 (nhân bản), không sao lưu | Liên tục | Theo `retention.ms` của topic | Không áp dụng | Vài giây, bù từ outbox | Đối soát offset hằng ngày |
| `sys.audit.v1` | `audit-archiver` ra Parquet ký số | 5 phút | 10 năm | Object Lock 10 năm | ≤ 5 phút | Kiểm chữ ký và đếm bản ghi hằng ngày |
| Minh chứng, biểu mẫu ký | RGW multisite + `rclone` sang kho sao lưu | Liên tục + hằng ngày | 10 năm | Object Lock 10 năm cả bản chính và bản sao | ≤ 24 giờ từ sao lưu; vài phút từ multisite | Lấy mẫu 1.000 đối tượng mỗi ngày, so SHA-256 với `evidence_version` |
| OpenSearch | Snapshot S3 | Hằng ngày 02:00 | 14 bản | Object Lock 14 ngày | Dẫn xuất, bù từ Kafka hoặc CSDL | Khôi phục thử hằng tuần |
| ClickHouse | `BACKUP` ra S3 | Đầy đủ chủ nhật, gia tăng hằng ngày | 5 tuần | Object Lock 35 ngày | Dẫn xuất, bù từ Kafka | Khôi phục thử hằng tháng |
| etcd | `etcdctl snapshot` mã hoá | 6 giờ | 30 ngày | Object Lock 30 ngày | ≤ 6 giờ (phần lớn dựng lại từ Git) | `etcdutl snapshot status` sau mỗi bản |
| Tài nguyên K8s, volume không thuộc CSDL | Velero | 6 giờ và hằng ngày | 30 và 14 ngày | Object Lock theo bucket | ≤ 6 giờ | Khôi phục thử hằng tháng vào cụm staging |
| OpenBao | Snapshot Raft | Hằng giờ | 90 ngày | Object Lock 90 ngày | ≤ 1 giờ | Khôi phục thử hằng quý |
| Khoá HSM | Nhân bản trong miền bảo mật, thẻ M trong N | Khi tạo khoá mới | Vĩnh viễn trong vòng đời khoá | Thẻ vật lý trong két | 0 | Kiểm kê thẻ hằng quý |
| Kho Git (GitOps, mã nguồn, Policy Pack) | Gương sang phía Nam + bundle hằng ngày | Liên tục + hằng ngày | 1 năm | Object Lock 1 năm | ≤ 24 giờ | Clone thử hằng tuần |
| Harbor (ảnh, SBOM, chữ ký) | Nhân bản Harbor sang `harbor-sn` | Theo sự kiện đẩy ảnh | Theo chính sách giữ của Harbor | Không áp dụng | Vài phút | Kéo thử ảnh theo digest hằng ngày |
| Toàn bộ kho sao lưu | Bản ngoại tuyến | Hằng tháng | 12 tháng | Tách mạng, băng LTO-9 WORM | ≤ 1 tháng | Đọc thử 5% băng hằng quý |

Dung lượng kho sao lưu phía Nam cho CELL-HCM ước tính như sau. PostgreSQL: 35 bản nền mỗi bản khoảng 350 GB nén cộng WAL khoảng 15 GB nén mỗi giờ cao điểm, trung bình 150 GB mỗi ngày, tổng 35 × 350 + 35 × 150 ≈ 17,5 TB, cộng bản tháng và năm khoảng 6 TB, khoảng 24 TB. Minh chứng: CELL-HCM chiếm khoảng 20% tổng số tài khoản, nên khoảng 20% × 192 TB ≈ 38 TB mỗi năm (ước tính). OpenSearch và ClickHouse: khoảng 10 TB. Toàn bộ nhóm khác: dưới 5 TB. Tổng năm đầu khoảng 77 TB dữ liệu cho CELL-HCM, nhân hệ số erasure coding 8+3 của kho sao lưu (1,375) được khoảng 106 TB thô. Toàn quốc năm đầu ước tính khoảng 450 TB thô ở kho sao lưu, tăng khoảng 280 TB mỗi năm chủ yếu do minh chứng.

## Giữ outbox 72 giờ để phát lại sau chuyển vùng

Một chi tiết thiết kế bổ sung so với baseline là thời gian giữ dòng trong bảng `outbox_event` của mọi service. Baseline mô tả outbox và Debezium nhưng không nêu thời gian giữ. TL-04 quy định dòng outbox đã được Debezium đọc vẫn được giữ 72 giờ trước khi tác vụ dọn xoá (theo phân vùng ngày, xoá bằng `DROP PARTITION` để không gây phình bảng). Lý do: khi chuyển vùng, Kafka phía Nam có thể thiếu vài giây tới vài phút sự kiện cuối, và slot logic của Debezium không đi theo sang TTDL khác. Bước "phát lại outbox" trong DR-10 khởi động Debezium phía Nam với snapshot chế độ `when_needed` trên bảng outbox và lọc theo `created_at` từ thời điểm "điểm an toàn" (thời điểm WAL cuối đã có ở phía Nam trừ đi 15 phút), rồi để consumer loại bản trùng theo `eventId`. Thời gian 72 giờ đủ cho một sự cố cuối tuần được phát hiện muộn. Chi phí lưu trữ thêm của `pg-core` CELL-HCM ước tính 1.800 sự kiện mỗi giây × 1 KB × 86.400 giây × 3 ngày ≈ 470 GB ở đỉnh, thực tế thấp hơn nhiều vì đỉnh chỉ kéo dài vài giờ mỗi ngày.

## Kịch bản DR

Mỗi kịch bản DR dưới đây có mã, phạm vi, mục tiêu RPO và RTO, cách phát hiện, các bước chính, người chịu trách nhiệm và tần suất diễn tập. Runbook chi tiết từng lệnh nằm trong kho runbook (OPS-14); tài liệu này nêu bước ở mức đủ để kiểm tra tính khả thi và để thiết kế diễn tập. Vai trò được dùng: Chỉ huy sự cố (IC, trưởng ca trực SRE), DBA trực, SRE nền tảng, kỹ sư an ninh trực (SOC), chủ sản phẩm của cell, đại diện khách hàng.

| Mã | Tình huống | RPO | RTO | Tự động hay thủ công | Người chịu trách nhiệm | Diễn tập |
|---|---|---|---|---|---|---|
| DR-01 | Mất pod | 0 | ≤ 1 phút | Tự động (Kubernetes) | Không cần người | Liên tục trong PERF-07 |
| DR-02 | Mất node ứng dụng | 0 | ≤ 3 phút | Tự động | SRE theo dõi | Hằng tháng (chaos) |
| DR-03 | Mất một vùng sự cố trong TTDL | 0 | ≤ 10 phút | Tự động | IC xác nhận | Hằng quý (PERF-10) |
| DR-04 | Mất primary PostgreSQL | 0 | ≤ 5 phút (thực tế ≤ 30 s) | Tự động (CNPG) | DBA kiểm tra sau | Hằng tháng |
| DR-05 | Mất toàn bộ một cụm PostgreSQL trong TTDL | ≤ 1 phút | ≤ 1 giờ | Thủ công | DBA, IC | Hằng quý |
| DR-06 | Hỏng dữ liệu logic, cần PITR | Tới thời điểm trước lỗi | ≤ 4 giờ | Thủ công | DBA, chủ sản phẩm | Hằng quý |
| DR-07 | Mất cụm Kafka của cell | ≤ vài giây (bù từ outbox) | ≤ 1 giờ | Thủ công | SRE | Nửa năm |
| DR-08 | Mất cụm Ceph phục vụ cell | ≤ vài phút | ≤ 2 giờ | Thủ công | SRE lưu trữ | Nửa năm |
| DR-09 | Phải dựng lại cụm Kubernetes của cell | ≤ 1 phút | ≤ 4 giờ | Thủ công, có tự động hoá | SRE nền tảng | Nửa năm |
| DR-10 | Mất TTDL Hoà Lạc | ≤ 15 phút | ≤ 4 giờ | Thủ công, quyết định của IC | IC, toàn đội | Hằng năm toàn phần, hằng quý một cell |
| DR-11 | Mã độc tống tiền hoặc kẻ tấn công có quyền quản trị | Tới bản sạch cuối cùng | ≤ 24 giờ | Thủ công | SOC, IC, lãnh đạo | Hằng năm (diễn tập trên bàn và kỹ thuật) |
| DR-12 | Lộ khoá | 0 | ≤ 4 giờ để thu hồi; xoay vòng theo kế hoạch | Thủ công | SOC, quản trị khoá | Hằng năm |
| DR-13 | Mất vùng điều phối toàn cục | 0 | ≤ 15 phút | Tự động một phần (GeoDNS) | SRE | Hằng quý |
| DR-14 | Chuỗi băm sổ cái bị đứt | 0 | Phát hiện ≤ 24 giờ, điều tra ≤ 72 giờ | Thủ công | SOC, DBA, kiểm toán | Hằng năm |
| DR-15 | Mất OpenBao hoặc HSM | 0 | ≤ 1 giờ | Thủ công | Quản trị khoá | Hằng năm |
| DR-16 | Quay về TTDL chính sau DR | 0 | Theo kế hoạch, ngoài giờ | Thủ công | IC, DBA | Sau mỗi DR-10 thật hoặc diễn tập |

Các tình huống DR-01 đến DR-04 được xử lý tự động nhờ thiết kế ba vùng sự cố ở chương 3 và đã được kiểm chứng bằng các kịch bản thử hỏng PERF-07 đến PERF-10; chúng chỉ cần người theo dõi. Phần dưới đây mô tả các kịch bản cần người quyết định và thao tác.

**DR-05: mất toàn bộ một cụm PostgreSQL trong TTDL.** Tình huống này xảy ra khi cả ba instance của một cụm hỏng cùng lúc, ví dụ do lỗi lưu trữ lan rộng hoặc thao tác sai. Phát hiện: cảnh báo `CNPGClusterNotHealthy` và tỷ lệ lỗi 5xx của service phụ thuộc tăng vọt. Bước xử lý: (1) IC xác nhận và chuyển cell sang mức suy giảm phù hợp; (2) DBA đánh giá nguyên nhân; nếu lưu trữ cục bộ hỏng, dựng cụm mới cùng tên từ kho sao lưu bằng `bootstrap.recovery` với nguồn là `ObjectStore` (khôi phục nền mới nhất rồi phát WAL tới điểm cuối), mất khoảng 25–40 phút cho `pg-core` 1,1 TB (ước tính theo tốc độ khôi phục 1 GB/s từ kho cục bộ trong cùng TTDL qua bản sao S3 gần nhất); (3) nếu khôi phục quá lâu, promote replica cluster phía Nam cho riêng cụm đó và để ứng dụng ở Hoà Lạc kết nối chéo TTDL tạm thời, chấp nhận độ trễ tăng khoảng 20–30 ms mỗi truy vấn; (4) kiểm tra tính nhất quán bằng đối soát số dòng các bảng chính và `verify_run` nếu là `pg-ledger`.

**DR-06: hỏng dữ liệu logic, cần PITR.** Ví dụ: một bản phát hành có lỗi migration xoá sai cột, hoặc một thao tác hàng loạt cập nhật sai điểm của một tenant. Nguyên tắc: không khôi phục đè lên cụm đang chạy, mà khôi phục sang một cụm tạm tới thời điểm trước lỗi, sau đó trích và sửa phần dữ liệu bị ảnh hưởng. Bước: (1) chủ sản phẩm và DBA xác định thời điểm lỗi qua `audit_log` và `task_state_log`; (2) DBA tạo cụm `pg-core-pitr` từ sao lưu với `recoveryTarget.targetTime` trước lỗi một phút; (3) trích dữ liệu đúng của tenant hoặc bảng bị ảnh hưởng; (4) áp dụng sửa bằng script được duyệt qua CAB khẩn (OPS-19), chạy trong giao dịch, có ghi sổ cái; (5) phát sự kiện bù để các kho dẫn xuất cập nhật; (6) huỷ cụm tạm. Nếu lỗi ảnh hưởng toàn bộ cell và không thể sửa cục bộ, IC có thể quyết định khôi phục toàn cụm về thời điểm trước lỗi, chấp nhận mất các giao dịch sau thời điểm đó, và thông báo cho người dùng nhập lại; quyết định này cần chủ sản phẩm và đại diện khách hàng đồng ý.

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: pg-core-pitr
  namespace: data-pg-restore
spec:
  instances: 1
  imageName: harbor.vt.local/cnpg/postgresql@sha256:REPLACE_WITH_DIGEST
  storage: { storageClass: nvme-local, size: 2Ti }
  bootstrap:
    recovery:
      source: pg-core-src
      recoveryTarget:
        targetTime: "2026-10-12 14:31:00+07"
        exclusive: true
  externalClusters:
    - name: pg-core-src
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters:
          barmanObjectName: bk-sn-pg-core
          serverName: pg-core
```

**DR-07: mất cụm Kafka của cell.** Bước: (1) chuyển ứng dụng sang cụm Kafka phía Nam bằng cách đổi `bootstrap.servers` trong ConfigMap do Argo CD quản lý (một commit), các producer và consumer khởi động lại theo đợt; (2) Debezium khởi động lại, đọc tiếp từ slot hiện có ở primary Hoà Lạc (CSDL vẫn còn), không mất sự kiện; (3) consumer dùng offset đã được MirrorMaker 2 đồng bộ, tiêu thụ lại phần trùng một cách idempotent; (4) khi cụm Kafka Hoà Lạc được dựng lại, đảo chiều MirrorMaker 2 và chuyển về theo DR-16. Thời gian chủ yếu nằm ở bước khởi động lại ứng dụng theo đợt, khoảng 20–30 phút.

**DR-08: mất cụm Ceph phục vụ cell.** Vì cụm Ceph được chia sẻ theo nhóm cell (mục 3.8), sự cố này ảnh hưởng đồng thời CELL-HCM và CELL-HN (nếu là `ceph-hl-1`). Bước: (1) đổi zone master của realm từng cell bị ảnh hưởng sang zone phía Nam bằng `radosgw-admin zone modify --rgw-zone=cell-hcm-sn --master --default` và `radosgw-admin period update --commit` trên phía Nam; (2) đổi endpoint S3 trong cấu hình của evidence-service, docgen-service, esign-service (một commit GitOps); (3) volume RBD của các thành phần dùng `ceph-rbd` được khôi phục từ Velero hoặc dựng lại; CSDL không dùng Ceph RBD (dùng NVMe cục bộ) nên không bị ảnh hưởng; (4) đối tượng chưa kịp đồng bộ trong vài phút cuối được xác định bằng đối chiếu `evidence_version` với danh sách đối tượng ở zone phía Nam, người tải lên được thông báo tải lại. Trong lúc chuyển, tải lên minh chứng tạm dừng (mức suy giảm 2), các chức năng khác vẫn hoạt động.

**DR-09: dựng lại cụm Kubernetes của cell.** Dùng khi control plane hỏng không sửa được hoặc cụm bị nghi đã bị xâm nhập. Bước: (1) Cluster API dựng cụm mới cùng tên trên máy ảo mới từ ảnh hệ điều hành đã ký (khoảng 30 phút); (2) Argo CD đồng bộ các ứng dụng nền theo thứ tự sóng đồng bộ (sync wave): CRD, operator, OpenBao agent, ESO, cert-manager, rồi tới dữ liệu và ứng dụng; (3) CSDL được dựng lại từ replica cluster phía Nam (đảo chiều, coi phía Nam là nguồn) hoặc từ sao lưu, tuỳ phía Nam có còn khoẻ không; (4) Kafka dựng mới và nạp lại từ phía Nam qua MirrorMaker 2 đảo chiều; (5) kiểm tra khói và chuyển lưu lượng về. Nếu nghi bị xâm nhập, không khôi phục etcd hay Velero từ thời điểm sau mốc nghi ngờ, mà dựng lại sạch từ Git và chỉ khôi phục dữ liệu nghiệp vụ.

**DR-10: mất TTDL Hoà Lạc.** Đây là kịch bản kiểm chứng NFR-05 cấp TTDL. Quyết định chuyển vùng là quyết định của con người (IC sau khi tham vấn trưởng ca và, nếu kịp, giám đốc vận hành), không tự động. Lý do: tự động chuyển vùng khi mạng giữa hai TTDL chập chờn có thể tạo hai primary cùng ghi (split-brain), hậu quả nặng hơn nhiều so với 30 phút chờ quyết định. Mỗi cell chuyển vùng độc lập, theo thứ tự ưu tiên do khách hàng chốt trước (mặc định: CELL-DTW nếu đặt ở Hoà Lạc, CELL-HN, CELL-HCM, rồi các cell còn lại), với nhiều nhóm thao tác song song.

![Hình 8.2. Dòng thời gian chuyển vùng một cell khi mất TTDL Hoà Lạc](tk-dr-failover.png){width=16cm}

Hình 8.2 trình bày dòng thời gian mục tiêu cho một cell. Nhóm phát hiện mất tối đa 15 phút để xác nhận sự cố là thật (loại trừ sự cố mạng tạm thời giữa hai TTDL bằng kiểm tra từ ba điểm quan sát độc lập) và 15 phút để IC ra quyết định. Sau đó ba nhóm thao tác dữ liệu chạy song song: promote PostgreSQL, chuyển Kafka, đổi zone master của Ceph. Nhóm ứng dụng tăng số replica và node ở phía Nam, khởi động Debezium với slot mới và phát lại outbox, rồi kiểm tra khói và kiểm tra chuỗi băm. Cuối cùng đổi bản ghi GeoDNS của cell và theo dõi SLO. Tổng thời gian mục tiêu khoảng 2 giờ 50 phút, còn khoảng 70 phút dự phòng so với RTO 4 giờ.

Các bước chi tiết của DR-10 cho một cell:

1. IC tuyên bố sự cố SEV-1, mở kênh chỉ huy, giao vai trò. Ghi thời điểm T0.
2. SRE xác nhận Hoà Lạc mất từ ba điểm quan sát (phía Nam, CDN, điểm đo bên ngoài). Nếu Hoà Lạc chỉ mất kết nối với phía Nam nhưng vẫn phục vụ người dùng, không chuyển vùng.
3. DBA rào chắn (fence) primary cũ: nếu còn tiếp cận được Hoà Lạc qua kênh quản trị dự phòng, đặt annotation `cnpg.io/fencedInstances` cho mọi instance; nếu không, coi như đã rào chắn vì mất điện hoặc mất mạng hoàn toàn, và ghi nhận trong biên bản.
4. DBA promote replica cluster phía Nam cho từng cụm PostgreSQL bằng cách đổi `replica.primary` thành `pg-*-sn` trong values phía Nam (commit GitOps, Argo CD phía Nam đồng bộ). Đo RPO bằng so sánh LSN cuối đã nhận ở phía Nam với LSN cuối trong kho sao lưu và thời điểm của giao dịch cuối; ghi vào biên bản.
5. SRE dừng MirrorMaker 2, xác nhận topic phía Nam ở trạng thái đọc ghi, cấu hình ứng dụng phía Nam đã trỏ sẵn vào Kafka phía Nam.
6. SRE lưu trữ đổi zone master Ceph của realm cell sang phía Nam.
7. SRE tăng số replica tối thiểu của ứng dụng phía Nam từ mức chờ ấm (khoảng 30% so với Hoà Lạc) lên mức thường bằng cách bật hồ sơ `active` trong values (một commit), Cluster Autoscaler tăng node, dùng phần năng lực dự trữ 2.000 vCPU đã ký với Viettel Cloud (mục 4.4).
8. Kỹ sư nền tảng khởi động Debezium phía Nam với slot mới, chạy tác vụ phát lại outbox từ điểm an toàn (mục 8.10).
9. Đội kiểm thử chạy bộ kiểm tra khói tự động (đăng nhập, xem việc của tôi, cập nhật tiến độ, tải lên minh chứng, xem dashboard) và `verify_run` cho sổ cái 24 giờ gần nhất.
10. SRE cập nhật cờ `active_site = sn` của cell trong SVC-03; tác vụ `dns-sync` đổi bản ghi A của điểm vào cell sang IP phía Nam, TTL 60 giây; JWKS và endpoint Keycloak của cell không đổi tên miền nên phiên đăng nhập còn hạn được giữ nếu khoá ký đã được nhân bản (Keycloak `pg-iam` đã được nhân bản cùng PostgreSQL).
11. IC theo dõi SLO 40 phút, thông báo cho khách hàng qua kênh đã thống nhất, ghi T kết thúc và tính RTO.
12. Trong lúc chạy ở phía Nam, các tác vụ không thiết yếu tạm dừng để dồn năng lực cho nghiệp vụ chính: tính lại toàn bộ hằng đêm, dựng lại chỉ mục, suy luận AI (ai-gateway trả 503 có `Retry-After`, mức suy giảm 2).

Phân tích RPO của DR-10: luồng WAL bất đồng bộ sang phía Nam có độ trễ bình thường dưới 1 giây, nên RPO thực tế thường dưới 1 phút; cảnh báo ở 5 phút và 10 phút bảo đảm có phản ứng trước khi vượt 15 phút. Nếu luồng WAL đã đứt trước sự cố, phía Nam vẫn có WAL từ kho sao lưu với độ trễ tối đa `archive_timeout` (60 giây) cộng thời gian tải lên. Như vậy RPO 15 phút được đáp ứng với biên an toàn lớn (ước tính, xác nhận trong diễn tập).

**DR-11: mã độc tống tiền hoặc kẻ tấn công có quyền quản trị.** Giả định xấu nhất: kẻ tấn công có quyền quản trị cụm Kubernetes và CSDL của một hoặc nhiều cell, đã mã hoá hoặc xoá dữ liệu, có thể đã nằm vùng nhiều ngày. Nguyên tắc: không tin tưởng hệ thống hiện tại; khôi phục từ bản sao lưu bất biến lên hạ tầng sạch; không khôi phục bất kỳ cấu hình hay tệp thực thi nào từ thời điểm sau mốc nghi ngờ. Bước: (1) SOC cô lập cell (cắt kết nối Internet, giữ kênh quản trị qua PAM), thu thập chứng cứ số; (2) xác định mốc nghi ngờ sớm nhất qua SIEM, nhật ký kiểm toán API Kubernetes, nhật ký OpenBao; (3) thu hồi toàn bộ thông tin xác thực của cell (DR-12); (4) dựng cụm sạch theo DR-09 từ Git đã xác minh chữ ký commit và ảnh đã ký cosign; (5) khôi phục CSDL từ bản nền trong kho Object Lock tại thời điểm trước mốc nghi ngờ, phát WAL tới mốc đó; (6) đối chiếu sổ cái với bản neo ngoài để xác nhận dữ liệu khôi phục chưa bị sửa; (7) nhập lại các giao dịch sau mốc từ nguồn khác nếu có (ví dụ chứng từ giấy); (8) báo cáo cơ quan chức năng theo quy định. RTO 24 giờ ở đây là mục tiêu cho khôi phục kỹ thuật; thời gian điều tra có thể dài hơn.

**DR-12: lộ khoá.** Quy trình xử lý lộ khoá (SEC-60) phân theo loại khoá. Lộ mật khẩu ứng dụng tới CSDL hoặc Kafka: OpenBao thu hồi lease của secret động, ứng dụng tự nhận thông tin mới, không cần dừng dịch vụ. Lộ khoá ký JWT của Keycloak: xoay khoá, xoá khoá cũ khỏi JWKS, buộc mọi phiên đăng nhập lại; người dùng thấy phải đăng nhập lại một lần. Lộ DEK của một tenant: tạo DEK mới, mã hoá lại dữ liệu C2 của tenant đó bằng tác vụ nền (`rewrap`), thu hồi DEK cũ sau khi xong. Lộ KEK trong HSM gần như không xảy ra vì khoá không rời HSM; nếu nghi ngờ HSM bị chiếm quyền, tạo KEK mới trên HSM khác và bọc lại toàn bộ DEK. Lộ khoá ký sổ cái: tạo khoá mới, ghi một bản ghi "chuyển khoá" vào sổ cái ký bằng cả hai khoá, công bố dấu vân tay khoá mới tới điểm neo ngoài.

**DR-13: mất vùng điều phối toàn cục.** Vùng điều phối toàn cục (danh bạ tenant SVC-03, bộ định tuyến toàn cục, Keycloak toàn cục của SVC-02, trang chọn đơn vị) chạy chủ động ở Hoà Lạc, chờ nóng ở phía Nam. GeoDNS PowerDNS với `ifurlup` tự chuyển `app.danhgia.vn` sang phía Nam khi kiểm tra sức khoẻ thất bại 3 lần liên tiếp (mục 2.7). Đây là chuyển vùng tự động duy nhất trong hệ thống, được phép vì vùng điều phối toàn cục chỉ đọc là chủ yếu: dữ liệu ghi của nó (đăng ký tenant, bảng định tuyến) thay đổi rất ít và được nhân bản đồng bộ có xác nhận từ xa (`synchronous_commit = remote_apply` qua hai TTDL cho riêng cụm này, chấp nhận độ trễ ghi 20–30 ms). Người dùng đã vào cell không bị ảnh hưởng, vì cell hoạt động độc lập với vùng điều phối toàn cục khi đã có token.

**DR-14: chuỗi băm sổ cái bị đứt.** Tác vụ kiểm tra hằng ngày (OPS-22) chạy `verify_run` trên toàn bộ bản ghi của 24 giờ trước và kiểm tra ngẫu nhiên 1% bản ghi cũ hơn. Khi phát hiện đứt: (1) SOC mở sự cố an ninh, khoá quyền ghi trực tiếp vào `pg-ledger` của mọi tài khoản trừ ledger-service; (2) xác định dải bản ghi bị ảnh hưởng bằng cách đối chiếu gốc Merkle với bản neo ngoài; (3) so sánh với bản sao lưu bất biến trước thời điểm đứt để xác định nội dung gốc; (4) điều tra xem dữ liệu nghiệp vụ tương ứng có bị sửa không, bằng cách đối chiếu bản ghi sổ cái gốc với dữ liệu hiện tại trong `pg-core`; (5) báo cáo cho đơn vị kiểm toán. Không có thao tác "sửa chuỗi": bản ghi sổ cái là bất biến, sửa chữa được ghi thành bản ghi mới tham chiếu tới sự cố.

**DR-15: mất OpenBao hoặc HSM.** Mất một node OpenBao: Raft tự bầu lại leader, không gián đoạn. Mất toàn bộ cụm OpenBao: ứng dụng tiếp tục chạy với DEK đã lưu đệm (15 phút) và secret đã được ESO đồng bộ vào Kubernetes; trong thời gian đó quản trị khoá dựng lại OpenBao từ snapshot Raft mới nhất, tự mở khoá bằng HSM. Mất HSM Hoà Lạc: OpenBao chuyển sang HSM phía Nam trong cùng miền bảo mật qua cấu hình PKCS#11 nhiều khe; độ trễ mở DEK tăng khoảng 20–30 ms, chấp nhận được vì DEK được lưu đệm. Mất cả hai HSM: khôi phục miền bảo mật lên HSM thay thế từ thẻ M trong N, cần có mặt 3 người giữ thẻ; RTO phụ thuộc thời gian có HSM thay thế, nên hợp đồng với nhà cung cấp HSM phải có điều khoản thay thế trong 24 giờ.

**DR-16: quay về TTDL chính.** Sau khi Hoà Lạc được khôi phục, việc quay về là thao tác có kế hoạch, làm ngoài giờ cao điểm, không vội. Bước: (1) dựng lại cụm Hoà Lạc ở vai trò thứ cấp: CNPG ở Hoà Lạc khai báo `replica.primary = pg-*-sn` và đồng bộ từ phía Nam; MirrorMaker 2 chạy chiều phía Nam sang Hoà Lạc; Ceph zone Hoà Lạc đồng bộ lại; (2) khi độ trễ nhân bản ổn định dưới 1 giây, thực hiện switchover có điều phối: đổi `replica.primary` về `pg-*-hl` ở cả hai phía, CNPG hạ cấp phía Nam và promote Hoà Lạc mà không mất dữ liệu; (3) đảo MirrorMaker 2, đổi zone master Ceph, đổi `active_site` của cell; (4) kiểm tra khói, theo dõi SLO. Thời gian mục tiêu khoảng 1 giờ với gián đoạn ghi dưới 5 phút.

## Diễn tập khôi phục

Một bản sao lưu chưa khôi phục thử là một bản sao lưu chưa tồn tại. Chương trình diễn tập có ba cấp. Cấp tự động hằng ngày: tác vụ `restore-check` dựng một cụm PostgreSQL tạm trong namespace `data-pg-restore` của cụm staging, khôi phục luân phiên một trong các cụm PostgreSQL của một cell từ bản nền mới nhất cộng WAL tới 1 giờ trước, chạy bộ truy vấn kiểm tra (đếm dòng các bảng chính, so với thống kê đã ghi khi sao lưu, kiểm tra ràng buộc khoá ngoại, `verify_run` với `pg-ledger`), ghi kết quả lên Prometheus (`backup_restore_check_success`, `backup_restore_duration_seconds`) rồi huỷ cụm tạm. Cảnh báo nếu một cụm không có lần khôi phục thử thành công trong 8 ngày. Đây là thành phần "0 lỗi" của nguyên tắc 3-2-1-1-0.

Cấp kỹ thuật hằng quý: diễn tập DR-10 cho một cell, luân phiên giữa các cell, thực hiện trong khung giờ thấp điểm có thông báo trước, chuyển vùng thật sang phía Nam, chạy ở phía Nam ít nhất 4 giờ, rồi quay về theo DR-16. Các diễn tập DR-05, DR-06, DR-07, DR-13 được thực hiện trong cùng đợt hoặc trên `CELL-PERF`. Cấp toàn phần hằng năm: diễn tập mất TTDL Hoà Lạc cho toàn bộ hệ thống (trừ cell quốc phòng có lịch riêng), kết hợp với diễn tập trên bàn DR-11 và DR-12 với sự tham gia của lãnh đạo khách hàng.

Mỗi lần diễn tập có biên bản ghi: kịch bản, thời điểm từng bước, RPO đo được, RTO đo được, các bước không chạy đúng runbook, hạng mục khắc phục. Biên bản là bằng chứng nghiệm thu cho vấn đề C05, nhóm "Sao lưu dự phòng" của TCVN 11930 và chỉ số theo dõi "RPO 15 phút, RTO 4 giờ" của đợt 3 trong bản đánh giá. Nếu RTO đo được vượt 3 giờ (75% ngân sách), runbook và tự động hoá phải được cải thiện trước lần diễn tập tiếp theo.

# An toàn thông tin

Chương này gom toàn bộ biện pháp an toàn thông tin ở tầng triển khai và vận hành thành một danh mục mã SEC-01 đến SEC-60. Mỗi mã là một biện pháp kiểm tra được, có cách nghiệm thu, và được truy về tiêu chí trục D của bản đánh giá, các vấn đề S01 đến S16 mà bản đánh giá đã phát hiện ở hệ thống hiện tại, và yêu cầu cấp độ 4 theo TCVN 11930:2017. Thiết kế bảo mật ở tầng ứng dụng (mô hình phân quyền, phân loại dữ liệu C0 đến C3, sổ cái) đã có trong baseline mục 9, 10 và TL-01; TL-04 tập trung vào cách triển khai, cấu hình và kiểm chứng chúng trên Kubernetes.

## Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05

Bản đánh giá xác định năm lỗ hổng nghiêm trọng phải khắc phục ngay, cùng ba lỗ hổng mức cao S07, S08, S11 được gộp vào đợt 0a (2 tuần) với tiêu chí hoàn thành "0 phát hiện secret scanning; retest xâm nhập đạt". Các lỗ hổng này tồn tại ở hệ thống hiện tại (vOffice, backend Oracle), nên mỗi mã SEC dưới đây có hai phần: hành động khắc phục trên hệ hiện tại trong đợt 0a, và biện pháp kiểm soát vĩnh viễn trên nền tảng mới để lỗi không tái diễn.

| Mã SEC | Lỗ hổng | Khắc phục trên hệ hiện tại (đợt 0a) | Kiểm soát vĩnh viễn trên nền tảng mới | Bằng chứng nghiệm thu |
|---|---|---|---|---|
| SEC-01 | S01: thông tin xác thực và khoá riêng trong mã nguồn (CWE-798, CWE-321) | Xoay vòng mọi bí mật đã lộ; xoá khỏi lịch sử Git; chuyển sang biến môi trường lấy từ Secret | OpenBao, ESO, secret động; gitleaks chặn ở pre-commit và pipeline (SEC-09, SEC-10) | gitleaks quét toàn bộ lịch sử: 0 phát hiện |
| SEC-02 | S02: 9 tài khoản test được cấp token không cần mật khẩu (CWE-912) | Xoá nhánh mã cấp token; vô hiệu hoá 9 tài khoản; thu hồi token đang hiệu lực | Không có mã xác thực trong ứng dụng; xác thực chỉ qua Keycloak; Semgrep chặn mẫu "so sánh tên người dùng với hằng chuỗi" | Retest xâm nhập; rà soát mã |
| SEC-03 | S03: mọi URL chứa "/public" bỏ qua kiểm JWT (CWE-288) | Thay mẫu chứa chuỗi bằng danh sách khớp chính xác đường dẫn và phương thức | APISIX là điểm kiểm JWT duy nhất; danh sách tuyến công khai khai báo tường minh trong `ApisixRoute` có duyệt bảo mật; mặc định từ chối | ZAP thử 200 biến thể đường dẫn; 0 vượt qua |
| SEC-04 | S04: mật khẩu dạng rõ trong cookie đọc được bằng JavaScript (CWE-312, CWE-539) | Xoá mã ghi cookie; xoá cookie cũ phía client; buộc đổi mật khẩu người dùng bị ảnh hưởng | Luồng OIDC Authorization Code + PKCE; ứng dụng không bao giờ thấy mật khẩu; token trong bộ nhớ, refresh token trong cookie HttpOnly Secure SameSite=Strict | Kiểm tra trình duyệt; ZAP quy tắc cookie |
| SEC-05 | S05: SQL Injection qua ORDER BY, ≥ 150 chỗ nối chuỗi SQL (CWE-89) | Danh sách cho phép tên cột sắp xếp; sửa 150 chỗ nối chuỗi theo thứ tự rủi ro, bắt đầu từ các API công khai | jOOQ, JPA tham số hoá; Semgrep quy tắc `sql-concat` chặn merge; người dùng CSDL của ứng dụng không có quyền DDL | Semgrep: 0 phát hiện; sqlmap trên API sắp xếp: 0 |
| SEC-06 | S07: CORS cho mọi origin kèm credentials, không có CSRF token (CWE-942, CWE-352) | Danh sách origin cụ thể; bật CSRF cho các form còn dùng cookie phiên | CORS cấu hình ở APISIX theo danh sách origin của tenant; API dùng Bearer token nên không chịu CSRF; refresh token SameSite=Strict | ZAP quy tắc CORS |
| SEC-07 | S08: ghi JWT, refresh token, body request vào log (CWE-532) | Xoá log ở `JwtTokenFilter`; xoá log cũ chứa token; xoay vòng khoá ký JWT | Bộ lọc che dữ liệu nhạy cảm trong Logback và Fluent Bit (SEC-53); kiểm tra tự động tìm mẫu JWT trong Loki | Truy vấn Loki tìm `eyJ`: 0 kết quả |
| SEC-08 | S11: JWT lộ ra JavaScript và URL, cookie thiếu Secure, SameSite (CWE-598, CWE-614) | Bỏ `?token=` khỏi 5 URL; thêm Secure, SameSite cho mọi cookie | Token không bao giờ nằm trong URL; tải tệp bằng URL ký trước thời hạn ngắn của Ceph thay vì token trong URL | ZAP; tìm `token=` trong nhật ký truy cập APISIX: 0 |

**Khắc phục S01 trên hệ hiện tại.** Trình tự bắt buộc là xoay vòng trước, xoá khỏi lịch sử sau. Nếu chỉ xoá khỏi lịch sử Git mà không xoay vòng, bí mật vẫn hợp lệ trong tay bất kỳ ai đã clone repo trước đó. Danh sách bí mật cần xoay vòng lấy từ kết quả gitleaks trên toàn bộ lịch sử của 4 repo; bản đánh giá ước tính khoảng 65 phát hiện. Thứ tự xoay vòng theo mức ảnh hưởng: kubeconfig của cụm sản xuất (tạo lại chứng thư, thu hồi chứng thư cũ), khoá riêng RSA dùng ký JWT (tạo cặp khoá mới, buộc mọi người đăng nhập lại), mật khẩu Oracle, mật khẩu SSO, thông tin xác thực VNeID (yêu cầu cấp lại với Trung tâm dữ liệu quốc gia về dân cư). Sau khi xoay vòng, dọn lịch sử bằng `git filter-repo`, buộc mọi người clone lại, và bật bảo vệ đẩy bí mật ở máy chủ Git.

```bash
# Quét toàn bộ lịch sử và xuất báo cáo SARIF
gitleaks git --redact --report-format sarif --report-path gitleaks-full.sarif /src/backend2.0

# Sau khi đã xoay vòng toàn bộ bí mật: xoá tệp và chuỗi khỏi lịch sử
git filter-repo --invert-paths --path k8s/voffice-k8s-config --path-glob '*.pem' --path-glob '*.p12'
git filter-repo --replace-text ../replacements.txt   # mỗi dòng: chuỗi_bí_mật==>***REMOVED***
git push --force --all && git push --force --tags
```

**Khắc phục S03.** Mẫu lỗi là bộ lọc kiểm tra `uri.contains("/public")`, nên `/api/admin/users?x=/public` hoặc `/api/public/../admin/users` đều được bỏ qua. Bản sửa trên hệ hiện tại thay bằng danh sách khớp chính xác đường dẫn đã chuẩn hoá cùng phương thức:

```java
private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
    "GET /api/public/health",
    "GET /api/public/version",
    "POST /api/public/auth/login");

private boolean isPublic(HttpServletRequest req) {
  String path = UriComponentsBuilder.fromPath(req.getRequestURI()).build().normalize().getPath();
  return PUBLIC_ENDPOINTS.contains(req.getMethod() + " " + path);
}
```

Trên nền tảng mới, quyết định "tuyến nào không cần token" không nằm trong mã ứng dụng mà trong cấu hình APISIX được quản lý bằng GitOps. Mọi `ApisixRoute` mặc định gắn plugin `openid-connect` với `bearer_only: true`; một tuyến chỉ được bỏ plugin này khi có nhãn `security.vt/public: "true"` và chính sách Kyverno chỉ chấp nhận nhãn đó khi commit được ít nhất một thành viên nhóm bảo mật duyệt (kiểm tra bằng CODEOWNERS của thư mục `routes/public/` trong kho GitOps). Ứng dụng phía sau vẫn kiểm tra lại JWT (phòng thủ nhiều lớp) bằng Spring Security Resource Server.

**Khắc phục S05.** Lỗi ORDER BY xuất hiện vì tên cột sắp xếp không truyền được qua tham số ràng buộc. Bản sửa là ánh xạ tên trường do client gửi sang tên cột qua danh sách cho phép, không bao giờ đưa chuỗi của client vào câu SQL:

```java
private static final Map<String, String> SORTABLE = Map.of(
    "createdAt", "d.created_date", "title", "d.title", "deadline", "d.deadline");

String orderBy(String field, String dir) {
  String col = SORTABLE.get(field);
  if (col == null) throw new BadRequestException("Trường sắp xếp không hợp lệ");
  return col + ("desc".equalsIgnoreCase(dir) ? " DESC" : " ASC");
}
```

Quy tắc Semgrep chặn mẫu nối chuỗi SQL được đưa vào pipeline của cả hệ hiện tại và nền tảng mới; trên hệ hiện tại, quy tắc chạy ở chế độ báo cáo cho mã cũ và chế độ chặn cho mã thay đổi, để 150 chỗ cũ được sửa dần mà không chặn mọi bản phát hành.

```yaml
rules:
  - id: vt-sql-string-concat
    languages: [java]
    severity: ERROR
    message: "Không nối chuỗi vào câu SQL. Dùng tham số ràng buộc hoặc jOOQ (SEC-05)."
    patterns:
      - pattern-either:
          - pattern: $STMT.executeQuery("..." + $X)
          - pattern: $STMT.execute("..." + $X)
          - pattern: $EM.createNativeQuery("..." + $X, ...)
          - pattern: $JDBC.query("..." + $X, ...)
          - pattern: $SB.append("ORDER BY " + $X)
    metadata: { cwe: "CWE-89", owasp: "A03:2021" }
```

Kết thúc đợt 0a, đơn vị kiểm thử xâm nhập độc lập kiểm tra lại cả tám lỗ hổng (SEC-41). Chỉ số theo dõi "phát hiện secret scanning" phải từ khoảng 65 về 0, và chỉ số "vấn đề nghiêm trọng còn mở" giảm từ 12 xuống ít nhất 7 (năm lỗ hổng S01 đến S05 đóng), hướng tới ≤ 4 ở cuối đợt 0.

## Danh mục SEC và ánh xạ trục D

Bảng dưới đây là danh mục đầy đủ SEC-01 đến SEC-60, kèm tiêu chí trục D, vấn đề của bản đánh giá và yêu cầu cấp độ 4 mà mỗi mã đáp ứng. Bảng này là đầu vào của ma trận truy vết ở chương 13.

| Mã | Biện pháp | Trục D, vấn đề | Mục |
|---|---|---|---|
| SEC-01 | Loại bỏ và xoay vòng bí mật trong mã | D1, S01 | 9.1 |
| SEC-02 | Xoá tài khoản ẩn, xác thực tập trung | D3, S02 | 9.1 |
| SEC-03 | Danh sách tuyến công khai khớp chính xác | D3, S03 | 9.1 |
| SEC-04 | Không lưu mật khẩu phía client, OIDC PKCE | D4, S04 | 9.1 |
| SEC-05 | Truy vấn tham số hoá, chặn nối chuỗi SQL | D7, S05 | 9.1 |
| SEC-06 | CORS theo danh sách, chống CSRF | D7, S07 | 9.1 |
| SEC-07 | Không ghi token vào log | D9, S08 | 9.1 |
| SEC-08 | Không đặt JWT trong URL, cookie an toàn | D5, S11 | 9.1 |
| SEC-09 | gitleaks ở pre-commit, pipeline, quét định kỳ | D1 | 9.6 |
| SEC-10 | OpenBao và ESO, secret động cho CSDL | D1 | 9.4 |
| SEC-11 | Mã hoá phong bì bằng HSM, DEK theo tenant | D8, D12 | 9.4 |
| SEC-12 | Xoay vòng khoá và bí mật định kỳ | D1, D8 | 9.4 |
| SEC-13 | MFA bắt buộc (TOTP, WebAuthn, VNeID mức 2) | D2, S06 | 9.3 |
| SEC-14 | Chống dò mật khẩu: khoá tạm, giới hạn tốc độ | D2, S06 | 9.3 |
| SEC-15 | Băm mật khẩu Argon2id hoặc PBKDF2 có salt | D4, S06 | 9.3 |
| SEC-16 | Thời hạn phiên: access 5 phút, refresh 8 giờ, thu hồi ngay | D5, S12 | 9.3 |
| SEC-17 | PKCE, state, nonce cho mọi client OIDC | D5 | 9.3 |
| SEC-18 | Phân quyền tập trung OPA, ABAC theo phạm vi tổ chức | D6, S14 | 9.3 |
| SEC-19 | Chống XSS: mã hoá đầu ra, CSP nghiêm ngặt | D7, S13 | 9.8 |
| SEC-20 | Mật mã được phê duyệt, cấm trust-all, cấm DES và MD5 | D8, S09 | 9.4 |
| SEC-21 | mTLS STRICT giữa các service | D8 | 9.5 |
| SEC-22 | Nhật ký sự kiện an ninh đẩy về SIEM | D9 | 9.3 |
| SEC-23 | Quét thành phần (SCA) và SBOM CycloneDX | D10, S10 | 9.6 |
| SEC-24 | Header bảo mật HTTP | D11, S16 | 9.5 |
| SEC-25 | Mã hoá đầu cuối dữ liệu C3 | D12 | 9.4 |
| SEC-26 | Pod Security Standards mức restricted | Cấp độ 4, C04 | 9.3 |
| SEC-27 | Kiểm tra CIS bằng kube-bench | Cấp độ 4 | 9.3 |
| SEC-28 | Chính sách Kyverno bắt buộc | C04 | 9.3 |
| SEC-29 | RBAC tối thiểu, không dùng cluster-admin hằng ngày | D6 | 9.3 |
| SEC-30 | Nhật ký kiểm toán API Kubernetes | D9 | 9.3 |
| SEC-31 | Mã hoá Secret trong etcd bằng KMS v2 | D8 | 9.3 |
| SEC-32 | NetworkPolicy mặc định từ chối | Cấp độ 4 | 3.6, 9.5 |
| SEC-33 | Phân vùng mạng theo vùng bảo mật | Cấp độ 4 | 9.5 |
| SEC-34 | WAF Coraza với OWASP CRS | D7 | 9.5 |
| SEC-35 | Chống DDoS nhiều lớp | Cấp độ 4 | 9.5 |
| SEC-36 | Kiểm soát lưu lượng ra ngoài | Cấp độ 4 | 9.5 |
| SEC-37 | Chuỗi cung ứng SLSA mức 3 | D10, C04 | 9.6 |
| SEC-38 | Ký ảnh và chứng thực bằng cosign | D10, C04 | 9.6 |
| SEC-39 | SAST Semgrep chặn merge | D7 | 9.6 |
| SEC-40 | DAST OWASP ZAP | D7, D11 | 9.8 |
| SEC-41 | Kiểm thử xâm nhập độc lập | Cấp độ 4 | 9.8 |
| SEC-42 | PAM ghi phiên truy cập đặc quyền | D6, cấp độ 4 | 9.3 |
| SEC-43 | SOC 24/7 và SIEM | D9, cấp độ 4 | 9.7 |
| SEC-44 | Sao lưu 3-2-1-1-0 bất biến | C5, cấp độ 4 | 8.1 |
| SEC-45 | Gói cài đặt ngoại tuyến có chữ ký | Cấp độ 4 | 9.9 |
| SEC-46 | Mật mã do Ban Cơ yếu Chính phủ cấp cho triển khai quốc phòng | Cấp độ 4 | 9.9 |
| SEC-47 | Quét mã độc tệp tải lên bằng ClamAV | D7 | 9.5 |
| SEC-48 | Giới hạn tải lên: kích thước, loại tệp, kiểm tra nội dung thật | D7 | 9.5 |
| SEC-49 | Ảnh distroless, không root, hệ thống tệp chỉ đọc | C04 | 9.3 |
| SEC-50 | Phát hiện hành vi bất thường lúc chạy (Falco, Tetragon) | D9 | 9.3 |
| SEC-51 | Vá hệ điều hành node tự động (kured) | D10 | 9.3 |
| SEC-52 | Đánh giá tác động dữ liệu cá nhân, che dữ liệu ngoài sản xuất | D12 | 9.7 |
| SEC-53 | Che thông tin cá nhân trong log | D9, D12, S15 | 10.3 |
| SEC-54 | Phát hiện đăng nhập bất thường | D2 | 9.3 |
| SEC-55 | Chỉ dùng ảnh theo digest, cấm thẻ `latest` | D10 | 9.3 |
| SEC-56 | Bảo vệ etcd: mạng riêng, mTLS, mã hoá đĩa | D8 | 9.3 |
| SEC-57 | TLS và ACL cho Kafka, Valkey, PostgreSQL | D8 | 9.5 |
| SEC-58 | DBA không đọc được dữ liệu C2; quy trình phá kính | D6, D12 | 9.4 |
| SEC-59 | cert-manager với PKI của OpenBao | D8 | 9.4 |
| SEC-60 | Quy trình xử lý lộ khoá | D8 | 8.11 (DR-12) |

Ánh xạ ngược từ trục D sang mã SEC, để kiểm tra rằng mỗi tiêu chí D đang ở mức thấp đều có biện pháp:

| Tiêu chí | Nội dung tóm tắt | Mã SEC chính | Mã SEC hỗ trợ |
|---|---|---|---|
| D1 | Quản lý bí mật | SEC-01, SEC-09, SEC-10 | SEC-12 |
| D2 | MFA, chống dò mật khẩu | SEC-13, SEC-14 | SEC-54 |
| D3 | Không cửa sau, không bỏ qua xác thực | SEC-02, SEC-03 | SEC-28 |
| D4 | Lưu mật khẩu an toàn | SEC-04, SEC-15 | |
| D5 | Quản lý phiên, token | SEC-08, SEC-16, SEC-17 | |
| D6 | Kiểm soát truy cập | SEC-18 | SEC-29, SEC-42, SEC-58 |
| D7 | Chống tiêm mã, XSS, CSRF | SEC-05, SEC-06, SEC-19 | SEC-34, SEC-39, SEC-40, SEC-47, SEC-48 |
| D8 | Mật mã và bảo vệ đường truyền | SEC-11, SEC-20, SEC-21, SEC-59 | SEC-31, SEC-56, SEC-57, SEC-60 |
| D9 | Nhật ký an ninh | SEC-07, SEC-22 | SEC-30, SEC-43, SEC-50, SEC-53 |
| D10 | Thành phần có lỗ hổng, chuỗi cung ứng | SEC-23 | SEC-37, SEC-38, SEC-51, SEC-55 |
| D11 | Header và cấu hình bảo mật HTTP | SEC-24 | SEC-40 |
| D12 | Bảo vệ dữ liệu nhạy cảm | SEC-25, SEC-11 | SEC-52, SEC-53, SEC-58 |

## Bảo mật nền tảng Kubernetes

**Pod Security Standards (SEC-26).** Mọi namespace ứng dụng và dữ liệu gắn nhãn `pod-security.kubernetes.io/enforce: restricted` (mục 3.5). Một số thành phần hạ tầng cần quyền cao hơn (Cilium, CSI driver của Ceph, node-exporter, Falco) chạy trong namespace `kube-system` và `platform-privileged` với mức `privileged`, và chỉ nhóm SRE nền tảng có quyền triển khai vào hai namespace này. Kyverno bổ sung các ràng buộc mà PSS không có.

**Kiểm tra CIS (SEC-27).** kube-bench chạy dưới dạng Job hằng tuần trên mọi node theo CIS Kubernetes Benchmark tương ứng phiên bản, kết quả đẩy lên Prometheus và báo cáo tuân thủ. Mục tiêu: 0 mục FAIL ở control plane và worker, các mục WARN có giải trình. Các thiết lập quan trọng nhất của API server được cố định trong cấu hình kubeadm của Cluster API:

```yaml
apiVersion: controlplane.cluster.x-k8s.io/v1beta1
kind: KubeadmControlPlane
metadata:
  name: cell-hcm-hl-cp
spec:
  replicas: 3
  version: v1.36.2
  kubeadmConfigSpec:
    clusterConfiguration:
      apiServer:
        extraArgs:
          - { name: anonymous-auth, value: "false" }
          - { name: authorization-mode, value: "Node,RBAC" }
          - { name: enable-admission-plugins, value: "NodeRestriction" }
          - { name: profiling, value: "false" }
          - { name: audit-policy-file, value: /etc/kubernetes/audit-policy.yaml }
          - { name: audit-log-path, value: /var/log/kubernetes/audit/audit.log }
          - { name: audit-log-maxage, value: "7" }
          - { name: audit-log-maxbackup, value: "10" }
          - { name: audit-log-maxsize, value: "200" }
          - { name: encryption-provider-config, value: /etc/kubernetes/encryption-config.yaml }
          - { name: tls-min-version, value: VersionTLS12 }
          - { name: service-account-lookup, value: "true" }
          - { name: oidc-issuer-url, value: "https://sso-ops.vt.local/realms/platform-ops" }
          - { name: oidc-client-id, value: kubernetes }
          - { name: oidc-username-claim, value: preferred_username }
          - { name: oidc-groups-claim, value: groups }
      controllerManager:
        extraArgs:
          - { name: profiling, value: "false" }
          - { name: terminated-pod-gc-threshold, value: "1000" }
      etcd:
        local:
          extraArgs:
            - { name: cipher-suites, value: "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384" }
```

**Mã hoá Secret trong etcd (SEC-31, SEC-56).** Secret được mã hoá ở mức lưu trữ bằng KMS v2, với plugin KMS là OpenBao Transit (khoá gốc trong HSM). Như vậy một bản sao etcd hay một snapshot bị lộ không làm lộ Secret. etcd chạy trên chính master, lắng nghe trên mạng quản trị riêng, mọi kết nối dùng mTLS, đĩa của master được mã hoá bằng LUKS.

```yaml
apiVersion: apiserver.config.k8s.io/v1
kind: EncryptionConfiguration
resources:
  - resources: ["secrets"]
    providers:
      - kms:
          apiVersion: v2
          name: openbao-transit
          endpoint: unix:///var/run/kms/openbao.sock
          timeout: 3s
      - identity: {}
```

Nhà cung cấp `identity` đặt cuối để API server đọc được Secret cũ chưa mã hoá trong lúc chuyển đổi; sau khi chạy `kubectl get secrets -A -o json | kubectl replace -f -` để ghi lại toàn bộ Secret bằng khoá KMS, mục này được giữ nhưng không còn dữ liệu nào dùng nó, và kube-bench kiểm tra điều đó.

**Nhật ký kiểm toán API (SEC-30).** Chính sách kiểm toán ghi đầy đủ yêu cầu và phản hồi cho thao tác trên Secret, RBAC và `pods/exec`, ghi mức siêu dữ liệu cho mọi thao tác ghi khác, và bỏ qua các thao tác đọc lặp lại của thành phần hệ thống để giảm khối lượng. Nhật ký được Fluent Bit thu thập vào luồng kiểm toán riêng (mục 10.3) và gửi tới SIEM.

```yaml
apiVersion: audit.k8s.io/v1
kind: Policy
omitStages: ["RequestReceived"]
rules:
  - level: None
    users: ["system:kube-proxy", "system:apiserver"]
    verbs: ["get", "list", "watch"]
  - level: None
    resources: [{ group: "coordination.k8s.io", resources: ["leases"] }]
  - level: Metadata
    resources: [{ group: "", resources: ["secrets", "configmaps"] }]
  - level: RequestResponse
    resources:
      - { group: "rbac.authorization.k8s.io" }
      - { group: "", resources: ["pods/exec", "pods/attach", "pods/portforward", "serviceaccounts/token"] }
  - level: RequestResponse
    resources: [{ group: "postgresql.cnpg.io" }, { group: "kafka.strimzi.io" }]
    verbs: ["create", "update", "patch", "delete"]
  - level: Metadata
    verbs: ["create", "update", "patch", "delete", "deletecollection"]
  - level: None
```

Secret được ghi ở mức `Metadata`, không phải `RequestResponse`, để nhật ký kiểm toán không trở thành nơi chứa nội dung bí mật.

**RBAC (SEC-29).** Không người dùng nào có `cluster-admin` thường trực. Người vận hành đăng nhập `kubectl` bằng OIDC qua Keycloak riêng của vận hành (`platform-ops`), có MFA, và nhận quyền theo nhóm. Quyền cao được cấp theo thời hạn qua PAM: người cần quyền mở yêu cầu, người duyệt chấp thuận, PAM cấp `ClusterRoleBinding` có thời hạn 4 giờ và ghi toàn bộ phiên (SEC-42). Bảng vai trò:

| Vai trò | Phạm vi | Quyền | Cấp phát |
|---|---|---|---|
| `view-app` | Namespace ứng dụng của cell | `get`, `list`, `watch` trên tài nguyên trừ Secret | Thường trực, nhóm phát triển và SRE |
| `operate-app` | Namespace ứng dụng | Thêm `delete pods`, `patch` scale và rollout restart | Thường trực, SRE trực |
| `debug-app` | Namespace ứng dụng | Thêm `pods/exec`, `pods/portforward` | Qua PAM, 4 giờ |
| `view-data` | `data-*` | Đọc tài nguyên CNPG, Strimzi; không đọc Secret | Thường trực, DBA |
| `operate-data` | `data-*` | Thao tác CRD CNPG, Strimzi | Qua PAM, 4 giờ, DBA |
| `platform-admin` | Toàn cụm | `cluster-admin` | Qua PAM, 4 giờ, hai người duyệt; phá kính |
| Argo CD | Toàn cụm | Tạo, sửa tài nguyên được khai báo trong ApplicationSet | Tài khoản dịch vụ, không người dùng |

**Chính sách Kyverno bắt buộc (SEC-28, SEC-49, SEC-55, SEC-38).** Kyverno chạy ở chế độ `Enforce` cho các chính sách dưới đây; chính sách mới được đưa vào ở chế độ `Audit` ít nhất hai tuần trước khi chuyển sang `Enforce`. Ngoại lệ chỉ được cấp bằng `PolicyException` có hạn, nằm trong kho GitOps và qua duyệt bảo mật.

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: vt-require-signed-images
  annotations:
    policies.kyverno.io/description: "Chỉ chạy ảnh từ Harbor nội bộ, theo digest, có chữ ký cosign và chứng thực SLSA (SEC-38, SEC-55)."
spec:
  validationFailureAction: Enforce
  webhookTimeoutSeconds: 15
  background: false
  rules:
    - name: verify-signature-and-provenance
      match:
        any:
          - resources:
              kinds: ["Pod"]
              namespaceSelector:
                matchExpressions:
                  - { key: vt.io/zone, operator: In, values: ["app", "data", "secure", "edge", "egress"] }
      verifyImages:
        - imageReferences: ["harbor.vt.local/*"]
          mutateDigest: false
          verifyDigest: true
          required: true
          attestors:
            - entries:
                - keys:
                    kms: "hashivault://cosign-release"
          attestations:
            - type: https://slsa.dev/provenance/v1
              attestors:
                - entries:
                    - keys:
                        kms: "hashivault://cosign-release"
              conditions:
                - all:
                    - key: "{{ buildDefinition.externalParameters.repository }}"
                      operator: AnyIn
                      value: ["https://git.vt.local/okr/*"]
                    - key: "{{ runDetails.builder.id }}"
                      operator: Equals
                      value: "https://jenkins.vt.local/okr-builder"
---
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: vt-pod-hardening
spec:
  validationFailureAction: Enforce
  background: true
  rules:
    - name: only-internal-registry-by-digest
      match: { any: [{ resources: { kinds: ["Pod"] } }] }
      exclude: { any: [{ resources: { namespaces: ["kube-system", "platform-privileged"] } }] }
      validate:
        message: "Ảnh phải lấy từ harbor.vt.local và ghi theo digest sha256 (SEC-55)."
        foreach:
          - list: "request.object.spec.[containers, initContainers, ephemeralContainers][]"
            deny:
              conditions:
                any:
                  - key: "{{ element.image }}"
                    operator: NotEquals
                    value: "harbor.vt.local/*@sha256:*"
    - name: readonly-rootfs-nonroot
      match: { any: [{ resources: { kinds: ["Pod"] } }] }
      exclude: { any: [{ resources: { namespaces: ["kube-system", "platform-privileged"] } }] }
      validate:
        message: "Container phải chạy không root, UID ≥ 10000, hệ thống tệp gốc chỉ đọc (SEC-49)."
        pattern:
          spec:
            securityContext:
              runAsNonRoot: true
            containers:
              - securityContext:
                  readOnlyRootFilesystem: true
                  allowPrivilegeEscalation: false
                  runAsUser: ">=10000"
                  capabilities:
                    drop: ["ALL"]
    - name: require-resources-and-probes
      match:
        any:
          - resources:
              kinds: ["Pod"]
              namespaceSelector: { matchLabels: { vt.io/zone: app } }
      validate:
        message: "Container ứng dụng phải có requests, limits bộ nhớ, readinessProbe và livenessProbe (C01, C03)."
        pattern:
          spec:
            containers:
              - resources:
                  requests: { cpu: "?*", memory: "?*" }
                  limits: { memory: "?*" }
                readinessProbe: { httpGet: { port: 8081 } }
                livenessProbe: { httpGet: { port: 8081 } }
    - name: no-automount-sa-token-unless-needed
      match: { any: [{ resources: { kinds: ["Pod"], selector: { matchLabels: { vt.io/needs-k8s-api: "false" } } } }] }
      validate:
        message: "Pod không gọi Kubernetes API phải tắt automountServiceAccountToken."
        pattern:
          spec:
            automountServiceAccountToken: false
---
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: vt-require-ha
spec:
  validationFailureAction: Enforce
  rules:
    - name: deployment-min-replicas-and-spread
      match:
        any:
          - resources:
              kinds: ["Deployment", "Rollout"]
              namespaceSelector: { matchLabels: { vt.io/zone: app } }
      validate:
        message: "Service sản xuất cần ít nhất 3 replica và topologySpreadConstraints theo zone (chỉ số 'Số node tối thiểu mỗi service' = 3)."
        pattern:
          spec:
            replicas: ">=3"
            template:
              spec:
                topologySpreadConstraints:
                  - topologyKey: topology.kubernetes.io/zone
                    whenUnsatisfiable: DoNotSchedule
    - name: require-pdb
      match:
        any:
          - resources:
              kinds: ["Deployment", "Rollout"]
              namespaceSelector: { matchLabels: { vt.io/zone: app } }
      preconditions:
        all:
          - key: "{{ request.operation }}"
            operator: Equals
            value: CREATE
      validate:
        message: "Mỗi workload phải có PodDisruptionBudget cùng nhãn app.kubernetes.io/name."
        deny:
          conditions:
            all:
              - key: "{{ length(lookup('policy/v1', 'PodDisruptionBudget', '{{ request.namespace }}', '{{ request.object.metadata.name }}') || `[]`) }}"
                operator: Equals
                value: 0
```

Chính sách `require-pdb` được chạy ở chế độ `Audit` cho tới khi mọi chart thư viện đã sinh PDB trước Deployment trong cùng sóng đồng bộ Argo CD, vì thứ tự tạo tài nguyên trong một lần đồng bộ có thể khiến kiểm tra lookup thất bại giả.

**Phát hiện bất thường lúc chạy (SEC-50).** Falco với trình điều khiển eBPF hiện đại chạy trên mọi node, với bộ quy tắc mặc định cộng các quy tắc riêng: shell được mở trong container của namespace ứng dụng hoặc dữ liệu; tiến trình ghi vào thư mục ngoài `/tmp` của container chỉ đọc; kết nối ra ngoài từ pod không thuộc namespace `egress`; đọc tệp `/var/run/secrets` bởi tiến trình khác tiến trình chính. Tetragon được dùng cho chính sách thực thi ở nhân (ví dụ chặn `ptrace` trong namespace `secure`). Cảnh báo từ Falco qua Falcosidekick vào Kafka `sys.security.runtime.v1` rồi tới SIEM; quy tắc mức `Critical` gọi ngay người trực SOC.

**Vá hệ điều hành node (SEC-51).** Node dùng ảnh hệ điều hành tối giản dựng sẵn (Ubuntu 24.04 LTS bản tối giản hoặc Flatcar), vá bằng cách thay ảnh node theo chu kỳ hằng tháng qua Cluster API (thay node mới, không vá tại chỗ). Với bản vá nhân khẩn, kured khởi động lại từng node theo khung giờ 01:00–05:00, tôn trọng PDB và không chạy trong cửa sổ đóng băng trừ khi lỗ hổng có CVSS ≥ 9.

**Xác thực người dùng (SEC-13 đến SEC-17, SEC-54).** Các biện pháp này cấu hình trong Keycloak của từng cell và được kiểm tra trong pipeline cấu hình (Keycloak cấu hình bằng `keycloak-config-cli` từ kho GitOps, không chỉnh tay). MFA bắt buộc với mọi người dùng ở cấp độ 4: OTP qua ứng dụng (TOTP) hoặc WebAuthn; VNeID mức 2 được coi là đã đạt MFA. Luồng đăng nhập không có nhánh bỏ qua MFA (sửa S06: "MFA bỏ qua được"). Chống dò mật khẩu: khoá tạm thời 15 phút sau 5 lần sai, khoá cố định sau 20 lần sai trong 24 giờ, kết hợp giới hạn tốc độ theo IP ở APISIX. Băm mật khẩu Argon2id (Keycloak 26 trở lên hỗ trợ), áp dụng dần khi người dùng đăng nhập; mật khẩu chuyển từ hệ cũ được băm lại khi đăng nhập lần đầu. Thời hạn: access token 5 phút, refresh token 8 giờ ngày làm việc, phiên SSO tối đa 10 giờ; đăng xuất hoặc khoá tài khoản thu hồi ngay: Keycloak phát sự kiện xác thực lên topic `iam.auth.event.v1` (quy ước của TL-01), một consumer ghi danh sách phiên bị thu hồi vào Valkey và APISIX kiểm tra danh sách này. Phát hiện đăng nhập bất thường: quy tắc trong SIEM cho đăng nhập từ quốc gia mới, đăng nhập đồng thời từ hai vị trí xa, số lần sai tăng đột biến theo tenant.

**Phân quyền tập trung (SEC-18).** Theo TL-01 (ADR-21), OPA chạy là một container trong cùng pod với service và được gọi qua localhost, vì Istio ambient không có sidecar để gắn bộ kiểm tra ủy quyền. Chart thư viện `vt-service` thêm container `opa` (requests 100m CPU, 128 MiB; giới hạn 256 MiB) với probe riêng; gói chính sách Rego được phát hành qua kho GitOps, tải bằng cơ chế bundle có chữ ký và OPA kiểm chữ ký trước khi nạp. Khi OPA không trả lời trong 50 ms hoặc lỗi, thư viện `vt-authz` từ chối truy cập (đóng khi lỗi), không bao giờ cho qua; readinessProbe của pod phụ thuộc cả container ứng dụng và OPA, nên pod chưa nạp xong gói chính sách không nhận lưu lượng. Danh sách dùng đánh giá từng phần (partial evaluation) để sinh điều kiện lọc SQL thay vì kiểm từng dòng. Thiết kế mô hình phân quyền theo phạm vi tổ chức nằm trong TL-01; ở tầng triển khai, yêu cầu là gói chính sách có kiểm thử đơn vị (`opa test`) đạt 100% trong pipeline và mọi quyết định từ chối được ghi vào luồng nhật ký an ninh (SEC-22).

## Quản lý bí mật và khoá: OpenBao, ESO, HSM

**Kiến trúc (SEC-10, SEC-11).** Mỗi cell có một cụm OpenBao 5 node trong namespace `secure`, tự mở khoá bằng HSM qua PKCS#11 (mục 8.7). OpenBao cung cấp bốn loại dịch vụ: kho khoá giá trị (KV v2) cho bí mật tĩnh; secret động cho PostgreSQL (mỗi pod nhận người dùng CSDL riêng có thời hạn); Transit cho mã hoá phong bì (bọc DEK bằng KEK) và ký cosign; PKI cho chứng thư nội bộ. Ứng dụng không đọc OpenBao trực tiếp cho bí mật tĩnh mà qua External Secrets Operator, để ứng dụng chỉ thấy Secret Kubernetes chuẩn; riêng Transit và secret động được gọi trực tiếp qua thư viện `vt-crypto` với xác thực Kubernetes.

Cấu hình xác thực Kubernetes và chính sách của mission-core:

```bash
bao auth enable -path=k8s-cell-hcm kubernetes
bao write auth/k8s-cell-hcm/config kubernetes_host="https://api.cell-hcm-hl.vt.local:6443" \
  kubernetes_ca_cert=@/etc/bao/k8s-ca.crt disable_local_ca_jwt=true

bao policy write mission-core - <<'EOF'
path "kv/data/cell-hcm/mission-core/*"   { capabilities = ["read"] }
path "database/creds/pg-core-mission"     { capabilities = ["read"] }
path "transit/encrypt/kek-tenant-*"       { capabilities = ["update"] }
path "transit/decrypt/kek-tenant-*"       { capabilities = ["update"] }
path "transit/datakey/wrapped/kek-tenant-*" { capabilities = ["update"] }
EOF

bao write auth/k8s-cell-hcm/role/mission-core \
  bound_service_account_names=mission-core bound_service_account_namespaces=app-mission \
  audience=openbao token_policies=mission-core token_ttl=1h token_max_ttl=4h
```

Secret động cho PostgreSQL. Theo TL-01, mỗi module có hai vai trò `svc_<module>` (chạy, chỉ DML) và `mig_<module>` (Flyway, có DDL). Người dùng do OpenBao tạo là thành viên của vai trò nhóm `svc_<module>`, có thuộc tính `NOBYPASSRLS`, sống 24 giờ, được gia hạn bởi ứng dụng và bị thu hồi khi pod chết; vì các bảng bật RLS ở chế độ `FORCE`, kể cả chủ sở hữu bảng cũng không đọc vượt tenant:

```bash
bao secrets enable database
bao write database/config/pg-core-hcm plugin_name=postgresql-database-plugin \
  connection_url="postgresql://{{username}}:{{password}}@pg-core-rw.data-pg.svc:5432/okr?sslmode=verify-full" \
  username="bao_admin" password_authentication="scram-sha-256" \
  allowed_roles="pg-core-mission,pg-core-readonly" root_rotation_statements="ALTER ROLE \"{{name}}\" WITH PASSWORD '{{password}}';"
bao write -force database/rotate-root/pg-core-hcm

bao write database/roles/pg-core-mission db_name=pg-core-hcm default_ttl=24h max_ttl=72h \
  creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN NOBYPASSRLS PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' IN ROLE svc_mission;" \
  revocation_statements="REASSIGN OWNED BY \"{{name}}\" TO svc_mission; DROP OWNED BY \"{{name}}\"; DROP ROLE IF EXISTS \"{{name}}\";"
```

Lệnh `rotate-root` ngay sau khi cấu hình làm mật khẩu khởi tạo của `bao_admin` mất hiệu lực, nên không người nào biết mật khẩu quản trị mà OpenBao dùng. Với PgBouncer ở chế độ giao dịch, secret động theo pod làm số người dùng CSDL tăng theo số pod; PgBouncer được cấu hình `auth_query` để xác thực người dùng động, và pool được gom theo cơ sở dữ liệu thay vì theo người dùng (`pool_mode = transaction`, đặt `user` cố định ở phía máy chủ trong `[databases]`), để không nhân số kết nối phía máy chủ theo số người dùng động. Vì chế độ giao dịch không giữ trạng thái phiên giữa hai giao dịch, ngữ cảnh tenant được đặt bằng `set_config('app.tenant_id', ?, true)` ở đầu mỗi giao dịch (tương đương `SET LOCAL`, ADR-22 của TL-01), không bao giờ dùng `SET` mức phiên; kiểm thử tích hợp chạy qua PgBouncer thật để phát hiện vi phạm. Chi tiết ngân sách kết nối ở mục 4.7 không đổi.

ESO đồng bộ bí mật tĩnh, ví dụ khoá API gửi SMS của notify-service:

```yaml
apiVersion: external-secrets.io/v1
kind: ClusterSecretStore
metadata:
  name: openbao-cell
spec:
  provider:
    vault:
      server: https://openbao.secure.svc:8200
      path: kv
      version: v2
      caProvider: { type: ConfigMap, name: vt-internal-ca, key: ca.crt, namespace: secure }
      auth:
        kubernetes:
          mountPath: k8s-cell-hcm
          role: external-secrets
          serviceAccountRef: { name: external-secrets, namespace: platform-eso }
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: notify-sms-gateway
  namespace: app-notify
spec:
  refreshInterval: 15m
  secretStoreRef: { kind: ClusterSecretStore, name: openbao-cell }
  target:
    name: notify-sms-gateway
    creationPolicy: Owner
    deletionPolicy: Retain
  data:
    - secretKey: SMS_API_KEY
      remoteRef: { key: cell-hcm/notify/sms, property: api_key }
```

**Mã hoá phong bì và DEK (SEC-11).** Mỗi tenant có một KEK trong Transit tên `kek-tenant-<tenantId>`, kiểu `aes256-gcm96`, được bảo vệ bởi khoá gốc trong HSM (Transit có tuỳ chọn khoá do HSM giữ qua cơ chế managed keys). DEK tạo theo tenant theo tháng bằng `transit/datakey/wrapped`. Theo TL-01 (ADR-30), DEK được tạo theo tổ hợp tenant, tháng và mục đích (ví dụ nhận xét đánh giá, khiếu nại tách DEK riêng), bản bọc lưu trong bảng `keyring.dek`; AAD gồm tenant, tên cột và id dòng; bản mã có byte phiên bản thuật toán để đổi thuật toán sau này. Bản rõ của DEK chỉ tồn tại trong bộ nhớ của pod, lưu đệm 15 phút. Chi phí đo trong PERF-14.

**Xoay vòng (SEC-12).** Lịch xoay vòng: KEK theo tenant hằng năm (`bao write -f transit/keys/kek-tenant-x/rotate`, phiên bản cũ vẫn giải mã được, tác vụ `rewrap` bọc lại DEK); DEK mới mỗi tháng (dữ liệu cũ giữ DEK cũ); mật khẩu CSDL động 24 giờ; chứng thư mTLS của ztunnel 24 giờ; chứng thư nội bộ do cert-manager cấp 90 ngày, gia hạn ở ngày thứ 60; khoá ký JWT của Keycloak 90 ngày với giai đoạn chồng lấn 7 ngày; khoá cosign hằng năm. Việc xoay vòng là quy trình OPS-20 và được giám sát bằng chỉ số tuổi khoá.

**PKI nội bộ (SEC-59).** OpenBao PKI có CA gốc ngoại tuyến (tạo một lần trên HSM, cất giữ), CA trung gian cho mỗi cell. cert-manager cấp chứng thư cho PostgreSQL, Kafka, Valkey, OpenSearch, APISIX nội bộ qua `ClusterIssuer` kiểu Vault:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: openbao-pki-cell
spec:
  vault:
    server: https://openbao.secure.svc:8200
    path: pki_int_cell_hcm/sign/internal-services
    caBundleSecretRef: { name: vt-internal-ca, key: ca.crt }
    auth:
      kubernetes:
        mountPath: /v1/auth/k8s-cell-hcm
        role: cert-manager
        serviceAccountRef: { name: cert-manager }
```

**Mật mã được phê duyệt (SEC-20).** Danh sách thuật toán cho phép: AES-256-GCM, ChaCha20-Poly1305 cho mã hoá đối xứng; ECDSA P-256 và P-384, RSA ≥ 2048 (khoá mới tạo từ năm 2027 dùng ≥ 3072), Ed25519 cho chữ ký (chữ ký số pháp lý theo chuẩn của tổ chức chứng thực); SHA-256, SHA-384, SHA-3 cho băm; Argon2id, PBKDF2-HMAC-SHA256 ≥ 600.000 vòng cho mật khẩu; TLS 1.3, TLS 1.2 chỉ với bộ mã ECDHE-AEAD. Cấm DES, 3DES, RC4, MD5, SHA-1 cho chữ ký, `PBEWithMD5AndDES` (sửa S09), và mọi `TrustManager` chấp nhận tất cả chứng thư. Semgrep có quy tắc chặn các mẫu này; Trivy cấu hình kiểm tra TLS của endpoint. Với triển khai cho lực lượng vũ trang, bộ thuật toán được thay theo hướng dẫn của Ban Cơ yếu Chính phủ (SEC-46, mục 9.9).

**Mã hoá đầu cuối C3 (SEC-25).** Thiết kế mã hoá đầu cuối thuộc TL-01 (ADR-27): khoá nội dung mã hoá bằng WebCrypto ở thiết bị, bọc cho từng người nhận bằng khoá ECDH P-256 theo thiết bị, không trích xuất được; danh bạ khoá công khai có ký và ghi sổ cái; khoá phục hồi của tổ chức được chia Shamir 3 trên 5 cho năm người giữ, cần 3 người để phục hồi. Ở tầng triển khai, yêu cầu là: khoá riêng của thiết bị sinh và lưu trong thiết bị (WebCrypto `extractable: false`, Keystore hoặc Secure Enclave trên di động); máy chủ chỉ lưu khoá công khai và bản mã; các mảnh khoá phục hồi được giao trực tiếp cho người giữ theo biên bản, không đi qua máy chủ; APISIX và WAF không được cấu hình kiểm tra nội dung của tuyến C3 (vì đó là bản mã), nên các tuyến này giới hạn chặt kích thước và tốc độ. Chi phí đo trong PERF-15.

**DBA không đọc được dữ liệu C2 (SEC-58).** Dữ liệu C2 được mã hoá ở mức trường bằng DEK mà DBA không có quyền mở (chính sách OpenBao chỉ cấp quyền `decrypt` cho tài khoản dịch vụ của ứng dụng). DBA thao tác CSDL qua PAM với người dùng có vai trò `dba_ops` (không có `SELECT` trên các bảng nghiệp vụ, chỉ có quyền trên danh mục hệ thống, thống kê, và chức năng quản trị). Khi cần đọc dữ liệu nghiệp vụ để xử lý sự cố, DBA dùng quy trình phá kính: yêu cầu trong PAM, hai người duyệt (một từ khách hàng), cấp vai trò đọc có thời hạn 2 giờ, phiên được ghi hình, và một bản ghi được ghi vào sổ cái.

## An ninh mạng

![Hình 9.1. Các vùng mạng và luồng được phép](tk-vung-mang.png){width=16cm}

Hình 9.1 trình bày sáu vùng mạng của một cell và các luồng được phép giữa chúng (SEC-33). Lưu lượng từ Internet hoặc mạng truyền số liệu chuyên dùng (TSLCD) đi qua lớp chống DDoS của Viettel, cân bằng tải L4, rồi vào vùng biên nơi APISIX kết thúc TLS và chạy WAF. Vùng ứng dụng chỉ nhận lưu lượng từ vùng biên và trong nội bộ vùng, với mTLS STRICT. Vùng dữ liệu chỉ nhận kết nối từ vùng ứng dụng tới đúng cổng của từng thành phần. Vùng bảo mật (sổ cái, ký số, OpenBao, ai-gateway) chạy trên node riêng có đường mạng riêng tới HSM, và ứng dụng chỉ tương tác với vùng này qua Kafka và API đã công bố. Lưu lượng ra ngoài chỉ đi qua cổng ra (egress gateway) với danh sách đích cho phép. Vùng quản trị chỉ truy cập được qua PAM, với mọi phiên được ghi hình.

Các lớp kiểm soát mạng và cách triển khai:

| Mã | Lớp | Triển khai | Kiểm chứng |
|---|---|---|---|
| SEC-35 | Chống DDoS | Lớp 3, 4: dịch vụ chống DDoS của Viettel ở biên mạng (lọc volumetric, SYN flood); lớp 7: APISIX `limit-req`, `limit-count` theo IP, người dùng, tenant (mục 6.8), Coraza chặn mẫu tấn công; CDN hấp thụ tài nguyên tĩnh | Diễn tập DDoS với nhà cung cấp hằng năm |
| SEC-34 | WAF | Coraza trong APISIX với OWASP CRS 4, mức paranoia 2 cho API, ngưỡng điểm bất thường 5; quy tắc loại trừ theo tuyến được quản lý trong Git; chế độ phát hiện 2 tuần trước chặn với mỗi tuyến mới | Tỷ lệ chặn nhầm < 0,01% (đo trên nhật ký 2 tuần); ZAP |
| SEC-32 | NetworkPolicy | Mặc định từ chối mọi chiều trong mọi namespace; cho phép tường minh (mục 3.6); Cilium thực thi, Hubble ghi luồng bị chặn | Hubble: 0 luồng ngoài danh sách |
| SEC-21 | mTLS | Istio ambient, PeerAuthentication STRICT toàn cụm cho vùng ứng dụng; vùng dữ liệu dùng TLS gốc với `verify-full` (mục 3.7) | `istioctl x ztunnel-config` và kiểm tra bắt gói |
| SEC-57 | TLS và ACL dữ liệu | PostgreSQL `hostssl` + SCRAM; Kafka mTLS listener + ACL theo topic do Strimzi `KafkaUser` quản lý; Valkey TLS + ACL theo người dùng; OpenSearch TLS + vai trò | kube-bench tuỳ chỉnh; kiểm tra kết nối không TLS bị từ chối |
| SEC-36 | Lưu lượng ra | Cilium `CiliumNetworkPolicy` với `toFQDNs` chỉ cho namespace `egress`; egress gateway có IP cố định để đối tác lọc; danh sách đích: VNeID, MySign, TSA, LGSP, NDXP, vOffice, máy chủ SMS, email | Hubble: luồng ra ngoài danh sách bị chặn và cảnh báo |
| SEC-24 | Header HTTP | APISIX `response-rewrite` toàn cục: `Strict-Transport-Security: max-age=63072000; includeSubDomains; preload`, `Content-Security-Policy` (mục 9.8), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`, `Cross-Origin-Opener-Policy: same-origin` | ZAP; securityheaders kiểm tra nội bộ |
| SEC-47 | Quét mã độc | ClamAV (clamd) trong pool app-worker, cập nhật chữ ký qua máy chủ gương nội bộ mỗi giờ; tệp ở trạng thái `CHO_QUET` cho tới khi sạch | PERF-18, tệp EICAR |
| SEC-48 | Giới hạn tải lên | Kích thước tối đa 50 MB (cấu hình theo tenant), loại tệp theo danh sách, kiểm tra nội dung thật bằng Apache Tika (không tin phần mở rộng), loại bỏ macro và JavaScript khỏi PDF khi tạo bản xem trước | Kiểm thử với bộ tệp độc hại mẫu |

Chính sách lưu lượng ra của cổng ra:

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: egress-allowlist
  namespace: egress
spec:
  endpointSelector:
    matchLabels: { app.kubernetes.io/name: egress-proxy }
  egress:
    - toEndpoints:
        - matchLabels: { k8s:io.kubernetes.pod.namespace: kube-system, k8s-app: kube-dns }
      toPorts:
        - ports: [{ port: "53", protocol: UDP }]
          rules: { dns: [{ matchPattern: "*" }] }
    - toFQDNs:
        - matchName: api.vneid.gov.vn
        - matchName: rs.mysign.vn
        - matchName: tsa.ca.gov.vn
        - matchPattern: "*.lgsp.vt-province.gov.vn"
        - matchName: ndxp.gov.vn
      toPorts:
        - ports: [{ port: "443", protocol: TCP }]
```

Tên miền trong ví dụ là minh hoạ; danh sách thật được lấy từ hồ sơ tích hợp của từng tenant (SVC-13) và được sinh tự động vào kho GitOps khi một tích hợp mới được duyệt.

## Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD

Bản đánh giá ghi nhận cả 4 pipeline của hệ hiện tại bỏ qua kiểm thử, không quét và không ký ảnh, ảnh chạy quyền root (C04), và chỉ số "pipeline có cổng chất lượng" là 0/4. Mục tiêu sau đợt 0 là 4/4, và nền tảng mới đạt SLSA Build mức 3 (SEC-37): bản dựng chạy trên hạ tầng dựng được quản lý, cô lập giữa các lần dựng, có bằng chứng nguồn gốc (provenance) không giả mạo được do chính hệ thống dựng ký.

![Hình 9.2. Chuỗi CI/CD và GitOps từ merge request tới các đợt phát hành theo cell](tk-cicd-gitops.png){width=16cm}

Hình 9.2 cho thấy đường đi của một thay đổi. Merge request cần hai người duyệt. Jenkins dựng, chạy kiểm thử đơn vị và kiểm thử tích hợp với Testcontainers, chạy bộ kiểm thử vàng của Policy Pack (phải đạt 100%), quét mã nguồn (Semgrep, gitleaks, Trivy chế độ tệp), dựng ảnh bằng BuildKit không root, sinh SBOM, quét ảnh, ký ảnh và chứng thực bằng cosign với khoá trong OpenBao Transit. Ảnh được đẩy lên Harbor theo digest, và Jenkins mở một commit cập nhật digest trong kho GitOps. Argo CD đồng bộ theo ApplicationSet, Kyverno kiểm tra chữ ký ở thời điểm tạo pod. Thay đổi đi qua staging (ZAP, k6) rồi vào sản xuất theo bốn đợt cell (OPS-02), Argo Rollouts phân tích SLO ở từng bước canary và tự quay lui khi vi phạm.

Jenkinsfile của một service, dùng thư viện dùng chung `vt-pipeline`:

```groovy
@Library('vt-pipeline@v3.4.0') _

pipeline {
  agent {
    kubernetes {
      inheritFrom 'vt-build-ephemeral'   // pod dựng dùng một lần, không giữ trạng thái giữa các lần dựng
      defaultContainer 'jdk21'
    }
  }
  options {
    timeout(time: 45, unit: 'MINUTES')
    disableConcurrentBuilds(abortPrevious: true)
    buildDiscarder(logRotator(numToKeepStr: '50'))
  }
  environment {
    SERVICE  = 'mission-core'
    REGISTRY = 'harbor.vt.local/okr'
    COSIGN_KEY = 'hashivault://cosign-release'
  }
  stages {
    stage('Bí mật và SAST') {
      parallel {
        stage('gitleaks') { steps { sh 'gitleaks git --redact --exit-code 1 --log-opts="origin/main..HEAD" .' } }
        stage('Semgrep')  { steps { sh 'semgrep scan --config p/java --config .semgrep/vt-rules.yml --error --sarif -o semgrep.sarif' } }
        stage('Trivy fs') { steps { sh 'trivy fs --scanners vuln,misconfig,license --severity HIGH,CRITICAL --exit-code 1 .' } }
      }
    }
    stage('Dựng và kiểm thử') {
      steps {
        sh './mvnw -B -ntp verify -Pci'   // unit test, Testcontainers, ArchUnit, JaCoCo
        sh './mvnw -B -ntp -pl policy-pack-tests test -Dgolden=true'
      }
      post {
        always {
          junit '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml'
          recordCoverage(tools: [[parser: 'JACOCO']], qualityGates: [[threshold: 80.0, metric: 'LINE', baseline: 'MODIFIED_FILES', criticality: 'FAILURE']])
        }
      }
    }
    stage('Ảnh, SBOM, quét') {
      steps {
        container('buildkit') {
          sh '''
            buildctl-daemonless.sh build --frontend dockerfile.v0 --local context=. --local dockerfile=. \
              --opt build-arg:BASE=harbor.vt.local/base/java21-distroless@sha256:$BASE_DIGEST \
              --output type=image,name=$REGISTRY/$SERVICE:$GIT_COMMIT,push=true \
              --metadata-file build-meta.json
          '''
        }
        script { env.DIGEST = readJSON(file: 'build-meta.json')['containerimage.digest'] }
        sh 'syft $REGISTRY/$SERVICE@$DIGEST -o cyclonedx-json=sbom.cdx.json'
        sh 'trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 $REGISTRY/$SERVICE@$DIGEST'
        sh 'curl -sf -X POST -H "X-Api-Key: $DTRACK_KEY" -F "autoCreate=true" -F "projectName=$SERVICE" -F "projectVersion=$GIT_COMMIT" -F "bom=@sbom.cdx.json" https://dtrack.vt.local/api/v1/bom'
      }
    }
    stage('Ký và chứng thực') {
      when { branch 'main' }
      steps {
        sh 'cosign sign --yes --key $COSIGN_KEY --tlog-upload=false $REGISTRY/$SERVICE@$DIGEST'
        sh 'cosign attest --yes --key $COSIGN_KEY --tlog-upload=false --type cyclonedx --predicate sbom.cdx.json $REGISTRY/$SERVICE@$DIGEST'
        sh 'vt-provenance --builder-id https://jenkins.vt.local/okr-builder --repo $GIT_URL --commit $GIT_COMMIT --digest $DIGEST > provenance.json'
        sh 'cosign attest --yes --key $COSIGN_KEY --tlog-upload=false --type slsaprovenance1 --predicate provenance.json $REGISTRY/$SERVICE@$DIGEST'
      }
    }
    stage('Cập nhật GitOps') {
      when { branch 'main' }
      steps {
        vtGitopsBump(service: env.SERVICE, digest: env.DIGEST, env: 'staging')   // mở commit có chữ ký vào kho GitOps
      }
    }
  }
  post {
    failure { vtNotify(channel: 'okr-ci', service: env.SERVICE) }
  }
}
```

Một số điểm thiết kế cần giải thích. Pod dựng là pod dùng một lần (`vt-build-ephemeral`), không có bộ nhớ đệm Maven chia sẻ có quyền ghi giữa các dự án, để một bản dựng bị nhiễm độc không ảnh hưởng bản dựng khác; bộ nhớ đệm phụ thuộc là một proxy Nexus chỉ đọc. Bước tạo provenance chạy trong một container riêng mà mã của dự án không can thiệp được, và khoá ký nằm trong OpenBao chỉ cấp quyền `sign` cho tài khoản dịch vụ của bước đó, nên mã dự án không tự ký được provenance giả; đây là yêu cầu cốt lõi của SLSA mức 3. Cờ `--tlog-upload=false` được dùng vì hệ thống đặt trong mạng nội bộ không gửi dữ liệu ra nhật ký minh bạch công khai Rekor; với triển khai SaaS có thể dựng Rekor nội bộ nếu khách hàng yêu cầu. Ngưỡng độ bao phủ 80% áp dụng cho tệp thay đổi, không cho toàn dự án, để không chặn mã cũ trong giai đoạn chuyển đổi.

Dependency-Track (SEC-23) giữ SBOM của mọi phiên bản đang chạy, và khi một CVE mới được công bố, nó cho biết ngay service nào ở cell nào bị ảnh hưởng. Chính sách xử lý: CVE Critical có bản vá phải được vá và phát hành trong 7 ngày, High trong 30 ngày (OPS-26). Bản đánh giá liệt kê các thành phần hết hỗ trợ của hệ hiện tại (pdf.js 2.0.943, Axis 1.4, jQuery 1.11.3, S10); chúng được đưa vào danh sách chặn của Trivy và Dependency-Track để không xuất hiện lại trong nền tảng mới.

Argo CD dùng ApplicationSet sinh ứng dụng theo cell từ tệp mô tả cell; cấu hình phát hành theo đợt được trình bày ở chương 11 (OPS-01, OPS-02). Ở góc độ an ninh, Argo CD có ba ràng buộc: chỉ đồng bộ từ kho GitOps có commit được ký (kiểm tra GPG trong `AppProject.spec.signatureKeys`); mỗi `AppProject` giới hạn namespace đích và loại tài nguyên được tạo; giao diện Argo CD đăng nhập qua Keycloak vận hành có MFA và không ai có quyền `sync` thủ công lên sản xuất ngoài vai trò phát hành.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: okr-apps
  namespace: argocd
spec:
  sourceRepos: ["https://git.vt.local/okr/gitops.git"]
  signatureKeys:
    - keyID: 4AEE18F83AFDEB23
  destinations:
    - server: "*"
      namespace: "app-*"
  clusterResourceWhitelist: []
  namespaceResourceBlacklist:
    - { group: "", kind: ResourceQuota }
    - { group: "rbac.authorization.k8s.io", kind: "*" }
  roles:
    - name: release-manager
      policies:
        - p, proj:okr-apps:release-manager, applications, sync, okr-apps/*, allow
      groups: ["ops-release-managers"]
```

## Tuân thủ TCVN 11930:2017 cấp độ 4

Hệ thống đánh giá cán bộ thu thập và xử lý thông tin cá nhân của cán bộ, công chức, viên chức trên quy mô cả nước, kết quả đánh giá ảnh hưởng trực tiếp tới quy hoạch, bổ nhiệm; theo Nghị định 85/2016/NĐ-CP và Thông tư 12/2022/TT-BTTTT, baseline xác định hệ thống ở cấp độ 4 (NFR-08). Bảng dưới đây ánh xạ các nhóm yêu cầu của TCVN 11930 với tiêu chí của bản đánh giá và mã SEC của TL-04.

| Nhóm yêu cầu TCVN 11930 | Tiêu chí bản đánh giá | Mã SEC, DR, OPS đáp ứng |
|---|---|---|
| Xác thực | D2, D3, D4, D5 | SEC-02, SEC-03, SEC-04, SEC-08, SEC-13 đến SEC-17, SEC-54 |
| Kiểm soát truy cập | D6 | SEC-18, SEC-29, SEC-42, SEC-58 |
| Nhật ký hệ thống | D9, G5 | SEC-07, SEC-22, SEC-30, SEC-43, SEC-50, SEC-53; luồng kiểm toán riêng (mục 10.3) |
| Bảo mật đường truyền | D8 | SEC-20, SEC-21, SEC-57, SEC-59 |
| Chống chối bỏ | I4, I8 | Sổ cái, ký số, dấu thời gian (ADR-09); neo ngoài (mục 8.8); PERF-16, PERF-17 |
| Ứng dụng và mã nguồn | D1, D7, D10 | SEC-01, SEC-05, SEC-06, SEC-09, SEC-19, SEC-23, SEC-37 đến SEC-41 |
| Toàn vẹn dữ liệu | I1 đến I5 | Sổ cái chuỗi băm, kiểm tra hằng ngày (OPS-22), DR-14; Object Lock |
| Bảo mật dữ liệu | D8, D12, E5 | SEC-11, SEC-25, SEC-31, SEC-52, SEC-58 |
| Sao lưu dự phòng | C5 | SEC-44, chương 8, OPS-18 |

Ngoài các nhóm trên, bản đánh giá nêu tám yêu cầu bổ sung đặc trưng của cấp độ 4. Bảng sau trình bày cách đáp ứng từng yêu cầu:

| Yêu cầu cấp độ 4 | Đáp ứng | Bằng chứng |
|---|---|---|
| MFA bắt buộc | SEC-13: mọi người dùng, không có nhánh bỏ qua; người vận hành dùng WebAuthn | Cấu hình Keycloak trong Git; báo cáo tỷ lệ người dùng có MFA = 100% |
| Khoá theo khách hàng trong HSM | SEC-11: KEK theo tenant, khoá gốc trong HSM; khách hàng giữ thẻ M trong N | Danh sách khoá Transit; biên bản giao thẻ |
| mTLS | SEC-21, SEC-57 | Kiểm tra bắt gói; cấu hình PeerAuthentication |
| PAM có ghi phiên | SEC-42: mọi truy cập đặc quyền tới Kubernetes, CSDL, máy chủ, HSM qua PAM; ghi hình; lưu 1 năm | Báo cáo phiên PAM hằng tháng |
| SOC 24/7 | SEC-43: SOC của Viettel Cyber Security giám sát qua SIEM, nhận luồng `sec.*`; thời gian phản hồi sự cố Critical ≤ 15 phút | Hợp đồng dịch vụ; báo cáo SOC hằng tháng |
| Sao lưu 3-2-1 bất biến | SEC-44: 3-2-1-1-0, Object Lock compliance | Chương 8; biên bản diễn tập |
| SBOM | SEC-23: CycloneDX cho mọi ảnh, lưu trong Dependency-Track | Danh sách SBOM theo phiên bản đang chạy |
| Build có chữ ký | SEC-37, SEC-38: cosign, provenance SLSA mức 3, Kyverno từ chối ảnh không ký | Nhật ký từ chối của Kyverno; kiểm tra ngẫu nhiên chữ ký |

**SOC và SIEM (SEC-43).** Mọi luồng nhật ký an ninh (nhật ký kiểm toán API Kubernetes, sự kiện Keycloak, quyết định từ chối của OPA, cảnh báo Falco, nhật ký WAF, nhật ký OpenBao, phiên PAM, sự kiện `sys.audit.v1` của ứng dụng) được chuẩn hoá theo lược đồ chung (ECS) và gửi tới SIEM của SOC qua Kafka topic `sys.siem.v1` với kết nối mTLS một chiều ra ngoài. Quy tắc phát hiện tối thiểu được thống nhất với SOC trước khi vận hành, gồm: nhiều lần đăng nhập thất bại; truy cập phá kính; thay đổi RBAC; ảnh không ký bị từ chối; shell trong container; truy vấn dữ liệu bất thường theo khối lượng (một tài khoản xuất quá 1.000 hồ sơ trong 1 giờ); lời gọi `decrypt` tới Transit tăng đột biến; chuỗi băm đứt.

**Dữ liệu cá nhân (SEC-52).** Theo Nghị định 13/2023/NĐ-CP, hệ thống xử lý dữ liệu cá nhân nhạy cảm (kết quả đánh giá, kỷ luật) phải có hồ sơ đánh giá tác động. TL-04 đóng góp vào hồ sơ này phần mô tả biện pháp kỹ thuật ở chương 8 và chương 9. Môi trường không phải sản xuất không dùng dữ liệu thật; khi cần dữ liệu gần thật để tái hiện lỗi, dữ liệu được che bằng tác vụ `anonymize` (thay thế tên, số định danh, ngày sinh, nhận xét bằng dữ liệu tổng hợp giữ phân phối) trên cụm cách ly trước khi chuyển sang staging.

## Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP

NFR-08 yêu cầu OWASP ASVS 4.0.3 mức 2 cho toàn hệ thống và mức 3 cho ba chương V2 (xác thực), V3 (quản lý phiên) và V6 (mật mã lưu trữ). Việc kiểm chứng gồm ba lớp. Lớp thứ nhất là kiểm tra tự động trong pipeline: Semgrep (SAST, SEC-39), gitleaks, Trivy, kiểm thử đơn vị cho các quy tắc phân quyền. Lớp thứ hai là DAST bằng OWASP ZAP (SEC-40) chạy trên staging sau mỗi lần phát hành: quét thụ động toàn bộ lưu lượng của bộ kiểm thử đầu cuối, và quét chủ động hằng đêm theo đặc tả OpenAPI của từng service với người dùng thử có token. Lớp thứ ba là kiểm thử xâm nhập độc lập (SEC-41) trước khi vận hành chính thức, sau mỗi thay đổi lớn về xác thực hoặc phân quyền, và ít nhất hằng năm; kiểm thử viên dùng danh mục kiểm tra ASVS và báo cáo theo từng mục.

Cấu hình ZAP chạy bằng Automation Framework trong Jenkins:

```yaml
env:
  contexts:
    - name: okr-api
      urls: ["https://staging.cell-stg.danhgia.vn/api/"]
      includePaths: ["https://staging.cell-stg.danhgia.vn/api/.*"]
      excludePaths: [".*/actuator/.*"]
      authentication:
        method: script
        parameters: { script: auth/keycloak-client-credentials.js, scriptEngine: "ECMAScript : Graal.js" }
      sessionManagement: { method: headers, parameters: { Authorization: "Bearer {%json:access_token%}" } }
  parameters: { failOnError: true, progressToStdout: true }
jobs:
  - type: openapi
    parameters: { apiUrl: "https://staging.cell-stg.danhgia.vn/api/v1/openapi.json", context: okr-api }
  - type: passiveScan-config
    parameters: { maxAlertsPerRule: 10 }
  - type: activeScan
    parameters: { context: okr-api, policy: API-Minimal, maxScanDurationInMins: 60, threadPerHost: 4 }
  - type: report
    parameters: { template: sarif-json, reportFile: zap.sarif }
  - type: exitStatus
    parameters: { errorLevel: High, warnLevel: Medium }
```

Content Security Policy (SEC-19) cho ứng dụng web được đặt ở APISIX cho mọi tuyến HTML: `default-src 'self'; script-src 'self' 'strict-dynamic' 'nonce-{nonce}'; style-src 'self' 'nonce-{nonce}'; img-src 'self' data: blob: https://cdn.danhgia.vn; connect-src 'self' https://*.danhgia.vn; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; upgrade-insecure-requests; report-to csp`. Nonce do shell sinh ở máy chủ cho mỗi lần tải trang. Bộ dựng biểu mẫu SDUI không bao giờ chèn HTML từ dữ liệu (chỉ dựng component đã đăng ký), nên các mẫu XSS của hệ hiện tại (407 `evalJavaScript` nối chuỗi, 360 `Html.setContent`, S13) không có chỗ tồn tại trong nền tảng mới; Semgrep chặn `dangerouslySetInnerHTML` và `innerHTML` trong mã frontend.

Tiêu chí đạt của lớp kiểm thử: ZAP 0 cảnh báo High, các cảnh báo Medium có giải trình; kiểm thử xâm nhập 0 phát hiện Critical, High chưa khắc phục tại thời điểm vận hành; bảng tự đánh giá ASVS mức 2 đạt 100% mục áp dụng, mức 3 cho các chương đã nêu.

## Cài đặt cách ly cho lực lượng vũ trang và Công an

Mô hình 3 ở mục 2.4 yêu cầu cài đặt trong mạng không có kết nối Internet, nhiều khi không có cả kết nối tới hạ tầng của Viettel. TL-04 thiết kế gói cài đặt ngoại tuyến (SEC-45) để việc cài đặt và nâng cấp không cần kết nối, mà vẫn giữ được bảo đảm chuỗi cung ứng: người nhận kiểm tra được mọi thành phần đều do Viettel dựng và chưa bị sửa trên đường vận chuyển.

Gói phát hành LTS (OPS-12) gồm: mọi ảnh container theo digest dưới dạng thư mục OCI; Helm chart đã đóng gói; chữ ký cosign, SBOM và provenance của từng ảnh; kho GitOps tại thẻ phát hành dưới dạng Git bundle; ảnh hệ điều hành node; gói cơ sở dữ liệu chữ ký ClamAV và cơ sở dữ liệu lỗ hổng Trivy tại thời điểm phát hành; công cụ dòng lệnh; tài liệu và bản kê (manifest) chứa băm SHA-256 của mọi tệp. Bản kê được ký bằng khoá phát hành ngoại tuyến của Viettel; khoá công khai được giao cho khách hàng một lần qua kênh riêng.

```bash
# Phía Viettel: đóng gói một bản LTS
REL=okr-lts-2027.1.0
mkdir -p $REL/images
for img in $(yq '.images[]' release/$REL/images.yaml); do
  oras copy --to-oci-layout "$img" "$REL/images:$(echo $img | tr '/:@' '___')"
  cosign save "$img" --dir "$REL/signatures/$(echo $img | tr '/:@' '___')"
done
helm package charts/* -d $REL/charts
git -C gitops bundle create ../$REL/gitops.bundle --all
trivy image --download-db-only --cache-dir $REL/trivy-db
cp -r clamav-mirror/*.cvd $REL/clamav/
( cd $REL && find . -type f ! -name MANIFEST* -exec sha256sum {} + | sort -k2 > MANIFEST.sha256 )
cosign sign-blob --key hsm://release-offline --output-signature $REL/MANIFEST.sha256.sig $REL/MANIFEST.sha256
tar --zstd -cf $REL.tar.zst $REL

# Phía khách hàng, trong mạng cách ly: kiểm tra rồi nạp vào Harbor nội bộ
cosign verify-blob --key vt-release.pub --signature MANIFEST.sha256.sig MANIFEST.sha256
sha256sum -c MANIFEST.sha256 --quiet
for d in images/*; do oras copy --from-oci-layout "$d" "harbor.qp.local/okr/$(basename $d | ...)"; done
```

Dòng cuối dùng dấu `...` để lược phần đổi tên ảnh theo quy ước kho của khách hàng; script đầy đủ nằm trong gói (`tools/load-images.sh`). Sau khi nạp, Kyverno ở môi trường khách hàng kiểm tra chữ ký bằng khoá công khai phát hành (thay cho khoá trong OpenBao Transit của Viettel), nên chỉ ảnh đã ký bởi Viettel chạy được.

Các khác biệt của mô hình cách ly so với SaaS:

| Khía cạnh | SaaS | Cách ly |
|---|---|---|
| Cập nhật | Liên tục theo đợt cell | Bản LTS mỗi 6 tháng, bản vá an ninh khi cần, chuyển bằng phương tiện vật lý được kiểm soát |
| Cơ sở dữ liệu lỗ hổng, chữ ký mã độc | Cập nhật tự động hằng giờ | Cập nhật theo gói hằng tuần qua phương tiện vật lý |
| Mật mã | Thuật toán tiêu chuẩn quốc tế, HSM thương mại | Thuật toán và thiết bị mật mã do Ban Cơ yếu Chính phủ cấp (SEC-46); thư viện `vt-crypto` có lớp trừu tượng nhà cung cấp để thay thế |
| Chứng thực, ký số | MySign, TSA công cộng | Hạ tầng PKI chuyên dùng của khách hàng |
| AI | vLLM với mô hình của Viettel AI | Có thể tắt hoàn toàn; nếu bật, mô hình được chuyển cùng gói và chạy trên GPU của khách hàng |
| Giám sát | SOC Viettel | SOC của khách hàng; Viettel hỗ trợ từ xa qua kênh được phép hoặc tại chỗ |
| Giấy phép công cụ | Grafana, Loki, Tempo, k6 (AGPL) chạy nội bộ ở Viettel | Không đóng gói thành phần AGPL nếu pháp chế chưa duyệt; dùng phương án Apache-2.0 nêu ở mục 10.1 |
| Quan sát | Thanos, Loki toàn quốc | Ngăn xếp quan sát đầy đủ trong mạng khách hàng, dựng từ thành phần Apache-2.0 nếu pháp chế chưa duyệt phân phối thành phần AGPL (mục 10.1) |

Kiểm tra sau cài đặt cách ly gồm: kube-bench, kiểm tra không có luồng ra ngoài (Hubble), chạy bộ PERF-01 rút gọn và bộ kiểm thử khói, kiểm tra chữ ký của mọi ảnh đang chạy, và diễn tập khôi phục một cụm PostgreSQL. Biên bản kiểm tra là một phần hồ sơ đề nghị phê duyệt cấp độ của khách hàng.

# Quan sát hệ thống

## Mục tiêu và hiện trạng

Bản đánh giá xếp trục B (khả năng quan sát) và trục G (nhật ký) vào nhóm yếu nhất của hệ hiện tại: không có tracing phân tán dù agent OpenTelemetry đã có trong ảnh nhưng không được nạp (B1, B01), không lan truyền ngữ cảnh trace (B2), endpoint Prometheus không hoạt động và có 0 SLI (B3, B4, B02), không giám sát phía người dùng (B8, B03), log văn bản tự do không có `trace_id` (G2, G3, G02), LogCenter bỏ log khi tải cao và chỉ giữ khoảng 5% (G4, G01), không có chính sách lưu giữ và không tách nhật ký kiểm toán (G5, G6, G03), ghi JWT và body request vào log (G7, S08). Chỉ số theo dõi "request có trace end-to-end" hiện là 0%, mục tiêu sau đợt 0 là ≥ 90% và đích là ≥ 99% (NFR-10).

Chương này thiết kế ngăn xếp quan sát theo ADR-12. Grafana, Loki, Tempo (và công cụ thử tải k6 ở chương 7) dùng giấy phép AGPL-3.0; theo TL-01, chúng chỉ chạy như công cụ vận hành nội bộ của Viettel và không được phân phối kèm phần mềm giao cho khách hàng. Với bản cài đám mây riêng và bản cách ly, nơi ngăn xếp quan sát phải chạy trong hạ tầng của khách hàng, việc đưa các thành phần này vào gói cần pháp chế rà soát trước; phương án thay thế không AGPL là Prometheus và Thanos cho metric, Jaeger cho trace, OpenSearch cho log, Perses cho dashboard (đều Apache-2.0). Thành phần của ngăn xếp: OpenTelemetry cho thu thập, Tempo cho trace, Prometheus và Thanos cho metric, Loki cho log, Grafana cho hiển thị, Grafana Faro cho giám sát phía người dùng, Alertmanager cho cảnh báo. Nguyên tắc chung là ba tín hiệu (trace, metric, log) phải liên kết được với nhau qua `trace_id` và các nhãn chuẩn (`cell`, `tenant`, `service`, `namespace`), để từ một cảnh báo SLO người trực đi tới đúng trace chậm và đúng dòng log trong vài cú nhấp.

![Hình 10.1. Ngăn xếp quan sát của một cell](tk-quan-sat.png){width=16cm}

Hình 10.1 trình bày luồng dữ liệu quan sát. Ứng dụng phát trace và metric qua OTLP tới OTel Collector chạy dạng DaemonSet trên mỗi node, collector này gắn thuộc tính Kubernetes và chuyển tới tầng gateway, nơi thực hiện lấy mẫu theo đuôi (tail sampling) và che dữ liệu cá nhân. Log ứng dụng ghi ra stdout dạng JSON và được Fluent Bit thu thập với bộ đệm đĩa, đẩy tới Loki; riêng nhật ký kiểm toán và an ninh được tách sang Kafka `sys.audit.v1`, từ đó tới kho bất biến 10 năm và SIEM. Prometheus của mỗi cell đẩy khối dữ liệu lên kho S3 qua Thanos sidecar; Thanos Query ở cụm quản trị cho phép truy vấn liên cell. Grafana đọc cả ba nguồn, Alertmanager nhận cảnh báo từ quy tắc Prometheus và Thanos Ruler.

## Trace: OpenTelemetry và lấy mẫu theo đuôi

Các service Java dùng OpenTelemetry Java agent (nạp bằng `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`, agent được đưa vào ảnh nền chứ không tải lúc chạy), các service Go và Python dùng SDK tương ứng. Lan truyền ngữ cảnh theo chuẩn W3C Trace Context (`traceparent`, `tracestate`) và W3C Baggage cho `tenant` (B2). APISIX sinh `traceparent` nếu request đến chưa có và gắn `trace_id` vào phản hồi qua header `X-Trace-Id`, để người dùng báo lỗi kèm mã này. Kafka mang `traceparent` trong header của bản ghi; Debezium được cấu hình chuyển cột `trace_context` của bảng outbox thành header, nên trace nối liền từ API ghi qua outbox, Kafka tới consumer ở service khác.

**Cách hiểu chỉ tiêu NFR-10.** NFR-10 yêu cầu "≥ 99% request có trace end-to-end". Nếu hiểu là lưu trữ 99% trace thì với 15.000 request mỗi giây toàn quốc, mỗi trace trung bình 12 span, mỗi span khoảng 500 byte, khối lượng là 15.000 × 12 × 500 ≈ 90 MB mỗi giây, khoảng 7,8 TB mỗi ngày (ước tính), tốn kém mà không có giá trị chẩn đoán tương xứng. TL-04 hiểu NFR-10 theo hai vế. Vế thứ nhất là **độ đầy đủ của ngữ cảnh**: ≥ 99% request đi qua APISIX có `traceparent` hợp lệ được lan truyền tới mọi chặng phía sau, đo bằng tỷ lệ span con có span cha tồn tại trong cùng trace trên tập trace được lấy mẫu (chỉ số `trace_completeness_ratio`). Vế thứ hai là **lưu trữ có chọn lọc**: 100% trace có lỗi, 100% trace chậm hơn ngưỡng SLO của loại request, 100% trace của request có header `X-Debug-Trace` do bộ thử tải hoặc hỗ trợ kỹ thuật gắn, và 10% trace bình thường. Với tỷ lệ lỗi và chậm khoảng 5%, lượng trace lưu trữ khoảng 15% tổng, tức khoảng 1,2 TB mỗi ngày toàn quốc (ước tính), giữ 14 ngày trong Tempo. Cách hiểu này được ghi vào ghi chú của tài liệu để chủ đầu tư xác nhận.

Cấu hình tầng gateway của OTel Collector:

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
processors:
  memory_limiter: { check_interval: 1s, limit_percentage: 80, spike_limit_percentage: 20 }
  attributes/redact:
    actions:
      - { key: http.request.header.authorization, action: delete }
      - { key: enduser.id, action: hash }
      - { pattern: "^(db\\.statement)$", action: delete }
  transform/redact_url:
    trace_statements:
      - context: span
        statements:
          - replace_pattern(attributes["url.query"], "(token|code|password)=[^&]*", "$$1=***")
  tail_sampling:
    decision_wait: 15s
    num_traces: 400000
    expected_new_traces_per_sec: 4000
    policies:
      - name: errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: slow-read
        type: and
        and:
          and_sub_policy:
            - { name: is-get, type: string_attribute, string_attribute: { key: http.request.method, values: [GET] } }
            - { name: over-300ms, type: latency, latency: { threshold_ms: 300 } }
      - name: slow-any
        type: latency
        latency: { threshold_ms: 800 }
      - name: forced
        type: string_attribute
        string_attribute: { key: vt.debug, values: ["1"] }
      - name: baseline
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }
  batch: { send_batch_size: 8192, timeout: 2s }
exporters:
  otlp/tempo:
    endpoint: tempo-distributor.observability.svc:4317
    tls: { ca_file: /etc/otel/ca.crt }
    sending_queue: { enabled: true, num_consumers: 8, queue_size: 20000, storage: file_storage }
    retry_on_failure: { enabled: true, max_elapsed_time: 10m }
extensions:
  file_storage: { directory: /var/lib/otelcol/queue, timeout: 10s }
service:
  extensions: [file_storage]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, attributes/redact, transform/redact_url, tail_sampling, batch]
      exporters: [otlp/tempo]
```

Lấy mẫu theo đuôi đòi hỏi mọi span của một trace tới cùng một bản sao collector gateway. Tầng DaemonSet dùng exporter `loadbalancing` với khoá định tuyến `traceID` tới các pod gateway (StatefulSet 3 đến 6 pod mỗi cell), đây là cấu hình chuẩn của OTel Collector cho tail sampling. Mỗi pod gateway giữ tối đa 400.000 trace trong 15 giây; với CELL-HCM ở 6.000 request mỗi giây khi đột biến, mỗi pod trong 4 pod nhận khoảng 1.500 trace mỗi giây, cần 22.500 trace trong bộ nhớ, dư nhiều so với giới hạn.

## Log: Fluent Bit có bộ đệm đĩa, không mất log khi tải cao

Ứng dụng ghi log dạng JSON theo lược đồ ECS ra stdout, mỗi dòng có `@timestamp`, `log.level`, `service.name`, `trace.id`, `span.id`, `tenant.id`, `cell`, `event.dataset` (G2, G3). Thư viện Logback dùng `logstash-logback-encoder` kèm bộ che dữ liệu nhạy cảm (SEC-53): mẫu JWT (`eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+`), số CCCD 12 chữ số, số điện thoại, email được thay bằng `***` trước khi ghi; ghi body request bị cấm bằng quy tắc Semgrep. Đây là lớp che thứ nhất; Fluent Bit là lớp thứ hai.

Vấn đề G4 của bản đánh giá ("DROP_OLDEST, giữ khoảng 5% khi bão hoà", G01) là hệ quả của bộ đệm chỉ trong bộ nhớ có chính sách bỏ bản ghi cũ. TL-04 thiết kế Fluent Bit với bộ đệm hệ thống tệp (`storage.type filesystem`) để khi Loki chậm hoặc mất kết nối, log được giữ trên đĩa node và gửi lại khi Loki phục hồi, thay vì bị bỏ. Dung lượng bộ đệm tính như sau: một node app-general ở tải đỉnh sinh khoảng 2 MB log mỗi giây (ước tính: 16 pod, mỗi pod khoảng 250 request mỗi giây, 1 dòng log khoảng 500 byte mỗi request ở mức INFO, chưa nén), nên 10 GB bộ đệm đĩa giữ được khoảng 10.240 / 2 ≈ 5.100 giây, tức khoảng 85 phút Loki gián đoạn hoàn toàn. Mỗi node có volume riêng 20 GB cho thư mục bộ đệm, không dùng chung với đĩa của container runtime.

```ini
[SERVICE]
    Flush                     1
    Log_Level                 warn
    HTTP_Server               On
    HTTP_Port                 2020
    Health_Check              On
    storage.path              /var/fluent-bit/buffer
    storage.sync              normal
    storage.checksum          Off
    storage.max_chunks_up     256
    storage.backlog.mem_limit 256M
    storage.metrics           On

[INPUT]
    Name                tail
    Tag                 kube.*
    Path                /var/log/containers/*.log
    Exclude_Path        /var/log/containers/*_kube-system_*.log
    multiline.parser    cri
    DB                  /var/fluent-bit/tail.db
    DB.locking          true
    Mem_Buf_Limit       64M
    storage.type        filesystem
    storage.pause_on_chunks_overlimit off
    Skip_Long_Lines     Off
    Buffer_Max_Size     1M
    Refresh_Interval    5

[INPUT]
    Name                tail
    Tag                 k8saudit
    Path                /var/log/kubernetes/audit/audit.log
    Parser              json
    DB                  /var/fluent-bit/audit.db
    storage.type        filesystem

[FILTER]
    Name                kubernetes
    Match               kube.*
    Merge_Log           On
    Keep_Log            Off
    Labels              On
    Annotations         Off

[FILTER]
    Name                lua
    Match               kube.*
    script              /fluent-bit/scripts/redact.lua
    call                redact_pii

[FILTER]
    Name                rewrite_tag
    Match               kube.*
    Rule                $event['dataset'] ^(audit|security)$ audit.$TAG false
    Emitter_Storage.type filesystem

[OUTPUT]
    Name                loki
    Match               kube.*
    Host                loki-gateway.observability.svc
    Port                443
    tls                 On
    tls.verify          On
    labels              cell=${CELL}, namespace=$kubernetes['namespace_name'], service=$kubernetes['labels']['app.kubernetes.io/name'], level=$level
    structured_metadata trace_id=$trace['id'], tenant_id=$tenant['id']
    line_format         json
    Retry_Limit         no_limits
    storage.total_limit_size 10G

[OUTPUT]
    Name                kafka
    Match               audit.*
    Brokers             kafka-kafka-bootstrap.data-kafka.svc:9093
    Topics              sys.audit.v1
    rdkafka.security.protocol   ssl
    rdkafka.ssl.ca.location     /etc/fluent-bit/kafka/ca.crt
    rdkafka.ssl.certificate.location /etc/fluent-bit/kafka/user.crt
    rdkafka.ssl.key.location    /etc/fluent-bit/kafka/user.key
    rdkafka.acks                all
    rdkafka.enable.idempotence  true
    Retry_Limit         no_limits
    storage.total_limit_size 5G

[OUTPUT]
    Name                kafka
    Match               k8saudit
    Brokers             kafka-kafka-bootstrap.data-kafka.svc:9093
    Topics              sys.audit.v1
    rdkafka.security.protocol   ssl
    rdkafka.ssl.ca.location     /etc/fluent-bit/kafka/ca.crt
    rdkafka.ssl.certificate.location /etc/fluent-bit/kafka/user.crt
    rdkafka.ssl.key.location    /etc/fluent-bit/kafka/user.key
    rdkafka.acks                all
    Retry_Limit         no_limits
    storage.total_limit_size 5G
```

Các tham số quan trọng: `Retry_Limit no_limits` không bao giờ bỏ chunk vì hết số lần thử; `storage.total_limit_size` giới hạn dung lượng đĩa mỗi đầu ra; `storage.pause_on_chunks_overlimit off` để đầu vào tiếp tục ghi ra đĩa thay vì tạm dừng đọc tệp khi bộ nhớ đầy. Khi đĩa bộ đệm đầy (Loki gián đoạn quá 85 phút ở tải đỉnh), Fluent Bit buộc phải bỏ chunk cũ nhất; trường hợp này được giám sát bằng `fluentbit_output_dropped_records_total` và cảnh báo ngay khi giá trị khác 0. Kubelet vẫn giữ tệp log container theo `containerLogMaxSize: 100Mi` và `containerLogMaxFiles: 5`, tạo thêm một lớp đệm khi Fluent Bit tự gặp sự cố. Phép thử nghiệm thu G4: chạy PERF-01 và chặn mạng tới Loki 30 phút, kiểm tra số dòng log trong Loki sau khi phục hồi bằng số dòng ứng dụng đã ghi (đếm qua bộ đếm `logback_events_total`), sai lệch phải bằng 0.

**Lưu giữ (G5, NFR-09).** Loki giữ log ứng dụng 90 ngày ở tầng nóng (chỉ mục TSDB và khối trong Ceph S3 của cụm quản trị), sau đó tác vụ hằng ngày chép khối quá 90 ngày sang bucket lạnh trên kho sao lưu với lớp lưu trữ dung lượng lớn, giữ thêm 1 năm và truy vấn được khi cần bằng cách gắn lại vào một Loki tạm. Khối lượng log ước tính toàn quốc: 15.000 request mỗi giây × 1,5 dòng × 500 byte ≈ 11 MB mỗi giây ở đỉnh, trung bình ngày khoảng 30% đỉnh, nén khoảng 10 lần, được khoảng 11 × 0,3 × 86.400 / 10 ≈ 29 GB mỗi ngày, khoảng 2,6 TB cho 90 ngày nóng và 10,5 TB cho 1 năm lạnh (ước tính).

**Nhật ký kiểm toán tách riêng (G6).** Nhật ký kiểm toán nghiệp vụ (ai làm gì với hồ sơ nào, thuộc schema `audit` của từng service theo baseline) và nhật ký an ninh không đi chung luồng với log ứng dụng. Chúng đi qua Kafka `sys.audit.v1` (sao chép 3, `min.insync.replicas` 2, ACL chỉ cho phép Fluent Bit và tài khoản `audit-writer` của service ghi; tên topic theo quy ước `sys.` của TL-01), tới hai đích: consumer `audit-archiver` ghi Parquet có chữ ký vào bucket Object Lock 10 năm (mục 8.3), và luồng SIEM (SEC-43). Log ứng dụng thường có thể bị xoá sau 15 tháng; nhật ký kiểm toán thì không ai xoá được trong 10 năm.

## Metric: Prometheus, Thanos và SLI

Mỗi cell có một cặp Prometheus (hai bản sao chạy song song, khử trùng ở Thanos Query) do Prometheus Operator quản lý, thu thập mỗi 15 giây từ `ServiceMonitor` và `PodMonitor` mà chart thư viện sinh cho mọi service (B3). Ứng dụng Java xuất metric bằng Micrometer qua endpoint `/actuator/prometheus` trên cổng quản trị 8081, gồm các bộ đếm và histogram chuẩn (HTTP server, JDBC pool Hikari, Kafka client, JVM, Resilience4j) và metric nghiệp vụ (số nhiệm vụ cập nhật, số minh chứng chờ quét, độ trễ tính điểm). Histogram dùng dạng native histogram của Prometheus để ước lượng phân vị chính xác mà không bùng nổ số chuỗi.

Thanos sidecar đẩy khối 2 giờ lên Ceph S3 của cụm quản trị. Thanos Compactor giảm độ phân giải: dữ liệu thô giữ 30 ngày, độ phân giải 5 phút giữ 180 ngày, độ phân giải 1 giờ giữ 2 năm để phục vụ rà soát năng lực hằng quý (OPS-25) và so sánh giữa các kỳ đánh giá cùng loại. Số chuỗi metric ước tính cho CELL-HCM khoảng 3 triệu chuỗi hoạt động (khoảng 300 pod × 5.000 chuỗi mỗi pod cộng metric hạ tầng), Prometheus mỗi bản sao cần khoảng 24 GiB bộ nhớ (ước tính theo khoảng 8 KB mỗi chuỗi hoạt động gồm chỉ mục và đầu khối).

Quy tắc ghi (recording rules) tính sẵn SLI theo Four Golden Signals cho mọi API chính (B4), làm đầu vào cho dashboard và cảnh báo:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: slo-api-recording
  namespace: observability
spec:
  groups:
    - name: slo-api.rules
      interval: 30s
      rules:
        - record: slo:api_requests:rate5m
          expr: sum by (cell, route, kind) (rate(apisix_http_latency_count{type="request"}[5m]))
        - record: slo:api_errors:rate5m
          expr: sum by (cell, route, kind) (rate(apisix_http_status{code=~"5.."}[5m]))
        - record: slo:api_read_fast:ratio_rate5m
          expr: |
            sum by (cell) (rate(apisix_http_latency_bucket{type="request", kind="read", le="300"}[5m]))
            / sum by (cell) (rate(apisix_http_latency_count{type="request", kind="read"}[5m]))
        - record: slo:api_availability_errors:ratio_rate5m
          expr: sum by (cell) (rate(apisix_http_status{code=~"5.."}[5m])) / sum by (cell) (rate(apisix_http_status[5m]))
        - record: slo:api_availability_errors:ratio_rate30m
          expr: sum by (cell) (rate(apisix_http_status{code=~"5.."}[30m])) / sum by (cell) (rate(apisix_http_status[30m]))
        - record: slo:api_availability_errors:ratio_rate1h
          expr: sum by (cell) (rate(apisix_http_status{code=~"5.."}[1h])) / sum by (cell) (rate(apisix_http_status[1h]))
        - record: slo:api_availability_errors:ratio_rate6h
          expr: sum by (cell) (rate(apisix_http_status{code=~"5.."}[6h])) / sum by (cell) (rate(apisix_http_status[6h]))
```

Nhãn `kind` (read, write, async) được APISIX gắn theo phương thức HTTP và theo chú thích tuyến (`vt.io/kind: async` cho tuyến trả 202). Nhãn `tenant` không được đưa vào metric của APISIX ở cell pool có 1.000 tenant để tránh bùng nổ số chuỗi; phân tích theo tenant dùng ClickHouse với dữ liệu từ nhật ký truy cập.

**Truy vấn chậm (B7).** PostgreSQL bật `pg_stat_statements` và `auto_explain` với `log_min_duration = 500ms`, `auto_explain.log_analyze = on` với `sample_rate = 0.01` để chi phí thấp. postgres-exporter xuất top truy vấn theo tổng thời gian; dashboard "Truy vấn chậm" liệt kê theo `queryid` kèm kế hoạch thực thi từ log. Hibernate bật `hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS=200` để ghi truy vấn chậm kèm `trace_id`, nối truy vấn chậm với request gây ra nó.

## Cảnh báo theo tốc độ tiêu ngân sách lỗi

Cảnh báo SLO dùng phương pháp nhiều cửa sổ, nhiều tốc độ tiêu (multi-window, multi-burn-rate). Với SLO 99,9% trong 30 ngày, ngân sách lỗi là 0,1%. Tốc độ tiêu 14,4 nghĩa là nếu giữ tốc độ đó, ngân sách 30 ngày cạn trong 30 / 14,4 ≈ 2,1 ngày, và trong 1 giờ đã tiêu 1 / (30 × 24) × 14,4 = 2% ngân sách. Cảnh báo gọi người trực khi cả cửa sổ dài (1 giờ) và cửa sổ ngắn (5 phút) đều vượt ngưỡng, để vừa phát hiện nhanh vừa tự tắt nhanh khi sự cố qua đi.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: slo-api-alerts
  namespace: observability
spec:
  groups:
    - name: slo-api.alerts
      rules:
        - alert: SLOAvailabilityFastBurn
          expr: |
            slo:api_availability_errors:ratio_rate1h > (14.4 * 0.001)
            and slo:api_availability_errors:ratio_rate5m > (14.4 * 0.001)
          for: 2m
          labels: { severity: critical, slo: availability }
          annotations:
            summary: "Cell {{ $labels.cell }} tiêu ngân sách lỗi khả dụng nhanh (14,4 lần)"
            runbook_url: https://runbook.vt.local/okr/slo-availability
        - alert: SLOAvailabilitySlowBurn
          expr: |
            slo:api_availability_errors:ratio_rate6h > (6 * 0.001)
            and slo:api_availability_errors:ratio_rate30m > (6 * 0.001)
          for: 15m
          labels: { severity: warning, slo: availability }
          annotations:
            summary: "Cell {{ $labels.cell }} tiêu ngân sách lỗi khả dụng (6 lần)"
            runbook_url: https://runbook.vt.local/okr/slo-availability
        - alert: SLOReadLatencyBurn
          expr: (1 - slo:api_read_fast:ratio_rate5m) > (14.4 * 0.05)
          for: 5m
          labels: { severity: critical, slo: read-latency }
          annotations:
            summary: "Cell {{ $labels.cell }}: hơn 72% request đọc chậm hơn 300 ms trong 5 phút"
```

Bảng cảnh báo tối thiểu (các cảnh báo hạ tầng chuẩn của kube-prometheus-stack, CNPG, Strimzi được giữ nguyên và không liệt kê):

| Cảnh báo | Điều kiện | Mức | Người nhận | Runbook |
|---|---|---|---|---|
| SLOAvailabilityFastBurn | Tốc độ tiêu ≥ 14,4 ở 1 giờ và 5 phút | critical | Trực SRE, gọi điện | slo-availability |
| SLOAvailabilitySlowBurn | Tốc độ tiêu ≥ 6 ở 6 giờ và 30 phút | warning | Trực SRE, tin nhắn | slo-availability |
| SLOReadLatencyBurn, SLOWriteLatencyBurn | Tỷ lệ request chậm vượt ngưỡng | critical | Trực SRE | slo-latency |
| ScoringLagHigh | Độ trễ EVT-08 tới EVT-10 p99 > 60 s trong 10 phút | warning | Trực SRE, chủ service scoring | scoring-lag |
| KafkaConsumerLagGrowing | `deriv(kafka_consumergroup_lag[15m]) > 0` và lag > 100.000 | warning | Trực SRE | kafka-lag |
| DLQNotEmpty | `kafka_topic_partition_current_offset{topic=~".*\\.dlq\\..*"}` tăng | warning | Chủ service | dlq (OPS-24) |
| OutboxBacklog | Dòng outbox chưa gửi > 10.000 hoặc cũ hơn 60 s | critical | Trực SRE, DBA | outbox |
| LedgerLagHigh | SLO-LEDGER: p99,9 > 30 s | warning | Trực SRE | ledger-lag |
| LedgerChainBroken | `ledger_verify_failures_total` tăng | critical | SOC, DBA, IC | DR-14 |
| CNPGReplicaClusterLag | Độ trễ replica cluster phía Nam > 5 phút (warning), > 10 phút (critical) | warning, critical | DBA | pg-replica-lag |
| CNPGPrimaryCPUHigh | CPU primary > 75% trong 15 phút | warning | DBA, SRE | pg-cpu |
| PgBouncerWaitingClients | `pgbouncer_pools_client_waiting_connections > 50` trong 2 phút | warning | DBA | pgbouncer |
| BackupMissing | Không có bản nền thành công trong 26 giờ hoặc WAL lưu trữ trễ > 5 phút | critical | DBA | backup |
| RestoreCheckStale | `backup_restore_check_success` không có giá trị 1 trong 8 ngày | warning | DBA | restore-check |
| CephSyncBehind | Đối tượng chưa đồng bộ multisite cũ hơn 10 phút | warning | SRE lưu trữ | ceph-sync |
| FluentBitDropping | `fluentbit_output_dropped_records_total` tăng | critical | SRE | logging |
| TraceCompletenessLow | `trace_completeness_ratio < 0.99` trong 1 giờ | warning | SRE, chủ service | tracing |
| CertificateExpiringSoon | Chứng thư hết hạn trong < 14 ngày | warning | SRE | certs |
| OpenBaoSealed | Một node OpenBao bị niêm phong | critical | Quản trị khoá | openbao |
| KyvernoImageRejected | Kyverno từ chối ảnh không ký | warning | SOC | supply-chain |
| FalcoCritical | Quy tắc Falco mức Critical | critical | SOC | runtime-security |
| SaturationLevel2 | Cell vào mức suy giảm 2 (mục 6.9) | critical | IC | degradation |
| NodeCapacityHeadroomLow | Năng lực dự phòng của node pool < 15% trong 30 phút | warning | SRE | capacity |
| ErrorBudgetHalfSpent | Tiêu > 50% ngân sách trước ngày 15 | info | Chủ sản phẩm, SRE | release-freeze |

Mỗi cảnh báo có `runbook_url` trỏ tới runbook tương ứng (OPS-14). Alertmanager gom cảnh báo theo `cell` và `alertname`, ức chế cảnh báo hệ quả (ví dụ khi `CNPGClusterNotHealthy` đang kích hoạt thì ức chế `OutboxBacklog` của cùng cell), định tuyến mức critical tới hệ thống gọi trực, mức warning tới kênh nhắn tin của đội.

## Dashboard và giám sát phía người dùng

Grafana có bốn nhóm dashboard dựng từ mã (Grafonnet, lưu trong kho GitOps): (1) dashboard SLO theo cell, hiển thị ngân sách lỗi còn lại, tốc độ tiêu, phân vị độ trễ; (2) dashboard theo service với Four Golden Signals, pool kết nối, Kafka lag, Resilience4j (trạng thái circuit breaker, tỷ lệ bị bulkhead từ chối); (3) dashboard hạ tầng dữ liệu (CNPG, Strimzi, Valkey, OpenSearch, ClickHouse, Ceph); (4) dashboard nghiệp vụ cho vận hành kỳ đánh giá: số người dùng đang hoạt động theo cell, tiến độ nộp hồ sơ, số minh chứng chờ quét, tiến độ tính lại điểm, tiến độ ký lô. Mọi biểu đồ độ trễ có liên kết exemplar tới Tempo; mọi trace có liên kết tới log trong Loki theo `trace_id` (B5).

Giám sát phía người dùng (B8) dùng Grafana Faro Web SDK nhúng trong shell Angular, thu Core Web Vitals (LCP, INP, CLS), lỗi JavaScript, thời gian tải từng remote của Native Federation, và tạo span trình duyệt nối với trace phía máy chủ qua `traceparent`. Dữ liệu Faro gửi tới Faro receiver của OTel Collector qua APISIX với giới hạn tốc độ riêng, lấy mẫu 10% phiên (100% phiên có lỗi). Ứng dụng di động Flutter dùng OpenTelemetry Dart SDK với cùng nguyên tắc. RUM là nguồn của SLO-PAGE và SLO-LCP ở mục 7.1.

## Mức đáp ứng các tiêu chí B và G

| Tiêu chí | Hiện trạng theo bản đánh giá | Thiết kế TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| B1 Tracing phân tán | Chưa đạt: agent có nhưng không nạp | OTel agent trong ảnh nền, Collector, Tempo (mục 10.2) | Trace liền mạch qua APISIX, mission-core, Kafka, scoring trong PERF-01 |
| B2 Lan truyền ngữ cảnh | Chưa đạt: header tự chế | W3C Trace Context, Baggage, header Kafka, outbox `trace_context` | `trace_completeness_ratio` ≥ 0,99 |
| B3 Endpoint metrics | Một phần | Micrometer `/actuator/prometheus` mọi service, ServiceMonitor do chart sinh | Prometheus target up 100% |
| B4 SLI, SLO | Chưa đạt: 0 SLI | Recording rules mục 10.4, SLO mục 7.1 | Dashboard SLO theo cell |
| B5 APM | Một phần | Tempo, exemplar, liên kết trace với log | Từ cảnh báo tới trace trong ≤ 3 thao tác |
| B6 Health check | Một phần: manifest không khai báo probe | Ba loại probe trên cổng 8081 do chart sinh, Kyverno bắt buộc (SEC-28) | Kyverno: 0 vi phạm |
| B7 Truy vấn chậm | Một phần | `pg_stat_statements`, `auto_explain`, log truy vấn chậm có `trace_id` | Dashboard truy vấn chậm |
| B8 RUM | Chưa đạt | Grafana Faro, OTel Dart | SLO-PAGE, SLO-LCP có số liệu |
| G1 Thu gom tập trung | Một phần | Fluent Bit DaemonSet mọi node, mọi namespace | 100% namespace có log trong Loki |
| G2 Log có cấu trúc | Chưa đạt | JSON ECS | Truy vấn LogQL theo trường |
| G3 Liên kết log với trace | Một phần | `trace.id` trong mọi dòng log, structured metadata Loki | Từ trace mở được log tương ứng |
| G4 Không mất log khi tải cao | Chưa đạt: giữ khoảng 5% | Bộ đệm đĩa Fluent Bit, `Retry_Limit no_limits`, cảnh báo khi bỏ bản ghi | Phép thử chặn Loki 30 phút: sai lệch 0 |
| G5 Lưu giữ, bảo vệ log | Chưa đạt | 90 ngày nóng, 1 năm lạnh; kiểm toán 10 năm Object Lock | Cấu hình retention; Object Lock |
| G6 Tách audit log | Chưa đạt | Luồng `sys.audit.v1` riêng, archiver, SIEM | Kiểm toán độc lập với log ứng dụng |
| G7 Không ghi dữ liệu nhạy cảm | Chưa đạt | Che hai lớp (Logback, Fluent Bit Lua), Semgrep cấm ghi body, truy vấn định kỳ tìm mẫu JWT (SEC-07, SEC-53) | Truy vấn tìm `eyJ`, mẫu CCCD: 0 kết quả |

# Quy trình vận hành

## Danh mục quy trình OPS

Chương này mô tả các quy trình vận hành mà thiết kế ở các chương trước cần để hoạt động đúng. Mỗi quy trình có mã OPS, chu kỳ, người chịu trách nhiệm và mức tự động hoá. Runbook chi tiết từng lệnh được duy trì trong kho runbook dưới dạng Markdown có thể chạy được (các khối lệnh được kiểm thử định kỳ trên staging), không nằm trong tài liệu này.

| Mã | Quy trình | Chu kỳ | Chịu trách nhiệm | Tự động hoá |
|---|---|---|---|---|
| OPS-01 | GitOps: ApplicationSet theo cell | Liên tục | Đội nền tảng | Argo CD |
| OPS-02 | Phát hành theo đợt cell | Mỗi bản phát hành | Quản lý phát hành | Argo CD RollingSync |
| OPS-03 | Canary với phân tích SLO | Mỗi bản phát hành | Quản lý phát hành | Argo Rollouts |
| OPS-04 | Cờ tính năng theo tenant | Khi bật tính năng | Chủ sản phẩm | OpenFeature, flagd |
| OPS-05 | Quay lui tự động | Khi vi phạm SLO | Tự động, SRE xác nhận | Argo Rollouts |
| OPS-06 | Đóng băng thay đổi trước mốc hạn | Theo lịch | Quản lý phát hành | Argo CD sync window |
| OPS-07 | Đưa tenant mới vào vận hành ≤ 1 ngày | Theo yêu cầu | Đội triển khai khách hàng | Pipeline onboarding |
| OPS-08 | Chuyển tenant giữa cell | Theo yêu cầu | DBA, đội nền tảng | Một phần |
| OPS-09 | Migration CSDL expand, contract | Mỗi bản phát hành | Đội phát triển, DBA | Flyway |
| OPS-10 | Phát hành Policy Pack ≤ 5 ngày | Theo yêu cầu | Chủ sản phẩm, nghiệp vụ | Pipeline Policy Pack |
| OPS-11 | Phát hành UI Pack ≤ 1 ngày | Theo yêu cầu | Đội triển khai | Pipeline UI Pack |
| OPS-12 | Bản LTS cho mô hình cách ly | 6 tháng | Quản lý phát hành | Pipeline đóng gói |
| OPS-13 | Trực vận hành và mức sự cố | Liên tục | SRE | Hệ thống gọi trực |
| OPS-14 | Runbook | Liên tục | Mọi đội | Kiểm thử runbook |
| OPS-15 | Nâng cấp Kubernetes | Quý | Đội nền tảng | Cluster API |
| OPS-16 | Vá node | Tháng | Đội nền tảng | Cluster API, kured |
| OPS-17 | Danh mục chuẩn bị trước mốc hạn | Mỗi kỳ | SRE, chủ sản phẩm | Một phần |
| OPS-18 | Diễn tập khôi phục | Ngày, quý, năm | DBA, IC | `restore-check` |
| OPS-19 | Hội đồng thay đổi (CAB) | Tuần, khẩn | Quản lý thay đổi | Quy trình |
| OPS-20 | Xoay vòng khoá | Theo lịch mục 9.4 | Quản trị khoá | OpenBao, cert-manager |
| OPS-21 | Tính lại và đối chiếu điểm hằng đêm | Ngày | Chủ service scoring | Saga SVC-06 |
| OPS-22 | Kiểm tra chuỗi băm hằng ngày | Ngày | SOC | CronJob `verify_run` |
| OPS-23 | Thêm cell mới | Theo nhu cầu | Đội nền tảng | Cluster API, ApplicationSet |
| OPS-24 | Xử lý thư chết (DLQ) | Liên tục | Chủ service | Công cụ phát lại |
| OPS-25 | Rà soát năng lực | Quý | SRE, kiến trúc | Báo cáo Thanos |
| OPS-26 | Vá thư viện và CVE | Liên tục | Đội phát triển, SOC | Dependency-Track, Renovate |

## GitOps và phát hành theo đợt cell

**OPS-01.** Kho GitOps có cấu trúc: `clusters/<cụm>/` cho cấu hình riêng của cụm; `cells/<cell>/cell.yaml` mô tả cell (hạng, TTDL chính, danh sách tenant silo, đợt phát hành); `apps/<service>/` chứa values chung; `envs/<môi trường>/` chứa digest ảnh. ApplicationSet dùng bộ sinh ma trận kết hợp danh sách cell với danh sách service để sinh một Application cho mỗi cặp. Chiến lược `RollingSync` của ApplicationSet đồng bộ theo từng bước được chọn bằng nhãn `wave` của Application, thực hiện thứ tự phát hành theo đợt cell (OPS-02).

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: okr-mission-core
  namespace: argocd
spec:
  goTemplate: true
  goTemplateOptions: ["missingkey=error"]
  generators:
    - matrix:
        generators:
          - git:
              repoURL: https://git.vt.local/okr/gitops.git
              revision: main
              files: [{ path: "cells/*/cell.yaml" }]
          - list:
              elements:
                - { site: hl }
                - { site: sn }
  strategy:
    type: RollingSync
    rollingSync:
      steps:
        - matchExpressions: [{ key: wave, operator: In, values: ["1"] }]
        - matchExpressions: [{ key: wave, operator: In, values: ["2"] }]
          maxUpdate: 50%
        - matchExpressions: [{ key: wave, operator: In, values: ["3"] }]
        - matchExpressions: [{ key: wave, operator: In, values: ["4"] }]
  template:
    metadata:
      name: "mission-core-{{ .cell.name }}-{{ .site }}"
      labels:
        wave: "{{ .cell.releaseWave }}"
        cell: "{{ .cell.name }}"
    spec:
      project: okr-apps
      source:
        repoURL: https://git.vt.local/okr/gitops.git
        targetRevision: main
        path: charts/mission-core
        helm:
          valueFiles:
            - ../../apps/mission-core/values.yaml
            - "../../apps/mission-core/values-sizing-{{ .cell.sizing }}.yaml"
            - "../../cells/{{ .cell.name }}/values-{{ .site }}.yaml"
            - ../../envs/prod/digests.yaml
      destination:
        name: "{{ .cell.name }}-{{ .site }}"
        namespace: app-mission
      syncPolicy:
        syncOptions: ["ServerSideApply=true", "PruneLast=true"]
        retry: { limit: 3, backoff: { duration: 30s, factor: 2, maxDuration: 5m } }
```

Bốn đợt phát hành: đợt 1 là CELL-G4 (cell bridge có số tài khoản nhỏ nhất trong nhóm SaaS, đóng vai trò cell "chim hoàng yến"); đợt 2 là CELL-G1 đến G3 và CELL-SH; đợt 3 là CELL-HN và CELL-DTW; đợt 4 là CELL-HCM. Giữa các đợt có thời gian ổn định tối thiểu 24 giờ ngày làm việc. Thứ tự này đặt cell lớn nhất và nhạy cảm nhất ở cuối để một lỗi chỉ lộ ra ở tải lớn vẫn có cơ hội được phát hiện ở các cell nhỏ hơn trước. TL-04 đề xuất bổ sung một cell thí điểm nội bộ `CELL-NB` (tenant là chính các đơn vị của Viettel dùng thử) đứng trước CELL-G4 như đợt 0, khi số lượng người dùng nội bộ đủ lớn.

![Hình 11.1. Phát hành theo đợt cell với canary và quay lui tự động](tk-phat-hanh-cell.png){width=16cm}

Hình 11.1 cho thấy một bản phát hành đi qua staging, rồi qua bốn đợt cell. Trong mỗi đợt, Argo Rollouts chạy canary: 5% lưu lượng sang phiên bản mới trong 30 phút với phân tích SLO tự động, rồi tăng dần. Nếu phân tích phát hiện vi phạm ở bất kỳ đợt nào, Rollouts tự huỷ (abort) và đưa toàn bộ lưu lượng về phiên bản cũ, các đợt sau không được bắt đầu. Sau khi đợt cuối hoàn tất, tính năng mới vẫn ở trạng thái tắt và được bật theo tenant bằng cờ tính năng (OPS-04), tách việc triển khai mã khỏi việc phát hành tính năng tới người dùng.

**Canary và phân tích (OPS-03, OPS-05).** Mission-core và các service tiếp nhận lưu lượng người dùng dùng `Rollout` của Argo Rollouts với định tuyến lưu lượng qua APISIX (Argo Rollouts hỗ trợ APISIX gốc, điều chỉnh trọng số trong `ApisixRoute`). Mẫu phân tích `slo-api` so sánh phiên bản canary với ngưỡng tuyệt đối:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: slo-api
spec:
  args:
    - name: service
    - name: cell
  metrics:
    - name: error-rate
      interval: 2m
      count: 15
      failureLimit: 1
      successCondition: result[0] < 0.002
      provider:
        prometheus:
          address: http://prometheus-operated.observability.svc:9090
          query: |
            sum(rate(http_server_requests_seconds_count{service="{{args.service}}", rollouts_pod_template_hash=~".*", role="canary", status=~"5.."}[2m]))
            / clamp_min(sum(rate(http_server_requests_seconds_count{service="{{args.service}}", role="canary"}[2m])), 1)
    - name: p95-read-latency
      interval: 2m
      count: 15
      failureLimit: 2
      successCondition: result[0] < 0.3
      provider:
        prometheus:
          address: http://prometheus-operated.observability.svc:9090
          query: |
            histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service="{{args.service}}", role="canary", method="GET"}[2m])))
    - name: p95-write-latency
      interval: 2m
      count: 15
      failureLimit: 2
      successCondition: result[0] < 0.8
      provider:
        prometheus:
          address: http://prometheus-operated.observability.svc:9090
          query: |
            histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service="{{args.service}}", role="canary", method=~"POST|PUT|PATCH|DELETE"}[2m])))
---
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: mission-core
  namespace: app-mission
spec:
  replicas: 6
  revisionHistoryLimit: 5
  selector: { matchLabels: { app.kubernetes.io/name: mission-core } }
  workloadRef: { apiVersion: apps/v1, kind: Deployment, name: mission-core-template, scaleDown: progressively }
  strategy:
    canary:
      canaryMetadata: { labels: { role: canary } }
      stableMetadata: { labels: { role: stable } }
      trafficRouting:
        apisix:
          route:
            name: mission-core
            rules: [api-v1]
      steps:
        - setWeight: 5
        - analysis:
            templates: [{ templateName: slo-api }]
            args:
              - { name: service, value: mission-core }
              - { name: cell, valueFrom: { fieldRef: { fieldPath: metadata.labels['vt.io/cell'] } } }
        - setWeight: 25
        - pause: { duration: 15m }
        - setWeight: 50
        - pause: { duration: 15m }
        - setWeight: 100
      abortScaleDownDelaySeconds: 600
```

Ngưỡng tỷ lệ lỗi 0,2% của canary cao hơn ngân sách 0,1% của SLO vì canary có ít lưu lượng, biến động thống kê lớn hơn; điều kiện `failureLimit: 1` trên 15 lần đo yêu cầu vi phạm lặp lại mới huỷ. Ở CELL-G4 với khoảng 800 request mỗi giây ở giờ cao điểm, 5% là khoảng 40 request mỗi giây, đủ để 2 phút có khoảng 4.800 mẫu. Phát hành vào khung giờ thấp điểm có thể không đủ mẫu; khi đó bước phân tích được kéo dài bằng tham số `count` lớn hơn.

**Cờ tính năng (OPS-04).** Cờ tính năng dùng chuẩn OpenFeature với nhà cung cấp flagd chạy trong cell, định nghĩa cờ lưu trong kho GitOps theo tenant. Mọi tính năng mới có tác động tới người dùng phải nằm sau cờ; cờ được bật dần theo tenant thí điểm, rồi theo nhóm tenant. Cờ tồn tại quá 90 ngày sau khi bật cho mọi tenant bị đánh dấu nợ kỹ thuật và phải được dọn.

## Đóng băng thay đổi và hội đồng thay đổi

**Cửa sổ đóng băng (OPS-06).** Tải đỉnh của hệ thống tập trung ở cuối kỳ đánh giá. Cửa sổ đóng băng sản xuất: từ ngày 24 của tháng cuối mỗi quý tới hết ngày 10 của tháng đầu quý sau; và từ 20/1 tới 05/2 hằng năm (cuối kỳ đánh giá năm, trùng giai đoạn trước Tết). Trong cửa sổ, chỉ cho phép bản sửa lỗi nghiêm trọng và bản vá an ninh CVSS ≥ 9, qua CAB khẩn. Argo CD thực thi bằng sync window, nên quy tắc không phụ thuộc vào trí nhớ của người:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: okr-apps
  namespace: argocd
spec:
  syncWindows:
    - kind: deny
      schedule: "0 0 24 3,6,9,12 *"
      duration: 408h        # 17 ngày: từ 24 tháng cuối quý tới hết 10 tháng đầu quý sau
      applications: ["*"]
      manualSync: true      # cho phép đồng bộ thủ công qua vai trò release-manager khi CAB khẩn duyệt
      timeZone: Asia/Ho_Chi_Minh
    - kind: deny
      schedule: "0 0 20 1 *"
      duration: 384h        # 16 ngày: 20/1 tới hết 04/2, mở lại 05/2
      applications: ["*"]
      manualSync: true
      timeZone: Asia/Ho_Chi_Minh
```

Lịch đóng băng chi tiết của từng năm được sinh từ cấu hình kỳ đánh giá (`config.period` của MOD-08) bởi một tác vụ cập nhật kho GitOps, vì mỗi khách hàng có thể đặt mốc hạn khác nhau; tác vụ này cũng sinh các khung tăng trước của KEDA (mục 6.4) từ cùng nguồn, nên lịch đóng băng và lịch tăng năng lực luôn nhất quán.

**Hội đồng thay đổi (OPS-19).** Thay đổi chia ba loại. Thay đổi chuẩn (phát hành ứng dụng qua pipeline, thay đổi cấu hình trong ngưỡng đã duyệt) không cần CAB vì pipeline đã có kiểm soát. Thay đổi thường (nâng cấp Kubernetes, thay đổi lược đồ CSDL lớn, thêm cell) qua CAB hằng tuần. Thay đổi khẩn qua CAB khẩn trực tuyến có ít nhất quản lý thay đổi, trưởng ca SRE và chủ sản phẩm. Ngân sách lỗi là đầu vào của CAB: cell đã tiêu quá 50% ngân sách trước ngày 15 tạm dừng thay đổi thường.

## Vòng đời tenant

**Đưa tenant mới vào vận hành ≤ 1 ngày (OPS-07, NFR-07).** Pipeline onboarding nhận hồ sơ tenant đã duyệt (tên, mã đơn vị, mô hình pool, bridge hoặc silo, cell đích, tên miền, IdP, tích hợp) và thực hiện tự động: (1) ghi bản ghi vào danh bạ SVC-03; (2) với tenant bridge, tạo cơ sở dữ liệu riêng trong cụm PostgreSQL của cell bằng CRD `Database` của CNPG; với tenant pool, chèn dòng `tenant` và chính sách RLS đã có sẵn; với tenant silo, gọi quy trình thêm cell OPS-23; (3) tạo KEK `kek-tenant-<id>` trong OpenBao Transit; (4) tạo Organization trong Keycloak của cell, cấu hình IdP liên kết nếu có; (5) nạp cây tổ chức ban đầu qua SCIM hoặc tệp nhập; (6) gán Policy Pack và UI Pack mặc định của khối; (7) tạo bản ghi DNS CNAME của tenant (mục 2.7); (8) chạy kiểm tra khói theo tenant. Các bước 1 đến 8 chạy trong khoảng 30–60 phút; phần còn lại của một ngày dành cho kiểm tra nghiệp vụ với khách hàng. Pipeline cập nhật trạng thái vòng đời của tenant trong `registry.tenant` theo danh sách trạng thái của TL-02: `KHOI_TAO` khi hồ sơ được duyệt; `DANG_CAP_PHAT` trong các bước 1 đến 3 (CSDL, khoá); `DANG_CAU_HINH` trong các bước 4 đến 7 (IdP, cây tổ chức, Policy Pack, UI Pack, DNS); `SAN_SANG` khi kiểm tra khói đạt; `DANG_VAN_HANH` khi khách hàng xác nhận nghiệm thu. Khi hoàn tất bước 3, nền tảng phát sự kiện `sys.tenant.provisioned.v1` để các service khởi tạo dữ liệu mặc định. Bước nào lỗi thì pipeline dừng ở trạng thái hiện tại, có thể chạy lại từ bước lỗi vì mọi bước đều idempotent. Trạng thái `TAM_NGUNG` (hết hạn hợp đồng, yêu cầu của khách hàng) chặn đăng nhập ở Keycloak và chặn ghi ở APISIX nhưng giữ dữ liệu; `DA_CHAM_DUT` kích hoạt quy trình xuất dữ liệu bàn giao rồi xoá mật mã bằng cách huỷ KEK của tenant (mục 8.4), sau thời gian giữ theo hợp đồng.

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Database
metadata:
  name: t-hagiang-okr
  namespace: data-pg
  labels: { vt.io/tenant: hagiang, vt.io/cell: cell-g1 }
spec:
  cluster: { name: pg-core }
  name: t_hagiang
  owner: app_owner
  ensure: present
  databaseReclaimPolicy: retain
  extensions:
    - { name: pg_stat_statements, ensure: present }
    - { name: pgcrypto, ensure: present }
  schemas:
    - { name: mission, owner: app_owner, ensure: present }
    - { name: audit, owner: app_owner, ensure: present }
```

Sau khi CNPG tạo cơ sở dữ liệu, Flyway chạy toàn bộ migration của các module cho cơ sở dữ liệu mới dưới dạng Job do pipeline onboarding tạo, và PgBouncer được cập nhật mục `[databases]` qua ConfigMap. Với cell bridge có khoảng 60–70 tenant cấp tỉnh trong một cụm PostgreSQL, số cơ sở dữ liệu vẫn nằm trong giới hạn thực tế của PostgreSQL.

**Chuyển tenant giữa cell (OPS-08).** Dùng khi một tenant phát triển vượt cell pool, hoặc sáp nhập đơn vị. Cơ chế dựa trên nhân bản logic của PostgreSQL 17 với publication có bộ lọc hàng theo `tenant_id` (với tenant pool) hoặc publication toàn cơ sở dữ liệu (với tenant bridge): (1) tạo cấu trúc ở cell đích; (2) tạo publication ở nguồn `CREATE PUBLICATION mv_t123 FOR TABLES IN SCHEMA mission, audit WHERE (tenant_id = 't123')` (bộ lọc hàng đặt theo từng bảng; câu lệnh thật liệt kê bảng); (3) subscription ở đích sao chép dữ liệu ban đầu rồi theo dõi thay đổi; (4) sao chép đối tượng minh chứng của tenant bằng `rclone` giữa hai realm Ceph; (5) khi độ trễ nhân bản về 0, đặt tenant ở chế độ chỉ đọc 5–15 phút (theo TL-01), đợi đồng bộ cuối, đối soát số dòng và băm theo bảng; (6) đổi cell của tenant trong SVC-03 và bản ghi DNS; (7) mở lại ghi ở cell đích; (8) giữ dữ liệu ở nguồn 30 ngày rồi xoá. Sổ cái của tenant không được sao chép sửa đổi mà được chuyển nguyên chuỗi; chuỗi băm ở cell đích nối tiếp từ `entry_hash` cuối của cell nguồn (TL-01), kèm một bản ghi "chuyển cell" ký bằng khoá của cả hai cell.

## Migration CSDL theo mẫu mở rộng, thu hẹp

**OPS-09.** Bản đánh giá ghi nhận hệ hiện tại không có migration có phiên bản, ứng dụng tự sửa lược đồ lúc chạy (K03). Nền tảng mới dùng Flyway với migration trong repo của từng module, chạy như Job trước khi Rollout bắt đầu (hook `PreSync` của Argo CD), dưới tài khoản `migrator` có quyền DDL (tài khoản ứng dụng không có DDL, SEC-05). Vì phát hành canary nghĩa là hai phiên bản cùng chạy trên một lược đồ, mọi thay đổi lược đồ tuân thủ mẫu mở rộng, thu hẹp (expand, contract) qua tối thiểu hai bản phát hành: bản N thêm cột mới, ghi cả cột cũ và cột mới; tác vụ nền chuyển dữ liệu cũ; bản N+1 đọc cột mới; bản N+2 xoá cột cũ. Quy tắc kiểm tra tự động trong pipeline (dùng `squawk` để kiểm tra SQL) chặn các thao tác khoá bảng lâu: thêm cột `NOT NULL` không có giá trị mặc định, tạo chỉ mục không có `CONCURRENTLY`, đổi kiểu cột, đổi tên cột hoặc bảng trong cùng bản phát hành với mã sử dụng. Mỗi migration đặt `lock_timeout = '5s'` và `statement_timeout` phù hợp, để một migration bị chặn sẽ thất bại nhanh thay vì giữ khoá làm tắc toàn bộ cell.

## Phát hành Policy Pack, UI Pack và bản LTS

**Policy Pack ≤ 5 ngày (OPS-10, NFR-07).** Policy Pack là gói quy tắc tính điểm và xếp loại có phiên bản (baseline mục 11). Quy trình năm ngày: ngày 1, chuyên viên nghiệp vụ soạn thay đổi trong công cụ soạn quy tắc, tạo merge request; ngày 2, pipeline chạy kiểm tra cú pháp, kiểm thử đơn vị quy tắc và bộ kiểm thử vàng (golden test) do nghiệp vụ ký, phải đạt 100% (tiêu chí J6, chỉ số "test vàng 100%" của đợt 2); ngày 3, chạy so sánh bóng (shadow) trên dữ liệu đã che của kỳ trước, báo cáo chênh lệch điểm theo tenant; ngày 4, chủ sản phẩm và đại diện nghiệp vụ của khách hàng duyệt báo cáo chênh lệch, ký phát hành; ngày 5, phát hành qua sự kiện, áp dụng cho kỳ được chỉ định. Policy Pack không đòi hỏi phát hành lại mã, chỉ đi qua pipeline cấu hình và ghi một bản ghi vào sổ cái.

**UI Pack ≤ 1 ngày (OPS-11).** UI Pack (token thiết kế, lược đồ SDUI, biểu mẫu) được kiểm tra lược đồ, kiểm tra khả năng tiếp cận tự động (axe-core, tiêu chí WCAG 2.1 AA của NFR-11), kiểm tra kích thước ≤ 30 KB, chụp ảnh màn hình so sánh trên staging, rồi phát hành; sự kiện EVT-23 kích hoạt vô hiệu hoá cache CDN theo khoá phiên bản.

**Bản LTS (OPS-12).** Mỗi 6 tháng, phiên bản đã chạy ổn định ở SaaS ít nhất một quý được chọn làm LTS, đóng gói theo mục 9.9, hỗ trợ bản vá an ninh 18 tháng. Bản vá an ninh cho LTS được dựng lại từ nhánh LTS với cùng pipeline, ký bằng cùng khoá phát hành.

## Trực vận hành, mức sự cố và runbook

**OPS-13.** Ca trực SRE 24/7 theo hai cấp: cấp 1 tiếp nhận cảnh báo và thực hiện runbook; cấp 2 là kỹ sư chuyên sâu theo lĩnh vực (nền tảng, CSDL, lưu trữ, an ninh) gọi khi cần. Mức sự cố:

| Mức | Định nghĩa | Thời gian phản hồi | Thời gian khắc phục mục tiêu | Thông báo |
|---|---|---|---|---|
| SEV-1 | Một cell trở lên không dùng được, mất dữ liệu, sự cố an ninh nghiêm trọng, hoặc DR | ≤ 15 phút | ≤ 4 giờ | Lãnh đạo, khách hàng mỗi 30 phút |
| SEV-2 | Chức năng chính suy giảm (mức suy giảm 2 kéo dài), vi phạm SLO nhanh | ≤ 30 phút | ≤ 8 giờ | Khách hàng bị ảnh hưởng mỗi 2 giờ |
| SEV-3 | Chức năng phụ lỗi, một tenant bị ảnh hưởng, có cách làm tạm | ≤ 4 giờ làm việc | ≤ 3 ngày làm việc | Qua phiếu hỗ trợ |
| SEV-4 | Lỗi nhỏ, câu hỏi | ≤ 1 ngày làm việc | Theo kế hoạch phát hành | Qua phiếu hỗ trợ |

Mọi sự cố SEV-1 và SEV-2 có bản phân tích sau sự cố không quy lỗi cá nhân (blameless postmortem) trong 5 ngày làm việc, với hạng mục khắc phục có người và hạn; hạng mục được theo dõi tới khi đóng.

**OPS-14.** Mỗi cảnh báo trong bảng mục 10.5 có một runbook. Runbook có cấu trúc thống nhất: triệu chứng, tác động, kiểm tra nhanh (lệnh, truy vấn, dashboard), hành động theo nhánh, khi nào leo thang, cách xác nhận đã khắc phục. Runbook cho các thao tác thường xuyên (bật mức suy giảm, khởi động lại consumer, phát lại DLQ) được tự động hoá thành lệnh của công cụ `vtctl` để giảm lỗi thao tác.

## Bảo trì nền tảng

**Nâng cấp Kubernetes (OPS-15).** Chính sách n-1: sản xuất chạy phiên bản minor liền trước phiên bản mới nhất của upstream (tại thời điểm viết, sản xuất chạy 1.36 theo mục 3.1). Mỗi quý, ngoài cửa sổ đóng băng: nâng cụm staging và `CELL-PERF`, chạy PERF hồi quy rút gọn và kiểm thử đầu cuối; kiểm tra API bị loại bỏ bằng `kubent` hoặc `pluto` trên kho GitOps; nâng các operator tương thích trước; nâng control plane cụm sản xuất theo đợt cell (cùng thứ tự OPS-02), mỗi lần một master; nâng worker bằng cách thay MachineDeployment với `maxSurge: 1`, `maxUnavailable: 0`; với node dữ liệu, DBA chuyển primary đi trước khi node bị rút (`kubectl cnpg promote`), từng node một.

**Vá node (OPS-16).** Ảnh node mới mỗi tháng, triển khai bằng thay MachineDeployment như trên; vá nhân khẩn bằng kured (SEC-51).

**Chuẩn bị trước mốc hạn (OPS-17).** Mười ngày trước mỗi mốc hạn của kỳ đánh giá, SRE và chủ sản phẩm rà danh mục: (1) xác nhận lịch tăng trước của KEDA đã được sinh đúng theo `config.period` của từng tenant (mục 6.4); (2) xác nhận năng lực dự trữ Viettel Cloud (mục 4.4) sẵn sàng; (3) chạy PERF-04 trên `CELL-PERF` với dữ liệu cập nhật; (4) kiểm tra ngân sách lỗi, đóng các hạng mục khắc phục còn mở; (5) xác nhận lịch trực tăng cường; (6) thông báo cho khách hàng về cửa sổ đóng băng và kênh hỗ trợ; (7) kiểm tra kết quả diễn tập khôi phục gần nhất; (8) xác nhận dung lượng còn trống của Ceph, Kafka, OpenSearch đủ cho đỉnh (≥ 30% sau khi tính lượng tăng dự kiến). Danh mục cũng gồm việc chuẩn bị cho saga khoá kỳ theo TL-02: bước 0 kiểm tra điều kiện; bước 1 chuyển kỳ sang `DANG_KHOA` (EVT-09); bước 2 tính điểm hàng loạt (EVT-10, PERF-19); bước 3 sinh biểu mẫu (EVT-17, PERF-20); bước 4 ký lô (EVT-18, PERF-17); bước 5 chuyển `DA_KHOA` và niêm phong sổ cái (EVT-19). Lệnh khoá do lãnh đạo (AC-04) phê duyệt và quản trị tenant (AC-02) khởi động có MFA. Huỷ khoá và bù trừ cần người duyệt thứ hai (AC-09) và chỉ được phép trước khi bước 4 hoàn tất, phát sự kiện EVT-26 `config.period.unlocked.v1`. Vì vậy ca trực cần biết thời điểm dự kiến của bước 4 với từng tenant lớn: sau mốc đó, một lỗi ở bước 4 hoặc 5 chỉ được xử lý bằng thử lại hoặc sửa tiến về phía trước, không quay lui.

## Quy trình định kỳ khác

**OPS-18. Diễn tập khôi phục.** Theo mục 8.12: hằng ngày tự động, hằng quý một cell, hằng năm toàn phần.

**OPS-20. Xoay vòng khoá.** Theo lịch ở mục 9.4; chỉ số `vt_key_age_days` theo loại khoá, cảnh báo khi một khoá quá hạn xoay vòng 7 ngày.

**OPS-21. Tính lại và đối chiếu điểm hằng đêm.** Lúc 00:30, saga của SVC-06 tính lại toàn bộ điểm của các kỳ đang mở ở mỗi cell (cùng cơ chế PERF-19), so sánh với điểm đã tính dần trong ngày; lần tính lại chạy dưới dạng một `scoring.score_job` loại `KIEM_TRA_TAI_LAP` (TL-03, mục Schema scoring), và mọi hồ sơ có `result_hash` khác với `scoring.score_result` đã tính dần được đánh dấu `LOI` kèm cảnh báo cho chủ service. Quy trình này phát hiện lỗi tính dần (sự kiện bị bỏ sót, xử lý không idempotent) trước khi người dùng thấy, và là bằng chứng cho tiêu chí I6 (kết quả tái lập được).

**OPS-22. Kiểm tra chuỗi băm.** CronJob lúc 03:00 kiểm tra bản neo của ngày trước đã được ít nhất hai bên độc lập xác nhận nhận được, rồi chạy `verify_run` cho 24 giờ trước và 1% ngẫu nhiên của bản ghi cũ hơn; kết quả ghi vào `ledger.verify_run` và metric; thất bại kích hoạt DR-14.

**OPS-23. Thêm cell mới.** Ngưỡng tách cell thống nhất với TL-01: một cell vượt 1,5 triệu tài khoản hoặc 75.000 người dùng đồng thời, hoặc workflow-service của cell cần vượt 300 user task mỗi giây (ngưỡng tách engine), thì phải chia. Quy trình được kích hoạt sớm hơn, khi rà soát năng lực dự báo cell sẽ chạm 80% của một trong các ngưỡng đó trong hai quý tới, hoặc khi có tenant silo mới: (1) khai báo cell trong `cells/<tên>/cell.yaml` (hạng, TTDL, đợt phát hành, nhóm Ceph); (2) Cluster API dựng cụm ở hai TTDL; (3) ApplicationSet tự sinh mọi Application cho cell mới; (4) tạo realm RGW, KEK, Keycloak realm; (5) chạy PERF-01 và PERF-03 rút gọn; (6) đăng ký cell trong SVC-03 và chuyển tenant vào bằng OPS-08. Thời gian mục tiêu 5 ngày làm việc, chủ yếu là kiểm tra.

**OPS-24. Xử lý thư chết.** Quy ước đặt tên theo TL-01: mỗi consumer có topic thử lại riêng `<topic>.retry.<consumer>` và hàng đợi thư chết riêng `<topic>.dlq.<consumer>`, nên một consumer lỗi không làm ảnh hưởng consumer khác của cùng topic. Sau 3 lần thử lại có lùi thời gian, bản ghi lỗi được chuyển sang `<topic>.dlq.<consumer>` kèm header lý do. Chủ service xem DLQ qua giao diện quản trị, phân loại: lỗi dữ liệu (sửa dữ liệu nguồn rồi phát lại), lỗi mã (sửa mã, phát hành, rồi phát lại), bản ghi không hợp lệ vĩnh viễn (ghi nhận và bỏ qua có duyệt). Công cụ `vtctl dlq replay --topic <t> --from <offset> --to <offset>` phát lại về topic gốc qua topic trung gian `<topic>.replay.<consumer>` (được loại khỏi nhân bản MirrorMaker 2, mục 8.3). Mục tiêu: DLQ của topic nghiệp vụ được xử lý hết trong 1 ngày làm việc; trong cửa sổ đóng băng, trong 4 giờ.

**OPS-25. Rà soát năng lực.** Mỗi quý, SRE và kiến trúc so sánh số liệu thật (Thanos độ phân giải 1 giờ) với mô hình định cỡ ở chương 4: RPS đỉnh theo cell, RPS mỗi pod, CPU primary CSDL, dung lượng lưu trữ, tăng trưởng minh chứng. Lệch quá 15% thì cập nhật mô hình, các giá trị `maxReplicas`, ngân sách kết nối và kế hoạch phần cứng. Kết quả là đầu vào của OPS-23.

**OPS-26. Vá thư viện và CVE.** Renovate mở merge request cập nhật phụ thuộc hằng tuần, gộp theo nhóm; Dependency-Track cảnh báo CVE mới trên SBOM đang chạy. Thời hạn: Critical có bản vá 7 ngày, High 30 ngày, Medium theo chu kỳ phát hành; CVE nằm trong thành phần không dùng tới đường mã bị ảnh hưởng được ghi nhận bằng VEX (Vulnerability Exploitability eXchange) có giải trình thay vì bỏ qua im lặng.

# Lộ trình triển khai

## Nguyên tắc

Lộ trình triển khai của TL-04 bám theo sáu đợt mà bản đánh giá đã đề xuất (0a, 0b, 0c, 1, 2, 3) và dùng đúng tiêu chí hoàn thành của bản đánh giá làm tiêu chí nghiệm thu. Ba đợt đầu (đợt 0) diễn ra trên **hệ thống hiện tại** đang phục vụ người dùng: mục tiêu là đóng lỗ hổng, dựng nền vận hành tối thiểu và chặn chất lượng ở pipeline, chứ chưa thay kiến trúc. Ba đợt sau xây nền tảng mới theo mẫu Strangler Fig (ADR-20): APISIX đứng trước hệ cũ ngay từ đợt 0b, sau đó từng chức năng được chuyển sang nền tảng mới phía sau cùng một cổng. Điều này có nghĩa là nhiều biện pháp của TL-04 (manifest có probe, OTel, Fluent Bit, Kyverno, pipeline có cổng chất lượng) được áp dụng trước tiên cho hệ cũ, và cũng chính các thành phần đó được dùng lại cho nền tảng mới, không làm hai lần.

Mỗi đợt được trình bày theo bốn ý: hạng mục hạ tầng và vận hành mà TL-04 bàn giao; các mã SEC, OPS, PERF, DR được đưa vào vận hành; tiêu chí hoàn thành theo bản đánh giá; và cách đo tiêu chí đó bằng công cụ của TL-04.

## Đợt 0a: khắc phục khẩn (2 tuần)

Đợt 0a đóng các vấn đề S01 đến S05, S07, S08, S11. Phần việc chủ yếu là sửa mã của hệ cũ do đội phát triển thực hiện; phần của TL-04 là các biện pháp ở mục 9.1 và hạ tầng tối thiểu để kiểm chứng.

| Hạng mục | Mã TL-04 | Kết quả bàn giao |
|---|---|---|
| Quét toàn bộ lịch sử 4 repo bằng gitleaks, lập danh sách bí mật | SEC-01, SEC-09 | Báo cáo SARIF, danh sách xoay vòng |
| Xoay vòng kubeconfig, khoá ký JWT, mật khẩu Oracle, SSO, VNeID; dọn lịch sử Git | SEC-01 | Biên bản xoay vòng; repo đã dọn |
| Dựng OpenBao tối thiểu (3 node, tự mở khoá bằng khoá tạm, chuyển sang HSM ở đợt 1) và ESO cho cụm hiện tại | SEC-10 | Bí mật của hệ cũ lấy từ OpenBao qua Secret |
| Xoá tài khoản ẩn, sửa danh sách tuyến công khai, bỏ cookie mật khẩu, sửa ORDER BY | SEC-02 đến SEC-05 | Bản phát hành vá |
| CORS danh sách, cookie Secure và SameSite, bỏ token khỏi URL và log | SEC-06 đến SEC-08 | Bản phát hành vá |
| gitleaks ở pre-commit và ở pipeline, chế độ chặn | SEC-09 | Pipeline chặn khi có bí mật |
| Kiểm thử xâm nhập lại bởi đơn vị độc lập | SEC-41 | Báo cáo retest |

**Tiêu chí hoàn thành:** 0 phát hiện secret scanning; retest xâm nhập đạt. Cách đo: gitleaks chạy trên toàn bộ lịch sử của 4 repo trả 0 phát hiện; báo cáo retest không còn phát hiện nào trong tám lỗ hổng. Chỉ số theo dõi "phát hiện secret scanning" từ khoảng 65 về 0; "vấn đề nghiêm trọng còn mở" giảm 5 (S01 đến S05).

## Đợt 0b: nền vận hành tối thiểu (6 tuần)

Đợt 0b đóng các vấn đề C01 đến C03, F01, B01, B02, G01, G02 trên hệ hiện tại.

| Hạng mục | Mã TL-04 | Kết quả bàn giao |
|---|---|---|
| Chuyển hệ cũ sang chart thư viện `vt-service`: manifest riêng mỗi service, ba loại probe, PDB, `topologySpreadConstraints`, tối thiểu 3 replica | Mục 5.1, SEC-28 (chế độ Audit) | Chart và values cho 4 thành phần của hệ cũ |
| Heap 70% giới hạn container, requests và limits theo đo thực tế | Mục 5.1 | Không còn `-Xmx` bằng giới hạn |
| Pool kết nối 20–40 mỗi pod, PgBouncer hoặc giới hạn phía Oracle | Mục 4.7 (áp dụng nguyên tắc ngân sách kết nối cho Oracle) | Tổng kết nối tới Oracle giảm từ khoảng 5.000 xuống dưới 600 |
| APISIX đứng trước hệ cũ, sinh `traceparent` | Mục 3.9 | Mọi request đi qua cổng mới |
| Nạp OTel Java agent, OTel Collector, Tempo; Micrometer registry, Prometheus; log JSON có `trace_id`; Fluent Bit với bộ đệm đĩa thay LogCenter | Chương 10 (rút gọn) | Trace, metric, log liên kết được |
| Dựng cụm quản trị đầu tiên (Argo CD, Harbor, Grafana) | OPS-01 | Hệ cũ triển khai bằng GitOps |
| Thử hỏng: tắt node, xoá pod | PERF-07 (rút gọn) | Báo cáo thử hỏng |

**Tiêu chí hoàn thành:** trace xuyên 4 tầng; mất 1 node không gián đoạn. Cách đo: một request từ trình duyệt qua APISIX, lớp web, backend, Oracle hiển thị thành một trace liền trong Tempo với 4 tầng span; kịch bản PERF-07 rút gọn (tắt ngẫu nhiên một node mỗi 10 phút trong 1 giờ ở tải thường của hệ cũ) không có lỗi 5xx nào do mất node. Chỉ số "request có trace end-to-end" đạt ≥ 90% (đo bằng `trace_completeness_ratio`), "node tối thiểu cho mỗi service" đạt 3.

## Đợt 0c: chịu lỗi và cổng chất lượng (6 tuần)

Đợt 0c đóng các vấn đề F02 đến F04, C04, J02, K03, A01.

| Hạng mục | Mã TL-04 | Kết quả bàn giao |
|---|---|---|
| Resilience4j cho lời gọi core với service: circuit breaker, timeout, bulkhead; executor có giới hạn | Thư viện `vt-platform-starter` của TL-01; dashboard mục 10.6 | 100% lời gọi core với service có circuit breaker |
| Pipeline Jenkins có cổng chất lượng cho 4 repo: kiểm thử chặn, độ bao phủ tệp thay đổi, Semgrep, Trivy, SBOM, ký ảnh; ảnh không root | SEC-23, SEC-37 đến SEC-39, SEC-49, SEC-55 | 4/4 pipeline có cổng chất lượng |
| Kyverno chuyển sang Enforce cho chữ ký ảnh và hardening | SEC-28, SEC-38 | Ảnh không ký bị từ chối |
| Flyway với schema riêng cho phần mới, cấm ứng dụng tự sửa lược đồ | OPS-09 | Migration có phiên bản |
| Thử tải hệ cũ ở 500 phiên đồng thời | PERF-01, PERF-04 (theo hồ sơ tải của hệ cũ) | Báo cáo thử tải |

**Tiêu chí hoàn thành:** p95 < 3 s ở 500 CCU; pipeline chặn khi lỗi. Cách đo: k6 với 500 người dùng ảo theo hồ sơ thao tác thực tế đo p95 tại APISIX; một merge request cố ý chứa kiểm thử lỗi, bí mật giả và thư viện có CVE Critical bị pipeline từ chối ở cả 4 repo. Chỉ số "pipeline có quality gate" đạt 4/4; "lời gọi liên service có circuit breaker" đạt 100% core với service; "phiên đồng thời đạt SLO" đạt 500. Sau đợt 0, "vấn đề nghiêm trọng còn mở" phải ≤ 4 và "điểm đáp ứng tổng hợp" ≥ 40.

## Đợt 1: định danh, tổ chức và tích hợp (Q4/2026 đến Q1/2027)

Đợt 1 bắt đầu nền tảng mới với các thành phần định danh và dữ liệu tổ chức, đóng S06, S12, S14, K01, K02, L01, L03. Về hạ tầng, đây là đợt dựng cell đầu tiên.

| Hạng mục | Mã TL-04 | Kết quả bàn giao |
|---|---|---|
| Dựng `CELL-G4` và cụm vùng điều phối toàn cục ở Hoà Lạc theo chương 3, hạng M | Chương 3, OPS-23 | Cell đầu tiên vận hành |
| HSM, OpenBao 5 node tự mở khoá bằng HSM, PKI nội bộ, cert-manager | SEC-10, SEC-11, SEC-59 | Khoá gốc trong HSM |
| Keycloak mỗi cell với MFA, chống dò mật khẩu, Argon2id, thời hạn phiên; VNeID có state, nonce | SEC-13 đến SEC-17, SEC-54 | Đăng nhập tập trung |
| OPA cùng pod, ABAC theo phạm vi tổ chức | SEC-18 | Phân quyền tập trung |
| Mạng: NetworkPolicy, Istio ambient, egress gateway, WAF | SEC-21, SEC-32 đến SEC-36 | Sáu vùng mạng |
| Nhật ký kiểm toán API, Falco, luồng `sys.audit.v1` tới SIEM | SEC-22, SEC-30, SEC-43, SEC-50 | SOC nhận dữ liệu |
| Sao lưu CNPG, kho sao lưu phía Nam, `restore-check` hằng ngày | Chương 8, OPS-18 | Sao lưu bất biến cho dữ liệu mới |
| Onboarding tenant tự động | OPS-07 | Tenant mới ≤ 1 ngày |

**Tiêu chí hoàn thành:** 0 truy vấn mới vào VHR_*. Cách đo: integration-gateway (SVC-13) là thành phần duy nhất có tài khoản đọc bảng vOffice; truy vấn trên nhật ký kiểm toán của Oracle (hoặc `pg_stat_statements` sau khi chuyển) cho thấy không có câu lệnh mới nào từ tài khoản khác chạm tới bảng có tiền tố `VHR_`; chỉ số "bảng mới trong schema VOFFICE" giữ ở 0.

## Đợt 2: lõi nghiệp vụ trên nền tảng mới (Q1 đến Q2/2027)

Đợt 2 chuyển lõi nghiệp vụ sang mission-core trên PostgreSQL, đóng I01 đến I04, H01 đến H04, A03, E02, E03, L02, L04.

| Hạng mục | Mã TL-04 | Kết quả bàn giao |
|---|---|---|
| Sáu cụm PostgreSQL mỗi cell, PgBouncer, ngân sách kết nối | Mục 4.5, 4.7 | Cụm CSDL theo định cỡ |
| Kafka, Debezium outbox giữ 72 giờ, MirrorMaker 2 sang phía Nam | Mục 4.6, 8.3, 8.10 | Luồng sự kiện có nhân bản |
| Ceph RGW với Object Lock cho minh chứng, multisite | Mục 3.8, 8.4 | Minh chứng WORM |
| Sổ cái, niêm phong, neo ngoài, kiểm tra hằng ngày | Mục 8.8, OPS-22, DR-14 | Sổ cái kiểm chứng được |
| Pipeline Policy Pack với test vàng | OPS-10 | Phát hành quy tắc ≤ 5 ngày |
| Co giãn: HPA, KEDA, lịch tăng trước | Chương 6 | Co giãn tự động |
| Chương trình thử tải đầy đủ trên `CELL-PERF`, gồm các kịch bản góp ý G2, G3 | PERF-01 đến PERF-25 | Báo cáo thử tải |
| Phát hành canary theo đợt cell | OPS-02, OPS-03, OPS-05 | Phát hành tự quay lui |

**Tiêu chí hoàn thành:** phát hành quy tắc ≤ 5 ngày; test vàng 100%. Cách đo: một thay đổi Policy Pack thật được đi trọn quy trình OPS-10, thời gian từ lúc mở merge request tới lúc phát hành được ghi trong pipeline; bộ kiểm thử vàng do nghiệp vụ ký đạt 100%. Ngoài ra, TL-04 đặt điều kiện kỹ thuật để chuyển sang đợt 3: PERF-11, PERF-12, PERF-14, PERF-16, PERF-25 đạt (trả lời định lượng góp ý G2, G3).

## Đợt 3: quy mô quốc gia (từ Q3/2027)

Đợt 3 mở rộng ra toàn bộ cell, đóng C02, C05, E01, A06, H05, L05.

| Hạng mục | Mã TL-04 | Kết quả bàn giao |
|---|---|---|
| Dựng đủ 8 cell SaaS ở Hoà Lạc và chờ ấm ở phía Nam | Chương 2, 3, 4; OPS-23 | Kiến trúc cell đầy đủ |
| Năng lực dự trữ 2.000 vCPU với Viettel Cloud | Mục 4.4 | Hợp đồng dự trữ |
| GeoDNS, `dns-sync`, quy trình chuyển vùng | Mục 2.7, DR-10, DR-13, DR-16 | Chuyển vùng theo cell |
| Diễn tập DR-10 cho từng cell, rồi toàn phần | OPS-18 | Biên bản diễn tập |
| Chuyển tenant từ hệ cũ vào cell theo đợt | OPS-08 | Tenant chạy trên nền tảng mới |
| GPU và vLLM | Mục 4.9, PERF-26 | Trợ lý AI |
| Bản cách ly LTS đầu tiên | OPS-12, mục 9.9 | Gói cài cách ly có chữ ký |
| Đánh giá cấp độ 4, kiểm thử xâm nhập toàn diện | Mục 9.7, SEC-41 | Hồ sơ cấp độ |

**Tiêu chí hoàn thành:** 70.000 phiên đạt SLO; RPO 15 phút, RTO 4 giờ. Cách đo: PERF-03 trên một cell hạng L (60.000 CCU) cộng một cell hạng M chạy đồng thời đạt SLO-READ, SLO-WRITE và SLO-PAGE với tổng ≥ 70.000 phiên; biên bản diễn tập DR-10 ghi RPO đo được ≤ 15 phút và RTO ≤ 4 giờ. Mục tiêu nền tảng của TL-04 xa hơn đợt 3: 300.000 CCU trên toàn bộ cell (NFR-01), "node tối thiểu" đạt 3 mỗi cell nhân 2 TTDL.

## Bảng chỉ số theo dõi và cách đo

Bảng dưới đây lấy nguyên chỉ số theo dõi của bản đánh giá, bổ sung cột cách đo bằng công cụ của TL-04 để chỉ số được báo cáo tự động thay vì thủ công.

| Chỉ số | Hiện tại | Sau đợt 0 | Mục tiêu nền tảng | Cách đo trong TL-04 |
|---|---|---|---|---|
| Điểm đáp ứng tổng hợp (/100) | 17,4 | ≥ 40 | ≥ 75 | Chấm lại checklist mỗi quý theo ma trận chương 13, bằng chứng lấy từ cột "Bằng chứng nghiệm thu" |
| Vấn đề nghiêm trọng còn mở | 12 | ≤ 4 | 0 | Danh sách vấn đề trong hệ thống quản lý công việc, nhãn `danh-gia/nghiem-trong` |
| Phát hiện secret scanning | khoảng 65 | 0 | 0 | gitleaks quét toàn bộ lịch sử hằng tuần (SEC-09), số phát hiện đẩy lên Prometheus |
| Lời gọi liên service có circuit breaker | 0% | 100% core với service | 100% | Metric Resilience4j: tỷ lệ client HTTP, Kafka có circuit breaker được đăng ký trên tổng client |
| Request có trace end-to-end | 0% | ≥ 90% | ≥ 99% | `trace_completeness_ratio` (mục 10.2) |
| Pipeline có quality gate | 0/4 | 4/4 | 4/4 | Thư viện `vt-pipeline` ghi nhãn phiên bản; báo cáo pipeline nào chưa dùng |
| Phiên đồng thời đạt SLO (p95 ≤ 1 s) | < 50 | 500 | 70.000 (đợt 3); 300.000 (NFR-01) | PERF-03 và số người dùng hoạt động đo bằng Faro trong giờ cao điểm thật |
| Node tối thiểu cho mỗi service | 1 | 3 | 3 mỗi cell × 2 TTDL | Kyverno `vt-require-ha` (SEC-28); truy vấn kube-state-metrics |
| Bảng mới trong schema VOFFICE | 23 | 0 bảng mới | 0 | So sánh danh mục bảng Oracle hằng tuần |

# Ma trận truy vết

## Cách đọc ma trận

Ma trận dưới đây ánh xạ từng tiêu chí của bảy trục checklist trong bản đánh giá (B, C, D, E, F, G, J) sang biện pháp của TL-04 (mã SEC, OPS, PERF, DR hoặc mục) và bằng chứng nghiệm thu. Các trục còn lại (A kiến trúc ứng dụng, H nghiệp vụ, I toàn vẹn và pháp lý, K tích hợp, L trải nghiệm) thuộc phạm vi chính của TL-01, TL-02, TL-03; TL-04 chỉ đóng góp một phần và được ghi ở cột "Ghi chú" khi có. Cột "Bằng chứng nghiệm thu" là thứ mà người nghiệm thu cần thấy để chấm tiêu chí đạt; với tiêu chí đo bằng kịch bản thử tải hoặc diễn tập, bằng chứng là báo cáo của kịch bản đó.

## Trục C: triển khai và hạ tầng

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| C1 | Ảnh container an toàn, tối giản | Mục 5.1; SEC-49, SEC-55; Trivy ảnh (SEC-23) | Kyverno báo 0 vi phạm `readonly-rootfs-nonroot`; báo cáo Trivy 0 High, Critical chưa xử lý |
| C2 | Manifest riêng cho từng service | Chart thư viện `vt-service` (mục 5.1, 5.5); OPS-01 | Kho GitOps có chart riêng mỗi service; Argo CD đồng bộ theo service |
| C3 | Probe liveness, readiness, startup | Mục 5.1, 5.5; SEC-28 `require-resources-and-probes` | Kyverno 0 vi phạm; PERF-07 không lỗi khi xếp lại pod |
| C4 | Requests, limits và heap hợp lý | Mục 5.1 (heap 70%), 5.2 | Không OOMKill trong PERF-05 (72 giờ) |
| C5 | Tự động co giãn theo tải | Chương 6; HPA, KEDA, Cluster Autoscaler | PERF-04: p95 phục hồi ≤ 3 phút sau đột biến; lịch tăng trước sinh từ `config.period` |
| C6 | Tiến trình không trạng thái | Mục 5.1; Valkey cho phiên, Ceph cho tệp; không `sessionAffinity` | PERF-07; kiểm tra manifest không có `sessionAffinity`, `hostPath` |
| C7 | Sẵn sàng cao khi gián đoạn node | Mục 3.4 (`topologySpreadConstraints`), PDB, SEC-28 `vt-require-ha` | PERF-07, PERF-10; DR-02, DR-03 |
| C8 | Lưu trữ tệp tách khỏi node | Mục 3.8 (Ceph RGW, RBD); evidence-service ghi S3 | Kyverno chặn `hostPath`; PERF-18 |
| C9 | Cấu hình tách khỏi bản build; GitOps | OPS-01; ba tệp values (mục 2.1); ESO | Cùng một digest chạy ở staging và sản xuất; Argo CD không có ứng dụng lệch trạng thái |
| C10 | Chuỗi cung ứng có kiểm soát | Mục 9.6; SEC-37, SEC-38, SEC-23 | Provenance SLSA, chữ ký cosign của mọi ảnh đang chạy; nhật ký từ chối của Kyverno |
| C5 (nhóm TCVN "Sao lưu dự phòng", theo ánh xạ của bản đánh giá) | Kế hoạch DR, sao lưu | Chương 8; SEC-44; DR-01 đến DR-16; OPS-18 | Biên bản diễn tập DR-10; `backup_restore_check_success` |

## Trục D: an toàn thông tin

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| D1 | Không có thông tin xác thực trong mã nguồn | SEC-01, SEC-09, SEC-10, SEC-12 | gitleaks 0 phát hiện trên toàn lịch sử; mọi Secret có nguồn ESO hoặc OpenBao động |
| D2 | MFA, chống dò mật khẩu | SEC-13, SEC-14, SEC-54 | Cấu hình Keycloak trong Git; tỷ lệ người dùng có MFA 100%; phép thử dò mật khẩu bị khoá sau 5 lần |
| D3 | Không chức năng ẩn, không bỏ qua xác thực | SEC-02, SEC-03, SEC-28 | Retest xâm nhập; ZAP thử biến thể đường dẫn; rà soát `ApisixRoute` công khai |
| D4 | Lưu mật khẩu bằng hàm băm có salt | SEC-04, SEC-15 | Thuật toán băm trong Keycloak là Argon2id; không có mật khẩu ở client |
| D5 | Quản lý phiên an toàn | SEC-08, SEC-16, SEC-17 | Access 5 phút, refresh 8 giờ; thu hồi có hiệu lực ≤ 5 s qua `iam.auth.event.v1` |
| D6 | Kiểm soát truy cập tập trung | SEC-18, SEC-29, SEC-42, SEC-58 | `opa test` 100%; nhật ký từ chối; báo cáo phiên PAM |
| D7 | Chống injection, XSS, CSRF, CORS sai | SEC-05, SEC-06, SEC-19, SEC-34, SEC-39, SEC-40, SEC-47, SEC-48 | Semgrep 0 phát hiện; ZAP 0 High; sqlmap 0 |
| D8 | Mật mã và kiểm tra chứng thư đúng chuẩn | SEC-11, SEC-20, SEC-21, SEC-31, SEC-56, SEC-57, SEC-59, SEC-60 | Semgrep quy tắc mật mã; kiểm tra TLS endpoint; kube-bench etcd |
| D9 | Nhật ký an toàn, không chứa dữ liệu nhạy cảm | SEC-07, SEC-22, SEC-30, SEC-43, SEC-50, SEC-53 | Truy vấn Loki tìm mẫu JWT, CCCD: 0; SIEM nhận đủ luồng |
| D10 | Thành phần không có lỗ hổng đã biết | SEC-23, SEC-37, SEC-51, SEC-55; OPS-26 | Dependency-Track 0 Critical quá hạn 7 ngày |
| D11 | Header bảo mật HTTP | SEC-24, SEC-19 (CSP) | ZAP kiểm tra header; báo cáo CSP |
| D12 | Mã hoá đầu cuối dữ liệu mật | SEC-25, SEC-11, SEC-52, SEC-58 | PERF-15; phép thử bắt gói chỉ thấy bản mã |

## Trục E: công nghệ

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| E1 | Runtime, framework còn được hỗ trợ | Kubernetes n-1 (mục 3.1, OPS-15); ảnh nền Java 21; OPS-26 | Bảng phiên bản thành phần cập nhật hằng quý |
| E2 | CSDL hợp đa khách thuê, cài cách ly | PostgreSQL CNPG (mục 4.5); ba mô hình pool, bridge, silo; bản cách ly (mục 9.9) | Cùng chart chạy ở ba mô hình; onboarding OPS-07 |
| E3 | Message broker cho đỉnh tải | Kafka Strimzi (mục 4.6); KEDA theo độ trễ consumer (mục 6.3) | PERF-04, PERF-19: tồn đọng được xử lý hết |
| E4 | Kho đối tượng cho minh chứng | Ceph RGW, Object Lock (mục 3.8, 8.4) | PERF-18; RGW từ chối ghi đè |
| E5 | Cache phân tán có thiết kế | Valkey Cluster (mục 4.6), tiền tố khoá theo tenant, không C2 dạng rõ | Kiểm tra khoá mẫu trong Valkey |
| E6 | Thành phần không hết hỗ trợ, giấy phép phù hợp | Danh sách chặn Trivy, Dependency-Track (mục 9.6); quy tắc AGPL (mục 10.1) | Báo cáo giấy phép từ SBOM |
| E7 | Năng lực đã kiểm chứng ≥ tải mục tiêu | Chương 4 và 7; PERF-01 đến PERF-26 | Báo cáo PERF-03, PERF-04, PERF-06 |
| E8 | Thành phần AI, BI theo kiến trúc đích | vLLM, GPU (mục 4.9); ClickHouse, Cube (mục 4.6) | PERF-24, PERF-26 |

## Trục F: chịu lỗi

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| F1 | Ngắt mạch khi phụ thuộc lỗi | Resilience4j qua `vt-platform-starter` (TL-01); dashboard (mục 10.6) | Chỉ số circuit breaker 100%; PERF-08 |
| F2 | Timeout cho mọi lời gọi ra ngoài | Timeout chuẩn ở thư viện; `statement_timeout`, `lock_timeout` (OPS-09); APISIX upstream timeout | Kiểm thử tích hợp với phụ thuộc chậm |
| F3 | Retry có kiểm soát và idempotent | `Idempotency-Key`, consumer idempotent theo `eventId`; topic `.retry` (OPS-24) | Script k6 (mục 7.7); PERF-17 không ký trùng |
| F4 | Giới hạn tốc độ, cắt tải | APISIX `limit-req`, `limit-count` (mục 6.8); mức suy giảm (mục 6.9) | PERF-06: 429, 503 có `Retry-After`, tự phục hồi |
| F5 | Cô lập tài nguyên theo phụ thuộc | Bulkhead trong thư viện; node pool tách (mục 3.3); PgBouncer theo module | PERF-06: thành phần gãy đầu tiên không kéo theo thành phần khác |
| F6 | Giới hạn kích thước kết quả, upload | `limit` ≤ 200; SEC-48; xử lý dạng luồng | PERF-18: bộ nhớ không tăng theo kích thước tệp |
| F7 | Pool kết nối định cỡ theo tải | Mục 4.7: ngân sách kết nối | Tổng kết nối phía máy chủ ≤ ngân sách ở PERF-03 |
| F8 | Xử lý nền cho tác vụ nặng | Kafka, KEDA, API 202 (mục 6.3, SLO-ASYNC) | PERF-19, PERF-20; SLO-ASYNC |
| F9 | Kiểm soát ghi đồng thời | `If-Match`, ETag (đã đạt; giữ nguyên) | Script k6 đếm 409/412 |
| F10 | Xử lý lỗi chuẩn hoá | RFC 9457 (TL-01); cảnh báo theo tỷ lệ 5xx | ZAP không thấy stack trace |
| F11 | Kích thước đơn vị mã hợp lý | Thuộc TL-01; TL-04 đóng góp Semgrep, ArchUnit trong pipeline | Báo cáo pipeline |

## Trục G: nhật ký

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| G1 | Thu gom log tập trung | Fluent Bit DaemonSet (mục 10.3) | 100% namespace có log trong Loki |
| G2 | Log có cấu trúc | JSON ECS | Truy vấn LogQL theo trường |
| G3 | Liên kết log với trace | `trace.id` mọi dòng; structured metadata | Từ trace mở được log |
| G4 | Không mất log khi tải cao | Bộ đệm đĩa, `Retry_Limit no_limits` (mục 10.3) | Phép thử chặn Loki 30 phút: sai lệch 0 |
| G5 | Lưu giữ và bảo vệ log | 90 ngày nóng, 1 năm lạnh, kiểm toán 10 năm Object Lock (NFR-09) | Cấu hình retention; Object Lock compliance |
| G6 | Tách audit log | `sys.audit.v1`, `audit-archiver` (mục 8.3, 10.3) | Kho kiểm toán độc lập |
| G7 | Không ghi dữ liệu nhạy cảm vào log | SEC-07, SEC-53 | Truy vấn định kỳ 0 kết quả |

## Trục B: khả năng quan sát

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| B1 | Tracing phân tán end-to-end | OTel agent, Collector, Tempo (mục 10.2) | Trace liền mạch trong PERF-01 |
| B2 | Lan truyền ngữ cảnh | W3C Trace Context, header Kafka, outbox | `trace_completeness_ratio` ≥ 0,99 |
| B3 | Endpoint metrics | Micrometer, ServiceMonitor (mục 10.4) | Target up 100% |
| B4 | SLI, SLO | Mục 7.1, 10.4, 10.5 | Dashboard SLO; cảnh báo burn rate |
| B5 | Giám sát hiệu năng ứng dụng | Tempo, exemplar, dashboard (mục 10.6) | Từ cảnh báo tới trace ≤ 3 thao tác |
| B6 | Health check | Probe cổng 8081, Kyverno | Kyverno 0 vi phạm |
| B7 | Truy vấn chậm | `pg_stat_statements`, `auto_explain` (mục 10.4) | Dashboard truy vấn chậm |
| B8 | Giám sát phía người dùng | Grafana Faro, OTel Dart (mục 10.6) | SLO-PAGE, SLO-LCP |

## Trục J: kiểm thử

| Tiêu chí | Nội dung | Biện pháp TL-04 | Bằng chứng nghiệm thu |
|---|---|---|---|
| J1 | Mật độ unit test | Pipeline chạy kiểm thử đơn vị, độ bao phủ tệp thay đổi ≥ 80% (mục 9.6) | Báo cáo JaCoCo |
| J2 | CI chặn khi test lỗi | Jenkinsfile (mục 9.6) | Merge request lỗi bị chặn |
| J3 | Ngưỡng độ phủ | `recordCoverage` với cổng chất lượng | Báo cáo pipeline |
| J4 | Test tích hợp tự động | Testcontainers PostgreSQL, Kafka; chạy qua PgBouncer thật | Báo cáo failsafe |
| J5 | Test đầu cuối | Bộ kiểm thử khói trên staging, sau DR (DR-10 bước 9) | Báo cáo kiểm thử khói |
| J6 | Bộ test vàng do nghiệp vụ ký | OPS-10 | Test vàng 100% |
| J7 | Thử tải định kỳ | Chương 7: lịch chạy mỗi phát hành và mỗi quý | Báo cáo trong repo `perf-results` |
| J8 | Phân tích tĩnh | Semgrep, gitleaks, Trivy (SEC-39, SEC-09, SEC-23) | SARIF trong pipeline |
| J9 | Tài liệu kiến trúc khớp mã | Kho GitOps là nguồn sự thật của triển khai; tài liệu TL-04 dẫn chiếu mã | Rà soát hằng quý theo OPS-25 |

## Góp ý của lãnh đạo và chỉ tiêu NFR

Ba góp ý của lãnh đạo được TL-01 phân tích; TL-04 cung cấp phép đo kiểm chứng:

| Góp ý | Nội dung | Kiểm chứng trong TL-04 | Bằng chứng |
|---|---|---|---|
| Góp ý G1 | Giao diện riêng theo tenant (SDUI, micro-frontend) | PERF-21; OPS-11; CDN (mục 7.2) | LCP ≤ 2,5 s; UI Pack ≤ 30 KB; phát hành UI Pack ≤ 1 ngày |
| Góp ý G2 | Nghẽn engine BPMN với nhiều tenant | PERF-11, PERF-12, PERF-13; workflow api và executor tách (mục 6.6) | 150 user task/s đạt NFR-03; ≤ 120 định nghĩa với 1.000 tenant; ACT_RU ≤ 60 GB |
| Góp ý G3 | Hiệu năng bảy lớp bảo vệ và mã hoá | PERF-14 đến PERF-18, PERF-25 | Bảng phân rã độ trễ theo lớp; tổng thêm ≤ 10 ms p95 |

Ánh xạ chỉ tiêu phi chức năng của baseline:

| NFR | Biện pháp TL-04 | Bằng chứng |
|---|---|---|
| NFR-01 6 triệu tài khoản, 300.000 CCU, 5.000 tenant | Chương 2, 4; OPS-23 | PERF-03; bảng định cỡ mục 4.2 |
| NFR-02 15.000 RPS, 30.000 đột biến | Chương 4, 6 | PERF-03, PERF-04, PERF-06 |
| NFR-03 Độ trễ | Mục 7.1, 7.2 | SLO-READ, SLO-WRITE, SLO-ASYNC, SLO-PAGE, SLO-LCP |
| NFR-04 Khả dụng | Chương 3, 6; SLO-AVL | Báo cáo SLO hằng tháng |
| NFR-05 Khôi phục | Chương 8 | DR-04, DR-10 biên bản |
| NFR-06 Đỉnh cuối kỳ | Mục 4.3, 6.4 | PERF-19, PERF-20 |
| NFR-07 Linh hoạt | OPS-07, OPS-10, OPS-11 | Thời gian quy trình ghi trong pipeline |
| NFR-08 An toàn cấp độ 4, ASVS | Chương 9 | Hồ sơ cấp độ; báo cáo ASVS |
| NFR-09 Lưu giữ | Mục 8.9, 10.3 | Cấu hình retention, Object Lock |
| NFR-10 Quan sát | Mục 10.2, 10.4 | `trace_completeness_ratio`; SLI mọi API chính |
| NFR-11 Trải nghiệm | OPS-11 (axe-core) | Báo cáo WCAG tự động |

Ma trận này là đầu vào cho lần chấm lại checklist sau mỗi đợt. Một tiêu chí chỉ được chấm "đạt" khi có bằng chứng ở cột cuối, không chỉ khi có thiết kế; tiêu chí đã có thiết kế nhưng chưa có bằng chứng được chấm "một phần".
