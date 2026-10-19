> Nguồn: Baseline mục 2, 3, 3.1, 4 (ADR-01), 5, 6; TL-01 chương "Quan điểm và kiến trúc tổng thể", mục SVC-17; TL-04 chương "Mô hình triển khai".

# Tổng quan hệ thống

Trang này đặt phân hệ web Mission vào bức tranh chung của nền tảng giao nhiệm vụ, quản lý OKR/KPI và đánh giá cán bộ. Người đọc là lập trình viên frontend hoặc AI agent mới vào dự án: sau khi đọc xong, người đọc cần biết hệ thống phục vụ ai, ở quy mô nào, được chia thành những thành phần nào, và phân hệ web (SVC-17 `web-frontend`) đứng ở đâu trong số đó.

## Bối cảnh nghiệp vụ

Nền tảng phục vụ việc giao nhiệm vụ, theo dõi tiến độ, nghiệm thu sản phẩm và đánh giá, xếp loại cán bộ, đảng viên theo các văn bản mà người dùng gọi chung là "Thông tư 22 và QĐ 366". Theo hướng dẫn viết của bộ tài liệu, căn cứ thực tế gồm QĐ 366-QĐ/TW, HD 02 và dự thảo Quy định mới cho luồng Đảng, cùng NĐ 335/2025 và NĐ 233/2026 cho luồng Nhà nước. Tên gọi "Thông tư 22" vì vậy là cách gọi của người dùng, chưa được đối chiếu với một văn bản cụ thể (xem [câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)).

Hệ thống có hai luồng đánh giá song song cho cùng một người và cùng dữ liệu sản phẩm: luồng `DANG` chấm trên thang 100 điểm và luồng `NHA_NUOC` chấm theo cơ cấu 30/70 (baseline mục 9.1). Bốn mức xếp loại là `HTXSNV`, `HTTNV`, `HTNV`, `KHTNV`. Công thức tính điểm, bảng xếp loại và luồng duyệt không viết cứng trong mã mà nằm trong Policy Pack có phiên bản, còn giao diện riêng của từng tổ chức (tenant) nằm trong UI Pack có phiên bản (ADR-05, ADR-19).

Bộ tài liệu thiết kế được xây trên ba nguồn đầu vào: bản đánh giá kiến trúc công nghệ 10/2026 (checklist 110 tiêu chí, 12 trục A đến L), báo cáo Ban Giám đốc 10/2026 (8 nhóm tính năng) và ba góp ý của lãnh đạo G1, G2, G3. Góp ý G1 hỏi về giao diện riêng theo tenant bằng SDUI và micro-frontend, và là góp ý ảnh hưởng trực tiếp nhất tới phân hệ web (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md)).

## Chỉ tiêu phi chức năng

Baseline mục 3 đặt mười một chỉ tiêu phi chức năng (NFR) dùng thống nhất trong cả bốn tài liệu. Bảng dưới chép lại giá trị thiết kế; cột cuối nêu ý nghĩa đối với người làm frontend, phần này là diễn giải của wiki.

