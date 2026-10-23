> Nguồn: Baseline mục 1 (phạm vi, căn cứ), mục 2 (mô hình cell, tenant), mục 4 (ADR), mục 9 (trạng thái), mục 10 (AC, C0 đến C3), mục 13 (quy ước API); TL-01 mục frontend (SDUI, Native Federation, UI Pack, Policy Pack, Design Token); TL-02 (thuật ngữ nghiệp vụ QT-02 đến QT-11); TL-04 (cell, SLO, ngân sách lỗi).

# Thuật ngữ

Trang này giải thích các thuật ngữ thường gặp trong mã, tài liệu và task của phân hệ web. Thuật ngữ được nhóm theo chủ đề; trong mỗi nhóm, bảng nêu thuật ngữ và nghĩa dùng trong dự án, không phải định nghĩa chung của ngành. Khi một thuật ngữ có mã hoặc giá trị cố định trong nguồn, bảng ghi đúng giá trị đó để tra cứu.

## Kiến trúc nền tảng

Các thuật ngữ dưới đây mô tả cách nền tảng được chia và vận hành. Chi tiết ở [Tổng quan hệ thống](01-tong-quan-he-thong.md).

| Thuật ngữ | Nghĩa trong dự án |
|---|---|
| Tenant | Một khách hàng sử dụng nền tảng (tỉnh, khối, cơ quan, doanh nghiệp), có cấu hình, dữ liệu và giao diện riêng |
| Cell | Một bản triển khai độc lập của toàn bộ hệ thống phục vụ một nhóm tenant, ví dụ CELL-HN, CELL-G4, CELL-SH; lỗi ở một cell không lan sang cell khác |
| Silo, bridge, pool | Ba mức cô lập tenant: silo là cell riêng; bridge là CSDL riêng mỗi tenant trong cụm chung; pool là bảng chung có `tenant_id` và bảo mật mức dòng (RLS) |
| Vùng điều phối toàn cục | Phần dùng chung đứng trên các cell (global control plane): tenant-registry (SVC-03), bộ định tuyến toàn cục, kho Policy Pack và UI Pack gốc, CDN tài nguyên tĩnh, kho phân tích quốc gia chỉ nhận số liệu tổng hợp |
| Modulith | Kiểu kiến trúc của mission-core (ADR-01): một ứng dụng triển khai, chia module rõ ranh giới bằng Spring Modulith |
| Saga | Chuỗi bước nghiệp vụ dài có bước bù trừ khi lỗi, ví dụ khoá kỳ và ký lô (ADR-04, tầng 3) |
| Outbox | Bảng ghi sự kiện cùng giao dịch với dữ liệu, sau đó Debezium đẩy sang Kafka; bảo đảm không mất sự kiện |
| Strangler | Cách thay dần hệ cũ (ADR-20): APISIX đứng trước, chuyển từng chức năng sang hệ mới |
| SLO, ngân sách lỗi | Mục tiêu cấp dịch vụ và phần sai lệch được phép trong kỳ; tiêu quá 50% trước ngày 15 thì dừng phát hành tính năng (OPS-19) |

## Giao diện và frontend

Các thuật ngữ này xuất hiện nhiều nhất trong mã `frontend/`. Chi tiết ở [Kiến trúc frontend](02-kien-truc-frontend.md) và [Giao diện theo tenant](03-giao-dien-theo-tenant.md).

