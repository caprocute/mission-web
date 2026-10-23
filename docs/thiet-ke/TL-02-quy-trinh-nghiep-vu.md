---
title: "Đặc tả quy trình nghiệp vụ và giao diện"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Mã tài liệu TL-02 · Phiên bản 1.1 · 10/2026"
toc-title: "Mục lục"
---

# Thông tin tài liệu {-}

Tài liệu này là phần đặc tả quy trình nghiệp vụ và giao diện (TL-02) của bộ bốn tài liệu thiết kế Nền tảng đánh giá cán bộ theo OKR và KPI. Phần A trình bày các nội dung dùng chung cho toàn bộ TL-02 và chín quy trình đầu tiên, từ đăng nhập (QT-00) tới khoá kỳ (QT-08). Phần B trình bày các quy trình từ đánh giá, xếp loại (QT-09) tới cấu hình giao diện tenant (QT-18) theo cùng một khung đặc tả, nên hai phần được ghép thành một tài liệu liền mạch khi phát hành.

| Phiên bản | Ngày | Người thực hiện | Nội dung thay đổi |
|---|---|---|---|
| 0.1 | 12/10/2026 | Nhóm phân tích nghiệp vụ | Dựng khung đặc tả, danh mục quy trình và màn hình theo cơ sở thiết kế chung |
| 0.5 | 16/10/2026 | Nhóm phân tích nghiệp vụ, nhóm thiết kế giao diện | Hoàn thành lưu đồ, quy tắc nghiệp vụ và mockup QT-00 đến QT-08 |
| 1.0 | 19/10/2026 | Nhóm phân tích nghiệp vụ | Bản phát hành để rà soát cùng TL-01, TL-03, TL-04 |
| 1.1 | 23/10/2026 | Nhóm phân tích nghiệp vụ | Cập nhật theo thực tế triển khai đợt 1: phụ lục cuối ghi điểm lệch, mã bổ sung và quyết định; sửa tại chỗ nơi nhận SCIM ở QT-01 |

Bảng "Tài liệu liên quan" dưới đây cho biết tài liệu này dựa vào đâu và được dùng ở đâu. Các mã thành phần (SVC), module (MOD), sự kiện (EVT), bảng dữ liệu và trạng thái trong tài liệu này đều lấy từ cơ sở thiết kế chung; khi tài liệu cần thêm cột, bảng hay trạng thái mới, phần đề xuất được ghi riêng để người thiết kế cơ sở dữ liệu đối chiếu.

| Mã | Tên tài liệu | Quan hệ với tài liệu này |
|---|---|---|
| TL-01 | Kiến trúc tổng thể và kiến trúc kỹ thuật | Cung cấp danh mục service, quyết định kiến trúc (ADR), cơ chế sự kiện và saga mà các quy trình ở đây sử dụng |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện (tài liệu này, phần A và phần B) | Mô tả quy trình, quy tắc nghiệp vụ, màn hình, trường dữ liệu và API ở mức nghiệp vụ |
| TL-03 | Thiết kế cơ sở dữ liệu | Chi tiết hoá các bảng `schema.bảng` được tham chiếu trong bảng trường của từng màn hình |
| TL-04 | Thiết kế triển khai, vận hành và an toàn thông tin | Quy định biện pháp an toàn (SEC), quy trình vận hành (OPS), kịch bản thử tải (PERF) mà các quy trình ở đây viện dẫn |
| Đầu vào 1 | Báo cáo Ban Giám đốc về Nền tảng đánh giá cán bộ theo OKR và KPI (10/2026, 74 trang trình chiếu) | Nguồn mục tiêu sản phẩm, tám nhóm tính năng, giao diện minh hoạ |
| Đầu vào 2 | Bản đánh giá kiến trúc công nghệ (10/2026, 12 trục, 110 tiêu chí) | Danh mục kiểm tra mà thiết kế phải đáp ứng |

# Giới thiệu

## Mục đích của tài liệu

Tài liệu đặc tả quy trình nghiệp vụ và giao diện mô tả cách cán bộ, công chức, viên chức và đảng viên làm việc với nền tảng trong một kỳ đánh giá: từ lúc đăng nhập, lập mục tiêu và kế hoạch, nhận và thực hiện nhiệm vụ, nộp sản phẩm, cho tới khi kỳ được khoá để tính điểm. Mỗi quy trình được viết sao cho ba nhóm người đọc cùng dùng được. Người làm nghiệp vụ ở cơ quan tổ chức cán bộ đọc phần thông tin chung, lưu đồ và quy tắc nghiệp vụ để xác nhận rằng nền tảng làm đúng quy định. Người thiết kế giao diện và lập trình viên frontend đọc phần đặc tả màn hình để dựng giao diện và biết từng trường lấy dữ liệu ở đâu, kiểm tra gì. Người thiết kế service và cơ sở dữ liệu đọc bảng chức năng, sự kiện và phần liên kết thiết kế để biết mỗi thao tác gọi API nào, ghi vào bảng nào và phát sự kiện gì.

Tài liệu không thay thế các văn bản quy định của Đảng và Nhà nước. Khi một quy tắc nghiệp vụ dựa vào điều khoản cụ thể, tài liệu ghi rõ căn cứ để người duyệt đối chiếu. Khi quy định chưa rõ hoặc đang ở dạng dự thảo, tài liệu ghi quy tắc đó là tham số của gói chính sách (Policy Pack) để cơ quan có thẩm quyền quyết định giá trị cụ thể mà không phải sửa mã nguồn.

## Phạm vi

Phần A của TL-02 bao gồm năm chương mở đầu dùng chung cho cả tài liệu và chín quy trình từ QT-00 đến QT-08 cùng 24 màn hình từ SCR-0001 đến SCR-0801. Chín quy trình này bao phủ toàn bộ nửa đầu vòng đời của một kỳ đánh giá: định danh người dùng và chọn ngữ cảnh làm việc, đưa một tổ chức (tenant) vào vận hành cùng cây tổ chức và dữ liệu nhân sự, lập và duyệt mục tiêu theo phương pháp OKR (Objectives and Key Results), cập nhật kết quả then chốt hằng tuần, lập và duyệt kế hoạch công tác quý theo Bảng 1 của Phụ lục 1, giao và nhận nhiệm vụ, theo dõi tiến độ, nộp và nghiệm thu minh chứng, và cuối cùng là khoá kỳ để tính điểm hàng loạt.

Phần B (QT-09 đến QT-18) bao gồm tự đánh giá, thẩm định, quyết định xếp loại quý và năm, kiểm điểm và hồ sơ số, khiếu nại, hậu kiểm, cảnh báo bất thường, soạn Policy Pack, bảng điều hành, trợ lý AI và cấu hình giao diện tenant. Một số quy trình ở phần A chuyển giao dữ liệu cho phần B; ví dụ QT-08 kết thúc bằng việc mở QT-09, còn quy tắc tính điểm mà QT-07 dùng để xem trước điểm được soạn và phát hành ở QT-15. Những chỗ chuyển giao như vậy được ghi rõ trong mục quan hệ giữa các quy trình.

Tài liệu áp dụng cho cả hai luồng đánh giá mà nền tảng hỗ trợ. Luồng Đảng (`DANG`) đánh giá tổ chức đảng và đảng viên theo thang 100 điểm của dự thảo Quy định mới. Luồng Nhà nước (`NHA_NUOC`) đánh giá cán bộ, công chức, viên chức theo NĐ 335/2025 và NĐ 233/2026 với 30 điểm tiêu chí chung và 70 điểm kết quả thực hiện nhiệm vụ. Một người có thể đồng thời thuộc cả hai luồng, ví dụ một công chức là đảng viên sinh hoạt ở chi bộ cơ quan; khi đó dữ liệu nhiệm vụ và minh chứng chỉ nhập một lần và được dùng cho cả hai luồng như trình bày ở chương Tổng quan nghiệp vụ.

## Căn cứ

Các quy trình trong tài liệu dựa trên những văn bản sau. Số hiệu và ngày ban hành được ghi theo tài liệu đầu vào mà nhóm dự án nhận được; một số văn bản mới ban hành hoặc đang ở dạng dự thảo, nên người duyệt cần đối chiếu số hiệu, trích yếu và điều khoản với bản gốc trước khi tài liệu được phê duyệt chính thức.

| Ký hiệu dùng trong tài liệu | Văn bản | Nội dung được sử dụng |
|---|---|---|
| QĐ 366 | Quy định số 366-QĐ/TW của Bộ Chính trị về kiểm điểm, đánh giá, xếp loại chất lượng hằng năm đối với tập thể, cá nhân trong hệ thống chính trị | Nguyên tắc đánh giá, các mức xếp loại, trách nhiệm của tập thể và người đứng đầu |
| HD 43 | Hướng dẫn số 43-HD/BTCTW của Ban Tổ chức Trung ương | Tiêu chí, quy trình kiểm điểm, đánh giá theo QĐ 366 |
| HD 02 | Hướng dẫn số 02-HD/BTCTW của Ban Tổ chức Trung ương | Hướng dẫn kiểm điểm, đánh giá, xếp loại tổ chức đảng, đảng viên |
| Quy định mới | Dự thảo Quy định về đánh giá cán bộ theo kết quả thực hiện nhiệm vụ, kèm Phụ lục 1 đến Phụ lục 6 | OKR (PL 1), Bảng 1 kế hoạch công tác, Bảng 3 danh mục công việc, cách tính số lượng, chất lượng, tiến độ, khối đối tượng (PL 2A), dữ liệu và tách vai trò (PL 4), xử lý trường hợp điều động (PL 6) |
| Mẫu 1A đến Mẫu 3 | Các biểu mẫu kèm dự thảo Quy định mới | Phiếu tự đánh giá, bảng chấm điểm, phiếu xếp loại được sinh ở QT-08 và QT-09 |
| Luật CBCC 2025 | Luật Cán bộ, công chức (sửa đổi) năm 2025 | Nguyên tắc đánh giá công chức theo kết quả, sản phẩm cụ thể |
| NĐ 335/2025 | Nghị định số 335/2025/NĐ-CP về đánh giá, xếp loại chất lượng cán bộ, công chức, viên chức | Luồng Nhà nước, cơ cấu 30 điểm tiêu chí chung và 70 điểm kết quả nhiệm vụ, cách tính theo số lượng, chất lượng, tiến độ |
| NĐ 233/2026 | Nghị định số 233/2026/NĐ-CP về đánh giá viên chức trong đơn vị sự nghiệp công lập | Luồng Nhà nước áp dụng cho viên chức, gói chính sách chuyên ngành |
| "Thông tư 22" | Văn bản người dùng gọi là "Thông tư 22" trong yêu cầu ban đầu | Được hiểu là văn bản hướng dẫn thi hành ở cấp bộ; nhóm dự án chưa nhận được bản gốc nên chưa trích điều khoản cụ thể |

Văn bản người dùng gọi là "Thông tư 22" được giữ nguyên tên gọi để người đặt hàng nhận ra. Vì chưa có bản gốc, tài liệu không gán quy tắc nào cho văn bản này; mọi quy tắc liên quan được đặt thành tham số Policy Pack (QT-15) để khi có văn bản chính thức chỉ cần cập nhật gói chính sách và chạy lại bộ ca kiểm thử vàng.

## Khung trình bày và giả định về biểu mẫu

Yêu cầu ban đầu đề nghị trình bày theo "biểu mẫu như dự án MRC". Nhóm dự án không tìm thấy mẫu MRC trong hồ sơ dự án, nên tài liệu dùng cấu trúc đặc tả quy trình phổ biến của các dự án công nghệ thông tin khối nhà nước tại Viettel, như đã ghi trong cơ sở thiết kế chung (mục 2, giả định 6). Theo cấu trúc này, mỗi quy trình lần lượt có thông tin chung, lưu đồ làn bơi, bảng mô tả các bước, trạng thái đối tượng, quy tắc nghiệp vụ, ngoại lệ và xử lý, đặc tả màn hình và liên kết thiết kế. Mỗi màn hình có mockup, mô tả các vùng theo số chú thích, bảng trường dữ liệu, bảng chức năng và bảng thông báo. Khi có mẫu MRC chính thức, chỉ cần đổi khung trình bày; nội dung đặc tả giữ nguyên.

# Thuật ngữ và chữ viết tắt

Bảng sau giải thích các thuật ngữ được dùng nhiều trong tài liệu. Một số thuật ngữ có cả tên tiếng Việt và tên tiếng Anh; tài liệu ưu tiên tên tiếng Việt và ghi tên tiếng Anh trong ngoặc ở lần đầu.

| Thuật ngữ | Giải thích |
|---|---|
| Mục tiêu (Objective, O) | Chuyển biến định tính mà đơn vị hoặc cá nhân cần đạt trong kỳ, ngắn gọn, có căn cứ và gắn với mục tiêu cấp trên |
| Kết quả then chốt (Key Result, KR) | Kết quả đo được bằng con số, có giá trị đầu, chỉ tiêu, đơn vị đo và hạn, cho biết mục tiêu đã đạt tới đâu |
| Check-in KR | Lần cập nhật định kỳ giá trị hiện tại của KR kèm mức tự tin Cao, Trung bình hoặc Thấp |
| Phân rã ba tầng | Cách liên kết từ mục tiêu (Tầng 1) qua chương trình, đề án (Tầng 2) tới nhiệm vụ cụ thể trong kế hoạch quý (Tầng 3) |
| Bảng 1 | Bảng kế hoạch công tác quý trong Phụ lục 1 dự thảo Quy định mới, liệt kê công việc, đơn vị chủ trì, phối hợp, sản phẩm, hạn |
| Bảng 3 | Danh mục công việc theo vị trí việc làm, có mã, sản phẩm, đơn vị tính, mức khó, điểm chuẩn, tiêu chuẩn chất lượng, minh chứng |
| Nhiệm vụ thường xuyên, then chốt | Hai nhóm nhiệm vụ trong kế hoạch; tỷ trọng giữa hai nhóm do cấp có thẩm quyền quyết định và được cấu hình trong Policy Pack |
| Minh chứng | Tệp hoặc liên kết chứng minh sản phẩm của nhiệm vụ đã hoàn thành, có mã băm SHA-256 và phiên bản |
| Nghiệm thu | Việc người có thẩm quyền xác nhận sản phẩm và chọn mức chất lượng 1; 0,75; 0,5; 0,25 hoặc 0 |
| A, B, C | Ba thành phần điểm của nhiệm vụ: số lượng (A), chất lượng (B), tiến độ (C) |
| Kỳ đánh giá | Khoảng thời gian tính điểm (quý hoặc năm) có trạng thái `MO`, `DANG_KHOA`, `DA_KHOA`, `MO_LAI_HAU_KIEM` |
| Khoá kỳ | Tiến trình chốt dữ liệu của kỳ, tính điểm hàng loạt, sinh biểu mẫu và ký số, chạy dưới dạng saga |
| Saga | Chuỗi bước giao dịch phân tán, mỗi bước có hành động bù trừ (compensation) để hoàn tác khi bước sau thất bại |
| Tenant | Một tổ chức thuê bao dùng nền tảng với dữ liệu, cấu hình và giao diện riêng, ví dụ một tỉnh hoặc một đảng uỷ |
| Cell | Một bộ triển khai đầy đủ các service phục vụ một nhóm tenant; lỗi ở một cell không lan sang cell khác |
| Silo, bridge, pool | Ba mô hình thuê bao: hạ tầng riêng, cơ sở dữ liệu riêng trong cụm chung, bảng chung có phân tách theo dòng |
| Policy Pack | Gói chính sách có phiên bản chứa công thức tính điểm, bảng quyết định xếp loại, tham số thời hạn, luồng duyệt |
| UI Pack | Gói giao diện có phiên bản chứa token thiết kế, bố cục trang và biểu mẫu động của một tenant |
| SDUI | Giao diện do máy chủ điều khiển bố cục (Server-Driven UI), áp dụng có chọn lọc theo ADR-05 |
| Sổ cái | Nhật ký chuỗi băm chỉ được ghi thêm do ledger-service (SVC-09) quản lý, dùng để chứng minh dữ liệu không bị sửa |
| Hai trục thời gian | Cách lưu dữ liệu tổ chức với thời gian hiệu lực (`valid_from`, `valid_to`) và thời gian ghi nhận (`recorded_from`, `recorded_to`) |
| MFA bước lên | Yêu cầu xác thực thêm một yếu tố (step-up authentication) khi người dùng thực hiện thao tác quan trọng |
| SSO | Đăng nhập một lần (Single Sign-On) qua máy chủ định danh của cell |
| VNeID | Ứng dụng định danh điện tử quốc gia; mức 2 là tài khoản đã xác thực trực tiếp |
| SCIM | Giao thức chuẩn đồng bộ danh tính (System for Cross-domain Identity Management) phiên bản 2.0 |
| CSDL CBCCVC | Cơ sở dữ liệu quốc gia về cán bộ, công chức, viên chức, kết nối qua nền tảng chia sẻ dữ liệu quốc gia (NDXP) |
| OCR | Nhận dạng ký tự quang học, dùng để đọc số, ngày, trích yếu văn bản từ ảnh chụp |
| ETag, If-Match | Cơ chế khoá lạc quan của API: máy chủ trả phiên bản dòng, client gửi lại khi cập nhật, sai phiên bản thì trả mã 409 |
| Idempotency-Key | Khoá chống ghi trùng do client sinh cho mỗi thao tác ghi; gửi lại cùng khoá không tạo bản ghi mới |

Bảng chữ viết tắt dưới đây chỉ gồm những chữ được dùng trong phần A.

| Viết tắt | Nghĩa đầy đủ |
|---|---|
| AC | Tác nhân (actor) |
| BR | Quy tắc nghiệp vụ (business rule) |
| EVT | Sự kiện miền (domain event) |
| MSG | Thông báo hiển thị cho người dùng |
| MOD | Module của lõi nghiệp vụ mission-core |
| SVC | Service (thành phần triển khai độc lập) |
| QT | Quy trình nghiệp vụ |
| SCR | Màn hình |
| PL | Phụ lục của dự thảo Quy định mới |
| KPI | Chỉ số đánh giá kết quả nhiệm vụ, ở đây là điểm nhiệm vụ tính từ A, B, C |
| CBCCVC | Cán bộ, công chức, viên chức |
| IdP | Nhà cung cấp định danh (Identity Provider) |
| AAL2 | Mức bảo đảm xác thực 2 (Authenticator Assurance Level 2) theo NIST SP 800-63B |
| WCAG | Hướng dẫn khả năng truy cập nội dung web (Web Content Accessibility Guidelines) |

# Tác nhân và phân quyền

## Danh sách tác nhân

Nền tảng có mười hai tác nhân. Mười tác nhân đầu là con người với vai trò khác nhau trong tổ chức; tác nhân AC-11 là các hệ thống ngoài kết nối qua cổng tích hợp; tác nhân AC-12 là trợ lý trí tuệ nhân tạo chỉ có quyền đọc. Mỗi tác nhân được ánh xạ sang một vai trò kỹ thuật trong máy chủ định danh Keycloak và trong bộ chính sách Open Policy Agent (OPA). Một người có thể giữ nhiều vai trò cùng lúc thông qua các phân công khác nhau; ví dụ một Phó Trưởng ban vừa là người được đánh giá (AC-06) vừa là người giao việc và nghiệm thu (AC-05) cho chuyên viên trong ban.

| Mã | Tác nhân | Vai trò hệ thống | Quyền chính trong phần A |
|---|---|---|---|
| AC-01 | Quản trị nền tảng (Viettel) | `platform_admin` | Khởi tạo tenant, chọn cell và mô hình thuê bao, gắn Policy Pack gốc và UI Pack, theo dõi dung lượng cell |
| AC-02 | Quản trị tenant | `tenant_admin` | Khai báo IdP, nhập cây tổ chức, đối soát nhân sự, tạo kỳ đánh giá, khởi chạy khoá kỳ |
| AC-03 | Quản trị nghiệp vụ, cấu hình | `policy_author` | Soạn danh mục công việc mẫu, tham số Policy Pack (chi tiết ở QT-15 phần B) |
| AC-04 | Lãnh đạo, người đứng đầu | `leader` | Duyệt OKR, duyệt kế hoạch quý có ký số, phê duyệt lệnh khoá kỳ |
| AC-05 | Cán bộ quản lý trực tiếp | `manager` | Soạn OKR đơn vị, lập kế hoạch, giao việc, duyệt đề nghị gia hạn, nghiệm thu sản phẩm |
| AC-06 | Cán bộ, công chức, viên chức, đảng viên | `member` | Check-in KR, nhận việc, cập nhật tiến độ, báo điểm nghẽn, nộp minh chứng, đăng ký nhiệm vụ |
| AC-07 | Cơ quan tổ chức cán bộ | `appraiser` | Xem dữ liệu phục vụ thẩm định; trong phần A chỉ đọc kế hoạch và minh chứng |
| AC-08 | Cấp có thẩm quyền quyết định | `decider` | Không có thao tác ghi trong phần A |
| AC-09 | Người duyệt thứ hai | `second_approver` | Duyệt thứ hai khi huỷ và bù trừ toàn bộ saga khoá kỳ |
| AC-10 | Cơ quan kiểm tra, giám sát | `inspector` | Đọc sổ cái, nhật ký đồng bộ, tiến trình khoá kỳ |
| AC-11 | Hệ thống ngoài | client credentials | Gửi văn bản chỉ đạo, cung cấp dữ liệu nhân sự, định danh VNeID, ký số MySign |
| AC-12 | Trợ lý AI | `ai_readonly` | Đọc dữ liệu được phép để soát KR, trích nhiệm vụ từ văn bản, đọc minh chứng bằng OCR; không ghi |

Quyền trong bảng trên là quyền theo vai trò. Ngoài vai trò, mọi yêu cầu còn được kiểm tra theo thuộc tính (ABAC): người dùng chỉ thấy và thao tác trên dữ liệu của đơn vị mình và đơn vị cấp dưới theo cây tổ chức, chỉ trong luồng (`DANG` hoặc `NHA_NUOC`) mà phân công của họ cho phép, và chỉ với lớp dữ liệu (C0 đến C3) mà vai trò được cấp. Ví dụ, một Trưởng ban có vai trò `leader` duyệt được kế hoạch của ban mình nhưng không duyệt được kế hoạch của ban khác cùng cấp, dù cùng vai trò.

## Nguyên tắc tách vai trò

Phụ lục 4 mục V.1 của dự thảo Quy định mới yêu cầu tách người khởi tạo dữ liệu với người xác nhận dữ liệu đó. Nền tảng cụ thể hoá yêu cầu này thành năm vai trò không được trùng nhau trên cùng một hồ sơ hoặc cùng một nhiệm vụ: người khởi tạo, người nghiệm thu, người thẩm định, người quyết định và người khoá kỳ. Như vậy, người thực hiện nhiệm vụ không tự nghiệm thu sản phẩm của mình, người trình kế hoạch không tự duyệt kế hoạch, người phê duyệt lệnh khoá kỳ không đồng thời là người khởi chạy lệnh đó.

Việc tách vai trò được kiểm soát ở ba lớp. Ở lớp giao diện, màn hình ẩn nút "Phê duyệt" hoặc "Xác nhận nghiệm thu" khi người đang đăng nhập trùng với người khởi tạo. Ở lớp API, chính sách OPA từ chối yêu cầu với lỗi `403` và mã lỗi nghiệp vụ tương ứng, kể cả khi người dùng gọi API trực tiếp. Ở lớp dữ liệu, sổ cái ghi lại người thực hiện từng bước nên cơ quan kiểm tra (AC-10) có thể truy vết sau này. Trường hợp đơn vị quá nhỏ không đủ người để tách vai trò, ví dụ một chi bộ có ba đảng viên, Policy Pack cho phép cấp trên trực tiếp đảm nhận vai trò nghiệm thu; ngoại lệ này luôn được ghi vết và hiển thị trên bảng cảnh báo.

## Ngữ cảnh làm việc

Mỗi phiên làm việc gắn với một ngữ cảnh gồm tenant, đơn vị, phân công và vai trò. Người có nhiều phân công, ví dụ chuyên viên Ban Tổ chức kiêm Bí thư Chi bộ 3, chọn ngữ cảnh ngay sau khi đăng nhập (QT-00) và có thể đổi ngữ cảnh bất cứ lúc nào ở thanh trên. Ngữ cảnh quyết định menu, dữ liệu hiển thị và luồng đánh giá áp dụng. Việc chuyển ngữ cảnh không cần đăng nhập lại, nhưng nếu ngữ cảnh mới yêu cầu mức xác thực cao hơn, ví dụ vai trò thẩm định yêu cầu AAL2, hệ thống yêu cầu MFA bước lên trước khi chuyển.

# Tổng quan nghiệp vụ

## Vòng đời một kỳ đánh giá

Nền tảng tổ chức toàn bộ công việc của cán bộ quanh một kỳ đánh giá. Kỳ đánh giá thường là một quý; kỳ năm được tổng hợp từ bốn kỳ quý theo quy tắc liên thông quý và năm ở QT-10. Trong mỗi kỳ, dữ liệu đi theo một chuỗi liên tục: căn cứ chỉ đạo sinh ra mục tiêu, mục tiêu sinh ra kế hoạch, kế hoạch sinh ra nhiệm vụ, nhiệm vụ sinh ra sản phẩm và minh chứng, minh chứng được nghiệm thu thành ba thành phần điểm, và các thành phần điểm được chốt lại khi kỳ khoá để làm đầu vào cho đánh giá, xếp loại.

![Hình 4.1. Vòng đời một kỳ đánh giá từ căn cứ tới hậu kiểm](nv-vong-doi.png){width=16cm}

Hình 4.1 chia vòng đời thành hai chặng. Chặng thứ nhất, "Lập mục tiêu, kế hoạch và thực hiện", diễn ra trong suốt kỳ và thuộc phạm vi phần A: đơn vị lập mục tiêu và kết quả then chốt từ căn cứ (QT-02), cập nhật KR hằng tuần (QT-03), lập kế hoạch quý theo Bảng 1 (QT-04), giao việc (QT-05), theo dõi tiến độ (QT-06), nộp và nghiệm thu minh chứng theo năm mức chất lượng (QT-07). Chặng thứ hai, "Chốt kỳ và đánh giá", bắt đầu khi kỳ kết thúc: khoá kỳ bằng saga (QT-08), sau đó tự đánh giá, thẩm định, quyết định và ký số (QT-09, QT-10), và cuối cùng là hậu kiểm, khiếu nại (QT-12, QT-13). Đường nét đứt nối tới sổ cái cho thấy mọi thao tác ở cả hai chặng đều được ghi vào nhật ký chuỗi băm của ledger-service (SVC-09), nên kết quả đánh giá luôn truy ngược được tới từng minh chứng và từng người xác nhận.

Điểm quan trọng của vòng đời này là điểm số không được nhập tay ở cuối kỳ. Điểm của mỗi nhiệm vụ được tính dần ngay khi minh chứng được xác nhận, theo công thức trong Policy Pack đang gắn cho kỳ; khoá kỳ chỉ chốt lại và tính lại toàn bộ trên ảnh chụp dữ liệu đã đóng băng. Cách làm này vừa giảm tải đột biến cuối kỳ, vừa bảo đảm cán bộ thấy điểm dự kiến của mình trong suốt quý thay vì chỉ biết kết quả khi đã muộn.

## Quan hệ giữa các quy trình

Mười chín quy trình của nền tảng không độc lập với nhau. Một số quy trình là điều kiện tiên quyết của quy trình khác, một số quy trình nhận sự kiện từ quy trình khác để tự động bắt đầu. Hình 4.2 thể hiện các quan hệ chính.

![Hình 4.2. Quan hệ giữa các quy trình QT-00 đến QT-18](nv-quan-he-qt.png){width=16cm}

Nhóm "Nền tảng" gồm QT-00, QT-01, QT-15 và QT-18 là nhóm phải có trước. Một tenant phải được khởi tạo cùng cây tổ chức và dữ liệu nhân sự (QT-01) thì người dùng mới đăng nhập được (QT-00); Policy Pack phải được phát hành (QT-15) thì kế hoạch quý mới có danh mục, tỷ trọng và hạn duyệt để kiểm tra (QT-04); UI Pack phải được phát hành (QT-18) thì giao diện của tenant mới có bố cục và biểu mẫu riêng. Nhóm "Mục tiêu và kế hoạch" nối tiếp: OKR được duyệt (QT-02) mở ra check-in (QT-03) và làm căn cứ cho kế hoạch quý (QT-04). Nhóm "Thực hiện" đi theo chuỗi giao việc (QT-05), tiến độ (QT-06), minh chứng (QT-07). Cả kết quả check-in và kết quả nghiệm thu đều đổ vào khoá kỳ (QT-08), từ đó mở ra đánh giá quý (QT-09) và các quy trình sau.

Quan hệ ngược duy nhất là đường nét đứt từ nhóm hậu kiểm về QT-08. Khi cơ quan kiểm tra phát hiện sai sót sau khi kỳ đã khoá, kỳ có thể được mở lại hậu kiểm với hai người duyệt (QT-13); sau khi sửa xong, kỳ được khoá lại bằng chính saga của QT-08 nhưng chỉ tính lại phần hồ sơ bị ảnh hưởng.

Quan hệ giữa các quy trình được hiện thực bằng sự kiện miền thay vì gọi trực tiếp. Ví dụ, khi kế hoạch được duyệt ở QT-04, module task (MOD-02) phát sự kiện KeHoachDaDuyet (EVT-03); chính module này tiêu thụ sự kiện để sinh các nhiệm vụ ở trạng thái `NHAP` cho QT-05, đồng thời analytics (SVC-15) cập nhật bảng điều hành và ledger-service (SVC-09) ghi sổ cái. Nhờ vậy, một quy trình bị chậm không làm treo quy trình khác.

## Lịch nghiệp vụ của một quý

Các mốc thời gian của một quý được quy định một phần trong văn bản và một phần do cấp có thẩm quyền quyết định. Nền tảng coi mọi mốc là tham số của Policy Pack để mỗi tenant có thể điều chỉnh trong giới hạn văn bản cho phép. Hai mốc cứng được dùng trong toàn tài liệu là: kế hoạch công tác quý phải được duyệt trước khi hết tuần thứ hai của tháng đầu quý, và kết quả đánh giá quý trước phải được báo cáo trước ngày 10 của tháng đầu quý sau.

![Hình 4.3. Lịch nghiệp vụ mẫu của Quý I/2027](nv-lich-quy.png){width=16cm}

Hình 4.3 minh hoạ lịch Quý I/2027. Ngày 04/01/2027 là ngày làm việc đầu tiên của quý, cũng là ngày khoá kỳ Quý IV/2026; trong tuần đó, hai việc chạy song song. Một mặt, cán bộ tự đánh giá, cơ quan tổ chức thẩm định và cấp có thẩm quyền quyết định xếp loại Quý IV/2026 để kịp báo cáo trước ngày 10/01/2027 (mốc hiển thị là 09/01). Mặt khác, các đơn vị soạn, duyệt OKR và kế hoạch Quý I/2027 để kịp hạn duyệt kế hoạch ngày 08/01/2027. Từ tuần thứ hai, giao việc, check-in hằng tuần và nộp minh chứng diễn ra liên tục tới cuối quý; khoảng giữa quý là thời gian điều chỉnh kế hoạch nếu có chỉ đạo mới. Hạn nộp minh chứng là ngày cuối quý 31/03/2027, hạn nghiệm thu tồn đọng là 02/04/2027, và kỳ Quý I/2027 được khoá ngày 05/04/2027.

Cách tính hạn duyệt kế hoạch cần nói rõ vì văn bản dùng cụm từ "trước tuần thứ hai của tháng đầu quý" mà chưa định nghĩa tuần. Tài liệu chọn cách hiểu: tuần thứ nhất là tuần lịch (thứ Hai đến Chủ nhật) chứa ngày đầu tháng, hạn duyệt là ngày làm việc cuối cùng của tuần thứ hai. Theo cách này, hạn của Quý IV/2026 là thứ Sáu 09/10/2026 và hạn của Quý I/2027 là thứ Sáu 08/01/2027. Đây là tham số `plan_approval_deadline_rule` trong Policy Pack; nếu cơ quan có thẩm quyền hiểu khác, ví dụ tính tuần từ ngày đầu tháng, chỉ cần đổi tham số. Cách hiểu này cần được đối chiếu với điều khoản về kế hoạch công tác trong dự thảo Quy định mới trước khi áp dụng chính thức.

## Hai luồng đánh giá dùng chung dữ liệu

Nhiều cán bộ thuộc đồng thời luồng Đảng và luồng Nhà nước. Một công chức Sở Nội vụ là đảng viên sinh hoạt tại chi bộ cơ quan sẽ được đánh giá công chức theo NĐ 335/2025 và đánh giá đảng viên theo QĐ 366 và HD 02. Nếu mỗi luồng yêu cầu nhập nhiệm vụ và minh chứng riêng, cán bộ phải làm hai lần cùng một việc và hai kết quả dễ mâu thuẫn. Nền tảng áp dụng nguyên tắc "một dữ liệu, một lần nhập": nhiệm vụ, sản phẩm, minh chứng và kết quả nghiệm thu chỉ được nhập một lần, sau đó mỗi luồng áp dụng công thức của mình lên cùng dữ liệu đó.

![Hình 4.4. Hai luồng đánh giá dùng chung dữ liệu nhập một lần](nv-hai-luong.png){width=16cm}

Hình 4.4 cho thấy ba khối dữ liệu dùng chung là nhiệm vụ (`task.task`), phiên bản minh chứng (`product.evidence_version`) và kết quả nghiệm thu với ba thành phần A, B, C (`product.acceptance`). scoring-service (SVC-07) đọc bảng gắn chính sách (`policy.policy_binding`) để biết người này, trong kỳ này, thuộc luồng nào và áp dụng phiên bản Policy Pack nào. Với luồng Đảng, điểm kết quả nhiệm vụ tính trên thang 100 và ngưỡng xếp loại mặc định là 90, 80, 60. Với luồng Nhà nước, điểm gồm 30 điểm tiêu chí chung và 70 điểm kết quả nhiệm vụ, ngưỡng mặc định là 90, 70, 50. Mỗi luồng sinh một hồ sơ đánh giá riêng (`appraisal.appraisal_profile`) và kết quả của mỗi luồng phục vụ mục đích riêng: kết quả luồng Đảng dùng cho công tác cán bộ như bố trí, quy hoạch; kết quả luồng Nhà nước dùng cho thu nhập tăng thêm, thi đua và xử lý. Các ngưỡng nêu trên là giá trị cấu hình mặc định theo tài liệu đầu vào, được quản lý trong bảng quyết định xếp loại của Policy Pack (QT-15).

Hệ quả thiết kế của nguyên tắc này xuất hiện ở nhiều quy trình của phần A. Ở QT-04, mỗi dòng kế hoạch ghi rõ luồng áp dụng để tỷ trọng nhóm được tính đúng. Ở QT-05, một văn bản chỉ đạo chỉ sinh một nhiệm vụ dù người chủ trì thuộc hai luồng. Ở QT-07, người nghiệm thu chỉ chọn mức chất lượng một lần. Ở QT-08, saga tính điểm cho cả hai luồng trong cùng một lần chạy trên cùng ảnh chụp dữ liệu.

# Quy ước đặc tả và giao diện chung

## Cách đọc đặc tả một quy trình

Mỗi quy trình trong tài liệu được viết theo cùng một khung gồm tám mục. Mục "Thông tin chung" là bảng tóm tắt mã, tên, mục đích, căn cứ, tác nhân, tần suất, điều kiện bắt đầu, kết thúc, đầu vào, đầu ra và chỉ số đo hiệu quả, kèm một đoạn bối cảnh giải thích vì sao quy trình cần như vậy. Mục "Lưu đồ quy trình" là sơ đồ làn bơi, mỗi làn là một tác nhân hoặc một nhóm service. Mục "Mô tả các bước" là bảng các bước đánh số khớp với lưu đồ, ghi rõ màn hình, sự kiện và thời hạn của từng bước. Mục "Trạng thái đối tượng" mô tả máy trạng thái của đối tượng chính mà quy trình làm thay đổi, theo trạng thái chuẩn của cơ sở thiết kế chung. Mục "Quy tắc nghiệp vụ" liệt kê các quy tắc có mã BR, căn cứ và cách hệ thống kiểm soát. Mục "Ngoại lệ và xử lý" mô tả các tình huống lệch khỏi luồng chính. Mục "Đặc tả màn hình" mô tả từng màn hình. Mục "Liên kết thiết kế" nối quy trình với module, service, bảng dữ liệu, sự kiện và biện pháp an toàn thông tin ở các tài liệu khác.

Quy tắc nghiệp vụ được đánh mã `BR-xx-yy`, trong đó `xx` là số quy trình và `yy` là số thứ tự trong quy trình. Cột "Cách hệ thống kiểm soát" dùng ba mức. Mức **chặn** nghĩa là hệ thống không cho thực hiện thao tác và trả lỗi; mức **cảnh báo** nghĩa là hệ thống cho thực hiện nhưng hiển thị cảnh báo, có thể yêu cầu ghi lý do và đưa cảnh báo tới cấp duyệt; mức **ghi vết** nghĩa là hệ thống không can thiệp nhưng ghi nhận vào sổ cái hoặc bảng cảnh báo để hậu kiểm. Thông báo hiển thị cho người dùng được đánh mã `MSG-xx-yy` theo cùng quy ước.

## Cách đọc đặc tả một màn hình

Mỗi màn hình bắt đầu bằng bảng thông tin (mục đích, tác nhân, đường vào từ menu, quyền cần có, thiết bị hỗ trợ), sau đó là hình mockup. Trên mockup có các vòng tròn đỏ đánh số; phần mô tả các vùng giải thích từng vùng theo số đó. Bảng trường liệt kê các trường dữ liệu chính với kiểu, tính bắt buộc, định dạng, mặc định, ràng buộc và nguồn dữ liệu viết dạng `schema.bảng.cột`. Những cột chưa có trong danh sách bảng của cơ sở thiết kế chung là cột đề xuất mới; toàn bộ đề xuất được tổng hợp trong tệp ghi chú kèm tài liệu để TL-03 chi tiết hoá. Bảng chức năng liệt kê nút hoặc hành động, điều kiện hiển thị, xử lý, API và sự kiện phát ra. Bảng thông báo liệt kê nội dung và thời điểm hiển thị.

Dữ liệu trong mockup là dữ liệu giả lập nhưng được giữ nhất quán giữa các màn hình. Người dùng chính trong ví dụ là đồng chí Nguyễn Văn An, chuyên viên Ban Tổ chức Đảng uỷ khối thuộc tenant "Đảng uỷ các cơ quan Đảng Trung ương", người giao việc và nghiệm thu là đồng chí Lê Minh Châu, Phó Trưởng ban, người đứng đầu ban là đồng chí Trần Thị Bình, Trưởng ban. Ngày hiện tại trong phần lớn mockup là thứ Hai 19/10/2026, tuần 4 trên 13 tuần của Quý IV/2026. Một số màn hình mô tả thời điểm khác của vòng đời, ví dụ màn hình duyệt OKR Quý I/2027 (ngày 05/01/2027) hay màn hình khoá kỳ Quý IV/2026 (ngày 04/01/2027); khi đó phần mô tả ghi rõ thời điểm của tình huống.

## Bố cục chung và hệ thống thiết kế

Màn hình máy tính dùng bố cục ba vùng. Thanh trên cùng cao 56 điểm ảnh chứa biểu trưng "viettel." màu đỏ, đường dẫn vị trí (breadcrumb), ô tìm kiếm toàn cục, nhãn tenant đang làm việc, nhãn ngữ cảnh đơn vị và vai trò (bấm để đổi), nút chuyển chế độ chữ lớn "Aa", chuông thông báo có số đếm và ảnh đại diện. Menu trái rộng 232 điểm ảnh chia nhóm "Điều hành", "Mục tiêu và nhiệm vụ", "Đánh giá", "Quản trị"; mục đang mở được tô nền đỏ nhạt và viền trái đỏ; các mục có việc chờ hiển thị số đếm. Vùng nội dung chính có tiêu đề trang, dòng mô tả ngữ cảnh, các nút hành động bên phải tiêu đề, sau đó là các thẻ (card) bo góc 8 điểm ảnh. Người quản trị nền tảng và quản trị tenant thấy menu quản trị riêng gồm các nhóm "Nền tảng", "Tenant", "Giám sát".

Hệ thống thiết kế (design system) theo nhận diện Viettel. Màu chủ đạo là đỏ Viettel `#EE0033` dùng cho nút chính, mục đang chọn và điểm nhấn; nền trang xám rất nhạt; chữ chính màu xám đậm gần đen. Phông chữ là phông không chân có đủ dấu tiếng Việt, cỡ chữ cơ sở 14 điểm ảnh trên máy tính. Theo ADR-05, các giá trị màu, chữ, khoảng cách, bo góc là token thiết kế (design token) trong UI Pack của tenant; mỗi tenant có thể đổi màu nhấn và biểu trưng, nhưng không đổi được màu trạng thái và các quy ước truy cập nêu dưới đây, vì đó là ngữ nghĩa dùng chung.

## Màu và nhãn trạng thái

Trạng thái được hiển thị bằng nhãn (badge) có màu và luôn kèm chữ, không bao giờ chỉ dùng màu, để người mù màu vẫn đọc được. Bảng dưới đây quy định ý nghĩa của từng màu.

| Màu nhãn | Ý nghĩa | Ví dụ trạng thái |
|---|---|---|
| Xanh lá | Đã hoàn tất, đạt, đúng tiến độ | `DA_DUYET`, `HOAN_THANH`, `DA_XAC_NHAN`, "Đạt", "Đúng tiến độ" |
| Xanh dương | Đang xử lý bình thường | `DANG_THUC_HIEN`, `DANG_NGHIEM_THU`, `CHO_DUYET` |
| Vàng cam | Cần chú ý, sắp đến hạn, cảnh báo | "Còn 2 ngày", "Gần giống", `YEU_CAU_BO_SUNG`, `DANG_KHOA` |
| Đỏ | Lỗi chặn, quá hạn, từ chối | "Trễ 2 ngày", "KPI mơ hồ", `TU_CHOI`, `TRA_LAI` |
| Xám | Nháp, chưa bắt đầu, đã đóng | `NHAP`, `DONG`, `HUY` |
| Tím | Nội dung do AI tạo hoặc ô do AI điền | "Nội dung do AI tạo", nhãn "AI" cạnh ô dữ liệu |

Trong danh sách và bảng, mã trạng thái kỹ thuật như `DANG_THUC_HIEN` chỉ hiển thị cho người quản trị và trên màn hình chi tiết; người dùng thông thường thấy nhãn tiếng Việt tương ứng. Tình trạng tiến độ như "Trễ 2 ngày" được tính theo ngày làm việc dựa trên lịch nghỉ của tenant (`config.holiday_calendar`).

## Thông báo, xác nhận và lỗi

Nền tảng dùng bốn hình thức thông báo. Thông báo nổi (toast) xuất hiện ở góc trên bên phải trong 5 giây khi thao tác ghi thành công. Hộp cảnh báo nằm trong trang (inline alert) đặt ngay trên vùng có vấn đề, dùng cho cảnh báo và lỗi chặn của quy tắc nghiệp vụ, kèm hướng dẫn sửa. Hộp thoại xác nhận (modal) chỉ dùng cho thao tác không hoàn tác được như duyệt có ký số, xác nhận nghiệm thu, khởi chạy khoá kỳ. Thông báo đẩy và thư trong ứng dụng do notification-service (SVC-12) gửi khi có sự kiện liên quan tới người dùng, ví dụ được giao việc hoặc minh chứng được nghiệm thu.

Lỗi từ API được trả theo định dạng Problem Details (RFC 9457) với trường `type` là mã lỗi nghiệp vụ, ví dụ `https://okr.viettel.vn/errors/BR-04-03`. Giao diện ánh xạ mã lỗi sang thông báo `MSG-xx-yy` bằng tiếng Việt. Khi API trả mã 409 do xung đột phiên bản (người khác vừa sửa cùng bản ghi), giao diện giữ nguyên dữ liệu người dùng đang nhập, tải phiên bản mới nhất và hiển thị phần khác biệt để người dùng chọn giữ bản nào; dữ liệu người dùng không bao giờ bị mất do xung đột.

## Danh sách, phân trang và tìm kiếm

Mọi danh sách dùng phân trang theo con trỏ (cursor pagination) với kích thước trang mặc định 20 dòng và tối đa 200 dòng theo quy ước API. Giao diện hiển thị "Hiển thị 1–8 trong 1.251" khi tổng số có sẵn từ chỉ mục; với danh sách rất lớn không đếm chính xác, giao diện chỉ hiển thị nút trang trước và trang sau. Bộ lọc đặt trên đầu danh sách dưới dạng ô tìm kiếm và các nút chọn nhanh có số đếm (ví dụ "Chậm 1", "Sắp hạn 2"). Lựa chọn bộ lọc, cột hiển thị và kích thước trang được nhớ theo từng người dùng. Tìm kiếm toàn cục ở thanh trên dùng search (SVC-16) và chỉ trả về đối tượng người dùng có quyền xem.

## Chế độ chữ lớn và người dùng cao tuổi

Nhiều đảng viên cao tuổi sinh hoạt ở chi bộ khu dân cư cần dùng nền tảng để check-in và nộp minh chứng. Theo NFR-11, nền tảng có chế độ "Đảng viên cao tuổi" bật bằng nút "Aa" ở thanh trên hoặc trong phần cài đặt cá nhân. Ở chế độ này, cỡ chữ cơ sở tăng lên tối thiểu 17 điểm ảnh, độ tương phản tăng, vùng chạm của nút tối thiểu 48 điểm ảnh và hệ thống bật luồng rút gọn: các thao tác thường ngày như check-in KR, cập nhật tiến độ, nộp minh chứng bằng ảnh chụp được thiết kế để hoàn thành trong tối đa ba lần chạm kể từ trang chủ. Chế độ chữ lớn là một bộ token phụ trong UI Pack, nên không cần màn hình riêng; chi tiết cấu hình ở QT-18 (SCR-1803, phần B).

## Thiết bị di động

Ứng dụng di động (SVC-18) phục vụ các thao tác cần làm nhanh tại chỗ: xem việc trong ngày, check-in KR, cập nhật tiến độ, chụp ảnh văn bản làm minh chứng, nhận thông báo. Các thao tác cần màn hình lớn như lập kế hoạch, duyệt kế hoạch có so sánh phiên bản, nghiệm thu có đối chiếu tiêu chuẩn, khoá kỳ chỉ có trên máy tính; ứng dụng di động mở các màn hình đó ở chế độ chỉ đọc. Ứng dụng di động lưu tạm dữ liệu nhập khi mất sóng trong vùng lưu trữ đã mã hoá của thiết bị và tự gửi khi có mạng, kèm khoá chống ghi trùng `Idempotency-Key` sinh từ lúc người dùng bấm gửi, nên việc gửi lại không tạo bản ghi trùng. Thanh điều hướng dưới cùng có năm mục: "Hôm nay", "Việc", "OKR", "Minh chứng", "Tôi".

## Khả năng truy cập

Mọi màn hình đáp ứng WCAG 2.1 mức AA. Cụ thể, tỷ lệ tương phản chữ thường tối thiểu 4,5:1 và chữ lớn tối thiểu 3:1; mọi chức năng dùng được bằng bàn phím với thứ tự tab hợp lý và vòng tiêu điểm nhìn thấy rõ; mọi trường nhập có nhãn gắn với ô; mọi biểu tượng có nhãn văn bản thay thế; thông báo lỗi được đọc bởi trình đọc màn hình qua vùng `aria-live`; biểu đồ có bảng dữ liệu thay thế. Bộ kiểm thử tự động axe-core chạy trong quy trình tích hợp liên tục cho mọi màn hình; lỗi mức nghiêm trọng chặn phát hành (TL-04).

## Quy ước về nội dung do AI tạo

Trợ lý AI (AC-12) chỉ có quyền đọc và không bao giờ là người quyết định. Mọi nội dung do AI tạo, gồm gợi ý viết lại KR, tóm tắt tuần, nhiệm vụ trích từ văn bản, thông tin đọc từ ảnh chụp minh chứng, đều được trình bày theo ba quy ước bắt buộc. Thứ nhất, khối nội dung do AI tạo có viền tím nét đứt, nền tím nhạt và nhãn "Nội dung do AI tạo" kèm tên mô hình, thời điểm tạo và nguồn dữ liệu đã dùng. Thứ hai, ô dữ liệu do AI điền sẵn có viền tím và nhãn "AI" nhỏ cạnh tên trường; khi người dùng sửa ô đó, viền tím biến mất và ô được coi là dữ liệu do người nhập. Thứ ba, dữ liệu do AI điền không được dùng để tính điểm hay làm thay đổi trạng thái nghiệp vụ cho tới khi người có thẩm quyền bấm nút xác nhận; việc xác nhận được ghi vào sổ cái kèm danh sách ô người dùng đã giữ nguyên và ô đã sửa, để hậu kiểm biết phần nào do máy đề xuất.

Mỗi lần AI đưa ra gợi ý được ghi vào `ai.suggestion_log` cùng mã yêu cầu, phiên bản mô hình và kết quả người dùng chấp nhận hay bỏ qua. Dữ liệu lớp C3 (mật) không bao giờ được gửi tới ai-gateway (SVC-14).

## Danh mục quy trình và màn hình

Bảng sau là danh mục đầy đủ mười chín quy trình và các màn hình của TL-02. Phần A đặc tả QT-00 đến QT-08; phần B đặc tả QT-09 đến QT-18.

| Mã QT | Tên quy trình | Màn hình | Phần |
|---|---|---|---|
| QT-00 | Đăng nhập, chọn đơn vị và vai trò | SCR-0001 Đăng nhập (SSO, VNeID, MFA); SCR-0002 Trang chủ "Việc của tôi hôm nay" | A |
| QT-01 | Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự | SCR-0101 Danh sách tenant; SCR-0102 Khởi tạo tenant; SCR-0103 Cây tổ chức có lịch sử; SCR-0104 Đồng bộ và đối soát nhân sự | A |
| QT-02 | Lập và phê duyệt mục tiêu, OKR | SCR-0201 Cây mục tiêu OKR; SCR-0202 Tạo, sửa O và KR; SCR-0203 Phê duyệt OKR | A |
| QT-03 | Check-in KR | SCR-0301 Check-in KR; SCR-0302 Check-in trên di động | A |
| QT-04 | Lập và phê duyệt kế hoạch công tác quý | SCR-0401 Thư viện danh mục công việc; SCR-0402 Lập kế hoạch quý; SCR-0403 Phê duyệt kế hoạch | A |
| QT-05 | Giao việc, đăng ký nhiệm vụ, nhận nhiệm vụ từ văn bản | SCR-0501 Giao việc; SCR-0502 Tạo nhiệm vụ từ văn bản; SCR-0503 Việc của tôi; SCR-0504 Việc của tôi trên di động | A |
| QT-06 | Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn, điều chỉnh | SCR-0601 Chi tiết nhiệm vụ; SCR-0602 Đề nghị gia hạn, điều chỉnh | A |
| QT-07 | Nộp và nghiệm thu sản phẩm, minh chứng | SCR-0701 Nộp minh chứng; SCR-0702 Nộp minh chứng trên di động; SCR-0703 Nghiệm thu chất lượng 5 mức | A |
| QT-08 | Khoá kỳ (saga chốt kỳ) | SCR-0801 Quản lý kỳ và tiến trình khoá kỳ | A |
| QT-09 | Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý | SCR-0901 đến SCR-0904 | B |
| QT-10 | Xếp loại năm, liên thông quý và năm, phân nhóm triển vọng | SCR-1001, SCR-1002 | B |
| QT-11 | Kiểm điểm và hồ sơ số | SCR-1101, SCR-1102 | B |
| QT-12 | Khiếu nại, giải trình | SCR-1201, SCR-1202 | B |
| QT-13 | Hậu kiểm, mở lại kỳ (hai người duyệt) | SCR-1301, SCR-1302 | B |
| QT-14 | Cảnh báo bất thường | SCR-1401 | B |
| QT-15 | Soạn, kiểm thử và phát hành Policy Pack | SCR-1501 đến SCR-1504 | B |
| QT-16 | Bảng điều hành và báo cáo | SCR-1601 đến SCR-1603 | B |
| QT-17 | Trợ lý AI | SCR-1701 | B |
| QT-18 | Cấu hình giao diện tenant (UI Pack) | SCR-1801 đến SCR-1803 | B |

# Định danh, tenant và cơ cấu tổ chức

Chương này đặc tả hai quy trình nền móng. QT-00 mô tả cách một người dùng vào được nền tảng với đúng danh tính, đúng tổ chức và đúng vai trò. QT-01 mô tả cách một tổ chức mới, ví dụ một tỉnh hay một đảng uỷ, được đưa vào vận hành cùng cây tổ chức có lịch sử và dữ liệu nhân sự được đồng bộ từ các cơ sở dữ liệu quốc gia. Hai quy trình này không tạo ra điểm số, nhưng mọi quy trình sau đều phụ thuộc vào chất lượng dữ liệu mà chúng tạo ra: nếu một cán bộ được gắn sai đơn vị, toàn bộ nhiệm vụ, minh chứng và kết quả đánh giá của người đó sẽ nằm sai chỗ.

## QT-00. Đăng nhập, chọn đơn vị và vai trò

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-00 |
| Tên quy trình | Đăng nhập, chọn đơn vị và vai trò |
| Mục đích | Xác thực danh tính người dùng ở mức bảo đảm phù hợp với vai trò, xác định tenant, đơn vị và vai trò của phiên làm việc, đưa người dùng tới trang "Việc của tôi hôm nay" |
| Phạm vi áp dụng | Mọi người dùng là con người (AC-01 đến AC-10) trên web và ứng dụng di động, ở mọi tenant và mọi cell |
| Căn cứ | Luật Giao dịch điện tử 2023 và NĐ 69/2024/NĐ-CP về định danh và xác thực điện tử (dùng VNeID mức 2); TCVN 11930:2017 yêu cầu xác thực cho hệ thống cấp độ 4; PL 4 dự thảo Quy định mới về chống làm thay và tách vai trò |
| Tác nhân | AC-04 đến AC-10 (người dùng), AC-01, AC-02 (quản trị), AC-11 (VNeID, IdP của cơ quan) |
| Tần suất và khối lượng ước tính | Ước tính 6 triệu tài khoản, mỗi tài khoản trung bình 1,2 phiên mỗi ngày làm việc trong tuần cao điểm, tức khoảng 7 triệu lần đăng nhập mỗi ngày; giờ cao điểm 8:00 đến 9:00 dồn khoảng 25% số đó, tương đương khoảng 490 lần đăng nhập mỗi giây toàn quốc (giả định về hành vi, cần đo lại khi vận hành thử) |
| Điều kiện bắt đầu | Tenant ở trạng thái `DANG_VAN_HANH`; người dùng có tài khoản đang hiệu lực và ít nhất một phân công còn hiệu lực trong `org.assignment` |
| Điều kiện kết thúc | Phiên làm việc có token chứa tenant, đơn vị, vai trò và mức xác thực; trang chủ hiển thị dữ liệu đúng ngữ cảnh |
| Đầu vào | Tên miền tenant, thông tin xác thực (mật khẩu, mã OTP, khoá FIDO2, VNeID, chứng thư số), lựa chọn phân công |
| Đầu ra | Phiên đăng nhập (token truy cập 5 phút, token làm mới 30 phút trượt), bản ghi kiểm toán đăng nhập, trang chủ |
| Chỉ số đo hiệu quả | Thời gian từ lúc mở trang tới lúc thấy trang chủ p95 ≤ 4 giây với tài khoản cơ quan và ≤ 8 giây với VNeID; tỷ lệ đăng nhập thất bại do lỗi hệ thống < 0,1%; 100% vai trò `appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin` đăng nhập ở mức AAL2 |

Một người dùng có thể làm việc ở nhiều tổ chức. Một chuyên viên Ban Tổ chức Đảng uỷ khối có thể đồng thời là Bí thư Chi bộ 3; một cán bộ được điều động từ tỉnh này sang tỉnh khác vẫn cần xem lại hồ sơ đánh giá ở tỉnh cũ trong thời gian chuyển tiếp. Vì vậy QT-00 không chỉ trả lời câu hỏi "người này là ai" mà còn trả lời "người này đang làm việc với tư cách nào". Tenant được nhận diện từ tên miền truy cập (ví dụ `dangtw.okr.viettel.vn`), sau đó api-gateway (SVC-01) tra tenant-registry (SVC-03) để định tuyến tới đúng cell. Máy chủ định danh iam (SVC-02) chạy trong cell và xác thực theo chuẩn OpenID Connect với luồng mã cấp phép có PKCE (Authorization Code with Proof Key for Code Exchange). Sau khi xác thực, người dùng chọn phân công nếu có nhiều phân công; lựa chọn này được ghi vào token dưới dạng thuộc tính `org` và `roles` để mọi service phía sau kiểm tra quyền mà không phải gọi lại org-service.

Mức bảo đảm xác thực phụ thuộc vai trò và rủi ro. Người dùng thông thường đăng nhập một yếu tố được, nhưng các vai trò có quyền thẩm định, quyết định, duyệt thứ hai, quản trị phải có MFA (mức AAL2). Ngoài ra, một số thao tác như duyệt kế hoạch có ký số, xác nhận nghiệm thu, khởi chạy khoá kỳ yêu cầu MFA bước lên nếu lần MFA gần nhất đã quá 15 phút. Đăng nhập từ thiết bị mới hoặc vị trí bất thường cũng kích hoạt MFA và gửi thông báo cho người dùng; đây là một trong các biện pháp chống làm thay của lớp bảo vệ thứ sáu (G3).

### Lưu đồ quy trình

![Hình 6.1. Lưu đồ làn bơi QT-00 Đăng nhập, chọn đơn vị và vai trò](nv-qt00-luong.png){width=16cm}

Hình 6.1 có năm làn. Làn "Người dùng" bắt đầu khi người dùng mở tên miền của tenant và chọn cách đăng nhập. Làn "Cổng" (SVC-01, SVC-03) phân giải tên miền ra tenant và cell. Làn "Định danh" (SVC-02) thực hiện luồng OIDC, kiểm tra tham số `state` và `nonce` chống giả mạo, sau đó quyết định có cần MFA hay không dựa trên vai trò và điểm rủi ro. Làn "VNeID, IdP" là nơi xác thực khi người dùng chọn VNeID hoặc IdP của cơ quan; iam đóng vai trò môi giới danh tính (identity brokering) và ánh xạ danh tính ngoài sang tài khoản nội bộ. Làn "Ứng dụng" (SVC-05, SVC-17) nạp UI Pack của tenant, quyền ABAC và bản sao dữ liệu tổ chức của người dùng từ `org_replica`, sau đó người dùng chọn phân công và vào trang chủ.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở tên miền tenant | AC-04 đến AC-10 | Người dùng mở địa chỉ của tổ chức; trang đăng nhập hiển thị tên tenant, biểu trưng và các phương thức được bật cho tenant | SCR-0001 | Trang đăng nhập đúng tenant | Ngay |
| 2 | Phân giải tenant và cell | SVC-01, SVC-03 | Cổng tra `registry.tenant_domain` (có bộ nhớ đệm), chuyển hướng tới máy chủ định danh của cell với tham số `kc_org` của tenant | Không có | Chuyển hướng OIDC | ≤ 50 ms |
| 3 | Chọn phương thức và xác thực | Người dùng, SVC-02, AC-11 | Người dùng nhập tài khoản cơ quan, hoặc chọn VNeID mức 2, hoặc chứng thư số; với VNeID, iam nhận mã định danh cá nhân và ánh xạ qua bảng liên kết danh tính | SCR-0001 | Danh tính được xác thực | ≤ 2 phút |
| 4 | Đánh giá yêu cầu MFA | SVC-02 | Nếu phân công có vai trò yêu cầu AAL2, hoặc thiết bị mới, hoặc điểm rủi ro vượt ngưỡng, iam yêu cầu MFA | SCR-0001 | Quyết định MFA | Ngay |
| 5 | Xác thực MFA | Người dùng | Nhập mã SmartOTP 6 số, hoặc chạm khoá FIDO2/WebAuthn, hoặc xác nhận đẩy qua VNeID | SCR-0001 | Mức AAL2 | Mã hết hạn sau 60 giây |
| 6 | Phát token | SVC-02 | Token chứa `tenant_id`, danh sách phân công, `acr` (mức xác thực), `auth_time`; ghi sự kiện đăng nhập vào nhật ký kiểm toán | Không có | Bản ghi kiểm toán đăng nhập | ≤ 200 ms |
| 7 | Chọn đơn vị, vai trò | Người dùng | Nếu có từ hai phân công còn hiệu lực trở lên, hiển thị danh sách để chọn; phân công dùng lần trước được chọn sẵn | SCR-0001 | Ngữ cảnh phiên | Ngay |
| 8 | Nạp giao diện và quyền | SVC-17, SVC-05 | Frontend nạp UI Pack theo phiên bản (ETag), bố cục trang chủ SDUI, quyền ABAC đã tính sẵn cho ngữ cảnh | SCR-0002 | Trang chủ hiển thị | p95 ≤ 1 s |
| 9 | Xem việc hôm nay | Người dùng | Trang chủ tổng hợp việc đến hạn, việc chậm, KR cần check-in, minh chứng chờ nghiệm thu, mốc của kỳ, thông báo | SCR-0002 | Không có | Không có |

Điểm khó của quy trình nằm ở bước 3 và bước 7. Ở bước 3, một người có thể có tài khoản cơ quan và đồng thời đăng nhập bằng VNeID; hai danh tính phải được gắn về cùng một bản ghi người (`org.person`) bằng số định danh cá nhân đã băm HMAC, không lưu số định danh dạng rõ trong iam. Nếu không khớp được, hệ thống không tự tạo tài khoản mới mà hướng người dùng tới quản trị tenant để liên kết thủ công, tránh tạo hai hồ sơ cho một người. Ở bước 7, danh sách phân công lấy từ bản sao `org_replica.assignment_v` tại thời điểm hiện tại theo trục hiệu lực; phân công đã kết thúc không hiển thị, trừ khi người dùng có quyền xem lại hồ sơ cũ trong thời gian chuyển tiếp theo PL 6.

### Trạng thái đối tượng

Đối tượng chính của QT-00 là phiên đăng nhập. Phiên không thuộc danh sách trạng thái chuẩn của cơ sở thiết kế chung vì do Keycloak quản lý; tài liệu mô tả trạng thái logic để thống nhất cách giao diện xử lý.

![Hình 6.2. Trạng thái logic của phiên đăng nhập](nv-qt00-trang-thai.png){width=14cm}

Hình 6.2 cho thấy phiên có hai mức xác thực AAL1 và AAL2. Phiên ở AAL2 tự hạ về AAL1 sau 15 phút kể từ lần MFA gần nhất; khi người dùng thực hiện thao tác yêu cầu AAL2, giao diện mở hộp MFA bước lên mà không làm mất dữ liệu đang nhập.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| CHUA_XAC_THUC | Xác thực một yếu tố thành công | AAL1 | Vai trò không yêu cầu AAL2, không có rủi ro | Phân công yêu cầu AAL2 bị ẩn tới khi bước lên |
| CHUA_XAC_THUC | Xác thực kèm MFA | AAL2 | Mã OTP đúng, hoặc FIDO2 hợp lệ | `acr` = `aal2` |
| AAL1 | MFA bước lên | AAL2 | Thao tác hoặc ngữ cảnh yêu cầu | `auth_time` được cập nhật |
| AAL2 | Quá 15 phút từ lần MFA | AAL1 | Không có | Không đăng xuất, chỉ hạ mức |
| AAL1, AAL2 | Không hoạt động 30 phút | HET_HAN | Không có | Dữ liệu nháp trên trình duyệt được giữ |
| AAL1, AAL2 | Đăng xuất, thu hồi, khoá tài khoản | BI_THU_HOI | Quản trị khoá hoặc người dùng báo truy cập bất thường | Token làm mới bị thu hồi ngay |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-00-01 | Vai trò `appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin` chỉ được dùng trong phiên AAL2 | TCVN 11930:2017; NFR-08 (ASVS mức L3 cho xác thực) | Chặn: phân công có các vai trò này không chọn được khi phiên ở AAL1 |
| BR-00-02 | Duyệt có ký số, xác nhận nghiệm thu, khởi chạy khoá kỳ, mở lại kỳ yêu cầu MFA trong vòng 15 phút trước thao tác | PL 4 dự thảo Quy định mới (chống làm thay) | Chặn: API trả 401 với `insufficient_user_authentication`, giao diện mở MFA bước lên |
| BR-00-03 | Đăng nhập VNeID chỉ chấp nhận tài khoản mức 2 | NĐ 69/2024/NĐ-CP | Chặn: tài khoản mức 1 nhận thông báo MSG-00-03 |
| BR-00-04 | Một danh tính ngoài chỉ được liên kết với một bản ghi người trong tenant | PL 4 dự thảo Quy định mới về toàn vẹn dữ liệu | Chặn: liên kết trùng bị từ chối, chuyển quản trị tenant xử lý |
| BR-00-05 | Đăng nhập sai 5 lần trong 15 phút khoá tài khoản 15 phút | OWASP ASVS 2.2.1 | Chặn và ghi vết, gửi thông báo cho người dùng |
| BR-00-06 | Đăng nhập từ thiết bị hoặc vị trí mới phải MFA và gửi thông báo | Biện pháp chống làm thay (G3, lớp 6) | Cảnh báo người dùng, ghi vết vào nhật ký kiểm toán |
| BR-00-07 | Chỉ phân công còn hiệu lực tại thời điểm đăng nhập được chọn làm ngữ cảnh | Dữ liệu hai trục thời gian của org-service | Chặn: phân công hết hiệu lực không hiển thị |
| BR-00-08 | Ngữ cảnh phiên được ghi vào mọi bản ghi sổ cái do người dùng tạo | PL 4 về truy vết | Ghi vết tự động bởi ledger-service |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| VNeID không phản hồi hoặc trả lỗi | iam ngắt sau 10 giây theo bộ ngắt mạch; trang đăng nhập hiển thị MSG-00-04 và gợi ý đăng nhập bằng tài khoản cơ quan; sự cố được ghi để đội vận hành theo dõi (TL-04) |
| Người dùng mất thiết bị SmartOTP | Người dùng chọn "Phương thức khác"; nếu không còn phương thức nào, quản trị tenant cấp mã khôi phục một lần sau khi xác minh qua đơn vị, việc cấp mã được ghi sổ cái |
| Người dùng không có phân công nào còn hiệu lực | Hiển thị MSG-00-06, không vào trang chủ; thường gặp khi vừa nghỉ hưu hoặc điều động chưa được ghi nhận, quản trị tenant xử lý ở QT-01 |
| Tenant ở trạng thái `TAM_NGUNG` | Trang đăng nhập hiển thị MSG-00-07; chỉ quản trị nền tảng đăng nhập được để xử lý |
| Cell đang chuyển sang trung tâm dự phòng | Cổng định tuyến sang trung tâm dự phòng theo kịch bản DR của TL-04; phiên cũ phải đăng nhập lại, dữ liệu nháp trên trình duyệt được giữ |
| Người dùng báo truy cập bất thường | Mọi phiên của tài khoản bị thu hồi, buộc đổi mật khẩu, quản trị tenant nhận cảnh báo |

### Đặc tả màn hình

#### SCR-0001. Đăng nhập (SSO, VNeID, MFA)

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Xác thực danh tính, MFA và chọn phân công trong cùng một luồng |
| Tác nhân | Mọi người dùng là con người |
| Đường vào | Mở tên miền của tenant; hoặc tự động khi phiên hết hạn |
| Quyền | Không cần quyền trước khi đăng nhập |
| Thiết bị | Máy tính, máy tính bảng, điện thoại (bố cục co giãn); ứng dụng di động dùng màn hình gốc với cùng luồng |

![Hình 6.3. Màn hình đăng nhập SCR-0001 gồm ba bước xác thực, MFA và chọn phân công](SCR-0001.png){width=16cm}

Hình 6.3 trình bày ba bước của luồng đăng nhập trên một khung hình để người đọc thấy toàn bộ luồng; trên giao diện thật, mỗi bước là một trạng thái của cùng khung bên phải. Vùng bên trái giới thiệu nền tảng, cam kết dữ liệu lưu trong nước và hướng dẫn bật chế độ chữ lớn.

Vùng 1 là bước xác thực danh tính. Dòng mô tả cho biết người dùng đang đăng nhập vào tenant nào và cell nào, giúp người dùng phát hiện trang giả mạo. Ba thẻ chọn phương thức là "Tài khoản cơ quan", "VNeID", "Chứng thư số"; chỉ những phương thức được bật trong `registry.tenant_idp` mới hiển thị. Vùng 2 là nút đăng nhập bằng VNeID mức 2 và dòng "Lần đăng nhập gần nhất" giúp người dùng phát hiện truy cập lạ. Vùng 3 là bước MFA: dòng giải thích vì sao cần MFA (vai trò Thẩm định yêu cầu AAL2), sáu ô nhập mã, đồng hồ đếm ngược và các phương thức thay thế. Vùng 4 là cảnh báo đăng nhập từ thiết bị mới kèm liên kết "Báo cáo truy cập bất thường". Vùng 5 là bước chọn phân công khi người dùng có nhiều phân công còn hiệu lực.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Tên miền truy cập | Chuỗi, chỉ đọc | Có | Tên miền, ≤ 253 ký tự | Từ địa chỉ trình duyệt | Phải tồn tại và tenant `DANG_VAN_HANH` | `registry.tenant_domain.domain` |
| 2 | Phương thức đăng nhập | Lựa chọn | Có | Một trong ba giá trị | Tài khoản cơ quan | Chỉ phương thức được bật | `registry.tenant_idp.idp_type` |
| 3 | Tên đăng nhập hoặc thư điện tử | Chuỗi | Có | ≤ 254 ký tự | Ghi nhớ nếu đã chọn | Không phân biệt hoa thường | Keycloak `user_entity.username` |
| 4 | Mật khẩu | Mật khẩu | Có | 12 đến 128 ký tự | Không có | Kiểm tra tại iam, không lưu ở trình duyệt | Keycloak `credential` |
| 5 | Ghi nhớ thiết bị 30 ngày | Hộp chọn | Không | Đúng/Sai | Sai | Không áp dụng cho vai trò AAL2 | Keycloak, cookie thiết bị |
| 6 | Mã xác thực | Số | Có ở bước MFA | Đúng 6 chữ số | Không có | Hết hạn 60 giây, tối đa 5 lần sai | Keycloak `credential` (TOTP) |
| 7 | Phương thức MFA | Lựa chọn | Có ở bước MFA | SmartOTP, FIDO2, đẩy VNeID | Phương thức đăng ký đầu tiên | Chỉ phương thức đã đăng ký | Keycloak `credential.type` |
| 8 | Phân công làm việc | Lựa chọn | Có khi ≥ 2 phân công | Một phân công | Phân công dùng lần trước | Còn hiệu lực theo `valid_from`, `valid_to` | `org_replica.assignment_v.id` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Đăng nhập | Bước 1, đã nhập đủ | Gửi biểu mẫu tới iam theo luồng OIDC | Keycloak `/realms/{cell}/login-actions/authenticate` | Không có (nhật ký kiểm toán iam) |
| Đăng nhập bằng VNeID | VNeID được bật | Chuyển hướng tới VNeID qua môi giới danh tính | Keycloak `/realms/{cell}/broker/vneid/login` | Không có |
| Xác nhận | Bước MFA, đủ 6 số | Kiểm tra mã | Keycloak (luồng xác thực) | Không có |
| Gửi lại mã | Sau 30 giây | Gửi lại yêu cầu đẩy hoặc nhắc dùng mã mới | Keycloak | Không có |
| Báo cáo truy cập bất thường | Có cảnh báo thiết bị mới | Thu hồi mọi phiên, mở luồng đổi mật khẩu | `/api/v1/config/security/report-suspicious` | EVT-21 (thông báo cho quản trị) |
| Chọn phân công | Bước 5 | Ghi ngữ cảnh vào phiên, cấp token mới có `org` | `/api/v1/org/me/context` (PUT) | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-00-01 | Tên đăng nhập hoặc mật khẩu không đúng. Đồng chí còn {n} lần thử trước khi tài khoản bị tạm khoá 15 phút. | Xác thực thất bại |
| MSG-00-02 | Tài khoản tạm khoá đến {giờ} do đăng nhập sai nhiều lần. | Vượt 5 lần sai |
| MSG-00-03 | Tài khoản VNeID của đồng chí chưa ở mức 2. Vui lòng nâng mức tài khoản hoặc dùng tài khoản cơ quan. | VNeID trả mức 1 |
| MSG-00-04 | Dịch vụ VNeID đang gián đoạn. Đồng chí có thể đăng nhập bằng tài khoản cơ quan. | VNeID quá thời gian hoặc lỗi |
| MSG-00-05 | Vai trò {vai trò} yêu cầu xác thực nhiều lớp. Vui lòng nhập mã từ ứng dụng SmartOTP. | Cần AAL2 |
| MSG-00-06 | Tài khoản chưa có phân công công tác còn hiệu lực. Vui lòng liên hệ quản trị của đơn vị. | Không có phân công |
| MSG-00-07 | Tổ chức {tên tenant} đang tạm ngừng sử dụng nền tảng. | Tenant `TAM_NGUNG` |
| MSG-00-08 | Đăng nhập từ thiết bị mới lúc {giờ} tại {nơi}. Nếu không phải đồng chí, bấm "Báo cáo truy cập bất thường". | Thiết bị mới |

#### SCR-0002. Trang chủ "Việc của tôi hôm nay"

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người dùng biết ngay hôm nay cần làm gì: việc đến hạn, việc chậm, KR cần check-in, minh chứng chờ, mốc của kỳ |
| Tác nhân | AC-04 đến AC-07 (bố cục khác nhau theo vai trò) |
| Đường vào | Sau đăng nhập; menu "Điều hành / Việc hôm nay"; biểu trưng ở thanh trên |
| Quyền | Vai trò bất kỳ trong ngữ cảnh đã chọn |
| Thiết bị | Máy tính; trên di động là tab "Hôm nay" với cùng dữ liệu (xem SCR-0504) |

![Hình 6.4. Trang chủ SCR-0002 "Việc của tôi hôm nay" của chuyên viên](SCR-0002.png){width=16cm}

Hình 6.4 là trang chủ của đồng chí Nguyễn Văn An sáng thứ Hai 19/10/2026. Bố cục trang do UI Pack của tenant quy định theo cơ chế SDUI (ADR-05): UI Pack chọn những khối nào hiển thị và theo thứ tự nào, còn bản thân từng khối là thành phần viết sẵn trong danh mục thành phần có phiên bản.

Vùng 1 là nút chuyển nhanh giữa các phân công của người dùng (Chuyên viên Ban Tổ chức và Bí thư Chi bộ 3) và nút "Cập nhật nhanh" mở hộp cập nhật tiến độ. Vùng 2 là bốn thẻ chỉ số: việc đến hạn hôm nay, việc chậm tiến độ, KR cần check-in tuần này, minh chứng chờ nghiệm thu; bấm vào thẻ mở danh sách đã lọc tương ứng. Vùng 3 là bảng việc cần làm, sắp theo mức khẩn, có cột vai trò (chủ trì hay phối hợp), hạn, tiến độ và tình trạng; nút ở cuối dòng là hành động phù hợp nhất với trạng thái, ví dụ "Cập nhật" hoặc "Nộp SP". Vùng 4 là danh sách KR cần check-in và các mốc của kỳ. Vùng 5 là thông báo gần đây và khối "Tóm tắt tuần" do AI tạo, có viền tím và nêu rõ nguồn dữ liệu đã dùng.

Khối tóm tắt tuần minh hoạ cách nền tảng giải thích điểm tiến độ cho người dùng trước khi quá muộn: việc NV-2026-Q4-0142 có hạn 15/10/2026 nhưng chưa xong, tính đến 19/10 đã chậm 2 ngày làm việc (16/10 và 19/10); nếu nộp sản phẩm trong hôm nay thì hệ số tiến độ C = 1 − 0,05 × 2 = 0,90, mỗi ngày làm việc chậm thêm trừ tiếp 0,05.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Lời chào, ngày, kỳ, tuần | Văn bản, chỉ đọc | Có | "Thứ Hai, 19/10/2026 · Quý IV/2026 · Tuần 4/13" | Hiện tại | Tuần tính từ ngày bắt đầu kỳ | `config.period.start_date`, `config.period.name` |
| 2 | Ngữ cảnh phân công | Nút chọn | Có | Tên chức danh, đơn vị | Phân công đang dùng | Phân công còn hiệu lực | `org_replica.assignment_v` |
| 3 | Số việc đến hạn hôm nay | Số | Có | Số nguyên | 0 | Đếm nhiệm vụ của người dùng có hạn = hôm nay | `task.task.due_date`, `task.task_assignee` |
| 4 | Số việc chậm tiến độ | Số | Có | Số nguyên | 0 | Đếm nhiệm vụ có cờ `qua_han` | `task.task.qua_han` |
| 5 | Số KR cần check-in | Số | Có | Số nguyên | 0 | KR người dùng sở hữu chưa có check-in trong tuần | `okr.key_result.owner_id`, `okr.kr_checkin.checkin_at` |
| 6 | Số minh chứng chờ nghiệm thu | Số | Có | Số nguyên | 0 | Phiên bản minh chứng `DA_NOP` hoặc `DANG_NGHIEM_THU` | `product.evidence_version.status` |
| 7 | Bảng việc cần làm | Danh sách | Có | Tối đa 7 dòng | Sắp theo mức khẩn | Chỉ nhiệm vụ chưa `HOAN_THANH`, `HUY` | `task.task`, `task.task_assignee.role` |
| 8 | Mốc của kỳ | Danh sách | Có | Ngày, nội dung | Theo Policy Pack | Lấy từ lịch kỳ | `config.period.milestones` (cột JSON đề xuất) |
| 9 | Thông báo | Danh sách | Không | Tối đa 5 dòng | Chưa đọc trước | Chỉ của người dùng | `notify.notification` |
| 10 | Tóm tắt tuần | Văn bản do AI tạo | Không | ≤ 600 ký tự | Ẩn nếu tenant tắt AI | Chỉ dùng dữ liệu của chính người dùng | `ai.suggestion_log` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Đổi phân công | Có ≥ 2 phân công | Đổi ngữ cảnh, tải lại trang | `/api/v1/org/me/context` (PUT) | Không có |
| Cập nhật nhanh | Có nhiệm vụ đang thực hiện | Mở hộp cập nhật tiến độ rút gọn (QT-06) | `/api/v1/tasks/{id}/progress` (POST) | EVT-05 |
| Cập nhật, Nộp SP | Theo trạng thái nhiệm vụ | Mở SCR-0601 hoặc SCR-0701 | Không có | Không có |
| Check-in | KR chưa check-in tuần | Mở SCR-0301 | Không có | Không có |
| Đánh dấu đã đọc | Có thông báo chưa đọc | Đánh dấu đọc tất cả | `/api/v1/notifications/read` (POST) | Không có |
| Tải trang | Luôn | Lấy bố cục và dữ liệu khối | `/api/v1/ui/layouts/home`, `/api/v1/tasks?assignee=me&view=today`, `/api/v1/okr/key-results?owner=me&needCheckin=true` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-00-09 | Đồng chí có {n} việc chậm tiến độ. Hệ số tiến độ giảm 0,05 cho mỗi ngày làm việc chậm do nguyên nhân chủ quan. | Có việc `qua_han` |
| MSG-00-10 | Dữ liệu cập nhật lúc {giờ}. Bấm để làm mới. | Dữ liệu cũ hơn 5 phút |
| MSG-00-11 | Không có việc nào cần làm hôm nay. | Danh sách trống |

### Liên kết thiết kế

QT-00 được hiện thực bởi api-gateway (SVC-01), iam (SVC-02) dựa trên Keycloak với một realm cho mỗi cell và một Organization cho mỗi tenant, tenant-registry (SVC-03) để phân giải tên miền, web-frontend (SVC-17) và mobile-app (SVC-18) để hiển thị. Dữ liệu dùng gồm `registry.tenant_domain`, `registry.tenant_idp`, bảng Keycloak và `org_replica.assignment_v`. Quy trình không phát sự kiện miền ngoài EVT-21 khi có cảnh báo; nhật ký đăng nhập được Keycloak đẩy sang ledger-service qua bộ lắng nghe sự kiện. Các biện pháp an toàn liên quan gồm chính sách mật khẩu, MFA, chống dò mật khẩu, quản lý phiên và phát hiện đăng nhập bất thường, được đặc tả trong TL-04 (nhóm SEC về xác thực và phiên); kiến trúc định danh đa tenant được trình bày trong TL-01.

## QT-01. Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-01 |
| Tên quy trình | Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự |
| Mục đích | Đưa một tổ chức mới vào vận hành trong một ngày làm việc với đúng mô hình thuê bao, đúng gói chính sách, cây tổ chức có lịch sử và dữ liệu nhân sự đã đối soát với cơ sở dữ liệu quốc gia |
| Phạm vi áp dụng | Mọi tenant mới (tỉnh, thành phố, đảng uỷ, đơn vị sự nghiệp, doanh nghiệp) và mọi lần đồng bộ nhân sự định kỳ của tenant đang vận hành |
| Căn cứ | PL 2A dự thảo Quy định mới (9 khối đối tượng); PL 6 (đánh giá người điều động, luân chuyển giữa kỳ); Luật CBCC 2025 về quản lý hồ sơ công chức; NĐ 47/2024/NĐ-CP về chia sẻ dữ liệu (kết nối NDXP); hợp đồng dịch vụ với tổ chức |
| Tác nhân | AC-01 (quản trị nền tảng), AC-02 (quản trị tenant), AC-11 (CSDL CBCCVC, CSDL đảng viên, IdP của tỉnh) |
| Tần suất và khối lượng ước tính | Khởi tạo: dự kiến 5.000 tenant trong 3 năm, đỉnh khoảng 30 tenant mỗi tuần trong đợt triển khai đại trà (ước tính theo lộ trình); đồng bộ: mỗi đêm một lần cho mỗi nguồn và mỗi tenant, cộng SCIM gần thời gian thực, tổng khoảng 6 triệu bản ghi người được đối chiếu mỗi đêm |
| Điều kiện bắt đầu | Hợp đồng hoặc quyết định triển khai đã ký; tổ chức đã cung cấp danh sách đơn vị và đầu mối quản trị |
| Điều kiện kết thúc | Tenant ở trạng thái `DANG_VAN_HANH`; không còn lệch dữ liệu mức Cao; kỳ đánh giá đầu tiên đã được tạo |
| Đầu vào | Thông tin tenant, quy mô dự kiến, luồng áp dụng, tệp Excel cây tổ chức, kết nối IdP và nguồn nhân sự |
| Đầu ra | Bản ghi `registry.tenant` và các bảng liên quan, CSDL hoặc schema của tenant, Organization trong Keycloak, cây tổ chức trong `org.*`, bảng ánh xạ `integ.mapping_xref` |
| Chỉ số đo hiệu quả | Thời gian từ lúc khai báo tới `DANG_VAN_HANH` ≤ 1 ngày (NFR-07), trong đó phần tự động ≤ 2 giờ với bridge và ≤ 5 phút với pool; tỷ lệ bản ghi khớp tự động ≥ 98%; lệch mức Cao = 0 trước khi kích hoạt |

Thiết kế cell và ba mô hình thuê bao trong TL-01 chỉ có giá trị khi việc đưa tenant vào vận hành đủ nhanh và đủ an toàn. Với 5.000 tenant, nếu mỗi tenant cần một tuần làm thủ công thì việc triển khai đại trà không khả thi. QT-01 vì vậy tách rõ phần quyết định của con người (chọn mô hình, chọn cell, chọn gói chính sách, đối soát dữ liệu lệch) với phần tự động hoá hoàn toàn (cấp phát CSDL, khoá mã hoá, Organization trong Keycloak, tên miền, chứng chỉ TLS) chạy dưới dạng saga trên workflow-service (SVC-06) với bước bù trừ khi lỗi.

Phần khó nhất của QT-01 không phải cấp phát hạ tầng mà là dữ liệu tổ chức. Bộ máy hành chính Việt Nam vừa trải qua đợt sắp xếp lớn: hợp nhất sở, bỏ cấp huyện, sáp nhập xã từ 01/07/2025. Kết quả đánh giá của một quý cũ phải được tái hiện đúng theo cơ cấu tại thời điểm đó, còn cán bộ bị điều động giữa kỳ phải được đánh giá theo PL 6 (dưới hai tháng tại đơn vị mới thì đơn vị cũ đánh giá). Vì vậy cây tổ chức được lưu theo hai trục thời gian: thời gian hiệu lực trả lời "cơ cấu này đúng trong khoảng nào", thời gian ghi nhận trả lời "hệ thống biết điều đó từ lúc nào". Nhờ đó, một quyết định điều động ký ngày 12/10 nhưng đến 19/10 mới được nhập vẫn có hiệu lực từ 12/10, và báo cáo in ra ngày 15/10 vẫn được tái hiện đúng như hệ thống đã biết vào ngày 15/10.

### Lưu đồ quy trình

![Hình 6.5. Lưu đồ làn bơi QT-01 Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự](nv-qt01-luong.png){width=16cm}

Hình 6.5 có bốn làn. Làn "Quản trị nền tảng" (AC-01) khai báo tenant, chọn mô hình và cell, chọn Policy Pack gốc và UI Pack, và cuối cùng kích hoạt tenant. Làn "Điều phối" là saga cấp phát do tenant-registry (SVC-03) khởi tạo và workflow-service (SVC-06) điều phối. Làn "Quản trị tenant" (AC-02) khai báo IdP và VNeID, nhập cây tổ chức có lịch sử và đối soát dữ liệu lệch. Làn "Tích hợp" gồm integration-gateway (SVC-13) đồng bộ từ SCIM, CSDL CBCCVC, CSDL đảng viên và org-service (SVC-04) ghi dữ liệu hai trục thời gian, phát các sự kiện DonViDaThayDoi (EVT-14), PhanCongDaThayDoi (EVT-15), NhanSuDaThayDoi (EVT-16). Vòng lặp "Còn lệch mức Cao?" bảo đảm tenant không được kích hoạt khi dữ liệu nhân sự còn sai ở mức có thể làm sai kết quả đánh giá.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Khai báo tenant | AC-01 | Nhập tên, mã, số hợp đồng, luồng áp dụng (Đảng, Nhà nước hoặc cả hai), quy mô dự kiến (số tài khoản, số đơn vị), đầu mối quản trị | SCR-0101, SCR-0102 bước 1 | `registry.tenant` ở `KHOI_TAO` | 15 phút |
| 2 | Chọn mô hình và cell | AC-01 | Hệ thống gợi ý mô hình theo quy mô (pool dưới 5.000 tài khoản, bridge từ 5.000 tới 1.000.000, silo trên 1.000.000 hoặc yêu cầu cách ly); AC-01 chọn cell còn dung lượng dưới 85% sau khi thêm; chọn tên miền, vùng lưu dữ liệu, khoá mã hoá riêng | SCR-0102 bước 2 | Cấu hình cell | 15 phút |
| 3 | Chọn Policy Pack gốc và UI Pack | AC-01 | Chọn gói chính sách đã phát hành theo luồng (ví dụ Khối Đảng · Quy định mới v2.0 và Nhà nước · NĐ 335 v1.3), gói giao diện (mặc định hoặc riêng), gắn biểu trưng | SCR-0102 bước 3 | `policy.policy_binding` nháp | 15 phút |
| 4 | Saga cấp phát | SVC-03, SVC-06 | Tạo CSDL (bridge) hoặc đăng ký `tenant_id` (pool), tạo khoá KEK trong HSM, tạo Organization trong Keycloak, đăng ký tên miền và cấp chứng chỉ TLS tự động, sao chép Policy Pack và UI Pack xuống cell, tạo lịch nghỉ mặc định | SCR-0101 (tiến độ) | `DANG_CAP_PHAT` rồi `DANG_CAU_HINH` | ≤ 2 giờ (bridge), ≤ 5 phút (pool) |
| 5 | Khai báo IdP, VNeID, vai trò | AC-02 | Khai báo IdP của tỉnh (OIDC, SAML hoặc LDAP liên kết), bật VNeID, ánh xạ nhóm sang vai trò | SCR-0102 bước 4 | `registry.tenant_idp` | 1 giờ |
| 6 | Nhập cây tổ chức có lịch sử | AC-02 | Nhập tệp Excel theo mẫu gồm đơn vị, cấp, loại, khối đối tượng PL 2A, đơn vị cấp trên, ngày hiệu lực, đơn vị tiền thân; khai báo các sự kiện sáp nhập, giải thể, đổi tên với ngày hiệu lực | SCR-0103 | `org.org_unit`, `org.org_unit_relation`; EVT-14 | 2 giờ |
| 7 | Đồng bộ nhân sự | SVC-13, SVC-04 | Đồng bộ từ CSDL CBCCVC qua NDXP, CSDL đảng viên, SCIM 2.0 từ IdP (cập nhật 10/2026: giao diện SCIM đặt ở SVC-04); khớp người theo số định danh băm HMAC và ngày sinh; ghi `org.person`, `org.assignment` với hai trục thời gian | SCR-0104 | EVT-15, EVT-16; `org.scim_sync_log` | ≤ 1 giờ cho 100.000 bản ghi |
| 8 | Đối soát lệch | AC-02 | Xem danh sách lệch theo loại và mức; chọn lấy theo nguồn, giữ dữ liệu nội bộ, ánh xạ thủ công, hoặc tạo điều động tương lai | SCR-0104 | Lệch mức Cao về 0 | 2 đến 4 giờ |
| 9 | Kiểm tra tổng thể và kích hoạt | AC-01 | Hệ thống kiểm tra dung lượng, tên miền, chứng chỉ, Policy Pack đạt test case vàng, IdP hoạt động, lệch mức Cao bằng 0; AC-01 kích hoạt; AC-02 tạo kỳ đánh giá đầu tiên | SCR-0102 bước 6 | `SAN_SANG` rồi `DANG_VAN_HANH` | 30 phút |

Bước 4 là bước duy nhất chạy hoàn toàn tự động và là bước có nhiều điểm lỗi nhất vì phụ thuộc nhiều hệ thống. Saga cấp phát ghi lại từng bước con đã hoàn tất; nếu bước con thứ năm (cấp chứng chỉ) lỗi, saga thử lại theo chính sách lùi dần, và chỉ khi lỗi không khắc phục được mới bù trừ ngược (thu hồi tên miền, xoá Organization, huỷ khoá chưa dùng, xoá CSDL rỗng) để tenant trở về `KHOI_TAO`. Bước 8 là bước tốn thời gian nhất và cần con người. Lệch được phân ba mức: mức Cao là lệch làm sai người được đánh giá hoặc sai người đánh giá (chức vụ lãnh đạo, không khớp người, đơn vị chính), mức Vừa là lệch làm sai thời điểm (điều động, nghỉ hưu chưa cập nhật), mức Thấp là lệch thông tin phụ (nhóm trong IdP). Chỉ lệch mức Cao chặn kích hoạt; lệch mức Vừa và Thấp được xử lý dần trong vận hành.

### Trạng thái đối tượng

Đối tượng chính của QT-01 là tenant. Cơ sở thiết kế chung chưa quy định trạng thái của `registry.tenant`, nên tài liệu đề xuất bảy trạng thái dưới đây và ghi vào danh sách đề xuất cho TL-03.

![Hình 6.6. Trạng thái của tenant trong registry.tenant](nv-qt01-trang-thai.png){width=14cm}

Hình 6.6 cho thấy tenant chỉ chuyển sang `SAN_SANG` khi đã đủ IdP, cây tổ chức và không còn lệch mức Cao; trạng thái `TAM_NGUNG` dùng cho hết hạn hợp đồng hoặc sự cố an ninh và có thể quay lại vận hành; `DA_CHAM_DUT` là trạng thái cuối sau khi dữ liệu đã được xuất trả cho tổ chức theo hợp đồng.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Khai báo tenant | `KHOI_TAO` | Mã và tên miền chưa dùng | Chưa cấp phát tài nguyên |
| `KHOI_TAO` | Xác nhận cell và mô hình | `DANG_CAP_PHAT` | Dung lượng cell sau khi thêm < 85% | Saga cấp phát bắt đầu |
| `DANG_CAP_PHAT` | Saga thành công | `DANG_CAU_HINH` | Mọi bước con hoàn tất | Gửi thư cho đầu mối quản trị tenant |
| `DANG_CAP_PHAT` | Saga lỗi không khắc phục | `KHOI_TAO` | Bù trừ xong | Ghi lý do lỗi |
| `DANG_CAU_HINH` | Đủ điều kiện | `SAN_SANG` | IdP hoạt động, cây tổ chức có, lệch mức Cao bằng 0 | Tự động kiểm tra |
| `SAN_SANG` | Kích hoạt | `DANG_VAN_HANH` | AC-01 xác nhận, MFA | Người dùng đăng nhập được |
| `DANG_VAN_HANH` | Hết hạn hợp đồng, sự cố an ninh | `TAM_NGUNG` | Quyết định của AC-01 | Chỉ quản trị đăng nhập được |
| `TAM_NGUNG` | Gia hạn, khắc phục | `DANG_VAN_HANH` | AC-01 xác nhận | Không mất dữ liệu |
| `TAM_NGUNG` | Chấm dứt | `DA_CHAM_DUT` | Đã xuất trả dữ liệu, hết thời hạn lưu giữ theo hợp đồng | Sổ cái giữ tối thiểu 10 năm (NFR-09) |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-01-01 | Tenant trên 1.000.000 tài khoản hoặc thuộc khối Đảng Trung ương, lực lượng vũ trang phải dùng mô hình silo | Cơ sở thiết kế chung mục 6; yêu cầu cách ly khối Đảng | Chặn: không chọn được bridge, pool cho tenant thuộc diện này |
| BR-01-02 | Không thêm tenant vào cell nếu dung lượng sau khi thêm vượt 85% | Quy tắc vận hành cell (TL-01) | Chặn: cell vượt ngưỡng hiển thị nhưng không chọn được |
| BR-01-03 | Chỉ gắn Policy Pack ở trạng thái `DA_PHAT_HANH` và đạt 100% test case vàng | QT-15; trạng thái chuẩn Policy Pack | Chặn |
| BR-01-04 | Tenant thuộc cả hai luồng phải gắn đủ một gói cho mỗi luồng | Mục Hai luồng đánh giá | Chặn |
| BR-01-05 | Mỗi đơn vị phải thuộc đúng một khối đối tượng trong 9 khối PL 2A | PL 2A dự thảo Quy định mới | Chặn khi nhập |
| BR-01-06 | Thay đổi tổ chức không được ghi đè lịch sử; mỗi thay đổi tạo phiên bản mới với `valid_from` theo quyết định và `recorded_from` theo thời điểm ghi | Yêu cầu tái hiện đánh giá quá khứ; PL 4 về toàn vẹn | Chặn: API không có thao tác sửa tại chỗ |
| BR-01-07 | Sáp nhập, hợp nhất đơn vị phải khai báo đơn vị tiền thân để chuyển phân công và tra cứu lịch sử | Sắp xếp đơn vị hành chính 2025 | Chặn khi thiếu đơn vị tiền thân |
| BR-01-08 | Một người chỉ có một phân công chính tại một thời điểm; có thể có nhiều kiêm nhiệm, biệt phái | Luật CBCC 2025 | Chặn phân công chính thứ hai chồng thời gian |
| BR-01-09 | Người điều động giữa kỳ dưới 2 tháng tại đơn vị mới thì đơn vị cũ đánh giá | PL 6 dự thảo Quy định mới | Ghi vết: org-service tính `danh_gia_tai_don_vi` cho kỳ, MOD-04 dùng khi lập hồ sơ |
| BR-01-10 | Không kích hoạt tenant khi còn lệch mức Cao | Bảo đảm đúng người được đánh giá | Chặn |
| BR-01-11 | Dữ liệu từ hệ ngoài chỉ vào qua integration-gateway và bảng ánh xạ mã | ADR về lớp chống hỏng (ACL), cơ sở thiết kế chung mục 8 | Chặn: org-service không nhận ghi từ client ngoài |
| BR-01-12 | Số định danh cá nhân chỉ lưu dạng mã hoá trường (C2) và chỉ mục HMAC để khớp | Lớp dữ liệu C2 (G3) | Chặn ở lớp lưu trữ |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Không còn cell bridge nào dưới ngưỡng 85% | SCR-0102 hiển thị MSG-01-03; AC-01 mở yêu cầu dựng cell mới theo quy trình vận hành của TL-04; tenant giữ `KHOI_TAO` |
| Bản sao dự phòng của cell đang trễ | Hiển thị cảnh báo nhưng không chặn nếu độ trễ dưới ngưỡng RPO 15 phút; chặn nếu vượt |
| CSDL CBCCVC quá thời gian | integration-gateway ghi thông điệp vào `integ.dead_letter`, thử lại theo lịch; SCR-0104 hiển thị lần chạy lỗi |
| Hai hồ sơ trùng tên khác ngày sinh | Không tự khớp; đưa vào lệch mức Cao loại "Không khớp người" để đối soát thủ công |
| Quyết định điều động có hiệu lực trong tương lai | Tạo phân công tương lai với `valid_from` là ngày hiệu lực; phân công hiện tại tự kết thúc ngày trước đó |
| Thay đổi tổ chức nhập muộn sau khi kỳ đã khoá | Ghi với `recorded_from` hiện tại; kỳ đã khoá không bị tính lại tự động; nếu thay đổi làm sai kết quả, mở lại kỳ theo QT-13 |
| Saga cấp phát lỗi giữa chừng | Thử lại tối đa 3 lần với thời gian chờ tăng dần; sau đó bù trừ và báo AC-01 |

### Đặc tả màn hình

#### SCR-0101. Danh sách tenant

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Theo dõi toàn bộ tenant, tải theo cell và các việc cần xử lý của vùng điều phối toàn cục |
| Tác nhân | AC-01 |
| Đường vào | Menu quản trị "Nền tảng / Danh sách tenant" |
| Quyền | `platform_admin`, phiên AAL2 |
| Thiết bị | Máy tính |

![Hình 6.7. Danh sách tenant SCR-0101 tại vùng điều phối toàn cục](SCR-0101.png){width=16cm}

Hình 6.7 là màn hình của quản trị nền tảng ngày 19/10/2026. Bốn thẻ trên cùng cho biết số tenant đang vận hành so với giới hạn thiết kế 5.000, tổng tài khoản so với 6 triệu, số tenant đang khởi tạo và số cell. Vùng 1 là bộ lọc theo cell, mô hình và trạng thái. Vùng 2 là bảng tenant với cột cell, mô hình, số tài khoản, Policy Pack đang gắn, UI Pack và trạng thái; dòng Thái Nguyên cho thấy tenant đang khởi tạo ở bước 4 trên 6. Vùng 3 là biểu đồ tải theo cell, tính bằng số tài khoản chia dung lượng thiết kế, kèm quy tắc đề nghị mở cell mới khi vượt 85%, và danh sách việc cần xử lý. Vùng 4 là nút "Khởi tạo tenant".

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Tên tenant | Chuỗi | Có | ≤ 200 ký tự | Không có | Duy nhất | `registry.tenant.name` |
| 2 | Tên miền | Chuỗi | Có | Tên miền con của `okr.viettel.vn` hoặc tên miền riêng | Không có | Duy nhất | `registry.tenant_domain.domain` |
| 3 | Cell | Mã | Có | `CELL-xxx` | Không có | Cell đang hoạt động | `registry.tenant_cell.cell_id` |
| 4 | Mô hình | Liệt kê | Có | `silo`, `bridge`, `pool` | Theo gợi ý | Theo BR-01-01 | `registry.tenant_cell.tenancy_model` |
| 5 | Tài khoản | Số | Có | Số nguyên | 0 | Đồng bộ mỗi giờ từ cell | `registry.tenant.account_count` (đề xuất) |
| 6 | Policy Pack đang gắn | Văn bản | Có | Tên và phiên bản | Không có | Tham chiếu gói đã phát hành | `policy.policy_binding` (bản sao ở registry) |
| 7 | UI Pack | Văn bản | Có | Tên và phiên bản | Mặc định | Tham chiếu gói đã phát hành | `config.ui_pack_version` |
| 8 | Trạng thái | Liệt kê | Có | Bảy trạng thái ở Hình 6.6 | `KHOI_TAO` | Theo máy trạng thái | `registry.tenant.status` (đề xuất) |
| 9 | Tải theo cell | Phần trăm | Có | 0 đến 100% | Không có | Tính từ số tài khoản và dung lượng thiết kế | `registry.cell.capacity_accounts` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Khởi tạo tenant | Luôn | Mở SCR-0102 | Không có | Không có |
| Chi tiết | Mỗi dòng | Mở trang chi tiết tenant | `/api/v1/config/tenants/{id}` | Không có |
| Lọc, tìm | Luôn | Lọc danh sách theo con trỏ | `/api/v1/config/tenants?cell=&model=&status=&cursor=` | Không có |
| Xuất danh sách | Luôn | Tạo tác vụ xuất, trả 202 | `/api/v1/config/tenants/export` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-01-01 | Cell {mã} đã sử dụng {x}% dung lượng thiết kế. Đề nghị lập kế hoạch mở cell mới. | Một cell vượt 85% |
| MSG-01-02 | Tenant {tên} đang khởi tạo bước {n}/6. | Có tenant đang khởi tạo |

#### SCR-0102. Khởi tạo tenant

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Hướng dẫn quản trị nền tảng đi qua sáu bước khởi tạo một tenant, với gợi ý và kiểm tra tự động ở mỗi bước |
| Tác nhân | AC-01; AC-02 tham gia ở bước 4 và bước 5 |
| Đường vào | SCR-0101, nút "Khởi tạo tenant"; hoặc mở lại bản nháp theo mã yêu cầu |
| Quyền | `platform_admin` (bước 1, 2, 3, 6), `tenant_admin` của tenant (bước 4, 5); phiên AAL2 |
| Thiết bị | Máy tính |

![Hình 6.8. Trình hướng dẫn khởi tạo tenant SCR-0102 ở bước chọn cell và mô hình](SCR-0102.png){width=16cm}

Hình 6.8 là bước 2 của yêu cầu ONB-2026-0318 khởi tạo tenant Tỉnh Thái Nguyên theo hợp đồng ký ngày 10/10/2026. Vùng 1 là thanh tiến trình sáu bước: thông tin tenant, cell và mô hình, Policy Pack và UI Pack, định danh, tổ chức và nhân sự, kiểm tra và kích hoạt. Vùng 2 là ba thẻ mô hình thuê bao với mô tả ngắn và thời gian dựng ước tính; thẻ "Bridge" được chọn theo gợi ý của hệ thống vì quy mô 98.420 tài khoản nằm trong khoảng 5.000 đến 1.000.000 và tỉnh yêu cầu khoá mã hoá riêng. Vùng 3 là bảng chọn cell, cho thấy CELL-G2 sau khi thêm còn ở mức 64% trong khi CELL-G1 sẽ vượt ngưỡng 86% nên bị đánh dấu; phía dưới là tên miền, vùng lưu dữ liệu và loại khoá mã hoá. Vùng 4 là bảng tóm tắt cấu hình đã chọn (luồng áp dụng, Policy Pack gốc của hai luồng, UI Pack, IdP gồm Organization trong Keycloak, VNeID và LDAP liên kết, nguồn nhân sự) và danh sách kiểm tra trước khi tiếp tục. Vùng 5 là nút chuyển sang bước tiếp theo.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã yêu cầu khởi tạo | Chuỗi, chỉ đọc | Có | `ONB-yyyy-nnnn` | Tự sinh | Duy nhất | `registry.tenant.onboarding_code` (đề xuất) |
| 2 | Số hợp đồng | Chuỗi | Có | ≤ 50 ký tự | Không có | Không trống | `registry.tenant.contract_ref` (đề xuất) |
| 3 | Luồng áp dụng | Nhiều lựa chọn | Có | `DANG`, `NHA_NUOC` | Cả hai | Ít nhất một | `registry.tenant.flows` (đề xuất) |
| 4 | Quy mô dự kiến | Số | Có | Tài khoản, đơn vị | Không có | > 0 | `registry.tenant.expected_accounts` (đề xuất) |
| 5 | Mô hình thuê bao | Lựa chọn | Có | `silo`, `bridge`, `pool` | Theo gợi ý | BR-01-01 | `registry.tenant_cell.tenancy_model` |
| 6 | Cell | Lựa chọn | Có | Mã cell | Cell tải thấp nhất phù hợp | BR-01-02 | `registry.tenant_cell.cell_id` |
| 7 | Tên miền truy cập | Chuỗi | Có | Tên miền hợp lệ | `{mã}.okr.viettel.vn` | Chưa dùng | `registry.tenant_domain.domain` |
| 8 | Khu vực lưu dữ liệu | Lựa chọn | Có | Trung tâm chính | Hoà Lạc | Trong nước | `registry.tenant_cell.data_region` (đề xuất) |
| 9 | Khoá mã hoá (KEK) | Lựa chọn | Có | Riêng theo tenant hoặc dùng chung của cell | Riêng với silo, bridge | Bắt buộc riêng với silo | `registry.tenant.kek_ref` (đề xuất) |
| 10 | Policy Pack gốc theo luồng | Lựa chọn | Có | Gói và phiên bản | Gói mặc định của khối | BR-01-03, BR-01-04 | `policy.policy_binding.pack_version_id` |
| 11 | UI Pack | Lựa chọn | Có | Gói và phiên bản | Mặc định v3.0 | Đã phát hành | `config.ui_pack_version.id` |
| 12 | IdP | Danh sách | Có | OIDC, SAML, LDAP liên kết, VNeID | Organization của cell | Kiểm tra kết nối thành công | `registry.tenant_idp` |
| 13 | Nguồn nhân sự | Danh sách | Có | CSDL CBCCVC, CSDL đảng viên, SCIM | Theo luồng | Có kết nối hợp lệ | `integ.connector` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | Mọi bước | Lưu cấu hình đang soạn | `/api/v1/config/tenants/{id}` (PATCH, If-Match) | Không có |
| Tiếp tục | Bước hiện tại hợp lệ | Kiểm tra, chuyển bước; ở cuối bước 3 khởi chạy saga cấp phát (202) | `/api/v1/config/tenants/{id}/provision` (POST, Idempotency-Key) | Không có (saga ghi tiến độ) |
| Kiểm tra kết nối IdP | Bước 4 | Thử luồng OIDC hoặc bind LDAP | `/api/v1/config/tenants/{id}/idp/test` | Không có |
| Kích hoạt | Bước 6, mọi kiểm tra đạt | Chuyển `DANG_VAN_HANH`, MFA bước lên | `/api/v1/config/tenants/{id}/activate` | EVT-21 (thông báo đầu mối) |
| Huỷ | Trạng thái `KHOI_TAO` | Huỷ yêu cầu, không cấp phát | `/api/v1/config/tenants/{id}` (DELETE) | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-01-03 | Không còn cell {mô hình} nào dưới ngưỡng 85% sau khi thêm tenant. Vui lòng mở yêu cầu dựng cell mới. | Không có cell phù hợp |
| MSG-01-04 | Tên miền {tên} đã được sử dụng. | Trùng tên miền |
| MSG-01-05 | Gói chính sách {gói} chưa đạt 100% test case vàng nên chưa gắn được. | Vi phạm BR-01-03 |
| MSG-01-06 | Cấp phát tenant thành công trong {phút} phút. Đã gửi thư cho đầu mối quản trị tenant. | Saga hoàn tất |
| MSG-01-07 | Cấp phát lỗi ở bước {bước}: {lý do}. Hệ thống đã hoàn tác các bước trước. | Saga bù trừ |

#### SCR-0103. Cây tổ chức có lịch sử

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Xem, nhập và thay đổi cây tổ chức theo thời gian hiệu lực, tra cứu cơ cấu tại một ngày trong quá khứ |
| Tác nhân | AC-02; AC-07, AC-10 chỉ đọc |
| Đường vào | Menu quản trị "Tenant / Cây tổ chức" |
| Quyền | `tenant_admin` để sửa; `appraiser`, `inspector` để xem |
| Thiết bị | Máy tính |

![Hình 6.9. Cây tổ chức có lịch sử SCR-0103 của Tỉnh Thái Nguyên](SCR-0103.png){width=16cm}

Hình 6.9 là cây tổ chức Tỉnh Thái Nguyên ở chế độ "Hiện tại". Vùng 1 là bộ chọn chế độ xem: "Hiện tại" hiển thị cơ cấu đang hiệu lực, còn "Xem tại ngày" cho phép chọn một ngày trong quá khứ, ví dụ 30/06/2025 trước khi sắp xếp đơn vị hành chính cấp xã, để tái hiện đúng cơ cấu đã dùng khi đánh giá quý II/2025; chế độ đó chỉ đọc. Ô "Ghi nhận đến" cho phép chọn trục thời gian ghi nhận để biết hệ thống đã biết gì tại một thời điểm. Vùng 2 là cây đơn vị; đơn vị đã kết thúc hiệu lực như "Huyện Phú Bình (kết thúc 30/06/2025)" được hiển thị mờ, đơn vị hợp nhất có nhãn ngày hợp nhất. Vùng 3 là thẻ chi tiết đơn vị Sở Nội vụ với mã nội bộ, mã CSDL CBCCVC, cấp, khối đối tượng PL 2A, luồng đánh giá, hiệu lực, thời điểm và người ghi nhận, đơn vị cấp trên, đơn vị tiền thân. Vùng 4 là lịch sử thay đổi, trong đó có thay đổi ghi muộn một ngày (đổi tên phòng hiệu lực 15/09 nhưng ghi nhận 16/09) và thay đổi tương lai đã duyệt (điều động ngày 01/11/2026). Vùng 5 là danh sách nhân sự và phân công với loại phân công chính, kiêm nhiệm, điều động đi, biệt phái và ghi chú về quy tắc PL 6.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Chế độ xem | Lựa chọn | Có | Hiện tại, Xem tại ngày | Hiện tại | Ngày không sau hôm nay | Tham số truy vấn `asOf` |
| 2 | Ghi nhận đến | Ngày | Có | dd/mm/yyyy | Hôm nay | Không sau hôm nay | Tham số truy vấn `recordedAsOf` |
| 3 | Mã đơn vị | Chuỗi | Có | ≤ 30 ký tự | Không có | Duy nhất trong tenant | `org.org_unit.code` |
| 4 | Mã CSDL CBCCVC | Chuỗi | Không | Theo mã định danh đơn vị quốc gia | Không có | Khớp `integ.mapping_xref` | `integ.mapping_xref.external_id` |
| 5 | Tên đơn vị | Chuỗi | Có | ≤ 255 ký tự | Không có | Không trống | `org.org_unit.name` |
| 6 | Cấp, loại | Liệt kê | Có | Trung ương, tỉnh, xã; loại cơ quan | Không có | Theo danh mục | `org.org_unit.level`, `org.org_unit.unit_type` |
| 7 | Khối đối tượng | Liệt kê | Có | 1 trong 9 khối PL 2A | Không có | BR-01-05 | `org.org_unit.subject_group_id` → `org.subject_group` |
| 8 | Luồng đánh giá | Nhiều lựa chọn | Có | `DANG`, `NHA_NUOC` | Theo tenant | Ít nhất một | `org.org_unit.flows` (đề xuất) |
| 9 | Hiệu lực từ, đến | Ngày | Có từ | dd/mm/yyyy | Hôm nay | `valid_to` > `valid_from` | `org.org_unit.valid_from`, `valid_to` |
| 10 | Ghi nhận lúc, người ghi | Thời điểm | Có | Tự sinh | Hiện tại | Chỉ đọc | `org.org_unit.recorded_from`, `created_by` |
| 11 | Đơn vị cấp trên | Tham chiếu | Có trừ gốc | Mã đơn vị | Không có | Không tạo vòng | `org.org_unit_relation` (`relation_type` = `PARENT`) |
| 12 | Đơn vị tiền thân | Danh sách tham chiếu | Có khi sáp nhập | Mã đơn vị | Không có | BR-01-07 | `org.org_unit_relation` (`relation_type` = `PREDECESSOR`) |
| 13 | Phân công | Danh sách | Không | Người, chức danh, loại, hiệu lực | Không có | BR-01-08 | `org.assignment` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Xem tại ngày | Luôn | Tải cây theo `asOf` và `recordedAsOf`, khoá sửa | `/api/v1/org/units/tree?asOf=&recordedAsOf=` | Không có |
| Nhập từ Excel | Chế độ Hiện tại, `tenant_admin` | Tải tệp, kiểm tra, trả báo cáo lỗi; tác vụ dài trả 202 | `/api/v1/org/imports` (POST) | EVT-14 cho mỗi đơn vị |
| Thay đổi tổ chức | Chế độ Hiện tại | Mở hộp chọn loại thay đổi: thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể; nhập ngày hiệu lực và văn bản căn cứ | `/api/v1/org/units/{id}/changes` (POST) | EVT-14, EVT-15 khi phân công chuyển theo |
| Sửa | Chế độ Hiện tại | Tạo phiên bản mới của đơn vị, không ghi đè | `/api/v1/org/units/{id}` (PUT, If-Match) | EVT-14 |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-01-08 | Đang xem cơ cấu tại ngày {ngày}. Chế độ này chỉ đọc. | Chọn Xem tại ngày |
| MSG-01-09 | Sáp nhập cần khai báo ít nhất một đơn vị tiền thân. | Vi phạm BR-01-07 |
| MSG-01-10 | Thay đổi có hiệu lực trước ngày khoá của kỳ {kỳ}. Kết quả kỳ đã khoá sẽ không tự tính lại; nếu cần, đề nghị mở lại kỳ. | Ghi muộn vào kỳ đã khoá |
| MSG-01-11 | Đã chuyển {n} phân công sang đơn vị mới theo quyết định {số}. | Sáp nhập thành công |

#### SCR-0104. Đồng bộ và đối soát nhân sự

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Theo dõi các lần đồng bộ từ nguồn ngoài và xử lý dữ liệu lệch trước khi dữ liệu được dùng cho đánh giá |
| Tác nhân | AC-02 |
| Đường vào | Menu quản trị "Tenant / Đồng bộ nhân sự" (có số đếm lệch mức Cao) |
| Quyền | `tenant_admin`, phiên AAL2 khi áp dụng hàng loạt |
| Thiết bị | Máy tính |

![Hình 6.10. Đồng bộ và đối soát nhân sự SCR-0104](SCR-0104.png){width=16cm}

Hình 6.10 là màn hình đối soát của Tỉnh Thái Nguyên trước khi kích hoạt. Vùng 1 là bốn thẻ trạng thái nguồn: CSDL CBCCVC qua NDXP thành công với 61.204 bản ghi và 37 thay đổi, CSDL đảng viên thành công với 48.966 bản ghi, SCIM từ IdP tỉnh thành công một phần với 3 lỗi lược đồ, và tổng số 412 mục lệch cần đối soát. Vùng 2 là danh sách lệch có bộ lọc theo loại (chức vụ, đơn vị, không khớp người, đảng tịch) và mức, mỗi dòng có giá trị nguồn, giá trị nền tảng hiện có, mức và đề xuất xử lý. Vùng 3 là các nút xử lý hàng loạt cho những dòng đã chọn. Vùng 4 là chi tiết bản ghi đang chọn, cho biết mã ngoài, mã nội bộ, khớp theo tiêu chí nào, lần đồng bộ nào và ngày hiệu lực theo quyết định, kèm giải thích rằng khi chấp nhận, org-service ghi phiên bản mới với `valid_from` bằng ngày quyết định và `recorded_from` bằng thời điểm duyệt, rồi phát PhanCongDaThayDoi (EVT-15). Phía dưới là nhật ký đồng bộ, trong đó lần chạy lỗi hết thời gian được giữ trong `integ.dead_letter` để chạy lại.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Nguồn đồng bộ | Liệt kê | Có | CBCCVC, Đảng viên, SCIM | Không có | Kết nối đang bật | `integ.connector.code` |
| 2 | Lần chạy, kết quả | Bản ghi | Có | Thời điểm, số bản ghi, số thay đổi, số lỗi | Không có | Chỉ đọc | `org.scim_sync_log`, `integ.sync_cursor` |
| 3 | Cán bộ | Chuỗi | Có | Họ tên, CCCD che 4 số cuối | Không có | Dữ liệu C2 hiển thị đã che | `org.person.full_name`, `org.person.national_id_enc` |
| 4 | Trường lệch | Liệt kê | Có | Chức vụ, đơn vị, không khớp người, đảng tịch, trạng thái, kiêm nhiệm | Không có | Không có | `org.sync_discrepancy.field` (bảng đề xuất) |
| 5 | Giá trị nguồn, giá trị hiện có | Chuỗi | Có | Văn bản | Không có | Chỉ đọc | `org.sync_discrepancy.source_value`, `current_value` |
| 6 | Mức | Liệt kê | Có | Cao, Vừa, Thấp | Theo quy tắc phân loại | Mức Cao chặn kích hoạt | `org.sync_discrepancy.severity` |
| 7 | Đề xuất | Liệt kê | Có | Lấy theo nguồn, giữ nội bộ, đối soát thủ công, tạo điều động tương lai, kết thúc phân công | Theo quy tắc | Không có | `org.sync_discrepancy.suggested_action` |
| 8 | Quyết định xử lý | Liệt kê | Có khi xử lý | Như đề xuất | Đề xuất | Ghi người, thời điểm | `org.sync_discrepancy.resolution`, `resolved_by` |
| 9 | Mã ngoài, mã nội bộ | Chuỗi | Có | Mã nguồn, UUID | Không có | Một mã ngoài một mã nội bộ | `integ.mapping_xref.external_id`, `internal_id` |
| 10 | Hiệu lực từ | Ngày | Có | dd/mm/yyyy | Ngày theo quyết định nguồn | Không sau ngày hiệu lực ghi trong nguồn | `org.assignment.valid_from` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Đồng bộ ngay | Luôn | Tạo lần chạy đồng bộ, trả 202 | `/api/v1/org/sync-runs` (POST) | Không có |
| Lịch đồng bộ | Luôn | Mở cấu hình lịch | `/api/v1/org/sync-schedules` | Không có |
| Lấy theo nguồn | Đã chọn dòng | Ghi phiên bản mới theo giá trị nguồn | `/api/v1/org/discrepancies/resolve` (POST, Idempotency-Key) | EVT-15 hoặc EVT-16 |
| Giữ dữ liệu nội bộ | Đã chọn dòng | Ghi quyết định giữ, đánh dấu bỏ qua cho đến khi nguồn đổi | `/api/v1/org/discrepancies/resolve` | Không có |
| Ánh xạ thủ công | Một dòng loại không khớp người | Chọn bản ghi người đúng, ghi `integ.mapping_xref` | `/api/v1/org/discrepancies/{id}/map` | EVT-16 |
| Ghi chú, giao xử lý | Đã chọn dòng | Giao cho cán bộ tổ chức đơn vị kiểm tra | `/api/v1/org/discrepancies/{id}/assign` | EVT-21 |
| Chạy lại thông điệp lỗi | Có dòng trong hàng đợi lỗi | Đưa lại vào luồng xử lý | `/api/v1/org/sync-runs/{id}/retry` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-01-12 | Còn {n} mục lệch mức Cao. Tenant chưa thể kích hoạt. | Vi phạm BR-01-10 |
| MSG-01-13 | Đã áp dụng {n} quyết định. {m} phân công được cập nhật với ngày hiệu lực theo quyết định gốc. | Xử lý hàng loạt thành công |
| MSG-01-14 | Lần đồng bộ {nguồn} lúc {giờ} lỗi: {lý do}. Thông điệp đã được lưu để chạy lại. | Lỗi đồng bộ |
| MSG-01-15 | Hai hồ sơ trùng tên khác ngày sinh. Vui lòng đối soát thủ công. | Không khớp người |

### Liên kết thiết kế

QT-01 dùng tenant-registry (SVC-03) ở vùng điều phối toàn cục, workflow-service (SVC-06) cho saga cấp phát, iam (SVC-02) cho Organization và IdP, org-service (SVC-04) cho cây tổ chức hai trục thời gian, integration-gateway (SVC-13) cho kết nối CSDL CBCCVC, CSDL đảng viên và SCIM, và module config (MOD-08) cho kỳ đánh giá, lịch nghỉ, UI Pack. Các bảng chính là `registry.tenant`, `registry.cell`, `registry.tenant_cell`, `registry.tenant_domain`, `registry.tenant_idp`, `org.org_unit`, `org.org_unit_relation`, `org.person`, `org.assignment`, `org.subject_group`, `org.scim_sync_log`, `integ.connector`, `integ.mapping_xref`, `integ.sync_cursor`, `integ.dead_letter`, `policy.policy_binding`. Sự kiện phát ra gồm EVT-14, EVT-15, EVT-16 (tiêu thụ bởi `org_replica` của mission-core và iam) và EVT-21. Tài liệu đề xuất thêm bảng `org.sync_discrepancy` và cột trạng thái cho `registry.tenant` (xem tệp ghi chú). Về an toàn thông tin, quy trình liên quan tới quản lý khoá mã hoá theo tenant trong HSM, mã hoá trường C2 cho số định danh, xác thực máy với máy cho kết nối NDXP và quy trình vận hành dựng cell mới, được đặc tả trong TL-04; mô hình cell và thuê bao được trình bày trong TL-01.

# Mục tiêu và kế hoạch công tác

Chương này đặc tả ba quy trình ở đầu mỗi kỳ và xuyên suốt kỳ: lập và phê duyệt mục tiêu theo OKR (QT-02), check-in kết quả then chốt hằng tuần (QT-03), lập và phê duyệt kế hoạch công tác quý theo Bảng 1 (QT-04). Ba quy trình này trả lời ba câu hỏi khác nhau. OKR trả lời "đơn vị muốn tạo ra chuyển biến gì và đo bằng gì". Check-in trả lời "đến tuần này đã đạt tới đâu và có tin là sẽ đạt không". Kế hoạch quý trả lời "để đạt được điều đó, đơn vị sẽ làm những việc gì, ai chủ trì, sản phẩm là gì, hạn khi nào". Tách ba câu hỏi này là yêu cầu cốt lõi của PL 1 dự thảo Quy định mới: kết quả then chốt đo kết quả, không đồng nhất với nhiệm vụ; nhiệm vụ đo khối lượng, chất lượng, tiến độ công việc.

## QT-02. Lập và phê duyệt mục tiêu, OKR

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-02 |
| Tên quy trình | Lập và phê duyệt mục tiêu, OKR |
| Mục đích | Xây dựng từ 1 đến 3 mục tiêu cho mỗi kỳ, mỗi mục tiêu có kết quả then chốt đo được, có căn cứ và liên kết với mục tiêu cấp trên; được người có thẩm quyền phê duyệt trước khi triển khai |
| Phạm vi áp dụng | Tập thể lãnh đạo, quản lý, đơn vị, phòng, ban và cá nhân được giao OKR; kỳ quý và kỳ năm |
| Căn cứ | PL 1 dự thảo Quy định mới (định nghĩa mục tiêu, kết quả then chốt, khuyến nghị 1 đến 3 mục tiêu mỗi kỳ, phân rã ba tầng); QĐ 366 về đánh giá tập thể và người đứng đầu theo kết quả; nghị quyết, chương trình công tác năm của cấp uỷ, chính quyền làm căn cứ |
| Tác nhân | AC-05 hoặc AC-06 (người soạn), AC-04 (người duyệt), AC-12 (trợ lý AI soát KR) |
| Tần suất và khối lượng ước tính | Mỗi đơn vị mỗi quý một bộ OKR, cá nhân có OKR tuỳ cấu hình; ước tính khoảng 400.000 bộ OKR mỗi quý toàn quốc (giả định khoảng 6% tài khoản là người soạn), dồn vào 5 ngày làm việc đầu quý |
| Điều kiện bắt đầu | Kỳ đánh giá ở trạng thái `MO` hoặc đã được tạo trước; OKR cấp trên đã duyệt (trừ cấp cao nhất của tenant) |
| Điều kiện kết thúc | Bộ OKR ở trạng thái `DA_DUYET`, sự kiện MucTieuDaDuyet (EVT-01) đã phát, check-in được mở |
| Đầu vào | Căn cứ (nghị quyết, kế hoạch năm), OKR cấp trên, OKR kỳ trước, gợi ý của trợ lý AI |
| Đầu ra | Bản ghi `okr.objective`, `okr.key_result`, `okr.basis`, `okr.okr_link`; bản ghi sổ cái của lần duyệt |
| Chỉ số đo hiệu quả | 100% OKR có căn cứ và liên kết cấp trên; tỷ lệ KR bị cảnh báo "mơ hồ" lúc trình duyệt < 5%; 95% OKR được duyệt trước hạn duyệt của kỳ |

Kinh nghiệm áp dụng OKR trong khu vực công cho thấy hai lỗi thường gặp. Lỗi thứ nhất là đặt quá nhiều mục tiêu, biến OKR thành bản liệt kê mọi việc của đơn vị; PL 1 vì vậy khuyến nghị 1 đến 3 mục tiêu mỗi kỳ. Lỗi thứ hai là viết KR như một nhiệm vụ ("Tổ chức hội nghị sơ kết") hoặc như một khẩu hiệu ("Tăng cường công tác kiểm tra"), khiến KR không đo được và không phân biệt được với kế hoạch công tác. QT-02 kiểm soát hai lỗi này ngay lúc soạn: hệ thống cảnh báo khi vượt 3 mục tiêu và yêu cầu ghi lý do, bộ kiểm tra cú pháp và trợ lý AI phát hiện KR thiếu chỉ tiêu, thiếu đơn vị đo, chứa từ mơ hồ hoặc giống nhiệm vụ, và chặn trình duyệt khi còn lỗi chặn.

Phân rã ba tầng là cách nối OKR với công việc hằng ngày. Tầng 1 là mục tiêu và KR của đơn vị. Tầng 2 là chương trình, đề án lớn phục vụ KR, ví dụ chương trình "Nâng chất lượng sinh hoạt chi bộ". Tầng 3 là nhiệm vụ cụ thể trong kế hoạch quý (QT-04). Liên kết giữa các tầng được lưu trong `okr.okr_link`; một nhiệm vụ có thể phục vụ một KR, nhưng chỉ được tính điểm ở một nơi để tránh tính trùng.

### Lưu đồ quy trình

![Hình 7.1. Lưu đồ làn bơi QT-02 Lập và phê duyệt mục tiêu, OKR](nv-qt02-luong.png){width=16cm}

Hình 7.1 có bốn làn. Người soạn (AC-05 hoặc AC-06) mở kỳ mới, chọn căn cứ và mục tiêu cấp trên, soạn mục tiêu và KR với chỉ tiêu, đơn vị đo, trọng số, người xác nhận. Trợ lý AI (AC-12) soát KR mơ hồ, KR giống nhiệm vụ và gợi ý viết lại; trợ lý chỉ đề xuất, không sửa trực tiếp. Nếu còn lỗi chặn, người soạn phải sửa KR hoặc ghi lý do khi vượt 3 mục tiêu rồi soạn lại. Khi không còn lỗi chặn, người soạn trình duyệt; làn hệ thống (MOD-01, SVC-06) kiểm tra lại các quy tắc ở phía máy chủ và tạo tiến trình duyệt theo mẫu `duyet-okr`. Người duyệt (AC-04) phê duyệt hoặc trả lại kèm ý kiến. Khi được duyệt, hệ thống ghi trạng thái `DA_DUYET`, phát EVT-01 và ghi sổ cái; OKR có hiệu lực và check-in được mở.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở kỳ mới | AC-05, AC-06 | Mở cây mục tiêu của đơn vị, chọn kỳ; có thể sao chép mục tiêu kỳ trước làm điểm khởi đầu | SCR-0201 | Không có | Ngày đầu quý |
| 2 | Chọn căn cứ và mục tiêu cấp trên | AC-05, AC-06 | Chọn một hoặc nhiều căn cứ trong `okr.basis` và mục tiêu cấp trên mà mục tiêu này phục vụ | SCR-0202 | Liên kết căn cứ | Không có |
| 3 | Soạn mục tiêu và KR | AC-05, AC-06 | Nhập tên mục tiêu (≤ 200 ký tự), đơn vị sở hữu; với mỗi KR nhập nội dung, đơn vị đo, giá trị đầu, chỉ tiêu, trọng số, hạn, người xác nhận, tần suất check-in; tổng trọng số KR của một mục tiêu bằng 100% | SCR-0202 | Bản nháp, lưu tự động | Không có |
| 4 | Soát KR | AC-12, MOD-01 | Bộ kiểm tra cú pháp chạy ngay khi nhập; người soạn có thể mời trợ lý AI soát toàn bộ, nhận gợi ý viết lại có nguồn trích dẫn | SCR-0202 | Danh sách lỗi, cảnh báo | Tức thì |
| 5 | Sửa lỗi, ghi lý do | AC-05, AC-06 | Sửa KR có lỗi chặn; dùng gợi ý AI nếu thấy phù hợp (ô dùng gợi ý được đánh dấu là nội dung AI cho tới khi người soạn xác nhận); ghi lý do nếu vượt 3 mục tiêu | SCR-0202 | Không còn lỗi chặn | Không có |
| 6 | Trình phê duyệt | AC-05, AC-06 | Bấm "Trình phê duyệt"; hệ thống kiểm tra lại toàn bộ quy tắc ở máy chủ, chuyển trạng thái `CHO_DUYET`, tạo tiến trình `duyet-okr` với người duyệt lấy từ `flowable.wf_binding` | SCR-0202 | `CHO_DUYET` | Trước hạn duyệt của kỳ |
| 7 | Xem xét | AC-04 | Người duyệt xem hàng chờ, kết quả kiểm tra, cây liên kết; có thể xem lịch sử phiên bản | SCR-0203 | Không có | Theo tham số luồng duyệt |
| 8a | Phê duyệt | AC-04 | Ghi ý kiến (không bắt buộc), xác nhận; có thể duyệt hàng loạt các bộ OKR không có cảnh báo | SCR-0203 | `DA_DUYET`; EVT-01; sổ cái | Hạn duyệt kỳ |
| 8b | Trả lại | AC-04 | Ghi ý kiến (bắt buộc), trả lại cho người soạn | SCR-0203 | `NHAP`; EVT-21 | Hạn duyệt kỳ |
| 9 | Mở check-in, phân rã | MOD-01, MOD-02 | OKR có hiệu lực; KR xuất hiện trong danh sách check-in; KR được dùng làm liên kết cho kế hoạch quý | SCR-0201 | Không có | Ngay |

Điểm khó của quy trình là bước 4 và bước 5. Bộ kiểm tra cú pháp chạy trên trình duyệt bằng biểu thức CEL trong UI Pack và chạy lại trên máy chủ, gồm các luật như "KR phải có chỉ tiêu và đơn vị đo", "tổng trọng số bằng 100%", "nội dung không chứa từ trong danh sách từ mơ hồ" (ví dụ "tăng cường", "đẩy mạnh", "kịp thời", "nâng cao" khi không kèm con số). Trợ lý AI phát hiện thêm các trường hợp tinh tế hơn như KR mô tả hành động thay vì kết quả. Trợ lý AI không có quyền ghi: khi người soạn bấm "Dùng gợi ý", giao diện điền nội dung vào ô với viền tím và chỉ khi người soạn lưu thì nội dung mới được ghi, với dấu `source = AI_ACCEPTED` trong sổ cái.

### Trạng thái đối tượng

Đối tượng chính là mục tiêu `okr.objective` với các trạng thái chuẩn `NHAP`, `CHO_DUYET`, `DA_DUYET`, `DONG`. KR không có trạng thái riêng mà theo trạng thái của mục tiêu chứa nó.

![Hình 7.2. Trạng thái của mục tiêu okr.objective](nv-qt02-trang-thai.png){width=12cm}

Hình 7.2 cho thấy mục tiêu bị trả lại quay về `NHAP` chứ không có trạng thái "trả lại" riêng, vì người soạn chỉ cần sửa và trình lại. Mục tiêu đã duyệt có thể được điều chỉnh giữa kỳ; khi đó hệ thống tạo phiên bản mới ở `CHO_DUYET` và giữ phiên bản đã duyệt để check-in tiếp tục cho tới khi phiên bản mới được duyệt.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Tạo mục tiêu | `NHAP` | Kỳ tồn tại, chưa khoá | Lưu tự động |
| `NHAP` | Trình | `CHO_DUYET` | Không còn lỗi chặn | Tạo tiến trình `duyet-okr` |
| `CHO_DUYET` | Trả lại hoặc người soạn rút lại | `NHAP` | Có ý kiến khi trả lại | Thông báo người soạn |
| `CHO_DUYET` | Phê duyệt | `DA_DUYET` | Người duyệt khác người soạn | Phát EVT-01, ghi sổ cái |
| `DA_DUYET` | Điều chỉnh giữa kỳ | `CHO_DUYET` (phiên bản mới) | Kỳ còn `MO` | Phiên bản cũ vẫn hiệu lực tới khi phiên bản mới được duyệt |
| `DA_DUYET` | Kỳ khoá, hoặc huỷ có lý do | `DONG` | Saga khoá kỳ bước 5, hoặc người duyệt huỷ | Không check-in thêm |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-02-01 | Mỗi đơn vị, mỗi kỳ nên có 1 đến 3 mục tiêu; vượt 3 phải ghi lý do | PL 1 dự thảo Quy định mới | Cảnh báo, bắt buộc ghi lý do; người duyệt thấy nhãn "Vượt 3 O" |
| BR-02-02 | Mỗi mục tiêu phải có ít nhất một căn cứ và liên kết mục tiêu cấp trên (trừ cấp cao nhất) | PL 1 về liên kết, phân rã | Chặn trình duyệt |
| BR-02-03 | Mỗi mục tiêu có 2 đến 5 KR | PL 1 (khuyến nghị) | Cảnh báo khi ngoài khoảng |
| BR-02-04 | KR phải có đơn vị đo, giá trị đầu, chỉ tiêu, hạn và người xác nhận | PL 1 (KR đo được) | Chặn trình duyệt |
| BR-02-05 | KR chứa từ mơ hồ mà không có chỉ tiêu định lượng là KR mơ hồ | PL 1; yêu cầu chặn KPI mơ hồ | Chặn trình duyệt |
| BR-02-06 | KR mô tả hành động hoặc sản phẩm một lần (giống nhiệm vụ) cần cân nhắc chuyển sang kế hoạch quý | PL 1 (KR không đồng nhất với nhiệm vụ) | Cảnh báo, người duyệt thấy nhãn |
| BR-02-07 | Tổng trọng số KR trong một mục tiêu bằng 100% | Quy ước tính tiến độ mục tiêu | Chặn trình duyệt |
| BR-02-08 | Người duyệt không được là người soạn | PL 4 V.1 | Chặn: nút "Phê duyệt" ẩn, API trả 403 |
| BR-02-09 | Trả lại phải có ý kiến | Truy vết quyết định | Chặn |
| BR-02-10 | OKR đã duyệt không sửa tại chỗ; điều chỉnh tạo phiên bản mới | PL 4 về toàn vẹn | Chặn sửa tại chỗ |
| BR-02-11 | Nội dung do AI gợi ý chỉ được ghi khi người soạn xác nhận | Nguyên tắc AI không quyết định | Ghi vết nguồn `AI_ACCEPTED` hoặc `AI_EDITED` |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Mục tiêu cấp trên chưa được duyệt khi cấp dưới cần soạn | Cho phép soạn và liên kết tới bản nháp cấp trên; chỉ chặn ở bước trình duyệt với thông báo MSG-02-03 |
| Mục tiêu cấp trên được điều chỉnh sau khi cấp dưới đã duyệt | Gửi thông báo cho chủ sở hữu các mục tiêu liên kết; không tự đổi OKR cấp dưới |
| Quá hạn duyệt của kỳ | Tiến trình duyệt tự nhắc người duyệt và người đứng đầu; ghi dấu hiệu bất thường cho QT-14 |
| Người duyệt vắng mặt | Luồng duyệt lấy người duyệt thay thế từ `flowable.wf_binding` (cấp phó được uỷ quyền) và ghi rõ duyệt thay |
| Hai người cùng sửa một bộ OKR | API trả 409; giao diện hiển thị khác biệt và cho chọn giữ bản nào |
| Trợ lý AI không phản hồi | Bộ kiểm tra cú pháp vẫn chạy; nút "Trợ lý AI soát KR" báo tạm thời không dùng được, không chặn soạn thảo |

### Đặc tả màn hình

#### SCR-0201. Cây mục tiêu OKR

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Xem toàn bộ mục tiêu, KR của đơn vị trong kỳ, tiến độ, mức tự tin, liên kết dọc và ngang, lịch sử check-in |
| Tác nhân | AC-04, AC-05, AC-06 |
| Đường vào | Menu "Mục tiêu và nhiệm vụ / Mục tiêu, OKR" |
| Quyền | Thành viên đơn vị xem; chủ sở hữu KR check-in; AC-05 soạn |
| Thiết bị | Máy tính; trên di động có dạng danh sách rút gọn ở tab "OKR" |

![Hình 7.3. Cây mục tiêu OKR SCR-0201 của Ban Tổ chức Đảng uỷ khối, Quý IV/2026](SCR-0201.png){width=16cm}

Hình 7.3 là cây mục tiêu của Ban Tổ chức Đảng uỷ khối ở tuần 4 Quý IV/2026. Phía trên có ba chế độ xem: cây, bảng và bản đồ liên kết. Vùng 1 là dải căn cứ và mục tiêu cấp trên O-ĐUK-1, cho biết ban đang có 2 trên 3 mục tiêu được khuyến nghị. Vùng 2 là mục tiêu O1 "Nâng cao chất lượng tổ chức đảng và đảng viên" đã duyệt ngày 08/10, tiến độ 64%, với ba KR có trọng số, người xác nhận, nguồn số liệu, giá trị hiện tại trên chỉ tiêu và mức tự tin. Vùng 3 làm nổi bật KR 1.3 có mức tự tin Thấp. Vùng 4 là bảng bên phải với lịch sử check-in của KR đang chọn, vướng mắc gần nhất và các nhiệm vụ Tầng 2, Tầng 3 liên kết. Vùng 5 là liên kết ngang với Văn phòng Đảng uỷ khối và quy tắc đưa KR có mức tự tin Thấp hai tuần liên tiếp lên bảng điều hành của lãnh đạo.

Tiến độ mục tiêu được tính bằng trung bình có trọng số của tiến độ các KR; tiến độ KR bằng (giá trị hiện tại − giá trị đầu) chia (chỉ tiêu − giá trị đầu), giới hạn trong đoạn từ 0 đến 100%. Với O1 trong hình, giá trị đầu của cả ba KR bằng 0, nên tiến độ là 0,4 × (2,4/3) + 0,3 × (72/100) + 0,3 × (32,6/90) = 0,32 + 0,216 + 0,109 ≈ 64%. Với O2, tiến độ là 0,5 × 68% + 0,5 × (4/12) ≈ 51%. Tiến độ OKR phục vụ điều hành và là một căn cứ đánh giá kết quả của tập thể và người đứng đầu; cách quy đổi tiến độ OKR thành điểm, nếu có, là tham số của Policy Pack.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kỳ | Lựa chọn | Có | Tên kỳ | Kỳ đang `MO` | Kỳ của tenant | `config.period.id` |
| 2 | Căn cứ | Danh sách | Có | Số, trích yếu văn bản | Không có | Ít nhất một | `okr.basis.doc_ref`, `okr.basis.title` |
| 3 | Mục tiêu cấp trên | Tham chiếu | Có trừ cấp cao nhất | Mã, tên | Không có | Mục tiêu cùng kỳ của đơn vị cấp trên | `okr.okr_link` (`link_type` = `PARENT`) |
| 4 | Mã, tên mục tiêu | Chuỗi | Có | `O{n}`, ≤ 200 ký tự | Không có | Duy nhất trong đơn vị và kỳ | `okr.objective.code`, `okr.objective.title` |
| 5 | Trạng thái mục tiêu | Liệt kê | Có | `NHAP`, `CHO_DUYET`, `DA_DUYET`, `DONG` | `NHAP` | Theo máy trạng thái | `okr.objective.status` |
| 6 | Tiến độ mục tiêu | Phần trăm | Có | 0 đến 100 | 0 | Tính từ KR | `okr.objective.progress_pct` (cột tính, đề xuất) |
| 7 | Nội dung KR | Chuỗi | Có | ≤ 300 ký tự | Không có | BR-02-04, BR-02-05 | `okr.key_result.title` |
| 8 | Trọng số KR | Phần trăm | Có | 0 đến 100 | Chia đều | Tổng 100% | `okr.key_result.weight` |
| 9 | Giá trị hiện tại, chỉ tiêu | Số | Có | Theo đơn vị đo | Giá trị đầu | Lấy từ check-in mới nhất | `okr.key_result.current_value`, `okr.key_result.target_value` |
| 10 | Mức tự tin | Liệt kê | Có sau check-in đầu | Cao, Trung bình, Thấp | Không có | Từ check-in mới nhất | `okr.kr_checkin.confidence` |
| 11 | Nguồn số liệu | Chuỗi | Không | Tên hệ thống nguồn | Nhập tay | Kết nối hợp lệ | `okr.key_result.data_source` (đề xuất) |
| 12 | Liên kết ngang | Danh sách | Không | Đơn vị, mục tiêu | Không có | Cùng kỳ | `okr.okr_link` (`link_type` = `PEER`) |
| 13 | Nhiệm vụ liên kết | Danh sách | Không | Mã, tên, tiến độ | Không có | Nhiệm vụ cùng kỳ | `task.task.kr_id` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Cây, Bảng, Bản đồ liên kết | Luôn | Đổi chế độ hiển thị | `/api/v1/okr/objectives?unit=&period=&view=tree` | Không có |
| Thêm mục tiêu | AC-05 của đơn vị, kỳ chưa khoá | Mở SCR-0202 | Không có | Không có |
| Xuất mẫu | Luôn | Sinh tệp theo mẫu báo cáo OKR, trả 202 | `/api/v1/okr/objectives/export` | Không có |
| Check-in KR | Chủ sở hữu KR, mục tiêu `DA_DUYET` | Mở SCR-0301 với KR đã chọn | Không có | Không có |
| Xem tất cả nhiệm vụ liên kết | Có liên kết | Mở danh sách nhiệm vụ đã lọc theo KR | `/api/v1/tasks?krId=` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-02-01 | KR {mã} có mức tự tin Thấp hai tuần liên tiếp và đã được đưa lên bảng điều hành của lãnh đạo. | KR chuyển sang `RUI_RO` |
| MSG-02-02 | Kỳ {kỳ} chưa có mục tiêu. Bấm "Thêm mục tiêu" hoặc sao chép từ kỳ trước. | Cây trống |

#### SCR-0202. Tạo, sửa mục tiêu và KR

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn mục tiêu và KR với kiểm tra tức thì, gợi ý của trợ lý AI và danh sách kiểm tra trước khi trình |
| Tác nhân | AC-05, AC-06 (người soạn); AC-12 (gợi ý) |
| Đường vào | SCR-0201, nút "Thêm mục tiêu" hoặc "Sửa" |
| Quyền | `manager` của đơn vị sở hữu, hoặc `member` với OKR cá nhân |
| Thiết bị | Máy tính |

![Hình 7.4. Màn hình soạn mục tiêu và KR SCR-0202 với cảnh báo và gợi ý của trợ lý AI](SCR-0202.png){width=16cm}

Hình 7.4 là bản nháp thứ ba của đồng chí Lê Minh Châu khi soạn mục tiêu Quý I/2027 ngày 05/01/2027. Vùng 1 là cảnh báo: kỳ đã có 3 mục tiêu, thêm mục tiêu thứ tư vượt khuyến nghị nên phải ghi lý do và cấp phê duyệt sẽ thấy cảnh báo. Vùng 2 là phần thông tin mục tiêu gồm căn cứ (Kế hoạch 05-KH/ĐUK), mục tiêu cấp trên, tên mục tiêu có bộ đếm ký tự, đơn vị sở hữu, kỳ và ô lý do vượt 3 mục tiêu. Vùng 3 là bảng KR soạn trực tiếp; dòng KR 1.3 "Tăng cường công tác kiểm tra, giám sát" bị đánh dấu đỏ vì mơ hồ, thiếu chỉ tiêu và đơn vị đo; dòng KR 1.4 "Tổ chức hội nghị sơ kết" bị đánh dấu vàng vì giống một nhiệm vụ. Vùng 4 là khối trợ lý AI có viền tím, ghi rõ chỉ đọc, đưa ra hai cách viết lại KR 1.3 có đo được, kèm nguồn tham chiếu, và hai nút "Bỏ qua", "Dùng gợi ý vào ô KR 1.3". Vùng 5 là danh sách kiểm tra trước khi trình và lời giải thích về phân rã ba tầng.

Ở phiên bản trình duyệt (xem SCR-0203), người soạn đã dùng gợi ý thứ nhất cho KR 1.3, chuyển KR 1.4 sang kế hoạch quý và rút bớt một mục tiêu để giữ ba mục tiêu. Đây là kết quả mong muốn của cơ chế cảnh báo: hệ thống không cấm, nhưng làm cho lựa chọn đúng trở thành lựa chọn dễ nhất.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Căn cứ | Nhiều lựa chọn | Có | Văn bản trong thư viện căn cứ | Căn cứ kỳ trước | BR-02-02 | `okr.basis` qua `okr.objective_basis` (bảng nối đề xuất) |
| 2 | Mục tiêu cấp trên | Lựa chọn | Có trừ cấp cao nhất | Mã, tên | Không có | Cùng kỳ | `okr.okr_link` |
| 3 | Tên mục tiêu | Chuỗi | Có | ≤ 200 ký tự | Không có | Không trống | `okr.objective.title` |
| 4 | Đơn vị sở hữu | Tham chiếu, chỉ đọc | Có | Tên đơn vị | Đơn vị của ngữ cảnh | Thuộc quyền người soạn | `okr.objective.owner_unit_id` |
| 5 | Kỳ | Tham chiếu | Có | Tên kỳ | Kỳ sắp tới hoặc đang mở | Chưa khoá | `okr.objective.period_id` |
| 6 | Lý do vượt 3 mục tiêu | Văn bản | Có khi vượt | ≤ 500 ký tự | Không có | BR-02-01 | `okr.objective.over_limit_reason` (đề xuất) |
| 7 | Mã KR | Chuỗi, tự sinh | Có | `KR {o}.{n}` | Tự sinh | Duy nhất trong mục tiêu | `okr.key_result.code` |
| 8 | Nội dung KR | Chuỗi | Có | ≤ 300 ký tự | Không có | BR-02-05, BR-02-06 | `okr.key_result.title` |
| 9 | Đơn vị đo | Lựa chọn hoặc chuỗi | Có | %, người, hồ sơ, ngày, lần, mốc | Không có | BR-02-04 | `okr.key_result.unit` |
| 10 | Giá trị đầu | Số | Có | Số thập phân | 0 | Không âm trừ khi đơn vị cho phép | `okr.key_result.baseline_value` |
| 11 | Chỉ tiêu | Số | Có | Số thập phân | Không có | Khác giá trị đầu | `okr.key_result.target_value` |
| 12 | Trọng số | Phần trăm | Có | 0 đến 100 | Chia đều | Tổng 100% (BR-02-07) | `okr.key_result.weight` |
| 13 | Hạn | Ngày | Có | dd/mm/yyyy | Ngày cuối kỳ | Trong kỳ | `okr.key_result.due_date` |
| 14 | Người xác nhận | Tham chiếu người | Có | Họ tên, chức danh | Người đứng đầu đơn vị | Khác chủ sở hữu KR | `okr.key_result.verifier_id` |
| 15 | Tần suất check-in | Liệt kê | Có | Tuần, Hai tuần, Tháng | Tuần | Theo Policy Pack | `okr.key_result.checkin_frequency` |
| 16 | Nguồn nội dung | Liệt kê, ẩn | Có | `HUMAN`, `AI_ACCEPTED`, `AI_EDITED` | `HUMAN` | Ghi khi lưu | `okr.key_result.content_source` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | Luôn | Lưu, tự động mỗi 30 giây | `/api/v1/okr/objectives/{id}` (PUT, If-Match) | Không có |
| Thêm KR | Dưới 5 KR | Thêm dòng | Không có (lưu cùng mục tiêu) | Không có |
| Trợ lý AI soát KR | Tenant bật AI | Gửi nội dung (không có dữ liệu C3) để soát | `/api/v1/ai/okr/review` (POST) | Không có |
| Dùng gợi ý vào ô | Có gợi ý | Điền nội dung với viền tím, chờ người soạn lưu | Không có | Không có |
| Trình phê duyệt | Không còn lỗi chặn | Kiểm tra lại ở máy chủ, chuyển `CHO_DUYET`, tạo tiến trình | `/api/v1/okr/objectives/{id}/submit` (POST, Idempotency-Key) | Không có (tiến trình duyệt tạo nhiệm vụ người duyệt) |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-02-03 | Mục tiêu cấp trên {mã} chưa được duyệt. Đồng chí có thể soạn nhưng chưa trình được. | Cấp trên chưa duyệt |
| MSG-02-04 | Kỳ {kỳ} đã có {n} mục tiêu. PL 1 khuyến nghị từ 1 đến 3 mục tiêu mỗi kỳ; vui lòng ghi lý do nếu vẫn thêm. | Vượt 3 mục tiêu |
| MSG-02-05 | KPI mơ hồ: "{từ}" không đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo. | Vi phạm BR-02-05 |
| MSG-02-06 | KR {mã} giống một nhiệm vụ, không phải kết quả đo được. Cân nhắc chuyển sang Kế hoạch quý. | Vi phạm BR-02-06 |
| MSG-02-07 | Tổng trọng số KR là {x}%, cần bằng 100%. | Vi phạm BR-02-07 |
| MSG-02-08 | Đã trình phê duyệt. Người duyệt: {tên}, hạn {ngày}. | Trình thành công |

#### SCR-0203. Phê duyệt OKR

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người có thẩm quyền xem hàng chờ OKR, kết quả kiểm tra tự động và phê duyệt hoặc trả lại |
| Tác nhân | AC-04 |
| Đường vào | Menu "Đánh giá / Chờ tôi duyệt"; thông báo; trang chủ của lãnh đạo |
| Quyền | `leader` của đơn vị cấp trên trực tiếp theo `flowable.wf_binding` |
| Thiết bị | Máy tính; trên di động chỉ xem và duyệt bộ không có cảnh báo |

![Hình 7.5. Màn hình phê duyệt OKR SCR-0203 của Phó Bí thư Thường trực](SCR-0203.png){width=16cm}

Hình 7.5 là màn hình của đồng chí Đinh Văn Sơn, Phó Bí thư Thường trực, ngày 07/01/2027 với 6 bộ OKR Quý I/2027 chờ duyệt, hạn duyệt 08/01/2027. Vùng 1 là hàng chờ theo đơn vị với số mục tiêu, số KR và kết quả kiểm tra tổng hợp; các nhãn "Vượt 3 O" và "KR mơ hồ" giúp người duyệt biết bộ nào cần xem kỹ. Vùng 2 là chi tiết bộ OKR của Ban Tổ chức với mỗi mục tiêu, KR, chỉ tiêu, trọng số, liên kết cấp trên hoặc ngang và kết quả kiểm tra từng dòng; KR 1.3 được đánh dấu "Gần giống NV-2027-Q1-0012" vì hệ thống phát hiện nội dung tương tự một nhiệm vụ đã có. Vùng 3 là ô ý kiến phê duyệt, bắt buộc khi trả lại, và tuỳ chọn thông báo cho các đơn vị liên kết ngang. Vùng 4 là hai nút "Trả lại để sửa" và "Phê duyệt". Vùng 5 là lịch sử luồng duyệt và ghi chú về mẫu luồng `duyet-okr` v2 với tham số theo tenant.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Hàng chờ | Danh sách | Có | Đơn vị, ngày trình, người trình, số O/KR, kiểm tra | Sắp theo ngày trình | Nhiệm vụ duyệt của người dùng | `flowable.wf_task_projection` |
| 2 | Phiên bản | Số, chỉ đọc | Có | Số nguyên | Phiên bản mới nhất | Không có | `okr.objective.version_no` (đề xuất) |
| 3 | Kết quả kiểm tra | Danh sách | Có | Đạt, cảnh báo, lỗi | Không có | Tính lại khi mở | Kết quả kiểm tra của MOD-01 (không lưu) |
| 4 | Ý kiến phê duyệt | Văn bản | Có khi trả lại | ≤ 2.000 ký tự | Không có | BR-02-09 | `okr.objective_review.comment` (bảng đề xuất) |
| 5 | Thông báo liên kết ngang | Hộp chọn | Không | Đúng/Sai | Đúng | Không có | Tham số yêu cầu |
| 6 | Lịch sử luồng duyệt | Danh sách | Có | Thời điểm, người, hành động | Không có | Chỉ đọc | `flowable.ACT_HI_TASKINST` (qua projection) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Phê duyệt | Người duyệt khác người soạn | Hoàn thành nhiệm vụ duyệt, chuyển `DA_DUYET` | `/api/v1/okr/objectives/{id}/approve` (POST, Idempotency-Key) | EVT-01 |
| Trả lại để sửa | Có ý kiến | Chuyển về `NHAP`, thông báo người soạn | `/api/v1/okr/objectives/{id}/return` (POST) | EVT-21 |
| Duyệt hàng loạt (đủ điều kiện) | Có bộ không cảnh báo | Duyệt các bộ "Đạt" đã chọn, mỗi bộ một giao dịch | `/api/v1/okr/objectives/approve-batch` (POST, 202) | EVT-01 cho từng bộ |
| Xem cây | Luôn | Mở SCR-0201 ở chế độ chỉ đọc | Không có | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-02-09 | Đã phê duyệt OKR của {đơn vị}. Check-in KR đã được mở. | Duyệt thành công |
| MSG-02-10 | Vui lòng nhập ý kiến khi trả lại. | Trả lại không có ý kiến |
| MSG-02-11 | Đồng chí là người soạn bộ OKR này nên không thể phê duyệt. | Vi phạm BR-02-08 |
| MSG-02-12 | Còn {n} bộ OKR chờ duyệt, hạn {ngày}. | Nhắc trước hạn 1 ngày |

### Liên kết thiết kế

QT-02 do module okr (MOD-01) của mission-core (SVC-05) xử lý, luồng duyệt chạy trên workflow-service (SVC-06) theo mẫu `duyet-okr` với tham số trong `flowable.wf_binding`, trợ lý AI qua ai-gateway (SVC-14). Bảng dữ liệu chính là `okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `flowable.wf_task_projection`. Sự kiện phát ra là MucTieuDaDuyet (EVT-01) và YeuCauThongBao (EVT-21); analytics (SVC-15), ledger-service (SVC-09) và module task (MOD-02) tiêu thụ EVT-01. Tài liệu đề xuất thêm bảng `okr.objective_basis`, `okr.objective_review` và một số cột (xem tệp ghi chú). Về an toàn thông tin, quy trình liên quan tới kiểm soát truy cập ABAC theo cây tổ chức và chính sách dữ liệu gửi tới AI, đặc tả trong TL-04; lập luận vì sao luồng duyệt OKR dùng BPMN còn check-in thì không dùng được trình bày trong TL-01 (ADR-04).

## QT-03. Check-in KR

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-03 |
| Tên quy trình | Check-in kết quả then chốt |
| Mục đích | Cập nhật định kỳ giá trị hiện tại của KR kèm mức tự tin đạt chỉ tiêu, kết quả nổi bật và vướng mắc, để lãnh đạo phát hiện sớm KR có nguy cơ không đạt |
| Phạm vi áp dụng | Mọi KR thuộc mục tiêu ở trạng thái `DA_DUYET` trong kỳ đang `MO` |
| Căn cứ | PL 1 dự thảo Quy định mới (theo dõi, cập nhật kết quả then chốt định kỳ, mức tự tin) |
| Tác nhân | AC-05, AC-06 (chủ sở hữu KR), AC-11 (nguồn số liệu), AC-04 (nhận cảnh báo) |
| Tần suất và khối lượng ước tính | Mặc định hằng tuần; ước tính khoảng 1,2 triệu KR đang hoạt động toàn quốc (giả định 3 KR cho mỗi bộ OKR), khoảng 1,2 triệu lần check-in mỗi tuần, dồn 40% vào chiều thứ Sáu |
| Điều kiện bắt đầu | Mục tiêu đã duyệt; đến kỳ check-in theo tần suất của KR |
| Điều kiện kết thúc | Bản ghi check-in ở trạng thái `DA_GUI`; tiến độ mục tiêu được tính lại; EVT-02 đã phát |
| Đầu vào | Giá trị hiện tại (nhập tay hoặc lấy tự động), mức tự tin, kết quả nổi bật, vướng mắc |
| Đầu ra | Bản ghi `okr.kr_checkin`, cập nhật `okr.key_result.current_value`, cờ rủi ro, bảng điều hành |
| Chỉ số đo hiệu quả | Tỷ lệ KR được check-in đúng hạn ≥ 90%; thời gian check-in trên di động ≤ 60 giây, ≤ 3 chạm từ thông báo; 100% KR có mức tự tin Thấp hai lần liên tiếp xuất hiện trên bảng điều hành trong 5 phút |

Check-in là thao tác có tần suất cao nhất của phần OKR và vì vậy phải nhẹ nhất. Theo ADR-04, check-in không chạy qua engine BPMN mà là thao tác ghi trực tiếp vào module okr với máy trạng thái khai báo; không có bước duyệt. Giá trị check-in được ghi theo nguyên tắc chỉ thêm (append-only): một lần check-in đã gửi không sửa được, nếu nhập sai thì gửi một lần check-in mới có ghi chú đính chính. Cách làm này đơn giản hoá việc kiểm toán và phù hợp với tinh thần của OKR là theo dõi xu hướng chứ không phải chấm điểm từng lần.

Mức tự tin là phần quan trọng nhất của check-in. Một KR có thể đang ở mức thấp nhưng chủ sở hữu tự tin Cao vì biết kết quả sẽ đến dồn vào cuối kỳ, ngược lại một KR đang tiến triển tốt có thể bị đánh giá tự tin Thấp vì một rủi ro mới xuất hiện. Nền tảng dùng mức tự tin làm tín hiệu cảnh báo sớm: KR có mức tự tin Thấp hai lần liên tiếp được gắn cờ rủi ro và đưa lên bảng điều hành của lãnh đạo đơn vị để chỉ đạo kịp thời.

### Lưu đồ quy trình

![Hình 7.6. Lưu đồ làn bơi QT-03 Check-in KR](nv-qt03-luong.png){width=16cm}

Hình 7.6 có bốn làn. Chủ sở hữu KR nhận nhắc check-in, mở KR trên web hoặc di động. Nếu KR có nguồn số liệu (ví dụ CSDL đảng viên), integration-gateway (SVC-13) lấy giá trị tự động và người dùng chỉ cần xác nhận. Người dùng chọn mức tự tin, ghi kết quả và vướng mắc rồi gửi. Module okr (MOD-01) ghi bản ghi chỉ thêm, tính lại tiến độ mục tiêu và kiểm tra điều kiện "tự tin Thấp hai lần liên tiếp"; nếu có, lãnh đạo (AC-04) nhận cảnh báo để chỉ đạo hoặc giao thêm việc. Cuối cùng hệ thống phát KrDaCheckIn (EVT-02) và cập nhật bảng điều hành.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Nhắc check-in | SVC-12 | Gửi thông báo đẩy và thư trong ứng dụng vào sáng thứ Năm cho KR chưa check-in trong chu kỳ | Không có | Thông báo | Theo tần suất KR |
| 2 | Mở KR | AC-05, AC-06 | Mở danh sách KR cần check-in từ thông báo, trang chủ hoặc tab "OKR" | SCR-0301, SCR-0302 | Không có | Không có |
| 3 | Lấy số liệu nguồn | SVC-13 | Nếu KR có nguồn số liệu, lấy giá trị và thời điểm lấy; người dùng có thể ghi đè kèm lý do | SCR-0301 | Giá trị gợi ý | ≤ 3 giây |
| 4 | Nhập giá trị hiện tại | AC-05, AC-06 | Gõ số, kéo thanh trượt (di động) hoặc xác nhận số liệu nguồn | SCR-0301, SCR-0302 | Không có | Không có |
| 5 | Chọn mức tự tin | AC-05, AC-06 | Chọn Cao, Trung bình hoặc Thấp | SCR-0301, SCR-0302 | Không có | Không có |
| 6 | Ghi kết quả, vướng mắc | AC-05, AC-06 | Nhập kết quả nổi bật và vướng mắc, đề nghị hỗ trợ; trên di động có thể nói để nhập | SCR-0301, SCR-0302 | Không có | Không có |
| 7 | Gửi | AC-05, AC-06 | Gửi check-in; trên di động nếu mất sóng thì lưu tạm và tự gửi khi có mạng | SCR-0301, SCR-0302 | `DA_GUI` | Trước hạn check-in |
| 8 | Ghi và tính lại | MOD-01 | Ghi `okr.kr_checkin`, cập nhật giá trị hiện tại của KR, tính lại tiến độ mục tiêu, cập nhật cờ rủi ro | Không có | EVT-02 | ≤ 1 giây |
| 9 | Cảnh báo lãnh đạo | MOD-01, SVC-12, SVC-15 | KR chuyển sang `RUI_RO`: đưa lên bảng điều hành, thông báo lãnh đạo đơn vị | Không có | EVT-21 | ≤ 5 phút |

Điểm cần lưu ý là bước 3. Số liệu tự động chỉ là gợi ý; chủ sở hữu KR vẫn là người xác nhận và chịu trách nhiệm về con số. Khi người dùng ghi đè số liệu nguồn, hệ thống yêu cầu lý do và lưu cả giá trị nguồn lẫn giá trị người nhập để người xác nhận KR đối chiếu khi cần.

### Trạng thái đối tượng

QT-03 làm thay đổi hai đối tượng: bản ghi check-in và cờ rủi ro của KR. Cả hai đều chưa có trong danh sách trạng thái chuẩn; tài liệu đề xuất như Hình 7.7.

![Hình 7.7. Trạng thái của lần check-in và cờ rủi ro của KR](nv-qt03-trang-thai.png){width=14cm}

Hình 7.7 cho thấy lần check-in chỉ có hai trạng thái `NHAP` và `DA_GUI`, sau khi gửi thì bất biến. Cờ rủi ro có ba mức: `BINH_THUONG`, `THEO_DOI` sau một lần tự tin Thấp và `RUI_RO` sau lần Thấp thứ hai liên tiếp. KR rời `RUI_RO` khi có hai lần check-in liên tiếp không Thấp, để tránh cờ bật tắt liên tục.

| Đối tượng | Trạng thái hiện tại | Sự kiện | Trạng thái mới | Ghi chú |
|---|---|---|---|---|
| Lần check-in | (chưa có) | Lưu nháp | `NHAP` | Chỉ người tạo thấy |
| Lần check-in | `NHAP` | Gửi | `DA_GUI` | Bất biến, phát EVT-02 |
| Cờ rủi ro KR | `BINH_THUONG` | Check-in tự tin Thấp | `THEO_DOI` | Không thông báo lãnh đạo |
| Cờ rủi ro KR | `THEO_DOI` | Check-in tự tin Thấp | `RUI_RO` | Đưa lên bảng điều hành, thông báo |
| Cờ rủi ro KR | `THEO_DOI` | Check-in Trung bình hoặc Cao | `BINH_THUONG` | Không có |
| Cờ rủi ro KR | `RUI_RO` | Hai lần liên tiếp không Thấp | `BINH_THUONG` | Gỡ khỏi bảng điều hành |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-03-01 | Chỉ check-in KR thuộc mục tiêu `DA_DUYET` trong kỳ `MO` | Máy trạng thái mục tiêu và kỳ | Chặn |
| BR-03-02 | Mỗi lần check-in bắt buộc có giá trị hiện tại và mức tự tin | PL 1 (theo dõi định kỳ, mức tự tin) | Chặn |
| BR-03-03 | Check-in đã gửi không sửa, không xoá; đính chính bằng check-in mới | PL 4 về toàn vẹn | Chặn: không có API sửa |
| BR-03-04 | Ghi đè số liệu nguồn phải có lý do | Truy vết số liệu | Chặn khi thiếu lý do; ghi cả hai giá trị |
| BR-03-05 | Giá trị hiện tại thay đổi quá 50% khoảng chỉ tiêu so với lần trước được cảnh báo nhập nhầm | Chất lượng dữ liệu | Cảnh báo, yêu cầu xác nhận |
| BR-03-06 | Tự tin Thấp hai lần liên tiếp chuyển KR sang `RUI_RO` và thông báo lãnh đạo | Quản trị rủi ro theo OKR | Ghi vết và thông báo tự động |
| BR-03-07 | Tiến độ nhiệm vụ không tự cộng vào giá trị KR | PL 1 (KR không đồng nhất với nhiệm vụ) | Thiết kế: không có liên kết tính toán |
| BR-03-08 | Quá hạn check-in hai chu kỳ liên tiếp được ghi nhận cho người đứng đầu | Kỷ luật điều hành | Ghi vết, hiển thị trên bảng điều hành |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Nguồn số liệu không phản hồi | Hiển thị MSG-03-03, cho nhập tay; đánh dấu giá trị là nhập tay để người xác nhận biết |
| Mất sóng trên di động | Lưu tạm trong vùng lưu trữ mã hoá, gửi lại với cùng Idempotency-Key khi có mạng; nếu kỳ đã khoá lúc gửi lại thì báo MSG-03-05 |
| Chủ sở hữu KR vắng mặt dài ngày | Lãnh đạo đơn vị giao quyền check-in tạm thời cho người khác; check-in ghi rõ "check-in thay" |
| Nhập nhầm giá trị đã gửi | Gửi check-in mới với ghi chú đính chính; biểu đồ hiển thị cả hai điểm |
| Mục tiêu đang chờ duyệt phiên bản điều chỉnh | Check-in tiếp tục trên phiên bản đã duyệt; khi phiên bản mới được duyệt, giá trị hiện tại được chuyển sang KR tương ứng nếu KR giữ nguyên mã |

### Đặc tả màn hình

#### SCR-0301. Check-in KR

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho chủ sở hữu KR cập nhật giá trị, mức tự tin, kết quả và vướng mắc trên máy tính, với ngữ cảnh đầy đủ |
| Tác nhân | AC-05, AC-06 |
| Đường vào | Menu "Mục tiêu và nhiệm vụ / Check-in KR" (có số đếm); SCR-0201; trang chủ |
| Quyền | Chủ sở hữu KR hoặc người được giao check-in thay |
| Thiết bị | Máy tính |

![Hình 7.8. Màn hình check-in KR SCR-0301 trên máy tính](SCR-0301.png){width=16cm}

Hình 7.8 là màn hình check-in tuần 4 của đồng chí Nguyễn Văn An, hạn thứ Sáu 23/10/2026. Vùng 1 là danh sách KR cần check-in, KR 2.1 đã check-in ngày 16/10. Vùng 2 là thông tin KR 1.1 đang check-in: mục tiêu, công thức tính (số đảng viên mới kết nạp trong năm chia 500 đảng viên đầu kỳ), chỉ tiêu 3% tương ứng 15 người, giá trị lần trước 2,4% (12 người), giá trị hiện tại 2,6% (13 người) và tiến độ 87% chỉ tiêu; giá trị được lấy tự động từ CSDL đảng viên lúc 08:00 qua SVC-13. Vùng 3 là ba nút chọn mức tự tin và hai ô kết quả nổi bật, vướng mắc, kèm lời nhắc rằng check-in được ghi vào sổ cái và không sửa sau khi gửi. Vùng 4 là nút "Gửi check-in". Vùng 5 là biểu đồ diễn biến KR theo tuần với đường chỉ tiêu và danh sách nhiệm vụ liên kết, kèm câu giải thích rằng tiến độ nhiệm vụ không tự cộng vào KR.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | KR | Tham chiếu, chỉ đọc | Có | Mã, nội dung | KR đầu tiên chưa check-in | BR-03-01 | `okr.key_result.id` |
| 2 | Công thức | Văn bản, chỉ đọc | Không | ≤ 300 ký tự | Không có | Không có | `okr.key_result.formula_text` (đề xuất) |
| 3 | Chỉ tiêu | Số, chỉ đọc | Có | Theo đơn vị đo | Không có | Không có | `okr.key_result.target_value` |
| 4 | Giá trị lần trước | Số, chỉ đọc | Không | Theo đơn vị đo | Không có | Không có | `okr.kr_checkin.value` (lần gần nhất) |
| 5 | Giá trị hiện tại | Số | Có | Số thập phân, tối đa 4 chữ số lẻ | Giá trị nguồn hoặc lần trước | BR-03-02, BR-03-05 | `okr.kr_checkin.value` |
| 6 | Giá trị nguồn | Số, chỉ đọc | Không | Theo đơn vị đo | Không có | Lưu kèm thời điểm lấy | `okr.kr_checkin.source_value`, `source_fetched_at` (đề xuất) |
| 7 | Lý do ghi đè | Văn bản | Có khi ghi đè | ≤ 500 ký tự | Không có | BR-03-04 | `okr.kr_checkin.override_reason` (đề xuất) |
| 8 | Mức tự tin | Liệt kê | Có | `CAO`, `TRUNG_BINH`, `THAP` | Mức lần trước | BR-03-02 | `okr.kr_checkin.confidence` |
| 9 | Kết quả nổi bật | Văn bản | Không | ≤ 1.000 ký tự | Không có | Không có | `okr.kr_checkin.highlights` |
| 10 | Vướng mắc, đề nghị hỗ trợ | Văn bản | Không | ≤ 1.000 ký tự | Không có | Bắt buộc khi tự tin Thấp (cấu hình) | `okr.kr_checkin.blockers` |
| 11 | Trạng thái | Liệt kê, ẩn | Có | `NHAP`, `DA_GUI` | `NHAP` | Không có | `okr.kr_checkin.status` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | Luôn | Lưu bản nháp | `/api/v1/okr/key-results/{id}/checkins` (POST, `status=NHAP`) | Không có |
| Gửi check-in | Đủ trường bắt buộc | Ghi `DA_GUI`, tính lại tiến độ, cập nhật cờ rủi ro | `/api/v1/okr/key-results/{id}/checkins` (POST, Idempotency-Key) | EVT-02; EVT-21 nếu chuyển `RUI_RO` |
| Lấy lại số liệu nguồn | KR có nguồn | Gọi lại nguồn | `/api/v1/okr/key-results/{id}/source-value` | Không có |
| Lịch sử | Luôn | Mở lịch sử check-in | `/api/v1/okr/key-results/{id}/checkins?cursor=` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-03-01 | Đã gửi check-in KR {mã}. Tiến độ mục tiêu {O} hiện là {x}%. | Gửi thành công |
| MSG-03-02 | Giá trị mới chênh {x}% so với lần trước. Đồng chí có chắc chắn không? | Vi phạm BR-03-05 |
| MSG-03-03 | Không lấy được số liệu từ {nguồn}. Đồng chí có thể nhập tay. | Nguồn lỗi |
| MSG-03-04 | Vui lòng ghi lý do khi thay đổi số liệu lấy từ {nguồn}. | Ghi đè thiếu lý do |
| MSG-03-05 | Kỳ {kỳ} đã khoá. Check-in lưu trên máy chưa được gửi. | Gửi lại sau khi khoá kỳ |

#### SCR-0302. Check-in trên di động

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Check-in nhanh trên điện thoại trong tối đa ba chạm từ thông báo, kể cả khi sóng yếu |
| Tác nhân | AC-05, AC-06, đặc biệt đảng viên ở chi bộ cơ sở |
| Đường vào | Thông báo đẩy "Check-in tuần"; tab "OKR" |
| Quyền | Như SCR-0301 |
| Thiết bị | Điện thoại Android, iOS |

![Hình 7.9. Check-in KR trên di động SCR-0302 gồm danh sách KR và màn hình nhập](SCR-0302.png){width=15cm}

Hình 7.9 có hai điện thoại. Điện thoại bên trái lúc 08:42 hiển thị vùng 1 là danh sách 2 KR cần check-in với hạn 23/10, mỗi KR có giá trị trên chỉ tiêu, mức tự tin lần trước và nút "Check-in", kèm dòng giải thích dữ liệu được lưu tạm có mã hoá khi mất sóng. Điện thoại bên phải lúc 08:44 là màn hình check-in KR 1.3. Vùng 2 là giá trị hiện tại 37,0% (17/46 tổ chức) nhập bằng thanh trượt hoặc gõ số. Vùng 3 là ba nút mức tự tin cỡ lớn và ô vướng mắc. Vùng 4 là nút "Nói để nhập" dùng nhận dạng giọng nói tiếng Việt, hữu ích cho đảng viên cao tuổi. Vùng 5 là nút "Gửi check-in" cố định ở cuối màn hình. Từ thông báo, người dùng chạm lần một để mở KR, lần hai để chọn mức tự tin (giá trị nguồn đã điền sẵn), lần ba để gửi, đáp ứng NFR-11.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Danh sách KR cần check-in | Danh sách | Có | Mã, nội dung rút gọn, giá trị | Theo hạn | Như SCR-0301 | `okr.key_result`, `okr.kr_checkin` |
| 2 | Giá trị hiện tại | Số, thanh trượt | Có | Bước 0,1 theo đơn vị | Giá trị nguồn hoặc lần trước | BR-03-05 | `okr.kr_checkin.value` |
| 3 | Mức tự tin | Liệt kê | Có | Ba nút | Mức lần trước | BR-03-02 | `okr.kr_checkin.confidence` |
| 4 | Vướng mắc | Văn bản hoặc giọng nói | Không | ≤ 1.000 ký tự | Không có | Giọng nói chuyển thành văn bản trên thiết bị hoặc qua SVC-14 | `okr.kr_checkin.blockers` |
| 5 | Khoá chống ghi trùng | Chuỗi, ẩn | Có | UUIDv7 | Sinh khi bấm gửi | Không đổi khi gửi lại | Header `Idempotency-Key` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Check-in | KR chưa check-in chu kỳ | Mở màn hình nhập | Không có | Không có |
| Nói để nhập | Thiết bị có micro, người dùng cho phép | Ghi âm, chuyển văn bản, điền ô vướng mắc | Trên thiết bị hoặc `/api/v1/ai/speech-to-text` | Không có |
| Gửi check-in | Đủ trường | Gửi ngay hoặc xếp hàng chờ khi mất sóng | `/api/v1/okr/key-results/{id}/checkins` (POST, Idempotency-Key) | EVT-02 |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-03-06 | Đã lưu trên máy. Check-in sẽ tự gửi khi có mạng. | Mất sóng |
| MSG-03-07 | Đã gửi {n} check-in đang chờ. | Có mạng trở lại |

### Liên kết thiết kế

QT-03 do module okr (MOD-01) xử lý trực tiếp, không qua workflow-service; số liệu nguồn qua integration-gateway (SVC-13); thông báo qua notification-service (SVC-12); ứng dụng di động (SVC-18) có hàng đợi ngoại tuyến. Bảng dữ liệu chính là `okr.key_result`, `okr.kr_checkin`. Sự kiện phát ra là KrDaCheckIn (EVT-02), tiêu thụ bởi analytics (SVC-15), notification-service và ledger-service, và EVT-21 khi KR chuyển sang rủi ro. Tài liệu đề xuất thêm cột trạng thái cho `okr.kr_checkin` và cột cờ rủi ro cho `okr.key_result` (xem tệp ghi chú). Kịch bản thử tải đỉnh check-in chiều thứ Sáu và lưu trữ ngoại tuyến được mã hoá trên thiết bị được đặc tả trong TL-04.

## QT-04. Lập và phê duyệt kế hoạch công tác quý

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-04 |
| Tên quy trình | Lập và phê duyệt kế hoạch công tác quý |
| Mục đích | Lập kế hoạch công tác quý theo Bảng 1 PL 1 với công việc chọn từ danh mục Bảng 3, mỗi việc có đúng một đơn vị chủ trì, sản phẩm đo được, hạn và điểm; phê duyệt có ký số trước hạn; điều chỉnh có kiểm soát bằng phiên bản |
| Phạm vi áp dụng | Mọi đơn vị và cá nhân có kế hoạch công tác quý trong cả hai luồng; bao gồm cả việc duy trì thư viện danh mục công việc |
| Căn cứ | PL 1 dự thảo Quy định mới: Bảng 1 (kế hoạch công tác), Bảng 3 (danh mục công việc theo vị trí việc làm, mức khó, điểm, tiêu chuẩn chất lượng, minh chứng), yêu cầu duyệt kế hoạch trước tuần thứ hai của tháng đầu quý; NĐ 335/2025 về đánh giá theo sản phẩm cụ thể; NĐ 30/2020/NĐ-CP về thể thức văn bản (tiêu chuẩn chất lượng của sản phẩm là văn bản) |
| Tác nhân | AC-05 (người lập), AC-04 (người duyệt), AC-03 (quản trị danh mục), AC-06 (đăng ký nhiệm vụ đưa vào điều chỉnh), SVC-10 (ký số) |
| Tần suất và khối lượng ước tính | Mỗi đơn vị mỗi quý một kế hoạch và tối đa hai lần điều chỉnh; ước tính khoảng 6 triệu kế hoạch cá nhân và đơn vị mỗi quý nếu tenant cấu hình kế hoạch cá nhân, tương ứng phần "duyệt kế hoạch" trong ước tính 12 triệu tiến trình BPMN mỗi quý của cơ sở thiết kế chung; trung bình 8 đến 15 dòng mỗi kế hoạch |
| Điều kiện bắt đầu | Kỳ quý đã tạo; Policy Pack của kỳ đã gắn; danh mục công việc của vị trí việc làm đã có; OKR của kỳ đã duyệt hoặc đang chờ duyệt |
| Điều kiện kết thúc | Phiên bản kế hoạch ở `DA_DUYET`, đã ký số; sự kiện KeHoachDaDuyet (EVT-03) đã phát; nhiệm vụ `NHAP` đã được sinh cho từng dòng |
| Đầu vào | Danh mục Bảng 3, kế hoạch quý trước, OKR đã duyệt, văn bản chỉ đạo, đăng ký nhiệm vụ của cán bộ |
| Đầu ra | `task.plan`, `task.plan_version`, `task.plan_item`; tệp kế hoạch đã ký số; nhiệm vụ `NHAP` trong `task.task` |
| Chỉ số đo hiệu quả | ≥ 95% kế hoạch được duyệt trước hạn; 0 dòng kế hoạch có KPI mơ hồ được duyệt; ≥ 80% dòng kế hoạch lấy từ danh mục; thời gian lập kế hoạch trung bình ≤ 45 phút khi sao chép từ quý trước |

Kế hoạch quý là cầu nối giữa OKR và nhiệm vụ. Theo PL 1, mỗi dòng kế hoạch là một công việc có đơn vị chủ trì, đơn vị phối hợp, sản phẩm, số lượng, hạn và điểm; điểm lấy từ danh mục Bảng 3 theo mức khó, nên hai cán bộ cùng vị trí việc làm làm cùng loại việc sẽ được tính cùng thang. Điều này giải quyết vấn đề lớn nhất của đánh giá cán bộ truyền thống là mỗi đơn vị tự đặt "chỉ tiêu" theo cách riêng, không so sánh được. Nền tảng vì vậy coi thư viện danh mục là dữ liệu dùng chung có phiên bản: cơ quan tổ chức cấp trên ban hành danh mục mẫu, đơn vị kế thừa và bổ sung, và mọi thay đổi danh mục sau khi đã dùng ở kỳ đã khoá đều tạo phiên bản mới để không làm thay đổi điểm đã tính.

Phê duyệt kế hoạch là một trong bốn mốc ký số pháp lý của nền tảng (G3, lớp bảo vệ thứ hai). Khi được duyệt, nội dung phiên bản kế hoạch được khoá, tính mã băm SHA-256 và ký số bằng chữ ký của người duyệt qua MySign; từ đó, mọi điểm số tính cho các nhiệm vụ của kế hoạch đều truy ngược được về một phiên bản kế hoạch đã ký. Điều chỉnh kế hoạch giữa quý không sửa phiên bản đã ký mà tạo phiên bản mới, đi lại toàn bộ luồng duyệt và ký số. Hệ thống so sánh hai phiên bản cho người duyệt thấy chính xác việc gì được thêm, sửa, bỏ và vì sao.

### Lưu đồ quy trình

![Hình 7.10. Lưu đồ làn bơi QT-04 Lập và phê duyệt kế hoạch công tác quý](nv-qt04-luong.png){width=16cm}

Hình 7.10 có bốn làn. Người lập kế hoạch (AC-05) bắt đầu khi kỳ mở và OKR đã duyệt, sao chép kế hoạch quý trước hoặc nhận gợi ý từ OKR, rồi chọn việc từ danh mục Bảng 3 và đặt chủ trì, phối hợp, sản phẩm, số lượng, hạn. Làn hệ thống (MOD-02) kiểm tra lỗi chặn như KPI mơ hồ, thiếu chủ trì, thiếu sản phẩm; nếu có lỗi, người lập sửa dòng lỗi và chọn lại. Khi không còn lỗi chặn, người lập gửi duyệt, hệ thống khoá nội dung phiên bản và tính mã băm. Người duyệt (AC-04) duyệt hoặc trả lại kèm ý kiến. Khi duyệt, esign-service (SVC-10) ký số kế hoạch qua MySign kèm dấu thời gian, sau đó hệ thống chuyển `DA_DUYET`, phát EVT-03 và sinh các nhiệm vụ ở trạng thái `NHAP`.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Duy trì danh mục | AC-03, AC-05 | Kế thừa danh mục mẫu cấp trên, thêm công việc đặc thù, nhập từ Excel; mỗi công việc có mã, tên, sản phẩm, đơn vị tính, mức khó 1 đến 5, điểm chuẩn 0 đến 100, tiêu chuẩn chất lượng, minh chứng yêu cầu, thời hạn chuẩn | SCR-0401 | `task.work_catalog`, `task.catalog_item` | Trước kỳ |
| 2 | Khởi tạo kế hoạch | AC-05 | Tạo phiên bản 1 ở `NHAP`, sao chép từ quý trước hoặc nhận gợi ý từ OKR (mỗi KR chưa có nhiệm vụ được gợi ý một dòng) | SCR-0402 | `task.plan_version` `NHAP` | Ngày đầu quý |
| 3 | Lập danh sách công việc | AC-05 | Với mỗi dòng: nội dung, nhóm (thường xuyên hoặc then chốt), KR liên kết, đúng một chủ trì, phối hợp, mã danh mục, số lượng, hạn; điểm lấy từ danh mục | SCR-0402 | `task.plan_item` | Không có |
| 4 | Kiểm tra tức thì | MOD-02 | Kiểm tra từng dòng và toàn kế hoạch: KPI mơ hồ, thiếu sản phẩm, thiếu chủ trì, việc ngoài danh mục chưa có điểm, KR chưa có nhiệm vụ, tỷ trọng nhóm | SCR-0402 | Danh sách lỗi, cảnh báo | Tức thì |
| 5 | Gửi duyệt | AC-05 | Hệ thống kiểm tra lại ở máy chủ; nếu đạt, khoá nội dung, tính mã băm, chuyển `CHO_DUYET`, tạo tiến trình `duyet-ke-hoach` | SCR-0402 | `CHO_DUYET` | Trước hạn duyệt |
| 6 | Xem xét | AC-04 | Xem kế hoạch, kết quả kiểm tra; với điều chỉnh, xem so sánh với phiên bản đã duyệt | SCR-0403 | Không có | Hạn duyệt |
| 7a | Duyệt và ký số | AC-04, SVC-10 | MFA bước lên nếu cần; ký số bằng MySign; esign-service gắn dấu thời gian và lưu chữ ký | SCR-0403 | `DA_DUYET`; EVT-18 rồi EVT-03 | Trước hết tuần thứ hai của tháng đầu quý |
| 7b | Trả lại | AC-04 | Ghi ý kiến, trả lại | SCR-0403 | `TRA_LAI`; EVT-21 | Hạn duyệt |
| 8 | Sinh nhiệm vụ | MOD-02 | Tiêu thụ EVT-03, sinh mỗi dòng mới một nhiệm vụ `NHAP` gắn `plan_item_id`; với điều chỉnh, dòng sửa cập nhật nhiệm vụ chưa bắt đầu, dòng bỏ chuyển nhiệm vụ sang `HUY` nếu chưa có minh chứng xác nhận | Không có | EVT-06 cho nhiệm vụ huỷ | ≤ 1 phút |
| 9 | Điều chỉnh giữa quý | AC-05 | Tạo phiên bản n+1 từ phiên bản đã duyệt, sửa, ghi lý do từng thay đổi, gửi duyệt lại; tối đa 2 lần trong quý (tham số) | SCR-0402, SCR-0403 | Phiên bản mới `NHAP` | Trước tháng cuối quý (tham số) |

Có hai điểm khó cần giải thích. Thứ nhất là hạn duyệt. Hạn được tính tự động từ tham số `plan_approval_deadline_rule` của Policy Pack và lịch nghỉ của tenant; với Quý IV/2026 là thứ Sáu 09/10/2026. Quá hạn, hệ thống không chặn duyệt (vì chặn sẽ khiến đơn vị không có kế hoạch) nhưng ghi nhận dấu hiệu bất thường cho QT-14 và thông báo người đứng đầu. Thứ hai là quan hệ giữa điều chỉnh kế hoạch và hạn nhiệm vụ. Nếu cho phép đổi hạn nhiệm vụ qua điều chỉnh kế hoạch, đơn vị có thể "hợp thức hoá" việc chậm bằng cách lùi hạn hàng loạt, làm sai hệ số tiến độ C. Vì vậy hạn của nhiệm vụ đang thực hiện không đổi qua điều chỉnh kế hoạch; muốn đổi hạn phải dùng đề nghị gia hạn ở QT-06, nơi người duyệt phải xác định nguyên nhân chủ quan hay khách quan.

### Trạng thái đối tượng

Đối tượng chính là phiên bản kế hoạch `task.plan_version` với trạng thái chuẩn `NHAP`, `CHO_DUYET`, `DA_DUYET`, `TRA_LAI`.

![Hình 7.11. Trạng thái của phiên bản kế hoạch task.plan_version](nv-qt04-trang-thai.png){width=12cm}

Hình 7.11 cho thấy phiên bản đã duyệt là bất biến. Điều chỉnh không sửa phiên bản này mà tạo phiên bản n+1 ở `NHAP`. Tại mọi thời điểm, kế hoạch có đúng một phiên bản hiệu lực là phiên bản `DA_DUYET` có số lớn nhất; các nhiệm vụ đọc thông tin kế hoạch từ phiên bản hiệu lực đó.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Tạo phiên bản | `NHAP` | Kỳ `MO`; với điều chỉnh, số lần điều chỉnh chưa vượt giới hạn | Phiên bản n+1 sao chép từ phiên bản hiệu lực |
| `NHAP` | Gửi duyệt | `CHO_DUYET` | Không lỗi chặn | Khoá nội dung, tính mã băm |
| `CHO_DUYET` | Trả lại | `TRA_LAI` | Có ý kiến | Mở khoá nội dung cho người lập |
| `TRA_LAI` | Sửa và gửi lại | `CHO_DUYET` | Không lỗi chặn | Mã băm tính lại |
| `CHO_DUYET` | Duyệt và ký số thành công | `DA_DUYET` | Người duyệt khác người lập; chữ ký hợp lệ | Phát EVT-03; phiên bản trước (nếu có) hết hiệu lực từ ngày duyệt |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-04-01 | Kế hoạch quý phải được duyệt trước khi hết tuần thứ hai của tháng đầu quý | PL 1 dự thảo Quy định mới | Cảnh báo trước 3 ngày làm việc; quá hạn ghi vết và thông báo người đứng đầu |
| BR-04-02 | Mỗi dòng kế hoạch có đúng một đơn vị hoặc cá nhân chủ trì; có thể có nhiều phối hợp | Bảng 1 PL 1 | Chặn gửi duyệt |
| BR-04-03 | Dòng kế hoạch chứa từ mơ hồ mà thiếu sản phẩm, đơn vị tính hoặc hạn là KPI mơ hồ | PL 1; yêu cầu chặn KPI mơ hồ | Chặn gửi duyệt |
| BR-04-04 | Mỗi dòng phải có sản phẩm, số lượng, đơn vị tính và hạn trong kỳ | Bảng 1, Bảng 3 PL 1 | Chặn gửi duyệt |
| BR-04-05 | Điểm của dòng lấy từ danh mục; việc ngoài danh mục phải được cấp duyệt chấm điểm khi duyệt kế hoạch | Bảng 3 PL 1 | Cảnh báo khi lập; chặn duyệt khi chưa có điểm |
| BR-04-06 | Tổng điểm tối đa theo nhóm thường xuyên và then chốt theo tỷ trọng do cấp có thẩm quyền quyết định | Policy Pack của luồng | Chặn khi vượt tỷ trọng; cảnh báo khi thiếu |
| BR-04-07 | Mỗi KR đã duyệt nên có ít nhất một nhiệm vụ liên kết | Phân rã ba tầng PL 1 | Cảnh báo |
| BR-04-08 | Người duyệt không được là người lập | PL 4 V.1 | Chặn |
| BR-04-09 | Duyệt kế hoạch phải ký số; phiên bản đã duyệt bất biến | Mốc ký số pháp lý (G3); PL 4 | Chặn sửa; ký số bắt buộc |
| BR-04-10 | Điều chỉnh tạo phiên bản mới, tối đa 2 lần mỗi quý, có lý do từng thay đổi, hiệu lực từ ngày duyệt, không hồi tố | Policy Pack (tham số); nguyên tắc không hồi tố | Chặn khi vượt số lần; chặn khi thiếu lý do |
| BR-04-11 | Không được bỏ khỏi kế hoạch việc đã có minh chứng được xác nhận | Bảo toàn kết quả đã nghiệm thu | Chặn |
| BR-04-12 | Hạn của nhiệm vụ đang thực hiện không đổi qua điều chỉnh kế hoạch | Bảo toàn cách tính hệ số tiến độ | Chặn; hướng sang QT-06 |
| BR-04-13 | Sửa mục danh mục đã dùng ở kỳ đã khoá tạo phiên bản danh mục mới | Bảo toàn điểm đã tính | Chặn sửa tại chỗ |
| BR-04-14 | Mỗi công việc trong danh mục có mức khó từ 1 đến 5 và điểm chuẩn từ 0 đến 100 | Bảng 3 PL 1 | Chặn khi nhập |

Quy tắc BR-04-06 cần ví dụ cụ thể. Ở luồng Đảng của Ban Tổ chức Đảng uỷ khối, Policy Pack "Khối Đảng v2.0" quy định điểm kết quả nhiệm vụ tối đa 100, chia 30 cho nhóm thường xuyên và 70 cho nhóm then chốt. Điểm của từng dòng (ví dụ 20 điểm cho thẩm định hồ sơ kết nạp, 30 điểm cho báo cáo chuyên đề) là trọng số của dòng trong nhóm. Theo công thức mặc định của gói, điểm nhóm bằng tổng của (điểm danh mục nhân KPI nhiệm vụ) chia tổng điểm danh mục của nhóm, nhân điểm tối đa của nhóm; KPI nhiệm vụ là trung bình cộng của A, B, C được tính ở QT-07. Công thức cụ thể và cách xử lý khi một nhóm không có việc nào là phần của Policy Pack và được kiểm thử bằng test case vàng ở QT-15.

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Có chỉ đạo đột xuất giữa quý | Tạo nhiệm vụ phát sinh ngay ở QT-05 để không chậm thực hiện; nhiệm vụ phát sinh được đưa vào lần điều chỉnh kế hoạch gần nhất |
| Đã hết số lần điều chỉnh | Hiển thị MSG-04-07; người đứng đầu có thể cho phép thêm một lần với lý do, việc này được ghi vết và hiển thị trên bảng cảnh báo |
| Ký số lỗi do MySign gián đoạn | Phiên bản giữ `CHO_DUYET`, yêu cầu ký được xếp hàng và thử lại; người duyệt nhận thông báo khi ký xong; không chuyển `DA_DUYET` khi chưa có chữ ký |
| Đơn vị chủ trì là đơn vị khác | Dòng được gửi tới đơn vị chủ trì để xác nhận nhận việc trước khi kế hoạch được duyệt; nếu từ chối, dòng bị đánh lỗi |
| Danh mục chưa có công việc phù hợp | Thêm việc ngoài danh mục; người duyệt chấm điểm khi duyệt và có thể đề nghị bổ sung vào danh mục |
| Tệp Excel danh mục có dòng lỗi | Nhập phần hợp lệ, trả tệp lỗi có cột lý do |

### Đặc tả màn hình

#### SCR-0401. Thư viện danh mục công việc

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Quản lý danh mục công việc theo vị trí việc làm theo Bảng 3, có phiên bản và kế thừa từ danh mục mẫu cấp trên |
| Tác nhân | AC-03, AC-05; AC-06 chỉ xem |
| Đường vào | Menu "Mục tiêu và nhiệm vụ / Danh mục công việc" |
| Quyền | `policy_author` hoặc `manager` được giao quản lý danh mục để sửa; mọi thành viên để xem |
| Thiết bị | Máy tính |

![Hình 7.12. Thư viện danh mục công việc SCR-0401 theo Bảng 3](SCR-0401.png){width=16cm}

Hình 7.12 là danh mục "Chuyên viên công tác đảng viên" phiên bản 3, áp dụng từ Quý IV/2026, kế thừa từ danh mục mẫu của Ban Tổ chức Trung ương. Vùng 1 là bộ lọc theo mã, tên, vị trí việc làm, mức khó và trạng thái, với tổng 142 công việc của 9 vị trí. Vùng 2 là bảng danh mục với các cột theo Bảng 3: mã, công việc, sản phẩm, đơn vị tính, mức khó, điểm, tiêu chuẩn chất lượng và minh chứng yêu cầu; ví dụ TC-DV-03 "Xây dựng báo cáo chuyên đề công tác tổ chức" có mức khó 4, điểm 30, tiêu chuẩn chất lượng "đủ số liệu, đúng thể thức NĐ 30/2020, được lãnh đạo ban duyệt". Vùng 3 là thẻ chi tiết của công việc đang chọn với nhóm mặc định, hệ số mức khó, thời hạn chuẩn 10 ngày làm việc, số nhiệm vụ đã dùng và phiên bản, kèm quy tắc tạo phiên bản mới khi sửa mục đã dùng. Vùng 4 là các nút nhập từ Excel, nhân bản phiên bản và thêm công việc. Vùng 5 là kết quả nhập Excel gần nhất: 1.221 dòng hợp lệ, 12 dòng thiếu đơn vị tính hoặc mức khó, 7 dòng có tên mơ hồ.

Hệ số mức khó hiển thị trong thẻ chi tiết (ví dụ mức 4 có hệ số 1,2) là tham số Policy Pack dùng để gợi ý điểm chuẩn khi xây dựng danh mục; điểm dùng để tính là điểm chuẩn đã được duyệt trong danh mục, không nhân thêm hệ số khi tính điểm.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Danh mục, phiên bản | Tham chiếu | Có | Tên, số phiên bản | Phiên bản đang áp dụng | Một phiên bản áp dụng mỗi kỳ | `task.work_catalog.id`, `task.work_catalog.version_no` |
| 2 | Kế thừa từ | Tham chiếu | Không | Danh mục cấp trên | Không có | Không tạo vòng | `task.work_catalog.parent_catalog_id` (đề xuất) |
| 3 | Vị trí việc làm | Tham chiếu | Có | Tên vị trí | Không có | Thuộc `org.position` | `task.catalog_item.position_id` (đề xuất) |
| 4 | Mã công việc | Chuỗi | Có | `TC-xx-nn`, ≤ 20 ký tự | Tự sinh | Duy nhất trong danh mục | `task.catalog_item.code` |
| 5 | Tên công việc | Chuỗi | Có | ≤ 255 ký tự | Không có | Không chứa từ mơ hồ | `task.catalog_item.name` |
| 6 | Sản phẩm | Chuỗi | Có | ≤ 255 ký tự | Không có | Không trống | `task.catalog_item.product_name` |
| 7 | Đơn vị tính | Chuỗi | Có | ≤ 30 ký tự | Không có | Không trống | `task.catalog_item.unit` |
| 8 | Mức khó | Số nguyên | Có | 1 đến 5 | 3 | BR-04-14 | `task.catalog_item.difficulty` |
| 9 | Điểm chuẩn | Số | Có | 0 đến 100 | Gợi ý theo mức khó | BR-04-14 | `task.catalog_item.base_score` |
| 10 | Tiêu chuẩn chất lượng | Văn bản | Có | ≤ 1.000 ký tự | Không có | Dùng làm danh sách đối chiếu ở SCR-0703 | `task.catalog_item.quality_criteria` |
| 11 | Minh chứng yêu cầu | Văn bản | Có | ≤ 500 ký tự | Không có | Không trống | `task.catalog_item.evidence_required` |
| 12 | Nhóm mặc định | Liệt kê | Có | Thường xuyên, Then chốt | Thường xuyên | Không có | `task.catalog_item.default_group` (đề xuất) |
| 13 | Thời hạn chuẩn | Số | Không | Ngày làm việc | Không có | > 0 | `task.catalog_item.standard_days` (đề xuất) |
| 14 | Trạng thái | Liệt kê | Có | Đang áp dụng, Ngừng | Đang áp dụng | Không có | `task.catalog_item.status` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Thêm công việc | Quyền sửa | Thêm mục vào phiên bản nháp | `/api/v1/catalog/{catalogId}/items` (POST) | Không có |
| Sửa | Quyền sửa | Sửa mục; nếu mục đã dùng ở kỳ khoá thì yêu cầu nhân bản phiên bản | `/api/v1/catalog/{catalogId}/items/{id}` (PUT, If-Match) | Không có |
| Nhân bản phiên bản | Quyền sửa | Tạo phiên bản n+1 ở nháp | `/api/v1/catalog/{catalogId}/versions` (POST) | Không có |
| Nhập từ Excel | Quyền sửa | Tải tệp, kiểm tra, trả 202 và kết quả | `/api/v1/catalog/{catalogId}/imports` (POST) | Không có |
| Tải tệp lỗi | Có dòng lỗi | Tải tệp lỗi | `/api/v1/catalog/imports/{jobId}/errors` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-04-01 | Công việc {mã} đã được dùng ở kỳ đã khoá. Thay đổi sẽ được lưu ở phiên bản danh mục mới, kỳ cũ giữ nguyên. | Sửa mục đã dùng |
| MSG-04-02 | Đã nhập {n} dòng hợp lệ; {m} dòng lỗi. Tải tệp lỗi để xem lý do. | Nhập Excel xong |

#### SCR-0402. Lập kế hoạch quý

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Lập kế hoạch công tác quý theo Bảng 1 với kiểm tra tức thì và gửi duyệt |
| Tác nhân | AC-05 |
| Đường vào | Menu "Mục tiêu và nhiệm vụ / Kế hoạch quý" |
| Quyền | `manager` của đơn vị |
| Thiết bị | Máy tính |

![Hình 7.13. Màn hình lập kế hoạch quý SCR-0402 theo Bảng 1](SCR-0402.png){width=16cm}

Hình 7.13 là phiên bản 1 kế hoạch Quý IV/2026 của Ban Tổ chức Đảng uỷ khối ngày 05/10/2026, đang ở trạng thái `NHAP`. Vùng 1 là dải nhắc hạn: hạn phê duyệt 09/10/2026, còn 4 ngày làm việc, kèm giải thích cách tính hạn và hệ quả khi quá hạn. Vùng 2 là bảng công việc với các cột STT, nội dung, nhóm, KR liên kết, chủ trì, phối hợp, mã danh mục với sản phẩm và số lượng, hạn, điểm. Dòng 6 "Đẩy mạnh công tác tuyên truyền kịp thời" được tô đỏ vì chứa từ mơ hồ và thiếu sản phẩm, đơn vị tính, hạn, nên chặn gửi duyệt; dòng 4 là việc ngoài danh mục chưa có điểm. Dưới bảng có hai nút thêm từ danh mục và thêm việc ngoài danh mục. Vùng 3 là tỷ trọng nhóm (thường xuyên 30, then chốt 70, tổng 100, luồng Đảng) do Policy Pack quy định. Vùng 4 là danh sách kiểm tra kế hoạch. Vùng 5 là nút "Gửi duyệt", đang bị vô hiệu cho tới khi sửa dòng 6.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kỳ | Tham chiếu, chỉ đọc | Có | Tên kỳ | Kỳ đang soạn | Kỳ `MO` | `task.plan.period_id` |
| 2 | Đơn vị | Tham chiếu, chỉ đọc | Có | Tên đơn vị | Đơn vị của ngữ cảnh | Không có | `task.plan.owner_unit_id` |
| 3 | Số phiên bản, trạng thái | Chỉ đọc | Có | Số nguyên, trạng thái | 1, `NHAP` | Không có | `task.plan_version.version_no`, `task.plan_version.status` |
| 4 | Hạn phê duyệt | Ngày, chỉ đọc | Có | dd/mm/yyyy | Tính từ Policy Pack và lịch nghỉ | BR-04-01 | `config.period.plan_deadline` (đề xuất) |
| 5 | Nội dung công việc | Chuỗi | Có | ≤ 500 ký tự | Tên mục danh mục | BR-04-03 | `task.plan_item.title` |
| 6 | Nhóm | Liệt kê | Có | Thường xuyên, Then chốt | Nhóm mặc định của mục | Không có | `task.plan_item.task_group` |
| 7 | KR liên kết | Tham chiếu | Không | Mã KR | Không có | KR đã duyệt cùng kỳ | `task.plan_item.kr_id` |
| 8 | Chủ trì | Tham chiếu | Có | Một đơn vị hoặc cá nhân | Đơn vị lập | BR-04-02 | `task.plan_item.lead_assignee_id` |
| 9 | Phối hợp | Danh sách tham chiếu | Không | Đơn vị, cá nhân | Không có | Không trùng chủ trì | `task.plan_item.co_assignees` (JSON, đề xuất) |
| 10 | Mục danh mục | Tham chiếu | Có trừ việc ngoài danh mục | Mã | Không có | Danh mục áp dụng cho kỳ | `task.plan_item.catalog_item_id` |
| 11 | Sản phẩm, số lượng | Chuỗi, số | Có | Theo danh mục | Theo danh mục | BR-04-04 | `task.plan_item.product_name`, `quantity` |
| 12 | Hạn | Ngày | Có | dd/mm/yyyy hoặc "Hằng tháng" | Không có | Trong kỳ | `task.plan_item.due_date`, `recurrence` |
| 13 | Điểm | Số | Có khi duyệt | 0 đến 100 | Điểm danh mục | BR-04-05 | `task.plan_item.score_weight` |
| 14 | Luồng | Liệt kê, ẩn | Có | `DANG`, `NHA_NUOC`, cả hai | Theo đơn vị | Không có | `task.plan_item.flows` (đề xuất) |
| 15 | Tỷ trọng nhóm | Số, chỉ đọc | Có | Điểm tối đa | Từ Policy Pack | BR-04-06 | `policy.policy_rule` (tham số `group_weights`) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Sao chép từ quý trước | Phiên bản 1 trống | Sao chép các dòng thường xuyên và dòng chưa hoàn thành của quý trước, hạn dịch theo kỳ | `/api/v1/plans/{id}/versions/{v}/copy-from` (POST) | Không có |
| Gợi ý từ OKR | Có KR chưa có nhiệm vụ | Đề xuất dòng cho mỗi KR | `/api/v1/plans/{id}/suggestions` | Không có |
| Thêm từ danh mục | Luôn | Mở hộp chọn mục danh mục | Không có | Không có |
| Thêm việc ngoài danh mục | Luôn | Thêm dòng trống, đánh dấu chờ chấm điểm | Không có | Không có |
| Lưu nháp | Luôn | Lưu phiên bản | `/api/v1/plans/{id}/versions/{v}` (PUT, If-Match) | Không có |
| Gửi duyệt | Không lỗi chặn | Kiểm tra lại, khoá, tính mã băm, chuyển `CHO_DUYET`, tạo tiến trình | `/api/v1/plans/{id}/versions/{v}/submit` (POST, Idempotency-Key) | Không có |
| Tạo điều chỉnh | Có phiên bản `DA_DUYET`, chưa vượt số lần | Tạo phiên bản n+1 | `/api/v1/plans/{id}/versions` (POST) | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-04-03 | Hạn phê duyệt kế hoạch là {ngày}. Còn {n} ngày làm việc. | Luôn hiển thị khi `NHAP` |
| MSG-04-04 | KPI mơ hồ ở dòng {n}: "{từ}". Vui lòng ghi rõ sản phẩm, đơn vị tính và hạn. | Vi phạm BR-04-03 |
| MSG-04-05 | Dòng {n} có {k} đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì. | Vi phạm BR-04-02 |
| MSG-04-06 | Tổng điểm nhóm {nhóm} là {x}, vượt điểm tối đa {y}. | Vi phạm BR-04-06 |
| MSG-04-07 | Kế hoạch đã điều chỉnh {n} lần trong quý, đạt giới hạn. | Vi phạm BR-04-10 |
| MSG-04-08 | Đã quá hạn phê duyệt kế hoạch {ngày}. Việc này đã được ghi nhận và báo người đứng đầu. | Quá hạn |

#### SCR-0403. Phê duyệt kế hoạch

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người có thẩm quyền duyệt kế hoạch hoặc điều chỉnh kế hoạch, thấy rõ khác biệt giữa các phiên bản và ký số |
| Tác nhân | AC-04 |
| Đường vào | Menu "Đánh giá / Chờ tôi duyệt"; thông báo |
| Quyền | `leader` của đơn vị theo `flowable.wf_binding`; phiên AAL2 |
| Thiết bị | Máy tính |

![Hình 7.14. Phê duyệt điều chỉnh kế hoạch SCR-0403 với so sánh hai phiên bản](SCR-0403.png){width=16cm}

Hình 7.14 là màn hình của đồng chí Trần Thị Bình, Trưởng ban, duyệt điều chỉnh kế hoạch Quý IV/2026: so sánh phiên bản 1 đã duyệt và ký số ngày 08/10 với phiên bản 2 trình ngày 16/10. Vùng 1 là bốn thẻ tóm tắt: 1 việc thêm, 1 việc sửa, 1 việc bỏ và tổng điểm then chốt không đổi (70). Vùng 2 là bảng khác biệt: thêm "Rà soát quy hoạch cấp uỷ nhiệm kỳ 2025–2030" (TC-DV-06, 35 điểm, hạn 15/11) theo Văn bản 518-CV/ĐUK; sửa "Thẩm định hồ sơ kết nạp đảng viên đợt 2" từ 15 lên 18 hồ sơ vì Chi bộ 2 và 7 bổ sung hồ sơ; bỏ "Tập huấn nghiệp vụ cho chi bộ cơ sở" để lùi sang Quý I/2027. Dưới bảng là hai quy tắc BR-04-11 và BR-04-12. Vùng 3 là thông tin trình: người trình, lần điều chỉnh 1 trên tối đa 2, hiệu lực từ ngày duyệt không hồi tố, mã băm phiên bản 2. Vùng 4 là ô ý kiến, lựa chọn ký số bằng MySign và nút "Trả lại". Vùng 5 là nút "Duyệt và ký số" kèm lưu ý về MFA bước lên.

Cần lưu ý cách đọc tổng điểm then chốt trong ví dụ này: việc thêm 35 điểm và việc bỏ 15 điểm không làm tổng điểm nhóm then chốt vượt 70, vì 70 là điểm tối đa của nhóm còn điểm của từng dòng là trọng số trong nhóm (xem BR-04-06). Người duyệt vì vậy thấy dòng "không đổi tỷ trọng" thay vì một con số tổng mới.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Phiên bản so sánh | Tham chiếu | Có | Số phiên bản | Phiên bản hiệu lực và phiên bản chờ duyệt | Cùng kế hoạch | `task.plan_version` |
| 2 | Loại thay đổi | Liệt kê, chỉ đọc | Có | Thêm, Sửa, Bỏ | Tính từ so sánh | Không có | Tính từ `task.plan_item.origin_item_id` (đề xuất) |
| 3 | Lý do thay đổi | Văn bản, chỉ đọc | Có với mỗi thay đổi | ≤ 500 ký tự | Không có | BR-04-10 | `task.plan_item.change_reason` (đề xuất) |
| 4 | Lần điều chỉnh | Số, chỉ đọc | Có | n trên tối đa | Không có | BR-04-10 | `task.plan_version.version_no` và tham số Policy Pack |
| 5 | Mã băm phiên bản | Chuỗi, chỉ đọc | Có | SHA-256 | Tính khi gửi duyệt | Không có | `task.plan_version.content_hash` (đề xuất) |
| 6 | Ý kiến người duyệt | Văn bản | Có khi trả lại | ≤ 2.000 ký tự | Không có | Không có | `task.plan_version.review_comment` (đề xuất) |
| 7 | Phương thức ký số | Lựa chọn | Có khi duyệt | MySign, USB token, chứng thư tổ chức | MySign đã liên kết | Chứng thư hợp lệ | `esign.certificate_ref` |
| 8 | Yêu cầu ký | Tham chiếu, ẩn | Có | Mã yêu cầu | Tạo khi bấm duyệt | Không có | `esign.sign_request.id` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chỉ hiện thay đổi, Toàn bộ | Có phiên bản trước | Đổi chế độ xem | `/api/v1/plans/{id}/versions/{v}/diff?base={v0}` | Không có |
| Trả lại | Người duyệt | Chuyển `TRA_LAI` với ý kiến | `/api/v1/plans/{id}/versions/{v}/return` (POST) | EVT-21 |
| Duyệt và ký số | Người duyệt khác người lập, AAL2 trong 15 phút | Tạo yêu cầu ký, chờ người duyệt xác nhận trên MySign; khi ký xong chuyển `DA_DUYET` | `/api/v1/plans/{id}/versions/{v}/approve` (POST, Idempotency-Key), `/api/v1/esign/requests` | EVT-18, EVT-03 |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-04-09 | Vui lòng xác nhận ký trên ứng dụng MySign trong 120 giây. | Sau khi bấm duyệt |
| MSG-04-10 | Đã duyệt và ký số kế hoạch phiên bản {v}. Hệ thống đang sinh {n} nhiệm vụ mới. | Ký thành công |
| MSG-04-11 | Không bỏ được việc "{tên}" vì đã có minh chứng được xác nhận. | Vi phạm BR-04-11 |
| MSG-04-12 | Hạn của nhiệm vụ đang thực hiện không đổi qua điều chỉnh kế hoạch. Vui lòng dùng đề nghị gia hạn. | Vi phạm BR-04-12 |
| MSG-04-13 | Dịch vụ ký số đang gián đoạn. Yêu cầu ký đã được lưu và sẽ gửi lại. | MySign lỗi |

### Liên kết thiết kế

QT-04 do module task (MOD-02) của mission-core xử lý, luồng duyệt trên workflow-service (SVC-06) theo mẫu `duyet-ke-hoach`, ký số qua esign-service (SVC-10), tham số tỷ trọng và hạn từ module policy (MOD-05). Bảng dữ liệu chính là `task.work_catalog`, `task.catalog_item`, `task.plan`, `task.plan_version`, `task.plan_item`, `task.task`, `esign.sign_request`, `esign.sign_item`. Sự kiện phát ra là KeHoachDaDuyet (EVT-03) sau khi nhận KySoDaHoanTat (EVT-18), và TrangThaiNhiemVuDoi (EVT-06) khi điều chỉnh huỷ nhiệm vụ. Tài liệu đề xuất thêm một số cột cho `task.plan_version`, `task.plan_item`, `task.catalog_item` và `config.period` (xem tệp ghi chú). Về an toàn thông tin, quy trình liên quan tới ký số mốc pháp lý, dấu thời gian và lưu chữ ký dài hạn, đặc tả trong TL-04; lý do đặt ký số ngoài đường găng của request được phân tích trong TL-01 (G3).

# Thực hiện nhiệm vụ và nghiệm thu

Chương này đặc tả ba quy trình diễn ra suốt quý, với tần suất cao nhất của nền tảng: giao và nhận nhiệm vụ (QT-05), cập nhật tiến độ, điểm nghẽn và đề nghị điều chỉnh (QT-06), nộp và nghiệm thu minh chứng (QT-07). Theo ADR-04, vòng đời nhiệm vụ và minh chứng không chạy trên engine BPMN mà dùng máy trạng thái khai báo trong mission-core, vì khối lượng thay đổi lúc cao điểm lên tới hàng nghìn lần mỗi giây. Engine BPMN chỉ được dùng cho bước duyệt đề nghị gia hạn khi Policy Pack yêu cầu duyệt nhiều cấp. Kết quả của chương này là ba thành phần điểm A, B, C của từng nhiệm vụ, được tính dần ngay khi minh chứng được xác nhận.

## QT-05. Giao việc, đăng ký nhiệm vụ, nhận nhiệm vụ từ văn bản

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-05 |
| Tên quy trình | Giao việc, đăng ký nhiệm vụ, nhận nhiệm vụ từ văn bản |
| Mục đích | Biến dòng kế hoạch, văn bản chỉ đạo và đăng ký của cán bộ thành nhiệm vụ có đúng một người chủ trì, người nghiệm thu khác người thực hiện, sản phẩm, tiêu chuẩn chất lượng và hạn; mỗi nhiệm vụ chỉ được tạo một lần |
| Phạm vi áp dụng | Mọi nhiệm vụ của kỳ đang `MO`, ở cả ba nguồn: kế hoạch quý, văn bản chỉ đạo, đăng ký của cán bộ; cả nhiệm vụ phát sinh ngoài kế hoạch |
| Căn cứ | PL 1 dự thảo Quy định mới (Bảng 1, Bảng 3; nhiệm vụ có sản phẩm, tiêu chuẩn, hạn); PL 4 V.1 (tách người thực hiện và người nghiệm thu); NĐ 30/2020/NĐ-CP (văn bản điện tử, định dạng EdXML khi nhận từ hệ thống quản lý văn bản); nguyên tắc "một dữ liệu, một lần nhập" của báo cáo Ban Giám đốc |
| Tác nhân | AC-05 (người giao), AC-06 (người thực hiện, người đăng ký), AC-11 (vOffice, iOffice), AC-12 (trợ lý AI trích nhiệm vụ) |
| Tần suất và khối lượng ước tính | Ước tính trung bình 8 nhiệm vụ mỗi người mỗi quý (theo giả định 8 tệp minh chứng mỗi người mỗi quý của cơ sở thiết kế chung), tức khoảng 48 triệu nhiệm vụ mỗi quý toàn quốc; khoảng 20% nhiệm vụ đến từ văn bản chỉ đạo |
| Điều kiện bắt đầu | Kế hoạch có phiên bản `DA_DUYET` (nguồn kế hoạch); hoặc văn bản chỉ đạo được nhận (EVT-24); hoặc cán bộ gửi đăng ký |
| Điều kiện kết thúc | Nhiệm vụ ở trạng thái `DA_GIAO`, EVT-04 đã phát; chuyển `DANG_THUC_HIEN` khi người chủ trì nhận việc hoặc cập nhật lần đầu |
| Đầu vào | Dòng kế hoạch, văn bản chỉ đạo (EdXML hoặc PDF), đăng ký nhiệm vụ, danh mục Bảng 3 |
| Đầu ra | `task.task`, `task.task_assignee`, `task.task_source_doc`, `task.task_state_log`, thông báo cho người được giao |
| Chỉ số đo hiệu quả | 100% nhiệm vụ có đúng một chủ trì và người nghiệm thu khác người thực hiện; tỷ lệ nhiệm vụ trùng lặp phát hiện sau khi giao < 1%; ≥ 80% trường do AI trích được người giao giữ nguyên (đo chất lượng trích); thời gian từ khi văn bản đến tới khi nhiệm vụ được giao trung vị ≤ 1 ngày làm việc |

Một nhiệm vụ có thể đến từ ba nguồn và nền tảng phải bảo đảm cả ba nguồn hội tụ về cùng một đối tượng `task.task` với cùng cấu trúc. Nguồn thứ nhất là kế hoạch quý: khi kế hoạch được duyệt, mỗi dòng sinh một nhiệm vụ `NHAP` và người quản lý chỉ cần hoàn thiện phân công rồi giao. Nguồn thứ hai là văn bản chỉ đạo: văn bản đến từ vOffice hoặc iOffice qua integration-gateway (SVC-13), trợ lý AI đọc văn bản, trích các nhiệm vụ cùng chủ trì, phối hợp, hạn và sản phẩm, và người giao xác nhận. Nguồn thứ ba là cán bộ tự đăng ký nhiệm vụ từ danh mục; đăng ký được người quản lý trực tiếp duyệt và đưa vào lần điều chỉnh kế hoạch gần nhất.

Nguyên tắc "một dữ liệu, một lần nhập" có hai hệ quả trong QT-05. Thứ nhất, văn bản chỉ đạo không được nhập lại bằng tay: số, ngày, trích yếu, nội dung lấy nguyên từ hệ thống quản lý văn bản, nhiệm vụ lưu liên kết tới văn bản nguồn trong `task.task_source_doc`. Thứ hai, trước khi tạo nhiệm vụ mới, hệ thống tìm nhiệm vụ gần giống đang có (theo độ tương đồng nội dung, cùng đơn vị, cùng kỳ) và yêu cầu người giao chọn gộp vào nhiệm vụ cũ hoặc ghi lý do tạo mới. Trợ lý AI chỉ trích và gợi ý; mọi nhiệm vụ do AI trích phải được người giao xác nhận từng nhiệm vụ trước khi giao, và ô do AI điền luôn được đánh dấu viền tím cho tới khi xác nhận.

### Lưu đồ quy trình

![Hình 8.1. Lưu đồ làn bơi QT-05 Giao việc, đăng ký nhiệm vụ, nhận nhiệm vụ từ văn bản](nv-qt05-luong.png){width=16cm}

Hình 8.1 có năm làn. Làn "Nguồn" có ba điểm vào: dòng kế hoạch đã duyệt, văn bản chỉ đạo từ vOffice, iOffice qua SVC-13 kèm sự kiện VanBanChiDaoDaNhan (EVT-24), và đăng ký của cán bộ từ danh mục. Với văn bản, trợ lý AI (SVC-14) trích nhiệm vụ, chủ trì, hạn, sản phẩm và gắn nhãn AI. Người giao (AC-05) xác nhận hoặc sửa ô do AI điền, hoặc lập nhiệm vụ từ dòng kế hoạch, duyệt đăng ký, rồi chọn đúng một chủ trì, các phối hợp và người nghiệm thu. Làn hệ thống (MOD-02) kiểm tra trùng: nếu có nhiệm vụ gần giống, người giao gộp hoặc ghi lý do; sau đó nhiệm vụ chuyển `DA_GIAO`, phát NhiemVuDaGiao (EVT-04), gửi thông báo và ghi sổ cái. Người thực hiện (AC-06) nhận việc, nhiệm vụ chuyển `DANG_THUC_HIEN`.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1a | Sinh nhiệm vụ từ kế hoạch | MOD-02 | Khi nhận EVT-03, sinh nhiệm vụ `NHAP` cho mỗi dòng kế hoạch mới, sao chép sản phẩm, số lượng, hạn, điểm, tiêu chuẩn từ dòng kế hoạch và danh mục | Không có | `task.task` `NHAP` | ≤ 1 phút sau duyệt |
| 1b | Nhận văn bản chỉ đạo | AC-11, SVC-13 | vOffice hoặc iOffice gửi văn bản đến (EdXML), SVC-13 ánh xạ đơn vị nhận qua `integ.mapping_xref`, lưu `integ.inbox_message`, phát EVT-24 | Không có | EVT-24 | Gần thời gian thực |
| 2 | Trích nhiệm vụ bằng AI | SVC-14 | Đọc văn bản, tách các đoạn giao việc, trích tên nhiệm vụ, chủ trì, phối hợp, hạn, sản phẩm, gợi ý mục danh mục và KR, kèm độ tin cậy; chỉ trích đoạn giao cho đơn vị nhận | SCR-0502 | Gợi ý trong `ai.suggestion_log` | ≤ 1 phút |
| 1c | Đăng ký nhiệm vụ | AC-06 | Cán bộ chọn mục danh mục, số lượng, hạn đề xuất, gửi đăng ký | SCR-0503 | Nhiệm vụ `NHAP` có cờ đăng ký | Không có |
| 3 | Xác nhận hoặc lập nhiệm vụ | AC-05 | Với văn bản: xem văn bản và từng nhiệm vụ AI trích, sửa ô nếu cần, xác nhận hoặc bỏ; với kế hoạch: mở nhiệm vụ `NHAP`; với đăng ký: duyệt hoặc từ chối | SCR-0501, SCR-0502 | Nhiệm vụ đủ thông tin | Trong 1 ngày làm việc |
| 4 | Phân công | AC-05 | Chọn đúng một người chủ trì, các phối hợp, người nghiệm thu, hạn, mốc kiểm soát; xem khối lượng việc hiện có của người chủ trì | SCR-0501 | `task.task_assignee` | Không có |
| 5 | Kiểm tra trùng và quy tắc | MOD-02 | Kiểm tra một chủ trì, người nghiệm thu khác người thực hiện, hạn trong kỳ, thuộc kế hoạch đã duyệt; tìm nhiệm vụ gần giống | SCR-0501, SCR-0502 | Danh sách kiểm tra | Tức thì |
| 6 | Gộp hoặc ghi lý do | AC-05 | Nếu có nhiệm vụ gần giống: gộp vào nhiệm vụ cũ (văn bản trở thành căn cứ bổ sung) hoặc tạo mới với lý do | SCR-0502 | Liên kết căn cứ hoặc nhiệm vụ mới | Không có |
| 7 | Giao việc | AC-05 | Bấm "Giao việc"; nhiệm vụ chuyển `DA_GIAO`, ghi `task.task_state_log` | SCR-0501, SCR-0502 | EVT-04; EVT-21 | Không có |
| 8 | Nhận việc | AC-06 | Người chủ trì bấm "Nhận việc" hoặc cập nhật tiến độ lần đầu | SCR-0503, SCR-0504 | `DANG_THUC_HIEN`; EVT-06 | Trong 2 ngày làm việc (tham số) |

Bước 2 và bước 6 là hai bước cần giải thích kỹ. Ở bước 2, văn bản 512-CV/ĐUK trong ví dụ có bốn đoạn giao việc nhưng chỉ ba đoạn liên quan tới Ban Tổ chức (chủ trì hoặc phối hợp); đoạn thứ hai giao cho các đảng uỷ, chi bộ trực thuộc nên không được trích thành nhiệm vụ của ban, mà được hiển thị để người giao biết và, nếu cần, dùng chức năng "giao đồng loạt" cho các đơn vị trực thuộc. Độ tin cậy của mỗi nhiệm vụ trích được hiển thị để người giao biết nhiệm vụ nào cần xem kỹ. Ở bước 6, nhiệm vụ thứ ba trích từ văn bản ("Rà soát, hoàn thiện hồ sơ đảng viên trên CSDL đảng viên") gần giống nhiệm vụ NV-2026-Q4-0098 đang thực hiện; nếu người giao tạo mới, cùng một công việc sẽ được tính điểm hai lần. Gộp nghĩa là văn bản mới được thêm làm căn cứ của nhiệm vụ cũ, hạn và sản phẩm của nhiệm vụ cũ chỉ đổi qua đề nghị điều chỉnh ở QT-06.

### Trạng thái đối tượng

Đối tượng chính là nhiệm vụ `task.task` với trạng thái chuẩn `NHAP`, `DA_GIAO`, `DANG_THUC_HIEN`, `CHO_NGHIEM_THU`, `HOAN_THANH`, nhánh `TAM_DUNG`, `HUY` và cờ `qua_han`. Máy trạng thái được khai báo trong bảng chuyển trạng thái do Policy Pack tham số hoá, mọi lần chuyển được ghi vào `task.task_state_log`.

![Hình 8.2. Trạng thái của nhiệm vụ task.task](nv-qt05-trang-thai.png){width=14cm}

Hình 8.2 cho thấy toàn bộ vòng đời nhiệm vụ, dùng chung cho QT-05, QT-06 và QT-07. Cờ `qua_han` không phải trạng thái mà là thuộc tính được bật khi ngày hiện tại vượt hạn tính điểm và nhiệm vụ chưa ở `CHO_NGHIEM_THU` hoặc `HOAN_THANH`; nhờ vậy một nhiệm vụ vừa `DANG_THUC_HIEN` vừa quá hạn mà không cần thêm trạng thái.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Sinh từ kế hoạch, văn bản, đăng ký | `NHAP` | Kỳ `MO` | Chưa hiển thị với người thực hiện (trừ đăng ký) |
| `NHAP` | Giao việc | `DA_GIAO` | BR-05-01 đến BR-05-05 | Phát EVT-04 |
| `DA_GIAO` | Nhận việc hoặc cập nhật lần đầu | `DANG_THUC_HIEN` | Người chủ trì | Phát EVT-06 |
| `DANG_THUC_HIEN` | Nộp đủ sản phẩm | `CHO_NGHIEM_THU` | Đủ số lượng phiên bản minh chứng `DA_NOP` (QT-07) | Ngày nộp đủ là ngày hoàn thành để tính C |
| `CHO_NGHIEM_THU` | Yêu cầu bổ sung | `DANG_THUC_HIEN` | Người nghiệm thu (QT-07) | Ngày hoàn thành được xác định lại khi nộp phiên bản đạt |
| `CHO_NGHIEM_THU` | Mọi sản phẩm `DA_XAC_NHAN` | `HOAN_THANH` | QT-07 | Điểm A, B, C được chốt cho nhiệm vụ |
| `DANG_THUC_HIEN` | Đề nghị tạm dừng được duyệt | `TAM_DUNG` | QT-06 | Ngày tạm dừng không tính chậm |
| `TAM_DUNG` | Tiếp tục | `DANG_THUC_HIEN` | Người giao | Hạn dịch theo số ngày tạm dừng |
| `DA_GIAO`, `DANG_THUC_HIEN`, `TAM_DUNG` | Đề nghị huỷ được duyệt | `HUY` | QT-06; không có minh chứng `DA_XAC_NHAN` | Không tính điểm |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-05-01 | Mỗi nhiệm vụ có đúng một người chủ trì | Bảng 1 PL 1 | Chặn giao |
| BR-05-02 | Người nghiệm thu khác người chủ trì và các người phối hợp | PL 4 V.1 | Chặn giao |
| BR-05-03 | Nhiệm vụ phải có sản phẩm, số lượng, đơn vị tính, tiêu chuẩn chất lượng và hạn trong kỳ | PL 1 | Chặn giao |
| BR-05-04 | Nhiệm vụ từ kế hoạch phải thuộc phiên bản kế hoạch `DA_DUYET` hiệu lực | QT-04 | Chặn giao |
| BR-05-05 | Nhiệm vụ do AI trích chỉ được giao khi người giao xác nhận từng nhiệm vụ | Nguyên tắc AI không quyết định | Chặn; ghi vết danh sách ô giữ nguyên và ô đã sửa |
| BR-05-06 | Nhiệm vụ có độ tương đồng ≥ 0,7 với nhiệm vụ đang có cùng đơn vị và kỳ phải gộp hoặc ghi lý do | Một dữ liệu, một lần nhập; tránh tính điểm trùng | Cảnh báo, bắt buộc chọn |
| BR-05-07 | Một văn bản chỉ đạo chỉ sinh nhiệm vụ một lần cho mỗi đoạn giao việc | Một dữ liệu, một lần nhập | Chặn: khoá theo mã văn bản và số đoạn |
| BR-05-08 | Nhiệm vụ đăng ký phải được người quản lý trực tiếp duyệt và đưa vào lần điều chỉnh kế hoạch gần nhất | PL 1 | Chặn giao khi chưa duyệt; ghi vết |
| BR-05-09 | Nhiệm vụ phát sinh ngoài kế hoạch phải có căn cứ (văn bản hoặc chỉ đạo ghi rõ) | PL 1 | Chặn giao khi thiếu căn cứ |
| BR-05-10 | Một nhiệm vụ chỉ tính điểm ở một hồ sơ cho mỗi luồng | Tránh tính trùng | Chặn ở scoring-service |
| BR-05-11 | Người chủ trì có quá nhiều việc trong cùng tuần được cảnh báo cho người giao | Cân bằng khối lượng | Cảnh báo (ngưỡng tham số) |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| AI trích sai đơn vị chủ trì | Người giao sửa ô; ô chuyển thành dữ liệu do người nhập; lần sửa được ghi để đánh giá chất lượng mô hình |
| Văn bản không đọc được (ảnh mờ, định dạng lạ) | AI trả độ tin cậy thấp hoặc không trích; người giao tạo nhiệm vụ thủ công và liên kết văn bản |
| ai-gateway không phản hồi | Văn bản vẫn được nhận và hiển thị; người giao tạo nhiệm vụ thủ công; trích tự động chạy lại khi dịch vụ phục hồi |
| Người chủ trì được điều động trước hạn | Nhiệm vụ chưa hoàn thành được chuyển cho người khác qua đề nghị điều chỉnh (QT-06); kết quả đã nghiệm thu giữ cho người cũ theo PL 6 |
| Văn bản gửi nhầm đơn vị | Người giao bỏ văn bản với lý do; SVC-13 gửi phản hồi về hệ thống nguồn nếu có giao diện |
| Giao đồng loạt cho nhiều đơn vị | Mỗi đơn vị nhận một nhiệm vụ riêng cùng nội dung, cùng căn cứ; mỗi nhiệm vụ tính điểm riêng |

### Đặc tả màn hình

#### SCR-0501. Giao việc

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Lập hoặc hoàn thiện nhiệm vụ và giao cho người chủ trì với đầy đủ sản phẩm, tiêu chuẩn, hạn và người nghiệm thu |
| Tác nhân | AC-05 |
| Đường vào | Menu "Nhiệm vụ"; nhiệm vụ `NHAP` sinh từ kế hoạch; nút "Giao việc" trên SCR-0201 |
| Quyền | `manager` của đơn vị |
| Thiết bị | Máy tính |

![Hình 8.3. Màn hình giao việc SCR-0501](SCR-0501.png){width=16cm}

Hình 8.3 là màn hình đồng chí Lê Minh Châu giao nhiệm vụ "Rà soát quy hoạch cấp uỷ nhiệm kỳ 2025–2030 tại 46 tổ chức đảng trực thuộc" từ dòng 7 của kế hoạch Quý IV/2026 phiên bản 2. Vùng 1 là nguồn và nội dung: nguồn (từ kế hoạch quý, từ văn bản, phát sinh), dòng kế hoạch, mục danh mục TC-DV-06 với mức khó 5 và 35 điểm, tên nhiệm vụ, nhóm, KR liên kết và mức ưu tiên. Vùng 2 là phân công: đúng một người chủ trì (đồng chí Nguyễn Văn An, đang chủ trì 5 việc, phối hợp 2 việc), các phối hợp, hạn 15/11/2026 kèm số ngày làm việc tính theo lịch nghỉ, người nghiệm thu (khác người chủ trì), mốc kiểm soát và tuỳ chọn giao đồng loạt. Vùng 3 là sản phẩm và tiêu chuẩn lấy từ danh mục. Vùng 4 là danh sách kiểm tra trước khi giao, trong đó có lưu ý nhiệm vụ gần giống NV-2026-Q3-0211 với độ tương đồng 0,71; phía dưới là giải thích về trạng thái sau khi giao và biểu đồ khối lượng việc của người chủ trì theo tuần. Vùng 5 là nút "Giao việc".

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã nhiệm vụ | Chuỗi, tự sinh | Có | `NV-yyyy-Qn-nnnn` | Tự sinh khi lưu | Duy nhất trong tenant | `task.task.code` |
| 2 | Nguồn nhiệm vụ | Liệt kê | Có | `KE_HOACH`, `VAN_BAN`, `PHAT_SINH`, `DANG_KY` | Theo đường vào | BR-05-09 | `task.task.source_type` (đề xuất) |
| 3 | Dòng kế hoạch | Tham chiếu | Có khi nguồn kế hoạch | Phiên bản, dòng | Không có | BR-05-04 | `task.task.plan_item_id` |
| 4 | Mục danh mục | Tham chiếu | Có trừ việc ngoài danh mục | Mã, tên, mức khó, điểm | Theo dòng kế hoạch | Danh mục áp dụng cho kỳ | `task.task.catalog_item_id` |
| 5 | Tên nhiệm vụ | Chuỗi | Có | ≤ 500 ký tự | Theo dòng kế hoạch | Không chứa từ mơ hồ | `task.task.title` |
| 6 | Nhóm | Liệt kê | Có | Thường xuyên, Then chốt | Theo dòng kế hoạch | Không có | `task.task.task_group` |
| 7 | KR liên kết | Tham chiếu | Không | Mã KR | Theo dòng kế hoạch | KR cùng kỳ | `task.task.kr_id` (đề xuất) |
| 8 | Mức ưu tiên | Liệt kê | Có | Cao, Trung bình, Thấp | Trung bình | Không có | `task.task.priority` |
| 9 | Người chủ trì | Tham chiếu người | Có | Đúng một | Không có | BR-05-01 | `task.task_assignee` (`role` = `CHU_TRI`) |
| 10 | Phối hợp | Danh sách | Không | Người hoặc đơn vị | Không có | Không trùng chủ trì | `task.task_assignee` (`role` = `PHOI_HOP`) |
| 11 | Hạn hoàn thành | Ngày | Có | dd/mm/yyyy | Theo dòng kế hoạch | Trong kỳ; BR-05-03 | `task.task.due_date` |
| 12 | Người nghiệm thu | Tham chiếu người | Có | Một người | Người giao | BR-05-02 | `task.task.acceptor_id` (đề xuất) |
| 13 | Mốc kiểm soát | Danh sách | Không | Ngày, nội dung | Không có | Trước hạn | `task.task.milestones` (JSON, đề xuất) |
| 14 | Sản phẩm, số lượng, đơn vị tính | Chuỗi, số | Có | Theo danh mục | Theo danh mục | BR-05-03 | `task.task.product_name`, `quantity`, `unit` |
| 15 | Minh chứng yêu cầu | Văn bản | Có | ≤ 500 ký tự | Theo danh mục | Không trống | `task.task.evidence_required` |
| 16 | Tiêu chuẩn chất lượng | Văn bản | Có | ≤ 1.000 ký tự | Theo danh mục | Không trống | `task.task.quality_criteria` |
| 17 | Giao đồng loạt | Hộp chọn | Không | Đúng/Sai | Sai | Khi chọn, thêm danh sách đơn vị nhận | Tham số yêu cầu |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | Luôn | Lưu nhiệm vụ `NHAP` | `/api/v1/tasks/{id}` (PUT, If-Match) hoặc `/api/v1/tasks` (POST) | Không có |
| Giao việc | Danh sách kiểm tra không có lỗi chặn | Chuyển `DA_GIAO`, ghi nhật ký trạng thái | `/api/v1/tasks/{id}/assign` (POST, Idempotency-Key) | EVT-04, EVT-21 |
| Giao đồng loạt | Đã chọn giao đồng loạt | Tạo một nhiệm vụ cho mỗi đơn vị, trả 202 | `/api/v1/tasks/bulk-assign` (POST) | EVT-04 cho mỗi nhiệm vụ |
| Xem nhiệm vụ gần giống | Có cảnh báo trùng | Mở nhiệm vụ cũ ở cửa sổ bên | `/api/v1/tasks/{id}/similar` | Không có |
| Huỷ | Nhiệm vụ `NHAP` | Bỏ bản nháp | `/api/v1/tasks/{id}` (DELETE) | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-05-01 | Mỗi nhiệm vụ chỉ có đúng một người chủ trì. | Vi phạm BR-05-01 |
| MSG-05-02 | Người nghiệm thu phải khác người thực hiện nhiệm vụ. | Vi phạm BR-05-02 |
| MSG-05-03 | Nhiệm vụ gần giống {mã} (độ tương đồng {x}). Vui lòng gộp hoặc ghi lý do tạo mới. | BR-05-06 |
| MSG-05-04 | Đồng chí {tên} đang chủ trì {n} việc trong tuần {k}. Cân nhắc phân công lại. | BR-05-11 |
| MSG-05-05 | Đã giao nhiệm vụ {mã} cho đồng chí {tên}. | Giao thành công |

#### SCR-0502. Tạo nhiệm vụ từ văn bản chỉ đạo

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Hiển thị văn bản chỉ đạo đến và các nhiệm vụ do AI trích để người giao xác nhận từng nhiệm vụ trước khi giao |
| Tác nhân | AC-05; AC-12 (trích) |
| Đường vào | Thông báo "Văn bản đã sinh n nhiệm vụ chờ xác nhận"; menu "Nhiệm vụ / Văn bản đến" |
| Quyền | `manager` của đơn vị nhận văn bản |
| Thiết bị | Máy tính |

![Hình 8.4. Tạo nhiệm vụ từ văn bản chỉ đạo SCR-0502 với ô do AI điền viền tím](SCR-0502.png){width=16cm}

Hình 8.4 là văn bản 512-CV/ĐUK ngày 12/10/2026 của Đảng uỷ các cơ quan Đảng Trung ương về triển khai kiểm điểm, đánh giá, xếp loại năm 2026, nhận từ vOffice lúc 15:02 dạng EdXML. Vùng 1 là toàn văn văn bản ở bên trái; các đoạn giao việc được AI tô sáng và đánh số để người giao đối chiếu với nhiệm vụ trích. Vùng 2 là khối "Nội dung do AI tạo" ghi tên mô hình và thời điểm trích, với ba thẻ nhiệm vụ. Mỗi thẻ có độ tin cậy và các ô tên nhiệm vụ, chủ trì, phối hợp, hạn có viền tím và nhãn "AI"; sản phẩm, danh mục gợi ý, KR gợi ý hiển thị bên dưới. Nhiệm vụ 1 đã xác nhận (độ tin cậy 0,94), nhiệm vụ 2 chờ xác nhận (0,88), nhiệm vụ 3 có thể trùng (0,71). Vùng 3 là cảnh báo nhiệm vụ 3 gần giống NV-2026-Q4-0098 đang thực hiện. Vùng 4 là các nút xử lý nhiệm vụ đang chọn: sửa, bỏ, gộp vào nhiệm vụ cũ, xác nhận. Vùng 5 là nút "Xác nhận và giao 2 nhiệm vụ", chỉ giao các nhiệm vụ đã được xác nhận.

Nhiệm vụ 1 sau khi giao chính là nhiệm vụ NV-2026-Q4-0171 "Dự thảo hướng dẫn kiểm điểm chi bộ cuối năm" có hạn 30/10/2026 xuất hiện trên trang chủ của đồng chí Nguyễn Văn An (Hình 6.4); liên kết tới văn bản 512-CV/ĐUK được giữ trong nhiệm vụ để người nghiệm thu và cơ quan kiểm tra truy vết.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Số, ký hiệu văn bản | Chuỗi, chỉ đọc | Có | Theo NĐ 30/2020 | Từ EdXML | Không sửa | `task.task_source_doc.doc_number` |
| 2 | Ngày văn bản, nơi nhận | Ngày, chuỗi, chỉ đọc | Có | dd/mm/yyyy | Từ EdXML | Không sửa | `task.task_source_doc.doc_date`, `integ.inbox_message.received_at` |
| 3 | Trích yếu, nội dung | Văn bản, chỉ đọc | Có | Không giới hạn | Từ EdXML | Không sửa | `task.task_source_doc.summary`, tệp gốc trong evidence-service |
| 4 | Đoạn nguồn | Số, chỉ đọc | Có | Số thứ tự đoạn | AI xác định | BR-05-07 | `task.task_source_doc.paragraph_no` (đề xuất) |
| 5 | Tên nhiệm vụ | Chuỗi, AI điền | Có | ≤ 500 ký tự | Gợi ý AI | Viền tím tới khi xác nhận | `task.task.title` |
| 6 | Chủ trì | Tham chiếu, AI điền | Có | Một đơn vị hoặc người | Gợi ý AI | BR-05-01 | `task.task_assignee` |
| 7 | Phối hợp | Danh sách, AI điền | Không | Đơn vị | Gợi ý AI | Không trùng chủ trì | `task.task_assignee` |
| 8 | Hạn | Ngày, AI điền | Có | dd/mm/yyyy | Gợi ý AI | Trong kỳ hoặc kỳ sau | `task.task.due_date` |
| 9 | Sản phẩm | Chuỗi | Có | ≤ 255 ký tự | Gợi ý AI | Không trống | `task.task.product_name` |
| 10 | Danh mục gợi ý, KR gợi ý | Tham chiếu | Không | Mã | Gợi ý AI | Danh mục và KR cùng kỳ | `task.task.catalog_item_id`, `task.task.kr_id` |
| 11 | Độ tin cậy | Số, chỉ đọc | Có | 0 đến 1, hai chữ số | Từ AI | Không có | `ai.suggestion_log.confidence` |
| 12 | Trạng thái xác nhận | Liệt kê | Có | Chờ xác nhận, Đã xác nhận, Đã bỏ, Đã gộp | Chờ xác nhận | BR-05-05 | `ai.suggestion_log.decision` |
| 13 | Danh sách ô người đã sửa | Danh sách, ẩn | Có khi xác nhận | Tên trường | Rỗng | Ghi vết | `ai.suggestion_log.edited_fields` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Sửa | Nhiệm vụ chưa giao | Cho sửa ô; ô đã sửa mất viền tím | Không có (lưu khi xác nhận) | Không có |
| Bỏ nhiệm vụ này | Nhiệm vụ chưa giao | Ghi quyết định bỏ với lý do | `/api/v1/ai/suggestions/{id}/decision` (POST) | Không có |
| Gộp vào nhiệm vụ cũ | Có nhiệm vụ gần giống | Thêm văn bản làm căn cứ của nhiệm vụ cũ | `/api/v1/tasks/{oldId}/source-docs` (POST) | EVT-21 cho người chủ trì nhiệm vụ cũ |
| Xác nhận nhiệm vụ | Chờ xác nhận | Tạo nhiệm vụ `NHAP` từ dữ liệu đã xác nhận, ghi danh sách ô đã sửa | `/api/v1/tasks/from-document` (POST, Idempotency-Key) | Không có |
| Xác nhận và giao | Có ≥ 1 nhiệm vụ đã xác nhận | Giao các nhiệm vụ đã xác nhận | `/api/v1/tasks/assign-batch` (POST) | EVT-04 cho mỗi nhiệm vụ |
| Bỏ qua văn bản | Luôn | Đánh dấu văn bản đã xem, không tạo nhiệm vụ, ghi lý do | `/api/v1/tasks/source-docs/{id}/dismiss` | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-05-06 | Văn bản {số} đã sinh {n} nhiệm vụ chờ xác nhận. | Sau khi AI trích |
| MSG-05-07 | Ô viền tím do AI điền. Sửa ô nào thì ô đó thành dữ liệu do người nhập. | Luôn hiển thị trên khối AI |
| MSG-05-08 | Nhiệm vụ {n} chưa được xác nhận nên chưa giao được. | Giao khi còn nhiệm vụ chờ |
| MSG-05-09 | Đoạn {n} của văn bản đã được tạo nhiệm vụ {mã}. | Vi phạm BR-05-07 |
| MSG-05-10 | Không trích được nhiệm vụ từ văn bản này. Vui lòng tạo nhiệm vụ thủ công. | AI không trích được |

#### SCR-0503. Việc của tôi

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Danh sách nhiệm vụ của người dùng trong kỳ với bộ lọc nhanh, cập nhật hàng loạt và đăng ký nhiệm vụ |
| Tác nhân | AC-06 |
| Đường vào | Menu "Mục tiêu và nhiệm vụ / Nhiệm vụ"; trang chủ |
| Quyền | `member` |
| Thiết bị | Máy tính |

![Hình 8.5. Danh sách Việc của tôi SCR-0503](SCR-0503.png){width=16cm}

Hình 8.5 là danh sách 7 nhiệm vụ Quý IV/2026 của đồng chí Nguyễn Văn An. Vùng 1 là các nút lọc nhanh có số đếm (tất cả, chậm, sắp hạn, chờ nghiệm thu, hoàn thành) và bộ lọc theo vai trò, nhóm, KR. Vùng 2 là bảng nhiệm vụ với cột nhóm, vai trò, hạn, tiến độ, tình trạng và trạng thái; danh sách cho thấy đủ các trạng thái `DA_GIAO`, `DANG_THUC_HIEN`, `CHO_NGHIEM_THU`, `HOAN_THANH`. Vùng 3 là bảng xem nhanh nhiệm vụ đang chọn (NV-2026-Q4-0157) với sản phẩm, người giao, điểm nghẽn và các nút mở chi tiết, lưu tiến độ. Vùng 4 là nút xuất Excel và nút đăng ký nhiệm vụ. Vùng 5 là khối đăng ký nhiệm vụ từ danh mục, ví dụ đăng ký kiểm tra 2 chi bộ trực thuộc (TC-DV-05) hạn đề xuất 20/11/2026.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc nhanh | Liệt kê | Có | Tất cả, Chậm, Sắp hạn, Chờ nghiệm thu, Hoàn thành | Tất cả | Không có | Tính từ `task.task.status`, `qua_han`, `due_date` |
| 2 | Nhiệm vụ, mã | Chuỗi | Có | Tên, `NV-…` | Không có | Không có | `task.task.title`, `code` |
| 3 | Vai trò | Liệt kê | Có | Chủ trì, Phối hợp | Không có | Không có | `task.task_assignee.role` |
| 4 | Hạn | Ngày | Có | dd/mm | Không có | Không có | `task.task.due_date` |
| 5 | Tiến độ | Phần trăm | Có | 0 đến 100 | 0 | Không có | `task.task.progress_pct` |
| 6 | Tình trạng | Văn bản tính | Có | "Trễ n ngày", "Còn n ngày", "Hôm nay", "Đúng tiến độ" | Không có | Ngày làm việc theo lịch nghỉ | Tính từ `due_date` và `config.holiday_calendar` |
| 7 | Trạng thái | Liệt kê | Có | Trạng thái chuẩn | Không có | Không có | `task.task.status` |
| 8 | Đăng ký: mục danh mục | Tham chiếu | Có | Mã | Không có | Danh mục áp dụng | `task.task.catalog_item_id` |
| 9 | Đăng ký: số lượng, hạn đề xuất | Số, ngày | Có | Theo đơn vị tính | Không có | Hạn trong kỳ | `task.task.quantity`, `due_date` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lọc, sắp xếp | Luôn | Tải danh sách | `/api/v1/tasks?assignee=me&period=&filter=&cursor=&limit=20` | Không có |
| Mở chi tiết | Mỗi dòng | Mở SCR-0601 | Không có | Không có |
| Nhận việc | Nhiệm vụ `DA_GIAO`, người chủ trì | Chuyển `DANG_THUC_HIEN` | `/api/v1/tasks/{id}/accept` (POST) | EVT-06 |
| Lưu tiến độ | Nhiệm vụ đang thực hiện | Ghi tiến độ | `/api/v1/tasks/{id}/progress` (POST, Idempotency-Key) | EVT-05 |
| Cập nhật tiến độ hàng loạt | Đã chọn nhiều dòng | Mở bảng nhập nhanh | `/api/v1/tasks/progress-batch` (POST) | EVT-05 cho mỗi nhiệm vụ |
| Gửi đăng ký | Đủ trường | Tạo nhiệm vụ `NHAP` có cờ đăng ký, gửi người quản lý | `/api/v1/tasks/registrations` (POST) | EVT-21 |
| Xuất Excel | Luôn | Tạo tác vụ xuất | `/api/v1/tasks/export` (202) | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-05-11 | Đã gửi đăng ký nhiệm vụ. Người quản lý trực tiếp sẽ xem xét. | Gửi đăng ký |
| MSG-05-12 | Đăng ký nhiệm vụ "{tên}" đã được duyệt và sẽ đưa vào điều chỉnh kế hoạch. | Đăng ký được duyệt |

#### SCR-0504. Việc của tôi trên di động

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Xem việc và cập nhật tiến độ nhanh trên điện thoại |
| Tác nhân | AC-06 |
| Đường vào | Tab "Việc" của ứng dụng; thông báo được giao việc |
| Quyền | `member` |
| Thiết bị | Điện thoại Android, iOS |

![Hình 8.6. Việc của tôi và cập nhật tiến độ trên di động SCR-0504](SCR-0504.png){width=15cm}

Hình 8.6 có hai điện thoại. Điện thoại bên trái lúc 08:42 là danh sách việc tuần 4 với vùng 1 là các nút lọc nhanh và thẻ nhiệm vụ có thanh tiến độ, tình trạng màu. Điện thoại bên phải lúc 08:47 là màn hình cập nhật tiến độ nhiệm vụ NV-2026-Q4-0157. Vùng 2 là thanh trượt tiến độ từ 45% lên 60% kèm dòng gợi ý "11/18 hồ sơ đã có phiếu thẩm định" tính từ sản phẩm đã nộp. Vùng 3 là ô ghi chú. Vùng 4 là lựa chọn báo điểm nghẽn với nội dung "Chờ kết quả thẩm tra lý lịch từ địa phương" và nút nộp minh chứng. Vùng 5 là nút "Lưu". Cập nhật này xuất hiện trong nhật ký tiến độ trên máy tính với nhãn "(di động)" (Hình 8.9).

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Thẻ nhiệm vụ | Danh sách | Có | Tên rút gọn, mã ngắn, hạn, tiến độ, tình trạng | Sắp theo hạn | Như SCR-0503 | `task.task` |
| 2 | Tiến độ | Phần trăm, thanh trượt | Có | Bước 5% | Giá trị hiện tại | Không giảm trừ khi có ghi chú (BR-06-02) | `task.task_progress.progress_pct` |
| 3 | Ghi chú | Văn bản hoặc giọng nói | Không | ≤ 1.000 ký tự | Không có | Không có | `task.task_progress.note` |
| 4 | Điểm nghẽn | Có/Không, văn bản | Không | ≤ 500 ký tự | Không | Khi Có thì bắt buộc nội dung | `task.task_blocker.description` |
| 5 | Kênh cập nhật | Liệt kê, ẩn | Có | `WEB`, `MOBILE` | `MOBILE` | Không có | `task.task_progress.channel` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Mở nhiệm vụ | Mỗi thẻ | Mở màn hình cập nhật | Không có | Không có |
| Lưu | Có thay đổi | Gửi hoặc xếp hàng khi mất sóng | `/api/v1/tasks/{id}/progress` (POST, Idempotency-Key) | EVT-05 |
| Báo điểm nghẽn | Chọn Có | Ghi điểm nghẽn cùng lần cập nhật | `/api/v1/tasks/{id}/blockers` (POST) | EVT-21 cho người giao |
| Nộp minh chứng | Nhiệm vụ đang thực hiện | Mở SCR-0702 | Không có | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-05-13 | Đã lưu tiến độ {x}%. | Lưu thành công |
| MSG-05-14 | Đồng chí được giao nhiệm vụ mới: {tên}, hạn {ngày}. | Thông báo đẩy khi EVT-04 |

### Liên kết thiết kế

QT-05 do module task (MOD-02) xử lý với máy trạng thái khai báo; văn bản đến qua integration-gateway (SVC-13); trích nhiệm vụ qua ai-gateway (SVC-14) với quyền `ai_readonly`; tìm nhiệm vụ gần giống qua search (SVC-16) và chỉ mục vector trong `ai.doc_chunk`; thông báo qua notification-service (SVC-12). Bảng dữ liệu chính là `task.task`, `task.task_assignee`, `task.task_source_doc`, `task.task_state_log`, `integ.inbox_message`, `integ.mapping_xref`, `ai.suggestion_log`. Sự kiện tiêu thụ là EVT-03 và EVT-24; sự kiện phát ra là NhiemVuDaGiao (EVT-04), TrangThaiNhiemVuDoi (EVT-06), YeuCauThongBao (EVT-21). Tài liệu đề xuất thêm một số cột cho `task.task`, `task.task_source_doc` và `ai.suggestion_log` (xem tệp ghi chú). Về an toàn thông tin, quy trình liên quan tới lọc dữ liệu trước khi gửi AI (không gửi dữ liệu C3), kiểm soát lời nhắc độc hại trong văn bản đến và xác thực máy với máy cho vOffice, đặc tả trong TL-04; lý do vòng đời nhiệm vụ không chạy trên BPMN được trình bày trong TL-01 (ADR-04).

## QT-06. Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn và điều chỉnh

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-06 |
| Tên quy trình | Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn và điều chỉnh |
| Mục đích | Ghi nhận tiến độ thực hiện, phát hiện sớm nguy cơ trễ hạn, xử lý điểm nghẽn và kiểm soát mọi thay đổi về hạn, số lượng, sản phẩm, tạm dừng, huỷ nhiệm vụ, với nguyên nhân chủ quan hoặc khách quan được xác định rõ để tính đúng hệ số tiến độ |
| Phạm vi áp dụng | Mọi nhiệm vụ ở trạng thái `DA_GIAO`, `DANG_THUC_HIEN`, `TAM_DUNG` trong kỳ đang `MO` |
| Căn cứ | PL 1 dự thảo Quy định mới về đánh giá tiến độ (hệ số tiến độ giảm 0,05 cho mỗi ngày làm việc chậm do nguyên nhân chủ quan); NĐ 335/2025 về đánh giá theo tiến độ hoàn thành; PL 4 về ghi vết thay đổi |
| Tác nhân | AC-06 (người thực hiện), AC-05 (người giao, người duyệt đề nghị), AC-04 (duyệt cấp trên khi vượt ngưỡng) |
| Tần suất và khối lượng ước tính | Ước tính 2 lần cập nhật tiến độ mỗi nhiệm vụ mỗi tuần, khoảng 7,4 triệu lần mỗi ngày làm việc toàn quốc (48 triệu nhiệm vụ đang mở, 13 tuần, 5 ngày); khoảng 5% nhiệm vụ có đề nghị gia hạn hoặc điều chỉnh, tức khoảng 2,4 triệu đề nghị mỗi quý (giả định, cần đo lại) |
| Điều kiện bắt đầu | Nhiệm vụ đã giao; người dùng là người chủ trì hoặc phối hợp |
| Điều kiện kết thúc | Tiến độ được ghi, hoặc điểm nghẽn được xử lý, hoặc đề nghị được duyệt hoặc từ chối và nhiệm vụ được cập nhật tương ứng |
| Đầu vào | Phần trăm tiến độ, ghi chú, điểm nghẽn, đề nghị kèm lý do và tài liệu |
| Đầu ra | `task.task_progress`, `task.task_blocker`, `task.task_change_request`, cập nhật `task.task`, dự báo nguy cơ trễ, cờ `qua_han` |
| Chỉ số đo hiệu quả | ≥ 90% nhiệm vụ được cập nhật ít nhất một lần mỗi tuần; ≥ 70% nhiệm vụ trễ hạn đã được dự báo "nguy cơ cao" trước hạn ít nhất 2 ngày làm việc; thời gian duyệt đề nghị trung vị ≤ 1 ngày làm việc |

Hệ số tiến độ C là thành phần điểm nhạy cảm nhất vì nó phụ thuộc vào cách xác định "chậm". Theo công thức của nền tảng, C bằng 1 trừ 0,05 nhân số ngày làm việc chậm do nguyên nhân chủ quan, và không nhỏ hơn 0; như vậy chậm 20 ngày làm việc trở lên do chủ quan thì C bằng 0. Ngày làm việc được tính theo lịch nghỉ của tenant, gồm cả lịch làm bù. Ngày chậm do nguyên nhân khách quan, như chờ kết quả thẩm tra từ địa phương, chờ ý kiến của cơ quan khác, thiên tai, không bị trừ điểm, nhưng chỉ khi nguyên nhân khách quan đã được người có thẩm quyền chấp nhận trong một đề nghị gia hạn. Nếu không có đề nghị được chấp nhận, mọi ngày chậm đều tính là chủ quan.

Vì lý do đó, QT-06 phân biệt hai loại hạn. Hạn thực hiện là ngày người thực hiện phải hoàn thành, hiển thị trên mọi màn hình và dùng để nhắc việc. Hạn tính điểm là ngày dùng để tính C. Khi đề nghị gia hạn được chấp nhận với nguyên nhân khách quan, cả hai hạn cùng dời sang ngày mới. Khi đề nghị được chấp nhận nhưng người duyệt xác định nguyên nhân chủ quan, chỉ hạn thực hiện dời (để công việc tiếp tục có kế hoạch rõ ràng), còn hạn tính điểm giữ nguyên. Thiết kế này cho phép người quản lý linh hoạt trong điều hành mà không làm sai lệch điểm.

### Lưu đồ quy trình

![Hình 8.7. Lưu đồ làn bơi QT-06 Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn và điều chỉnh](nv-qt06-luong.png){width=16cm}

Hình 8.7 có ba làn. Người thực hiện (AC-06) cập nhật phần trăm tiến độ và ghi chú; hệ thống (MOD-02, SVC-15) ghi `task.task_progress`, phát TienDoDaCapNhat (EVT-05) và dự báo nguy cơ trễ dựa trên nhịp thực hiện. Nếu có điểm nghẽn, người thực hiện ghi nguyên nhân và mức ảnh hưởng; người giao (AC-05) xử lý hoặc chuyển cấp trên. Nếu cần đổi hạn, số lượng, sản phẩm, tạm dừng hoặc huỷ, người thực hiện gửi đề nghị; người giao duyệt và xác định nguyên nhân khách quan hay chủ quan; hệ thống áp dụng (hạn mới hoặc giữ hạn tính điểm), phát TrangThaiNhiemVuDoi (EVT-06) và ghi sổ cái. Nếu không có thay đổi và nhiệm vụ đã quá hạn, hệ thống đặt cờ `qua_han`.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Cập nhật tiến độ | AC-06 | Nhập phần trăm tiến độ và ghi chú; người phối hợp ghi được nhật ký nhưng không đổi phần trăm | SCR-0601, SCR-0504 | `task.task_progress`; EVT-05 | Ít nhất mỗi tuần (tham số) |
| 2 | Dự báo nguy cơ trễ | SVC-15 | Tính nhịp thực hiện từ lịch sử tiến độ và sản phẩm đã nộp, so với số ngày làm việc còn lại; xếp mức thấp, trung bình, cao | SCR-0601 | Dự báo trên nhiệm vụ | ≤ 5 phút sau cập nhật |
| 3 | Báo điểm nghẽn | AC-06 | Ghi nội dung, nguyên nhân (khách quan hoặc chủ quan theo đánh giá của người thực hiện), mức ảnh hưởng, người cần hỗ trợ, hạn xử lý | SCR-0601, SCR-0504 | `task.task_blocker`; EVT-21 | Khi phát sinh |
| 4 | Xử lý điểm nghẽn | AC-05 | Giao người xử lý, đặt hạn xử lý, hoặc chuyển cấp trên; đóng điểm nghẽn khi đã giải quyết | SCR-0601 | Điểm nghẽn đóng | Theo hạn xử lý |
| 5 | Gửi đề nghị | AC-06 | Chọn loại đề nghị (gia hạn, điều chỉnh số lượng, điều chỉnh sản phẩm, tạm dừng, huỷ), nhập giá trị mới, nguyên nhân, lý do chi tiết, điểm nghẽn liên quan, tài liệu kèm; xem trước hệ quả với hệ số C | SCR-0602 | `task.task_change_request` `CHO_DUYET` | Trước hạn thực hiện |
| 6 | Duyệt đề nghị | AC-05; AC-04 nếu vượt ngưỡng | Xem lý do, tài liệu, lịch sử; xác định nguyên nhân khách quan hoặc chủ quan; chấp nhận hoặc từ chối có lý do; đề nghị vượt ngưỡng (ví dụ gia hạn quá 10 ngày làm việc hoặc lần thứ ba) chuyển luồng duyệt BPMN `duyet-de-nghi` | SCR-0601 | `DA_DUYET` hoặc `TU_CHOI` | ≤ 2 ngày làm việc |
| 7 | Áp dụng | MOD-02 | Gia hạn khách quan: dời cả hạn thực hiện và hạn tính điểm; gia hạn chủ quan: chỉ dời hạn thực hiện; điều chỉnh số lượng, sản phẩm: cập nhật nhiệm vụ; tạm dừng: chuyển `TAM_DUNG`; huỷ: chuyển `HUY` | Không có | EVT-06; sổ cái | Ngay |
| 8 | Đặt cờ quá hạn | MOD-02 (tác vụ định kỳ) | Hằng ngày lúc 00:05 theo giờ Việt Nam, đặt `qua_han` cho nhiệm vụ đã qua hạn tính điểm mà chưa `CHO_NGHIEM_THU` hoặc `HOAN_THANH`; gửi nhắc | Không có | EVT-21 | Hằng ngày |

Điểm khó của bảng trên là bước 6 và bước 7. Người duyệt thường là người giao việc, tức là người có thông tin tốt nhất về nguyên nhân chậm, nhưng cũng là người có thể bị ảnh hưởng bởi kết quả (vì kết quả nhiệm vụ của cấp dưới góp vào kết quả của đơn vị). Để giảm rủi ro "nương tay", nền tảng có ba biện pháp. Thứ nhất, người duyệt phải chọn nguyên nhân một cách tường minh và ghi lý do khi xác định khách quan. Thứ hai, tỷ lệ đề nghị được chấp nhận là khách quan của từng người duyệt được analytics theo dõi và vượt ngưỡng thì phát dấu hiệu bất thường cho QT-14. Thứ ba, mọi quyết định được ghi sổ cái để cơ quan kiểm tra hậu kiểm.

### Trạng thái đối tượng

QT-06 làm thay đổi trạng thái nhiệm vụ (đã mô tả ở Hình 8.2) và tạo ra đối tượng đề nghị `task.task_change_request`. Cơ sở thiết kế chung có bảng này nhưng chưa quy định trạng thái; tài liệu đề xuất năm trạng thái như Hình 8.8.

![Hình 8.8. Trạng thái của đề nghị gia hạn, điều chỉnh task.task_change_request](nv-qt06-trang-thai.png){width=12cm}

Hình 8.8 cho thấy đề nghị có ba kết thúc: được duyệt, bị từ chối và người gửi rút lại. Đề nghị đã kết thúc là bất biến; muốn đề nghị lại thì gửi đề nghị mới, nên lịch sử đề nghị của một nhiệm vụ luôn đầy đủ.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Soạn đề nghị | `NHAP` | Nhiệm vụ đang mở | Chỉ người gửi thấy |
| `NHAP` | Gửi | `CHO_DUYET` | Đủ trường bắt buộc; không có đề nghị cùng loại đang chờ | Thông báo người duyệt |
| `CHO_DUYET` | Chấp nhận | `DA_DUYET` | Người duyệt khác người gửi; đã chọn nguyên nhân | Áp dụng vào nhiệm vụ, phát EVT-06 |
| `CHO_DUYET` | Từ chối | `TU_CHOI` | Có lý do | Nhiệm vụ giữ nguyên |
| `CHO_DUYET` | Người gửi rút lại | `DA_RUT` | Chưa được xử lý | Không có |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-06-01 | Hệ số tiến độ C = 1 − 0,05 × số ngày làm việc chậm do nguyên nhân chủ quan, không nhỏ hơn 0 | PL 1 dự thảo Quy định mới; tham số Policy Pack | Tính tự động tại scoring-service |
| BR-06-02 | Tiến độ không được giảm so với lần trước trừ khi có ghi chú giải thích | Chất lượng dữ liệu | Chặn khi thiếu ghi chú |
| BR-06-03 | Chỉ người chủ trì đổi được phần trăm tiến độ; người phối hợp chỉ ghi nhật ký | Một chủ trì (Bảng 1) | Chặn |
| BR-06-04 | Ngày chậm chỉ được coi là khách quan khi có đề nghị gia hạn được chấp nhận với nguyên nhân khách quan | PL 1 | Thiết kế tính C dựa trên hạn tính điểm |
| BR-06-05 | Gia hạn với nguyên nhân chủ quan chỉ dời hạn thực hiện, không dời hạn tính điểm | PL 1 | Tự động khi áp dụng |
| BR-06-06 | Mỗi nhiệm vụ được gia hạn tối đa 2 lần mỗi quý; lần thứ ba hoặc gia hạn quá 10 ngày làm việc phải được cấp trên duyệt | Tham số Policy Pack | Chặn ở cấp người giao; chuyển luồng duyệt cấp trên |
| BR-06-07 | Đề nghị phải gửi trước hạn thực hiện; gửi sau hạn được nhận nhưng ngày chậm trước khi gửi luôn tính là chủ quan | Kỷ luật điều hành | Cảnh báo người gửi; ghi vết |
| BR-06-08 | Người duyệt không được là người gửi đề nghị | PL 4 V.1 | Chặn |
| BR-06-09 | Không huỷ được nhiệm vụ đã có minh chứng `DA_XAC_NHAN` | Bảo toàn kết quả nghiệm thu | Chặn |
| BR-06-10 | Thời gian `TAM_DUNG` không tính vào ngày chậm; hạn dời theo số ngày làm việc tạm dừng | PL 1 | Tự động khi tiếp tục |
| BR-06-11 | Từ chối hoặc xác định nguyên nhân chủ quan phải có lý do | Truy vết quyết định | Chặn |
| BR-06-12 | Tỷ lệ chấp nhận khách quan bất thường của một người duyệt được phát hiện | Chống nương tay (PL 4) | Ghi vết, phát dấu hiệu bất thường (EVT-20) |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Người duyệt vắng mặt quá 2 ngày làm việc | Đề nghị tự chuyển người duyệt thay thế theo `flowable.wf_binding`; người gửi được thông báo |
| Đề nghị chưa được duyệt khi đã qua hạn cũ | Ngày chậm tạm tính là chủ quan và hiển thị như vậy; khi đề nghị được duyệt khách quan, hệ thống tính lại từ hạn mới |
| Kỳ sắp khoá còn đề nghị chờ duyệt | Saga khoá kỳ (QT-08) liệt kê đề nghị tồn đọng ở bước kiểm tra tiền điều kiện; người duyệt phải xử lý trước khi khoá |
| Nhiệm vụ chuyển người chủ trì do điều động | Dùng đề nghị điều chỉnh loại "đổi người chủ trì" (cấu hình); tiến độ và minh chứng đã có giữ nguyên |
| Cập nhật đồng thời từ web và di động | API trả 409 cho cập nhật đến sau; ứng dụng hiển thị giá trị mới nhất và cho nhập lại |
| Lịch nghỉ thay đổi sau khi đã tính | Hạn và số ngày chậm được tính lại cho các nhiệm vụ chưa hoàn thành; nhiệm vụ đã hoàn thành giữ nguyên |

### Đặc tả màn hình

#### SCR-0601. Chi tiết nhiệm vụ

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Xem toàn bộ thông tin, tiến độ, điểm nghẽn, sản phẩm, đề nghị và lịch sử của một nhiệm vụ, và thực hiện các thao tác trong quá trình thực hiện |
| Tác nhân | AC-06 (người thực hiện), AC-05 (người giao, nghiệm thu), AC-07, AC-10 (chỉ đọc) |
| Đường vào | SCR-0503, SCR-0002, thông báo, tìm kiếm toàn cục |
| Quyền | Người được giao, người giao, người nghiệm thu, lãnh đạo đơn vị và cấp trên theo ABAC |
| Thiết bị | Máy tính; trên di động có dạng rút gọn |

![Hình 8.9. Chi tiết nhiệm vụ SCR-0601 với nhật ký tiến độ, điểm nghẽn và đề nghị](SCR-0601.png){width=16cm}

Hình 8.9 là chi tiết nhiệm vụ NV-2026-Q4-0157 "Thẩm định hồ sơ đề nghị kết nạp đảng viên đợt 2", nhóm then chốt, phục vụ KR 1.1, danh mục TC-DV-01, giao ngày 08/10/2026. Vùng 1 là trạng thái `DANG_THUC_HIEN` và bốn nút thao tác chính: báo điểm nghẽn, đề nghị gia hạn, nộp sản phẩm, cập nhật tiến độ. Vùng 2 là bốn thẻ chỉ số: tiến độ 60%, hạn 21/10/2026 còn 2 ngày làm việc, sản phẩm đã xác nhận 8 trên 18 với 3 đang chờ nghiệm thu, và dự báo trễ hạn "nguy cơ cao" dựa trên nhịp 3 hồ sơ mỗi ngày (còn 7 hồ sơ cần hơn 2 ngày làm việc). Dưới các thẻ là thanh tab tổng quan, tiến độ, điểm nghẽn, sản phẩm và minh chứng, đề nghị, lịch sử. Vùng 3 là nhật ký tiến độ, trong đó có cập nhật từ di động lúc 08:47 ngày 19/10 và một ghi nhận của người phối hợp không đổi phần trăm. Vùng 4 là bảng điểm nghẽn đang mở "Chờ kết quả thẩm tra lý lịch 4 quần chúng từ địa phương", nguyên nhân khách quan, người xử lý là Văn phòng Đảng uỷ, hạn xử lý 24/10. Vùng 5 là đề nghị gia hạn tới 28/10/2026 đang chờ duyệt; bên phải là thông tin nhiệm vụ và vòng đời trạng thái.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Trạng thái, cờ quá hạn | Liệt kê, chỉ đọc | Có | Trạng thái chuẩn; Đúng/Sai | Không có | Theo máy trạng thái | `task.task.status`, `task.task.qua_han` |
| 2 | Tiến độ | Phần trăm | Có | 0 đến 100 | 0 | BR-06-02, BR-06-03 | `task.task.progress_pct`, `task.task_progress` |
| 3 | Hạn thực hiện | Ngày, chỉ đọc | Có | dd/mm/yyyy | Hạn khi giao | Chỉ đổi qua đề nghị | `task.task.due_date` |
| 4 | Hạn tính điểm | Ngày, chỉ đọc | Có | dd/mm/yyyy | Bằng hạn khi giao | BR-06-04, BR-06-05 | `task.task.score_due_date` (đề xuất) |
| 5 | Sản phẩm đã xác nhận, chờ nghiệm thu | Số, chỉ đọc | Có | n/N | 0 | Đếm theo trạng thái minh chứng | `product.deliverable`, `product.evidence_version.status` |
| 6 | Dự báo trễ hạn | Liệt kê, chỉ đọc | Không | Thấp, Trung bình, Cao kèm giải thích | Không có | Tính lại sau mỗi cập nhật | `task.task.delay_risk` (đề xuất, ghi bởi SVC-15) |
| 7 | Nhật ký tiến độ | Danh sách | Có | Thời điểm, người, kênh, thay đổi, nội dung | Không có | Chỉ thêm | `task.task_progress` |
| 8 | Điểm nghẽn | Danh sách | Không | Nội dung, nguyên nhân, mức, người xử lý, hạn, trạng thái | Không có | Nội dung bắt buộc | `task.task_blocker` |
| 9 | Đề nghị | Danh sách | Không | Loại, giá trị mới, nguyên nhân, trạng thái | Không có | Một đề nghị cùng loại đang chờ | `task.task_change_request` |
| 10 | Thông tin nhiệm vụ | Nhóm chỉ đọc | Có | Chủ trì, phối hợp, nghiệm thu, sản phẩm, điểm danh mục, nguồn | Không có | Không có | `task.task`, `task.task_assignee`, `task.catalog_item` |
| 11 | Vòng đời | Danh sách | Có | Chuyển trạng thái, thời điểm | Không có | Chỉ đọc | `task.task_state_log` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Cập nhật tiến độ | Người chủ trì; nhiệm vụ đang thực hiện | Mở hộp cập nhật | `/api/v1/tasks/{id}/progress` (POST, Idempotency-Key) | EVT-05 |
| Báo điểm nghẽn | Người được giao | Mở hộp điểm nghẽn | `/api/v1/tasks/{id}/blockers` (POST) | EVT-21 |
| Xử lý điểm nghẽn | Người giao | Giao người xử lý, đặt hạn, đóng | `/api/v1/tasks/{id}/blockers/{bid}` (PATCH, If-Match) | EVT-21 |
| Đề nghị gia hạn | Người chủ trì; nhiệm vụ đang mở | Mở SCR-0602 | Không có | Không có |
| Duyệt đề nghị | Người duyệt; đề nghị `CHO_DUYET` | Mở hộp duyệt: chọn nguyên nhân, chấp nhận hoặc từ chối | `/api/v1/tasks/{id}/change-requests/{cid}/decision` (POST, Idempotency-Key) | EVT-06 |
| Nộp sản phẩm | Người chủ trì | Mở SCR-0701 | Không có | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-06-01 | Tiến độ mới thấp hơn lần trước. Vui lòng ghi chú lý do. | Vi phạm BR-06-02 |
| MSG-06-02 | Nhiệm vụ có nguy cơ trễ hạn cao: cần khoảng {n} ngày làm việc, còn {m} ngày. | Dự báo mức Cao |
| MSG-06-03 | Nhiệm vụ đã quá hạn {n} ngày làm việc. Hệ số tiến độ dự kiến C = {c}. | Cờ `qua_han` bật |
| MSG-06-04 | Đã ghi điểm nghẽn và thông báo cho đồng chí {người giao}. | Báo điểm nghẽn |
| MSG-06-05 | Đã chấp nhận đề nghị với nguyên nhân {khách quan/chủ quan}. Hạn tính điểm: {ngày}. | Duyệt đề nghị |

#### SCR-0602. Đề nghị gia hạn, điều chỉnh

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người thực hiện gửi đề nghị gia hạn, điều chỉnh, tạm dừng hoặc huỷ nhiệm vụ, thấy trước hệ quả với hệ số tiến độ |
| Tác nhân | AC-06 |
| Đường vào | SCR-0601, nút "Đề nghị gia hạn" |
| Quyền | Người chủ trì của nhiệm vụ |
| Thiết bị | Máy tính (hộp thoại trên SCR-0601); di động có biểu mẫu tương đương |

![Hình 8.10. Hộp thoại đề nghị gia hạn, điều chỉnh SCR-0602](SCR-0602.png){width=16cm}

Hình 8.10 là hộp thoại mở trên nền màn hình chi tiết nhiệm vụ. Vùng 1 là biểu mẫu: loại đề nghị (gia hạn được chọn), hạn mới đề nghị 28/10/2026 kèm số ngày làm việc tăng thêm (+5), nguyên nhân khách quan, điểm nghẽn liên quan BN-0157-01, lý do chi tiết (4 hồ sơ chờ kết quả thẩm tra lý lịch do Đảng uỷ xã nơi cư trú gửi, dự kiến nhận 24/10, đã có công văn đôn đốc số 47-CV/BTC) và tài liệu kèm theo có mã băm. Vùng 2 là khối xem trước hệ quả: nếu được chấp nhận là khách quan, hạn tính điểm đổi thành 28/10 và hoàn thành đúng hạn mới thì C = 1,00; nếu bị đánh giá là chủ quan, hạn tính điểm giữ 21/10 và hoàn thành ngày 28/10 là chậm 5 ngày làm việc, C = 1 − 0,05 × 5 = 0,75. Vùng 3 là thông tin người duyệt và số lần gia hạn đã dùng (0 trên 2 trong quý). Vùng 4 là nút "Gửi đề nghị".

Khối xem trước hệ quả có vai trò quan trọng về hành vi: cán bộ hiểu ngay rằng gia hạn không "xoá" việc chậm nếu nguyên nhân là chủ quan, nên đề nghị được viết có căn cứ hơn và người duyệt có thông tin để quyết định.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Loại đề nghị | Liệt kê | Có | `GIA_HAN`, `DOI_SO_LUONG`, `DOI_SAN_PHAM`, `TAM_DUNG`, `HUY` | `GIA_HAN` | BR-06-09 với huỷ | `task.task_change_request.request_type` |
| 2 | Hạn mới đề nghị | Ngày | Có với gia hạn | dd/mm/yyyy | Không có | Sau hạn hiện tại; trong kỳ hoặc ngày hạn nghiệm thu tồn đọng | `task.task_change_request.new_due_date` |
| 3 | Số ngày làm việc tăng thêm | Số, chỉ đọc | Có với gia hạn | Số nguyên | Tính tự động | Theo lịch nghỉ; BR-06-06 | Tính từ `config.holiday_calendar` |
| 4 | Số lượng, sản phẩm mới | Số, chuỗi | Có với loại tương ứng | Theo đơn vị tính | Giá trị hiện tại | > 0 | `task.task_change_request.new_values` (JSON) |
| 5 | Nguyên nhân theo người gửi | Liệt kê | Có | Khách quan, Chủ quan | Không có | Không có | `task.task_change_request.cause_claimed` (đề xuất) |
| 6 | Điểm nghẽn liên quan | Tham chiếu | Không | Mã điểm nghẽn | Điểm nghẽn đang mở | Cùng nhiệm vụ | `task.task_change_request.blocker_id` (đề xuất) |
| 7 | Lý do chi tiết | Văn bản | Có | 20 đến 2.000 ký tự | Không có | Không trống | `task.task_change_request.reason` |
| 8 | Tài liệu kèm theo | Tệp | Không | PDF, ảnh, ≤ 20 MB | Không có | Mã băm SHA-256, quét mã độc | `evidence.object_meta` (qua `task.task_change_request.attachment_ids`) |
| 9 | Người duyệt | Tham chiếu, chỉ đọc | Có | Họ tên | Người giao việc hoặc theo luồng | BR-06-08 | `task.task_change_request.approver_id` (đề xuất) |
| 10 | Số lần gia hạn đã dùng | Số, chỉ đọc | Có | n trên tối đa | Đếm | BR-06-06 | Đếm `task.task_change_request` `DA_DUYET` loại gia hạn |
| 11 | Nguyên nhân do người duyệt xác định | Liệt kê | Có khi duyệt | Khách quan, Chủ quan | Không có | BR-06-11 | `task.task_change_request.cause_decided` (đề xuất) |
| 12 | Trạng thái đề nghị | Liệt kê | Có | `NHAP`, `CHO_DUYET`, `DA_DUYET`, `TU_CHOI`, `DA_RUT` | `NHAP` | Hình 8.8 | `task.task_change_request.status` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Đổi loại đề nghị | Luôn | Đổi các trường hiển thị | Không có | Không có |
| Xem trước hệ quả | Có hạn mới | Tính C cho hai trường hợp khách quan và chủ quan | `/api/v1/tasks/{id}/change-requests/preview` (POST) | Không có |
| Đính kèm tài liệu | Luôn | Tải tệp dạng luồng, tính mã băm | `/api/v1/files` (POST) | Không có |
| Gửi đề nghị | Đủ trường | Tạo đề nghị `CHO_DUYET`, thông báo người duyệt; nếu vượt ngưỡng thì tạo tiến trình `duyet-de-nghi` | `/api/v1/tasks/{id}/change-requests` (POST, Idempotency-Key) | EVT-21 |
| Rút lại | Đề nghị `CHO_DUYET` của mình | Chuyển `DA_RUT` | `/api/v1/tasks/{id}/change-requests/{cid}/withdraw` (POST) | Không có |
| Huỷ | Luôn | Đóng hộp thoại, không lưu | Không có | Không có |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-06-06 | Nếu được chấp nhận là khách quan: C = {c1}. Nếu bị đánh giá là chủ quan: C = {c2}. | Xem trước hệ quả |
| MSG-06-07 | Nhiệm vụ đã gia hạn {n} lần trong quý. Đề nghị này sẽ được chuyển lên {cấp trên} duyệt. | Vượt ngưỡng BR-06-06 |
| MSG-06-08 | Đề nghị gửi sau hạn thực hiện. Các ngày chậm trước khi gửi được tính là chủ quan. | BR-06-07 |
| MSG-06-09 | Không thể huỷ nhiệm vụ đã có sản phẩm được nghiệm thu. | Vi phạm BR-06-09 |
| MSG-06-10 | Đã gửi đề nghị tới đồng chí {người duyệt}. | Gửi thành công |

### Liên kết thiết kế

QT-06 do module task (MOD-02) xử lý; dự báo nguy cơ trễ và phát hiện tỷ lệ chấp nhận bất thường do analytics (SVC-15); đề nghị vượt ngưỡng chạy luồng duyệt trên workflow-service (SVC-06) theo mẫu `duyet-de-nghi`; tệp đính kèm qua evidence-service (SVC-08). Bảng dữ liệu chính là `task.task`, `task.task_progress`, `task.task_blocker`, `task.task_change_request`, `task.task_state_log`, `config.holiday_calendar`. Sự kiện phát ra là TienDoDaCapNhat (EVT-05), TrangThaiNhiemVuDoi (EVT-06), YeuCauThongBao (EVT-21) và DauHieuBatThuong (EVT-20) từ analytics. Tài liệu đề xuất cột `score_due_date`, `delay_risk` cho `task.task`, cột trạng thái và nguyên nhân cho `task.task_change_request`, cột `channel` cho `task.task_progress` (xem tệp ghi chú). Kịch bản thử tải cập nhật tiến độ đồng loạt và ngân sách độ trễ khoảng 25 ms phía máy chủ cho thao tác này được phân tích trong TL-01 (G3) và kiểm chứng trong TL-04.

## QT-07. Nộp và nghiệm thu sản phẩm, minh chứng

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-07 |
| Tên quy trình | Nộp và nghiệm thu sản phẩm, minh chứng |
| Mục đích | Thu nhận minh chứng cho sản phẩm của nhiệm vụ với bảo đảm toàn vẹn (mã băm, phiên bản, chỉ ghi), cho người nghiệm thu đối chiếu tiêu chuẩn và chọn một trong năm mức chất lượng, từ đó tính ba thành phần điểm A, B, C của nhiệm vụ |
| Phạm vi áp dụng | Mọi sản phẩm của nhiệm vụ trong kỳ đang `MO`, kể cả nộp bổ sung trong thời hạn nghiệm thu tồn đọng sau ngày cuối kỳ |
| Căn cứ | PL 1 dự thảo Quy định mới (đánh giá số lượng, chất lượng, tiến độ; năm mức chất lượng); PL 4 (minh chứng không sửa được, tách người nộp và người xác nhận); NĐ 335/2025 về đánh giá theo sản phẩm; Luật Giao dịch điện tử 2023 về giá trị pháp lý của thông điệp dữ liệu |
| Tác nhân | AC-06 (người nộp), AC-05 (người nghiệm thu), AC-12 (trợ lý AI đọc văn bản), AC-11 (vOffice cung cấp văn bản đã ban hành) |
| Tần suất và khối lượng ước tính | Theo cơ sở thiết kế chung, khoảng 8 tệp minh chứng mỗi người mỗi quý, kích thước trung bình 1 MB: 6 triệu × 8 = 48 triệu tệp mỗi quý, khoảng 192 TB dữ liệu gốc mỗi năm trước khử trùng; khoảng 30% số lần nộp dồn vào 3 ngày cuối quý |
| Điều kiện bắt đầu | Nhiệm vụ ở `DANG_THUC_HIEN` hoặc `CHO_NGHIEM_THU`; người nộp là người chủ trì |
| Điều kiện kết thúc | Mọi sản phẩm của nhiệm vụ có phiên bản minh chứng `DA_XAC_NHAN`, nhiệm vụ `HOAN_THANH`, điểm A, B, C được tính; hoặc phiên bản bị `TU_CHOI` hoặc `YEU_CAU_BO_SUNG` và nhiệm vụ trở về `DANG_THUC_HIEN` |
| Đầu vào | Tệp, văn bản đã ban hành, đường dẫn hệ thống nguồn, ảnh chụp; giải trình khi trùng; mức chất lượng và nhận xét của người nghiệm thu |
| Đầu ra | `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `evidence.object_meta`, `evidence.object_version`, `evidence.dedup_index`, `evidence.scan_result`; điểm tạm tính trong `scoring.score_result` |
| Chỉ số đo hiệu quả | 100% minh chứng có mã băm SHA-256 kiểm lại ở máy chủ; 0 minh chứng chưa xác nhận được tính điểm; thời gian nghiệm thu trung vị ≤ 3 ngày làm việc; tải lên tệp 10 MB trên 4G p95 ≤ 10 giây |

Minh chứng là nền móng của toàn bộ kết quả đánh giá. Nếu minh chứng có thể bị sửa sau khi nộp, bị dùng lại cho nhiều nhiệm vụ hoặc được tính điểm khi chưa ai xác nhận, thì mọi điểm số phía sau đều không đáng tin. QT-07 vì vậy áp dụng bốn nguyên tắc. Thứ nhất, minh chứng có phiên bản và chỉ ghi: mỗi lần nộp lại tạo phiên bản mới, phiên bản cũ được giữ nguyên trong kho đối tượng có khoá chống xoá (Object Lock). Thứ hai, mỗi tệp được tính mã băm SHA-256 ngay khi tải lên dạng luồng, mã băm được kiểm lại ở máy chủ và ghi vào sổ cái. Thứ ba, trạng thái thực hiện của nhiệm vụ và trạng thái nghiệm thu của minh chứng được tách biệt: người thực hiện quyết định khi nào nộp, người nghiệm thu quyết định chất lượng. Thứ tư, chỉ phiên bản minh chứng ở trạng thái `DA_XAC_NHAN` mới được tính điểm.

Trùng minh chứng được phát hiện bằng chỉ mục khử trùng `evidence.dedup_index` theo mã băm. Khi một tệp trùng mã băm với tệp đã nộp cho nhiệm vụ khác, hệ thống không chặn (vì có trường hợp hợp lệ, như một phụ lục số liệu dùng chung cho hai báo cáo theo chỉ đạo), nhưng yêu cầu người nộp ghi giải trình và hiển thị cảnh báo nổi bật cho người nghiệm thu. Dung lượng lưu trữ thực tế cũng giảm vì tệp trùng chỉ lưu một bản vật lý.

### Lưu đồ quy trình

![Hình 8.11. Lưu đồ làn bơi QT-07 Nộp và nghiệm thu sản phẩm, minh chứng](nv-qt07-luong.png){width=16cm}

Hình 8.11 có bốn làn. Người thực hiện (AC-06) bắt đầu khi có sản phẩm, chọn nguồn minh chứng: văn bản đã ban hành lấy từ vOffice, tải tệp, hoặc chụp ảnh trên di động. Làn minh chứng (SVC-08, SVC-14) tải dạng luồng, tính SHA-256, ghi với Object Lock, quét mã độc bất đồng bộ, rồi chạy OCR để trích số, ngày, trích yếu và gợi ý nhiệm vụ khớp. Người thực hiện xác nhận ô do AI điền, ghi giải trình nếu trùng và nộp. Nếu trùng mã băm, hệ thống gắn cờ cảnh báo cho người nghiệm thu. Người nghiệm thu (AC-05) đối chiếu tiêu chuẩn và chọn mức 1; 0,75; 0,5; 0,25 hoặc 0. Nếu xác nhận, làn hệ thống (MOD-03, SVC-07) chuyển `DA_XAC_NHAN`, phát MinhChungDaXacNhan (EVT-08) và tính dần điểm A, B, C. Nếu chưa đạt, minh chứng chuyển `YEU_CAU_BO_SUNG` hoặc `TU_CHOI` và người thực hiện quay lại bước chọn nguồn.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Chọn nguồn | AC-06 | Văn bản đã ban hành (chọn từ danh sách văn bản của đơn vị trong vOffice, không tải lại), tải tệp (PDF, DOCX, XLSX, ảnh ≤ 50 MB), đường dẫn hệ thống nguồn (CSDL đảng viên, LGSP), hoặc chụp ảnh | SCR-0701, SCR-0702 | Không có | Không có |
| 2 | Tải lên và băm | SVC-08 | Tải dạng luồng qua URL ký sẵn, tính SHA-256 trên trình duyệt và kiểm lại ở máy chủ; ghi đối tượng với Object Lock; tra `evidence.dedup_index` | SCR-0701, SCR-0702 | `evidence.object_version` | Tệp 10 MB khoảng 15 đến 25 ms tính băm phía máy chủ |
| 3 | Quét mã độc | SVC-08 | Quét bất đồng bộ; tệp nhiễm bị cách ly và phiên bản không nộp được | SCR-0701 | `evidence.scan_result` | ≤ 2 phút |
| 4 | OCR và trích thông tin | SVC-14 | Đọc số, ký hiệu, ngày ban hành, trích yếu; gợi ý nhiệm vụ khớp với độ khớp; điền ô có viền tím | SCR-0702 | Gợi ý trong `ai.suggestion_log` | ≤ 30 giây |
| 5 | Xác nhận và giải trình | AC-06 | Xác nhận hoặc sửa ô do AI điền; ghi giải trình nếu trùng mã băm; tích cam kết | SCR-0701, SCR-0702 | Không có | Không có |
| 6 | Nộp | AC-06 | Phiên bản chuyển `DA_NOP`; nếu đủ số lượng sản phẩm, nhiệm vụ chuyển `CHO_NGHIEM_THU` và ngày nộp được ghi làm ngày hoàn thành tạm | SCR-0701, SCR-0702 | EVT-07; EVT-06 | Trước hạn nộp minh chứng của kỳ |
| 7 | Mở nghiệm thu | AC-05 | Người nghiệm thu mở minh chứng; phiên bản chuyển `DANG_NGHIEM_THU`; hệ thống kiểm toàn vẹn mã băm khi mở | SCR-0703 | Không có | Trong 5 ngày làm việc sau khi nộp (tham số) |
| 8 | Đối chiếu tiêu chuẩn | AC-05 | Đánh giá từng tiêu chuẩn chất lượng của mục danh mục là đạt hoặc chưa đạt | SCR-0703 | Không có | Không có |
| 9 | Chọn mức chất lượng | AC-05 | Chọn B ∈ {1; 0,75; 0,5; 0,25; 0}; chọn nguyên nhân chỉnh sửa (chủ quan, khách quan) nếu có phiên bản bị yêu cầu bổ sung trước đó; ghi nhận xét | SCR-0703 | Không có | Không có |
| 10a | Xác nhận | AC-05 | MFA bước lên nếu cần; phiên bản chuyển `DA_XAC_NHAN`; nếu mọi sản phẩm đã xác nhận thì nhiệm vụ chuyển `HOAN_THANH` | SCR-0703 | EVT-08; EVT-06 | Không có |
| 10b | Yêu cầu bổ sung hoặc từ chối | AC-05 | Ghi nội dung cần bổ sung hoặc lý do từ chối; phiên bản chuyển `YEU_CAU_BO_SUNG` hoặc `TU_CHOI`; nhiệm vụ trở về `DANG_THUC_HIEN` | SCR-0703 | EVT-06; EVT-21 | Không có |
| 11 | Tính dần điểm | SVC-07 | Nhận EVT-08, tính A, B, C và KPI nhiệm vụ theo Policy Pack đang gắn, ghi điểm tạm tính kèm vết tính toán | Không có | EVT-10 (điểm tạm tính) | ≤ 1 phút |

Ba thành phần điểm được tính như sau theo công thức mặc định của Policy Pack. Số lượng A bằng số sản phẩm đã xác nhận chia số lượng được giao, tối đa bằng 1. Chất lượng B bằng mức chất lượng người nghiệm thu chọn; nếu nhiệm vụ có nhiều sản phẩm thì B là trung bình các mức. Tiến độ C tính theo BR-06-01 với ngày hoàn thành là ngày nộp phiên bản minh chứng cuối cùng được xác nhận; số ngày chậm giữa phiên bản bị yêu cầu bổ sung và phiên bản đạt được tính là chủ quan hay khách quan theo nguyên nhân chỉnh sửa do người nghiệm thu chọn. KPI nhiệm vụ mặc định là trung bình cộng (A + B + C)/3. Ví dụ ở SCR-0703, nhiệm vụ NV-2026-Q4-0142 có A = 1,00 (một trên một báo cáo), B = 0,75 (cơ bản đạt, phải bổ sung số liệu), C = 0,90 (hạn 15/10, hoàn thành 19/10, chậm 2 ngày làm việc do chủ quan), nên KPI = (1,00 + 0,75 + 0,90)/3 ≈ 0,883; với điểm danh mục 30, nhiệm vụ đóng góp 30 × 0,883 ≈ 26,5 điểm trọng số vào nhóm then chốt theo công thức ở BR-04-06.

### Trạng thái đối tượng

Đối tượng chính là phiên bản minh chứng `product.evidence_version` với trạng thái chuẩn `NHAP`, `DA_NOP`, `DANG_NGHIEM_THU`, `DA_XAC_NHAN`, `YEU_CAU_BO_SUNG`, `TU_CHOI`.

![Hình 8.12. Trạng thái của phiên bản minh chứng product.evidence_version](nv-qt07-trang-thai.png){width=12cm}

Hình 8.12 cho thấy ba trạng thái cuối của một phiên bản. Phiên bản `YEU_CAU_BO_SUNG` không quay lại `NHAP` mà kết thúc; người nộp tạo phiên bản mới, nên lịch sử phiên bản luôn đầy đủ. Song song với máy trạng thái này, nhiệm vụ có máy trạng thái riêng (Hình 8.2); bảng dưới đây cho thấy cách hai máy trạng thái tác động lẫn nhau.

| Trạng thái minh chứng | Sự kiện | Trạng thái mới | Tác động tới nhiệm vụ | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Tạo phiên bản | `NHAP` | Không có | Phiên bản n+1 của minh chứng |
| `NHAP` | Nộp | `DA_NOP` | `DANG_THUC_HIEN` → `CHO_NGHIEM_THU` khi đủ số lượng | Cần mã băm hợp lệ và quét sạch; phát EVT-07 |
| `DA_NOP` | Người nghiệm thu mở | `DANG_NGHIEM_THU` | Không đổi | Kiểm toàn vẹn khi mở |
| `DANG_NGHIEM_THU` | Xác nhận | `DA_XAC_NHAN` | `CHO_NGHIEM_THU` → `HOAN_THANH` khi mọi sản phẩm xác nhận | Phát EVT-08; được tính điểm |
| `DANG_NGHIEM_THU` | Yêu cầu bổ sung | `YEU_CAU_BO_SUNG` | `CHO_NGHIEM_THU` → `DANG_THUC_HIEN` | Người nộp tạo phiên bản mới |
| `DANG_NGHIEM_THU` | Từ chối | `TU_CHOI` | `CHO_NGHIEM_THU` → `DANG_THUC_HIEN` | Lý do bắt buộc; B = 0 nếu không nộp lại trước hạn nghiệm thu tồn đọng |
| `DA_NOP` | Kỳ khoá trước khi nghiệm thu | `DA_NOP` (giữ) | Không đổi | Gắn cờ "không tính trong kỳ" (QT-08) |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-07-01 | Minh chứng không sửa, không xoá; nộp lại tạo phiên bản mới | PL 4 dự thảo Quy định mới | Chặn ở API và kho đối tượng (Object Lock chế độ tuân thủ) |
| BR-07-02 | Mọi tệp có mã băm SHA-256 tính khi tải lên và kiểm lại ở máy chủ; mã băm ghi vào sổ cái | PL 4; lớp bảo vệ 3 (G3) | Chặn nộp khi mã băm không khớp |
| BR-07-03 | Tệp trùng mã băm với minh chứng của nhiệm vụ khác phải có giải trình và được cảnh báo cho người nghiệm thu | Chống dùng lại minh chứng | Cảnh báo, bắt buộc giải trình |
| BR-07-04 | Tệp chưa quét sạch không nộp được | An toàn thông tin | Chặn |
| BR-07-05 | Chỉ phiên bản `DA_XAC_NHAN` được tính điểm | Cơ sở thiết kế chung 9.1 | Chặn ở scoring-service |
| BR-07-06 | Người nghiệm thu khác người nộp và người phối hợp | PL 4 V.1 | Chặn |
| BR-07-07 | Mức chất lượng chỉ nhận một trong năm giá trị 1; 0,75; 0,5; 0,25; 0 | PL 1 | Chặn |
| BR-07-08 | Yêu cầu bổ sung, từ chối, chọn mức dưới 1 phải có nhận xét | Truy vết quyết định | Chặn |
| BR-07-09 | Ô do AI điền chỉ có hiệu lực khi người nộp xác nhận; minh chứng chỉ tính điểm khi người nghiệm thu xác nhận | Nguyên tắc AI không quyết định | Chặn; ghi vết ô giữ nguyên, ô đã sửa |
| BR-07-10 | Trạng thái thực hiện và trạng thái nghiệm thu tách biệt | Cơ sở thiết kế chung 9.1 | Thiết kế hai máy trạng thái |
| BR-07-11 | Xác nhận nghiệm thu yêu cầu MFA trong vòng 15 phút | BR-00-02 | Chặn, mở MFA bước lên |
| BR-07-12 | Quá hạn nghiệm thu 5 ngày làm việc được nhắc người nghiệm thu và cấp trên | Kỷ luật điều hành; tham số | Cảnh báo, ghi vết |
| BR-07-13 | Văn bản đã ban hành lấy từ vOffice không tải lại tệp; dùng tệp và chữ ký số gốc | Một dữ liệu, một lần nhập | Thiết kế; kiểm tra chữ ký số khi nhận |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Mất kết nối giữa chừng khi tải tệp lớn | Tải theo khối có thể tiếp tục; mã băm tính lại trên toàn tệp khi hoàn tất |
| Tệp nhiễm mã độc | Cách ly tệp, thông báo người nộp và đội an toàn thông tin; phiên bản không nộp được |
| OCR đọc sai số văn bản | Người nộp sửa ô; ô mất viền tím; lần sửa được ghi để cải thiện mô hình |
| Người nghiệm thu vắng mặt quá hạn | Chuyển người nghiệm thu thay thế theo luồng uỷ quyền; ghi rõ nghiệm thu thay |
| Minh chứng nộp sau ngày cuối kỳ | Được nhận trong thời hạn nghiệm thu tồn đọng (ví dụ đến 02/04/2027 cho Quý I/2027); sau đó kỳ khoá và minh chứng còn `DA_NOP` không được tính trong kỳ |
| Phát hiện minh chứng giả sau khi xác nhận | Không sửa phiên bản; cơ quan kiểm tra mở hậu kiểm theo QT-13, kết quả được tính lại khi kỳ mở lại |
| Kiểm toàn vẹn khi mở không khớp | Chặn nghiệm thu, phát cảnh báo an ninh mức cao, chuyển đội vận hành theo quy trình sự cố của TL-04 |

### Đặc tả màn hình

#### SCR-0701. Nộp minh chứng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Nộp sản phẩm, minh chứng cho nhiệm vụ trên máy tính với kiểm tra toàn vẹn, phát hiện trùng và quản lý phiên bản |
| Tác nhân | AC-06 |
| Đường vào | SCR-0601, nút "Nộp sản phẩm"; menu "Minh chứng"; trang chủ |
| Quyền | Người chủ trì của nhiệm vụ |
| Thiết bị | Máy tính |

![Hình 8.13. Màn hình nộp minh chứng SCR-0701 với cảnh báo trùng mã băm](SCR-0701.png){width=16cm}

Hình 8.13 là màn hình đồng chí Nguyễn Văn An nộp phiên bản 2 minh chứng cho nhiệm vụ NV-2026-Q4-0142 "Báo cáo chuyên đề công tác kiểm tra quý III", hạn 15/10/2026. Vùng 1 là ba thẻ nguồn minh chứng; thẻ "Văn bản đã ban hành" được chọn và văn bản 47-BC/BTCĐUK ngày 19/10/2026 do đồng chí Trần Thị Bình ký số được lấy từ vOffice, không phải tải lại. Vùng 2 là bảng tệp của phiên bản 2 với dung lượng, mã SHA-256 rút gọn, trạng thái quét mã độc và OCR; tệp phụ lục đang quét. Vùng 3 là cảnh báo trùng: tệp "phu-luc-so-lieu.xlsx" trùng mã băm với minh chứng của NV-2026-Q3-0088 do đồng chí Phạm Thu Hà nộp ngày 02/09/2026, kèm ô giải trình bắt buộc. Vùng 4 là lịch sử phiên bản: phiên bản 1 nộp 14/10 đã bị yêu cầu bổ sung số liệu chi bộ 3, 6; phiên bản 2 đang soạn; phía dưới là khối "Trạng thái tách biệt" cho thấy trạng thái thực hiện và trạng thái nghiệm thu riêng rẽ, và ô cam kết. Vùng 5 là nút "Nộp để nghiệm thu".

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Nhiệm vụ, sản phẩm | Tham chiếu, chỉ đọc | Có | Mã, tên, hạn, sản phẩm | Theo đường vào | Người nộp là chủ trì | `product.deliverable.task_id`, `task.task` |
| 2 | Nguồn minh chứng | Liệt kê | Có | `VAN_BAN`, `TEP`, `LIEN_KET`, `ANH_CHUP` | `TEP` | Không có | `product.evidence.source_type` (đề xuất) |
| 3 | Văn bản đã ban hành | Tham chiếu | Có khi nguồn văn bản | Số, ngày, trích yếu | Không có | Văn bản của đơn vị trong vOffice | `product.evidence.external_doc_ref` (đề xuất), `integ.mapping_xref` |
| 4 | Số phiên bản | Số, chỉ đọc | Có | Số nguyên | n+1 | Tăng dần | `product.evidence_version.version_no` |
| 5 | Tệp | Tệp | Có với nguồn tệp | PDF, DOCX, XLSX, JPG, PNG; ≤ 50 MB | Không có | BR-07-04 | `evidence.object_meta`, `evidence.object_version` |
| 6 | Mã SHA-256 | Chuỗi, chỉ đọc | Có | 64 ký tự hex | Tính khi tải | BR-07-02 | `evidence.object_version.sha256` |
| 7 | Kết quả quét | Liệt kê, chỉ đọc | Có | Đang quét, Sạch, Nhiễm | Đang quét | BR-07-04 | `evidence.scan_result.verdict` |
| 8 | Trùng với | Tham chiếu, chỉ đọc | Không | Mã nhiệm vụ, người, ngày | Không có | Từ chỉ mục khử trùng | `evidence.dedup_index` |
| 9 | Giải trình dùng lại | Văn bản | Có khi trùng | 20 đến 1.000 ký tự | Không có | BR-07-03 | `product.evidence_version.reuse_justification` (đề xuất) |
| 10 | Trạng thái nghiệm thu | Liệt kê, chỉ đọc | Có | Trạng thái chuẩn | `NHAP` | Hình 8.12 | `product.evidence_version.status` |
| 11 | Cam kết | Hộp chọn | Có | Đúng | Sai | Bắt buộc khi nộp | `product.evidence_version.attested_at` (đề xuất) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn tệp, kéo thả | Nguồn tệp | Xin URL tải lên ký sẵn, tải dạng luồng, tính băm | `/api/v1/files/uploads` (POST), `/api/v1/files/uploads/{id}/complete` (POST) | Không có |
| Chọn văn bản | Nguồn văn bản | Tìm văn bản đã ban hành của đơn vị | `/api/v1/evidences/external-docs?q=` | Không có |
| Lưu nháp | Luôn | Lưu phiên bản `NHAP` | `/api/v1/evidences/{id}/versions` (POST) hoặc (PUT, If-Match) | Không có |
| Nộp để nghiệm thu | Mọi tệp sạch, mã băm khớp, đủ giải trình và cam kết | Chuyển `DA_NOP`; cập nhật nhiệm vụ nếu đủ sản phẩm | `/api/v1/evidences/{id}/versions/{v}/submit` (POST, Idempotency-Key) | EVT-07; EVT-06 khi nhiệm vụ đổi trạng thái |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-07-01 | Tệp "{tên}" trùng mã băm với minh chứng đã nộp cho {mã nhiệm vụ} ({người}, {ngày}). Vui lòng ghi giải trình; minh chứng chưa được tính điểm cho tới khi người nghiệm thu xác nhận. | Trùng mã băm |
| MSG-07-02 | Tệp "{tên}" không an toàn và đã được cách ly. | Quét phát hiện mã độc |
| MSG-07-03 | Mã băm kiểm tra ở máy chủ không khớp. Vui lòng tải lại tệp. | Băm không khớp |
| MSG-07-04 | Đã nộp phiên bản {v}. Người nghiệm thu: đồng chí {tên}. | Nộp thành công |
| MSG-07-05 | Nhiệm vụ đã chuyển sang Chờ nghiệm thu. | Đủ sản phẩm |

#### SCR-0702. Nộp minh chứng trên di động

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Chụp ảnh văn bản giấy, để AI đọc số, ngày, trích yếu và nhiệm vụ khớp, rồi người nộp xác nhận và nộp |
| Tác nhân | AC-06; AC-12 (OCR) |
| Đường vào | Tab "Minh chứng"; nút "Nộp minh chứng" trên SCR-0504 |
| Quyền | Người chủ trì của nhiệm vụ |
| Thiết bị | Điện thoại có máy ảnh |

![Hình 8.14. Nộp minh chứng trên di động SCR-0702 với OCR và ô do AI điền viền tím](SCR-0702.png){width=15cm}

Hình 8.14 minh hoạ cách nộp cùng sản phẩm bằng điện thoại khi người nộp chỉ có bản giấy. Điện thoại bên trái lúc 09:10 là màn hình chụp: vùng 1 là khung ngắm văn bản 47-BC/BTCĐUK với thanh tiến trình "Đang đọc văn bản… 92%" và hai nút thư viện ảnh, chụp. Điện thoại bên phải lúc 09:11 là màn hình xác nhận. Vùng 2 là khối "AI đã đọc văn bản, cần xác nhận" với ba ô viền tím có nhãn "AI": số ký hiệu, ngày ban hành, trích yếu. Vùng 3 là ô nhiệm vụ khớp NV-0142 với độ khớp 0,93, mã SHA-256 và dòng nhắc rằng minh chứng chỉ được tính điểm sau khi người nộp và người nghiệm thu xác nhận. Vùng 4 là nút "Sửa" và vùng 5 là nút "Xác nhận và nộp". Từ tab "Minh chứng", người dùng chụp (chạm 1), xem kết quả đọc (chạm 2) và xác nhận nộp (chạm 3).

Ảnh chụp được nén và chuyển thành PDF trên thiết bị trước khi tải lên; mã băm được tính trên tệp PDF đó. Ảnh gốc không được giữ lại trong thư viện ảnh của điện thoại, tránh lộ văn bản ngoài ứng dụng.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Ảnh chụp | Ảnh | Có | JPEG, chuyển PDF trên thiết bị; ≤ 20 MB | Không có | Độ nét đủ để OCR, nếu không thì yêu cầu chụp lại | `evidence.object_version` |
| 2 | Số, ký hiệu | Chuỗi, AI điền | Có | Theo NĐ 30/2020 | Từ OCR | Viền tím tới khi xác nhận | `product.evidence_version.doc_number` (đề xuất) |
| 3 | Ngày ban hành | Ngày, AI điền | Có | dd/mm/yyyy | Từ OCR | Không sau hôm nay | `product.evidence_version.doc_date` (đề xuất) |
| 4 | Trích yếu | Chuỗi, AI điền | Có | ≤ 500 ký tự | Từ OCR | Không trống | `product.evidence_version.doc_summary` (đề xuất) |
| 5 | Nhiệm vụ khớp | Tham chiếu, AI gợi ý | Có | Mã, tên, độ khớp | Gợi ý có độ khớp cao nhất | Nhiệm vụ đang mở của người nộp | `product.deliverable.task_id`; `ai.suggestion_log.confidence` |
| 6 | Mã SHA-256 | Chuỗi, chỉ đọc | Có | 64 ký tự hex | Tính trên thiết bị | Kiểm lại ở máy chủ | `evidence.object_version.sha256` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chụp, chọn ảnh | Luôn | Chụp, chuyển PDF, tải lên | `/api/v1/files/uploads` (POST) | Không có |
| Đọc văn bản | Sau khi tải lên | OCR và trích thông tin | `/api/v1/ai/evidence/extract` (POST) | Không có |
| Sửa | Có ô do AI điền | Cho sửa ô; ô đã sửa mất viền tím | Không có | Không có |
| Xác nhận và nộp | Đủ trường, quét sạch | Ghi dữ liệu đã xác nhận, nộp phiên bản | `/api/v1/evidences/{id}/versions/{v}/submit` (POST, Idempotency-Key) | EVT-07 |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-07-06 | Ảnh chưa đủ nét để đọc. Vui lòng chụp lại nơi đủ sáng. | OCR không đọc được |
| MSG-07-07 | Ô viền tím do AI điền. Minh chứng chỉ được dùng tính điểm sau khi đồng chí xác nhận và người nghiệm thu xác nhận. | Luôn hiển thị |
| MSG-07-08 | Không tìm thấy nhiệm vụ khớp. Vui lòng chọn nhiệm vụ. | Độ khớp dưới 0,5 |

#### SCR-0703. Nghiệm thu chất lượng 5 mức

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người nghiệm thu xem minh chứng, đối chiếu tiêu chuẩn chất lượng của danh mục, chọn mức chất lượng và xem trước điểm nhiệm vụ |
| Tác nhân | AC-05 |
| Đường vào | Menu "Đánh giá / Chờ tôi duyệt"; thông báo minh chứng đã nộp |
| Quyền | Người nghiệm thu được chỉ định của nhiệm vụ; phiên AAL2 khi xác nhận |
| Thiết bị | Máy tính |

![Hình 8.15. Nghiệm thu chất lượng 5 mức SCR-0703 với xem trước điểm A, B, C](SCR-0703.png){width=16cm}

Hình 8.15 là màn hình đồng chí Lê Minh Châu nghiệm thu phiên bản 2 minh chứng của NV-2026-Q4-0142, nộp lúc 09:11 ngày 19/10/2026, đang ở trạng thái `DANG_NGHIEM_THU`. Vùng 1 là trình xem minh chứng (trang 1 trên 6) với mã băm đã kiểm toàn vẹn. Vùng 2 là bảng đối chiếu ba tiêu chuẩn chất lượng của TC-DV-03: đủ số liệu theo mẫu (đạt sau bổ sung ở phiên bản 2), đúng thể thức NĐ 30/2020, được lãnh đạo ban duyệt (ký số 19/10). Vùng 3 là năm mức chất lượng với mô tả; mức 0,75 "Cơ bản đạt, sửa nội dung không cốt lõi" được chọn vì phiên bản 1 thiếu số liệu, nguyên nhân chỉnh sửa là chủ quan, kèm nhận xét. Vùng 4 là khối xem trước điểm theo Policy Pack Khối Đảng v2.0: A = 1,00, B = 0,75, C = 0,90, KPI nhiệm vụ = 0,883, với lưu ý điểm chính thức do scoring-service tính khi nhận sự kiện MinhChungDaXacNhan. Vùng 5 là ba nút yêu cầu bổ sung, từ chối và xác nhận nghiệm thu.

Năm mức chất lượng và ý nghĩa của chúng được giữ thống nhất trong toàn nền tảng như bảng dưới. Mô tả từng mức là tham số của Policy Pack để mỗi khối có thể diễn đạt cho phù hợp, nhưng năm giá trị số thì cố định theo PL 1.

| Mức B | Ý nghĩa mặc định |
|---|---|
| 1,00 | Đạt yêu cầu, chỉ sửa thể thức |
| 0,75 | Cơ bản đạt, phải sửa nội dung không cốt lõi |
| 0,50 | Phải sửa nội dung cơ bản |
| 0,25 | Sai sót nghiêm trọng, phải làm lại |
| 0,00 | Không chấp nhận, sai chủ trương hoặc không có giá trị sử dụng |

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Minh chứng, phiên bản | Tham chiếu, chỉ đọc | Có | Tệp, số phiên bản | Phiên bản mới nhất đã nộp | Kiểm toàn vẹn khi mở | `product.evidence_version`, `evidence.object_version.sha256` |
| 2 | Tiêu chuẩn chất lượng | Danh sách | Có | Tiêu chuẩn, Đạt hoặc Chưa đạt, ghi chú | Theo danh mục | Mọi tiêu chuẩn phải được đánh giá | `task.catalog_item.quality_criteria`; kết quả ở `product.acceptance.criteria_results` (JSON, đề xuất) |
| 3 | Mức chất lượng B | Liệt kê | Có | 1; 0,75; 0,5; 0,25; 0 | Không có | BR-07-07 | `product.acceptance.quality_level` |
| 4 | Nguyên nhân chỉnh sửa | Liệt kê | Có khi có phiên bản bị yêu cầu bổ sung | Chủ quan, Khách quan, Không có | Không có | Ảnh hưởng cách tính C | `product.acceptance.revision_cause` (đề xuất) |
| 5 | Nhận xét | Văn bản | Có khi B < 1 hoặc bổ sung, từ chối | ≤ 2.000 ký tự | Không có | BR-07-08; dữ liệu C2 | `product.acceptance.comment_enc` |
| 6 | A, B, C, KPI xem trước | Số, chỉ đọc | Có | 0 đến 1, ba chữ số | Tính từ Policy Pack | Chỉ xem trước | Gọi scoring-service (không lưu) |
| 7 | Người nghiệm thu, thời điểm | Chỉ đọc | Có | Họ tên, thời điểm | Người dùng hiện tại | BR-07-06 | `product.acceptance.acceptor_id`, `accepted_at` |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Mở minh chứng | Luôn | Chuyển `DANG_NGHIEM_THU` lần đầu, kiểm toàn vẹn | `/api/v1/evidences/{id}/versions/{v}/open-review` (POST) | Không có |
| Xem trước điểm | Đã chọn B | Tính A, B, C, KPI theo Policy Pack của kỳ | `/api/v1/appraisals/score-preview` (POST) | Không có |
| Xác nhận nghiệm thu | Người nghiệm thu khác người nộp; đủ trường; AAL2 trong 15 phút | Ghi `product.acceptance`, chuyển `DA_XAC_NHAN` | `/api/v1/evidences/{id}/versions/{v}/accept` (POST, Idempotency-Key) | EVT-08; EVT-06 khi nhiệm vụ hoàn thành |
| Yêu cầu bổ sung | Có nhận xét | Chuyển `YEU_CAU_BO_SUNG` | `/api/v1/evidences/{id}/versions/{v}/request-revision` (POST) | EVT-06, EVT-21 |
| Từ chối | Có lý do | Chuyển `TU_CHOI` | `/api/v1/evidences/{id}/versions/{v}/reject` (POST) | EVT-06, EVT-21 |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-07-09 | Vui lòng đánh giá tất cả tiêu chuẩn chất lượng trước khi chọn mức. | Thiếu đánh giá tiêu chuẩn |
| MSG-07-10 | Mức chất lượng dưới 1 cần có nhận xét. | Vi phạm BR-07-08 |
| MSG-07-11 | Minh chứng có tệp trùng mã băm với {mã nhiệm vụ}. Giải trình của người nộp: "{giải trình}". | Có cờ trùng |
| MSG-07-12 | Đã xác nhận nghiệm thu mức {B}. Điểm nhiệm vụ đang được tính. | Xác nhận thành công |
| MSG-07-13 | Toàn vẹn minh chứng không được bảo đảm. Nghiệm thu bị chặn và sự cố đã được báo. | Kiểm toàn vẹn thất bại |
| MSG-07-14 | Đồng chí có {n} minh chứng chờ nghiệm thu quá {k} ngày làm việc. | Vi phạm BR-07-12 |

### Liên kết thiết kế

QT-07 do module product (MOD-03) của mission-core xử lý; lưu trữ, băm, khử trùng, quét mã độc do evidence-service (SVC-08) trên kho đối tượng có Object Lock; OCR và trích thông tin qua ai-gateway (SVC-14); tính điểm dần do scoring-service (SVC-07); văn bản đã ban hành qua integration-gateway (SVC-13); mã băm và quyết định nghiệm thu ghi vào ledger-service (SVC-09). Bảng dữ liệu chính là `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `evidence.object_meta`, `evidence.object_version`, `evidence.dedup_index`, `evidence.scan_result`, `scoring.score_result`, `scoring.score_trace`. Sự kiện phát ra là MinhChungDaNop (EVT-07), MinhChungDaXacNhan (EVT-08), TrangThaiNhiemVuDoi (EVT-06) và DiemDaTinh (EVT-10) cho điểm tạm tính. Tài liệu đề xuất thêm một số cột cho `product.evidence`, `product.evidence_version` và `product.acceptance` (xem tệp ghi chú). Về an toàn thông tin, quy trình liên quan tới lớp bảo vệ minh chứng chỉ ghi, mã hoá trường nhận xét (C2), quét mã độc và kiểm toàn vẹn định kỳ, đặc tả trong TL-04; ước tính dung lượng và chi phí băm được trình bày trong TL-01.

# Khoá kỳ đánh giá

Khoá kỳ là ranh giới giữa hai chặng của vòng đời: trước khoá kỳ, dữ liệu nhiệm vụ, minh chứng và kết quả then chốt thay đổi liên tục; sau khoá kỳ, dữ liệu được đóng băng, điểm được tính chính thức trên ảnh chụp đã đóng băng và các quy trình đánh giá, xếp loại của phần B bắt đầu. Khoá kỳ cũng là thời điểm tải cao nhất của nền tảng: một cell lớn phải tính lại toàn bộ điểm của khoảng 1,2 triệu hồ sơ trong tối đa 2 giờ và sinh, ký số lô 100.000 phiếu trong tối đa 4 giờ (NFR-06). Vì vậy khoá kỳ được thiết kế là một saga trên workflow-service (SVC-06): một chuỗi bước có thể theo dõi, tạm dừng, chạy lại từng phần và bù trừ khi lỗi, thay vì một thao tác "bấm nút" đơn lẻ.

## QT-08. Khoá kỳ (saga chốt kỳ)

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã quy trình | QT-08 |
| Tên quy trình | Khoá kỳ (saga chốt kỳ) |
| Mục đích | Chốt dữ liệu của kỳ đánh giá, tính điểm chính thức hàng loạt cho mọi hồ sơ của cả hai luồng, sinh biểu mẫu và bảng chấm điểm, ký số niêm phong, chuyển kỳ sang `DA_KHOA` và mở các quy trình đánh giá |
| Phạm vi áp dụng | Mọi kỳ đánh giá (quý, năm) của mọi tenant; cả lần khoá lại sau khi mở lại hậu kiểm (QT-13) |
| Căn cứ | PL 1 dự thảo Quy định mới (thời hạn hoàn thành đánh giá quý, báo cáo trước ngày 10 của tháng đầu quý sau); PL 4 (khoá dữ liệu, tách vai trò khoá kỳ, mở lại phải có hai người duyệt); QĐ 366 và HD 02 về trình tự kiểm điểm, đánh giá; NĐ 335/2025 về thời điểm đánh giá |
| Tác nhân | AC-04 (phê duyệt lệnh khoá kỳ), AC-02 (khởi chạy và điều khiển saga), AC-09 (duyệt thứ hai khi huỷ và bù trừ toàn bộ), AC-10 (theo dõi, chỉ đọc), SVC-06, SVC-07, SVC-10, SVC-11 |
| Tần suất và khối lượng ước tính | Bốn lần khoá kỳ quý và một lần khoá kỳ năm mỗi năm cho mỗi tenant; với cell lớn nhất khoảng 1,2 triệu hồ sơ mỗi lần; toàn quốc khoảng 6 triệu hồ sơ mỗi quý, phân tán theo lịch khoá của từng tenant |
| Điều kiện bắt đầu | Kỳ ở `MO` (hoặc `MO_LAI_HAU_KIEM`); đã qua hạn nghiệm thu tồn đọng; lệnh khoá kỳ đã được AC-04 phê duyệt; không có saga khoá kỳ khác đang chạy cho kỳ |
| Điều kiện kết thúc | Kỳ ở `DA_KHOA`, mọi bước saga hoàn tất, sổ cái đã niêm phong, hồ sơ đánh giá được tạo; hoặc saga bị huỷ, mọi bước đã bù trừ và kỳ trở về `MO` |
| Đầu vào | Dữ liệu kỳ (OKR, nhiệm vụ, minh chứng đã xác nhận, nghiệm thu), Policy Pack ghim cho kỳ, cây tổ chức tại ngày cuối kỳ, lệnh khoá kỳ |
| Đầu ra | `config.period` ở `DA_KHOA`; `scoring.score_job`, `scoring.score_result`, `scoring.score_trace`; `appraisal.score_snapshot`; `docgen.render_output`; `esign.sign_request`; `ledger.merkle_seal`; hồ sơ `appraisal.appraisal_profile` ở `CHUA_LAP` |
| Chỉ số đo hiệu quả | Tính điểm 1,2 triệu hồ sơ ≤ 2 giờ, sinh và ký 100.000 phiếu ≤ 4 giờ (NFR-06); tỷ lệ hồ sơ lỗi phải xử lý tay < 0,01%; 0 trường hợp ghi dữ liệu vào kỳ đã khoá; kết quả tính lại trên cùng ảnh chụp cho ra cùng điểm (tính tất định) |

Có ba lý do khoá kỳ phải là saga thay vì một giao dịch. Lý do thứ nhất là quy mô: không thể khoá, tính 1,2 triệu hồ sơ, sinh tệp và ký số trong một giao dịch cơ sở dữ liệu. Lý do thứ hai là nhiều service tham gia: config (MOD-08) giữ trạng thái kỳ, scoring-service (SVC-07) tính điểm, document-service (SVC-11) sinh biểu mẫu, esign-service (SVC-10) ký số, ledger-service (SVC-09) niêm phong; mỗi service có cơ sở dữ liệu riêng. Lý do thứ ba là khả năng phục hồi: nếu ký số lỗi vì dịch vụ chứng thư gián đoạn ở bước cuối, không được phép tính lại toàn bộ điểm, mà chỉ cần thử lại bước ký; nếu lỗi không khắc phục được, phải hoàn tác có trật tự để kỳ trở về trạng thái mở, không để lại dữ liệu nửa vời.

Tính tất định là yêu cầu then chốt của bước tính điểm. Điểm được tính trên ảnh chụp dữ liệu tại thời điểm khoá (bước 1), với phiên bản Policy Pack được ghim cho kỳ từ khi kỳ được tạo (không dùng phiên bản mới phát hành giữa kỳ), và với cây tổ chức theo trục hiệu lực tại ngày cuối kỳ. Mỗi kết quả lưu vết tính toán (`scoring.score_trace`) gồm dữ liệu đầu vào, phiên bản quy tắc và từng bước tính, nên khi hậu kiểm hoặc khiếu nại, cơ quan kiểm tra có thể chạy lại và nhận đúng kết quả cũ.

### Lưu đồ quy trình

![Hình 9.1. Lưu đồ làn bơi QT-08 Khoá kỳ với sáu bước saga và nhánh bù trừ](nv-qt08-luong.png){width=15cm}

Hình 9.1 có bốn làn. Làn quản trị (AC-02, AC-04) bắt đầu khi hết hạn nghiệm thu của kỳ: AC-04 phê duyệt lệnh khoá kỳ, AC-02 khởi chạy saga sau khi xác thực MFA. Làn saga (SVC-06) điều phối sáu bước đánh số 0 đến 5: kiểm tra tiền điều kiện, khoá kỳ và phát KyDaKhoa (EVT-09), yêu cầu sinh mẫu, yêu cầu ký số lô và hoàn tất. Làn tính điểm (SVC-07) thực hiện bước 2 tính điểm hàng loạt, ghi ảnh chụp điểm và phát DiemDaTinh (EVT-10). Làn biểu mẫu và ký số (SVC-11, SVC-10) sinh mẫu, bảng chấm điểm (EVT-17) và ký lô bằng chứng thư tổ chức (EVT-18). Các đường nét đứt là nhánh lỗi: lỗi không khắc phục được ở bước tính điểm hoặc lỗi ký kéo dài dẫn tới bù trừ theo thứ tự ngược, trả kỳ về `MO` để quản trị xử lý và khởi chạy lại.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Lập và phê duyệt lệnh khoá kỳ | AC-02 lập, AC-04 phê duyệt | Lệnh ghi kỳ, căn cứ (thông báo của cấp uỷ hoặc quyết định), thời điểm dự kiến; AC-04 duyệt với MFA | SCR-0801 | Lệnh `DA_DUYET` | Trước ngày khoá ít nhất 1 ngày làm việc |
| 2 | Khởi chạy saga | AC-02 | Kiểm tra AC-02 khác AC-04, MFA bước lên, tạo saga với khoá idempotency theo kỳ (ví dụ `CK-2026Q4`) | SCR-0801 | Saga `DANG_CHAY` | Ngày khoá theo lịch |
| 3 | Bước 0: tiền điều kiện | SVC-06 | Kiểm tra hạn nghiệm thu tồn đọng đã qua, không còn đề nghị gia hạn chờ duyệt, Policy Pack ghim cho kỳ hợp lệ, cây tổ chức không còn lệch mức Cao, không có lỗi đồng bộ chưa xử lý; liệt kê minh chứng còn `DA_NOP` sẽ không được tính | SCR-0801 | Đạt hoặc dừng; danh sách cảnh báo | ≤ 5 phút |
| 4 | Bước 1: khoá kỳ | SVC-06, MOD-08 | Chuyển `config.period` sang `DANG_KHOA`; phát EVT-09; các module MOD-01, MOD-02, MOD-03 và phần gắn chính sách của kỳ trong MOD-05 chặn ghi vào kỳ; chụp ảnh dữ liệu (mốc nhất quán của cơ sở dữ liệu) | SCR-0801 | `DANG_KHOA`; EVT-09 | ≤ 1 phút |
| 5 | Bước 2: tính điểm hàng loạt | SVC-07 | Chia hồ sơ thành lô, mỗi lô một `scoring.score_job`; worker co giãn theo hàng đợi; mỗi hồ sơ tính điểm luồng Đảng và luồng Nhà nước theo `policy.policy_binding`; ghi `scoring.score_result`, `scoring.score_trace`; MOD-04 nhận EVT-10 và ghi `appraisal.score_snapshot`; lô lỗi tự thử lại tối đa 3 lần, hồ sơ vẫn lỗi vào hàng đợi lỗi | SCR-0801 | EVT-10 | ≤ 2 giờ cho 1,2 triệu hồ sơ |
| 6 | Bước 3: sinh mẫu và bảng chấm điểm | SVC-11 | Sinh biểu mẫu điền sẵn điểm và bảng chấm điểm tổng hợp theo đơn vị; tệp lưu vào kho đối tượng có mã băm | SCR-0801 | EVT-17 | Nằm trong ngân sách 4 giờ cho sinh và ký |
| 7 | Bước 4: ký số lô | SVC-10 | Ký số niêm phong các bảng tổng hợp bằng chứng thư tổ chức trong HSM, gắn dấu thời gian | SCR-0801 | EVT-18 | Như trên |
| 8 | Bước 5: hoàn tất | SVC-06, MOD-08, SVC-09 | Chuyển kỳ `DA_KHOA`; mục tiêu của kỳ chuyển `DONG`; ledger-service niêm phong Merkle cho kỳ (EVT-19); tạo hồ sơ đánh giá `CHUA_LAP`; thông báo toàn tenant | SCR-0801 | `DA_KHOA`; EVT-19; EVT-21 | ≤ 10 phút |
| 9 | Theo dõi, xử lý lỗi | AC-02 | Theo dõi tiến trình; chạy lại hồ sơ lỗi; tạm dừng sau bước hiện tại; huỷ và bù trừ toàn bộ khi cần (cần AC-09 duyệt thứ hai) | SCR-0801 | Không có | Trong khi saga chạy |
| 10 | Mở quy trình đánh giá | MOD-04 | Mở QT-09 (tự đánh giá, thẩm định, quyết định) cho các hồ sơ vừa tạo | Không có | EVT-11 khi hồ sơ chuyển bước | Ngay sau bước 5 |

Bảng sau mô tả hành động bù trừ của từng bước. Bù trừ chạy theo thứ tự ngược từ bước cao nhất đã hoàn tất. Bước 0 và bước 5 không có bù trừ: bước 0 chỉ kiểm tra, còn bước 5 là điểm không quay lại (sau bước 5, mọi thay đổi phải đi qua mở lại hậu kiểm ở QT-13).

| Bước saga | Hành động chính | Hành động bù trừ | Ghi chú |
|---|---|---|---|
| 0. Kiểm tra tiền điều kiện | Kiểm tra, liệt kê cảnh báo | Không có | Không đạt thì saga dừng, không cần bù trừ |
| 1. Khoá kỳ | `config.period` = `DANG_KHOA`, phát EVT-09 | Trả `config.period` về `MO`, phát sự kiện huỷ khoá kỳ (đề xuất), các module mở ghi lại | Cờ "không tính trong kỳ" của minh chứng được gỡ |
| 2. Tính điểm hàng loạt | Ghi lô điểm của lần chạy | Đánh dấu các lô điểm của lần chạy là bị thay thế (`superseded`), không xoá | Giữ vết để phân tích lỗi |
| 3. Sinh mẫu | Sinh tệp | Đánh dấu thu hồi các tệp đã sinh; tệp vẫn giữ trong kho chỉ ghi | Không xoá vì Object Lock |
| 4. Ký số lô | Ký các bảng tổng hợp | Huỷ các yêu cầu ký đang chờ; chữ ký đã hoàn tất không thu hồi được nên huỷ toàn bộ chỉ cho phép trước khi bước 4 hoàn tất | Sau bước 4 chỉ còn đường mở lại hậu kiểm |
| 5. Hoàn tất | `DA_KHOA`, niêm phong | Không có | Điểm không quay lại |

Điểm khó của QT-08 là bước 2. Theo NFR-06, cell lớn nhất phải tính 1,2 triệu hồ sơ trong 2 giờ, tức khoảng 10.000 hồ sơ mỗi phút. Ở tenant Đảng uỷ các cơ quan Đảng Trung ương trong ví dụ, 298.760 hồ sơ được tính với tốc độ đo được trên màn hình khoảng 5.800 hồ sơ mỗi phút, dự kiến xong sau khoảng 51 phút; tốc độ này thấp hơn mức cần cho cell lớn vì cell CELL-DTW nhỏ hơn và chạy 24 worker. Đây là số đo của kịch bản minh hoạ trên mockup, không phải số đo thực tế; năng lực thực được kiểm chứng bằng kịch bản thử tải trong TL-04. Để đạt tốc độ cần thiết, việc tính điểm không đọc lại dữ liệu nghiệp vụ qua API mà đọc từ ảnh chụp đã đóng băng theo lô, quy tắc của Policy Pack được biên dịch một lần cho cả lô, và mỗi hồ sơ là một đơn vị độc lập nên lỗi ở hồ sơ này không làm hỏng hồ sơ khác.

### Trạng thái đối tượng

Đối tượng chính là kỳ đánh giá `config.period` với trạng thái chuẩn `MO`, `DANG_KHOA`, `DA_KHOA`, `MO_LAI_HAU_KIEM`.

![Hình 9.2. Trạng thái của kỳ đánh giá config.period](nv-qt08-trang-thai.png){width=12cm}

Hình 9.2 cho thấy kỳ chỉ có hai đường ra khỏi `DANG_KHOA`: tới `DA_KHOA` khi saga hoàn tất bước 5, hoặc về `MO` khi saga bị bù trừ toàn bộ. Từ `DA_KHOA`, kỳ chỉ có thể mở lại hậu kiểm qua QT-13 với hai người duyệt, và sau khi hậu kiểm xong được khoá lại bằng chính saga của QT-08, chỉ tính lại các hồ sơ bị ảnh hưởng.

| Trạng thái hiện tại | Sự kiện | Trạng thái mới | Điều kiện | Ghi chú |
|---|---|---|---|---|
| (chưa có) | Tạo kỳ | `MO` (từ ngày bắt đầu) | Policy Pack được ghim | Kỳ tạo trước hiển thị "Đã tạo" tới ngày bắt đầu |
| `MO` | Saga bước 1 | `DANG_KHOA` | Bước 0 đạt | Chặn ghi vào kỳ; phát EVT-09 |
| `DANG_KHOA` | Bù trừ toàn bộ | `MO` | Hai người duyệt; trước khi bước 4 hoàn tất | Phát sự kiện huỷ khoá kỳ (đề xuất) |
| `DANG_KHOA` | Saga hoàn tất bước 5 | `DA_KHOA` | Mọi bước đạt | Niêm phong sổ cái |
| `DA_KHOA` | Mở lại hậu kiểm (QT-13) | `MO_LAI_HAU_KIEM` | Hai người duyệt | EVT-22; chỉ mở phạm vi được duyệt |
| `MO_LAI_HAU_KIEM` | Saga khoá lại hoàn tất | `DA_KHOA` | Như khoá lần đầu, phạm vi hẹp | Tính lại hồ sơ bị ảnh hưởng |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-08-01 | Lệnh khoá kỳ phải được AC-04 phê duyệt; người khởi chạy (AC-02) khác người phê duyệt | PL 4 V.1 (tách vai trò khoá kỳ) | Chặn |
| BR-08-02 | Khởi chạy, tạm dừng, huỷ saga yêu cầu MFA trong vòng 15 phút | BR-00-02 | Chặn, MFA bước lên |
| BR-08-03 | Không khởi chạy khi chưa qua hạn nghiệm thu tồn đọng của kỳ | Lịch kỳ trong Policy Pack | Chặn ở bước 0 |
| BR-08-04 | Không khởi chạy khi còn đề nghị gia hạn chờ duyệt hoặc lệch tổ chức mức Cao | Bảo đảm dữ liệu đúng | Chặn ở bước 0 |
| BR-08-05 | Minh chứng còn `DA_NOP` hoặc `DANG_NGHIEM_THU` lúc khoá không được tính điểm trong kỳ và được gắn cờ "không tính trong kỳ" | Chỉ `DA_XAC_NHAN` được tính (9.1) | Ghi vết, thông báo người nộp và người nghiệm thu |
| BR-08-06 | Khi kỳ `DANG_KHOA` hoặc `DA_KHOA`, mọi ghi vào dữ liệu của kỳ bị từ chối | PL 4 (khoá dữ liệu); lớp bảo vệ 5 (G3) | Chặn ở mọi module, kiểm tra trạng thái kỳ trong bộ nhớ đệm |
| BR-08-07 | Điểm được tính bằng phiên bản Policy Pack ghim cho kỳ, không dùng phiên bản phát hành sau | Tính tất định | Chặn: scoring-service chỉ đọc `policy.policy_binding` của kỳ |
| BR-08-08 | Mỗi kỳ chỉ có một saga khoá kỳ đang chạy | Tránh chạy trùng | Chặn bằng khoá idempotency theo kỳ |
| BR-08-09 | Huỷ và bù trừ toàn bộ cần hai người duyệt và chỉ cho phép trước khi bước 4 hoàn tất | PL 4 (hai người duyệt) | Chặn |
| BR-08-10 | Mọi bước saga, mọi lần thử lại và mọi hành động điều khiển được ghi sổ cái | PL 4 về truy vết | Ghi vết tự động |
| BR-08-11 | Kết quả tính điểm phải tất định: chạy lại trên cùng ảnh chụp cho cùng kết quả | Hậu kiểm, khiếu nại | Kiểm tra bằng test case vàng và chạy lại mẫu ngẫu nhiên 0,1% hồ sơ |
| BR-08-12 | Kỳ phải khoá đủ sớm để hoàn thành đánh giá và báo cáo trước ngày 10 của tháng đầu quý sau | PL 1 dự thảo Quy định mới | Cảnh báo khi lịch khoá muộn hơn tham số |

### Ngoại lệ và xử lý

| Tình huống | Cách xử lý |
|---|---|
| Một số hồ sơ lỗi khi tính điểm | Lô tự thử lại tối đa 3 lần; hồ sơ vẫn lỗi vào hàng đợi lỗi, saga tiếp tục các hồ sơ khác; AC-02 xem lỗi và chạy lại sau khi sửa nguyên nhân; bước 2 chỉ hoàn tất khi hàng đợi lỗi rỗng hoặc AC-04 chấp nhận để lại hồ sơ lỗi với lý do |
| scoring-service quá tải hoặc chậm hơn ngân sách | KEDA tăng số worker theo độ dài hàng đợi; nếu vẫn chậm, saga tiếp tục nhưng phát cảnh báo vận hành; ngân sách NFR-06 được theo dõi trên SCR-0801 |
| Dịch vụ ký số gián đoạn ở bước 4 | Bước 4 thử lại theo chính sách lùi dần trong tối đa 4 giờ; không tính lại bước 2, 3; quá thời gian thì AC-02 quyết định chờ tiếp hoặc huỷ và bù trừ |
| Phát hiện sai sót lớn khi đang ở bước 2 | AC-02 tạm dừng sau bước hiện tại, lập đề nghị huỷ; AC-09 duyệt thứ hai; saga bù trừ và kỳ trở về `MO` |
| Người dùng đang soạn khi kỳ chuyển `DANG_KHOA` | Lưu bị từ chối với MSG-08-06; dữ liệu nháp được giữ trên trình duyệt để tham khảo |
| Cell chuyển sang trung tâm dự phòng giữa saga | Trạng thái saga được lưu bền vững; sau khi chuyển, saga tiếp tục từ bước dở dang theo khoá idempotency |
| Thay đổi tổ chức ghi muộn có hiệu lực trong kỳ đã khoá | Không tự tính lại; hiển thị MSG-01-10; nếu cần thì mở lại hậu kiểm |

### Đặc tả màn hình

#### SCR-0801. Quản lý kỳ và tiến trình khoá kỳ

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Quản lý danh sách kỳ đánh giá, lệnh khoá kỳ và theo dõi, điều khiển tiến trình saga khoá kỳ theo thời gian thực |
| Tác nhân | AC-02 (điều khiển), AC-04 (phê duyệt lệnh), AC-09 (duyệt thứ hai khi huỷ), AC-10 (chỉ đọc) |
| Đường vào | Menu "Quản trị / Kỳ đánh giá" |
| Quyền | `tenant_admin` để điều khiển; `leader` để phê duyệt; `second_approver`, `inspector` để xem; phiên AAL2 |
| Thiết bị | Máy tính |

![Hình 9.3. Quản lý kỳ và tiến trình saga khoá kỳ SCR-0801](SCR-0801.png){width=16cm}

Hình 9.3 là màn hình của đồng chí Hoàng Đức Dũng, quản trị tenant Đảng uỷ các cơ quan Đảng Trung ương, lúc 07:44 ngày 04/01/2027 khi đang khoá kỳ Quý IV/2026 bằng saga "chot-ky" phiên bản 3. Vùng 1 là danh sách kỳ: Quý IV/2026 đang `DANG_KHOA`, Năm 2026 đang `MO`, Quý III/2026 đã khoá, Quý II/2026 đã khoá sau một lần mở lại hậu kiểm (12/08 đến 20/08), Quý I/2027 đã tạo và mở từ 01/01. Vùng 2 là thông tin lệnh khoá kỳ (người khởi chạy, người phê duyệt, căn cứ Thông báo 21-TB/ĐUK, Policy Pack ghim cho kỳ, mã saga) và bốn chỉ số: 298.760 hồ sơ trong kỳ, 233.040 đã tính điểm với tốc độ khoảng 5.800 hồ sơ mỗi phút, 17 lỗi chờ xử lý và thời điểm hoàn tất dự kiến 10:30 kèm ngân sách NFR-06. Vùng 3 là bảng tiến trình sáu bước saga, mỗi bước có biểu tượng trạng thái, thời gian bắt đầu và kết thúc, kết quả chi tiết và hành động bù trừ; bước 0 đạt có cảnh báo 214 minh chứng còn `DA_NOP`, bước 1 đã khoá và phát EVT-09, bước 2 đang chạy 78% với 3 lô lỗi tự thử lại, các bước 3 đến 5 đang chờ. Vùng 4 là nhật ký sự kiện của saga theo thời gian. Vùng 5 là ba nút điều khiển và lưu ý về hai người duyệt khi huỷ.

Thời điểm hoàn tất dự kiến 10:30 gồm khoảng 11 phút còn lại của bước 2 (xong khoảng 07:55), thời gian sinh mẫu và ký số lô của bước 3, 4, và thời gian dự phòng; con số được tính lại sau mỗi lô dựa trên tốc độ đo được.

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kỳ | Bản ghi | Có | Tên, từ ngày, đến ngày, trạng thái | Không có | Không chồng lấn cùng loại kỳ | `config.period.name`, `start_date`, `end_date`, `status` |
| 2 | Lịch kỳ | JSON | Có | Hạn duyệt kế hoạch, hạn nộp minh chứng, hạn nghiệm thu tồn đọng, ngày khoá dự kiến | Theo Policy Pack | BR-08-12 | `config.period.milestones` (đề xuất) |
| 3 | Policy Pack ghim | Tham chiếu | Có | Gói, phiên bản | Gói hiệu lực khi tạo kỳ | BR-08-07 | `policy.policy_binding` (`period_id`) |
| 4 | Lệnh khoá kỳ | Bản ghi | Có | Người lập, người duyệt, thời điểm, căn cứ | Không có | BR-08-01 | `config.period_lock_order` (bảng đề xuất) |
| 5 | Mã saga | Chuỗi, chỉ đọc | Có | UUIDv7 | Tự sinh | Không có | `flowable.ACT_RU_EXECUTION` qua `config.period_lock_order.saga_id` |
| 6 | Khoá idempotency | Chuỗi, chỉ đọc | Có | `CK-{năm}{kỳ}` | Tự sinh | BR-08-08 | `config.period_lock_order.idempotency_key` (đề xuất) |
| 7 | Số hồ sơ, đã tính, lỗi | Số, chỉ đọc | Có | Số nguyên | 0 | Không có | Tổng hợp từ `scoring.score_job` |
| 8 | Tốc độ, dự kiến hoàn tất | Số, thời điểm, chỉ đọc | Có | Hồ sơ/phút, hh:mm | Tính lại sau mỗi lô | Không có | Tính từ `scoring.score_job.finished_at` |
| 9 | Bước saga | Danh sách | Có | Số bước, tên, trạng thái, thời gian, kết quả, bù trừ | Không có | Chỉ đọc | `config.period_lock_step` (bảng đề xuất, chiếu từ lịch sử Flowable) |
| 10 | Lô tính điểm | Danh sách | Có | Mã lô, số hồ sơ, trạng thái, số lần thử | Không có | Chỉ đọc | `scoring.score_job` |
| 11 | Hồ sơ lỗi | Danh sách | Không | Hồ sơ, lỗi, số lần thử | Không có | Chỉ đọc | `scoring.score_job` (lỗi chi tiết), hàng đợi lỗi |
| 12 | Minh chứng không tính trong kỳ | Số, danh sách | Có | Số nguyên | 0 | BR-08-05 | `product.evidence_version.excluded_period_id` (đề xuất) |
| 13 | Nhật ký sự kiện | Danh sách | Có | Thời điểm, sự kiện | Mới nhất trước | Chỉ đọc | `ledger.ledger_entry` (lọc theo saga) |

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tạo kỳ | `tenant_admin` | Tạo kỳ mới với lịch theo Policy Pack và ghim gói | `/api/v1/periods` (POST) | Không có |
| Lịch ngày nghỉ | `tenant_admin` | Mở lịch nghỉ, làm bù | `/api/v1/config/holiday-calendars` | Không có |
| Lập lệnh khoá kỳ | Kỳ `MO`, chưa có lệnh | Tạo lệnh, gửi AC-04 | `/api/v1/periods/{id}/lock-orders` (POST) | EVT-21 |
| Phê duyệt lệnh | AC-04, khác người lập | Duyệt với MFA | `/api/v1/periods/{id}/lock-orders/{oid}/approve` (POST) | Không có |
| Khởi chạy | Lệnh đã duyệt; AC-02 khác người duyệt | Khởi tạo saga, trả 202 và địa chỉ theo dõi | `/api/v1/periods/{id}/lock` (POST, Idempotency-Key) → `/api/v1/jobs/{jobId}` | EVT-09 (ở bước 1) |
| Xem BPMN | Saga đang chạy hoặc đã chạy | Hiển thị sơ đồ tiến trình với bước hiện tại | `/api/v1/periods/{id}/lock/diagram` | Không có |
| Tạm dừng sau bước hiện tại | Saga đang chạy | Đặt cờ tạm dừng; saga dừng khi bước hiện tại xong | `/api/v1/periods/{id}/lock/pause` (POST) | Không có |
| Chạy lại hồ sơ lỗi | Có hồ sơ lỗi | Tạo lô mới cho các hồ sơ lỗi | `/api/v1/periods/{id}/lock/retry-failed` (POST, Idempotency-Key) | EVT-10 khi tính xong |
| Huỷ và bù trừ toàn bộ | Trước khi bước 4 hoàn tất | Tạo đề nghị huỷ; khi AC-09 duyệt, saga chạy bù trừ theo thứ tự ngược | `/api/v1/periods/{id}/lock/cancel` (POST), `/api/v1/periods/{id}/lock/cancel/approve` (POST) | Sự kiện huỷ khoá kỳ (đề xuất) |

| Mã | Nội dung | Khi nào |
|---|---|---|
| MSG-08-01 | Lệnh khoá kỳ {kỳ} đã được phê duyệt bởi đồng chí {tên}. Có thể khởi chạy từ {thời điểm}. | Lệnh được duyệt |
| MSG-08-02 | Chưa thể khởi chạy: {danh sách điều kiện chưa đạt}. | Bước 0 không đạt |
| MSG-08-03 | Có {n} minh chứng chưa được nghiệm thu sẽ không được tính điểm trong kỳ. Người nộp và người nghiệm thu đã được thông báo. | Bước 0 đạt có cảnh báo |
| MSG-08-04 | Bước {n} đang chậm hơn ngân sách. Dự kiến hoàn tất {giờ}. | Vượt ngân sách |
| MSG-08-05 | Đề nghị huỷ và bù trừ cần người duyệt thứ hai. Đã gửi tới {tên}. | Bấm huỷ |
| MSG-08-06 | Kỳ {kỳ} đang khoá. Dữ liệu của kỳ không thể thay đổi. | Ghi vào kỳ `DANG_KHOA` hoặc `DA_KHOA` |
| MSG-08-07 | Kỳ {kỳ} đã khoá lúc {giờ}. Hồ sơ đánh giá đã sẵn sàng cho bước tự đánh giá. | Saga hoàn tất |
| MSG-08-08 | Saga đã bù trừ toàn bộ. Kỳ {kỳ} trở về trạng thái mở. | Huỷ thành công |

### Liên kết thiết kế

QT-08 do workflow-service (SVC-06) điều phối dưới dạng saga theo mẫu `chot-ky`, với các bên tham gia là module config (MOD-08) giữ trạng thái kỳ, scoring-service (SVC-07) tính điểm, module appraisal (MOD-04) ghi ảnh chụp điểm và tạo hồ sơ, document-service (SVC-11) sinh biểu mẫu, esign-service (SVC-10) ký số, ledger-service (SVC-09) niêm phong. Bảng dữ liệu chính là `config.period`, `policy.policy_binding`, `scoring.score_job`, `scoring.score_result`, `scoring.score_trace`, `appraisal.score_snapshot`, `appraisal.appraisal_profile`, `docgen.render_job`, `docgen.render_output`, `esign.sign_request`, `esign.sign_item`, `ledger.merkle_seal`, `flowable.wf_template`, `flowable.wf_binding`. Sự kiện phát ra là KyDaKhoa (EVT-09), DiemDaTinh (EVT-10), HoSoSoDaSinh (EVT-17), KySoDaHoanTat (EVT-18), SoCaiDaNiemPhong (EVT-19), YeuCauThongBao (EVT-21); khi khoá lại sau hậu kiểm, saga tiêu thụ KyDaMoLaiHauKiem (EVT-22). Tài liệu đề xuất thêm bảng `config.period_lock_order`, `config.period_lock_step`, cột `milestones`, `plan_deadline` cho `config.period`, cột `excluded_period_id` cho `product.evidence_version` và một sự kiện huỷ khoá kỳ (xem tệp ghi chú). Kiến trúc saga, cơ chế co giãn worker và ngân sách hiệu năng của NFR-06 được trình bày trong TL-01; kịch bản thử tải đỉnh cuối kỳ, quy trình vận hành khoá kỳ và kịch bản khôi phục khi chuyển trung tâm dữ liệu giữa saga được đặc tả trong TL-04.


# Quy trình đánh giá, xếp loại và hồ sơ số

Chương này đặc tả ba quy trình tạo ra kết quả có giá trị pháp lý của nền tảng: đánh giá và xếp loại theo quý (QT-09), xếp loại năm cùng phân nhóm triển vọng (QT-10), kiểm điểm và lập hồ sơ số (QT-11). Ba quy trình này nhận đầu vào từ các quy trình ở chương trước, cụ thể là mục tiêu O và KR đã duyệt (QT-02), kế hoạch quý đã duyệt (QT-04), nhiệm vụ và tiến độ (QT-05, QT-06), minh chứng đã nghiệm thu (QT-07) và sự kiện khoá kỳ (QT-08). Đầu ra của chúng là phiếu xếp loại, quyết định có ký số và gói hồ sơ quý, năm được lưu trữ lâu dài.

Điểm chung của cả ba quy trình là nguyên tắc "hệ thống tính, con người quyết định". Điểm số và mức đề xuất do scoring-service (SVC-07) tính theo Policy Pack đang gắn với kỳ, không ai sửa tay được. Ngược lại, mức thẩm định, mức đề xuất và mức quyết định vẫn là ý chí của người có thẩm quyền, được lưu riêng và phải có giải trình khi khác với mức trước đó. Cách tách này đáp ứng đồng thời yêu cầu của dự thảo Quy định mới (QĐ Điều 16 về lưu bốn mức) và tiêu chí I6 của bản đánh giá kiến trúc (kết quả tính lại được bất kỳ lúc nào).

Về số liệu minh hoạ, toàn bộ mockup và ví dụ trong chương dùng chung một hồ sơ mẫu để người đọc đối chiếu: đồng chí Trần Minh Hoàng, Trưởng phòng Tổng hợp, Ban Tổ chức Đảng uỷ khối, thuộc tenant "Đảng uỷ các cơ quan Đảng TW" (CELL-DTW), kỳ Quý IV/2026. Hồ sơ có bốn nhiệm vụ then chốt (KPI nhóm 0,908, quy ra 63,6 trên 70 điểm) và năm nhiệm vụ thường xuyên (KPI nhóm 0,920, quy ra 27,6 trên 30 điểm), tổng 91,2 điểm. Tỷ lệ nhiệm vụ vượt mục tiêu là 2/9, tức 22%, thấp hơn ngưỡng 30% nên hệ thống hạ mức từ "Hoàn thành xuất sắc nhiệm vụ" xuống "Hoàn thành tốt nhiệm vụ". Ví dụ này được dùng xuyên suốt từ QT-09 tới QT-13 và cả ở bảng điều hành của QT-16.

## QT-09. Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-09 |
| Tên | Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý |
| Mục đích | Lập hồ sơ đánh giá quý cho từng cán bộ, tính điểm theo Policy Pack, thu nhận lần lượt bốn mức (tự đề xuất, thẩm định, đề xuất, quyết định) có giải trình và ký số, ra quyết định xếp loại hàng loạt |
| Phạm vi áp dụng | Luồng Đảng (100 điểm) cho cán bộ lãnh đạo, quản lý và đảng viên thuộc diện đánh giá quý; luồng Nhà nước (30/70) cho công chức, viên chức khi tenant bật; cả hai luồng dùng chung dữ liệu sản phẩm, minh chứng |
| Căn cứ | Dự thảo Quy định mới Điều 10, Điều 11 (tiêu chí chung, điều kiện các mức), khoản 11.2.4 (điều kiện loại trừ), khoản 14.3.3 (người đứng đầu), Điều 16 khoản 16.3 (lưu bốn mức, giải trình); PL 3 (A, B, C); PL 6 §12 (chuyển mức liền kề); Mẫu 2, Mẫu 3; NĐ 335/2025 cho luồng Nhà nước; Luật Giao dịch điện tử 2023 (ký số) |
| Tác nhân | AC-06 cán bộ được đánh giá; AC-05 quản lý trực tiếp (đề xuất); AC-07 cơ quan tổ chức (thẩm định); AC-08 cấp có thẩm quyền (quyết định); SVC-07 scoring-service; SVC-06 workflow-service; SVC-10 esign-service |
| Tần suất và khối lượng ước tính | Mỗi quý một lần. Ước tính theo baseline mục 3.1: khoảng 6 triệu hồ sơ quý toàn quốc khi mọi tenant dùng, cell lớn nhất khoảng 1,2 triệu hồ sơ; mỗi hồ sơ khoảng 4 user task (tự đánh giá, thẩm định, đề xuất, quyết định), 30% dồn vào 3 ngày cuối, đỉnh khoảng 80 đến 110 user task mỗi giây ở cell lớn (số ước tính) |
| Điều kiện bắt đầu | Kỳ quý ở trạng thái `DA_KHOA` sau saga chốt kỳ (QT-08, EVT-09) và scoring-service đã phát EVT-10 cho hồ sơ |
| Điều kiện kết thúc | Hồ sơ đạt `DA_KY_SO` (quyết định đã ký) và được khoá `DA_KHOA` khi hết hạn khiếu nại hoặc khi gói hồ sơ được ký (QT-11) |
| Đầu vào | Kế hoạch quý đã duyệt, nhiệm vụ và kết quả nghiệm thu (`product.acceptance`), minh chứng `DA_XAC_NHAN`, mục tiêu O và KR, lịch ngày nghỉ của tenant, Policy Pack gắn với kỳ |
| Đầu ra | `appraisal.score_snapshot`, `appraisal.rating_proposal` (bốn mức), `appraisal.rating_explanation`, quyết định xếp loại có số và ký số, sự kiện EVT-11, EVT-12 |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ hồ sơ hoàn tất đúng hạn (mục tiêu ≥ 98%); thời gian trung bình từ khoá kỳ tới quyết định (mục tiêu ≤ 10 ngày làm việc); tỷ lệ hồ sơ có khác mức giữa tự đề xuất và quyết định; tỷ lệ khiếu nại trên số hồ sơ; thời gian ký lô 100.000 phiếu ≤ 4 giờ (NFR-06) |

Trong hệ thống hiện tại, theo bản đánh giá kiến trúc (tiêu chí H4), công thức tính đúng nhưng tồn tại ba bộ máy tính song song, chỉ có kỳ quý và chỉ lưu một mức xếp loại cuối cùng. Hệ quả là khi có khiếu nại, không ai trả lời được câu hỏi "ai đã đề xuất mức nào, vì sao khác". QT-09 giải quyết điều đó bằng cách tách hai phần việc. Phần tính toán do scoring-service thực hiện một lần cho mỗi hồ sơ và lưu thành ảnh chụp điểm (snapshot) có mã băm đầu vào và phiên bản Policy Pack. Phần ý chí của con người được lưu thành bốn bản ghi riêng trong `appraisal.rating_proposal`, mỗi bản ghi có người thực hiện, thời điểm, lý do và chữ ký số.

Quy trình chạy trên workflow-service (SVC-06, Flowable 7) bằng mẫu luồng `duyet-ho-so-danh-gia` phiên bản 3, được tham số hoá theo tenant và khối qua bảng `flowable.wf_binding` (xem QT-15, SCR-1504). Nhờ vậy, Đảng uỷ khối dùng bốn cấp duyệt còn một xã chỉ dùng hai cấp mà không phải nhân bản định nghĩa BPMN. Đây là biện pháp chính của ADR-04 để tránh "mạng nhện" luồng mà góp ý G2 lo ngại.

### Lưu đồ quy trình

![Hình 10.1. Lưu đồ làn bơi QT-09: tự đánh giá, thẩm định, đề xuất và quyết định xếp loại quý](nv-b-qt09-luong.png){width=16cm}

Lưu đồ gồm bốn làn. Làn "Hệ thống" khởi đầu khi nhận sự kiện KyDaKhoa (EVT-09): MOD-04 lập hồ sơ ở trạng thái `CHUA_LAP`, scoring-service tính điểm cho cả luồng Đảng và luồng Nhà nước rồi lưu ảnh chụp điểm, sau đó bảng quyết định DMN xếp loại sinh mức đề xuất của hệ thống kèm câu giải thích. Chỉ khi có ảnh chụp điểm, hồ sơ mới chuyển sang `TU_DANH_GIA` và cán bộ mới mở được phiếu. Ở làn "Cán bộ", người được đánh giá đánh dấu tiêu chí chung, chọn mức tự đề xuất, ghi giải trình rồi ký số và nộp. Làn "Cơ quan tổ chức" thẩm định, so sánh với tự đánh giá; nếu mức thẩm định khác mức tự đề xuất thì bắt buộc ghi giải trình trước khi ký. Thẩm định viên có thể trả lại để cán bộ bổ sung. Làn cuối là quản lý trực tiếp và cấp quyết định: đề xuất mức, quyết định hàng loạt và ký lô. Khi ký lô hoàn tất, esign-service phát EVT-18 và MOD-04 phát EVT-12 XepLoaiDaQuyetDinh để MOD-06 lập hồ sơ số và ledger-service ghi sổ cái.

Lưu đồ cố ý không có nhánh "sửa điểm". Điểm chỉ thay đổi khi dữ liệu đầu vào thay đổi qua một quy trình có kiểm soát: trước khi khoá kỳ là QT-07 (nghiệm thu lại), sau khi khoá kỳ là QT-13 (mở lại kỳ hai người duyệt).

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Lập hồ sơ đánh giá | Hệ thống (MOD-04) | Khi nhận EVT-09, tạo một `appraisal.appraisal_profile` cho mỗi người thuộc diện đánh giá của kỳ, theo luồng (`DANG`, `NHA_NUOC`) mà tenant bật; áp 13 trường hợp đặc thù PL 6 nếu phát hiện | Không có | Hồ sơ `CHUA_LAP` | Trong 15 phút sau khoá kỳ |
| 2 | Tính điểm | SVC-07 | Đọc kết quả nghiệm thu, minh chứng `DA_XAC_NHAN`, lịch ngày nghỉ; tính A, B, C, KPI nhiệm vụ, KPI nhóm, điểm; chạy bảng DMN xếp loại; lưu snapshot có `policy_version` và `input_hash` | Không có | `scoring.score_result`, `appraisal.score_snapshot`; EVT-10 | Cell lớn ≤ 2 giờ (NFR-06) |
| 3 | Mở phiếu tự đánh giá | AC-06 | Hồ sơ chuyển `TU_DANH_GIA`; cán bộ nhận thông báo; mở phiếu thấy điểm hệ thống tính, mức tham khảo và câu giải thích | SCR-0901 | EVT-11, EVT-21 | Hạn nộp do tham số kỳ, mặc định 3 ngày làm việc |
| 4 | Đánh dấu tiêu chí chung | AC-06 | Đánh dấu từng tiêu chí đáp ứng hay không; hệ thống quy đổi tỷ lệ ra 4 mức | SCR-0901 | `appraisal.general_criteria_result` | Trong hạn bước 3 |
| 5 | Tự đề xuất mức, giải trình | AC-06 | Chọn mức tự đề xuất; nếu khác mức tham khảo thì ghi giải trình; nếu đề xuất HTXSNV thì mô tả giải pháp đột phá kèm minh chứng | SCR-0901 | `appraisal.rating_proposal` (TU_DE_XUAT), `appraisal.self_assessment` | Trong hạn bước 3 |
| 6 | Ký số và nộp | AC-06, SVC-10 | Sinh bản xem trước Mẫu 1B hoặc Mẫu 2, ký số qua MySign hoặc Viettel-CA; hồ sơ chuyển `CHO_THAM_DINH` | SCR-0901 | EVT-18, EVT-11 | Trong hạn bước 3 |
| 7 | Thẩm định | AC-07 | Xem hàng đợi; so sánh tự đánh giá với dữ liệu nghiệm thu; đọc gợi ý bất thường; chọn mức thẩm định, ghi nhận xét | SCR-0902 | `appraisal.rating_proposal` (THAM_DINH) | 3 ngày làm việc |
| 8 | Giải trình khác mức | AC-07 | Nếu mức thẩm định khác mức tự đề xuất thì bắt buộc ghi lý do trước khi ký | SCR-0902 | `appraisal.rating_proposal.reason_enc` | Trong bước 7 |
| 9 | Ký thẩm định hoặc trả lại | AC-07 | Ký số chuyển `DA_THAM_DINH`; hoặc trả lại bổ sung (hồ sơ về `TU_DANH_GIA`, giữ nguyên các bản ghi cũ) | SCR-0902 | EVT-18, EVT-11 | Trong bước 7 |
| 10 | Đề xuất mức | AC-05 hoặc tập thể lãnh đạo | Xem phiếu xếp loại có giải thích; chọn mức đề xuất; bước này có thể được bỏ qua theo tham số mẫu luồng | SCR-0903 | `appraisal.rating_proposal` (DE_XUAT); `CHO_QUYET_DINH` | 2 ngày làm việc |
| 11 | Quyết định hàng loạt | AC-08 | Xem danh sách theo đơn vị; cột quyết định mặc định bằng mức đề xuất; đổi mức thì ghi lý do; xem phân bố mức | SCR-0904 | `appraisal.rating_proposal` (QUYET_DINH) | 3 ngày làm việc |
| 12 | Ký lô quyết định | AC-08, SVC-10 | Nhập số, ngày quyết định; một lần xác thực cho cả lô; mỗi hồ sơ một chữ ký PAdES riêng; tiến độ hiển thị trực tiếp | SCR-0904 | `appraisal.batch_decision`; EVT-18; hồ sơ `DA_KY_SO`; EVT-12 | Lô 100.000 phiếu ≤ 4 giờ |
| 13 | Công bố và thông báo | Hệ thống | Gửi kết quả cho cán bộ; mở thời hạn khiếu nại (mặc định 10 ngày làm việc) | SCR-0903 | EVT-21 | Ngay sau bước 12 |
| 14 | Khoá hồ sơ | Hệ thống | Hết hạn khiếu nại mà không có khiếu nại, hoặc gói hồ sơ được ký (QT-11), hồ sơ chuyển `DA_KHOA` | Không có | EVT-11; ghi sổ cái | Theo tham số |

Các bước 1 và 2 hoàn toàn tự động và chạy dưới dạng tác vụ nền. scoring-service tiêu thụ EVT-09 theo phân vùng `tenantId`, co giãn bằng KEDA theo độ trễ consumer, nên một cell 1,2 triệu hồ sơ được tính trong khoảng thời gian NFR-06 cho phép mà không ảnh hưởng tới các API trực tuyến. Kết quả mỗi hồ sơ được ghi một lần vào `appraisal.score_snapshot`; nếu cùng đầu vào được tính lại (ví dụ do consumer xử lý lặp), `input_hash` trùng nên bảng inbox bỏ qua, bảo đảm tính luỹ đẳng.

Các bước 3 đến 12 là user task của Flowable. Danh sách việc mà người dùng thấy trên SCR-0002 và các màn hình hàng đợi không truy vấn trực tiếp bảng `ACT_RU_TASK` mà đọc bảng chiếu `flowable.wf_task_projection`, được cập nhật bằng sự kiện. Cách này giữ cho engine chỉ phải xử lý thao tác hoàn thành task (khoảng 80 đến 110 lần mỗi giây ở cell lớn), còn tải đọc danh sách việc dồn về PostgreSQL bản sao đọc.

Thời hạn từng bước là tham số của mẫu luồng, không viết cứng. Bảng trên ghi giá trị mặc định của khối Đảng. Khi quá hạn, mẫu luồng tự chuyển cho cấp phó được uỷ quyền hoặc nhắc lại theo tham số "Quá hạn" ở SCR-1504.

### Trạng thái đối tượng

![Hình 10.2. Sơ đồ trạng thái hồ sơ đánh giá appraisal.appraisal_profile](nv-b-qt09-trangthai.png){width=14cm}

Sơ đồ bám đúng baseline mục 9.1. Hồ sơ đi tuần tự từ `CHUA_LAP` tới `DA_KHOA`, có hai vòng quay lui là "trả lại bổ sung" (từ `CHO_THAM_DINH` về `TU_DANH_GIA`) và "yêu cầu xem lại" (từ `CHO_QUYET_DINH` về `DA_THAM_DINH`). Nhánh `KHIEU_NAI` chỉ mở sau khi đã ký quyết định; trong thời gian khiếu nại, kết quả vẫn có hiệu lực và hồ sơ không thể bị khoá.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | EVT-09 KyDaKhoa | `CHUA_LAP` | Người thuộc diện đánh giá của kỳ theo `org_replica.assignment_v` tại ngày khoá kỳ |
| `CHUA_LAP` | EVT-10 DiemDaTinh | `TU_DANH_GIA` | Có snapshot hợp lệ cho mọi luồng mà tenant bật |
| `TU_DANH_GIA` | Ký số và nộp | `CHO_THAM_DINH` | Đủ trường bắt buộc; có giải trình nếu tự đề xuất khác mức tham khảo; chữ ký hợp lệ |
| `CHO_THAM_DINH` | Trả lại bổ sung | `TU_DANH_GIA` | Có lý do trả lại; số lần trả lại ≤ 2 (tham số) |
| `CHO_THAM_DINH` | Ký số thẩm định | `DA_THAM_DINH` | Người thẩm định khác người được đánh giá; có giải trình nếu khác mức |
| `DA_THAM_DINH` | Đề xuất mức hoặc bỏ qua bước | `CHO_QUYET_DINH` | Theo điều kiện bỏ qua cấp 3 trong `flowable.wf_binding` |
| `CHO_QUYET_DINH` | Yêu cầu xem lại | `DA_THAM_DINH` | Có lý do |
| `CHO_QUYET_DINH` | Quyết định | `DA_QUYET_DINH` | Người quyết định có vai trò `decider` với đơn vị; không phải người thẩm định |
| `DA_QUYET_DINH` | EVT-18 KySoDaHoanTat | `DA_KY_SO` | Chữ ký hợp lệ, dấu thời gian RFC 3161 |
| `DA_KY_SO` | EVT-25 KhieuNaiDaGui | `KHIEU_NAI` | Trong thời hạn khiếu nại |
| `KHIEU_NAI` | Kết luận giữ nguyên | `DA_KY_SO` | Có văn bản trả lời đã ký |
| `KHIEU_NAI` | Kết luận có điều chỉnh | `DA_KHOA` | Điều chỉnh thi hành qua QT-13 |
| `DA_KY_SO` | Hết hạn khiếu nại hoặc khoá gói | `DA_KHOA` | Không còn khiếu nại mở |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-09-01 | KPI của một nhiệm vụ bằng (A + B + C) / 3, trong đó A = min(số lượng sản phẩm được duyệt / số lượng kế hoạch, 1), B là mức chất lượng nghiệm thu theo 5 mức 1 / 0,75 / 0,5 / 0,25 / 0, C = max(0, 1 − 0,05 × số ngày làm việc chậm do chủ quan) | PL 3 mục 1, 2, 3 | Công thức CEL trong Policy Pack, chạy trên scoring-service bằng cel-java; người dùng không sửa được; mỗi thành phần có vết tính trong `scoring.score_trace` |
| BR-09-02 | Chỉ sản phẩm có minh chứng ở trạng thái `DA_XAC_NHAN` được đếm vào A; minh chứng bị cảnh báo trùng mà chưa xác nhận hợp lệ không được đếm | PL 4 III.5 | scoring-service lọc theo `product.evidence_version.status` và cờ chặn của `oversight.anomaly_alert` |
| BR-09-03 | Điểm kết quả nhiệm vụ = Σ (KPI nhóm × điểm tối đa nhóm); KPI nhóm là trung bình KPI các nhiệm vụ trong nhóm; tổng điểm tối đa các nhóm bằng 100; làm tròn 1 chữ số thập phân, nửa lên | PL A3; Bảng chấm điểm | Tham số nhóm và quy tắc làm tròn trong Policy Pack; kiểm tra tổng 100 khi soạn (SCR-1501) |
| BR-09-04 | Phần vượt mục tiêu không cộng thêm điểm (A tối đa bằng 1) mà được ghi nhận riêng thành tỷ lệ nhiệm vụ vượt mục tiêu | PL A3 | Trường `ty_le_vuot` trong snapshot, dùng làm đầu vào bảng DMN |
| BR-09-05 | Tiêu chí chung không chấm điểm; tỷ lệ đáp ứng 100% là "Thực hiện tốt", từ 90% đến dưới 100% là "Đảm bảo", từ 60% đến dưới 90% là "Cơ bản đảm bảo", dưới 60% là "Không đảm bảo" | QĐ Điều 10; PL A3 | Quy đổi tự động trên SCR-0901 và trong DMN; nhãn hiển thị theo biểu mẫu (PL A4) nhưng cùng một dữ liệu gốc |
| BR-09-06 | Mức xếp loại cuối cùng là mức thấp hơn giữa mức theo kết quả nhiệm vụ và mức theo tiêu chí chung | QĐ Điều 11; PL 6 §12 | Hàm `min_muc` trong bảng DMN (SCR-1502) |
| BR-09-07 | HTXSNV khi đủ đồng thời: ≥ 90 điểm, hoàn thành 100% mục tiêu O, ≥ 30% nhiệm vụ vượt mục tiêu, có giải pháp đột phá được kiểm chứng, tiêu chí chung "Thực hiện tốt" | QĐ Điều 11 | Dòng 4 bảng DMN; ngưỡng 30% và cách đếm (theo số nhiệm vụ) là tham số theo PL A4 |
| BR-09-08 | HTTNV khi ≥ 80 điểm, không có nhiệm vụ then chốt nào không hoàn thành do chủ quan, tiêu chí chung từ "Đảm bảo" trở lên | QĐ Điều 11 | Dòng 5 bảng DMN |
| BR-09-09 | HTNV khi từ 60 đến dưới 80 điểm và tiêu chí chung từ "Cơ bản đảm bảo" trở lên; KHTNV khi dưới 60 điểm, hoặc tiêu chí chung "Không đảm bảo", hoặc rơi vào điều kiện loại trừ | QĐ Điều 11, khoản 11.2.4 | Dòng 1, 2, 3, 6 bảng DMN; hit policy FIRST |
| BR-09-10 | Hồ sơ đủ điểm nhưng thiếu điều kiện của mức đề xuất thì chuyển xuống mức liền kề, và hệ thống phải sinh câu giải thích nêu điều kiện thiếu, giá trị thực tế, số điều khoản và phiên bản Policy Pack | PL 6 §12; PL A3 | Mẫu câu giải thích gắn với từng dòng DMN; lưu `appraisal.rating_explanation`; hiển thị ở SCR-0903 |
| BR-09-11 | Luồng Nhà nước dùng thang 30 điểm tiêu chí chung và 70 điểm KPI, ngưỡng ≥ 90, 70 đến dưới 90, 50 đến dưới 70, dưới 50; tính trên cùng dữ liệu sản phẩm với luồng Đảng nhưng lưu kết quả riêng | NĐ 335/2025; PL A5 | Hai snapshot, mỗi snapshot một `policy_version`; không ghi đè nhau |
| BR-09-12 | Lưu riêng bốn mức tự đề xuất, thẩm định, đề xuất, quyết định; mức sau khác mức trước liền kề thì người chọn mức sau phải giải trình | QĐ khoản 16.3; Mẫu 3 | Bốn bản ghi `appraisal.rating_proposal` theo `level_kind`; API từ chối chuyển bước khi thiếu `reason` (lỗi 422) |
| BR-09-13 | Người đứng đầu bị xếp KHTNV khi tập thể không đạt 05 chỉ tiêu được giao, hoặc không đạt tất cả mục tiêu O, hoặc trên 50% đơn vị do mình phụ trách bị xếp KHTNV | QĐ khoản 14.3.3 | Quy tắc riêng trong DMN, đọc kết quả tập thể và tỷ lệ đơn vị KHTNV; hồ sơ người đứng đầu chỉ quyết định sau khi các đơn vị trực thuộc đã có mức |
| BR-09-14 | Tách vai trò: với cùng một hồ sơ, người được đánh giá, người nghiệm thu, người thẩm định, người quyết định phải là những người khác nhau | PL 4 V.1 | Chính sách OPA kiểm tra khi giao task và khi gọi API ghi |
| BR-09-15 | Ký số tại các mốc: phiếu tự đánh giá, kết quả thẩm định, quyết định xếp loại | Luật GDĐT 2023; tiêu chí I4 | Bước chuyển trạng thái chỉ hoàn tất khi nhận EVT-18 tương ứng |
| BR-09-16 | Điểm chỉ do hệ thống tính; không có chức năng sửa điểm trực tiếp | PL 4 III.2; tiêu chí I6 | Không có API ghi `score_snapshot` từ giao diện; mọi thay đổi đầu vào phải qua QT-07 hoặc QT-13 |
| BR-09-17 | Tỷ lệ HTXSNV của một đơn vị vượt ngưỡng tham số của khối (mặc định 20%) thì cảnh báo và đề nghị phiên hiệu chỉnh trước khi ký lô | Dự thảo về tỷ lệ xuất sắc; PL 4 V.6 | Kiểm tra trên SCR-0904; đồng thời job dấu hiệu số 2 ở QT-14 |

Các quy tắc BR-09-01 đến BR-09-11 thuộc Policy Pack, nghĩa là có phiên bản, có test case vàng và có thể khác nhau giữa các khối. Các quy tắc BR-09-12 đến BR-09-16 là quy tắc nền tảng, viết bằng mã trong MOD-04 và chính sách OPA, không cấu hình được, vì chúng bảo đảm tính toàn vẹn và khả năng truy vết mà mọi tenant đều phải tuân thủ.

Đối với hồ sơ mẫu, quy tắc BR-09-10 sinh câu giải thích: "Đủ điểm mức HTXSNV (91,2 ≥ 90) nhưng chưa đạt điều kiện 30% nhiệm vụ vượt mục tiêu (thực tế 22%, theo QĐ Điều 11), nên chuyển xuống mức liền kề theo PL 6 §12. Policy Pack Đảng v2.0." Câu này không do người viết mà được ghép từ mẫu câu của dòng DMN thứ 5 với giá trị thực tế, nhờ đó mọi hồ sơ bị hạ mức đều có lời giải thích cùng cấu trúc.

### Ngoại lệ và xử lý

Trường hợp thứ nhất là hồ sơ chưa có snapshot do scoring-service lỗi hoặc dữ liệu đầu vào thiếu (ví dụ nhiệm vụ chưa nghiệm thu khi khoá kỳ). Hồ sơ giữ ở `CHUA_LAP`, job tính điểm ghi lỗi vào `scoring.score_job` và đưa vào hàng đợi thử lại; sau 3 lần thất bại, hệ thống tạo việc cho cơ quan tổ chức trên SCR-0801 để xử lý thủ công. Cán bộ thấy thông báo MSG-09-01 thay vì phiếu trống.

Trường hợp thứ hai là cán bộ thuộc một trong 13 trường hợp đặc thù của PL 6, chẳng hạn nghỉ thai sản quá nửa kỳ. MOD-04 đọc cờ `dossier.special_case` khi lập hồ sơ và áp cách xử lý của Policy Pack: không lập hồ sơ quý, hoặc lập hồ sơ nhưng đánh dấu "không xếp loại", hoặc giao cho đơn vị cũ đánh giá và đơn vị mới cho ý kiến. Chi tiết trình bày ở QT-11.

Trường hợp thứ ba là cán bộ không nộp phiếu đúng hạn. Mẫu luồng tự nhắc trước 1 ngày; quá hạn, hồ sơ vẫn chuyển sang thẩm định với cờ "không tự đánh giá", mức tự đề xuất để trống và được ghi rõ trên Mẫu 3. Hệ thống không tự điền mức thay cán bộ.

Trường hợp thứ tư là ký số thất bại (chứng thư hết hạn, dịch vụ ký từ xa không phản hồi). Trạng thái hồ sơ không chuyển; esign-service thử lại theo cơ chế lùi dần, và nếu là ký lô thì các hồ sơ đã ký giữ nguyên `DA_KY_SO`, hồ sơ chưa ký vẫn ở `CHO_QUYET_DINH`. Người ký có thể tiếp tục lô sau khi khắc phục mà không phải ký lại các hồ sơ đã xong.

Trường hợp thứ năm là có xung đột cập nhật đồng thời, ví dụ hai thẩm định viên cùng mở một hồ sơ. API cập nhật yêu cầu `If-Match` theo `row_version`; người lưu sau nhận lỗi 409 và MSG-09-07, phải tải lại để xem thay đổi của người kia.

Trường hợp thứ sáu là thay đổi tổ chức trong kỳ, chẳng hạn cán bộ được điều động sau ngày khoá kỳ. Hồ sơ vẫn thuộc đơn vị tại ngày khoá kỳ, vì MOD-04 đọc `org_replica.assignment_v` theo hai trục thời gian (tiêu chí I7); người thẩm định là cơ quan tổ chức của đơn vị cũ.

### Đặc tả màn hình

#### SCR-0901. Phiếu tự đánh giá

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cán bộ xem kết quả hệ thống tính, đánh dấu tiêu chí chung, tự đề xuất mức, ghi giải trình và ký số nộp phiếu |
| Tác nhân | AC-06 (`member`) |
| Đường vào | Trang chủ "Việc của tôi hôm nay" (SCR-0002), thẻ "Tự đánh giá quý"; menu Đánh giá › Phiếu tự đánh giá; thông báo đẩy |
| Quyền | Chỉ chủ hồ sơ được ghi khi hồ sơ ở `TU_DANH_GIA`; quản lý trực tiếp xem chỉ đọc |
| Thiết bị | Web desktop, máy tính bảng; trên di động dùng luồng rút gọn (SCR-1803 khi bật chế độ cao tuổi) |

![Hình 10.3. SCR-0901 Phiếu tự đánh giá quý IV/2026 của hồ sơ mẫu](SCR-0901.png){width=16cm}

Màn hình chia ba cột theo thứ tự công việc của người dùng: đọc tiêu chí chung, xem kết quả nhiệm vụ, rồi quyết định mức tự đề xuất. Thanh bước phía trên cho biết hồ sơ đang ở bước 1/5 và các thẻ luồng cho phép chuyển giữa luồng Đảng và luồng Nhà nước mà không rời màn hình.

Vùng 1 là các thẻ luồng. Thẻ "Luồng Đảng · 100 điểm" ghi rõ Policy Pack Đảng v2.0, thẻ "Luồng Nhà nước · 30/70" ghi Policy Pack NĐ 335 v1.3. Hai thẻ hiển thị cùng danh sách nhiệm vụ nhưng khác cách quy đổi điểm. Vùng 2 là danh sách 9 tiêu chí chung dạng hộp đánh dấu; hệ thống tính tỷ lệ đáp ứng và quy đổi mức tức thời, kèm dòng nhắc quy đổi 4 mức. Vùng 3 là bảng kết quả nhiệm vụ do hệ thống tính, có nhãn "Hệ thống tính · không sửa tay" và liên kết "Vết tính" mở chi tiết A, B, C của từng nhiệm vụ. Vùng 4 là khối tự đề xuất mức: hộp chọn mức, cảnh báo khi mức tự đề xuất cao hơn mức tham khảo, ô giải pháp đột phá, ô giải trình và ô ưu điểm, hạn chế. Vùng 5 là nhóm nút ở góc phải trên, trong đó "Ký số và nộp" chỉ bật khi đủ trường bắt buộc.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Luồng đánh giá | Thẻ chọn | Có | `DANG` hoặc `NHA_NUOC` | `DANG` | Chỉ hiện luồng tenant bật | `appraisal.appraisal_profile.flow` |
| 2 | Tiêu chí chung (9 dòng) | Hộp đánh dấu | Có | Boolean mỗi tiêu chí | Đáp ứng | Danh mục tiêu chí theo Policy Pack và khối đối tượng | `appraisal.general_criteria_result.met` |
| 3 | Tỷ lệ đáp ứng | Số, chỉ đọc | Không áp dụng | Phần trăm, 0 chữ số thập phân | Tính tự động | Bằng số tiêu chí đáp ứng / tổng | Tính trên client bằng cel-js, kiểm lại ở máy chủ |
| 4 | Mức tiêu chí chung | Nhãn, chỉ đọc | Không áp dụng | 4 giá trị | Tính tự động | Theo BR-09-05 | `appraisal.general_criteria_result.level` |
| 5 | Bảng nhiệm vụ (tên, SL duyệt/KH, B, C, KPI) | Bảng, chỉ đọc | Không áp dụng | Số 2 hoặc 3 chữ số thập phân | Từ snapshot | Không sửa được | `appraisal.score_snapshot.detail_json`, `scoring.score_trace` |
| 6 | KPI nhóm và điểm nhóm | Số, chỉ đọc | Không áp dụng | 3 chữ số (KPI), 1 chữ số (điểm) | Từ snapshot | Theo BR-09-03 | `appraisal.score_snapshot.group_scores_json` |
| 7 | Điểm kết quả nhiệm vụ | Số, chỉ đọc | Không áp dụng | 0,0 đến 100,0 | Từ snapshot | Theo BR-09-03 | `appraisal.score_snapshot.total_score` |
| 8 | Mức hệ thống tham khảo | Nhãn | Không áp dụng | HTXSNV, HTTNV, HTNV, KHTNV | Từ DMN | Theo BR-09-06 đến BR-09-10 | `appraisal.rating_explanation.system_level` |
| 9 | Mức tự đề xuất | Danh sách chọn | Có | 4 giá trị | Bằng mức tham khảo | Khác mức tham khảo thì bật trường 11 bắt buộc | `appraisal.rating_proposal.level` (level_kind = TU_DE_XUAT) |
| 10 | Giải pháp đột phá | Văn bản dài | Có khi chọn HTXSNV | ≤ 2.000 ký tự, kèm ít nhất 1 minh chứng | Trống | Phải gắn minh chứng `DA_XAC_NHAN` hoặc văn bản ghi nhận | `appraisal.self_assessment.breakthrough_enc` |
| 11 | Giải trình khác mức tham khảo | Văn bản dài | Có khi trường 9 khác trường 8 | 20 đến 2.000 ký tự | Trống | Không được để trống hoặc dưới 20 ký tự | `appraisal.rating_proposal.reason_enc` |
| 12 | Ưu điểm, hạn chế, hướng khắc phục | Văn bản dài | Có | ≤ 2.000 ký tự, C2 | Trống | Mã hoá trường, tự lưu nháp 30 giây một lần | `appraisal.self_assessment.content_enc` |
| 13 | Hạn nộp, số ngày làm việc còn lại | Nhãn | Không áp dụng | dd/mm/yyyy | Từ tham số kỳ | Tính theo `config.holiday_calendar` | `config.period.self_assess_due` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Xem trước Mẫu 1B | Luôn hiện | Sinh bản xem trước từ dữ liệu hiện tại, có dấu "Bản nháp" | `POST /api/v1/dossiers/forms/preview` | Không có |
| Lưu nháp | Hồ sơ ở `TU_DANH_GIA` | Lưu trường 2, 9 đến 12; kiểm tra `If-Match` | `PUT /api/v1/appraisals/profiles/{id}/self-assessment` | Không có |
| Vết tính | Luôn hiện | Mở ngăn bên hiển thị A, B, C và nguồn dữ liệu của từng nhiệm vụ | `GET /api/v1/appraisals/profiles/{id}/score-trace` | Không có |
| Chuyển thẻ luồng | Tenant bật hai luồng | Tải snapshot của luồng còn lại | `GET /api/v1/appraisals/profiles/{id}/scores?flow=NHA_NUOC` | Không có |
| Ký số và nộp | Đủ trường bắt buộc | Kiểm tra quy tắc, tạo yêu cầu ký, chờ EVT-18 rồi chuyển `CHO_THAM_DINH` | `POST /api/v1/appraisals/profiles/{id}/submit` (202), `POST /api/v1/esign/requests` | EVT-11, EVT-18 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-01 | Kết quả quý của đồng chí đang được tính, vui lòng quay lại sau. Thời điểm dự kiến hoàn tất: {thoi_diem}. | Thông tin | Hồ sơ `CHUA_LAP` |
| MSG-09-02 | Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp (QĐ Điều 16). | Cảnh báo | Chọn mức khác trường 8 |
| MSG-09-03 | Đề xuất mức Hoàn thành xuất sắc cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng đã được xác nhận. | Lỗi kiểm tra | Chọn HTXSNV mà thiếu trường 10 |
| MSG-09-04 | Đã nộp phiếu tự đánh giá và ký số thành công lúc {thoi_diem}. Phiếu đã chuyển sang bước thẩm định. | Thành công | Nhận EVT-18 |
| MSG-09-05 | Ký số không thành công: {ly_do}. Phiếu chưa được nộp, dữ liệu đã nhập vẫn được giữ. | Lỗi | esign-service trả lỗi |

#### SCR-0902. Thẩm định

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cơ quan tổ chức thẩm định lần lượt các hồ sơ trong hàng đợi, so sánh tự đánh giá với dữ liệu nghiệm thu, chọn mức thẩm định, giải trình và ký số |
| Tác nhân | AC-07 (`appraiser`) |
| Đường vào | Menu Đánh giá › Thẩm định; thông báo việc mới; SCR-1601 thẻ "Hồ sơ chờ thẩm định" |
| Quyền | `appraiser` của đơn vị chứa hồ sơ theo ABAC cây tổ chức; không thẩm định hồ sơ của chính mình hoặc hồ sơ mình đã nghiệm thu |
| Thiết bị | Web desktop (màn hình lõi viết mã, không cấu hình SDUI) |

![Hình 10.4. SCR-0902 Thẩm định hồ sơ đánh giá, so sánh tự đánh giá và kết quả thẩm định](SCR-0902.png){width=16cm}

Màn hình theo bố cục "hàng đợi bên trái, chi tiết bên phải" để thẩm định viên làm liên tục nhiều hồ sơ. Hồ sơ đang chọn hiển thị bốn thẻ số liệu: điểm tự tính theo minh chứng đã nộp (93,4), điểm sau thẩm định (91,2, giảm 2,2 do điều chỉnh B, C), mức tiêu chí chung và mức hệ thống tính.

Vùng 1 là hàng đợi thẩm định, mỗi dòng gồm tên, chức vụ, điểm hệ thống và mức tự đề xuất; dòng có nhãn cam "Tự chấm cao 3 quý" là gợi ý từ dấu hiệu bất thường số 1 của QT-14. Vùng 2 là phần đầu hồ sơ với trạng thái `CHO_THAM_DINH`, thông tin chữ ký và mã băm phiếu đã nộp, cùng nhãn gợi ý tím. Gợi ý này chỉ giúp thẩm định viên chú ý chứ không thay đổi kết quả. Vùng 3 là bảng so sánh B, C tự đánh giá với B, C thẩm định cho từng nhiệm vụ then chốt; dòng có điều chỉnh hiển thị căn cứ màu đỏ (ví dụ "B: phải sửa nội dung cơ bản, biên bản nghiệm thu 18/12"). Vùng 4 gồm thẻ "Kết luận thẩm định" và thẻ "Giải trình bắt buộc"; thẻ sau tự hiện khi mức thẩm định khác mức tự đề xuất và chặn nút ký khi ô lý do trống.

Cần lưu ý rằng cột "B tự" và "C tự" là giá trị cán bộ đề nghị khi nộp minh chứng (ví dụ cho rằng nhiệm vụ không chậm), còn cột thẩm định lấy từ `product.acceptance` do người nghiệm thu ghi. Thẩm định viên không sửa B, C ở màn hình này; nếu thấy nghiệm thu sai, họ trả lại hồ sơ kèm đề nghị nghiệm thu lại qua QT-07 khi kỳ còn mở, hoặc chuyển hậu kiểm khi kỳ đã khoá.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc hàng đợi | Danh sách chọn | Không | Trạng thái, đơn vị | Chờ thẩm định | Chỉ đơn vị trong phạm vi ABAC | `flowable.wf_task_projection` |
| 2 | Dòng hàng đợi | Bảng | Không áp dụng | Tên, chức vụ, điểm, mức | Sắp theo hạn | Phân trang con trỏ, tối đa 200 | `appraisal.appraisal_profile`, `org_replica.person_v` |
| 3 | Thông tin nộp và chữ ký | Nhãn | Không áp dụng | Thời điểm, nhà cung cấp, băm | Từ hồ sơ | Kiểm tra chữ ký hợp lệ khi mở | `esign.sign_item`, `appraisal.appraisal_profile.submitted_at` |
| 4 | Điểm tự tính, điểm sau thẩm định | Số | Không áp dụng | 1 chữ số thập phân | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot` |
| 5 | Bảng so sánh B, C | Bảng | Không áp dụng | 2 chữ số thập phân | Từ dữ liệu | Chỉ đọc; tô đỏ khi khác | `appraisal.self_assessment.claims_json`, `product.acceptance.quality_level`, `product.acceptance.delay_days` |
| 6 | Mức thẩm định | Danh sách chọn | Có | 4 giá trị | Bằng mức hệ thống | Khác mức tự đề xuất thì bật trường 8 | `appraisal.rating_proposal.level` (THAM_DINH) |
| 7 | Nhận xét thẩm định | Văn bản dài | Có | ≤ 4.000 ký tự, C2 | Trống | Mã hoá trường | `appraisal.rating_proposal.comment_enc` |
| 8 | Lý do khác mức tự đề xuất | Văn bản dài | Có khi khác mức | 20 đến 2.000 ký tự | Trống | Theo BR-09-12 | `appraisal.rating_proposal.reason_enc` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn hồ sơ trong hàng đợi | Luôn | Tải chi tiết, kiểm tra quyền OPA | `GET /api/v1/appraisals/profiles/{id}` | Không có |
| Mở minh chứng | Luôn | Mở trình xem minh chứng theo phiên bản, ghi nhật ký truy cập C2 | `GET /api/v1/evidences/{id}/versions/{v}` | Không có |
| Trả lại bổ sung | Hồ sơ `CHO_THAM_DINH` | Bắt buộc lý do; hoàn thành user task với biến `ket_qua = TRA_LAI` | `POST /api/v1/appraisals/profiles/{id}/return` | EVT-11, EVT-21 |
| Ký số và chuyển đề xuất | Đủ trường 6, 7 và trường 8 nếu cần | Lưu bản ghi THAM_DINH, ký số, hoàn thành user task | `POST /api/v1/appraisals/profiles/{id}/appraisal` (202) | EVT-18, EVT-11 |
| Xuất danh sách | Luôn | Xuất bảng hàng đợi ra XLSX (không gồm trường C2) | `POST /api/v1/appraisals/exports` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-06 | Mức thẩm định khác mức tự đề xuất. Theo QĐ Điều 16, đồng chí cần ghi lý do trước khi ký. | Cảnh báo | Chọn mức khác |
| MSG-09-07 | Hồ sơ vừa được người khác cập nhật. Vui lòng tải lại để xem thay đổi trước khi lưu. | Lỗi | API trả 409 |
| MSG-09-08 | Đồng chí không thể thẩm định hồ sơ này vì đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (PL 4 V.1). | Lỗi phân quyền | OPA từ chối |
| MSG-09-09 | Đã trả lại hồ sơ cho đồng chí {ten} bổ sung. Lần trả lại thứ {n}/{toi_da}. | Thành công | Trả lại xong |

#### SCR-0903. Phiếu xếp loại có giải thích

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Trình bày điểm theo cấu phần, các điều kiện của từng mức, mức đề xuất của hệ thống và câu giải thích vì sao hạ mức; là nơi quản lý trực tiếp chọn mức đề xuất và cán bộ xem kết quả sau quyết định |
| Tác nhân | AC-06 xem kết quả của mình; AC-05 chọn mức đề xuất; AC-07, AC-08 xem |
| Đường vào | Từ SCR-0902 sau thẩm định; menu Đánh giá › Kết quả xếp loại; thông báo kết quả |
| Quyền | Chủ hồ sơ, quản lý trực tiếp, thẩm định viên và cấp quyết định của đơn vị; dữ liệu C2 |
| Thiết bị | Web desktop và di động (bố cục một cột trên di động) |

![Hình 10.5. SCR-0903 Phiếu xếp loại có giải thích lý do hạ mức](SCR-0903.png){width=16cm}

Đây là màn hình hiện thực hoá yêu cầu "bộ máy quy tắc phải tự giải thích vì sao một hồ sơ bị hạ mức" của PL A3. Người xem đọc từ trên xuống sẽ thấy điểm 91,2, cách cộng thành 27,6 và 63,6, rồi thấy ngay điều kiện nào đạt, điều kiện nào chưa đạt, và cuối cùng là câu kết luận có số điều khoản.

Vùng 1 là bảng điểm theo cấu phần: hai nhóm nhiệm vụ với KPI nhóm, điểm tối đa và điểm; hai dòng ghi nhận riêng là tỷ lệ việc vượt 22% và tỷ lệ hoàn thành mục tiêu O 100%, với chú thích "ghi nhận riêng, không cộng điểm". Vùng 2 là danh sách điều kiện của mức HTXSNV và HTTNV dạng dấu tích; điều kiện "≥ 30% nhiệm vụ vượt mục tiêu (thực tế 22%)" có nhãn đỏ "Chưa đạt". Vùng 3 là ghi chú luồng Nhà nước: cùng dữ liệu sản phẩm, 28,0 điểm tiêu chí chung cộng 70 × 0,912 bằng 63,8, tổng 91,8 điểm, lưu riêng. Vùng 4 là khối "Mức đề xuất của hệ thống" có viền đỏ, chứa câu giải thích sinh từ mẫu câu của DMN. Vùng 5 là nhãn phiên bản "Policy Pack Đảng v2.0" ở đầu trang, luôn hiển thị để người xem biết bộ quy tắc nào đã được áp dụng. Dưới cùng là dải "Vết các mức đề xuất" thể hiện chuỗi tự đề xuất HTXSNV (04/01), thẩm định HTTNV (07/01), giải trình bắt buộc đã ghi, đề xuất HTTNV (08/01) và chờ quyết định, cùng mã snapshot và băm đầu vào.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Điểm kết quả nhiệm vụ | Số | Không áp dụng | 1 chữ số thập phân | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.total_score` |
| 2 | Bảng cấu phần | Bảng | Không áp dụng | KPI 3 chữ số, điểm 1 chữ số | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.group_scores_json` |
| 3 | Tỷ lệ việc vượt, tỷ lệ mục tiêu O | Số | Không áp dụng | Phần trăm | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.over_target_ratio`, `appraisal.score_snapshot.objective_ratio` |
| 4 | Danh sách điều kiện các mức | Danh sách | Không áp dụng | Đạt hoặc chưa đạt kèm giá trị | Từ DMN | Chỉ đọc | `appraisal.rating_explanation.conditions_json` |
| 5 | Mức đề xuất của hệ thống | Nhãn | Không áp dụng | 4 giá trị | Từ DMN | Chỉ đọc | `appraisal.rating_explanation.system_level` |
| 6 | Câu giải thích | Văn bản | Không áp dụng | ≤ 1.000 ký tự | Sinh tự động | Chứa số điều khoản và phiên bản | `appraisal.rating_explanation.text` |
| 7 | Phiên bản Policy Pack | Nhãn | Không áp dụng | Mã gói và số phiên bản | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.policy_version` |
| 8 | Vết các mức | Dải chip | Không áp dụng | Mức, ngày, người | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` (4 bản ghi) |
| 9 | Mã snapshot và băm đầu vào | Nhãn | Không áp dụng | UUIDv7, SHA-256 rút gọn | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.id`, `appraisal.score_snapshot.input_hash` |
| 10 | Mức đề xuất của quản lý | Danh sách chọn | Có với AC-05 ở bước đề xuất | 4 giá trị | Bằng mức thẩm định | Khác mức thẩm định thì bắt buộc lý do | `appraisal.rating_proposal.level` (DE_XUAT) |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chuyển thẻ Luồng Nhà nước | Tenant bật | Hiển thị snapshot luồng Nhà nước | `GET /api/v1/appraisals/profiles/{id}/scores?flow=NHA_NUOC` | Không có |
| Thẻ Vết tính | Luôn | Hiển thị vết tính từng bước của scoring-service | `GET /api/v1/appraisals/profiles/{id}/score-trace` | Không có |
| Tải Mẫu 1B (PDF/A) | Sau khi có quyết định | Tải bản đã ký từ document-service | `GET /api/v1/dossiers/forms/{formId}/file` | Không có |
| Ghi mức đề xuất | AC-05, hồ sơ `DA_THAM_DINH` | Lưu bản ghi DE_XUAT, hoàn thành user task | `POST /api/v1/appraisals/profiles/{id}/proposal` | EVT-11 |
| Gửi khiếu nại | Chủ hồ sơ, hồ sơ `DA_KY_SO`, còn hạn | Mở SCR-1201 | Không có (điều hướng) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-10 | Kết quả này được tính theo Policy Pack {ma_goi} phiên bản {phien_ban}, áp dụng cho kỳ {ky}. | Thông tin | Di chuột vào nhãn phiên bản |
| MSG-09-11 | Kết quả xếp loại quý {ky} của đồng chí đã được quyết định: {muc}. Thời hạn khiếu nại đến {han}. | Thông báo đẩy | Nhận EVT-12 |
| MSG-09-12 | Mức đề xuất khác mức thẩm định. Vui lòng ghi lý do (QĐ khoản 16.3). | Cảnh báo | AC-05 chọn mức khác |

#### SCR-0904. Quyết định xếp loại hàng loạt và ký số

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cấp có thẩm quyền xem toàn bộ hồ sơ đến bước quyết định của một đơn vị hoặc khối, kiểm tra phân bố mức, quyết định và ký lô một lần cho cả danh sách |
| Tác nhân | AC-08 (`decider`); thư ký tổng hợp hỗ trợ chuẩn bị nhưng không ký |
| Đường vào | Menu Đánh giá › Quyết định xếp loại; thông báo "Có 48 hồ sơ chờ quyết định" |
| Quyền | `decider` của đơn vị; MFA bước lên khi ký lô |
| Thiết bị | Web desktop |

![Hình 10.6. SCR-0904 Quyết định xếp loại hàng loạt và ký lô](SCR-0904.png){width=16cm}

Màn hình giải quyết bài toán quy mô của bước quyết định: một Ban Thường vụ có thể quyết định vài trăm hồ sơ trong một phiên họp, nên không thể ký từng phiếu. Bảng chính bên trái liệt kê đủ bốn cột mức (tự đề xuất, thẩm định, đề xuất, quyết định) để người quyết định thấy chỗ khác nhau mà không phải mở từng hồ sơ.

Vùng 1 là bảng hồ sơ với hộp chọn, điểm và bốn mức; cột ghi chú nêu lý do đặc biệt như "Khác mức · đã giải trình", "Dưới 60 điểm" hay "Quy tắc người đứng đầu: đạt" (hồ sơ của Trưởng ban Ngô Thanh Tâm). Vùng 2 là cột "Quyết định" dạng ô chọn, mặc định bằng mức đề xuất; đổi mức sẽ mở hộp thoại ghi lý do. Vùng 3 là biểu đồ phân bố mức quyết định (HTXSNV 9 người, 19%; HTTNV 30 người, 63%; HTNV 7 người, 15%; KHTNV 2 người, 4%) và kết luận "trong giới hạn tham số của khối (≤ 20%)". Vùng 4 là khối ký lô: chứng thư của người ký (Ngô Thanh Tâm, MySign), phương thức "một lần xác nhận cho cả lô, mỗi hồ sơ một chữ ký riêng (PAdES)", số và ngày quyết định 215-QĐ/ĐUK ngày 12/01/2027, thanh tiến độ 36/48 và ghi chú hồ sơ chưa ký vẫn ở `CHO_QUYET_DINH`.

Ký lô được thực hiện bằng saga tầng 3 (ADR-04). Sau khi người ký xác thực một lần với nhà cung cấp chữ ký số từ xa, esign-service nhận danh sách mã băm tài liệu và ký từng tài liệu; mỗi chữ ký thành công phát một EVT-18, MOD-04 chuyển hồ sơ tương ứng sang `DA_KY_SO`. Thông lượng thiết kế 100.000 phiếu trong 4 giờ, tương đương khoảng 7 phiếu mỗi giây (số ước tính, TL-04 kiểm chứng bằng kịch bản PERF).

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc khối, đơn vị | Danh sách chọn | Không | Theo cây tổ chức | Tất cả trong phạm vi | ABAC | `org_replica.org_unit_v` |
| 2 | Hộp chọn hồ sơ | Hộp đánh dấu | Có (ít nhất 1) | Boolean | Chọn tất cả | Chỉ hồ sơ `CHO_QUYET_DINH` | Trạng thái giao diện |
| 3 | Bốn cột mức | Nhãn | Không áp dụng | 4 giá trị | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` |
| 4 | Mức quyết định | Danh sách chọn | Có | 4 giá trị | Bằng mức đề xuất | Đổi mức thì bắt buộc lý do ≥ 20 ký tự | `appraisal.rating_proposal.level` (QUYET_DINH) |
| 5 | Phân bố mức | Biểu đồ | Không áp dụng | Số người và tỷ lệ | Tính tức thời | So với ngưỡng `phan_bo.toi_da_htxsnv` của khối | Tính trên client từ danh sách |
| 6 | Số, ngày quyết định | Văn bản | Có | Số ký hiệu theo quy định văn thư, ngày dd/mm/yyyy | Trống | Không trùng số trong năm của đơn vị | `appraisal.batch_decision.decision_no`, `appraisal.batch_decision.decision_date` |
| 7 | Chứng thư người ký | Nhãn | Không áp dụng | Tên, nhà cung cấp, hạn | Từ esign | Còn hạn ít nhất 1 ngày | `esign.certificate_ref` |
| 8 | Tiến độ ký lô | Thanh tiến độ | Không áp dụng | Đã ký / tổng | 0 | Cập nhật qua SSE | `esign.sign_request`, `esign.sign_item` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Xuất Mẫu 3 | Luôn | Sinh Mẫu 3 dạng nháp từ danh sách đang chọn | `POST /api/v1/dossiers/forms` (202) | Không có |
| Đổi mức quyết định | Hồ sơ đang chọn | Mở hộp thoại lý do; lưu tạm trên lô | Không có (lưu cùng lô) | Không có |
| Quyết định và ký lô | Có ít nhất 1 hồ sơ, đủ số và ngày | Tạo `appraisal.batch_decision`, ghi các bản ghi QUYET_DINH, gọi MFA bước lên, tạo yêu cầu ký lô | `POST /api/v1/appraisals/batch-decisions` (202, trả `Location: /api/v1/jobs/{id}`) | EVT-11; sau đó EVT-18, EVT-12 cho từng hồ sơ |
| Theo dõi tiến độ | Khi có lô đang chạy | Nhận cập nhật qua SSE từ notification-service | `GET /api/v1/jobs/{id}` | Không có |
| Huỷ lô | Lô chưa hoàn tất | Dừng các chữ ký chưa thực hiện; hồ sơ đã ký giữ nguyên | `POST /api/v1/appraisals/batch-decisions/{id}/cancel` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-13 | Tỷ lệ HTXSNV của danh sách là {ty_le}%, vượt giới hạn {nguong}% của khối. Đề nghị tổ chức phiên hiệu chỉnh trước khi ký. | Cảnh báo | Phân bố vượt ngưỡng |
| MSG-09-14 | Hồ sơ của người đứng đầu chỉ được quyết định sau khi các đơn vị trực thuộc đã có mức (QĐ khoản 14.3.3). | Lỗi kiểm tra | Chọn hồ sơ người đứng đầu quá sớm |
| MSG-09-15 | Đã ký {da_ky}/{tong} hồ sơ. {loi} hồ sơ lỗi sẽ được tự thử lại; đồng chí có thể đóng màn hình. | Thông tin | Trong khi ký lô |
| MSG-09-16 | Đã hoàn tất quyết định {so_qd}: {tong} hồ sơ đã ký số và thông báo tới cán bộ. | Thành công | Lô hoàn tất |

### Liên kết thiết kế

QT-09 do MOD-04 `appraisal` trong mission-core (SVC-05) điều phối, phối hợp với SVC-07 scoring-service (tính điểm, DMN), SVC-06 workflow-service (mẫu luồng `duyet-ho-so-danh-gia`), SVC-10 esign-service (ký số, ký lô), SVC-11 document-service (xem trước biểu mẫu) và SVC-12 notification-service. Dữ liệu chính nằm ở schema `appraisal` (`appraisal_profile`, `general_criteria_result`, `self_assessment`, `score_snapshot`, `rating_proposal`, `rating_explanation`), `scoring` (`score_job`, `score_result`, `score_trace`), `esign` và `flowable.wf_task_projection`. Sự kiện tham gia gồm EVT-09, EVT-10, EVT-11, EVT-12, EVT-18, EVT-21. Về an toàn thông tin, các trường nhận xét, giải trình là dữ liệu C2 được mã hoá trường (ADR-06); ký số tại ba mốc đáp ứng tiêu chí I4; snapshot có `policy_version` và `input_hash` đáp ứng I6; mọi chuyển trạng thái được ghi sổ cái qua outbox (I1, I2). Cấu trúc bảng chi tiết xem TL-03, mục schema `appraisal`; kịch bản thử tải tính lại 1,2 triệu hồ sơ và ký lô 100.000 phiếu xem TL-04 nhóm PERF.

## QT-10. Xếp loại năm, liên thông quý–năm, phân nhóm triển vọng

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-10 |
| Tên | Xếp loại năm, liên thông quý–năm, phân nhóm triển vọng |
| Mục đích | Xếp loại cán bộ theo năm trên cơ sở kết quả bốn quý đã khoá, áp quy tắc liên thông quý–năm, và phân nhóm triển vọng phục vụ công tác cán bộ |
| Phạm vi áp dụng | Mọi cán bộ, đảng viên thuộc diện đánh giá năm của tenant; phân nhóm triển vọng chỉ cho đối tượng mà Policy Pack quy định (cán bộ lãnh đạo, quản lý và diện quy hoạch) |
| Căn cứ | Dự thảo Quy định mới khoản 15.5 (một quý KHTNV thì không xếp HTXSNV năm), khoản 15.6 (hai quý liên tiếp KHTNV thì đề xuất bố trí lại), khoản 12.1.5 (phân nhóm hằng năm), Điều 11; PL 5 (tiêu chí phân nhóm); PL A4 (ẩn phân nhóm ở kỳ quý) |
| Tác nhân | AC-06 cán bộ; AC-07 cơ quan tổ chức; AC-04 lãnh đạo, tập thể lãnh đạo; AC-08 cấp quyết định; SVC-07 scoring-service |
| Tần suất và khối lượng ước tính | Mỗi năm một lần, vào tháng 1 năm sau. Khoảng 6 triệu hồ sơ năm toàn quốc (ước tính); phân nhóm triển vọng ước tính khoảng 10% số hồ sơ (diện lãnh đạo, quản lý, quy hoạch), tức khoảng 600.000 lượt |
| Điều kiện bắt đầu | Kỳ năm được mở (`config.period` loại NAM, trạng thái `MO`) và kỳ quý IV đã `DA_KHOA`; bốn kết quả quý của cán bộ đã có quyết định |
| Điều kiện kết thúc | Quyết định xếp loại năm đã ký số; danh sách phân nhóm đã được tập thể thông qua và ký số |
| Đầu vào | Bốn bản `appraisal.rating_proposal` mức QUYET_DINH của các quý; bốn `appraisal.score_snapshot`; tỷ lệ việc vượt cả năm; tham số năm của Policy Pack |
| Đầu ra | `appraisal.annual_rating`, `appraisal.prospect_group`, cờ đề xuất bố trí lại, EVT-12 cho kỳ năm |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ hồ sơ năm hoàn tất trước hạn nộp cấp trên; số hồ sơ bị chặn HTXSNV do liên thông; số đề xuất bố trí lại được xử lý trong 30 ngày; tỷ lệ phân nhóm có lý do khi khác gợi ý |

Hệ thống hiện tại theo bản đánh giá (H4) chỉ có kỳ quý, nên việc xếp loại năm đang làm bằng bảng tính ngoài hệ thống và quy tắc liên thông phụ thuộc trí nhớ của cán bộ tổ chức. QT-10 đưa quy tắc liên thông vào bảng quyết định của Policy Pack để mọi hồ sơ được kiểm tra như nhau, đồng thời giữ nguyên nguyên tắc kỳ đã khoá không bị tính lại: kết quả quý I đến quý III có thể đã được tính bằng Policy Pack v1.4, còn quý IV và năm dùng v2.0, và hệ thống không "nâng cấp" các quý cũ sang phiên bản mới.

Theo dự thảo, kết quả quý IV gộp vào năm, nghĩa là phiếu quý IV đồng thời là đầu vào của xếp loại năm. Cách tính điểm năm là tham số `nam.cach_tinh` của Policy Pack; giá trị mặc định `TRUNG_BINH_QUY` lấy trung bình điểm bốn quý đã quyết định. Với hồ sơ mẫu, điểm năm là (91,8 + 88,6 + 90,2 + 91,2) / 4 = 90,4. Tuy đủ 90 điểm và không có quý nào KHTNV, tỷ lệ nhiệm vụ vượt mục tiêu cả năm chỉ đạt 24%, nên hệ thống đề xuất HTTNV năm.

Phân nhóm triển vọng là bước riêng, chỉ mở sau khi đã có quyết định xếp loại năm. Bốn nhóm theo PL 5 là Nhóm 1 "Phát triển vượt trội", Nhóm 2 "Có triển vọng", Nhóm 3 "Cần bồi dưỡng thêm" và Nhóm 4 "Cần xem xét bố trí". Hệ thống chỉ đưa gợi ý ban đầu và không tự phân nhóm, vì đây là nhận định của tập thể lãnh đạo về con người, không phải phép tính.

### Lưu đồ quy trình

![Hình 10.7. Lưu đồ làn bơi QT-10: xếp loại năm và phân nhóm triển vọng](nv-b-qt10-luong.png){width=16cm}

Làn "Hệ thống" đọc bốn kết quả quý đã khoá, tính điểm năm theo tham số, rồi lần lượt kiểm tra hai quy tắc liên thông. Nếu có ít nhất một quý KHTNV, mức HTXSNV năm bị chặn (khoản 15.5). Nếu có hai quý liên tiếp KHTNV, hồ sơ được gắn cờ đề xuất bố trí lại (khoản 15.6). Sau đó bảng DMN năm sinh mức đề xuất và câu giải thích. Các làn còn lại tái sử dụng chuỗi tự đánh giá, thẩm định, quyết định của QT-09 với cùng mẫu luồng ở chế độ năm, rồi thêm bước phân nhóm triển vọng và thông qua tập thể. Khi hoàn tất, MOD-04 phát EVT-12 với `periodType = NAM` để MOD-06 lập hồ sơ năm (QT-11).

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở kỳ năm | AC-02 hoặc AC-03 | Mở `config.period` loại NAM, gắn Policy Pack áp dụng cho năm | SCR-0801 | `config.period` `MO` | Đầu tháng 1 |
| 2 | Lập hồ sơ năm | Hệ thống (MOD-04) | Khi quý IV khoá, tạo hồ sơ năm cho từng người, gắn bốn kết quả quý | Không có | `appraisal.appraisal_profile` (kỳ năm) | Trong 15 phút sau EVT-09 của quý IV |
| 3 | Tính điểm năm và liên thông | SVC-07 | Tính điểm năm, tỷ lệ việc vượt cả năm, áp quy tắc 15.5 và 15.6, chạy DMN năm | Không có | `appraisal.score_snapshot` (năm); EVT-10 | Cell lớn ≤ 2 giờ |
| 4 | Tự đánh giá năm | AC-06 | Như bước 3 đến 6 của QT-09, phiếu ở chế độ năm, hiển thị bốn kết quả quý chỉ đọc | SCR-0901 | `appraisal.rating_proposal` (TU_DE_XUAT) | 5 ngày làm việc |
| 5 | Thẩm định năm | AC-07 | Xem bảng tổng hợp năm, lọc hồ sơ có cảnh báo liên thông, chọn mức thẩm định | SCR-1001, SCR-0902 | `appraisal.rating_proposal` (THAM_DINH) | 5 ngày làm việc |
| 6 | Quyết định xếp loại năm | AC-08 | Quyết định hàng loạt và ký lô như QT-09 | SCR-0904 | `appraisal.annual_rating`; EVT-18; EVT-12 | 5 ngày làm việc |
| 7 | Xử lý đề xuất bố trí lại | AC-07, AC-04 | Với hồ sơ có cờ 15.6, lập đề xuất bố trí lại gửi cấp có thẩm quyền về cán bộ | SCR-1001 | Việc mới trong MOD-02, EVT-04 | 30 ngày |
| 8 | Phân nhóm triển vọng | AC-04, AC-07 | Mở bảng phân nhóm, xem gợi ý, kéo thả vào bốn nhóm, xếp thứ tự trong nhóm, ghi lý do khi khác gợi ý, gắn kế hoạch bồi dưỡng | SCR-1002 | `appraisal.prospect_group` (nháp) | 10 ngày làm việc |
| 9 | Thông qua tập thể, ký số | AC-04, AC-08 | Trình tập thể lãnh đạo; sau khi thông qua, người có thẩm quyền ký số danh sách | SCR-1002 | `appraisal.prospect_group` (đã ký); EVT-18 | Theo lịch họp |

Điểm khác biệt quan trọng so với QT-09 nằm ở bước 3. Snapshot năm không chứa lại dữ liệu nhiệm vụ mà tham chiếu tới bốn snapshot quý bằng mã và mã băm; `input_hash` của snapshot năm là băm của bốn băm quý cùng tham số năm. Cách này giúp người kiểm tra chứng minh được rằng kết quả năm được tính từ đúng các kết quả quý đã khoá, kể cả khi một quý sau đó được mở lại qua QT-13: khi đó snapshot quý mới có băm khác và hệ thống tự yêu cầu tính lại năm.

Bước 7 không thay đổi mức xếp loại mà tạo việc cho cơ quan tổ chức. Đề xuất bố trí lại là thủ tục công tác cán bộ nằm ngoài phạm vi chấm điểm, nên nền tảng chỉ bảo đảm không bỏ sót trường hợp và theo dõi đến khi có kết quả.

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-10-01 | Kết quả xếp loại năm dựa trên kết quả các quý đã được quyết định; kết quả quý IV đồng thời là đầu vào của năm | Dự thảo Quy định mới; PL A5 (quý IV gộp vào năm) | Hồ sơ năm chỉ lập khi đủ bốn bản QUYET_DINH hoặc có trường hợp đặc thù PL 6 |
| BR-10-02 | Điểm năm tính theo tham số `nam.cach_tinh`; mặc định là trung bình điểm bốn quý (`TRUNG_BINH_QUY`); có thể cấu hình trung bình có trọng số hoặc tính trên toàn bộ nhiệm vụ năm | Chưa thống nhất trong dự thảo, ghi nhận ở PL A4 | Tham số Policy Pack; mỗi giá trị có bộ test case vàng riêng |
| BR-10-03 | Có ít nhất một quý xếp loại KHTNV thì không được xếp HTXSNV năm | QĐ khoản 15.5 | Dòng chặn trong DMN năm, đứng trước dòng HTXSNV; câu giải thích nêu quý nào |
| BR-10-04 | Có hai quý liên tiếp xếp loại KHTNV thì gắn cờ đề xuất bố trí lại công việc | QĐ khoản 15.6 | Hàm CEL kiểm tra cặp quý liên tiếp; cờ `annual_rating.reassign_flag`; tạo việc trong MOD-02 |
| BR-10-05 | Các điều kiện HTXSNV, HTTNV của năm áp dụng như quý, tính trên số liệu cả năm (tỷ lệ việc vượt năm, mục tiêu O năm) | QĐ Điều 11 | DMN năm dùng chung cấu trúc DMN quý |
| BR-10-06 | Kết quả các quý đã khoá giữ nguyên phiên bản Policy Pack đã dùng; không tính lại quý cũ bằng phiên bản mới khi xếp loại năm | Nguyên tắc tái lập kết quả, tiêu chí I6 | Snapshot quý chỉ đọc; snapshot năm lưu danh sách phiên bản của từng quý |
| BR-10-07 | Phân nhóm triển vọng chỉ thực hiện ở kỳ năm; ở kỳ quý chức năng bị khoá và trường "dự kiến phân nhóm" trên Mẫu 2 quý bị ẩn | QĐ khoản 12.1.5; PL A4 | Tham số `phan_nhom.mo_o_ky_quy = false`; API trả 409 nếu gọi ở kỳ quý |
| BR-10-08 | Hệ thống chỉ đưa gợi ý thứ tự tham khảo, không tự phân nhóm; khi người dùng xếp khác gợi ý thì bắt buộc ghi lý do | PL 5; nguyên tắc AI không quyết định | Trường lý do bắt buộc khi nhóm khác gợi ý |
| BR-10-09 | Người bị xếp KHTNV năm hoặc có cờ bố trí lại không được đưa vào Nhóm 1 hoặc Nhóm 2 | PL 5 | Kiểm tra CEL khi lưu và khi trình |
| BR-10-10 | Kết quả phân nhóm là dữ liệu C2, chỉ `leader` và `appraiser` của đơn vị xem được; không xuất sang kho phân tích ở dạng định danh | ADR-06 | OPA; CDC chỉ đẩy số lượng theo nhóm, không đẩy danh tính |

Quy tắc BR-10-02 phản ánh một điểm chưa rõ trong dự thảo được nêu ở PL A4. Thay vì chọn một cách và viết cứng, nền tảng tham số hoá để khi có hướng dẫn chính thức chỉ cần phát hành Policy Pack mới (≤ 5 ngày theo NFR-07). Các ca kiểm thử của cả hai cách đều nằm trong bộ test case vàng, nên việc đổi tham số không làm hỏng kết quả của các khối khác.

### Ngoại lệ và xử lý

Khi cán bộ chỉ có ba quý do được tuyển dụng hoặc điều động giữa năm, Policy Pack áp trường hợp đặc thù tương ứng của PL 6: tính điểm năm trên số quý thực có và ghi chú trên Mẫu 2. Nếu có quý không xếp loại do nghỉ thai sản (PL 6 §4), quý đó không tính vào trung bình và không bị coi là KHTNV.

Khi một quý của năm đang có khiếu nại chưa kết luận, hồ sơ năm vẫn được lập nhưng bị chặn ở bước quyết định với thông báo MSG-10-03. Lý do là kết luận khiếu nại có thể thay đổi kết quả quý và kéo theo kết quả năm.

Khi một quý được mở lại sau khi năm đã quyết định (QT-13), hệ thống tính lại snapshot năm. Nếu mức năm thay đổi, hồ sơ năm được đưa trở lại bước quyết định với lý do "thi hành kết luận hậu kiểm" và quyết định cũ được giữ nguyên trong lịch sử, có đánh dấu bị thay thế.

Khi tập thể lãnh đạo không thông qua danh sách phân nhóm, danh sách trở về trạng thái nháp, lý do không thông qua được lưu, và việc trình lại tạo một phiên bản mới của danh sách.

### Đặc tả màn hình

#### SCR-1001. Xếp loại năm

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Tổng hợp kết quả bốn quý và mức năm của toàn đơn vị, làm nổi bật hồ sơ bị ảnh hưởng bởi quy tắc liên thông, giải thích chi tiết cho từng người |
| Tác nhân | AC-07 (`appraiser`); AC-04, AC-08 xem |
| Đường vào | Menu Đánh giá › Xếp loại năm; từ SCR-0801 khi kỳ năm mở |
| Quyền | Theo ABAC cây tổ chức; dữ liệu C2 |
| Thiết bị | Web desktop |

![Hình 10.8. SCR-1001 Xếp loại năm 2026 với các cảnh báo liên thông quý–năm](SCR-1001.png){width=16cm}

Màn hình được thiết kế để cơ quan tổ chức xử lý trước những hồ sơ "có vấn đề" thay vì đọc lần lượt 48 hồ sơ. Bốn thẻ số liệu đầu trang cho biết 48/48 hồ sơ năm đã lập, 3 hồ sơ bị chặn HTXSNV do có quý KHTNV, 1 hồ sơ đề xuất bố trí lại và 48 hồ sơ chờ phân nhóm.

Vùng 1 là tiêu đề và dòng phụ ghi rõ phiên bản: "Policy Pack Đảng v2.0 (Q1–Q3 dùng v1.4, giữ nguyên theo kỳ đã khoá)", cùng nút "Chỉ hiện hồ sơ có cảnh báo liên thông". Vùng 3 là bảng tổng hợp với bốn cột quý, điểm năm, mức tự đề xuất năm, mức hệ thống đề xuất và cột "Quy tắc liên thông". Dòng Phạm Đức Long có quý II KHTNV nên hiện ghi chú màu cam "Có quý KHTNV ⇒ chặn HTXSNV (QĐ 15.5)"; dòng Bùi Văn Khánh có quý II đến quý IV KHTNV nên hiện ghi chú màu đỏ "2 quý liên tiếp KHTNV ⇒ đề xuất bố trí lại (QĐ 15.6)". Vùng 2 là ngăn "Giải thích liên thông" của hồ sơ đang chọn (Trần Minh Hoàng): công thức điểm năm, tham số `nam.cach_tinh`, danh sách bốn điều kiện với dấu tích. Vùng 4 là câu kết luận của hệ thống kèm điều khoản và bốn nhãn mức quý chỉ đọc, kèm ghi chú quý IV gộp vào năm.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc đơn vị | Danh sách chọn | Không | Cây tổ chức | Đơn vị của người xem | ABAC | `org_replica.org_unit_v` |
| 2 | Lọc hồ sơ có cảnh báo | Công tắc | Không | Boolean | Tắt | Không có | Trạng thái giao diện |
| 3 | Thẻ số liệu (4 thẻ) | Số | Không áp dụng | Số nguyên | Tính tức thời | Chỉ đọc | `appraisal.annual_rating` (đếm theo cờ) |
| 4 | Mức quý I đến IV | Nhãn | Không áp dụng | 4 giá trị | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` (QUYET_DINH của từng quý) |
| 5 | Điểm năm | Số | Không áp dụng | 1 chữ số thập phân | Từ snapshot năm | Chỉ đọc | `appraisal.annual_rating.score` |
| 6 | Mức tự đề xuất năm | Nhãn | Không áp dụng | 4 giá trị | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` (TU_DE_XUAT, kỳ năm) |
| 7 | Mức hệ thống đề xuất | Nhãn | Không áp dụng | 4 giá trị | Từ DMN năm | Chỉ đọc | `appraisal.annual_rating.system_level` |
| 8 | Quy tắc liên thông | Văn bản | Không áp dụng | ≤ 200 ký tự | Sinh tự động | Chỉ đọc | `appraisal.annual_rating.block_reason`, `appraisal.annual_rating.reassign_flag` |
| 9 | Giải thích chi tiết | Văn bản và danh sách điều kiện | Không áp dụng | Theo mẫu câu | Sinh tự động | Chỉ đọc | `appraisal.rating_explanation` (kỳ năm) |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn dòng | Luôn | Tải giải thích của hồ sơ | `GET /api/v1/appraisals/annual/{id}/explanation` | Không có |
| Chỉ hiện hồ sơ có cảnh báo liên thông | Luôn | Lọc theo `block_reason` hoặc `reassign_flag` | `GET /api/v1/appraisals/annual?period={id}&flag=lien_thong` | Không có |
| Chuyển thẩm định năm | Có hồ sơ ở bước tự đánh giá đã nộp | Mở SCR-0902 ở chế độ năm | Không có (điều hướng) | Không có |
| Lập đề xuất bố trí lại | Hồ sơ có `reassign_flag` | Tạo việc cho cơ quan tổ chức trong MOD-02 với căn cứ khoản 15.6 | `POST /api/v1/appraisals/annual/{id}/reassignment-proposal` | EVT-04 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-10-01 | Hồ sơ có quý {quy} xếp loại KHTNV nên không được xếp HTXSNV năm (QĐ khoản 15.5). | Thông tin | Hiển thị giải thích |
| MSG-10-02 | Cán bộ {ten} có hai quý liên tiếp KHTNV ({quy_1}, {quy_2}). Hệ thống đã tạo việc đề xuất bố trí lại (QĐ khoản 15.6). | Cảnh báo | Gắn cờ 15.6 |
| MSG-10-03 | Chưa thể quyết định xếp loại năm vì khiếu nại {ma_kn} về kết quả quý {quy} chưa có kết luận. | Lỗi kiểm tra | Có khiếu nại mở |
| MSG-10-04 | Kết quả quý {quy} vừa được điều chỉnh sau hậu kiểm. Điểm năm đã được tính lại từ {cu} thành {moi}. | Thông báo | Snapshot quý thay đổi |

#### SCR-1002. Phân nhóm triển vọng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho lãnh đạo xếp cán bộ vào bốn nhóm triển vọng, xếp thứ tự trong nhóm, ghi lý do, gắn kế hoạch bồi dưỡng và trình tập thể |
| Tác nhân | AC-04 (`leader`); AC-07 chuẩn bị |
| Đường vào | Menu Đánh giá › Phân nhóm triển vọng (chỉ hiện khi kỳ năm đã có quyết định) |
| Quyền | `leader`, `appraiser` của đơn vị; dữ liệu C2 |
| Thiết bị | Web desktop (kéo thả cần chuột hoặc bút) |

![Hình 10.9. SCR-1002 Phân nhóm triển vọng năm 2026 dạng bảng kéo thả bốn cột](SCR-1002.png){width=16cm}

Bảng kéo thả bốn cột được chọn vì thao tác phân nhóm có bản chất là so sánh tương đối giữa người với người; nhìn cả bốn nhóm cùng lúc giúp tập thể thấy ngay sự cân đối. Mỗi thẻ cán bộ có số thứ tự trong nhóm, một dòng tóm tắt (chức vụ, số năm HTXSNV hoặc lý do) và chip "Kế hoạch bồi dưỡng".

Vùng 1 là tiêu đề cùng dòng nhắc căn cứ "chỉ áp dụng ở kỳ năm (QĐ 12.1.5), tên nhóm theo PL 5, cấu hình trong Policy Pack". Vùng 2 là ghi chú màu cam giải thích chức năng bị khoá ở kỳ quý theo tham số `phan_nhom.mo_o_ky_quy = false` và nhắc dữ liệu là C2. Vùng 3 là bốn cột nhóm: Nhóm 1 có Đỗ Quang Vinh và Vũ Ngọc Sơn; Nhóm 2 có Trần Minh Hoàng, Lê Thu Hà và Nguyễn Hải Yến; Nhóm 3 có Hoàng Thị Mai và Phạm Đức Long; Nhóm 4 có Bùi Văn Khánh. Vùng 4 gồm thẻ "Gợi ý ban đầu của hệ thống" nói rõ hệ thống không tự phân nhóm, và ô "Lý do điều chỉnh so với gợi ý" với ví dụ lý do chuyển Vũ Ngọc Sơn lên Nhóm 1.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Nhóm | Cột kéo thả | Có | 4 nhóm theo Policy Pack | Theo gợi ý | BR-10-09 | `appraisal.prospect_group.group_code` |
| 2 | Thứ tự trong nhóm | Số | Có | Số nguyên ≥ 1 | Theo gợi ý | Không trùng trong nhóm | `appraisal.prospect_group.rank_in_group` |
| 3 | Tóm tắt thẻ | Nhãn | Không áp dụng | ≤ 80 ký tự | Sinh tự động | Chỉ đọc | `appraisal.annual_rating`, `org_replica.assignment_v` |
| 4 | Kế hoạch bồi dưỡng | Liên kết | Không | Tham chiếu kế hoạch đào tạo | Trống | Không có | `appraisal.prospect_group.training_plan_ref` |
| 5 | Nhóm gợi ý | Nhãn ẩn | Không áp dụng | 4 giá trị | Sinh tự động | Chỉ đọc | `appraisal.prospect_group.suggested_group` |
| 6 | Lý do điều chỉnh | Văn bản dài | Có khi khác gợi ý | 20 đến 1.000 ký tự, C2 | Trống | BR-10-08 | `appraisal.prospect_group.reason_enc` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Kéo thả thẻ | Danh sách ở trạng thái nháp | Đổi nhóm hoặc thứ tự; nếu khác gợi ý thì yêu cầu lý do | Lưu cục bộ, gửi khi lưu nháp | Không có |
| Xem tiêu chí PL 5 | Luôn | Mở ngăn tiêu chí của từng nhóm từ Policy Pack | `GET /api/v1/policies/packs/{id}/prospect-criteria` | Không có |
| Lưu nháp | Trạng thái nháp | Lưu toàn bộ danh sách, kiểm tra `If-Match` | `PUT /api/v1/appraisals/prospect-groups/{periodId}/{orgUnitId}` | Không có |
| Trình tập thể lãnh đạo | Đủ lý do cho mọi điều chỉnh | Khởi động user task thông qua tập thể; khoá chỉnh sửa | `POST /api/v1/appraisals/prospect-groups/{periodId}/{orgUnitId}/submit` | EVT-11 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-10-05 | Phân nhóm triển vọng chỉ thực hiện ở kỳ năm (QĐ khoản 12.1.5). | Lỗi kiểm tra | Mở ở kỳ quý |
| MSG-10-06 | Cán bộ {ten} xếp loại năm {muc} nên không thể đưa vào {nhom}. | Lỗi kiểm tra | Vi phạm BR-10-09 |
| MSG-10-07 | Đồng chí đã xếp {ten} khác nhóm gợi ý. Vui lòng ghi lý do điều chỉnh. | Cảnh báo | Khác gợi ý |

### Liên kết thiết kế

QT-10 dùng MOD-04 `appraisal` (bảng `annual_rating`, `prospect_group`, `score_snapshot`, `rating_proposal`, `rating_explanation`), SVC-07 scoring-service với DMN năm, SVC-06 workflow-service với mẫu `duyet-ho-so-danh-gia` ở chế độ năm, MOD-02 `task` để tạo việc đề xuất bố trí lại và SVC-10 esign-service. Sự kiện: EVT-09 (quý IV khoá), EVT-10, EVT-11, EVT-12, EVT-04, EVT-18. Dữ liệu phân nhóm là C2 và không đưa sang ClickHouse ở dạng định danh (ADR-06); bảng điều hành QT-16 chỉ thấy số lượng theo nhóm. Tham số `nam.cach_tinh` và `phan_nhom.mo_o_ky_quy` là tham số mới của Policy Pack, được ghi trong tệp ghi chú để TL-03 bổ sung vào `policy.policy_rule`.

## QT-11. Kiểm điểm và hồ sơ số

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-11 |
| Tên | Kiểm điểm và hồ sơ số |
| Mục đích | Tổ chức kiểm điểm theo đúng trình tự (gửi dự thảo, góp ý, hội nghị, biên bản, theo dõi khắc phục), sinh tự động các biểu mẫu từ dữ liệu, xử lý 13 trường hợp đặc thù, đóng gói hồ sơ quý, năm có ký số và chuyển hồ sơ diện Trung ương quản lý |
| Phạm vi áp dụng | Tập thể lãnh đạo, quản lý và cá nhân thuộc diện kiểm điểm cuối năm (luồng Đảng); hồ sơ quý cho đối tượng đánh giá quý |
| Căn cứ | Dự thảo Quy định mới Điều 6, Điều 7 (gửi dự thảo ít nhất 03 ngày làm việc trước hội nghị); Hướng dẫn mục 2 đến 4; Mẫu 1A, 1B, 1C, 1D, Mẫu 2, Mẫu 3 và bảng chấm điểm; PL 6 (13 trường hợp đặc thù); Điều 17 và Hướng dẫn mục 10 (chuyển hồ sơ diện TW); PL 4 V.3 (ký số, dấu thời gian) |
| Tác nhân | AC-06 người được kiểm điểm; chủ trì, thư ký, thành viên chi bộ hoặc tập thể (vai trò `member` có phân công trong hội nghị); AC-07 cơ quan tổ chức; AC-08 người ký gói; SVC-11 document-service; SVC-10 esign-service; SVC-13 integration-gateway |
| Tần suất và khối lượng ước tính | Kiểm điểm năm một lần; gói hồ sơ quý và năm. Ước tính khoảng 6 triệu bản kiểm điểm cá nhân mỗi năm toàn quốc, khoảng 1,2 triệu ở cell lớn; mỗi gói đơn vị trung bình khoảng 100 tệp (ví dụ gói mẫu 106 tệp) |
| Điều kiện bắt đầu | Kỳ năm (hoặc quý) có quyết định xếp loại (EVT-12) hoặc kế hoạch kiểm điểm được tạo |
| Điều kiện kết thúc | Gói hồ sơ đã ký số, nộp cấp trên và được tiếp nhận (`DA_LUU_TRU`); gói con diện TW đã gửi thành công |
| Đầu vào | Kết quả xếp loại, snapshot điểm, danh sách đối tượng, biên bản kỳ trước, mẫu biểu trong `docgen.template` theo phiên bản Policy Pack |
| Đầu ra | `dossier.review_meeting`, `dossier.review_comment`, `dossier.form_instance`, `dossier.special_case`, `dossier.dossier_package` có manifest SHA-256 và chữ ký; EVT-17, EVT-18 |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ hội nghị đạt điều kiện gửi dự thảo đúng hạn (mục tiêu 100%); tỷ lệ biểu mẫu sinh thành công lần đầu (≥ 99%); thời gian sinh và ký lô 100.000 phiếu ≤ 4 giờ (NFR-06); tỷ lệ hạn chế kỳ trước được khắc phục |

Theo bản đánh giá kiến trúc, tiêu chí H5 "Kiểm điểm, hồ sơ số" đang ở mức chưa đạt: hệ thống hiện tại không sinh mẫu, không ký số. Trong thực tế, cán bộ tổ chức đang chép số liệu từ hệ thống sang mẫu Word, đây là nguồn gốc của sai sót và của việc điểm trên biểu mẫu không khớp điểm trong hệ thống. QT-11 đảo ngược cách làm: mọi biểu mẫu được sinh từ dữ liệu đã quyết định bởi document-service (SVC-11), mang tem phiên bản Policy Pack, và mỗi tệp có mã băm trong manifest của gói.

Kiểm điểm là sinh hoạt chính trị có yếu tố con người cao, nên nền tảng không thay thế hội nghị mà hỗ trợ trình tự. Hệ thống kiểm tra thời hạn gửi dự thảo theo ngày làm việc của lịch tenant, thu góp ý trước hội nghị, cho thư ký ghi ý kiến tại hội nghị, sinh biên bản, và tự đưa các hạn chế đã nêu thành danh sách theo dõi khắc phục cho kỳ sau.

### Lưu đồ quy trình

![Hình 10.10. Lưu đồ làn bơi QT-11: kiểm điểm, sinh biểu mẫu và gói hồ sơ số](nv-b-qt11-luong.png){width=16cm}

Lưu đồ bắt đầu ở làn "Hệ thống": MOD-06 phát hiện các trường hợp đặc thù của PL 6 dựa trên dữ liệu tổ chức và trạng thái hồ sơ, sau đó document-service sinh dự thảo Mẫu 1A đến 1D đã điền sẵn số liệu. Người được kiểm điểm hoàn thiện phần tự nhận xét và gửi dự thảo. Làn "Chi bộ, tập thể" kiểm tra điều kiện ít nhất 03 ngày làm việc, thu góp ý, tổ chức hội nghị, lập biên bản có ký số và theo dõi khắc phục. Khi hội nghị kết thúc, document-service sinh bản chính thức các mẫu và bảng chấm điểm ở định dạng PDF/A, MOD-06 ghép thành gói hồ sơ có manifest. Làn "Tổ chức, cấp trên" ký số gói, nộp; với hồ sơ diện Trung ương quản lý, gói con được chuyển qua integration-gateway bằng EdXML.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Lập kế hoạch kiểm điểm | AC-07 | Tạo hội nghị: đối tượng, chủ trì, thư ký, thành phần, ngày giờ, địa điểm | SCR-1101 | `dossier.review_meeting` | Theo kế hoạch của cấp uỷ |
| 2 | Phát hiện trường hợp đặc thù | Hệ thống (MOD-06) | Đối chiếu 13 trường hợp PL 6 với dữ liệu tổ chức, phân công, trạng thái hồ sơ; ghi cách xử lý theo Policy Pack | SCR-1102 | `dossier.special_case` | Khi lập kế hoạch và khi dữ liệu thay đổi |
| 3 | Sinh dự thảo bản kiểm điểm | SVC-11 | Sinh Mẫu 1A (tập thể), 1B, 1C, 1D (cá nhân) đã điền điểm, mức, nhiệm vụ, hạn chế kỳ trước | SCR-1101 | `dossier.form_instance` (dự thảo); EVT-17 | Trong 5 phút |
| 4 | Hoàn thiện và gửi dự thảo | AC-06 | Bổ sung tự nhận xét, hướng khắc phục; bấm gửi; hệ thống ghi thời điểm gửi | SCR-1101 | `dossier.review_meeting.draft_sent_at`; EVT-21 | ≥ 03 ngày làm việc trước hội nghị |
| 5 | Kiểm tra thời hạn | Hệ thống | Đếm số ngày làm việc giữa thời điểm gửi và ngày hội nghị theo `config.holiday_calendar` | SCR-1101 | Cờ đạt hoặc không đạt | Ngay khi gửi |
| 6 | Thu góp ý | Thành viên | Đọc dự thảo, gửi góp ý phân loại ưu điểm, hạn chế, kiến nghị | SCR-1101 | `dossier.review_comment` | Đến trước hội nghị |
| 7 | Tổ chức hội nghị | Chủ trì, thư ký | Điểm danh, thư ký ghi ý kiến, biểu quyết mức xếp loại tại hội nghị | SCR-1101 | `dossier.review_comment` (tại hội nghị) | Ngày hội nghị |
| 8 | Lập biên bản, ký số | Thư ký, chủ trì | Sinh biên bản từ dữ liệu hội nghị; chủ trì và thư ký ký số | SCR-1101 | `dossier.form_instance` (biên bản); EVT-17, EVT-18 | 2 ngày làm việc |
| 9 | Ghi nhận hạn chế cần khắc phục | Hệ thống, thư ký | Tạo danh sách hạn chế cần khắc phục để theo dõi ở kỳ sau | SCR-1101 | `dossier.remediation_item` | Cùng bước 8 |
| 10 | Sinh biểu mẫu chính thức | SVC-11 | Sinh Mẫu 1A đến 1D, Mẫu 2, Mẫu 3, bảng chấm điểm ở PDF/A-2b có tem phiên bản | SCR-1102 | `docgen.render_job`, `dossier.form_instance`; EVT-17 | Lô 100.000 phiếu ≤ 4 giờ cùng ký số |
| 11 | Ký số biểu mẫu | Cá nhân, người có thẩm quyền | Mỗi người ký bản kiểm điểm của mình; Trưởng ban ký Mẫu 3 | SCR-1102 | EVT-18 | 3 ngày làm việc |
| 12 | Đóng gói và kiểm tra | MOD-06 | Ghép tệp, tính SHA-256 từng tệp, lập manifest, chạy danh sách kiểm tra trước khi ký gói | SCR-1102 | `dossier.dossier_package` `CHO_KY` | Tự động |
| 13 | Ký số gói và nộp | AC-08 | Ký manifest gói kèm dấu thời gian RFC 3161; gói chuyển `DA_NOP` và bị khoá | SCR-1102 | EVT-18; ghi sổ cái | Hạn nộp cấp trên |
| 14 | Chuyển hồ sơ diện TW | AC-07, SVC-13 | Tách gói con cho hồ sơ diện TW quản lý, mã hoá theo chứng thư bên nhận, gửi EdXML | SCR-1102 | `integ.inbox_message` phía nhận; xác nhận | Theo QĐ Điều 17 |

Bước 5 là nơi quy định "ít nhất 03 ngày làm việc" được kiểm soát bằng máy thay vì bằng sự cẩn thận của thư ký. Với hội nghị mẫu, dự thảo gửi lúc 16:20 ngày 08/01/2027 (thứ Sáu), hội nghị ngày 14/01/2027 (thứ Năm). Các ngày làm việc trọn vẹn ở giữa là 11, 12 và 13/01, tức 3 ngày, nên đạt yêu cầu. Cách đếm (có tính ngày gửi hay không, gửi sau giờ hành chính có tính là ngày hôm đó hay không) là tham số `kiem_diem.cach_dem_ngay` của Policy Pack, mặc định chỉ đếm ngày làm việc trọn vẹn nằm giữa hai mốc.

Bước 10 và 11 là điểm nóng tải cuối năm. document-service chạy dưới dạng worker co giãn bằng KEDA, sinh tệp từ mẫu docx4j, chuyển sang PDF/A qua Gotenberg rồi trả về đối tượng trong Ceph. Thời gian sinh một phiếu ước tính 0,5 đến 1,5 giây; với 100.000 phiếu và 20 worker, thời gian sinh khoảng 1 đến 2 giờ, phần còn lại của ngân sách 4 giờ dành cho ký lô (số ước tính, kiểm chứng bằng kịch bản PERF trong TL-04).

### Trạng thái đối tượng

![Hình 10.11. Sơ đồ trạng thái gói hồ sơ số dossier.dossier_package](nv-b-qt11-trangthai.png){width=13cm}

Baseline mục 9.1 chưa quy định trạng thái của gói hồ sơ, nên tài liệu này đề xuất bộ trạng thái dưới đây và ghi vào tệp ghi chú để TL-03 bổ sung. Gói bắt đầu ở `DANG_LAP`, chuyển `DANG_SINH` khi gửi yêu cầu sinh biểu mẫu, quay lại `DANG_LAP` nếu có tệp sinh lỗi, sang `CHO_KY` khi mọi tệp đã sinh, `DA_KY` khi có chữ ký gói, `DA_NOP` khi đã gửi cấp trên và `DA_LUU_TRU` khi cấp trên tiếp nhận. Gói `DA_NOP` chỉ trở lại `DANG_LAP` qua QT-13, và khi đó gói cũ được giữ nguyên, gói mới có số phiên bản tăng.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Kỳ đã quyết định (EVT-12) | `DANG_LAP` | Một gói cho mỗi đơn vị và kỳ |
| `DANG_LAP` | Yêu cầu sinh biểu mẫu | `DANG_SINH` | Mọi hồ sơ của đơn vị đã có quyết định hoặc thuộc trường hợp đặc thù |
| `DANG_SINH` | Có tệp lỗi | `DANG_LAP` | Ghi lỗi vào `docgen.render_job` |
| `DANG_SINH` | EVT-17 cho tệp cuối cùng | `CHO_KY` | Đủ tệp theo danh mục của Policy Pack |
| `CHO_KY` | EVT-18 chữ ký gói | `DA_KY` | Danh sách kiểm tra đạt |
| `DA_KY` | Nộp cấp trên | `DA_NOP` | Gửi thành công |
| `DA_NOP` | Cấp trên tiếp nhận | `DA_LUU_TRU` | Có biên nhận |
| `DA_NOP` | Mở lại kỳ (EVT-22) | `DANG_LAP` | Tạo phiên bản gói mới, giữ gói cũ |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-11-01 | Dự thảo bản kiểm điểm phải gửi tới thành viên ít nhất 03 ngày làm việc trước hội nghị | QĐ Điều 6, Điều 7; HD mục 2 | Bước 5 tính theo `config.holiday_calendar`; nếu không đạt thì cảnh báo và yêu cầu dời ngày hội nghị hoặc ghi lý do được cấp uỷ chấp thuận |
| BR-11-02 | Biểu mẫu 1A, 1B, 1C, 1D, 2, 3 và bảng chấm điểm phải sinh từ dữ liệu đã quyết định; không nhập tay điểm, mức trên biểu mẫu | PL 4 V.3; tiêu chí H5 | document-service đọc trực tiếp snapshot và bản ghi QUYET_DINH; vùng số liệu trên mẫu bị khoá |
| BR-11-03 | Mỗi biểu mẫu mang tem phiên bản Policy Pack và mẫu biểu đã dùng | Tiêu chí I6 | Chân trang tự chèn; `dossier.form_instance.template_version` |
| BR-11-04 | Nhãn tiêu chí chung trên biểu mẫu theo đúng biểu mẫu ("Đảm bảo, Không đảm bảo" ở Mẫu 1; "Không vi phạm, Vi phạm" ở Mẫu 2) nhưng dùng một dữ liệu gốc | PL A4 | Ánh xạ nhãn trong `docgen.template` |
| BR-11-05 | Hệ thống phát hiện và xử lý 13 trường hợp đặc thù của PL 6 theo cách Policy Pack quy định | PL 6 | Bảng quy tắc `special_case` trong Policy Pack; danh sách hiển thị ở SCR-1102 |
| BR-11-06 | Góp ý là dữ liệu C2; nếu tenant bật chế độ ẩn danh, người được kiểm điểm thấy nội dung nhưng không thấy người góp ý | HD mục 3; ADR-06 | Mã hoá trường; OPA lọc trường người góp ý |
| BR-11-07 | Kết quả biểu quyết tại hội nghị được ghi vào biên bản và Mẫu 3 nhưng không thay mức quyết định của cấp có thẩm quyền | HD mục 4 | Lưu trong `dossier.review_meeting.vote_json`, không ghi vào `rating_proposal` |
| BR-11-08 | Hạn chế nêu trong biên bản phải được theo dõi khắc phục và báo cáo ở kỳ kiểm điểm sau | QĐ Điều 7 | `dossier.remediation_item` với trạng thái; tự hiện ở hội nghị kỳ sau |
| BR-11-09 | Gói hồ sơ chỉ được ký khi mọi hồ sơ đã `DA_KY_SO`, không còn khiếu nại đang mở và mã băm từng tệp khớp manifest | PL 4 V.3; tiêu chí I5 | Danh sách kiểm tra tự động trên SCR-1102 |
| BR-11-10 | Gói đã ký và nộp bị khoá; mọi thay đổi phải qua mở lại kỳ (QT-13) và tạo phiên bản gói mới | PL 4 V.2; tiêu chí I3 | Trạng thái `DA_NOP`; API ghi trả 409 |
| BR-11-11 | Hồ sơ cán bộ diện Trung ương quản lý được chuyển tới Ban Tổ chức Trung ương hoặc Văn phòng Trung ương bằng gói con có ký số và mã hoá theo chứng thư bên nhận | QĐ Điều 17; HD mục 10 | SVC-13 integration-gateway, EdXML; danh sách diện TW lấy từ 40 chức danh PL 2B cấu hình trong `org` |

Bảng dưới liệt kê 13 trường hợp đặc thù mà Policy Pack khối Đảng v2.0 mô hình hoá. Danh sách được tổng hợp từ PL 6 và báo cáo BGĐ (PL A2 nhóm 5); cách xử lý là tham số của Policy Pack, nên khi văn bản chính thức thay đổi chỉ cần phát hành phiên bản mới.

| Mục | Trường hợp | Cách xử lý mặc định |
|---|---|---|
| §1 | Điều động, luân chuyển dưới 2 tháng trong kỳ | Đơn vị cũ đánh giá, đơn vị mới cho ý kiến |
| §2 | Điều động, luân chuyển từ 2 tháng trở lên trong kỳ | Đơn vị mới đánh giá, đơn vị cũ cung cấp kết quả phần thời gian trước |
| §3 | Kiêm nhiệm nhiều chức vụ | Đánh giá tại nơi giữ chức vụ chính, nơi kiêm nhiệm cho ý kiến |
| §4 | Nghỉ thai sản, ốm đau dài ngày trên 50% thời gian kỳ | Không xếp loại kỳ quý; ghi chú trên Mẫu 2 |
| §5 | Đi học, biệt phái dài hạn | Cơ sở đào tạo hoặc nơi biệt phái cung cấp nhận xét; đơn vị quản lý xếp loại |
| §6 | Mới tuyển dụng, mới bổ nhiệm dưới 6 tháng | Đánh giá theo thời gian công tác thực tế, không xét HTXSNV năm |
| §7 | Đang bị xem xét, xác minh, kiểm tra | Tạm treo quyết định đến khi có kết luận |
| §8 | Đã bị kỷ luật trong kỳ | Áp điều kiện loại trừ theo hình thức kỷ luật |
| §9 | Đơn vị mới thành lập, sáp nhập, chia tách trong kỳ | Dùng cây tổ chức theo ngày hiệu lực; cấp trên trực tiếp đánh giá |
| §10 | Nghỉ hưu, thôi việc trong kỳ | Đánh giá đến thời điểm nghỉ nếu đủ thời gian tối thiểu |
| §11 | Đảng viên ngoài hệ thống chính trị (Mẫu 1D) | Áp bộ tiêu chí riêng theo PL 2E |
| §12 | Không đủ điều kiện của mức đề xuất | Chuyển xuống mức liền kề, sinh câu giải thích |
| §13 | Người đứng đầu có đơn vị phụ trách KHTNV trên 50% | Áp quy tắc QĐ khoản 14.3.3 |

### Ngoại lệ và xử lý

Khi dự thảo không được gửi đủ 03 ngày làm việc, hệ thống không tự huỷ hội nghị mà hiển thị MSG-11-01, đề nghị dời ngày. Nếu cấp uỷ quyết định giữ nguyên ngày vì lý do đặc biệt, chủ trì phải ghi lý do và lý do này được in vào biên bản.

Khi document-service sinh lỗi một phần (ví dụ thiếu dữ liệu một trường của Mẫu 1C), gói quay về `DANG_LAP`, các tệp đã sinh được giữ, nút "Sinh lại mẫu lỗi" chỉ sinh lại các tệp lỗi. Gói mẫu đang ở trạng thái 104/106 tệp đã xong.

Khi một cá nhân chưa ký bản kiểm điểm của mình đến hạn, danh sách kiểm tra hiển thị cảnh báo; người ký gói không thể ký cho đến khi đủ hoặc khi có quyết định ghi nhận "không ký" kèm lý do trong biên bản.

Khi gửi gói con diện TW thất bại (bên nhận không phản hồi, chứng thư bên nhận hết hạn), integration-gateway đưa thông điệp vào `integ.dead_letter`, thử lại theo lịch và thông báo cho cơ quan tổ chức. Gói chính vẫn được nộp bình thường.

Khi một hồ sơ trong gói đang có khiếu nại, gói bị chặn ký (BR-11-09). Nếu đến hạn nộp mà khiếu nại chưa kết luận, cơ quan tổ chức có thể nộp gói với hồ sơ đó được đánh dấu "đang khiếu nại" theo tham số của tenant, và gói bổ sung sẽ được nộp sau.

### Đặc tả màn hình

#### SCR-1101. Hội nghị kiểm điểm

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Điều hành trình tự kiểm điểm: kiểm tra thời hạn gửi dự thảo, thu góp ý, ghi ý kiến tại hội nghị, biểu quyết, sinh biên bản và theo dõi khắc phục |
| Tác nhân | Thư ký hội nghị (vai trò `appraiser` hoặc `member` được phân công), chủ trì, thành viên, người được kiểm điểm |
| Đường vào | Menu Hồ sơ số › Kiểm điểm; thông báo mời góp ý |
| Quyền | Thành viên hội nghị xem dự thảo và góp ý; chỉ thư ký ghi ý kiến tại hội nghị; chỉ chủ trì kết thúc hội nghị |
| Thiết bị | Web desktop, máy tính bảng tại phòng họp |

![Hình 10.12. SCR-1101 Hội nghị kiểm điểm cuối năm 2026 của Chi bộ Ban Tổ chức](SCR-1101.png){width=16cm}

Thanh bước năm bước (gửi dự thảo, thu góp ý, hội nghị, biên bản và ký số, theo dõi khắc phục) cho biết hội nghị đang ở bước 3. Màn hình chia ba cột: thông tin thủ tục bên trái, nội dung góp ý ở giữa, kết quả bên phải.

Vùng 1 là thẻ "Kiểm tra thời hạn gửi dự thảo" hiển thị thời điểm gửi 08/01/2027 16:20, ngày hội nghị 14/01/2027, số ngày làm việc ở giữa là 3 và kết luận màu xanh "Đạt yêu cầu ≥ 03 ngày làm việc (QĐ Điều 6)"; bên dưới là bảng thành phần dự với trạng thái từng người (đã góp ý, đã đọc, chưa mở, vắng có lý do). Vùng 2 là danh sách góp ý phân loại ưu điểm, hạn chế, kiến nghị, gồm góp ý trước hội nghị và ý kiến thư ký ghi tại hội nghị, kèm ô nhập và ghi chú về chế độ ẩn danh. Vùng 3 là thẻ "Khắc phục hạn chế kỳ trước" lấy từ `dossier.remediation_item` với trạng thái từng hạn chế. Vùng 4 là thẻ biểu quyết: mức đề xuất của hệ thống HTTNV và kết quả biểu quyết (HTXSNV 1, HTTNV 5, HTNV 0), kèm nhắc rằng biểu quyết không thay mức quyết định.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Đối tượng kiểm điểm | Nhãn | Không áp dụng | Họ tên, chức vụ | Từ kế hoạch | Chỉ đọc | `dossier.review_meeting.subject_person_id` |
| 2 | Ngày giờ, địa điểm hội nghị | Ngày giờ, văn bản | Có | dd/mm/yyyy hh:mm | Trống | Sau thời điểm gửi dự thảo ≥ 3 ngày làm việc | `dossier.review_meeting.meeting_at` |
| 3 | Thời điểm gửi dự thảo | Ngày giờ | Không áp dụng | dd/mm/yyyy hh:mm | Ghi khi gửi | Chỉ đọc | `dossier.review_meeting.draft_sent_at` |
| 4 | Số ngày làm việc ở giữa | Số | Không áp dụng | Số nguyên | Tính tự động | Theo BR-11-01 | Tính từ `config.holiday_calendar` |
| 5 | Thành phần, trạng thái | Bảng | Không áp dụng | Họ tên, trạng thái | Từ phân công | Chỉ đọc | `dossier.review_meeting.participants_json` |
| 6 | Góp ý | Văn bản dài, loại | Có khi gửi góp ý | ≤ 4.000 ký tự, C2; loại ưu điểm, hạn chế, kiến nghị | Trống | Mã hoá trường | `dossier.review_comment.content_enc`, `dossier.review_comment.kind` |
| 7 | Ý kiến tại hội nghị | Văn bản dài | Không | ≤ 4.000 ký tự, C2 | Trống | Chỉ thư ký nhập | `dossier.review_comment` (phase = HOI_NGHI) |
| 8 | Hạn chế kỳ trước | Danh sách | Không áp dụng | Nội dung, trạng thái | Từ biên bản kỳ trước | Thư ký cập nhật trạng thái | `dossier.remediation_item` |
| 9 | Kết quả biểu quyết | Số theo mức | Có khi kết thúc | Số nguyên | 0 | Tổng ≤ số người có mặt | `dossier.review_meeting.vote_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Gửi dự thảo | Người được kiểm điểm, trước hội nghị | Ghi `draft_sent_at`, gửi thông báo, chạy kiểm tra thời hạn | `POST /api/v1/dossiers/meetings/{id}/send-draft` | EVT-21 |
| Tải dự thảo Mẫu 1B | Thành viên | Tải bản dự thảo, ghi nhật ký truy cập | `GET /api/v1/dossiers/forms/{formId}/file` | Không có |
| Gửi góp ý | Thành viên, trước hoặc trong hội nghị | Lưu góp ý mã hoá | `POST /api/v1/dossiers/meetings/{id}/comments` | Không có |
| Sinh biên bản | Thư ký, sau khi có biểu quyết | Gọi document-service sinh biên bản | `POST /api/v1/dossiers/meetings/{id}/minutes` (202) | EVT-17 |
| Kết thúc hội nghị | Chủ trì | Khoá góp ý, tạo `remediation_item` từ các hạn chế, yêu cầu ký biên bản | `POST /api/v1/dossiers/meetings/{id}/close` | EVT-11, EVT-18 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-11-01 | Dự thảo được gửi {so_ngay} ngày làm việc trước hội nghị, chưa đủ 03 ngày theo QĐ Điều 6. Đề nghị dời ngày hội nghị hoặc ghi lý do được cấp uỷ chấp thuận. | Cảnh báo | Kiểm tra thời hạn không đạt |
| MSG-11-02 | Đồng chí được mời góp ý cho bản kiểm điểm của đồng chí {ten} trước ngày {ngay}. | Thông báo đẩy | Gửi dự thảo |
| MSG-11-03 | Đã kết thúc hội nghị. Biên bản đang được sinh và sẽ gửi chủ trì, thư ký ký số. | Thành công | Kết thúc hội nghị |

#### SCR-1102. Gói hồ sơ số và ký số

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Theo dõi việc sinh biểu mẫu, xử lý trường hợp đặc thù, kiểm tra trước khi ký, ký số gói và nộp cấp trên, chuyển gói con diện TW |
| Tác nhân | AC-07 chuẩn bị; AC-08 ký gói |
| Đường vào | Menu Hồ sơ số › Gói hồ sơ số; thông báo "Gói hồ sơ sẵn sàng ký" |
| Quyền | `appraiser` của đơn vị chuẩn bị; người có thẩm quyền ký gói; MFA bước lên khi ký |
| Thiết bị | Web desktop |

![Hình 10.13. SCR-1102 Gói hồ sơ số năm 2026 DP-2026-N-0142](SCR-1102.png){width=16cm}

Màn hình chia hai cột: nội dung gói bên trái, kiểm tra và hành động bên phải. Dòng phụ đầu trang cho biết mã gói DP-2026-N-0142 và hạn nộp cấp trên 01/02/2027.

Vùng 1 là bảng "Biểu mẫu sinh từ dữ liệu" với nhãn tiến độ "document-service · 104/106 tệp xong"; mỗi dòng là một mẫu (1A, 1B, 1C, 1D, 2, 3, bảng chấm điểm), đối tượng, trạng thái và định dạng "PDF/A-2b · tem v2.0". Vùng 2 là bảng trường hợp đặc thù phát hiện trong kỳ: 5 trên 13 trường hợp có phát sinh (§1, §4, §7, §9, §12) với tổng 13 hồ sơ, mỗi dòng nêu cách xử lý theo Policy Pack. Vùng 3 là danh sách kiểm tra trước khi ký gói: 48/48 hồ sơ `DA_KY_SO`, mã băm khớp manifest, Mẫu 1C còn 2 người chưa ký, Mẫu 3 chờ Trưởng ban ký, không còn khiếu nại mở. Vùng 4 là thẻ chuyển hồ sơ diện TW: 2 hồ sơ, nơi nhận Ban Tổ chức Trung ương, trạng thái "Chưa gửi". Vùng 5 là thẻ ký số gói với người ký Ngô Thanh Tâm, Viettel-CA, dấu thời gian RFC 3161 và mã băm gói của 106 tệp.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã gói, hạn nộp | Nhãn | Không áp dụng | DP-yyyy-{Q/N}-nnnn | Sinh tự động | Duy nhất trong tenant | `dossier.dossier_package.code`, `dossier.dossier_package.due_date` |
| 2 | Danh sách biểu mẫu | Bảng | Không áp dụng | Mẫu, đối tượng, trạng thái | Từ dữ liệu | Chỉ đọc | `dossier.form_instance`, `docgen.render_job` |
| 3 | Trường hợp đặc thù | Bảng | Không áp dụng | Mục, cách xử lý, số hồ sơ | Phát hiện tự động | Chỉ đọc; xem chi tiết từng hồ sơ | `dossier.special_case` |
| 4 | Danh sách kiểm tra | Danh sách | Không áp dụng | Đạt hoặc cảnh báo | Tính tức thời | Theo BR-11-09 | Tính từ `appraisal.appraisal_profile`, `dossier.form_instance`, `oversight.complaint` |
| 5 | Nơi nhận gói con diện TW | Danh sách chọn | Có khi có hồ sơ diện TW | Ban Tổ chức TW hoặc Văn phòng TW | Theo chức danh | Chỉ đơn vị nhận đã cấu hình kết nối | `integ.connector` |
| 6 | Người ký, nhà cung cấp | Nhãn | Không áp dụng | Họ tên, CA | Từ esign | Chứng thư còn hạn | `esign.certificate_ref` |
| 7 | Mã băm gói | Nhãn | Không áp dụng | SHA-256 rút gọn, số tệp | Tính khi đóng gói | Băm của manifest | `dossier.dossier_package.manifest_hash` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Sinh lại mẫu lỗi | Có tệp lỗi | Gửi lại các render job lỗi | `POST /api/v1/dossiers/packages/{id}/regenerate` (202) | EVT-17 |
| Xem biểu mẫu | Tệp đã sinh | Mở trình xem PDF/A, ghi nhật ký truy cập | `GET /api/v1/dossiers/forms/{formId}/file` | Không có |
| Tải gói (ZIP và manifest) | Gói từ `CHO_KY` trở đi | Tạo tệp ZIP kèm manifest JSON có chữ ký | `POST /api/v1/dossiers/packages/{id}/export` (202) | Không có |
| Gửi gói con | Có hồ sơ diện TW, gói đã ký | Tách, mã hoá, gửi qua integration-gateway | `POST /api/v1/dossiers/packages/{id}/transfers` (202) | Không có (SVC-13 ghi `integ`) |
| Ký số gói và nộp | Danh sách kiểm tra đạt | MFA bước lên, ký manifest, gắn dấu thời gian, chuyển `DA_NOP` | `POST /api/v1/dossiers/packages/{id}/sign` (202), `POST /api/v1/esign/requests` | EVT-18; ghi sổ cái |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-11-04 | Chưa thể ký gói: {so_muc} mục kiểm tra chưa đạt. Xem chi tiết ở danh sách kiểm tra. | Lỗi kiểm tra | Bấm ký khi chưa đạt |
| MSG-11-05 | Gói {ma_goi} đã được ký số và nộp lúc {thoi_diem}. Gói đã bị khoá; mọi thay đổi phải qua mở lại kỳ. | Thành công | Ký gói xong |
| MSG-11-06 | Gửi gói con tới {noi_nhan} chưa thành công: {ly_do}. Hệ thống sẽ tự thử lại lúc {thoi_diem}. | Cảnh báo | SVC-13 lỗi |
| MSG-11-07 | Có {n} tệp sinh lỗi. Đồng chí có thể bấm "Sinh lại mẫu lỗi" sau khi bổ sung dữ liệu. | Cảnh báo | render job lỗi |

### Liên kết thiết kế

QT-11 do MOD-06 `dossier` điều phối (bảng `review_meeting`, `review_comment`, `form_instance`, `special_case`, `dossier_package` và bảng đề xuất mới `remediation_item`), dùng SVC-11 document-service (schema `docgen`: `template`, `render_job`, `render_output`), SVC-10 esign-service, SVC-08 evidence-service (lưu tệp PDF/A với Object Lock), SVC-13 integration-gateway (EdXML, `integ.connector`, `integ.dead_letter`) và SVC-09 ledger-service. Sự kiện: EVT-12 (đầu vào), EVT-17, EVT-18, EVT-21, EVT-22 (mở lại kỳ). Đáp ứng tiêu chí H5 (sinh mẫu, ký số, 13 trường hợp, đóng gói), I4 (ký số), I5 (tệp bất biến có mã băm) và I8 (dấu thời gian RFC 3161 trên chữ ký gói). Năng lực sinh và ký 100.000 phiếu trong 4 giờ là chỉ tiêu NFR-06, kịch bản đo ở TL-04.

# Quy trình giám sát và hậu kiểm

Chương này đặc tả ba quy trình giữ cho kết quả đánh giá đáng tin cậy sau khi đã được tính và quyết định: khiếu nại, giải trình (QT-12), hậu kiểm và mở lại kỳ có hai người duyệt (QT-13), cảnh báo bất thường (QT-14). Ba quy trình này tương ứng với nhóm tính năng 6 "Giám sát, hậu kiểm" của báo cáo BGĐ và trả lời trực tiếp các tiêu chí H6, I1, I2, I3 của bản đánh giá kiến trúc, vốn đang ở mức "Chưa đạt" hoặc "Một phần" vì hệ thống hiện tại chỉ khoá từng hồ sơ và ghi đè dữ liệu tại chỗ.

Ba quy trình dùng chung một nguyên tắc: kỳ đã khoá thì không ai sửa được dữ liệu, kể cả quản trị viên. Mọi điều chỉnh sau khoá kỳ đều phải đi qua một cửa duy nhất là yêu cầu mở lại kỳ, có lý do, có phạm vi giới hạn, có hai người có thẩm quyền khác nhau cùng duyệt và được ghi vào sổ cái chuỗi băm. Khiếu nại được chấp nhận (QT-12) và cảnh báo được kết luận có vi phạm (QT-14) đều kết thúc bằng việc tạo yêu cầu mở lại kỳ (QT-13), chứ không có đường tắt nào khác.

## QT-12. Khiếu nại, giải trình

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-12 |
| Tên | Khiếu nại, giải trình |
| Mục đích | Cho cán bộ khiếu nại kết quả đánh giá, giải trình bổ sung hoặc bảo lưu ý kiến; cho cơ quan có thẩm quyền tiếp nhận, yêu cầu giải trình các bên, kết luận và trả lời bằng văn bản có ký số |
| Phạm vi áp dụng | Kết quả xếp loại quý, năm; kết quả nghiệm thu chất lượng; kết quả phân nhóm (chỉ bảo lưu ý kiến) |
| Căn cứ | Dự thảo Quy định mới khoản 16.1 (khiếu nại, giải trình), khoản 16.2 (bảo lưu ý kiến, trả lời bằng văn bản); PL 4 V.1 (tách vai trò); PL 3 mục 2 (miễn trừ chậm do khách quan) |
| Tác nhân | AC-06 người khiếu nại; AC-07 người xử lý (được chỉ định tự động); AC-05 người nghiệm thu, AC-07 người thẩm định với vai trò bên giải trình; AC-08 người ký văn bản trả lời |
| Tần suất và khối lượng ước tính | Phát sinh sau mỗi kỳ công bố kết quả. Giả định tỷ lệ khiếu nại khoảng 0,5% số hồ sơ (chưa có số đo), tương đương khoảng 30.000 khiếu nại mỗi quý toàn quốc, khoảng 6.000 ở cell lớn |
| Điều kiện bắt đầu | Hồ sơ ở `DA_KY_SO` và còn trong thời hạn khiếu nại (tham số, mặc định 10 ngày làm việc từ ngày công bố) |
| Điều kiện kết thúc | Văn bản trả lời đã ký số và gửi người khiếu nại; nếu có điều chỉnh thì yêu cầu mở lại kỳ đã thi hành xong (QT-13); hồ sơ khiếu nại `DONG` |
| Đầu vào | Kết quả bị khiếu nại, cấu phần cụ thể, lý do, tài liệu kèm theo |
| Đầu ra | `oversight.complaint`, `oversight.complaint_response`, văn bản trả lời PDF/A ký số, yêu cầu mở lại kỳ nếu có điều chỉnh, ý kiến bảo lưu lưu vào hồ sơ |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ khiếu nại được trả lời đúng hạn (mục tiêu 100% trong 15 ngày làm việc); tỷ lệ khiếu nại được chấp nhận toàn phần hoặc một phần; thời gian trung bình xử lý; tỷ lệ khiếu nại lặp lại cùng nội dung |

Khiếu nại trong hệ thống hiện tại được xử lý ngoài hệ thống bằng văn bản giấy, nên kết quả khiếu nại không gắn được với kết quả đánh giá và không ai thống kê được khiếu nại theo nội dung. QT-12 đưa khiếu nại thành một đối tượng có vòng đời riêng (`oversight.complaint`) gắn với hồ sơ đánh giá và với cấu phần cụ thể bị khiếu nại (ví dụ hệ số C của một nhiệm vụ). Nhờ gắn đến cấu phần, người xử lý tính thử được ảnh hưởng của việc chấp nhận khiếu nại ngay trên màn hình, và cơ quan kiểm tra thống kê được loại cấu phần nào bị khiếu nại nhiều nhất để điều chỉnh hướng dẫn nghiệm thu.

Trường `kind` của khiếu nại phân biệt ba hình thức theo khoản 16.1 và 16.2: khiếu nại (đề nghị xem xét lại), giải trình bổ sung (cung cấp thêm thông tin, không đề nghị thay đổi), bảo lưu ý kiến (không đồng ý nhưng không đề nghị xử lý, chỉ yêu cầu lưu vào hồ sơ). Hồ sơ mẫu dùng xuyên suốt là khiếu nại KN-2027-0031 của đồng chí Trần Minh Hoàng, đề nghị không trừ hệ số C của nhiệm vụ then chốt 3 vì chậm do Đảng uỷ cơ sở X gửi hồ sơ muộn, có công văn 45-CV/ĐU làm căn cứ.

### Lưu đồ quy trình

![Hình 11.1. Lưu đồ làn bơi QT-12: khiếu nại, giải trình và trả lời bằng văn bản](nv-b-qt12-luong.png){width=16cm}

Người khiếu nại chọn kết quả và cấu phần, nêu lý do, đính kèm tài liệu, chọn có bảo lưu ý kiến hay không, rồi ký số và gửi. Hệ thống chuyển hồ sơ đánh giá sang nhánh `KHIEU_NAI`, phát EVT-25 để workflow-service khởi động mẫu luồng `xu-ly-khieu-nai` v2 và chọn người xử lý theo quy tắc tách vai trò. Người xử lý tiếp nhận, yêu cầu giải trình các bên liên quan, tính thử phương án điều chỉnh và đưa ra kết luận. Kết luận "giữ nguyên" đi thẳng tới bước ký văn bản trả lời. Kết luận "điều chỉnh" tạo sẵn yêu cầu mở lại kỳ (QT-13) rồi mới ký văn bản trả lời. Người khiếu nại nhận văn bản; nếu vẫn không đồng ý, ý kiến bảo lưu được lưu vào hồ sơ và in trên Mẫu 3.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Soạn khiếu nại | AC-06 | Chọn kết quả, hình thức, cấu phần; nêu lý do, đề nghị cụ thể; đính kèm tài liệu (lưu ở evidence-service, có SHA-256) | SCR-1201 | `oversight.complaint` nháp | Trong thời hạn khiếu nại |
| 2 | Ký số và gửi | AC-06, SVC-10 | Ký số nội dung khiếu nại; hồ sơ đánh giá chuyển `KHIEU_NAI` | SCR-1201 | EVT-25, EVT-18 | Như trên |
| 3 | Chỉ định người xử lý | Hệ thống (SVC-06, OPA) | Chọn người có vai trò `appraiser` của cấp có thẩm quyền, loại trừ người đã nghiệm thu, thẩm định, quyết định hồ sơ | Không có | Task giao cho người xử lý; EVT-21 | Ngay khi gửi |
| 4 | Tiếp nhận, phân loại | AC-07 | Kiểm tra thẩm quyền, thời hạn; tiếp nhận hoặc không thụ lý có lý do | SCR-1202 | `complaint.status = DA_TIEP_NHAN` hoặc `KHONG_THU_LY` | 2 ngày làm việc |
| 5 | Yêu cầu giải trình | AC-07 | Gửi yêu cầu giải trình bằng văn bản tới người nghiệm thu, đơn vị liên quan | SCR-1202 | `DANG_GIAI_TRINH`; EVT-21 | Hạn giải trình 3 ngày làm việc |
| 6 | Gửi giải trình | Bên liên quan | Trả lời yêu cầu, đính kèm tài liệu | SCR-1202 (chế độ bên liên quan) | `oversight.complaint_response` (loại giải trình) | Theo hạn bước 5 |
| 7 | Tính thử điều chỉnh | AC-07, SVC-07 | Nhập phương án điều chỉnh, gọi scoring-service tính thử với Policy Pack của kỳ, không ghi | SCR-1202 | Kết quả tính thử (không lưu snapshot) | Trong bước 8 |
| 8 | Kết luận | AC-07 | Chọn kết luận: giữ nguyên, chấp nhận một phần, chấp nhận toàn bộ; sinh văn bản trả lời từ mẫu | SCR-1202 | `CHO_KET_LUAN`; `complaint_response` (kết luận) | Tổng thời gian ≤ 15 ngày làm việc |
| 9 | Tạo yêu cầu mở lại kỳ | AC-07 | Nếu có điều chỉnh và kỳ đã khoá, tạo yêu cầu mở lại kỳ gắn khiếu nại | SCR-1202, SCR-1301 | `oversight.reopen_request` | Cùng bước 8 |
| 10 | Ký văn bản trả lời | AC-08 | Ký số văn bản trả lời | SCR-1202 | `DA_TRA_LOI`; EVT-18 | Trong thời hạn 15 ngày làm việc |
| 11 | Nhận trả lời, bảo lưu | AC-06 | Đọc văn bản; nếu đã chọn bảo lưu và không đồng ý thì xác nhận bảo lưu | SCR-1201 | Ý kiến bảo lưu lưu vào hồ sơ | 5 ngày làm việc |
| 12 | Đóng khiếu nại | Hệ thống | Đóng khi giữ nguyên hoặc bảo lưu; khi có điều chỉnh thì đóng sau khi QT-13 hoàn tất | Không có | `DONG`; hồ sơ đánh giá về `DA_KY_SO` hoặc `DA_KHOA` | Tự động |

Bước 3 thể hiện cách hệ thống thực thi quy tắc tách vai trò của PL 4 V.1 mà không dựa vào sự tự giác. Mẫu luồng không gán task cho một người cố định mà gọi một quyết định OPA với đầu vào là danh sách người đã tham gia hồ sơ (lấy từ `appraisal.rating_proposal`, `product.acceptance`) và danh sách người có vai trò `appraiser` của cấp có thẩm quyền; OPA trả về những ứng viên hợp lệ, workflow-service chọn người có ít việc nhất. Trong ví dụ, người xử lý là Lê Thu Hà vì đồng chí không thẩm định hồ sơ của Trần Minh Hoàng ở quý IV.

Bước 7 cho phép người xử lý trả lời câu hỏi "nếu chấp nhận thì kết quả thay đổi thế nào" trước khi kết luận. Với KN-2027-0031, nếu bỏ trừ C của nhiệm vụ then chốt 3 thì C từ 0,85 thành 1,00, KPI nhiệm vụ từ 0,950 thành 1,000, KPI nhóm then chốt từ 0,908 thành 0,921 và điểm từ 91,2 thành 92,1. Mức vẫn là HTTNV vì tỷ lệ việc vượt 22% vẫn dưới 30%. Kết quả tính thử dùng Policy Pack Đảng v2.0, đúng phiên bản đã dùng khi khoá kỳ.

### Trạng thái đối tượng

![Hình 11.2. Sơ đồ trạng thái khiếu nại oversight.complaint](nv-b-qt12-trangthai.png){width=13cm}

Baseline chưa quy định trạng thái chi tiết của khiếu nại, nên tài liệu này đề xuất bộ trạng thái dưới đây (ghi trong tệp ghi chú). Từ `MOI`, khiếu nại được tiếp nhận hoặc không thụ lý (quá hạn, sai thẩm quyền). Sau tiếp nhận, khiếu nại có thể đi qua bước giải trình hoặc đi thẳng tới chờ kết luận. Văn bản trả lời đã ký đưa khiếu nại sang `DA_TRA_LOI`; nếu có điều chỉnh, khiếu nại chờ thi hành ở `DANG_THI_HANH` cho tới khi việc mở lại kỳ và tính lại hoàn tất.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Ký số và gửi | `MOI` | Trong thời hạn khiếu nại; hồ sơ `DA_KY_SO` |
| `MOI` | Phân công tiếp nhận | `DA_TIEP_NHAN` | Người xử lý hợp lệ theo OPA |
| `MOI` | Không thụ lý | `KHONG_THU_LY` | Có lý do (quá hạn, sai thẩm quyền, trùng nội dung đã trả lời) |
| `DA_TIEP_NHAN` | Yêu cầu giải trình | `DANG_GIAI_TRINH` | Có ít nhất một bên được yêu cầu |
| `DANG_GIAI_TRINH` | Đủ giải trình hoặc hết hạn | `CHO_KET_LUAN` | Mọi yêu cầu đã trả lời hoặc quá hạn |
| `DA_TIEP_NHAN` | Không cần giải trình | `CHO_KET_LUAN` | Có ghi chú |
| `CHO_KET_LUAN` | Ký văn bản trả lời | `DA_TRA_LOI` | Văn bản sinh từ mẫu, đã ký số |
| `DA_TRA_LOI` | Có điều chỉnh | `DANG_THI_HANH` | Có `reopen_request` gắn khiếu nại |
| `DANG_THI_HANH` | Mở lại kỳ, tính lại xong | `DONG` | `reopen_request` hoàn tất |
| `DA_TRA_LOI` | Giữ nguyên hoặc bảo lưu ý kiến | `DONG` | Hết hạn xác nhận hoặc người khiếu nại xác nhận |
| `KHONG_THU_LY` | Tự động | `DONG` | Đã gửi thông báo không thụ lý |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-12-01 | Cán bộ được khiếu nại kết quả đánh giá của mình trong thời hạn quy định kể từ ngày công bố | QĐ khoản 16.1 | Tham số `khieu_nai.thoi_han` (mặc định 10 ngày làm việc); nút gửi khoá khi quá hạn |
| BR-12-02 | Khiếu nại phải chỉ rõ kết quả, cấu phần bị khiếu nại, lý do, căn cứ và đề nghị cụ thể | QĐ khoản 16.1 | Trường bắt buộc; ít nhất một cấu phần được chọn |
| BR-12-03 | Có ba hình thức: khiếu nại, giải trình bổ sung, bảo lưu ý kiến | QĐ khoản 16.1, 16.2 | Trường `oversight.complaint.kind` |
| BR-12-04 | Người xử lý không được là người đã nghiệm thu, thẩm định, đề xuất hoặc quyết định hồ sơ bị khiếu nại | PL 4 V.1 | OPA khi giao task và khi gọi API kết luận |
| BR-12-05 | Mọi khiếu nại phải được trả lời bằng văn bản có ký số trong thời hạn quy định | QĐ khoản 16.2 | Mẫu luồng có hạn 15 ngày làm việc, nhắc và chuyển cấp khi quá hạn; văn bản sinh từ mẫu TL-KN-01 |
| BR-12-06 | Trong khi khiếu nại chưa kết luận, kết quả hiện hành vẫn có hiệu lực; gói hồ sơ chứa hồ sơ bị khiếu nại bị chặn ký | QĐ Điều 16; BR-11-09 | Trạng thái `KHIEU_NAI` của hồ sơ; danh sách kiểm tra gói |
| BR-12-07 | Điều chỉnh do chấp nhận khiếu nại với kỳ đã khoá chỉ được thi hành qua mở lại kỳ có hai người duyệt | PL 4 V.2; tiêu chí I3 | Không có API điều chỉnh trực tiếp; nút "Tạo yêu cầu mở lại kỳ" |
| BR-12-08 | Tính thử phải dùng đúng phiên bản Policy Pack của kỳ bị khiếu nại | Tiêu chí I6 | scoring-service nhận `policy_version` từ snapshot gốc |
| BR-12-09 | Người khiếu nại không đồng ý với trả lời được bảo lưu ý kiến; ý kiến bảo lưu được lưu vào hồ sơ và thể hiện trên Mẫu 3 | QĐ khoản 16.2 | `oversight.complaint_response` loại BAO_LUU; document-service in vào Mẫu 3 |
| BR-12-10 | Nội dung khiếu nại, giải trình là dữ liệu C2; mỗi lần giải mã để đọc được ghi sổ cái | ADR-06 | Mã hoá trường; nhật ký truy cập |
| BR-12-11 | Khiếu nại cùng nội dung đã được trả lời thì không thụ lý lại, trừ khi có căn cứ mới | Thực tiễn giải quyết khiếu nại | Hệ thống so khớp cấu phần và gợi ý trùng; người xử lý quyết định |

### Ngoại lệ và xử lý

Nếu khiếu nại được gửi sau thời hạn, hệ thống không cho gửi dưới hình thức khiếu nại nhưng vẫn cho gửi dưới hình thức bảo lưu ý kiến, vì ý kiến bảo lưu không làm phát sinh nghĩa vụ xem xét lại.

Nếu không tìm được người xử lý hợp lệ (ví dụ đơn vị nhỏ chỉ có một cán bộ tổ chức đã thẩm định hồ sơ), OPA trả về danh sách rỗng; mẫu luồng chuyển lên cơ quan tổ chức cấp trên trực tiếp theo cây tổ chức và ghi lý do chuyển cấp.

Nếu bên được yêu cầu giải trình không trả lời đúng hạn, khiếu nại vẫn chuyển sang `CHO_KET_LUAN` và văn bản trả lời ghi nhận việc không có giải trình.

Nếu kỳ chưa khoá (khiếu nại kết quả nghiệm thu trong kỳ), điều chỉnh được thi hành bằng nghiệm thu lại qua QT-07 mà không cần mở lại kỳ.

Nếu người khiếu nại rút khiếu nại trước khi có kết luận, khiếu nại chuyển `DONG` với lý do "rút", hồ sơ đánh giá trở lại `DA_KY_SO`, nội dung khiếu nại vẫn được lưu.

### Đặc tả màn hình

#### SCR-1201. Gửi khiếu nại

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cán bộ soạn, ký số và gửi khiếu nại, giải trình bổ sung hoặc bảo lưu ý kiến về kết quả đánh giá; theo dõi tiến trình xử lý |
| Tác nhân | AC-06 (`member`) |
| Đường vào | Nút "Gửi khiếu nại" trên SCR-0903; thông báo kết quả xếp loại |
| Quyền | Chủ hồ sơ; hồ sơ ở `DA_KY_SO` |
| Thiết bị | Web desktop và di động |

![Hình 11.3. SCR-1201 Gửi khiếu nại kết quả xếp loại quý IV/2026](SCR-1201.png){width=16cm}

Dòng phụ đầu trang cho biết kết quả công bố ngày 12/01/2027 theo QĐ 215-QĐ/ĐUK và hạn gửi khiếu nại 22/01/2027, còn 7 ngày làm việc. Biểu mẫu chiếm cột trái, thông tin quy trình ở cột phải.

Vùng 1 là tiêu đề và dòng thời hạn. Vùng 2 gồm hai trường "Kết quả bị khiếu nại" (Xếp loại quý IV/2026, HTTNV, 91,2 điểm) và "Hình thức" (khiếu nại, giải trình bổ sung, bảo lưu ý kiến). Vùng 3 là nhóm chip cấu phần: cán bộ đã chọn "Nhiệm vụ then chốt 2 · B = 0,50" và "Nhiệm vụ then chốt 3 · C = 0,85"; tiếp theo là ô lý do, căn cứ (mã hoá C2, tối đa 8.000 ký tự), ô đề nghị cụ thể, tài liệu kèm theo có mã băm và hộp đánh dấu bảo lưu ý kiến. Vùng 4 là thẻ "Quy trình xử lý" với năm bước và thời hạn của từng bước. Vùng 5 là thẻ "Ảnh hưởng khi gửi" và ghi chú tách vai trò: hồ sơ chuyển nhánh `KHIEU_NAI`, kết quả hiện hành vẫn hiệu lực, gói hồ sơ năm bị chặn ký, người xử lý do hệ thống chọn.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kết quả bị khiếu nại | Danh sách chọn | Có | Kỳ, mức, điểm | Kết quả mới nhất | Chỉ kết quả còn hạn khiếu nại | `oversight.complaint.profile_id` |
| 2 | Hình thức | Danh sách chọn | Có | KHIEU_NAI, GIAI_TRINH, BAO_LUU | KHIEU_NAI | Quá hạn thì chỉ còn BAO_LUU | `oversight.complaint.kind` |
| 3 | Cấu phần bị khiếu nại | Chip chọn nhiều | Có | Danh sách cấu phần của snapshot | Trống | Ít nhất 1 | `oversight.complaint.targets_json` |
| 4 | Lý do, căn cứ | Văn bản dài | Có | 50 đến 8.000 ký tự, C2 | Trống | Mã hoá trường | `oversight.complaint.content_enc` |
| 5 | Đề nghị cụ thể | Văn bản | Có | ≤ 500 ký tự | Trống | Không có | `oversight.complaint.request_text` |
| 6 | Tài liệu kèm theo | Tệp | Không | PDF, DOCX, EML, ảnh; ≤ 20 MB mỗi tệp | Trống | Quét mã độc, tính SHA-256 | `evidence.object_meta` (tham chiếu từ `oversight.complaint.attachments_json`) |
| 7 | Bảo lưu ý kiến nếu không được chấp nhận | Hộp đánh dấu | Không | Boolean | Không chọn | Không có | `oversight.complaint.reserve_opinion` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | Khiếu nại chưa gửi | Lưu nháp, mã hoá nội dung | `POST /api/v1/oversight/complaints` hoặc `PUT /api/v1/oversight/complaints/{id}` | Không có |
| Thêm tệp | Chưa gửi | Tải lên dạng luồng qua evidence-service | `POST /api/v1/files` | Không có |
| Ký số và gửi | Đủ trường bắt buộc, còn hạn | Ký số, chuyển hồ sơ sang `KHIEU_NAI`, khởi động mẫu luồng | `POST /api/v1/oversight/complaints/{id}/submit` (202) | EVT-25, EVT-18 |
| Rút khiếu nại | Trước khi có kết luận | Đóng khiếu nại với lý do rút | `POST /api/v1/oversight/complaints/{id}/withdraw` | EVT-21 |
| Xác nhận bảo lưu | Sau khi có trả lời, đã chọn bảo lưu | Lưu ý kiến bảo lưu vào hồ sơ | `POST /api/v1/oversight/complaints/{id}/reserve` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-12-01 | Đã quá thời hạn khiếu nại ({han}). Đồng chí vẫn có thể gửi ý kiến dưới hình thức bảo lưu ý kiến. | Cảnh báo | Mở sau hạn |
| MSG-12-02 | Đã gửi khiếu nại {ma_kn}. Kết quả hiện hành vẫn có hiệu lực cho tới khi có kết luận. Hạn trả lời: {han_tra_loi}. | Thành công | Gửi xong |
| MSG-12-03 | Vui lòng chọn ít nhất một cấu phần bị khiếu nại. | Lỗi kiểm tra | Thiếu trường 3 |
| MSG-12-04 | Khiếu nại {ma_kn} đã có văn bản trả lời. Đồng chí có {so_ngay} ngày làm việc để xác nhận hoặc bảo lưu ý kiến. | Thông báo đẩy | Văn bản trả lời được ký |

#### SCR-1202. Xử lý khiếu nại

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người xử lý tiếp nhận, theo dõi dòng thời gian, yêu cầu và đọc giải trình, tính thử điều chỉnh, kết luận, sinh và ký văn bản trả lời, tạo yêu cầu mở lại kỳ |
| Tác nhân | AC-07 người xử lý; bên liên quan ở chế độ giải trình; AC-08 ký văn bản |
| Đường vào | Menu Giám sát › Khiếu nại; thông báo task mới |
| Quyền | Người xử lý được chỉ định; OPA kiểm tra tách vai trò ở mọi thao tác ghi |
| Thiết bị | Web desktop |

![Hình 11.4. SCR-1202 Xử lý khiếu nại KN-2027-0031](SCR-1202.png){width=16cm}

Màn hình có ba cột: danh sách khiếu nại, chi tiết và giải trình, kết luận và thi hành. Dòng phụ đầu trang ghi rõ người xử lý Lê Thu Hà "không tham gia thẩm định hồ sơ liên quan", là cách nhắc trực quan về quy tắc tách vai trò.

Vùng 1 là danh sách khiếu nại với mã, người khiếu nại, đối tượng, trạng thái và số ngày còn lại; KN-2027-0031 đang được chọn ở trạng thái "Đang giải trình, còn 11 ngày". Vùng 2 là phần đầu khiếu nại với nhãn `KHIEU_NAI`, ghi chú nội dung C2 đã được giải mã cho người xử lý và việc truy cập đã được ghi sổ cái, tóm tắt đề nghị và dòng thời gian bốn mốc (gửi, tiếp nhận, yêu cầu giải trình, đã nhận giải trình). Vùng 3 là thẻ "Giải trình đã nhận" từ người nghiệm thu Ngô Thanh Tâm và Đảng uỷ cơ sở X (thừa nhận gửi muộn do sáp nhập chi bộ, công văn 51-CV/ĐU). Vùng 4 là thẻ "Kết luận": kết quả xử lý "Chấp nhận một phần · điều chỉnh C", điều chỉnh đề nghị "NV then chốt 3: C 0,85 → 1,00 (miễn trừ khách quan)", ô tính thử màu cam cho kết quả 91,2 thành 92,1 và mức vẫn HTTNV, văn bản trả lời sinh từ mẫu TL-KN-01 số 08-CV/BTC. Vùng 5 là thẻ "Thi hành kết luận" nói rõ kỳ quý IV đã `DA_KHOA`, cần mở lại kỳ hai người duyệt và hệ thống đã tạo sẵn yêu cầu MLK-2027-007.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Danh sách khiếu nại | Bảng | Không áp dụng | Mã, người, trạng thái, hạn | Sắp theo hạn | Chỉ khiếu nại được giao hoặc trong phạm vi | `oversight.complaint`, `flowable.wf_task_projection` |
| 2 | Nội dung khiếu nại | Văn bản | Không áp dụng | Giải mã khi xem | Từ dữ liệu | Ghi nhật ký truy cập | `oversight.complaint.content_enc` |
| 3 | Dòng thời gian | Danh sách | Không áp dụng | Thời điểm, hành động | Từ sổ cái và luồng | Chỉ đọc | `ledger.ledger_entry`, `oversight.complaint_response` |
| 4 | Bên được yêu cầu giải trình | Chọn nhiều | Có khi yêu cầu | Người hoặc đơn vị | Gợi ý theo cấu phần | Ít nhất 1 | `oversight.complaint_response` (loại YEU_CAU) |
| 5 | Giải trình đã nhận | Danh sách | Không áp dụng | Người, nội dung, tệp | Từ dữ liệu | Chỉ đọc | `oversight.complaint_response` (loại GIAI_TRINH) |
| 6 | Kết quả xử lý | Danh sách chọn | Có | GIU_NGUYEN, CHAP_NHAN_MOT_PHAN, CHAP_NHAN | Trống | Khác GIU_NGUYEN thì bắt buộc trường 7 | `oversight.complaint_response.outcome` |
| 7 | Điều chỉnh đề nghị | Văn bản có cấu trúc | Có khi chấp nhận | Cấu phần, giá trị cũ, giá trị mới, lý do | Trống | Giá trị mới trong miền hợp lệ của cấu phần | `oversight.complaint_response.adjustment_json` |
| 8 | Kết quả tính thử | Nhãn | Không áp dụng | Điểm, mức trước và sau | Tính khi bấm | Policy Pack của kỳ | Trả về từ SVC-07, không lưu |
| 9 | Văn bản trả lời | Tệp sinh từ mẫu | Có | Số văn bản, PDF/A | Sinh từ mẫu TL-KN-01 | Phải ký trước khi gửi | `docgen.render_output`, `oversight.complaint_response.document_ref` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tiếp nhận hoặc không thụ lý | Khiếu nại `MOI` | Chuyển trạng thái; không thụ lý bắt buộc lý do | `POST /api/v1/oversight/complaints/{id}/accept` hoặc `/reject` | EVT-21 |
| Yêu cầu giải trình | `DA_TIEP_NHAN` | Tạo yêu cầu, gửi thông báo, đặt hạn | `POST /api/v1/oversight/complaints/{id}/explanation-requests` | EVT-21 |
| Tính thử | Có điều chỉnh | Gọi scoring-service chế độ mô phỏng | `POST /api/v1/appraisals/profiles/{id}/simulate` | Không có |
| Xem văn bản | Đã có kết luận | Sinh bản xem trước văn bản trả lời | `POST /api/v1/dossiers/forms/preview` | Không có |
| Ký số và trả lời | Đủ trường 6, 7, 9 | Ký văn bản, gửi người khiếu nại | `POST /api/v1/oversight/complaints/{id}/respond` (202) | EVT-18, EVT-21 |
| Tạo yêu cầu mở lại kỳ | Kết luận có điều chỉnh, kỳ `DA_KHOA` | Tạo `reopen_request` với phạm vi lấy từ trường 7 | `POST /api/v1/oversight/reopen-requests` | Không có (sự kiện phát ở QT-13) |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-12-05 | Đồng chí không thể xử lý khiếu nại này vì đã tham gia {vai_tro} hồ sơ liên quan (PL 4 V.1). | Lỗi phân quyền | OPA từ chối |
| MSG-12-06 | Kết quả tính thử theo Policy Pack {phien_ban}: điểm {cu} thành {moi}; mức {muc_cu} thành {muc_moi}. Kết quả chưa được ghi. | Thông tin | Tính thử |
| MSG-12-07 | Kỳ {ky} đã khoá. Điều chỉnh chỉ có hiệu lực sau khi yêu cầu mở lại kỳ được hai người có thẩm quyền phê duyệt. | Cảnh báo | Kết luận có điều chỉnh |
| MSG-12-08 | Khiếu nại {ma_kn} còn {so_ngay} ngày làm việc đến hạn trả lời. | Nhắc việc | Trước hạn 2 ngày |

### Liên kết thiết kế

QT-12 do MOD-07 `oversight` quản lý (bảng `complaint`, `complaint_response`), chạy trên mẫu luồng `xu-ly-khieu-nai` v2 của SVC-06, dùng OPA để chọn người xử lý, SVC-07 để tính thử, SVC-11 sinh văn bản trả lời, SVC-10 ký số, SVC-08 lưu tài liệu kèm theo, SVC-12 thông báo. Sự kiện: EVT-25 KhieuNaiDaGui, EVT-18, EVT-21; khi thi hành dẫn sang EVT-22 của QT-13. Nội dung khiếu nại là dữ liệu C2 (ADR-06). Đáp ứng nhóm tính năng 6 "khiếu nại, giải trình, bảo lưu ý kiến; trả lời bằng văn bản" của PL A2 và tiêu chí H6.

## QT-13. Hậu kiểm, mở lại kỳ (hai người duyệt)

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-13 |
| Tên | Hậu kiểm, mở lại kỳ (hai người duyệt) |
| Mục đích | Tổ chức hậu kiểm theo kế hoạch, đột xuất hoặc theo phản ánh; mở lại kỳ đã khoá trong phạm vi giới hạn khi có hai người có thẩm quyền khác nhau cùng duyệt; tra cứu sổ cái và kiểm tra toàn vẹn chuỗi băm, chỉ ra vị trí bị phá vỡ |
| Phạm vi áp dụng | Mọi kỳ đánh giá đã `DA_KHOA`; mọi dữ liệu đánh giá trong phạm vi được mở; mọi bản ghi sổ cái của tenant |
| Căn cứ | Dự thảo Quy định mới khoản 15.3 (hậu kiểm, huỷ, đánh giá lại); PL 4 III.2 (nhật ký giá trị trước, sau, lý do, người làm, người duyệt), V.2 (khoá và mở kỳ có phê duyệt), V.1 (tách vai trò); tiêu chí I2, I3, I8 |
| Tác nhân | AC-07 cơ quan tổ chức, AC-10 cơ quan kiểm tra (tạo yêu cầu, hậu kiểm); AC-08 người duyệt thứ nhất; AC-09 người duyệt thứ hai; SVC-09 ledger-service; SVC-07; SVC-06 |
| Tần suất và khối lượng ước tính | Mở lại kỳ là sự kiện hiếm. Giả định khoảng 1 yêu cầu trên 1.000 hồ sơ mỗi quý (chưa có số đo), tức khoảng 6.000 yêu cầu mỗi quý toàn quốc; kiểm tra toàn vẹn chạy định kỳ hằng đêm và theo yêu cầu, mỗi lần quét hàng triệu bản ghi (ví dụ 1.284.311 bản ghi trong 48 giây là số đo trên môi trường thử) |
| Điều kiện bắt đầu | Có kế hoạch hậu kiểm, quyết định hậu kiểm đột xuất, phản ánh, kết luận khiếu nại có điều chỉnh (QT-12) hoặc cảnh báo kết luận có vi phạm (QT-14) |
| Điều kiện kết thúc | Kỳ trở lại `DA_KHOA` sau khi tính lại, sinh lại biểu mẫu và ký lại; báo cáo hậu kiểm được lập (`oversight.post_audit`) |
| Đầu vào | Lý do, phạm vi (danh sách hồ sơ, bảng, trường được phép sửa), thời hạn mở, căn cứ |
| Đầu ra | `oversight.reopen_request`, hai bản `oversight.reopen_approval`, bản ghi sổ cái cho mọi thay đổi, snapshot mới, biểu mẫu phiên bản mới, `oversight.post_audit`, `ledger.verify_run` |
| Chỉ số đo hiệu quả quy trình | Thời gian từ tạo yêu cầu đến đủ hai phê duyệt; tỷ lệ yêu cầu bị từ chối; số thay đổi ngoài phạm vi bị chặn; kết quả kiểm tra toàn vẹn hằng đêm (mục tiêu 100% phân vùng khớp); thời gian chạy kiểm tra một tenant |

Hai năng lực được gộp trong một quy trình vì chúng phục vụ cùng một câu hỏi của cơ quan kiểm tra: "kết quả này có bị sửa sau khi đã khoá không, và nếu cần sửa thì ai đã cho phép". Năng lực thứ nhất là mở lại kỳ theo nguyên tắc bốn mắt (four-eyes principle): không một cá nhân nào, kể cả quản trị nền tảng, đủ quyền một mình để sửa dữ liệu của kỳ đã khoá. Năng lực thứ hai là sổ cái chỉ ghi thêm theo ADR-09: mỗi thay đổi dữ liệu đánh giá được ghi một bản ghi chứa mã băm của bản ghi trước trong cùng phân vùng (64 phân vùng mỗi tenant), mỗi giờ gốc Merkle của các phân vùng được niêm phong bằng chữ ký số và dấu thời gian RFC 3161, và bản neo được gửi cho bên thứ ba giữ. Công cụ kiểm tra tính lại chuỗi băm và chỉ ra chính xác bản ghi bị sửa nếu có ai can thiệp thẳng vào cơ sở dữ liệu.

Yêu cầu MLK-2027-007 dùng làm ví dụ được tạo để thi hành kết luận khiếu nại KN-2027-0031. Phạm vi chỉ gồm một hồ sơ và chỉ cho phép sửa hệ số C của nhiệm vụ then chốt 3 trong `product.acceptance`, sau đó tính lại điểm và xếp loại.

### Lưu đồ quy trình

![Hình 11.5. Lưu đồ làn bơi QT-13: hậu kiểm và mở lại kỳ với hai người duyệt](nv-b-qt13-luong.png){width=16cm}

Cơ quan kiểm tra hoặc tổ chức khởi đầu bằng hậu kiểm theo một trong ba căn cứ, tra cứu sổ cái để xác định lịch sử thay đổi, rồi lập yêu cầu mở lại kỳ. Người duyệt thứ nhất và người duyệt thứ hai lần lượt xác thực đa yếu tố bước lên (step-up MFA) và ký số phê duyệt. Làn "Hệ thống" kiểm tra bằng OPA rằng hai người duyệt khác nhau, khác người tạo, đúng thẩm quyền; nếu vi phạm, yêu cầu bị từ chối và ghi sổ cái. Nếu hợp lệ, kỳ chuyển `MO_LAI_HAU_KIEM` chỉ trong phạm vi đã duyệt và phát EVT-22. Người được giao sửa dữ liệu trong phạm vi, mỗi thay đổi kèm lý do và được ghi sổ cái. scoring-service tính lại bằng chính phiên bản Policy Pack của kỳ, document-service sinh lại biểu mẫu, esign-service ký lại, và kỳ được khoá lại. Kết quả hậu kiểm được lập thành báo cáo trong `oversight.post_audit`.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Khởi tạo hậu kiểm | AC-10 hoặc AC-07 | Tạo đợt hậu kiểm: loại (kế hoạch, đột xuất, theo phản ánh), phạm vi, căn cứ | SCR-1301 | `oversight.post_audit` (mở) | Theo kế hoạch |
| 2 | Tra cứu sổ cái | AC-10 | Lọc theo đối tượng, thời gian, loại sự kiện; xem giá trị trước, sau, người làm | SCR-1302 | Không thay đổi dữ liệu | Không giới hạn |
| 3 | Kiểm tra toàn vẹn | AC-10, SVC-09 | Chạy kiểm tra chuỗi băm theo phân vùng, đối chiếu niêm phong Merkle và bản neo | SCR-1302 | `ledger.verify_run`; nếu phá vỡ thì EVT-20 | Vài phút (tác vụ nền) |
| 4 | Lập yêu cầu mở lại kỳ | AC-07 hoặc AC-10 | Chọn loại, phạm vi hồ sơ, dữ liệu được phép sửa, thời hạn mở, lý do, căn cứ; xem ảnh hưởng dự kiến | SCR-1301 | `oversight.reopen_request` `CHO_DUYET_1` | Không giới hạn |
| 5 | Chỉ định người duyệt | Hệ thống (OPA) | Chọn người duyệt thứ nhất (`decider`) và thứ hai (`second_approver`) theo thẩm quyền, loại trừ người tạo và người đã tham gia hồ sơ | SCR-1301 | Hai task duyệt | Ngay khi tạo |
| 6 | Duyệt thứ nhất | AC-08 | Step-up MFA, ký số phê duyệt hoặc từ chối có ý kiến | SCR-1301 | `oversight.reopen_approval` (1); `CHO_DUYET_2` | 2 ngày làm việc |
| 7 | Duyệt thứ hai | AC-09 | Như bước 6 | SCR-1301 | `oversight.reopen_approval` (2); `DA_DUYET` | 2 ngày làm việc |
| 8 | Mở lại kỳ trong phạm vi | MOD-07, MOD-08 | Kiểm tra OPA lần cuối; chuyển kỳ sang `MO_LAI_HAU_KIEM` chỉ với phạm vi đã duyệt | Không có | EVT-22; ghi sổ cái | Ngay khi đủ hai phê duyệt |
| 9 | Sửa dữ liệu | Người được giao | Sửa đúng trường trong phạm vi, mỗi thay đổi có lý do; thay đổi ngoài phạm vi bị chặn | SCR-0703 (nghiệm thu) hoặc màn hình tương ứng | Bản ghi sổ cái giá trị trước, sau | Trong thời hạn mở |
| 10 | Tính lại | SVC-07 | Tính lại các hồ sơ trong phạm vi bằng phiên bản Policy Pack của kỳ; tạo snapshot mới, giữ snapshot cũ | Không có | EVT-10 | Trong 15 phút |
| 11 | Sinh lại, ký lại | SVC-11, SVC-10, AC-08 | Sinh phiên bản mới của Mẫu 2, Mẫu 3; người có thẩm quyền ký lại | SCR-1102 | EVT-17, EVT-18 | Trong thời hạn mở |
| 12 | Khoá lại kỳ | MOD-08 | Khi hoàn tất hoặc hết hạn mở, kỳ trở lại `DA_KHOA` | Không có | EVT-09 (khoá lại); ghi sổ cái | Tự động |
| 13 | Lập báo cáo hậu kiểm | AC-10 | Ghi kết luận, các thay đổi đã thực hiện, đính kèm bằng chứng xuất từ sổ cái | SCR-1301 | `oversight.post_audit` (đóng) | Theo kế hoạch |

Điểm then chốt ở bước 8 là "mở trong phạm vi" chứ không mở cả kỳ. Trạng thái `MO_LAI_HAU_KIEM` gắn với kỳ, nhưng quyền ghi được kiểm soát ở mức hồ sơ và trường: MOD-08 lưu phạm vi trong `oversight.reopen_request.scope_json` và nạp vào bộ nhớ đệm kiểm tra trạng thái kỳ (lớp bảo vệ 5 trong G3, chi phí dưới 0,1 ms mỗi lần ghi). Một API ghi vào dữ liệu của kỳ đang mở lại chỉ được chấp nhận khi đối tượng nằm trong phạm vi; ngược lại API trả lỗi 409 với mã `period-locked`.

Bước 10 bảo đảm kết quả mới tái lập được: snapshot mới có `policy_version` giống snapshot cũ và `input_hash` mới. Người kiểm tra có thể đối chiếu hai snapshot và thấy chính xác đầu vào nào đã thay đổi.

### Trạng thái đối tượng

![Hình 11.6. Trạng thái kỳ đánh giá config.period và yêu cầu mở lại kỳ oversight.reopen_request](nv-b-qt13-trangthai.png){width=14cm}

Phần trên của sơ đồ là trạng thái kỳ đánh giá đúng baseline mục 9.1: `MO` qua saga chốt kỳ sang `DANG_KHOA`, sau đó `DA_KHOA`; khi đủ hai phê duyệt thì sang `MO_LAI_HAU_KIEM` và trở lại `DA_KHOA` khi hoàn tất hoặc hết hạn. Phần dưới là trạng thái của yêu cầu mở lại kỳ, đề xuất mới trong tài liệu này: `CHO_DUYET_1`, `CHO_DUYET_2`, `DA_DUYET` hoặc `TU_CHOI`.

| Đối tượng | Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|---|
| Kỳ | `DA_KHOA` | Yêu cầu mở lại đủ hai phê duyệt | `MO_LAI_HAU_KIEM` | OPA: hai người khác nhau, khác người tạo, đúng thẩm quyền; EVT-22 |
| Kỳ | `MO_LAI_HAU_KIEM` | Hoàn tất hoặc hết hạn mở | `DA_KHOA` | Mọi hồ sơ trong phạm vi đã tính lại và ký lại, hoặc hết hạn |
| Yêu cầu | (chưa có) | Tạo yêu cầu | `CHO_DUYET_1` | Đủ lý do, phạm vi, thời hạn |
| Yêu cầu | `CHO_DUYET_1` | Người duyệt 1 đồng ý | `CHO_DUYET_2` | Step-up MFA, ký số |
| Yêu cầu | `CHO_DUYET_2` | Người duyệt 2 đồng ý | `DA_DUYET` | Step-up MFA, ký số, khác người duyệt 1 |
| Yêu cầu | `CHO_DUYET_1` hoặc `CHO_DUYET_2` | Từ chối | `TU_CHOI` | Có ý kiến |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-13-01 | Kỳ đã khoá không sửa được dữ liệu đánh giá; mọi API ghi bị chặn | PL 4 V.2; tiêu chí I3 | Kiểm tra trạng thái kỳ trong bộ nhớ đệm ở mọi lệnh ghi của MOD-01 đến MOD-04; lỗi 409 |
| BR-13-02 | Mở lại kỳ phải có hai người có thẩm quyền khác nhau cùng phê duyệt, có lý do | PL 4 V.2; tiêu chí I3 | Hai bản `oversight.reopen_approval`; OPA kiểm tra `approver_1 ≠ approver_2 ≠ requester` |
| BR-13-03 | Người duyệt không được là người đã thẩm định hoặc quyết định hồ sơ bị mở | PL 4 V.1 | OPA đọc lịch sử tham gia hồ sơ |
| BR-13-04 | Mỗi phê duyệt yêu cầu xác thực đa yếu tố bước lên và ký số | Lớp bảo vệ 6 (G3); tiêu chí I4 | Keycloak step-up (ACR cao); esign-service |
| BR-13-05 | Mở lại kỳ chỉ trong phạm vi được duyệt (danh sách hồ sơ, bảng, trường) và có thời hạn | QĐ khoản 15.3 | `scope_json`; kiểm tra phạm vi ở mọi lệnh ghi; hết hạn tự khoá lại |
| BR-13-06 | Tính lại phải dùng đúng phiên bản Policy Pack đã dùng khi khoá kỳ; không áp phiên bản mới cho kỳ cũ | Tiêu chí I6 | scoring-service đọc `policy_version` từ snapshot gốc |
| BR-13-07 | Mọi thay đổi ghi giá trị trước, giá trị sau, lý do, người thực hiện, người duyệt, thời điểm | PL 4 III.2; tiêu chí I1 | Outbox ghi bản ghi sổ cái cùng giao dịch dữ liệu |
| BR-13-08 | Biểu mẫu và snapshot cũ được giữ nguyên; bản mới có số phiên bản tăng | Tiêu chí I5, I6 | Không có thao tác xoá, ghi đè; Object Lock với tệp |
| BR-13-09 | Sổ cái chỉ ghi thêm; mỗi bản ghi chứa băm bản ghi trước trong phân vùng; niêm phong gốc Merkle mỗi giờ bằng ký số và dấu thời gian RFC 3161 | ADR-09; tiêu chí I2, I8 | ledger-service; tài khoản CSDL chỉ có quyền INSERT; cụm CSDL riêng |
| BR-13-10 | Kiểm tra toàn vẹn phải chỉ ra được phân vùng và bản ghi đầu tiên bị phá vỡ | Tiêu chí I2 | Thuật toán quét tuần tự theo phân vùng, so băm lưu và băm tính lại |
| BR-13-11 | Phát hiện phá vỡ chuỗi thì tạo cảnh báo mức Cao và khoá ghi phân vùng đó cho tới khi được xử lý | ADR-09 | EVT-20; cờ khoá phân vùng trong ledger-service |
| BR-13-12 | Công cụ kiểm tra có thể chạy độc lập trên bản xuất, không cần tin máy chủ | Tiêu chí I2 | CLI Go công bố mã nguồn; bản xuất JSON kèm chữ ký |

### Ngoại lệ và xử lý

Khi chỉ có một người có thẩm quyền ở đơn vị (đơn vị nhỏ), OPA không tìm được người duyệt thứ hai và yêu cầu tự chuyển lên cấp trên trực tiếp chọn người duyệt thứ hai. Hệ thống không bao giờ hạ yêu cầu xuống một người duyệt.

Khi hết thời hạn mở mà việc sửa chưa xong, kỳ tự khoá lại; các thay đổi đã thực hiện vẫn được giữ và ghi sổ cái, nhưng các hồ sơ chưa tính lại xong được đánh dấu "chưa hoàn tất hậu kiểm" và phải tạo yêu cầu mới.

Khi kiểm tra toàn vẹn phát hiện phá vỡ chuỗi (như ở phân vùng 17, bản ghi 1042 trong ví dụ), ledger-service khoá ghi phân vùng, phát cảnh báo mức Cao và thông báo cho quản trị an toàn thông tin. Việc khôi phục không sửa lại bản ghi bị phá mà ghi một bản ghi "đánh dấu sự cố" mới, mở một phân đoạn chuỗi mới có tham chiếu niêm phong hợp lệ gần nhất, và quy trình ứng cứu sự cố được thực hiện theo TL-04 (OPS về sự cố an toàn thông tin).

Khi dịch vụ dấu thời gian không phản hồi lúc niêm phong, ledger-service vẫn niêm phong bằng chữ ký số và gắn dấu thời gian bù khi dịch vụ trở lại; niêm phong được đánh dấu "dấu thời gian trễ" để người kiểm tra biết.

### Đặc tả màn hình

#### SCR-1301. Yêu cầu mở lại kỳ

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Lập, xem ảnh hưởng và phê duyệt yêu cầu mở lại kỳ có phạm vi giới hạn, theo nguyên tắc hai người duyệt |
| Tác nhân | AC-07, AC-10 tạo; AC-08 người duyệt thứ nhất; AC-09 người duyệt thứ hai |
| Đường vào | Menu Giám sát › Hậu kiểm, mở lại kỳ; nút "Tạo yêu cầu mở lại kỳ" ở SCR-1202; thông báo task duyệt |
| Quyền | Tạo: `appraiser`, `inspector`; duyệt: `decider`, `second_approver` được OPA chỉ định |
| Thiết bị | Web desktop |

![Hình 11.7. SCR-1301 Yêu cầu mở lại kỳ MLK-2027-007 đang chờ người duyệt thứ hai](SCR-1301.png){width=16cm}

Màn hình hiển thị yêu cầu MLK-2027-007 dưới góc nhìn của người duyệt thứ hai Phan Văn Quân. Nhãn "Chờ người duyệt thứ hai" ở đầu trang cho biết bước hiện tại.

Vùng 1 là tiêu đề và dòng căn cứ: kỳ Quý IV/2026 đã khoá từ 12/01/2027 17:30, tạo bởi Lê Thu Hà, căn cứ kết luận khiếu nại KN-2027-0031. Vùng 2 là phần phạm vi: loại hậu kiểm "Theo khiếu nại, phản ánh", phạm vi "Danh sách hồ sơ cụ thể (1)", thời hạn mở 3 ngày làm việc đến 24/01/2027, và bảng đối tượng nêu rõ bảng `appraisal.appraisal_profile`, trạng thái `DA_KHOA`, dữ liệu được phép sửa là `product.acceptance` của nhiệm vụ then chốt 3 (hệ số C). Vùng 3 là lý do mở lại và bảng "Ảnh hưởng dự kiến (tính thử, chưa ghi)": điểm 91,2 thành 92,1, mức HTTNV giữ nguyên, 2 biểu mẫu cần sinh lại. Vùng 4 là thẻ "Hai người duyệt": Ngô Thanh Tâm đã duyệt ngày 20/01 10:14 với MFA và ký số; Phan Văn Quân đang chờ duyệt; ghi chú ràng buộc tách vai trò. Vùng 5 là thẻ duyệt với ô ý kiến bắt buộc, nút "Từ chối" và "Xác thực MFA và duyệt", kèm mô tả việc hệ thống sẽ làm khi đủ hai phê duyệt.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã yêu cầu | Nhãn | Không áp dụng | MLK-yyyy-nnn | Sinh tự động | Duy nhất trong tenant | `oversight.reopen_request.code` |
| 2 | Kỳ | Danh sách chọn | Có | Kỳ `DA_KHOA` | Từ căn cứ | Chỉ kỳ đã khoá | `oversight.reopen_request.period_id` |
| 3 | Loại hậu kiểm | Danh sách chọn | Có | KE_HOACH, DOT_XUAT, PHAN_ANH | Theo căn cứ | Không có | `oversight.reopen_request.audit_kind` |
| 4 | Phạm vi | Danh sách chọn và bảng | Có | Danh sách hồ sơ; bảng và trường được sửa | Từ căn cứ | Ít nhất 1 đối tượng; không chọn "toàn kỳ" nếu không có quyết định hậu kiểm toàn diện | `oversight.reopen_request.scope_json` |
| 5 | Thời hạn mở | Số ngày làm việc | Có | 1 đến 10 | 3 | Tính ngày kết thúc theo lịch tenant | `oversight.reopen_request.open_until` |
| 6 | Lý do mở lại | Văn bản dài | Có | 50 đến 4.000 ký tự | Trống | Không có | `oversight.reopen_request.reason` |
| 7 | Căn cứ | Liên kết | Có | Mã khiếu nại, cảnh báo, quyết định hậu kiểm | Từ nơi tạo | Đối tượng căn cứ phải tồn tại | `oversight.reopen_request.basis_ref` |
| 8 | Ảnh hưởng dự kiến | Bảng | Không áp dụng | Chỉ tiêu, trước, sau | Tính thử | Chỉ đọc | Trả về từ SVC-07 chế độ mô phỏng |
| 9 | Người duyệt và trạng thái | Thẻ | Không áp dụng | Họ tên, vai trò, thời điểm, MFA, chữ ký | Từ dữ liệu | Chỉ đọc | `oversight.reopen_approval` |
| 10 | Ý kiến người duyệt | Văn bản dài | Có | 10 đến 2.000 ký tự | Trống | Không có | `oversight.reopen_approval.comment` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tạo yêu cầu | Người tạo có quyền | Lưu yêu cầu, gọi OPA chỉ định người duyệt, khởi động mẫu `mo-lai-ky-hai-nguoi` | `POST /api/v1/oversight/reopen-requests` | EVT-21 |
| Tính thử ảnh hưởng | Có phạm vi và điều chỉnh | Mô phỏng tính lại | `POST /api/v1/oversight/reopen-requests/{id}/simulate` | Không có |
| Xác thực MFA và duyệt | Người duyệt được chỉ định, đúng lượt | Step-up MFA, ký số phê duyệt; nếu là phê duyệt thứ hai thì mở lại kỳ | `POST /api/v1/oversight/reopen-requests/{id}/approvals` | EVT-18; khi đủ: EVT-22 |
| Từ chối | Người duyệt được chỉ định | Ghi ý kiến, đóng yêu cầu | `POST /api/v1/oversight/reopen-requests/{id}/reject` | EVT-21 |
| Huỷ yêu cầu | Người tạo, trước khi đủ phê duyệt | Đóng yêu cầu có lý do | `POST /api/v1/oversight/reopen-requests/{id}/cancel` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-13-01 | Hai người duyệt phải là hai người khác nhau, khác người tạo yêu cầu và chưa tham gia thẩm định, quyết định hồ sơ bị mở (PL 4 V.1). | Lỗi phân quyền | OPA từ chối |
| MSG-13-02 | Cần xác thực bổ sung để phê duyệt mở lại kỳ. | Thông tin | Trước step-up MFA |
| MSG-13-03 | Yêu cầu {ma} đã đủ hai phê duyệt. Kỳ {ky} được mở lại trong phạm vi {so_doi_tuong} đối tượng đến {han}. | Thành công | Phát EVT-22 |
| MSG-13-04 | Kỳ {ky} đã khoá. Dữ liệu này nằm ngoài phạm vi được mở lại nên không thể sửa. | Lỗi | API ghi trả 409 |

#### SCR-1302. Tra cứu sổ cái và kiểm tra toàn vẹn

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cơ quan kiểm tra tra cứu lịch sử thay đổi của một đối tượng, chạy kiểm tra toàn vẹn chuỗi băm, xem vị trí phá vỡ, niêm phong Merkle và bản neo, xuất bằng chứng |
| Tác nhân | AC-10 (`inspector`); AC-07 xem trong phạm vi đơn vị |
| Đường vào | Menu Giám sát › Sổ cái; liên kết từ SCR-1301, SCR-1401 |
| Quyền | `inspector` của tenant; mọi lần tra cứu được ghi sổ cái |
| Thiết bị | Web desktop |

![Hình 11.8. SCR-1302 Tra cứu sổ cái và kết quả kiểm tra chuỗi băm phát hiện phá vỡ tại bản ghi 1042](SCR-1302.png){width=16cm}

Màn hình là công cụ của người kiểm tra, nên ưu tiên tính chính xác và khả năng xuất bằng chứng. Dòng phụ cho biết cấu hình sổ cái của tenant: 64 phân vùng chuỗi, niêm phong Merkle mỗi giờ, dịch vụ dấu thời gian Viettel-CA.

Vùng 1 là thanh tra cứu với đối tượng (appraisal_profile của Trần Minh Hoàng, quý IV/2026), khoảng thời gian và loại sự kiện. Vùng 2 là bảng bản ghi sổ cái chỉ ghi thêm, mỗi dòng có mã bản ghi, thời điểm, topic sự kiện, người thực hiện, giá trị trước và sau; ví dụ dòng `product.evidence.accepted.v1` ngày 18/12 ghi C từ chưa có thành 0,85. Vùng 3 là kết quả kiểm tra chuỗi băm của phân vùng 17, minh hoạ ba bản ghi 1041, 1042, 1043 nối nhau bằng `prev` và `hash`; bản ghi 1042 viền đỏ với "4b07… ≠ tính lại 9d3c…", kèm kết luận nội dung đã bị sửa sau khi ghi, gốc Merkle niêm phong lúc 18/12 15:00 chứa băm gốc 4b07… khớp bản neo, đã tạo cảnh báo mức Cao và khoá ghi phân vùng. Vùng 4 là thẻ "Lần kiểm tra" VR-2027-0112: 64 phân vùng, 1.284.311 bản ghi, 7.224 niêm phong, chạy 48 giây, 63/64 phân vùng khớp. Vùng 5 là thẻ "Niêm phong và bản neo" với mã niêm phong, gốc, chữ ký hợp lệ, dấu thời gian RFC 3161 và xác nhận của cơ quan giám sát giữ bản neo.

Thời gian 48 giây cho khoảng 1,3 triệu bản ghi là số đo trên môi trường thử với 8 luồng song song theo phân vùng; con số này dùng để minh hoạ và sẽ được đo lại theo kịch bản PERF của TL-04 trên cấu hình triển khai thực.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Đối tượng | Ô tìm kiếm | Không | Loại đối tượng và mã | Trống | Chỉ đối tượng trong phạm vi quyền | `ledger.ledger_entry.aggregate_type`, `ledger.ledger_entry.aggregate_id` |
| 2 | Khoảng thời gian | Khoảng ngày | Không | dd/mm/yyyy | 90 ngày gần nhất | Tối đa 2 năm mỗi lần | `ledger.ledger_entry.occurred_at` |
| 3 | Loại sự kiện | Danh sách chọn | Không | Topic EVT | Tất cả | Không có | `ledger.ledger_entry.event_type` |
| 4 | Bảng bản ghi | Bảng | Không áp dụng | Mã, thời điểm, sự kiện, người, trước, sau | Theo bộ lọc | Phân trang con trỏ; trường C2 hiện dạng che | `ledger.ledger_entry` |
| 5 | Kết quả chuỗi | Sơ đồ khối | Không áp dụng | prev, hash, trạng thái khớp | Từ lần kiểm tra | Chỉ đọc | `ledger.verify_run.result_json` |
| 6 | Lần kiểm tra | Thẻ | Không áp dụng | Mã, phạm vi, thời gian chạy, kết quả | Lần gần nhất | Chỉ đọc | `ledger.verify_run` |
| 7 | Niêm phong, bản neo | Thẻ | Không áp dụng | Mã, gốc, chữ ký, dấu thời gian | Theo bản ghi chọn | Chỉ đọc | `ledger.merkle_seal`, `ledger.anchor_receipt` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tìm | Luôn | Truy vấn sổ cái theo bộ lọc | `GET /api/v1/ledger/entries?aggregate=...&from=...&to=...` | Không có |
| Chạy kiểm tra toàn vẹn | `inspector` | Tạo tác vụ kiểm tra toàn bộ hoặc một phân vùng | `POST /api/v1/ledger/verify-runs` (202) | Nếu phá vỡ: EVT-20 |
| Xem bản ghi | Chọn dòng | Hiển thị chi tiết, băm, niêm phong chứa bản ghi | `GET /api/v1/ledger/entries/{id}` | Không có |
| Xuất bằng chứng (JSON và chữ ký) | Có kết quả | Xuất các bản ghi, niêm phong, bản neo trong phạm vi, ký bằng khoá của ledger-service | `POST /api/v1/ledger/exports` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-13-05 | Kiểm tra toàn vẹn hoàn tất: {so_khop}/{tong} phân vùng khớp, {so_ban_ghi} bản ghi, {thoi_gian} giây. | Thành công | Tác vụ xong, không phá vỡ |
| MSG-13-06 | Phát hiện chuỗi băm bị phá vỡ tại bản ghi {ma} của phân vùng {pv}. Đã tạo cảnh báo mức Cao và khoá ghi phân vùng. | Lỗi nghiêm trọng | Phát hiện phá vỡ |
| MSG-13-07 | Tệp bằng chứng đã sẵn sàng. Có thể kiểm tra độc lập bằng công cụ dòng lệnh ledger-verify. | Thành công | Xuất xong |

### Liên kết thiết kế

QT-13 dùng MOD-07 `oversight` (bảng `reopen_request`, `reopen_approval`, `post_audit`), MOD-08 `config` (`config.period`), SVC-09 ledger-service (`ledger_entry`, `merkle_seal`, `anchor_receipt`, `verify_run`), SVC-02 iam cho step-up MFA, SVC-06 với mẫu `mo-lai-ky-hai-nguoi` v1, SVC-07, SVC-11, SVC-10. Sự kiện: EVT-22 KyDaMoLaiHauKiem, EVT-19 SoCaiDaNiemPhong, EVT-20 (khi phá vỡ chuỗi), EVT-09 (khoá lại), EVT-10, EVT-17, EVT-18. Đáp ứng trực tiếp các tiêu chí I1 (nhật ký trước, sau, lý do), I2 (sổ cái chuỗi băm, niêm phong, công cụ kiểm tra), I3 (khoá kỳ, mở lại hai người duyệt), I8 (dấu thời gian RFC 3161) và H6. Thiết kế chi tiết sổ cái xem TL-01 (ADR-09) và TL-03 (schema `ledger`); biện pháp bảo vệ cụm CSDL sổ cái xem TL-04.

## QT-14. Cảnh báo bất thường

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-14 |
| Tên | Cảnh báo bất thường |
| Mục đích | Tự động phát hiện tám dấu hiệu bất thường trong dữ liệu đánh giá, chuyển cảnh báo kèm chuỗi bằng chứng tới cơ quan tổ chức để xem xét, và chuyển cơ quan kiểm tra khi cần; cảnh báo không phải là kết luận vi phạm |
| Phạm vi áp dụng | Mọi dữ liệu mục tiêu, nhiệm vụ, minh chứng, nghiệm thu, tự đánh giá, thẩm định, xếp loại của tenant |
| Căn cứ | PL 4 V.6 (phát hiện dấu hiệu bất thường), III.5 (cảnh báo minh chứng trùng); báo cáo BGĐ slide 31 (tám dấu hiệu); PL A2 nhóm 6 |
| Tác nhân | SVC-15 analytics (job phát hiện); MOD-07 oversight; AC-07 cơ quan tổ chức; AC-10 cơ quan kiểm tra |
| Tần suất và khối lượng ước tính | Job chạy hằng đêm lúc 02:00 theo cell, cộng phát hiện tức thời cho minh chứng trùng mã băm. Giả định khoảng 1 cảnh báo trên 500 hồ sơ mỗi quý (ví dụ Ban Tổ chức có 23 cảnh báo trong 90 ngày), tức khoảng 12.000 cảnh báo mỗi quý toàn quốc |
| Điều kiện bắt đầu | Job phát hiện tìm thấy dữ liệu vượt ngưỡng của một dấu hiệu, hoặc evidence-service phát hiện trùng mã băm |
| Điều kiện kết thúc | Cảnh báo `DONG` với một trong các kết quả: hợp lệ, không vi phạm, có vi phạm (đã chuyển hậu kiểm), tự đóng |
| Đầu vào | Dữ liệu CDC trong ClickHouse; ngưỡng phát hiện của tenant; `evidence.dedup_index` |
| Đầu ra | `oversight.anomaly_alert`, `oversight.alert_action`, cờ chặn tính điểm tạm thời, yêu cầu mở lại kỳ nếu có vi phạm |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ cảnh báo được xử lý trong 10 ngày làm việc; tỷ lệ cảnh báo đúng (dẫn tới kết luận có vi phạm) so với tổng; tỷ lệ cảnh báo xác nhận hợp lệ (dùng để hiệu chỉnh ngưỡng); số dấu hiệu lặp lại ở cùng đơn vị |

Báo cáo BGĐ (slide 31) xác định tám dấu hiệu bất thường cần theo dõi, và PL 4 V.6 yêu cầu nền tảng phát hiện và chuyển cho cơ quan tổ chức kiểm tra. Bản đánh giá kiến trúc xếp năng lực này vào H6 ở mức "Một phần" vì hệ thống hiện tại không có. QT-14 đặt việc phát hiện ở phía đọc (SVC-15 analytics) để không ảnh hưởng tới đường ghi của mission-core: job chạy trên ClickHouse với dữ liệu CDC đã giả danh hoá theo ADR-06, chỉ đẩy về MOD-07 mã đối tượng và chuỗi bằng chứng; MOD-07 mới là nơi lưu cảnh báo và quản lý vòng đời.

Nguyên tắc quan trọng nhất của quy trình là cảnh báo không phải kết luận vi phạm. Cảnh báo chỉ cho biết "chỗ này cần xem", vì nhiều dấu hiệu có lý do hợp lệ: một đơn vị có tỷ lệ xuất sắc cao có thể thực sự xuất sắc, một báo cáo tháng có thể được nộp lại vì cùng mẫu. Vì vậy đối tượng của cảnh báo không thấy cảnh báo cho tới khi có kết luận kiểm tra, mọi thao tác xử lý đều có lý do và được ghi sổ cái, và tác động tự động của cảnh báo được giới hạn ở mức tạm thời, có thể đảo ngược (ví dụ tạm chưa tính điểm minh chứng trùng).

Bảng dưới liệt kê tám dấu hiệu, cách phát hiện và hành động mặc định. Ngưỡng là tham số của tenant, có thể điều chỉnh trên màn hình "Ngưỡng phát hiện" bởi người có vai trò `policy_author`.

| Số | Dấu hiệu | Cách phát hiện (ngưỡng mặc định) | Hành động tự động | Hành động đề xuất cho người xử lý |
|---|---|---|---|---|
| 1 | Tự chấm cao hơn thẩm định có hệ thống | Mức tự đề xuất cao hơn mức thẩm định ≥ 3 quý liên tiếp, hoặc điểm tự tính cao hơn điểm sau thẩm định ≥ 5 điểm trong 2 quý | Gợi ý trên hàng đợi thẩm định (SCR-0902) | Nhắc trao đổi với cán bộ; ghi nhận vào nhận xét |
| 2 | Xếp loại đồng loạt, cào bằng | Tỷ lệ HTXSNV của đơn vị vượt ngưỡng khối, hoặc ≥ 90% cán bộ cùng một mức, hoặc độ lệch chuẩn điểm thấp bất thường | Cảnh báo trên SCR-0904 trước khi ký lô | Yêu cầu phiên hiệu chỉnh; xem lại tiêu chí |
| 3 | Mục tiêu đặt quá dễ | KR đạt ≥ 100% ngay trong 2 tuần đầu kỳ, hoặc chỉ tiêu thấp hơn kết quả kỳ trước ≥ 20% | Gắn nhãn trên cây OKR (người duyệt OKR thấy) | Xem lại chỉ tiêu ở kỳ sau; ghi nhận khi thẩm định |
| 4 | Minh chứng trùng hoặc dùng lại | Cùng SHA-256 cho nhiều sản phẩm khác nhau, hoặc tương đồng nội dung OCR ≥ 95% | Tạm chưa tính điểm các minh chứng liên quan | Xác nhận hợp lệ hoặc giao kiểm tra |
| 5 | Cập nhật dồn vào cuối kỳ | ≥ 60% lượt cập nhật tiến độ của đơn vị dồn vào 3 ngày cuối kỳ hoặc cuối tuần | Cảnh báo cho quản lý trực tiếp | Nhắc cập nhật đều; ghi nhận khi đánh giá |
| 6 | Sửa dữ liệu khi đã biết kết quả | Có thay đổi mục tiêu, kế hoạch, nghiệm thu sau thời điểm tính điểm tạm hoặc sau khoá kỳ (qua mở lại) | Cảnh báo mức Cao | Giao kiểm tra; đối chiếu sổ cái |
| 7 | Chẻ nhỏ công việc | Số nhiệm vụ của một người tăng ≥ 50% so với kỳ trước trong khi tổng khối lượng không đổi, hoặc nhiều nhiệm vụ cùng sản phẩm, cùng hạn | Cảnh báo cho người duyệt kế hoạch | Xem lại kế hoạch; gộp nhiệm vụ ở kỳ sau |
| 8 | Miễn trừ "khách quan" lặp lại | Một người nghiệm thu áp miễn trừ chậm khách quan ≥ 3 lần trong 2 quý, hoặc tỷ lệ miễn trừ vượt ngưỡng khối | Cảnh báo cho cơ quan tổ chức | Xem căn cứ miễn trừ; giao kiểm tra nếu cần |

### Lưu đồ quy trình

![Hình 11.9. Lưu đồ làn bơi QT-14: phát hiện và xử lý cảnh báo bất thường](nv-b-qt14-luong.png){width=16cm}

Dữ liệu từ các schema nghiệp vụ được Debezium đẩy sang ClickHouse. Job đêm của analytics chạy tám luật với ngưỡng của từng tenant, chấm mức Cao, Vừa, Thấp và đính kèm chuỗi bằng chứng (mã bản ghi sổ cái, mã minh chứng, mã băm). Mỗi phát hiện sinh một EVT-20; MOD-07 tạo `oversight.anomaly_alert` và áp hành động tự động tương ứng. Cơ quan tổ chức xem cảnh báo trên trung tâm cảnh báo, đi theo chuỗi truy vết tới minh chứng và đánh giá. Nếu thấy hợp lệ, họ xác nhận có lý do và cảnh báo đóng, cờ chặn được gỡ. Nếu cần kiểm tra, họ giao cho cơ quan kiểm tra; kết luận có vi phạm dẫn tới hậu kiểm (QT-13), kết luận không vi phạm thì đóng.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Phát hiện | SVC-15 | Job đêm chạy 8 luật; minh chứng trùng mã băm phát hiện tức thời khi tải lên | Không có | EVT-20 | 02:00 hằng đêm; tức thời với dấu hiệu 4 |
| 2 | Tạo cảnh báo | MOD-07 | Tạo `anomaly_alert` (mức, dấu hiệu, đối tượng, chuỗi bằng chứng); chống trùng theo khoá dấu hiệu và đối tượng | Không có | `oversight.anomaly_alert` `MOI`; EVT-21 tới người xử lý | Ngay khi nhận EVT-20 |
| 3 | Áp hành động tự động | MOD-07 | Áp hành động mặc định của dấu hiệu (ví dụ tạm chưa tính điểm minh chứng trùng) | Không có | `oversight.alert_action` (tự động) | Ngay |
| 4 | Xem cảnh báo | AC-07 | Lọc theo dấu hiệu, mức, trạng thái; mở chi tiết | SCR-1401 | `DANG_XEM` | Trong 10 ngày làm việc |
| 5 | Truy vết bằng chứng | AC-07 | Đi theo chuỗi điểm, cấu phần, KPI, sản phẩm, minh chứng; mở sổ cái nếu cần | SCR-1401, SCR-1603, SCR-1302 | Không thay đổi dữ liệu | Như trên |
| 6 | Xác nhận hợp lệ | AC-07 | Ghi lý do; gỡ hành động tự động | SCR-1401 | `HOP_LE` rồi `DONG`; `alert_action` | Như trên |
| 7 | Giao kiểm tra | AC-07 | Ghi nội dung giao; chọn cơ quan kiểm tra | SCR-1401 | `DA_GIAO_KIEM_TRA`; EVT-21 | Như trên |
| 8 | Kiểm tra, kết luận | AC-10 | Kiểm tra theo nghiệp vụ; kết luận có hoặc không vi phạm | SCR-1401 | `CO_VI_PHAM` hoặc `KHONG_VI_PHAM` | Theo kế hoạch kiểm tra |
| 9 | Chuyển hậu kiểm | AC-10 | Với kết luận có vi phạm, tạo yêu cầu mở lại kỳ (QT-13) | SCR-1301 | `oversight.reopen_request` | Sau bước 8 |
| 10 | Tự đóng | Hệ thống | Nếu dữ liệu nguồn hết dấu hiệu trước khi xử lý (ví dụ minh chứng trùng đã được thay) thì tự đóng có ghi chú | Không có | `TU_DONG_DONG` rồi `DONG` | Khi job chạy lại |

### Trạng thái đối tượng

![Hình 11.10. Sơ đồ trạng thái cảnh báo oversight.anomaly_alert](nv-b-qt14-trangthai.png){width=13cm}

Bộ trạng thái của cảnh báo là đề xuất mới (ghi trong tệp ghi chú). Trạng thái thể hiện rõ sự phân biệt giữa "xem xét" và "kết luận": chỉ cơ quan kiểm tra mới đưa cảnh báo sang `CO_VI_PHAM`, còn cơ quan tổ chức chỉ xác nhận hợp lệ hoặc giao kiểm tra.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | EVT-20 | `MOI` | Không trùng cảnh báo mở cùng dấu hiệu, đối tượng |
| `MOI` | Người xử lý mở | `DANG_XEM` | Người có vai trò `appraiser` trong phạm vi |
| `MOI` | Dữ liệu nguồn hết dấu hiệu | `TU_DONG_DONG` | Job chạy lại không còn phát hiện |
| `DANG_XEM` | Xác nhận hợp lệ | `HOP_LE` | Có lý do ≥ 20 ký tự |
| `DANG_XEM` | Giao kiểm tra | `DA_GIAO_KIEM_TRA` | Có nội dung giao |
| `DA_GIAO_KIEM_TRA` | Kết luận | `CO_VI_PHAM` hoặc `KHONG_VI_PHAM` | Người có vai trò `inspector` |
| `HOP_LE`, `CO_VI_PHAM`, `KHONG_VI_PHAM`, `TU_DONG_DONG` | Hoàn tất | `DONG` | `CO_VI_PHAM` cần có yêu cầu hậu kiểm đã tạo |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-14-01 | Hệ thống phát hiện tám dấu hiệu bất thường theo ngưỡng cấu hình của tenant | PL 4 V.6; slide 31 | Job SQL trên ClickHouse, ngưỡng trong `config.tenant_setting` |
| BR-14-02 | Cảnh báo không phải kết luận vi phạm; chỉ cơ quan kiểm tra được kết luận có vi phạm | PL 4 V.6 | Trạng thái `CO_VI_PHAM` chỉ đặt được bởi vai trò `inspector` |
| BR-14-03 | Đối tượng của cảnh báo không thấy cảnh báo cho tới khi có kết luận kiểm tra | Nguyên tắc khách quan khi kiểm tra | OPA ẩn `anomaly_alert` với chủ thể là đối tượng |
| BR-14-04 | Minh chứng bị cảnh báo trùng chưa được tính điểm cho tới khi người có thẩm quyền xác nhận | PL 4 III.5 | Cờ chặn trong `alert_action`, scoring-service đọc khi tính |
| BR-14-05 | Mọi thao tác xử lý cảnh báo (xác nhận, giao, đóng) phải có lý do và được ghi sổ cái | PL 4 III.2 | Trường lý do bắt buộc; outbox ghi sổ cái |
| BR-14-06 | Job phát hiện chỉ đọc dữ liệu C0, C1 và C2 đã giả danh hoá; không đọc nội dung C3 | ADR-06 | CDC chỉ đẩy cột cho phép; chuỗi bằng chứng chỉ chứa mã |
| BR-14-07 | Phá vỡ chuỗi băm sổ cái luôn tạo cảnh báo mức Cao | ADR-09 | ledger-service phát EVT-20 |
| BR-14-08 | Người xử lý cảnh báo không được là đối tượng của cảnh báo | PL 4 V.1 | OPA |
| BR-14-09 | Tỷ lệ xác nhận hợp lệ của mỗi dấu hiệu được theo dõi để hiệu chỉnh ngưỡng; thay đổi ngưỡng có ghi lý do và phiên bản | Chất lượng phát hiện | Màn hình "Ngưỡng phát hiện", lưu phiên bản trong `config.tenant_setting` |

### Ngoại lệ và xử lý

Nếu job phát hiện lỗi hoặc chạy quá thời gian, analytics ghi lỗi, cảnh báo đã có không bị ảnh hưởng, và vận hành nhận cảnh báo kỹ thuật theo TL-04. Job được thiết kế luỹ đẳng: chạy lại không tạo cảnh báo trùng nhờ khoá (tenant, dấu hiệu, đối tượng, kỳ).

Nếu cảnh báo minh chứng trùng xảy ra ngay trước khoá kỳ, cờ chặn làm các minh chứng đó không được tính khi khoá kỳ. Sau khi khoá, nếu cảnh báo được xác nhận hợp lệ, điều chỉnh điểm phải đi qua mở lại kỳ (QT-13). Để giảm trường hợp này, saga chốt kỳ (QT-08) hiển thị danh sách cảnh báo dấu hiệu 4 còn mở trước khi khoá.

Nếu một đơn vị có quá nhiều cảnh báo cùng dấu hiệu (ví dụ dấu hiệu 5 ở mọi phòng), MOD-07 gộp thành một cảnh báo cấp đơn vị để tránh làm ngập trung tâm cảnh báo.

### Đặc tả màn hình

#### SCR-1401. Trung tâm cảnh báo

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Tập trung mọi cảnh báo bất thường của phạm vi quản lý, cho phép lọc, xem chuỗi truy vết bằng chứng và xử lý từng cảnh báo |
| Tác nhân | AC-07 (`appraiser`); AC-10 (`inspector`) |
| Đường vào | Menu Giám sát › Cảnh báo; thông báo cảnh báo mức Cao; SCR-1601 |
| Quyền | Trong phạm vi cây tổ chức; ẩn cảnh báo mà người xem là đối tượng |
| Thiết bị | Web desktop |

![Hình 11.11. SCR-1401 Trung tâm cảnh báo quý IV/2026, chi tiết cảnh báo minh chứng trùng mã băm](SCR-1401.png){width=16cm}

Màn hình theo tinh thần slide 32 của báo cáo BGĐ: danh sách cảnh báo bên trái, chi tiết và chuỗi truy vết bên phải. Dòng phụ cho biết job phát hiện chạy lúc 02:00 hằng đêm trên ClickHouse và có 5 cảnh báo mới.

Vùng 1 là bảng cảnh báo với mức (Cao, Vừa, Thấp), dấu hiệu, đối tượng, kỳ và trạng thái; tám dòng minh hoạ cả tám dấu hiệu, trong đó dòng "Minh chứng trùng mã băm, Phòng C" đang được chọn. Vùng 4 là ghi chú màu cam "Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm" cùng các nguyên tắc ghi sổ cái và ẩn với đối tượng; bên dưới là thẻ phân bố 90 ngày (23 cảnh báo mới, 14 xác nhận hợp lệ, 7 giao kiểm tra, 2 có vi phạm sau kiểm tra). Vùng 2 là phần đầu chi tiết cảnh báo với chuỗi truy vết PL 4: điểm nhóm thường xuyên của Phòng C, cấu phần A của 3 nhiệm vụ, ba sản phẩm "Báo cáo tháng 10, 11, 12", cùng một tệp BC-thang.pdf có SHA-256 5e88…a1f4; bảng người nộp và thời điểm; nguồn sổ cái (ledger_entry 88412, 90177, 93305) và `evidence.dedup_index`. Vùng 3 là ghi chú tác động (3 minh chứng bị gắn cờ chưa tính điểm), ô ghi chú xử lý bắt buộc và hai nút "Xác nhận hợp lệ", "Giao kiểm tra".

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Lọc dấu hiệu, mức | Danh sách chọn | Không | 8 dấu hiệu; Cao, Vừa, Thấp | Tất cả | Không có | Trạng thái giao diện |
| 2 | Bảng cảnh báo | Bảng | Không áp dụng | Mức, dấu hiệu, đối tượng, kỳ, trạng thái | Sắp theo mức rồi thời gian | Phân trang con trỏ | `oversight.anomaly_alert` |
| 3 | Phân bố 90 ngày | Thẻ số | Không áp dụng | Số nguyên | Tính tức thời | Chỉ đọc | `oversight.anomaly_alert` (đếm theo trạng thái) |
| 4 | Chuỗi truy vết | Danh sách lồng | Không áp dụng | Từ điểm tới minh chứng | Từ cảnh báo | Chỉ đọc | `oversight.anomaly_alert.evidence_chain_json` |
| 5 | Bảng nguồn | Bảng | Không áp dụng | Người nộp, nhiệm vụ, thời điểm | Từ chuỗi | Chỉ đọc | `product.evidence_version`, `evidence.dedup_index` |
| 6 | Tác động tự động | Nhãn | Không áp dụng | Văn bản | Từ hành động | Chỉ đọc | `oversight.alert_action` (loại tự động) |
| 7 | Ghi chú xử lý | Văn bản dài | Có | 20 đến 2.000 ký tự | Trống | Bắt buộc với mọi hành động | `oversight.alert_action.note` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn cảnh báo | Luôn | Tải chi tiết; nếu `MOI` thì chuyển `DANG_XEM` | `GET /api/v1/oversight/alerts/{id}` | Không có |
| Xác nhận hợp lệ | `DANG_XEM`, đủ ghi chú | Đóng cảnh báo hợp lệ, gỡ cờ chặn | `POST /api/v1/oversight/alerts/{id}/actions` (type = HOP_LE) | EVT-21; ghi sổ cái |
| Giao kiểm tra | `DANG_XEM`, đủ ghi chú | Giao cho cơ quan kiểm tra | `POST /api/v1/oversight/alerts/{id}/actions` (type = GIAO_KIEM_TRA) | EVT-21 |
| Kết luận | `inspector`, `DA_GIAO_KIEM_TRA` | Ghi kết luận; có vi phạm thì mở SCR-1301 tạo yêu cầu | `POST /api/v1/oversight/alerts/{id}/actions` (type = KET_LUAN) | EVT-21 |
| Ngưỡng phát hiện | `policy_author` | Mở màn hình ngưỡng, lưu phiên bản mới có lý do | `PUT /api/v1/config/anomaly-thresholds` | Không có |
| Mở sổ cái | Luôn | Chuyển sang SCR-1302 với bộ lọc theo bản ghi | Không có (điều hướng) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-14-01 | Có {n} cảnh báo mới mức Cao trong phạm vi của đồng chí. | Thông báo đẩy | Sau job đêm |
| MSG-14-02 | Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm. Vui lòng ghi rõ lý do xử lý. | Thông tin | Mở chi tiết lần đầu |
| MSG-14-03 | Đã xác nhận hợp lệ. {n} minh chứng được tính điểm trở lại. | Thành công | Xác nhận hợp lệ |
| MSG-14-04 | Kỳ {ky} đã khoá. Kết luận có vi phạm cần được thi hành qua yêu cầu mở lại kỳ. | Cảnh báo | Kết luận có vi phạm |

### Liên kết thiết kế

QT-14 dùng SVC-15 analytics (ClickHouse, job phát hiện), MOD-07 `oversight` (`anomaly_alert`, `alert_action`), SVC-08 evidence-service (`evidence.dedup_index`), SVC-09 ledger-service (chuỗi bằng chứng), SVC-12 thông báo, SVC-07 (đọc cờ chặn). Sự kiện: EVT-20 DauHieuBatThuong, EVT-21, và gián tiếp EVT-22 khi chuyển hậu kiểm. Dữ liệu vào job tuân thủ ADR-06; kiến trúc phía đọc theo ADR-14 (CQRS). Đáp ứng tiêu chí H6 (phát hiện 8 dấu hiệu) và H3 (cảnh báo minh chứng trùng).

# Quy trình cấu hình, phân tích và trợ lý AI

Chương này đặc tả bốn quy trình làm cho nền tảng thích ứng được với nhiều loại khách hàng mà không phải sửa mã: soạn, kiểm thử và phát hành Policy Pack trong Config Studio (QT-15), bảng điều hành và báo cáo cho lãnh đạo (QT-16), trợ lý AI tiếng Việt (QT-17) và cấu hình giao diện riêng theo tenant bằng UI Pack (QT-18). Ba trong bốn quy trình là câu trả lời trực tiếp cho các góp ý của lãnh đạo: QT-15 hiện thực cách chống "mạng nhện" luồng duyệt của góp ý G2 bằng mẫu và tham số; QT-18 hiện thực kết luận của góp ý G1 về giao diện riêng theo tenant với SDUI có giới hạn; QT-16 và QT-17 đặt toàn bộ phần đọc nặng và phần AI ra ngoài đường găng của mission-core, phù hợp tinh thần của góp ý G3 về hiệu năng.

Chương phân biệt rõ hai loại "cấu hình". Cấu hình nghiệp vụ (công thức, bảng xếp loại, luồng duyệt, danh mục) nằm trong Policy Pack, ảnh hưởng tới kết quả đánh giá, nên phải qua test case vàng, phê duyệt và ký số, thời gian phát hành tối đa 5 ngày (NFR-07). Cấu hình trình bày (màu, logo, bố cục, biểu mẫu) nằm trong UI Pack, không ảnh hưởng tới phép tính, nên phát hành trong 1 ngày và quay lui được ngay. Hai loại gói được gắn với nhau bằng tham chiếu phiên bản để biểu mẫu luôn khớp quy tắc.

## QT-15. Soạn, kiểm thử và phát hành Policy Pack

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-15 |
| Tên | Soạn, kiểm thử và phát hành Policy Pack |
| Mục đích | Cho BA cấu hình soạn công thức tính điểm dạng khối ghép, đặt tham số, sửa bảng quyết định xếp loại DMN, cấu hình luồng duyệt bằng chọn mẫu và tham số, chạy bộ test case vàng đạt 100%, gửi phê duyệt, ký số và phát hành gắn với kỳ hiệu lực |
| Phạm vi áp dụng | Policy Pack gốc của khối (Ban Tổ chức TW, Bộ Nội vụ soạn) và Policy Pack kế thừa của tenant (chỉ được sửa phần tenant được phép) |
| Căn cứ | PL 4 VI.5 (Config Studio); PL 3 (công thức); QĐ Điều 11 (điều kiện mức); PL A3, A4 (điểm cần làm rõ: cách tính chất lượng, ngày lịch hay ngày làm việc, cách hiểu 30%); ADR-19 (CEL, DMN 1.4, test case vàng); ADR-04 (mẫu và tham số); NFR-07 |
| Tác nhân | AC-03 BA cấu hình (`policy_author`); AC-08 người phê duyệt phát hành (lãnh đạo cơ quan tổ chức); SVC-07 scoring-service; SVC-06 workflow-service; SVC-10 esign-service |
| Tần suất và khối lượng ước tính | Vài lần mỗi năm cho mỗi khối; tổng số phiên bản phát hành toàn quốc ước tính dưới 200 mỗi năm. Số mẫu luồng toàn hệ thống khoảng 20 đến 40 (hiện có 27 mẫu nhân phiên bản), không tăng theo số tenant |
| Điều kiện bắt đầu | Có văn bản mới, hướng dẫn mới hoặc yêu cầu điều chỉnh; BA nhân bản phiên bản đang dùng |
| Điều kiện kết thúc | Phiên bản mới `DA_PHAT_HANH`, gắn kỳ hiệu lực qua `policy.policy_binding`, đã đồng bộ xuống các cell; phiên bản cũ vẫn áp cho các kỳ đã khoá |
| Đầu vào | Văn bản căn cứ, phiên bản đang dùng, bộ test case vàng đã được ký duyệt |
| Đầu ra | `policy.policy_pack_version` mới, `policy.policy_rule`, `policy.decision_table`, `policy.test_run`, `flowable.wf_binding`, chữ ký phát hành, EVT-13 |
| Chỉ số đo hiệu quả quy trình | Thời gian từ khi có văn bản tới khi phát hành (mục tiêu ≤ 5 ngày); tỷ lệ phiên bản phải thu hồi sau phát hành (mục tiêu 0); số ca test vàng; tỷ lệ khớp cel-java và cel-js (100%) |

Bản đánh giá kiến trúc ghi nhận hệ thống hiện tại không có Policy Pack (tiêu chí H8 "Một phần") và có ba bộ máy tính song song (H4). Hệ quả là mỗi lần văn bản thay đổi phải sửa mã và phát hành lại phần mềm, còn kết quả kỳ cũ không tái lập được. QT-15 tách toàn bộ quy tắc thành dữ liệu có phiên bản: công thức viết bằng CEL (Common Expression Language), bảng xếp loại viết bằng DMN 1.4, luồng duyệt chọn từ danh mục mẫu đã kiểm thử. scoring-service là nơi duy nhất thực thi, bằng cel-java và Flowable DMN.

Config Studio không phải công cụ lập trình. BA cấu hình thao tác trên các khối ghép (ví dụ khối "A · Số lượng", "B · Chất lượng", "C · Tiến độ") và tham số dạng danh sách chọn; biểu thức CEL được sinh ra phía sau và có thể xem ở chế độ "Xem dạng CEL" để kiểm tra. Mọi biểu thức được kiểm tra kiểu khi lưu. Thay đổi chỉ có hiệu lực khi đạt 100% bộ test case vàng, được người có thẩm quyền ký phê duyệt và gắn với một kỳ hiệu lực trong tương lai; kỳ đã khoá không bao giờ bị tính lại bằng phiên bản mới.

Về luồng duyệt, Config Studio không có trình vẽ BPMN tự do. Đây là quyết định có chủ ý của ADR-04 để trả lời góp ý G2: nếu mỗi tenant tự vẽ luồng, số định nghĩa BPMN sẽ tăng theo số tenant (tới 5.000), mỗi định nghĩa cần kiểm thử riêng, và các luồng gọi chéo nhau tạo thành "mạng nhện" khó vận hành. Thay vào đó, đội sản phẩm phát triển và kiểm thử khoảng 20 đến 40 mẫu luồng; tenant chỉ chọn mẫu và điền tham số (số cấp duyệt, vai trò từng cấp, thời hạn, hành vi khi quá hạn, bước ký số, điều kiện bỏ qua cấp), lưu trong `flowable.wf_binding`.

### Lưu đồ quy trình

![Hình 12.1. Lưu đồ làn bơi QT-15: soạn, kiểm thử và phát hành Policy Pack](nv-b-qt15-luong.png){width=16cm}

BA cấu hình nhân bản phiên bản đang dùng thành phiên bản nháp, sửa công thức và tham số, sửa bảng quyết định, chọn mẫu luồng và tham số, rồi chạy test case vàng. Làn "Hệ thống" kiểm tra hai điều kiện: 100% ca đạt và kết quả cel-java trùng cel-js. Không đạt thì quay lại bước sửa; đạt thì phiên bản chuyển `DAT_KIEM_THU`. Người phê duyệt xem khác biệt so với phiên bản đang dùng và kết quả test, rồi ký số phê duyệt hoặc từ chối. Sau khi ký, saga phát hành của workflow-service gắn kỳ hiệu lực, đồng bộ gói từ vùng điều phối toàn cục xuống các cell, nạp vào scoring-service và phát EVT-13. Phiên bản mới áp từ kỳ hiệu lực; các kỳ đã khoá giữ phiên bản cũ.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Nhân bản phiên bản | AC-03 | Chọn gói và phiên bản đang dùng; tạo phiên bản nháp mới kế thừa toàn bộ | SCR-1501 | `policy.policy_pack_version` `NHAP` | Ngày 1 |
| 2 | Soạn công thức | AC-03 | Kéo, sửa khối công thức; kiểm tra kiểu tức thời; xem trước với hồ sơ mẫu | SCR-1501 | `policy.policy_rule` | Ngày 1 đến 2 |
| 3 | Đặt tham số | AC-03 | Cách tính chất lượng, loại ngày tính chậm, làm tròn, điểm tối đa nhóm, ngưỡng việc vượt | SCR-1501 | `policy.policy_rule` (tham số) | Ngày 1 đến 2 |
| 4 | Sửa bảng quyết định | AC-03 | Sửa các dòng DMN xếp loại, quy tắc mức cuối, quy tắc người đứng đầu, mẫu câu giải thích | SCR-1502 | `policy.decision_table` | Ngày 2 |
| 5 | Cấu hình luồng duyệt | AC-03 | Chọn mẫu luồng, điền tham số, xem trước luồng, chạy linter và test của mẫu | SCR-1504 | `flowable.wf_binding` (nháp) | Ngày 2 |
| 6 | Bổ sung test case | AC-03 | Thêm ca kiểm thử cho quy tắc mới; ca mới cần người phụ trách nghiệp vụ ký duyệt mới vào bộ vàng | SCR-1503 | `policy.golden_test_case` | Ngày 2 đến 3 |
| 7 | Chạy test case vàng | AC-03, SVC-07 | Chạy toàn bộ bộ test trên cel-java và cel-js, kiểm thử thuộc tính; hiển thị ca lỗi và khác biệt | SCR-1503 | `policy.test_run`; `DANG_KIEM_THU` rồi `DAT_KIEM_THU` hoặc `NHAP` | Vài giây đến vài phút |
| 8 | Gửi phê duyệt | AC-03 | Chọn kỳ hiệu lực, phạm vi áp dụng (khối, tenant); gửi | SCR-1501, SCR-1503 | `CHO_PHE_DUYET`; EVT-21 | Ngày 3 |
| 9 | Phê duyệt, ký số | AC-08 | Xem báo cáo khác biệt, kết quả test; ký số hoặc từ chối có ý kiến | SCR-1503 (chế độ phê duyệt) | EVT-18 hoặc về `NHAP` | Ngày 3 đến 4 |
| 10 | Saga phát hành | SVC-06 | Gắn `policy_binding` với kỳ hiệu lực; đồng bộ xuống cell; nạp gói vào scoring-service; kích hoạt `wf_binding`; bù trừ nếu một bước lỗi | Không có | `DA_PHAT_HANH`; EVT-13 | Ngày 4 đến 5 |
| 11 | Ngừng phiên bản cũ | Hệ thống | Phiên bản cũ chuyển `NGUNG` với kỳ mới nhưng vẫn được dùng cho kỳ đã khoá và để tính lại khi hậu kiểm | Không có | `NGUNG` | Khi phiên bản mới có hiệu lực |

Thời hạn trong bảng là ví dụ phân bổ để đạt chỉ tiêu NFR-07 "phát hành Policy Pack mới ≤ 5 ngày" cho một thay đổi cỡ vừa (ví dụ đổi cách đếm ngày chậm). Thay đổi lớn như chuyển từ QĐ 366 sang dự thảo Quy định mới (v1.4 lên v2.0) cần nhiều thời gian soạn test hơn nhưng vẫn không cần phát hành lại phần mềm.

Bước 7 chạy hai engine cho cùng một bộ test. cel-java trên máy chủ là nguồn sự thật để tính điểm; cel-js trên trình duyệt dùng cho kiểm tra tức thời trên biểu mẫu và xem trước. Nếu hai engine cho kết quả khác nhau ở bất kỳ ca nào, phiên bản không được đạt kiểm thử, vì khác biệt đó có nghĩa người dùng sẽ thấy một kết quả trên màn hình và nhận một kết quả khác trong phiếu.

### Trạng thái đối tượng

![Hình 12.2. Sơ đồ trạng thái phiên bản Policy Pack policy.policy_pack_version](nv-b-qt15-trangthai.png){width=13cm}

Sơ đồ đúng baseline mục 9.1. Mọi thay đổi nội dung sau `DAT_KIEM_THU` đưa phiên bản trở lại `NHAP`, bảo đảm không thể phê duyệt một nội dung chưa được kiểm thử.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Nhân bản hoặc tạo mới | `NHAP` | `policy_author` của gói |
| `NHAP` | Chạy test case vàng | `DANG_KIEM_THU` | Biểu thức hợp lệ kiểu |
| `DANG_KIEM_THU` | Có ca không đạt hoặc hai engine khác nhau | `NHAP` | Lưu `test_run` |
| `DANG_KIEM_THU` | 100% ca đạt, hai engine khớp | `DAT_KIEM_THU` | Lưu `test_run` |
| `DAT_KIEM_THU` | Sửa nội dung | `NHAP` | Mọi sửa đổi |
| `DAT_KIEM_THU` | Gửi phê duyệt | `CHO_PHE_DUYET` | Có kỳ hiệu lực trong tương lai |
| `CHO_PHE_DUYET` | Từ chối | `NHAP` | Có ý kiến |
| `CHO_PHE_DUYET` | Ký số phê duyệt, saga phát hành xong | `DA_PHAT_HANH` | Người duyệt khác người soạn; EVT-13 |
| `DA_PHAT_HANH` | Phiên bản mới thay thế hoặc thu hồi | `NGUNG` | Vẫn dùng cho kỳ đã gắn |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-15-01 | Công thức viết bằng khối ghép sinh ra biểu thức CEL; biểu thức được kiểm tra kiểu khi lưu và không được truy cập dữ liệu ngoài ngữ cảnh khai báo | ADR-19 | cel-java type-check với khai báo biến của ngữ cảnh tính điểm |
| BR-15-02 | Cách tính chất lượng B là tham số: theo 5 mức của PL 3 (mặc định) hoặc trừ 25% mỗi lần chỉnh sửa theo Bảng chấm điểm | PL 3 mục 2; PL A4 | Danh sách chọn; mỗi giá trị có ca test riêng |
| BR-15-03 | Loại ngày tính chậm là tham số: ngày làm việc theo lịch tenant (mặc định) hoặc ngày lịch | PL 3; PL A4 | Danh sách chọn; hàm `ngay_lam_viec` đọc `config.holiday_calendar` |
| BR-15-04 | Ngưỡng và cách đếm "30% nhiệm vụ vượt mục tiêu" là tham số (tối thiểu hay đúng; đếm theo số nhiệm vụ hay theo điểm) | QĐ Điều 11; PL A4 | Tham số trong DMN |
| BR-15-05 | Tổng điểm tối đa các nhóm bằng 100 với luồng Đảng; 30 và 70 với luồng Nhà nước | PL A3, A5 | Kiểm tra tức thời; chặn lưu |
| BR-15-06 | Bảng xếp loại dùng DMN 1.4 hit policy FIRST; mỗi dòng có điều khoản căn cứ và mẫu câu giải thích | ADR-19; PL A3 | Trình soạn DMN; trường điều khoản và mẫu câu bắt buộc |
| BR-15-07 | Phiên bản chỉ được gửi phê duyệt khi 100% ca test vàng đạt và kết quả cel-java trùng cel-js | ADR-19; ADR-05 | Trạng thái `DAT_KIEM_THU` bắt buộc; nút gửi khoá |
| BR-15-08 | Ca test vàng mới phải được người phụ trách nghiệp vụ ký duyệt trước khi vào bộ vàng; BA không tự sửa kỳ vọng của ca đã ký | Kiểm soát chất lượng | `golden_test_case.approved_by`, chữ ký |
| BR-15-09 | Người phê duyệt phát hành khác người soạn | PL 4 V.1 | OPA |
| BR-15-10 | Phiên bản phát hành phải gắn kỳ hiệu lực; không gắn cho kỳ đã khoá; kỳ đã khoá giữ phiên bản cũ | Tiêu chí I6 | `policy.policy_binding` kiểm tra trạng thái kỳ |
| BR-15-11 | Luồng duyệt chỉ được cấu hình bằng chọn mẫu và tham số; không có trình vẽ BPMN tự do theo tenant | ADR-04; góp ý G2 | Config Studio chỉ ghi `flowable.wf_binding`; định nghĩa BPMN chỉ triển khai qua CI của đội sản phẩm |
| BR-15-12 | Mẫu luồng tuân thủ linter: tối đa 1 tầng call activity, không gọi tiến trình chéo tenant, không có service task gọi đồng bộ ra ngoài, mỗi mẫu có bộ test | ADR-04 | Linter chạy trong CI và khi lưu tham số; hiển thị ở SCR-1504 |
| BR-15-13 | Policy Pack của tenant kế thừa gói gốc và chỉ được sửa phần gói gốc đánh dấu "cho phép tenant điều chỉnh" | Đa khách thuê | Cờ `overridable` trên từng quy tắc |
| BR-15-14 | Mọi phiên bản phát hành được ký số và ghi sổ cái | Tiêu chí I4, I2 | esign-service; outbox |

### Ngoại lệ và xử lý

Nếu saga phát hành lỗi ở một cell (ví dụ cell mất kết nối với vùng điều phối toàn cục), saga thực hiện bù trừ: gỡ `policy_binding` ở các cell đã nạp, giữ phiên bản ở `CHO_PHE_DUYET` và báo lỗi. Phiên bản không bao giờ ở trạng thái "đã phát hành một nửa". Khi cell trở lại, BA bấm phát hành lại mà không cần ký lại nếu nội dung không đổi.

Nếu sau khi phát hành phát hiện sai sót trước kỳ hiệu lực, người phê duyệt có thể thu hồi (`NGUNG`) và phiên bản trước tiếp tục áp dụng. Nếu kỳ hiệu lực đã bắt đầu nhưng chưa khoá, phải phát hành phiên bản sửa và scoring-service tính lại các hồ sơ của kỳ đang mở; nếu kỳ đã khoá, việc sửa kết quả phải đi qua QT-13.

Nếu một ca test vàng có kỳ vọng mâu thuẫn với văn bản (như ví dụ CV1 = 15 hay 30 điểm trong PL A4), ca được giữ trong bộ test với ghi chú sai lệch và kỳ vọng theo cách hiểu đã chọn; khi có hướng dẫn chính thức, ca được cập nhật qua ký duyệt mới.

### Đặc tả màn hình

#### SCR-1501. Config Studio – công thức

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn công thức KPI và tổng hợp điểm bằng khối ghép, đặt tham số, xem trước với hồ sơ mẫu |
| Tác nhân | AC-03 (`policy_author`) |
| Đường vào | Menu Config Studio › Công thức |
| Quyền | `policy_author` của gói; tenant chỉ sửa phần được phép |
| Thiết bị | Web desktop (màn hình lõi viết mã, không cấu hình SDUI) |

![Hình 12.3. SCR-1501 Config Studio, công thức KPI dạng khối ghép và tham số](SCR-1501.png){width=16cm}

Thanh trên cùng cho biết gói `khoi-dang/quy-dinh-moi` đang soạn phiên bản v2.1 ở trạng thái `NHAP`, trong khi v2.0 đã phát hành và đang áp dụng cho quý IV/2026; dòng kế thừa ghi "QĐ 366 v1.4 → dự thảo Quy định mới v2.x". Dải thẻ (công thức, bảng quyết định, biểu mẫu, luồng duyệt, danh mục, test case, lịch sử) là các phần của một Policy Pack.

Vùng 1 là công thức KPI dạng khối: KPI = (A · Số lượng + B · Chất lượng + C · Tiến độ) / 3, mỗi khối một màu; bên dưới là ba thẻ chi tiết với biểu thức CEL tương ứng `min(so_luong_duyet / ke_hoach, 1.0)`, `muc_chat_luong` và `max(0.0, 1.0 - 0.05 * ngay_cham_chu_quan)`, kèm ghi chú nguồn dữ liệu. Vùng 2 là thẻ tham số: cách tính chất lượng (theo 5 mức của PL 3, có nhắc phương án khác ở PL A4), trừ tiến độ 0,05 mỗi ngày làm việc theo lịch tenant, cách xử lý lý do trả lại, quy tắc làm tròn. Vùng 3 là thẻ tổng hợp điểm với biểu thức tổng và bảng điểm tối đa nhóm 30 và 70, có kiểm tra tổng bằng 100. Vùng 4 là thẻ xem trước với hồ sơ mẫu TC-017: nhiệm vụ then chốt 2 cho KPI 0,783, nhóm then chốt 63,6, nhóm thường xuyên 27,6, điểm 91,2. Vùng 5 là nút "Gửi phê duyệt phát hành", chỉ bật khi phiên bản ở `DAT_KIEM_THU`.

Công thức trong Policy Pack được lưu dưới dạng CEL. Đoạn dưới minh hoạ cách các khối ở vùng 1 và vùng 3 được biểu diễn; biến `nv` là một nhiệm vụ trong ngữ cảnh tính điểm, `tham_so` là tham số của gói; các hàm `math.least`, `math.greatest` thuộc thư viện mở rộng math của CEL.

```yaml
# policy_rule: khoi-dang/quy-dinh-moi v2.1, nhóm cong_thuc
kpi_nhiem_vu: >
  (math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)
   + nv.muc_chat_luong
   + math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(nv.ngay_cham_chu_quan))) / 3.0
# sum() là hàm mở rộng được đăng ký trong môi trường CEL của scoring-service
diem_ket_qua: >
  sum(nhom.map(n, n.kpi_nhom * n.diem_toi_da))
tham_so:
  tru_tien_do: 0.05
  loai_ngay: NGAY_LAM_VIEC
  cach_tinh_chat_luong: NAM_MUC_PL3
  lam_tron: { chu_so: 1, kieu: NUA_LEN }
```

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Gói, phiên bản | Nhãn | Không áp dụng | Mã gói, vX.Y, trạng thái | Từ dữ liệu | Chỉ đọc | `policy.policy_pack`, `policy.policy_pack_version` |
| 2 | Khối công thức KPI | Trình soạn khối | Có | Khối và toán tử | Kế thừa | Kiểm tra kiểu CEL khi lưu | `policy.policy_rule.expression` (rule_key = kpi_nhiem_vu) |
| 3 | Biểu thức A, B, C | Mã CEL (chỉ đọc khi ở chế độ khối) | Có | CEL | Kế thừa | Kiểu trả về double trong [0, 1] | `policy.policy_rule.expression` |
| 4 | Cách tính chất lượng | Danh sách chọn | Có | NAM_MUC_PL3, TRU_25_MOI_LAN | NAM_MUC_PL3 | Không có | `policy.policy_rule.params_json` |
| 5 | Mức trừ tiến độ | Số | Có | 0,00 đến 0,20 | 0,05 | Hai chữ số thập phân | `policy.policy_rule.params_json` |
| 6 | Loại ngày | Danh sách chọn | Có | NGAY_LAM_VIEC, NGAY_LICH | NGAY_LAM_VIEC | Không có | `policy.policy_rule.params_json` |
| 7 | Lý do trả lại tính vào chất lượng | Danh sách chọn | Có | Ba lựa chọn | Chỉ lý do cốt lõi | Không có | `policy.policy_rule.params_json` |
| 8 | Làm tròn | Danh sách chọn | Có | Số chữ số, kiểu | 1, nửa lên | Không có | `policy.policy_rule.params_json` |
| 9 | Điểm tối đa nhóm | Số | Có | Số nguyên | 30, 70 | Tổng 100 (Đảng) | `policy.policy_rule.params_json` (rule_key = nhom) |
| 10 | Hồ sơ mẫu xem trước | Danh sách chọn | Không | Mã ca test | TC-017 | Chỉ ca trong bộ vàng | `policy.golden_test_case` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| So sánh v2.0 | Luôn | Hiển thị khác biệt quy tắc, tham số giữa hai phiên bản | `GET /api/v1/policies/packs/{id}/versions/{v}/diff?base=2.0` | Không có |
| Xem dạng CEL | Luôn | Chuyển trình soạn sang xem mã CEL | Không có (phía client) | Không có |
| Lưu nháp | Phiên bản `NHAP` hoặc `DAT_KIEM_THU` | Kiểm tra kiểu, lưu; nếu đang `DAT_KIEM_THU` thì về `NHAP` | `PUT /api/v1/policies/packs/{id}/versions/{v}/rules/{key}` | Không có |
| Xem trước | Luôn | Tính thử hồ sơ mẫu bằng cel-js và cel-java | `POST /api/v1/policies/packs/{id}/versions/{v}/evaluate` | Không có |
| Chạy test case | Luôn | Chuyển sang SCR-1503 và bắt đầu chạy | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (202) | Không có |
| Gửi phê duyệt phát hành | `DAT_KIEM_THU` | Chọn kỳ hiệu lực, phạm vi; khởi động mẫu duyệt phát hành | `POST /api/v1/policies/packs/{id}/versions/{v}/submit` | EVT-21 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-01 | Biểu thức không hợp lệ: {loi_cel} tại khối {khoi}. | Lỗi kiểm tra | Kiểm tra kiểu thất bại |
| MSG-15-02 | Tổng điểm tối đa các nhóm là {tong}, phải bằng 100. | Lỗi kiểm tra | Sai tổng |
| MSG-15-03 | Phiên bản đã thay đổi sau lần kiểm thử gần nhất nên trở về trạng thái nháp. Vui lòng chạy lại test case. | Cảnh báo | Sửa khi `DAT_KIEM_THU` |

#### SCR-1502. Bảng quyết định xếp loại

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn bảng DMN xếp loại cá nhân, quy tắc mức cuối, quy tắc người đứng đầu và mẫu câu giải thích |
| Tác nhân | AC-03 (`policy_author`) |
| Đường vào | Config Studio › thẻ Bảng quyết định |
| Quyền | Như SCR-1501 |
| Thiết bị | Web desktop |

![Hình 12.4. SCR-1502 Bảng quyết định xếp loại DMN 1.4 với hit policy FIRST](SCR-1502.png){width=16cm}

Bảng quyết định `xep_loai_ca_nhan` có sáu dòng, đầu vào là điểm, tỷ lệ mục tiêu O, tỷ lệ việc vượt, giải pháp đột phá, mức tiêu chí chung, cờ loại trừ; đầu ra là mức, điều khoản và lời giải thích. Hit policy FIRST nghĩa là dòng đầu tiên thoả mãn được chọn, nên các dòng KHTNV đứng trước, dòng HTXSNV đứng trước dòng HTTNV, và dòng HTTNV chính là "mức liền kề" khi thiếu điều kiện xuất sắc.

Vùng 1 là thanh gói và dải thẻ. Vùng 2 là bảng DMN; dòng 5 (≥ 80 điểm, tiêu chí chung từ "Đảm bảo", không loại trừ, kết quả HTTNV, căn cứ "QĐ Điều 11 · PL 6 §12", giải thích "Thiếu điều kiện mức trên, chuyển mức liền kề") đang được chọn vì đây là dòng áp cho hồ sơ mẫu. Vùng 3 là quy tắc "mức cuối cùng" `min_muc(muc_ket_qua_nhiem_vu, muc_tu_tieu_chi_chung)` và bảng quy đổi tiêu chí chung. Vùng 4 là quy tắc người đứng đầu theo khoản 14.3.3 viết bằng CEL. Vùng 5 là mẫu câu giải thích có các biến `muc_tren`, `diem`, `nguong`, `dieu_kien_thieu`, `gia_tri`, `dieu_khoan`; phiên bản Policy Pack được chèn tự động.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Hit policy | Danh sách chọn | Có | FIRST, UNIQUE, PRIORITY | FIRST | Chỉ các policy được hỗ trợ | `policy.decision_table.hit_policy` |
| 2 | Cột đầu vào | Biểu thức FEEL đơn giản | Có | So sánh, khoảng, giá trị | Trống (ô để trống nghĩa là bất kỳ) | Kiểu khớp khai báo | `policy.decision_table.rules_json` |
| 3 | Cột kết quả mức | Danh sách chọn | Có | 4 mức | Trống | Không có | `policy.decision_table.rules_json` |
| 4 | Cột điều khoản | Văn bản | Có | ≤ 100 ký tự | Trống | Bắt buộc mỗi dòng | `policy.decision_table.rules_json` |
| 5 | Cột giải thích | Văn bản | Có | ≤ 200 ký tự | Trống | Bắt buộc mỗi dòng | `policy.decision_table.rules_json` |
| 6 | Quy tắc mức cuối | Mã CEL | Có | CEL | Kế thừa | Kiểm tra kiểu | `policy.policy_rule` (rule_key = muc_cuoi) |
| 7 | Quy tắc người đứng đầu | Mã CEL | Có | CEL | Kế thừa | Kiểm tra kiểu | `policy.policy_rule` (rule_key = nguoi_dung_dau) |
| 8 | Mẫu câu giải thích | Văn bản có biến | Có | ≤ 1.000 ký tự | Kế thừa | Mọi biến phải tồn tại trong ngữ cảnh | `policy.decision_table.explanation_templates_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Thêm, xoá, đổi thứ tự dòng | Phiên bản `NHAP` | Sửa bảng trên client, kiểm tra trùng và chồng lấn | Không có (lưu khi bấm lưu) | Không có |
| Xuất DMN XML | Luôn | Xuất bảng theo chuẩn DMN 1.4 | `GET /api/v1/policies/packs/{id}/versions/{v}/decision-tables/{key}/dmn` | Không có |
| Lưu nháp | Phiên bản `NHAP` | Kiểm tra cú pháp FEEL, biến của mẫu câu | `PUT /api/v1/policies/packs/{id}/versions/{v}/decision-tables/{key}` | Không có |
| Chạy test case | Luôn | Như SCR-1501 | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-04 | Dòng {i} không bao giờ được chọn vì dòng {j} phía trên luôn thoả mãn trước (hit policy FIRST). | Cảnh báo | Phát hiện dòng bị che |
| MSG-15-05 | Mẫu câu giải thích dùng biến {bien} không có trong ngữ cảnh xếp loại. | Lỗi kiểm tra | Biến không tồn tại |

#### SCR-1503. Chạy test case vàng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Chạy bộ test case vàng trên hai engine, xem kết quả từng ca, lịch sử chạy, chi tiết lỗi; là điều kiện để gửi phê duyệt |
| Tác nhân | AC-03; AC-08 xem khi phê duyệt |
| Đường vào | Config Studio › thẻ Test case; nút "Chạy test case" ở SCR-1501, SCR-1502 |
| Quyền | `policy_author`; người phê duyệt chỉ đọc |
| Thiết bị | Web desktop |

![Hình 12.5. SCR-1503 Kết quả chạy test case vàng lần 14 đạt 148/148](SCR-1503.png){width=16cm}

Dòng phụ cho biết bộ test `bo-ca-mau-ban-to-chuc-v3` có 148 ca, được Ban Tổ chức TW ký duyệt ngày 02/10/2026, chạy trên cel-java và cel-js. Bốn thẻ số liệu là phần quan trọng nhất của màn hình vì chúng là điều kiện phát hành.

Vùng 1 là thanh gói và dải thẻ. Vùng 2 là bốn thẻ: lần chạy #14 của v2.1 đạt 148/148 trong 6,8 giây; hai engine khớp 148/148; kiểm thử thuộc tính 10.000 bộ dữ liệu ngẫu nhiên với điều kiện điểm luôn trong [0; 100] và C ≥ 0; ghi chú "Tính lại kỳ đã khoá: Không áp dụng, v2.1 chỉ hiệu lực từ Q I/2027". Bên dưới là bảng kết quả từng ca, minh hoạ 8 ca tiêu biểu như TC-017 "Hạ mức do thiếu 30% việc vượt" (HTTNV, 91,2), TC-023 "Chậm 21 ngày làm việc nên C = 0, không âm", TC-058 "Người đứng đầu: 52% đơn vị KHTNV", TC-077 "Ngày lễ không tính ngày chậm", TC-131 "Số liệu ví dụ PL 1 Bảng 3". Vùng 3 là lịch sử chạy: #14 đạt 148/148, #13 đạt 147/148, #9 của v2.0 đạt 148/148. Vùng 4 là thẻ "Lỗi ở lần #13 (đã sửa)": TC-077 kỳ vọng C = 0,90 nhưng thực tế 0,85 do tham số loại ngày bị đổi sang ngày lịch ở nháp 6, kèm đoạn khác biệt cấu hình. Vùng 5 là nút "Gửi phê duyệt phát hành", chỉ bật khi 100% ca đạt.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ test | Nhãn | Không áp dụng | Mã bộ, số ca, người ký, ngày | Bộ gắn với gói | Chỉ đọc | `policy.golden_test_case` (nhóm theo bộ) |
| 2 | Kết quả lần chạy | Thẻ số | Không áp dụng | Đạt / tổng, thời gian | Lần mới nhất | Chỉ đọc | `policy.test_run.passed`, `policy.test_run.total`, `policy.test_run.duration_ms` |
| 3 | Khớp hai engine | Thẻ số | Không áp dụng | Khớp / tổng | Lần mới nhất | Phải bằng tổng | `policy.test_run.engine_match` |
| 4 | Kiểm thử thuộc tính | Thẻ số | Không áp dụng | Số bộ dữ liệu, bất biến | 10.000 | Không vi phạm bất biến | `policy.test_run.property_result_json` |
| 5 | Bảng ca | Bảng | Không áp dụng | Mã, mô tả, kỳ vọng, thực tế, kết quả | Lọc tất cả | Chỉ đọc | `policy.test_run.cases_json` |
| 6 | Lịch sử chạy | Danh sách | Không áp dụng | Số lần, kết quả, thời điểm, bản nháp | 10 lần gần nhất | Chỉ đọc | `policy.test_run` |
| 7 | Chi tiết lỗi | Thẻ | Không áp dụng | Ca, kỳ vọng, thực tế, khác biệt cấu hình | Lần lỗi được chọn | Chỉ đọc | `policy.test_run.cases_json`, khác biệt phiên bản |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Thêm ca kiểm thử | `policy_author` | Tạo ca mới ở trạng thái chờ ký duyệt | `POST /api/v1/policies/test-cases` | Không có |
| Chạy lại | Luôn | Chạy toàn bộ bộ vàng trên hai engine và kiểm thử thuộc tính | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (202) | Không có |
| Lọc ca | Luôn | Lọc đạt, không đạt | Phía client | Không có |
| Gửi phê duyệt phát hành | `DAT_KIEM_THU` | Như SCR-1501 | `POST /api/v1/policies/packs/{id}/versions/{v}/submit` | EVT-21 |
| Ký số phê duyệt | AC-08, `CHO_PHE_DUYET` | Ký số, khởi động saga phát hành | `POST /api/v1/policies/packs/{id}/versions/{v}/approve` (202) | EVT-18, EVT-13 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-06 | Lần chạy #{n}: {dat}/{tong} ca đạt. Phiên bản chưa đạt kiểm thử nên chưa thể gửi phê duyệt. | Cảnh báo | Có ca không đạt |
| MSG-15-07 | Ca {ma} cho kết quả khác nhau giữa cel-java ({gt_java}) và cel-js ({gt_js}). | Lỗi | Hai engine khác nhau |
| MSG-15-08 | Phiên bản {v} đã được phát hành, áp dụng từ kỳ {ky}. Các kỳ đã khoá tiếp tục dùng phiên bản cũ. | Thành công | Nhận EVT-13 |

#### SCR-1504. Cấu hình luồng duyệt (mẫu + tham số)

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Chọn mẫu luồng duyệt đã kiểm thử và điền tham số cho khối hoặc tenant, xem trước luồng sinh từ mẫu, chạy linter và test của mẫu |
| Tác nhân | AC-03 (`policy_author`) |
| Đường vào | Config Studio › thẻ Luồng duyệt |
| Quyền | `policy_author`; tham số phát hành cùng Policy Pack |
| Thiết bị | Web desktop |

![Hình 12.6. SCR-1504 Cấu hình luồng duyệt bằng chọn mẫu và tham số](SCR-1504.png){width=16cm}

Dòng phụ đầu trang nêu nguyên tắc "Không vẽ BPMN tự do theo tenant, chọn mẫu đã kiểm thử, điền tham số, lưu vào flowable.wf_binding". Màn hình là bằng chứng giao diện cho cách trả lời góp ý G2.

Vùng 1 là danh sách mẫu luồng: `duyet-ho-so-danh-gia` v3 với 42 test (đang chọn), `duyet-ke-hoach-quy` v2, `duyet-kep-chi-huy-cap-uy` v1 (lực lượng vũ trang, chỉ huy song song chính uỷ), `xu-ly-khieu-nai` v2, `mo-lai-ky-hai-nguoi` v1; ghi chú cho biết toàn hệ thống có 27 mẫu nhân phiên bản và số định nghĩa BPMN không tăng theo số tenant. Vùng 2 là thẻ tham số áp cho khối Đảng, tenant Đảng uỷ các cơ quan Đảng TW, từ quý I/2027: 4 cấp duyệt, cấp 2 thẩm định bởi vai trò `appraiser` của Ban Tổ chức, cấp 4 quyết định bởi Ban Thường vụ, thời hạn mỗi bước 3 ngày làm việc và nhắc trước 1 ngày, quá hạn thì chuyển cấp phó được uỷ quyền, ký số ở bước tự đánh giá, thẩm định, quyết định. Vùng 3 là điều kiện bỏ qua cấp 3 viết bằng CEL: người được đánh giá là chuyên viên và mức thẩm định bằng mức tự đề xuất. Vùng 4 là xem trước luồng chỉ đọc sinh từ mẫu (tự đánh giá, thẩm định, đề xuất có thể bỏ qua, quyết định ký lô, khoá hồ sơ) và bốn nhãn linter: đạt; tối đa 1 tầng call activity; không có service task gọi đồng bộ ra ngoài; 42/42 test mẫu đạt với tham số này.

Tham số được lưu thành một bản ghi `flowable.wf_binding`. Khi một hồ sơ cần khởi động luồng, workflow-service tra `wf_binding` theo (tenant, khối, loại quy trình, kỳ) để biết mẫu và tham số, rồi khởi động tiến trình của mẫu với các biến tương ứng. Một ví dụ bản ghi:

```json
{
  "template_key": "duyet-ho-so-danh-gia",
  "template_version": 3,
  "scope": { "tenant": "dang-uy-cac-co-quan-dang-tw", "khoi": "DANG", "from_period": "2027-Q1" },
  "params": {
    "so_cap": 4,
    "cap": [
      { "buoc": "TU_DANH_GIA", "vai_tro": "member", "ky_so": true },
      { "buoc": "THAM_DINH", "vai_tro": "appraiser", "don_vi": "BAN_TO_CHUC", "ky_so": true },
      { "buoc": "DE_XUAT", "vai_tro": "manager", "bo_qua_khi": "nguoi_duoc_danh_gia.chuc_danh in ['CHUYEN_VIEN'] && muc_tham_dinh == muc_tu_de_xuat" },
      { "buoc": "QUYET_DINH", "vai_tro": "decider", "ky_lo": true, "ky_so": true }
    ],
    "thoi_han_moi_buoc": "P3D_LV",
    "nhac_truoc": "P1D_LV",
    "khi_qua_han": "CHUYEN_CAP_PHO_UY_QUYEN"
  }
}
```

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mẫu luồng | Danh sách thẻ | Có | Khoá mẫu, phiên bản | Mẫu đang dùng | Chỉ mẫu đã phát hành, phù hợp loại quy trình | `flowable.wf_template` |
| 2 | Phạm vi áp dụng | Nhãn và danh sách chọn | Có | Khối, tenant, kỳ bắt đầu | Từ gói | Kỳ bắt đầu chưa khoá | `flowable.wf_binding.scope_json` |
| 3 | Số cấp duyệt | Danh sách chọn | Có | Trong khoảng mẫu cho phép (2 đến 4) | 4 | Theo khai báo mẫu | `flowable.wf_binding.params_json` |
| 4 | Vai trò, đơn vị từng cấp | Danh sách chọn | Có | Vai trò hệ thống, đơn vị | Theo mẫu | Vai trò tồn tại trong `config.role_binding` | `flowable.wf_binding.params_json` |
| 5 | Thời hạn, nhắc | Thời lượng | Có | Số ngày làm việc | 3 và 1 | 1 đến 15 | `flowable.wf_binding.params_json` |
| 6 | Hành vi khi quá hạn | Danh sách chọn | Có | Nhắc lại, chuyển cấp phó, chuyển cấp trên | Chuyển cấp phó | Theo khai báo mẫu | `flowable.wf_binding.params_json` |
| 7 | Bước ký số | Chọn nhiều | Có | Các bước của mẫu | Ba mốc pháp lý | Không bỏ được bước ký bắt buộc của mẫu | `flowable.wf_binding.params_json` |
| 8 | Điều kiện bỏ qua cấp | Mã CEL | Không | CEL trả về bool | Trống | Kiểm tra kiểu; chỉ áp cho cấp mẫu cho phép bỏ qua | `flowable.wf_binding.params_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn mẫu | Luôn | Tải khai báo tham số của mẫu | `GET /api/v1/policies/workflow-templates/{key}/versions/{v}` | Không có |
| Mô phỏng luồng | Đủ tham số | Chạy mẫu với tham số trên engine thử, với một số hồ sơ mẫu | `POST /api/v1/policies/workflow-bindings/simulate` | Không có |
| Lưu tham số | Đủ tham số, linter đạt | Lưu `wf_binding` nháp thuộc phiên bản Policy Pack đang soạn; chạy test của mẫu | `PUT /api/v1/policies/packs/{id}/versions/{v}/workflow-bindings/{key}` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-09 | Tham số chưa hợp lệ với mẫu {mau}: {loi}. | Lỗi kiểm tra | Linter hoặc test mẫu không đạt |
| MSG-15-10 | Luồng duyệt chỉ được cấu hình bằng mẫu và tham số. Nếu cần quy trình mới, vui lòng gửi yêu cầu bổ sung mẫu cho đội sản phẩm. | Thông tin | Người dùng tìm chức năng vẽ luồng |

### Liên kết thiết kế

QT-15 do MOD-05 `policy` quản lý (bảng `policy_pack`, `policy_pack_version`, `policy_rule`, `decision_table`, `golden_test_case`, `test_run`, `policy_binding`), dùng SVC-07 scoring-service (cel-java, Flowable DMN), SVC-06 workflow-service (`flowable.wf_template`, `flowable.wf_binding`, saga phát hành), SVC-10 esign-service, SVC-17 web-frontend với `@vt/form-engine` (cel-js) và vùng điều phối toàn cục (kho Policy Pack gốc). Sự kiện: EVT-13 PolicyPackDaPhatHanh, EVT-18, EVT-21. Liên quan ADR-19 (bộ máy quy tắc), ADR-04 (G2, mẫu và tham số), NFR-07 (≤ 5 ngày). Đáp ứng tiêu chí H8 (Config Studio, Policy Pack có phiên bản và test case), H4 (một bộ máy tính duy nhất cấu hình được), H9 (hai luồng), I6 (phiên bản quy tắc gắn kết quả).

## QT-16. Bảng điều hành và báo cáo

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-16 |
| Tên | Bảng điều hành và báo cáo |
| Mục đích | Cung cấp cho lãnh đạo bảng điều hành theo phạm vi quản lý (4 con số chính, so sánh đơn vị, tóm tắt tuần do AI tạo, việc cần chỉ đạo), cho phép tự dựng dashboard bằng kéo thả chỉ số chuẩn hoá và đi xuống theo chuỗi truy vết tới minh chứng và căn cứ |
| Phạm vi áp dụng | Mọi lãnh đạo, quản lý trong phạm vi cây tổ chức của mình; báo cáo định kỳ; so sánh nhiều kỳ |
| Căn cứ | PL 5 (phân tích, phân nhóm); PL 4 III.2 (truy vết hai chiều); QĐ khoản 10.2.2 (05 chỉ tiêu KT-XH, chỉ tính chỉ tiêu đã công bố số liệu); báo cáo BGĐ slide 18, 24 và PL A2 nhóm 7; ADR-14 |
| Tác nhân | AC-04 lãnh đạo (`leader`); AC-05 quản lý; SVC-15 analytics; SVC-14 ai-gateway; MOD-02 (nhắc, giao lại) |
| Tần suất và khối lượng ước tính | Hằng ngày. Giả định khoảng 5% người dùng là lãnh đạo, quản lý (khoảng 300.000 người), mỗi người mở bảng điều hành 2 lần mỗi ngày; tải truy vấn đỉnh ước tính khoảng 300 đến 600 truy vấn mỗi giây toàn quốc vào kho phân tích, phần lớn trúng bộ đệm 5 phút của Cube (số ước tính) |
| Điều kiện bắt đầu | Người dùng có vai trò `leader` hoặc `manager` mở bảng điều hành |
| Điều kiện kết thúc | Người dùng đã xem, hoặc đã thực hiện hành động chỉ đạo (nhắc, giao lại), hoặc đã lưu dashboard tự dựng |
| Đầu vào | Dữ liệu CDC trong ClickHouse; lớp ngữ nghĩa Cube; tóm tắt tuần của ai-gateway; dữ liệu chi tiết trong mission-core khi đi xuống |
| Đầu ra | Màn hình, báo cáo xuất; dashboard tự dựng (`config.dashboard_def`); việc nhắc, giao lại trong MOD-02 |
| Chỉ số đo hiệu quả quy trình | Thời gian tải bảng điều hành p95 ≤ 1 giây (NFR-03); độ trễ dữ liệu ≤ 5 phút; tỷ lệ việc chậm được chỉ đạo trong 1 ngày; số dashboard tự dựng được dùng lại |

Bản đánh giá kiến trúc ghi nhận hệ thống hiện tại chỉ có 7 khối báo cáo cố định (H7 "Một phần") và các truy vấn báo cáo chạy thẳng trên CSDL nghiệp vụ. QT-16 tách hoàn toàn phía đọc theo mẫu CQRS (ADR-14): dữ liệu từ các schema nghiệp vụ được Debezium đẩy sang ClickHouse, lớp ngữ nghĩa Cube định nghĩa một lần các chỉ số chuẩn (tiến độ mục tiêu O, tỷ lệ nhiệm vụ đúng hạn, điểm KPI trung bình, phân bố mức…), và mọi bảng điều hành, dashboard tự dựng, báo cáo đều truy vấn qua Cube với ngữ cảnh bảo mật (securityContext) chứa tenant và đường dẫn cây tổ chức của người xem. Nhờ đó, truy vấn báo cáo không bao giờ chạm vào PostgreSQL của mission-core, và chỉ số "tiến độ mục tiêu O" có cùng một định nghĩa ở mọi màn hình.

Chuỗi đi xuống điểm → cấu phần → KPI/KR → sản phẩm → minh chứng → kế hoạch → mục tiêu O → căn cứ là yêu cầu truy vết hai chiều của PL 4 III.2. Ba mắt xích đầu lấy từ kho phân tích, các mắt xích sau lấy từ mission-core theo mã đối tượng, có kiểm tra quyền ở từng bước, vì minh chứng và nhận xét là dữ liệu C2 không đưa sang kho phân tích ở dạng rõ.

### Lưu đồ quy trình

![Hình 12.7. Lưu đồ làn bơi QT-16: bảng điều hành, đi xuống và dựng dashboard](nv-b-qt16-luong.png){width=16cm}

Lãnh đạo mở bảng điều hành; dữ liệu đến từ lớp ngữ nghĩa với phạm vi cây tổ chức của người xem, tóm tắt tuần đến từ ai-gateway ở chế độ chỉ đọc. Sau khi đọc bốn con số, so sánh đơn vị và tóm tắt, lãnh đạo chọn một trong ba hành động. "Đi xuống" mở chuỗi truy vết tới minh chứng, khi đó mission-core trả chi tiết theo mã có kiểm tra quyền. "Chỉ đạo" gửi nhắc hoặc giao lại nhiệm vụ chỉ bằng một lần bấm, MOD-02 phát EVT-04 hoặc EVT-21. "Tự dựng" mở trình dựng dashboard để kéo thả các chỉ số chuẩn của lớp ngữ nghĩa.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở bảng điều hành | AC-04 | Tải 4 con số, bảng so sánh đơn vị, xu hướng, tóm tắt tuần, việc cần chỉ đạo theo phạm vi | SCR-1601 | Không có | p95 ≤ 1 giây |
| 2 | So sánh, lọc | AC-04 | Chọn kỳ, so với kỳ trước, lọc theo khối, luồng | SCR-1601 | Không có | Tức thời |
| 3 | Đọc tóm tắt AI | AC-04 | Đọc 3 đến 5 ý nổi bật có nhãn "Nội dung do AI tạo" và nguồn; mở "Xem dữ liệu đã dùng" | SCR-1601 | Không có | Tóm tắt sinh mỗi sáng thứ Hai |
| 4 | Chỉ đạo | AC-04 | Bấm "Nhắc" hoặc "Giao lại" trên việc chậm; giao lại mở hộp thoại chọn người | SCR-1601 | EVT-21 hoặc EVT-04 | Tức thời |
| 5 | Đi xuống | AC-04 | Bấm đơn vị, rồi cán bộ, rồi điểm; đi theo chuỗi tới minh chứng, kế hoạch, mục tiêu O, căn cứ | SCR-1603 | Không có; truy cập C2 được ghi sổ cái | Mỗi bước p95 ≤ 300 ms |
| 6 | Dựng dashboard | AC-04, AC-05 | Kéo chỉ số và chiều vào lưới, chọn kiểu biểu đồ, bộ lọc, cho phép đi xuống | SCR-1602 | `config.dashboard_def` (nháp) | Không giới hạn |
| 7 | Lưu, chia sẻ | AC-04 | Lưu dashboard; chia sẻ cho nhóm người dùng; người được chia sẻ chỉ thấy dữ liệu trong phạm vi của chính họ | SCR-1602 | `config.dashboard_def` | Tức thời |
| 8 | Xuất báo cáo | AC-04 | Xuất báo cáo PDF hoặc XLSX theo mẫu | SCR-1601 | Tác vụ nền | ≤ 1 phút |

Bước 7 cho thấy cách phân quyền dữ liệu được giữ nguyên khi chia sẻ dashboard: dashboard chỉ là định nghĩa (chỉ số, chiều, bộ lọc, bố cục), không chứa dữ liệu. Khi người được chia sẻ mở, Cube sinh truy vấn với securityContext của chính họ. Bí thư chia sẻ "Bảng của Bí thư" cho Ban Thường vụ thì mỗi uỷ viên thấy số liệu đúng phạm vi mình phụ trách.

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-16-01 | Mọi số liệu trên bảng điều hành và dashboard được lọc theo phạm vi cây tổ chức của người xem | PL 4 V.1; ADR-06 | securityContext của Cube chứa `tenant` và `org_path`; Cube áp điều kiện ở mọi truy vấn |
| BR-16-02 | Dashboard tự dựng chỉ dùng chỉ số và chiều được công bố trong lớp ngữ nghĩa; không cho viết SQL tự do | ADR-14 | Trình dựng chỉ liệt kê measures, dimensions của phiên bản lớp ngữ nghĩa |
| BR-16-03 | Đi xuống phải lần được theo chuỗi điểm, cấu phần, KPI hoặc KR, sản phẩm, minh chứng, kế hoạch, mục tiêu O, căn cứ | PL 4 III.2 | Mỗi đối tượng lưu khoá liên kết ngược; API chuỗi truy vết |
| BR-16-04 | Dữ liệu C2 (minh chứng, nhận xét) không hiển thị từ kho phân tích; khi đi xuống phải lấy từ mission-core có kiểm tra quyền và ghi nhật ký truy cập | ADR-06 | Cube không có cột C2 rõ; API chi tiết của mission-core |
| BR-16-05 | Chỉ tiêu KT-XH chỉ tính khi số liệu đã được công bố chính thức | QĐ khoản 10.2.2 | Cờ `da_cong_bo` trên dữ liệu chỉ tiêu nhận từ integration-gateway |
| BR-16-06 | Tóm tắt do AI tạo phải gắn nhãn "Nội dung do AI tạo", nêu nguồn dữ liệu và thời điểm; AI không có quyền ghi | Nguyên tắc AI không quyết định | Xem QT-17 |
| BR-16-07 | Hành động chỉ đạo (nhắc, giao lại) thực hiện bằng API của MOD-02 với quyền của người bấm, không phải quyền của bảng điều hành | PL 4 V.1 | Gọi API nghiệp vụ, OPA kiểm tra |
| BR-16-08 | Dữ liệu kho phân tích có độ trễ tối đa 5 phút và hiển thị thời điểm cập nhật | NFR-03 | CDC liên tục; nhãn thời điểm trên màn hình |

### Ngoại lệ và xử lý

Khi kho phân tích chậm hơn ngưỡng (CDC trễ quá 5 phút), bảng điều hành vẫn hiển thị dữ liệu gần nhất kèm cảnh báo "Số liệu cập nhật lúc {thời điểm}". Không có cơ chế dự phòng truy vấn thẳng vào mission-core, vì như vậy sẽ chuyển tải phân tích sang đường ghi.

Khi người dùng đi xuống tới một đối tượng ngoài phạm vi quyền (ví dụ minh chứng của đơn vị khác được liên kết ngang), mắt xích đó hiển thị dạng che với thông báo không đủ quyền; chuỗi vẫn tiếp tục ở các mắt xích được phép.

Khi ai-gateway không sẵn sàng, ô tóm tắt tuần hiển thị "Chưa có tóm tắt tuần này"; các phần khác không bị ảnh hưởng.

### Đặc tả màn hình

#### SCR-1601. Bảng điều hành lãnh đạo

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho lãnh đạo nắm tình hình thực hiện nhiệm vụ của phạm vi quản lý trong một màn hình và chỉ đạo ngay |
| Tác nhân | AC-04 (`leader`); AC-05 xem phạm vi của mình |
| Đường vào | Trang chủ của vai trò lãnh đạo; menu Bảng điều hành |
| Quyền | Theo cây tổ chức |
| Thiết bị | Web desktop, máy tính bảng; trên di động hiển thị 4 con số và danh sách cần chỉ đạo |

![Hình 12.8. SCR-1601 Bảng điều hành Đảng uỷ khối quý IV/2026](SCR-1601.png){width=16cm}

Màn hình theo tinh thần slide 18 của báo cáo BGĐ. Dòng phụ ghi "Tuần 6/13, số liệu từ kho phân tích, trễ tối đa 5 phút, phạm vi theo cây tổ chức của người xem".

Vùng 1 là tiêu đề cùng ba nút "So với quý trước", "Xuất báo cáo" và "+ Giao việc". Vùng 2 là bốn con số: tiến độ mục tiêu O 72%, nhiệm vụ đúng hạn 86% (tăng 2% so với tuần trước), việc chậm cần chỉ đạo 14 (tăng 3), hồ sơ chờ thẩm định 9 (hạn 10/01); bên dưới là bảng tiến độ theo đơn vị cùng khối (Ban Tuyên giáo 92%, Ban Kiểm tra 88%, Đảng uỷ cơ sở C 71%, Đảng uỷ cơ sở D 68%, Văn phòng E 41%, Ban Tổ chức 85%) với nhãn trạng thái và đường xu hướng 6 tuần so với quý trước. Vùng 3 là thẻ "Tóm tắt tuần" có nhãn tím "Nội dung do AI tạo", ba ý nổi bật và dòng nguồn "kho phân tích ngày 14/11, ai-gateway, mô hình Viettel LLM" với liên kết "Xem dữ liệu đã dùng". Vùng 4 là thẻ "Cần chỉ đạo ngay" với ba việc, mỗi việc có nhãn trễ hoặc còn hạn và hai nút "Nhắc", "Giao lại".

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kỳ | Danh sách chọn | Không | Quý, năm | Kỳ hiện hành | Không có | `config.period` |
| 2 | Tiến độ mục tiêu O | Số | Không áp dụng | Phần trăm | Tính | Chỉ đọc | Cube measure `okr.tien_do_o` (từ `okr.key_result`, `okr.kr_checkin`) |
| 3 | Nhiệm vụ đúng hạn | Số | Không áp dụng | Phần trăm và chênh lệch | Tính | Chỉ đọc | Cube measure `task.ty_le_dung_han` (từ `task.task`) |
| 4 | Việc chậm cần chỉ đạo | Số | Không áp dụng | Số nguyên | Tính | Chỉ đọc | Cube measure `task.so_viec_cham` |
| 5 | Hồ sơ chờ thẩm định | Số | Không áp dụng | Số nguyên, hạn | Tính | Chỉ đọc | Cube measure `appraisal.cho_tham_dinh` |
| 6 | Tiến độ theo đơn vị | Bảng, thanh | Không áp dụng | Đơn vị, phần trăm, trạng thái | Đơn vị cùng khối | Ngưỡng màu theo tham số tenant | Cube, chiều `org_unit` |
| 7 | Xu hướng | Biểu đồ đường | Không áp dụng | 6 tuần | Kỳ này và kỳ trước | Chỉ đọc | Cube, chiều thời gian tuần |
| 8 | Tóm tắt tuần | Danh sách văn bản | Không áp dụng | 3 đến 5 ý | Bản mới nhất | Nhãn AI và nguồn bắt buộc | `ai.suggestion_log` (kind = TOM_TAT_TUAN) |
| 9 | Việc cần chỉ đạo | Danh sách | Không áp dụng | Tên, loại, đơn vị, hạn | Sắp theo mức trễ | Chỉ việc trong phạm vi | Cube kết hợp mã `task.task` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tải bảng | Mở màn hình | Gọi các truy vấn Cube song song | `POST /api/v1/analytics/query` | Không có |
| So với quý trước | Luôn | Thêm chiều so sánh kỳ | `POST /api/v1/analytics/query` | Không có |
| Bấm đơn vị | Luôn | Mở SCR-1603 với ngữ cảnh đơn vị | Không có (điều hướng) | Không có |
| Nhắc | Việc chậm trong phạm vi | Gửi nhắc tới người chủ trì | `POST /api/v1/tasks/{id}/reminders` | EVT-21 |
| Giao lại | Việc trong phạm vi, người bấm có quyền giao | Mở hộp thoại chọn người, ghi lý do | `POST /api/v1/tasks/{id}/reassign` | EVT-04 |
| Xem dữ liệu đã dùng | Có tóm tắt | Hiển thị các truy vấn và số liệu AI đã dùng | `GET /api/v1/ai/suggestions/{id}/sources` | Không có |
| Xuất báo cáo | Luôn | Sinh báo cáo theo mẫu | `POST /api/v1/analytics/reports` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-16-01 | Số liệu cập nhật lúc {thoi_diem}, chậm hơn thường lệ. | Cảnh báo | CDC trễ quá 5 phút |
| MSG-16-02 | Đã gửi nhắc tới đồng chí {ten} về việc "{viec}". | Thành công | Nhắc xong |
| MSG-16-03 | Đã giao lại việc "{viec}" cho {ten}. Người được giao cũ đã nhận thông báo. | Thành công | Giao lại xong |

#### SCR-1602. Trình dựng dashboard

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho lãnh đạo, quản lý tự dựng dashboard bằng kéo thả chỉ số và chiều đã chuẩn hoá trong lớp ngữ nghĩa, không cần đội kỹ thuật |
| Tác nhân | AC-04, AC-05 |
| Đường vào | Menu Bảng điều hành › Dashboard của tôi › Tạo mới |
| Quyền | Mọi `leader`, `manager`; dữ liệu theo phạm vi của người xem |
| Thiết bị | Web desktop |

![Hình 12.9. SCR-1602 Trình dựng dashboard kéo thả trên lớp ngữ nghĩa](SCR-1602.png){width=16cm}

Màn hình ba cột: danh mục chỉ số và chiều bên trái, lưới dựng ở giữa, thuộc tính widget bên phải. Tiêu đề "Bảng của Bí thư, bản nháp 3".

Vùng 1 là danh mục chỉ số (tiến độ mục tiêu O, tỷ lệ đúng hạn, điểm KPI trung bình, phân bố mức xếp loại, số minh chứng chờ nghiệm thu, số ngày chậm bình quân, mức tự tin KR, số cảnh báo bất thường) và chiều (đơn vị theo cây tổ chức, kỳ, khối đối tượng PL 2A, loại nhiệm vụ, luồng Đảng hoặc Nhà nước), kèm ghi chú chỉ hiện chỉ số của lớp ngữ nghĩa phiên bản 2026.4, không viết SQL tự do. Vùng 2 là lưới với hai thẻ số và biểu đồ "Điểm KPI trung bình theo quý" đang được chọn. Vùng 3 là ô "Thả chỉ số vào đây" và thẻ phân bố mức. Vùng 4 là thuộc tính widget: chỉ số, chiều, kiểu biểu đồ, bộ lọc (luồng Đảng, khối cơ quan tham mưu), cho phép đi xuống tới minh chứng, và ghi chú truy vấn sinh ra là Cube REST `/load` với securityContext gồm tenant và org_path, cache 5 phút.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Tên dashboard | Văn bản | Có | ≤ 100 ký tự | "Dashboard mới" | Không trùng trong không gian của người dùng | `config.dashboard_def.name` |
| 2 | Danh mục chỉ số, chiều | Danh sách kéo | Không áp dụng | Theo lớp ngữ nghĩa | Phiên bản hiện hành | Chỉ đọc | Cube meta API |
| 3 | Widget | Phần tử lưới | Có (ít nhất 1) | Vị trí, kích thước | Trống | Tối đa 20 widget | `config.dashboard_def.layout_json` |
| 4 | Chỉ số của widget | Danh sách chọn | Có | Measure | Trống | Có trong lớp ngữ nghĩa | `config.dashboard_def.layout_json` |
| 5 | Chiều | Danh sách chọn | Không | Dimension | Trống | Tối đa 2 chiều | `config.dashboard_def.layout_json` |
| 6 | Kiểu biểu đồ | Danh sách chọn | Có | Thẻ số, cột, đường, thanh, bảng | Theo chỉ số | Phù hợp kiểu dữ liệu | `config.dashboard_def.layout_json` |
| 7 | Bộ lọc | Biểu thức đơn giản | Không | Chiều = giá trị | Trống | Chỉ trên chiều công bố | `config.dashboard_def.layout_json` |
| 8 | Cho phép đi xuống | Danh sách chọn | Không | Có, không | Có | Chỉ chỉ số có liên kết truy vết | `config.dashboard_def.layout_json` |
| 9 | Chia sẻ | Chọn nhiều | Không | Nhóm người dùng | Không chia sẻ | Chỉ nhóm trong tenant | `config.dashboard_def.shared_with` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Kéo chỉ số vào lưới | Luôn | Tạo widget, gọi truy vấn xem trước | `POST /api/v1/analytics/query` | Không có |
| Xem trước | Luôn | Hiển thị dashboard ở chế độ người xem | Phía client | Không có |
| Lưu dashboard | Có ít nhất 1 widget | Lưu định nghĩa | `PUT /api/v1/analytics/dashboards/{id}` | Không có |
| Chia sẻ cho Ban Thường vụ | Đã lưu | Lưu danh sách chia sẻ | `POST /api/v1/analytics/dashboards/{id}/shares` | EVT-21 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-16-04 | Chỉ số {chi_so} không hỗ trợ chiều {chieu}. | Lỗi kiểm tra | Kết hợp không hợp lệ |
| MSG-16-05 | Người được chia sẻ chỉ thấy số liệu trong phạm vi quản lý của chính họ. | Thông tin | Khi chia sẻ |

#### SCR-1603. Đi xuống tới minh chứng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Hiển thị chuỗi truy vết từ điểm tới minh chứng và ngược lên mục tiêu O, căn cứ của một kết quả cụ thể |
| Tác nhân | AC-04, AC-05, AC-07, AC-10 |
| Đường vào | Bấm đơn vị hoặc con số ở SCR-1601, SCR-1602; liên kết từ SCR-1401 |
| Quyền | Theo cây tổ chức; mỗi mắt xích kiểm tra quyền riêng |
| Thiết bị | Web desktop |

![Hình 12.10. SCR-1603 Đi xuống từ điểm 91,2 tới minh chứng phiên bản 3](SCR-1603.png){width=16cm}

Vùng 1 là tiêu đề và dòng mô tả chuỗi truy vết PL 4. Vùng 2 là dải tám thẻ nối nhau: điểm 91,2 của Trần Minh Hoàng quý IV; cấu phần 63,6 (then chốt × 70); KPI nhiệm vụ 0,783 (nhiệm vụ then chốt 2); sản phẩm 1/1 (báo cáo sơ kết); minh chứng v3 `DA_XAC_NHAN` (đang chọn); kế hoạch KH-Q4 v2 `DA_DUYET`; mục tiêu O1, KR 1.2 của Phòng Tổng hợp; căn cứ Nghị quyết Đại hội Đảng bộ khối. Bên dưới bên trái là bảng KPI của nhiệm vụ then chốt 2 với A = 1,00, B = 0,50 (nghiệm thu 18/12 "phải sửa nội dung cơ bản"), C = 0,85 (hạn 04/12, hoàn thành 09/12, chậm 3 ngày làm việc do chủ quan), KPI 0,783 theo Policy Pack Đảng v2.0, và đoạn "Ngược lên mục tiêu". Vùng 3 là thẻ minh chứng phiên bản 3: tệp BC-so-ket-TCCS-v3.pdf 1,8 MB, SHA-256, lưu Ceph RGW với Object Lock đến 2037, quét mã độc sạch, OCR trích số 112-BC/BTC, người nộp và người xác nhận khác nhau, cùng bảng ba phiên bản (v1, v2 yêu cầu bổ sung, v3 xác nhận) và ghi chú "mọi phiên bản giữ nguyên, không ghi đè". Vùng 4 là nút "Xuất chuỗi bằng chứng".

Ví dụ này cũng kiểm chứng công thức: C = 1 − 0,05 × 3 = 0,85 vì từ 04/12/2026 (thứ Sáu) tới 09/12/2026 (thứ Tư) có 3 ngày làm việc (07, 08, 09/12); KPI = (1 + 0,5 + 0,85) / 3 = 0,783.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mắt xích điểm, cấu phần | Thẻ | Không áp dụng | Số | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot` |
| 2 | Mắt xích KPI | Thẻ và bảng A, B, C | Không áp dụng | 2 hoặc 3 chữ số | Từ vết tính | Chỉ đọc | `scoring.score_trace` |
| 3 | Mắt xích sản phẩm | Thẻ | Không áp dụng | Số lượng duyệt / kế hoạch | Từ dữ liệu | Chỉ đọc | `product.deliverable` |
| 4 | Mắt xích minh chứng | Thẻ và bảng phiên bản | Không áp dụng | Tệp, băm, trạng thái | Phiên bản được tính | Kiểm tra quyền C2; ghi nhật ký | `product.evidence_version`, `evidence.object_meta`, `evidence.scan_result` |
| 5 | Mắt xích kế hoạch | Thẻ | Không áp dụng | Mã, phiên bản, trạng thái | Từ dữ liệu | Chỉ đọc | `task.plan_version`, `task.plan_item` |
| 6 | Mắt xích mục tiêu, KR | Thẻ | Không áp dụng | Mã, tên | Từ dữ liệu | Chỉ đọc | `okr.objective`, `okr.key_result`, `okr.okr_link` |
| 7 | Mắt xích căn cứ | Thẻ | Không áp dụng | Tên văn bản | Từ dữ liệu | Chỉ đọc | `okr.basis` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn mắt xích | Luôn | Tải chi tiết mắt xích | `GET /api/v1/analytics/trace/{objectType}/{id}` | Không có |
| Mở tệp minh chứng | Có quyền C2 | Mở trình xem, ghi nhật ký truy cập | `GET /api/v1/evidences/{id}/versions/{v}/content` | Không có |
| Xuất chuỗi bằng chứng | Luôn | Xuất JSON và PDF chuỗi truy vết kèm mã băm, mã bản ghi sổ cái | `POST /api/v1/analytics/trace/exports` (202) | Không có |
| Quay lại bảng điều hành | Luôn | Điều hướng | Không có | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-16-06 | Đồng chí không có quyền xem chi tiết {doi_tuong} thuộc {don_vi}. | Lỗi phân quyền | Mắt xích ngoài phạm vi |
| MSG-16-07 | Minh chứng này đã có {n} phiên bản. Phiên bản được tính điểm là v{v}. | Thông tin | Mở minh chứng |

### Liên kết thiết kế

QT-16 dùng SVC-15 analytics (ClickHouse `analytics_<cell>`, Cube, Superset nhúng, analytics-api), SVC-14 ai-gateway (tóm tắt tuần), MOD-02 `task` (nhắc, giao lại), các module MOD-01 đến MOD-04 và SVC-08 cho chuỗi đi xuống, bảng đề xuất mới `config.dashboard_def`. Sự kiện: EVT-04, EVT-21 (phát), và toàn bộ sự kiện nghiệp vụ là nguồn CDC. Liên quan ADR-14 (CQRS phía đọc), ADR-06 (không đưa C2 rõ sang kho phân tích), NFR-03. Đáp ứng tiêu chí H7 (dashboard tự dựng trên lớp ngữ nghĩa, đi xuống tới minh chứng, so sánh nhiều kỳ) và yêu cầu truy vết hai chiều PL 4 III.2.

## QT-17. Trợ lý AI

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-17 |
| Tên | Trợ lý AI |
| Mục đích | Hỗ trợ người dùng soạn nháp mục tiêu O và KR từ văn bản căn cứ, soát KR mơ hồ hoặc trùng với nhiệm vụ, tóm tắt tình hình; mọi nội dung AI tạo được gắn nhãn và nguồn, AI không có quyền ghi |
| Phạm vi áp dụng | Soạn OKR (QT-02), soát kế hoạch (QT-04), tóm tắt tuần (QT-16), hỏi đáp văn bản hướng dẫn; không áp dụng cho chấm điểm, thẩm định, quyết định |
| Căn cứ | PL 1 mục 3.2a (KR phải đo lường, kiểm chứng được, không đồng nhất với nhiệm vụ); HD mục 1c; báo cáo BGĐ slide 30 và nguyên tắc "AI không quyết định"; PL A2 nhóm 8 (kênh trợ lý AI tiếng Việt) |
| Tác nhân | AC-06, AC-05 người dùng; AC-12 trợ lý AI (`ai_readonly`); SVC-14 ai-gateway; MOD-01 okr |
| Tần suất và khối lượng ước tính | Dồn vào hai tuần đầu quý khi lập OKR và kế hoạch. Giả định 20% người dùng dùng trợ lý, mỗi người 5 lượt mỗi quý, tức khoảng 6 triệu lượt mỗi quý; đỉnh ước tính khoảng 50 đến 100 lượt suy luận mỗi giây toàn quốc (số ước tính, phụ thuộc mức chấp nhận thực tế) |
| Điều kiện bắt đầu | Người dùng mở trợ lý ở màn hình có hỗ trợ (SCR-0202, SCR-1701) và có văn bản căn cứ trong phạm vi quyền đọc |
| Điều kiện kết thúc | Người dùng chèn gợi ý vào nháp (rồi tự trình duyệt qua QT-02) hoặc bỏ qua; mọi lượt được ghi `ai.suggestion_log` |
| Đầu vào | Câu hỏi tiếng Việt, văn bản căn cứ (đã chia đoạn trong `ai.doc_chunk`), dữ liệu tổ chức trong phạm vi quyền |
| Đầu ra | Gợi ý có nhãn "Nội dung do AI tạo" và nguồn; bản nháp O/KR do người dùng chèn (cờ `ai_assisted`); `ai.suggestion_log`, `ai.guardrail_event` |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ gợi ý được chấp nhận; tỷ lệ KR được trợ lý cảnh báo mơ hồ mà người dùng sửa; thời gian phản hồi p95 (mục tiêu ≤ 8 giây cho gợi ý, đo khi triển khai); số sự kiện rào chắn |

Nguyên tắc nền tảng của QT-17 là AI hỗ trợ soạn thảo chứ không ra quyết định và không ghi dữ liệu. Trợ lý chạy với vai trò `ai_readonly` (AC-12): ai-gateway (SVC-14) chỉ có quyền đọc các văn bản và dữ liệu mà chính người dùng được đọc, theo cơ chế uỷ quyền token của người dùng; ai-gateway không có tài khoản ghi vào bất kỳ schema nghiệp vụ nào. Khi người dùng bấm "Dùng gợi ý", chính giao diện của người dùng gọi API của MOD-01 để lưu vào bản nháp với quyền của người dùng, và cờ `ai_assisted` được đặt để sau này biết nội dung có nguồn gốc AI. Việc trình phê duyệt hoàn toàn do người dùng thực hiện.

Mô hình ngôn ngữ chạy tại chỗ (vLLM trong vùng bảo mật có GPU), dữ liệu không ra khỏi hạ tầng của tenant. Dữ liệu C3 không bao giờ được đưa vào trợ lý (ADR-06). Rào chắn đầu vào lọc các yêu cầu ngoài phạm vi (ví dụ "chấm điểm giúp tôi", "đề xuất xếp loại cho đồng chí X"), rào chắn đầu ra kiểm tra gợi ý có trích nguồn, không chứa dữ liệu ngoài phạm vi quyền và không chứa kết luận đánh giá.

### Lưu đồ quy trình

![Hình 12.11. Lưu đồ làn bơi QT-17: trợ lý AI chỉ đọc soạn nháp OKR và soát KR](nv-b-qt17-luong.png){width=16cm}

Người dùng chọn văn bản căn cứ và hỏi bằng tiếng Việt. ai-gateway kiểm tra quyền đọc và rào chắn đầu vào, truy xuất các đoạn liên quan từ `ai.doc_chunk` (pgvector) cùng PL 1 của dự thảo, gọi mô hình ngôn ngữ để soạn O và KR hoặc soát KR, áp rào chắn đầu ra, gắn nhãn "Nội dung do AI tạo" và nguồn, rồi ghi `ai.suggestion_log`. Người dùng xem gợi ý: dùng thì chèn vào bản nháp (lưu ở MOD-01 với cờ `ai_assisted`), bỏ qua thì việc từ chối cũng được ghi nhận. Bước trình phê duyệt nằm ở QT-02 và do người dùng tự làm.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở trợ lý, chọn căn cứ | AC-06 | Mở ngăn trợ lý; đính kèm hoặc chọn văn bản căn cứ đã có trong hệ thống (từ EVT-24 hoặc tải lên) | SCR-1701 | Không có | Không giới hạn |
| 2 | Kiểm tra quyền, rào chắn đầu vào | SVC-14 | Xác minh token người dùng, quyền đọc văn bản; lọc yêu cầu ngoài phạm vi | Không có | `ai.guardrail_event` nếu chặn | ≤ 200 ms |
| 3 | Truy xuất ngữ cảnh | SVC-14 | Tìm đoạn liên quan trong `ai.doc_chunk` theo véc-tơ, cộng PL 1 và dữ liệu tổ chức trong phạm vi (ví dụ danh sách 18 chi bộ) | Không có | Danh sách nguồn | ≤ 500 ms |
| 4 | Sinh gợi ý | SVC-14 | Mô hình soạn O và KR có chỉ tiêu đo được; soát KR mơ hồ; phát hiện KR trùng với KR đơn vị khác | Không có | Bản gợi ý | Vài giây |
| 5 | Rào chắn đầu ra, gắn nhãn | SVC-14 | Kiểm tra trích nguồn, phạm vi dữ liệu, không có kết luận đánh giá; gắn nhãn và nguồn | SCR-1701 | `ai.suggestion_log` | ≤ 200 ms |
| 6 | Xem và quyết định | AC-06 | Đọc gợi ý, lý do, nguồn; chọn "Dùng gợi ý" hoặc "Bỏ qua" | SCR-1701 | Cập nhật `ai.suggestion_log.accepted_by` hoặc lý do bỏ qua | Không giới hạn |
| 7 | Chèn vào nháp | AC-06, MOD-01 | Giao diện gọi API okr với quyền người dùng; người dùng có thể sửa tiếp | SCR-1701 | `okr.objective`, `okr.key_result` (NHAP, `ai_assisted = true`) | Tức thời |
| 8 | Trình phê duyệt | AC-06 | Người dùng tự bấm "Trình phê duyệt"; chuyển sang QT-02 | SCR-1701, SCR-0203 | Mục tiêu `CHO_DUYET` | Theo QT-02 |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-17-01 | Trợ lý AI không có quyền ghi dữ liệu nghiệp vụ; mọi nội dung chỉ vào hệ thống khi người dùng chủ động chèn | Nguyên tắc AI không quyết định; AC-12 | ai-gateway không có tài khoản CSDL ghi vào schema nghiệp vụ; Keycloak client `ai_readonly` chỉ có scope đọc |
| BR-17-02 | Mọi nội dung do AI tạo phải gắn nhãn "Nội dung do AI tạo" và nêu nguồn (văn bản, trang, dữ liệu đã dùng) | Minh bạch AI | Rào chắn đầu ra từ chối gợi ý không có nguồn; giao diện hiển thị nhãn bắt buộc |
| BR-17-03 | Trợ lý chỉ đọc dữ liệu mà người dùng đang có quyền đọc | PL 4 V.1; ADR-06 | Uỷ quyền token người dùng (token exchange); lọc `doc_chunk` theo ACL |
| BR-17-04 | Trợ lý không chấm điểm, không đề xuất mức xếp loại, không nhận xét cán bộ | Nguyên tắc AI không quyết định | Rào chắn đầu vào và đầu ra; ghi `guardrail_event` |
| BR-17-05 | KR phải đo lường, kiểm chứng được và không đồng nhất với nhiệm vụ; trợ lý cảnh báo KR mơ hồ và đề xuất cách viết lại | PL 1 mục 3.2a | Luật soát KR (từ mơ hồ như "tăng cường", "đẩy mạnh" không có chỉ tiêu) kết hợp mô hình |
| BR-17-06 | Mỗi kỳ khuyến nghị 1 đến 3 mục tiêu O; trợ lý không đề xuất quá 3 | PL 1 | Tham số trong prompt và kiểm tra đầu ra |
| BR-17-07 | Dữ liệu C3 không đưa vào trợ lý; dữ liệu C2 chỉ dùng khi người dùng có quyền và không lưu trong nhật ký mô hình | ADR-06 | `doc_chunk` không chứa C3; nhật ký suy luận không lưu nội dung C2 |
| BR-17-08 | Nội dung được chèn từ gợi ý mang cờ `ai_assisted`, hiển thị cho người duyệt OKR | Minh bạch AI | Cột cờ trên `okr.objective`, `okr.key_result`; nhãn trên SCR-0203 |
| BR-17-09 | Mọi lượt dùng trợ lý được ghi nhật ký: câu hỏi (đã che C2), nguồn, gợi ý, quyết định của người dùng | Truy vết | `ai.suggestion_log` |

### Ngoại lệ và xử lý

Khi hàng đợi suy luận đầy (đầu quý), ai-gateway trả 202 kèm mã theo dõi và giao diện hiển thị "Trợ lý đang bận, gợi ý sẽ hiện khi xong"; người dùng vẫn soạn OKR bình thường. ai-gateway co giãn theo độ dài hàng đợi suy luận.

Khi rào chắn chặn yêu cầu ngoài phạm vi, trợ lý trả lời lịch sự rằng việc chấm điểm, xếp loại thuộc thẩm quyền của con người theo quy định, và gợi ý màn hình phù hợp.

Khi văn bản căn cứ chưa được lập chỉ mục (vừa tải lên), ai-gateway lập chỉ mục trước rồi mới trả lời; thời gian chờ có thể tăng vài giây.

Khi gợi ý trích nguồn không tồn tại (mô hình "bịa" nguồn), rào chắn đầu ra đối chiếu với danh sách nguồn đã truy xuất và loại bỏ gợi ý đó.

### Đặc tả màn hình

#### SCR-1701. Trợ lý AI soạn OKR và soát KR

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người dùng soạn bản nháp OKR có hỗ trợ của trợ lý AI chỉ đọc, xem cảnh báo KR mơ hồ, chèn gợi ý và tự trình phê duyệt |
| Tác nhân | AC-06, AC-05; AC-12 trợ lý |
| Đường vào | Menu Mục tiêu › Tạo OKR; nút "Trợ lý AI" trên SCR-0202 |
| Quyền | Người soạn OKR của đơn vị |
| Thiết bị | Web desktop; trên di động trợ lý mở dạng ngăn kéo toàn màn hình |

![Hình 12.12. SCR-1701 Trợ lý AI soạn OKR quý I/2027 và soát KR mơ hồ](SCR-1701.png){width=16cm}

Màn hình theo tinh thần slide 30 của báo cáo BGĐ: bản nháp OKR bên trái, trợ lý bên phải với nền tím nhạt và nhãn "Chỉ đọc · không ghi".

Vùng 4 là bản nháp OKR quý I/2027 của Ban Tổ chức (bản nháp 4, trạng thái `NHAP`), có nhắc khuyến nghị 1 đến 3 mục tiêu. Mục tiêu O1 "Nâng chất lượng sinh hoạt chi bộ khối cơ quan" mang nhãn "chèn từ gợi ý AI · đã chấp nhận" và căn cứ Kế hoạch công tác năm 2027, mục II.2; KR 1.3 "Tăng cường công tác kiểm tra, giám sát" được tô vàng với nhãn đỏ "Chưa đo được". Cuối cột là nút "Trình phê duyệt" kèm câu "Chỉ người dùng mới có nút Trình phê duyệt. Trợ lý AI không có quyền ghi vào O/KR". Vùng 1 là yêu cầu của người dùng kèm tệp KH-25-KH/ĐUK.pdf và trả lời đầu tiên của trợ lý (đề xuất 2 O và 5 KR bám mục II.2 và III.1) với nhãn "Nội dung do AI tạo" và nguồn "KH-25-KH/ĐUK tr. 4–6; PL 1 dự thảo". Vùng 2 là thẻ soát KR 1.3: giải thích cụm "tăng cường" không đo được và giống mô tả nhiệm vụ, gợi ý viết lại "Kiểm tra 100% chi bộ trực thuộc trước 31/3/2027", lý do theo PL 1 mục 3.2a, dữ liệu đã dùng là danh sách 18 chi bộ, cùng hai nút "Bỏ qua" và "Dùng gợi ý". Vùng 3 là lưu ý KR 2.1 trùng chỉ tiêu với KR của Văn phòng Đảng uỷ khối, đề nghị liên kết ngang, và ô hỏi trợ lý ở cuối.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mục tiêu O | Văn bản | Có | ≤ 300 ký tự | Trống | 1 đến 3 mục tiêu mỗi kỳ (cảnh báo khi > 3) | `okr.objective.title` |
| 2 | Căn cứ của O | Liên kết | Có | Văn bản căn cứ, mục | Trống | Phải có căn cứ cấp trên | `okr.objective.basis_id`, `okr.basis` |
| 3 | KR | Văn bản | Có | ≤ 300 ký tự | Trống | Có chỉ tiêu đo được; cảnh báo khi mơ hồ | `okr.key_result.title` |
| 4 | Chỉ tiêu, trọng số KR | Số | Có | Chỉ tiêu theo đơn vị; trọng số % | Trống | Tổng trọng số của O bằng 100% | `okr.key_result.target_value`, `okr.key_result.weight` |
| 5 | Cờ AI hỗ trợ | Nhãn | Không áp dụng | Boolean | false | Đặt khi chèn từ gợi ý | `okr.objective.ai_assisted`, `okr.key_result.ai_assisted` |
| 6 | Câu hỏi cho trợ lý | Văn bản | Không | ≤ 2.000 ký tự | Trống | Rào chắn đầu vào | `ai.suggestion_log.prompt_redacted` |
| 7 | Tệp căn cứ đính kèm | Tệp | Không | PDF, DOCX ≤ 20 MB | Trống | Quyền đọc; lập chỉ mục | `ai.doc_chunk` (qua `evidence.object_meta`) |
| 8 | Gợi ý | Thẻ | Không áp dụng | Văn bản, nguồn, lý do | Từ trợ lý | Nhãn AI bắt buộc | `ai.suggestion_log.output`, `ai.suggestion_log.sources_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Gửi câu hỏi | Ô hỏi có nội dung | Gọi trợ lý; hiển thị kết quả hoặc mã theo dõi | `POST /api/v1/ai/assist/okr` | Không có |
| Dùng gợi ý | Có gợi ý chưa xử lý | Chèn vào nháp bằng API okr với quyền người dùng; ghi chấp nhận | `PUT /api/v1/okr/objectives/{id}` hoặc `POST /api/v1/okr/key-results`; `POST /api/v1/ai/suggestions/{id}/accept` | Không có |
| Bỏ qua | Có gợi ý chưa xử lý | Ghi từ chối, tuỳ chọn lý do | `POST /api/v1/ai/suggestions/{id}/dismiss` | Không có |
| Lưu nháp | Luôn | Lưu bản nháp OKR | `PUT /api/v1/okr/objectives/{id}` | Không có |
| Trình phê duyệt | Người soạn, đủ trường bắt buộc | Chuyển sang luồng QT-02 | `POST /api/v1/okr/objectives/{id}/submit` | EVT-21 (sau đó EVT-01 khi duyệt) |
| Lịch sử nháp | Luôn | Hiển thị các bản nháp và nguồn gốc nội dung | `GET /api/v1/okr/objectives/{id}/history` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-17-01 | KR "{kr}" chưa đo được. Gợi ý: {goi_y}. | Cảnh báo | Soát KR |
| MSG-17-02 | Trợ lý không thực hiện chấm điểm hoặc đề xuất xếp loại. Việc này thuộc thẩm quyền của cán bộ và cấp có thẩm quyền theo quy định. | Thông tin | Rào chắn chặn |
| MSG-17-03 | Trợ lý đang bận. Gợi ý sẽ hiển thị khi xong, đồng chí có thể tiếp tục soạn. | Thông tin | Hàng đợi đầy (202) |
| MSG-17-04 | Đã chèn gợi ý vào bản nháp. Nội dung được đánh dấu "AI hỗ trợ" để người duyệt biết. | Thành công | Dùng gợi ý |

### Liên kết thiết kế

QT-17 dùng SVC-14 ai-gateway (schema `ai`: `doc_chunk`, `suggestion_log`, `guardrail_event`; vLLM tại chỗ; pgvector), MOD-01 `okr` (`objective`, `key_result`, `basis`, `okr_link`), SVC-02 iam (client `ai_readonly`, token exchange), SVC-13 integration-gateway (EVT-24 nhận văn bản chỉ đạo để lập chỉ mục). Sự kiện: EVT-24 (đầu vào), EVT-21. Liên quan ADR-06 (không đưa C3 vào AI). Đáp ứng PL A1 nhóm 1 "AI kiểm tra KR có trùng với nhiệm vụ không", PL A2 nhóm 8 "trợ lý AI tiếng Việt" và tiêu chí H1.

## QT-18. Cấu hình giao diện tenant (UI Pack)

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-18 |
| Tên | Cấu hình giao diện tenant (UI Pack) |
| Mục đích | Cho quản trị tenant cấu hình giao diện riêng (token màu, logo, chữ, bo góc), bố cục trang chủ và widget từ danh mục component cho phép, biểu mẫu động, chế độ Đảng viên cao tuổi; xem trước; phát hành trong 1 ngày không cần phát hành lại mã; quay lui phiên bản |
| Phạm vi áp dụng | Mọi tenant; bề mặt cấu hình được theo kết luận G1; plugin chuyên ngành theo manifest |
| Căn cứ | Góp ý G1 và baseline mục 14 G1; ADR-05 (bốn lớp); NFR-07 (UI Pack ≤ 1 ngày), NFR-11 (WCAG 2.1 AA, chế độ cao tuổi chữ ≥ 17 px, ≤ 3 chạm) |
| Tác nhân | AC-02 quản trị tenant (soạn); người duyệt (AC-02 thứ hai hoặc AC-03 khi biểu mẫu gắn Policy Pack); SVC-17 web-frontend; SVC-18 mobile-app; MOD-08 config |
| Tần suất và khối lượng ước tính | Khi tenant mới vào vận hành và vài lần mỗi năm; tối đa 5.000 tenant nhưng mỗi lần phát hành chỉ vô hiệu bộ đệm CDN của một tenant; tải UI Pack 11,2 KB gzip với tenant mẫu (số đo trên bản dựng thử), ngân sách ≤ 30 KB |
| Điều kiện bắt đầu | Tenant đã khởi tạo (QT-01); quản trị tenant mở cấu hình giao diện |
| Điều kiện kết thúc | Phiên bản UI Pack `DA_PHAT_HANH`, EVT-23 đã phát, bộ đệm CDN đã vô hiệu; người dùng nhận giao diện mới ở lần tải trang kế tiếp |
| Đầu vào | Bộ token mặc định của `@vt/design-system`, danh mục component cho phép (registry có phiên bản), Policy Pack đang gắn (với biểu mẫu) |
| Đầu ra | `config.ui_pack_version` mới, `config.form_schema` mới, CSS custom properties và preset PrimeNG biên dịch sẵn trên CDN; EVT-23 |
| Chỉ số đo hiệu quả quy trình | Thời gian từ yêu cầu tới phát hành (≤ 1 ngày); tỷ lệ phiên bản phải quay lui; kích thước UI Pack (≤ 30 KB); thời gian dựng biểu mẫu 50 trường (≤ 50 ms); điểm kiểm tra khả năng truy cập AA |

Góp ý G1 của lãnh đạo hỏi liệu kiến trúc SDUI cộng Micro-frontends có áp dụng được để mỗi tenant có giao diện riêng. Kết luận đã chốt ở baseline mục 14 là áp dụng được nhưng có chọn lọc theo lớp, và QT-18 là quy trình người dùng của kết luận đó. Lớp 1 (token) áp dụng toàn bộ: tenant đổi màu, logo, chữ, bo góc, khoảng cách trong giới hạn tương phản AA. Lớp 2 (SDUI có giới hạn) chỉ áp dụng cho bề mặt cấu hình được: bố cục trang chủ và widget, nhóm trường và thứ tự thẻ ở trang chi tiết, bố cục dashboard; schema bố cục chỉ được tham chiếu component có trong danh mục cho phép, không chứa HTML hay JavaScript tuỳ ý. Lớp 3 (biểu mẫu động) dùng Formly cộng JSON Schema cộng CEL. Lớp 4 (plugin) nạp module chuyên ngành như `fe-plugin-yte` theo manifest của tenant.

Phần không cấu hình được cũng quan trọng như phần cấu hình được. Các màn hình tương tác nặng và có hệ quả pháp lý (cây OKR, Config Studio, thẩm định so sánh, quyết định hàng loạt, bảng điều hành có đi xuống) được viết mã, kiểm thử tự động, kiểm tra khả năng truy cập một lần cho mọi tenant; chúng chỉ nhận token. Lý do là chất lượng kiểm thử, khả năng truy cập, hiệu năng và an toàn (chống XSS) của các màn hình này không thể phụ thuộc vào cấu hình của từng tenant. Công thức tính điểm cũng không nằm trong UI Pack mà trong Policy Pack (QT-15).

Ví dụ dùng xuyên suốt là hai tenant dùng chung một bản dựng web-frontend: Đảng uỷ các cơ quan Đảng TW với UI Pack v2.3 (màu đỏ #EE0033, bo góc 8 px) và Sở Y tế tỉnh X với UI Pack v1.8 đang soạn (màu #0A7E8C, bo góc 14 px, logo riêng, widget chuyên môn từ plugin Y tế, biểu mẫu phiếu đánh giá viên chức y tế `phieu-vc-yte` v12 gắn Policy Pack `nn-335/so-y-te` v1.3).

### Lưu đồ quy trình

![Hình 12.13. Lưu đồ làn bơi QT-18: cấu hình, kiểm tra và phát hành UI Pack](nv-b-qt18-luong.png){width=16cm}

Quản trị tenant lần lượt chọn token, sắp bố cục trang chủ từ danh mục widget, dựng biểu mẫu động, xem trước song song hai tenant và chế độ cao tuổi, rồi gửi duyệt. Làn "Hệ thống" chạy bộ kiểm tra tự động: schema hợp lệ, mọi component nằm trong registry, tương phản đạt AA, kích thước gói ≤ 30 KB, biểu thức CEL cho cùng kết quả trên cel-js và cel-java. Lỗi thì quay lại sửa; đạt thì chuyển người duyệt. Sau khi duyệt, MOD-08 biên dịch token thành CSS custom properties và preset PrimeNG, lưu `ui_pack_version`, phát EVT-23 để web-frontend vô hiệu bộ đệm CDN. Người dùng nhận giao diện mới trong vòng một ngày mà không cần phát hành lại mã; khi có sự cố, quản trị tenant quay lui về phiên bản trước.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Tạo phiên bản nháp | AC-02 | Nhân bản phiên bản đang dùng hoặc từ bộ mặc định | SCR-1801 | `config.ui_pack_version` `NHAP` | Giờ thứ 1 |
| 2 | Chọn token | AC-02 | Màu thương hiệu, màu chữ trên nền thương hiệu, màu cảnh báo, phông, cỡ chữ cơ sở, bo góc, đơn vị khoảng cách, logo | SCR-1801 | Token JSON (W3C Design Tokens) | Giờ thứ 1 đến 2 |
| 3 | Bố cục trang chủ | AC-02 | Thêm, xoá, sắp xếp widget từ danh mục component cho phép, kể cả widget của plugin được bật | SCR-1801 | Schema bố cục SDUI | Giờ thứ 2 |
| 4 | Dựng biểu mẫu động | AC-02 | Kéo thả trường, đặt khoá dữ liệu, kiểu, quy tắc kiểm tra CEL, điều kiện hiển thị, lớp dữ liệu; chạy bộ test biểu mẫu | SCR-1802 | `config.form_schema` (phiên bản mới) | Giờ thứ 2 đến 4 |
| 5 | Chế độ cao tuổi | AC-02 | Bật bộ token phụ "elder" và cờ "ít bước" ở mức tenant | SCR-1801, SCR-1803 | Cờ trong `ui_pack_version` | Giờ thứ 4 |
| 6 | Xem trước | AC-02 | Xem song song giao diện hai tenant, xem trên khung di động, xem chế độ cao tuổi | SCR-1801, SCR-1803 | Không có | Giờ thứ 4 |
| 7 | Gửi duyệt, kiểm tra tự động | AC-02, MOD-08 | Chạy bộ kiểm tra; lỗi thì về nháp | SCR-1801 | `DANG_KIEM_TRA` rồi `CHO_DUYET` hoặc `NHAP` | Vài phút |
| 8 | Duyệt | Người duyệt | Xem khác biệt, ảnh chụp xem trước; duyệt hoặc từ chối | SCR-1801 (chế độ duyệt) | `DA_PHAT_HANH` hoặc `NHAP` | Trong ngày |
| 9 | Biên dịch, phát hành | MOD-08, SVC-17 | Biên dịch token sang CSS và preset; đẩy lên CDN với ETag theo phiên bản; phát EVT-23 vô hiệu bộ đệm | Không có | EVT-23 | ≤ 15 phút |
| 10 | Quay lui | AC-02 | Chọn phiên bản trước và kích hoạt lại | SCR-1801 thẻ Phiên bản | Phiên bản trước `DA_PHAT_HANH`; EVT-23 | ≤ 15 phút |

Tổng thời gian trong bảng thể hiện cách đạt chỉ tiêu NFR-07 "thay giao diện tenant ≤ 1 ngày": phần soạn mất vài giờ, kiểm tra tự động vài phút, duyệt trong ngày, phát hành dưới 15 phút. Không có bước nào cần đội phát triển hay quy trình phát hành phần mềm.

### Trạng thái đối tượng

![Hình 12.14. Sơ đồ trạng thái phiên bản UI Pack config.ui_pack_version](nv-b-qt18-trangthai.png){width=13cm}

Bộ trạng thái của UI Pack là đề xuất mới (baseline chỉ quy định trạng thái Policy Pack). Khác với Policy Pack, phiên bản UI Pack bị thay thế có thể được kích hoạt lại ngay (quay lui) vì thay đổi giao diện không ảnh hưởng tới kết quả tính toán.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Tạo hoặc nhân bản | `NHAP` | `tenant_admin` |
| `NHAP` | Gửi duyệt | `DANG_KIEM_TRA` | Không có |
| `DANG_KIEM_TRA` | Lỗi kiểm tra tự động | `NHAP` | Danh sách lỗi |
| `DANG_KIEM_TRA` | Đạt | `CHO_DUYET` | Mọi kiểm tra đạt |
| `CHO_DUYET` | Từ chối | `NHAP` | Có ý kiến |
| `CHO_DUYET` | Duyệt | `DA_PHAT_HANH` | Người duyệt khác người soạn; EVT-23 |
| `DA_PHAT_HANH` | Phiên bản mới phát hành | `THAY_THE` | Tự động |
| `THAY_THE` | Quay lui | `DA_PHAT_HANH` | Phiên bản tương thích Policy Pack đang gắn |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-18-01 | Token theo chuẩn W3C Design Tokens, biên dịch thành CSS custom properties và preset PrimeNG; màu chữ trên nền phải đạt tương phản WCAG AA (≥ 4,5 : 1 với chữ thường) | ADR-05; NFR-11 | Kiểm tra tương phản tự động khi chọn màu và khi gửi duyệt |
| BR-18-02 | Bố cục SDUI chỉ được tham chiếu component trong danh mục cho phép có phiên bản; không chứa HTML, JavaScript tuỳ ý | ADR-05; G1 | Kiểm tra schema với `config.component_registry`; renderer `@vt/sdui-renderer` từ chối component lạ |
| BR-18-03 | Màn hình lõi (cây OKR, Config Studio, thẩm định, quyết định hàng loạt, bảng điều hành, sổ cái) chỉ nhận token, không cấu hình bố cục | ADR-05; G1 | Các màn hình này không đọc schema SDUI |
| BR-18-04 | Biểu mẫu động dùng Formly và JSON Schema; quy tắc kiểm tra và điều kiện hiển thị viết bằng CEL; trình duyệt kiểm tra tức thời bằng cel-js, máy chủ kiểm tra lại bằng cel-java và là nguồn sự thật | ADR-05; ADR-19 | `@vt/form-engine`; API nghiệp vụ kiểm tra lại toàn bộ schema |
| BR-18-05 | Bộ test biểu mẫu phải cho cùng kết quả trên cel-js và cel-java | ADR-05 | Chạy khi gửi duyệt; chặn nếu khác |
| BR-18-06 | Phiên bản biểu mẫu tham chiếu phiên bản Policy Pack; trường được tính điểm phải khớp khai báo của Policy Pack | ADR-05 | Cột `config.ui_pack_version.policy_pack_ref`, kiểm tra khoá dữ liệu |
| BR-18-07 | Thay đổi thuần giao diện không phải chạy lại test case tính điểm | ADR-05 | UI Pack tách khỏi Policy Pack |
| BR-18-08 | Kích thước UI Pack ≤ 30 KB gzip; dựng biểu mẫu 50 trường ≤ 50 ms | Baseline G1 | Kiểm tra khi gửi duyệt; số đo từ bộ test trình duyệt không giao diện |
| BR-18-09 | Chế độ Đảng viên cao tuổi: chữ cơ sở ≥ 17 px, nút cao ≥ 48 px, tương phản ≥ 7 : 1, luồng rút gọn một câu hỏi mỗi màn hình, thao tác thường ngày ≤ 3 chạm | NFR-11 | Bộ token phụ "elder" và cờ "ít bước"; kiểm tra tự động |
| BR-18-10 | Phát hành UI Pack trong ≤ 1 ngày, không phát hành lại mã; có thể quay lui về phiên bản trước | NFR-07 | Trạng thái `THAY_THE` kích hoạt lại được; EVT-23 |
| BR-18-11 | Trường biểu mẫu khai báo lớp dữ liệu (C1, C2); trường C2 được mã hoá trường khi lưu | ADR-06 | Thuộc tính `x-classification` trong JSON Schema |
| BR-18-12 | Plugin chuyên ngành chỉ được nạp khi có trong manifest của tenant, bản dựng đã ký và quét như service | ADR-05; ADR-16 | Native Federation đọc manifest; kiểm tra chữ ký |

### Ngoại lệ và xử lý

Khi người dùng đang mở ứng dụng trong lúc phát hành, giao diện của họ không đổi đột ngột; web-frontend nhận EVT-23 qua kênh thời gian thực và hiển thị gợi ý "Có giao diện mới, tải lại khi thuận tiện". Biểu mẫu đang nhập dở vẫn dùng schema cũ cho tới khi nộp, và máy chủ chấp nhận cả hai phiên bản schema trong thời gian chuyển tiếp nếu chúng tham chiếu cùng phiên bản Policy Pack.

Khi một widget của plugin không tải được (plugin lỗi hoặc bị gỡ khỏi manifest), renderer SDUI hiển thị khung giữ chỗ và ghi lỗi, phần còn lại của trang chủ vẫn hoạt động.

Khi quay lui về một phiên bản UI Pack có biểu mẫu tham chiếu Policy Pack đã ngừng, hệ thống chặn quay lui và yêu cầu chọn phiên bản tương thích.

### Đặc tả màn hình

#### SCR-1801. Cấu hình giao diện tenant

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn token, bố cục trang chủ, chế độ cao tuổi, xem trước song song hai tenant, gửi duyệt và phát hành, quay lui |
| Tác nhân | AC-02 (`tenant_admin`); người duyệt |
| Đường vào | Menu Quản trị › Giao diện tenant |
| Quyền | `tenant_admin` của tenant; người duyệt khác người soạn |
| Thiết bị | Web desktop |

![Hình 12.15. SCR-1801 Cấu hình giao diện tenant Sở Y tế tỉnh X, xem trước song song với Đảng uỷ khối](SCR-1801.png){width=16cm}

Tiêu đề nêu rõ phạm vi "Lớp 1 Design Token (W3C Design Tokens) và lớp 2 bố cục trang chủ, không cần phát hành lại mã, NFR-07 ≤ 1 ngày". Thanh trạng thái cho biết UI Pack Sở Y tế tỉnh X v1.8 đang `NHAP`, gắn Policy Pack `nn-335/so-y-te` v1.3 và biểu mẫu tham chiếu `form_schema` v12.

Vùng 1 là bảng token với tên chuẩn (ví dụ `color.brand.primary` #0A7E8C, `font.size.base` 13px, `radius.card` 14px, `asset.logo` logo-so-y-te-x.svg) và kết quả kiểm tra tương phản WCAG AA 4,9 : 1. Vùng 2 là bố cục trang chủ SDUI với ba widget kéo thả (WorkTodayList "Việc của tôi", KpiTile × 2, ChuyenMonKpiWidget của plugin `fe-plugin-yte`) và nút "Thêm widget từ danh mục" (registry v2026.3). Vùng 3 là khối chế độ Đảng viên cao tuổi với hai công tắc: bộ token phụ "elder" và cờ "ít bước". Vùng 4 là xem trước song song hai tenant dùng cùng một bản dựng web-frontend: Đảng uỷ các cơ quan Đảng TW (UI Pack v2.3, đỏ, bo góc 8 px) và Sở Y tế tỉnh X (UI Pack v1.8, xanh mòng két, bo góc 14 px). Vùng 5 là bảng "Phạm vi được cấu hình" liệt kê bề mặt nào tenant cấu hình được và phần nào không, cùng dòng "Tải UI Pack 11,2 KB gzip (ngân sách ≤ 30 KB), phục vụ qua CDN, ETag theo phiên bản, phát hành phát EVT-23 vô hiệu cache".

Đoạn dưới là ví dụ token của Sở Y tế tỉnh X ở định dạng W3C Design Tokens, đúng như được lưu trong `config.ui_pack_version.tokens_json`:

```json
{
  "color": {
    "brand": {
      "primary": { "$type": "color", "$value": "#0A7E8C" },
      "on-primary": { "$type": "color", "$value": "#FFFFFF" }
    },
    "semantic": { "danger": { "$type": "color", "$value": "#D93025" } }
  },
  "font": {
    "family": { "base": { "$type": "fontFamily", "$value": ["DejaVu Sans", "sans-serif"] } },
    "size": { "base": { "$type": "dimension", "$value": "13px" } }
  },
  "radius": { "card": { "$type": "dimension", "$value": "14px" } },
  "space": { "unit": { "$type": "dimension", "$value": "4px" } }
}
```

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Phiên bản, trạng thái | Nhãn | Không áp dụng | vX.Y, trạng thái | Từ dữ liệu | Chỉ đọc | `config.ui_pack_version.version`, `config.ui_pack_version.status` |
| 2 | Policy Pack gắn | Nhãn và liên kết | Có | Mã gói, phiên bản | Gói đang gắn với tenant | Phải `DA_PHAT_HANH` | `config.ui_pack_version.policy_pack_ref` |
| 3 | Token màu | Bộ chọn màu | Có | Hex 6 ký tự | Bộ mặc định | Tương phản AA với màu chữ tương ứng | `config.ui_pack_version.tokens_json` |
| 4 | Token chữ, bo góc, khoảng cách | Danh sách chọn, số | Có | px | Bộ mặc định | Cỡ chữ cơ sở 12 đến 16 px (chế độ thường); bo góc 0 đến 16 px | `config.ui_pack_version.tokens_json` |
| 5 | Logo | Tệp | Không | SVG hoặc PNG ≤ 50 KB | Logo mặc định | SVG được làm sạch, không có script | `config.ui_pack_version.assets_json` |
| 6 | Bố cục trang chủ | Danh sách kéo thả | Có | Widget, thứ tự, tham số | Bố cục mặc định | Mọi widget có trong registry; tối đa 12 widget | `config.ui_pack_version.layout_json`, `config.component_registry` |
| 7 | Bật chế độ cao tuổi | Công tắc | Không | Boolean | Tắt | Không có | `config.ui_pack_version.elder_mode` |
| 8 | Bật cờ ít bước | Công tắc | Không | Boolean | Tắt | Chỉ khi bật chế độ cao tuổi hoặc theo người dùng | `config.ui_pack_version.few_steps` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | `NHAP` | Lưu token, bố cục, cờ | `PUT /api/v1/ui/packs/{id}/versions/{v}` | Không có |
| Thêm widget từ danh mục | `NHAP` | Mở danh mục component cho phép, kể cả widget plugin trong manifest | `GET /api/v1/ui/components?registry=2026.3` | Không có |
| Xem trước | Luôn | Dựng giao diện mẫu với token và bố cục nháp | `GET /api/v1/ui/packs/{id}/versions/{v}/preview` | Không có |
| Gửi duyệt và phát hành | `NHAP` | Chạy kiểm tra tự động, chuyển người duyệt | `POST /api/v1/ui/packs/{id}/versions/{v}/submit` | EVT-21 |
| Duyệt | Người duyệt, `CHO_DUYET` | Biên dịch, đẩy CDN, phát hành | `POST /api/v1/ui/packs/{id}/versions/{v}/publish` | EVT-23 |
| Quay lui v1.6 | Có phiên bản `THAY_THE` tương thích | Kích hoạt lại phiên bản đã chọn | `POST /api/v1/ui/packs/{id}/versions/{v}/activate` | EVT-23 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-18-01 | Màu chữ {mau_chu} trên nền {mau_nen} có tương phản {ti_le} : 1, chưa đạt mức AA (4,5 : 1). | Lỗi kiểm tra | Chọn màu |
| MSG-18-02 | Component {ten} không có trong danh mục cho phép phiên bản {registry}. | Lỗi kiểm tra | Bố cục có component lạ |
| MSG-18-03 | UI Pack v{v} đã phát hành. Người dùng sẽ nhận giao diện mới ở lần tải trang kế tiếp. | Thành công | Phát hành xong |
| MSG-18-04 | Không thể quay lui về v{v} vì biểu mẫu của phiên bản này tham chiếu Policy Pack đã ngừng. | Lỗi | Quay lui không tương thích |
| MSG-18-05 | Kích thước UI Pack là {kb} KB, vượt ngân sách 30 KB. | Lỗi kiểm tra | Kiểm tra kích thước |

#### SCR-1802. Trình dựng biểu mẫu động

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Dựng biểu mẫu nghiệp vụ của tenant bằng kéo thả trường, khai báo quy tắc kiểm tra và điều kiện hiển thị bằng CEL, xem trước tức thời, sinh JSON Schema, chạy bộ test biểu mẫu |
| Tác nhân | AC-02 (`tenant_admin`); AC-03 khi biểu mẫu gắn Policy Pack |
| Đường vào | Menu Quản trị › Biểu mẫu động; liên kết từ SCR-1801 |
| Quyền | `tenant_admin`; trường được tính điểm chỉ thêm được khi Policy Pack khai báo |
| Thiết bị | Web desktop |

![Hình 12.16. SCR-1802 Trình dựng biểu mẫu động phiếu đánh giá viên chức y tế](SCR-1802.png){width=16cm}

Tiêu đề và dòng phụ nêu công nghệ và phiên bản: Formly cộng JSON Schema, kiểm tra bằng CEL (cel-js trên trình duyệt, cel-java trên máy chủ là nguồn sự thật), `form_schema` `phieu-vc-yte` v12, gắn Policy Pack `nn-335/so-y-te` v1.3.

Vùng 1 là bảng thành phần gồm mười loại trường được phép (văn bản ngắn, văn bản dài C2, số, ngày, chọn một, chọn nhiều, tệp minh chứng, bảng lặp, nhóm trường, chữ ký số) và ghi chú "không có trường HTML tự do". Vùng 2 là xem trước biểu mẫu như người dùng thấy: khoa, phòng; số ca trực trong quý với gợi ý tối thiểu 36 lấy từ danh mục vị trí. Vùng 3 là trường "Tỷ lệ hồ sơ bệnh án đạt chuẩn (%)" đang nhập giá trị 104 và hiển thị lỗi tức thời "Giá trị phải từ 0 đến 100 (kiểm tra tức thời, 0,04 ms)"; trường "Mô tả sự cố và biện pháp khắc phục" chỉ hiện khi "Sự cố y khoa" bằng Có và là trường C2. Vùng 4 là ngăn thuộc tính trường `ty_le_hsba_dat`: khoá dữ liệu, kiểu number, quy tắc CEL `self >= 0.0 && self <= 100.0`, điều kiện hiển thị của trường mô tả sự cố `form.su_co_y_khoa == "CO"`, thông báo lỗi. Vùng 5 là JSON Schema sinh ra kèm thuộc tính mở rộng `x-cel` và `x-classification`, và kết quả "Bộ test 24/24, cel-js = cel-java; dựng 50 trường: 31 ms".

Con số 0,04 ms mỗi lần kiểm tra và 31 ms để dựng 50 trường là số đo trên máy trạm phát triển (Chrome, CPU tầm trung) với bản dựng thử; ngân sách thiết kế là 50 ms cho 50 trường theo baseline G1.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã biểu mẫu, phiên bản | Nhãn | Không áp dụng | khoá, vN | Từ dữ liệu | Chỉ đọc | `config.form_schema.form_key`, `config.form_schema.version` |
| 2 | Khoá dữ liệu trường | Văn bản | Có | snake_case, ≤ 64 ký tự | Sinh từ nhãn | Duy nhất trong biểu mẫu; nếu là trường tính điểm thì phải khớp Policy Pack | `config.form_schema.schema_json` |
| 3 | Kiểu | Danh sách chọn | Có | string, number, date, enum, array, object, file, signature | Theo thành phần | Không có | `config.form_schema.schema_json` |
| 4 | Nhãn, gợi ý | Văn bản | Có | ≤ 200 ký tự | Trống | Không có | `config.form_schema.ui_json` |
| 5 | Bắt buộc | Công tắc | Có | Boolean | Không | Không có | `config.form_schema.schema_json` (required) |
| 6 | Quy tắc kiểm tra (CEL) | Mã CEL | Không | Biểu thức trả về bool, biến `self`, `form` | Trống | Kiểm tra kiểu bằng cel-js và cel-java | `config.form_schema.schema_json` (`x-cel`) |
| 7 | Điều kiện hiển thị (CEL) | Mã CEL | Không | Biểu thức trả về bool | Trống | Không tham chiếu vòng | `config.form_schema.ui_json` (hideExpression) |
| 8 | Thông báo lỗi | Văn bản | Có khi có quy tắc | ≤ 200 ký tự | Trống | Không có | `config.form_schema.ui_json` |
| 9 | Lớp dữ liệu | Danh sách chọn | Có | C1, C2 | C1 | Văn bản dài nhận xét mặc định C2 | `config.form_schema.schema_json` (`x-classification`) |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Kéo thành phần vào biểu mẫu | Phiên bản nháp | Thêm trường, sinh khoá dữ liệu | Phía client | Không có |
| Chạy bộ test biểu mẫu | Luôn | Chạy các ca test trên cel-js tại trình duyệt và cel-java tại máy chủ, so sánh | `POST /api/v1/ui/forms/{key}/versions/{v}/test-runs` | Không có |
| Lưu nháp | Phiên bản nháp | Lưu schema và ui | `PUT /api/v1/ui/forms/{key}/versions/{v}` | Không có |
| Đưa vào UI Pack v1.8 | Bộ test đạt | Gắn phiên bản biểu mẫu vào UI Pack nháp | `POST /api/v1/ui/packs/{id}/versions/{v}/forms` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-18-06 | Biểu thức CEL không hợp lệ: {loi}. | Lỗi kiểm tra | Soạn quy tắc |
| MSG-18-07 | Trường {khoa} được Policy Pack {goi} dùng để tính điểm nên không thể đổi kiểu hoặc xoá. | Lỗi | Sửa trường tính điểm |
| MSG-18-08 | Ca test {ma} cho kết quả khác nhau giữa trình duyệt và máy chủ. | Lỗi | Hai engine khác nhau |

#### SCR-1803. Chế độ Đảng viên cao tuổi (di động)

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho đảng viên cao tuổi thực hiện các việc thường ngày (xác nhận sinh hoạt, tự đánh giá cuối năm) trên điện thoại với chữ lớn, tương phản cao, một câu hỏi mỗi màn hình và nhập bằng giọng nói |
| Tác nhân | AC-06 đảng viên ở chi bộ khu dân cư, đảng viên cao tuổi |
| Đường vào | Ứng dụng di động (SVC-18); bật theo tenant trong UI Pack hoặc theo từng người trong "Tôi › Hiển thị" |
| Quyền | Như chế độ thường |
| Thiết bị | Điện thoại (Flutter); web di động |

![Hình 12.17. SCR-1803 Chế độ Đảng viên cao tuổi trên di động: việc của tôi và tự đánh giá luồng rút gọn](SCR-1803.png){width=15cm}

Hai khung điện thoại minh hoạ hai màn hình của cùng một người dùng ở Chi bộ khu phố 3. Chế độ này không phải ứng dụng riêng: cùng dữ liệu, cùng API với chế độ thường, chỉ khác bộ token và cách trình bày luồng.

Vùng 1 là danh sách "Việc của tôi" với thẻ lớn, mỗi thẻ một việc và một nút chính chiếm toàn chiều ngang (ví dụ "Sinh hoạt chi bộ tháng 12, hạn thứ Năm 18/12, Đã tham gia"). Vùng 2 là thẻ "Tự đánh giá cuối năm, còn 5 ngày, 3 bước" với nút "Bắt đầu", và hai nút "Nói" (nhập bằng giọng nói tiếng Việt) và "Gọi hỗ trợ". Từ trang chủ tới hoàn thành một việc thường ngày cần không quá 3 chạm. Vùng 3 là màn hình tự đánh giá bước 2/3 ở luồng rút gọn: một câu hỏi lớn và hai lựa chọn dạng nút cao, ô ghi chú không bắt buộc có thể nhấn để nói; dòng chú thích "Chữ 19 px, tương phản 12,6 : 1, mỗi màn hình một câu hỏi". Vùng 4 là hai nút điều hướng lớn "Quay lại" và "Tiếp tục". Bên phải là chú thích thiết kế: token phụ "elder" (chữ cơ sở 17 đến 19 px, nút cao ≥ 48 px, viền đậm 2 px, tương phản ≥ 7 : 1) và cờ "ít bước" (một câu hỏi mỗi màn hình, bỏ trường không bắt buộc, nhập bằng giọng nói).

Luồng rút gọn tự đánh giá của đảng viên khu dân cư chỉ gồm 3 bước vì Policy Pack của khối này (PL 2E) có ít tiêu chí và không có nhiệm vụ then chốt; phiếu sau khi hoàn tất vẫn sinh ra cùng bản ghi `appraisal.self_assessment` như chế độ thường và đi tiếp QT-09.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Danh sách việc | Thẻ | Không áp dụng | Tên, hạn, trạng thái | Việc trong tuần | Tối đa 5 thẻ trên trang đầu | `flowable.wf_task_projection`, `task.task` |
| 2 | Câu trả lời tiêu chí | Chọn một (nút lớn) | Có | Đầy đủ, chưa đầy đủ | Trống | Không có | `appraisal.general_criteria_result.met` |
| 3 | Ghi chú thêm | Văn bản hoặc giọng nói | Không | ≤ 1.000 ký tự | Trống | Nhận dạng giọng nói chạy tại chỗ; văn bản được hiển thị để xác nhận | `appraisal.self_assessment.content_enc` |
| 4 | Cỡ chữ, tương phản | Thiết lập người dùng | Không | Theo token elder | Theo tenant | Không thấp hơn 17 px | `notify.channel_pref` (mục hiển thị) hoặc hồ sơ người dùng trong `config.tenant_setting` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Đã tham gia | Việc sinh hoạt chi bộ | Ghi xác nhận tham gia | `POST /api/v1/tasks/{id}/progress` | EVT-05 |
| Bắt đầu | Phiếu tự đánh giá mở | Mở luồng rút gọn | `GET /api/v1/appraisals/profiles/{id}` | Không có |
| Nói | Luôn | Ghi âm, chuyển văn bản tiếng Việt, hiển thị để xác nhận | `POST /api/v1/ai/speech-to-text` | Không có |
| Tiếp tục, Quay lại | Trong luồng | Lưu từng bước vào nháp cục bộ mã hoá và máy chủ | `PUT /api/v1/appraisals/profiles/{id}/self-assessment` | Không có |
| Gọi hỗ trợ | Luôn | Gọi số hỗ trợ của chi bộ hoặc tổng đài | Thiết bị | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-18-09 | Đã ghi nhận đồng chí tham gia sinh hoạt chi bộ tháng 12. | Thành công | Bấm "Đã tham gia" |
| MSG-18-10 | Hệ thống đã nghe: "{van_ban}". Đồng chí bấm "Đúng" để lưu hoặc "Nói lại". | Xác nhận | Sau nhận dạng giọng nói |

### Liên kết thiết kế

QT-18 dùng MOD-08 `config` (bảng `ui_pack`, `ui_pack_version`, `form_schema`, `feature_flag` và bảng đề xuất mới `component_registry`), SVC-17 web-frontend (`fe-shell`, `@vt/design-system`, `@vt/sdui-renderer`, `@vt/form-engine`, plugin `fe-plugin-yte` qua Native Federation), SVC-18 mobile-app (Flutter, token elder), CDN và vùng điều phối toàn cục (kho UI Pack gốc). Sự kiện: EVT-23 UiPackDaPhatHanh, EVT-21. Liên quan ADR-05 (G1, bốn lớp), ADR-10 (Angular, Flutter), ADR-16 (ký bản dựng plugin), NFR-07 (≤ 1 ngày), NFR-11 (WCAG 2.1 AA, chế độ cao tuổi). Đáp ứng góp ý G1 bằng quy trình cụ thể: phần tenant cấu hình được (token, bố cục bề mặt cấu hình được, biểu mẫu, plugin) và phần không cấu hình được (màn hình lõi viết mã, công thức tính điểm) đều được nêu rõ trên SCR-1801.

# Ma trận truy vết

Chương này nối các quy trình và màn hình của TL-02 với phần còn lại của bộ tài liệu theo chuỗi truy vết bắt buộc của baseline mục 1: quy trình (QT) đến màn hình (SCR), đến API và module hoặc service (MOD, SVC trong TL-01), đến bảng dữ liệu và sự kiện (TL-03), rồi đến thành phần triển khai, chỉ tiêu năng lực và biện pháp an toàn thông tin (TL-04). Chương gồm ba bảng. Bảng thứ nhất truy vết toàn bộ 19 quy trình QT-00 đến QT-18. Bảng thứ hai đối chiếu các tiêu chí nhóm H (phạm vi chức năng) và nhóm I (toàn vẹn, chống chối bỏ) của bản đánh giá kiến trúc với quy trình và màn hình đáp ứng. Bảng thứ ba đối chiếu tám nhóm tính năng trong PL A1, A2 của báo cáo BGĐ.

![Hình 13.1. Chuỗi truy vết từ quy trình tới triển khai trong bộ tài liệu TL-01 đến TL-04](nv-b-truyvet.png){width=16cm}

Hình trên tóm tắt chiều đọc của các bảng. Từ một quy trình, người đọc tìm được màn hình thực hiện nó; từ màn hình tìm được API (tiền tố `/api/v1/<module>`) và module, service xử lý; từ module tìm được bảng dữ liệu và sự kiện; từ bảng và sự kiện tìm được thành phần triển khai, kịch bản thử tải và biện pháp an toàn thông tin trong TL-04. Chiều ngược lại dùng khi đánh giá ảnh hưởng của một thay đổi: sửa một bảng dữ liệu thì biết những màn hình và quy trình nào bị ảnh hưởng.

## Truy vết quy trình, màn hình, API, dữ liệu và sự kiện

Với QT-00 đến QT-08, thông tin được lấy từ baseline mục 5.1 (module), mục 7 (sự kiện), mục 9 (bảng), mục 11 (màn hình) và suy luận theo quy ước API mục 13; chi tiết thuộc phạm vi TL-02a. Với QT-09 đến QT-18, thông tin khớp với các bảng chức năng ở hai chương trước. Cột API chỉ liệt kê nhóm đường dẫn chính, danh sách đầy đủ nằm trong đặc tả OpenAPI 3.1 của TL-01.

| QT | Màn hình | API chính | Bảng dữ liệu chính | Sự kiện | MOD / SVC |
|---|---|---|---|---|---|
| QT-00 Đăng nhập, chọn đơn vị và vai trò | SCR-0001, SCR-0002 | OIDC của Keycloak; `/api/v1/org/me`, `/api/v1/tasks/my` | bảng Keycloak trong `iam`; `org_replica.assignment_v`; `flowable.wf_task_projection` | Không có | SVC-01, SVC-02, SVC-17, SVC-18, MOD-02 |
| QT-01 Khởi tạo tenant, cây tổ chức, đồng bộ nhân sự | SCR-0101, SCR-0102, SCR-0103, SCR-0104 | `/api/v1/config/tenants`, `/api/v1/org/units`, `/api/v1/org/sync` | `registry.tenant`, `registry.tenant_cell`, `org.org_unit`, `org.person`, `org.assignment`, `org.scim_sync_log`, `integ.mapping_xref` | EVT-14, EVT-15, EVT-16 | SVC-03, SVC-04, SVC-13, MOD-08 |
| QT-02 Lập và phê duyệt mục tiêu, OKR | SCR-0201, SCR-0202, SCR-0203 | `/api/v1/okr/objectives`, `/api/v1/okr/key-results`, `/api/v1/okr/links` | `okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `flowable.wf_binding` | EVT-01 | MOD-01, SVC-06, SVC-14 |
| QT-03 Check-in KR | SCR-0301, SCR-0302 | `/api/v1/okr/key-results/{id}/checkins` | `okr.kr_checkin`, `okr.key_result` | EVT-02 | MOD-01, SVC-18, SVC-15 |
| QT-04 Lập và phê duyệt kế hoạch công tác quý | SCR-0401, SCR-0402, SCR-0403 | `/api/v1/catalog`, `/api/v1/plans`, `/api/v1/plans/{id}/versions` | `task.work_catalog`, `task.catalog_item`, `task.plan`, `task.plan_version`, `task.plan_item` | EVT-03 | MOD-02, SVC-06, SVC-10 |
| QT-05 Giao việc, đăng ký nhiệm vụ, nhận từ văn bản | SCR-0501, SCR-0502, SCR-0503, SCR-0504 | `/api/v1/tasks`, `/api/v1/tasks/{id}/assignees`, `/api/v1/tasks/from-document` | `task.task`, `task.task_assignee`, `task.task_source_doc`, `integ.inbox_message` | EVT-04, EVT-24 | MOD-02, SVC-13, SVC-14, SVC-16 |
| QT-06 Cập nhật tiến độ, điểm nghẽn, gia hạn | SCR-0601, SCR-0602 | `/api/v1/tasks/{id}/progress`, `/api/v1/tasks/{id}/blockers`, `/api/v1/tasks/{id}/change-requests` | `task.task_progress`, `task.task_blocker`, `task.task_change_request`, `task.task_state_log` | EVT-05, EVT-06 | MOD-02, SVC-06, SVC-12 |
| QT-07 Nộp và nghiệm thu sản phẩm, minh chứng | SCR-0701, SCR-0702, SCR-0703 | `/api/v1/files`, `/api/v1/evidences`, `/api/v1/products/{id}/acceptance` | `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `evidence.object_meta`, `evidence.dedup_index`, `evidence.scan_result` | EVT-07, EVT-08 | MOD-03, SVC-08, SVC-14 (OCR), SVC-07 |
| QT-08 Khoá kỳ (saga chốt kỳ) | SCR-0801 | `/api/v1/periods`, `/api/v1/periods/{id}/lock`, `/api/v1/jobs/{id}` | `config.period`, `config.holiday_calendar`, `scoring.score_job` | EVT-09 | MOD-08, SVC-06 (saga), SVC-07, SVC-09 |
| QT-09 Tự đánh giá, thẩm định, đề xuất, quyết định quý | SCR-0901, SCR-0902, SCR-0903, SCR-0904 | `/api/v1/appraisals/profiles`, `/api/v1/appraisals/batch-decisions`, `/api/v1/esign/requests` | `appraisal.appraisal_profile`, `appraisal.general_criteria_result`, `appraisal.self_assessment`, `appraisal.score_snapshot`, `appraisal.rating_proposal`, `appraisal.rating_explanation`, `appraisal.batch_decision`, `scoring.score_trace` | EVT-09, EVT-10, EVT-11, EVT-12, EVT-18, EVT-21 | MOD-04, SVC-07, SVC-06, SVC-10, SVC-11 |
| QT-10 Xếp loại năm, liên thông, phân nhóm | SCR-1001, SCR-1002 | `/api/v1/appraisals/annual`, `/api/v1/appraisals/prospect-groups` | `appraisal.annual_rating`, `appraisal.prospect_group`, `appraisal.score_snapshot` | EVT-10, EVT-11, EVT-12, EVT-04 | MOD-04, SVC-07, MOD-02 |
| QT-11 Kiểm điểm và hồ sơ số | SCR-1101, SCR-1102 | `/api/v1/dossiers/meetings`, `/api/v1/dossiers/forms`, `/api/v1/dossiers/packages` | `dossier.review_meeting`, `dossier.review_comment`, `dossier.form_instance`, `dossier.special_case`, `dossier.dossier_package`, `dossier.remediation_item`, `docgen.render_job` | EVT-12, EVT-17, EVT-18, EVT-21 | MOD-06, SVC-11, SVC-10, SVC-13 |
| QT-12 Khiếu nại, giải trình | SCR-1201, SCR-1202 | `/api/v1/oversight/complaints` | `oversight.complaint`, `oversight.complaint_response` | EVT-25, EVT-18, EVT-21 | MOD-07, SVC-06, SVC-07, SVC-11 |
| QT-13 Hậu kiểm, mở lại kỳ hai người duyệt | SCR-1301, SCR-1302 | `/api/v1/oversight/reopen-requests`, `/api/v1/ledger/entries`, `/api/v1/ledger/verify-runs` | `oversight.reopen_request`, `oversight.reopen_approval`, `oversight.post_audit`, `ledger.ledger_entry`, `ledger.merkle_seal`, `ledger.anchor_receipt`, `ledger.verify_run` | EVT-22, EVT-19, EVT-20, EVT-09, EVT-10 | MOD-07, MOD-08, SVC-09, SVC-02, SVC-06 |
| QT-14 Cảnh báo bất thường | SCR-1401 | `/api/v1/oversight/alerts`, `/api/v1/config/anomaly-thresholds` | `oversight.anomaly_alert`, `oversight.alert_action`, `evidence.dedup_index` | EVT-20, EVT-21 | SVC-15, MOD-07, SVC-08 |
| QT-15 Soạn, kiểm thử, phát hành Policy Pack | SCR-1501, SCR-1502, SCR-1503, SCR-1504 | `/api/v1/policies/packs`, `/api/v1/policies/test-cases`, `/api/v1/policies/workflow-templates` | `policy.policy_pack`, `policy.policy_pack_version`, `policy.policy_rule`, `policy.decision_table`, `policy.golden_test_case`, `policy.test_run`, `policy.policy_binding`, `flowable.wf_template`, `flowable.wf_binding` | EVT-13, EVT-18, EVT-21 | MOD-05, SVC-07, SVC-06, SVC-10 |
| QT-16 Bảng điều hành và báo cáo | SCR-1601, SCR-1602, SCR-1603 | `/api/v1/analytics/query`, `/api/v1/analytics/dashboards`, `/api/v1/analytics/trace` | ClickHouse `analytics_<cell>`; `config.dashboard_def`; bảng nguồn của MOD-01 đến MOD-04 | EVT-04, EVT-21 (phát); mọi EVT nghiệp vụ là nguồn CDC | SVC-15, SVC-14, MOD-02 |
| QT-17 Trợ lý AI | SCR-1701 | `/api/v1/ai/assist/okr`, `/api/v1/ai/suggestions` | `ai.doc_chunk`, `ai.suggestion_log`, `ai.guardrail_event`, `okr.objective`, `okr.key_result` | EVT-24 (đầu vào), EVT-21 | SVC-14, MOD-01, SVC-02 |
| QT-18 Cấu hình giao diện tenant | SCR-1801, SCR-1802, SCR-1803 | `/api/v1/ui/packs`, `/api/v1/ui/forms`, `/api/v1/ui/components` | `config.ui_pack`, `config.ui_pack_version`, `config.form_schema`, `config.feature_flag`, `config.component_registry` | EVT-23, EVT-21 | MOD-08, SVC-17, SVC-18 |

Bảng cho thấy hai đặc điểm của thiết kế. Thứ nhất, mỗi quy trình có một module chủ sở hữu duy nhất trong mission-core (hoặc một service với QT-16, QT-17), còn các service khác chỉ tham gia qua API công bố hoặc sự kiện; điều này khớp với ranh giới module của Spring Modulith (ADR-01) và giúp ArchUnit kiểm tra được phụ thuộc. Thứ hai, các sự kiện EVT-09, EVT-10, EVT-12, EVT-18 là "xương sống" nối các quy trình đánh giá: khoá kỳ (QT-08) kích hoạt tính điểm, tính điểm mở phiếu (QT-09), quyết định kích hoạt hồ sơ số (QT-11), ký số hoàn tất từng mốc. Các bảng có tên in trong cột dữ liệu mà không có trong baseline mục 9 (`appraisal.batch_decision`, `dossier.remediation_item`, `config.component_registry`, `config.dashboard_def`) là đề xuất mới, được liệt kê trong tệp ghi chú để TL-03 xác nhận.

## Đối chiếu tiêu chí của bản đánh giá kiến trúc

Bảng dưới đối chiếu chín tiêu chí nhóm H (phạm vi chức năng nền tảng, ISO/IEC 25010 Functional Suitability) và tám tiêu chí nhóm I (toàn vẹn và chống chối bỏ) với quy trình, màn hình đáp ứng. Cột "Hiện trạng" lấy từ bản đánh giá; cột "Cách đáp ứng" tóm tắt biện pháp trong TL-02, phần kiến trúc chi tiết nằm ở TL-01 và TL-03.

| Mã | Tiêu chí | Hiện trạng theo bản đánh giá | Quy trình | Màn hình | Cách đáp ứng |
|---|---|---|---|---|---|
| H1 | Mục tiêu và OKR | Chưa đạt | QT-02, QT-03, QT-17 | SCR-0201, SCR-0202, SCR-0203, SCR-0301, SCR-0302, SCR-1701 | Căn cứ, mục tiêu O (cảnh báo khi trên 3), KR có chỉ tiêu, trọng số, người xác nhận; phân rã ba tầng; check-in có mức tự tin; AI soát KR mơ hồ, trùng |
| H2 | Giao việc và KPI | Một phần | QT-04, QT-05, QT-06 | SCR-0401 đến SCR-0403, SCR-0501 đến SCR-0504, SCR-0601, SCR-0602 | Một chủ trì nhiều phối hợp; danh mục có sản phẩm, đơn vị tính, mức khó; kế hoạch có phiên bản; cảnh báo sớm chậm |
| H3 | Sản phẩm, minh chứng | Một phần | QT-07, QT-14 | SCR-0701 đến SCR-0703, SCR-1401 | Minh chứng có phiên bản; tách thực hiện và nghiệm thu; chất lượng 5 mức; chỉ `DA_XAC_NHAN` được tính (BR-09-02); cảnh báo trùng mã băm (dấu hiệu 4) |
| H4 | Đánh giá, xếp loại | Một phần | QT-09, QT-10, QT-15 | SCR-0901 đến SCR-0904, SCR-1001, SCR-1501, SCR-1502 | Một bộ máy tính duy nhất (SVC-07) theo Policy Pack; kỳ quý và năm; liên thông quý–năm; quy tắc người đứng đầu; lưu 4 mức; câu giải thích hạ mức |
| H5 | Kiểm điểm, hồ sơ số | Chưa đạt | QT-11 | SCR-1101, SCR-1102 | Sinh Mẫu 1A đến 1D, 2, 3 từ dữ liệu; ký số; 13 trường hợp đặc thù; đóng gói quý, năm; chuyển hồ sơ diện TW |
| H6 | Giám sát, hậu kiểm | Một phần | QT-12, QT-13, QT-14 | SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401 | Nhật ký không sửa được; khoá, mở kỳ hai người duyệt; khiếu nại, giải trình, bảo lưu; hậu kiểm; tám dấu hiệu bất thường |
| H7 | Phân tích, dashboard | Một phần | QT-16, QT-10 | SCR-1601, SCR-1602, SCR-1603, SCR-1002 | Dashboard tự dựng trên lớp ngữ nghĩa Cube; đi xuống tới minh chứng; so sánh nhiều kỳ; phân nhóm triển vọng năm |
| H8 | Quản trị nền tảng | Một phần | QT-01, QT-15, QT-18 | SCR-0101 đến SCR-0104, SCR-1501 đến SCR-1504, SCR-1801 đến SCR-1803 | Đa khách thuê; cây tổ chức có lịch sử; tách vai trò (OPA); Config Studio, Policy Pack có phiên bản và test case; UI Pack |
| H9 | Hai luồng Đảng, Nhà nước | Một phần | QT-09, QT-15 | SCR-0901, SCR-0903, SCR-1501 | Hai snapshot cho cùng người, cùng kỳ, cùng dữ liệu sản phẩm; Policy Pack Đảng (100 điểm) và Nhà nước (30/70) độc lập (BR-09-11) |
| I1 | Nhật ký thay đổi đầy đủ | Chưa đạt | QT-13 và mọi quy trình ghi | SCR-1302 | Outbox ghi sổ cái cùng giao dịch; giá trị trước, sau, lý do, người làm, người duyệt, thời điểm (BR-13-07) |
| I2 | Sổ cái chỉ ghi, chuỗi băm, niêm phong | Chưa đạt | QT-13 | SCR-1302 | 64 phân vùng chuỗi mỗi tenant; niêm phong Merkle mỗi giờ; công cụ kiểm tra chỉ ra bản ghi bị phá vỡ; CLI kiểm tra độc lập |
| I3 | Khoá kỳ, mở lại cần hai người duyệt | Chưa đạt | QT-08, QT-13 | SCR-0801, SCR-1301 | Kỳ `DA_KHOA` chặn mọi ghi; mở lại cần hai phê duyệt khác người, có MFA bước lên, trong phạm vi và thời hạn |
| I4 | Ký số tại mốc pháp lý | Chưa đạt | QT-04, QT-09, QT-11, QT-12, QT-15 | SCR-0403, SCR-0901, SCR-0902, SCR-0904, SCR-1102, SCR-1202, SCR-1503 | Ký kế hoạch, phiếu tự đánh giá, thẩm định, quyết định (ký lô), gói hồ sơ, văn bản trả lời khiếu nại, phát hành Policy Pack |
| I5 | Minh chứng bất biến có mã băm | Chưa đạt | QT-07, QT-11 | SCR-0701, SCR-1102, SCR-1603 | SHA-256 khi tải lên; Ceph Object Lock; phiên bản mới không xoá bản cũ; manifest gói có băm từng tệp |
| I6 | Snapshot gắn phiên bản quy tắc | Một phần | QT-09, QT-10, QT-13, QT-15 | SCR-0903, SCR-1001, SCR-1301, SCR-1503 | `score_snapshot` có `policy_version`, `input_hash`, vết tính; tính lại bằng đúng phiên bản; kỳ đã khoá không bị tính lại bằng phiên bản mới |
| I7 | Dữ liệu hai trục thời gian | Chưa đạt | QT-01, QT-09 | SCR-0103 | Cây tổ chức, phân công lưu `valid_from/valid_to` và `recorded_from/recorded_to`; hồ sơ đánh giá đọc tổ chức tại ngày khoá kỳ |
| I8 | Dấu thời gian tin cậy | Chưa đạt | QT-11, QT-13 | SCR-1102, SCR-1302 | Dấu thời gian RFC 3161 trên chữ ký gói hồ sơ và niêm phong Merkle; bản neo gửi bên thứ ba |

Tất cả 17 tiêu chí H và I đều có ít nhất một quy trình và một màn hình đáp ứng. Các tiêu chí ở mức "Chưa đạt" của bản đánh giá (H1, H5, I1 đến I5, I7, I8) được giải quyết chủ yếu bằng các quy trình mới QT-02, QT-11, QT-13 và bằng các quy tắc nền tảng không cấu hình được (BR-09-12 đến BR-09-16, BR-13-01 đến BR-13-12).

## Đối chiếu nhóm tính năng của báo cáo BGĐ

PL A1 và A2 của báo cáo BGĐ liệt kê tám nhóm tính năng với căn cứ trong bộ dự thảo. Bảng dưới đối chiếu từng tính năng với quy trình và màn hình; tính năng được gom theo dòng như trong phụ lục.

| Nhóm | Tính năng (theo PL A1, A2) | Căn cứ | Quy trình | Màn hình |
|---|---|---|---|---|
| 1. Mục tiêu, OKR | Quản lý căn cứ; mục tiêu O khuyến nghị 1 đến 3 mỗi kỳ; KR có chỉ tiêu, trọng số, hạn, người xác nhận | HD 1b–c; PL 1 3.2a | QT-02 | SCR-0201, SCR-0202 |
| 1. Mục tiêu, OKR | Phân rã ba tầng nhiều lớp; mục tiêu con phải có căn cứ cấp trên | PL 1 mục 3 | QT-02 | SCR-0201 |
| 1. Mục tiêu, OKR | Truy vết hai chiều từ căn cứ đến O, KR, nhiệm vụ; liên kết ngang | PL 4 III.2 | QT-02, QT-16 | SCR-0201, SCR-1603 |
| 1. Mục tiêu, OKR | Check-in KR có mức tự tin; AI kiểm tra KR trùng nhiệm vụ | HD 1c; đề xuất | QT-03, QT-17 | SCR-0301, SCR-0302, SCR-1701 |
| 2. Giao việc, KPI | Kế hoạch theo Bảng 1: một chủ trì, nhiều phối hợp | PL 1 mục 2; QĐ 3.3 | QT-04, QT-05 | SCR-0402, SCR-0501 |
| 2. Giao việc, KPI | Danh mục công việc theo Bảng 3 | PL 1 Bảng 2–3 | QT-04 | SCR-0401 |
| 2. Giao việc, KPI | Phân loại thường xuyên hay then chốt; chặn tính điểm trùng | HD 1e; PL 3 | QT-04, QT-09 | SCR-0402, SCR-0901 |
| 2. Giao việc, KPI | Duyệt kế hoạch trước tuần 2; điều chỉnh có phiên bản; chặn KPI mơ hồ | QĐ 12.1.1; HD 1g | QT-04, QT-06, QT-17 | SCR-0403, SCR-0602, SCR-1701 |
| 2. Giao việc, KPI | Cập nhật tiến độ; cảnh báo sớm; sổ điểm nghẽn | QĐ 12.1.2; PL 4 | QT-06, QT-16 | SCR-0601, SCR-1601 |
| 3. Sản phẩm, minh chứng | Minh chứng có phiên bản; tách thực hiện và nghiệm thu | PL 4 II.2 | QT-07 | SCR-0701, SCR-0702 |
| 3. Sản phẩm, minh chứng | Nghiệm thu chất lượng 5 mức; ghi nguyên nhân khách quan | PL 3 mục 2 | QT-07 | SCR-0703 |
| 3. Sản phẩm, minh chứng | Chưa xác nhận thì không tính điểm; cảnh báo minh chứng trùng | PL 4 III.5 | QT-07, QT-09, QT-14 | SCR-0703, SCR-0901, SCR-1401 |
| 4. Đánh giá, xếp loại | Bộ máy KPI (A+B+C)/3, tỷ trọng nhóm cấu hình được, làm tròn | PL 3; Bảng chấm điểm | QT-09, QT-15 | SCR-0901, SCR-0903, SCR-1501 |
| 4. Đánh giá, xếp loại | Tiêu chí chung 4 mức theo tỷ lệ; mức cuối là mức thấp hơn | QĐ 10–11; PL 6 §12 | QT-09, QT-15 | SCR-0901, SCR-1502 |
| 4. Đánh giá, xếp loại | Điều kiện loại trừ; quy tắc người đứng đầu | QĐ 11.2.4, 14.3.3 | QT-09, QT-15 | SCR-0904, SCR-1502 |
| 4. Đánh giá, xếp loại | Liên thông quý và năm | QĐ 15.5–15.6 | QT-10 | SCR-1001 |
| 4. Đánh giá, xếp loại | Lưu riêng 4 mức; bắt buộc giải trình khi khác | QĐ 16.3; Mẫu 3 | QT-09 | SCR-0901 đến SCR-0904 |
| 5. Kiểm điểm, hồ sơ số | Sinh Mẫu 1A–1D, 2, 3, bảng chấm điểm; ký số, dấu thời gian | Các Mẫu; PL 4 V.3 | QT-11 | SCR-1102 |
| 5. Kiểm điểm, hồ sơ số | Gửi dự thảo ≥ 03 ngày làm việc; góp ý; biên bản; theo dõi khắc phục | QĐ Đ6–7; HD 2–4 | QT-11 | SCR-1101 |
| 5. Kiểm điểm, hồ sơ số | 13 trường hợp đặc thù | PL 6 | QT-11, QT-09 | SCR-1102 |
| 5. Kiểm điểm, hồ sơ số | Đóng gói quý, năm; chuyển hồ sơ diện TW | QĐ Đ17; HD 10 | QT-11 | SCR-1102 |
| 6. Giám sát, hậu kiểm | Nhật ký không sửa được; khoá và mở kỳ có phê duyệt | PL 4 III.2, V.2 | QT-08, QT-13 | SCR-0801, SCR-1301, SCR-1302 |
| 6. Giám sát, hậu kiểm | Khiếu nại, giải trình, bảo lưu; trả lời bằng văn bản | QĐ 16.1–16.2 | QT-12 | SCR-1201, SCR-1202 |
| 6. Giám sát, hậu kiểm | Hậu kiểm theo kế hoạch, đột xuất, theo phản ánh | QĐ 15.3 | QT-13 | SCR-1301 |
| 6. Giám sát, hậu kiểm | Phát hiện 8 dấu hiệu bất thường | PL 4 V.6 | QT-14 | SCR-1401 |
| 7. Phân tích, dashboard | Dựng dashboard trên lớp ngữ nghĩa; đi xuống tới minh chứng; so sánh nhiều kỳ | Đề xuất | QT-16 | SCR-1601, SCR-1602, SCR-1603 |
| 7. Phân tích, dashboard | Phân nhóm triển vọng năm, xếp thứ tự, kế hoạch đào tạo; khoá ở kỳ quý | PL 5; QĐ 12.1.5 | QT-10 | SCR-1002 |
| 7. Phân tích, dashboard | Theo dõi 05 chỉ tiêu KT-XH; chỉ tính chỉ tiêu đã công bố | QĐ 10.2.2 | QT-16, QT-09 | SCR-1601, SCR-1502 |
| 8. Quản trị nền tảng | Đa khách hàng, cây tổ chức có lịch sử, 9 khối, 40 chức danh diện TW | PL 2A–2E | QT-01 | SCR-0101 đến SCR-0104 |
| 8. Quản trị nền tảng | Tách vai trò khởi tạo, giao, xác nhận, nghiệm thu, thẩm định, quyết định, khoá kỳ | PL 4 V.1 | QT-09, QT-12, QT-13 (OPA) | SCR-0902, SCR-1202, SCR-1301 |
| 8. Quản trị nền tảng | Config Studio: công thức, biểu mẫu, luồng duyệt, Policy Pack có phiên bản và test case | PL 4 VI.5 | QT-15, QT-18 | SCR-1501 đến SCR-1504, SCR-1802 |
| 8. Quản trị nền tảng | Tích hợp văn bản, CSDL CBCCVC, VNeID, ký số, LGSP | PL 4 V.3; NĐ 233 | QT-00, QT-01, QT-05, QT-11 | SCR-0001, SCR-0104, SCR-0502, SCR-1102 |
| 8. Quản trị nền tảng | Kênh web, di động, thông báo đẩy, trợ lý AI tiếng Việt | Đề xuất | QT-03, QT-05, QT-17, QT-18 | SCR-0302, SCR-0504, SCR-1701, SCR-1803 |

Mọi dòng tính năng trong PL A1, A2 đều được ít nhất một quy trình đáp ứng. Bốn điểm chưa thống nhất của dự thảo nêu ở PL A4 (cách tính chất lượng, ngày lịch hay ngày làm việc, cách hiểu điều kiện 30%, phân nhóm ở kỳ quý) không được viết cứng mà trở thành tham số của Policy Pack (BR-15-02, BR-15-03, BR-15-04, BR-10-07), nên khi có hướng dẫn chính thức chỉ cần phát hành phiên bản Policy Pack mới theo QT-15 trong thời hạn NFR-07.

# Phụ lục. Cập nhật sau triển khai đợt 1 (10/2026) {-}

Phụ lục này ghi lại những điểm mà quy trình, màn hình và quy tắc nghiệp vụ đã được hiện thực khác với TL-02 phiên bản 1.0, tính đến mã trên nhánh `main` ngày 23/10/2026. Nguồn đối chiếu là mã nguồn, bộ hợp đồng `contracts/openapi/`, ma trận phân quyền `docs/phan-quyen/ma-tran.json` và trang wiki 14. Cách ghi trạng thái giống Phụ lục D của TL-01: "Đã chốt" là đã có quyết định của người phụ trách, "Theo mã, chờ xác nhận" là phương án mặc định mà mã đang chạy, "Câu hỏi mở" là việc chưa có câu trả lời. Khi TL-02 và TL-01 mâu thuẫn, phụ lục ghi theo mã và nêu rõ; trong đợt này, phần lớn mâu thuẫn được giải quyết theo TL-02, vì TL-02 mô tả quy trình ở mức chi tiết hơn.

## Quy ước chung của đặc tả {-}

Hai quy ước chung thay đổi cách đọc các bảng sự kiện của từng màn hình. Thứ nhất, theo ADR-32 (TL-01 Phụ lục F), cột "API" trong bảng sự kiện SCR nay được hiểu theo `operationId` của bộ hợp đồng; đường dẫn ghi trong TL-02 chỉ còn giá trị minh hoạ. Khi đường dẫn trong TL-02 khác hợp đồng, hợp đồng thắng; ví dụ quyết định với gợi ý AI là `POST /ai/suggestions/{id}/decision` với giá trị `CHAP_NHAN`, `SUA`, `BO_QUA`, không phải hai thao tác `/accept`, `/dismiss`. Thứ hai, văn bản hiển thị trên màn hình phải là nhãn nghiệp vụ tiếng Việt: mockup và đặc tả không được in mã SCR, QT, BR, MSG, mã trạng thái thô hay tên trường lên giao diện. Quy định chi tiết và bảng từ thay thế nằm ở trang wiki 16; ví dụ nhiệm vụ có nguồn `DOT_XUAT` hiển thị là "Phát sinh", mức dự báo trễ hạn `VUA` hiển thị là "Trung bình".

## Mã định danh bổ sung {-}

Đợt triển khai cấp thêm một số mã trong dải của TL-02a cho chức năng quản trị vai trò và uỷ quyền (WEB-058, CORE-08-16). Các mã này không thay nghĩa mã cũ.

| Loại | Mã mới | Nội dung |
|---|---|---|
| Màn hình | SCR-0105, SCR-0106, SCR-0107 | Vai trò theo đơn vị (cấp, duyệt thứ hai, thu hồi); hồ sơ quyền của một người; uỷ quyền khi vắng mặt |
| Quy tắc | BR-01-13 đến BR-01-18 | Ràng buộc cấp, duyệt thứ hai, xác thực bước cao (BR-01-15), thu hồi và uỷ quyền; nội dung đầy đủ ở `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md` và trang wiki 15a |
| Thông báo | MSG-01-16 đến MSG-01-18 | Thông điệp của ba màn hình trên, theo cùng tài liệu đề xuất |
| Sự kiện | EVT-27 `VaiTroDaThayDoi` | Phát khi cấp, duyệt, từ chối, thu hồi, hết hạn vai trò và uỷ quyền |
| Màn hình (BS-01) | SCR-1505, SCR-1506 | Thư viện, trình soạn mẫu luồng và giám sát tiến trình theo mẫu (nội bộ nền tảng), đặc tả ở TL-01 Phụ lục E |

## Phần A: QT-00 đến QT-08 {-}

Bảng dưới đây liệt kê các điểm khác thiết kế của phần A. Điểm quan trọng nhất là saga khoá kỳ: TL-01 chương G2 liệt kê năm bước (khoá kỳ, tính điểm hàng loạt, sinh Mẫu 1A đến 3, ký lô, mở khoá có điều kiện), còn TL-02a QT-08 đặc tả sáu bước có bước kiểm tra tiền điều kiện ở đầu và bước hoàn tất ở cuối. Mã MOD-08 (`LockStep`) và màn hình SCR-0801 theo đúng sáu bước của TL-02a, và TL-01 đã được sửa cho khớp.

| Quy trình, màn hình | TL-02 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| QT-00, SCR-0001 | Xác thực bước cao khi thao tác nhạy cảm | Hiệu lực 15 phút sau lần xác thực `aal2` (BR-00-02); thiếu thì trả mã yêu cầu xác thực bước cao để giao diện mở lại hộp xác thực | Theo mã |
| QT-01, bước 4 | Saga cấp phát do SVC-03 khởi tạo, SVC-06 điều phối | Saga cấp phát chạy ngay trong tenant-registry (SVC-03), trạng thái lưu ở `registry.job`; sự kiện `sys.tenant.provisioned.v1` phát khi tenant chuyển sang đang cấu hình | Theo mã, chờ xác nhận |
| QT-01, bước 7 | SVC-13 đồng bộ SCIM 2.0 từ IdP | SCIM 2.0 đặt ở org-service (SVC-04); SVC-13 chỉ đồng bộ CSDL CBCCVC, CSDL đảng viên và tệp lô qua cổng nội bộ của org-service | Đã chốt 22/10/2026 |
| QT-01, SCR-0101 | Ngưỡng mô hình bridge từ 5.000 tới 1.000.000 tài khoản | Giữ ngưỡng của TL-02a (tham số `TENANT_BRIDGE_MAX`, mặc định 1.000.000), khác con số 150.000 ở TL-01 | Theo mã, chờ xác nhận |
| QT-01, SCR-0104 | Bốn cách xử lý lệch dữ liệu | Hợp đồng có năm hành động gợi ý và bốn cách xử lý; giao diện hiện đủ năm | Theo mã |
| QT-02, SCR-0203 | Duyệt bộ mục tiêu một lần | Duyệt hàng loạt chạy nền (202), kết quả từng mục tiêu trả về trong danh sách lỗi của tác vụ | Theo mã |
| QT-02, SCR-0201 | KR nháp đủ chỉ tiêu và hạn | KR ở trạng thái nháp cho phép thiếu chỉ tiêu và hạn; kiểm đủ khi trình duyệt | Theo mã |
| QT-02 | Tầng OKR (cá nhân, đơn vị, cấp trên) | Chưa chốt cách phân tầng hiển thị | Câu hỏi mở |
| QT-04, BR-04-01 | Hạn duyệt kế hoạch quý lưu ở cấu hình kỳ | Tính khi đọc theo quy tắc `NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2` của Policy Pack; dấu "duyệt muộn" được tính, không lưu cột; quá hạn không chặn duyệt | Theo mã, chờ xác nhận |
| QT-04, gửi duyệt | Tiến trình `duyet-ke-hoach` | Tiến trình tên `duyet-ke-hoach-quy` (theo SCR-1504 và hợp đồng), khởi tạo ngay khi gửi duyệt, không đợi EVT-03; nội dung phiên bản chờ duyệt được khoá bằng trạng thái và mã băm SHA-256 | Theo mã, chờ xác nhận |
| QT-04, dòng kế hoạch | Có tên sản phẩm | Thêm cột `plan_item.product_name` vào TL-03 để lưu `productName` của hợp đồng | Đã chốt 23/10/2026, chờ hiện thực |
| QT-05, SCR-0503 | Năm loại đề nghị điều chỉnh | Giữ năm loại `GIA_HAN`, `DOI_SO_LUONG`, `DOI_SAN_PHAM`, `TAM_DUNG`, `HUY`; đề nghị không có bản nháp, gửi là chờ duyệt; mỗi quyết định bắt buộc ghi nguyên nhân và lý do | Theo mã |
| QT-05, giao lại | Đề nghị đổi người chủ trì | Thao tác giao lại trực tiếp của người giao, có lưu vết; consumer EVT-15 đánh dấu nhiệm vụ cần giao lại khi người chủ trì chuyển công tác | Theo mã, chờ xác nhận |
| QT-06, SCR-0601 | Dự báo nguy cơ trễ hạn | Nhịp thực hiện lấy theo 5 ngày làm việc gần nhất; ngưỡng Thấp khi n/m ≤ 0,8, Trung bình khi đến 1,0, Cao khi vượt 1,0 (suy luận, tham số dịch vụ); kết quả ghi qua cổng `delay-risk` của MOD-02 | Theo mã, chờ xác nhận |
| QT-07, SCR-0701 | Một tệp mỗi phiên bản minh chứng | Tối đa 20 tệp mỗi phiên bản; tệp trùng mã băm với minh chứng khác được đánh dấu nghi trùng; tệp bị cách ly do mã độc không cho tải | Theo mã |
| QT-07, SCR-0703 | Nghiệm thu thay | Đã hiện thực theo uỷ quyền; thêm cột `acceptance.on_behalf_of`, `delegation_id` để màn hình và báo cáo hiện nhãn "nghiệm thu thay" | Đã chốt 23/10/2026, chờ hiện thực |
| QT-08, bước 1 | Chuyển kỳ sang `DANG_KHOA` và phát EVT-09 | EVT-09 "Kỳ đã khoá" phát khi kỳ đã `DA_KHOA` (bước hoàn tất), đúng nghĩa tên sự kiện; việc chuyển `MO` sang `DANG_KHOA` được báo bằng dòng outbox kỹ thuật `period.drain.marker` mà TL-03 đã định nghĩa cho bước khoá của saga, không đặt mã EVT mới; các module chặn ghi qua bảng chiếu trạng thái kỳ; EVT-26 phát khi bù trừ `DANG_KHOA` về `MO` | Đã chốt 23/10/2026, chờ hiện thực (dấu chặn luồng) |
| QT-08, SCR-0801 | Saga sáu bước, hai người duyệt khi huỷ | Đúng sáu bước của TL-02a; huỷ được trước khi bước ký số lô xong; người duyệt phải khác người lập, người khởi chạy phải khác người duyệt; lệnh khoá có thời điểm dự kiến | Đã chốt (theo TL-02a) |

## Phần B: QT-09 đến QT-18 {-}

Phần B có nhiều điểm suy luận ở các luật cảnh báo bất thường (QT-14), vì TL-02b cho một phần số ngưỡng và TL-01 nêu ví dụ ngưỡng khác. Job phát hiện bất thường theo TL-02b, các ngưỡng còn thiếu số được đặt mặc định và tenant ghi đè được. Các con số dưới đây cần nghiệp vụ xác nhận trước khi đưa vào vận hành thật.

| Quy trình, màn hình | TL-02 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| QT-09, SCR-0901 | Tự đánh giá | Nộp tự đánh giá có ký số; hồ sơ không tự đánh giá đúng hạn được đánh dấu thiếu | Theo mã |
| QT-09, QT-10 | Mức xếp loại theo bảng | "Hoàn thành xuất sắc nhiệm vụ" bao hàm điều kiện của mức thấp hơn; khi người đề xuất chọn mức khác mức gợi ý phải nêu lý do (DC-05) | Đã chốt 22/10/2026 |
| QT-09, EVT-10 | Sự kiện mang luồng, câu giải thích | Lược đồ Avro chưa có các trường này; giá trị nằm trong kết quả tính, MOD-04 đọc qua mã kết quả | Câu hỏi mở (hợp đồng) |
| QT-10, SCR-0904 | Ký lô quyết định | Ký lô có ngưỡng lỗi 0,2 và huỷ lô khi lỗi một phần; số quyết định duy nhất theo năm và tenant | Theo mã |
| QT-11, SCR-1102 | Kết thúc hội nghị phát EVT-11 | Chưa phát EVT-11 vì sự kiện thuộc MOD-04; góp ý "hạn chế" tự sinh mục cần khắc phục | Câu hỏi mở |
| QT-12, SCR-1201 | Khiếu nại gửi một lần | Cho lưu nháp (cần bỏ ràng buộc NOT NULL của TL-03); mã khiếu nại dạng KN-năm-số; hạn khiếu nại 10 ngày làm việc từ khi quyết định được ký | Theo mã, chờ xác nhận |
| QT-13 | Hai người duyệt mở lại kỳ | Chọn hai người duyệt bằng Java, không bao giờ hạ xuống một người duyệt; mở lại hết hạn thì kỳ tự khoá lại | Đã chốt bởi luồng điều phối |
| QT-14, luật 1 đến 8 | Ngưỡng ở TL-02b, ví dụ khác ở TL-01 | Theo TL-02b; ngưỡng thiếu số là giả định; dấu hiệu 8 gộp với BR-06-12 thành một luật miễn trừ lặp; cảnh báo tự đóng khi lượt sau không còn phát hiện | Theo mã, chờ xác nhận |
| QT-14, xử lý cảnh báo | Thông báo cảnh báo mức cao | Gom thông báo hằng ngày lúc 03:30, mỗi người xử lý một thông báo, không nêu tên người bị cảnh báo | Theo mã |
| QT-15, SCR-1504 | Tham số luồng phát hành cùng Policy Pack | MOD-05 công bố API, SVC-06 giữ dữ liệu; `wf_binding` thêm cột phiên bản Policy Pack vào khoá duy nhất | Đã chốt 23/10/2026, chờ hiện thực |
| QT-15 | Soạn gói cho từng tập khách hàng | Hồ sơ tập khách hàng (ADR-33); hồ sơ TWĐ chạy 1.441 ca vàng trên cùng bản build lõi với hai gói cũ | Đã chốt 22/10/2026 |
| QT-16, SCR-1601 | Đệm kết quả 5 phút | Đệm 60 giây theo TL-01, cộng làm mới pre-aggregation 5 phút, vẫn trong giới hạn BR-16-08; chỉ tiêu kinh tế xã hội có cờ công bố chưa có nguồn dữ liệu | Theo mã |
| QT-16, chia sẻ bảng điều hành | Thông báo cho người được chia sẻ | Chưa phát EVT-21 vì analytics-api không có danh bạ người dùng theo đơn vị | Câu hỏi mở |
| QT-17, SCR-1701 | Ba ca sử dụng AI | Thêm soạn OKR, soát KR, hỏi đáp quy định, giải thích điểm và nhập bằng giọng nói; đọc chữ minh chứng (OCR) lưu văn bản mã hoá C2 | Theo mã; nhà cung cấp nhận dạng tiếng nói là câu hỏi mở |
| QT-18, SCR-1801 | UI Pack | Có chế độ "Đảng viên cao tuổi" trong token; gửi duyệt chạy kiểm thử nền; quay lui chỉ về bản đã ký | Theo mã |