| Mã | Chỉ tiêu | Giá trị thiết kế | Ý nghĩa với phân hệ web |
|---|---|---|---|
| NFR-01 | Quy mô | 6.000.000 tài khoản; 300.000 CCU toàn quốc; tối đa 5.000 tenant | Mọi tài nguyên tĩnh phải phục vụ qua CDN; mỗi lần tải dư thừa nhân với hàng trăm nghìn phiên |
| NFR-02 | Thông lượng | 15.000 request/giây đỉnh tại cổng API; chịu 30.000 khi đột biến; đọc/ghi 80/20 | Không gọi API thừa, không thăm dò (polling) dày; dùng SSE cho thông báo |
| NFR-03 | Độ trễ | Đọc p95 ≤ 300 ms; ghi p95 ≤ 800 ms; tải trang p95 ≤ 1 s; LCP ≤ 2,5 s trên 4G; tác vụ nặng trả mã theo dõi ≤ 200 ms | Tác vụ dài nhận 202 và theo dõi `/api/v1/jobs/{id}`, không chờ đồng bộ |
| NFR-04 | Khả dụng | 99,9%/tháng mỗi cell; 99,95% vùng điều phối toàn cục | Remote lỗi không được làm hỏng shell |
| NFR-05 | Khôi phục | RPO ≤ 15 phút, RTO ≤ 4 giờ khi mất trung tâm dữ liệu; RPO ≈ 0, RTO ≤ 5 phút khi mất một node CSDL | Ứng dụng phải chịu được lỗi tạm thời và thử lại an toàn nhờ `Idempotency-Key` |
| NFR-06 | Đỉnh cuối kỳ | Tính lại 1,2 triệu hồ sơ ≤ 2 giờ; sinh và ký lô 100.000 phiếu ≤ 4 giờ | Màn hình ký lô, khoá kỳ hiển thị tiến độ qua SSE thay vì tải lại |
| NFR-07 | Linh hoạt | Tenant mới ≤ 1 ngày; Policy Pack ≤ 5 ngày; UI Pack ≤ 1 ngày, không phát hành lại mã | Giao diện tenant phải thay được hoàn toàn bằng dữ liệu (token, bố cục, biểu mẫu) |
| NFR-08 | An toàn | Hồ sơ cấp độ 4 (NĐ 85/2016, TT 12/2022, TCVN 11930:2017); OWASP ASVS 4.0.3 mức L2, mức L3 cho xác thực, phiên, mật mã | CSP nghiêm, không `innerHTML`, token chỉ trong bộ nhớ |
| NFR-09 | Lưu giữ | Log 90 ngày nóng + 1 năm lạnh; kiểm toán và sổ cái ≥ 10 năm | Không ghi dữ liệu nhập của người dùng vào log trình duyệt |
| NFR-10 | Quan sát | ≥ 99% request có trace end-to-end; Four Golden Signals cho API chính | Mọi request API mang `traceparent` |
| NFR-11 | Trải nghiệm | WCAG 2.1 AA; chế độ "Đảng viên cao tuổi" (chữ ≥ 17 px, tương phản cao, ít bước); thao tác thường ngày ≤ 3 chạm | Là yêu cầu chức năng của mọi màn hình, không phải phần trang trí |