| Thuật ngữ | Nghĩa trong dự án |
|---|---|
| Shell (host) | Ứng dụng `fe-shell`: khung trang, đăng nhập, menu, nạp UI Pack và các remote |
| Remote | Ứng dụng con nạp lúc chạy vào shell, ví dụ `fe-task`; mỗi remote phụ trách một nhóm QT |
| Native Federation | Cơ chế nạp remote dựa trên ES module và import map (ADR-10), thay cho Module Federation của webpack |
| SDUI (Server-Driven UI) | Giao diện dựng từ cấu trúc dữ liệu do máy chủ gửi (bố cục, widget, thuộc tính), lớp hai của ADR-05 |
| Registry (component registry) | Danh mục component SDUI được phép dùng, có tên, lược đồ thuộc tính và phiên bản; UI Pack chỉ dùng được component trong registry |
| Widget | Component SDUI đặt lên trang qua tên `vt.widget.*`, ví dụ `vt.widget.myTasksToday` |
| Design Token | Biến thiết kế có tên (màu, phông, bo góc, khoảng cách), biên dịch thành biến CSS `--vt-*`; lớp một của ADR-05 |
| UI Pack | Gói giao diện của tenant gồm token, bố cục, biểu mẫu, plugin, bản dịch; có phiên bản, được ký, phát hành không cần build lại mã |
| Policy Pack | Gói chính sách nghiệp vụ của tenant (công thức, bảng quyết định, luồng duyệt, luật OPA); UI Pack khai dải Policy Pack tương thích (ADR-25) |
| Plugin | Remote do tenant hoặc bên thứ ba cung cấp, khai trong UI Pack; lớp bốn của ADR-05 |
| Chế độ người cao tuổi | Chế độ hiển thị `data-mode="elderly"` (NFR-11): chữ lớn, tương phản cao, ít bước, vùng chạm lớn |
| CEL | Common Expression Language, ngôn ngữ biểu thức dùng cho điều kiện hiển thị và luật biểu mẫu; `cel-js` ở trình duyệt, `cel-java` ở máy chủ |
| DMN | Bảng quyết định theo chuẩn Decision Model and Notation, dùng trong Policy Pack (ADR-19) |
| RUM | Đo lường người dùng thật trên trình duyệt (Real User Monitoring), ở dự án là Grafana Faro |
| LCP, INP, CLS | Ba chỉ số Web Vitals: thời gian hiện nội dung lớn nhất, độ trễ phản hồi tương tác, độ dịch chuyển bố cục |

## API, bảo mật và dữ liệu

Chi tiết ở [Quy ước API](06-quy-uoc-api.md), [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md) và [Bảo mật frontend](08-bao-mat-frontend.md).

| Thuật ngữ | Nghĩa trong dự án |
|---|---|
| Idempotency-Key | Header khoá duy nhất cho mỗi thao tác ghi, giúp gửi lại an toàn mà không tạo bản ghi trùng |
| ETag, If-Match | Phiên bản của tài nguyên và điều kiện cập nhật; lệch phiên bản thì máy chủ từ chối với mã xung đột |
| RFC 9457 (Problem Details) | Định dạng JSON chuẩn cho lỗi API: `type`, `title`, `status`, `detail`, kèm `code`, `errors`, `traceId` |
| Phân trang con trỏ | Phân trang bằng `cursor` do máy chủ trả (`nextCursor`), `limit` tối đa 200 |
| 202 Accepted | Phản hồi cho tác vụ dài, kèm `Location` tới `/api/v1/jobs/{id}` để theo dõi |
| SSE | Server-Sent Events, kênh đẩy một chiều từ máy chủ dùng cho thông báo và tiến độ |
| OIDC, PKCE | Giao thức đăng nhập OpenID Connect và cơ chế chống đánh cắp mã ủy quyền cho client công khai |
| AAL1, AAL2 | Mức bảo đảm xác thực: một yếu tố, hoặc nhiều yếu tố (MFA) |
| Step-up | Xác thực bước lên: yêu cầu MFA lại ngay trước thao tác nhạy cảm (RFC 9470) |
| RBAC, ABAC | Phân quyền theo vai trò và theo thuộc tính (đơn vị, luồng, lớp dữ liệu) |
| OPA | Open Policy Agent, bộ đánh giá chính sách phân quyền chạy cùng pod với service (ADR-21) |
| C0, C1, C2, C3 | Bốn lớp dữ liệu: công khai, nội bộ, nhạy cảm, mật |
| E2EE | Mã hoá đầu cuối cho dữ liệu C3, giải mã chỉ trong trình duyệt người được phép (ADR-27) |
| CSP, Trusted Types | Chính sách bảo mật nội dung và cơ chế trình duyệt chặn gán chuỗi không tin cậy vào DOM |
| AC-01 đến AC-12 | Mã tác nhân; mỗi tác nhân ứng với một vai trò, ví dụ AC-06 là `member` (bảng đầy đủ ở [trang 04](04-ma-dinh-danh-va-truy-vet.md)) |

## Nghiệp vụ đánh giá

