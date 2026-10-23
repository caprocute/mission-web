# Hướng dẫn sử dụng Mission theo vai trò

<!-- VH-05 · YC-PT-11, NFR-11 · vai trò AC-01..AC-10 theo docs/phan-quyen/ma-tran.json (wiki/15a). Ảnh chụp tự động bằng frontend/e2e/huong-dan/chup-anh.spec.ts. -->

Bộ tài liệu này hướng dẫn cán bộ, công chức, viên chức và đảng viên sử dụng hệ thống Mission để giao nhiệm vụ, theo dõi mục tiêu và kết quả then chốt (OKR), lập kế hoạch công tác quý, nghiệm thu sản phẩm và đánh giá, xếp loại theo định kỳ. Mỗi vai trò có một tài liệu riêng, viết theo đúng trình tự công việc thường ngày của vai trò đó. Tài liệu chỉ mô tả những chức năng mà vai trò được phép dùng, nên đồng chí sẽ thấy trên màn hình đúng những mục được nhắc đến trong tài liệu của mình.

Trước khi đọc tài liệu của vai trò, đồng chí nên đọc phần [Thao tác dùng chung](00-thao-tac-dung-chung.md). Phần này hướng dẫn đăng nhập, chọn đơn vị và vai trò làm việc, xác thực nhiều lớp khi ký số, xem thông báo và bật chế độ Đảng viên cao tuổi. Đây là các thao tác mọi vai trò đều dùng.

## Chọn tài liệu theo vai trò

Một người có thể giữ nhiều vai trò cùng lúc. Ví dụ, một trưởng phòng vừa là cán bộ (có việc của mình) vừa là người quản lý trực tiếp (giao việc cho phòng). Khi đó, đồng chí đọc tài liệu của từng vai trò mình được giao.

| Vai trò | Ai thường giữ vai trò này | Tài liệu |
|---|---|---|
| Quản trị nền tảng | Đội ngũ vận hành nền tảng, phụ trách khởi tạo đơn vị triển khai và mẫu quy trình dùng chung | [Quản trị nền tảng](ac-01-quan-tri-nen-tang.md) |
| Quản trị đơn vị triển khai | Cán bộ quản trị hệ thống của tỉnh, bộ, ngành hoặc cơ quan | [Quản trị đơn vị](ac-02-quan-tri-don-vi.md) |
| Quản trị nghiệp vụ | Cán bộ cấu hình quy định đánh giá, công thức tính điểm, danh mục công việc mẫu | [Quản trị nghiệp vụ](ac-03-quan-tri-nghiep-vu.md) |
| Lãnh đạo, người đứng đầu | Bí thư, thủ trưởng, lãnh đạo đơn vị | [Lãnh đạo](ac-04-lanh-dao.md) |
| Cán bộ quản lý trực tiếp | Trưởng phòng, trưởng ban, người giao việc và nghiệm thu | [Quản lý trực tiếp](ac-05-quan-ly-truc-tiep.md) |
| Cán bộ, công chức, viên chức, đảng viên | Mọi người dùng có việc được giao và phải tự đánh giá định kỳ | [Cán bộ](ac-06-can-bo.md) |
| Cơ quan tổ chức cán bộ | Cán bộ thẩm định hồ sơ đánh giá, chuẩn bị hội nghị kiểm điểm | [Thẩm định](ac-07-tham-dinh.md) |
| Cấp có thẩm quyền quyết định | Người ký quyết định xếp loại | [Quyết định xếp loại](ac-08-quyet-dinh.md) |
| Người duyệt thứ hai | Người duyệt lần hai các thao tác cần hai người cùng đồng ý | [Người duyệt thứ hai](ac-09-duyet-thu-hai.md) |
| Cơ quan kiểm tra, giám sát | Cán bộ ủy ban kiểm tra, thanh tra, giám sát | [Kiểm tra, giám sát](ac-10-kiem-tra-giam-sat.md) |

## Về ảnh minh hoạ

Ảnh trong tài liệu được chụp tự động từ hệ thống bằng dữ liệu mẫu, nên tên người, tên đơn vị và số liệu trong ảnh chỉ để minh hoạ, không phải dữ liệu thật. Khi giao diện thay đổi, nhóm phát triển chụp lại toàn bộ ảnh để tài liệu luôn khớp với màn hình đồng chí đang dùng. Màu sắc, biểu trưng và bố cục trang chủ có thể khác đôi chút giữa các đơn vị, vì mỗi đơn vị được tuỳ chỉnh giao diện riêng.

## Bản in

Mỗi tài liệu có thể in ra giấy hoặc lưu thành tệp PDF. Nhóm vận hành dựng bản in bằng lệnh `python3 docs/huong-dan-su-dung/dung-ban-in.py`; các tệp PDF được đặt trong thư mục `ban-in`.

<!-- Ghi chú cho người bảo trì: chụp lại ảnh bằng `cd frontend && npx playwright test -c e2e/huong-dan` (giả lập) hoặc thêm `E2E_MODE=he-thong` để chụp trên server dev; kiểm văn bản bằng `python3 docs/huong-dan-su-dung/kiem-tra.py`. -->