Baseline mục 3.1 suy ra thông lượng bằng định luật Little (Little's Law): với 300.000 người dùng đồng thời và thời gian suy nghĩ trung bình giữa hai request khoảng 20 giây, tải trung bình khoảng 15.000 request/giây, nhân hệ số đột biến 2 thành 30.000 request/giây. Đây là số ước tính thiết kế, không phải số đo. Cell lớn nhất (TP. Hồ Chí Minh) được ước khoảng 1,2 triệu tài khoản, 60.000 CCU và khoảng 3.000 request/giây. Con số này giải thích vì sao frontend phải tiết kiệm request: một widget trang chủ gọi thêm một API mỗi lần mở trang sẽ cộng thêm hàng nghìn request mỗi giây ở giờ cao điểm.

## Kiến trúc lai theo từng phần (ADR-01)

ADR-01 chốt rằng hệ thống không áp một kiểu kiến trúc cho toàn bộ. Lõi nghiệp vụ là một khối đơn thể chia module (Modular Monolith) viết bằng Spring Modulith, tên triển khai `mission-core` (SVC-05), gồm tám module MOD-01 đến MOD-08. Những điểm nóng hoặc vùng bảo mật riêng như tính điểm, minh chứng, sổ cái, ký số tách thành microservice. Khi mở rộng ra quy mô quốc gia, toàn bộ bộ service được nhân bản theo kiến trúc dựa trên ô (Cell-based Architecture). Theo TL-01, frontend thuộc kiểu micro-frontend; điều kiện xem lại lựa chọn này là "khi số remote lớn hơn 15, gom lại theo miền".

Với người làm web, hệ quả thực tế của ADR-01 là hầu hết API nghiệp vụ đều nằm trong một service duy nhất (`mission-core`) với tiền tố theo module, ví dụ `/api/v1/tasks` của MOD-02 hay `/api/v1/appraisals` của MOD-04. Một số API thuộc service khác như `/api/v1/files` (SVC-08), `/api/v1/esign` (SVC-10), `/api/v1/ledger` (SVC-09), `/api/v1/analytics` (SVC-15) hay `/api/v1/ai` (SVC-14), nhưng tất cả cùng đi qua cổng APISIX (SVC-01) của cell, nên trình duyệt chỉ cần biết một gốc API.

## Cell và vùng điều phối toàn cục

Baseline mục 6 chia hệ thống thành các cell, mỗi cell triển khai một bộ service đầy đủ và có CSDL riêng. Bảng dưới liệt kê các cell; phân hệ web dùng mã cell để hiển thị cho người dùng biết họ đang ở đâu (chống trang giả mạo ở SCR-0001) và để chọn gốc API.

| Mã cell | Phạm vi | Mô hình thuê bao | Quy mô dự kiến |
|---|---|---|---|
| CELL-DTW | Đảng uỷ các cơ quan Đảng Trung ương (19 đảng bộ) | silo, hạ tầng riêng khối Đảng, HSM riêng | ≈ 0,3 triệu tài khoản |
| CELL-HCM | TP. Hồ Chí Minh | silo | ≈ 1,2 triệu |
| CELL-HN | Hà Nội | silo | ≈ 1,0 triệu |
| CELL-G1 đến CELL-G4 | Nhóm tỉnh, mỗi cell 7 đến 9 tỉnh | bridge: CSDL riêng mỗi tỉnh trong cụm của cell | ≈ 0,6 đến 0,8 triệu mỗi cell |
| CELL-SH | Xã, chi bộ, đơn vị nhỏ, doanh nghiệp | pool: bảng chung + `tenant_id` + RLS | ≈ 0,8 triệu, tới hàng nghìn tenant |
| Bản cách ly | Lực lượng vũ trang, Công an | cell đơn đóng gói Helm ngoại tuyến | theo đơn vị |

Ngoài các cell còn có vùng điều phối toàn cục (global control plane) gồm tenant-registry (SVC-03), bộ định tuyến toàn cục, kho Policy Pack và UI Pack gốc đồng bộ xuống cell, CDN tài nguyên tĩnh và kho phân tích quốc gia chỉ nhận dữ liệu tổng hợp. Hai trung tâm dữ liệu được dùng: chính ở Hoà Lạc, dự phòng ở phía Nam. TL-04 đề xuất thêm cell thí điểm nội bộ `CELL-NB` phát hành trước `CELL-G4`; mã này chưa có trong baseline nên được ghi ở [câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md).

Theo TL-01 chương định tuyến cell, shell gọi `GET /api/v1/registry/resolve?tenant=` để biết tenant thuộc cell nào; nếu tenant đã chuyển sang cell khác, máy chủ trả `421` kèm địa chỉ mới và shell tự chuyển hướng. Vùng toàn cục lưu đệm ánh xạ tenant sang cell trong 5 phút. Đây là lý do cấu hình lúc chạy (`/config.json`) chứa mã cell, và lý do cùng một bản build phải chạy được ở mọi cell.

## Danh mục service

Baseline mục 5 liệt kê mười tám service. Bảng dưới rút gọn cột vai trò; cột cuối ghi tiền tố API mà frontend gặp, lấy theo quy ước API baseline mục 13 và danh mục API ở TL-01 Phụ lục C.

| Mã | Tên triển khai | Vai trò chính | Frontend gặp qua |
|---|---|---|---|
| SVC-01 | api-gateway (APISIX) | Cổng API, WAF Coraza, xác thực JWT qua JWKS, giới hạn tốc độ, định tuyến strangler | Mọi request |
| SVC-02 | iam (Keycloak 26) | Định danh, SSO, MFA, VNeID, IdP khách hàng | OIDC, `@vt/auth` |
| SVC-03 | tenant-registry (toàn cục) | Danh bạ tenant sang cell, tên miền, khám phá IdP | `/api/v1/registry/resolve` |
| SVC-04 | org-service | Cây tổ chức hai trục thời gian, nhân sự, SCIM 2.0 | `/api/v1/org` |
| SVC-05 | mission-core | Lõi nghiệp vụ, tám module MOD-01 đến MOD-08 | `/api/v1/okr`, `tasks`, `plans`, `catalog`, `products`, `evidences`, `appraisals`, `policies`, `dossiers`, `oversight`, `config`, `periods`, `ui` |
| SVC-06 | workflow-service (Flowable 7) | Luồng duyệt BPMN/DMN tầng 2, saga tầng 3 | `/api/v1/workflow/inbox`, gián tiếp qua mission-core |
| SVC-07 | scoring-service | Tính điểm, xếp loại đề xuất theo Policy Pack | Vết tính, tính thử qua `/api/v1/appraisals` |
| SVC-08 | evidence-service | Tải lên minh chứng, SHA-256, Object Lock, quét mã độc | `/api/v1/files` |
| SVC-09 | ledger-service | Sổ cái chỉ ghi, chuỗi băm, niêm phong Merkle | `/api/v1/ledger` |
| SVC-10 | esign-service | Ký số tại mốc pháp lý, ký lô | `/api/v1/esign` |
| SVC-11 | document-service | Sinh Mẫu 1A đến 1D, 2, 3, gói PDF/A | `/api/v1/dossiers/forms` |
| SVC-12 | notification-service | Thông báo đẩy, SSE/WebSocket | `/api/v1/notifications/stream` |
| SVC-13 | integration-gateway | Tích hợp vOffice, iOffice, CSDL CBCCVC, Anti-Corruption Layer | Gián tiếp |
| SVC-14 | ai-gateway | Cổng LLM chỉ đọc, RAG, OCR, gắn nhãn AI | `/api/v1/ai` |
| SVC-15 | analytics | CQRS phía đọc, ClickHouse, Cube, phát hiện bất thường | `/api/v1/analytics` |
| SVC-16 | search (OpenSearch) | Tìm kiếm toàn văn (không chứa C2/C3) | `/api/v1/search` |
| SVC-17 | web-frontend | Shell Angular 21 + remote Native Federation + plugin; phục vụ tĩnh qua CDN/nginx | Chính là phân hệ này |
| SVC-18 | mobile-app (Flutter 3) | Ứng dụng di động cho cán bộ | Dùng chung API, token thiết kế |

Tám module của `mission-core` được baseline mục 5.1 gắn với nhóm tính năng: MOD-01 `okr`, MOD-02 `task`, MOD-03 `product`, MOD-04 `appraisal`, MOD-05 `policy`, MOD-06 `dossier`, MOD-07 `oversight`, MOD-08 `config`. Mỗi module sở hữu một schema PostgreSQL cùng tên. Nhóm tính năng "Phân tích, dashboard" nằm ở SVC-15 và trợ lý AI nằm ở SVC-14.

## Vị trí của SVC-17

Baseline mô tả SVC-17 là "Shell Angular 21 + remote Native Federation + plugin; phục vụ tĩnh qua CDN/nginx", công nghệ Angular 21, PrimeNG, Formly, cel-js, không sở hữu CSDL và co giãn bằng CDN. Ba điểm sau quyết định cách làm việc hằng ngày của đội web.

Thứ nhất, SVC-17 không có dữ liệu nghiệp vụ riêng. Mọi trạng thái bền vững nằm ở máy chủ; trình duyệt chỉ giữ token trong bộ nhớ, UI Pack đã lưu đệm trong IndexedDB và các tuỳ chọn hiển thị như chế độ chữ lớn. Dữ liệu C2 không được lưu cục bộ dạng rõ (ADR-06, ADR-18).

Thứ hai, SVC-17 tiêu thụ hai sự kiện của nền tảng: EVT-13 `PolicyPackDaPhatHanh` (topic `policy.pack.published.v1`) và EVT-23 `UiPackDaPhatHanh` (topic `config.uipack.published.v1`) dùng để vô hiệu bộ đệm CDN khi tenant phát hành giao diện mới. Trình duyệt không đọc Kafka; theo TL-02 QT-18, web-frontend nhận thông báo qua kênh thời gian thực và gợi ý người dùng tải lại khi thuận tiện.

Thứ ba, SVC-17 là nơi hiện thực hoá phần lớn góp ý G1. Cách chia bốn lớp (token, SDUI có giới hạn, biểu mẫu động, plugin) được trình bày ở [Giao diện theo tenant](03-giao-dien-theo-tenant.md), còn cách tổ chức shell và remote ở [Kiến trúc frontend](02-kien-truc-frontend.md).

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Thuật ngữ](13-thuat-ngu.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