Các thuật ngữ nghiệp vụ dùng trong TL-02 và giao diện. Chi tiết quy trình ở [trang 05a](05a-quy-trinh-va-man-hinh-qt00-08.md) và [trang 05b](05b-quy-trinh-va-man-hinh-qt09-18.md).

| Thuật ngữ | Nghĩa trong dự án |
|---|---|
| OKR, O, KR | Mục tiêu và kết quả then chốt; O là mục tiêu, KR là kết quả then chốt đo được của O |
| KPI | Chỉ số đo hiệu quả công việc, dùng trong tính điểm nhiệm vụ |
| Check-in | Cập nhật định kỳ tiến độ của KR (QT-03) |
| Kế hoạch công tác | Danh sách công việc của cá nhân hoặc đơn vị trong quý, được phê duyệt (QT-04) |
| Nhiệm vụ | Công việc được giao cho người chủ trì (`CHU_TRI`) và người phối hợp (`PHOI_HOP`), có hạn và tiến độ (QT-05, QT-06) |
| Minh chứng | Tệp hoặc dữ liệu chứng minh kết quả nhiệm vụ, lưu không sửa được (QT-07) |
| Nghiệm thu | Người quản lý xác nhận chất lượng kết quả nhiệm vụ theo 5 mức (SCR-0703) |
| A, B, C (số lượng, chất lượng, tiến độ) | Ba thành phần của KPI nhiệm vụ (BR-09-01): A = min(số sản phẩm đã xác nhận / số lượng giao, 1), phần vượt ghi riêng thành tỷ lệ vượt (BR-09-04); B là trung bình mức chất lượng nghiệm thu của các sản phẩm, một trong năm mức 1; 0,75; 0,5; 0,25; 0 (BR-07-07); C = max(0, 1 − 0,05 × số ngày làm việc chậm do chủ quan). Chỉ sản phẩm có minh chứng `DA_XAC_NHAN` và không bị chặn do cảnh báo trùng được đếm (BR-07-05, BR-09-02) |
| KPI nhiệm vụ, KPI nhóm | KPI nhiệm vụ mặc định là (A + B + C) / 3; KPI nhóm là trung bình KPI các nhiệm vụ trong nhóm; điểm = Σ KPI nhóm × điểm tối đa nhóm, tối đa 100, làm tròn 1 chữ số nửa lên (BR-09-03). Công thức nằm trong Policy Pack, SVC-07 tính (DV-07-03) |
| Vết tính | Các bước tính điểm của một kết quả (`scoring.score_trace`, khoảng 40 bước): mã quy tắc, căn cứ, giá trị vào, giá trị ra; nguồn của ngăn "Vết tính" ở SCR-0901 và thẻ vết tính ở SCR-0903 |
| Kỳ, khoá kỳ | Kỳ đánh giá (tháng, quý, năm); khoá kỳ là saga tính điểm, sinh biểu mẫu, ký lô rồi niêm phong (QT-08) |
| Tự đánh giá, thẩm định, quyết định | Ba bước xếp loại: cá nhân tự đánh giá, cơ quan tổ chức thẩm định, cấp có thẩm quyền quyết định (QT-09) |
| HTXSNV, HTTNV, HTNV, KHTNV | Bốn mức xếp loại: hoàn thành xuất sắc nhiệm vụ, hoàn thành tốt nhiệm vụ, hoàn thành nhiệm vụ, không hoàn thành nhiệm vụ |
| Luồng `DANG`, `NHA_NUOC` | Hai luồng đánh giá song song: theo hệ thống Đảng và theo hệ thống chính quyền; một người có thể thuộc cả hai |
| Mẫu 1A đến 1D, 2, 3 | Các biểu mẫu hồ sơ do SVC-11 sinh, ví dụ Mẫu 1B là phiếu tự đánh giá xem trước ở SCR-0901, Mẫu 3 xuất khi quyết định hàng loạt ở SCR-0904 |
| Sổ cái | Nhật ký chỉ ghi thêm, có ký lô, lưu kết quả đã khoá để chống sửa đổi (ADR-31) |
| Mở lại kỳ | Thủ tục có người duyệt thứ hai để sửa kết quả sau khi khoá (QT-13) |
| Hồ sơ số | Gói PDF/A đã ký của kết quả đánh giá, dùng cho hội nghị kiểm điểm và lưu trữ (QT-11) |

## Liên quan

- [README của wiki](README.md)
- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
