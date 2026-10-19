> Nguồn: `AGENTS.md` (mục 1, mục 3, mục 5); `CLAUDE.md`; `docs/tasks/README.md`; bộ tài liệu thiết kế baseline, TL-01, TL-02 (TL-02a, TL-02b), TL-03, TL-04 mà từng trang trích dẫn ở dòng "Nguồn" của trang đó.

# Wiki tri thức phân hệ web Mission

Wiki này gom tri thức cần thiết để phát triển phân hệ web của nền tảng Mission, nền tảng đánh giá cán bộ theo OKR và KPI phục vụ khoảng 6 triệu tài khoản với khoảng 300.000 người dùng đồng thời. Bộ tài liệu thiết kế (TL) rất dài và viết cho mọi phân hệ; wiki trích ra phần liên quan tới frontend, đối chiếu với mã hiện có và ghi rõ chỗ nguồn còn lệch nhau. Wiki không thay thế TL: mỗi trang mở đầu bằng dòng "Nguồn" chỉ mục TL cụ thể để tra lại khi cần.

## Cách dùng

Người mới vào dự án nên đọc theo thứ tự trang 01, 02, 04, 10, rồi đọc trang theo việc đang làm. AI agent làm task trong `docs/tasks/` đọc `AGENTS.md` trước (quy định bắt buộc, `CLAUDE.md` tự nạp cả `AGENTS.md` và trang này), sau đó đọc các trang wiki mà task liệt kê ở mục "Đầu vào bắt buộc đọc". Khi cần biết một màn hình làm gì, tra mã SCR trong trang 05a hoặc 05b; khi cần biết vai trò nào được thấy hay làm một chức năng, tra trang 15a; khi gặp từ lạ, tra trang 13; khi thấy hai nguồn nói khác nhau, xem trang 14 trước khi tự quyết định.

## Mục lục

| Trang | Nội dung |
|---|---|
| [01-tong-quan-he-thong.md](01-tong-quan-he-thong.md) | Phạm vi, quy mô, yêu cầu phi chức năng NFR, mô hình cell và tenant, các service |
| [02-kien-truc-frontend.md](02-kien-truc-frontend.md) | Shell, remote, Native Federation, thư viện `@vt/*`, luồng khởi động, ánh xạ remote với QT |
| [03-giao-dien-theo-tenant.md](03-giao-dien-theo-tenant.md) | Bốn lớp tuỳ biến của ADR-05: token, SDUI, biểu mẫu động, plugin; UI Pack và registry |
| [04-ma-dinh-danh-va-truy-vet.md](04-ma-dinh-danh-va-truy-vet.md) | Các loại mã định danh, tác nhân AC, sự kiện EVT, trạng thái, quy tắc truy vết trong mã và commit |
| [05a-quy-trinh-va-man-hinh-qt00-08.md](05a-quy-trinh-va-man-hinh-qt00-08.md) | Quy trình QT-00 đến QT-08 và các màn hình SCR tương ứng |
| [05b-quy-trinh-va-man-hinh-qt09-18.md](05b-quy-trinh-va-man-hinh-qt09-18.md) | Quy trình QT-09 đến QT-18 và các màn hình SCR tương ứng |
| [06-quy-uoc-api.md](06-quy-uoc-api.md) | Đường dẫn, idempotency, `If-Match`, phân trang con trỏ, lỗi RFC 9457, tác vụ 202, SSE |
| [07-xac-thuc-phan-quyen.md](07-xac-thuc-phan-quyen.md) | Keycloak, OIDC Code + PKCE, MFA, step-up, thời hạn token, RBAC và ABAC với OPA |
| [08-bao-mat-frontend.md](08-bao-mat-frontend.md) | CSP, Trusted Types, chống XSS, header HTTP, phân lớp dữ liệu, E2EE C3, bảng kiểm khi viết mã |
| [09-hieu-nang-va-trai-nghiem.md](09-hieu-nang-va-trai-nghiem.md) | SLO, ngân sách kích thước, đo lường người dùng thật, WCAG và chế độ người cao tuổi |
| [10-quy-uoc-ma-nguon.md](10-quy-uoc-ma-nguon.md) | Cấu trúc repo, Angular hiện đại, bẫy kỹ thuật, các bước thêm remote và widget SDUI |
| [11-kiem-thu-va-chat-luong.md](11-kiem-thu-va-chat-luong.md) | Các tầng kiểm thử, kiểm thử UI Pack, cổng chất lượng pipeline, tiêu chí hoàn thành task |
| [12-ha-tang-dev-va-trien-khai.md](12-ha-tang-dev-va-trien-khai.md) | Hạ tầng dev bằng Docker Compose, đóng gói nginx, khác biệt với production, GitOps, lộ trình |
| [13-thuat-ngu.md](13-thuat-ngu.md) | Thuật ngữ kiến trúc, frontend, API, bảo mật và nghiệp vụ đánh giá |
| [14-quyet-dinh-va-cau-hoi-mo.md](14-quyet-dinh-va-cau-hoi-mo.md) | ADR liên quan web, quyết định đã chốt, điểm lệch giữa nguồn, câu hỏi mở |
| [15-phan-quyen.md](15-phan-quyen.md) | Nguyên tắc phân quyền RBAC và ABAC, mô hình vai trò AC, phạm vi dữ liệu, mức dữ liệu, tách vai trò, bốn mắt, step-up, hiện thực từng tầng, cấp và thu hồi quyền |
| [15a-ma-tran-phan-quyen.md](15a-ma-tran-phan-quyen.md) | Ma trận phân quyền chức năng theo SCR × AC-01..AC-12, ma trận API, mức dữ liệu, ràng buộc; sinh từ `docs/phan-quyen/ma-tran.json` |
| [16-noi-dung-giao-dien.md](16-noi-dung-giao-dien.md) | Quy định nội dung hiển thị cho cán bộ, viên chức: không lộ mã thiết kế, trạng thái thô, tên kỹ thuật; bảng từ thay thế; hai lớp kiểm tra tự động |

## Quy tắc cập nhật wiki

Ai cũng được sửa wiki, kể cả AI agent, và việc sửa đi cùng PR của task tạo ra tri thức đó. Theo `AGENTS.md` mục 5, một task phải cập nhật wiki khi nó tạo ra tri thức mới: một quy ước, một bẫy kỹ thuật, một quyết định hay một câu trả lời cho câu hỏi mở; báo cáo cuối task ghi rõ trang nào đã sửa. Khi một câu hỏi ở trang 14 được trả lời, chuyển nó sang phần quyết định đã chốt và sửa các trang liên quan, không xoá dấu vết.

Khi sửa, giữ đúng mã định danh và tên trạng thái như nguồn (ADR, SVC, MOD, EVT, QT, SCR, BR, MSG, NFR, SEC, OPS, PERF, DR, tên schema, tên bảng), không đặt mã mới. Nội dung suy ra mà nguồn không ghi trực tiếp phải đánh dấu "(suy luận)". Mỗi trang giữ dòng "Nguồn" ở đầu và mục "Liên quan" ở cuối. Không ghi địa chỉ máy chủ, tên tài khoản, mật khẩu, token hay khoá vào wiki; các thông tin đó chỉ nằm trong tệp `.env` không commit. Khi wiki và TL lệch nhau, TL thắng, và người phát hiện sửa wiki cho khớp.

## Liên quan

- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
