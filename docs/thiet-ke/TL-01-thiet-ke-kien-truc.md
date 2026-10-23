---
title: "Thiết kế kiến trúc chi tiết"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Mã tài liệu TL-01 · Phiên bản 1.1 · 10/2026"
toc-title: "Mục lục"
---

# Thông tin tài liệu {-}

## Kiểm soát phiên bản {-}

| Phiên bản | Ngày | Người thực hiện | Nội dung thay đổi |
|---|---|---|---|
| 0.1 | 12/10/2026 | Nhóm kiến trúc nền tảng | Khung tài liệu, danh mục thành phần, sơ đồ ngữ cảnh và container |
| 0.5 | 16/10/2026 | Nhóm kiến trúc nền tảng | Thiết kế chi tiết SVC-01 đến SVC-18, ba chuyên đề G1, G2, G3 |
| 0.9 | 18/10/2026 | Nhóm kiến trúc nền tảng | Ma trận truy vết 110 tiêu chí, phụ lục ADR, ngăn xếp công nghệ, danh mục API |
| 1.0 | 19/10/2026 | Kiến trúc sư trưởng | Bản trình duyệt cùng TL-02, TL-03, TL-04 |
| 1.1 | 23/10/2026 | Nhóm kiến trúc nền tảng | Cập nhật theo thực tế triển khai đợt 1: thêm ADR-32, ADR-33 vào Phụ lục A; Phụ lục D ghi điểm lệch và quyết định; nhập BS-01, BS-02, BS-03 thành Phụ lục E, F, G; sửa tại chỗ mục hợp đồng API, saga chốt kỳ, khởi tạo luồng duyệt, Phụ lục B và C |

## Tài liệu liên quan {-}

| Mã | Tên tài liệu | Quan hệ với TL-01 |
|---|---|---|
| TL-01 | Thiết kế kiến trúc chi tiết (tài liệu này) | Xác định thành phần, kiểu kiến trúc từng phần, giao tiếp, quyết định kiến trúc và truy vết checklist |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện | Cung cấp quy trình QT-00 đến QT-18 và màn hình SCR mà các API, module trong TL-01 phục vụ |
| TL-03 | Thiết kế cơ sở dữ liệu và đồng bộ dữ liệu | Chi tiết hoá schema, bảng, chỉ mục, phân vùng, RLS và cơ chế đồng bộ mà TL-01 nêu ở mức kiến trúc |
| TL-04 | Triển khai, hiệu năng, dự phòng và ATTT | Hiện thực hoá TL-01 trên Kubernetes, kiểm chứng các con số ước tính bằng kịch bản thử tải, biện pháp ATTT, DR |
| ĐV-01 | Đánh giá kiến trúc công nghệ, 10/2026 (29 trang, 110 tiêu chí) | Checklist đánh giá mà TL-01 phải đáp ứng; nguồn hiện trạng và lộ trình đợt 0a đến 3 |
| ĐV-02 | Báo cáo Ban Giám đốc "Nền tảng đánh giá cán bộ theo OKR và KPI", 10/2026 (74 slide) | Mục tiêu sản phẩm, tám nhóm tính năng, kiến trúc định hướng slide 35 đến 45, phụ lục A1 đến A6 |

Tài liệu này dùng thống nhất các mã định danh của tệp cơ sở thiết kế chung (baseline): ADR, SVC, MOD, EVT, QT, SCR, NFR, tên schema và tên bảng. Các mã mới do TL-01 bổ sung nằm trong dải ADR-21 đến ADR-40 và được liệt kê ở Phụ lục A.

# Giới thiệu

## Mục đích

Tài liệu này mô tả kiến trúc chi tiết của Nền tảng đánh giá cán bộ theo OKR và KPI, sản phẩm mà Viettel xây dựng để phục vụ việc giao nhiệm vụ, theo dõi kết quả và đánh giá, xếp loại cán bộ theo QĐ 366-QĐ/TW, HD 02-HD/BTCTW, dự thảo Quy định mới của Đảng và theo luồng Nhà nước tại NĐ 335/2025, NĐ 233/2026. Trong giao tiếp hằng ngày, người dùng gọi chung các căn cứ này là "Thông tư 22 và QĐ 366"; tài liệu giữ cách gọi chính xác theo văn bản để tránh nhầm lẫn khi truy vết quy tắc.

Mục đích thứ nhất của tài liệu là xác định hệ thống gồm những thành phần nào, mỗi thành phần chịu trách nhiệm gì, sở hữu dữ liệu nào và giao tiếp với thành phần khác ra sao. Mục đích thứ hai là giải thích vì sao mỗi phần của hệ thống được chọn một kiểu kiến trúc riêng, chứ không áp một kiểu chung cho toàn bộ. Một nền tảng vừa phải bảo đảm tính nhất quán pháp lý của kết quả đánh giá, vừa phải chịu đỉnh tải dồn vào vài ngày cuối quý, vừa phải cài được ở mạng cách ly cho lực lượng vũ trang thì không thể có một khuôn mẫu duy nhất đúng cho mọi phần. Mục đích thứ ba là trả lời có lý lẽ và có số liệu ba góp ý của lãnh đạo: giao diện riêng theo tenant (G1), nguy cơ nghẽn engine BPMN khi số tenant lên tới hàng nghìn (G2), và chi phí hiệu năng của bảy lớp bảo vệ cùng các giải pháp mã hoá dữ liệu mật (G3). Mục đích cuối cùng là chứng minh thiết kế đáp ứng đủ 110 tiêu chí của bản đánh giá kiến trúc công nghệ 10/2026, thông qua ma trận truy vết ở chương cuối.

## Phạm vi

Tài liệu bao phủ toàn bộ nền tảng ở mức kiến trúc phần mềm và kiến trúc giải pháp, gồm mười tám thành phần SVC-01 đến SVC-18, tám module của lõi nghiệp vụ MOD-01 đến MOD-08, hai mươi lăm sự kiện EVT-01 đến EVT-25, mô hình cell và ba mô hình thuê bao, kiến trúc tích hợp với các hệ thống ngoài, kiến trúc bảo mật, quan sát, AI, kiểm thử và chuyển giao liên tục. Với mỗi thành phần, tài liệu nêu đến mức mà đội phát triển có thể bắt tay vào hiện thực: cấu trúc gói mã, endpoint chính, cấu hình mẫu, cách co giãn, cách chịu lỗi.

Một số nội dung được nêu ở mức kiến trúc trong tài liệu này nhưng được chi tiết hoá ở tài liệu khác. Định nghĩa đầy đủ từng bảng, cột, chỉ mục, phân vùng và chính sách bảo mật theo dòng (Row-Level Security, RLS) thuộc TL-03. Trình tự thao tác của người dùng, màn hình và quy tắc nghiệp vụ (BR) thuộc TL-02. Cấu hình Kubernetes, định cỡ phần cứng theo cell, kịch bản thử tải (PERF), kịch bản khôi phục thảm hoạ (DR) và biện pháp an toàn thông tin ở mức vận hành (SEC) thuộc TL-04. Khi một nội dung đã có ở tài liệu khác, TL-01 chỉ dẫn chiếu, không lặp lại.

Tài liệu không bao gồm thiết kế hạ tầng vật lý của trung tâm dữ liệu, hồ sơ đề xuất cấp độ an toàn hệ thống thông tin chính thức, và thiết kế mật mã chuyên dùng của Ban Cơ yếu Chính phủ cho bản cài cách ly. Với nội dung cuối, tài liệu chỉ định nghĩa điểm cắm (lớp trừu tượng `CryptoProvider`) để bộ mật mã chuyên dùng thay thế bộ mật mã dân sự mà không phải sửa mã nghiệp vụ.

## Đối tượng đọc

Người đọc chính là kiến trúc sư trưởng phía Viettel, người phê duyệt các quyết định kiến trúc. Với người đọc này, các chương 2, 3, 7, 8, 9 và Phụ lục A là trọng tâm vì chúng chứa lập luận lựa chọn và số liệu định lượng.

Người đọc thứ hai là trưởng nhóm phát triển backend, frontend, di động và dữ liệu. Chương 4, 5, 6, 10 đến 14 và Phụ lục C cung cấp cho họ cấu trúc mã, hợp đồng API, sự kiện và quy ước bắt buộc. Người đọc thứ ba là nhóm vận hành và an toàn thông tin, cần đọc chương 12 đến 18 trước khi đọc TL-04. Người đọc thứ tư là nhóm đánh giá độc lập, dùng chương 20 để đối chiếu từng tiêu chí của bản đánh giá với mục đáp ứng.

Tài liệu giả định người đọc nắm được các khái niệm phổ biến của kiến trúc phần mềm hiện đại như Spring Boot, Kubernetes, Kafka, PostgreSQL, OAuth 2.0 và OpenID Connect. Các khái niệm đặc thù hơn, như bảng chuyển trạng thái tham số hoá, chuỗi băm phân vùng hay mã hoá phong bì, được giải thích tại chỗ khi xuất hiện lần đầu.

## Thuật ngữ và chữ viết tắt

Bảng dưới đây liệt kê các thuật ngữ và chữ viết tắt dùng xuyên suốt tài liệu. Khi một thuật ngữ tiếng Anh có cách dịch tiếng Việt ổn định, tài liệu dùng tiếng Việt và ghi tiếng Anh trong ngoặc ở lần xuất hiện đầu tiên của mỗi chương lớn.

| Thuật ngữ | Giải thích |
|---|---|
| ABAC | Kiểm soát truy cập theo thuộc tính (Attribute-Based Access Control), ở đây là thuộc tính cây tổ chức, luồng, lớp dữ liệu |
| ACL | Lớp chống ô nhiễm (Anti-Corruption Layer), dịch mô hình của hệ ngoài sang mô hình chuẩn của nền tảng |
| ADR | Bản ghi quyết định kiến trúc (Architecture Decision Record) |
| BPMN, DMN | Ký pháp mô hình hoá quy trình nghiệp vụ và bảng quyết định của OMG |
| CCU | Số người dùng đồng thời (Concurrent Users) |
| CDC | Bắt thay đổi dữ liệu (Change Data Capture), ở đây đọc WAL của PostgreSQL bằng Debezium |
| CEL | Ngôn ngữ biểu thức Common Expression Language, dùng cho công thức và điều kiện kiểm tra |
| Cell | Đơn vị triển khai độc lập gồm đầy đủ dịch vụ, CSDL, Kafka và kho tệp, phục vụ một nhóm tenant |
| CQRS | Tách mô hình ghi và mô hình đọc (Command Query Responsibility Segregation) |
| DEK, KEK | Khoá mã hoá dữ liệu (Data Encryption Key) và khoá bọc khoá (Key Encryption Key) |
| E2EE | Mã hoá đầu cuối (End-to-End Encryption) |
| EVT | Mã sự kiện nghiệp vụ trong danh mục baseline |
| HSM | Mô-đun an ninh phần cứng (Hardware Security Module) |
| KR, O | Kết quả then chốt và mục tiêu then chốt theo dự thảo Quy định mới |
| MOD | Mã module trong lõi nghiệp vụ mission-core |
| NFR | Chỉ tiêu phi chức năng trong baseline |
| Outbox, Inbox | Mẫu hộp thư đi giao dịch (Transactional Outbox) và hộp thư đến idempotent (Idempotent Inbox) |
| PL 4 | Phụ lục 4 của dự thảo Quy định mới, quy định yêu cầu với nền tảng số |
| Policy Pack | Gói quy tắc có phiên bản chứa công thức, bảng xếp loại, test case vàng, được ký số |
| RLS | Bảo mật theo dòng (Row-Level Security) của PostgreSQL |
| RPO, RTO | Lượng dữ liệu tối đa có thể mất và thời gian khôi phục tối đa |
| Saga | Chuỗi giao dịch cục bộ có hành động bù trừ, dùng thay giao dịch phân tán |
| SDUI | Giao diện điều khiển từ máy chủ (Server-Driven UI) |
| SLI, SLO | Chỉ số mức dịch vụ và mục tiêu mức dịch vụ |
| SVC | Mã thành phần triển khai trong baseline |
| Tenant | Khách thuê, một đơn vị khách hàng có cấu hình, dữ liệu và quy tắc riêng |
| UI Pack | Gói cấu hình giao diện có phiên bản của tenant: token, bố cục, biểu mẫu, plugin |
| WORM | Ghi một lần, đọc nhiều lần (Write Once Read Many) |

Chữ viết tắt văn bản pháp lý giữ nguyên theo số hiệu: NĐ là nghị định, QĐ là quyết định, HD là hướng dẫn, TT là thông tư. Bảng xếp loại dùng bốn mức chuẩn `HTXSNV`, `HTTNV`, `HTNV`, `KHTNV`, tương ứng hoàn thành xuất sắc, hoàn thành tốt, hoàn thành và không hoàn thành nhiệm vụ.

## Tài liệu liên quan

Tài liệu dựa trên hai đầu vào chính. Bản đánh giá kiến trúc công nghệ 10/2026 đối chiếu năm repo hiện tại (backend-missions, dhtn_fe, backend2.0, web-spring, twd_mission) với mục tiêu nền tảng, cho điểm đáp ứng tổng hợp 17,4/100 trên 12 trục và nêu 64 vấn đề, trong đó 12 vấn đề nghiêm trọng. Đây là checklist mà thiết kế phải đáp ứng. Báo cáo Ban Giám đốc 10/2026 mô tả mục tiêu sản phẩm, tám nhóm tính năng, kiến trúc định hướng ở các slide 35 đến 45 và các phụ lục A1 đến A6 về tính năng, quy tắc chấm điểm, hai luồng đánh giá và tám nhóm dữ liệu tối thiểu.

Bên cạnh hai đầu vào này, thiết kế tham chiếu các chuẩn sau: ISO/IEC/IEEE 42010:2022 cho mô tả kiến trúc; ISO/IEC 25010:2023 cho đặc tính chất lượng; OWASP ASVS 4.0.3 cho yêu cầu bảo mật ứng dụng; TCVN 11930:2017, NĐ 85/2016/NĐ-CP và TT 12/2022/TT-BTTTT cho bảo đảm an toàn hệ thống thông tin theo cấp độ; Luật Giao dịch điện tử 20/2023/QH15 cho chữ ký số; RFC 3161 cho dấu thời gian; RFC 9457 cho định dạng lỗi; RFC 9700 cho thực hành tốt OAuth 2.0; OpenTelemetry, W3C Trace Context, CloudEvents 1.0, AsyncAPI 3.0 và OpenAPI 3.1 cho quan sát và hợp đồng tích hợp; NĐ 47/2020/NĐ-CP, NĐ 69/2024/NĐ-CP và Luật Dữ liệu 60/2024/QH15 cho kết nối, chia sẻ dữ liệu và định danh điện tử. Số hiệu văn bản pháp quy được trích theo hiểu biết tại thời điểm viết; trước khi phát hành chính thức cần đối chiếu bản gốc và văn bản thay thế nếu có, như bản đánh giá đã lưu ý.

# Động lực kiến trúc

Chương này trình bày những yếu tố quyết định hình dạng của kiến trúc: đặc tính riêng của bài toán đánh giá cán bộ, các chỉ tiêu phi chức năng NFR-01 đến NFR-11, ước tính tải theo Little's Law, khoảng cách giữa hiện trạng và mục tiêu theo 12 trục của bản đánh giá, và các ràng buộc pháp lý. Mọi lựa chọn ở các chương sau đều quay về một hoặc nhiều yếu tố trong chương này.

## Đặc tính hệ thống

### Nhất quán pháp lý

Kết quả đánh giá, xếp loại cán bộ là căn cứ cho bố trí, quy hoạch, thay thế cán bộ ở luồng Đảng, và cho thu nhập tăng thêm, tiền thưởng ở luồng Nhà nước. Vì vậy một kết quả đã quyết định phải tái hiện được tại mọi thời điểm sau này, kể cả khi bộ quy tắc đã đổi, cây tổ chức đã sáp nhập, hay cán bộ đã điều động. PL 4 của dự thảo Quy định mới yêu cầu nhật ký không sửa được, giá trị trước và sau của mọi thay đổi, khoá và mở kỳ có phê duyệt, cùng giới hạn rõ ràng cho AI.

Hệ quả kiến trúc là các bước chốt có giá trị pháp lý như quyết định xếp loại, khoá kỳ, xác nhận minh chứng phải chạy trong một giao dịch ACID cùng với việc ghi sự kiện tương ứng. Nếu tách các bước này ra nhiều service với nhiều CSDL thì phải dùng giao dịch phân tán hoặc saga, và khi đó trạng thái trung gian không nhất quán có thể quan sát được, đúng loại rủi ro mà một hồ sơ khiếu nại sẽ khai thác. Đây là lý do mạnh nhất để giữ lõi nghiệp vụ trong một tiến trình Modular Monolith.

### Quy định đổi theo quý

Bộ dự thảo bản 15/9/2026 còn ít nhất bảy điểm chưa thống nhất mà Báo cáo BGĐ liệt kê ở Phụ lục A4, như cách tính chất lượng, ngày chậm theo ngày lịch hay ngày làm việc, cách hiểu điều kiện "30% vượt mục tiêu". Ngưỡng HTNV trong mã hiện tại là 50 điểm trong khi dự thảo của Đảng là 60; năm tiêu chí chung đã thành chín nhóm. Sau khi văn bản ban hành, hướng dẫn thực hiện tiếp tục được điều chỉnh theo từng đợt.

Hệ quả kiến trúc có hai mặt. Mặt thứ nhất là mọi thứ thay đổi theo văn bản phải nằm ngoài mã nguồn, trong Policy Pack có phiên bản, kiểm thử được và ký số được. Mặt thứ hai là ranh giới giữa các miền nghiệp vụ chưa ổn định: việc OKR mới được thêm vào, cách liên thông quý với năm, cách tách tiêu chí chung khỏi thang 100 có thể dịch chuyển trách nhiệm giữa các module. Khi ranh giới chưa ổn định, chi phí điều chỉnh ranh giới giữa hai package trong cùng tiến trình thấp hơn nhiều lần so với giữa hai microservice có CSDL riêng, nên lõi được giữ là một Modular Monolith cho tới khi ranh giới ổn định ít nhất hai quý.

### Ba mô hình triển khai

Báo cáo BGĐ ở slide 40 và 52 yêu cầu một mã nguồn cho ba cách cài: SaaS trên Viettel Cloud, đám mây riêng của tỉnh hoặc bộ, và cài đặt cách ly mạng cho lực lượng vũ trang và công an. Bản cách ly được cập nhật bằng gói ký số chuyển tay qua trạm kiểm định một chiều, chạy trên hạ tầng nhỏ hơn nhiều so với một cell SaaS, và không có đội vận hành chuyên sâu tại chỗ.

Hệ quả kiến trúc là số tiến trình cần cài ở bản nhỏ nhất phải ít, mọi thành phần phải chạy được không cần Internet, không dùng dịch vụ đám mây độc quyền, và mọi giấy phép phải cho phép phân phối bản cài. Kiến trúc chọn "một mã, hai cách đóng gói": cùng mã nguồn nhưng ở bản cách ly, các service điểm nóng như scoring-service hay notification-service có thể chạy với một replica và cấu hình tài nguyên nhỏ, còn các thành phần phân tích nặng như ClickHouse có thể tắt bằng cờ tính năng và thay bằng truy vấn trên bản sao đọc.

### Tải dồn cuối kỳ

Tải không phân bố đều. Báo cáo quý phải nộp trước ngày 10 tháng đầu quý, hồ sơ năm trước ngày 01/02. Bản đánh giá giả định 30% hồ sơ nộp trong ba ngày cuối, hệ số giờ cao điểm 4. Vào những ngày này, tính điểm, nộp minh chứng, sinh biểu mẫu, ký số và thông báo đồng loạt tăng vọt, trong khi các chức năng khác như cấu hình, danh mục gần như không đổi.

Hệ quả kiến trúc là những khối có hồ sơ tải khác biệt rõ phải co giãn độc lập và được san tải qua hàng đợi. Đây là lý do tách scoring-service, evidence-service, notification-service, document-service thành microservice có KEDA co giãn theo độ trễ hàng đợi, và là lý do tính điểm theo kiểu tính dần theo sự kiện thay vì dồn vào cuối kỳ.

### Đa ngành

Bảy nhóm khách hàng (khối Đảng, chính quyền, sự nghiệp, trường học, bệnh viện, LLVT và công an, doanh nghiệp) khác nhau ở khung pháp lý, thang điểm, chu kỳ, hạ tầng và tích hợp đặc thù, như bảng ở slide 23 của Báo cáo BGĐ. Cùng một cán bộ có thể vừa là đảng viên vừa là công chức, nên bị đánh giá theo hai thang khác nhau trên cùng dữ liệu sản phẩm.

Hệ quả kiến trúc là khác biệt giữa các nhóm khách hàng phải đi qua đúng ba cửa: Policy Pack cho quy tắc, plugin qua điểm mở rộng có hợp đồng API cho nghiệp vụ chuyên ngành, và bộ kết nối cho tích hợp đặc thù. Không có nhánh mã nguồn riêng cho từng ngành.

### Quy mô quốc gia

Quy mô thiết kế là 6 triệu tài khoản, 300.000 người dùng đồng thời, tối đa 5.000 tenant, từ một chi bộ vài chục đảng viên đến một thành phố hơn một triệu tài khoản. Một sự cố ở một tỉnh không được phép làm dừng cả nước.

Hệ quả kiến trúc là Cell-based Architecture: chia khách hàng vào các cell độc lập, mỗi cell có đầy đủ dịch vụ, CSDL, Kafka, kho tệp, khoá mã hoá riêng; phạm vi ảnh hưởng của một sự cố bị giới hạn trong một cell. Vùng điều phối toàn cục chỉ giữ danh bạ tenant, định tuyến và kho gốc cấu hình, không nằm trên đường xử lý dữ liệu nghiệp vụ.

## Chỉ tiêu phi chức năng

Bảng dưới đây nhắc lại các chỉ tiêu phi chức năng đã chốt trong baseline và chỉ ra chương của TL-01 trình bày giải pháp đáp ứng. Giá trị trong bảng là giá trị thiết kế, được TL-04 kiểm chứng bằng kịch bản thử tải và diễn tập.

| Mã | Chỉ tiêu | Giá trị thiết kế | Giải pháp chính trong TL-01 |
|---|---|---|---|
| NFR-01 | Quy mô | 6 triệu tài khoản, 300.000 CCU, tối đa 5.000 tenant | Cell-based, ba mô hình thuê bao (chương 4, 12) |
| NFR-02 | Thông lượng | 15.000 req/s đỉnh thường xuyên, 30.000 req/s đột biến, đọc/ghi 80/20 | Co giãn ngang theo cell, cache, CQRS, san tải (chương 13) |
| NFR-03 | Độ trễ | Đọc p95 ≤ 300 ms, ghi p95 ≤ 800 ms, trang p95 ≤ 1 s, LCP ≤ 2,5 s trên 4G | Ngân sách độ trễ, tác vụ nặng chạy nền (chương 9, 13) |
| NFR-04 | Khả dụng | 99,9% mỗi cell SaaS, 99,95% vùng toàn cục | Đa vùng sẵn sàng, PDB, chịu lỗi (chương 14) |
| NFR-05 | Khôi phục | RPO ≤ 15 phút, RTO ≤ 4 giờ khi mất TTDL; RPO ≈ 0, RTO ≤ 5 phút khi mất node CSDL | CloudNativePG đồng bộ trong TTDL, sao chép liên TTDL (chương 4, TL-04) |
| NFR-06 | Đỉnh cuối kỳ | Tính lại 1,2 triệu hồ sơ ≤ 2 giờ; sinh và ký 100.000 phiếu ≤ 4 giờ | Tính dần theo sự kiện, worker KEDA, ký lô (chương 8, 10) |
| NFR-07 | Linh hoạt | Tenant mới ≤ 1 ngày; Policy Pack ≤ 5 ngày; UI Pack ≤ 1 ngày | Khởi tạo tenant tự động, Policy Pack, UI Pack (chương 7, 10, 12) |
| NFR-08 | An toàn | Hồ sơ đề xuất cấp độ 4; ASVS L2, L3 cho xác thực, phiên, mật mã | Zero trust, IAM, ABAC, mật mã theo lớp (chương 9, 16) |
| NFR-09 | Lưu giữ | Log ứng dụng 90 ngày nóng + 1 năm lạnh; kiểm toán, sổ cái ≥ 10 năm | Tách audit log, sổ cái, kho lạnh (chương 11, 15) |
| NFR-10 | Quan sát | ≥ 99% request có trace end-to-end; SLI Four Golden Signals | OpenTelemetry từ trình duyệt tới CSDL (chương 15) |
| NFR-11 | Trải nghiệm | WCAG 2.1 AA; chế độ Đảng viên cao tuổi; ≤ 3 chạm cho thao tác thường ngày | Design token, SDUI có giới hạn, Flutter (chương 7) |

Các chỉ tiêu này không độc lập với nhau. NFR-03 và NFR-08 kéo về hai hướng ngược nhau, vì mỗi lớp bảo vệ thêm vào đường xử lý đều tốn thời gian; chương 9 phân tích cụ thể sự đánh đổi này. NFR-07 và NFR-11 cũng vậy: cho tenant tự đổi giao diện nhiều đến đâu thì càng khó bảo đảm khả năng truy cập; chương 7 đặt giới hạn rõ ràng cho phần cấu hình được.

## Ước tính tải theo Little's Law

### Cách tính

Little's Law phát biểu rằng trong một hệ ổn định, số phần tử trung bình trong hệ L bằng tốc độ đến λ nhân thời gian lưu lại trung bình W, tức L = λ × W. Áp dụng cho người dùng tương tác, mỗi người dùng đồng thời luân phiên giữa thời gian suy nghĩ Z (đọc màn hình, nhập liệu) và thời gian chờ phản hồi R. Khi R rất nhỏ so với Z thì chu kỳ của một người dùng xấp xỉ Z, và tốc độ request toàn hệ là λ ≈ L / Z.

Với L = 300.000 người dùng đồng thời theo NFR-01 và giả định thời gian suy nghĩ trung bình giữa hai request của một phiên Z ≈ 20 giây, ta có λ = 300.000 / 20 = 15.000 request/giây. Đây là đỉnh thường xuyên tại cổng API. Ngày hạn nộp có đột biến, nên hệ số 2 được áp dụng, cho 30.000 request/giây là năng lực thiết kế. Đây là số ước tính dựa trên giả định, không phải số đo.

Tỷ lệ đọc/ghi 80/20 cho 15.000 × 0,2 = 3.000 request ghi mỗi giây, và 6.000 khi đột biến. Mỗi request ghi sinh trung bình 1,5 sự kiện nghiệp vụ (một thao tác cập nhật tiến độ sinh EVT-05; một thao tác hoàn thành sinh EVT-06 cùng một yêu cầu thông báo EVT-21), nên Kafka toàn quốc nhận khoảng 3.000 × 1,5 = 4.500 sự kiện/giây và 9.000 sự kiện/giây khi đột biến. Con số này nhỏ so với năng lực của một cụm Kafka ba broker, và lại được chia cho nhiều cell, nên Kafka không phải nút thắt.

### Phân bổ theo cell

Cell lớn nhất là CELL-HCM với khoảng 1,2 triệu tài khoản. Giả định 5% tài khoản đồng thời ở giờ cao điểm cho 60.000 CCU, tương ứng 60.000 / 20 = 3.000 request/giây, 6.000 khi đột biến, trong đó ghi chiếm 600 đến 1.200 request/giây. Đây là cell định cỡ chuẩn; các cell khác nhỏ hơn và được định cỡ theo tỷ lệ tài khoản (chi tiết trong TL-04, mục định cỡ theo cell).

### Tải luồng duyệt

Luồng duyệt có giá trị riêng vì nó quyết định cách dùng engine BPMN (chương 8). Mỗi người có khoảng 2 tiến trình duyệt mỗi quý (duyệt kế hoạch và hồ sơ đánh giá), mỗi tiến trình khoảng 4 user task, tổng cộng khoảng 8 user task mỗi người mỗi quý. Với 6 triệu người, ta có 12 triệu tiến trình và 48 triệu user task mỗi quý.

Nếu 30% số user task dồn vào ba ngày cuối, mỗi ngày 8 giờ làm việc, thì số lần hoàn thành user task trung bình trong ba ngày đó là 48.000.000 × 0,3 / (3 × 8 × 3.600) = 14.400.000 / 86.400 ≈ 167 lần/giây. Nhân hệ số giờ cao điểm 4, ta có khoảng 670 lần hoàn thành user task mỗi giây trên toàn quốc. Baseline phân bổ cho cell lớn khoảng 80 đến 110 lần/giây. Nếu chia đúng tỷ lệ tài khoản (1,2 / 6 = 20%) thì cell HCM nhận khoảng 133 lần/giây; tài liệu này dùng 150 lần/giây làm năng lực thiết kế cho workflow-service của cell lớn để bao cả hai cách ước tính và có biên an toàn. Chương 8 dùng con số này để định cỡ engine.

### Dung lượng minh chứng

Giả định mỗi người nộp 8 tệp minh chứng mỗi quý, trung bình 1 MB mỗi tệp, ta có 6.000.000 × 4 × 8 × 1 MB ≈ 192 TB dữ liệu gốc mỗi năm trước khi khử trùng theo SHA-256. Ceph lưu theo mã xoá (erasure coding) 4+2, tức hệ số dung lượng 1,5, nên dung lượng thô cần thêm mỗi năm khoảng 290 TB. Khử trùng theo mã băm giảm con số này trong thực tế vì nhiều minh chứng là cùng một văn bản được nhiều người cùng nộp; thiết kế không trừ phần này để giữ biên an toàn.

### So sánh với ước tính của bản đánh giá

Bản đánh giá ước tính khoảng 70.000 phiên đồng thời và 3.100 request/giây cho đỉnh cuối quý, chỉ tính 5,6 triệu đảng viên và chỉ tính thao tác nộp hồ sơ. Baseline chọn mục tiêu cao hơn khoảng 4,3 lần về phiên và 4,8 lần về thông lượng vì ba lý do: phạm vi gồm cả công chức, viên chức ngoài luồng Đảng; tính cả thao tác thường ngày như cập nhật tiến độ, xem bảng điều hành; và cần biên an toàn cho tăng trưởng. Năng lực đo được của hệ hiện tại là 50 người dùng đồng thời với thời gian xử lý khoảng 11,5 giây, tức thiết kế mới phải tăng năng lực khoảng bốn bậc độ lớn. Khoảng cách này không thể lấp bằng tối ưu cục bộ; nó đòi hỏi thay đổi cấu trúc, đúng như phần còn lại của tài liệu trình bày.

## Hiện trạng và khoảng cách theo 12 trục

Bản đánh giá cho điểm hiện trạng trên 12 trục, không trục nào đạt ngưỡng đáp ứng cơ bản 75%. Bảng dưới đây tóm tắt điểm hiện tại, phát hiện chính và hướng thiết kế đóng khoảng cách. Điểm đích là mục tiêu nền tảng ≥ 75 điểm tổng hợp như bảng chỉ số theo dõi của bản đánh giá.

| Trục | Điểm hiện tại | Phát hiện chính | Hướng đóng khoảng cách trong TL-01 |
|---|---|---|---|
| A. Kiến trúc phần mềm | 19% | Distributed Monolith, 23 bảng mới trong schema VOFFICE, lớp 22.798 dòng | Modular Monolith có schema riêng, ArchUnit, outbox, ADR (chương 3, 5) |
| L. Tương hợp, tích hợp | 21% | Tích hợp qua bảng dùng chung, chưa có cổng tích hợp | integration-gateway, ACL, CloudEvents, OpenAPI (chương 6) |
| B. Quan sát | 25% | Không có tracing, 0 SLI | OpenTelemetry, traceparent, SLO (chương 15) |
| C. Container, điều phối | 5% | SPOF một node, 0 probe, 0 HPA, pool 500/pod | Helm riêng, probe, HPA/KEDA, pool 20 đến 40/pod (chương 5, 13, TL-04) |
| D. An toàn thông tin | 17% | 5 lỗ hổng nghiêm trọng S01 đến S05 | Keycloak, OPA, OpenBao, mật mã chuẩn (chương 16) |
| E. Hiệu năng, mở rộng | 12% | 50 CCU, thiếu broker, kho đối tượng, kho phân tích | Kafka, Ceph, ClickHouse, cell (chương 4, 13) |
| F. Độ tin cậy, chất lượng mã | 18% | 0 circuit breaker, 31/33 kết nối thiếu timeout | Resilience4j chuẩn hoá, RFC 9457 (chương 14) |
| G. Log tập trung | 14% | DROP_OLDEST giữ khoảng 5%, log văn bản, ghi token | Log JSON ECS, Fluent Bit đệm đĩa, tách audit (chương 15) |
| H. Phạm vi chức năng | 31% | Không có OKR, ba bộ máy tính điểm song song | Tám module, Policy Pack, hai luồng (chương 5, 10) |
| I. Toàn vẹn, chống chối bỏ | 6% | Ghi đè tại chỗ, không ký số, không băm | Sổ cái chuỗi băm, Merkle, RFC 3161, ký số (chương 11) |
| J. Kiểm thử, CI/CD | 28% | 4/4 pipeline bỏ qua test | Tháp kiểm thử, quality gate, SLSA (chương 18) |
| K. Đa khách thuê | 12% | Một tenant mỗi bản cài, cấu hình FE lúc build | Silo, bridge, pool, RLS, cấu hình lúc chạy (chương 12) |

Ba thông điệp của bản đánh giá được thiết kế giữ làm thứ tự ưu tiên. An toàn thông tin là điều kiện tiên quyết, nên năm lỗ hổng S01 đến S05 được khắc phục trong đợt 0a trước mọi thay đổi kiến trúc. Kiến trúc đúng hướng nhưng sai cách làm, nên điều kiện tiên quyết của mọi bước tái cấu trúc là chấm dứt dùng chung CSDL. Hệ hiện tại chưa có nền vận hành cho quy mô quốc gia, nên số lượng service được giữ ở mức tối thiểu có lý do cho tới khi trục B, C, F đạt ngưỡng.

## Ràng buộc pháp lý

Ràng buộc pháp lý tác động trực tiếp đến kiến trúc, không chỉ đến quy trình vận hành. Bảng sau liệt kê các ràng buộc chính và hệ quả kiến trúc của từng ràng buộc.

| Căn cứ | Yêu cầu | Hệ quả kiến trúc |
|---|---|---|
| Luật ATTT mạng 86/2015, NĐ 85/2016, TT 12/2022, TCVN 11930:2017 | Bảo đảm an toàn theo cấp độ; baseline đặt mục tiêu hồ sơ đề xuất cấp độ 4 | MFA, HSM theo khách hàng, mTLS, PAM ghi phiên, SOC 24/7, sao lưu bất biến, SBOM và ký bản build |
| Luật Giao dịch điện tử 20/2023, NĐ 23/2025 | Chữ ký số có giá trị pháp lý, dấu thời gian | esign-service với giao diện trừu tượng, ký ở bốn mốc pháp lý, TSA RFC 3161 |
| Luật Dữ liệu 60/2024, NĐ 47/2020 | Kết nối, chia sẻ qua nền tảng tích hợp LGSP, NDXP | Bộ kết nối chuẩn trong integration-gateway, mô hình dữ liệu chuẩn tám nhóm PL 4 |
| NĐ 69/2024 | Định danh điện tử VNeID | Liên kết VNeID qua Keycloak identity brokering, OIDC PKCE, state, nonce |
| Luật Bảo vệ dữ liệu cá nhân, NĐ 356/2025 | Phân loại, mã hoá, đánh giá tác động xử lý dữ liệu cá nhân, lưu trong nước | Phân loại C0 đến C3, mã hoá trường C2, không đưa PII vào cache, log, kho phân tích |
| Luật Bảo vệ bí mật nhà nước 2018 | Bí mật nhà nước chỉ xử lý trên hệ thống đáp ứng yêu cầu bảo vệ bí mật, mật mã của Ban Cơ yếu | Không xử lý bí mật nhà nước trên cell SaaS; bản cách ly dùng `CryptoProvider` chuyên dùng |
| Luật Trí tuệ nhân tạo 134/2025, PL 4 | AI không quyết định, nội dung AI phải gắn nhãn | ai-gateway chỉ đọc, gắn nhãn, lưu lý do, không có quyền ghi vào O/KR hay xếp loại |
| Dự thảo Quy định mới, PL 4 | Truy vết, chống sửa hồi tố, test case được phê duyệt | Sổ cái, khoá kỳ hai người duyệt, Policy Pack với test case vàng ký số |

Ràng buộc về bí mật nhà nước cần nhấn mạnh vì nó vạch một ranh giới cứng trong kiến trúc. Dữ liệu đánh giá cán bộ ở khu vực dân sự là dữ liệu nhạy cảm, được bảo vệ bằng mã hoá trường và mã hoá đầu cuối, nhưng không phải bí mật nhà nước theo nghĩa của luật. Khi một tenant có nhu cầu xử lý thông tin thuộc danh mục bí mật nhà nước, như ở khối quân đội, thông tin đó không được đưa vào cell SaaS dù được mã hoá đầu cuối; nó chỉ được xử lý trên bản cài cách ly với mật mã chuyên dùng. Chương 9 trình bày chi tiết ranh giới này.

# Quan điểm và nguyên tắc kiến trúc

## Quan điểm chung: hợp lý theo từng phần

Quan điểm xuyên suốt của thiết kế là mỗi phần của hệ thống được chọn kiểu kiến trúc phù hợp với đặc tính của chính nó, thay vì áp một kiểu chung cho toàn hệ thống. Quan điểm này được chốt bằng ADR-01 và có cơ sở từ bản đánh giá: hiện trạng là Distributed Monolith, tức đã tách tiến trình nhưng vẫn dùng chung CSDL, nên mang chi phí của microservice mà không có lợi ích của nó. Lời giải không phải là tách thêm service, cũng không phải gộp tất cả lại, mà là đặt mỗi ranh giới đúng chỗ có lý do.

Có ba câu hỏi quyết định kiểu kiến trúc của một phần hệ thống. Câu hỏi thứ nhất là phần đó cần mức nhất quán nào: nếu các thay đổi phải cùng thành công hoặc cùng thất bại trong một giao dịch có giá trị pháp lý thì chúng nên ở cùng một tiến trình và cùng một CSDL. Câu hỏi thứ hai là hồ sơ tải của phần đó có khác biệt rõ so với phần còn lại không: nếu có, phần đó cần co giãn độc lập và nên tách ra. Câu hỏi thứ ba là phần đó có thuộc vùng bảo mật riêng, hạ tầng riêng hay nhịp thay đổi riêng không: nếu có, ranh giới tiến trình và mạng đem lại lợi ích về cô lập.

Trả lời ba câu hỏi cho từng phần cho ra một kiến trúc lai. Lõi nghiệp vụ là Modular Monolith. Các điểm nóng và vùng bảo mật là microservice có CSDL riêng. Tích hợp giữa các phần và với bên ngoài là hướng sự kiện. Phân tích là CQRS. Quy mô quốc gia là cell. Luồng nghiệp vụ được điều phối ở ba tầng khác nhau tuỳ khối lượng và tính chất. Phần còn lại của chương này trình bày các nguyên tắc chung, bảng ánh xạ chi tiết, và tiêu chí định lượng để tách thêm service khi điều kiện thay đổi.

## Nguyên tắc kiến trúc

Các nguyên tắc dưới đây là ràng buộc bắt buộc với mọi đội phát triển. Mỗi nguyên tắc có lý do và cách kiểm tra tự động hoặc thủ tục kiểm tra rõ ràng, vì một nguyên tắc không kiểm tra được sẽ sớm bị vi phạm khi áp lực tiến độ tăng.

Nguyên tắc thứ nhất là mỗi module hoặc service sở hữu dữ liệu riêng. Mỗi MOD có một schema riêng và một tài khoản CSDL riêng chỉ có quyền trên schema đó; mỗi SVC có schema hoặc CSDL riêng. Không ai đọc, ghi trực tiếp bảng của module khác. Dữ liệu dùng chung chỉ trao đổi qua interface công bố, API hoặc sự kiện. Nguyên tắc này đáp ứng A2 và được kiểm tra bằng quyền CSDL (tài khoản `svc_task` không có quyền `USAGE` trên schema `appraisal`) cùng với ArchUnit chặn import chéo package `internal`.

Nguyên tắc thứ hai là quy tắc nghiệp vụ là dữ liệu có phiên bản, không phải mã. Công thức, ngưỡng, điều kiện xếp loại, bảng chuyển trạng thái, cấp duyệt nằm trong Policy Pack; mã nguồn chỉ chứa bộ máy thực thi. Nguyên tắc này đáp ứng H4, H8, I6 và được kiểm tra bằng rà soát mã: không có hằng số điểm hay ngưỡng xếp loại trong mã Java ngoài bộ test.

Nguyên tắc thứ ba là ghi dữ liệu và phát sự kiện trong cùng một giao dịch. Mọi sự kiện nghiệp vụ được ghi vào bảng `outbox_event` của schema nguồn trong cùng giao dịch với thay đổi dữ liệu; Debezium đọc WAL và đẩy lên Kafka. Không có tác vụ phụ chạy bằng luồng trong bộ nhớ. Nguyên tắc này đáp ứng A4 và loại bỏ vấn đề A03 của hiện trạng, nơi `MissionSideEffectExecutor` làm mất tác vụ khi pod dừng.

Nguyên tắc thứ tư là không có lời gọi đồng bộ trên đường găng sang hệ thống ngoài. Lời gọi tới vOffice, CSDL CBCCVC, MySign, TSA đều qua integration-gateway hoặc esign-service theo kiểu bất đồng bộ, có hàng đợi và trạng thái trung gian. Lời gọi đồng bộ giữa các service nội bộ chỉ dùng khi cần dữ liệu tức thời, và luôn có timeout, circuit breaker, bulkhead theo cấu hình chuẩn ở chương 14.

Nguyên tắc thứ năm là lớp bảo vệ tốn kém đi ra khỏi đường găng. Chuỗi băm ghi bất đồng bộ qua outbox, ký số chỉ ở bốn mốc pháp lý và chạy theo lô, quét mã độc và OCR chạy nền. Trên đường găng chỉ còn những kiểm tra có chi phí dưới mili giây. Chương 9 định lượng nguyên tắc này.

Nguyên tắc thứ sáu là ngữ cảnh tenant đi xuyên suốt và được kiểm tra ở nhiều lớp. Tenant được xác định từ claim trong JWT, kiểm tra ở cổng API, đặt vào ngữ cảnh ứng dụng, truyền xuống CSDL bằng biến phiên và được RLS cưỡng chế. Một lỗi ở một lớp không đủ để lộ dữ liệu tenant khác.

Nguyên tắc thứ bảy là mọi thứ quan sát được. Mỗi request mang `traceparent` từ trình duyệt tới CSDL; mỗi dòng log mang `trace_id`, `span_id`, `tenant_id`; mỗi API chính có SLI và SLO. Một service chưa có tracing, metric và cảnh báo thì chưa được đưa vào môi trường vận hành.

Nguyên tắc thứ tám là một bản build cho mọi môi trường và mọi tenant. Cấu hình, thương hiệu, manifest, URL IdP được nạp lúc chạy. Không có bản build riêng theo khách hàng. Nguyên tắc này đáp ứng K6 và C9, và là điều kiện để giao diện riêng theo tenant (chương 7) không phá vỡ quy trình phát hành.

Nguyên tắc thứ chín là ưu tiên công nghệ đội ngũ đang làm chủ. Java 21, Spring Boot 3.5, Angular 21 với Native Federation và PrimeNG, Log4j2, Jenkins, Kubernetes được giữ nguyên. Thành phần chỉ được thay khi có lý do được ghi trong ADR: Oracle sang PostgreSQL vì E2, Redis sang Valkey và Elasticsearch sang OpenSearch vì thay đổi giấy phép, iText7 AGPL sang docx4j và OpenPDF vì E6. Go được dùng cho ba service có yêu cầu thông lượng cao hoặc dấu chân nhỏ (tenant-registry, ledger-service, notification-service), Python cho ai-gateway vì hệ sinh thái AI.

Nguyên tắc thứ mười là tách service theo tiêu chí định lượng, không theo cảm tính. Một module của lõi chỉ được tách thành service khi đạt các tiêu chí ở mục cuối chương này, và việc tách phải có ADR.

## Bảng kiểu kiến trúc theo từng phần hệ thống

Bảng dưới đây là bản tóm tắt quan trọng nhất của tài liệu. Mỗi dòng cho biết một phần hệ thống dùng kiểu kiến trúc nào, vì sao, và trong điều kiện nào thì lựa chọn đó cần được xem lại. Cột cuối cùng đặc biệt quan trọng, vì một quyết định kiến trúc không có điều kiện thay đổi sẽ trở thành giáo điều.

| Phần hệ thống | Kiểu kiến trúc | Lý do chính | Điều kiện xem lại |
|---|---|---|---|
| Lõi nghiệp vụ OKR, giao việc, minh chứng, đánh giá, Policy Pack, kiểm điểm, hậu kiểm, cấu hình (SVC-05) | Modular Monolith (Spring Modulith), hexagonal trong từng module, schema riêng mỗi module | Nhất quán ACID cho mốc pháp lý; ranh giới miền chưa ổn định; cài cách ly cần ít tiến trình | Một module đạt đủ 5 tiêu chí tách ở mục sau |
| Tính điểm (SVC-07) | Microservice worker theo hàng đợi | Đỉnh cuối kỳ gấp hàng chục lần ngày thường; tính toán CPU nặng | Không xem lại; có thể gộp vào lõi ở bản cách ly nhỏ |
| Minh chứng (SVC-08) | Microservice reactive (WebFlux) | I/O lớn, kho WORM, quét mã độc, OCR | Không xem lại |
| Thông báo (SVC-12) | Microservice Go, hướng sự kiện | Thông lượng cao, kết nối SSE/WebSocket dài | Không xem lại |
| Cổng tích hợp (SVC-13) | Microservice, Anti-Corruption Layer, Apache Camel | Nhịp thay đổi theo đối tác; cô lập lỗi bên ngoài | Tách thành nhiều connector service khi số đối tác lớn hơn 20 |
| Sổ cái (SVC-09), ký số (SVC-10), AI (SVC-14) | Microservice trong vùng bảo mật riêng | Cô lập khoá, HSM, GPU; quyền tối thiểu; kiểm toán riêng | Không xem lại |
| Tổ chức, nhân sự (SVC-04) | Microservice, dữ liệu hai trục thời gian, Event-carried State Transfer | Dùng chung với hệ điều hành văn bản; nguồn sự thật cho mọi tenant | Không xem lại |
| Luồng nghiệp vụ | Ba tầng: máy trạng thái trong mã, BPMN Flowable, saga điều phối | Tách theo khối lượng và tính chất người hay máy | Khi tải user task một cell vượt 300 lần/giây, chia engine theo nhóm tenant |
| Tích hợp nội bộ và ngoài | Hướng sự kiện, Kafka, outbox, inbox, CloudEvents ra ngoài | Không mất, không trùng; cô lập thời gian giữa các bên | Không xem lại |
| Phân tích, dashboard (SVC-15) | CQRS phía đọc, ClickHouse, Cube, Superset | Truy vấn tổng hợp không làm chậm nghiệp vụ | Bản cách ly nhỏ dùng bản sao đọc thay ClickHouse |
| Tìm kiếm (SVC-16) | Chỉ mục dẫn xuất từ sự kiện | Truy vấn toàn văn không phù hợp CSDL quan hệ | Không xem lại |
| Phân tách quy mô | Cell-based Architecture, ba mô hình silo, bridge, pool | Giới hạn phạm vi sự cố; khách hàng lớn cần cô lập | Tách cell khi một cell vượt 1,5 triệu tài khoản hoặc 75.000 CCU |
| Vùng điều phối toàn cục | Dịch vụ nhỏ, không trạng thái nghiệp vụ | Không nằm trên đường xử lý dữ liệu | Không xem lại |
| Frontend web (SVC-17) | Micro-frontend Native Federation, design token, SDUI có giới hạn | Nhiều đội song song; giao diện theo tenant | Khi số remote lớn hơn 15, gom lại theo miền |
| Di động (SVC-18) | Ứng dụng Flutter ngoại tuyến trước, dùng chung schema biểu mẫu | Mạng yếu ở cơ sở; người cao tuổi | Không xem lại |
| Vòng đời pháp lý (hồ sơ đánh giá, khoá kỳ, nghiệm thu) | Event Sourcing chọn lọc qua sổ cái chỉ ghi thêm | Tái hiện trạng thái tại mọi thời điểm | Không áp dụng cho CRUD danh mục, cấu hình |

Hai lựa chọn cần giải thích thêm vì chúng đi ngược trực giác. Thứ nhất, Event Sourcing không được áp dụng cho toàn hệ thống. Dữ liệu danh mục, cấu hình, giao việc hằng ngày là dữ liệu CRUD được truy vấn nhiều và đổi cấu trúc thường xuyên; dựng lại trạng thái từ sự kiện cho các dữ liệu này tốn chi phí mà không đem lại giá trị pháp lý. Ngược lại, vòng đời hồ sơ đánh giá, nghiệm thu, khoá và mở kỳ, phát hành Policy Pack có giá trị pháp lý, nên mọi thay đổi trạng thái của chúng được ghi thêm vào sổ cái với chuỗi băm (chương 11), còn bảng trạng thái hiện hành trong schema nghiệp vụ là phép chiếu tiện cho truy vấn.

Thứ hai, workflow-service tách khỏi mission-core dù luồng duyệt gắn chặt với nghiệp vụ. Lý do là engine BPMN có hồ sơ tải và hồ sơ CSDL riêng: các bảng `ACT_RU_*` bị cập nhật dày, bảng lịch sử phình nhanh, job executor tạo tải polling. Đặt engine trong CSDL của lõi sẽ làm tải này ảnh hưởng trực tiếp đến giao dịch nghiệp vụ. Chương 8 trình bày đầy đủ lập luận.

## Tiêu chí định lượng để tách thêm service

Bản đánh giá đề xuất năm tiêu chí để quyết định khi nào một module của lõi được tách thành service riêng. Tài liệu này chuyển năm tiêu chí đó thành ngưỡng đo được, và yêu cầu một module phải đạt đồng thời tiêu chí 4 và 5 cùng ít nhất một trong ba tiêu chí 1, 2, 3 thì mới được tách.

| Số | Tiêu chí của bản đánh giá | Ngưỡng đo được | Nguồn số liệu |
|---|---|---|---|
| 1 | Hồ sơ tải khác biệt rõ | Tải đỉnh của module ≥ 5 lần tải trung bình của phần còn lại của lõi, đo bằng CPU giây hoặc RPS ở p99 qua ít nhất hai kỳ cao điểm | Prometheus, metric `http_server_requests` gắn nhãn module |
| 2 | Cần nhịp phát hành độc lập | ≥ 30% số lần phát hành của lõi trong hai quý chỉ thay đổi module này, hoặc yêu cầu khách hàng phát hành module này ngoài lịch | Lịch sử Git, Argo CD |
| 3 | Thuộc vùng bảo mật hoặc hạ tầng riêng | Module xử lý khoá, HSM, GPU, hoặc cần vùng mạng riêng theo hồ sơ cấp độ | Hồ sơ ATTT |
| 4 | Ranh giới ổn định | Interface `api` của module không có thay đổi phá vỡ tương thích trong ≥ 2 quý; schema sự kiện đã đăng ký ở Apicurio với chế độ tương thích BACKWARD | Spring Modulith documenter, Apicurio |
| 5 | Có đội sở hữu và nền vận hành | Có đội sở hữu riêng; module đã có tracing, circuit breaker, quality gate, SLO | Bảng sở hữu, Grafana |

Áp dụng bảng này cho tám module hiện tại cho thấy chưa module nào đủ điều kiện tách trong năm đầu, vì tiêu chí 4 chưa thể đạt khi văn bản pháp lý còn đang thay đổi. Ứng viên sớm nhất là MOD-02 `task` nếu tải cập nhật tiến độ chiếm phần lớn tải của lõi, và MOD-06 `dossier` nếu nghiệp vụ kiểm điểm có nhịp phát hành riêng theo hướng dẫn của từng khối. Khi tách, module chuyển sang service bằng cách thay lời gọi interface bằng client REST hoặc sự kiện có cùng hợp đồng; nhờ cấu trúc hexagonal ở chương 5, phần `domain` và `application` không phải viết lại.

# Kiến trúc tổng thể

## Sơ đồ ngữ cảnh

Sơ đồ ngữ cảnh (C4 mức 1) đặt nền tảng vào môi trường xung quanh: ai dùng nó và nó trao đổi với hệ thống nào. Ở mức này, toàn bộ nền tảng được xem như một hộp đen.

![Hình 4.1. Sơ đồ ngữ cảnh của nền tảng (C4 mức 1)](kt-ngu-canh.png){width=16cm}

Phía trên là bốn nhóm người dùng chính, ánh xạ tới các tác nhân AC-01 đến AC-10 của baseline. Cán bộ, đảng viên là nhóm đông nhất, dùng cả web và di động cho các thao tác hằng ngày như cập nhật tiến độ, nộp minh chứng, tự đánh giá. Lãnh đạo dùng bảng điều hành và duyệt hàng loạt. Cơ quan tổ chức và cơ quan kiểm tra thẩm định, hậu kiểm, xử lý cảnh báo. Quản trị tenant và BA cấu hình soạn Policy Pack, UI Pack và quản lý cây tổ chức.

Phía dưới là chín nhóm hệ thống ngoài. Điểm quan trọng của sơ đồ là mọi trao đổi với hệ thống ngoài đều đi qua cổng tích hợp SVC-13 (riêng VNeID đi qua Keycloak và các nhà cung cấp chữ ký số đi qua esign-service), không có kết nối trực tiếp từ lõi nghiệp vụ. Văn phòng số vOffice của Viettel không còn là "hệ chủ" mà là một trong nhiều nguồn văn bản chỉ đạo, ngang hàng với iOffice của VNPT hay hệ văn bản của hãng khác. Đây là hiện thực hoá kết luận ở slide 12 của Báo cáo BGĐ: tách lõi định danh, phân quyền và dữ liệu, nhưng vẫn giữ liên kết chặt với hệ văn bản qua giao diện tích hợp.

Hai quan hệ ở bên phải có tính chất khác. SOC 24/7 của Viettel Cyber Security giám sát nền tảng qua luồng log ATTT và cảnh báo, không truy cập dữ liệu nghiệp vụ. Cơ quan giám sát nhận bản neo của sổ cái (gốc Merkle đã ký và gắn dấu thời gian) để có thể kiểm chứng độc lập rằng không ai, kể cả Viettel, sửa lại lịch sử. Quan hệ thứ hai là thông điệp "kiểm chứng độc lập, không cần tin lời nhà cung cấp" ở slide 42.

## Sơ đồ container

Sơ đồ container (C4 mức 2) mở hộp đen thành các thành phần triển khai độc lập. Mỗi hộp là một SVC của baseline; các hộp được nhóm theo kiểu kiến trúc đã chọn ở chương 3.

![Hình 4.2. Sơ đồ container của một cell (C4 mức 2)](kt-container.png){width=16cm}

Lối vào duy nhất từ bên ngoài là api-gateway SVC-01 (APISIX với WAF Coraza). Trình duyệt và ứng dụng di động lấy token từ iam SVC-02 (Keycloak) theo luồng OIDC Authorization Code với PKCE, rồi gọi API qua cổng. Cổng kiểm chữ ký JWT bằng JWKS, giới hạn tốc độ và định tuyến tới service đích.

Ở giữa là lõi Modular Monolith mission-core SVC-05, nơi xử lý phần lớn request nghiệp vụ. Lõi ghi vào PostgreSQL theo schema của từng module và ghi sự kiện vào bảng outbox cùng giao dịch. Debezium đọc WAL và đẩy sự kiện lên Kafka. Từ Kafka, các service tiêu thụ theo vai trò: scoring-service tính điểm dần khi minh chứng được xác nhận; ledger-service nối sự kiện vào chuỗi băm; notification-service gửi thông báo; workflow-service khởi tạo tiến trình duyệt; integration-gateway đẩy sự kiện ra hệ ngoài; analytics và search xây mô hình đọc.

Có ba quan hệ đồng bộ đáng chú ý. Lõi gọi workflow-service qua REST có Resilience4j để hoàn thành user task và truy vấn hộp việc cần duyệt, vì người dùng chờ kết quả ngay. Cổng chuyển thẳng request tải tệp tới evidence-service để luồng dữ liệu lớn không đi qua lõi. Cổng chuyển request trợ lý AI tới ai-gateway, nơi có hàng đợi suy luận riêng. Mọi quan hệ còn lại là bất đồng bộ qua Kafka.

Vùng bảo mật gồm ledger-service, esign-service và ai-gateway được đặt trong namespace Kubernetes riêng, với chính sách mạng chỉ cho phép các luồng đã khai báo, tài khoản dịch vụ có quyền tối thiểu, và HSM hoặc GPU riêng. org-service SVC-04 là nguồn sự thật cho cây tổ chức và nhân sự, phát sự kiện EVT-14 đến EVT-16 để các service khác nhân bản dữ liệu cần đọc vào schema `org_replica`, theo mẫu chuyển trạng thái mang theo sự kiện (Event-carried State Transfer).

## Vùng điều phối toàn cục và cell

### Vùng điều phối toàn cục

Vùng điều phối toàn cục (global control plane) là phần duy nhất không thuộc cell nào. Nó gồm GeoDNS, APISIX lớp ngoài, tenant-registry SVC-03, kho gốc Policy Pack và UI Pack, CDN tài nguyên tĩnh và kho phân tích quốc gia chỉ nhận dữ liệu tổng hợp. Mục tiêu khả dụng của vùng này là 99,95%, cao hơn mỗi cell, vì một sự cố ở đây ảnh hưởng tới mọi cell.

Để đạt mục tiêu đó với chi phí hợp lý, vùng toàn cục được thiết kế sao cho không nằm trên đường xử lý dữ liệu nghiệp vụ. APISIX lớp ngoài chỉ giải tên miền hoặc claim tenant thành cell và chuyển tiếp; kết quả tra cứu tenant → cell được lưu đệm 5 phút tại APISIX và trong cookie định tuyến ký HMAC, nên nếu tenant-registry ngừng trong vài phút thì request của tenant đang hoạt động vẫn đi đúng cell. Kho gốc Policy Pack và UI Pack được đồng bộ xuống từng cell; cell luôn đọc bản sao cục bộ. Kho phân tích quốc gia nhận dữ liệu qua MirrorMaker 2, chậm vài phút cũng không ảnh hưởng nghiệp vụ.

### Cell

![Hình 4.3. Vùng điều phối toàn cục, các cell và hai trung tâm dữ liệu](kt-cell.png){width=16cm}

Mỗi cell là một bộ đầy đủ SVC-01, SVC-02, SVC-04 đến SVC-16 cùng hạ tầng dùng chung trong cell: Kafka KRaft, Apicurio, Debezium, PostgreSQL CloudNativePG với PgBouncer, Valkey Cluster, Ceph RGW, OpenSearch, ClickHouse, OpenBao, Istio ambient, OTel Collector, Fluent Bit. Một cell không chia sẻ CSDL, Kafka hay kho tệp với cell khác. Các cell chạy cùng một phiên bản phần mềm; khác biệt giữa chúng chỉ là quy mô tài nguyên và mô hình thuê bao.

Bảng cell của baseline gồm CELL-DTW cho Đảng ủy các cơ quan Đảng Trung ương theo mô hình silo với HSM riêng, CELL-HCM và CELL-HN theo mô hình silo, CELL-G1 đến CELL-G4 cho nhóm tỉnh theo mô hình bridge (mỗi tỉnh một CSDL trong cụm PostgreSQL của cell), CELL-SH cho xã, chi bộ, đơn vị nhỏ và doanh nghiệp theo mô hình pool (bảng chung với `tenant_id` và RLS), và bản cách ly cho LLVT, công an. Mỗi cell có một bản sao ấm ở trung tâm dữ liệu phía Nam: CloudNativePG replica cluster nhận WAL liên tục, MirrorMaker 2 sao chép topic, Ceph multisite sao chép bucket. Chương 12 trình bày định tuyến cell và chuyển tenant giữa cell; TL-04 trình bày quy trình chuyển vùng khi mất trung tâm dữ liệu.

Kích thước cell được giới hạn ở khoảng 1,5 triệu tài khoản và 75.000 CCU. Giới hạn này có hai lý do. Lý do thứ nhất là phạm vi ảnh hưởng: một sự cố cell lớn nhất ảnh hưởng không quá 25% người dùng toàn quốc. Lý do thứ hai là định cỡ CSDL: với cell 75.000 CCU, tải ghi đỉnh khoảng 1.500 request/giây, một cụm PostgreSQL một primary trên máy chủ NVMe 64 nhân xử lý được với biên an toàn, không cần phân mảnh ngang CSDL, vốn là một trong những thay đổi tốn kém và rủi ro nhất.

## Ba mô hình triển khai

Ba mô hình triển khai trong Báo cáo BGĐ dùng chung một bộ image và một bộ Helm chart; khác biệt nằm ở tệp values và hồ sơ triển khai.

| Mô hình | Hạ tầng | Phạm vi | Khác biệt cấu hình chính |
|---|---|---|---|
| SaaS trên Viettel Cloud | Các cell ở hai TTDL Hoà Lạc và phía Nam | Đa số khách hàng dân sự | Đầy đủ thành phần; HPA, KEDA; CDN; liên kết VNeID, MySign |
| Đám mây riêng của tỉnh, bộ | Một cell trên hạ tầng của khách hàng hoặc vùng riêng của Viettel Cloud | Khách hàng cần chủ quyền hạ tầng | Như SaaS nhưng tenant-registry cục bộ chỉ có một cell; HSM của khách hàng |
| Cài đặt cách ly | Cụm Kubernetes nhỏ, không Internet | LLVT, công an | Helm ngoại tuyến, registry nội bộ, mật mã chuyên dùng, AI tại chỗ, tắt kết nối ra ngoài |

Ở bản cách ly, bộ thành phần tối thiểu gồm APISIX, Keycloak, org-service, mission-core, workflow-service, scoring-service, evidence-service, ledger-service, esign-service, document-service, notification-service, PostgreSQL, Kafka một broker hoặc ba broker tuỳ quy mô, Ceph hoặc MinIO, OpenBao, Prometheus, Loki, Grafana. ClickHouse và Superset có thể tắt; khi đó analytics-api đọc từ bản sao đọc PostgreSQL. ai-gateway chạy mô hình tại chỗ trên GPU riêng. Gói cập nhật được ký số, đối chiếu SBOM và đi qua trạm kiểm định một chiều; kênh phát hành LTS chậm hơn lõi chung một chu kỳ, trừ bản vá an ninh khẩn.

## Luồng một request điển hình từ trình duyệt tới CSDL

Để thấy các thành phần phối hợp ra sao, mục này đi theo một request "cập nhật tiến độ nhiệm vụ" từ trình duyệt của cán bộ tới CSDL và quay lại. Đây là thao tác ghi phổ biến nhất của hệ thống.

![Hình 4.4. Đường đi của request cập nhật tiến độ nhiệm vụ](kt-request-flow.png){width=16cm}

Ở bước 1, trình duyệt đã có shell `fe-shell`, remote `fe-task` và UI Pack của tenant trong bộ đệm, được kiểm tra lại bằng ETag với CDN. Ở bước 2, `fe-task` gửi `PATCH /api/v1/tasks/{id}/progress` kèm `Authorization: Bearer`, `traceparent` do thư viện `@vt/telemetry` sinh, `If-Match` mang `row_version` hiện tại và `Idempotency-Key` là UUIDv7 sinh một lần cho thao tác. Ở bước 3, APISIX chạy luật WAF Coraza theo bộ OWASP CRS, kiểm chữ ký JWT bằng JWKS của Keycloak cell (bộ đệm khoá 10 phút), áp giới hạn tốc độ theo người dùng, tenant và API, ghi `X-Tenant-Id` từ claim `tenant_id`, rồi chuyển tiếp tới mission-core.

Ở bước 4 và 5, lưu lượng đi qua ztunnel của Istio ambient, được mã hoá mTLS với danh tính SPIFFE của service account. Ở bước 6, bộ lọc `TenantFilter` của mission-core đọc tenant từ JWT đã được Spring Security xác thực lại (không tin header từ cổng một cách mù quáng), đặt `TenantContext` và gắn `tenant_id` vào MDC của Log4j2 và thuộc tính span. Ở bước 7, lớp ứng dụng hỏi OPA chạy cùng pod quyết định `allow` cho chủ thể, hành động `task:progress:update` và tài nguyên; OPA dùng dữ liệu cây tổ chức đã nạp sẵn để kiểm tra người dùng có phải người chủ trì hoặc phối hợp của nhiệm vụ trong phạm vi đơn vị không.

Ở bước 8, lớp hạ tầng lấy kết nối từ pool HikariCP qua PgBouncer chế độ transaction và thực thi `SELECT set_config('app.tenant_id', ?, true)` ở đầu giao dịch, tương đương `SET LOCAL`. Ở bước 9, trong cùng giao dịch, ứng dụng kiểm tra kỳ chưa khoá bằng bộ đệm trạng thái kỳ, cập nhật `task.task_progress` với điều kiện `row_version` khớp, ghi một dòng `task.outbox_event` cho EVT-05 TienDoDaCapNhat, và ghi `task.idempotency_key`. RLS bảo đảm câu lệnh chỉ chạm dòng của tenant hiện hành. Ở bước 10 và 11, giao dịch commit và API trả 200 với ETag mới. Ước tính thời gian phía máy chủ khoảng 25 ms ở p95, chi tiết ở chương 9.

Sau khi trả lời người dùng, phần việc còn lại diễn ra bất đồng bộ. Debezium đọc dòng outbox từ WAL trong vài chục mili giây, đẩy lên `task.task.progressed.v1`. ledger-service nối sự kiện vào chuỗi băm phân vùng của tenant; analytics cập nhật bảng phân tích; nếu tiến độ đạt 100% thì một luồng khác chuyển trạng thái nhiệm vụ và phát EVT-06 cho scoring-service. Người dùng không phải chờ bất kỳ bước nào trong số này.

# Thiết kế chi tiết từng thành phần

Chương này mô tả mười tám thành phần SVC-01 đến SVC-18. Mỗi thành phần được trình bày theo cùng một khung: vai trò, công nghệ và phiên bản, API chính, dữ liệu sở hữu, sự kiện phát và nhận, cơ chế co giãn, chịu lỗi, bảo mật và cấu hình mẫu. Riêng mission-core SVC-05 là phần lớn nhất và quan trọng nhất nên được trình bày trước và sâu hơn, gồm cấu trúc gói theo module, quy tắc ArchUnit, ví dụ Spring Modulith, máy trạng thái nhiệm vụ, quản lý giao dịch và outbox. Danh mục API đầy đủ theo module nằm ở Phụ lục C; định nghĩa bảng chi tiết nằm ở TL-03.

## SVC-05 mission-core: lõi Modular Monolith

### Vai trò

mission-core là lõi nghiệp vụ, chứa tám module MOD-01 `okr`, MOD-02 `task`, MOD-03 `product`, MOD-04 `appraisal`, MOD-05 `policy`, MOD-06 `dossier`, MOD-07 `oversight`, MOD-08 `config`. Lõi xử lý phần lớn request ghi và đọc nghiệp vụ, bảo đảm tính nhất quán ACID cho các mốc pháp lý, và phát sự kiện nghiệp vụ qua outbox. Lõi chạy như một tiến trình Spring Boot duy nhất, nhiều replica không trạng thái sau cổng API.

Lý do giữ tám module trong một tiến trình đã được trình bày ở chương 2 và 3. Điểm cần nhấn mạnh là "một tiến trình" không có nghĩa là "một khối bùn". Mỗi module có ranh giới được kiểm tra tự động, schema và tài khoản CSDL riêng, interface công bố và sự kiện công bố riêng. Nếu một module đạt tiêu chí tách ở chương 3, việc tách chỉ là thay cách nối, không phải viết lại.

### Công nghệ và phiên bản

Lõi dùng Java 21 (Eclipse Temurin, image distroless), Spring Boot 3.5, Spring Modulith 1.4, Spring Data JPA với Hibernate 6.6, Flyway 11 cho migration theo schema, HikariCP, Resilience4j 2.3, cel-java 0.9 cho biểu thức, springdoc-openapi 2.8 sinh OpenAPI 3.1, OpenTelemetry Java agent 2.x, Log4j2 với JSON Template Layout theo ECS, ArchUnit 1.4 và Testcontainers trong kiểm thử. Luồng ảo (virtual threads) của Java 21 được bật cho Tomcat (`spring.threads.virtual.enabled=true`) để một replica phục vụ nhiều request chờ I/O mà không cần pool luồng lớn; bulkhead và pool kết nối vẫn giới hạn số request đồng thời chạm CSDL.

### Cấu trúc gói Java theo module

Gói gốc là `vn.viettel.okrkpi`. Mỗi module là một gói con trực tiếp của gói gốc, và Spring Modulith coi mỗi gói con trực tiếp là một module ứng dụng. Bên trong mỗi module, mã được tổ chức theo kiến trúc lục giác (hexagonal) với năm gói con có vai trò cố định.

```text
vn.viettel.okrkpi
├── MissionCoreApplication.java
├── shared                      // nhân dùng chung, không chứa nghiệp vụ
│   ├── kernel                  // TenantId, PeriodId, Money, Clock, Result
│   ├── tenant                  // TenantContext, TenantFilter, TenantAwareDataSource
│   ├── outbox                  // OutboxWriter, OutboxEvent, EventEnvelope
│   ├── crypto                  // CryptoProvider, FieldEncryptor, DekCache
│   ├── authz                   // OpaClient, @RequiresPermission
│   └── error                   // ProblemDetails theo RFC 9457
├── okr                         // MOD-01
├── task                        // MOD-02
│   ├── api                     // @NamedInterface("api"): TaskApi, TaskSummary, sự kiện công bố
│   │   ├── TaskApi.java
│   │   ├── TaskSummary.java
│   │   └── events
│   │       ├── NhiemVuDaGiao.java
│   │       └── TrangThaiNhiemVuDoi.java
│   ├── application             // use case và cổng (port)
│   │   ├── AssignTaskUseCase.java
│   │   ├── UpdateProgressUseCase.java
│   │   ├── port
│   │   │   ├── TaskRepository.java
│   │   │   └── TransitionTableProvider.java
│   │   └── TaskApiService.java // hiện thực TaskApi
│   ├── domain                  // mô hình thuần, không phụ thuộc Spring
│   │   ├── Task.java
│   │   ├── TaskStatus.java
│   │   ├── TaskCommand.java
│   │   ├── TaskStateMachine.java
│   │   └── TransitionTable.java
│   ├── infrastructure          // adapter vào và ra
│   │   ├── web
│   │   │   └── TaskController.java
│   │   ├── persistence
│   │   │   ├── TaskEntity.java
│   │   │   └── JpaTaskRepository.java
│   │   └── policy
│   │       └── PolicyBackedTransitionTableProvider.java
│   └── internal                // tiện ích nội bộ, không ai ngoài module dùng
│       └── TaskMapper.java
├── product                     // MOD-03
├── appraisal                   // MOD-04
├── policy                      // MOD-05
├── dossier                     // MOD-06
├── oversight                   // MOD-07
└── config                      // MOD-08
```

Vai trò của năm gói con được quy định như sau. Gói `api` là bề mặt công bố của module, gồm interface dịch vụ, DTO bất biến (Java `record`) và sự kiện công bố; đây là gói duy nhất module khác được phép tham chiếu. Gói `application` chứa use case, mỗi use case là một lớp có một phương thức công khai, cùng các cổng ra (port) mà hạ tầng hiện thực; ranh giới giao dịch nằm ở đây. Gói `domain` chứa aggregate, value object, máy trạng thái và quy tắc nghiệp vụ thuần, không phụ thuộc Spring, JPA hay Jackson, để kiểm thử nhanh và để tái sử dụng khi tách module. Gói `infrastructure` chứa adapter: controller REST, repository JPA, client gọi service khác, listener sự kiện. Gói `internal` chứa phần chi tiết không công bố như mapper, hằng số kỹ thuật.

Quy tắc phụ thuộc là `infrastructure` → `application` → `domain`, `api` chỉ phụ thuộc kiểu dữ liệu thuần, và `domain` không phụ thuộc gì ngoài `shared.kernel`. Module A chỉ được phụ thuộc `B.api`. Không có phụ thuộc vòng giữa module, kể cả qua `api`.

![Hình 5.1. Cấu trúc module của mission-core và hướng phụ thuộc cho phép](kt-mission-core-module.png){width=15cm}

Hình trên lấy module `okr` làm ví dụ đầy đủ năm gói con, và vẽ rút gọn các module `task`, `appraisal`, `policy`, `config` chỉ với gói `api` để làm rõ quan hệ. Module `task` gọi interface `OkrApi` để liên kết nhiệm vụ với kết quả then chốt và gọi `PeriodApi` của `config` để kiểm tra kỳ khoá; module `appraisal` gọi `PolicyQueryApi` để lấy quy tắc. Chiều ngược lại, khi `appraisal` cần biết nhiệm vụ đã hoàn thành, nó nhận sự kiện ứng dụng do `task` phát chứ không gọi trực tiếp, nên không phát sinh vòng. Mọi module dùng chung gói `shared` cho các khái niệm kỹ thuật (tenant, outbox, mã hoá), và kiểm thử ArchUnit cùng `ApplicationModules.verify()` chặn mọi truy cập vào gói `internal` của module khác.

### Khai báo module bằng Spring Modulith

Mỗi module khai báo phụ thuộc cho phép trong `package-info.java`. Spring Modulith dùng khai báo này để xác minh lúc kiểm thử và để sinh tài liệu kiến trúc (sơ đồ C4 mức 3, bảng interface công bố) cùng mã, đáp ứng J9.

```java
// vn/viettel/okrkpi/task/package-info.java
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-02 Giao việc và KPI",
    allowedDependencies = { "shared", "config::api", "policy::api", "okr::api" }
)
package vn.viettel.okrkpi.task;
```

```java
// vn/viettel/okrkpi/task/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package vn.viettel.okrkpi.task.api;
```

Bảng sau là ma trận phụ thuộc cho phép giữa tám module. Ma trận này được chọn để không có vòng: các module phía trên cung cấp dữ liệu nền, các module phía dưới tiêu thụ. Khi module phía trên cần biết điều gì xảy ra ở module phía dưới, nó lắng nghe sự kiện thay vì gọi interface.

| Module | Được phụ thuộc vào `api` của | Nhận sự kiện từ |
|---|---|---|
| MOD-08 `config` | không module nào | EVT-22 từ MOD-07 |
| MOD-05 `policy` | `config` | không |
| MOD-01 `okr` | `config`, `policy` | không |
| MOD-02 `task` | `config`, `policy`, `okr` | EVT-01 từ MOD-01, EVT-18 từ SVC-10 |
| MOD-03 `product` | `config`, `policy`, `task` | không |
| MOD-04 `appraisal` | `config`, `policy`, `task`, `product`, `okr` | EVT-09, EVT-10, EVT-18 |
| MOD-06 `dossier` | `config`, `appraisal` | EVT-12, EVT-17, EVT-18 |
| MOD-07 `oversight` | `config`, `appraisal`, `task`, `product` | EVT-20, EVT-25 |

### Quy tắc ArchUnit

Spring Modulith xác minh ranh giới module và vòng phụ thuộc. ArchUnit bổ sung các quy tắc bên trong module mà Modulith không kiểm tra, như hướng phụ thuộc lục giác, kích thước lớp, cấm System.out. Bộ kiểm thử dưới đây chạy trong mọi build; vi phạm làm build thất bại, đáp ứng A3 và F11.

```java
package vn.viettel.okrkpi.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import vn.viettel.okrkpi.MissionCoreApplication;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.GeneralCodingRules.*;

@AnalyzeClasses(packages = "vn.viettel.okrkpi",
                importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

  @Test
  void modulesAreValidAndDocumented() {
    ApplicationModules modules = ApplicationModules.of(MissionCoreApplication.class);
    modules.verify(); // không vòng, chỉ dùng interface công bố
    new Documenter(modules).writeModulesAsPlantUml().writeModuleCanvases();
  }

  @ArchTest
  static final ArchRule domainIsPure = noClasses()
      .that().resideInAPackage("..domain..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..", "jakarta.persistence..",
          "com.fasterxml.jackson..", "..infrastructure..", "..application..");

  @ArchTest
  static final ArchRule applicationDoesNotSeeInfrastructure = noClasses()
      .that().resideInAPackage("..application..")
      .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

  @ArchTest
  static final ArchRule internalIsPrivateToModule = classes()
      .that().resideInAPackage("vn.viettel.okrkpi.(*).internal..")
      .should().onlyBeAccessed().byClassesThat()
      .resideInAPackage("vn.viettel.okrkpi.$1..");

  @ArchTest
  static final ArchRule controllersOnlyInWebAdapter = classes()
      .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
      .should().resideInAPackage("..infrastructure.web..");

  @ArchTest
  static final ArchRule transactionsOnlyInApplication = noMethods()
      .that().areDeclaredInClassesThat().resideOutsideOfPackage("..application..")
      .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class);

  @ArchTest
  static final ArchRule noStdStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  @ArchTest
  static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
```

Quy tắc `internalIsPrivateToModule` dùng nhóm bắt `(*)` của ArchUnit để buộc lớp trong `task.internal` chỉ được truy cập bởi lớp trong `task`. Giới hạn kích thước lớp 1.000 dòng và hàm 100 dòng, độ phức tạp nhận thức không quá 15, được kiểm tra bằng SonarQube quality gate (chương 18) vì ArchUnit không đo số dòng một cách thuận tiện.

### Giao tiếp giữa module

Module giao tiếp với nhau theo đúng hai cách, chọn theo tính chất của tương tác.

Cách thứ nhất là gọi đồng bộ qua interface công bố, dùng khi bên gọi cần câu trả lời ngay để tiếp tục cùng một giao dịch. Ví dụ, khi MOD-02 giao nhiệm vụ, nó cần biết kỳ còn mở không (gọi `config.api.PeriodApi`) và KR được liên kết có tồn tại không (gọi `okr.api.OkrApi`). Lời gọi này là lời gọi phương thức Java trong cùng tiến trình, chạy trong cùng giao dịch, không có chi phí mạng.

```java
package vn.viettel.okrkpi.config.api;

public interface PeriodApi {
  PeriodSnapshot current(TenantId tenant, String cadence);
  /** Ném PeriodLockedException nếu kỳ ở trạng thái DANG_KHOA hoặc DA_KHOA. */
  void assertWritable(TenantId tenant, PeriodId period);
}

public record PeriodSnapshot(PeriodId id, String code, PeriodStatus status,
                             java.time.LocalDate startDate, java.time.LocalDate endDate) {}
```

Cách thứ hai là sự kiện ứng dụng, dùng khi bên phát không cần biết ai xử lý và việc xử lý có thể diễn ra sau. Ví dụ, khi nhiệm vụ chuyển sang `HOAN_THANH`, MOD-04 cần cập nhật dữ liệu đầu vào cho hồ sơ đánh giá, nhưng MOD-02 không nên phụ thuộc MOD-04. Spring Modulith hỗ trợ việc này bằng `@ApplicationModuleListener`, tức một listener chạy bất đồng bộ sau khi giao dịch phát commit, trong giao dịch riêng, và được ghi vào sổ đăng ký phát hành sự kiện (Event Publication Registry) để thử lại nếu thất bại.

```java
package vn.viettel.okrkpi.appraisal.infrastructure.events;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.appraisal.application.RefreshAppraisalInputsUseCase;
import vn.viettel.okrkpi.task.api.events.TrangThaiNhiemVuDoi;

@Component
class TaskStatusListener {
  private final RefreshAppraisalInputsUseCase refresh;

  TaskStatusListener(RefreshAppraisalInputsUseCase refresh) { this.refresh = refresh; }

  @ApplicationModuleListener
  void on(TrangThaiNhiemVuDoi event) {
    if (event.newStatus().isTerminal()) {
      refresh.forAssignees(event.tenantId(), event.periodId(), event.assigneeIds());
    }
  }
}
```

Sổ đăng ký phát hành sự kiện của Spring Modulith dùng bảng `event_publication` đặt trong schema kỹ thuật `modulith` (bảng bổ sung do TL-01 đề xuất, TL-03 chi tiết hoá). Sổ này chỉ phục vụ giao tiếp trong tiến trình. Sự kiện cần ra khỏi tiến trình, như EVT-04 hay EVT-06, đi qua outbox của schema module như trình bày ở mục sau. Hai cơ chế không trùng nhau: một sự kiện công bố ra ngoài luôn được ghi outbox; nếu module nội bộ cũng cần nghe nó thì nghe qua sự kiện ứng dụng, không đọc lại từ Kafka, để tránh độ trễ và vòng phụ thuộc vào hạ tầng.

### Máy trạng thái nhiệm vụ

Nhiệm vụ là đối tượng có khối lượng thay đổi trạng thái lớn nhất hệ thống. Theo thiết kế ba tầng điều phối của ADR-04 (chương 8), vòng đời nhiệm vụ không chạy trên engine BPMN mà trên một máy trạng thái khai báo trong mã. Bảng chuyển trạng thái được tham số hoá bởi Policy Pack: Policy Pack có thể bật hoặc tắt một chuyển trạng thái (ví dụ cho phép hay không cho phép tạm dừng), thêm điều kiện bảo vệ bằng CEL, quy định vai trò được thực hiện; nhưng tập trạng thái chuẩn là cố định theo baseline.

![Hình 5.2. Máy trạng thái nhiệm vụ task.task.status](kt-may-trang-thai-nhiem-vu.png){width=13cm}

Hình trên thể hiện các trạng thái chuẩn `NHAP`, `DA_GIAO`, `DANG_THUC_HIEN`, `CHO_NGHIEM_THU`, `HOAN_THANH`, cùng hai nhánh `TAM_DUNG` và `HUY`. Cờ `qua_han` không phải trạng thái mà là thuộc tính tính theo lịch ngày làm việc của tenant, vì một nhiệm vụ có thể quá hạn ở bất kỳ trạng thái nào trước khi hoàn thành. Mọi chuyển trạng thái được ghi vào `task.task_state_log` cùng giao dịch, và phát EVT-06 qua outbox.

Mã dưới đây là phần lõi của máy trạng thái trong gói `domain`. Nó không phụ thuộc framework, nhận bảng chuyển trạng thái từ cổng `TransitionTableProvider` và bộ đánh giá điều kiện từ Policy Pack.

```java
// Gộp nhiều tệp để trình bày: TaskStatus.java, TaskCommand.java, Transition.java,
// TransitionTable.java, GuardEvaluator.java, TaskStateMachine.java cùng gói domain.
package vn.viettel.okrkpi.task.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TaskStatus {
  NHAP, DA_GIAO, DANG_THUC_HIEN, CHO_NGHIEM_THU, HOAN_THANH, TAM_DUNG, HUY;
  public boolean isTerminal() { return this == HOAN_THANH || this == HUY; }
}

public sealed interface TaskCommand permits TaskCommand.Assign, TaskCommand.Accept,
    TaskCommand.Submit, TaskCommand.ApproveAcceptance, TaskCommand.RequestRework,
    TaskCommand.Suspend, TaskCommand.Resume, TaskCommand.Cancel {
  String code();
  record Assign() implements TaskCommand { public String code() { return "ASSIGN"; } }
  record Accept() implements TaskCommand { public String code() { return "ACCEPT"; } }
  record Submit(String deliverableId) implements TaskCommand { public String code() { return "SUBMIT"; } }
  record ApproveAcceptance(java.math.BigDecimal qualityLevel) implements TaskCommand { public String code() { return "APPROVE"; } }
  record RequestRework(String reason) implements TaskCommand { public String code() { return "REWORK"; } }
  record Suspend(String reason) implements TaskCommand { public String code() { return "SUSPEND"; } }
  record Resume() implements TaskCommand { public String code() { return "RESUME"; } }
  record Cancel(String reason) implements TaskCommand { public String code() { return "CANCEL"; } }
}

/** Một dòng của bảng chuyển trạng thái, do Policy Pack cung cấp. */
public record Transition(TaskStatus from, String command, TaskStatus to,
                         Set<String> allowedRoles, String guardCel, boolean reasonRequired) {}

public final class TransitionTable {
  private final Map<String, Transition> byKey;
  private final String policyVersion;

  public TransitionTable(Map<String, Transition> byKey, String policyVersion) {
    this.byKey = Map.copyOf(byKey);
    this.policyVersion = policyVersion;
  }
  public java.util.Optional<Transition> find(TaskStatus from, String command) {
    return java.util.Optional.ofNullable(byKey.get(from.name() + ":" + command));
  }
  public String policyVersion() { return policyVersion; }
}

public interface GuardEvaluator {
  boolean test(String celExpression, Map<String, Object> activation);
}

public final class TaskStateMachine {
  private static final Set<TaskStatus> LOCK_EXEMPT = EnumSet.noneOf(TaskStatus.class);

  private final TransitionTable table;
  private final GuardEvaluator guards;

  public TaskStateMachine(TransitionTable table, GuardEvaluator guards) {
    this.table = table;
    this.guards = guards;
  }

  public TransitionResult apply(Task task, TaskCommand cmd, Actor actor,
                                boolean periodWritable, Instant now) {
    if (!periodWritable && !LOCK_EXEMPT.contains(task.status())) {
      return TransitionResult.rejected("PERIOD_LOCKED", "Kỳ đã khoá, không thể chuyển trạng thái");
    }
    var tr = table.find(task.status(), cmd.code()).orElse(null);
    if (tr == null) {
      return TransitionResult.rejected("TRANSITION_NOT_ALLOWED",
          "Không cho phép " + cmd.code() + " từ trạng thái " + task.status());
    }
    if (actor.roles().stream().noneMatch(tr.allowedRoles()::contains)) {
      return TransitionResult.rejected("ROLE_NOT_ALLOWED", "Vai trò không được thực hiện thao tác này");
    }
    if (tr.reasonRequired() && reasonOf(cmd).isBlank()) {
      return TransitionResult.rejected("REASON_REQUIRED", "Thao tác này bắt buộc nêu lý do");
    }
    if (tr.guardCel() != null && !guards.test(tr.guardCel(), task.activation(actor, now))) {
      return TransitionResult.rejected("GUARD_FAILED", "Chưa đáp ứng điều kiện: " + tr.guardCel());
    }
    TaskStatus from = task.status();
    task.moveTo(tr.to(), now);
    return TransitionResult.accepted(from, tr.to(), table.policyVersion(), reasonOf(cmd));
  }

  private static String reasonOf(TaskCommand c) {
    return switch (c) {
      case TaskCommand.RequestRework r -> r.reason();
      case TaskCommand.Suspend s -> s.reason();
      case TaskCommand.Cancel x -> x.reason();
      default -> "";
    };
  }
}
```

Bảng chuyển trạng thái mặc định được khai báo trong Policy Pack dưới khoá `task_lifecycle`. Ví dụ dòng chuyển `CHO_NGHIEM_THU` sang `HOAN_THANH` yêu cầu vai trò `manager` và điều kiện CEL `deliverables.all(d, d.status == 'DA_XAC_NHAN')`, tức mọi sản phẩm của nhiệm vụ phải có minh chứng đã xác nhận, đúng yêu cầu PL 4 III.5 rằng dữ liệu chưa xác nhận không được dùng để tính điểm. Bảng đầy đủ thuộc TL-02, mục quy tắc nghiệp vụ QT-06 và QT-07.

Tại sao máy trạng thái trong mã đủ cho nhiệm vụ mà không cần BPMN? Vì vòng đời nhiệm vụ không có song song, không có chờ đợi dài do hệ thống quản lý, không có định thời phức tạp ngoài cờ quá hạn. Mỗi chuyển trạng thái là một lệnh của con người, được kiểm tra và ghi trong một giao dịch ngắn. Thêm một engine vào giữa chỉ thêm bảng runtime, thêm job, thêm khoá lạc quan trên một bảng nóng khác, mà không thêm khả năng nào cần thiết. Chương 8 định lượng chi phí này.

### Quản lý giao dịch

Ranh giới giao dịch đặt ở lớp `application`, mỗi use case một giao dịch. Mức cô lập là `READ COMMITTED` mặc định của PostgreSQL; xung đột ghi đồng thời được phát hiện bằng khoá lạc quan trên cột `row_version` (annotation `@Version` của JPA) và trả HTTP 409 kèm Problem Details, đáp ứng F9. Các thao tác chốt có điều kiện đếm, như khoá kỳ (không cho phép thêm minh chứng khi đang khoá), dùng khoá hàng `SELECT ... FOR UPDATE` trên dòng `config.period` để tuần tự hoá ngắn gọn.

Ba quy tắc giao dịch là bắt buộc. Thứ nhất, không gọi mạng ra ngoài tiến trình trong giao dịch, trừ lời gọi tới CSDL; mọi tác dụng phụ ra ngoài đi qua outbox. Thứ hai, mọi giao dịch có giới hạn thời gian 5 giây (`@Transactional(timeout = 5)` và `statement_timeout` 30 giây ở tài khoản CSDL làm lưới an toàn cuối cùng), đáp ứng F2. Thứ ba, giao dịch đọc dài như xuất báo cáo không chạy trong lõi mà chuyển sang analytics hoặc tác vụ nền trả mã theo dõi.

```java
package vn.viettel.okrkpi.task.application;

@Service
public class UpdateProgressUseCase {
  private final TaskRepository tasks;
  private final PeriodApi periods;
  private final OutboxWriter outbox;
  private final IdempotencyStore idem;
  private final Clock clock;

  // constructor injection bỏ qua cho gọn

  @Transactional(timeout = 5)
  @RequiresPermission("task:progress:update")
  public TaskView execute(TenantId tenant, TaskId id, int expectedVersion,
                          ProgressInput in, String idempotencyKey, Actor actor) {
    var cached = idem.find(tenant, idempotencyKey);
    if (cached.isPresent()) return cached.get().as(TaskView.class);

    Task task = tasks.findForUpdate(tenant, id, expectedVersion)
        .orElseThrow(() -> new ConcurrentModificationProblem(id, expectedVersion));
    periods.assertWritable(tenant, task.periodId());

    ProgressEntry entry = task.recordProgress(in.percent(), in.note(), in.blocker(), actor, clock.instant());
    tasks.save(task);

    outbox.append(EventEnvelope.of("task.task.progressed.v1", tenant, task.id().value(),
        new TienDoDaCapNhat(task.id(), entry.percent(), entry.blockerFlag(), actor.id()),
        Classification.C1));

    TaskView view = TaskView.from(task);
    idem.remember(tenant, idempotencyKey, view);
    return view;
  }
}
```

### Outbox

Mỗi schema module có bảng `outbox_event` với cấu trúc thống nhất. Debezium dùng Outbox Event Router để định tuyến theo cột `topic`, lấy cột `aggregate_key` làm khoá thông điệp và giữ thứ tự theo khoá trong một partition. Cấu trúc dưới đây là hợp đồng giữa ứng dụng và Debezium; TL-03 chi tiết hoá chỉ mục và phân vùng theo thời gian để dọn dẹp.

```sql
CREATE TABLE task.outbox_event (
  id              uuid        PRIMARY KEY,           -- eventId, UUIDv7
  tenant_id       uuid        NOT NULL,
  aggregate_type  text        NOT NULL,              -- 'task'
  aggregate_key   text        NOT NULL,              -- 'tenantId:aggregateId'
  topic           text        NOT NULL,              -- 'task.task.progressed.v1'
  event_type      text        NOT NULL,              -- 'TienDoDaCapNhat'
  payload         bytea       NOT NULL,              -- Avro nhị phân theo schema Apicurio
  schema_id       bigint      NOT NULL,              -- global id trong Apicurio
  headers         jsonb       NOT NULL,              -- traceparent, actorId, policyVersion, classification
  occurred_at     timestamptz NOT NULL DEFAULT now()
) PARTITION BY RANGE (occurred_at);
```

Ứng dụng chỉ chèn, không bao giờ cập nhật bảng này. Debezium đọc thay đổi từ WAL qua replication slot nên không cần quét bảng; dòng outbox được xoá theo phân vùng sau 3 ngày bằng `pg_partman`, đủ để phục hồi nếu Kafka Connect ngừng lâu. Cấu hình connector mẫu (lọc theo schema `task` để minh hoạ; bản vận hành dùng một connector cho cả CSDL như giải thích bên dưới) như sau.

```json
{
  "name": "outbox-task-cell-hcm",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "plugin.name": "pgoutput",
    "database.hostname": "pg-core-rw.cell-hcm.svc",
    "database.port": "5432",
    "database.user": "${secrets:debezium/user}",
    "database.password": "${secrets:debezium/password}",
    "database.dbname": "core",
    "topic.prefix": "cdc.hcm",
    "slot.name": "outbox_task",
    "publication.name": "pub_outbox_task",
    "table.include.list": "task.outbox_event",
    "tombstones.on.delete": "false",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_key",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType,tenant_id:header:tenantId,headers:header:meta",
    "value.converter": "io.debezium.converters.BinaryDataConverter",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "heartbeat.interval.ms": "10000",
    "snapshot.mode": "no_data"
  }
}
```

Mỗi CSDL có một connector outbox (và một connector phân tích) với replication slot riêng, thay vì một connector cho mỗi schema: mỗi slot phải giải mã toàn bộ WAL của CSDL, nên tách theo schema sẽ giải mã cùng một WAL hơn mười lần và cần hơn một trăm slot ở cell bridge. Mỗi schema vẫn giữ bảng outbox, topic và quyền riêng; khi một module tách thành microservice có CSDL riêng thì nó có connector riêng (xem TL-03, mục 10.2). Độ trễ từ commit tới Kafka được giám sát bằng metric `MilliSecondsBehindSource` của Debezium với ngưỡng cảnh báo 5 giây; độ lớn WAL giữ lại bởi slot được giám sát qua `pg_replication_slots` với ngưỡng 10 GB để tránh đầy đĩa nếu connector ngừng.

### API chính

API của lõi theo quy ước `/api/v1/<module>`. Bảng sau liệt kê các nhóm chính; Phụ lục C liệt kê endpoint cụ thể kèm quyền.

| Tiền tố | Module | Ví dụ endpoint |
|---|---|---|
| `/api/v1/okr` | MOD-01 | `POST /objectives`, `POST /key-results/{id}/checkins` |
| `/api/v1/tasks`, `/api/v1/plans`, `/api/v1/catalog` | MOD-02 | `POST /tasks`, `PATCH /tasks/{id}/progress`, `POST /tasks/{id}/transitions` |
| `/api/v1/products` | MOD-03 | `POST /products/{id}/evidence-versions`, `POST /evidence-versions/{id}/acceptance` |
| `/api/v1/appraisals` | MOD-04 | `POST /appraisals/{id}/self-assessment`, `POST /appraisals/{id}/decision` |
| `/api/v1/policies` | MOD-05 | `POST /policies/{id}/versions/{v}/test-runs`, `POST /policies/{id}/versions/{v}/publish` |
| `/api/v1/dossiers` | MOD-06 | `POST /dossiers/meetings`, `POST /dossiers/{id}/packages` |
| `/api/v1/oversight` | MOD-07 | `POST /oversight/complaints`, `POST /oversight/reopen-requests` |
| `/api/v1/config`, `/api/v1/periods`, `/api/v1/ui` | MOD-08 | `POST /periods/{id}/lock`, `GET /ui/packs/current` |

### Dữ liệu sở hữu

Lõi sở hữu tám schema `okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config` với các bảng như baseline mục 9, cộng schema `org_replica` (bản sao chỉ đọc của dữ liệu tổ chức) và schema kỹ thuật `modulith`. Mỗi schema có tài khoản CSDL riêng `svc_<module>` chỉ có quyền DML trên schema của mình và quyền `SELECT` trên `org_replica`; migration chạy bằng tài khoản `mig_<module>` riêng có quyền DDL, ứng dụng chạy không cần quyền DDL, đáp ứng K1.

Một điểm thực tế cần nói rõ: tám module chạy trong cùng một tiến trình nhưng dùng tám tài khoản CSDL, nên mỗi module có một `DataSource` riêng với pool riêng nhỏ. Cách này cô lập tài nguyên theo module (một module bị truy vấn chậm không ăn hết kết nối của module khác, tức bulkhead ở mức CSDL) và cưỡng chế quyền ở CSDL. Đổi lại, một use case chạm hai module (ví dụ giao nhiệm vụ cần kiểm tra kỳ) phải gọi qua interface công bố của module kia và dùng kết nối của module kia trong một giao dịch riêng chỉ đọc, hoặc dùng bộ đệm. Lõi không dùng giao dịch phân tán XA; khi cần ghi nhất quán hai module, thiết kế đặt dữ liệu chốt vào một module và module kia nhận sự kiện.

### Sự kiện phát và nhận

Lõi phát EVT-01 đến EVT-09, EVT-11, EVT-12, EVT-13, EVT-21, EVT-22, EVT-23, EVT-25 theo baseline. Lõi nhận EVT-10 từ scoring-service (MOD-04), EVT-14 đến EVT-16 từ org-service (cập nhật `org_replica`), EVT-17 từ document-service (MOD-06), EVT-18 từ esign-service (MOD-02, MOD-04, MOD-06), EVT-20 từ analytics (MOD-07), EVT-24 từ integration-gateway (MOD-02). Mọi consumer ghi `inbox_processed` trong cùng giao dịch với tác dụng của nó.

### Co giãn, chịu lỗi, bảo mật

Lõi co giãn ngang bằng HPA theo hai chỉ số: CPU trung bình 60% và độ trễ p95 của API ghi lấy từ Prometheus Adapter với ngưỡng 400 ms. Cell lớn chạy tối thiểu 6 replica, tối đa 40 replica; giới hạn trên được tính từ tổng kết nối CSDL ở chương 13. Mỗi replica yêu cầu 2 vCPU, 4 GiB bộ nhớ, giới hạn 4 vCPU, 4 GiB, heap `-XX:MaxRAMPercentage=70`. Readiness probe trỏ tới `/actuator/health/readiness` gồm kiểm tra pool CSDL; liveness probe trỏ tới `/actuator/health/liveness` không gồm phụ thuộc ngoài để tránh khởi động lại dây chuyền; startup probe cho phép 120 giây.

Về chịu lỗi, lõi gọi workflow-service và evidence-service (lấy siêu dữ liệu) qua client có Resilience4j theo cấu hình chuẩn chương 14. Khi workflow-service không phản hồi, các thao tác duyệt trả 503 kèm `Retry-After`, còn các thao tác không cần duyệt vẫn hoạt động bình thường. Khi Kafka không sẵn sàng, lõi vẫn ghi được vì outbox nằm trong CSDL; sự kiện sẽ được đẩy khi Kafka trở lại.

Về bảo mật, mọi endpoint mặc định từ chối; quyền khai báo bằng `@RequiresPermission` ở use case và được OPA quyết định; truy vấn danh sách áp bộ lọc phạm vi cây tổ chức do OPA trả về qua đánh giá từng phần (partial evaluation) chuyển thành điều kiện SQL. Trường C2 mã hoá bằng `FieldEncryptor` trước khi rời ứng dụng. Chương 16 trình bày đầy đủ.

### Cấu hình mẫu

```yaml
spring:
  application:
    name: mission-core
  threads:
    virtual:
      enabled: true
  jpa:
    open-in-view: false
    properties:
      hibernate.jdbc.batch_size: 50
      hibernate.order_updates: true
      hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS: 500
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: false
      republish-outstanding-events-on-restart: true
okrkpi:
  datasources:
    task:
      url: jdbc:postgresql://pgbouncer.cell-hcm.svc:6432/core?prepareThreshold=0&ApplicationName=mission-core-task
      username: ${DB_TASK_USER}
      password: ${DB_TASK_PASSWORD}
      hikari:
        maximum-pool-size: 8
        minimum-idle: 2
        connection-timeout: 2000
        max-lifetime: 1200000
    appraisal:
      url: jdbc:postgresql://pgbouncer.cell-hcm.svc:6432/core?prepareThreshold=0&ApplicationName=mission-core-appraisal
      username: ${DB_APPRAISAL_USER}
      password: ${DB_APPRAISAL_PASSWORD}
      hikari:
        maximum-pool-size: 4
  tenant:
    claim: tenant_id
    db-setting: app.tenant_id
  opa:
    url: http://127.0.0.1:8181/v1/data/okrkpi/authz
    timeout: 50ms
    decision-cache-ttl: 30s
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
  observations:
    key-values:
      cell: ${CELL_ID}
```

Tham số `prepareThreshold=0` của driver PostgreSQL tắt prepared statement phía máy chủ, cần thiết khi PgBouncer chạy chế độ transaction trước bản 1.21; với PgBouncer từ 1.21 trở lên bật `max_prepared_statements`, tham số này có thể bỏ để giảm chi phí phân tích câu lệnh. Kích thước pool mỗi module nhỏ có chủ đích; cách tính ở chương 13.

## SVC-01 api-gateway

### Vai trò

api-gateway là lối vào duy nhất của mỗi cell cho mọi lưu lượng từ trình duyệt, ứng dụng di động và đối tác. Cổng đảm nhận năm việc: chấm dứt TLS 1.3, lọc tấn công bằng tường lửa ứng dụng web (WAF), xác thực JWT qua JWKS, giới hạn tốc độ theo người dùng, tenant và API, và định tuyến theo đường dẫn, kể cả định tuyến Strangler Fig giữa hệ cũ và nền tảng mới (ADR-20). Ở vùng toàn cục, một lớp APISIX ngoài làm nhiệm vụ định tuyến tenant sang cell.

### Công nghệ và phiên bản

Apache APISIX 3.x (bản LTS hiện hành), etcd 3.5 làm kho cấu hình, plugin Coraza WAF với bộ luật OWASP Core Rule Set 4, plugin `openid-connect` ở chế độ chỉ kiểm bearer token (`bearer_only`), `limit-count` và `limit-req` dùng Valkey làm bộ đếm phân tán, `opentelemetry`, `prometheus`, `request-id`. Cấu hình được quản lý bằng APISIX Ingress Controller qua CRD `ApisixRoute`, nằm trong Git và đồng bộ bằng Argo CD.

### API, dữ liệu và sự kiện

Cổng không có API nghiệp vụ. Nó công bố `/.well-known/` cho cấu hình khám phá và chuyển tiếp mọi `/api/v1/*`. Dữ liệu sở hữu là cấu hình tuyến trong etcd. Cổng không phát hay nhận sự kiện Kafka; log truy cập đi qua Fluent Bit.

### Co giãn, chịu lỗi, bảo mật

Cổng co giãn bằng HPA theo CPU 60% và RPS mỗi pod; cell lớn chạy tối thiểu 4, tối đa 24 pod, mỗi pod 2 vCPU. Một pod APISIX xử lý được khoảng 10.000 đến 15.000 request/giây đơn giản theo số liệu công bố của dự án; với WAF và kiểm JWT, thiết kế ước tính thận trọng 2.000 request/giây mỗi pod, nên cell HCM ở 6.000 request/giây cần 3 pod, tối thiểu 4 để chịu mất một node. Cổng không giữ trạng thái; etcd chạy ba node. Khi Valkey bộ đếm không phản hồi, plugin giới hạn tốc độ chuyển sang bộ đếm cục bộ của từng pod (cấu hình `allow_degradation: true`) để cổng không trở thành điểm lỗi đơn vì một thành phần phụ.

Cổng là điểm khắc phục kiến trúc cho lỗ hổng S03 (bỏ qua xác thực với mọi URL chứa "/public"): danh sách tuyến không yêu cầu xác thực được khai báo tường minh, so khớp chính xác đường dẫn và phương thức, và một bài kiểm thử trong CI quét toàn bộ CRD để bảo đảm mọi tuyến khác đều có plugin xác thực.

### Cấu hình mẫu

```yaml
apiVersion: apisix.apache.org/v2
kind: ApisixRoute
metadata:
  name: mission-core-tasks
  namespace: cell-hcm
spec:
  http:
    - name: tasks-write
      match:
        hosts: ["*.okr.viettel.vn"]
        paths: ["/api/v1/tasks/*", "/api/v1/plans/*"]
        methods: ["POST", "PATCH", "PUT", "DELETE"]
      backends:
        - serviceName: mission-core
          servicePort: 8080
      timeout:
        connect: 2s
        send: 10s
        read: 10s
      plugins:
        - name: openid-connect
          enable: true
          config:
            discovery: "https://id.cell-hcm.okr.viettel.vn/realms/okrkpi/.well-known/openid-configuration"
            bearer_only: true
            use_jwks: true
            token_signing_alg_values_expected: "ES256"
            set_userinfo_header: false
        - name: limit-count
          enable: true
          config:
            count: 60
            time_window: 60
            key_type: var_combination
            key: "$http_x_tenant_id $jwt_sub"
            policy: redis-cluster
            redis_cluster_nodes: ["valkey-0.valkey:6379", "valkey-1.valkey:6379", "valkey-2.valkey:6379"]
            redis_cluster_name: gw-ratelimit
            rejected_code: 429
            allow_degradation: true
            show_limit_quota_header: true
        - name: coraza-filter        # plugin Wasm coraza-proxy-wasm nạp qua cấu hình wasm của APISIX
          enable: true
          config:
            conf: >-
              {"directives_map":{"default":["Include @demo-conf","Include @crs-setup-demo-conf",
              "SecRuleEngine On","Include @owasp_crs/*.conf"]},"default_directives":"default"}
        - name: opentelemetry
          enable: true
          config:
            sampler:
              name: parent_base
              options:
                root:
                  name: trace_id_ratio
                  options:
                    fraction: 1.0
```

Biến `$jwt_sub` trong khoá giới hạn tốc độ là biến do một plugin nhỏ tự viết (Lua, 30 dòng) gán sau khi `openid-connect` xác thực, đọc claim `sub` và `tenant_id`; plugin này cũng ghi đè header `X-Tenant-Id` từ claim để không tin header do client gửi. Giới hạn 60 thao tác ghi mỗi phút mỗi người dùng là giá trị mặc định; cổng có ba tầng giới hạn chi tiết ở chương 14.

## SVC-02 iam

### Vai trò

iam cung cấp định danh, đăng nhập một lần, xác thực đa yếu tố, liên kết VNeID và IdP của khách hàng, quản lý phiên và phát hành token. Đây là thành phần thay thế bảng `USER_TOKENS` dùng chung với vOffice, đáp ứng L2, K2 và là nền để khắc phục S02, S04, S06, S12.

### Công nghệ và phiên bản

Keycloak 26 chạy trên Quarkus, cụm Infinispan nhúng cho phiên, PostgreSQL riêng schema `iam`. Tính năng Organizations của Keycloak 26 được dùng để đại diện tenant trong một realm duy nhất mỗi cell (ADR-08). Các mở rộng tự phát triển gồm: bộ liên kết VNeID (Identity Provider OIDC với kiểm tra `state`, `nonce`, PKCE và ánh xạ số định danh sang thuộc tính đã băm), bộ ánh xạ claim `tenant_id`, `org_unit_ids`, `flows`, `acr`, và trình nghe sự kiện đẩy sự kiện đăng nhập sang Kafka cho phát hiện bất thường.

### API, dữ liệu và sự kiện

API là các endpoint OIDC chuẩn (`/realms/okrkpi/protocol/openid-connect/auth`, `/token`, `/logout`, `/certs`) và Admin REST API chỉ mở trong mạng quản trị. Đồng bộ người dùng nhận từ org-service qua EVT-16 bằng một consumer nhỏ gọi Admin API. Sự kiện đăng nhập thành công, thất bại, đổi MFA được đẩy lên topic `iam.auth.event.v1` (sự kiện kỹ thuật, không thuộc danh mục EVT nghiệp vụ) cho analytics và SOC.

### Co giãn, chịu lỗi, bảo mật

Keycloak co giãn bằng HPA theo CPU; cell lớn chạy 3 đến 8 pod, mỗi pod 2 vCPU, 3 GiB. Tải đăng nhập không lớn vì token sống 15 phút và làm mới bằng refresh token xoay vòng: với 60.000 CCU, mỗi người làm mới 4 lần mỗi giờ, cho khoảng 67 lần làm mới mỗi giây, nhỏ so với năng lực vài trăm lần mỗi giây của mỗi pod. Đỉnh thực sự là đầu giờ làm việc, khi nhiều người đăng nhập cùng lúc; trường hợp này được kiểm chứng bằng kịch bản thử tải đăng nhập dồn trong TL-04.

Cấu hình bảo mật chính: access token 15 phút, ký ES256; refresh token xoay vòng, thu hồi khi dùng lại; phiên SSO tối đa 10 giờ, nhàn rỗi 30 phút; MFA (TOTP hoặc WebAuthn) bắt buộc cho vai trò `platform_admin`, `tenant_admin`, `appraiser`, `decider`, `second_approver`, `inspector`; nâng bậc xác thực (step-up) yêu cầu `acr=aal2` trong 5 phút gần nhất cho thao tác ký và quyết định; khoá tạm 15 phút sau 5 lần sai; mật khẩu băm PBKDF2-SHA512 210.000 vòng hoặc Argon2 theo chính sách Keycloak 26.

## SVC-03 tenant-registry

### Vai trò

tenant-registry là danh bạ toàn cục: mỗi tenant thuộc cell nào, dùng tên miền nào, mô hình thuê bao nào, trạng thái hợp đồng ra sao, đăng nhập qua IdP nào. Đây là service duy nhất của vùng toàn cục mà bộ định tuyến hỏi tới.

### Công nghệ, API, dữ liệu

Go 1.23, thư viện chuẩn `net/http`, `pgx` 5, PostgreSQL toàn cục schema `registry` với các bảng `tenant`, `cell`, `tenant_cell`, `tenant_domain`, `tenant_idp`. API chính:

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/api/v1/registry/resolve?host=` hoặc `?tenant=` | Trả cell, URL IdP, trạng thái; được APISIX ngoài gọi và lưu đệm 5 phút | client nội bộ |
| POST | `/api/v1/registry/tenants` | Tạo tenant, gán cell, khởi động quy trình khởi tạo | `platform_admin` |
| POST | `/api/v1/registry/tenants/{id}/move` | Bắt đầu chuyển tenant sang cell khác | `platform_admin`, hai người duyệt |
| GET | `/api/v1/registry/cells` | Danh sách cell, sức chứa, tải hiện tại | `platform_admin` |

### Co giãn, chịu lỗi

Service không trạng thái, chạy 3 pod trải ba vùng sẵn sàng, PostgreSQL toàn cục ba node đồng bộ. Tải rất nhỏ nhờ lưu đệm ở APISIX. Khi registry ngừng, request mới của tenant đã có trong đệm vẫn đi đúng; tenant chưa có trong đệm nhận 503 với `Retry-After: 30`. Đây là đánh đổi có ý thức để vùng toàn cục không nằm trên đường găng.

## SVC-04 org-service

### Vai trò

org-service là nguồn sự thật cho cây tổ chức, nhân sự, vị trí việc làm, chức danh, phân công, kiêm nhiệm và 9 khối đối tượng theo PL 2A. Nó dùng chung cho cả nền tảng đánh giá và hệ điều hành văn bản (bước 01 của lộ trình tách nền tảng trong Báo cáo BGĐ), thay việc đọc trực tiếp `VHR_ORG`, `VHR_EMPLOYEE` của vOffice (vấn đề K01, tiêu chí K3, L4).

### Dữ liệu hai trục thời gian

Mọi bảng chính của schema `org` mang hai trục thời gian: trục hiệu lực `valid_from`, `valid_to` cho biết thông tin đúng trong khoảng thời gian thực tế nào, và trục ghi nhận `recorded_from`, `recorded_to` cho biết hệ thống biết điều đó từ khi nào. Nhờ vậy, có thể trả lời câu hỏi "vào ngày ra quyết định xếp loại 15/01/2027, theo dữ liệu hệ thống biết lúc đó, ông A giữ chức vụ gì ở đơn vị nào", kể cả khi sau đó có quyết định điều động ghi lùi ngày. Đây là lớp bảo vệ thứ tư trong bảy lớp, đáp ứng I7.

Chi phí của hai trục thời gian chấp nhận được vì tần suất ghi thấp. Một thay đổi phân công không cập nhật tại chỗ mà đóng dòng cũ (đặt `recorded_to`) và chèn dòng mới, tức gấp khoảng hai lần thao tác ghi; với vài nghìn thay đổi tổ chức mỗi ngày toàn quốc, đây là tải không đáng kể. Truy vấn "hiện hành" dùng chỉ mục từng phần `WHERE recorded_to = 'infinity' AND valid_to = 'infinity'`, nên không chậm hơn bảng một trục. TL-03 trình bày ràng buộc loại trừ (`EXCLUDE USING gist`) chống chồng lấn khoảng hiệu lực.

### API, sự kiện

API chính gồm `GET /api/v1/org/units/{id}/tree?asOf=&knownAt=`, `GET /api/v1/org/persons/{id}/assignments?asOf=`, `POST /api/v1/org/assignments` (bổ nhiệm, kiêm nhiệm, điều động), và SCIM 2.0 tại `/scim/v2/Users`, `/scim/v2/Groups` cho đồng bộ từ HRM và IdP khách hàng. org-service phát EVT-14 DonViDaThayDoi, EVT-15 PhanCongDaThayDoi, EVT-16 NhanSuDaThayDoi; nhận dữ liệu từ CSDL CBCCVC và CSDL đảng viên qua integration-gateway.

### Co giãn, chịu lỗi

Service Java 21, Spring Boot 3.5, HPA 3 đến 10 pod. Vì các service khác giữ bản sao `org_replica` cập nhật qua sự kiện, org-service ngừng không làm dừng nghiệp vụ; chỉ các thao tác sửa cây tổ chức bị ảnh hưởng. Đây là lợi ích trực tiếp của mẫu chuyển trạng thái mang theo sự kiện: đường găng của lõi không có lời gọi đồng bộ sang org-service.

## SVC-06 workflow-service

### Vai trò

workflow-service chạy tầng 2 và tầng 3 của kiến trúc điều phối luồng ba tầng (ADR-04): luồng duyệt có con người tham gia bằng BPMN, bảng chọn cấp duyệt bằng DMN, và saga điều phối cho chốt kỳ, phát hành Policy Pack, ký lô. Chương 8 trình bày phân tích đầy đủ cho góp ý G2; mục này chỉ tóm tắt khung thành phần.

### Công nghệ, API, dữ liệu

Flowable 7.1 nhúng trong Spring Boot 3.5, Java 21, CSDL PostgreSQL riêng `flowable` (không chung cụm với lõi ở cell lớn). API công bố cho lõi và frontend:

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/v1/workflow/inbox?assignee=me&limit=` | Hộp việc cần duyệt, đọc từ `wf_task_projection` |
| POST | `/api/v1/workflow/tasks/{taskId}/complete` | Hoàn thành user task với quyết định và lý do |
| POST | `/api/v1/workflow/tasks/batch-complete` | Duyệt hàng loạt có giới hạn 200 mục |
| GET | `/api/v1/workflow/instances/{businessKey}/history` | Lịch sử duyệt của một hồ sơ |
| POST | `/api/v1/workflow/sagas/period-close` | Khởi động saga chốt kỳ (trả 202 và mã theo dõi) |

Dữ liệu gồm bảng `ACT_*` của Flowable, `wf_template` (mẫu luồng đã đăng ký), `wf_binding` (tham số theo tenant, khối, loại hồ sơ) và `wf_task_projection` (bảng phẳng phục vụ hộp việc, cập nhật bởi listener). Service nhận EVT-03, EVT-11, EVT-13, EVT-25 để khởi tạo tiến trình và phát lệnh hoàn thành bước về lõi qua API công bố. Cập nhật 10/2026: tiến trình duyệt OKR, duyệt kế hoạch quý và duyệt đề nghị điều chỉnh được khởi tạo ngay khi đối tượng được gửi duyệt, qua cổng nội bộ `StartApprovalService` của workflow-service, theo TL-02a; lý do là EVT-01, EVT-03 là sự kiện "đã duyệt", xảy ra sau tiến trình chứ không trước (xem Phụ lục D).

### Co giãn

API co giãn bằng HPA 3 đến 12 pod. Bộ thực thi bất đồng bộ (async executor) chạy ở một Deployment riêng cùng image với cờ cấu hình khác, co giãn bằng KEDA theo số job đến hạn trong `ACT_RU_JOB`, 2 đến 8 pod. Tách hai vai trò giúp đỉnh hoàn thành user task không tranh tài nguyên với xử lý timer và job.

## SVC-07 scoring-service

### Vai trò

scoring-service tính điểm KPI của nhiệm vụ, tổng hợp điểm theo nhóm, áp quy tắc xếp loại đề xuất theo Policy Pack, và lưu vết tính. Nó chạy theo hai chế độ: tính dần theo sự kiện khi minh chứng được xác nhận hoặc nhiệm vụ đổi trạng thái, và tính lại hàng loạt khi chốt kỳ hoặc khi phát hành Policy Pack mới cho kỳ chưa khoá.

### Công nghệ, dữ liệu, sự kiện

Java 21, Spring Boot 3.5, Spring Kafka, cel-java, Flowable DMN engine dùng độc lập (không cần BPMN engine) cho bảng xếp loại. Dữ liệu trong schema `scoring`: `score_job`, `score_result`, `score_trace`. Service nhận EVT-06, EVT-08, EVT-09, EVT-13, EVT-22 và phát EVT-10 DiemDaTinh. Đầu vào tính điểm (nhiệm vụ, sản phẩm, mức chất lượng, ngày chậm) được lấy từ sự kiện và từ một API đọc lô của lõi `GET /api/v1/appraisals/inputs?period=&cursor=` trả dữ liệu theo trang 500 hồ sơ, không đọc trực tiếp CSDL của lõi.

### Co giãn

Worker co giãn bằng KEDA theo độ trễ consumer group trên topic đầu vào. Với NFR-06 tính lại 1,2 triệu hồ sơ trong 2 giờ, thông lượng cần là 1.200.000 / 7.200 ≈ 167 hồ sơ/giây. Ước tính một hồ sơ trung bình 25 nhiệm vụ, mỗi nhiệm vụ ba biểu thức CEL đã biên dịch sẵn chạy khoảng 5 µs, cộng tổng hợp và bảng DMN khoảng 1 ms, cộng đọc đầu vào và ghi kết quả theo lô khoảng 10 ms mỗi hồ sơ trên CSDL; một luồng xử lý được khoảng 80 hồ sơ/giây. Với 8 luồng mỗi pod và hiệu suất song song 50% do CSDL, một pod đạt khoảng 300 hồ sơ/giây; KEDA cho phép tối đa 12 pod, nên thời gian tính lại một cell lớn ước khoảng 6 đến 10 phút ở trường hợp tốt và dưới 30 phút khi CSDL bị chia sẻ với tải khác. Biên an toàn so với 2 giờ là lớn; đây là số ước tính cần TL-04 đo.

## SVC-08 evidence-service

### Vai trò

evidence-service lưu trữ tệp minh chứng: nhận tải lên dạng luồng, tính SHA-256 trong khi nhận, ghi vào Ceph RGW với Object Lock chế độ compliance, khử trùng theo mã băm, quét mã độc bằng ClamAV, gọi OCR qua ai-gateway, và cấp URL tải xuống có chữ ký thời hạn ngắn. Phần nghiệp vụ của minh chứng (phiên bản, trạng thái nghiệm thu) thuộc MOD-03 trong lõi; evidence-service chỉ giữ đối tượng và siêu dữ liệu kỹ thuật.

### Công nghệ, API

Java 21, Spring Boot 3.5 WebFlux (non-blocking, phù hợp luồng I/O dài), AWS SDK for Java v2 trỏ tới Ceph RGW, ClamAV 1.4 chạy daemon riêng. API chính:

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| POST | `/api/v1/files/uploads` | Khởi tạo phiên tải, trả `uploadId` và kích thước phần tối đa |
| PUT | `/api/v1/files/uploads/{uploadId}/parts/{n}` | Gửi một phần 5 đến 16 MB, hỗ trợ tải tiếp khi mất mạng |
| POST | `/api/v1/files/uploads/{uploadId}/complete` | Hoàn tất, trả `objectId`, `sha256`, `dedup` |
| GET | `/api/v1/files/{objectId}/download-url` | URL có chữ ký sống 5 phút sau khi kiểm quyền |
| GET | `/api/v1/files/{objectId}/scan` | Kết quả quét mã độc |

### Dữ liệu, sự kiện, co giãn

Schema `evidence` với `object_meta`, `object_version`, `dedup_index`, `scan_result`; bucket `evidence-<tenant>` ở mô hình silo và bridge, `evidence-pool` với tiền tố theo tenant ở mô hình pool. Khi tải lên hoàn tất, service ghi siêu dữ liệu và một sự kiện kỹ thuật `evidence.object.stored.v1` qua outbox; MOD-03 nhận sự kiện này để gắn đối tượng với phiên bản minh chứng và phát EVT-07. API co giãn bằng HPA theo số kết nối đang mở, worker quét và OCR co giãn bằng KEDA theo hàng đợi. Giới hạn kích thước tệp 50 MB mặc định, cấu hình theo tenant tối đa 200 MB, đáp ứng F6.

## SVC-09 ledger-service

### Vai trò

ledger-service là sổ cái chỉ ghi thêm, hiện thực lớp bảo vệ thứ nhất. Nó tiêu thụ mọi sự kiện có giá trị pháp lý, nối vào chuỗi băm phân vùng theo tenant, niêm phong gốc Merkle mỗi giờ, ký số và gắn dấu thời gian RFC 3161, gửi bản neo cho bên thứ ba, và cung cấp công cụ kiểm tra. Chương 11 trình bày chi tiết thuật toán.

### Công nghệ, dữ liệu, co giãn

Go 1.23 vì cần thông lượng băm cao, dấu chân bộ nhớ nhỏ và mã ngắn dễ kiểm toán; thư viện `crypto/sha256` chuẩn, `franz-go` cho Kafka, `pgx` cho PostgreSQL. CSDL riêng `ledger` trên cụm PostgreSQL riêng của vùng bảo mật, tài khoản ứng dụng chỉ có quyền `INSERT` và `SELECT` trên `ledger_entry`, không có `UPDATE`, `DELETE`; trigger chặn mọi cập nhật. Số replica bằng số partition của topic đầu vào chia cho số partition mỗi consumer, mặc định 8 replica cho 64 partition. Service phát EVT-19 SoCaiDaNiemPhong.

## SVC-10 esign-service

### Vai trò

esign-service thực hiện ký số tại các mốc pháp lý (duyệt kế hoạch, tự đánh giá, thẩm định, quyết định xếp loại, biên bản kiểm điểm, gói hồ sơ) qua một giao diện trừu tượng cắm được nhiều nhà cung cấp, đáp ứng L9 và I4. Nó cũng ký niêm phong sổ cái cho ledger-service.

### Thiết kế giao diện nhà cung cấp

```java
package vn.viettel.okrkpi.esign.spi;

public interface SignatureProvider {
  String id();                                   // "mysign", "viettel-ca", "gov-ca", "bcy"
  Set<SignatureFormat> supportedFormats();       // PADES_B_LTA, CADES_B_T, XADES_B_T
  SignSession begin(SignRequest request);        // tạo yêu cầu ký, có thể cần xác nhận trên điện thoại
  SignStatus poll(String sessionId);             // trạng thái: PENDING, SIGNED, REJECTED, EXPIRED
  List<SignedItem> collect(String sessionId);    // lấy chữ ký hoặc tài liệu đã ký
}
```

Ký từ xa qua MySign là bất đồng bộ: người ký xác nhận trên ứng dụng di động, thời gian chờ thực tế từ vài giây đến vài phút. Vì vậy esign-service không bao giờ được gọi đồng bộ trên đường găng của người dùng; lõi tạo yêu cầu ký qua saga, giao diện hiển thị trạng thái "chờ ký", và EVT-18 KySoDaHoanTat đến khi xong. Ký lô cho quyết định xếp loại hàng loạt (NFR-06, 100.000 phiếu trong 4 giờ) dùng một phiên ký với danh sách băm tài liệu, người quyết định xác nhận một lần; với ký bằng HSM của tổ chức (dấu của cơ quan), thông lượng HSM khoảng 1.000 chữ ký ECDSA P-256 mỗi giây theo thông số phổ biến, nên 100.000 phiếu ký trong vài phút, còn thời gian chủ yếu nằm ở sinh PDF (chương 10).

Dữ liệu trong schema `esign`: `sign_request`, `sign_item`, `certificate_ref`. Service chạy trong vùng bảo mật, truy cập HSM qua PKCS#11, co giãn bằng HPA cho API và KEDA cho worker ký lô.

## SVC-11 document-service

### Vai trò

document-service sinh các biểu mẫu Mẫu 1A, 1B, 1C, 1D, Mẫu 2, Mẫu 3, bảng chấm điểm và gói hồ sơ PDF/A từ dữ liệu, đáp ứng H5. Mẫu được quản lý có phiên bản theo Policy Pack để khi biểu mẫu thay đổi theo hướng dẫn mới, kỳ cũ vẫn sinh lại đúng mẫu cũ.

### Công nghệ

Java 21, Spring Boot 3.5, docx4j 11 (giấy phép Apache 2.0) để trộn dữ liệu vào mẫu DOCX, Gotenberg 8 (dịch vụ chuyển đổi dựa trên LibreOffice, Apache 2.0) để chuyển DOCX sang PDF/A-2b, OpenPDF (LGPL/MPL) để ghép và gắn siêu dữ liệu. iText 7 không được dùng vì giấy phép AGPL-3.0 không cho phép đóng gói lại trong bản cài phân phối cho khách hàng mà không mua giấy phép thương mại, đúng vấn đề E03 của bản đánh giá.

### API, dữ liệu, sự kiện, co giãn

API: `POST /api/v1/dossiers/render-jobs` (trả 202 và `Location: /api/v1/jobs/{id}`), `GET /api/v1/jobs/{id}`. Dữ liệu trong schema `docgen` (`template`, `render_job`, `render_output`) và bucket tệp sinh ra. Service nhận lệnh sinh từ saga và phát EVT-17 HoSoSoDaSinh. Worker co giãn bằng KEDA theo hàng đợi. Ước tính sinh một phiếu 2 trang khoảng 300 đến 600 ms trên Gotenberg; NFR-06 yêu cầu 100.000 phiếu trong 4 giờ, tức khoảng 7 phiếu/giây, cần khoảng 4 đến 6 pod Gotenberg mỗi pod 2 luồng chuyển đổi, nằm trong khả năng co giãn của cell.

## SVC-12 notification-service

### Vai trò

notification-service gửi thông báo qua bốn kênh: thông báo trong ứng dụng thời gian thực qua Server-Sent Events, thông báo đẩy di động (FCM, APNs, hoặc kênh nội bộ ở bản cách ly), SMS qua cổng SMS của Viettel, và email. Nó thay việc ghi thẳng vào bảng `NOTIFICATION`, `SMS_MASTER` của vOffice (vấn đề K01, tiêu chí K4).

### Công nghệ, dữ liệu, sự kiện

Go 1.23 vì mỗi pod cần giữ hàng chục nghìn kết nối SSE dài với chi phí bộ nhớ thấp; Valkey Streams làm bus phân phối giữa các pod (người dùng kết nối vào pod nào thì pod đó nhận thông điệp). Schema `notify` với `notification`, `delivery`, `channel_pref`, `template`. Service nhận EVT-21 YeuCauThongBao từ mọi module và một số sự kiện nghiệp vụ trực tiếp (EVT-04, EVT-07, EVT-11, EVT-20); không phát sự kiện nghiệp vụ.

### Co giãn, chịu lỗi

API SSE co giãn bằng HPA theo số kết nối mỗi pod (ngưỡng 20.000); worker gửi kênh ngoài co giãn bằng KEDA theo độ dài hàng đợi theo kênh. Mỗi kênh ngoài có bulkhead và circuit breaker riêng; khi cổng SMS lỗi, thông báo SMS xếp hàng và thử lại có giới hạn, thông báo trong ứng dụng vẫn đi bình thường. Thông báo hàng loạt cuối kỳ (ví dụ nhắc 1 triệu người nộp hồ sơ) được chia nhỏ theo tenant và giới hạn tốc độ theo kênh để không làm nghẽn cổng SMS.

## SVC-13 integration-gateway

### Vai trò

integration-gateway là cổng tích hợp và lớp chống ô nhiễm giữa nền tảng và mọi hệ thống ngoài: vOffice, iOffice, Trục liên thông văn bản quốc gia (EdXML qua X-Road), LGSP, NDXP, CSDL quốc gia CBCCVC, CSDL đảng viên, HRM, HIS. Chương 6 trình bày đầy đủ kiến trúc tích hợp.

### Công nghệ, dữ liệu, sự kiện

Java 21, Spring Boot 3.5, Apache Camel 4 (giấy phép Apache 2.0) với các thành phần `camel-kafka`, `camel-rest`, `camel-cxf` cho SOAP, `camel-sftp`, `camel-jackson`; Resilience4j qua `camel-resilience4j`. Schema `integ` với `connector`, `inbox_message`, `mapping_xref`, `sync_cursor`, `dead_letter`. Service phát EVT-24 VanBanChiDaoDaNhan và các sự kiện đồng bộ tổ chức đầu vào cho org-service; nhận EVT-12, EVT-19 và các sự kiện cần chia sẻ ra ngoài.

### Co giãn, chịu lỗi

Mỗi bộ kết nối chạy như một route Camel có bulkhead riêng, circuit breaker riêng, và hàng đợi thư chết riêng. Service co giãn bằng HPA 2 đến 8 pod. Để một đối tác chậm không ảnh hưởng đối tác khác, các bộ kết nối có tải lớn hoặc giao thức đặc thù (ví dụ X-Road cần security server riêng) được triển khai thành Deployment riêng cùng image, cấu hình chỉ bật route tương ứng.

## SVC-14 ai-gateway

### Vai trò

ai-gateway là cổng mô hình ngôn ngữ chỉ đọc, cung cấp trợ lý soạn OKR, soát KR mơ hồ, trích nhiệm vụ từ văn bản chỉ đạo, OCR minh chứng, tóm tắt tuần cho lãnh đạo và giải thích điểm số. Chương 17 trình bày kiến trúc AI.

### Công nghệ, dữ liệu, co giãn

Python 3.12, FastAPI, vLLM phục vụ mô hình ngôn ngữ tiếng Việt của Viettel AI tự lưu trữ, pgvector trên PostgreSQL schema `ai` (`doc_chunk`, `suggestion_log`, `guardrail_event`), dịch vụ OCR Viettel. Service chạy trong vùng bảo mật với node GPU riêng; API co giãn bằng HPA theo độ dài hàng đợi suy luận, vLLM co giãn theo số GPU sẵn có. Service không có tài khoản ghi vào bất kỳ schema nghiệp vụ nào.

## SVC-15 analytics

### Vai trò

analytics hiện thực phía đọc của CQRS: nhận thay đổi dữ liệu qua Debezium CDC và sự kiện nghiệp vụ, nạp vào ClickHouse, cung cấp lớp ngữ nghĩa Cube cho chỉ số chuẩn hoá, Superset nhúng cho công cụ dựng dashboard, `analytics-api` cho bảng điều hành, và job phát hiện tám dấu hiệu bất thường phát EVT-20.

### Thiết kế

Dữ liệu vào ClickHouse qua bảng Kafka engine và materialized view. Chỉ cột C0, C1 và cột C2 đã giả danh hoá được đưa vào; cột C3 không bao giờ vào kho phân tích. Mỗi cell có cơ sở dữ liệu `analytics_<cell>`; với mô hình pool, mọi bảng có cột `tenant_id` và Cube áp bộ lọc an ninh theo tenant và phạm vi cây tổ chức lấy từ JWT. Kho phân tích quốc gia chỉ nhận dữ liệu tổng hợp ở mức đơn vị, không nhận dữ liệu cá nhân.

`analytics-api` (Java 21, Spring Boot) phục vụ màn hình bảng điều hành SCR-1601 với các truy vấn đã định nghĩa sẵn qua Cube REST API, lưu đệm kết quả trong Valkey 60 giây theo khoá tenant, đơn vị, kỳ. Đi xuống tới minh chứng (SCR-1603) chuyển từ ClickHouse sang API của lõi khi chạm tới cấp hồ sơ cá nhân, để kiểm quyền trên dữ liệu gốc.

## SVC-16 search

Tìm kiếm toàn văn nhiệm vụ, văn bản chỉ đạo, danh mục công việc chạy trên OpenSearch 2.x, thay Elasticsearch vì Elastic đã chuyển giấy phép sang SSPL/Elastic License từ bản 7.11 (bản AGPL bổ sung năm 2024 vẫn không phù hợp phân phối bản cài). Bộ lập chỉ mục là consumer Kafka nhận EVT-04, EVT-24 và sự kiện danh mục, ghi theo lô. Chỉ mục không chứa C2, C3. Mỗi tenant pool dùng bí danh chỉ mục có bộ lọc `tenant_id`; tenant silo và bridge dùng chỉ mục riêng. Kết quả tìm kiếm được kiểm quyền lại ở lõi trước khi hiển thị chi tiết.

## SVC-17 web-frontend

### Vai trò và cấu trúc

web-frontend gồm shell `fe-shell` Angular 21 và các remote Native Federation: `fe-okr`, `fe-task`, `fe-evidence`, `fe-appraisal`, `fe-dossier`, `fe-oversight`, `fe-dashboard`, `fe-config-studio`, `fe-admin`, cùng plugin chuyên ngành `fe-plugin-yte`, `fe-plugin-giaoduc`, `fe-plugin-llvt`. Mỗi remote là một repo hoặc một dự án trong monorepo Nx, build và triển khai độc lập, dùng chung thư viện `@vt/design-system`, `@vt/sdui-renderer`, `@vt/form-engine`, `@vt/auth`, `@vt/telemetry`. Cấu trúc này đáp ứng A8 và L1. Chương 7 trình bày kiến trúc giao diện theo tenant.

### Phục vụ và nhúng

Tài nguyên tĩnh được build một lần, phục vụ qua CDN với tên tệp có băm nội dung và `Cache-Control: public, max-age=31536000, immutable`; chỉ `index.html` và `federation.manifest.json` có `max-age=60`. Cấu hình môi trường (URL IdP, URL API, cell) nạp lúc chạy từ `/config.json` do nginx sinh từ biến môi trường, đáp ứng K6 và C9. Để nhúng vào cổng văn phòng số của khách hàng, shell cung cấp chế độ web component (`<vt-okr-app>`) dùng Angular Elements, đăng nhập một lần qua OIDC với IdP chung, không phụ thuộc trang đăng nhập riêng của hãng nào; đăng xuất gọi `end_session_endpoint` của Keycloak thay vì trỏ `login.zul` của ZK như hiện trạng.

## SVC-18 mobile-app

### Vai trò và thiết kế

mobile-app là ứng dụng Flutter 3 cho Android và iOS, phục vụ thao tác hằng ngày của cán bộ: việc của tôi, cập nhật tiến độ, check-in KR, chụp minh chứng có OCR, tự đánh giá, ký số qua MySign. Ứng dụng theo hướng ngoại tuyến trước: bản nháp lưu ở SQLite mã hoá bằng SQLCipher với khoá trong Android Keystore hoặc iOS Keychain; khi có mạng, hàng đợi đồng bộ gửi lên với `Idempotency-Key` để không tạo bản ghi trùng.

Ứng dụng dùng chung JSON Schema biểu mẫu và token thiết kế với web (chương 7): token được chuyển thành `ThemeData`, biểu mẫu động được dựng bằng bộ dựng Flutter đọc cùng schema, và biểu thức CEL được kiểm tra bằng thư viện CEL Dart hoặc, khi chưa có thư viện đủ trưởng thành, bằng cách gọi API kiểm tra của máy chủ khi trực tuyến và hoãn kiểm tra khi ngoại tuyến. Chế độ Đảng viên cao tuổi bật bộ token chữ lớn, tương phản cao và luồng rút gọn, đáp ứng NFR-11.

# Kiến trúc tích hợp và dữ liệu luân chuyển

Tích hợp là nơi hệ thống hiện tại yếu nhất về kiến trúc: dữ liệu nền đi qua 17 điểm ghép nối mức bảng với vOffice, tác vụ phụ chạy trong bộ nhớ, chưa có cổng tích hợp, chưa có hợp đồng công bố. Chương này trình bày kiến trúc tích hợp đích, gồm tích hợp nội bộ hướng sự kiện, hợp đồng API và sự kiện, lớp chống ô nhiễm, các bộ kết nối, cách chuyển dần từ hệ cũ và mô hình dữ liệu chuẩn.

## Tích hợp hướng sự kiện

### Vì sao hướng sự kiện

Giữa các thành phần nội bộ, kiến trúc chọn tích hợp hướng sự kiện (event-driven) qua Kafka làm mặc định, và chỉ dùng lời gọi đồng bộ khi cần câu trả lời tức thời. Lựa chọn này có ba lý do cụ thể. Thứ nhất, nó cô lập thời gian giữa các bên: khi notification-service ngừng năm phút, lõi vẫn giao việc bình thường và thông báo được gửi bù khi service trở lại. Thứ hai, nó cho phép thêm bên tiêu thụ mà không sửa bên phát: khi bổ sung bộ phát hiện bất thường, chỉ cần một consumer mới trên các topic sẵn có. Thứ ba, nó là cơ chế san tải tự nhiên: tính điểm hàng loạt cuối kỳ đọc từ hàng đợi theo tốc độ worker chịu được, không đẩy ngược áp lực về lõi.

### Bảo đảm giao nhận: outbox và inbox

Bảo đảm của toàn hệ là ít nhất một lần (at-least-once) kèm xử lý idempotent, tương đương hiệu quả đúng một lần ở mức nghiệp vụ. Phía phát, mẫu hộp thư đi giao dịch bảo đảm không mất: sự kiện được ghi vào `outbox_event` cùng giao dịch với dữ liệu, nên hoặc cả hai cùng có, hoặc cả hai cùng không. Debezium đọc WAL và đẩy lên Kafka với `acks=all` và `enable.idempotence=true`; nếu Kafka Connect khởi động lại giữa chừng, nó đọc lại từ vị trí đã xác nhận cuối cùng, nên có thể gửi trùng nhưng không mất.

Phía nhận, mẫu hộp thư đến idempotent bảo đảm không xử lý trùng. Mỗi consumer chèn `event_id` vào bảng `inbox_processed(event_id, consumer, processed_at)` trong cùng giao dịch với tác dụng nghiệp vụ; nếu chèn xung đột khoá chính thì sự kiện đã được xử lý, consumer bỏ qua và xác nhận offset.

![Hình 6.1. Luồng sự kiện từ outbox qua Kafka tới consumer có inbox idempotent](kt-outbox-kafka.png){width=16cm}

Hình trên thể hiện đầy đủ chuỗi. Ở bước 1 và 2, mission-core ghi nhiệm vụ và dòng outbox trong một giao dịch. Ở bước 3 và 4, Debezium nhận thay đổi từ WAL qua giao thức giải mã logic `pgoutput` và đẩy thông điệp Avro lên topic đích với khoá `tenantId:aggregateId` và header `traceparent`. Ở bước 5 và 6, notification-service nhận thông điệp và chèn vào inbox. Nhánh `alt` cho thấy hai trường hợp: sự kiện mới thì xử lý rồi xác nhận offset; sự kiện đã xử lý thì bỏ qua và xác nhận offset. Ghi chú cuối nói về xử lý lỗi: một thông điệp lỗi lặp lại 5 lần với backoff tăng dần được chuyển sang topic thư chết `<topic>.dlq` của consumer đó, kèm lý do, để không chặn partition.

Mã consumer chuẩn dùng Spring Kafka như sau. Điểm quan trọng là inbox và tác dụng nằm trong cùng giao dịch CSDL, còn xác nhận offset diễn ra sau khi giao dịch commit.

```java
@Component
class EvidenceAcceptedConsumer {
  private final InboxRepository inbox;
  private final IncrementalScoringUseCase scoring;

  @KafkaListener(topics = "product.evidence.accepted.v1", groupId = "scoring-incremental",
                 concurrency = "6")
  @Transactional(transactionManager = "jdbcTx", timeout = 10)
  public void on(ConsumerRecord<String, MinhChungDaXacNhan> rec, Acknowledgment ack) {
    UUID eventId = UUID.fromString(header(rec, "id"));
    if (!inbox.tryInsert(eventId, "scoring-incremental")) {   // INSERT ... ON CONFLICT DO NOTHING
      ack.acknowledge();
      return;
    }
    TenantContext.run(rec.value().getTenantId(), () -> scoring.onEvidenceAccepted(rec.value()));
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { ack.acknowledge(); }
    });
  }
}
```

### Thứ tự và phân vùng

Thứ tự sự kiện chỉ được bảo đảm trong cùng một khoá thông điệp, nhờ Kafka giữ thứ tự trong một partition. Khoá `tenantId:aggregateId` bảo đảm mọi sự kiện của cùng một nhiệm vụ hoặc cùng một hồ sơ đến consumer đúng thứ tự phát. Sự kiện của hai aggregate khác nhau không có thứ tự tương đối; consumer cần thứ tự giữa aggregate (hiếm) phải dùng trường `occurredAt` và phiên bản aggregate để sắp xếp. Số partition mặc định là 24 cho topic nghiệp vụ ở cell lớn, 12 ở cell nhỏ; topic đầu vào của ledger-service dùng 64 partition để khớp 64 phân vùng chuỗi băm.

## Quy ước topic, CloudEvents và AsyncAPI

### Quy ước topic

Tên topic theo dạng `<miền>.<thực-thể>.<sự-kiện>.v<phiên-bản>`, chữ thường, ví dụ `appraisal.rating.decided.v1`. Miền là tên module hoặc service phát. Khi hợp đồng thay đổi không tương thích, topic mới `.v2` được tạo và bên phát ghi song song hai topic trong thời gian chuyển tiếp tối thiểu một quý. Topic kỹ thuật không thuộc danh mục EVT dùng tiền tố `sys.`, ví dụ `sys.evidence.object.stored.v1`; topic thư chết dùng hậu tố `.dlq.<consumer>`.

Thời gian lưu mặc định 7 ngày cho topic nghiệp vụ, 30 ngày cho topic đầu vào sổ cái và tính điểm để phục vụ tính lại, và nén theo khoá (`cleanup.policy=compact`) cho topic trạng thái như `org.unit.changed.v1` để consumer mới dựng được bản sao đầy đủ mà không cần ảnh chụp riêng.

### Phong bì sự kiện

Nội bộ dùng Avro qua Apicurio Registry với chế độ tương thích `BACKWARD`: consumer mới đọc được sự kiện cũ, nên thêm trường tuỳ chọn là thay đổi an toàn. Mọi sự kiện có phong bì chung như baseline quy định. Ra ngoài qua integration-gateway, sự kiện được chuyển thành CloudEvents 1.0 định dạng JSON có cấu trúc. Ví dụ EVT-12 khi chia sẻ kết quả xếp loại lên CSDL CBCCVC:

```json
{
  "specversion": "1.0",
  "id": "0192a3f4-7c1e-7b2a-9d41-5f0c8e7a1b23",
  "source": "urn:vt-okrkpi:cell-hcm:appraisal",
  "type": "vn.viettel.okrkpi.appraisal.rating.decided.v1",
  "subject": "appraisal-profile/0192a3e0-1111-7aaa-8bbb-222233334444",
  "time": "2027-01-15T09:30:12.512Z",
  "datacontenttype": "application/json",
  "dataschema": "https://api.okr.viettel.vn/schemas/appraisal.rating.decided.v1.json",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "tenantid": "0191f000-aaaa-7bbb-8ccc-dddd0000eeee",
  "classification": "C2",
  "policyversion": "khoi-dang/quy-dinh-moi@2.1.0",
  "data": {
    "periodCode": "2026-Q4",
    "subjectRef": { "scheme": "cbccvc", "id": "079085001234" },
    "flow": "NHA_NUOC",
    "rating": "HTTNV",
    "score": 84.25,
    "decidedBy": { "scheme": "cbccvc", "id": "079078004321" },
    "signatureRef": "esign:0192a3f4-0000-7000-8000-000000000001"
  }
}
```

Các thuộc tính mở rộng `traceparent`, `tenantid`, `classification`, `policyversion` tuân thủ quy tắc đặt tên thuộc tính mở rộng của CloudEvents (chữ thường, không dấu gạch). Trường dữ liệu C2 ở đây là kết quả xếp loại và số định danh; khi gửi ra ngoài, kênh truyền dùng mTLS và tải trọng được ký bằng JWS để bên nhận kiểm tra nguồn gốc.

### AsyncAPI

Mọi topic công bố cho bên thứ ba được mô tả bằng AsyncAPI 3.0, sinh từ schema Avro trong Apicurio và bổ sung mô tả nghiệp vụ. Tài liệu AsyncAPI được phát hành cùng OpenAPI trên cổng tài liệu đối tác, có phiên bản và chính sách tương thích ngược, đáp ứng L10. Trích đoạn mô tả kênh EVT-04:

```yaml
asyncapi: 3.0.0
info:
  title: Sự kiện công bố của Nền tảng đánh giá cán bộ
  version: 1.4.0
channels:
  nhiemVuDaGiao:
    address: task.task.assigned.v1
    messages:
      NhiemVuDaGiao:
        $ref: '#/components/messages/NhiemVuDaGiao'
operations:
  nhanNhiemVuDaGiao:
    action: receive
    channel:
      $ref: '#/channels/nhiemVuDaGiao'
components:
  messages:
    NhiemVuDaGiao:
      contentType: application/cloudevents+json
      payload:
        type: object
        required: [taskId, title, assigneeUnitId, dueDate]
        properties:
          taskId: { type: string, format: uuid }
          title: { type: string, maxLength: 500 }
          assigneeUnitId: { type: string, format: uuid }
          dueDate: { type: string, format: date }
          sourceDocumentRef: { type: string }
```

## Hợp đồng API theo OpenAPI

Mọi API công khai có mô tả OpenAPI 3.1, đáp ứng A7. Theo ADR-32 (cập nhật 10/2026, chi tiết ở Phụ lục F), toàn bộ API công khai đi theo hướng "hợp đồng trước": bộ tệp OpenAPI 3.1 trong thư mục `contracts/openapi/` là nguồn sự thật và được duyệt trước khi viết mã ở bất kỳ phía nào. Frontend sinh kiểu TypeScript bằng openapi-typescript; backend sinh interface controller và DTO bằng openapi-generator, controller cài đặt interface đó. springdoc vẫn được giữ nhưng chỉ để đối chiếu ngược: CI chạy `openapi-diff` giữa tài liệu sinh lúc chạy và tệp hợp đồng, mọi khác biệt làm build thất bại; thay đổi phá vỡ tương thích phải đi kèm tăng phiên bản đường dẫn. Mỗi thao tác mang phần mở rộng `x-mission` để truy vết QT, SCR, owner, EVT, vai trò, phạm vi, AAL, bốn mắt và BR. API cho đối tác do integration-gateway công bố cũng theo hướng hợp đồng trước, với kho hợp đồng riêng.

Quy ước chung của mọi API: JSON với tên trường `camelCase`; không còn endpoint `application/x-www-form-urlencoded` mang chuỗi JSON (35 endpoint cũ của backend-missions được thay trong đợt 2); phân trang theo con trỏ với `limit` tối đa 200; ghi có `Idempotency-Key`; cập nhật có `If-Match`; lỗi theo RFC 9457 (chương 14); tác vụ dài trả 202 kèm `Location`. Chính sách phiên bản: phiên bản chính nằm trong đường dẫn (`/api/v1`), phiên bản cũ được duy trì tối thiểu 12 tháng sau khi phiên bản mới phát hành, với header `Deprecation` và `Sunset` theo RFC 9745 và RFC 8594.

## Lớp chống ô nhiễm

Lớp chống ô nhiễm (Anti-Corruption Layer) là thành phần dịch giữa mô hình của hệ ngoài và mô hình chuẩn của nền tảng, để khái niệm, mã và quy ước của hệ ngoài không "rò" vào lõi. Ví dụ, vOffice biểu diễn đơn vị bằng `VHR_ORG.ORG_ID` số nguyên và đường dẫn cây dạng chuỗi; iOffice có mã đơn vị theo quy ước tỉnh; CSDL CBCCVC dùng mã định danh cá nhân. Lõi không biết bất kỳ mã nào trong số đó; nó chỉ biết `org_unit.id` dạng UUIDv7.

![Hình 6.2. Lớp chống ô nhiễm và bộ kết nối trong integration-gateway](kt-tich-hop-acl.png){width=16cm}

Mỗi bộ kết nối trong integration-gateway gồm ba phần như hình trên. Adapter nói giao thức của đối tác (REST, SOAP, EdXML, SFTP, X-Road). Translator dịch mô hình đối tác sang mô hình chuẩn tám nhóm PL 4 và ngược lại, tra hoặc tạo ánh xạ trong `integ.mapping_xref(connector_id, external_type, external_id, internal_type, internal_id, valid_from)`. Publisher phát CloudEvents nội bộ lên Kafka. Thông điệp đến được ghi vào `integ.inbox_message` trước khi xử lý để chống trùng và để phát lại được; thông điệp lỗi sau khi hết số lần thử vào `integ.dead_letter` với giao diện xử lý thủ công cho quản trị tenant.

Mã Camel cho route nhận văn bản chỉ đạo từ vOffice minh hoạ cấu trúc này. Route chạy theo cơ chế kéo có con trỏ (lưu ở `integ.sync_cursor`) vì vOffice hiện tại chưa phát sự kiện; khi vOffice có webhook, chỉ cần thay `from`.

```java
@Component
class VOfficeDirectiveRoute extends RouteBuilder {
  private final JdbcMessageIdRepository inboxRepository; // trỏ tới integ.inbox_message

  VOfficeDirectiveRoute(JdbcMessageIdRepository inboxRepository) {
    this.inboxRepository = inboxRepository;
  }

  @Override
  public void configure() {
    errorHandler(deadLetterChannel("direct:integ-dead-letter")
        .maximumRedeliveries(5).redeliveryDelay(2000).backOffMultiplier(2)
        .useExponentialBackOff().retryAttemptedLogLevel(LoggingLevel.WARN));

    from("timer:voffice-directives?period=30000")
        .routeId("voffice-directives-pull")
        .bean(SyncCursorService.class, "load('voffice','directive')")
        .circuitBreaker()
            .resilience4jConfiguration()
                .failureRateThreshold(50).slidingWindowSize(20)
                .waitDurationInOpenState(30).timeoutEnabled(true).timeoutDuration(5000)
            .end()
            .toD("https://voffice-api.${header.tenantHost}/api/v2/directives?updatedSince=${header.cursor}&limit=200")
        .onFallback()
            .log(LoggingLevel.WARN, "vOffice không phản hồi, giữ con trỏ")
            .stop()
        .end()
        .unmarshal().json(VOfficeDirectivePage.class)
        .split(simple("${body.items}")).streaming()
            .idempotentConsumer(simple("voffice:${body.id}:${body.version}"), inboxRepository)
                .bean(VOfficeDirectiveTranslator.class)   // ACL: sang VanBanChiDao chuẩn
                .marshal().json()
                .toD("kafka:integ.document.received.v1?key=${header.aggregateKey}")
            .end()
        .end()
        .bean(SyncCursorService.class, "advance");
  }
}
```

## Các bộ kết nối

Bảng dưới đây liệt kê các bộ kết nối đích, giao thức, hướng dữ liệu, sự kiện liên quan và đợt triển khai theo lộ trình của bản đánh giá. Thứ tự kết nối theo khuyến nghị của bản đánh giá: văn phòng số cùng hãng qua API, Trục liên thông văn bản, LGSP/NDXP và CSDL CBCCVC, rồi văn phòng số hãng khác.

| Bộ kết nối | Giao thức, chuẩn | Hướng | Dữ liệu và sự kiện | Đợt |
|---|---|---|---|---|
| vOffice, ĐHTN (Viettel) | REST OpenAPI, CloudEvents qua webhook khi có | Hai chiều | Văn bản chỉ đạo, văn bản đã ban hành làm minh chứng (EVT-24); tổ chức trong giai đoạn chuyển tiếp | 1 |
| iOffice (VNPT) và hệ văn bản khác | REST hoặc SOAP của đối tác, kiểm thử hợp đồng Pact | Vào | Văn bản chỉ đạo, minh chứng | 3 |
| Trục liên thông văn bản quốc gia | EdXML theo QĐ 28/2018/QĐ-TTg, X-Road security server | Hai chiều | Gửi, nhận văn bản và thông tin nhiệm vụ giữa cơ quan | 2 |
| LGSP cấp tỉnh, bộ; NDXP | REST qua nền tảng tích hợp, chứng thư tổ chức | Hai chiều | Chỉ tiêu KT-XH (QĐ 10.2.2), chia sẻ kết quả tổng hợp lên IOC | 2 |
| CSDL quốc gia CBCCVC | Dịch vụ chia sẻ qua NDXP | Hai chiều | Nhận hồ sơ CBCCVC vào org-service; chia sẻ ngược kết quả đánh giá (EVT-12) theo quy định | 2 |
| CSDL đảng viên | Kết nối trên hạ tầng riêng khối Đảng | Vào, ra theo quy định | Thông tin đảng viên, tổ chức đảng | 3 |
| VNeID | OIDC Authorization Code + PKCE, state, nonce qua Keycloak | Vào | Định danh, xác thực | 1 |
| MySign, Viettel-CA, CA chuyên dùng Chính phủ, Ban Cơ yếu | API ký từ xa, PKCS#11, CSC API | Ra | Ký số mốc pháp lý, ký niêm phong; qua esign-service | 2 |
| TSA | RFC 3161 qua HTTP | Ra | Dấu thời gian cho chữ ký và niêm phong sổ cái | 2 |
| HRM, ERP, HIS, quản lý trường học | SCIM 2.0, REST, tệp lô qua SFTP | Vào | Nhân sự, chỉ số chuyên môn | 3 |

Hai bộ kết nối cần lưu ý riêng. Với VNeID, luồng hiện tại ở core và web thiếu kiểm tra `state` và `nonce` (vấn đề L03). Trong kiến trúc mới, VNeID là một Identity Provider của Keycloak; Keycloak tự sinh và kiểm tra `state`, `nonce`, dùng PKCE với `S256`, và các ứng dụng không bao giờ nói chuyện trực tiếp với VNeID, nên lỗi này bị loại ở mức kiến trúc. Với bộ kết nối văn phòng số hãng khác, rủi ro chính là hợp đồng của đối tác thay đổi không báo trước. Kiểm thử hợp đồng hướng người tiêu dùng (consumer-driven contract testing) bằng Pact được dùng: integration-gateway công bố kỳ vọng của mình lên Pact Broker, và một job định kỳ chạy kiểm tra trên môi trường thử của đối tác; khi đối tác chưa hợp tác chạy kiểm tra phía họ, job chạy kiểm tra ngược trên bản ghi tương tác thật đã che dữ liệu, đáp ứng L12.

## Chuyển dần theo Strangler Fig với APISIX

> Cập nhật 23/10/2026: ADR-20 không áp dụng, vì Mission được phát triển như một ứng dụng mới, không chuyển dần từ hệ hiện tại (quyết định của người phụ trách, xem Phụ lục D mục D.6). Nội dung dưới đây chỉ còn giá trị tham khảo.

Nền tảng mới không thay hệ cũ trong một lần. Theo ADR-20 và lộ trình của bản đánh giá, chức năng được chuyển dần theo thứ tự: danh mục và cấu hình, rồi đăng ký và giao việc, rồi đánh giá và xếp loại, cuối cùng là dashboard. Cơ chế chuyển là định tuyến theo đường dẫn tại APISIX, có thể bật theo tenant.

![Hình 6.3. Định tuyến Strangler Fig giữa hệ cũ và nền tảng mới tại APISIX](kt-strangler.png){width=16cm}

Ở mỗi bước chuyển, ba việc diễn ra cùng nhau. Thứ nhất, tuyến mới được khai báo trên APISIX với điều kiện theo tenant (biến `$jwt_tenant` thuộc danh sách tenant đã chuyển), các tenant khác vẫn đi hệ cũ. Thứ hai, trong thời gian chuyển tiếp, dữ liệu được đồng bộ hai chiều qua integration-gateway để người dùng chưa chuyển vẫn thấy dữ liệu của người đã chuyển, ví dụ nhiệm vụ do đơn vị đã chuyển giao cho đơn vị chưa chuyển; đồng bộ dùng `mapping_xref` để tránh vòng lặp. Thứ ba, sau khi toàn bộ tenant của một chức năng đã chuyển và chạy ổn định một kỳ đánh giá, mã tương ứng trong hệ cũ (khoảng 100,8 nghìn dòng mã KPI trong core) bị xoá và bảng tương ứng bị đóng băng, đáp ứng K8.

```yaml
apiVersion: apisix.apache.org/v2
kind: ApisixRoute
metadata:
  name: strangler-catalog
spec:
  http:
    - name: catalog-new
      priority: 10
      match:
        paths: ["/api/v1/catalog/*"]
        exprs:
          - subject: { scope: Variable, name: jwt_tenant }
            op: In
            set: ["0191f000-aaaa-7bbb-8ccc-dddd0000eeee", "0191f000-aaaa-7bbb-8ccc-dddd0000ffff"]
      backends: [{ serviceName: mission-core, servicePort: 8080 }]
    - name: catalog-legacy
      priority: 1
      match:
        paths: ["/api/v1/catalog/*"]
      backends: [{ serviceName: backend-missions-legacy, servicePort: 8080 }]
```

Danh sách tenant đã chuyển trong ví dụ là giá trị tĩnh cho dễ đọc; trong vận hành, danh sách được lấy từ tenant-registry và cờ tính năng, cập nhật qua Argo CD khi đội vận hành quyết định chuyển một tenant.

## Mô hình dữ liệu chuẩn theo tám nhóm PL 4

Mô hình dữ liệu chuẩn (Canonical Data Model, hay ngôn ngữ công bố theo Domain-Driven Design) là mô hình trao đổi chung cho mọi bộ kết nối, đáp ứng L11. Nó bám đúng tám nhóm dữ liệu tối thiểu theo PL 4 mà Phụ lục A6 của Báo cáo BGĐ liệt kê, và là đề xuất của Viettel cho chuẩn dữ liệu và API đánh giá cán bộ (kiến nghị thể chế số 1). Mỗi thực thể có mã định danh toàn cục dạng URN, lịch sử thay đổi và liên kết truy vết hai chiều.

| Nhóm PL 4 | Thực thể chuẩn | Định danh | Thuộc tính cốt lõi | Nguồn trong nền tảng |
|---|---|---|---|---|
| 1. Chủ thể, kỳ | Subject (tập thể, cá nhân), Position, Assignment, Period | `urn:vt-okrkpi:subject:{uuid}` kèm mã ngoài CBCCVC, đảng viên | Loại chủ thể, đơn vị, vị trí, lịch sử phân công, điều động, kỳ | `org.*`, `config.period` |
| 2. Mục tiêu O, KR | Objective, KeyResult, Checkin | `urn:vt-okrkpi:objective:{uuid}` | Căn cứ, hạn, trọng số, chỉ tiêu, giá trị hiện tại, người xác nhận, liên kết | `okr.*` |
| 3. KPI | KpiDefinition | `urn:vt-okrkpi:kpi:{uuid}` | Nội dung đo, đơn vị, nguồn dữ liệu, tỷ trọng, mức khó | `task.catalog_item`, `policy.*` |
| 4. Chương trình | Program, Package, Milestone | `urn:vt-okrkpi:program:{uuid}` | Tầng 2: chương trình, gói, đợt, mốc kiểm soát | `task.plan`, `task.plan_item` |
| 5. Nhiệm vụ | Task, TaskAssignee | `urn:vt-okrkpi:task:{uuid}` | Trạng thái, chủ trì, phối hợp, liên kết O/KR, văn bản nguồn | `task.task*` |
| 6. Điểm nghẽn, rủi ro | Blocker | `urn:vt-okrkpi:blocker:{uuid}` | Nguyên nhân, người xử lý, hạn xử lý, mức ảnh hưởng | `task.task_blocker` |
| 7. Sản phẩm, minh chứng | Deliverable, EvidenceVersion, Acceptance | `urn:vt-okrkpi:evidence:{uuid}` kèm `sha256` | Phiên bản, tệp, người cập nhật, người xác nhận, thời điểm, mức chất lượng | `product.*`, `evidence.*` |
| 8. Đánh giá, xếp loại | AppraisalProfile, ScoreSnapshot, RatingDecision | `urn:vt-okrkpi:appraisal:{uuid}` | Từng cấu phần, bốn mức (tự đề xuất, thẩm định, đề xuất, quyết định), phiên bản quy tắc | `appraisal.*`, `scoring.*` |

Mô hình chuẩn được công bố dưới dạng JSON Schema 2020-12 (cho API và CloudEvents) và Avro (cho Kafka nội bộ), sinh từ một nguồn duy nhất trong repo `canonical-model`. Bộ kết nối nào cũng dịch về mô hình này; không bộ kết nối nào được phát sự kiện mang cấu trúc riêng của đối tác vào Kafka nội bộ.

# Chuyên đề G1: Giao diện riêng theo tenant

## Câu hỏi và cách tiếp cận

Góp ý G1 của lãnh đạo đặt câu hỏi: mỗi tenant có thể có giao diện riêng, kiến trúc được đề xuất là Server-Driven UI (SDUI) kết hợp Micro-frontends theo ảnh góp ý; cần nghiên cứu xem có áp dụng được không. Ảnh góp ý mô tả một lớp "Tenant UI Orchestration" gồm bốn tầng: lớp Design Token (theme, thương hiệu, cỡ chữ người cao tuổi, biểu tượng), Layout Engine SDUI trả về cây component từ Policy Pack, Dynamic Form Engine dùng Formly và JSON Schema cùng bộ kiểm tra CEL, và Micro-frontend Plugins cho module chuyên sâu (Y tế, Giáo dục, LLVT). Ba ý đi kèm nhấn mạnh: tách design token theo `tenant_id` dưới dạng CSS variables, hỗ trợ chế độ Đảng viên cao tuổi trực tiếp từ token engine; máy chủ trả cấu trúc bố cục trang (Grid, Card, Drawer, Action Button) để frontend chỉ đóng vai trò render engine thuần tuý; và biên dịch biểu thức CEL trong Policy Pack thành WebAssembly hoặc JavaScript nhẹ để kiểm tra tức thì trên trình duyệt.

Cách tiếp cận của chuyên đề này là đánh giá từng lớp riêng theo năm khía cạnh: có áp dụng được với stack Angular 21, Native Federation, PrimeNG không; áp dụng ở mức độ nào; lợi ích cụ thể; rủi ro; và chi phí. Sau đó là thiết kế chi tiết cho phần được áp dụng, và kết luận. Lập trường đã chốt trong baseline và ADR-05 là áp dụng được nhưng có chọn lọc theo lớp; chuyên đề này trình bày lập luận và thiết kế đứng sau lập trường đó.

## Phân tích bốn lớp của kiến trúc đề xuất

### Lớp 1: Design Token theo tenant

Design token là các giá trị thiết kế nguyên tử (màu, phông, cỡ chữ, khoảng cách, bo góc, đổ bóng, biểu tượng, logo) được đặt tên theo ngữ nghĩa, ví dụ `color.primary`, `font.size.body`, thay vì viết cứng giá trị trong CSS. Khi mọi component đọc giá trị qua token, đổi bộ token là đổi diện mạo toàn ứng dụng.

Lớp này áp dụng được trọn vẹn và với chi phí thấp. PrimeNG từ bản 18 dùng hệ thống theme dựa trên design token (styled mode với preset), và Angular hỗ trợ CSS custom properties tự nhiên. Việc còn lại là đưa token ra khỏi bản build và nạp lúc chạy theo tenant. Lợi ích rõ ràng: mỗi tỉnh, mỗi khối có thương hiệu riêng mà không cần bản build riêng (K6); chế độ Đảng viên cao tuổi là một bộ token phụ cho chữ lớn, tương phản cao (NFR-11); thay đổi giao diện không qua quy trình phát hành mã (NFR-07). Rủi ro chính là tenant chọn màu làm giảm tương phản dưới ngưỡng WCAG 2.1 AA; rủi ro này được chặn bằng bộ kiểm tra tương phản tự động khi phát hành UI Pack. Chi phí ước tính khoảng 3 đến 4 tuần người cho pipeline token, preset PrimeNG và bộ kiểm tra.

### Lớp 2: Layout Engine SDUI

SDUI là cách máy chủ gửi xuống không chỉ dữ liệu mà cả mô tả giao diện (cây component, thuộc tính, hành động), còn ứng dụng khách chỉ diễn giải mô tả đó thành màn hình. Ưu điểm lớn của SDUI là thay đổi giao diện không cần phát hành ứng dụng, đặc biệt giá trị với ứng dụng di động phải qua kho ứng dụng.

Lớp này áp dụng được về kỹ thuật, nhưng không nên áp dụng toàn bộ như ý "frontend chỉ đóng vai trò render engine thuần tuý". Lý do nằm ở bản chất các màn hình của nền tảng. Có hai loại màn hình rất khác nhau. Loại thứ nhất là bề mặt cấu hình được, có cấu trúc lặp lại: bố cục trang chủ "Việc của tôi hôm nay" với các widget, trang chi tiết hồ sơ hay nhiệm vụ với nhóm trường và thứ tự tab, biểu mẫu nghiệp vụ, bố cục dashboard. Các bề mặt này khác nhau giữa tenant đúng theo cách SDUI giải quyết tốt: thêm, bớt, sắp xếp khối. Loại thứ hai là màn hình tương tác nặng: cây mục tiêu OKR với kéo thả và liên kết, Config Studio với trình dựng công thức, màn hình thẩm định so sánh hai cột với đi xuống minh chứng, bảng điều hành có drill-down. Các màn hình này có logic tương tác phức tạp, trạng thái cục bộ phong phú, yêu cầu hiệu năng và khả năng truy cập khắt khe; diễn đạt chúng bằng JSON sẽ biến schema bố cục thành một ngôn ngữ lập trình thứ hai, kém hơn TypeScript ở mọi khía cạnh trừ khả năng tải động.

Rủi ro của việc áp SDUI toàn bộ được định lượng như sau. Về kiểm thử, nếu mỗi tenant có thể đổi cấu trúc màn hình lõi, số tổ hợp giao diện cần kiểm thử tăng theo số tenant; với 5.000 tenant, kiểm thử hồi quy giao diện trở nên bất khả thi. Về khả năng truy cập, thứ tự tiêu điểm bàn phím, nhãn ARIA và vùng thông báo động phụ thuộc cấu trúc DOM; cho tenant sắp xếp tuỳ ý thì không còn bảo đảm WCAG. Về an toàn, nếu schema cho phép HTML hay biểu thức tuỳ ý, mỗi UI Pack trở thành một vectơ XSS lưu trữ. Về hiệu năng, một render engine tổng quát luôn chậm hơn component được biên dịch AOT và tối ưu riêng.

Kết luận cho lớp 2 là áp dụng có giới hạn: SDUI chỉ cho bề mặt cấu hình được, chỉ được tham chiếu component trong danh mục cho phép có phiên bản (component registry), không chứa HTML hay JavaScript tuỳ ý. Màn hình tương tác nặng viết bằng mã, nhận token và cấu hình nhẹ (ví dụ ẩn hiện cột, nhãn) nhưng không nhận cấu trúc. Chi phí ước tính khoảng 6 đến 8 tuần người cho renderer, registry và trình biên tập bố cục trong Config Studio.

### Lớp 3: Dynamic Form Engine

Biểu mẫu động là biểu mẫu được dựng từ mô tả dữ liệu (JSON Schema) cộng mô tả giao diện (UI schema), thay vì viết mã từng biểu mẫu. Nền tảng có hàng chục biểu mẫu thay đổi theo văn bản: phiếu tự đánh giá Mẫu 1A đến 1D theo từng đối tượng, phiếu thẩm định, phiếu khiếu nại, phiếu riêng của ngành y tế, giáo dục. Đây là nơi biểu mẫu động đem lại giá trị lớn nhất.

Lớp này áp dụng được trọn vẹn. Formly (`@ngx-formly/core`) là thư viện biểu mẫu động trưởng thành cho Angular, có lớp tích hợp PrimeNG và hỗ trợ dựng từ JSON Schema. Lợi ích: biểu mẫu thay đổi theo Policy Pack mà không viết lại màn hình, đúng mục tiêu ở slide 37 của Báo cáo BGĐ. Rủi ro là kiểm tra hợp lệ phía trình duyệt và phía máy chủ cho kết quả khác nhau, dẫn tới người dùng thấy "hợp lệ" nhưng máy chủ từ chối hoặc ngược lại; rủi ro này được xử lý ở phần thiết kế CEL bên dưới. Chi phí khoảng 4 đến 6 tuần người.

Về đề xuất biên dịch CEL thành WebAssembly, phân tích cho thấy không cần thiết. Biểu thức kiểm tra biểu mẫu ngắn (một đến vài phép so sánh, độ dài dưới 200 ký tự), số lượng mỗi biểu mẫu dưới 100. Thư viện thông dịch CEL bằng JavaScript (cel-js) phân tích và đánh giá một biểu thức như vậy trong khoảng 0,01 đến 0,1 ms trên máy tính phổ thông theo ước tính thiết kế, tức kiểm tra toàn bộ biểu mẫu 50 trường mất dưới 5 ms, không nhận thấy được. Biên dịch sang Wasm, ví dụ đóng gói cel-go bằng TinyGo, cho ra mô-đun khoảng 1 đến 3 MB phải tải, khởi tạo mất hàng chục mili giây, và mỗi lần gọi qua ranh giới JavaScript và Wasm đều tốn chi phí chuyển dữ liệu; tổng chi phí lớn hơn lợi ích với biểu thức ngắn. Wasm chỉ đáng cân nhắc nếu sau này trình duyệt cần chạy toàn bộ công thức tính điểm phức tạp ngoại tuyến, nhu cầu chưa có trong phạm vi.

### Lớp 4: Micro-frontend Plugins

Plugin micro-frontend là module giao diện chuyên ngành được nạp động vào shell theo manifest của tenant. Đội đang dùng Native Federation với Angular 21, nên lớp này áp dụng được với chi phí thấp nhất trong bốn lớp. Lợi ích: nghiệp vụ chuyên sâu của y tế, giáo dục, LLVT nằm trong kho mã riêng, do đội riêng phát triển, chỉ gọi API công bố của lõi, đúng nguyên tắc "đặc thù đi qua ba cửa" ở slide 53. Rủi ro là plugin kém chất lượng làm hỏng trải nghiệm hoặc lộ dữ liệu; rủi ro này được chặn bằng ký số bundle, quét bảo mật như service, CSP chặt, và hợp đồng plugin giới hạn API được dùng. Chi phí khoảng 2 đến 3 tuần người cho cơ chế manifest, kiểm chữ ký và hợp đồng plugin.

### Tổng hợp đánh giá

| Lớp | Áp dụng được | Mức độ áp dụng | Lợi ích chính | Rủi ro chính và biện pháp | Chi phí ước tính |
|---|---|---|---|---|---|
| 1. Design Token | Có | Toàn bộ | Thương hiệu theo tenant, chế độ cao tuổi, không build riêng | Tương phản kém; kiểm tra WCAG tự động khi phát hành | 3 đến 4 tuần người |
| 2. SDUI | Có | Giới hạn ở bề mặt cấu hình được | Bố cục trang chủ, chi tiết, dashboard theo tenant | Kiểm thử, a11y, XSS, hiệu năng; registry đóng, không HTML tuỳ ý | 6 đến 8 tuần người |
| 3. Dynamic Form | Có | Toàn bộ biểu mẫu nghiệp vụ | Biểu mẫu theo Policy Pack không viết lại mã | Lệch kiểm tra hai phía; test chung hai engine | 4 đến 6 tuần người |
| 3'. CEL sang Wasm | Không cần | Thay bằng cel-js | Không có lợi ích đáng kể | Tải nặng, khởi tạo chậm | Tiết kiệm khoảng 3 tuần người |
| 4. Plugin | Có | Cho module chuyên ngành | Đội ngành độc lập, không rẽ nhánh lõi | Plugin xấu; ký số, CSP, hợp đồng API | 2 đến 3 tuần người |

## Thiết kế chi tiết

### Tổng quan kiến trúc giao diện theo tenant

![Hình 7.1. Kiến trúc giao diện theo tenant bốn lớp](kt-giao-dien-tenant.png){width=16cm}

Hình trên thể hiện luồng từ soạn thảo tới hiển thị. Quản trị tenant soạn UI Pack trong Config Studio (SCR-1801, SCR-1802); MOD-08 lưu UI Pack vào `config.ui_pack` và `config.ui_pack_version`, kiểm tra hợp lệ schema, khả năng truy cập và ngân sách kích thước, ký số gói, rồi phát EVT-23 UiPackDaPhatHanh. Một consumer nhỏ đẩy tệp gói lên kho đối tượng gốc của CDN và vô hiệu hoá bộ đệm của đường dẫn "phiên bản hiện hành" của tenant. Shell Angular tải UI Pack từ CDN, lưu bản sao vào IndexedDB, và áp bốn lớp. Biểu mẫu gửi về API của lõi, nơi cel-java kiểm tra lại và là nguồn sự thật. Ứng dụng Flutter đọc cùng UI Pack. Các màn hình tương tác nặng viết bằng mã chỉ nhận lớp token.

### Cấu trúc UI Pack

UI Pack là một tệp JSON có phiên bản ngữ nghĩa, gồm năm phần: siêu dữ liệu, token, bố cục trang, biểu mẫu, và plugin. Ví dụ dưới đây là UI Pack của một tỉnh thuộc CELL-G2, rút gọn còn một bố cục và một biểu mẫu.

```json
{
  "$schema": "https://schemas.okr.viettel.vn/ui-pack/1.0.json",
  "pack": "tinh-quang-ngai/ui",
  "version": "1.3.0",
  "requires": {
    "registry": "^2.4.0",
    "policyPack": "khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0"
  },
  "tokens": {
    "color": {
      "primary":   { "$type": "color", "$value": "#C8102E" },
      "onPrimary": { "$type": "color", "$value": "#FFFFFF" },
      "surface":   { "$type": "color", "$value": "#FFFFFF" },
      "text":      { "$type": "color", "$value": "#1F2329" }
    },
    "font": {
      "family": { "$type": "fontFamily", "$value": ["Be Vietnam Pro", "Roboto", "sans-serif"] },
      "size": { "body": { "$type": "dimension", "$value": "14px" } }
    },
    "radius": { "card": { "$type": "dimension", "$value": "8px" } },
    "brand": { "logo": { "$type": "asset", "$value": "assets/logo-qn.svg", "sha256": "9f2c...e1" } }
  },
  "modes": {
    "elderly": {
      "font": { "size": { "body": { "$value": "18px" } } },
      "color": { "text": { "$value": "#000000" } },
      "flags": { "reducedSteps": true, "minTouchTarget": "48px" }
    }
  },
  "layouts": {
    "home": {
      "component": "vt.layout.grid",
      "props": { "columns": { "xs": 1, "md": 2, "xl": 3 } },
      "children": [
        { "component": "vt.widget.myTasksToday", "props": { "limit": 7 } },
        { "component": "vt.widget.okrProgress", "props": { "scope": "unit" },
          "visibleWhen": "user.roles.exists(r, r in ['leader', 'manager'])" },
        { "component": "vt.widget.pendingApprovals" },
        { "component": "vt.widget.announcement", "props": { "i18nKey": "home.notice.q4" } }
      ]
    }
  },
  "forms": {
    "tu-danh-gia-mau-1b": {
      "schemaRef": "policy://khoi-chinh-quyen/nd335/forms/mau-1b@1.2.0",
      "ui": {
        "sections": [
          { "title": "Kết quả thực hiện nhiệm vụ", "fields": ["ketQuaNhiemVu", "diemTuCham"] },
          { "title": "Hạn chế và hướng khắc phục", "fields": ["hanChe", "huongKhacPhuc"] }
        ],
        "widgets": { "hanChe": "vt.input.textarea", "diemTuCham": "vt.input.score" }
      }
    }
  },
  "plugins": [],
  "i18n": { "vi": { "home.notice.q4": "Hạn nộp hồ sơ quý IV là ngày 10/01/2027." } }
}
```

Phần `tokens` theo định dạng của nhóm cộng đồng W3C Design Tokens (thuộc tính `$type`, `$value`). Phần `modes.elderly` ghi đè token và bật cờ luồng rút gọn. Phần `layouts` là cây SDUI chỉ dùng tên component đã đăng ký; thuộc tính `visibleWhen` là biểu thức CEL trên ngữ cảnh người dùng, không phải JavaScript. Phần `forms` không chứa JSON Schema nghiệp vụ mà tham chiếu tới schema trong Policy Pack (`schemaRef`), vì cấu trúc dữ liệu của phiếu và quy tắc kiểm tra thuộc về quy tắc nghiệp vụ, còn UI Pack chỉ quyết định cách trình bày. Sự tách biệt này là nền của quản trị phiên bản ở mục sau.

### Pipeline token sang CSS variables và preset PrimeNG

Khi phát hành UI Pack, MOD-08 chạy bước biên dịch token ở máy chủ (mã TypeScript chạy trên Node trong job, dùng thư viện Style Dictionary) để sinh ra hai sản phẩm: tệp CSS chứa custom properties dưới `:root[data-tenant]` và `:root[data-mode="elderly"]`, và tệp JSON preset cho PrimeNG. Biên dịch ở máy chủ, không ở trình duyệt, để trình duyệt chỉ tải sản phẩm cuối và để bộ kiểm tra tương phản chạy trước khi phát hành.

Phía shell, thư viện `@vt/design-system` định nghĩa preset gốc dựa trên preset Aura của PrimeNG, và trỏ các token ngữ nghĩa của PrimeNG tới CSS variables của tenant. Nhờ vậy đổi tenant chỉ cần đổi tệp CSS, không phải dựng lại preset.

```typescript
// libs/design-system/src/vt-preset.ts
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

export const VtPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: 'color-mix(in srgb, var(--vt-color-primary) 8%, white)',
      100: 'color-mix(in srgb, var(--vt-color-primary) 16%, white)',
      500: 'var(--vt-color-primary)',
      600: 'color-mix(in srgb, var(--vt-color-primary) 88%, black)',
      700: 'color-mix(in srgb, var(--vt-color-primary) 76%, black)',
    },
    colorScheme: {
      light: {
        surface: { 0: 'var(--vt-color-surface)' },
        text: { color: 'var(--vt-color-text)' },
      },
    },
  },
  components: {
    card: { root: { borderRadius: 'var(--vt-radius-card)' } },
  },
});
```

```typescript
// apps/fe-shell/src/app/app.config.ts
import { ApplicationConfig, provideAppInitializer, inject } from '@angular/core';
import { providePrimeNG } from 'primeng/config';
import { VtPreset } from '@vt/design-system';
import { UiPackLoader } from '@vt/sdui-renderer';

export const appConfig: ApplicationConfig = {
  providers: [
    providePrimeNG({ theme: { preset: VtPreset, options: { cssLayer: true } } }),
    provideAppInitializer(() => inject(UiPackLoader).load()), // nạp CSS token trước khi render
  ],
};
```

Chế độ Đảng viên cao tuổi được bật bằng thuộc tính `data-mode="elderly"` trên phần tử gốc, chọn theo tuỳ chọn người dùng lưu ở `config.tenant_setting` theo người hoặc theo nút chuyển nhanh trên thanh trên. Ngoài token, cờ `reducedSteps` làm các màn hình hỗ trợ luồng rút gọn (ví dụ check-in KR một bước thay vì hai bước) chuyển sang phiên bản ít bước, đáp ứng yêu cầu "ít bước" của NFR-11.

### Component registry

Component registry là danh mục các component mà schema bố cục SDUI được phép tham chiếu, có phiên bản ngữ nghĩa riêng. Mỗi mục đăng ký gồm tên (ví dụ `vt.widget.myTasksToday`), lớp component Angular, JSON Schema của thuộc tính được phép, các sự kiện phát ra, và tiêu chí khả năng truy cập đã kiểm. Registry được phát hành cùng shell; UI Pack khai báo dải phiên bản registry tương thích (`requires.registry`), và trình biên tập bố cục chỉ cho chọn component có trong dải đó.

```typescript
// libs/sdui-renderer/src/registry.ts
import { Type } from '@angular/core';
import type { JSONSchema7 } from 'json-schema';

export interface RegistryEntry {
  name: string;                      // 'vt.widget.myTasksToday'
  load: () => Promise<Type<unknown>>;// nạp lười, tách bundle theo component
  propsSchema: JSONSchema7;          // thuộc tính hợp lệ; thuộc tính lạ bị bỏ
  container?: boolean;               // được chứa children hay không
  since: string;                     // phiên bản registry đưa vào
}

export const REGISTRY: ReadonlyMap<string, RegistryEntry> = new Map([
  ['vt.layout.grid', {
    name: 'vt.layout.grid', container: true, since: '2.0.0',
    load: () => import('./layout/grid.component').then(m => m.GridComponent),
    propsSchema: { type: 'object', additionalProperties: false,
      properties: { columns: { type: 'object' } } },
  }],
  ['vt.widget.myTasksToday', {
    name: 'vt.widget.myTasksToday', since: '2.0.0',
    load: () => import('@vt/fe-task/widgets').then(m => m.MyTasksTodayWidget),
    propsSchema: { type: 'object', additionalProperties: false,
      properties: { limit: { type: 'integer', minimum: 1, maximum: 20 } } },
  }],
]);
```

### SDUI renderer trong Angular

Renderer là một component đệ quy nhận một nút bố cục, tra registry, kiểm tra thuộc tính bằng JSON Schema (Ajv đã biên dịch sẵn schema), đánh giá `visibleWhen` bằng cel-js, rồi tạo component bằng `ViewContainerRef.createComponent`. Renderer không bao giờ dùng `innerHTML`, `bypassSecurityTrust*` hay đánh giá chuỗi thành mã.

```typescript
// libs/sdui-renderer/src/sdui-node.component.ts
import { Component, Input, OnChanges, ViewChild, ViewContainerRef, inject } from '@angular/core';
import { REGISTRY } from './registry';
import { PropsValidator } from './props-validator';
import { CelContext } from './cel-context';

export interface SduiNode {
  component: string;
  props?: Record<string, unknown>;
  children?: SduiNode[];
  visibleWhen?: string;
}

@Component({
  selector: 'vt-sdui-node',
  standalone: true,
  template: `<ng-container #host />`,
})
export class SduiNodeComponent implements OnChanges {
  @Input({ required: true }) node!: SduiNode;
  @ViewChild('host', { read: ViewContainerRef, static: true }) host!: ViewContainerRef;

  private readonly validator = inject(PropsValidator);
  private readonly cel = inject(CelContext);

  async ngOnChanges(): Promise<void> {
    this.host.clear();
    const entry = REGISTRY.get(this.node.component);
    if (!entry) { return; }                                   // component lạ: bỏ qua, ghi telemetry
    if (this.node.visibleWhen && !this.cel.evaluateBool(this.node.visibleWhen)) { return; }
    const props = this.validator.sanitize(entry, this.node.props ?? {});
    const type = await entry.load();
    const ref = this.host.createComponent(type);
    for (const [k, v] of Object.entries(props)) { ref.setInput(k, v); }
    if (entry.container && this.node.children?.length) {
      ref.setInput('children', this.node.children);           // container tự dựng con bằng vt-sdui-node
    }
  }
}
```

### Biểu mẫu động: Formly, JSON Schema và CEL

Biểu mẫu được dựng bởi `@vt/form-engine`, lớp mỏng trên Formly. Đầu vào gồm JSON Schema 2020-12 của phiếu (từ Policy Pack), phần `ui` của UI Pack, và các quy tắc kiểm tra CEL trong Policy Pack. Formly chuyển JSON Schema thành cấu hình trường qua `FormlyJsonschema`, `@vt/form-engine` bổ sung bố cục nhóm, widget và trình kiểm tra CEL.

Quy tắc kiểm tra liên trường được viết bằng CEL trong Policy Pack, ví dụ:

```yaml
forms:
  mau-1b:
    version: 1.2.0
    schema: forms/mau-1b.schema.json
    rules:
      - id: R-1B-01
        when: "true"
        assert: "diemTuCham >= 0.0 && diemTuCham <= 100.0"
        message: "Điểm tự chấm phải nằm trong khoảng 0 đến 100"
      - id: R-1B-02
        when: "deXuatMuc == 'HTXSNV'"
        assert: "size(giaiPhapDotPha) >= 50"
        message: "Đề xuất mức xuất sắc phải mô tả giải pháp đột phá được kiểm chứng (ít nhất 50 ký tự)"
```

Trình duyệt kiểm tra bằng cel-js khi người dùng nhập để phản hồi tức thì; máy chủ luôn kiểm tra lại bằng cel-java khi nhận dữ liệu và là nguồn sự thật. Để bảo đảm hai engine cho cùng kết quả, mỗi quy tắc có test case trong Policy Pack (đầu vào và kết quả mong đợi), và CI chạy cùng bộ test trên cả hai engine; quy tắc nào cho kết quả khác nhau làm phát hành thất bại. Các hàm mở rộng (ví dụ `workdays(from, to)` tính ngày làm việc theo lịch tenant) được hiện thực song song ở hai ngôn ngữ và nằm trong cùng bộ test. Quy tắc dùng hàm chưa có trên trình duyệt được đánh dấu `serverOnly: true`, chỉ kiểm tra khi gửi.

### Cơ chế bộ đệm: CDN, ETag, IndexedDB

UI Pack được phục vụ ở hai đường dẫn. Đường dẫn bất biến `/ui-packs/{tenant}/{version}/pack.json` có `Cache-Control: public, max-age=31536000, immutable` vì nội dung theo phiên bản không bao giờ đổi. Đường dẫn con trỏ `/ui-packs/{tenant}/current.json` chỉ chứa số phiên bản hiện hành và chữ ký, có `Cache-Control: max-age=60, stale-while-revalidate=600` và ETag; khi phát hành, EVT-23 kích hoạt vô hiệu bộ đệm CDN cho đường dẫn này.

Khi khởi động, shell đọc phiên bản đã lưu trong IndexedDB và hiển thị ngay bằng bản lưu (không chờ mạng), song song gửi `GET current.json` với `If-None-Match`. Nếu nhận 304 thì giữ nguyên; nếu phiên bản mới thì tải gói bất biến, kiểm chữ ký, lưu IndexedDB và áp ở lần điều hướng tiếp theo, không đổi giao diện giữa chừng khi người dùng đang nhập liệu. Với kích thước gói 30 KB nén, lần đầu tải thêm khoảng 60 ms trên 4G (giả định 4 Mbit/s thực tế), các lần sau gần như 0.

### Bảo mật: chống XSS và CSP

Mối đe doạ chính của SDUI là tenant hoặc kẻ tấn công chiếm tài khoản quản trị tenant chèn nội dung độc vào UI Pack. Thiết kế có năm lớp chặn. Thứ nhất, schema UI Pack không có trường nào nhận HTML hay JavaScript; văn bản hiển thị qua nội suy Angular, tự mã hoá đầu ra. Thứ hai, renderer chỉ tạo component có trong registry; tên lạ bị bỏ. Thứ ba, thuộc tính được kiểm bằng JSON Schema với `additionalProperties: false`; URL chỉ nhận lược đồ `https:` và đường dẫn tương đối trong danh sách cho phép. Thứ tư, biểu thức `visibleWhen` là CEL chỉ đọc ngữ cảnh, không có tác dụng phụ, giới hạn độ dài và chi phí đánh giá. Thứ năm, gói được ký số khi phát hành và shell kiểm chữ ký bằng khoá công khai của nền tảng trước khi áp.

Chính sách bảo mật nội dung (Content Security Policy) của shell được đặt chặt, không có `unsafe-inline` cho script và không có `unsafe-eval`:

```text
Content-Security-Policy: default-src 'self';
  script-src 'self' https://cdn.okr.viettel.vn 'strict-dynamic' 'nonce-{per-request}';
  style-src 'self' https://cdn.okr.viettel.vn 'nonce-{per-request}';
  img-src 'self' https://cdn.okr.viettel.vn data: blob:;
  connect-src 'self' https://*.okr.viettel.vn https://id.*.okr.viettel.vn;
  font-src 'self' https://cdn.okr.viettel.vn;
  frame-ancestors 'self' https://*.voffice.vn {tenant-portal-origins};
  object-src 'none'; base-uri 'self'; form-action 'self';
  require-trusted-types-for 'script'; trusted-types angular angular#bundler vt-federation;
```

Yêu cầu Trusted Types buộc mọi gán vào điểm nguy hiểm của DOM phải qua chính sách đã đặt tên; Angular hỗ trợ Trusted Types, và thư viện nạp remote Native Federation được bọc trong chính sách `vt-federation` chỉ chấp nhận URL từ manifest đã ký. Lưu ý cel-js thông dịch biểu thức bằng bộ phân tích riêng, không dùng `eval` hay `new Function`, nên tương thích với CSP không `unsafe-eval`; đây là một lý do nữa để không chọn thư viện CEL sinh mã JavaScript động. `frame-ancestors` liệt kê cổng văn phòng số của tenant được phép nhúng, lấy từ `config.tenant_setting`.

### Ngân sách hiệu năng

Ngân sách hiệu năng là giới hạn đo được cho từng phần, được kiểm tra trong CI bằng Lighthouse CI và giới hạn kích thước bundle; vượt ngân sách làm build thất bại.

| Hạng mục | Ngân sách | Cách đo |
|---|---|---|
| Shell + design system + runtime Angular | ≤ 250 KB gzip | `bundlesize` trong CI |
| Một remote miền (ví dụ `fe-task`) tải đầu | ≤ 180 KB gzip | `bundlesize` |
| UI Pack của tenant | ≤ 30 KB gzip | Kiểm tra khi phát hành |
| Dựng biểu mẫu 50 trường | ≤ 50 ms trên máy chuẩn | Benchmark trong Playwright |
| Đánh giá toàn bộ quy tắc CEL một biểu mẫu | ≤ 5 ms | Benchmark |
| LCP trang "Việc của tôi" trên 4G mô phỏng | ≤ 2,5 s | Lighthouse CI, Faro RUM |
| INP (độ trễ tương tác) | ≤ 200 ms | Faro RUM |

Với mạng 4G giả định 4 Mbit/s và độ trễ khứ hồi 100 ms, tải lần đầu 250 KB shell, 180 KB remote và 30 KB UI Pack mất khoảng 1 giây truyền cộng 3 đến 4 vòng khứ hồi, tức khoảng 1,4 giây, còn biên khoảng 1 giây cho phân tích, thực thi và gọi API dữ liệu trước ngưỡng LCP 2,5 giây. Đây là ước tính; Faro RUM đo thực tế.

### Kiểm thử hồi quy giao diện theo tenant

Không thể chụp mọi màn hình của 5.000 tenant. Chiến lược kiểm thử dựa trên nhận xét rằng sự khác biệt giữa tenant chỉ nằm trong UI Pack, và UI Pack chỉ tổ hợp những thành phần đã kiểm thử. Có bốn tầng. Tầng một kiểm thử từng component của registry bằng Storybook với các bộ token đại diện (mặc định, tương phản cao, cao tuổi, màu thương hiệu tối), chụp ảnh và so sánh bằng Playwright, chạy axe-core cho khả năng truy cập. Tầng hai là kiểm tra tĩnh UI Pack khi phát hành: hợp lệ schema, component có trong registry, tương phản màu chữ trên nền ≥ 4,5:1 theo WCAG cho mọi cặp token chữ và nền khai báo, kích thước. Tầng ba là kiểm thử khói (smoke test) cho từng UI Pack mới: dựng các trang có bố cục SDUI trong trình duyệt không giao diện, chụp ảnh, chạy axe-core; thời gian khoảng 2 phút mỗi gói. Tầng bốn là hồi quy theo mẫu: chọn 20 UI Pack đại diện nhất (theo số người dùng và độ phức tạp bố cục), chạy toàn bộ bộ kiểm thử đầu-cuối khi phát hành shell hoặc registry mới.

### Quản trị phiên bản UI Pack và Policy Pack

UI Pack và Policy Pack có phiên bản độc lập nhưng liên kết bằng tham chiếu có dải phiên bản. Quy tắc như sau. Policy Pack sở hữu cấu trúc dữ liệu phiếu (JSON Schema) và quy tắc kiểm tra (CEL); UI Pack chỉ sở hữu trình bày. UI Pack khai báo `requires.policyPack` là một dải phiên bản; khi phát hành, MOD-08 kiểm tra mọi `schemaRef` giải được trong dải đó và mọi trường được tham chiếu trong `ui.sections` tồn tại trong schema. Khi Policy Pack phát hành phiên bản chính mới có thay đổi schema phiếu, UI Pack hiện hành không còn khớp dải và phải phát hành phiên bản mới; MOD-05 chặn việc gắn Policy Pack mới cho kỳ nếu UI Pack hiện hành của tenant chưa tương thích.

Nhờ tách như vậy, thay đổi thuần giao diện (màu, logo, thứ tự widget, nhóm trường) không phải chạy lại test case tính điểm và phát hành trong ngày, đáp ứng NFR-07 về UI Pack ≤ 1 ngày. Ngược lại, thay đổi quy tắc luôn đi qua quy trình Policy Pack với test case vàng, đáp ứng NFR-07 về Policy Pack ≤ 5 ngày. Hồ sơ đã nộp lưu cùng phiên bản schema và phiên bản UI Pack đã dùng, để mở lại sau này hiển thị đúng như lúc nộp.

### Flutter dùng chung schema

Ứng dụng Flutter đọc cùng UI Pack. Token được chuyển thành `ThemeData` và `TextTheme` bằng một bộ chuyển đổi trong gói `vt_design_system`; chế độ cao tuổi đổi `textScaler` và bảng màu. Biểu mẫu động dùng bộ dựng Flutter đọc JSON Schema và phần `ui`, ánh xạ widget tên `vt.input.*` sang widget Flutter tương đương có trong registry di động. Bố cục SDUI trên di động chỉ hỗ trợ tập con component có đánh dấu `mobile: true` trong registry; component không hỗ trợ bị bỏ qua có ghi telemetry. Như vậy, cùng một nguồn cấu hình phục vụ hai nền tảng, và tenant không phải cấu hình hai lần.

## Kết luận G1

Kiến trúc SDUI kết hợp Micro-frontends trong ảnh góp ý áp dụng được cho nền tảng, với điều chỉnh ở hai điểm. Lớp Design Token, Dynamic Form và Plugin áp dụng trọn vẹn. Lớp SDUI áp dụng có giới hạn cho bề mặt cấu hình được (bố cục trang chủ, trang chi tiết, biểu mẫu, dashboard), qua danh mục component đóng có phiên bản, không biến toàn bộ frontend thành render engine thuần tuý, vì màn hình tương tác nặng cần mã để bảo đảm kiểm thử, khả năng truy cập, an toàn và hiệu năng. Đề xuất biên dịch CEL sang WebAssembly được thay bằng thông dịch cel-js vì biểu thức kiểm tra biểu mẫu ngắn, cel-js đánh giá dưới 0,1 ms mỗi biểu thức, trong khi Wasm tốn 1 đến 3 MB tải và hàng chục mili giây khởi tạo; máy chủ luôn kiểm tra lại bằng cel-java.

Với thiết kế này, thay giao diện một tenant mất dưới 1 ngày và không phát hành lại mã (NFR-07), chế độ Đảng viên cao tuổi là một bộ token và cờ (NFR-11), ngân sách tải shell 250 KB và UI Pack 30 KB giữ LCP dưới 2,5 giây trên 4G (NFR-03), và tổng chi phí khoảng 15 đến 21 tuần người cho cả bốn lớp. Quyết định được ghi tại ADR-05.

# Chuyên đề G2: BPMN ở quy mô hàng trăm, hàng nghìn tenant

## Câu hỏi

Góp ý G2 đặt vấn đề: dùng BPMN làm luồng nhưng thường hay nghẽn ở chính engine; với quy mô hàng trăm, hàng nghìn tenant thì sao, luồng có thành "mạng nhện" và trở thành nút thắt cổ chai không? Đây là lo ngại có cơ sở thực tế. Nhiều hệ thống dùng engine BPMN thế hệ dựa trên CSDL quan hệ đã gặp tình trạng engine chậm dần theo thời gian, job bị dồn, khoá lạc quan thất bại hàng loạt, và sơ đồ luồng chồng chéo đến mức không ai dám sửa.

Chương này trả lời theo trình tự: vì sao engine BPMN hay nghẽn, cơ chế cụ thể; định lượng tải luồng duyệt của nền tảng theo số liệu baseline; "mạng nhện" là gì và vì sao nguy hiểm; thiết kế ba tầng điều phối; mẫu cộng tham số với ví dụ BPMN và DMN; cấu hình Flowable cụ thể; chia theo cell; chỉ số giám sát; phương án thay thế; quy tắc quản trị; và kịch bản thử tải. Kết luận được nêu kèm số liệu ở cuối chương.

## Vì sao engine BPMN hay nghẽn

Engine BPMN dựa trên CSDL quan hệ như Flowable, Camunda 7, Activiti lưu trạng thái tiến trình trong các bảng runtime và lịch sử, và mỗi bước tiến của tiến trình là một giao dịch CSDL. Cách thiết kế này cho tính bền vững và khả năng truy vấn tốt, nhưng có bảy điểm nghẽn điển hình. Hiểu đúng từng điểm là điều kiện để tránh chúng.

### Bộ thực thi job dựa trên CSDL

Các bước bất đồng bộ (bước đánh dấu `async`, timer, gọi dịch vụ nền) được lưu thành dòng trong bảng job (`ACT_RU_JOB`, `ACT_RU_TIMER_JOB`). Bộ thực thi bất đồng bộ (async executor) định kỳ truy vấn bảng này để lấy job đến hạn, khoá job bằng cách cập nhật cột chủ sở hữu và thời hạn khoá, rồi thực thi. Khi nhiều node cùng lấy job, chúng tranh nhau cập nhật cùng những dòng; tỷ lệ xung đột tăng theo số node. Khi job sinh ra nhanh hơn tốc độ thực thi, bảng job phình, truy vấn lấy job chậm đi, và vòng luẩn quẩn bắt đầu.

### Khoá lạc quan trên bảng ACT_RU

Mỗi dòng runtime như `ACT_RU_EXECUTION` có cột phiên bản `REV_`. Hai giao dịch cùng cập nhật một execution (ví dụ hai nhánh song song cùng kết thúc, hoặc một timer và một người dùng cùng tác động một tiến trình) thì một giao dịch thất bại với `FlowableOptimisticLockingException` và phải thử lại. Với tiến trình có nhiều nhánh song song hoặc multi-instance song song trên cùng một execution cha, tỷ lệ thất bại tăng vọt dưới tải.

### Bảng lịch sử phình to

Ở mức lịch sử đầy đủ, mỗi bước của tiến trình ghi dòng vào `ACT_HI_ACTINST`, mỗi user task vào `ACT_HI_TASKINST`, mỗi thay đổi biến vào `ACT_HI_VARINST` và `ACT_HI_DETAIL`, mỗi liên kết danh tính vào `ACT_HI_IDENTITYLINK`. Một tiến trình 4 user task có thể sinh 30 đến 60 dòng lịch sử. Bảng lịch sử tăng tuyến tính theo thời gian, không bao giờ tự giảm, và các truy vấn lịch sử không có chỉ mục phù hợp quét bảng hàng trăm triệu dòng.

### Timer

Mỗi hạn xử lý, mỗi nhắc việc, mỗi leo thang là một timer job. Cuối kỳ, hàng trăm nghìn timer cùng đến hạn trong vài giờ (ví dụ "nhắc thẩm định trước hạn 2 ngày" cho toàn bộ hồ sơ có cùng hạn). Bộ thực thi timer phải lấy và chạy chúng trong khi vẫn phục vụ job thường.

### Polling

Bộ thực thi lấy job theo chu kỳ cố định. Chu kỳ ngắn thì tốn truy vấn vô ích khi không có việc; chu kỳ dài thì tăng độ trễ khi có việc. Với nhiều replica, số truy vấn polling nhân theo số replica và số loại job.

### Service task gọi đồng bộ

Đây là nguyên nhân gây nghẽn phổ biến nhất trong thực tế. Khi một service task gọi HTTP sang hệ thống khác trong giao dịch của engine, giao dịch CSDL và luồng thực thi bị giữ suốt thời gian chờ. Một hệ ngoài chậm 5 giây làm mỗi job giữ một kết nối CSDL 5 giây; pool kết nối cạn, mọi tiến trình khác cùng chờ. Hiện tượng lan truyền này giống hệt lỗi lan truyền mà bản đánh giá đã chỉ ra ở trục F của hệ hiện tại.

### Bộ đệm định nghĩa tiến trình

Engine giữ định nghĩa tiến trình đã phân tích trong bộ đệm. Nếu mỗi tenant có bản BPMN riêng, số định nghĩa tăng theo số tenant nhân số mẫu nhân số phiên bản. Bộ đệm có giới hạn sẽ liên tục bị đẩy ra và nạp lại, mỗi lần nạp là đọc tệp BPMN từ `ACT_GE_BYTEARRAY` và phân tích XML. Bộ đệm không giới hạn sẽ ăn hết bộ nhớ.

## Định lượng tải theo baseline

Mục 2.3 đã tính tải luồng duyệt từ baseline: khoảng 12 triệu tiến trình và 48 triệu user task mỗi quý toàn quốc; đỉnh khoảng 670 lần hoàn thành user task mỗi giây toàn quốc; cell lớn theo baseline 80 đến 110 lần mỗi giây, theo tỷ lệ tài khoản khoảng 133 lần mỗi giây, năng lực thiết kế 150 lần mỗi giây. Phần này chuyển các con số đó thành tải CSDL của engine.

Một lần hoàn thành user task trong Flowable ở mức lịch sử `audit` sinh khoảng 12 đến 18 câu lệnh SQL: xoá dòng `ACT_RU_TASK`, cập nhật `ACT_RU_EXECUTION`, cập nhật hoặc chèn biến, chèn và cập nhật `ACT_HI_TASKINST`, `ACT_HI_ACTINST`, xoá và chèn liên kết danh tính, tạo user task kế tiếp hoặc kết thúc tiến trình. Đây là ước tính dựa trên cấu trúc bảng của engine, cần đo trong thử tải. Lấy 15 câu lệnh làm trung bình, 150 lần hoàn thành mỗi giây cho khoảng 2.250 câu lệnh mỗi giây, phần lớn là thao tác theo khoá chính. Một cụm PostgreSQL 16 nhân trên NVMe xử lý hàng chục nghìn câu lệnh đơn giản mỗi giây; tải này chiếm dưới 20% năng lực, nếu engine có CSDL riêng.

Kích thước runtime cũng cần ước tính. Cell HCM có khoảng 20% số tiến trình toàn quốc, tức 2,4 triệu tiến trình mỗi quý. Giả định ở đỉnh cuối kỳ có 40% số tiến trình của quý đang mở, ta có khoảng 1 triệu tiến trình hoạt động, tương ứng khoảng 2 triệu dòng `ACT_RU_EXECUTION`, 1 triệu dòng `ACT_RU_TASK`, và với 4 biến mỗi tiến trình khoảng 4 triệu dòng `ACT_RU_VARIABLE`. Đây là kích thước bảng mà PostgreSQL xử lý thoải mái với chỉ mục đúng.

Lịch sử là phần cần kiểm soát. Ở mức `audit`, mỗi tiến trình 4 user task sinh khoảng 25 dòng lịch sử, tức 2,4 triệu × 25 = 60 triệu dòng mỗi quý ở cell HCM, 240 triệu dòng mỗi năm. Nếu không dọn, sau ba năm bảng lịch sử vượt 700 triệu dòng. Thiết kế giữ lịch sử trong engine 90 ngày sau khi tiến trình kết thúc và chuyển toàn bộ sang ClickHouse qua CDC trước khi dọn; nhờ đó bảng lịch sử của engine ổn định quanh 60 đến 80 triệu dòng.

Timer: mỗi user task có một timer hạn xử lý và tối đa một timer nhắc. Ở đỉnh, khoảng 1 triệu user task mở có tối đa 2 triệu timer job, trong đó số đến hạn trong một giờ cao điểm ước khoảng 50.000, tức khoảng 14 timer mỗi giây; tải này nhỏ nếu timer được phân bố và lấy theo lô.

Kết luận định lượng: với cách dùng đúng (chỉ luồng duyệt của con người, mẫu cộng tham số, CSDL riêng mỗi cell, không gọi đồng bộ ra ngoài, dọn lịch sử), tải engine ở cell lớn nhất khoảng 150 lần hoàn thành mỗi giây và vài nghìn câu lệnh mỗi giây, thấp hơn một bậc độ lớn so với giới hạn thực tế của một engine Flowable trên PostgreSQL. Ngược lại, nếu đưa cả vòng đời nhiệm vụ, minh chứng, check-in vào BPMN, tải chuyển trạng thái tăng thêm cỡ 1.200 lần ghi mỗi giây ở cell lớn (toàn bộ tải ghi của cell), mỗi lần ghi kéo theo 10 đến 15 câu lệnh của engine, tổng cộng 12.000 đến 18.000 câu lệnh mỗi giây trên các bảng nóng nhất, cộng với xung đột khoá lạc quan; đây chính là kịch bản nghẽn mà góp ý lo ngại.

## "Mạng nhện" là gì và vì sao nguy hiểm

"Mạng nhện" là tình trạng các định nghĩa luồng liên kết chằng chịt với nhau và với hệ thống bên ngoài đến mức không ai nắm được toàn cảnh. Nó có bốn biểu hiện. Biểu hiện thứ nhất là nhân bản định nghĩa theo tenant: mỗi tỉnh, mỗi khối có bản BPMN riêng được sao chép rồi sửa, sau vài năm có hàng nghìn biến thể gần giống nhau. Biểu hiện thứ hai là chuỗi call activity nhiều tầng: tiến trình A gọi B, B gọi C, C gửi message đánh thức D; một thay đổi ở C ảnh hưởng tới A theo cách không ai dự đoán. Biểu hiện thứ ba là liên kết chéo bằng signal và message phát rộng: một signal "kỳ đã khoá" đánh thức hàng trăm nghìn tiến trình cùng lúc. Biểu hiện thứ tư là service task gọi đồng bộ nhiều hệ ngoài, biến engine thành bộ điều phối tích hợp.

Mạng nhện nguy hiểm vì ba lý do. Về hiệu năng, mỗi biểu hiện tạo ra một dạng tải xấu: nhân bản làm vỡ bộ đệm định nghĩa; chuỗi call activity nhân số execution và số giao dịch; signal phát rộng tạo đột biến tải và xung đột khoá; gọi đồng bộ giữ kết nối. Với 5.000 tenant, nhân bản ngây thơ 30 mẫu, 4 phiên bản mỗi năm cho khoảng 600.000 định nghĩa sau một năm; với khoảng 100 KB bộ nhớ cho mỗi định nghĩa đã phân tích, bộ đệm cần khoảng 60 GB, không khả thi, nên engine sẽ liên tục nạp lại định nghĩa. Về vận hành, không thể di chuyển tiến trình đang chạy sang phiên bản mới khi có hàng nghìn biến thể; lỗi sửa ở một biến thể không tự áp cho biến thể khác. Về pháp lý, khi có khiếu nại, không trả lời được "hồ sơ này đã đi qua luồng nào, theo quy định nào" nếu luồng là một biến thể sửa tay.

Cần lưu ý rằng số tenant tự nó không làm engine nghẽn. Một engine phục vụ 5.000 tenant với 30 định nghĩa chung có cùng tải như phục vụ một tenant có cùng tổng số tiến trình. Thứ làm nghẽn là số định nghĩa, mức liên kết giữa định nghĩa, và việc đưa sai loại tải vào engine.

## Thiết kế ba tầng điều phối

ADR-04 phân việc điều phối luồng thành ba tầng, mỗi tầng dùng công cụ phù hợp với tính chất và khối lượng của nó.

![Hình 8.1. Ba tầng điều phối luồng](kt-ba-tang-dieu-phoi.png){width=15cm}

Tầng 1 là máy trạng thái khai báo trong mã, chạy trong mission-core, cho vòng đời khối lượng lớn: nhiệm vụ, phiên bản minh chứng, check-in KR, mục tiêu. Mỗi chuyển trạng thái là một lệnh của con người, kiểm tra theo bảng chuyển trạng thái do Policy Pack tham số hoá, ghi trong một giao dịch ngắn cùng với dữ liệu và outbox. Tầng này xử lý hàng nghìn thay đổi mỗi giây mà không có bảng runtime riêng, không job, không polling. Mục 5.1 đã trình bày mã.

Tầng 2 là Flowable 7 BPMN cho luồng duyệt có con người tham gia nhiều bước theo thẩm quyền: duyệt kế hoạch quý, duyệt OKR, hồ sơ đánh giá (tự đánh giá, thẩm định, đề xuất, quyết định), khiếu nại, mở lại kỳ hai người duyệt, phê duyệt phát hành Policy Pack. Đây là nơi BPMN đem lại giá trị thật: luồng nhiều cấp theo thẩm quyền khác nhau giữa khối Đảng (chi bộ, đảng uỷ cơ sở, tỉnh uỷ, Ban Tổ chức TW) và khối quân đội (chỉ huy và chính uỷ, cơ quan chính trị); sơ đồ đọc được bởi người nghiệp vụ; hạn xử lý, leo thang, uỷ quyền là tính năng có sẵn. Khối lượng khoảng 150 lần hoàn thành mỗi giây ở cell lớn.

Tầng 3 là saga điều phối cho quy trình kỹ thuật nhiều bước liên service, có hành động bù trừ: chốt kỳ (sáu bước theo TL-02a QT-08: kiểm tra tiền điều kiện, khoá kỳ, tính điểm hàng loạt, sinh Mẫu 1A đến 3 và bảng chấm điểm, ký số lô, hoàn tất; bù trừ về `MO` nếu huỷ trước khi ký lô xong), phát hành Policy Pack (kiểm thử, ký, phân phối xuống cell, kích hoạt), ký lô. Saga cũng mô hình hoá bằng BPMN trên cùng workflow-service để dùng chung công cụ giám sát, nhưng mỗi bước là một external worker task: engine chỉ ghi "bước X cần làm", worker ở service tương ứng (scoring, document, esign) chủ động lấy việc, thực hiện ngoài giao dịch của engine, rồi báo kết quả. Engine không bao giờ chờ hệ ngoài trong giao dịch của nó.

Ranh giới giữa các tầng rõ ràng. Tầng 1 phát sự kiện (EVT-03, EVT-11) khi một đối tượng cần duyệt; workflow-service nhận sự kiện và khởi tạo tiến trình tầng 2 với business key là id đối tượng. Khi một user task hoàn thành với quyết định, tầng 2 gọi API công bố của lõi để thực hiện chuyển trạng thái ở tầng 1 (ví dụ `POST /api/v1/plans/{id}/approve`), bằng external worker có idempotency key, không gọi trong giao dịch engine. Dữ liệu nghiệp vụ không nằm trong biến tiến trình; tiến trình chỉ mang id, tenant, khối, luồng và phiên bản Policy Pack.

## Mẫu cộng tham số

### Nguyên tắc

Định nghĩa luồng là mẫu (template) có tham số, không nhân bản theo tenant. Số định nghĩa bằng số mẫu nhân số phiên bản còn tiến trình chạy, khoảng 20 đến 40 mẫu, không tỷ lệ với số tenant. Mọi khác biệt giữa tenant như số cấp duyệt, vai trò từng cấp, thời hạn, có cho phép uỷ quyền hay không, nằm trong hai chỗ: bảng quyết định DMN chọn cấp duyệt, và bảng `flowable.wf_binding` gắn tham số theo tenant, khối, loại hồ sơ. Quản trị nghiệp vụ cấu hình qua màn hình SCR-1504 "Cấu hình luồng duyệt (mẫu + tham số)", không sửa BPMN.

### Ví dụ BPMN rút gọn: duyệt hồ sơ đánh giá

Mẫu dưới đây là luồng duyệt hồ sơ đánh giá sau khi cán bộ nộp tự đánh giá. Bước đầu gọi bảng DMN để tính danh sách cấp duyệt cho hồ sơ; sau đó một user task multi-instance tuần tự đi qua từng cấp; mỗi cấp có timer leo thang không ngắt; quyết định "trả lại" kết thúc vòng duyệt và báo lõi chuyển hồ sơ về trạng thái trước. Cuối cùng là external worker task yêu cầu lõi ghi kết quả.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="urn:vt-okrkpi:wf">
  <process id="tpl-appraisal-review" name="Duyệt hồ sơ đánh giá" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow sourceRef="start" targetRef="chooseLevels"/>

    <serviceTask id="chooseLevels" name="Chọn cấp duyệt" flowable:type="dmn">
      <extensionElements>
        <flowable:field name="decisionTableReferenceKey"><flowable:string>chon-cap-duyet</flowable:string></flowable:field>
        <flowable:field name="fallbackToDefaultTenant"><flowable:string>true</flowable:string></flowable:field>
      </extensionElements>
    </serviceTask>
    <sequenceFlow sourceRef="chooseLevels" targetRef="review"/>

    <userTask id="review" name="Duyệt theo cấp"
              flowable:candidateGroups="${tenantId}:${level.role}:${level.orgUnitId}"
              flowable:dueDate="${level.dueDate}">
      <multiInstanceLoopCharacteristics isSequential="true"
          flowable:collection="${approvalLevels}" flowable:elementVariable="level">
        <completionCondition>${decision == 'TRA_LAI'}</completionCondition>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <boundaryEvent id="remind" attachedToRef="review" cancelActivity="false">
      <timerEventDefinition><timeDuration>${remindAfter}</timeDuration></timerEventDefinition>
    </boundaryEvent>
    <sequenceFlow sourceRef="remind" targetRef="notifyRemind"/>
    <serviceTask id="notifyRemind" name="Nhắc người duyệt" flowable:type="external-worker"
                 flowable:topic="notify-remind"/>
    <sequenceFlow sourceRef="notifyRemind" targetRef="remindEnd"/>
    <endEvent id="remindEnd"/>

    <sequenceFlow sourceRef="review" targetRef="outcome"/>
    <exclusiveGateway id="outcome" default="toApply"/>
    <sequenceFlow id="toReturn" sourceRef="outcome" targetRef="applyReturn">
      <conditionExpression>${decision == 'TRA_LAI'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="toApply" sourceRef="outcome" targetRef="applyDecision"/>

    <serviceTask id="applyReturn" name="Lõi: trả lại hồ sơ" flowable:type="external-worker"
                 flowable:topic="core-appraisal-return"/>
    <serviceTask id="applyDecision" name="Lõi: ghi kết quả duyệt" flowable:type="external-worker"
                 flowable:topic="core-appraisal-advance"/>
    <sequenceFlow sourceRef="applyReturn" targetRef="end"/>
    <sequenceFlow sourceRef="applyDecision" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>
```

Định nghĩa trên không chứa tên đơn vị, vai trò cụ thể hay thời hạn của tenant nào. Nhóm ứng viên được tính từ biến `level` do DMN trả về, gồm vai trò, đơn vị và hạn; timer nhắc lấy thời lượng từ tham số `remindAfter` của `wf_binding`. Cùng một định nghĩa phục vụ chi bộ ba cấp duyệt lẫn bệnh viện hai cấp. Timer nhắc là sự kiện biên không ngắt, nên nhắc việc không làm gián đoạn user task. Nhắc việc và áp kết quả đều là external worker task: engine chỉ tạo job, worker ở notification-service và mission-core lấy việc qua REST API của Flowable, thực hiện, rồi báo hoàn thành.

### Ví dụ DMN chọn cấp duyệt

Bảng quyết định dưới đây chọn danh sách cấp duyệt theo khối, loại hồ sơ, và việc người được đánh giá có phải người đứng đầu hay không. Chính sách trúng (hit policy) là `COLLECT` để trả về nhiều cấp, mỗi dòng một cấp theo thứ tự. Bảng thuộc Policy Pack của từng khối, được kiểm thử bằng test case vàng như mọi quy tắc khác.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
             id="chon-cap-duyet-defs" name="Chọn cấp duyệt" namespace="urn:vt-okrkpi:dmn">
  <decision id="chon-cap-duyet" name="Chọn cấp duyệt hồ sơ đánh giá">
    <decisionTable id="dt-cap-duyet" hitPolicy="COLLECT">
      <input id="in-khoi" label="Khối">
        <inputExpression typeRef="string"><text>khoi</text></inputExpression>
      </input>
      <input id="in-loai" label="Loại hồ sơ">
        <inputExpression typeRef="string"><text>loaiHoSo</text></inputExpression>
      </input>
      <input id="in-ndd" label="Người đứng đầu">
        <inputExpression typeRef="boolean"><text>laNguoiDungDau</text></inputExpression>
      </input>
      <output id="out-thu-tu" name="thuTu" typeRef="number"/>
      <output id="out-vai-tro" name="role" typeRef="string"/>
      <output id="out-pham-vi" name="scope" typeRef="string"/>
      <output id="out-han" name="hanNgayLamViec" typeRef="number"/>
      <rule id="r1">
        <inputEntry><text>"DANG"</text></inputEntry>
        <inputEntry><text>"QUY"</text></inputEntry>
        <inputEntry><text>false</text></inputEntry>
        <outputEntry><text>1</text></outputEntry>
        <outputEntry><text>"appraiser"</text></outputEntry>
        <outputEntry><text>"DON_VI_TRUC_TIEP"</text></outputEntry>
        <outputEntry><text>3</text></outputEntry>
      </rule>
      <rule id="r2">
        <inputEntry><text>"DANG"</text></inputEntry>
        <inputEntry><text>"QUY"</text></inputEntry>
        <inputEntry><text>false</text></inputEntry>
        <outputEntry><text>2</text></outputEntry>
        <outputEntry><text>"decider"</text></outputEntry>
        <outputEntry><text>"CAP_UY_CO_THAM_QUYEN"</text></outputEntry>
        <outputEntry><text>5</text></outputEntry>
      </rule>
      <rule id="r3">
        <inputEntry><text>"DANG"</text></inputEntry>
        <inputEntry><text>"QUY"</text></inputEntry>
        <inputEntry><text>true</text></inputEntry>
        <outputEntry><text>1</text></outputEntry>
        <outputEntry><text>"decider"</text></outputEntry>
        <outputEntry><text>"CAP_TREN_TRUC_TIEP"</text></outputEntry>
        <outputEntry><text>5</text></outputEntry>
      </rule>
      <rule id="r4">
        <inputEntry><text>"NHA_NUOC"</text></inputEntry>
        <inputEntry><text>"QUY","NAM"</text></inputEntry>
        <inputEntry><text>-</text></inputEntry>
        <outputEntry><text>1</text></outputEntry>
        <outputEntry><text>"manager"</text></outputEntry>
        <outputEntry><text>"QUAN_LY_TRUC_TIEP"</text></outputEntry>
        <outputEntry><text>3</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
```

Kết quả DMN là danh sách dòng; một listener nhỏ sắp theo `thuTu`, phân giải `scope` thành đơn vị cụ thể bằng dữ liệu `org_replica` của workflow-service, chuyển `hanNgayLamViec` thành ngày theo lịch nghỉ của tenant, và gán vào biến `approvalLevels`. Phạm vi như `CAP_UY_CO_THAM_QUYEN` được phân giải theo cây tổ chức tại thời điểm khởi tạo tiến trình và lưu lại, nên điều động nhân sự sau đó không làm hồ sơ "mất" người duyệt; việc chuyển người duyệt khi có điều động là một thao tác uỷ quyền có lưu vết.

### Bảng tham số theo tenant

`flowable.wf_binding` gắn mẫu và tham số cho từng tổ hợp tenant, khối, loại hồ sơ, kỳ hiệu lực. Ví dụ một dòng: tenant CELL-HCM sở X, khối `NHA_NUOC`, loại `APPRAISAL_QUARTER`, mẫu `tpl-appraisal-review` phiên bản chính 3, Policy Pack `khoi-chinh-quyen/nd335@1.4`, tham số `{"remindAfter":"P2D","allowDelegation":true,"batchApproveMax":200}`. Khi tạo tiến trình, workflow-service tra `wf_binding` để chọn mẫu và tham số; không có dòng phù hợp thì dùng giá trị mặc định của khối.

## Cấu hình Flowable cụ thể

Cấu hình dưới đây là điểm xuất phát cho cell lớn, sẽ được tinh chỉnh theo kết quả thử tải trong TL-04. Mỗi tham số có lý do.

```yaml
flowable:
  database-schema-update: false          # migration qua Flyway riêng, ứng dụng không có quyền DDL
  history-level: audit                   # đủ cho truy vết duyệt; không ghi chi tiết biến
  async-executor-activate: ${WF_ASYNC_ENABLED:false}   # bật ở Deployment worker, tắt ở Deployment API
  process:
    definition-cache-limit: 200          # khoảng 40 mẫu x 5 phiên bản còn tiến trình
    async:
      executor:
        core-pool-size: 16
        max-pool-size: 32
        queue-capacity: 256
        max-async-jobs-due-per-acquisition: 64
        max-timer-jobs-per-acquisition: 64
        default-async-job-acquire-wait-time: 1s
        default-timer-job-acquire-wait-time: 5s
        async-job-lock-time: 5m
        timer-lock-time: 5m
  dmn:
    enabled: true
    history-enabled: false               # kết quả DMN lưu trong biến approvalLevels và sổ cái
  idm:
    enabled: false                       # danh tính lấy từ Keycloak qua JWT, không dùng IDM của Flowable
  eventregistry:
    enabled: false
spring:
  datasource:
    url: jdbc:postgresql://pg-flowable-rw.cell-hcm.svc:5432/flowable
    hikari:
      maximum-pool-size: 20
      connection-timeout: 2000
```

Ba cấu hình không có thuộc tính Spring Boot tương ứng được đặt qua bộ cấu hình engine trong mã: bộ sinh id dùng `StrongUuidGenerator` để tránh tranh chấp dòng `next.dbid` của bộ sinh id theo khối trong `ACT_GE_PROPERTY`; bật chèn hàng loạt với tối đa 100 câu lệnh mỗi lô; và bật dọn lịch sử với các tiến trình đã kết thúc quá 90 ngày, chạy lúc 2 giờ sáng.

```java
@Configuration
class FlowableEngineTuning implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {
  @Override
  public void configure(SpringProcessEngineConfiguration cfg) {
    cfg.setIdGenerator(new StrongUuidGenerator());
    cfg.setBulkInsertEnabled(true);
    cfg.setMaxNrOfStatementsInBulkInsert(100);
    cfg.setEnableHistoryCleaning(true);
    cfg.setCleanInstancesEndedAfter(Duration.ofDays(90));
    cfg.setHistoryCleaningTimeCycleConfig("0 0 2 * * ?");
    cfg.setEnableProcessDefinitionInfoCache(true);
  }
}
```

Hai cấu hình CSDL bổ sung cho PostgreSQL của engine: chỉ mục `(TENANT_ID_, ASSIGNEE_)` và `(TENANT_ID_, CREATE_TIME_)` trên `ACT_RU_TASK` cho truy vấn hộp việc (dù hộp việc chính đọc từ `wf_task_projection`), và `fillfactor=70` cho `ACT_RU_EXECUTION`, `ACT_RU_TASK` để cập nhật HOT không phải di chuyển dòng. Lịch sử `ACT_HI_*` được Debezium đọc sang ClickHouse trước khi dọn, nên truy vấn lịch sử dài hạn (báo cáo thời gian xử lý theo cấp, phát hiện "miễn trừ lặp lại") chạy trên ClickHouse.

Hộp việc của người dùng là truy vấn đọc nhiều nhất của luồng duyệt. Thay vì truy vấn `ACT_RU_TASK` với liên kết danh tính phức tạp, một listener sự kiện tác vụ của engine (`FlowableEventListener` cho `TASK_CREATED`, `TASK_ASSIGNED`, `TASK_COMPLETED`) cập nhật bảng phẳng `flowable.wf_task_projection(tenant_id, task_id, candidate_key, assignee, business_key, title, due_at, status)` trong cùng giao dịch, có chỉ mục theo `candidate_key` và `due_at`. Truy vấn hộp việc chạm một chỉ mục, dưới 5 ms.

## Chia theo cell

Không có một engine cho cả nước. Mỗi cell có một workflow-service với CSDL riêng, nên tải được chia tự nhiên theo cell: cell lớn nhất chịu khoảng 150 lần hoàn thành mỗi giây, các cell khác ít hơn. Trong một cell, Deployment API (không bật async executor) co giãn theo HPA và Deployment worker (bật async executor) co giãn theo KEDA; hai vai trò không tranh tài nguyên.

KEDA dùng bộ co giãn PostgreSQL đọc số job đến hạn:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: workflow-async-executor
spec:
  scaleTargetRef:
    name: workflow-service-worker
  minReplicaCount: 2
  maxReplicaCount: 8
  pollingInterval: 15
  cooldownPeriod: 300
  triggers:
    - type: postgresql
      metadata:
        targetQueryValue: "500"
        query: >-
          SELECT count(*) FROM act_ru_job
          WHERE lock_owner_ IS NULL AND (duedate_ IS NULL OR duedate_ <= now())
      authenticationRef:
        name: keda-flowable-pg
```

Mức xem lại kiến trúc đã nêu ở chương 3: khi tải user task của một cell vượt 300 lần mỗi giây kéo dài, tức gấp đôi năng lực thiết kế, workflow-service của cell đó được chia thành hai engine theo nhóm tenant, mỗi engine một CSDL. Vì định nghĩa là mẫu chung và tham số theo tenant, việc chia chỉ là chuyển dòng `wf_binding` và tiến trình đang chạy của một nhóm tenant, không phải sửa định nghĩa. Ngưỡng này cũng là điều kiện tách cell (chương 12).

## Chỉ số giám sát và ngưỡng cảnh báo

Flowable cung cấp metric qua Micrometer; bộ chỉ số dưới đây được đưa vào Prometheus và Grafana, kèm cảnh báo. Ngưỡng là giá trị khởi điểm, hiệu chỉnh sau thử tải.

| Chỉ số | Ý nghĩa | Ngưỡng cảnh báo | Ngưỡng nghiêm trọng |
|---|---|---|---|
| Số job đến hạn chưa khoá | Độ dồn của async executor | > 2.000 trong 5 phút | > 10.000 trong 5 phút |
| Độ trễ lấy job (từ đến hạn tới bắt đầu chạy) p95 | Executor theo kịp hay không | > 10 s | > 60 s |
| Số job thư chết (`ACT_RU_DEADLETTER_JOB`) tăng | Job lỗi hết số lần thử | > 10 mỗi giờ | > 100 mỗi giờ |
| Tỷ lệ `FlowableOptimisticLockingException` | Xung đột cập nhật đồng thời | > 0,5% số giao dịch | > 2% |
| Độ trễ API hoàn thành user task p95 | Trải nghiệm người duyệt | > 300 ms | > 800 ms |
| Độ trễ external worker (tạo job tới hoàn thành) p95 | Worker theo kịp | > 30 s | > 5 phút |
| Số dòng `ACT_RU_EXECUTION` | Kích thước runtime | > 5 triệu | > 10 triệu |
| Tăng trưởng `ACT_HI_*` mỗi ngày | Dọn lịch sử có chạy không | > 1,5 lần trung bình 30 ngày | Dọn lịch sử thất bại |
| Tỷ lệ trượt bộ đệm định nghĩa | Số định nghĩa vượt bộ đệm | > 1% | > 5% |
| Sử dụng pool kết nối | Bão hoà CSDL | > 70% | > 90% |
| Số định nghĩa tiến trình đang hoạt động | Dấu hiệu nhân bản | > 300 | > 1.000 |

Chỉ số cuối cùng là chỉ số "chống mạng nhện": theo thiết kế, số định nghĩa hoạt động không vượt quá vài trăm; nếu vượt, nghĩa là quy tắc quản trị đang bị vi phạm.

## Phương án thay thế đã cân nhắc

| Phương án | Điểm mạnh | Lý do không chọn |
|---|---|---|
| Camunda 8 (Zeebe) | Kiến trúc log phân tán, không dựa CSDL quan hệ, thông lượng rất cao | Giấy phép thương mại cho môi trường sản xuất từ bản 8.6; vận hành thêm cụm Zeebe, Elasticsearch hoặc OpenSearch cho Operate; khó cài cách ly với giấy phép |
| Camunda 7 Community | Tương tự Flowable, đội ngũ quen | Bản cộng đồng đã kết thúc hỗ trợ từ 10/2025 |
| Temporal | Rất mạnh cho saga kỹ thuật, độ bền cao, mô hình lập trình bằng mã | Không có ký pháp BPMN cho nghiệp vụ đọc và cấu hình; thêm cụm Temporal và CSDL riêng |
| Netflix Conductor (Orkes OSS) | JSON workflow, worker mô hình tốt | Dự án gốc của Netflix đã ngừng, bản cộng đồng do Orkes duy trì; không có BPMN |
| jBPM, Kogito | Mã nguồn mở, BPMN | Cộng đồng và lộ trình kém ổn định hơn Flowable; Kogito hướng serverless khác mô hình vận hành |
| Tự viết máy trạng thái cho mọi luồng | Không có engine | Mất ký pháp BPMN cho luồng nhiều cấp theo thẩm quyền, mất timer, uỷ quyền, leo thang có sẵn; chi phí tự xây lớn |
| Flowable 7 (chọn) | Apache 2.0, nhúng Spring Boot, BPMN, DMN, external worker, đội ngũ Java dùng được ngay | Cần kỷ luật sử dụng; được bảo đảm bằng thiết kế ba tầng và quy tắc quản trị |

Lựa chọn Flowable 7 không dựa trên việc Flowable nhanh hơn các phương án khác; Zeebe có thông lượng cao hơn. Lựa chọn dựa trên việc với cách dùng đúng, tải thực tế thấp hơn năng lực của Flowable một bậc độ lớn, trong khi Flowable phù hợp nhất với ràng buộc giấy phép, cài cách ly và năng lực đội ngũ.

## Quy tắc quản trị chống mạng nhện

Các quy tắc dưới đây được kiểm tra tự động bằng bộ linter BPMN chạy trong CI của repo mẫu luồng và khi BA lưu cấu hình trong Config Studio. Vi phạm quy tắc bắt buộc làm việc đăng ký mẫu thất bại.

Quy tắc thứ nhất: mỗi mẫu là một định nghĩa dùng chung cho mọi tenant; cấm triển khai định nghĩa gắn `tenantId`. Khác biệt chỉ qua DMN và `wf_binding`. Quy tắc thứ hai: tối đa một tầng call activity; tiến trình con không được gọi tiếp tiến trình khác. Quy tắc thứ ba: không gọi tiến trình chéo tenant, không dùng signal phát rộng; khi cần tác động hàng loạt (ví dụ kỳ khoá), lõi phát sự kiện và workflow-service xử lý theo lô có giới hạn tốc độ. Quy tắc thứ tư: không có service task gọi đồng bộ dịch vụ ngoài; mọi tác vụ của máy là external worker task, hoặc là Java delegate thuần tính toán không I/O. Quy tắc thứ năm: biến tiến trình chỉ mang id, mã và tham số nhỏ, tổng kích thước dưới 4 KB; dữ liệu nghiệp vụ nằm ở lõi. Quy tắc thứ sáu: không dùng multi-instance song song trên cùng một execution cha với số phần tử lớn hơn 20, để tránh xung đột khoá lạc quan. Quy tắc thứ bảy: mọi mẫu có bộ kiểm thử bằng Flowable test và các test case quyết định DMN, có mô tả nghiệp vụ, có người sở hữu. Quy tắc thứ tám: số phiên bản chính còn tiến trình chạy của một mẫu không quá ba; phiên bản cũ được di chuyển tiến trình (process instance migration) hoặc chờ kết thúc tự nhiên trước khi phát hành phiên bản thứ tư.

## Kịch bản thử tải tham chiếu

TL-04 đặt kịch bản thử tải chi tiết cho workflow-service; tài liệu này nêu yêu cầu đầu vào. Môi trường là bản sao cell lớn với CSDL engine riêng. Dữ liệu nền là 1 triệu tiến trình hoạt động, 60 triệu dòng lịch sử, 2 triệu timer job. Tải ổn định là 150 lần hoàn thành user task mỗi giây trong 2 giờ, kèm 1.500 truy vấn hộp việc mỗi giây. Tải đột biến là 300 lần hoàn thành mỗi giây trong 15 phút. Tải timer là 50.000 timer đến hạn trong một giờ. Lỗi có chủ đích gồm worker ký số ngừng 10 phút, một pod engine bị giết giữa chừng, primary CSDL chuyển đổi. Tiêu chí đạt là độ trễ hoàn thành user task p95 ≤ 300 ms ở tải ổn định và ≤ 800 ms ở tải đột biến, tỷ lệ lỗi khoá lạc quan ≤ 0,5%, không job thư chết do tải, độ trễ lấy job p95 ≤ 10 giây, và không mất tiến trình khi lỗi có chủ đích.

## Kết luận G2

Engine BPMN chỉ trở thành nút thắt khi bị dùng sai chỗ. Số tenant tự nó không làm engine nghẽn; thứ làm nghẽn là số định nghĩa nhân bản theo tenant, liên kết chằng chịt giữa định nghĩa, gọi đồng bộ ra ngoài trong giao dịch của engine, và đưa tải chuyển trạng thái khối lượng lớn vào engine.

Thiết kế tránh cả bốn nguyên nhân. Vòng đời khối lượng lớn chạy ở tầng 1 máy trạng thái trong mã, nên engine không nhận khoảng 1.200 lần ghi mỗi giây của cell lớn. Engine chỉ phục vụ luồng duyệt của con người, khoảng 150 lần hoàn thành user task mỗi giây ở cell lớn nhất, tương đương khoảng 2.250 câu lệnh SQL mỗi giây trên CSDL riêng, thấp hơn một bậc độ lớn so với giới hạn của PostgreSQL. Định nghĩa là mẫu cộng tham số, khoảng 20 đến 40 mẫu và dưới 200 định nghĩa trong bộ đệm, không đổi khi số tenant tăng từ 100 lên 5.000. Mỗi cell một engine nên không có điểm nghẽn quốc gia. Tác vụ máy là external worker nên engine không bao giờ chờ hệ ngoài. Lịch sử được dọn sau 90 ngày và chuyển sang ClickHouse, nên bảng lịch sử ổn định. Quy tắc quản trị được kiểm tra tự động, và chỉ số "số định nghĩa hoạt động" cảnh báo sớm nếu mạng nhện bắt đầu hình thành. Quyết định được ghi tại ADR-04; TL-04 kiểm chứng bằng kịch bản thử tải nêu trên.

# Chuyên đề G3: Hiệu năng của bảy lớp bảo vệ và mã hoá dữ liệu mật

## Câu hỏi và nguyên tắc

Góp ý G3 chỉ ra rằng bộ tài liệu định hướng chưa phân tích rõ vấn đề hiệu năng của bảy lớp bảo vệ đối với dữ liệu thường và của các giải pháp mã hoá đối với dữ liệu mật. Đây là câu hỏi đúng, vì mỗi lớp bảo vệ đều có chi phí, và nếu đặt sai chỗ, tổng chi phí của bảy lớp cộng với mã hoá có thể làm hệ thống không đạt NFR-03.

Chuyên đề này trả lời bằng một nguyên tắc và một phép tính. Nguyên tắc là lớp bảo vệ nào có chi phí lớn thì đưa ra khỏi đường găng của request, hoặc chỉ chạy ở mốc pháp lý có tần suất thấp. Phép tính là ngân sách độ trễ cho bốn thao tác đại diện, với dữ liệu thường và dữ liệu mật, cộng từng thành phần có ghi rõ nguồn gốc con số. Mọi con số trong chương là ước tính thiết kế từ đặc tính thuật toán và thông số phổ biến của phần cứng hiện đại; chúng được ghi là "ước tính" và được TL-04 kiểm chứng bằng kịch bản thử tải trên môi trường thật.

## Bảy lớp bảo vệ trên đường xử lý

![Hình 9.1. Vị trí của bảy lớp bảo vệ trên đường xử lý request ghi](kt-7-lop-bao-ve.png){width=15cm}

Hình trên chia đường xử lý thành hai vùng. Vùng đường găng đồng bộ gồm các bước mà người dùng phải chờ: cổng API, mTLS, kiểm tra quyền, kiểm tra kỳ khoá (lớp 5), mã hoá trường C2 (một phần lớp 7), giao dịch CSDL. Vùng bất đồng bộ hoặc tần suất thấp gồm sổ cái chuỗi băm (lớp 1), ký số (lớp 2), xử lý minh chứng (lớp 3), dữ liệu hai trục thời gian (lớp 4) và phát hiện làm thay (một phần lớp 6). Phần dưới phân tích từng lớp theo cùng khung: cơ chế, vị trí, tần suất, chi phí và cách tính, biện pháp giảm chi phí.

### Lớp 1: Nhật ký chuỗi băm

Cơ chế: mọi sự kiện có giá trị pháp lý được nối vào chuỗi băm phân vùng của tenant, mỗi bản ghi chứa băm của bản ghi trước; mỗi giờ niêm phong gốc Merkle bằng ký số và dấu thời gian (chi tiết chương 11). Vị trí: bất đồng bộ. Trên đường găng chỉ có việc chèn một dòng `outbox_event` trong giao dịch sẵn có; việc băm và nối chuỗi do ledger-service làm sau khi nhận sự kiện từ Kafka.

Tần suất: khoảng 4.500 sự kiện mỗi giây toàn quốc, 9.000 khi đột biến (mục 2.3), trong đó khoảng một nửa có giá trị pháp lý cần vào sổ cái. Chi phí: chèn một dòng outbox khoảng 1 KB trong giao dịch sẵn có tốn khoảng 0,2 đến 0,5 ms (một lần ghi chỉ mục khoá chính và một bản ghi WAL, không thêm fsync vì cùng commit). SHA-256 trên bản ghi chuẩn hoá 1 KB tốn khoảng 2 µs trên một nhân có lệnh SHA-NI (thông lượng khoảng 500 MB/s đến 1,5 GB/s mỗi nhân), nên 4.500 bản ghi mỗi giây tốn khoảng 9 ms CPU mỗi giây, tức dưới 1% một nhân. Chi phí thật của ledger-service nằm ở ghi CSDL: chèn theo lô 500 bản ghi mỗi giao dịch, khoảng 10 lô mỗi giây ở mức toàn quốc, chia cho các cell. Niêm phong mỗi giờ: một cây Merkle trên 64 đầu chuỗi mỗi tenant, một chữ ký, một yêu cầu TSA; với 5.000 tenant là 5.000 chữ ký mỗi giờ nếu ký riêng từng tenant, hoặc một chữ ký mỗi cell nếu gom gốc tenant thành cây Merkle cấp cell. Thiết kế chọn cây hai cấp: ký một gốc cấp cell mỗi giờ, kèm bằng chứng đường đi Merkle cho từng tenant, nên số lần ký và gọi TSA là 10 mỗi giờ cho cả nước.

Biện pháp giảm chi phí: đưa ra khỏi đường găng qua outbox; băm theo lô; cây Merkle hai cấp để giảm số lần ký và gọi TSA; 64 phân vùng chuỗi mỗi tenant để nhiều consumer xử lý song song mà vẫn giữ thứ tự trong phân vùng.

### Lớp 2: Ký số tại mốc pháp lý

Cơ chế: ký số các tài liệu có giá trị pháp lý theo Luật Giao dịch điện tử 2023 bằng chữ ký từ xa (MySign) hoặc chứng thư chuyên dùng, kèm dấu thời gian. Vị trí: chỉ ở bốn mốc pháp lý (kế hoạch được duyệt, phiếu tự đánh giá, kết quả thẩm định, quyết định xếp loại), cộng biên bản kiểm điểm và gói hồ sơ; thực hiện bất đồng bộ qua saga và esign-service.

Tần suất: khoảng 0,1 lần ký mỗi người mỗi tuần theo baseline, tức với 6 triệu người khoảng 600.000 lần ký mỗi tuần, nhưng dồn vào cuối kỳ. Ước tính đỉnh: 6 triệu người × 2 lần ký mỗi quý (tự đánh giá, và quyết định ký theo lô), 30% trong 3 ngày cuối, hệ số giờ cao điểm 4, cho khoảng 6.000.000 × 2 × 0,3 / 86.400 × 4 ≈ 167 phiên ký mỗi giây toàn quốc, khoảng 33 mỗi giây ở cell lớn. Chi phí: ký từ xa qua MySign mất 1 đến 3 giây mỗi phiên phía nhà cung cấp, cộng thời gian người dùng xác nhận trên điện thoại từ vài giây tới vài chục giây; ký ECDSA P-256 bằng HSM khoảng 1 ms mỗi chữ ký và khoảng 1.000 chữ ký mỗi giây mỗi HSM. Gọi TSA khoảng 50 đến 200 ms mỗi yêu cầu.

Biện pháp giảm chi phí: không đặt ký số trên đường găng của request; API ghi quyết định trả 202 sau khoảng 30 ms, trạng thái "chờ ký" hiển thị cho người dùng, kết quả về qua EVT-18. Ký lô: lãnh đạo quyết định xếp loại hàng loạt (SCR-0904) ký một lần cho danh sách tới 1.000 phiếu, mỗi phiếu là một mục trong cấu trúc chữ ký (danh sách băm tài liệu được ký), nên 100.000 phiếu của một cell cần khoảng 100 phiên xác nhận chứ không phải 100.000. Dấu thời gian lấy theo lô cho chữ ký cùng phiên. Kết quả là ký số không ảnh hưởng độ trễ API; ràng buộc thực sự là thông lượng sinh PDF (chương 5, SVC-11).

### Lớp 3: Minh chứng chỉ ghi

Cơ chế: tính SHA-256 của tệp khi tải lên, lưu vào Ceph RGW bật Object Lock chế độ compliance, phiên bản mới không xoá bản cũ, khử trùng theo băm, quét mã độc, OCR. Vị trí: SHA-256 tính dạng luồng trong khi nhận dữ liệu, nên chồng lên thời gian truyền mạng; Object Lock là thuộc tính khi ghi, không thêm vòng gọi; quét mã độc và OCR bất đồng bộ.

Tần suất: 6 triệu người × 8 tệp mỗi quý, 30% trong 3 ngày cuối với hệ số 4, cho khoảng 6.000.000 × 8 × 0,3 / 86.400 × 4 ≈ 667 tệp mỗi giây toàn quốc ở đỉnh, khoảng 133 tệp mỗi giây ở cell lớn. Chi phí: SHA-256 với thông lượng khoảng 400 đến 700 MB/s mỗi nhân (thận trọng hơn mức tối đa vì xen lẫn I/O) cho 15 đến 25 ms CPU với tệp 10 MB; với tệp trung bình 1 MB là 1,5 đến 2,5 ms. Ở 133 tệp mỗi giây trung bình 1 MB, CPU băm khoảng 0,3 nhân. Quét ClamAV khoảng 0,5 đến 2 giây cho tệp 10 MB tuỳ loại, chạy nền. Ghi Ceph với mã xoá 4+2 tốn băng thông nội bộ 1,5 lần kích thước tệp.

Biện pháp giảm chi phí: tải theo phần (multipart) để tận dụng song song và tải tiếp khi mất mạng; băm dạng luồng không đệm toàn bộ tệp; khử trùng theo băm giảm dung lượng và cũng là cơ sở phát hiện dấu hiệu "minh chứng trùng hoặc dùng lại"; minh chứng ở trạng thái `DA_NOP` cho đến khi quét xong, chỉ chặn bước nghiệm thu, không chặn người nộp.

### Lớp 4: Dữ liệu hai trục thời gian

Cơ chế: bảng tổ chức, nhân sự, phân công lưu cả thời điểm hiệu lực và thời điểm ghi nhận (chương 5, SVC-04). Vị trí: chỉ ở org-service, đồng bộ trong giao dịch của org-service; các service khác nhận qua sự kiện. Tần suất: thấp, vài nghìn thay đổi mỗi ngày toàn quốc, đỉnh vài chục mỗi giây khi nhập lô sau sáp nhập. Chi phí: mỗi thay đổi gấp khoảng hai lần thao tác ghi (đóng dòng cũ, chèn dòng mới) trên tập dữ liệu nhỏ; truy vấn hiện hành không chậm hơn nhờ chỉ mục từng phần. Tác động lên đường găng của nghiệp vụ hằng ngày là không, vì lõi đọc `org_replica` chỉ chứa phiên bản hiện hành cộng lịch sử cần cho truy vấn "tại thời điểm".

### Lớp 5: Khoá kỳ và hai người duyệt

Cơ chế: kỳ ở trạng thái `DA_KHOA` thì mọi thao tác ghi trên dữ liệu của kỳ bị từ chối; mở lại cần hai người có thẩm quyền khác nhau cùng duyệt, có lý do, ghi sổ cái. Vị trí: kiểm tra trạng thái kỳ nằm trên đường găng của mọi thao tác ghi. Tần suất: mỗi request ghi, khoảng 3.000 mỗi giây toàn quốc. Chi phí: trạng thái kỳ được giữ trong bộ đệm cục bộ của mỗi replica (Caffeine), cập nhật khi nhận EVT-09, EVT-22, hết hạn sau 30 giây làm lưới an toàn; một lần tra bộ đệm tốn dưới 0,1 ms. Để tránh khoảng hở giữa lúc khoá và lúc bộ đệm nhận sự kiện, saga khoá kỳ chuyển kỳ sang `DANG_KHOA` trước, chờ hết thời hạn bộ đệm (30 giây) cộng biên, rồi mới chạy tính điểm và chuyển `DA_KHOA`; trong lúc `DANG_KHOA`, thao tác ghi kiểm tra lại trạng thái kỳ trong CSDL bằng một truy vấn khoá chính, khoảng 0,3 ms. Luồng hai người duyệt là tiến trình BPMN tần suất rất thấp.

### Lớp 6: Chống làm thay

Cơ chế: đăng nhập qua VNeID hoặc MFA, nâng bậc xác thực (step-up) khi ký và quyết định, ký bằng thiết bị cá nhân, phát hiện đăng nhập bất thường (giờ, nơi, thiết bị, tốc độ di chuyển bất khả). Vị trí: MFA chỉ ở lúc đăng nhập và lúc nâng bậc; kiểm tra trên request chỉ là đọc claim `acr` và `auth_time` trong JWT đã xác thực; phát hiện bất thường chạy bất đồng bộ trên luồng sự kiện đăng nhập. Chi phí trên request thường: không đáng kể (đọc hai claim). Chi phí cho người dùng: thêm một bước xác nhận khi ký hoặc quyết định, tối đa một lần mỗi 5 phút.

### Lớp 7: Tách quyền kỹ thuật

Cơ chế: mã hoá cấp ứng dụng cho trường C2 với khoá trong HSM, để quản trị CSDL không đọc được nội dung; truy cập đặc quyền qua PAM có ghi hình phiên; tách vai trò vận hành. Vị trí: mã hoá và giải mã trường nằm trên đường găng của request chạm trường C2; mở khoá DEK bằng HSM nằm ngoài đường găng nhờ bộ đệm. Tần suất: mỗi request đọc hoặc ghi trường C2. Chi phí: AES-256-GCM với lệnh AES-NI có thông lượng vài GB/s mỗi nhân; một trường 200 byte tốn dưới 1 µs cho phần mã hoá, cộng chi phí của JCE (khởi tạo `Cipher`, cấp phát) khoảng 1 đến 3 µs. Một danh sách 50 dòng với 2 trường C2 mỗi dòng tốn khoảng 0,1 đến 0,3 ms. Mở DEK qua OpenBao và HSM khoảng 5 ms, nhưng chỉ xảy ra khi DEK chưa có trong bộ đệm, tức dưới một lần mỗi 15 phút cho mỗi tổ hợp tenant, tháng, mục đích, trên mỗi pod.

Biện pháp giảm chi phí: chỉ mã hoá trường thực sự nhạy cảm (danh sách ở mục phân loại); không mã hoá điểm số và mức xếp loại để tính toán và phân tích được (chúng được bảo vệ bằng RLS, ABAC và sổ cái); dùng lại đối tượng `Cipher` theo luồng; bộ đệm DEK.

### Tổng hợp chi phí bảy lớp

| Lớp | Vị trí | Tần suất | Chi phí trên đường găng (ước tính) | Chi phí ngoài đường găng (ước tính) |
|---|---|---|---|---|
| 1. Chuỗi băm | Bất đồng bộ qua outbox | Khoảng 4.500 sự kiện/s toàn quốc | 0,2 đến 0,5 ms một dòng outbox | 2 µs/bản ghi băm; 10 lần ký niêm phong/giờ toàn quốc |
| 2. Ký số | 4 mốc pháp lý, bất đồng bộ, ký lô | Khoảng 167 phiên/s đỉnh toàn quốc | 0 (API trả 202) | 1 đến 3 s mỗi phiên; HSM 1 ms/chữ ký |
| 3. Minh chứng chỉ ghi | Luồng khi tải; quét nền | Khoảng 667 tệp/s đỉnh toàn quốc | 15 đến 25 ms CPU/10 MB, chồng lên truyền mạng | Quét 0,5 đến 2 s/tệp |
| 4. Hai trục thời gian | Chỉ org-service | Thấp | 0 với nghiệp vụ hằng ngày | Ghi gấp 2 lần trên tập nhỏ |
| 5. Khoá kỳ | Mọi request ghi | Khoảng 3.000/s toàn quốc | < 0,1 ms (đệm), 0,3 ms khi đang khoá | Không |
| 6. Chống làm thay | Đăng nhập, step-up | Thấp | Đọc claim, không đáng kể | Phân tích đăng nhập bất đồng bộ |
| 7. Tách quyền kỹ thuật | Trường C2 | Request chạm C2 | 1 đến 3 µs/trường; 0,1 đến 0,3 ms/danh sách | 5 ms mở DEK, < 1 lần/15 phút/pod |

Bảng cho thấy tổng chi phí bảy lớp trên đường găng của một request ghi thông thường là khoảng 0,5 đến 1 ms, nhỏ so với ngân sách 800 ms của NFR-03. Lý do không phải vì các lớp bảo vệ rẻ, mà vì những lớp tốn kém nhất (ký số, quét mã độc, niêm phong) đã được đặt ra ngoài đường găng.

## Phân loại dữ liệu C0 đến C3

Phân loại dữ liệu là cơ sở để chọn biện pháp mật mã: mã hoá tất cả ở mức cao nhất thì tốn kém và mất khả năng xử lý; không mã hoá thì không đạt yêu cầu. ADR-06 định nghĩa bốn lớp.

| Lớp | Định nghĩa | Ví dụ trong nền tảng | Biện pháp mật mã | Hạn chế xử lý |
|---|---|---|---|---|
| C0 Công khai | Thông tin được phép công bố | Danh mục công việc mẫu, văn bản quy phạm, tên đơn vị | TLS 1.3 khi truyền | Không |
| C1 Nội bộ | Dữ liệu nghiệp vụ thường | Nhiệm vụ, tiến độ, OKR, sản phẩm, điểm số, mức xếp loại | TLS 1.3, mTLS nội bộ, mã hoá ổ đĩa (LUKS, Ceph at-rest) | Theo RLS và ABAC |
| C2 Nhạy cảm | Dữ liệu cá nhân, đánh giá con người, khiếu nại | Số định danh, ngày sinh, số điện thoại, nhận xét đánh giá, nội dung khiếu nại và trả lời, ý kiến góp ý kiểm điểm | Như C1, cộng mã hoá trường bằng envelope encryption, chỉ mục mù cho tra cứu chính xác | Không vào cache dạng rõ, không vào log, giả danh hoá trước khi vào kho phân tích |
| C3 Hạn chế | Nội dung mà chỉ người được chỉ định được xem | Nhiệm vụ được người giao đánh dấu "hạn chế", danh tính người phản ánh, hồ sơ xác minh, tài liệu kèm theo | Mã hoá đầu cuối tại client, máy chủ chỉ giữ bản mã | Không tìm toàn văn, không phân tích, không AI, không OCR, không quét mã độc phía máy chủ |

Một điểm cần nói rõ là điểm số và mức xếp loại thuộc C1, không phải C2, dù chúng nhạy cảm về mặt xã hội. Lý do là chúng phải được tính toán, tổng hợp, so sánh giữa đơn vị cùng khối, đi xuống từ dashboard; mã hoá trường sẽ phá vỡ toàn bộ chức năng phân tích và phát hiện bất thường. Chúng được bảo vệ bằng ba lớp khác: RLS theo tenant, ABAC theo cây tổ chức (chỉ người có thẩm quyền trên đơn vị mới đọc), và sổ cái phát hiện mọi sửa đổi. Nhận xét bằng lời về con người thì ngược lại: ít khi cần xử lý tự động, nhạy cảm cao, nên thuộc C2.

Lớp dữ liệu được khai báo ở mức cột trong mô hình dữ liệu (TL-03), và được kiểm tra tự động: trường JPA có annotation `@Classified(C2)` bắt buộc dùng bộ chuyển đổi mã hoá; bộ lọc log từ chối ghi đối tượng có trường C2, C3; CDC sang ClickHouse chỉ lấy cột C0, C1 và cột C2 đã giả danh hoá.

## Mã hoá phong bì cho dữ liệu C2

### Mô hình khoá

Mã hoá phong bì (envelope encryption) dùng hai tầng khoá. Khoá mã hoá dữ liệu (DEK) là khoá AES-256 dùng để mã hoá trực tiếp giá trị trường. Khoá bọc khoá (KEK) dùng để mã hoá DEK, và không bao giờ rời HSM hoặc kho khoá. CSDL lưu DEK đã bọc; muốn dùng DEK phải nhờ kho khoá mở bọc.

Phân cấp khoá như sau. Mỗi tenant có một KEK riêng trong OpenBao Transit (`kek-<tenant>`), được bảo vệ bởi khoá gốc của OpenBao nằm trong HSM. Tenant mô hình silo có HSM riêng (CELL-DTW) dùng KEK không trích xuất được nằm trực tiếp trong HSM của mình, truy cập qua PKCS#11. Mỗi tổ hợp tenant, tháng, mục đích (ví dụ `pii`, `appraisal-comment`, `complaint`) có một DEK, lưu đã bọc trong bảng `keyring.dek` của cell (bảng bổ sung do TL-01 đề xuất). Như vậy một tenant có khoảng 3 DEK mỗi tháng, 36 DEK mỗi năm; số lượng nhỏ, bộ đệm hiệu quả.

![Hình 9.2. Luồng mã hoá phong bì cho trường C2](kt-ma-hoa-envelope.png){width=15cm}

Hình trên mô tả luồng ghi một trường C2. Ở bước 1, `CryptoProvider` tìm DEK của tenant cho tháng hiện tại trong bộ đệm. Nếu có (trường hợp thông thường), dùng ngay. Nếu chưa có, ở bước 3 đến 8, nó đọc DEK đã bọc từ CSDL, gửi tới OpenBao Transit để mở bọc bằng KEK, OpenBao dùng khoá được HSM bảo vệ, trả DEK rõ, và `CryptoProvider` lưu vào bộ đệm với thời hạn 15 phút. Ở bước 9, giá trị được mã hoá bằng AES-256-GCM với nonce ngẫu nhiên 96 bit và dữ liệu xác thực bổ sung (AAD). Ở bước 10, bản mã được ghi vào cột `_enc` cùng `dek_id`.

### Định dạng bản mã và dữ liệu xác thực bổ sung

Bản mã có định dạng tự mô tả: một byte phiên bản thuật toán, 16 byte `dek_id`, 12 byte nonce, phần bản mã, 16 byte thẻ xác thực. Dữ liệu xác thực bổ sung là chuỗi `tenant_id | schema.bảng.cột | id dòng`. Nhờ AAD, kẻ có quyền ghi CSDL không thể chép bản mã của dòng này sang dòng khác, hay của tenant này sang tenant khác, vì giải mã sẽ thất bại xác thực. Byte phiên bản cho phép chuyển sang thuật toán khác (ví dụ thuật toán của Ban Cơ yếu ở bản cách ly) mà vẫn đọc được dữ liệu cũ.

```java
public final class FieldEncryptor {
  private static final int NONCE_LEN = 12, TAG_BITS = 128;
  private static final byte V1_AES256_GCM = 0x01;
  private final DekCache dekCache;
  private final SecureRandom rng = new SecureRandom();

  public byte[] encrypt(TenantId tenant, String purpose, String column, UUID rowId, byte[] plain) {
    Dek dek = dekCache.currentFor(tenant, purpose);           // đệm 15 phút, mở bằng OpenBao khi trượt
    byte[] nonce = new byte[NONCE_LEN];
    rng.nextBytes(nonce);
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, dek.key(), new GCMParameterSpec(TAG_BITS, nonce));
      c.updateAAD(aad(tenant, column, rowId));
      byte[] ct = c.doFinal(plain);
      return ByteBuffer.allocate(1 + 16 + NONCE_LEN + ct.length)
          .put(V1_AES256_GCM).put(uuidBytes(dek.id())).put(nonce).put(ct).array();
    } catch (GeneralSecurityException e) {
      throw new CryptoFailure("Không mã hoá được trường " + column, e);
    }
  }

  private static byte[] aad(TenantId t, String column, UUID rowId) {
    return (t.value() + "|" + column + "|" + rowId).getBytes(StandardCharsets.UTF_8);
  }
}
```

### Xoay vòng khoá

Có ba loại xoay vòng. DEK xoay hằng tháng tự nhiên: tháng mới thì DEK mới cho dữ liệu ghi mới; dữ liệu cũ vẫn giải mã bằng DEK cũ nhờ `dek_id` trong bản mã. Việc này cũng bảo đảm số lần dùng một DEK với nonce ngẫu nhiên nằm rất xa giới hạn an toàn khoảng 2^32 thông điệp của AES-GCM với nonce ngẫu nhiên. KEK xoay hằng năm hoặc khi nghi lộ: OpenBao tạo phiên bản KEK mới, một job gọi `transit/rewrap` để bọc lại mọi DEK của tenant bằng phiên bản mới, không cần giải mã dữ liệu, mất vài giây cho vài trăm DEK. Mã hoá lại dữ liệu (đổi DEK của dữ liệu cũ) chỉ cần khi nghi DEK bị lộ; job chạy nền theo lô, đọc, giải mã, mã hoá bằng DEK mới, cập nhật, ghi sổ cái.

Một lợi ích phụ là xoá mật mã (crypto-shredding): khi chấm dứt hợp đồng và hết thời hạn lưu trữ, huỷ KEK của tenant làm mọi dữ liệu C2 của tenant, kể cả trong bản sao lưu, không còn đọc được.

### Bộ đệm DEK và an toàn bộ nhớ

Bộ đệm DEK nằm trong heap của tiến trình, thời hạn 15 phút, tối đa 10.000 mục, khoá theo `(tenant, purpose, month)`. DEK không bao giờ được ghi vào Valkey, log hay heap dump; heap dump bị tắt ở môi trường vận hành (`-XX:-HeapDumpOnOutOfMemoryError` và chính sách pod cấm `ptrace`). Khi mục bị đẩy ra, mảng byte của khoá được ghi đè bằng 0 ở mức nỗ lực tốt nhất (JVM không bảo đảm xoá mọi bản sao). Đây là giới hạn đã biết của mã hoá cấp ứng dụng trong JVM, được bù bằng việc pod chạy không root, hệ thống tệp chỉ đọc, và không cho gắn trình gỡ lỗi.

### Tra cứu trên dữ liệu C2

Dữ liệu đã mã hoá không truy vấn theo giá trị được. Với các trường cần tra cứu chính xác như số định danh, thiết kế dùng chỉ mục mù (blind index): cột phụ `_bidx` lưu HMAC-SHA256 của giá trị đã chuẩn hoá với khoá HMAC riêng của tenant (cũng lấy từ OpenBao), cắt còn 16 byte. Tra cứu "tìm cán bộ có số định danh X" tính HMAC của X và so khớp cột `_bidx`. Chỉ mục mù lộ thông tin "hai dòng có cùng giá trị" nhưng không lộ giá trị; với số định danh vốn duy nhất, rủi ro này chấp nhận được. Tìm kiếm theo khoảng hay theo tiền tố không được hỗ trợ trên C2.

## Mã hoá đầu cuối cho dữ liệu C3

### Mô hình

Với C3, máy chủ không được đọc nội dung, kể cả khi bị xâm nhập hoàn toàn. Vì vậy mã hoá và giải mã phải diễn ra ở thiết bị của người gửi và người nhận, dùng WebCrypto trên trình duyệt và thư viện mật mã nền tảng trên di động. Hệ thống hiện tại đã có phân hệ nhiệm vụ mật mã hoá theo chứng thư người nhận (tiêu chí D12 đạt); thiết kế mới giữ nguyên ý tưởng và chuẩn hoá cách quản lý khoá.

![Hình 9.3. Luồng mã hoá đầu cuối cho dữ liệu C3](kt-e2ee.png){width=15cm}

Ở bước 1, trình duyệt người gửi lấy khoá công khai của từng người nhận và của khoá phục hồi tổ chức từ danh bạ khoá. Ở bước 2, nó sinh khoá nội dung (CEK) AES-256-GCM ngẫu nhiên và mã hoá nội dung. Ở bước 3, CEK được bọc riêng cho từng người nhận theo lược đồ ECDH-ES: sinh cặp khoá tạm, thoả thuận khoá với khoá công khai P-256 của người nhận, dẫn xuất khoá bọc bằng HKDF-SHA256, bọc CEK bằng AES-KW. Ở bước 4, nếu cần tra cứu, tính chỉ mục mù trên vài trường mô tả. Ở bước 5, gửi lên máy chủ bản mã, danh sách CEK đã bọc và chỉ mục mù. Ở bước 6, máy chủ kiểm tra quyền, ghi siêu dữ liệu và sổ cái, nhưng không có khả năng giải mã. Ở bước 7 và 8, người nhận tải bản mã và CEK đã bọc của mình, mở bằng khoá riêng không trích xuất được, giải mã nội dung.

### Quản lý khoá người nhận

Mỗi người dùng có cặp khoá mã hoá ECDH P-256 cho mỗi thiết bị, sinh trên thiết bị bằng `crypto.subtle.generateKey` với `extractable: false`, khoá riêng lưu trong IndexedDB dưới dạng `CryptoKey` không trích xuất được (trình duyệt không cho JavaScript đọc giá trị khoá), trên di động lưu trong Keystore hoặc Keychain có gắn sinh trắc. Khoá công khai được đăng ký lên danh bạ khoá khi người dùng đăng nhập có MFA lần đầu trên thiết bị; bản ghi đăng ký được ký bằng khoá danh bạ của tenant và ghi vào sổ cái, nên máy chủ không thể lặng lẽ thay khoá công khai của ai đó bằng khoá của kẻ tấn công mà không để lại dấu vết kiểm tra được. Giao diện hiển thị "dấu vân tay" ngắn của khoá để người dùng đối chiếu khi cần.

Ở tenant đã dùng chứng thư số cá nhân có khoá mã hoá (như phân hệ mật hiện tại), khoá công khai lấy từ chứng thư theo baseline (RSA-OAEP hoặc ECDH tuỳ chứng thư), giải mã thực hiện bằng thiết bị ký (USB token) qua phần mềm trung gian của nhà cung cấp. Lớp `@vt/e2ee` trừu tượng hoá hai nguồn khoá này. Lưu ý rằng chứng thư ký số từ xa (như MySign) chỉ dùng để ký, không dùng được để giải mã, nên không thay thế được khoá mã hoá trên thiết bị.

### Chia sẻ và thu hồi

Thêm người nhận cho nội dung đã có không cần mã hoá lại nội dung: một người nhận hiện hữu (hoặc người gửi) mở CEK trên thiết bị của mình và bọc lại cho người nhận mới, gửi lên máy chủ. Máy chủ kiểm tra quyền chia sẻ theo ABAC trước khi chấp nhận. Thu hồi quyền xem có giới hạn cố hữu: người đã từng giải mã có thể đã lưu nội dung; thiết kế chỉ bảo đảm người bị thu hồi không tải được bản mã từ máy chủ nữa và nội dung mới dùng CEK mới không bọc cho họ. Giới hạn này được nêu trong hướng dẫn sử dụng.

### Khôi phục khi mất khoá

Mất thiết bị là trường hợp thường gặp. Thiết kế có hai cơ chế. Cơ chế thứ nhất là mỗi CEK luôn được bọc thêm cho một khoá phục hồi của tổ chức (tenant). Khoá riêng phục hồi được chia theo lược đồ chia sẻ bí mật Shamir 3 trên 5 giữa năm cán bộ được chỉ định của cơ quan tổ chức, mỗi phần lưu trên thẻ thông minh riêng; khôi phục cần ba người cùng có mặt, thực hiện trên máy trạm cách ly, mọi lần khôi phục ghi sổ cái. Cơ chế thứ hai là người dùng có thể đăng ký thêm thiết bị thứ hai; khi thiết bị cũ còn hoạt động, nó bọc lại các CEK cho thiết bị mới. Cơ chế thứ nhất là điều kiện bắt buộc vì dữ liệu công vụ không được phép mất chỉ vì một người mất điện thoại; cũng vì cơ chế này, C3 bảo vệ trước nhà vận hành và kẻ tấn công máy chủ, chứ không bảo vệ trước cơ quan chủ quản theo quy trình có kiểm soát, đúng với tính chất dữ liệu công vụ.

### Giới hạn tìm kiếm, phân tích và AI

Mã hoá đầu cuối có cái giá rõ ràng. Máy chủ không tìm toàn văn được trên nội dung C3; OpenSearch không lập chỉ mục chúng. Tra cứu chỉ qua chỉ mục mù trên vài trường mô tả do người gửi tính, như mã nhiệm vụ, số văn bản, với khoá HMAC của nhóm chia sẻ (bọc cho thành viên nhóm như CEK); chỉ mục mù chỉ hỗ trợ so khớp chính xác. Danh sách hiển thị dựa trên siêu dữ liệu dạng rõ tối thiểu (trạng thái, hạn, đơn vị chủ trì, người giao) và tiêu đề đã mã hoá được giải mã tại trình duyệt cho các dòng đang hiển thị.

Phân tích và AI không xử lý nội dung C3. ClickHouse chỉ nhận siêu dữ liệu, nên dashboard đếm được số nhiệm vụ hạn chế theo trạng thái, theo đơn vị, theo hạn, nhưng không thống kê theo nội dung. ai-gateway không nhận C3; trợ lý AI không tóm tắt, không trích xuất từ nội dung C3. OCR không chạy; nếu người dùng cần OCR, việc đó phải chạy trên thiết bị. Quét mã độc phía máy chủ không thực hiện được trên tệp C3; thiết kế bù bằng danh sách định dạng tệp cho phép hẹp (PDF, DOCX, ảnh), kiểm tra cấu trúc tệp tại trình duyệt trước khi mã hoá, và hiển thị tệp nhận được trong trình xem cách ly (PDF.js bản mới nhất trong iframe sandbox) thay vì mở bằng ứng dụng ngoài. Bộ phát hiện tám dấu hiệu bất thường vẫn hoạt động trên siêu dữ liệu (thời điểm cập nhật, trạng thái, mã băm bản mã để phát hiện cùng một tệp mã hoá bị nộp lại), nhưng không phát hiện được trùng nội dung giữa hai bản mã khác khoá.

Vì cái giá này, C3 chỉ dành cho số ít nội dung thực sự cần, và người gửi chủ động đánh dấu. Ước tính thiết kế là C3 chiếm dưới 1% số nhiệm vụ và dưới 0,5% số tệp.

## Ranh giới pháp lý với bí mật nhà nước

Mã hoá đầu cuối mạnh về kỹ thuật nhưng không biến cell SaaS thành hệ thống được phép xử lý bí mật nhà nước. Theo Luật Bảo vệ bí mật nhà nước 2018 và các văn bản hướng dẫn, thông tin thuộc danh mục bí mật nhà nước chỉ được soạn thảo, lưu giữ, truyền đưa trên hệ thống đáp ứng yêu cầu bảo vệ bí mật nhà nước và phải dùng mật mã của cơ quan cơ yếu. Thuật toán AES, ECDH trên WebCrypto, dù mạnh, không phải mật mã cơ yếu.

Thiết kế vạch ranh giới cứng như sau. Cell SaaS và đám mây riêng không xử lý bí mật nhà nước; giao diện không có lựa chọn đóng dấu độ mật "Mật", "Tối mật", "Tuyệt mật"; điều khoản sử dụng và hướng dẫn nêu rõ; một bộ cảnh báo đơn giản nhắc người dùng khi tiêu đề hoặc tệp chứa cụm từ đóng dấu độ mật. Thông tin bí mật nhà nước của khối quân đội, công an chỉ được xử lý trên bản cài cách ly, nơi `CryptoProvider` được thay bằng bộ mật mã chuyên dùng của Ban Cơ yếu Chính phủ cho cả mã hoá trường, mã hoá đầu cuối và ký số, qua cùng giao diện lập trình. Thiết kế chi tiết của bộ mật mã chuyên dùng nằm ngoài phạm vi tài liệu và do cơ quan chuyên trách thẩm định.

## Ngân sách độ trễ cho bốn loại thao tác

Bảng dưới đây cộng từng thành phần độ trễ phía máy chủ và phía thiết bị cho bốn thao tác đại diện, với dữ liệu thường (C1 kèm vài trường C2) và dữ liệu mật (C3). Các giá trị là p95 ước tính trên một cell không quá tải, chưa gồm thời gian truyền mạng Internet giữa người dùng và trung tâm dữ liệu trừ khi ghi rõ. Thao tác thứ ba và thứ tư có phần bất đồng bộ được tách riêng.

| Thành phần | Đọc danh sách 50 nhiệm vụ, thường | Đọc danh sách, mật | Cập nhật tiến độ, thường | Cập nhật tiến độ, mật | Nộp minh chứng 10 MB, thường | Nộp minh chứng 10 MB, mật | Quyết định xếp loại có ký số, thường | Quyết định xếp loại, mật |
|---|---|---|---|---|---|---|---|---|
| Cổng API, WAF | 3 ms | 3 ms | 3 ms | 3 ms | 3 ms mỗi phần | 3 ms mỗi phần | 3 ms | 3 ms |
| Kiểm JWT | 0,3 ms | 0,3 ms | 0,3 ms | 0,3 ms | 0,3 ms | 0,3 ms | 0,3 ms | 0,3 ms |
| mTLS ztunnel | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms |
| OPA | 1 ms (đánh giá từng phần) | 1 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms | 0,5 ms |
| Kiểm tra kỳ khoá | 0 | 0 | 0,1 ms | 0,1 ms | 0,1 ms | 0,1 ms | 0,1 ms | 0,1 ms |
| Logic ứng dụng | 5 ms | 5 ms | 10 ms | 10 ms | 10 ms | 10 ms | 15 ms | 15 ms |
| CSDL, outbox | 15 ms | 15 ms | 8 ms | 9 ms | 8 ms | 8 ms | 12 ms | 12 ms |
| Mã hoá, giải mã máy chủ C2 | 0,3 ms | 0 | 0,05 ms | 0 | 0 | 0 | 0,05 ms | 0 |
| Băm SHA-256 máy chủ | 0 | 0 | 0 | 0 | 15 đến 25 ms (chồng lên truyền) | 15 đến 25 ms trên bản mã | 0 | 0 |
| Tuần tự hoá, kích thước phản hồi | 3 ms (khoảng 40 KB) | 4 ms (khoảng 60 KB) | 1 ms | 1 ms | 1 ms | 1 ms | 1 ms | 1 ms |
| Tổng phía máy chủ | khoảng 28 ms | khoảng 29 ms | khoảng 24 ms | khoảng 25 ms | khoảng 40 đến 50 ms ngoài truyền | khoảng 40 đến 50 ms ngoài truyền | khoảng 32 ms, trả 202 | khoảng 32 ms, trả 202 |
| Mật mã tại thiết bị | 0 | khoảng 2 ms mỗi dòng hiển thị, 20 dòng: 40 ms | 0 | khoảng 5 ms (sinh CEK, bọc cho 3 người nhận) | 0 | 30 đến 80 ms mã hoá theo đoạn 1 MB | 0 | khoảng 1 đến 2 s sinh PDF tại thiết bị |
| Truyền mạng (4G khoảng 20 Mbit/s lên) | khoảng 100 ms | khoảng 120 ms | khoảng 100 ms | khoảng 100 ms | khoảng 4 s | khoảng 4 s | khoảng 100 ms | khoảng 300 ms |
| Phần bất đồng bộ (không chờ) | Không | Không | Sổ cái, phân tích dưới 2 s | Như thường | Quét 0,5 đến 2 s, OCR vài giây | Không quét, không OCR | Sinh PDF 0,5 s, ký từ xa 1 đến 3 s cộng xác nhận của người ký, TSA 0,1 đến 0,3 s | Ký trên băm, TSA |
| Người dùng cảm nhận | khoảng 0,15 s | khoảng 0,2 s | khoảng 0,15 s | khoảng 0,15 s | khoảng 4 s, chủ yếu truyền | khoảng 4,1 s | 0,15 s tới "chờ ký", hoàn tất khi người ký xác nhận | khoảng 1,5 đến 2,5 s tới "chờ ký" |
| Ngưỡng NFR-03 | Đọc ≤ 300 ms | Đọc ≤ 300 ms | Ghi ≤ 800 ms | Ghi ≤ 800 ms | Tác vụ dài, trả mã ≤ 200 ms cho bước hoàn tất | Như thường | Ghi ≤ 800 ms, ký nền | Như thường |

Có ba nhận xét từ bảng. Thứ nhất, với dữ liệu thường, mọi thao tác đều nằm trong ngưỡng NFR-03 với biên rộng: thao tác ghi khoảng 24 đến 32 ms phía máy chủ so với ngưỡng 800 ms, đọc khoảng 28 ms so với 300 ms. Biên này cần thiết vì dưới tải cao, hàng đợi ở pool kết nối và CPU sẽ làm thời gian thực tế tăng; TL-04 đo ở 30.000 request/giây. Thứ hai, với dữ liệu mật, chi phí phía máy chủ gần như không đổi, vì máy chủ chỉ lưu bản mã; chi phí chuyển sang thiết bị, chủ yếu ở việc giải mã từng dòng danh sách (tối ưu bằng chỉ giải mã dòng đang hiển thị) và ở việc sinh PDF tại thiết bị cho phiếu chứa nội dung C3. Thứ ba, thao tác chậm nhất về cảm nhận là nộp minh chứng 10 MB, bị chi phối bởi băng thông mạng chứ không phải lớp bảo vệ; băm SHA-256 chồng lên thời gian truyền, Object Lock không thêm thời gian, quét mã độc chạy nền.

Phiếu xếp loại chứa C3 là trường hợp đặc biệt cần nói thêm. Nếu phần nhận xét của phiếu là C3, máy chủ không sinh được PDF vì không đọc được nội dung. Thiết kế sinh PDF tại trình duyệt của người có quyền (dùng thư viện tạo PDF chạy trên trình duyệt với mẫu do document-service cung cấp), tính băm SHA-256 của PDF tại thiết bị, gửi băm đi ký từ xa (MySign ký trên băm, không cần nội dung), rồi nhúng chữ ký vào PDF tại thiết bị và mã hoá PDF đã ký trước khi tải lên. Chi phí thêm khoảng 1 đến 2 giây tại thiết bị, chỉ áp dụng cho số ít phiếu C3.

## Kết luận G3

Bảy lớp bảo vệ không làm hệ thống chậm nếu được đặt đúng chỗ. Trên đường găng của một request ghi thông thường, tổng chi phí của các lớp bảo vệ khoảng 0,5 đến 1 ms (dòng outbox cho sổ cái, tra bộ đệm kỳ khoá, mã hoá trường C2, đọc claim xác thực), cộng chi phí hạ tầng an ninh (WAF, mTLS, OPA) khoảng 4 đến 5 ms, trong tổng khoảng 25 ms phía máy chủ, so với ngưỡng 800 ms của NFR-03. Các lớp có chi phí lớn, gồm ký số (1 đến 3 giây mỗi phiên), quét mã độc (0,5 đến 2 giây mỗi tệp), niêm phong Merkle và dấu thời gian, đều chạy bất đồng bộ hoặc theo lô, chỉ ở mốc pháp lý tần suất thấp.

Mã hoá được phân theo lớp dữ liệu. C1 chỉ mã hoá khi truyền và khi lưu ở mức đĩa, không tốn chi phí ứng dụng. C2 dùng mã hoá phong bì với DEK theo tenant, tháng và mục đích, KEK trong OpenBao được HSM bảo vệ, bộ đệm DEK 15 phút, chi phí 1 đến 3 µs mỗi trường và 5 ms mở khoá ít hơn một lần mỗi 15 phút mỗi pod. C3 dùng mã hoá đầu cuối tại thiết bị, máy chủ chỉ giữ bản mã, chi phí chuyển sang thiết bị (khoảng 2 ms mỗi dòng giải mã, 30 đến 80 ms mã hoá tệp 10 MB) và đổi lại mất khả năng tìm kiếm, phân tích, AI, OCR, quét mã độc phía máy chủ trên nội dung; vì vậy C3 chỉ dành cho số ít nội dung được đánh dấu. Bí mật nhà nước không thuộc phạm vi của cell SaaS dù có mã hoá đầu cuối; nó chỉ được xử lý ở bản cài cách ly với mật mã của Ban Cơ yếu qua lớp `CryptoProvider`. Quyết định được ghi tại ADR-06 và ADR-09; TL-04 kiểm chứng bằng kịch bản thử tải có bật đầy đủ các lớp bảo vệ.

# Policy Pack: quy tắc chấm điểm như mã có phiên bản

## Vấn đề cần giải quyết

Quy định đánh giá, xếp loại thay đổi thường xuyên và khác nhau giữa các khối. Hệ thống hiện tại nhúng quy tắc trong mã Java và câu lệnh SQL, nên mỗi lần Ban Tổ chức sửa hướng dẫn là một đợt phát triển, kiểm thử, triển khai kéo dài nhiều tuần, và không ai chứng minh được kết quả kỳ trước được tính theo đúng quy tắc của kỳ trước. Policy Pack (gói quy tắc) giải quyết vấn đề này bằng cách tách quy tắc ra khỏi mã thành một tạo phẩm có phiên bản, được kiểm thử bằng bộ ca mẫu do nghiệp vụ ký, được ký số khi phát hành, có hiệu lực theo kỳ, và được ghi kèm vào mọi kết quả tính. Mục tiêu đo được là thời gian từ khi có hướng dẫn mới tới khi áp dụng không quá 5 ngày làm việc (chỉ số của đợt 2), và 100% kết quả kỳ đã khoá tính lại được trùng khớp từng chữ số.

## Cấu trúc gói

Một Policy Pack là một thư mục được đóng gói thành tệp `.tar.zst` có chữ ký tách rời. Thư mục gồm tệp mô tả `policy.yaml`, các bảng quyết định DMN trong thư mục `dmn/`, bộ ca mẫu trong `tests/`, mẫu biểu trong `templates/` (tham chiếu tới document-service), và tệp `CHANGELOG.md` do nghiệp vụ viết. Gói có định danh `<khối>/<tên>` và phiên bản ngữ nghĩa: thay đổi phiên bản chính khi công thức hoặc ngưỡng đổi làm kết quả có thể khác; phiên bản phụ khi bổ sung tiêu chí mà không đổi kết quả cũ; bản vá khi sửa nhãn, mô tả.

Ví dụ dưới đây là bản đầy đủ hơn của ví dụ trong báo cáo Ban Giám đốc (slide 38), thể hiện đủ các phần mà công cụ phát hành kiểm tra.

```yaml
apiVersion: policy.okrkpi.vn/v1
kind: PolicyPack
metadata:
  id: khoi-dang/quy-dinh-moi
  version: 2.1.0
  luong: DANG                      # DANG hoặc NHA_NUOC
  ap_dung_cho:
    tenant_tags: [khoi-dang]
    doi_tuong: [CAN_BO, CONG_CHUC, VIEN_CHUC]
  hieu_luc:
    tu_ky: 2027-Q1
    den_ky: null                   # mở cho đến khi có gói thay thế
  can_cu_phap_ly:
    - "Quy định số .../QĐ-TW về đánh giá, xếp loại chất lượng"
    - "Hướng dẫn số .../HD-BTCTW"
  phe_duyet:
    ky_so: [Viettel-CA, "Ban Tổ chức"]
    nguoi_soan: user:01J9ZK...
    nguoi_duyet: user:01J9ZM...
inputs:                            # khai báo kiểu, nguồn để kiểm tra trước khi tính
  so_luong_duyet:   {type: int,    source: task.aggregate.approved_count}
  ke_hoach:         {type: int,    source: task.aggregate.planned_count, min: 0}
  muc_chat_luong:   {type: double, source: product.aggregate.quality_avg, enum_scale: [1, 0.75, 0.5, 0.25, 0]}
  ngay_lam_viec_cham: {type: int,  source: task.aggregate.late_workdays}
  ty_le_O:          {type: double, source: okr.aggregate.objective_ratio}
  ty_le_vuot:       {type: double, source: task.aggregate.over_target_ratio}
  truot_then_chot_chu_quan: {type: bool, source: task.aggregate.key_missed_subjective}
  nhom_kpi:         {type: "list<NhomKpi>", source: task.aggregate.kpi_groups}
  mien_tru:         {type: "list<MienTru>", source: appraisal.exemptions}
definitions:
  kpi_A: "ke_hoach == 0 ? 1.0 : math.least(double(so_luong_duyet) / double(ke_hoach), 1.0)"
  kpi_B: "muc_chat_luong"
  kpi_C: "math.greatest(0.0, 1.0 - 0.05 * double(ngay_lam_viec_cham))"
  kpi_nhiem_vu: "(kpi_A + kpi_B + kpi_C) / 3.0"
  diem: "nhom_kpi.map(g, g.kpi * g.diem_toi_da).sum()"
rounding:
  diem: {scale: 2, mode: HALF_UP}  # làm tròn thống nhất, ghi trong snapshot
classification:
  dmn: dmn/xep-loai-dang.dmn       # bảng quyết định chính
  thu_tu: [HTXSNV, HTTNV, HTNV, KHTNV]
  thieu_dieu_kien: muc_lien_ke     # thiếu điều kiện phụ thì xuống mức liền kề
exemptions:
  - ma: KHACH_QUAN_THIEN_TAI
    tac_dong: "loai_khoi_mau_so"   # nhiệm vụ được miễn bị loại khỏi kế hoạch
    can_phe_duyet: CAP_TREN_TRUC_TIEP
quotas:
  HTXSNV: {ty_le_toi_da: 0.20, pham_vi: DON_VI_CAP_TREN}
tests:
  bo_ca_mau: tests/bo-ca-mau-ban-to-chuc-v3.yaml
  nguoi_ky: "Ban Tổ chức"
  yeu_cau_dat: 1.0                 # 100% ca phải đạt
```

Phần `inputs` là điểm quan trọng nhất về kỹ thuật. Mỗi biến đầu vào có kiểu và nguồn tổng hợp rõ ràng; scoring-service gọi đúng các API tổng hợp đó để lấy dữ liệu, không đọc bảng của module khác. Nhờ khai báo, công cụ phát hành kiểm tra được mọi biến dùng trong biểu thức đều đã khai báo và đúng kiểu, nên lỗi gõ tên biến bị phát hiện lúc phát hành chứ không phải lúc tính điểm cuối kỳ.

## Ngôn ngữ biểu thức CEL và bảng quyết định DMN

Biểu thức trong `definitions` viết bằng CEL (Common Expression Language), ngôn ngữ biểu thức không Turing đầy đủ của Google: không có vòng lặp tự do, không có tác dụng phụ, luôn dừng, chi phí đánh giá giới hạn được. Thiết kế dùng `cel-java` (dự án `cel-java` chính thức, Apache 2.0) với bộ mở rộng `math` và `lists`, cộng một hàm `sum()` tự định nghĩa. Biểu thức được biên dịch và kiểm tra kiểu một lần khi nạp gói, rồi lưu `Program` trong bộ đệm theo `(pack_id, version)`; đánh giá một hồ sơ khoảng vài chục micro giây.

Phép xếp loại dùng bảng quyết định DMN (Decision Model and Notation) vì nghiệp vụ đọc được dạng bảng và Flowable DMN đã có sẵn. Mỗi dòng là một quy tắc với điều kiện viết bằng FEEL; chính sách khớp `FIRST` theo thứ tự từ mức cao xuống mức thấp.

```xml
<decision id="xep-loai-dang" name="Xếp loại chất lượng, luồng Đảng">
  <decisionTable hitPolicy="FIRST">
    <input label="Điểm"><inputExpression typeRef="number"><text>diem</text></inputExpression></input>
    <input label="Tỷ lệ O"><inputExpression typeRef="number"><text>ty_le_O</text></inputExpression></input>
    <input label="Tỷ lệ vượt"><inputExpression typeRef="number"><text>ty_le_vuot</text></inputExpression></input>
    <input label="Trượt then chốt chủ quan"><inputExpression typeRef="boolean"><text>truot_then_chot_chu_quan</text></inputExpression></input>
    <output name="xep_loai" typeRef="string"/>
    <rule><inputEntry><text>&gt;= 90</text></inputEntry><inputEntry><text>1</text></inputEntry>
          <inputEntry><text>&gt;= 0.30</text></inputEntry><inputEntry><text>-</text></inputEntry>
          <outputEntry><text>"HTXSNV"</text></outputEntry></rule>
    <rule><inputEntry><text>&gt;= 80</text></inputEntry><inputEntry><text>-</text></inputEntry>
          <inputEntry><text>-</text></inputEntry><inputEntry><text>false</text></inputEntry>
          <outputEntry><text>"HTTNV"</text></outputEntry></rule>
    <rule><inputEntry><text>&gt;= 60</text></inputEntry><inputEntry><text>-</text></inputEntry>
          <inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
          <outputEntry><text>"HTNV"</text></outputEntry></rule>
    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
          <inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
          <outputEntry><text>"KHTNV"</text></outputEntry></rule>
  </decisionTable>
</decision>
```

Quy tắc `thieu_dieu_kien: muc_lien_ke` thể hiện qua thứ tự dòng: một hồ sơ có điểm 92 nhưng tỷ lệ vượt chỉ 0,2 không khớp dòng đầu, rơi xuống dòng HTTNV nếu không trượt nhiệm vụ then chốt, tức đúng mức liền kề. Hạn mức HTXSNV không quá 20% trong phạm vi đơn vị cấp trên không tính được trên từng hồ sơ; scoring-service áp nó ở bước hậu xử lý theo lô, đánh dấu các hồ sơ vượt hạn mức để cấp có thẩm quyền quyết định theo quy trình (không tự động hạ mức), vì quy định hiện hành để việc lựa chọn cho tập thể lãnh đạo.

Hai lựa chọn khác đã cân nhắc. Biên dịch CEL sang WebAssembly để chạy cùng một mã trên máy chủ và trình duyệt như gợi ý trong góp ý giao diện bị loại vì chưa có trình biên dịch CEL sang Wasm đủ trưởng thành; thay vào đó trình duyệt dùng `cel-js` cho kiểm tra biểu mẫu, còn điểm chính thức luôn tính ở máy chủ. Dùng Drools bị loại vì ngôn ngữ quy tắc DRL quá mạnh (có tác dụng phụ, suy diễn tiến) khiến nghiệp vụ khó đọc và khó bảo đảm tính dừng.

## Bộ ca mẫu vàng

Bộ ca mẫu vàng (golden test) là cơ chế để nghiệp vụ, không phải lập trình viên, quyết định quy tắc có đúng hay không. Ban Tổ chức (hoặc cơ quan tổ chức của khối) chuẩn bị các hồ sơ mẫu với đầu vào và kết quả mong đợi tính bằng tay theo hướng dẫn, ký số tệp ca mẫu, và công cụ phát hành chạy gói trên toàn bộ ca, yêu cầu đạt 100% mới được chuyển trạng thái `DAT_KIEM_THU`.

```yaml
bo_ca_mau: bo-ca-mau-ban-to-chuc-v3
ca:
  - id: TC-001
    mo_ta: "Hoàn thành xuất sắc, đủ mọi điều kiện"
    dau_vao: {so_luong_duyet: 12, ke_hoach: 12, muc_chat_luong: 1.0, ngay_lam_viec_cham: 0,
              ty_le_O: 1.0, ty_le_vuot: 0.35, truot_then_chot_chu_quan: false,
              nhom_kpi: [{kpi: 1.0, diem_toi_da: 60}, {kpi: 0.95, diem_toi_da: 40}]}
    mong_doi: {kpi_nhiem_vu: 1.0, diem: 98.00, xep_loai: HTXSNV}
  - id: TC-014
    mo_ta: "Đủ điểm xuất sắc nhưng thiếu tỷ lệ vượt, xuống mức liền kề"
    dau_vao: {so_luong_duyet: 10, ke_hoach: 10, muc_chat_luong: 1.0, ngay_lam_viec_cham: 0,
              ty_le_O: 1.0, ty_le_vuot: 0.20, truot_then_chot_chu_quan: false,
              nhom_kpi: [{kpi: 0.95, diem_toi_da: 100}]}
    mong_doi: {diem: 95.00, xep_loai: HTTNV}
  - id: TC-031
    mo_ta: "Kế hoạch bằng 0 do toàn bộ nhiệm vụ được miễn trừ khách quan"
    dau_vao: {so_luong_duyet: 0, ke_hoach: 0, muc_chat_luong: 0.75, ngay_lam_viec_cham: 0,
              ty_le_O: 1.0, ty_le_vuot: 0.0, truot_then_chot_chu_quan: false,
              nhom_kpi: [{kpi: 0.75, diem_toi_da: 100}]}
    mong_doi: {kpi_A: 1.0, diem: 75.00, xep_loai: HTNV}
```

Ngoài ca do nghiệp vụ soạn, quy trình kiểm thử còn sinh tự động các ca biên (điểm đúng 59,995 trước và sau làm tròn, 89,995, kế hoạch bằng 0, ngày chậm rất lớn) và chạy kiểm thử thuộc tính (property-based testing) với jqwik để kiểm các bất biến: điểm luôn nằm trong khoảng 0 đến 100, tăng `so_luong_duyet` không bao giờ làm mức xếp loại giảm, hồ sơ có `ty_le_O < 1` không bao giờ được HTXSNV. Một bộ so sánh còn chạy gói mới và gói đang hiệu lực trên toàn bộ hồ sơ của kỳ gần nhất (dữ liệu đã giả danh hoá) và báo cáo phân bố chênh lệch mức xếp loại, để người phê duyệt thấy tác động thực tế trước khi ký.

## Phát hành, ký số và vòng đời

Gói đi qua máy trạng thái `NHAP → DANG_KIEM_THU → DAT_KIEM_THU → CHO_PHE_DUYET → DA_PHAT_HANH → NGUNG` của baseline. Ở `NHAP`, người soạn sửa gói trong Config Studio (SCR-1701) hoặc trong kho Git của khối. Chuyển `DANG_KIEM_THU` kích hoạt pipeline: kiểm tra lược đồ YAML, biên dịch CEL và kiểm kiểu, phân tích DMN (phát hiện dòng không bao giờ khớp, khoảng hở), chạy bộ ca mẫu vàng, kiểm thử thuộc tính, so sánh tác động. Đạt cả thì sang `DAT_KIEM_THU`; người soạn trình duyệt, gói sang `CHO_PHE_DUYET`. Người phê duyệt (khác người soạn, kiểm bằng ABAC) xem báo cáo tác động, ký số bằng chứng thư của cơ quan; chữ ký là chữ ký CAdES tách rời trên tệp băm SHA-256 của gói. Gói chuyển `DA_PHAT_HANH`, ghi `policy.policy_pack_version` với `content_hash`, `signature`, `effective_from_period`, phát EVT-13 PolicyPackDaPhatHanh, ghi sổ cái.

Khi nạp gói để tính điểm, scoring-service luôn kiểm chữ ký và băm nội dung so với bản ghi; gói không khớp bị từ chối và gây cảnh báo an ninh. Gói đã phát hành là bất biến; sửa lỗi nghĩa là phát hành phiên bản mới. Gói chuyển `NGUNG` khi có gói thay thế từ kỳ sau, nhưng vẫn được giữ vĩnh viễn để tính lại kỳ cũ.

## Hiệu lực theo kỳ

Việc chọn gói cho một hồ sơ dựa trên kỳ của hồ sơ, không dựa trên thời điểm tính. Hàm chọn lấy gói `DA_PHAT_HANH` (hoặc `NGUNG`) có `luong`, `ap_dung_cho` khớp với tenant và đối tượng, có `tu_ky ≤ kỳ` và (`den_ky` rỗng hoặc `den_ky ≥ kỳ`), và nếu có nhiều gói khớp thì lấy gói có `tu_ky` lớn nhất rồi phiên bản lớn nhất. Một ràng buộc CSDL bảo đảm không có hai gói cùng định danh, cùng luồng có khoảng hiệu lực chồng nhau. Nhờ quy tắc này, khi một hướng dẫn mới có hiệu lực từ quý 1/2027, mọi tính toán cho quý 4/2026, kể cả tính lại vào năm 2028, vẫn dùng gói cũ.

Một tenant có thể có gói riêng mở rộng gói chung của khối (ví dụ một tỉnh bổ sung tiêu chí đặc thù). Gói mở rộng khai báo `extends: khoi-dang/quy-dinh-moi@^2.1` và chỉ được thêm định nghĩa, thêm nhóm KPI, siết ngưỡng; không được nới ngưỡng của gói cha. Công cụ phát hành kiểm tra ràng buộc này bằng cách chạy bộ ca mẫu của gói cha trên gói con và yêu cầu không ca nào cho mức cao hơn.

## Tính lại tái lập được

Để chứng minh một kết quả được tính đúng, mọi kết quả chấm điểm lưu một ảnh chụp (snapshot) trong `scoring.score_result`: định danh và phiên bản gói, băm nội dung gói, toàn bộ giá trị đầu vào đã chuẩn hoá, băm của đầu vào, kết quả từng biểu thức trung gian, kết quả cuối, phiên bản của scoring-service. Băm đầu vào là SHA-256 của dạng JSON chuẩn hoá theo RFC 8785 (JSON Canonicalization Scheme), nên hai lần tính trên cùng dữ liệu cho cùng băm bất kể thứ tự trường.

```json
{
  "profile_id": "01JA2K7Q9X...",
  "period": "2027-Q1",
  "policy": {"id": "khoi-dang/quy-dinh-moi", "version": "2.1.0",
             "content_hash": "sha256:4f1c...9a"},
  "engine": "scoring-service 1.6.3",
  "inputs_hash": "sha256:b07e...12",
  "inputs": {"so_luong_duyet": 12, "ke_hoach": 12, "muc_chat_luong": 1.0, "...": "..."},
  "trace": {"kpi_A": 1.0, "kpi_B": 1.0, "kpi_C": 1.0, "kpi_nhiem_vu": 1.0, "diem": 98.0},
  "result": {"diem": 98.00, "xep_loai": "HTXSNV"},
  "computed_at": "2027-04-08T10:15:02Z"
}
```

Công cụ tính lại (`scoring recompute --profile <id>`) nạp đúng gói theo băm, nạp đầu vào từ snapshot, tính lại và so sánh từng giá trị trung gian. Kiểm tra định kỳ chạy tính lại ngẫu nhiên 1% kết quả mỗi kỳ đã khoá; kết quả khác nhau là sự cố mức cao. Để tính lại tái lập trên nhiều năm, phép toán dùng `BigDecimal` cho làm tròn cuối với quy tắc trong `rounding`, các phiên bản scoring-service cũ được giữ image để chạy lại khi cần, và CEL được cấu hình không dùng hàm phụ thuộc thời gian hay ngẫu nhiên. Ngoài tính lại từ snapshot, công cụ còn có chế độ đối chiếu: tổng hợp lại đầu vào từ dữ liệu gốc (qua truy vấn "tại thời điểm" khoá kỳ) và so với đầu vào trong snapshot, để phát hiện dữ liệu gốc bị sửa sau khi khoá.

## Hai luồng Đảng và Nhà nước

Tiêu chí H9 yêu cầu hai luồng đánh giá chạy song song cho cùng một người là đảng viên đồng thời là công chức. Thiết kế mô hình hoá luồng ở ba chỗ. Ở dữ liệu, `appraisal.appraisal_profile` có cột `luong` và ràng buộc duy nhất `(tenant_id, period_id, subject_id, luong)`, nên một người có tối đa hai hồ sơ mỗi kỳ. Ở quy tắc, mỗi luồng có gói riêng: luồng Đảng tính trên thang 100 với ngưỡng 90, 80, 60; luồng Nhà nước tính theo cơ cấu 30% tiêu chí chung và 70% kết quả nhiệm vụ, với ngưỡng 90, 70, 50 của baseline. Ở quy trình, mỗi luồng có mẫu BPMN riêng với cấp thẩm định, quyết định khác nhau (cấp uỷ với luồng Đảng, thủ trưởng với luồng Nhà nước).

Hai luồng dùng chung dữ liệu đầu vào (nhiệm vụ, sản phẩm, OKR) nhưng có thể dùng khác nhau: luồng Nhà nước dùng toàn bộ nhiệm vụ chuyên môn, luồng Đảng còn thêm tiêu chí rèn luyện, phẩm chất được nhập ở bước tự đánh giá. Một quy tắc ràng buộc liên luồng (theo quy định hiện hành, mức xếp loại đảng viên không cao hơn mức xếp loại công chức đối với người giữ chức vụ) được khai báo ở gói luồng Đảng dưới dạng đầu vào `xep_loai_nha_nuoc` lấy từ hồ sơ luồng kia, và saga đánh giá bảo đảm thứ tự: hồ sơ luồng Đảng chỉ được quyết định sau khi hồ sơ luồng Nhà nước ở trạng thái `DA_QUYET_DINH`.

## Tính gia tăng và tính theo lô

scoring-service tính điểm theo hai chế độ. Chế độ gia tăng chạy khi có sự kiện ảnh hưởng tới đầu vào trong kỳ (EVT-05 TienDoDaCapNhat, EVT-06 TrangThaiNhiemVuDoi, EVT-08 MinhChungDaXacNhan, EVT-02 KrDaCheckIn): service tính lại điểm tạm tính của người liên quan và cập nhật bảng điều hành, cho cán bộ thấy điểm dự kiến trong kỳ. Để không tính lại hàng nghìn lần khi một đơn vị nghiệm thu hàng loạt, sự kiện được gom theo người trong cửa sổ 30 giây (khoá phân vùng Kafka theo `subject_id` và bộ đệm gom trong consumer). Điểm tạm tính không có giá trị pháp lý, không ký, không vào sổ cái.

Chế độ theo lô chạy khi khoá kỳ: saga khoá kỳ phát lệnh tính chính thức cho toàn bộ hồ sơ của tenant, scoring-service chia thành các lô 1.000 hồ sơ, xử lý song song trên các pod, lưu snapshot, phát EVT-10 DiemDaTinh theo lô. Theo ước tính ở chương 5 (SVC-07), cell lớn cần khoảng 167 hồ sơ/giây để tính xong 1,2 triệu hồ sơ trong 2 giờ; với khoảng 300 hồ sơ/giây mỗi pod (giới hạn bởi lời gọi lấy đầu vào theo lô, không phải CEL), 2 đến 4 pod là đủ, cấu hình tối đa 12 pod để còn dư cho tính lại. Kết quả chính thức chỉ sinh từ chế độ lô, sau khi kỳ ở `DANG_KHOA`, nên đầu vào ổn định.

# Toàn vẹn dữ liệu và bằng chứng pháp lý

## Mục tiêu

Kết quả đánh giá ảnh hưởng tới quyền lợi của cán bộ và có thể bị khiếu nại, thanh tra. Nền tảng vì vậy phải chứng minh được ba điều với bên thứ ba, kể cả khi bên thứ ba không tin nhà vận hành: dữ liệu pháp lý không bị sửa sau khi ghi, mỗi sự kiện xảy ra không muộn hơn thời điểm được ghi, và kết quả được tính theo đúng quy tắc của kỳ. Chương này thiết kế sổ cái chỉ ghi với chuỗi băm phân vùng, niêm phong Merkle có dấu thời gian RFC 3161, bản neo bên ngoài, công cụ kiểm chứng độc lập, cùng với dữ liệu hai trục thời gian và khoá kỳ có hai người duyệt. Cơ chế tính lại tái lập đã trình bày ở chương Policy Pack.

## Sổ cái chuỗi băm phân vùng

ledger-service (SVC-09) ghi bảng `ledger.ledger_entry`. Một chuỗi đơn cho mỗi tenant sẽ buộc ghi tuần tự, thành nút thắt khi một tenant lớn có hàng trăm sự kiện mỗi giây. Thiết kế chia mỗi tenant thành 64 phân vùng chuỗi theo `hash(aggregate_id) mod 64`, nên mọi sự kiện của cùng một đối tượng (cùng nhiệm vụ, cùng hồ sơ) nằm trong cùng chuỗi và giữ thứ tự, còn các đối tượng khác nhau được ghi song song. Phân vùng chuỗi trùng với phân vùng Kafka của topic `sys.ledger.append.v1` để mỗi consumer sở hữu một tập chuỗi và không cần khoá phân tán.

Mỗi bản ghi gồm `tenant_id`, `chain_no` (0 đến 63), `seq` (tăng liên tục trong chuỗi), `event_id`, `event_type`, `aggregate_id`, `occurred_at`, `recorded_at`, `payload_hash`, `prev_hash`, `entry_hash`. Trường `entry_hash = SHA-256(0x01 || canonical(header) || payload_hash || prev_hash)`, với tiền tố miền `0x01` phân biệt băm bản ghi với băm nút Merkle (`0x02`) để tránh tấn công nhầm miền. `payload_hash` là băm của dữ liệu sự kiện chuẩn hoá theo RFC 8785; bản thân dữ liệu nằm ở module nguồn và ở kho sự kiện, sổ cái chỉ lưu băm để không nhân bản dữ liệu C2. Tài khoản của ledger-service chỉ có quyền `INSERT` và `SELECT`; một trigger từ chối `UPDATE`, `DELETE`, `TRUNCATE`, và tài khoản quản trị CSDL bị giám sát qua pgAudit, nên mọi cố gắng sửa đều để lại vết và đều bị công cụ kiểm chứng phát hiện.

```sql
CREATE TABLE ledger.ledger_entry (
  tenant_id     uuid        NOT NULL,
  chain_no      smallint    NOT NULL CHECK (chain_no BETWEEN 0 AND 63),
  seq           bigint      NOT NULL,
  event_id      uuid        NOT NULL,
  event_type    text        NOT NULL,
  aggregate_id  uuid        NOT NULL,
  occurred_at   timestamptz NOT NULL,
  recorded_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  payload_hash  bytea       NOT NULL,
  prev_hash     bytea       NOT NULL,
  entry_hash    bytea       NOT NULL,
  PRIMARY KEY (tenant_id, chain_no, seq),
  UNIQUE (tenant_id, event_id)
) PARTITION BY LIST (tenant_id);

CREATE FUNCTION ledger.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ledger_entry chỉ cho phép chèn'; END $$;
CREATE TRIGGER ledger_no_update BEFORE UPDATE OR DELETE ON ledger.ledger_entry
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation();
CREATE TRIGGER ledger_no_truncate BEFORE TRUNCATE ON ledger.ledger_entry
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation();
```

Bảng không có cột nào cần cập nhật sau khi chèn. Liên kết giữa bản ghi và lần niêm phong được biểu diễn ngược lại: bảng `ledger.merkle_seal` lưu khoảng `seq` đã niêm phong của từng chuỗi, nên bảng chính giữ đúng tính chất chỉ chèn.

Ràng buộc duy nhất trên `event_id` làm việc ghi idempotent: Kafka giao lại sự kiện thì lần chèn thứ hai vi phạm ràng buộc và bị bỏ qua. Sự kiện đến sai thứ tự trong cùng chuỗi không xảy ra vì cùng phân vùng Kafka; nếu consumer khởi động lại, nó đọc `seq` và `entry_hash` cuối của từng chuỗi từ CSDL trước khi tiếp tục.

## Định dạng băm chuẩn hoá

Băm chỉ có ý nghĩa nếu mọi bên tính ra cùng một giá trị từ cùng dữ liệu. Thiết kế quy định chuẩn hoá theo RFC 8785: khoá đối tượng sắp theo thứ tự điểm mã UTF-16, không khoảng trắng, số theo biểu diễn ngắn nhất của ECMAScript, chuỗi Unicode ở dạng NFC trước khi chuẩn hoá (yêu cầu bổ sung vì tiếng Việt có nhiều cách tổ hợp dấu). Dấu thời gian luôn ở dạng `YYYY-MM-DDThh:mm:ss.ffffffZ` theo UTC. Giá trị tiền, điểm biểu diễn bằng chuỗi thập phân để tránh sai lệch dấu phẩy động. Header của bản ghi chuẩn hoá gồm đúng các trường `tenant_id`, `chain_no`, `seq`, `event_id`, `event_type`, `aggregate_id`, `occurred_at`. Thư viện chuẩn hoá có bản Java, Go và TypeScript, cùng một bộ vector kiểm thử chung để bảo đảm ba bản cho cùng kết quả.

## Niêm phong Merkle, dấu thời gian và bản neo

![Hình 11.1. Chuỗi băm phân vùng, cây Merkle và bản neo](kt-so-cai-merkle.png){width=16cm}

Hình trên mô tả hai tầng niêm phong. Mỗi giờ, ledger-service lấy `entry_hash` cuối của 64 chuỗi của tenant làm lá, dựng cây Merkle nhị phân (băm nút `SHA-256(0x02 || trái || phải)`), được gốc tenant. Gốc của mọi tenant trong cell lại làm lá cho cây cấp cell. Gốc cấp cell được ký bằng khoá niêm phong của cell nằm trong HSM (ECDSA P-256) và gửi tới dịch vụ cấp dấu thời gian (TSA) theo RFC 3161 của tổ chức chứng thực được cấp phép, nhận về token dấu thời gian. Bản ghi `ledger.merkle_seal` lưu gốc, chữ ký, token TSA, và cho mỗi tenant đường đi Merkle từ gốc tenant lên gốc cell.

Token TSA chứng minh gốc tồn tại không muộn hơn thời điểm trong token, do bên thứ ba có thẩm quyền xác nhận; vì gốc phụ thuộc mọi bản ghi trước đó, mọi bản ghi được niêm phong đều được chứng minh thời điểm. Khoảng cách giữa hai lần niêm phong (tối đa 1 giờ) là cửa sổ mà trong đó nhà vận hành về lý thuyết có thể viết lại chuỗi chưa niêm phong; cửa sổ này được rút xuống 5 phút trong 3 ngày cuối kỳ và ngay khi có sự kiện mốc pháp lý (khoá kỳ, quyết định xếp loại), saga gọi niêm phong ngay.

Để chống trường hợp nhà vận hành dựng lại toàn bộ sổ cái và xin dấu thời gian mới, gốc cấp cell hằng ngày được neo ra ngoài, gọi là bản neo (anchor): gửi tới ít nhất hai nơi độc lập với nhà vận hành, là một hệ thống của cơ quan chủ quản (ví dụ Ban Tổ chức nhận qua LGSP) và hệ thống lưu trữ của một đơn vị kiểm toán độc lập, mỗi nơi trả biên nhận được lưu ở `ledger.anchor_receipt`. Bản neo nhỏ (một gốc 32 byte mỗi cell mỗi ngày), nên chi phí không đáng kể. Thiết kế không dùng chuỗi khối công cộng vì vấn đề pháp lý và chủ quyền dữ liệu, và vì hai bên độc lập có thẩm quyền là đủ cho mục đích chứng cứ hành chính.

## Công cụ kiểm chứng độc lập

`ledger-verify` là công cụ dòng lệnh viết bằng Go, mã nguồn được công bố cho cơ quan thanh tra, kiểm toán, chạy được hoàn toàn ngoại tuyến từ một gói xuất (export bundle) gồm các bản ghi sổ cái của phạm vi cần kiểm, các bản niêm phong, chứng thư của khoá niêm phong và của TSA. Công cụ thực hiện năm bước: tính lại `entry_hash` của từng bản ghi và kiểm liên kết `prev_hash` liên tục, không thiếu `seq`; dựng lại gốc tenant từ đầu chuỗi tại mỗi lần niêm phong; kiểm đường đi Merkle tới gốc cell; kiểm chữ ký gốc và token RFC 3161 theo chuỗi chứng thư; và, nếu được cung cấp, đối chiếu gốc với bản neo lấy trực tiếp từ bên độc lập.

```text
$ ledger-verify --bundle hcm-2027Q1-donvi-123.tar --anchors anchors-btc.json
Chuỗi 0..63: 1.284.330 bản ghi, liên kết hợp lệ, không thiếu seq
Niêm phong: 2.184 lần, gốc khớp, chữ ký hợp lệ (CN=Ledger Seal CELL-HCM)
Dấu thời gian RFC 3161: 2.184 token hợp lệ (TSA: ...), sớm nhất 2027-01-01T00:59:58Z
Bản neo: 91/91 ngày khớp với bên độc lập Ban Tổ chức
Kết luận: HỢP LỆ
```

Để kiểm một hồ sơ cụ thể mà không cần tải toàn bộ chuỗi, công cụ còn hỗ trợ bằng chứng bao hàm (inclusion proof): với một sự kiện, gói xuất chứa các bản ghi từ sự kiện tới lần niêm phong kế tiếp trong chuỗi của nó, cộng đường đi Merkle. ledger-service chạy kiểm chứng toàn bộ hằng ngày trên bản sao, ghi kết quả vào `ledger.verify_run`, và phát cảnh báo nếu thất bại.

## Ký số tại mốc pháp lý

Sổ cái chứng minh tính toàn vẹn của chuỗi sự kiện; chữ ký số chứng minh ý chí của người có thẩm quyền đối với một tài liệu. Hai cơ chế bổ sung nhau. Ở bốn mốc pháp lý và ở biên bản kiểm điểm, document-service sinh PDF/A, esign-service ký PAdES mức B-LTA (có dấu thời gian và dữ liệu kiểm tra chứng thư nhúng để kiểm được lâu dài) qua `SignatureProvider`, rồi sự kiện "đã ký" kèm băm của PDF đã ký được ghi sổ cái. Nhờ vậy, mỗi văn bản được ký vừa tự kiểm chứng được theo chuẩn chữ ký, vừa có vị trí trong chuỗi sự kiện. Chữ ký PAdES-LTA cần gia hạn dấu thời gian lưu trữ trước khi thuật toán hoặc chứng thư TSA hết hạn; job gia hạn chạy hằng năm cho tài liệu có thời hạn lưu trữ dài.

## Dữ liệu hai trục thời gian

Tranh chấp về kết quả đánh giá thường xoay quanh câu hỏi "vào thời điểm đó, ai là cấp trên trực tiếp, người này thuộc đơn vị nào, được giao nhiệm vụ gì". Sáp nhập tỉnh, đơn vị năm 2025 cho thấy câu trả lời thay đổi và có khi được sửa hồi tố. Dữ liệu hai trục thời gian (bitemporal) ghi cả thời gian hiệu lực (`valid_from`, `valid_to`: sự thật có hiệu lực trong đời thực từ khi nào) và thời gian ghi nhận (`recorded_from`, `recorded_to`: hệ thống biết sự thật đó từ khi nào).

Thiết kế áp dụng cho `org.unit`, `org.position_assignment`, `org.reporting_line` và `task.assignment`. Một quyết định điều động ký ngày 10 có hiệu lực từ ngày 1 được ghi với `valid_from = ngày 1`, `recorded_from = ngày 10`. Truy vấn "cấp trên trực tiếp của A tại ngày 5 theo hiểu biết tại ngày 7" trả cấp trên cũ, còn "theo hiểu biết hiện nay" trả cấp trên mới, và cả hai câu trả lời đều đúng và có thể giải thích. Bảng không bao giờ cập nhật dòng: thay đổi đóng `recorded_to` của dòng cũ và chèn dòng mới. Ràng buộc loại trừ (`EXCLUDE USING gist`) bảo đảm không có hai dòng hiện hành chồng khoảng hiệu lực cho cùng đối tượng.

```sql
SELECT manager_id
FROM org.reporting_line
WHERE tenant_id = current_setting('app.tenant_id')::uuid
  AND subject_id = :subject
  AND valid_period    @> :as_of_valid::timestamptz
  AND recorded_period @> :as_of_known::timestamptz;
```

Khi khoá kỳ, saga ghi lại `recorded_at` của thời điểm khoá; mọi tính toán chính thức và tính lại dùng "theo hiểu biết tại thời điểm khoá", nên sửa hồi tố sau khoá kỳ không làm thay đổi kết quả đã khoá. Nếu sửa hồi tố cần ảnh hưởng tới kết quả (ví dụ quyết định điều động bị phát hiện nhập sai), đó là lý do để mở lại kỳ theo quy trình hai người duyệt.

## Khoá kỳ và mở lại có hai người duyệt

Khoá kỳ là saga do mission-core điều phối với các bước: chuyển kỳ sang `DANG_KHOA` và chờ bộ đệm hết hạn như mô tả ở lớp 5; chạy tính điểm chính thức theo lô; niêm phong sổ cái ngay; chuyển kỳ sang `DA_KHOA`, phát EVT-09 KyDaKhoa. Khi `DA_KHOA`, mọi API ghi trên dữ liệu của kỳ trả lỗi `409` với kiểu vấn đề `period-locked`.

Mở lại kỳ (`MO_LAI_HAU_KIEM`) là thao tác bốn mắt: người đề nghị nêu lý do và phạm vi (toàn kỳ, một đơn vị, hay một hồ sơ), người phê duyệt phải là người khác có vai trò `PERIOD_REOPEN_APPROVER` ở cấp cao hơn, cả hai phải xác thực nâng bậc AAL2, và phê duyệt được ký số. ABAC chặn trường hợp người đề nghị và người duyệt là một, hoặc có quan hệ cấp trên cấp dưới trực tiếp do người đề nghị tự khai báo. Kỳ mở lại có thời hạn (mặc định 10 ngày làm việc) và chỉ mở phạm vi được duyệt; hết hạn tự khoá lại. Mọi thay đổi trong thời gian mở lại được gắn cờ hậu kiểm trên sổ cái và hiển thị riêng trên màn hình giám sát, và là dữ liệu đầu vào trực tiếp cho dấu hiệu bất thường "sửa sau khoá kỳ". Kết quả tính lại sau khi mở lại không ghi đè kết quả cũ mà tạo phiên bản kết quả mới, cả hai đều có snapshot, nên lịch sử thay đổi kết quả của một người là đầy đủ.

# Đa khách thuê

## Ba mô hình và tiêu chí chọn

Nền tảng phục vụ khoảng 5.000 khách thuê (tenant) từ một xã vài chục người tới một bộ hàng chục nghìn người, cộng các khối đặc thù như quân đội, công an. Một mô hình duy nhất không phù hợp cho tất cả, nên ADR-07 chọn ba mô hình theo phân loại phổ biến của kiến trúc SaaS. Mô hình pool cho tenant nhỏ và vừa dùng chung CSDL, chung schema, cách ly bằng cột `tenant_id` và chính sách bảo mật mức dòng (RLS). Mô hình bridge cho tenant lớn (bộ, tỉnh lớn) dùng chung cụm PostgreSQL của cell nhưng có CSDL logic riêng, và có thể có pod riêng cho các service nặng. Mô hình silo cho tenant có yêu cầu cách ly đặc biệt dùng cell riêng (CELL-DTW cho khối Trung ương) hoặc bản cài cách ly.

| Tiêu chí | Pool | Bridge | Silo |
|---|---|---|---|
| Đối tượng | Xã, phường, đơn vị dưới 5.000 tài khoản | Bộ, tỉnh, tập đoàn từ 5.000 đến 150.000 tài khoản | Khối Trung ương, đơn vị có yêu cầu pháp lý riêng, bản cài cách ly |
| Cách ly dữ liệu | RLS trên cột `tenant_id` | CSDL logic riêng trên cụm chung, cộng RLS | Cụm CSDL riêng, cell hoặc hạ tầng riêng |
| Cách ly tính toán | Chung pod, giới hạn tốc độ theo tenant | Có thể có pod riêng cho service nặng | Riêng toàn bộ |
| Khoá mã hoá | KEK riêng trong OpenBao chung | KEK riêng trong OpenBao chung | HSM riêng |
| Chi phí biên mỗi tenant (ước tính) | Rất thấp, vài chục MB dữ liệu | Trung bình | Cao, một cell tối thiểu |
| Tuỳ biến | Qua Policy Pack, UI Pack | Như pool, cộng lịch nâng cấp riêng | Toàn phần, gồm phiên bản phần mềm |

Bảng trên được dùng làm tiêu chí xếp tenant khi tiếp nhận. Một tenant có thể chuyển từ pool lên bridge khi tăng trưởng mà không đổi mã nguồn, vì mọi truy cập dữ liệu đều đi qua cùng một lớp định tuyến kết nối và RLS vẫn bật ở cả bridge.

## Lan truyền ngữ cảnh tenant từ JWT tới RLS

Ngữ cảnh tenant đi qua năm chặng, mỗi chặng có kiểm tra riêng để một lỗi ở chặng này bị chặn ở chặng sau. Chặng một là Keycloak: mỗi người dùng thuộc một tổ chức (Keycloak Organizations), token chứa claim `tenant_id` và `cell` do Keycloak thêm qua bộ ánh xạ, người dùng không thể tự đặt. Chặng hai là APISIX: kiểm chữ ký token, định tuyến tới cell theo claim `cell`, từ chối nếu cell không khớp với cell đang phục vụ. Chặng ba là bộ lọc trong service: `TenantContextFilter` đọc claim từ token đã được Spring Security xác thực (không đọc header do client gửi), đặt vào `TenantContext` gắn với luồng (dùng `ScopedValue` của Java 21 khi chạy luồng ảo).

```java
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class TenantContextFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) { chain.doFilter(req, res); return; }
    String tenant = jwt.getToken().getClaimAsString("tenant_id");
    if (tenant == null) {
      res.sendError(HttpServletResponse.SC_FORBIDDEN, "Thiếu tenant_id trong token");
      return;
    }
    TenantId id = TenantId.parse(tenant);
    try {
      ScopedValue.where(TenantContext.CURRENT, id).run(() -> {
        try { chain.doFilter(req, res); } catch (IOException | ServletException e) { throw new WrappedException(e); }
      });
    } catch (WrappedException e) { e.rethrow(); }
  }
}
```

Chặng bốn là giao dịch CSDL: một `ConnectionPreparer` gắn vào mọi giao dịch, gọi `SELECT set_config('app.tenant_id', ?, true)` ngay sau `BEGIN`. Tham số thứ ba `true` làm giá trị chỉ tồn tại trong giao dịch, điều bắt buộc vì PgBouncer chạy chế độ giao dịch (transaction pooling), một kết nối vật lý được dùng lại cho giao dịch của tenant khác ngay sau đó. Nếu không có tenant trong ngữ cảnh, preparer đặt giá trị rỗng, và RLS trả về không dòng nào thay vì mọi dòng. Chặng năm là RLS trong PostgreSQL.

```sql
ALTER TABLE task.task ENABLE ROW LEVEL SECURITY;
ALTER TABLE task.task FORCE ROW LEVEL SECURITY;      -- áp cả với chủ sở hữu bảng

CREATE POLICY tenant_isolation ON task.task
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Tài khoản ứng dụng không có BYPASSRLS, không sở hữu bảng
CREATE ROLE svc_task LOGIN NOBYPASSRLS;
GRANT USAGE ON SCHEMA task TO svc_task;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA task TO svc_task;
```

Chi phí của RLS là một điều kiện bằng thêm vào mọi truy vấn; vì mọi chỉ mục đều bắt đầu bằng `tenant_id` (quy ước của TL-03), điều kiện này dùng chỉ mục và không làm chậm đáng kể. Hàm `current_setting` được đánh dấu `STABLE` nên chỉ tính một lần mỗi truy vấn. Các job hệ thống chạy cho nhiều tenant (như khoá kỳ toàn cell) lặp qua từng tenant và đặt ngữ cảnh cho từng giao dịch, không dùng tài khoản vượt RLS; tài khoản có `BYPASSRLS` duy nhất là tài khoản sao lưu, không dùng được từ ứng dụng.

Hai lớp kiểm tra tự động bảo vệ thiết kế này. Một kiểm thử ArchUnit cấm gọi `DataSource.getConnection()` ngoài lớp hạ tầng chuẩn. Một bộ kiểm thử tích hợp với Testcontainers chạy mọi repository với hai tenant và khẳng định tenant A không bao giờ đọc được dòng của tenant B, kể cả qua truy vấn native; kiểm thử này nằm trong cổng chất lượng và chặn hợp nhất mã nếu thất bại.

## Định tuyến tới cell

Mặt phẳng toàn cục (global plane) giữ danh bạ `registry.tenant` ánh xạ tenant tới cell. Khi người dùng mở ứng dụng, shell gọi `GET /api/v1/registry/resolve?tenant=` qua điểm vào toàn cục với tenant lấy từ token; điểm vào xác thực token, kiểm tenant khớp claim, tra cell và trả URL của cell, kèm cookie định tuyến ký HMAC có hạn 5 phút để các request sau đi thẳng tới cell mà không tra lại. APISIX ở mỗi cell kiểm cookie và claim `cell` trong token; nếu tenant đã được chuyển sang cell khác, nó trả `421 Misdirected Request` kèm URL mới, shell tự chuyển hướng. Danh bạ được sao chép tới mọi cell với bộ đệm 5 phút, nên sự cố của mặt phẳng toàn cục không làm gián đoạn người dùng đã đăng nhập.

## Tiếp nhận tenant

Tiếp nhận một tenant mới là một tiến trình tự động do tenant-registry (SVC-03) điều phối, mục tiêu dưới 30 phút cho tenant pool. Các bước gồm: tạo bản ghi `registry.tenant` với mô hình và cell đích; tạo tổ chức trong Keycloak với IdP liên kết nếu khách hàng có IdP riêng; tạo KEK `kek-<tenant>` trong OpenBao và khoá HMAC cho chỉ mục mù; với pool, tạo phân vùng danh sách cho các bảng phân vùng theo tenant (ví dụ `ledger.ledger_entry`); với bridge, tạo CSDL logic, chạy Flyway, tạo tài khoản; gán Policy Pack và UI Pack mặc định của khối; nhập cây tổ chức ban đầu từ nguồn chuẩn (CSDL CBCCVC hoặc tệp chuẩn) qua integration-gateway; tạo cấu hình giới hạn tốc độ; chạy kiểm thử khói (đăng nhập bằng tài khoản kiểm thử, đọc cây tổ chức); phát sự kiện `sys.tenant.provisioned.v1`. Mỗi bước idempotent, nên tiến trình có thể chạy lại từ bước thất bại. Đáp ứng tiêu chí K5 (đa khách thuê trong một bản cài) đòi hỏi chính tiến trình này không cần triển khai lại phần mềm.

## Cô lập tài nguyên và giới hạn theo tenant

Rủi ro chính của mô hình pool là một tenant ồn (noisy neighbor) chiếm tài nguyên chung. Thiết kế giới hạn ở bốn chỗ. Ở cổng API, `limit-count` theo khoá `tenant_id` với hạn mức tỉ lệ với số tài khoản (mặc định 2 request mỗi giây mỗi 100 tài khoản, tối thiểu 20, cấu hình trong `registry.tenant_quota`), cộng giới hạn theo người dùng. Ở Kafka, hạn ngạch theo client cho các job nhập lô của tenant. Ở CSDL, `statement_timeout` 5 giây cho truy vấn của ứng dụng, 60 giây cho job báo cáo, và công cụ giám sát `pg_stat_statements` gắn nhãn tenant qua comment SQL để phát hiện tenant gây tải. Ở job nền, hàng đợi công bằng (fair queue): job của từng tenant được lấy luân phiên, một tenant gửi 100.000 job không chặn tenant khác.

| Tài nguyên | Giới hạn mặc định cho pool | Cách thực thi |
|---|---|---|
| Request API | 2 rps mỗi 100 tài khoản, tối thiểu 20 rps, đột biến gấp 3 trong 10 giây | APISIX `limit-count` khoá theo tenant |
| Tải lên minh chứng | 200 MB mỗi người mỗi ngày, 5 GB mỗi tenant mỗi giờ | evidence-service kiểm trước khi cấp URL tải |
| Xuất báo cáo | 5 job đồng thời mỗi tenant | Hàng đợi công bằng của document-service |
| Lưu trữ | Theo hợp đồng, cảnh báo ở 80% | Đo hằng ngày từ Ceph và PostgreSQL |
| Truy vấn phân tích | 10 truy vấn đồng thời mỗi tenant, 30 giây mỗi truy vấn | Cube và ClickHouse `max_concurrent_queries_for_user` |

## Chuyển tenant giữa các cell

Chuyển tenant cần thiết khi một cell vượt ngưỡng 1,5 triệu tài khoản hoặc 75.000 người dùng đồng thời (mục 3.3), hoặc khi tenant lên bridge, silo. Quy trình dùng sao chép logic của PostgreSQL theo bộ lọc dòng, không cần dừng dài. Bước một, tạo publication trên cell nguồn cho các bảng của tenant với bộ lọc `WHERE (tenant_id = '<id>')` (PostgreSQL 15 trở lên hỗ trợ bộ lọc dòng trong publication), và subscription trên cell đích; đồng thời sao chép đối tượng minh chứng bằng đồng bộ bucket của Ceph RGW. Bước hai, khi độ trễ sao chép dưới 1 giây, chuyển tenant sang chế độ chỉ đọc (cờ trong registry, APISIX trả `503` có `Retry-After` cho thao tác ghi), chờ sao chép bắt kịp, so khớp số dòng và tổng băm theo bảng. Bước ba, cập nhật `registry.tenant.cell`, chuyển sổ cái: chuỗi băm ở cell đích tiếp tục từ `entry_hash` cuối của cell nguồn, và một bản ghi chuyển giao được niêm phong ở cả hai cell. Bước bốn, mở ghi trên cell đích. Thời gian chỉ đọc ước tính 5 đến 15 phút cho tenant 100.000 tài khoản, được lên lịch ngoài giờ hành chính. Dữ liệu ở cell nguồn được giữ 30 ngày rồi xoá theo quy trình có biên bản.

# Hiệu năng và khả năng mở rộng

## Ngân sách và chiến lược

Chương 2 xác định tải thiết kế 30.000 request/giây toàn quốc, 6.000 ở cell lớn; NFR-03 yêu cầu p95 đọc 300 ms, ghi 800 ms. Chương 9 cho thấy chi phí của từng request đơn lẻ nhỏ; vấn đề của chương này là giữ chi phí đó khi tải cao, bằng cách giảm việc phải làm (bộ đệm, CDN), phân tán việc (bản sao đọc, CQRS, phân vùng), san phẳng đỉnh (hàng đợi), và định cỡ tài nguyên dùng chung (pool kết nối).

## Bộ đệm phân tán Valkey

Valkey 8 (nhánh mã nguồn mở của Redis sau khi Redis đổi giấy phép năm 2024, giấy phép BSD) chạy chế độ cluster ở mỗi cell, 3 shard chính, mỗi shard một bản sao. Quy ước khoá là `{<tenant>}:<module>:<đối tượng>:<id>:v<phiên bản>`; phần trong ngoặc nhọn là hash tag, đặt mọi khoá của một tenant trên cùng shard để thao tác nhiều khoá được. Bộ đệm dùng theo mẫu cache-aside, với thời hạn tuỳ loại dữ liệu và vô hiệu hoá theo sự kiện.

| Dữ liệu | Khoá ví dụ | Thời hạn | Vô hiệu hoá | Ghi chú |
|---|---|---|---|---|
| Cây tổ chức của tenant | `{t1}:org:tree:v42` | 1 giờ | Khi nhận EVT-14, EVT-15, phiên bản tăng | Phiên bản trong khoá nên không cần xoá |
| Trạng thái kỳ | Bộ đệm cục bộ Caffeine | 30 giây | EVT-09, EVT-22 | Không qua Valkey để đạt dưới 0,1 ms |
| Policy Pack đã biên dịch | Bộ đệm cục bộ | Không hết hạn | Băm nội dung là khoá | Bất biến |
| Kết quả dashboard | `{t1}:analytics:q:<băm truy vấn>` | 60 giây | Không | Chấp nhận trễ 60 giây |
| Phiên SSE | `{t1}:notify:conn:<user>` | 5 phút, làm mới | Khi ngắt | Định tuyến thông báo |
| Đếm giới hạn tốc độ | Do APISIX quản lý | Theo cửa sổ | Không | `allow_degradation` khi Valkey lỗi |

Hai quy tắc an toàn áp dụng cho bộ đệm. Dữ liệu C2 không được vào Valkey dạng rõ (vấn đề S15 của bản đánh giá); nếu cần đệm đối tượng có trường C2, trường đó được giữ ở dạng bản mã. Valkey bật xác thực ACL theo service và TLS, và mỗi service chỉ được truy cập tiền tố của mình. Để tránh dồn tải khi một khoá phổ biến hết hạn (cache stampede), thư viện đệm dùng khoá đơn chuyến (single-flight) trong tiến trình và làm mới sớm ngẫu nhiên trong 10% cuối thời hạn.

## CDN và tài nguyên tĩnh

Giao diện web là tài nguyên tĩnh có băm trong tên tệp, phục vụ qua CDN của Viettel IDC với thời hạn một năm; chỉ `index.html`, `federation.manifest.json` và UI Pack `current.json` có thời hạn 60 giây. Ước tính một lần tải đầu khoảng 1,2 MB nén (shell và remote của trang chủ), các lần sau gần như toàn bộ từ bộ đệm trình duyệt. Với 300.000 người dùng đồng thời và khoảng 20% tải mới mỗi giờ, CDN phục vụ khoảng 72 TB mỗi tháng, phần gốc gần như không chịu tải. Minh chứng không qua CDN vì cần kiểm quyền; tải xuống dùng URL ký có hạn 5 phút trực tiếp từ Ceph RGW.

## Bản sao đọc và CQRS

Mỗi cụm PostgreSQL của cell có một bản chính và hai bản sao đồng bộ (cho chuyển đổi dự phòng, RPO bằng 0 trong trung tâm dữ liệu) cộng hai bản sao bất đồng bộ dùng cho đọc. Các truy vấn danh sách, tra cứu không cần dữ liệu mới nhất từng mili giây (danh sách nhiệm vụ của đơn vị, lịch sử) được chuyển sang bản sao đọc bằng `@Transactional(readOnly = true)` kết hợp `AbstractRoutingDataSource`. Truy vấn ngay sau thao tác ghi của chính người dùng (đọc lại cái mình vừa ghi) phải nhất quán, nên định tuyến dùng cờ "đã ghi trong 2 giây gần đây" trong phiên để đọc bản chính. Độ trễ sao chép được giám sát; nếu vượt 1 giây, bộ định tuyến chuyển mọi đọc về bản chính.

CQRS ở mức hệ thống tách hẳn mô hình đọc cho phân tích: dữ liệu từ các schema nghiệp vụ qua Debezium vào Kafka, rồi vào ClickHouse (SVC-15). Bảng điều hành, báo cáo tổng hợp, phát hiện bất thường đọc ClickHouse, không chạm CSDL nghiệp vụ; đây là thay đổi then chốt so với hiện trạng, nơi báo cáo chạy thẳng trên Oracle nghiệp vụ. Độ trễ từ ghi tới khi thấy trên dashboard ước tính 2 đến 10 giây, được công bố trên giao diện ("số liệu cập nhật đến ...").

## San phẳng đỉnh bằng hàng đợi

Cuối kỳ tạo đỉnh gấp bốn lần trung bình (mục 2.3). Các thao tác không cần kết quả ngay được đưa vào hàng đợi để hệ thống xử lý với tốc độ ổn định (queue-based load leveling): sinh biểu mẫu, ký số, tính điểm chính thức, thông báo hàng loạt, OCR, quét mã độc, lập chỉ mục tìm kiếm. API trả `202 Accepted` kèm URL theo dõi, và giao diện hiển thị tiến trình. Worker co giãn theo độ dài hàng đợi bằng KEDA, có giới hạn tối đa để không quá tải tài nguyên dùng chung phía sau (ví dụ worker ký số tối đa bằng hạn mức đồng thời mà nhà cung cấp ký số cho phép).

## Định cỡ pool kết nối

Pool kết nối là tài nguyên dùng chung dễ thành nút thắt nhất. Định cỡ dựa trên định luật Little: số kết nối cần ≈ thông lượng truy vấn × thời gian giữ kết nối. Ví dụ với mission-core ở cell lớn: khoảng 6.000 request/giây đỉnh, trung bình 1,5 giao dịch mỗi request, thời gian giữ kết nối trung bình 8 ms, cần 6.000 × 1,5 × 0,008 = 72 kết nối đồng thời trên toàn bộ replica; với 20 replica và hệ số an toàn 2 cho dao động, mỗi replica cần khoảng 7 kết nối. Pool HikariCP được cấu hình 20 kết nối mỗi pod (nằm trong khoảng 20 đến 40 mỗi pod của khuyến nghị đánh giá), đủ dư khi phân bố tải không đều.

Phía CSDL, số kết nối vật lý nên gần số nhân CPU nhân hai cộng số ổ đĩa theo kinh nghiệm phổ biến của PostgreSQL; bản chính 32 vCPU nên khoảng 64 đến 80 kết nối hoạt động. PgBouncer chế độ giao dịch đứng giữa, nhận tới vài nghìn kết nối phía client (20 kết nối × 40 pod × 8 service) và ghép vào `default_pool_size = 60` mỗi CSDL và tài khoản, `max_db_connections = 80`. Khi pool phía ứng dụng cạn, request chờ tối đa `connectionTimeout = 2s` rồi lỗi `503` thay vì treo, để cắt tải lan lên cổng. Luồng ảo của Java 21 làm số request đồng thời trong một pod lớn hơn nhiều số kết nối, nên giới hạn đồng thời thực sự được đặt bằng bulkhead semaphore trước lớp truy cập dữ liệu (chương 14), tránh hàng nghìn luồng ảo cùng chờ pool.

## Phân vùng dữ liệu

Bảng lớn được phân vùng để giữ chỉ mục nhỏ và xoá dữ liệu cũ bằng cách gỡ phân vùng. Bảng sự kiện và nhật ký (`outbox_event`, `task.task_progress`, `ledger.ledger_entry`, bảng lịch sử Flowable) phân vùng theo thời gian (tháng) hoặc theo tenant với bridge; bảng nghiệp vụ lớn (`task.task` với khoảng 400 triệu dòng mỗi năm toàn quốc theo ước tính của TL-03) phân vùng theo băm `tenant_id` thành 32 phân vùng trong cụm của cell. `pg_partman` tạo trước phân vùng và gỡ phân vùng hết hạn. Chi tiết từng bảng nằm ở TL-03.

## Tổng hợp định cỡ cell

| Thành phần | Cell lớn (CELL-HCM, 1,2 triệu tài khoản) | Cell vừa (khoảng 600.000 tài khoản) | Căn cứ |
|---|---|---|---|
| Request đỉnh | 6.000 rps | 3.000 rps | Mục 2.3 |
| mission-core | 20 đến 40 pod, 2 đến 4 vCPU, 4 GiB | 10 đến 20 pod | Khoảng 300 rps mỗi pod ước tính |
| workflow-service | 6 đến 12 pod, 2 vCPU, 4 GiB | 4 đến 8 pod | 150 user task/s thiết kế |
| APISIX | 4 đến 6 pod | 3 pod | Khoảng 2.000 rps mỗi pod |
| PostgreSQL nghiệp vụ | 1 chính 32 vCPU, 256 GiB, NVMe, 4 bản sao | 1 chính 16 vCPU, 128 GiB, 4 bản sao | Khoảng 1.200 ghi/s đỉnh |
| Kafka | 6 broker | 3 broker | 1.800 sự kiện/s đỉnh |
| Valkey | 3 shard × 2 | 3 shard × 2 | |
| ClickHouse | 3 shard × 2 bản sao | 2 × 2 | |
| Ceph RGW | Dùng chung cụm lưu trữ của TTDL, khoảng 60 TB thô mỗi năm | Khoảng 30 TB | 192 TB/năm toàn quốc trước mã xoá |

Đây là ước tính thiết kế; TL-04 trình bày định cỡ chi tiết theo node và kết quả thử tải dùng để hiệu chỉnh.

# Độ tin cậy và khả năng chịu lỗi

## Nguyên tắc

Trong hệ thống phân tán, lỗi của phụ thuộc là chuyện thường. Mục tiêu của chương này là một phụ thuộc lỗi chỉ làm hỏng chức năng dùng nó, không làm hỏng cả hệ thống, và lỗi được báo cho người dùng theo cách hiểu được. Bản đánh giá ghi nhận hệ thống hiện tại không có circuit breaker, không có timeout cho lời gọi ra ngoài, retry vô hạn, và `ExecutorService` không giới hạn (F01 đến F05). Thiết kế đưa ra một cấu hình chuẩn áp dụng cho mọi service qua thư viện nền `vt-platform-starter`, để không service nào phải tự chọn.

## Cấu hình Resilience4j chuẩn

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowType: TIME_BASED
        slidingWindowSize: 30            # giây
        minimumNumberOfCalls: 20
        failureRateThreshold: 50
        slowCallDurationThreshold: 2s
        slowCallRateThreshold: 80
        waitDurationInOpenState: 20s
        permittedNumberOfCallsInHalfOpenState: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions: [java.io.IOException, java.util.concurrent.TimeoutException,
                           org.springframework.web.client.HttpServerErrorException]
        ignoreExceptions: [vn.viettel.okrkpi.shared.error.BusinessException]
    instances:
      esign-mysign:   {baseConfig: default, waitDurationInOpenState: 60s}
      vneid:          {baseConfig: default}
      cbccvc:         {baseConfig: default, slowCallDurationThreshold: 5s}
  timelimiter:
    configs:
      default: {timeoutDuration: 3s, cancelRunningFuture: true}
    instances:
      esign-mysign: {timeoutDuration: 10s}
      cbccvc:       {timeoutDuration: 8s}
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 200ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        enableRandomizedWait: true
        randomizedWaitFactor: 0.5
        retryExceptions: [java.io.IOException, java.util.concurrent.TimeoutException]
        ignoreExceptions: [vn.viettel.okrkpi.shared.error.BusinessException]
  bulkhead:
    configs:
      default: {maxConcurrentCalls: 25, maxWaitDuration: 50ms}
    instances:
      esign-mysign: {maxConcurrentCalls: 50}
      docgen:       {maxConcurrentCalls: 8}
```

Thứ tự áp dụng là bulkhead ngoài cùng, rồi time limiter, circuit breaker, retry trong cùng, để retry không vượt timeout tổng và một lời gọi bị từ chối bởi circuit breaker mở không tốn chỗ trong bulkhead quá lâu. Retry chỉ áp dụng cho thao tác idempotent (GET, hoặc lời gọi có `Idempotency-Key`); với ba lần thử, chờ 200 ms, nhân hai, ngẫu nhiên 50%, độ trễ tối đa thêm khoảng 0,9 giây. Ngẫu nhiên hoá (jitter) tránh hiện tượng hàng nghìn client thử lại đồng loạt sau một sự cố ngắn.

## Timeout mặc định

Mọi lời gọi ra ngoài tiến trình đều có timeout; không có giá trị "vô hạn" ở bất kỳ đâu. Nguyên tắc là timeout của tầng ngoài lớn hơn tổng timeout và retry của tầng trong, để tầng trong luôn bỏ cuộc trước.

| Lời gọi | Kết nối | Đọc hoặc tổng | Ghi chú |
|---|---|---|---|
| Trình duyệt tới APISIX | Mặc định trình duyệt | 30 s cho API, tác vụ dài dùng 202 | Shell hiển thị lỗi sau 30 s |
| APISIX tới service | 1 s | 10 s | `timeout` trong ApisixRoute |
| Service tới service (REST nội bộ) | 500 ms | 3 s | TimeLimiter mặc định |
| Service tới PostgreSQL | 2 s (Hikari) | `statement_timeout` 5 s, giao dịch 5 s | Job báo cáo 60 s |
| Service tới Valkey | 200 ms | 300 ms | Lỗi thì bỏ qua bộ đệm |
| Service tới Kafka (producer) | 1 s | `delivery.timeout.ms` 30 s | Chỉ debezium và consumer, ứng dụng ghi outbox |
| Service tới OPA cùng pod | 50 ms | 100 ms | Lỗi thì từ chối |
| Service tới OpenBao | 500 ms | 2 s | DEK trong bộ đệm nên ít gọi |
| Tới hệ thống ngoài (CBCCVC, LGSP, vOffice) | 2 s | 5 đến 10 s | Theo cấu hình từng connector |
| Tới ký số từ xa | 2 s | 10 s mỗi lời gọi; phiên chờ xác nhận tới 120 s qua polling | Không giữ request người dùng |

## Idempotency

Mọi API ghi có tác dụng tạo mới hoặc chuyển trạng thái chấp nhận header `Idempotency-Key` (UUID do client sinh). Service lưu khoá trong bảng `<schema>.idempotency_key` gồm `tenant_id`, `key`, băm nội dung request, mã trạng thái và phản hồi, hạn 24 giờ. Request đến với khoá đã có và cùng băm nội dung thì trả lại phản hồi đã lưu; khác băm thì trả `422` vì cùng khoá cho nội dung khác là lỗi client. Việc chèn khoá nằm trong cùng giao dịch với thao tác nghiệp vụ, nên không có trường hợp đã thực hiện nhưng chưa lưu khoá. Ứng dụng di động bắt buộc gửi khoá cho mọi thao tác đồng bộ ngoại tuyến. Ở phía consumer sự kiện, bảng `inbox_processed` đóng vai trò tương tự (chương 6).

Với ghi đồng thời (tiêu chí F9), mọi bảng có `row_version`; JPA dùng `@Version` cho khoá lạc quan, API nhận `If-Match` với ETag từ `row_version`, và xung đột trả `412 Precondition Failed` với kiểu vấn đề `concurrent-modification`, giao diện đề nghị tải lại và hiển thị khác biệt.

## Giới hạn tốc độ và cắt tải

APISIX áp ba tầng giới hạn: theo người dùng (mặc định 20 request mỗi giây, đột biến 40), theo tenant (mục 12.5), và theo route cho thao tác đắt (xuất báo cáo, tìm kiếm). Vượt giới hạn trả `429` với `Retry-After`. Khi chính cell quá tải, cắt tải (load shedding) ưu tiên giữ chức năng quan trọng: APISIX gắn lớp ưu tiên cho route (lớp 1 là đăng nhập, ký, nộp hồ sơ cuối kỳ; lớp 2 là thao tác nghiệp vụ hằng ngày; lớp 3 là dashboard, tìm kiếm, xuất), và plugin `limit-conn` cấu hình theo lớp với ngưỡng thấp dần, nên khi độ trễ p95 của upstream vượt ngưỡng, lớp 3 bị từ chối trước. Trong service, Spring Boot dùng bộ lọc kiểm tra số request đang xử lý và trả `503` sớm khi vượt ngưỡng, thay vì để hàng đợi luồng dài ra.

```yaml
# Trích ApisixRoute: route xuất báo cáo, lớp ưu tiên 3
plugins:
  - name: limit-conn
    enable: true
    config: {conn: 50, burst: 20, default_conn_delay: 0.1, key_type: var_combination,
             key: "$jwt_tenant", rejected_code: 503, allow_degradation: true}
  - name: limit-req
    enable: true
    config: {rate: 2, burst: 4, key_type: var_combination, key: "$jwt_sub", rejected_code: 429}
```

## Cô lập tài nguyên theo phụ thuộc

Bulkhead tách tài nguyên theo phụ thuộc để một phụ thuộc chậm không chiếm hết luồng hay kết nối. Trong service, mỗi phụ thuộc ngoài có bulkhead semaphore riêng (cấu hình ở trên). Với luồng ảo, giới hạn bằng semaphore quan trọng hơn giới hạn pool luồng, vì luồng ảo gần như không tốn chi phí và không còn giới hạn tự nhiên. `ExecutorService` không giới hạn bị cấm bằng ArchUnit (`Executors.newCachedThreadPool` và `newFixedThreadPool` ngoài lớp nền); tác vụ nền dùng executor có hàng đợi giới hạn và chính sách từ chối rõ ràng. Ở mức hệ thống, bộ kết nối có tải lớn chạy Deployment riêng (chương 5, SVC-13), và workflow-service có hàng đợi job riêng cho từng loại tác vụ bên ngoài.

Giới hạn kích thước (tiêu chí F6) áp ở mọi tầng: request JSON tối đa 1 MB (APISIX `client-control`), danh sách phân trang tối đa 200 dòng mỗi trang với con trỏ (cursor) thay vì offset cho tập lớn, xuất báo cáo tối đa 100.000 dòng mỗi tệp, tải lên theo giới hạn của SVC-08.

## Định dạng lỗi chuẩn RFC 9457

Mọi lỗi API trả theo RFC 9457 (Problem Details for HTTP APIs) với kiểu nội dung `application/problem+json`. Trường `type` là URI ổn định trỏ tới trang mô tả lỗi; `title` bằng tiếng Việt; `detail` không chứa thông tin nội bộ (không dấu vết ngăn xếp, không câu SQL); `traceId` để hỗ trợ tra cứu; `errors` liệt kê lỗi theo trường khi kiểm tra dữ liệu thất bại.

```json
{
  "type": "https://okrkpi.vn/problems/period-locked",
  "title": "Kỳ đánh giá đã khoá",
  "status": 409,
  "detail": "Kỳ 2027-Q1 của đơn vị đã khoá ngày 08/04/2027. Muốn sửa cần đề nghị mở lại kỳ.",
  "instance": "/api/v1/tasks/01JA3B.../progress",
  "code": "TASK-409-PERIOD-LOCKED",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": []
}
```

Spring Boot 3.5 hỗ trợ sẵn `ProblemDetail`; thư viện nền thêm `@ControllerAdvice` chung ánh xạ ngoại lệ nghiệp vụ sang mã, và ánh xạ mọi ngoại lệ không lường trước sang `500` với `detail` chung chung trong khi ghi đầy đủ vào log có `traceId`. Danh mục mã lỗi được quản lý trong kho hợp đồng cùng OpenAPI.

# Quan sát và nhật ký

## Mục tiêu

Bản đánh giá cho trục quan sát và trục log điểm thấp nhất: không có tracing, không có endpoint metrics, log văn bản tự do có chứa JWT, mỗi máy chủ một tệp log (B1 đến B8, G1 đến G7). Với hệ thống phân tán trên hàng trăm pod, không có quan sát thì không vận hành được. Thiết kế chọn OpenTelemetry làm chuẩn thu thập duy nhất cho ba tín hiệu (trace, metric, log), để thay đổi công cụ phía sau không đòi sửa mã ứng dụng.

## OpenTelemetry và lan truyền ngữ cảnh trace

Mỗi service Java chạy với OpenTelemetry Java agent 2.x gắn qua biến môi trường `JAVA_TOOL_OPTIONS`, tự động đo HTTP server và client, JDBC, Kafka, Valkey, gRPC, cộng các span nghiệp vụ tự định nghĩa bằng `@WithSpan` ở use case. Service Go dùng SDK OpenTelemetry Go; Python dùng instrumentation tự động. Dữ liệu gửi qua OTLP tới OpenTelemetry Collector chạy dạng DaemonSet trên mỗi node (thu log, metric node) và dạng Deployment cụm (xử lý, lấy mẫu đuôi, định tuyến). Collector chuyển trace tới Grafana Tempo, metric tới Prometheus (Thanos cho lưu trữ dài hạn và truy vấn liên cụm), log tới Loki cho log vận hành và tới OpenSearch cho log an ninh.

Ngữ cảnh trace lan truyền theo chuẩn W3C Trace Context (header `traceparent`, `tracestate`) qua bốn tầng. Ở trình duyệt, Grafana Faro Web SDK tạo span cho thao tác người dùng và gắn `traceparent` vào mọi request tới API cùng nguồn. APISIX với plugin `opentelemetry` tiếp nhận và tạo span cổng. Service nhận qua agent, và khi phát sự kiện, ghi `traceparent` vào cột `headers` của `outbox_event`; Debezium chuyển header vào bản ghi Kafka; consumer khôi phục ngữ cảnh, nên một trace đi xuyên từ cú nhấp chuột tới consumer bất đồng bộ. Ở CSDL, thư viện nền thêm comment SQL theo chuẩn sqlcommenter (`/*traceparent='00-...',tenant='...'*/`), nên truy vấn chậm trong `pg_stat_statements` và log PostgreSQL liên kết được về trace.

Lấy mẫu dùng hai tầng. Đầu vào lấy mẫu theo tỉ lệ 10% ở trình duyệt và cổng để giới hạn khối lượng; Collector giữ 100% trace có lỗi, trace chậm hơn 1 giây, và trace của thao tác mốc pháp lý (lấy mẫu đuôi, tail sampling). Ước tính khối lượng: 30.000 request/giây, trung bình 12 span mỗi request, 10% lấy mẫu, khoảng 36.000 span mỗi giây, khoảng 1,5 TB mỗi ngày trước nén, lưu 7 ngày trong Tempo trên Ceph.

## Chỉ số và SLI, SLO

Spring Boot Actuator với Micrometer cung cấp `/actuator/prometheus` trên cổng quản trị riêng (không lộ qua cổng API). Mỗi service xuất bốn tín hiệu vàng (Four Golden Signals): độ trễ (histogram `http.server.requests` với các mốc 50, 100, 300, 800, 2.000 ms), lưu lượng, tỉ lệ lỗi, độ bão hoà (pool kết nối, hàng đợi, heap, luồng). Cộng chỉ số nghiệp vụ: số chuyển trạng thái nhiệm vụ, số hồ sơ được tính điểm, độ trễ outbox (thời gian từ `created_at` tới khi phát lên Kafka), độ trễ consumer, số bản ghi sổ cái chưa niêm phong.

| Dịch vụ | SLI | SLO | Cửa sổ | Hành động khi tiêu hết ngân sách lỗi |
|---|---|---|---|---|
| API đọc lõi | Tỉ lệ request đọc thành công trả dưới 300 ms | 99,0% | 28 ngày | Dừng phát hành tính năng, ưu tiên hiệu năng |
| API ghi lõi | Tỉ lệ request ghi thành công trả dưới 800 ms | 99,0% | 28 ngày | Như trên |
| Khả dụng cell | Tỉ lệ request không trả 5xx | 99,9% trong giờ hành chính | 28 ngày | Họp xem xét sự cố |
| Luồng sự kiện | Tỉ lệ sự kiện tới consumer trong 5 giây | 99,5% | 7 ngày | Kiểm tra Debezium, Kafka |
| Sổ cái | Tỉ lệ bản ghi được niêm phong trong 65 phút | 99,9% | 7 ngày | Sự cố mức cao |
| Đăng nhập | Tỉ lệ đăng nhập thành công không do lỗi người dùng | 99,5% | 28 ngày | Kiểm tra Keycloak, VNeID |
| Ký số | Tỉ lệ phiên ký hoàn tất không do người dùng huỷ | 98% | 28 ngày | Kiểm tra nhà cung cấp |

Cảnh báo dựa trên tốc độ tiêu ngân sách lỗi theo nhiều cửa sổ (multi-window burn rate): cảnh báo khẩn khi tiêu 2% ngân sách trong 1 giờ (tốc độ 14,4), cảnh báo thường khi tiêu 5% trong 6 giờ. Cách này giảm cảnh báo giả so với ngưỡng tĩnh. Quy tắc SLO được sinh từ tệp khai báo bằng Sloth và quản lý trong Git cùng cấu hình triển khai. Giám sát truy vấn chậm (B7) dùng `pg_stat_statements` và `auto_explain` với ngưỡng 500 ms, xuất qua `postgres_exporter`; bảng xếp hạng truy vấn tốn tài nguyên nhất theo tenant hiển thị trên Grafana.

## Log có cấu trúc

Mọi service ghi log ra đầu ra chuẩn ở định dạng JSON theo Elastic Common Schema (ECS), không ghi tệp trong container. Với Java, cấu hình Log4j2 dùng `JsonTemplateLayout` với mẫu ECS có sẵn, thêm trường trace, tenant và dịch vụ. Ghi log bất đồng bộ (Async Loggers dựa trên LMAX Disruptor) để không chặn luồng nghiệp vụ; khi hàng đợi đầy, log mức DEBUG và INFO bị bỏ còn WARN và ERROR được giữ, và một bộ đếm số log bị bỏ được xuất thành metric, đáp ứng G4 theo cách đo được.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" shutdownHook="disable">
  <Properties>
    <Property name="service">${env:OTEL_SERVICE_NAME:-unknown}</Property>
  </Properties>
  <Appenders>
    <Console name="STDOUT" target="SYSTEM_OUT">
      <JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json">
        <EventTemplateAdditionalField key="service.name" value="${service}"/>
        <EventTemplateAdditionalField key="trace.id" format="JSON"
            value='{"$resolver": "mdc", "key": "trace_id"}'/>
        <EventTemplateAdditionalField key="span.id" format="JSON"
            value='{"$resolver": "mdc", "key": "span_id"}'/>
        <EventTemplateAdditionalField key="tenant.id" format="JSON"
            value='{"$resolver": "mdc", "key": "tenant_id"}'/>
      </JsonTemplateLayout>
    </Console>
  </Appenders>
  <Loggers>
    <AsyncRoot level="INFO" includeLocation="false">
      <AppenderRef ref="STDOUT"/>
    </AsyncRoot>
    <AsyncLogger name="vn.viettel.okrkpi" level="INFO" additivity="false">
      <AppenderRef ref="STDOUT"/>
    </AsyncLogger>
    <Logger name="org.hibernate.SQL" level="WARN"/>
  </Loggers>
</Configuration>
```

Cấu hình dùng `AsyncRoot` và `AsyncLogger` (chế độ hỗn hợp) nên không cần đặt bộ chọn ngữ cảnh bất đồng bộ toàn cục; hai tham số JVM đi kèm là `-Dlog4j2.asyncQueueFullPolicy=Discard` và `-Dlog4j2.discardThreshold=INFO` để khi hàng đợi đầy thì bỏ log từ mức INFO trở xuống thay vì chặn luồng nghiệp vụ. OpenTelemetry agent tự đưa `trace_id`, `span_id` vào MDC, đáp ứng G3. Fluent Bit trên mỗi node đọc log container, thêm nhãn Kubernetes, gửi tới Collector; Loki lưu 30 ngày cho log vận hành.

## Che dữ liệu nhạy cảm

Ba lớp bảo đảm log không chứa dữ liệu nhạy cảm (G7, vấn đề S08). Lớp một ở mã: kiểu dữ liệu có trường C2, C3 ghi đè `toString()` để che, kiểm bằng ArchUnit và kiểm thử đơn vị; cấm ghi log đối tượng request, header `Authorization`, cookie. Lớp hai ở Log4j2: một `RewritePolicy` che các mẫu nhận diện được (chuỗi JWT `eyJ...`, số định danh 12 chữ số, số điện thoại, email) trước khi ghi. Lớp ba ở Collector: bộ xử lý `redaction` và `transform` của OpenTelemetry Collector áp lại cùng các mẫu cho mọi nguồn, kể cả thành phần bên thứ ba. Mỗi tuần, một job quét mẫu log đã lưu tìm các mẫu nhạy cảm và báo cáo; phát hiện là lỗi cần sửa ở nguồn.

## Tách nhật ký kiểm toán

Nhật ký kiểm toán (audit log) khác log vận hành về mục đích, người đọc, thời hạn và yêu cầu toàn vẹn, nên được tách hoàn toàn (G6). Sự kiện kiểm toán gồm đăng nhập, đăng xuất, thay đổi quyền, truy cập dữ liệu C2, C3, xuất dữ liệu, mọi thao tác quản trị, mọi thao tác ở mốc pháp lý. Chúng được phát như sự kiện nghiệp vụ qua outbox lên topic `sys.audit.v1`, ghi vào OpenSearch chỉ mục kiểm toán (chỉ đọc với mọi vai trò trừ consumer ghi), lưu 5 năm (chuyển sang lưu trữ lạnh trên Ceph sau 1 năm), và sự kiện quan trọng được nối vào sổ cái để chống sửa. Người đọc là cán bộ an ninh và thanh tra qua màn hình riêng, có ghi lại chính việc truy cập nhật ký. Keycloak gửi sự kiện xác thực qua SPI lắng nghe sự kiện tới topic `iam.auth.event.v1`, cùng được đưa vào kiểm toán.

## Giám sát phía người dùng

Grafana Faro Web SDK trong shell thu Core Web Vitals (LCP, INP, CLS), lỗi JavaScript, thời gian tải từng remote Native Federation, và tạo span cho thao tác người dùng như đã nói. Dữ liệu gửi tới Faro Collector (thành phần Grafana Alloy) qua đường dẫn cùng nguồn để không bị chặn bởi CSP, lấy mẫu 10% phiên, không ghi nội dung trường nhập. Bảng theo dõi phía người dùng phân theo tenant, trình duyệt, loại mạng, đáp ứng B8, và là nguồn chính để kiểm ngân sách hiệu năng của chương 7. Ứng dụng di động dùng OpenTelemetry cho Flutter gửi cùng định dạng.

# Bảo mật

## Mô hình không tin cậy mặc định

Kiến trúc bảo mật theo mô hình không tin cậy mặc định (zero trust): không request nào được tin chỉ vì đến từ mạng nội bộ. Mọi request người dùng mang JWT được kiểm ở cổng và kiểm lại ở service; mọi kết nối giữa service được mã hoá và xác thực bằng mTLS với danh tính SPIFFE do Istio ambient cấp (ztunnel), cộng chính sách ủy quyền L4 chỉ cho phép các cặp service được khai báo; mọi quyết định truy cập dữ liệu qua chính sách tập trung. Phần này mô tả cách kiến trúc đáp ứng trục D; chi tiết cấu hình hạ tầng an ninh, mã kiểm soát SEC, quy trình kiểm thử xâm nhập nằm ở TL-04.

## Định danh và xác thực

Keycloak 26 (SVC-02) là nhà cung cấp định danh duy nhất. Tính năng Organizations của Keycloak 26 biểu diễn tenant trong một realm chung cho mô hình pool, cho phép mỗi tổ chức có IdP liên kết riêng (ví dụ IdP của một bộ đã có hệ thống định danh), miền email riêng, và thành viên riêng; tenant silo có realm riêng. Mật khẩu (khi còn dùng) băm bằng Argon2id, chính sách khoá tạm sau 5 lần sai với thời gian chờ tăng dần, kiểm danh sách mật khẩu đã lộ. Phiên dùng access token 15 phút ký ES256, refresh token xoay vòng một lần dùng có hạn 8 giờ trong giờ làm việc, phiên SSO tối đa 10 giờ, phát hiện dùng lại refresh token thì thu hồi cả chuỗi (D5).

Xác thực đa yếu tố mức AAL2 bắt buộc với vai trò quản trị, lãnh đạo, người phê duyệt, cán bộ tổ chức, và tuỳ chọn có khuyến khích với người dùng thường; phương thức gồm OTP ứng dụng (TOTP), WebAuthn (passkey, khoá bảo mật), và đăng nhập VNeID mức 2 được coi là đạt AAL2. Thao tác nhạy cảm (ký, quyết định xếp loại, mở lại kỳ, thay đổi quyền, xuất dữ liệu C2) yêu cầu nâng bậc: service kiểm claim `acr` bằng `aal2` và `auth_time` không quá 5 phút, nếu không trả `401` với header `WWW-Authenticate` chứa `insufficient_user_authentication` theo RFC 9470, và shell chuyển người dùng sang luồng xác thực lại.

## Đăng nhập VNeID

Liên kết VNeID dùng OpenID Connect luồng mã uỷ quyền với PKCE, qua Keycloak làm IdP môi giới (identity brokering), để ứng dụng không xử lý trực tiếp giao thức của VNeID. Bản đánh giá ghi nhận tích hợp hiện tại thiếu `state`, `nonce` (rủi ro CSRF đăng nhập và phát lại token). Thiết kế yêu cầu Keycloak sinh `state` ngẫu nhiên 128 bit gắn với phiên trình duyệt, `nonce` gắn vào yêu cầu và kiểm trong ID token, `code_verifier` 256 bit với `code_challenge_method=S256`, kiểm `iss`, `aud`, `exp`, `iat` và chữ ký ID token bằng JWKS của VNeID với bộ đệm khoá có xoay vòng. Nếu VNeID hỗ trợ PAR (RFC 9126) thì dùng PAR để tham số không lộ trên URL. Liên kết tài khoản VNeID với tài khoản công vụ dựa trên số định danh cá nhân khớp với hồ sơ từ CSDL CBCCVC, so khớp qua chỉ mục mù vì số định danh là C2; lần liên kết đầu cần người dùng xác nhận khi đang đăng nhập bằng phương thức khác.

## Uỷ quyền tập trung bằng ABAC

Bản đánh giá ghi nhận kiểm tra quyền phân tán ở hàng trăm chỗ trong mã (S14, D6). Thiết kế tách quyết định quyền ra Open Policy Agent (OPA) với chính sách viết bằng Rego, quản lý trong Git, kiểm thử bằng `opa test`, phân phối dưới dạng bundle ký tới container OPA cùng pod với service. Service gọi OPA qua localhost với đầu vào gồm chủ thể (từ JWT: người dùng, tenant, vai trò, đơn vị, mức xác thực), hành động, tài nguyên (loại, đơn vị chủ quản, người được giao, kỳ, lớp dữ liệu). Thông tin cây tổ chức (ai quản lý đơn vị nào) được đẩy vào OPA dưới dạng dữ liệu bundle theo tenant, cập nhật khi có EVT-14 DonViDaThayDoi hoặc EVT-15 PhanCongDaThayDoi.

```rego
package okrkpi.task

import rego.v1

default allow := false

# Người được giao xem và cập nhật tiến độ nhiệm vụ của mình
allow if {
  input.action in {"task:read", "task:update_progress"}
  input.resource.tenant_id == input.subject.tenant_id
  input.subject.user_id in input.resource.assignee_ids
}

# Lãnh đạo xem nhiệm vụ của đơn vị mình và đơn vị cấp dưới tại thời điểm hiện hành
allow if {
  input.action == "task:read"
  input.resource.tenant_id == input.subject.tenant_id
  "LANH_DAO" in input.subject.roles
  some unit in data.org[input.subject.tenant_id].managed_units[input.subject.user_id]
  input.resource.owner_unit_id in data.org[input.subject.tenant_id].subtree[unit]
}

# Dữ liệu hạn chế C3 chỉ cho người được chỉ định, bất kể vai trò
deny contains "c3_not_recipient" if {
  input.resource.classification == "C3"
  not input.subject.user_id in input.resource.recipient_ids
}

# Nghiệm thu cần nâng bậc xác thực
deny contains "step_up_required" if {
  input.action == "task:accept"
  not input.subject.acr == "aal2"
}

decision := {"allow": allow, "deny": deny}
```

Service cho phép khi `allow` đúng và `deny` rỗng. Với danh sách, kiểm từng dòng là quá đắt, nên thiết kế dùng đánh giá từng phần (partial evaluation) của OPA: service gửi truy vấn với tài nguyên chưa biết, OPA trả điều kiện còn lại (ví dụ `owner_unit_id IN (...)` hoặc `assignee_ids @> ...`), thư viện nền dịch thành mệnh đề SQL bổ sung. Kết quả đánh giá từng phần được đệm theo (người dùng, hành động, phiên bản bundle) trong 60 giây. Chi phí ước tính 0,3 đến 1 ms mỗi quyết định, đã tính trong ngân sách độ trễ chương 9. Lựa chọn đặt OPA cùng pod thay vì một dịch vụ quyết định tập trung tránh một vòng mạng và một điểm lỗi chung; OPA lỗi thì service từ chối (fail closed).

## Bảo vệ API và ứng dụng web

Bảo vệ ở cổng gồm WAF Coraza với bộ quy tắc OWASP CRS 4 ở mức paranoia 2, giới hạn kích thước request, giới hạn tốc độ, kiểm lược đồ OpenAPI cho route công khai bên thứ ba. Bảo vệ trong service gồm kiểm tra đầu vào bằng Jakarta Validation sinh từ OpenAPI, truy vấn chỉ qua tham số ràng buộc (JPA, `JdbcClient` với tham số), sắp xếp động chỉ qua danh sách trắng tên cột được ánh xạ từ tham số API (sửa tận gốc S05), mã hoá đầu ra mặc định của Angular, cấm `bypassSecurityTrust*` trừ trong thư viện đã duyệt. CORS chỉ cho phép danh sách nguồn cụ thể của tenant (cổng văn phòng số nhúng ứng dụng), không bao giờ `*` cùng thông tin xác thực. Vì token nằm trong bộ nhớ của ứng dụng (không trong cookie) và gửi qua header `Authorization`, CSRF không áp dụng cho API; riêng các endpoint dùng cookie (cookie định tuyến cell, luồng đăng nhập Keycloak) dùng `SameSite=Strict` hoặc `Lax` và token chống CSRF của Keycloak (D7).

Header bảo mật HTTP (D11) do APISIX thêm cho mọi phản hồi: `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `Content-Security-Policy` như chương 7 với `frame-ancestors` theo danh sách cổng được phép nhúng của tenant, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` tắt camera, micro, định vị trừ khi cần, `Cross-Origin-Opener-Policy: same-origin`.

## Khắc phục nhóm lỗ hổng nghiêm trọng S01 đến S05 ở mức kiến trúc

Năm lỗ hổng nghiêm trọng của bản đánh giá được vá khẩn ở đợt 0a; kiến trúc mới bảo đảm chúng không thể tái xuất hiện, bằng cơ chế chứ không bằng kỷ luật lập trình.

| Mã | Lỗ hổng | Vá khẩn đợt 0a | Biện pháp kiến trúc chống tái diễn |
|---|---|---|---|
| S01 | Thông tin xác thực, khoá trong mã nguồn (CWE-798, CWE-321) | Thu hồi, xoay vòng mọi bí mật đã lộ, chuyển sang biến môi trường | Mọi bí mật trong OpenBao, nạp qua External Secrets Operator; tài khoản CSDL động có hạn do OpenBao cấp; quét bí mật (gitleaks) chặn ở pre-commit và CI; image không chứa bí mật, kiểm bằng Trivy |
| S02 | 9 tài khoản kiểm thử lấy token không cần mật khẩu (CWE-912) | Xoá mã, thu hồi tài khoản | Ứng dụng không tự cấp token; chỉ Keycloak cấp, ứng dụng chỉ là resource server; không có cấu hình hay hồ sơ build cho phép bỏ qua; kiểm thử DAST trong CI thử đăng nhập không mật khẩu |
| S03 | Đường dẫn chứa "/public" bỏ qua JWT (CWE-288) | Sửa bộ lọc so khớp | Mặc định từ chối: Spring Security cấu hình `anyRequest().authenticated()`, danh sách công khai khai báo tường minh theo đường dẫn chính xác trong một chỗ duy nhất; APISIX cũng xác thực ở cổng; kiểm thử hợp đồng duyệt mọi route trong OpenAPI và khẳng định route không đánh dấu công khai trả 401 khi không token |
| S04 | Mật khẩu dạng rõ trong cookie (CWE-312, CWE-539) | Xoá cookie, bắt đổi mật khẩu | Ứng dụng không bao giờ nhận mật khẩu: đăng nhập chỉ diễn ra tại Keycloak; cookie phiên Keycloak `HttpOnly`, `Secure`, `SameSite`; ArchUnit cấm lớp ứng dụng tham chiếu `Cookie` ngoài thư viện nền |
| S05 | SQL injection qua ORDER BY ghép chuỗi ở hơn 150 chỗ (CWE-89) | Danh sách trắng cho các chỗ đã biết | Lớp truy cập dữ liệu chỉ nhận `Sort` đã kiểm từ ánh xạ API sang cột; ArchUnit và Semgrep cấm ghép chuỗi vào `createQuery`, `createNativeQuery`, `JdbcClient.sql`; SAST chặn ở cổng chất lượng; tài khoản CSDL tối thiểu quyền và RLS giới hạn thiệt hại nếu còn sót |

Các lỗ hổng khác: S06 (MFA, khoá tài khoản, băm không salt) giải quyết bằng Keycloak như đã nêu; S07 (CORS, CSRF), S11 (JWT trong URL), S13 (XSS), S16 (header) bằng biện pháp ở mục bảo vệ API; S08 (log chứa JWT) bằng ba lớp che log; S09 (TLS chấp nhận mọi chứng thư, DES) bằng việc cấm `TrustAllStrategy` qua ArchUnit, dùng kho tin cậy chuẩn, chỉ AES-GCM và TLS 1.2 trở lên; S10 (thư viện có lỗ hổng) bằng SCA và SBOM trong CI; S12 (phiên dài) bằng cấu hình phiên Keycloak; S14 (quyền phân tán) bằng OPA; S15 (PII trong cache) bằng quy tắc bộ đệm của chương 13.

# Trí tuệ nhân tạo

## Nguyên tắc

AI trong nền tảng là trợ lý, không phải người quyết định. Ba nguyên tắc cứng được thể hiện bằng kiến trúc: AI chỉ đọc, không có quyền ghi vào dữ liệu nghiệp vụ, mọi đề xuất phải được người dùng xác nhận và ghi qua API nghiệp vụ thông thường dưới danh tính của người dùng; AI chạy trên mô hình tự lưu trữ trong hạ tầng của Viettel, dữ liệu không ra ngoài; AI không xử lý dữ liệu C3 và chỉ xử lý C2 khi chức năng cần và người dùng có quyền.

## Kiến trúc cổng mô hình

ai-gateway (SVC-14) là điểm vào duy nhất tới mô hình. Mỗi yêu cầu đi qua năm bước: xác thực JWT và kiểm quyền qua OPA như mọi service; lắp ngữ cảnh (truy xuất tài liệu liên quan qua RAG, lấy dữ liệu nghiệp vụ qua API của lõi dưới danh tính người dùng, nên AI chỉ thấy những gì người dùng thấy); kiểm tra đầu vào (guardrail) phát hiện tiêm lệnh (prompt injection) trong nội dung tài liệu, che dữ liệu C2 không cần thiết; gọi vLLM; kiểm tra đầu ra (định dạng JSON theo lược đồ, không chứa dữ liệu ngoài phạm vi, không chứa nội dung không phù hợp) rồi trả về kèm trích dẫn nguồn. Mọi yêu cầu và đề xuất được ghi `ai.suggestion_log` cùng việc người dùng chấp nhận hay sửa, để đo chất lượng.

Tìm kiếm ngữ nghĩa (RAG) dùng pgvector trong schema `ai` với chỉ mục HNSW trên vector nhúng 1.024 chiều từ mô hình nhúng tiếng Việt tự lưu trữ. Kho tài liệu gồm văn bản quy định, hướng dẫn, danh mục công việc mẫu, Policy Pack (phần mô tả), và văn bản chỉ đạo của tenant (lọc theo `tenant_id` và quyền). Văn bản được chia đoạn 500 đến 800 từ có chồng lấp, giữ cấu trúc điều, khoản. Với khoảng vài triệu đoạn mỗi cell, pgvector đủ đáp ứng; nếu vượt khoảng 50 triệu vector hoặc cần độ trễ dưới 20 ms ở tải cao, ADR-28 cho phép chuyển sang OpenSearch k-NN.

OCR minh chứng dùng dịch vụ OCR của Viettel AI, chạy bất đồng bộ khi nhận `sys.evidence.object.stored.v1`, kết quả văn bản lưu trong schema `evidence` và được lập chỉ mục tìm kiếm, giúp phát hiện minh chứng trùng nội dung.

## Bốn tầng ứng dụng AI

Báo cáo Ban Giám đốc xác định bốn tầng AI với mức độ rủi ro tăng dần, và kiến trúc gắn mỗi tầng với điều kiện triển khai.

| Tầng | Chức năng | Dữ liệu dùng | Điều kiện triển khai |
|---|---|---|---|
| 1. Trợ lý | Gợi ý OKR, soát KR mơ hồ, giải thích điểm số, hỏi đáp quy định | Tài liệu quy định, dữ liệu của chính người dùng | Từ đợt 3, sau khi có Policy Pack và RAG |
| 2. Tự động hoá | Trích nhiệm vụ từ văn bản chỉ đạo, OCR minh chứng, tóm tắt tuần cho lãnh đạo, gợi ý mức chất lượng | Văn bản đến, minh chứng, tiến độ | Đề xuất luôn qua xác nhận của người; đo tỉ lệ chấp nhận trên 70% trước khi mở rộng |
| 3. Giám sát | Cảnh báo nguy cơ trễ hạn, phát hiện nhiệm vụ không có minh chứng | Dữ liệu ClickHouse | Quy tắc thống kê, có giải thích; không dùng LLM cho quyết định |
| 4. Phát hiện bất thường | Tám dấu hiệu bất thường | Dữ liệu ClickHouse ít nhất hai quý | Chỉ sau hai quý dữ liệu sạch; kết quả là gợi ý xem xét cho cán bộ giám sát, không tự động ảnh hưởng điểm |

## Tám dấu hiệu bất thường

Job phát hiện chạy trên ClickHouse sau mỗi lần nạp lô và trước khi khoá kỳ, phát EVT-20 DauHieuBatThuong cho oversight-service. Mỗi dấu hiệu có định nghĩa định lượng, ngưỡng cấu hình theo khối, và giải thích đi kèm để cán bộ giám sát hiểu vì sao bị đánh dấu.

| Dấu hiệu | Cách phát hiện | Ngưỡng ví dụ |
|---|---|---|
| Tự chấm cao hơn thẩm định | Chênh lệch tự đánh giá và thẩm định, lặp qua nhiều kỳ | Chênh trên 15 điểm ở 2 kỳ liên tiếp |
| Cào bằng | Phương sai điểm trong đơn vị thấp bất thường so với phân bố khối | Độ lệch chuẩn dưới 2 điểm với đơn vị trên 10 người |
| Mục tiêu quá dễ | Tỉ lệ KR đạt trên 100% sớm, so với lịch sử và đơn vị cùng khối | Trên 80% KR đạt trước giữa kỳ |
| Minh chứng trùng hoặc dùng lại | Trùng băm SHA-256, hoặc văn bản OCR gần giống (MinHash) giữa người, giữa kỳ | Trùng băm, hoặc độ tương đồng Jaccard trên 0,9 |
| Cập nhật dồn cuối kỳ | Tỉ lệ cập nhật tiến độ trong 3 ngày cuối kỳ | Trên 60% số cập nhật |
| Sửa sau khoá kỳ | Thay đổi gắn cờ hậu kiểm trên sổ cái | Mọi trường hợp, xếp hạng theo mức ảnh hưởng điểm |
| Chẻ nhỏ việc | Số nhiệm vụ tăng đột biến với khối lượng mỗi nhiệm vụ giảm | Số nhiệm vụ gấp 3 trung bình 4 kỳ |
| Miễn trừ khách quan lặp lại | Tần suất miễn trừ của người, đơn vị so với khối | Trên 3 lần trong 4 kỳ |

# Kiểm thử và tích hợp, triển khai liên tục

## Tháp kiểm thử

Bản đánh giá ghi nhận mật độ kiểm thử đơn vị gần như bằng không và CI không chặn khi kiểm thử lỗi (J1 đến J3). Thiết kế đặt tháp kiểm thử với tỉ lệ và mục tiêu cụ thể. Đáy tháp là kiểm thử đơn vị trên lớp `domain` và `application`, chạy trong vài giây, mục tiêu độ phủ dòng 80% và độ phủ nhánh 70% với mã mới (đo bằng JaCoCo, chặn ở SonarQube), và kiểm thử đột biến bằng PIT cho máy trạng thái và tính điểm với ngưỡng 70%. Tầng giữa là kiểm thử tích hợp với Testcontainers (PostgreSQL 17, Kafka, Valkey, Keycloak, OpenBao), kiểm repository, RLS giữa hai tenant, outbox tới Kafka, consumer idempotent; và kiểm thử module của Spring Modulith (`@ApplicationModuleTest`) kiểm từng module độc lập với sự kiện giả lập. Tầng trên là kiểm thử hợp đồng bằng Pact giữa frontend và API, giữa service và connector, và kiểm thử đầu cuối bằng Playwright cho khoảng 40 hành trình chính (giao việc tới nghiệm thu, tự đánh giá tới quyết định ký số), chạy trên môi trường tạm theo nhánh.

Bên cạnh tháp có bốn loại kiểm thử đặc thù. Bộ ca mẫu vàng của Policy Pack do nghiệp vụ ký (J6, chương 10). Kiểm thử thuộc tính bằng jqwik cho tính điểm và máy trạng thái (mọi chuỗi chuyển trạng thái ngẫu nhiên không bao giờ đưa nhiệm vụ vào trạng thái không hợp lệ). Kiểm thử kiến trúc bằng ArchUnit và Spring Modulith `verify()` (J9: tài liệu kiến trúc khớp mã, vì quy tắc phụ thuộc trong tài liệu này là chính các kiểm thử đó; Spring Modulith còn sinh sơ đồ module từ mã để đối chiếu với Hình 5.1). Thử tải định kỳ bằng k6 (J7) với kịch bản cuối kỳ chạy hằng tuần trên môi trường tiền vận hành và trước mỗi bản phát hành chính.

## Đường ống CI/CD

![Hình 18.1. Đường ống tích hợp và triển khai liên tục](kt-cicd.png){width=14cm}

Hình trên mô tả đường ống gồm ba giai đoạn. Giai đoạn tích hợp liên tục chạy trên Jenkins (công cụ hiện có của đội, chạy agent trong Kubernetes) với Jenkinsfile dùng thư viện dùng chung: biên dịch, kiểm thử đơn vị, kiểm thử tích hợp, kiểm thử kiến trúc, phân tích tĩnh SonarQube và Semgrep, quét bí mật gitleaks, phân tích thành phần phần mềm (SCA) bằng OWASP Dependency-Check hoặc Trivy, kiểm tương thích OpenAPI bằng `openapi-diff` và kiểm hợp đồng Pact qua Pact Broker (`can-i-deploy`). Cổng chất lượng chặn hợp nhất khi một trong các điều kiện sau không đạt: mọi kiểm thử qua, độ phủ mã mới đạt ngưỡng, không lỗi SonarQube mức Blocker hay Critical mới, không lỗ hổng CVSS từ 7,0 trong thư viện mà chưa có ngoại lệ được duyệt, không bí mật, không thay đổi phá vỡ hợp đồng API.

Giai đoạn chuỗi cung ứng tạo image bằng Jib (Java) hoặc Dockerfile nhiều tầng (Go, Python) trên ảnh nền tối giản (distroless hoặc Chainguard, chạy không root), sinh SBOM định dạng CycloneDX, quét image bằng Trivy, ký image và gắn chứng thực nguồn gốc (provenance) theo SLSA mức 3 bằng Cosign với khoá trong OpenBao, đẩy lên Harbor. Mỗi image chỉ build một lần và dùng cho mọi môi trường (K6).

Giai đoạn triển khai liên tục theo GitOps: Jenkins cập nhật phiên bản image trong kho cấu hình (Helm values theo môi trường và cell), Argo CD đồng bộ từ Git vào cụm. Kyverno ở cụm chỉ cho chạy image có chữ ký hợp lệ và chứng thực nguồn gốc từ đường ống chính thức, có SBOM, không chạy root, có giới hạn tài nguyên và probe. Triển khai lên vận hành theo kiểu canary bằng Argo Rollouts: 5% lưu lượng trong 15 phút, phân tích tự động SLI (tỉ lệ lỗi, độ trễ p95) so với bản cũ, rồi 25%, 50%, 100%; SLI xấu thì tự quay lui. Triển khai cell theo vòng: cell thử nghiệm nội bộ, cell nhỏ, rồi các cell lớn, cách nhau ít nhất một ngày làm việc, để một lỗi chỉ ảnh hưởng một phần người dùng. Thay đổi lược đồ CSDL theo mẫu mở rộng rồi thu hẹp (expand and contract) để bản cũ và bản mới chạy song song trong lúc canary.

# Lộ trình chuyển đổi theo Strangler Fig

> Cập nhật 23/10/2026: ADR-20 không áp dụng, vì Mission được phát triển như một ứng dụng mới, không chuyển dần từ hệ hiện tại (quyết định của người phụ trách, xem Phụ lục D mục D.6). Nội dung dưới đây chỉ còn giá trị tham khảo.

## Cách tiếp cận

Hệ thống hiện tại đang phục vụ người dùng thật và không thể dừng để viết lại. Lộ trình vì vậy theo mẫu Strangler Fig (đã mô tả kỹ thuật ở chương 6, mục chuyển dần với APISIX, Hình 6.3): đặt APISIX trước hệ thống cũ, xây từng phần mới phía sau cổng, chuyển lưu lượng theo route và theo tenant, và chỉ tắt phần cũ khi phần mới đã chạy ổn định. Lộ trình gắn với các đợt của bản đánh giá kiến trúc (0a, 0b, 0c, 1, 2, 3) để thống nhất cách đo tiến độ: mỗi đợt có mục tiêu kỹ thuật, các vấn đề của bản đánh giá được đóng, và điều kiện chuyển bước đo được. Không chuyển sang đợt sau khi điều kiện của đợt trước chưa đạt, trừ khi Hội đồng kiến trúc chấp thuận ngoại lệ bằng văn bản.

Đợt 0a, 0b, 0c tác động lên hệ thống hiện tại để giảm rủi ro ngay, vì nền tảng mới cần nhiều quý mới thay thế được. Từ đợt 1, các thành phần của kiến trúc đích trong tài liệu này bắt đầu xuất hiện và dần thay thế.

## Các đợt

### Đợt 0a: vá khẩn an ninh (2 tuần)

Mục tiêu là đóng các lỗ hổng có thể bị khai thác ngay: S01 đến S05, S07, S08, S11. Công việc gồm thu hồi và xoay vòng mọi bí mật trong mã, đưa bí mật ra biến môi trường tạm thời (chuyển OpenBao ở đợt 0c), xoá chức năng đăng nhập không mật khẩu của 9 tài khoản kiểm thử, sửa bộ lọc "/public", xoá cookie chứa mật khẩu và bắt đổi mật khẩu, áp danh sách trắng cho mọi chỗ ghép ORDER BY, sửa CORS, che JWT trong log, chuyển JWT khỏi URL. Điều kiện chuyển bước là quét bí mật trên toàn bộ kho mã cho kết quả không, và kiểm thử xâm nhập lại các lỗ hổng S01 đến S05 không khai thác được.

### Đợt 0b: nền vận hành trên Kubernetes (6 tuần)

Mục tiêu là hệ thống hiện tại chạy ổn định và quan sát được trên Kubernetes. Công việc gồm manifest riêng cho từng service với probe liveness, readiness, startup, PodDisruptionBudget, ràng buộc phân bố theo vùng (topology spread); heap JVM 70 đến 75% giới hạn bộ nhớ; pool kết nối 20 đến 40 mỗi pod; OpenTelemetry agent cho mọi service Java; endpoint Prometheus; log JSON ra đầu ra chuẩn. Đóng các vấn đề C01, C02, C03, F01, B01, B02, G01, G02. Điều kiện chuyển bước là một trace đi qua đủ bốn tầng (trình duyệt, cổng, service, CSDL) xem được trên Tempo, và rút một node khỏi cụm trong giờ làm việc không gây gián đoạn quan sát được với người dùng.

### Đợt 0c: chịu lỗi và chuỗi cung ứng (6 tuần)

Mục tiêu là hệ thống hiện tại không sụp dây chuyền và mọi thay đổi đi qua cổng chất lượng. Công việc gồm Resilience4j với cấu hình chuẩn của chương 14 cho mọi lời gọi ra ngoài, timeout ở mọi tầng, executor có giới hạn, cổng chất lượng trên Jenkins (kiểm thử, SonarQube, SCA), SBOM, ký image, Flyway cho thay đổi CSDL, đưa APISIX vào trước hệ thống cũ làm điểm Strangler. Đóng F02, F03, F04, C04, J02, K03, A01 (một phần). Điều kiện chuyển bước là thử tải đạt p95 dưới 3 giây ở 500 người dùng đồng thời trên hệ thống cũ, và đường ống chặn được một bản build có kiểm thử lỗi hoặc có lỗ hổng mức cao.

### Đợt 1: định danh, tổ chức và cổng tích hợp (quý 4/2026 đến quý 1/2027)

Đây là đợt đầu tiên của kiến trúc đích. Công việc gồm Keycloak (SVC-02) với MFA, liên kết VNeID có `state` và `nonce`, tenant-registry (SVC-03), org-service (SVC-04) với dữ liệu hai trục thời gian, OPA cho uỷ quyền, integration-gateway (SVC-13) với lớp chống ô nhiễm cho vOffice và nhận thông tin tổ chức. Hệ thống cũ chuyển sang đăng nhập qua Keycloak và đọc tổ chức qua API của org-service, ngừng đọc bảng `VHR_*`. Đóng S06, S12, S14, K01, K02, L01, L03. Điều kiện chuyển bước là không còn truy vấn mới nào vào bảng `VHR_*` (đo bằng giám sát truy vấn trên Oracle trong 2 tuần liên tiếp), 100% người dùng đăng nhập qua Keycloak, MFA bật cho mọi vai trò quản trị và lãnh đạo.

### Đợt 2: lõi nghiệp vụ mới (quý 1 đến quý 2/2027)

Đây là đợt lớn nhất. Công việc gồm mission-core trên PostgreSQL với các module OKR, nhiệm vụ, sản phẩm, đánh giá, policy, hồ sơ; Policy Pack với bộ ca mẫu vàng; ledger-service với chuỗi băm và dấu thời gian RFC 3161; evidence-service với Ceph Object Lock; esign-service; document-service; outbox và Kafka; analytics với ClickHouse (CQRS); kết nối Trục liên thông văn bản quốc gia, LGSP, NDXP, CSDL CBCCVC. Chuyển tenant theo nhóm, mỗi nhóm bắt đầu từ đầu một kỳ đánh giá để không có kỳ nào tính trên hai hệ thống. Dữ liệu lịch sử của kỳ cũ được chuyển sang ở dạng chỉ đọc, kèm niêm phong sổ cái cho bản chuyển đổi. Đóng I01 đến I04, H01 đến H04, A03, E02, E03, L02, L04. Điều kiện chuyển bước là áp dụng một thay đổi quy định mới trong không quá 5 ngày làm việc từ khi có văn bản, bộ ca mẫu vàng đạt 100%, công cụ kiểm chứng sổ cái độc lập báo hợp lệ trên một kỳ hoàn chỉnh, và tenant thí điểm hoàn thành một kỳ đánh giá đầy đủ trên hệ thống mới.

### Đợt 3: quy mô quốc gia (từ quý 3/2027)

Công việc gồm chia cell theo mô hình của chương 4, KEDA cho co giãn theo sự kiện, Helm và GitOps với Argo CD, dự phòng thảm hoạ hai trung tâm dữ liệu, bộ kết nối cho văn phòng số của hãng khác, CSDL đảng viên, ứng dụng di động, AI tầng 1 và 2, thay thế toàn bộ giao diện ZK bằng Angular. Đóng C02 (toàn phần), C05, E01, A06, H05, L05. Điều kiện hoàn thành là thử tải đạt SLO ở 70.000 phiên đồng thời trên một cell lớn (mức kiểm chứng của bản đánh giá), diễn tập thảm hoạ đạt RPO 15 phút và RTO 4 giờ, và hệ thống cũ được tắt.

## Chỉ số theo dõi chuyển đổi

| Chỉ số | Hiện trạng | Sau đợt 0c | Sau đợt 2 | Sau đợt 3 |
|---|---|---|---|---|
| Điểm đánh giá kiến trúc theo thang của bản đánh giá | 17,4 | Từ 40 | Từ 60 | Từ 75 |
| Lỗ hổng nghiêm trọng và cao còn mở | 16 | 0 nghiêm trọng | 0 | 0 |
| p95 thao tác ghi khi tải cao | Trên 10 s | Dưới 3 s ở 500 CCU | Dưới 800 ms ở tải cell thí điểm | Dưới 800 ms ở 70.000 phiên |
| Thời gian áp dụng thay đổi quy định | Nhiều tuần | Nhiều tuần | Không quá 5 ngày làm việc | Không quá 5 ngày làm việc |
| Tỉ lệ người dùng trên nền tảng mới | 0 | 0 | Tenant thí điểm | 100% |

Thời gian của đợt 1 đến đợt 3 là kế hoạch tham chiếu theo bản đánh giá; TL-04 lập kế hoạch chi tiết nguồn lực và lịch chuyển tenant.

# Ma trận truy vết 110 tiêu chí

## Cách đọc

Ma trận dưới đây ánh xạ từng tiêu chí trong bảng kiểm 110 tiêu chí của bản đánh giá kiến trúc sang giải pháp trong bộ tài liệu thiết kế. Mỗi dòng nêu mã và tên tiêu chí đúng như bảng kiểm, giải pháp tóm tắt trong một câu, mục trong TL-01 trình bày giải pháp, và tài liệu khác của bộ (TL-02 quy trình nghiệp vụ, TL-03 thiết kế CSDL, TL-04 triển khai, vận hành, an ninh) có nội dung bổ sung. Ma trận chia theo 12 trục để dễ tra cứu; số dòng của các trục cộng lại là 110. Số mục trong cột TL-01 theo đánh số của tài liệu này.

## Trục A: Phong cách kiến trúc

Trục A kiểm tra phong cách tổng thể và ranh giới giữa các phần. Giải pháp xoay quanh quan điểm hợp lý theo từng phần của chương 3: lõi Modular Monolith, service tách ở nơi có lý do định lượng, tích hợp qua sự kiện.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| A1 | Phong cách kiến trúc phù hợp quy mô quốc gia | Kết hợp cell theo vùng, lõi Modular Monolith và service tách theo năm tiêu chí định lượng. | Chương 3, 4 | TL-04 (cell) |
| A2 | Mỗi service sở hữu dữ liệu riêng | Mỗi service và mỗi module có schema riêng với tài khoản CSDL riêng, không đọc chéo. | 5.1, 5.2 đến 5.18 | TL-03 |
| A3 | Ranh giới module rõ, không phụ thuộc vòng | Spring Modulith và ArchUnit kiểm phụ thuộc chỉ qua gói `api`, chặn vòng trong CI. | 5.1 | |
| A4 | Tích hợp bất đồng bộ, đảm bảo giao nhận | Outbox, Debezium, Kafka và inbox idempotent cho giao nhận ít nhất một lần, xử lý đúng một lần. | 6.1 | TL-03 (outbox) |
| A5 | Tách mô hình ghi và mô hình đọc | CQRS với CDC sang ClickHouse cho phân tích và bản sao đọc cho truy vấn danh sách. | 5.15, 13.4 | TL-03 |
| A6 | Lưu vết bất biến cho dữ liệu pháp lý | Sổ cái chỉ chèn với chuỗi băm phân vùng, niêm phong Merkle và dấu thời gian RFC 3161. | 11.2, 11.4 | TL-03 (ledger) |
| A7 | Hợp đồng API thống nhất, máy đọc được | OpenAPI 3.1 cho REST và AsyncAPI 3.0 cho sự kiện, kiểm tương thích trong CI. | 6.2, 6.3, Phụ lục C | |
| A8 | Frontend chia module theo miền | Shell Angular và các remote Native Federation theo miền, triển khai độc lập. | 5.17, chương 7 | |

## Trục L: Liên thông và tích hợp

Trục L kiểm tra khả năng kết nối với hệ sinh thái chính phủ số. Giải pháp tập trung ở integration-gateway với lớp chống ô nhiễm và mô hình dữ liệu chuẩn.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| L1 | Nhúng giao diện vào cổng văn phòng số | Chế độ web component `<vt-okr-app>` với SSO OIDC và CSP `frame-ancestors` theo tenant. | 5.17, 7.3 | TL-02 |
| L2 | Liên kết định danh theo chuẩn mở | Keycloak hỗ trợ OIDC, SAML và liên kết IdP riêng của từng tổ chức. | 5.3, 16.2 | TL-04 |
| L3 | Định danh điện tử VNeID | Liên kết VNeID qua Keycloak với PKCE, `state`, `nonce` và kiểm đủ ID token. | 16.3 | TL-04 |
| L4 | Đồng bộ tổ chức, người dùng từ nguồn chuẩn | Connector CSDL CBCCVC và HRM cấp dữ liệu cho org-service qua sự kiện, lưu hai trục thời gian. | 5.5, 6.5 | TL-02, TL-03 |
| L5 | Nhận nhiệm vụ, minh chứng từ văn bản | Connector vOffice, iOffice nhận văn bản chỉ đạo, AI gợi ý trích nhiệm vụ, người xác nhận. | 6.4, 6.5, 17.3 | TL-02 |
| L6 | Liên thông văn bản giữa cơ quan | Connector Trục liên thông văn bản quốc gia theo EdXML qua X-Road. | 6.5 | TL-04 |
| L7 | Kết nối nền tảng tích hợp cấp bộ, tỉnh, quốc gia | Connector LGSP và NDXP qua integration-gateway với bulkhead và circuit breaker riêng. | 6.5 | TL-04 |
| L8 | Kết nối CSDL quốc gia CBCCVC, CSDL đảng viên | Connector riêng cho từng CSDL, đồng bộ theo con trỏ và đối soát định kỳ. | 6.5 | TL-02 |
| L9 | Tích hợp dịch vụ ký số qua giao diện thay thế được | Giao diện `SignatureProvider` với hiện thực MySign, chứng thư chuyên dùng và mật mã cơ yếu. | 5.10, 11.6 | TL-04 |
| L10 | Hợp đồng tích hợp công bố cho bên thứ ba | OpenAPI và AsyncAPI công bố qua cổng nhà phát triển, có chính sách phiên bản và ngừng hỗ trợ. | 6.2, 6.3 | |
| L11 | Mô hình dữ liệu chuẩn dùng chung | Mô hình dữ liệu chuẩn tám nhóm theo PL 4 với URN định danh. | 6.7 | TL-03 |
| L12 | Bộ kết nối cho văn phòng số hãng khác | Mẫu connector Camel và bộ kiểm thử hợp đồng Pact cho từng hãng. | 6.4, 6.5 | |

## Trục B: Quan sát

Trục B kiểm tra khả năng nhìn thấy hệ thống khi vận hành. Giải pháp là OpenTelemetry cho mọi tín hiệu và SLO có ngân sách lỗi.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| B1 | Tracing phân tán end-to-end | OpenTelemetry từ trình duyệt qua cổng, service, Kafka tới CSDL, lưu ở Tempo. | 15.2 | TL-04 |
| B2 | Lan truyền ngữ cảnh trace giữa service | W3C Trace Context qua HTTP, qua header outbox và Kafka, qua comment SQL. | 15.2, 6.1 | |
| B3 | Endpoint metrics hoạt động | Actuator và Micrometer xuất `/actuator/prometheus` trên cổng quản trị. | 15.3 | TL-04 |
| B4 | Chỉ số SLI/SLO cho dịch vụ | Bảng SLO theo dịch vụ, cảnh báo theo tốc độ tiêu ngân sách lỗi. | 15.3 | TL-04 |
| B5 | Giám sát hiệu năng ứng dụng | Bốn tín hiệu vàng và span nghiệp vụ trên Grafana, Tempo. | 15.3 | TL-04 |
| B6 | Health check cho bộ điều phối | Probe liveness, readiness, startup qua nhóm sức khoẻ của Actuator. | 5.1 | TL-04 |
| B7 | Giám sát truy vấn chậm | `pg_stat_statements`, `auto_explain` 500 ms và comment SQL gắn trace, tenant. | 15.3 | TL-03, TL-04 |
| B8 | Giám sát phía người dùng | Grafana Faro thu Core Web Vitals, lỗi và thời gian tải remote. | 15.7 | |

## Trục C: Đóng gói và hạ tầng chạy

Trục C kiểm tra cách đóng gói và chạy trên Kubernetes. Phần lớn chi tiết nằm ở TL-04; TL-01 xác định các yêu cầu kiến trúc.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| C1 | Image container an toàn, tối giản | Ảnh nền distroless chạy không root, quét Trivy, ký Cosign. | 18.2 | TL-04 |
| C2 | Manifest riêng cho từng service | Helm chart chung tham số hoá, values riêng cho từng service, môi trường và cell. | 18.2 | TL-04 |
| C3 | Probe liveness/readiness/startup | Ba loại probe bắt buộc, Kyverno từ chối manifest thiếu probe. | 5.1, 18.2 | TL-04 |
| C4 | Requests/limits và heap hợp lý | Requests, limits theo định cỡ và `MaxRAMPercentage=70`. | 5.1, 13.8 | TL-04 |
| C5 | Tự động co giãn theo tải | HPA theo CPU và độ trễ, KEDA theo hàng đợi Kafka và job Flowable. | 5.1, 8.8 | TL-04 |
| C6 | Tiến trình không trạng thái | Phiên trong token, bộ đệm ở Valkey, tệp ở Ceph, không trạng thái cục bộ cần giữ. | 4.5, 13.2 | TL-04 |
| C7 | Sẵn sàng cao khi gián đoạn node | PodDisruptionBudget, phân bố theo vùng, tối thiểu ba replica. | 19.2 | TL-04 |
| C8 | Lưu trữ tệp tách khỏi node | Minh chứng và tệp sinh ra trên Ceph RGW, không dùng ổ cục bộ. | 5.8 | TL-04 |
| C9 | Cấu hình tách khỏi bản build; GitOps | Cấu hình qua biến môi trường, `/config.json`, Argo CD đồng bộ từ Git. | 5.17, 18.2 | TL-04 |
| C10 | Chuỗi cung ứng phần mềm có kiểm soát | SBOM, ký image, chứng thực nguồn gốc SLSA, Kyverno kiểm khi chạy. | 18.2 | TL-04 |

## Trục D: An toàn thông tin

Trục D có số tiêu chí lớn và liên quan trực tiếp tới các lỗ hổng nghiêm trọng. Kiến trúc chuyển các biện pháp từ kỷ luật lập trình sang cơ chế bắt buộc.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| D1 | Không có thông tin xác thực trong mã nguồn | Bí mật trong OpenBao, nạp qua External Secrets, quét gitleaks chặn trong CI. | 16.6 | TL-04 |
| D2 | Xác thực đa yếu tố, chống dò mật khẩu | MFA AAL2 cho vai trò nhạy cảm, khoá tạm sau 5 lần sai, nâng bậc khi ký. | 16.2 | TL-04 |
| D3 | Không có chức năng ẩn, không bỏ qua xác thực | Mặc định từ chối, danh sách công khai tường minh, kiểm thử mọi route trả 401 khi thiếu token. | 16.6 | TL-04 |
| D4 | Lưu mật khẩu bằng hàm băm có salt | Keycloak băm Argon2id, ứng dụng không nhận mật khẩu. | 16.2 | TL-04 |
| D5 | Quản lý phiên an toàn | Access token 15 phút, refresh token xoay vòng một lần dùng, phát hiện dùng lại. | 16.2 | TL-04 |
| D6 | Kiểm soát truy cập tập trung | OPA với chính sách Rego, đánh giá từng phần cho danh sách, RLS theo tenant. | 16.4, 12.2 | TL-04 |
| D7 | Chống injection, XSS, CSRF, CORS sai | Truy vấn tham số, danh sách trắng sắp xếp, mã hoá đầu ra, CORS theo danh sách, token trong header. | 16.5, 16.6 | TL-04 |
| D8 | Mật mã và kiểm tra chứng thư đúng chuẩn | AES-256-GCM, TLS 1.2 trở lên, kho tin cậy chuẩn, cấm chấp nhận mọi chứng thư. | 9.4, 16.6 | TL-04 |
| D9 | Nhật ký an toàn, không chứa dữ liệu nhạy cảm | Ba lớp che log và nhật ký kiểm toán tách riêng có chống sửa. | 15.5, 15.6 | TL-04 |
| D10 | Thành phần không có lỗ hổng đã biết | SCA và quét image chặn CVSS từ 7,0, SBOM theo dõi liên tục. | 18.2 | TL-04 |
| D11 | Header bảo mật HTTP | APISIX thêm HSTS, CSP, nosniff, Referrer-Policy, Permissions-Policy, COOP. | 16.5 | TL-04 |
| D12 | Mã hoá đầu cuối dữ liệu mật | Mã hoá đầu cuối WebCrypto cho C3, khoá không trích xuất được, phục hồi Shamir 3 trên 5. | 9.5 | TL-04 |

## Trục E: Thành phần nền tảng

Trục E kiểm tra lựa chọn thành phần. Phụ lục B liệt kê đầy đủ phiên bản và giấy phép.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| E1 | Runtime, framework còn được hỗ trợ | Java 21 LTS, Spring Boot 3.5, Angular 21, PostgreSQL 17, có lịch nâng cấp. | Phụ lục B | TL-04 |
| E2 | CSDL hợp đa khách thuê, cài cách ly | PostgreSQL với RLS cho pool, CSDL logic cho bridge, cụm riêng cho silo. | 12.1, 12.2 | TL-03 |
| E3 | Message broker cho đỉnh tải | Kafka với phân vùng theo khoá, KEDA co giãn consumer. | 6.1, 13.5 | TL-04 |
| E4 | Kho đối tượng cho minh chứng | Ceph RGW với Object Lock chế độ compliance và mã xoá 4+2. | 5.8, 9.2 | TL-04 |
| E5 | Cache phân tán có thiết kế | Valkey cluster với quy ước khoá theo tenant, thời hạn và vô hiệu hoá theo sự kiện. | 13.2 | TL-04 |
| E6 | Thành phần không hết hỗ trợ, giấy phép phù hợp | Thay Redis bằng Valkey, Elasticsearch bằng OpenSearch, Vault bằng OpenBao, không dùng iText AGPL. | Phụ lục B | |
| E7 | Năng lực đã kiểm chứng ≥ tải mục tiêu | Định cỡ theo định luật Little và thử tải k6 ở mức 70.000 phiên mỗi cell lớn. | 2.3, 13.8 | TL-04 |
| E8 | Thành phần AI, BI theo kiến trúc đích | ai-gateway chỉ đọc với vLLM tự lưu trữ, ClickHouse, Cube, Superset cho BI. | 5.14, 5.15, chương 17 | |

## Trục F: Chịu lỗi và hiệu năng mã

Trục F kiểm tra cách mã ứng xử khi phụ thuộc lỗi và khi tải cao. Thư viện nền áp cấu hình chuẩn cho mọi service.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| F1 | Ngắt mạch khi phụ thuộc lỗi | Circuit breaker Resilience4j theo phụ thuộc với cấu hình chuẩn. | 14.2 | |
| F2 | Timeout cho mọi lời gọi ra ngoài | Bảng timeout mặc định cho mọi loại lời gọi, không có giá trị vô hạn. | 14.3 | |
| F3 | Retry có kiểm soát và idempotent | Tối đa 3 lần có lùi theo cấp số và ngẫu nhiên, chỉ cho thao tác idempotent. | 14.2, 14.4 | |
| F4 | Giới hạn tốc độ, cắt tải | Giới hạn theo người dùng, tenant, route ở APISIX, cắt tải theo lớp ưu tiên. | 14.5, 12.5 | TL-04 |
| F5 | Cô lập tài nguyên theo phụ thuộc | Bulkhead semaphore theo phụ thuộc, cấm executor không giới hạn. | 14.6 | |
| F6 | Giới hạn kích thước kết quả, upload | Request tối đa 1 MB, trang tối đa 200 dòng, tải lên theo phần với giới hạn. | 14.6, 5.8 | |
| F7 | Pool kết nối định cỡ theo tải | Định cỡ theo định luật Little, 20 kết nối mỗi pod, PgBouncer chế độ giao dịch. | 13.6 | TL-03 |
| F8 | Xử lý nền cho tác vụ nặng | Hàng đợi và worker KEDA cho sinh biểu mẫu, ký số, tính điểm, OCR, API trả 202. | 13.5 | |
| F9 | Kiểm soát ghi đồng thời | Khoá lạc quan `row_version`, ETag và `If-Match`, trả 412 khi xung đột. | 14.4 | TL-03 |
| F10 | Xử lý lỗi chuẩn hoá | RFC 9457 Problem Details với mã lỗi ổn định và `traceId`. | 14.7 | |
| F11 | Kích thước đơn vị mã hợp lý | Use case một phương thức, giới hạn độ phức tạp và kích thước lớp trong SonarQube. | 5.1, 18.2 | |

## Trục G: Nhật ký

Trục G kiểm tra cách thu gom, cấu trúc và bảo vệ log.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| G1 | Thu gom log tập trung mọi service | Fluent Bit trên mỗi node gửi qua Collector tới Loki và OpenSearch. | 15.4 | TL-04 |
| G2 | Log có cấu trúc | JSON theo ECS với Log4j2 `JsonTemplateLayout`. | 15.4 | |
| G3 | Liên kết log với trace | `trace.id`, `span.id` tự đưa vào MDC bởi OpenTelemetry agent. | 15.4 | |
| G4 | Không mất log khi tải cao | Ghi bất đồng bộ, chỉ bỏ log mức thấp khi đầy, đếm log bị bỏ thành metric. | 15.4 | TL-04 |
| G5 | Lưu giữ và bảo vệ log | Log vận hành 30 ngày, kiểm toán 5 năm, chỉ đọc, sự kiện quan trọng vào sổ cái. | 15.4, 15.6 | TL-04 |
| G6 | Tách audit log | Sự kiện kiểm toán qua topic riêng vào chỉ mục riêng với người đọc riêng. | 15.6 | TL-04 |
| G7 | Không ghi dữ liệu nhạy cảm vào log | Che ở mã, ở Log4j2 và ở Collector, quét mẫu hằng tuần. | 15.5 | |

## Trục H: Chức năng nghiệp vụ

Trục H kiểm tra độ phủ chức năng của tám nhóm tính năng và hai luồng đánh giá. Chi tiết quy trình nằm ở TL-02.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| H1 | Mục tiêu và OKR | Module `okr` với phân rã mục tiêu theo cây tổ chức và check-in KR. | 5.1 | TL-02, TL-03 |
| H2 | Giao việc và KPI | Module `task` với máy trạng thái tham số hoá và KPI tính từ Policy Pack. | 5.1 | TL-02, TL-03 |
| H3 | Sản phẩm, minh chứng | Module `product` và evidence-service với minh chứng chỉ ghi có băm. | 5.1, 5.8 | TL-02, TL-03 |
| H4 | Đánh giá, xếp loại | Module `appraisal`, scoring-service và bảng quyết định DMN. | 5.7, chương 10 | TL-02 |
| H5 | Kiểm điểm, hồ sơ số | Module `dossier`, document-service sinh PDF/A, ký PAdES. | 5.11, 11.6 | TL-02 |
| H6 | Giám sát, hậu kiểm | Module `oversight` với tám dấu hiệu bất thường và cờ hậu kiểm. | 17.4, 11.8 | TL-02 |
| H7 | Phân tích, dashboard | analytics với ClickHouse, Cube và bảng điều hành. | 5.15 | TL-02 |
| H8 | Quản trị nền tảng | tenant-registry, Config Studio, UI Pack và Policy Pack. | 5.4, chương 7, 10 | TL-02, TL-04 |
| H9 | Hai luồng Đảng / Nhà nước | Hai hồ sơ mỗi người mỗi kỳ, gói quy tắc và mẫu quy trình riêng cho từng luồng. | 10.8 | TL-02, TL-03 |

## Trục I: Toàn vẹn và giá trị pháp lý

Trục I kiểm tra khả năng chứng minh tính đúng của dữ liệu đánh giá trước bên thứ ba.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| I1 | Nhật ký thay đổi đầy đủ | Mọi chuyển trạng thái ghi log trạng thái và sự kiện, sự kiện pháp lý vào sổ cái. | 5.1, 11.2 | TL-03 |
| I2 | Sổ cái chỉ-ghi, chuỗi băm, niêm phong | Bảng chỉ chèn có trigger chặn sửa, 64 chuỗi mỗi tenant, niêm phong Merkle hằng giờ. | 11.2, 11.4 | TL-03 |
| I3 | Khoá kỳ, mở lại cần hai người duyệt | Saga khoá kỳ và mở lại bốn mắt có ký số, giới hạn phạm vi và thời hạn. | 11.8 | TL-02 |
| I4 | Ký số tại mốc pháp lý | Ký PAdES-LTA ở bốn mốc pháp lý và biên bản kiểm điểm, ký lô cho quyết định hàng loạt. | 9.2, 11.6 | TL-02 |
| I5 | Minh chứng bất biến có mã băm | SHA-256 khi tải lên, Object Lock compliance, phiên bản mới không xoá bản cũ. | 5.8, 9.2 | TL-03 |
| I6 | Snapshot kết quả gắn phiên bản quy tắc | Snapshot đầu vào, băm đầu vào, phiên bản và băm gói quy tắc cho mọi kết quả. | 10.7 | TL-03 |
| I7 | Dữ liệu hai trục thời gian | Thời gian hiệu lực và thời gian ghi nhận cho tổ chức, phân công, quan hệ báo cáo. | 11.7 | TL-03 |
| I8 | Dấu thời gian tin cậy | Token RFC 3161 cho niêm phong và chữ ký, bản neo hằng ngày tới hai bên độc lập. | 11.4 | TL-04 |

## Trục J: Kiểm thử và chất lượng

Trục J kiểm tra kỷ luật kiểm thử và cổng chất lượng.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| J1 | Mật độ unit test | Kiểm thử đơn vị cho `domain` và `application`, kiểm thử đột biến cho lõi tính toán. | 18.1 | |
| J2 | CI chặn khi test lỗi | Cổng chất lượng Jenkins và SonarQube chặn hợp nhất. | 18.2 | |
| J3 | Ngưỡng độ phủ | 80% dòng, 70% nhánh với mã mới, 70% đột biến cho tính điểm. | 18.1 | |
| J4 | Test tích hợp tự động | Testcontainers cho CSDL, Kafka, Keycloak, OpenBao, kiểm RLS hai tenant. | 18.1, 12.2 | |
| J5 | Test đầu-cuối | Playwright cho khoảng 40 hành trình chính trên môi trường tạm theo nhánh. | 18.1 | TL-02 |
| J6 | Bộ test vàng do nghiệp vụ ký | Bộ ca mẫu vàng ký số, yêu cầu đạt 100% để phát hành Policy Pack. | 10.4 | TL-02 |
| J7 | Thử tải định kỳ | k6 hằng tuần với kịch bản cuối kỳ và trước bản phát hành chính. | 18.1, 8.12 | TL-04 |
| J8 | Phân tích tĩnh | SonarQube, Semgrep, gitleaks, SCA trong CI. | 18.2 | |
| J9 | Tài liệu kiến trúc khớp mã | Quy tắc kiến trúc là kiểm thử ArchUnit và Modulith, sơ đồ module sinh từ mã. | 5.1, 18.1 | |

## Trục K: Tách lớp và tính độc lập

Trục K kiểm tra tính độc lập của nền tảng với hệ thống cũ và giữa các phần.

| Mã | Tiêu chí | Giải pháp | TL-01 | Tài liệu khác |
|---|---|---|---|---|
| K1 | Schema riêng, migration có phiên bản | Schema riêng mỗi module, Flyway với tài khoản migration riêng. | 5.1 | TL-03 |
| K2 | Định danh qua IdP chuẩn | Keycloak là IdP duy nhất, ứng dụng chỉ là resource server. | 5.3, 16.2 | TL-04 |
| K3 | Dữ liệu tổ chức qua API, không đọc bảng | org-service cung cấp API và sự kiện, lõi dùng bản sao `org_replica` từ sự kiện. | 5.5, 19.2 | TL-03 |
| K4 | Thông báo, SMS, liên thông qua sự kiện | notification-service nhận EVT-21, không ghi bảng của vOffice. | 5.12 | |
| K5 | Đa khách thuê trong một bản cài | Ba mô hình pool, bridge, silo, tiếp nhận tenant tự động không triển khai lại. | 12.1, 12.4 | TL-04 |
| K6 | Một bản build cho mọi môi trường | Image build một lần, cấu hình nạp lúc chạy. | 5.17, 18.2 | TL-04 |
| K7 | Chạy độc lập không cần hệ văn bản | Lõi không phụ thuộc vOffice; tích hợp văn bản là connector tuỳ chọn. | 6.4 | TL-02 |
| K8 | Không trùng lặp nghiệp vụ core/service | Bảng phần, kiểu, lý do của chương 3 và ranh giới module xác định một nơi sở hữu mỗi nghiệp vụ. | 3.3, 5.1 | |

Tổng số dòng của mười hai bảng là 8 + 12 + 8 + 10 + 12 + 8 + 11 + 7 + 9 + 8 + 9 + 8 = 110, khớp với bảng kiểm của bản đánh giá.

# Phụ lục A. Hồ sơ quyết định kiến trúc {-}

Phụ lục này ghi lại các quyết định kiến trúc (Architecture Decision Record, ADR) theo khuôn mẫu bốn phần: bối cảnh dẫn tới quyết định, nội dung quyết định, các phương án đã cân nhắc, và hệ quả cả tích cực lẫn tiêu cực. ADR-01 đến ADR-20 là các quyết định đã chốt trong tệp cơ sở thiết kế chung; tài liệu này viết đầy đủ lập luận. ADR-21 đến ADR-31 là quyết định bổ sung phát sinh trong quá trình thiết kế chi tiết. Một ADR chỉ được thay đổi bằng một ADR mới ghi rõ thay thế ADR nào; ADR cũ được giữ với trạng thái "đã thay thế".

## ADR-01. Kiến trúc lai theo từng phần {-}

**Bối cảnh.** Hệ thống phục vụ khoảng 6 triệu tài khoản, tải dồn cuối kỳ, đa ngành, ba mô hình triển khai, và quy định đổi theo quý. Hệ thống hiện tại là khối nguyên khối không ranh giới, nhưng đội ngũ có kinh nghiệm Java, Spring và không đủ người để vận hành hàng chục microservice ngay. Bản đánh giá ghi nhận A1 đến A3 không đạt.

**Quyết định.** Áp dụng kiểu kiến trúc theo từng phần: lõi nghiệp vụ là Modular Monolith với Spring Modulith; tách microservice ở nơi thoả các tiêu chí định lượng của chương 3 (tải khác biệt, vùng bảo mật, công nghệ khác, nhịp phát hành khác); toàn hệ thống chia cell theo vùng khi mở rộng. Không áp một kiểu cho toàn hệ thống.

**Phương án đã cân nhắc.** Microservice hoàn toàn theo từng bounded context (khoảng 25 service) bị loại vì chi phí vận hành, giao dịch phân tán ở các thao tác cần nhất quán mạnh như chuyển trạng thái nhiệm vụ kèm kiểm tra kỳ khoá, và đội ngũ chưa sẵn sàng. Giữ nguyên khối và chỉ tái cấu trúc bị loại vì không giải quyết được khả năng mở rộng và cô lập vùng bảo mật.

**Hệ quả.** Tích cực: giao dịch cục bộ trong lõi, ít thành phần cần vận hành, có đường tách dần khi số liệu cho thấy cần. Tiêu cực: phải giữ kỷ luật ranh giới module bằng kiểm thử kiến trúc, nếu không lõi sẽ thoái hoá thành khối nguyên khối mới.

## ADR-02. PostgreSQL 17 cho nền tảng mới {-}

**Bối cảnh.** Hệ thống hiện tại dùng Oracle với chi phí giấy phép theo nhân, khó nhân bản cho hàng chục cell và bản cài tại chỗ. Nền tảng cần RLS, phân vùng, sao chép logic có bộ lọc dòng, và vận hành trên Kubernetes.

**Quyết định.** Dùng PostgreSQL 17 vận hành bằng CloudNativePG trên node pool CSDL riêng với NVMe cục bộ. Mỗi module và mỗi service có schema và tài khoản CSDL riêng.

**Phương án đã cân nhắc.** Tiếp tục Oracle bị loại vì chi phí và vì mô hình bản cài tại chỗ. CSDL phân tán (CockroachDB, YugabyteDB) bị loại vì độ trễ ghi cao hơn, hệ sinh thái công cụ hẹp hơn, và nhu cầu mở rộng ngang đã được giải quyết bằng cell.

**Hệ quả.** Tích cực: không chi phí giấy phép, RLS gốc, đội ngũ vận hành phổ biến. Tiêu cực: phải chuyển đổi dữ liệu và câu lệnh từ Oracle; một cụm PostgreSQL có giới hạn ghi, nên kích thước cell phải được kiểm soát (ADR-29).

## ADR-03. Kafka với Transactional Outbox và Inbox {-}

**Bối cảnh.** Hệ thống hiện tại ghi thẳng vào bảng của vOffice để thông báo và liên thông, gây phụ thuộc chặt. Nền tảng mới cần phân phối sự kiện tới nhiều consumer (sổ cái, phân tích, thông báo, tích hợp) mà không mất sự kiện khi một bên lỗi.

**Quyết định.** Dùng Apache Kafka làm bus sự kiện; service ghi sự kiện vào bảng `outbox_event` trong cùng giao dịch nghiệp vụ, Debezium Outbox Event Router đưa lên Kafka; consumer ghi `inbox_processed` để xử lý idempotent. Apicurio Registry quản lý lược đồ sự kiện.

**Phương án đã cân nhắc.** Ghi đôi (ghi CSDL rồi gửi Kafka) bị loại vì mất sự kiện khi lỗi giữa hai bước. RabbitMQ bị loại vì không giữ lịch sử để phát lại và thông lượng thấp hơn ở tải phân tích.

**Hệ quả.** Tích cực: giao nhận ít nhất một lần có bảo đảm, phát lại được. Tiêu cực: thêm Debezium cần vận hành, consumer phải idempotent, sự kiện tới chậm khoảng vài trăm mili giây.

## ADR-04. Ba tầng điều phối luồng {-}

**Bối cảnh.** Góp ý G2 lo ngại engine BPMN nghẽn khi hàng trăm, hàng nghìn tenant. Chương 8 định lượng khoảng 48 triệu user task mỗi quý và chỉ ra nguyên nhân nghẽn là đưa vòng đời khối lượng lớn vào engine và nhân bản định nghĩa theo tenant.

**Quyết định.** Chia điều phối thành ba tầng: máy trạng thái khai báo trong mã cho vòng đời khối lượng lớn (nhiệm vụ, minh chứng, check-in); Flowable 7 BPMN cho luồng duyệt của con người, engine riêng theo cell, định nghĩa theo mẫu cộng tham số; saga cho các quy trình nhiều bước dài như khoá kỳ, phát hành Policy Pack, với bước nặng chạy bằng external worker.

**Phương án đã cân nhắc.** Đưa mọi thứ vào BPMN bị loại vì tải; Camunda 8 (Zeebe) bị loại vì giấy phép tự lưu trữ cho mục đích thương mại; Temporal bị loại vì nghiệp vụ không đọc được định nghĩa luồng.

**Hệ quả.** Tích cực: engine chỉ chịu khoảng 670 user task mỗi giây toàn quốc, chia theo cell. Tiêu cực: hai cách biểu diễn luồng cùng tồn tại, cần quy tắc rõ khi nào dùng tầng nào (chương 8).

## ADR-05. Giao diện theo tenant bốn lớp, áp dụng có chọn lọc {-}

**Bối cảnh.** Góp ý G1 đề xuất SDUI với Micro-frontends để mỗi tenant có giao diện riêng. Áp SDUI cho toàn bộ giao diện làm tăng chi phí phát triển và giảm hiệu năng; không áp thì không đáp ứng tuỳ biến đa ngành.

**Quyết định.** Bốn lớp với mức áp dụng khác nhau: token thiết kế theo tenant cho toàn bộ; SDUI có giới hạn cho các bề mặt cấu hình được; biểu mẫu động bằng Formly, JSON Schema và CEL; plugin Native Federation cho module chuyên ngành. UI Pack có phiên bản, tách khỏi phần tính điểm của Policy Pack.

**Phương án đã cân nhắc.** SDUI toàn phần và giao diện cố định có cấu hình màu sắc; cả hai được phân tích ở chương 7.

**Hệ quả.** Tích cực: tenant tuỳ biến được mà không cần phát hành phần mềm; hiệu năng giữ trong ngân sách. Tiêu cực: danh mục component được phép phải được quản trị; thêm một loại tạo phẩm có phiên bản (UI Pack).

## ADR-06. Phân loại dữ liệu bốn mức và mật mã theo mức {-}

**Bối cảnh.** Góp ý G3 lo ngại chi phí hiệu năng của mã hoá. Mã hoá đồng loạt làm mất khả năng tính toán, phân tích; không mã hoá thì không đạt yêu cầu bảo vệ dữ liệu cá nhân và dữ liệu hạn chế.

**Quyết định.** Bốn lớp C0 đến C3 như chương 9: C1 mã hoá khi truyền và ở mức đĩa; C2 mã hoá trường bằng mã hoá phong bì; C3 mã hoá đầu cuối tại client. Điểm số và mức xếp loại được bảo vệ bằng RLS, ABAC và sổ cái thay vì mã hoá trường. Bí mật nhà nước chỉ xử lý ở bản cài cách ly với mật mã Ban Cơ yếu.

**Phương án đã cân nhắc.** Mã hoá trong suốt cả CSDL (TDE) không bảo vệ trước quản trị CSDL; mã hoá đầu cuối cho mọi dữ liệu làm mất toàn bộ phân tích.

**Hệ quả.** Tích cực: chi phí mã hoá trên đường găng khoảng micro giây mỗi trường. Tiêu cực: dữ liệu C3 không tìm kiếm, phân tích được; cần quản lý khoá thiết bị và phục hồi.

## ADR-07. Cell và ba mô hình thuê bao {-}

**Bối cảnh.** Khoảng 5.000 tenant với quy mô chênh lệch hàng nghìn lần và yêu cầu cách ly khác nhau; một cụm duy nhất có bán kính ảnh hưởng quá lớn.

**Quyết định.** Kiến trúc cell theo vùng; trong mỗi cell, ba mô hình silo, bridge, pool như chương 12.

**Phương án đã cân nhắc.** Một CSDL mỗi tenant cho tất cả bị loại vì 5.000 CSDL khó vận hành; pool cho tất cả bị loại vì không đáp ứng tenant có yêu cầu cách ly.

**Hệ quả.** Tích cực: bán kính sự cố giới hạn trong cell, mở rộng bằng thêm cell. Tiêu cực: cần mặt phẳng toàn cục và quy trình chuyển tenant.

## ADR-08. Keycloak 26 với Organizations {-}

**Bối cảnh.** Hệ thống hiện tại tự quản lý mật khẩu và phiên, có nhiều lỗ hổng (S02, S04, S06, S12). Cần SSO với cổng văn phòng số, VNeID và IdP của khách hàng.

**Quyết định.** Keycloak 26 đặt theo cell, dùng Organizations để biểu diễn tenant trong realm chung, liên kết VNeID và IdP khách hàng qua identity brokering, OIDC luồng mã uỷ quyền với PKCE, `state`, `nonce`.

**Phương án đã cân nhắc.** Realm mỗi tenant bị loại vì không co giãn tới hàng nghìn realm; tự phát triển IdP bị loại vì rủi ro an ninh.

**Hệ quả.** Tích cực: ứng dụng không xử lý mật khẩu; chuẩn mở. Tiêu cực: Keycloak trở thành phụ thuộc quan trọng cần sẵn sàng cao.

## ADR-09. Sổ cái toàn vẹn {-}

**Bối cảnh.** Kết quả đánh giá cần chứng minh được trước thanh tra và khiếu nại, kể cả khi bên kiểm tra không tin nhà vận hành.

**Quyết định.** Sổ cái chỉ chèn với 64 chuỗi băm mỗi tenant, niêm phong Merkle mỗi giờ, ký số và dấu thời gian RFC 3161, bản neo gửi bên độc lập; ghi qua outbox nên không nằm trên đường găng.

**Phương án đã cân nhắc.** Chuỗi khối công cộng bị loại vì pháp lý và chủ quyền dữ liệu; CSDL sổ cái chuyên dụng (như immudb) bị loại để giảm số công nghệ, vì PostgreSQL với trigger chặn sửa và kiểm chứng độc lập đủ đáp ứng.

**Hệ quả.** Tích cực: kiểm chứng ngoại tuyến được bằng công cụ công bố. Tiêu cực: cửa sổ tối đa 1 giờ giữa hai lần niêm phong, được rút ngắn ở mốc pháp lý.

## ADR-10. Angular 21, Native Federation, PrimeNG và Flutter {-}

**Bối cảnh.** Slide 37 của báo cáo nêu React, nhưng đội ngũ hiện có kinh nghiệm Angular; cần micro-frontend để triển khai độc lập theo miền.

**Quyết định.** Web dùng Angular 21 với Native Federation và PrimeNG; di động dùng Flutter.

**Phương án đã cân nhắc.** React với Module Federation phù hợp về kỹ thuật nhưng đòi đào tạo lại đội ngũ; Kotlin Multiplatform cho di động chưa có đủ thư viện giao diện dùng chung.

**Hệ quả.** Tích cực: tận dụng đội ngũ, có bộ component doanh nghiệp. Tiêu cực: Native Federation còn mới hơn Module Federation, cần theo dõi phiên bản.

## ADR-11. mTLS bằng Istio ambient {-}

**Bối cảnh.** Cần mã hoá và xác thực giữa service theo mô hình không tin cậy mặc định; mesh dùng sidecar tốn khoảng 0,5 vCPU và vài trăm MB mỗi pod ở quy mô hàng nghìn pod.

**Quyết định.** Istio ambient mode với ztunnel trên mỗi node cho mTLS L4, không sidecar; waypoint L7 chỉ bật ở nơi cần.

**Phương án đã cân nhắc.** Istio sidecar và Linkerd (sidecar nhẹ) đều tốn tài nguyên theo pod; mTLS tự quản trong ứng dụng khó xoay vòng chứng thư.

**Hệ quả.** Tích cực: chi phí khoảng 0,5 ms mỗi lời gọi, không thay đổi pod. Tiêu cực: chính sách L7 cần waypoint, nên uỷ quyền chi tiết đặt ở OPA (ADR-21).

## ADR-12. Quan sát bằng OpenTelemetry {-}

**Bối cảnh.** Trục B và G điểm thấp nhất; cần chuẩn chung cho Java, Go, Python và trình duyệt.

**Quyết định.** OpenTelemetry agent và SDK, Collector, Tempo cho trace, Prometheus với Thanos cho metric, Loki cho log, Grafana, Faro cho giám sát phía người dùng.

**Phương án đã cân nhắc.** Bộ sản phẩm APM thương mại bị loại vì chi phí theo host và khó triển khai bản cách ly; Elastic APM bị loại vì giấy phép.

**Hệ quả.** Tích cực: một chuẩn, thay công cụ phía sau không sửa mã. Tiêu cực: nhiều thành phần lưu trữ cần vận hành.

## ADR-13. Ceph RGW với Object Lock {-}

**Bối cảnh.** Minh chứng khoảng 192 TB mỗi năm cần bất biến và lưu trữ dài hạn.

**Quyết định.** Ceph RGW giao diện S3 với Object Lock chế độ compliance, mã xoá 4+2.

**Phương án đã cân nhắc.** MinIO bị loại vì giấy phép AGPL và thay đổi chính sách phân phối bản cộng đồng; lưu tệp trên hệ thống tệp chia sẻ không có bất biến.

**Hệ quả.** Tích cực: bất biến được bảo đảm ở tầng lưu trữ, mở rộng ngang. Tiêu cực: Ceph cần đội vận hành có kinh nghiệm.

## ADR-14. ClickHouse, Cube và Superset cho phía đọc {-}

**Bối cảnh.** Báo cáo hiện chạy trên CSDL nghiệp vụ, gây chậm cuối kỳ.

**Quyết định.** CDC vào ClickHouse, lớp ngữ nghĩa Cube, Superset nhúng và analytics-api.

**Phương án đã cân nhắc.** Đọc từ bản sao PostgreSQL không đủ cho truy vấn tổng hợp trên hàng trăm triệu dòng; kho dữ liệu đám mây công cộng không phù hợp chủ quyền dữ liệu.

**Hệ quả.** Tích cực: tách hẳn tải phân tích. Tiêu cực: dữ liệu phân tích trễ vài giây; cần quản lý lược đồ hai nơi.

## ADR-15. OpenSearch cho tìm kiếm {-}

**Bối cảnh.** Cần tìm kiếm toàn văn tiếng Việt; Elasticsearch đổi giấy phép từ bản 7.11.

**Quyết định.** OpenSearch 2.x với bộ phân tích tiếng Việt.

**Phương án đã cân nhắc.** Tìm kiếm toàn văn của PostgreSQL thiếu phân tích tiếng Việt và xếp hạng tốt.

**Hệ quả.** Tích cực: giấy phép Apache 2.0. Tiêu cực: thêm một cụm cần vận hành.

## ADR-16. Chuỗi cung ứng phần mềm {-}

**Bối cảnh.** CI hiện tại không chặn khi kiểm thử lỗi, không có SBOM hay ký image (C10, J2, J8, D10).

**Quyết định.** Jenkins cho CI, Argo CD và Helm cho GitOps, Harbor, Cosign, Kyverno, SBOM CycloneDX, Trivy, Semgrep, gitleaks, Dependency-Track.

**Phương án đã cân nhắc.** GitLab CI phù hợp nhưng đội đã đầu tư Jenkins; Tekton đòi viết lại toàn bộ đường ống.

**Hệ quả.** Tích cực: chỉ chạy được image có nguồn gốc kiểm chứng. Tiêu cực: thêm thời gian đường ống khoảng 5 đến 8 phút.

## ADR-17. OpenBao và HSM cho bí mật và khoá {-}

**Bối cảnh.** Bí mật trong mã (S01); cần KEK cho mã hoá phong bì; HashiCorp Vault đổi giấy phép sang BSL.

**Quyết định.** OpenBao (nhánh MPL của Vault) với HSM qua PKCS#11 cho khoá gốc, External Secrets Operator đưa bí mật vào pod.

**Phương án đã cân nhắc.** Vault Enterprise tốn chi phí và giấy phép không phù hợp phân phối; Kubernetes Secrets đơn thuần không có xoay vòng, kiểm toán.

**Hệ quả.** Tích cực: bí mật động, kiểm toán truy cập. Tiêu cực: OpenBao là phụ thuộc quan trọng, cần cụm sẵn sàng cao.

## ADR-18. Valkey cho bộ đệm {-}

**Bối cảnh.** Redis đổi giấy phép năm 2024; cần bộ đệm phân tán theo cell.

**Quyết định.** Valkey 8 chế độ cluster, khoá có tiền tố tenant và module, không đệm C2, C3 dạng rõ.

**Phương án đã cân nhắc.** Redis bản cũ trước khi đổi giấy phép sẽ hết hỗ trợ; Hazelcast nặng hơn cho nhu cầu.

**Hệ quả.** Tích cực: tương thích giao thức Redis, giấy phép BSD. Tiêu cực: không.

## ADR-19. CEL và DMN cho bộ máy quy tắc {-}

**Bối cảnh.** Quy định đánh giá đổi theo quý, cần áp dụng trong vài ngày và tính lại được kỳ cũ.

**Quyết định.** CEL (cel-java) cho công thức, DMN 1.4 qua Flowable DMN cho bảng xếp loại, Policy Pack có phiên bản, bộ ca mẫu vàng, ký số.

**Phương án đã cân nhắc.** Drools bị loại vì ngôn ngữ quá mạnh và khó đọc; biểu thức JavaScript bị loại vì không giới hạn được chi phí và tác dụng phụ.

**Hệ quả.** Tích cực: nghiệp vụ đọc và kiểm được quy tắc. Tiêu cực: CEL có giới hạn biểu đạt, quy tắc phức tạp cần hàm mở rộng được duyệt.

## ADR-20. Strangler Fig với APISIX và lớp chống ô nhiễm {-}

**Bối cảnh.** Không thể dừng hệ thống hiện tại để thay thế.

**Quyết định.** APISIX định tuyến theo đường dẫn và theo tenant giữa hệ cũ và nền tảng mới; integration-gateway làm lớp chống ô nhiễm.

**Phương án đã cân nhắc.** Thay thế một lần bị loại vì rủi ro quá lớn.

**Hệ quả.** Tích cực: chuyển dần, quay lui được theo route. Tiêu cực: hai hệ thống chạy song song trong nhiều quý, cần đồng bộ dữ liệu hai chiều tạm thời.

**Trạng thái (cập nhật 23/10/2026).** Không áp dụng. Người phụ trách quyết định Mission là ứng dụng phát triển mới, không thay dần hệ hiện tại, nên không có định tuyến song song giữa hệ cũ và hệ mới, không đồng bộ hai chiều và không di trú dữ liệu từ Oracle. Lớp chống ô nhiễm của integration-gateway vẫn được giữ cho kết nối với hệ ngoài (giả lập trong giai đoạn phát triển theo YC-PT-13).

## ADR-21. OPA cùng pod với đánh giá từng phần {-}

**Bối cảnh.** Kiểm tra quyền phân tán (S14); uỷ quyền phụ thuộc cây tổ chức; Istio ambient không có sidecar nên không có điểm chặn L7 trong pod.

**Quyết định.** OPA chạy như container trong cùng pod với service, gọi qua localhost, bundle chính sách ký số; dùng đánh giá từng phần để sinh điều kiện SQL cho danh sách; lỗi thì từ chối.

**Phương án đã cân nhắc.** Dịch vụ quyết định tập trung thêm một vòng mạng và một điểm lỗi chung; nhúng thư viện chính sách trong Java (như Cedar Java) làm chính sách gắn với vòng đời phát hành của service.

**Hệ quả.** Tích cực: 0,3 đến 1 ms mỗi quyết định, chính sách tập trung trong Git. Tiêu cực: thêm khoảng 50 đến 100 MB bộ nhớ mỗi pod.

## ADR-22. PgBouncer chế độ giao dịch với ngữ cảnh tenant cục bộ giao dịch {-}

**Bối cảnh.** Hàng nghìn kết nối phía ứng dụng cần ghép vào khoảng 80 kết nối CSDL; RLS dựa trên biến phiên.

**Quyết định.** PgBouncer chế độ giao dịch; ngữ cảnh tenant đặt bằng `set_config('app.tenant_id', ?, true)` trong mỗi giao dịch; RLS `FORCE`; tài khoản ứng dụng không có `BYPASSRLS`.

**Phương án đã cân nhắc.** Chế độ phiên của PgBouncer giữ biến phiên an toàn nhưng không ghép kết nối hiệu quả; đặt biến bằng `SET` không có `LOCAL` gây rò ngữ cảnh giữa tenant.

**Hệ quả.** Tích cực: ghép kết nối hiệu quả và cách ly đúng. Tiêu cực: không dùng được câu lệnh chuẩn bị sẵn phía máy chủ (prepared statements) qua PgBouncer bản cũ; dùng PgBouncer 1.21 trở lên có hỗ trợ.

## ADR-23. Event Publication Registry cho sự kiện trong tiến trình {-}

**Bối cảnh.** Module trong lõi giao tiếp bằng sự kiện ứng dụng; cần bảo đảm listener chạy cả khi tiến trình khởi động lại.

**Quyết định.** Dùng Event Publication Registry của Spring Modulith (bảng trong schema `modulith`) cho sự kiện giữa module trong tiến trình; sự kiện ra ngoài tiến trình luôn qua outbox và Kafka.

**Phương án đã cân nhắc.** Đưa mọi sự kiện nội bộ qua Kafka tăng độ trễ và tải không cần thiết.

**Hệ quả.** Tích cực: đơn giản cho giao tiếp trong lõi, sẵn đường tách module ra service. Tiêu cực: hai cơ chế sự kiện cần quy tắc rõ.

## ADR-24. Cấu hình chịu lỗi chuẩn qua thư viện nền {-}

**Bối cảnh.** F1 đến F5 không đạt vì mỗi chỗ tự xử lý.

**Quyết định.** Thư viện `vt-platform-starter` cung cấp cấu hình Resilience4j, timeout, bulkhead, `ProblemDetail` theo RFC 9457, idempotency và che log mặc định; service chỉ ghi đè có lý do.

**Phương án đã cân nhắc.** Để chịu lỗi cho service mesh; ambient mode không có L7 trong pod và retry ở mesh không biết thao tác nào idempotent.

**Hệ quả.** Tích cực: hành vi đồng nhất. Tiêu cực: thư viện nền phải được nâng cấp đồng bộ.

## ADR-25. Liên kết phiên bản UI Pack và Policy Pack {-}

**Bối cảnh.** Biểu mẫu tự đánh giá (UI Pack) phải khớp đầu vào của quy tắc (Policy Pack) của cùng kỳ.

**Quyết định.** UI Pack khai báo dải phiên bản Policy Pack tương thích; công cụ phát hành kiểm trường biểu mẫu khớp `inputs` của gói quy tắc.

**Phương án đã cân nhắc.** Gộp hai gói làm mọi thay đổi giao diện phải qua phê duyệt quy tắc.

**Hệ quả.** Tích cực: thay giao diện độc lập nhưng an toàn. Tiêu cực: thêm một kiểm tra khi phát hành.

## ADR-26. Cây Merkle hai cấp và chuẩn hoá RFC 8785 {-}

**Bối cảnh.** Ký và gọi TSA cho từng tenant mỗi giờ là 5.000 lần mỗi giờ.

**Quyết định.** Gốc tenant làm lá cho cây cấp cell; ký và lấy dấu thời gian một lần mỗi cell mỗi giờ; dữ liệu băm chuẩn hoá theo RFC 8785 với chuỗi ở dạng NFC; tiền tố miền phân biệt băm lá và nút.

**Phương án đã cân nhắc.** Ký từng tenant; băm dạng nhị phân riêng khó triển khai trên nhiều ngôn ngữ.

**Hệ quả.** Tích cực: khoảng 10 lần ký mỗi giờ toàn quốc. Tiêu cực: kiểm chứng một tenant cần thêm đường đi Merkle cấp cell.

## ADR-27. Mã hoá đầu cuối C3 với khoá thiết bị và phục hồi tổ chức {-}

**Bối cảnh.** Dữ liệu hạn chế cần bảo vệ trước nhà vận hành, nhưng không được mất khi người dùng mất thiết bị.

**Quyết định.** Khoá ECDH P-256 không trích xuất được trên từng thiết bị, CEK bọc cho từng người nhận và cho khoá phục hồi tổ chức chia Shamir 3 trên 5; PDF chứa C3 sinh và ký trên băm tại thiết bị.

**Phương án đã cân nhắc.** Chỉ dùng khoá trong chứng thư số cá nhân không áp dụng được cho người dùng chỉ có ký số từ xa.

**Hệ quả.** Tích cực: máy chủ không đọc được C3. Tiêu cực: không tìm kiếm, phân tích, AI trên C3; quy trình phục hồi cần ba người.

## ADR-28. pgvector cho truy xuất tăng cường {-}

**Bối cảnh.** Trợ lý AI cần tìm kiếm ngữ nghĩa trên văn bản quy định và văn bản của tenant.

**Quyết định.** pgvector với chỉ mục HNSW trong schema `ai`; chuyển sang OpenSearch k-NN khi vượt khoảng 50 triệu vector mỗi cell hoặc cần độ trễ dưới 20 ms ở tải cao.

**Phương án đã cân nhắc.** CSDL vector chuyên dụng thêm một công nghệ cho khối lượng chưa cần.

**Hệ quả.** Tích cực: dùng lại PostgreSQL và RLS. Tiêu cực: phải theo dõi ngưỡng chuyển.

## ADR-29. Ngưỡng kích thước cell và chuyển tenant bằng sao chép logic {-}

**Bối cảnh.** Một cụm PostgreSQL có giới hạn ghi; cell quá lớn tăng bán kính sự cố.

**Quyết định.** Chia cell khi vượt 1,5 triệu tài khoản hoặc 75.000 người dùng đồng thời; chuyển tenant bằng sao chép logic có bộ lọc dòng, chỉ đọc 5 đến 15 phút.

**Phương án đã cân nhắc.** Xuất nhập toàn bộ dữ liệu cần dừng dài.

**Hệ quả.** Tích cực: mở rộng có kiểm soát. Tiêu cực: quy trình chuyển cần diễn tập định kỳ.

## ADR-30. DEK theo tenant, tháng và mục đích với chỉ mục mù {-}

**Bối cảnh.** Cần cân bằng giữa số khoá, hiệu quả bộ đệm và phạm vi thiệt hại khi lộ một khoá; cần tra cứu chính xác trên trường C2.

**Quyết định.** Một DEK cho mỗi tổ hợp tenant, tháng, mục đích; bộ đệm 15 phút; AAD gồm tenant, cột và id dòng; chỉ mục mù HMAC-SHA256 cắt 16 byte cho trường cần tra cứu.

**Phương án đã cân nhắc.** DEK mỗi dòng làm bộ đệm vô dụng; DEK mỗi tenant vĩnh viễn làm phạm vi lộ lớn.

**Hệ quả.** Tích cực: khoảng 36 DEK mỗi tenant mỗi năm. Tiêu cực: chỉ mục mù lộ tính bằng nhau của giá trị.

## ADR-31. Ký lô quyết định xếp loại {-}

**Bối cảnh.** Lãnh đạo quyết định xếp loại cho hàng trăm, hàng nghìn người cuối kỳ; ký từng phiếu không khả thi.

**Quyết định.** Ký một lần cho danh sách tới 1.000 phiếu, mỗi phiếu là một mục băm trong cấu trúc được ký; dấu thời gian theo lô.

**Phương án đã cân nhắc.** Ký từng phiếu tốn 100.000 lần xác nhận mỗi cell.

**Hệ quả.** Tích cực: khoảng 100 phiên xác nhận mỗi cell. Tiêu cực: kiểm chứng một phiếu cần kèm danh sách băm của lô.

## ADR-32. Hợp đồng API trước cho toàn bộ API công khai {-}

**Bối cảnh.** Mục "Hợp đồng API theo OpenAPI" ban đầu chọn hướng "mã trước, hợp đồng kiểm tra" cho phần lõi. Khi phân hệ web được nhiều luồng phát triển song song trong lúc backend chưa có mã, hướng này buộc frontend tự đoán đường dẫn và tên trường, và các chỗ TL ghi khác nhau chỉ lộ ra khi ghép nối.

**Quyết định.** Mọi API công khai dưới `/api/v1` (trừ API đối tác của SVC-13) được mô tả trước bằng OpenAPI 3.1 trong `contracts/openapi/`, mỗi module hoặc service một tệp. Frontend sinh kiểu TypeScript bằng openapi-typescript, backend sinh interface controller bằng openapi-generator; springdoc chỉ còn dùng để đối chiếu ngược trong CI. Mỗi thao tác có phần mở rộng `x-mission` để truy vết QT, SCR, owner, EVT, vai trò, phạm vi, AAL, bốn mắt và BR. Chi tiết ở Phụ lục F.

**Phương án đã cân nhắc.** Giữ "mã trước" làm frontend phải chờ backend; viết hợp đồng riêng cho từng màn hình làm trùng lặp và lệch nhau giữa các luồng.

**Hệ quả.** Tích cực: một bộ đường dẫn và tên trường chung, dữ liệu giả lập và kiểm thử hợp đồng sinh từ `examples`. Tiêu cực: mỗi thay đổi API cần PR sửa hợp đồng và chờ duyệt; khi mã lệch hợp đồng thì sửa mã (YC-PT-09). Trạng thái: đã chấp nhận ngày 19/10/2026.

## ADR-33. Hồ sơ tập khách hàng trên một lõi chung {-}

**Bối cảnh.** Policy Pack chỉ phân biệt hai luồng `DANG` và `NHA_NUOC` với một tập đầu vào cố định. Khi đối chiếu với giải pháp KPI của UEB cho Ban Tổ chức Trung ương, có sáu nhóm nghiệp vụ không biểu diễn được bằng cấu hình hiện có, vì lõi chưa cung cấp đủ dữ liệu thô.

**Quyết định.** Tách ba lớp: lớp lõi chỉ cung cấp sự kiện và đại lượng thô; lớp cấu hình gồm Policy Pack, danh mục, mẫu luồng, bộ quy tắc cảnh báo và UI Pack, đều có phiên bản; lớp hồ sơ tập khách hàng là bản kê có phiên bản trỏ tới đúng phiên bản của từng thành phần cấu hình. Lõi bổ sung sáu điểm cắm DC-01 đến DC-06, mặc định giữ hành vi cũ. Chi tiết ở Phụ lục G.

**Phương án đã cân nhắc.** Viết thẳng cách chấm của từng khách hàng vào mã làm mất khả năng phục vụ các tập khách hàng khác.

**Hệ quả.** Tích cực: thêm hồ sơ mới là việc cấu hình và ký bộ ca vàng. Tiêu cực: mở rộng `inputs` của scoring và một số cột ở TL-03. Tiêu chí nghiệm thu: cùng một bản build lõi đạt 100 phần trăm ca vàng của ít nhất hai hồ sơ; không có nhánh mã theo tên hồ sơ. Trạng thái: đã chấp nhận ngày 22/10/2026.

# Phụ lục B. Danh mục công nghệ {-}

Bảng dưới liệt kê công nghệ của kiến trúc đích, phiên bản tham chiếu tại thời điểm thiết kế (tháng 10/2026), giấy phép và lý do chọn. Phiên bản cụ thể khi triển khai theo chính sách nâng cấp của TL-04: chỉ dùng bản còn được hỗ trợ, cập nhật bản vá trong 30 ngày, bản vá an ninh nghiêm trọng trong 7 ngày.

| Nhóm | Công nghệ | Phiên bản | Giấy phép | Lý do |
|---|---|---|---|---|
| Ngôn ngữ, runtime | Java (Eclipse Temurin) | 21 LTS | GPLv2 với ngoại lệ Classpath | LTS, luồng ảo, đội ngũ sẵn có |
| Ngôn ngữ, runtime | Go | 1.23 | BSD-3 | Service nhẹ, nhiều kết nối (SSE, sổ cái) |
| Ngôn ngữ, runtime | Python | 3.12 | PSF | Hệ sinh thái AI |
| Framework | Spring Boot | 3.5 | Apache 2.0 | Chuẩn của đội |
| Framework | Spring Modulith | 1.4 | Apache 2.0 | Ranh giới module, sự kiện bền |
| Framework | Hibernate ORM | 6.6 | Apache 2.0 (từ 6.x) | JPA |
| Framework | Resilience4j | 2.3 | Apache 2.0 | Chịu lỗi |
| Framework | Apache Camel | 4.x LTS | Apache 2.0 | Tích hợp |
| Framework | FastAPI | 0.11x | MIT | API AI |
| Quy tắc | cel-java, cel-js | Bản ổn định hiện hành | Apache 2.0 | Biểu thức an toàn |
| Quy trình | Flowable | 7.x | Apache 2.0 | BPMN, DMN mã nguồn mở |
| Frontend | Angular | 21 | MIT | Chuẩn của đội |
| Frontend | Native Federation | Theo Angular 21 | MIT | Micro-frontend |
| Frontend | PrimeNG | 21 | MIT | Bộ component |
| Frontend | ngx-formly | 10 (cập nhật 10/2026, khớp Angular 21) | MIT | Biểu mẫu động |
| Di động | Flutter | 3.x | BSD-3 | Một mã cho hai nền tảng |
| CSDL | PostgreSQL | 17 | PostgreSQL License | ADR-02 |
| CSDL | CloudNativePG | 1.2x | Apache 2.0 | Vận hành PostgreSQL trên Kubernetes |
| CSDL | PgBouncer | 1.23 | ISC | Ghép kết nối |
| CSDL | pgvector | 0.8 | PostgreSQL License | Vector |
| Di trú lược đồ | Flyway Community | 11 | Apache 2.0 | Migration có phiên bản |
| Sự kiện | Apache Kafka | 3.9 hoặc 4.x (KRaft) | Apache 2.0 | ADR-03 |
| Sự kiện | Debezium | 3.x | Apache 2.0 | CDC, outbox |
| Sự kiện | Apicurio Registry | 3.x | Apache 2.0 | Lược đồ sự kiện |
| Bộ đệm | Valkey (thay Redis) | 8.x | BSD-3 | Redis đổi sang RSAL/SSPL năm 2024 |
| Tìm kiếm | OpenSearch (thay Elasticsearch) | 2.x | Apache 2.0 | Elasticsearch đổi giấy phép từ 7.11 |
| Phân tích | ClickHouse | 24.x LTS | Apache 2.0 | Kho cột |
| Phân tích | Cube | Bản cộng đồng | Apache 2.0 | Lớp ngữ nghĩa |
| Phân tích | Apache Superset | 4.x | Apache 2.0 | Dashboard nhúng |
| Lưu trữ | Ceph RGW | Squid 19.x | LGPL 2.1/3 | ADR-13 |
| Định danh | Keycloak | 26 | Apache 2.0 | ADR-08 |
| Uỷ quyền | Open Policy Agent | 1.x | Apache 2.0 | ADR-21 |
| Bí mật | OpenBao (thay Vault) | 2.x | MPL 2.0 | Vault đổi sang BSL năm 2023 |
| Bí mật | External Secrets Operator | 0.1x | Apache 2.0 | Đưa bí mật vào pod |
| Cổng | Apache APISIX | 3.x LTS | Apache 2.0 | Cổng, Strangler |
| Cổng | Coraza WAF, OWASP CRS | 3.x, 4.x | Apache 2.0 | WAF |
| Lưới dịch vụ | Istio ambient | 1.2x | Apache 2.0 | ADR-11 |
| Điều phối | Kubernetes | Bản được hỗ trợ hiện hành | Apache 2.0 | Nền chạy |
| Co giãn | KEDA | 2.x | Apache 2.0 | Co giãn theo sự kiện |
| GitOps | Argo CD, Argo Rollouts | 2.x, 1.x | Apache 2.0 | Triển khai |
| CI | Jenkins | LTS | MIT | Chuẩn của đội |
| Kho image | Harbor | 2.x | Apache 2.0 | Kho image, quét |
| Chuỗi cung ứng | Cosign, Kyverno, Trivy, Syft | Bản hiện hành | Apache 2.0 | Ký, chính sách, quét, SBOM |
| Chất lượng | SonarQube Community, Semgrep CE, gitleaks | Bản hiện hành | LGPL, LGPL, MIT | Phân tích tĩnh |
| Kiểm thử | JUnit 5, ArchUnit 1.4, Testcontainers, jqwik, PIT, Pact, Playwright, k6 | Bản hiện hành | EPL, Apache 2.0, MIT, AGPL (k6 chỉ dùng nội bộ) | Kiểm thử |
| Quan sát | OpenTelemetry, Tempo, Prometheus, Thanos, Loki, Grafana, Faro | Bản hiện hành | Apache 2.0, AGPL (Grafana, Loki, Tempo chạy như dịch vụ nội bộ, không phân phối lại) | ADR-12 |
| Log | Log4j2 | 2.24 | Apache 2.0 | Log JSON bất đồng bộ |
| Tài liệu | docx4j, OpenPDF, Gotenberg | 11, 2.x, 8.x | Apache 2.0, LGPL/MPL, Apache 2.0 | Sinh biểu mẫu, không dùng iText 7 vì AGPL |
| AI | vLLM | Bản hiện hành | Apache 2.0 | Phục vụ mô hình tự lưu trữ |

Bốn thay thế do giấy phép cần lưu ý. Redis được thay bằng Valkey vì từ năm 2024 Redis chuyển sang giấy phép kép RSAL và SSPL (bản 8 bổ sung AGPL), không phù hợp đóng gói trong bản cài cho khách hàng. Elasticsearch được thay bằng OpenSearch vì lý do tương tự từ bản 7.11. HashiCorp Vault được thay bằng OpenBao vì Vault chuyển sang BSL năm 2023. iText 7 không được dùng vì AGPL đòi công bố mã nguồn của sản phẩm phân phối hoặc mua giấy phép thương mại. Các thành phần AGPL còn lại trong bảng (Grafana, Loki, Tempo, k6) chỉ chạy như công cụ nội bộ của nhà vận hành, không được sửa đổi và không phân phối kèm sản phẩm; nếu bản cài tại chỗ cần kèm chúng, phải đánh giá lại theo quy trình pháp chế. Camunda 8 không được chọn vì giấy phép tự lưu trữ cho sản xuất của Zeebe không còn miễn phí, và Camunda 7 bản cộng đồng hết hỗ trợ tháng 10/2025.

# Phụ lục C. Danh mục API theo module {-}

> Cập nhật 10/2026: theo ADR-32, danh mục API có hiệu lực là bộ hợp đồng OpenAPI trong `contracts/openapi/` của repo mission-web. Bảng dưới đây được giữ để tra cứu thiết kế ban đầu; khi bảng khác hợp đồng (ví dụ `knownAt` nay là `recordedAsOf`, quyết định gợi ý AI là `POST /ai/suggestions/{id}/decision`), hợp đồng thắng. Các điểm đã chốt khi ghép nguồn nằm ở Phụ lục F.

Phụ lục này liệt kê các endpoint chính của nền tảng theo module. Mọi đường dẫn có tiền tố `/api/v1`. Cột quyền ghi vai trò cơ bản trong token; quyết định cuối cùng do OPA đưa ra theo chính sách ABAC (chương 16), ví dụ vai trò `manager` chỉ có hiệu lực trên đơn vị mình quản lý. Các vai trò dùng trong bảng gồm `staff` (cán bộ), `manager` (lãnh đạo đơn vị, người giao việc), `appraiser` (người thẩm định), `decider` (người quyết định xếp loại), `second_approver` (người duyệt thứ hai), `inspector` (giám sát, thanh tra), `org_officer` (cán bộ tổ chức), `policy_author`, `policy_approver`, `tenant_admin`, `platform_admin`. Thao tác đánh dấu "AAL2" yêu cầu nâng bậc xác thực. Hợp đồng đầy đủ nằm trong tệp OpenAPI của từng module.

## Mục tiêu và OKR (MOD-01) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/okr/objectives?period=&unit=&cursor=` | Danh sách mục tiêu theo kỳ, đơn vị | `staff` trong phạm vi |
| POST | `/okr/objectives` | Tạo mục tiêu, liên kết căn cứ và mục tiêu cấp trên | `staff`, `manager` |
| PATCH | `/okr/objectives/{id}` | Sửa mục tiêu ở trạng thái nháp | Người tạo |
| POST | `/okr/objectives/{id}/submit` | Trình duyệt mục tiêu | Người tạo |
| POST | `/okr/key-results` | Tạo kết quả then chốt cho mục tiêu | Người tạo mục tiêu |
| POST | `/okr/key-results/{id}/checkins` | Check-in giá trị và mức tự tin | Người phụ trách KR |
| GET | `/okr/alignment?objective=` | Cây liên kết dọc và ngang | `staff` trong phạm vi |

## Giao việc và KPI (MOD-02) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/catalog/items?unit=&q=` | Danh mục công việc của đơn vị | `staff` |
| POST | `/catalog/items` | Thêm mục danh mục | `manager`, `org_officer` |
| POST | `/plans` | Tạo kế hoạch quý có phiên bản | `manager` |
| POST | `/plans/{id}/submit` | Trình duyệt kế hoạch | `manager` |
| POST | `/plans/{id}/approve` | Duyệt kế hoạch, gọi bởi workflow-service | Client nội bộ, AAL2 với người duyệt |
| GET | `/tasks?assignee=me&status=&cursor=` | Việc của tôi | `staff` |
| GET | `/tasks?unit=&period=&cursor=` | Việc của đơn vị | `manager` |
| POST | `/tasks` | Giao nhiệm vụ, chủ trì và phối hợp | `manager` |
| GET | `/tasks/{id}` | Chi tiết nhiệm vụ | Người được giao, người giao, cấp trên |
| PATCH | `/tasks/{id}/progress` | Cập nhật tiến độ, có `Idempotency-Key` và `If-Match` | Người được giao |
| POST | `/tasks/{id}/transitions` | Chuyển trạng thái theo máy trạng thái | Theo bảng chuyển trạng thái |
| POST | `/tasks/{id}/extensions` | Đề nghị gia hạn | Người được giao |
| POST | `/tasks/{id}/blockers` | Báo điểm nghẽn | Người được giao |

## Sản phẩm và minh chứng (MOD-03, SVC-08) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| POST | `/products` | Khai báo sản phẩm của nhiệm vụ | Người được giao |
| POST | `/products/{id}/evidence-versions` | Nộp phiên bản minh chứng tham chiếu `objectId` | Người được giao |
| POST | `/evidence-versions/{id}/acceptance` | Nghiệm thu với mức chất lượng 5 mức | `manager`, AAL2 |
| POST | `/evidence-versions/{id}/supplement-requests` | Yêu cầu bổ sung | `manager` |
| POST | `/files/uploads` | Khởi tạo phiên tải lên | `staff` |
| PUT | `/files/uploads/{uploadId}/parts/{n}` | Gửi một phần | Người khởi tạo |
| POST | `/files/uploads/{uploadId}/complete` | Hoàn tất, trả băm | Người khởi tạo |
| GET | `/files/{objectId}/download-url` | URL ký có hạn 5 phút | Theo quyền trên sản phẩm |

## Đánh giá, xếp loại (MOD-04, SVC-07) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/appraisals?period=&flow=` | Hồ sơ đánh giá của tôi theo luồng | `staff` |
| POST | `/appraisals/{id}/self-assessment` | Nộp tự đánh giá, khởi động ký | Chủ hồ sơ, AAL2 |
| POST | `/appraisals/{id}/review` | Kết quả thẩm định | `appraiser`, AAL2 |
| POST | `/appraisals/{id}/decision` | Quyết định xếp loại, trả 202 chờ ký | `decider`, AAL2 |
| POST | `/appraisals/decisions/batch` | Quyết định và ký lô tới 1.000 hồ sơ | `decider`, AAL2 |
| GET | `/appraisals/{id}/score` | Điểm, snapshot và giải thích | Chủ hồ sơ, người thẩm định, người quyết định |
| GET | `/appraisals/inputs?period=&cursor=` | Đầu vào tính điểm theo lô | Client nội bộ scoring-service |
| POST | `/appraisals/{id}/recompute` | Tính lại và đối chiếu snapshot | `inspector` |

## Policy Pack (MOD-05) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/policies` | Danh sách gói và phiên bản | `policy_author`, `tenant_admin` |
| POST | `/policies/{id}/versions` | Tạo phiên bản nháp | `policy_author` |
| POST | `/policies/{id}/versions/{v}/test-runs` | Chạy kiểm thử, bộ ca mẫu vàng, so sánh tác động | `policy_author` |
| POST | `/policies/{id}/versions/{v}/submit` | Trình phê duyệt | `policy_author` |
| POST | `/policies/{id}/versions/{v}/publish` | Phê duyệt, ký số, phát hành | `policy_approver` khác người soạn, AAL2 |
| GET | `/policies/resolve?tenant=&period=&flow=` | Gói hiệu lực cho kỳ | Client nội bộ |

## Kiểm điểm và hồ sơ số (MOD-06, SVC-11) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| POST | `/dossiers/meetings` | Tạo hội nghị kiểm điểm | `org_officer` |
| POST | `/dossiers/meetings/{id}/comments` | Ghi góp ý | Thành phần hội nghị |
| POST | `/dossiers/meetings/{id}/minutes` | Lập biên bản, khởi động ký | Thư ký hội nghị, AAL2 |
| POST | `/dossiers/{id}/packages` | Đóng gói hồ sơ quý, năm | `org_officer` |
| POST | `/dossiers/render-jobs` | Yêu cầu sinh biểu mẫu, trả 202 | Hệ thống, `org_officer` |
| GET | `/jobs/{id}` | Trạng thái tác vụ dài | Người khởi tạo |

## Giám sát, hậu kiểm (MOD-07) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| POST | `/oversight/complaints` | Gửi khiếu nại kết quả | Chủ hồ sơ |
| POST | `/oversight/complaints/{id}/responses` | Trả lời khiếu nại | `decider` cấp trên |
| GET | `/oversight/anomalies?period=&unit=` | Danh sách dấu hiệu bất thường | `inspector` |
| POST | `/oversight/anomalies/{id}/resolution` | Ghi kết luận xem xét | `inspector` |
| POST | `/oversight/reopen-requests` | Đề nghị mở lại kỳ | `org_officer`, AAL2 |
| POST | `/oversight/reopen-requests/{id}/approve` | Duyệt mở lại, người thứ hai | `second_approver` khác người đề nghị, AAL2 |
| GET | `/oversight/ledger/proofs?event=` | Bằng chứng bao hàm của một sự kiện | `inspector` |

## Cấu hình nền tảng (MOD-08) {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/periods?year=` | Danh sách kỳ và trạng thái | `staff` |
| POST | `/periods/{id}/lock` | Khởi động saga khoá kỳ | `org_officer`, AAL2 |
| GET | `/ui/packs/current` | UI Pack hiệu lực của tenant | `staff` |
| POST | `/ui/packs/{id}/versions/{v}/publish` | Phát hành UI Pack | `tenant_admin`, AAL2 |
| GET | `/config/calendar?year=` | Lịch ngày làm việc | `staff` |
| PUT | `/config/feature-flags/{key}` | Bật tắt tính năng theo tenant | `tenant_admin` |

## Dịch vụ nền {-}

| Phương thức | Đường dẫn | Mô tả | Quyền |
|---|---|---|---|
| GET | `/registry/resolve?tenant=` | Tra cell của tenant | Client nội bộ, shell |
| POST | `/registry/tenants` | Tiếp nhận tenant | `platform_admin` |
| POST | `/registry/tenants/{id}/move` | Chuyển tenant sang cell khác | `platform_admin`, hai người duyệt |
| GET | `/org/units/{id}?asOf=&knownAt=` | Đơn vị tại thời điểm, hai trục thời gian | `staff` trong phạm vi |
| GET | `/org/units/{id}/subtree` | Cây đơn vị cấp dưới | `manager` |
| GET | `/workflow/inbox?assignee=me` | Hộp việc cần duyệt | `staff` |
| POST | `/workflow/tasks/{taskId}/complete` | Hoàn thành user task | Người được gán |
| POST | `/workflow/tasks/batch-complete` | Duyệt hàng loạt tới 200 mục | Người được gán |
| POST | `/workflow/sagas/period-close` | Saga chốt kỳ | Client nội bộ |
| POST | `/esign/sessions` | Mở phiên ký từ xa | Chủ thể ký, AAL2 |
| GET | `/esign/sessions/{id}` | Trạng thái phiên ký | Chủ thể ký |
| GET | `/notifications/stream` | Luồng SSE thông báo | `staff` |
| GET | `/search?q=&type=` | Tìm kiếm toàn văn | `staff`, kiểm quyền lại ở lõi |
| POST | `/ai/suggestions` | Yêu cầu gợi ý AI chỉ đọc | `staff` |
| GET | `/analytics/dashboards/{id}?period=&unit=` | Dữ liệu bảng điều hành | `manager`, `inspector` |

# Phụ lục D. Cập nhật sau triển khai đợt 1 (10/2026) {-}

Phụ lục này ghi lại những điểm mà mã nguồn trên nhánh `main` của repo mission-web, tính đến ngày 23/10/2026, đã hiện thực khác với nội dung TL-01 phiên bản 1.0, cùng với các quyết định mà người phụ trách đã chốt trong quá trình triển khai. Nguồn đối chiếu gồm mã nguồn, bộ hợp đồng `contracts/openapi/` và `contracts/events/catalog.yaml`, trang wiki 14 "Quyết định và câu hỏi mở", cùng ba tài liệu bổ sung BS-01, BS-02, BS-03 nay đã được nhập vào Phụ lục E, F và G của tài liệu này.

Nguyên tắc xử lý như sau. Khi TL-01 và một tài liệu khác (TL-02, TL-03, TL-04) mâu thuẫn nhau, phụ lục này ghi theo mã đang chạy và nêu rõ chỗ mâu thuẫn. Mỗi dòng có một trong ba trạng thái. "Đã chốt" nghĩa là người phụ trách hoặc luồng điều phối đã quyết định và nội dung chương tương ứng được hiểu theo dòng này. "Theo mã, chờ xác nhận" nghĩa là luồng triển khai đã chọn phương án mặc định có lý do, mã chạy theo phương án đó, nhưng chưa có quyết định chính thức. "Câu hỏi mở" nghĩa là cả thiết kế lẫn mã đều chưa có câu trả lời đầy đủ. Mã định danh (ADR, SVC, MOD, EVT, QT, SCR, BR, MSG) giữ nguyên như baseline; phụ lục không đặt mã mới ngoài các mã đã được cấp trong BS-02, BS-03 và WEB-058. Nội dung suy ra mà nguồn không ghi trực tiếp được đánh dấu "(suy luận)". Theo YC-PT-19, các chỉ tiêu hiệu năng trong giai đoạn này chỉ được ghi lại, chưa đo ở tải cao.

## D.1. Quyết định kiến trúc bổ sung {-}

Trong đợt triển khai đầu, hai quyết định kiến trúc mới đã được chấp nhận và nay có hồ sơ đầy đủ ở Phụ lục A. ADR-32 chuyển toàn bộ API công khai sang hướng hợp đồng trước: bộ tệp OpenAPI 3.1 trong `contracts/openapi/` là nguồn sự thật, frontend sinh kiểu TypeScript, backend sinh interface controller, và springdoc chỉ còn vai trò đối chiếu ngược trong CI. Quyết định này thay câu "Lõi theo hướng mã trước, hợp đồng kiểm tra" ở mục "Hợp đồng API theo OpenAPI" của chương tích hợp. ADR-33 đưa vào khái niệm hồ sơ tập khách hàng với sáu điểm cắm DC-01 đến DC-06, để khác biệt giữa các tập khách hàng (ví dụ hồ sơ TWĐ cho Ban Tổ chức Trung ương) nằm hoàn toàn trong cấu hình, không nằm trong mã lõi. Ngoài hai ADR này, quy tắc "sửa mã, không sửa hợp đồng" (YC-PT-09) được áp dụng nhất quán: khi mã và hợp đồng lệch nhau, mã được sửa cho khớp hợp đồng, còn thay đổi hợp đồng phải đi qua PR riêng.

Hệ quả trực tiếp của ADR-32 là Phụ lục C không còn là danh mục API có hiệu lực. Bảng ở Phụ lục C được giữ để tra cứu lịch sử thiết kế, còn danh mục API chính thức là bộ hợp đồng, hiện gồm 11 tệp module và hơn 351 thao tác ở bản nháp 1.0.0-draft.1, sau đó được bổ sung qua NEN-042, NEN-043, NEN-044. Mỗi thao tác mang phần mở rộng `x-mission` (QT, SCR, owner, EVT, vai trò, phạm vi, AAL, bốn mắt, BR, dòng TL nguồn) để chuỗi truy vết được kiểm tự động.

## D.2. Phân hệ web (SVC-17) {-}

Phần lớn kiến trúc frontend ở chương 7 (G1) và chương "Thiết kế chi tiết từng thành phần" được hiện thực đúng thiết kế: shell và remote theo Native Federation, Design Token theo tenant, SDUI có registry, biểu mẫu động bằng Formly và JSON Schema, plugin có kiểm soát. Các điểm khác biệt dưới đây chủ yếu là lựa chọn thư viện cụ thể và cách hiện thực các ràng buộc CSP.

| Chủ đề | TL-01 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| Trình soạn và hiển thị sơ đồ luồng (SCR-1504, SCR-1505) | BS-01 đề xuất nhúng trình soạn BPMN (bpmn-js) | ngx-vflow 2.7, dựng sơ đồ từ mô tả luồng đã kiểm, không nhúng trình soạn BPMN tự do | Đã chốt 22/10/2026 |
| Thư viện biểu mẫu động | ngx-formly 6.x (Phụ lục B) | Formly 10, khớp Angular 21 | Đã chốt |
| Đánh giá biểu thức CEL ở trình duyệt | cel-js | `@marcbachmann/cel-js` 8.0.0 cho bộ đánh giá dùng chung `evaluateMissionCel`; máy chủ vẫn là nguồn sự thật | Đã chốt |
| Kiểm JSON Schema ở trình duyệt | Ajv biên dịch sẵn schema | Bộ kiểm tự viết, vì Ajv chế độ mặc định sinh mã lúc chạy, trái CSP | Đã chốt |
| Chính sách Trusted Types | Một chính sách mặc định | Thêm hai chính sách có tên `native-federation` và `es-module-shims` để nạp remote; plugin nạp qua `vt-federation` | Đã chốt |
| Thư viện dùng chung `@vt/*` | Danh sách ở baseline mục 5.2 | Thêm `@vt/api-client` (mọi lời gọi API) và `@vt/e2ee` (luồng C3) | Đã chốt |
| Nhúng vào hệ thống khác | Chưa đặc tả | Phần tử tuỳ biến `<vt-okr-app>` dựng bằng Angular Elements, kiểm soát bằng `frame-ancestors` | Theo mã, chờ xác nhận |
| Bảng điều hành (SCR-1601) | Superset nhúng | Giao diện vẽ biểu đồ SVG từ `POST /analytics/query`; Superset nhúng (DV-15-05) chỉ dùng cho phân tích tự do | Theo mã, chờ xác nhận |

## D.3. Giao tiếp API và sự kiện {-}

Quy ước API ở chương 14 và baseline mục 13 được giữ, với một số điểm đã chốt khi viết hợp đồng. Xung đột phiên bản khi cập nhật trả 409 (không dùng 412), trừ giao diện SCIM theo chuẩn RFC 7644 vẫn dùng 412. Thời hạn giữ `Idempotency-Key` là 48 giờ. Kiểu lỗi RFC 9457 dùng `type` dạng `https://okrkpi.vn/problems/<slug>`, còn mã BR, MSG nằm ở trường mở rộng. Cập nhật tiến độ nhiệm vụ là `POST` (mỗi lần cập nhật là một bản ghi mới), danh sách việc của tôi là `/tasks/mine`, và tham số thời điểm ghi nhận của API tổ chức hai trục thời gian là `recordedAsOf` (thay `knownAt`). Tiền tố `/registry` của tenant-registry và tiền tố nội bộ `/internal/v1/...` cho lời gọi giữa service là bổ sung so với baseline mục 13.

| Chủ đề | TL-01 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| Danh mục sự kiện | 26 sự kiện EVT-01 đến EVT-26 | Thêm EVT-27 `VaiTroDaThayDoi` (`config.access.changed.v1`, MOD-08 phát) cho quản trị vai trò và uỷ quyền (WEB-058); danh mục máy đọc được ở `contracts/events/catalog.yaml` | Đã chốt |
| Người tiêu thụ EVT-06, EVT-24 | Không có SVC-16 | SVC-16 tiêu thụ EVT-04, EVT-06, EVT-24 để dựng chỉ mục tìm kiếm | Theo mã, chờ xác nhận |
| Sự kiện nội bộ của mission-core | Bảng outbox | Event Publication Registry của Spring Modulith (ADR-23); tắt phát lại khi khởi động, tự phát lại theo tenant | Đã chốt |
| Topic kỹ thuật | `iam.audit.v1` và tiền tố `sys.` | `iam.auth.event.v1` cho sự kiện Keycloak; thêm `sys.siem.v1`, `sys.security.runtime.v1`; `sys.audit.v1` chỉ do audit-writer và Fluent Bit ghi | Đã chốt |
| Hàng đợi lỗi | Một DLQ mỗi topic | `<topic>.retry.<consumer>` với 1, 5, 30 phút rồi `<topic>.dlq.<consumer>`; thử tại chỗ 3 lần | Đã chốt |
| Tương thích lược đồ | BACKWARD | Apicurio `BACKWARD_TRANSITIVE` cộng kiểm `VALIDITY FULL` | Đã chốt |
| Khởi tạo tiến trình duyệt | SVC-06 nhận EVT-03, EVT-11, EVT-13, EVT-25 để khởi tạo tiến trình | Khởi tạo qua cổng nội bộ `StartApprovalService` ngay khi gửi duyệt (theo TL-02a), vì EVT-01, EVT-03 là sự kiện "đã duyệt"; đề nghị bổ sung `POST /workflow/instances` vào hợp đồng | Theo mã, chờ xác nhận |
| Tín hiệu hết dấu hiệu bất thường | Không có | Đề xuất phát lại EVT-20 cùng `alertKey` với `severity = HET_DAU_HIEU`, không đặt mã EVT mới (chờ NEN-007) | Câu hỏi mở |

## D.4. mission-core (SVC-05) và các module {-}

Ma trận phụ thuộc module của TL-01 được kiểm bằng ArchUnit và Spring Modulith; mọi điểm lệch dưới đây đều giữ đúng ma trận, tức khi một module cần dữ liệu của module khác mà ma trận không cho gọi, mã dùng đảo phụ thuộc qua cổng `api` thay vì gọi thẳng. Trang wiki 21 mô tả cây gói `vn.viettel.okrkpi` và vị trí mã.

| Chủ đề | TL-01 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| Máy trạng thái nhiệm vụ (ADR-04 tầng 1) | Khai báo trong mã | Bảng chuyển trạng thái `task_lifecycle` (10 dòng) nằm trong Policy Pack ghim theo kỳ; lệnh chuyển theo hợp đồng `TaskTransition` (`TIEP_TUC`, `TRINH_NGHIEM_THU`, `HOAN_THANH`…) | Theo mã, chờ xác nhận |
| Hạn duyệt kế hoạch quý (BR-04-01) | Ghi vào `config.period.plan_deadline` | MOD-02 tính khi đọc từ tham số `plan_approval_deadline_rule` của Policy Pack và lịch làm việc, vì MOD-02 không được ghi schema `config` | Theo mã, chờ xác nhận |
| Chọn người duyệt mở lại kỳ (QT-13) | Chọn qua OPA | Chọn bằng Java trong MOD-07 (đi từ đơn vị gốc lên cấp trên, loại người lập và người đã tham gia hồ sơ); OPA trong pod chỉ trả cho phép hay không, không trả danh sách người. Thư mục `deploy/policies/` là chính sách Kyverno, Rego của dự án nằm ở `backend/opa/okrkpi` | Đã chốt bởi luồng điều phối |
| Chọn người xử lý khiếu nại (BR-12-04) | Chưa đặc tả nơi chạy | Một quy tắc Rego `okrkpi.complaint` dùng ở cả hai nơi: OPA chọn ứng viên, Java chạy cùng dữ liệu đầu vào để kiểm | Theo mã, chờ xác nhận |
| MOD-06 đọc Policy Pack | `PolicyQueryApi` | Qua cổng `config.api.SpecialCaseRulesPort` do MOD-05 hiện thực (đảo phụ thuộc), đúng ma trận | Đã chốt 22/10/2026 |
| Kết thúc hội nghị kiểm điểm phát EVT-11 | Có | Chưa phát, vì EVT-11 thuộc MOD-04 và `appraisal :: api` chưa có lệnh chuyển bước | Câu hỏi mở |
| Hồ sơ tập khách hàng (ADR-33) | Không có | MOD-05 lưu bản kê hồ sơ, hiện chỉ đối chiếu được thành phần `policyPack`; các thành phần `catalog`, `alertRuleSet`, `workflowParams`, `uiPack` mới kiểm dạng khoá | Theo mã, phần còn lại chờ cổng tra phiên bản |
| Biểu mẫu động | Policy Pack sở hữu lược đồ biểu mẫu | `config.form_schema` thuộc MOD-08 giữ lược đồ, kèm tham chiếu Policy Pack và danh sách trường ảnh hưởng điểm | Theo mã, chờ xác nhận |
| Biên dịch UI Pack | Job Node dùng Style Dictionary | `UiPackCompiler` viết bằng Java, ký ES256; tệp phát hành `ui-packs/{tenant}/{ver}/` bất biến, `current.json` hết hạn 60 giây | Theo mã, chờ xác nhận |
| Ghi nhận bằng chứng kiểm thử CEL | cel-java | Hai bộ máy: cel-java ở máy chủ và `cel-js-runner` (Node) để so khớp với trình duyệt; kết quả so tới 9 chữ số thập phân | Theo mã, chờ xác nhận |

## D.5. Các microservice {-}

Các service nằm ngoài mission-core được hiện thực với ngôn ngữ như TL-01 chọn (Java 21 Spring Boot, Go, Python FastAPI). Phiên bản Go dùng thực tế là 1.25 (toolchain 1.26.9), mới hơn bản 1.23 ghi trong baseline. Ảnh container Java được đóng bằng Jib, Go và Python dùng BuildKit.

| Service | Điểm khác thiết kế | Trạng thái |
|---|---|---|
| SVC-02 iam | Thời hạn token theo mẫu realm production: access 5 phút, refresh xoay vòng (nhàn rỗi 30 phút, tối đa 8 giờ), phiên SSO 10 giờ; bước xác thực cao `aal2` có hiệu lực 15 phút (BR-00-02); mã khôi phục MFA chạy trong SPI `mission-iam` của Keycloak; tên realm dev là `mission`, chart mặc định `okrkpi` | Theo mã; tên realm và claim `tenant` chờ chốt |
| SVC-03 tenant-registry | Saga cấp phát tenant chạy ngay trong tenant-registry (trạng thái ở `registry.job`), không chạy trên SVC-06 như chương 12 và TL-02a mô tả; tenant có 8 trạng thái theo TL-03, có thêm `DANG_CHUYEN_CELL` | Theo mã, chờ xác nhận |
| SVC-04 org-service | Giao diện SCIM 2.0 đặt ở org-service (17 thao tác), không đi qua integration-gateway; SCIM dùng 412 cho `If-Match` theo chuẩn | Đã chốt 22/10/2026 |
| SVC-06 workflow-service | Saga dùng một BPMN dùng chung `saga-dieu-phoi` với external worker `saga-buoc`; external worker gọi lõi đặt ở workflow-service (theo TL-03), không ở mission-core như G2 ghi; DMN `chon-cap-duyet` thêm dòng r5..r9 cho OKR, kế hoạch, đề nghị; workflow-service giữ bản sao `org_replica` và lịch nghỉ trong CSDL riêng (consumer chưa có) | Theo mã, chờ xác nhận |
| SVC-07 scoring-service | Làm tròn hai bước: kết quả trung gian giữ 10 chữ số, rồi làm tròn theo gói (1 chữ số, `NUA_LEN`) thay cho "2 chữ số HALF_UP"; trang đầu vào 200 dòng (không 500); Flowable DMN 7.2 chỉ đọc DMN 1.3 nên gói viết DMN 1.4 được chuyển đổi khi nạp | Đã chốt 22/10/2026 (làm tròn) |
| SVC-08 evidence-service | Spring MVC với luồng ảo (không WebFlux); tải tệp đi qua service theo hợp đồng thay vì URL ký sẵn ghi thẳng Ceph; quét mã độc bằng ClamAV qua worker riêng; xem trước bằng PDFBox | Theo mã, chờ xác nhận |
| SVC-09 ledger-service | Cột băm theo TL-03; `chain_head` thêm `is_locked`; còn phát `ledger.anomaly.detected.v1` riêng thay vì EVT-20 | Câu hỏi mở (sự kiện) |
| SVC-10 esign-service | Nhà cung cấp MySign với thời hạn lời gọi 10 giây theo TL-01; mục đích ký thêm `KHIEU_NAI`; mức B-LTA chờ DV-10-03 | Theo mã |
| SVC-11 document-service | Nhận lệnh sinh tệp qua REST (không qua Kafka); kiểm PDF/A bằng veraPDF; tem chân trang và XMP bằng PDFBox | Theo mã, chờ xác nhận |
| SVC-12 notification-service | Bảng thông báo phân vùng theo ngày bằng PL/pgSQL, giữ 90 ngày; SSE theo người với giới hạn 5 kết nối; hàng đợi kênh ngoài là bảng `delivery` lấy việc bằng `SKIP LOCKED` | Theo mã |
| SVC-14 ai-gateway | Đổi token (RFC 8693) sang audience mission-core với phạm vi `ai_readonly`; thêm các ca sử dụng soạn OKR, soát KR, hỏi đáp quy định, giải thích điểm, giọng nói; nhà cung cấp nhận dạng tiếng nói chưa chốt | Theo mã; ASR là câu hỏi mở |
| SVC-15 analytics | Giả danh mã người bằng SMT HMAC theo tenant trong Debezium; Cube đọc qua view `SQL SECURITY INVOKER`; đệm kết quả 60 giây theo TL-01 (TL-02b ghi 5 phút); analytics-api chưa gọi OPA | Theo mã, chờ xác nhận |
| SVC-16 search | Tài liệu tìm kiếm ghép từ nhiều sự kiện bằng `scripted_upsert`, phiên bản theo nhóm trường (lệch TL-03 dùng external version) | Theo mã, chờ xác nhận |

## D.6. Policy Pack và dữ liệu nền {-}

Chương "Policy Pack" được hiện thực đúng hướng: gói có phiên bản, ký số, bộ ca vàng, kiểm thử hai bộ máy và saga phát hành. Đợt này phát sinh bốn quyết định nghiệp vụ đã chốt ngày 22/10/2026 và một số điểm kỹ thuật. Về nghiệp vụ, làm tròn theo hai bước như mục D.5; điều kiện "Hoàn thành xuất sắc nhiệm vụ" bao hàm điều kiện của mức thấp hơn; tên khối và chức danh trong dữ liệu giả lập theo DL-07; và hồ sơ khách hàng TWĐ là cấu hình riêng theo ADR-33. Về dữ liệu, mọi gói hiện có (`khoi-dang/quy-dinh-moi` 2.1.0, `khoi-chinh-quyen/nd335` 1.4.0, `twd/diem-cong-viec`) là dữ liệu giả lập có cờ `gia_lap`, vì hệ thống được xây dựng như một hệ mới độc lập, mọi hệ ngoài được giả lập (YC-PT-13). Vì vậy các task chuyển đổi từ hệ cũ (LEG-01 đến LEG-12, DL-09 đến DL-11) đã bỏ. Ngày 23/10/2026, người phụ trách quyết định ADR-20 (Strangler Fig) không áp dụng nữa vì đây là ứng dụng phát triển mới; chương "Lộ trình chuyển đổi theo Strangler Fig" và mục di trú từ Oracle của TL-03 chỉ còn giá trị tham khảo (Đã chốt).

Ở phía kỹ thuật, manifest dạng tệp của gói (`policy.yaml`) viết `inputs`, `quotas`, `classification` khác lược đồ `PolicyManifest` của hợp đồng sau NEN-044; `PolicyPackController` chuẩn hoá khi trả qua API, chờ DL chốt một dạng. Khoá `wf_binding` của TL-03 không có phiên bản Policy Pack trong khi hợp đồng đặt tham số luồng dưới phiên bản gói; đây là câu hỏi mở cho TL-03.

## D.7. Hạ tầng và vận hành (tóm tắt) {-}

Các điểm hạ tầng chi tiết thuộc TL-04 và được ghi ở phụ lục cập nhật của TL-04. TL-01 chỉ cần lưu ý ba điểm. Thứ nhất, số cụm PostgreSQL mỗi cell lớn là sáu theo TL-04, TL-05 (TL-03 ghi năm). Thứ hai, OpenBao chạy trong namespace `secure-data` và cấp chứng chỉ nội bộ qua ClusterIssuer `openbao-pki-cell`. Thứ ba, môi trường thí điểm có thêm cell `CELL-NB` ngoài danh sách cell của baseline mục 6 (suy luận từ hạ tầng dev, chờ xác nhận tên chính thức).

## D.8. Chỉ tiêu hiệu năng chưa đo {-}

Theo YC-PT-19, giai đoạn hiện tại chỉ tập trung phát triển. Các chỉ tiêu sau được giữ nguyên như thiết kế và đánh dấu "chưa đo ở tải cao": outbox tới Kafka p99 ≤ 2 giây (kiểm thử tích hợp chỉ còn chặn treo 30 giây); 150 user task mỗi giây mỗi cell của workflow-service; tính điểm 8.000 hồ sơ mỗi giây; PERF-24 300 truy vấn bảng điều hành mỗi giây; nạp 100 nghìn sự kiện cảnh báo bất thường. Số đo trên dữ liệu vàng ở máy phát triển, ví dụ Cube p95 khoảng 8 ms khi trúng đệm và 35 ms khi đọc pre-aggregation, chỉ là ghi chú, không dùng để nghiệm thu.

# Phụ lục E. Giao diện cấu hình quy trình động trên Flowable (BS-01) {-}

> Phụ lục này nhập nguyên nội dung tài liệu bổ sung BS-01 vào TL-01 phiên bản 1.1. Câu "chưa ghép vào các tệp .docx đã phát hành" trong phần mở đầu chỉ còn giá trị lịch sử.

## Mục đích và phạm vi {-}

Tài liệu này trả lời câu hỏi "phần quy trình chạy trên Flowable có giao diện để cấu hình động quy trình không". Tài liệu được viết thành tệp bổ sung riêng, chưa ghép vào bốn tệp .docx đã phát hành, để không phải build lại bộ tài liệu. Khi bộ tài liệu được phát hành phiên bản tiếp theo, nội dung ở đây sẽ được đưa vào đúng vị trí đã nêu ở mục cuối.

Bộ tài liệu hiện tại đã có giao diện cấu hình quy trình ở cấp tenant, nhưng nằm trong chương Policy Pack nên khó tìm. Phần còn thiếu là giao diện để đội sản phẩm soạn và phát hành chính các mẫu luồng; hiện việc này chỉ được mô tả là thực hiện qua kho mã và CI. Tài liệu chia làm hai phần: phần thứ nhất chỉ ra những gì đã có, phần thứ hai đề xuất thiết kế cho phần còn thiếu.

## Những gì bộ tài liệu đã có {-}

### Cấu hình luồng ở cấp tenant (SCR-1504) {-}

Màn hình SCR-1504 "Cấu hình luồng duyệt (mẫu + tham số)" thuộc quy trình QT-15 "Soạn, kiểm thử và phát hành Policy Pack". Màn hình nằm ở TL-02, chương "Quy trình cấu hình, phân tích và trợ lý AI", mục SCR-1504, Hình 12.6. Người dùng là quản trị nghiệp vụ của cơ quan tổ chức (AC-03, vai trò `policy_author`), và đường vào là Config Studio › thẻ Luồng duyệt.

Trên màn hình này người cấu hình chọn một mẫu luồng đã được kiểm thử, ví dụ `duyet-ho-so-danh-gia` phiên bản 3, rồi điền tham số. Các tham số gồm số cấp duyệt, vai trò và đơn vị của từng cấp, thời hạn mỗi bước, nhắc hạn, hành vi khi quá hạn (chuyển cấp phó được uỷ quyền), các bước bắt buộc ký số, ký lô và điều kiện bỏ qua cấp viết bằng CEL. Màn hình hiển thị xem trước luồng sinh từ mẫu cùng kết quả linter, và chạy bộ test của mẫu với chính bộ tham số vừa điền. Tham số được lưu thành một bản ghi `flowable.wf_binding` và phát hành cùng Policy Pack, nên nó cũng phải đạt test, được phê duyệt và chỉ có hiệu lực từ một kỳ trong tương lai.

Vì vậy câu trả lời ngắn là có: tenant thay đổi được luồng duyệt của mình mà không cần phát hành lại phần mềm. Điều tenant không làm được là tự vẽ một sơ đồ BPMN mới.

### Lý do không cho tenant vẽ BPMN tự do {-}

Quyết định này là chủ ý của ADR-04 và quy tắc BR-15-11 (TL-02), nhằm trả lời góp ý G2 về nguy cơ "mạng nhện". Nếu mỗi tenant tự vẽ luồng, số định nghĩa BPMN sẽ tăng theo số tenant, có thể tới 5.000. Mỗi định nghĩa khi đó cần kiểm thử riêng, chiếm bộ đệm định nghĩa của engine, và rất dễ sinh ra gọi chéo tiến trình hoặc service task gọi đồng bộ ra ngoài. Đây đều là những nguyên nhân làm engine nghẽn mà TL-01 chương "Chuyên đề G2" đã phân tích.

Thay vào đó, đội sản phẩm duy trì khoảng 20 đến 40 mẫu dùng chung, còn tenant chỉ chọn mẫu và điền tham số. Cách này giữ số định nghĩa hoạt động dưới vài trăm dù số tenant tăng từ 100 lên 5.000. TL-01 đặt ngưỡng cảnh báo "chống mạng nhện" là 300 định nghĩa hoạt động và ngưỡng nghiêm trọng là 1.000.

### Dữ liệu và quy tắc quản trị đã có {-}

TL-03, mục "Schema flowable và saga (SVC-06)", đã định nghĩa hai bảng làm nền cho việc cấu hình động. Bảng `flowable.wf_template` lưu mẫu BPMN theo phiên bản, có trạng thái `NHAP`, `HIEU_LUC`, `NGUNG` và mã triển khai trong Flowable. Bảng `flowable.wf_binding` gắn mẫu với tenant, khối đối tượng, luồng Đảng hoặc Nhà nước và loại kỳ, kèm tham số trong `params_json`.

TL-01, mục "Quy tắc quản trị chống mạng nhện", đặt các quy tắc mà linter kiểm tra tự động. Thứ nhất, cấm triển khai định nghĩa gắn `tenantId`. Thứ hai, chỉ được có tối đa một tầng call activity. Thứ ba, không gọi tiến trình chéo tenant và không dùng signal phát rộng. Thứ tư, không có service task gọi đồng bộ ra ngoài, mà mọi tác vụ máy phải là external worker.

## Phần còn thiếu và đề xuất bổ sung {-}

### Khoảng trống {-}

Bộ tài liệu chỉ nói mẫu luồng được phát triển trong "repo mẫu luồng" và kiểm tra bằng CI. Bộ tài liệu chưa có màn hình nào cho ba việc sau:

1. Đội sản phẩm soạn hoặc sửa một mẫu luồng mới bằng giao diện đồ hoạ, thay vì sửa tệp XML trong kho mã.
2. Đội sản phẩm kiểm thử mẫu, phê duyệt và triển khai mẫu lên engine của từng cell.
3. Quản trị nền tảng và quản trị tenant theo dõi các tiến trình đang chạy theo mẫu, ví dụ phát hiện tiến trình kẹt và chuyển người xử lý.

Đề xuất dưới đây bổ sung hai màn hình, SCR-1505 cho việc 1 và 2, và SCR-1506 cho việc 3. Mã SCR-1505 và SCR-1506 nằm trong dải của QT-15, chưa được dùng trong baseline.

### SCR-1505. Thư viện và trình soạn mẫu luồng (nội bộ nền tảng) {-}

**Người dùng và quyền.** Màn hình chỉ dành cho đội sản phẩm phía Viettel, gắn với một vai trò mới `wf_template_author` thuộc AC-01 (quản trị nền tảng). Người phê duyệt phát hành là một người khác giữ vai trò `second_approver` (AC-09), theo nguyên tắc bốn mắt. Tenant không thấy màn hình này. Đây là điểm mấu chốt để giữ quyết định của ADR-04: giao diện đồ hoạ làm việc soạn mẫu dễ hơn, nhưng không làm số mẫu tăng theo tenant.

**Bố cục.** Màn hình có ba vùng. Vùng trái là thư viện mẫu, liệt kê mã mẫu, phiên bản, trạng thái, số tenant đang dùng (đếm từ `flowable.wf_binding`) và số test. Vùng giữa là trình soạn BPMN nhúng thư viện bpmn-js (mã nguồn mở, cùng ký pháp BPMN 2.0 mà Flowable đọc). Bảng màu của trình soạn chỉ có các phần tử được phép: user task, external worker task, exclusive gateway, parallel gateway, timer, một tầng call activity, và các khối dựng sẵn của nền tảng như "Bước ký số", "Ký lô", "Leo thang quá hạn". Vùng phải là bảng thuộc tính, gồm khai báo các tham số mà mẫu cho phép tenant điền. Mỗi tham số có kiểu, giá trị mặc định và miền giá trị, và chính khai báo này sinh ra biểu mẫu tham số trên SCR-1504.

**Kiểm tra khi soạn.** Linter chạy ngay trên trình duyệt sau mỗi thay đổi, dùng bộ quy tắc bpmnlint tuỳ biến hiện thực đúng bốn quy tắc chống mạng nhện của TL-01. Lỗi được tô đỏ trên sơ đồ. Cùng bộ quy tắc ấy cũng chạy lại ở máy chủ khi lưu, và máy chủ là nơi quyết định.

**Kiểm thử.** Mỗi mẫu có bộ test kịch bản, mỗi kịch bản gồm bộ tham số, chuỗi hành động của người dùng và trạng thái kết thúc mong đợi. Nút "Chạy test" gửi mẫu tới một engine Flowable kiểm thử dùng riêng (không phải engine của cell), chạy toàn bộ kịch bản với tham số của mọi `wf_binding` đang dùng mẫu đó, rồi báo kết quả. Nhờ vậy, một thay đổi mẫu làm hỏng cấu hình của bất kỳ tenant nào đều bị chặn trước khi phát hành.

**Vòng đời phiên bản.** Trạng thái của `flowable.wf_template` được mở rộng thành `NHAP` → `DAT_KIEM_THU` → `CHO_PHE_DUYET` → `HIEU_LUC` → `NGUNG`, tương tự vòng đời Policy Pack. Phiên bản đã lên `HIEU_LUC` không sửa được nữa; mọi thay đổi tạo phiên bản mới. Các tiến trình đang chạy tiếp tục theo phiên bản cũ cho tới khi kết thúc, và Flowable vốn giữ định nghĩa theo phiên bản nên điều này có sẵn. Chuyển `wf_binding` của một tenant sang phiên bản mới là thao tác trên SCR-1504, có hiệu lực từ kỳ sau.

**Đồng bộ với kho mã.** Giao diện không thay thế kho mã mà ghi vào nó. Khi người soạn bấm "Đề nghị phát hành", hệ thống tạo một merge request vào repo mẫu luồng, kèm tệp BPMN, khai báo tham số và bộ test. CI chạy lại linter và test, người phê duyệt duyệt merge request, và pipeline ký số gói mẫu (cosign) rồi ghi `wf_template` sang `HIEU_LUC`. Cách này giữ một nguồn sự thật duy nhất và giữ vết kiểm toán trong Git như mọi thay đổi mã khác.

**Triển khai lên cell.** Sau khi phát hành, saga "phát hành mẫu luồng" của SVC-06 triển khai định nghĩa lên engine của từng cell theo thứ tự cuốn chiếu giống phát hành phần mềm trong TL-04: cell thí điểm CELL-NB trước, sau đó các cell nhóm tỉnh, và CELL-HCM, CELL-DTW sau cùng. Mỗi bước kiểm tra trạng thái khoẻ của engine trước khi sang cell tiếp theo, và có bước bù trừ là đặt lại phiên bản trước làm mặc định.

### SCR-1506. Giám sát tiến trình theo mẫu {-}

Màn hình này cho quản trị tenant (AC-02) xem các tiến trình của tenant mình, và cho quản trị nền tảng (AC-01) xem tổng hợp theo cell. Dữ liệu đọc từ `flowable.wf_task_projection` và các chỉ số mà TL-01 đã định nghĩa cho engine, không truy vấn trực tiếp bảng `ACT_*` lúc cao điểm. Màn hình cung cấp ba thao tác có kiểm soát. Thao tác thứ nhất là chuyển người xử lý của một user task khi cán bộ nghỉ hoặc chuyển công tác. Thao tác thứ hai là gia hạn thời hạn của một bước. Thao tác thứ ba là xem sơ đồ tiến trình với bước hiện tại, dùng lại API `/diagram` đã có ở QT-08. Mọi thao tác đều ghi sổ cái. Màn hình không cho sửa biến tiến trình hay nhảy bước tuỳ ý, vì làm thế sẽ phá tính tái lập của hồ sơ đánh giá.

### Giới hạn để không ảnh hưởng mức tải 150 user task mỗi giây mỗi cell {-}

Giao diện mới không được đưa thêm tải vào đường găng của engine. Thiết kế đảm bảo điều này bằng bốn giới hạn.

Thứ nhất, linter chặn ngay từ khi soạn những cấu trúc làm tăng chi phí mỗi user task: service task đồng bộ, call activity lồng nhiều tầng, vòng lặp đa thể hiện (multi-instance) không có giới hạn số phần tử. Một mẫu được phép có tối đa 12 user task trên một nhánh và tối đa 50 phần tử đa thể hiện mỗi lần. Hai con số này là đề xuất và cần được đo lại bằng kịch bản thử tải của TL-04.

Thứ hai, mỗi mẫu trước khi phát hành phải chạy kịch bản thử tải rút gọn trên engine kiểm thử. Ngân sách là không quá 20 câu lệnh SQL cho mỗi lần hoàn thành user task. Con số này suy ra từ ước tính của TL-01: khoảng 2.250 câu lệnh mỗi giây chia cho 150 lần hoàn thành mỗi giây, tức khoảng 15 câu lệnh, cộng dư địa. Mẫu vượt ngân sách không được phát hành.

Thứ ba, tổng số định nghĩa đang hoạt động tiếp tục được giám sát bằng ngưỡng 300 và 1.000 của TL-01. Nếu giao diện mới khiến số mẫu tăng nhanh, cảnh báo sẽ kích hoạt việc rà soát gộp mẫu.

Thứ tư, triển khai định nghĩa lên engine chỉ diễn ra ngoài giờ cao điểm và không trong ba ngày cuối kỳ, vì đó là lúc tải user task cao nhất và sổ cái đang niêm phong 5 phút một lần.

### Thay đổi dữ liệu đề xuất (bổ sung cho TL-03) {-}

Bảng `flowable.wf_template` được bổ sung bốn cột. Cột `param_schema` kiểu `jsonb` lưu khai báo tham số dạng JSON Schema, dùng để sinh biểu mẫu trên SCR-1504 và kiểm tra `wf_binding.params_json`. Cột `test_suite_ref` kiểu `varchar(128)` lưu mã commit của bộ test trong repo mẫu luồng. Cột `approved_by` kiểu `uuid` lưu người phê duyệt phát hành. Cột `signature` kiểu `bytea` lưu chữ ký gói mẫu. Ràng buộc CHECK của cột `status` được mở rộng thêm `DAT_KIEM_THU` và `CHO_PHE_DUYET`.

Bảng mới `flowable.wf_template_rollout` lưu tiến độ triển khai một phiên bản mẫu lên từng cell. Bảng gồm các cột `wf_template_id`, `cell_code`, `flowable_deployment_id`, `status` (`CHO`, `DA_TRIEN_KHAI`, `LOI`, `DA_BU_TRU`) và `deployed_at`. Bảng `flowable.wf_binding` được bổ sung `version_no` và `status` (`NHAP`, `HIEU_LUC`, `NGUNG`) để thể hiện rõ trạng thái "nháp" mà SCR-1504 đang nhắc tới. Sự kiện mới đề xuất là `config.wf_template.published.v1`, do SVC-06 phát khi một phiên bản mẫu lên `HIEU_LUC`.

## Vị trí ghép vào bộ tài liệu khi phát hành phiên bản sau {-}

| Nội dung | Vị trí ghép |
|---|---|
| Đặc tả SCR-1505, SCR-1506 và mockup | TL-02, chương "Quy trình cấu hình, phân tích và trợ lý AI", sau SCR-1504 |
| Vai trò `wf_template_author` | Baseline mục vai trò, và TL-01 phần phân quyền |
| Vòng đời phiên bản mẫu, saga phát hành mẫu | TL-01, Chuyên đề G2, sau "Quy tắc quản trị chống mạng nhện" |
| Cột và bảng mới của schema flowable | TL-03, mục "Schema flowable và saga (SVC-06)" |
| Kịch bản thử tải rút gọn cho từng mẫu | TL-04, nhóm PERF, cạnh kịch bản thử tải workflow-service |


# Phụ lục F. Hợp đồng API trước (BS-02, ADR-32) {-}

> Phụ lục này nhập nguyên nội dung tài liệu bổ sung BS-02 vào TL-01 phiên bản 1.1. Câu "chưa ghép vào các tệp .docx đã phát hành" trong phần mở đầu chỉ còn giá trị lịch sử.

## Mục đích và phạm vi {-}

Tài liệu này ghi quyết định kiến trúc ADR-32: chuyển toàn bộ API công khai của nền tảng sang hướng "hợp đồng trước" (API-first), trong đó bộ tệp OpenAPI 3.1 là nguồn sự thật của giao tiếp giữa frontend và backend. Tài liệu được viết thành tệp bổ sung riêng như BS-01, chưa ghép vào các tệp .docx đã phát hành, để không phải build lại bộ tài liệu. Khi bộ tài liệu được phát hành phiên bản tiếp theo, nội dung ở đây được đưa vào đúng vị trí nêu ở mục cuối.

Phần thứ hai của tài liệu là bảng các điểm mà TL-01 Phụ lục C, TL-02 (bảng sự kiện của từng SCR), TL-03 (cột và ràng buộc CHECK) và ma trận phân quyền ghi khác nhau. Khi viết hợp đồng, mỗi điểm đã được chọn một phương án mặc định có lý do. Theo quy tắc YC-TK-10 của sổ tay yêu cầu, các điểm này đã được đưa lên người phụ trách; ngày 19/10/2026 anh Hiếu chấp nhận toàn bộ phương án mặc định nêu trong tài liệu này. Việc sửa TL-03 theo các điểm "cần bổ sung" vẫn chờ lần phát hành tiếp theo của bộ tài liệu.

## ADR-32: Hợp đồng API trước cho toàn bộ API công khai {-}

**Bối cảnh.** TL-01 mục "Hợp đồng API theo OpenAPI" chọn cho phần lõi hướng "mã trước, hợp đồng kiểm tra": springdoc sinh tài liệu từ annotation, CI so với tệp hợp đồng đã duyệt bằng `openapi-diff`. Chỉ API đối tác qua integration-gateway (SVC-13) theo hướng hợp đồng trước. Khi phân hệ web bắt đầu được phát triển song song bởi nhiều luồng (giao việc, OKR, đánh giá, giám sát, cấu hình) trong lúc backend chưa có mã, hướng "mã trước" để lại hai vấn đề. Thứ nhất, frontend không có hợp đồng để sinh kiểu và dữ liệu giả lập, nên mỗi luồng tự đặt đường dẫn và tên trường theo cách đọc riêng của mình. Thứ hai, những chỗ ba tài liệu TL ghi khác nhau về đường dẫn, tên cột hay giá trị liệt kê chỉ lộ ra khi hai phía ráp nối, tức là muộn và tốn kém.

**Quyết định.** Mọi API công khai dưới `/api/v1` (trừ API đối tác của SVC-13, vẫn giữ kho hợp đồng riêng) được mô tả trước bằng OpenAPI 3.1 trong thư mục `contracts/openapi/` của repo mission-web, mỗi module hoặc service một tệp, cộng một tệp thành phần dùng chung. Hợp đồng được viết và duyệt trước khi viết mã ở bất kỳ phía nào. Hai phía sinh mã từ hợp đồng:

- Frontend sinh kiểu TypeScript bằng openapi-typescript (chỉ sinh kiểu, không sinh mã chạy, phù hợp CSP của TL-01). Lệnh `api:check` trong CI báo lỗi khi tệp kiểu đã commit lệch với hợp đồng.
- Backend sinh interface controller và DTO bằng openapi-generator (Spring, chế độ `interfaceOnly`), controller cài đặt interface đó. springdoc vẫn được giữ, nhưng vai trò đổi thành công cụ đối chiếu ngược: CI chạy `openapi-diff` giữa tài liệu springdoc sinh lúc chạy và tệp hợp đồng đã gộp; mọi khác biệt làm build thất bại.

Mọi thay đổi API bắt đầu bằng một PR sửa tệp hợp đồng. Thay đổi phá vỡ tương thích phải đi kèm tăng phiên bản đường dẫn theo chính sách phiên bản hiện có của TL-01 (header `Deprecation`, `Sunset`). Hợp đồng được kiểm tra bằng Redocly CLI với bộ quy tắc ở `contracts/redocly.yaml`, gồm hai quy tắc riêng của dự án: mỗi thao tác phải có phần mở rộng `x-mission` để truy vết, và mỗi thao tác ghi phải khai `Idempotency-Key`.

**Phần mở rộng truy vết `x-mission`.** Mỗi thao tác mang `qt`, `scr`, `owner` (MOD hoặc SVC), `evt`, `roles` (tên vai trò của baseline mục 10), `scope` (ký hiệu phạm vi của ma trận phân quyền), `aal`, `fourEyes`, `rules` (BR) và `source` (dòng TL đã dùng). Nhờ vậy chuỗi truy vết QT → SCR → API → MOD/SVC → EVT mà AGENTS.md yêu cầu được kiểm tra tự động trên chính hợp đồng, và ma trận phân quyền có thể được đối chiếu với hợp đồng bằng công cụ thay vì đọc tay.

**Hệ quả.** Các luồng frontend dùng chung một bộ đường dẫn và tên trường; dữ liệu giả lập và kiểm thử hợp đồng sinh được từ `examples` trong tệp. Backend không còn tự do đặt tên trường khi viết controller, và mọi chỗ TL ghi khác nhau phải được chốt sớm, ngay khi viết hợp đồng. Chi phí là mỗi thay đổi API cần thêm một bước sửa hợp đồng và chờ duyệt. Quyết định này thay thế câu "Lõi theo hướng mã trước, hợp đồng kiểm tra" ở TL-01 mục "Hợp đồng API theo OpenAPI"; phần còn lại của mục đó (quy ước JSON, phân trang, idempotency, RFC 9457, chính sách phiên bản) giữ nguyên.

**Trạng thái.** Đã chấp nhận (anh Hiếu yêu cầu hướng API-first và chấp nhận toàn bộ phương án mặc định ngày 19/10/2026); đã có bộ hợp đồng bản nháp 1.0.0-draft.1 (WEB-063) gồm 11 tệp module, 351 thao tác, đều đạt lint.

## Bố cục bộ hợp đồng {-}

| Tệp | Phạm vi | Tiền tố đường dẫn | Số thao tác |
|---|---|---|---|
| `common/common.yaml` | Lỗi RFC 9457, phân trang con trỏ, `Idempotency-Key`, `If-Match`, job 202, bảo mật Keycloak, kiểu dùng chung | – | – |
| `modules/tasks.yaml` | MOD-02: nhiệm vụ, kế hoạch quý, danh mục công việc, văn bản đến | `/tasks`, `/plans`, `/catalog` | 58 |
| `modules/org.yaml` | SVC-04: người dùng hiện tại, tra cứu, cây tổ chức, đồng bộ và đối soát | `/org` | 20 |
| `modules/jobs.yaml` | Tác vụ dài dùng chung | `/jobs` | 2 |
| `modules/okr.yaml` | MOD-01 | `/okr` | 23 |
| `modules/products.yaml` | MOD-03, SVC-08 | `/products`, `/evidences`, `/files` | 22 |
| `modules/appraisals.yaml` | MOD-04, SVC-07 (đọc điểm) | `/appraisals` | 24 |
| `modules/dossiers.yaml` | MOD-06, SVC-11, SVC-10 | `/dossiers`, `/esign` | 31 |
| `modules/oversight.yaml` | MOD-07, SVC-09 | `/oversight`, `/ledger` | 36 |
| `modules/policies.yaml` | MOD-05 | `/policies` | 34 |
| `modules/config.yaml` | MOD-08 | `/config`, `/periods`, `/ui` | 54 |
| `modules/platform.yaml` | SVC-03, SVC-06, SVC-12, SVC-14, SVC-15, SVC-16 | `/registry`, `/workflow`, `/notifications`, `/ai`, `/analytics`, `/search` | 47 |

## Các điểm đã chốt khi ghép nguồn {-}

Các bảng dưới liệt kê từng chỗ nguồn ghi khác nhau, phương án hợp đồng đã chốt và lý do. Những giá trị liệt kê, đường dẫn hay trường mà nguồn không ghi và hợp đồng phải tự đặt được đánh dấu "(suy luận)" ngay trong tệp hợp đồng; bảng này chỉ nêu những điểm có ảnh hưởng đáng kể.

### Quy ước chung {-}

| Mã | Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn | Lý do |
|---|---|---|---|---|
| HD-01 | Mã lỗi xung đột `If-Match` | Baseline mục 13, TL-02: 409; TL-01 chương 11: 412 | 409, mã `<MODULE>-409-VERSION-CONFLICT`; thiếu `If-Match` trả 428 | Baseline là nguồn chung; frontend đã xử lý 409 |
| HD-02 | Thời hạn giữ `Idempotency-Key` | TL-01: 24 giờ; TL-03: 48 giờ | 48 giờ | TL-03 chi tiết hơn, có lý do (ứng dụng di động gửi lại sau mất mạng) |
| HD-03 | Cập nhật tiến độ | TL-01 PL C: `PATCH /tasks/{id}/progress`; TL-02 (8 chỗ), mã: `POST` | `POST` | Mỗi lần cập nhật tạo một dòng `task.task_progress` chỉ ghi thêm |
| HD-04 | Danh sách "Việc của tôi" | PL C, SCR-0503: `GET /tasks?assignee=me`; ma trận: `/tasks/my`; mã: `/tasks/mine` | `GET /tasks/mine`; `GET /tasks` cho danh sách đơn vị | Hai danh sách trả lược đồ khác nhau |
| HD-05 | Tên vai trò | PL C: `staff`, `org_officer`, `policy_approver`; baseline mục 10 và ma trận: `member`, `appraiser`, `tenant_admin`, `decider` | Tên của baseline mục 10 | Baseline và realm Keycloak dùng các tên này |
| HD-06 | Lược đồ job | TL chưa đặc tả | Giữ lược đồ `Job` của WEB-007 | Đã có mã dùng, không mâu thuẫn TL |
| HD-07 | Mâu thuẫn còn lại của HD-05 | TL-03 CHECK `appraisal_*.actor_role` dùng `staff` | Giữ giá trị `staff` trong dữ liệu, `member` trong `x-mission.roles` | Không tự đổi CHECK của TL-03; cần chốt đổi TL-03 sang `member` |

### Giao việc, kế hoạch, danh mục (tasks.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Điểm chuẩn dòng kế hoạch | TL-02: `score_weight`; TL-03: `standard_score` | `standardScore` theo TL-03 |
| Tên sản phẩm dòng kế hoạch | TL-02 có `product_name`; TL-03 thiếu cột | `productName`; TL-03 cần bổ sung cột |
| Mở nháp để giao | Mã frontend: `GET /tasks/{id}?view=assign` | `GET /tasks/{id}/draft` (một đường dẫn trả một lược đồ) |
| Gia hạn | PL C: `/tasks/{id}/extensions`; TL-02: đề nghị điều chỉnh | `/tasks/{id}/change-requests` |
| Gửi duyệt kế hoạch | PL C: `/plans/{id}/submit` | `/plans/{id}/versions/{v}/submit` |
| Thêm mục danh mục | PL C: `POST /catalog/items` | `POST /catalog/{catalogId}/items` |
| Loại đề nghị điều chỉnh | TL-02 và TL-03 CHECK khác nhau | Theo TL-02 |

### Tổ chức và nhân sự (org.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Cây con | PL C: `/org/units/{id}/subtree`; TL-02: `/tree` | `/subtree` theo PL C |
| Trục ghi nhận | PL C: `knownAt`; TL-02: `recordedAsOf` | `recordedAsOf` |
| Loại thay đổi tổ chức | SCR-0103 có thành lập, đổi tên, hợp nhất; TL-03 `org_change_order.kind` thiếu các giá trị này | `THANH_LAP`, `DOI_TEN`, `SAP_NHAP`, `HOP_NHAT`, `CHIA_TACH`, `GIAI_THE` (suy luận); TL-03 cần bổ sung |
| Độ dài mã đơn vị | TL-02: 30 ký tự; TL-03: `varchar(50)` | 30 |
| Đồng bộ ngay | Ma trận TL-02b: `/org/sync` | `POST /org/sync-runs` (có danh sách lần chạy cùng tài nguyên) |

### OKR (okr.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Sửa mục tiêu | PL C: `PATCH /okr/objectives/{id}`; TL-02, ma trận: `PUT` | `PUT`, lưu cả O và KR |
| Liên kết OKR | TL-02: `PARENT`/`PEER`; TL-03: `PHAN_RA`/`DONG_GOP`/`NGANG`/`CAN_CU` | Theo TL-03 |
| Thang trọng số và tiến độ | TL-03 0..1; nhiệm vụ trong mã frontend 0..100 | OKR 0..1, nhiệm vụ 0..100; cần chốt thống nhất |
| Người phụ trách KR | SCR-0002 cần; TL-03 thiếu `okr.key_result.owner_id` | Có trường (suy luận); TL-03 cần bổ sung |
| Sự kiện khi gửi OKR | TL-02a: không phát; TL-02b: EVT-21 | Không phát |
| Chưa phủ | Điều chỉnh OKR giữa kỳ (BR-02-10), đóng mục tiêu, check-in thay, tạo căn cứ | Chờ TL-02 đặc tả API |

### Sản phẩm và minh chứng (products.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Đường dẫn nghiệm thu | PL C: `/products/{id}/evidence-versions`, `/acceptance`; TL-02: `/evidences/{id}/versions/{v}/...` | Theo TL-02 |
| Thời hạn URL tải | TL-01: 5 phút; TL-03: 15 phút | 5 phút |
| Độ tươi AAL2 khi nghiệm thu | BR-07-11: 15 phút; TL-01 `auth_time`: 5 phút | 5 phút; cần chốt |
| Tải nội dung | Ma trận SCR-1603: `/evidences/{id}/versions/{v}/content` | Gộp về `/files/{objectId}/download-url` |
| Tải tệp lên | Ma trận SCR-1201: `POST /files` | `POST /files/uploads` |
| Phân loại tên tệp | TL-03 ghi C1 ở chỗ này, C2 ở chỗ khác | C2 |
| Số tệp mỗi phiên bản minh chứng | TL-02 SCR-0701 (Hình 8.13): bảng nhiều tệp trong một phiên bản, cảnh báo trùng theo từng tệp; TL-03 `product.evidence_version`: một `object_id` | Nhiều tệp (`files[]`, tối đa 20, suy luận); quét và cờ trùng ở cấp phiên bản là tổng hợp. Bổ sung 19/10/2026 theo phát hiện của WEB-032 |
| Nguyên nhân chỉnh sửa | TL-03 `revision_cause`: 5 giá trị theo mức chỉnh sửa; TL-02 SCR-0703 trường 4: Chủ quan, Khách quan, Không có; hợp đồng bản đầu dùng hai bộ ở hai module | Một enum dùng chung `RevisionCause` (common, theo TL-03) cộng cờ `isObjectiveRevision` cho chủ quan, khách quan, ở cả nghiệm thu và xem trước điểm. Bổ sung 19/10/2026 theo WEB-033 |
| Trình xem minh chứng theo trang | TL-02 SCR-0703 Hình 8.15 vẽ trình xem "trang 1 trên 6"; TL chưa có API, hợp đồng bản đầu chỉ có URL tải | Thêm `GET /files/{objectId}/preview` trả ảnh từng trang do SVC-08 dựng (suy luận); tệp C3 trả `KHONG_HO_TRO` và giải mã tại trình duyệt. Bổ sung 19/10/2026 theo WEB-033 |

### Đánh giá (appraisals.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Kiểu đường dẫn | PL C: `/appraisals/{id}/...`, `/appraisals/decisions/batch`; TL-02b: `/appraisals/profiles/{id}/...`, `/appraisals/batch-decisions` | Theo TL-02b |
| Quyết định một hồ sơ | PL C: `POST /appraisals/{id}/decision` | Gộp vào `batch-decisions` (lô một hồ sơ) |
| Hồ sơ của tôi | PL C: `GET /appraisals?period=&flow=` | `GET /appraisals/profiles/mine` |
| Mức xếp loại | TL-02b `level`; TL-03 `rating_code` | `ratingCode` |
| Tỉ lệ vượt | TL-02b `over_target_ratio`; TL-03 `exceed_ratio` | `exceedRatio` |
| Bước 9 QT-10 | Không có API trong TL | Chưa phủ |

### Hồ sơ số và ký số (dossiers.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Ký số | PL C: `/esign/sessions`; TL-02 (4 chỗ), TL-03: `sign_request` | `/esign/requests` |
| Gói hồ sơ | TL-02b: một gói cho mỗi đơn vị và kỳ; TL-03: `dossier_package.person_id NOT NULL` | Theo TL-02b; TL-03 cần sửa |
| Mục đích ký | TL-03 `sign_request.purpose` thiếu giá trị cho duyệt kế hoạch (SCR-0403) và trả lời khiếu nại (SCR-1202) | Giữ enum TL-03; TL-03 cần bổ sung |
| Hạn nộp | TL-02 `due_date`; TL-03 `deadline` | `deadline` |
| Lập gói | PL C: `POST /dossiers/{id}/packages` | `POST /dossiers/packages` |

### Giám sát và sổ cái (oversight.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Cảnh báo | PL C: `/oversight/anomalies`; TL-02b (3 chỗ): `/oversight/alerts` | `/oversight/alerts`, `/alerts/{id}/actions` |
| Trả lời khiếu nại | PL C: `POST /complaints/{id}/responses`; TL-02b: `/respond` (202) | `/respond`; tách thêm `PUT /conclusion` (suy luận) |
| Duyệt mở lại kỳ | PL C: `/approve`; TL-02b: `/approvals` | `/approvals` |
| Hành động cảnh báo | TL-03 thiếu `KET_LUAN` mà TL-02b dùng | Thêm `KET_LUAN`; TL-03 cần bổ sung |
| Khiếu nại nháp | TL-02b có nháp; TL-03 không có `NHAP`, `filed_at NOT NULL` | `status: null` khi chưa gửi; cần chốt |

### Policy Pack (policies.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Tiền tố | PL C: `/policies/{id}/versions`; TL-02b: `/policies/packs/{id}/versions/{v}` | Theo TL-02b |
| Phát hành | PL C: `/publish`, vai trò `policy_approver`; TL-02b: `/approve` | `/approve` (202), vai trò `decider` |
| Mã phiên bản | TL-02b `v2.1`; TL-03 CHECK semver | Semver ba phần `2.1.0` |
| Trạng thái mẫu luồng | TL-03: 3 giá trị; BS-01: 5 giá trị | Theo BS-01 |

### Cấu hình và kỳ (config.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Người khởi động khoá kỳ | PL C: `org_officer`; SCR-0801, ma trận: AC-02 | `tenant_admin` |
| Mô hình thuê bao | `registry.tenant` chữ hoa, có `AIRGAP`; `tenant_cell`, TL-02a chữ thường | Chữ thường ở `/config`; `/registry` giữ chữ hoa theo `registry.tenant` |
| Giao thức IdP | TL-02a nhắc LDAP liên kết; TL-03 CHECK chỉ `OIDC`, `SAML` | Theo TL-03 |
| Cột trùng nghĩa | `ui_pack_version` có `tokens`/`tokens_json`; `form_schema` có `form_code`/`form_key` | Một trường cho mỗi cặp; TL-03 cần gỡ cột thừa |

### Dịch vụ nền (platform.yaml) {-}

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Quyết định gợi ý AI | SCR-1701, ma trận: `/accept`, `/dismiss`; SCR-0502, mã: `/decision` | `POST /ai/suggestions/{id}/decision` |
| Dữ liệu bảng điều hành | PL C: `GET /analytics/dashboards/{id}`; SCR-1601: `POST /analytics/query` | Giữ cả hai (định nghĩa kèm dữ liệu, và truy vấn tự do) |
| Mẫu luồng (BS-01) | Có thể đặt dưới `/policies` hoặc `/workflow` | `/workflow/templates` cho SCR-1505; `/policies/workflow-templates` chỉ đọc cho SCR-1504 |

## Những thay đổi cần đưa vào TL ở phiên bản sau {-}

Khi phát hành phiên bản tiếp theo của bộ tài liệu, TL-01 mục "Hợp đồng API theo OpenAPI" thay câu về hướng "mã trước" bằng tóm tắt ADR-32 và thêm ADR-32 vào bảng ADR; Phụ lục C trỏ sang bộ hợp đồng thay vì liệt kê đường dẫn. TL-02 thay các đường dẫn trong bảng sự kiện SCR bằng `operationId` tương ứng. TL-03 sửa các điểm đã nêu "cần bổ sung" hoặc "cần sửa" ở trên: bảng con `product.evidence_version_file` cho nhiều tệp mỗi phiên bản minh chứng, cột `plan_item.product_name`, `okr.key_result.owner_id`, giá trị liệt kê của `org_change_order.kind`, `sign_request.purpose`, `alert_action`, quan hệ `dossier_package` theo đơn vị, và các cột trùng nghĩa của `config`.


# Phụ lục G. Hồ sơ tập khách hàng và điểm cắm lõi (BS-03, ADR-33) {-}

> Phụ lục này nhập nguyên nội dung tài liệu bổ sung BS-03 vào TL-01 phiên bản 1.1. Câu "chưa ghép vào các tệp .docx đã phát hành" trong phần mở đầu chỉ còn giá trị lịch sử.

## Mục đích và phạm vi {-}

Tài liệu này ghi quyết định kiến trúc ADR-33. Quyết định đưa vào Mission khái niệm **hồ sơ tập khách hàng**, tức một bộ cấu hình có phiên bản gom mọi khác biệt về cách chấm điểm và quy tắc nghiệp vụ của một tập khách hàng. Tài liệu cũng đặc tả sáu **điểm cắm** (DC-01 đến DC-06) mà lõi phải bổ sung để các hồ sơ có đủ dữ liệu thô mà biểu diễn những cách chấm khác nhau.

Bối cảnh trực tiếp là bộ giải pháp KPI của UEB cho Ban Tổ chức Trung ương (UEB-BTCTW-QTCV-01..05, v1.2, 10/2026). Báo cáo phân tích nằm ở `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md`. Ngày 22/10/2026, anh Hiếu chấp thuận cả bốn phương án khuyến nghị của báo cáo, kèm một điều kiện bắt buộc. Nghiệp vụ UEB đi vào Mission thành **một bộ hồ sơ riêng cho TWĐ, cấu hình được, không hợp nhất cứng vào lõi**. Quyết định này hiện thực quy tắc YC-TK-11 của sổ tay yêu cầu.

Giống BS-01 và BS-02, tài liệu được viết thành tệp bổ sung riêng và chưa ghép vào các tệp .docx đã phát hành. Mục cuối nêu vị trí đưa vào TL khi bộ tài liệu được phát hành phiên bản tiếp theo. Lớp cấp trên giữa các tenant (B7 trong báo cáo) không thuộc BS-03, vì cần thiết kế riêng và đợt thí điểm chưa cần.

## ADR-33: Hồ sơ tập khách hàng trên một lõi chung {-}

**Bối cảnh.** Policy Pack ở TL-01 đã tách công thức và bảng xếp loại ra khỏi mã. Tuy vậy, Policy Pack chỉ phân biệt theo hai luồng `DANG` và `NHA_NUOC`. Đầu vào (`inputs`) của gói lại là một tập biến đếm cố định, gồm số việc được duyệt, kế hoạch, mức chất lượng trung bình và số ngày chậm. Khi đối chiếu với giải pháp UEB, có sáu nhóm nghiệp vụ không biểu diễn được bằng cấu hình hiện có. Cụ thể là:

- điểm công việc theo hệ số;
- điểm quản lý theo phạm vi phụ trách;
- kiểu đo mốc;
- kế thừa tham số có khung giới hạn;
- chuỗi xếp loại nhiều bước;
- chế độ "mỗi công việc một người".

Nguyên nhân không nằm ở công thức. Lõi chưa cung cấp đủ dữ liệu thô cho công thức dùng. Nếu viết thẳng cách chấm của Ban Tổ chức vào mã, Mission sẽ mất khả năng phục vụ các tập khách hàng khác như khối Nhà nước, doanh nghiệp nhà nước và các tỉnh.

**Quyết định.** Mission tách ba lớp.

- **Lớp lõi** giữ nguyên kiến trúc đã chốt. Lõi chỉ cung cấp **sự kiện và đại lượng thô**: việc nào, điểm chuẩn bao nhiêu, *mức* của từng loại hệ số, mức chất lượng, số ngày chậm, nguyên nhân chậm, ai phụ trách mục nào. Lõi không chứa giá trị hệ số, công thức hay ngưỡng của bất kỳ khách hàng nào.
- **Lớp cấu hình** gồm Policy Pack, danh mục có kế thừa, mẫu luồng Flowable kèm tham số (BS-01), bộ quy tắc cảnh báo và UI Pack. Mọi thành phần của lớp này có phiên bản và hiệu lực theo kỳ.
- **Lớp hồ sơ tập khách hàng** là một bản kê có phiên bản, trỏ tới đúng phiên bản của từng thành phần cấu hình mà tập khách hàng dùng. Bản kê cũng bật hoặc tắt từng điểm cắm và khai báo khung tham số mà cấp dưới được phép chỉnh.

Mỗi điểm cắm DC-01..DC-06 là khả năng chung của lõi, không mang tên khách hàng. Mặc định của mỗi điểm cắm giữ đúng hành vi hiện tại, nên các gói đã phát hành (DL-01, DL-02) chạy không đổi. Giá trị riêng của UEB chỉ nằm trong **hồ sơ TWĐ** (mục "Hồ sơ TWĐ" bên dưới). Một tập khách hàng khác có thể dùng hồ sơ khác, hoặc tắt hẳn điểm cắm đó.

**Hệ quả.**

- Thêm một hồ sơ mới là việc cấu hình, nạp dữ liệu và ký bộ ca vàng, không phải một đợt phát triển.
- Lõi phải chịu thêm chi phí là mở rộng `inputs` của scoring và một vài cột hoặc bảng nhỏ ở TL-03.
- Tiêu chí nghiệm thu bắt buộc: **cùng một bản build của lõi phải đạt 100% ca vàng của ít nhất hai hồ sơ khác nhau**. Hai hồ sơ đó là hồ sơ TWĐ và hồ sơ "quy định mới" hiện có của DL-01. Kiểm thử này chạy trong CI của DV-07.
- Không được tạo nhánh mã `if (hoSo == TWĐ)` ở bất kỳ đâu. Kiểm tra ArchUnit và rà soát mã phải chặn mọi hằng số định danh hồ sơ trong `vn.viettel.okrkpi`.

**Trạng thái.** Đã chấp nhận (anh Hiếu, 22/10/2026). Lớp cấp trên giữa các tenant để ngoài phạm vi.

## Mô hình hồ sơ tập khách hàng {-}

### Bản kê hồ sơ {-}

Hồ sơ là một tài liệu YAML được đóng gói, ký và phát hành theo đúng vòng đời của Policy Pack: nháp, kiểm thử, đạt kiểm thử, chờ phê duyệt, đã phát hành, ngừng. Hai người khác nhau soạn và duyệt. Hồ sơ không chứa công thức. Hồ sơ chỉ chứa tham chiếu và cờ.

```yaml
apiVersion: policy.okrkpi.vn/v1
kind: CustomerProfile
metadata:
  id: twd/ban-to-chuc-ueb          # hồ sơ TWĐ theo giải pháp UEB
  version: 0.1.0
  ten: "Khối Đảng ở Trung ương – mẫu Ban Tổ chức (giả lập theo UEB)"
  gia_lap: true                    # YC-PT-14: dự thảo chưa ban hành
  can_cu: ["Dự thảo Quy định kiểm điểm, đánh giá, xếp loại (12/10/2026)", "UEB-BTCTW-QTCV-02 v1.2"]
thanh_phan:
  policy_pack: {id: twd/diem-cong-viec, version: "0.1.0", luong: DANG}
  danh_muc: {code: TWD-DM-25, version: 25}
  bo_quy_tac_canh_bao: {id: twd/diem-nghen, version: "0.1.0"}
  tham_so_luong: {mau: duyet-de-nghi, bo: twd-tham-quyen}
  ui_pack: null                    # dùng UI Pack của tenant
diem_cam:
  DC-01_diem_cong_viec: BAT
  DC-02_khung_ke_thua: BAT
  DC-03_pham_vi_phu_trach: BAT
  DC-04_kieu_do_moc: BAT
  DC-05_chuoi_xep_loai: BAT
  DC-06_che_do_phan_cong: MOT_NGUOI_MOT_VIEC
khung_tham_so: tham-so/khung-twd.yaml   # ràng buộc DC-02 cho cấp dưới
ap_dung_cho:
  tenant_tags: [khoi-dang-tw]
```

Hồ sơ gắn vào tenant bằng thẻ `tenant_tags`, theo đúng cách Policy Pack hiện dùng. Trong một kỳ, `policy_binding` vẫn là nơi ghim phiên bản gói. Bổ sung một cột `customer_profile_version_id` để snapshot ghi được cả phiên bản hồ sơ. Khi kỳ đã khoá, mọi thành phần mà hồ sơ trỏ tới đều bị đóng băng cùng với kỳ.

### Lưu trữ (bổ sung TL-03, schema `policy`) {-}

| Bảng | Nội dung | Ghi chú |
|---|---|---|
| `policy.customer_profile` | `code`, `name`, `is_simulated`, `legal_basis`, cột chung | Mã duy nhất trong tenant gốc toàn cục |
| `policy.customer_profile_version` | `version`, `status` (cùng tập giá trị với `policy_pack_version.status`), `manifest jsonb`, `content_hash`, `signature`, `approved_by` | Cùng ràng buộc CHECK "đã phát hành phải có chữ ký và người duyệt khác người soạn" |
| `policy.param_constraint` | `customer_profile_version_id`, `param_key`, `can_toggle`, `can_change_levels`, `min_value`, `max_value`, `tighten_direction` (`TANG`, `GIAM`, `KHONG`), `allowed_scopes` | Khung tham số của DC-02 |
| `policy.policy_binding` | thêm `customer_profile_version_id uuid` (FK, cho phép NULL với dữ liệu cũ) | Snapshot đọc từ đây |

Tất cả là dữ liệu C0 hoặc C1, đi theo RLS tenant và quy ước cột chung ở TL-03. Hồ sơ gốc do tenant gốc toàn cục phát hành và được phát tán qua saga phát hành đa cell (CORE-05-10, EVT-13) giống Policy Pack.

## Sáu điểm cắm của lõi {-}

Mỗi điểm cắm dưới đây được mô tả theo bốn ý: lõi bổ sung gì, mặc định khi không bật, hồ sơ TWĐ dùng thế nào, và tác động tới hợp đồng.

### DC-01 · Đầu vào tính điểm theo điểm công việc {-}

**Lõi bổ sung.** API đọc lô `GET /api/v1/appraisals/inputs` (`listAppraisalScoringInputs`) trả thêm cho mỗi hồ sơ danh sách `workItems`, gồm các việc lá của người đó trong kỳ. Mỗi việc lá mang các trường sau:

- `taskId`, `standardScore`;
- `coefficientLevels`, là bản đồ từ mã loại hệ số sang **mức**;
- `taskGroup`, `axisCode`;
- `statusAtLock`, `qualityLevel`;
- `subjectiveLateWorkdays`, `objectiveLateWorkdays`;
- `exceeded`, `outOfScope`, `deferredByDecision`.

Khai báo `inputs` của Policy Pack nhận kiểu `list<WorkItem>` với nguồn `task.aggregate.work_items`.

Lõi chỉ lưu **mức** của hệ số, không lưu giá trị. Bảng quy đổi từ mức ra giá trị nằm trong gói, theo kỳ. Nhờ vậy, đổi hệ số chỉ cần phát hành phiên bản gói mới, không phải sửa dữ liệu nhiệm vụ. Về dữ liệu, `task.task` và `task.catalog_item` đã có `difficulty` (1–5) và `task_group`. Bổ sung `coefficient_levels jsonb` cho các loại hệ số mở, mặc định `{}`. Bổ sung thêm `axis_code varchar(16)` (NULL được phép).

**Mặc định.** Các biến đếm cũ (`so_luong_duyet`, `ke_hoach`…) giữ nguyên. Gói không khai báo `work_items` thì không nhận danh sách, và chi phí đọc lô không đổi.

**Hồ sơ TWĐ dùng.** Điểm công việc được tính theo công thức:

`Đ = điểm chuẩn × hệ số khó × hệ số nhóm` (dành sẵn ba ô hệ số dự phòng)

Điểm đạt của một việc tính theo công thức `(Đ + Đ·m + Đ·(1 − 0,05·n)) / 3`. Từ đó có hai chế độ đưa về thang 100. Chế độ "tỷ lệ có hệ số" tính `100 × Σ đạt / Σ giao` và là mặc định của hồ sơ. Chế độ "gói điểm" được bật bằng tham số.

**Hiệu năng.** Danh sách được trả theo hồ sơ và có giới hạn 200 việc lá mỗi hồ sơ. Nếu vượt giới hạn, API phân trang con việc bằng con trỏ con. CEL đánh giá trên chương trình đã biên dịch, nên vẫn giữ được NFR-06 với hồ sơ trung bình 25 nhiệm vụ như TL-01 ước tính.

**Hợp đồng.** `appraisals.yaml` bổ sung `WorkItem` vào `AppraisalScoringInput` dưới dạng trường tùy chọn, nên không phá tương thích. `tasks.yaml` bổ sung `coefficientLevels`, `axisCode` cho `CatalogItem`, `Task`, `PlanItem`.

### DC-02 · Khung tham số kế thừa có giới hạn {-}

**Lõi bổ sung.** Cơ chế `extends` của Policy Pack hiện chỉ cho tenant "siết ngưỡng" qua cờ `policy_rule.overridable`. Cơ chế này được mở rộng thành ràng buộc khai báo cho từng tham số, lưu trong `policy.param_constraint`. Mỗi ràng buộc gồm các thuộc tính:

- tham số có được bật tắt không;
- có được đổi số mức không;
- khoảng tối thiểu và tối đa;
- chiều được phép siết;
- các cấp được phép chỉnh.

Công cụ phát hành từ chối mọi phiên bản cấp dưới vi phạm khung, và trả lỗi RFC 9457 nêu rõ tham số, giá trị và khung.

Phạm vi kế thừa có bốn cấp: hệ thống → khối → tỉnh → đơn vị. `task.work_catalog.scope` bổ sung hai giá trị `KHOI` và `TINH` bên cạnh `VI_TRI`, `DON_VI`, `QUOC_GIA`. `task.catalog_item` bổ sung `score_adjust_min` và `score_adjust_max` (tỷ lệ chỉnh cho phép so với dòng cha, NULL nghĩa là không được chỉnh).

**Mặc định.** Khi không có ràng buộc, hành vi giữ đúng `overridable` hiện tại.

**Hồ sơ TWĐ dùng.** Được chỉnh điểm chuẩn ±20% so với dòng cha. Hệ số nhóm nằm trong khung [1,0; 1,5]. Mức trừ mỗi ngày chậm chỉ được siết, tức chỉ tăng. Ngưỡng cảnh báo phân bố mức khó 60/20/10% được chỉnh theo khung.

**Hợp đồng.** `policies.yaml` bổ sung tài nguyên `param-constraints` thuộc phiên bản hồ sơ. `tasks.yaml` mở rộng enum `scope`.

### DC-03 · Phạm vi phụ trách cho điểm quản lý {-}

**Lõi bổ sung.** Với hồ sơ của người giữ chức vụ lãnh đạo, quản lý, `inputs` trả thêm `responsibilityScope`. Đây là danh sách các mục mà người đó phụ trách, gồm KPI, nhiệm vụ, KR và đơn vị, mỗi mục có các trường:

- `itemType`, `itemId`;
- các tổng S, Q, D đã tổng hợp;
- `targetAchievedRatio` (r);
- `confirmedLevel` (m);
- `ownLateWorkdays` (n).

Các số này tính trên chính mục đó, không cộng dồn hai lần phần giảm của cấp dưới. Phạm vi phụ trách lấy từ quan hệ "người phụ trách" đã có ở `okr` và `task`, cùng với phân công lãnh đạo của SVC-04, theo tổ chức tại thời điểm khoá kỳ.

**Mặc định.** Gói không khai báo thì không nhận dữ liệu này.

**Hồ sơ TWĐ dùng.** Điểm quản lý theo phạm vi phụ trách. Các phần giảm r, m, n áp đúng tại cấp KPI, nhiệm vụ hoặc KR. Cách làm này gắn kết quả của người lãnh đạo với kết quả của đơn vị, theo dự thảo Điều 11 khoản 1.

**Hợp đồng.** `appraisals.yaml` bổ sung `ResponsibilityItem` (trường tùy chọn).

### DC-04 · Danh mục kiểu đo mở và kiểu mốc {-}

**Lõi bổ sung.** `okr.key_result.metric_type` bổ sung giá trị `MOC`, nghĩa là mốc có thời hạn. Bảng mới `okr.kr_milestone` lưu `key_result_id`, `seq`, `due_date`, `recognition_condition`, `achieved_at`, `evidence_ref`, gồm cả các mốc trung gian. `direction` bổ sung giá trị `TOI_DA` (không vượt quá). KR kiểu tỷ lệ cho phép khai `numerator_label` và `denominator_label` để check-in nhập cả tử số và mẫu số.

Lõi chỉ ghi lại ngày đạt mốc và số ngày làm việc chậm, tính theo lịch nghỉ của CORE-08-04. Cách quy đổi ra tỷ lệ hoàn thành nằm trong gói.

Khi khuôn phân rã của hồ sơ yêu cầu, KPI trở thành nút đo được giữa nhiệm vụ và công việc. Việc này dùng lại `task.task.parent_task_id`, vốn đã có trong CORE-02-02, và bổ sung `task.task.node_kind`, nhận các giá trị `NHIEM_VU`, `KPI`, `CONG_VIEC`, `CONG_VIEC_CON`, mặc định `NHIEM_VU`.

**Mặc định.** Bốn kiểu đo cũ và cây hai tầng hiện có giữ nguyên.

**Hồ sơ TWĐ dùng.** Trừ 5% mỗi ngày làm việc chậm của mốc. Cây bảy bậc O → KR → NV → KPI → CV → CVc.

**Hợp đồng.** `okr.yaml` mở rộng `OkrMetricType` và bổ sung tài nguyên `milestones`. `tasks.yaml` bổ sung `nodeKind`.

### DC-05 · Chuỗi xếp loại nhiều bước và bốn giá trị mức {-}

**Lõi bổ sung.** `classification` của Policy Pack nhận thêm dạng `chuoi`. Dạng này là danh sách bước có thứ tự, mỗi bước là một bảng DMN hoặc một biểu thức CEL. Mỗi bước ghi vào `score_trace` gồm `stepNo`, `ruleCode`, `inputs`, `output` và câu lý do. Hợp đồng `appraisals.yaml` đã có cấu trúc này. Nhờ đó, phiếu đánh giá hiển thị được "vì sao bị hạ mức".

Về bốn giá trị mức, TL-03 đã đáp ứng nên DC-05 không thêm cột mới. Mức hệ thống gợi ý nằm ở `appraisal.score_snapshot.auto_rating_code`, kèm `downgrade_reasons`. Bốn mức do con người đưa ra là bốn dòng của `appraisal.rating_proposal`, phân biệt bằng `stage`: tự đề xuất, thẩm định, đề xuất của cấp có thẩm quyền và quyết định. Như vậy phiếu có đủ các giá trị mà UEB yêu cầu: gợi ý, tự nhận, đề xuất và quyết định.

DC-05 chỉ bổ sung một ràng buộc. Khi mức của một dòng `rating_proposal` khác `auto_rating_code` của ảnh chụp mà người đó đã nhìn thấy, dòng đó phải có lý do. Lý do được ghi vào `appraisal.rating_explanation`, với lời giải trình được mã hoá C2 như TL-03 đã thiết kế. Ràng buộc này được kiểm ở máy trạng thái của CORE-04-03. `downgrade_reasons` được điền từ các bước của chuỗi xếp loại.

Bảng mới `appraisal.incident` ghi các "sự việc" đã có kết luận của cấp có thẩm quyền: loại sự việc, ngày kết luận, văn bản và tác động. Điều kiện loại trừ trong gói đọc bảng này qua `inputs` để tự bật.

`quotas` trở thành **tùy chọn** của gói. Ví dụ `HTXSNV 20%` ở TL-01 chỉ còn là ví dụ cấu hình, không phải mặc định. Lý do là dự thảo Quy định 12/10/2026 không quy định trần.

**Mặc định.** Gói dùng `classification.dmn` một bảng `FIRST` như cũ vẫn hợp lệ.

**Hồ sơ TWĐ dùng.** Xếp loại gợi ý theo sáu bước. Mức Xuất sắc bao gồm điều kiện của mức thấp hơn và làm tròn hai bước, theo quyết định ngày 22/10. Không đặt trần, nhưng báo cáo tỷ lệ.

**Hợp đồng.** `appraisals.yaml` bổ sung tài nguyên `incidents` và mã lỗi bắt buộc lý do khi mức khác mức gợi ý (nếu hợp đồng chưa có). `policies.yaml` cho phép `classification.chain`.

### DC-06 · Chế độ phân công theo hồ sơ {-}

**Lõi bổ sung.** Lõi giữ mô hình "một chủ trì, nhiều phối hợp" (`task.task_assignee`) làm mặc định. Hồ sơ có tham số `che_do_phan_cong` nhận một trong hai giá trị:

| Giá trị | Hành vi |
|---|---|
| `CHU_TRI_PHOI_HOP` | Mặc định, giữ mô hình hiện tại |
| `MOT_NGUOI_MOT_VIEC` | Mỗi công việc lá đúng một người; điểm của việc cha chỉ tính khi mọi việc con xong; điểm thành phần của việc con cộng lại bằng điểm của việc cha |

Quy tắc của chế độ `MOT_NGUOI_MOT_VIEC` được kiểm ở bước giao việc (CORE-02-14) bằng tham số đọc qua `PolicyQueryApi`, không viết cứng. Việc con lưu `component_score numeric(6,3)` trên `task.task`.

**Hồ sơ TWĐ dùng.** `MOT_NGUOI_MOT_VIEC`.

**Hợp đồng.** `tasks.yaml` bổ sung `componentScore` và mã lỗi `TASK-422-ASSIGNMENT-MODE`.

## Hồ sơ TWĐ {-}

Hồ sơ TWĐ (`twd/ban-to-chuc-ueb`) là bản đầu tiên dùng các điểm cắm trên và được đánh dấu **giả lập** theo YC-PT-14, vì dự thảo chưa ban hành và nhiều tham số còn chờ Ban Tổ chức chọn. Hồ sơ gồm:

1. **Policy Pack `twd/diem-cong-viec`.** Gói `extends` từ gói chung của khối Đảng. Gói gồm:
   - công thức ba mặt điểm trên điểm công việc và hai chế độ đưa về thang 100;
   - bảng gói điểm theo nhóm đối tượng và tỷ trọng theo trục;
   - mức trừ 5% mỗi ngày;
   - thang mức chất lượng 1,00 / 0,75 / 0,50 / 0,25 / 0 và trần 0,75 cho mục cần khắc phục;
   - bảng hệ số khó 0,8 / 1 / 1,25 / 1,6 / 2,0 và hệ số nhóm 1 / 1,3;
   - chuỗi xếp loại sáu bước.
2. **Ca vàng.** Lấy từ mục 11.8, mục 32 (120 người, kết quả mong đợi 3 / 113 / 4 / 0), các ca G1–G13 của Phụ lục 01G và CTB1–CTB30. Bộ ca phải đạt 100% trước khi phát hành.
3. **Danh mục điểm chuẩn tạm thời của Ban.** Lấy theo dự thảo số 25, kể cả các dòng tách như 34a, 34b, ở phạm vi `KHOI`. Đi kèm khung tham số DC-02.
4. **Danh mục dùng chung theo dự thảo:**
   - 6 trục TR1–TR6;
   - 5 chỉ tiêu CT1–CT5, địa phương mở từ CT6;
   - điều kiện công nhận kiểu mốc;
   - bộ tiêu chí chung 12/10/11;
   - loại sự việc dẫn tới loại trừ.
5. **Bộ quy tắc cảnh báo `twd/diem-nghen`.** Gồm chín quy tắc tự sinh điểm nghẽn và rủi ro (mục 11.6), viết bằng CEL. Mỗi quy tắc có điều kiện, mức, người nhận, ngưỡng, giờ chạy và cờ bật tắt. Job của lõi chỉ đánh giá và ghi `task.task_blocker`. Bảng này hiện chưa có cột phân biệt nguồn, nên bổ sung `origin` (`NGUOI_DUNG` mặc định, `TU_DONG`) và `rule_code`. Khoá idempotent của dòng tự sinh là `(task_id, rule_code, ngày chạy)`.
6. **Tham số luồng.** Gồm bảng thẩm quyền duyệt thay đổi, thẩm quyền rà soát, thẩm định, quyết định và hạn 2/2/1/1 ngày làm việc. Các tham số này được đặt qua SCR-1504 theo phạm vi BS-01.

Mọi giá trị trên đều là dữ liệu của hồ sơ. Không giá trị nào được trở thành mặc định của lõi hay của hồ sơ khác.

## Ngoài phạm vi {-}

- **Lớp cấp trên giữa các tenant (B7).** Gồm O, KR của Trung ương, tỉnh, xã; liên kết; số cuốn lên. Hướng đã ghi trong báo cáo: danh mục dùng chung chỉ đọc, chỉ lưu liên kết, tổng hợp bên analytics qua sự kiện. Hướng này sẽ được làm ở một bản bổ sung sau.
- **Kết cấu dịch vụ và yêu cầu phi chức năng của UEB.** Gồm mục 34.1 và 34.4, cùng giao diện mockup và chức năng ghi thời gian thực hiện (mục 6.6). Báo cáo đã xếp các nội dung này vào nhóm C.

## Bảng truy vết sang task {-}

| Điểm cắm | Task mới | Task được bổ sung phạm vi |
|---|---|---|
| Hợp đồng cho DC-01..DC-06 | NEN-044 | – |
| Hồ sơ, DC-02 (kể cả phân giải tham số theo khung trong `PolicyQueryApi` đã gộp) | CORE-05-12 | – |
| DC-01, DC-02 phía danh mục | CORE-02-27 | – |
| DC-04 (cây KPI), DC-06 | CORE-02-28 | CORE-02-14, CORE-02-20 |
| DC-04 (kiểu mốc) | CORE-01-14 | CORE-01-03, CORE-01-08 |
| DC-01, DC-03, DC-05 phía MOD-04 | CORE-04-13 | CORE-04-06, CORE-04-03 |
| DC-01, DC-05 phía SVC-07 | – | DV-07-03, DV-07-05 |
| Hồ sơ TWĐ | DL-16 | – |

Thứ tự thực hiện là hợp đồng (NEN-044) trước, theo ADR-32 và YC-PT-09. Sau đó đến lõi (CORE-05-12, CORE-02-27, CORE-01-14, CORE-02-28, CORE-04-13). Tiếp theo là scoring (DV-07-03, DV-07-05). Cuối cùng là nạp hồ sơ TWĐ (DL-16). Giao diện cho các điểm cắm sẽ được giao thành task WEB khi hợp đồng đã gộp.

## Vị trí đưa vào bộ TL {-}

- **Baseline:** thêm ADR-33 vào bảng ADR.
- **TL-01:**
  - chương "Policy Pack" bổ sung mục "Hồ sơ tập khách hàng", phần `inputs` mở rộng (DC-01, DC-03), dạng `classification.chain` (DC-05);
  - sửa ví dụ `quotas` thành tùy chọn có chú thích;
  - SVC-07 bổ sung đầu vào lô mới.
- **TL-03:**
  - schema `policy` thêm ba bảng và một cột;
  - schema `task` thêm `coefficient_levels`, `axis_code`, `node_kind`, `component_score`, `score_adjust_min`, `score_adjust_max`, giá trị `scope` mới và cột `origin`, `rule_code` của `task_blocker`;
  - schema `okr` thêm `MOC`, `TOI_DA`, `kr_milestone`;
  - schema `appraisal` thêm bảng `incident` và ràng buộc lý do khi mức của `rating_proposal` khác mức gợi ý.
- **Wiki:** trang 14 ghi quyết định. Trang 17 và 21 cập nhật khi các task lõi gộp.
