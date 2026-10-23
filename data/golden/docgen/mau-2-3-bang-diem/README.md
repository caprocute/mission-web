# Dữ liệu vàng sinh Mẫu 2, Mẫu 3 và bảng chấm điểm (DV-11-03)

Thư mục này chứa dữ liệu giả lập để kiểm thử việc điền mẫu Word của document-service (SVC-11, MOD-06) cho Mẫu 2 (phiếu đánh giá, xếp loại cá nhân), Mẫu 3 (tổng hợp xếp loại của tập thể, bốn mức tự đề xuất, thẩm định, đề xuất, quyết định) và bảng chấm điểm kết quả thực hiện nhiệm vụ. Mẫu Word và manifest vùng trộn lấy từ `data/policy-pack/khoi-dang/quy-dinh-moi/templates/` (DL-05), nhãn dùng chung lấy từ `data/seed/docgen/nhan-dung-chung.json`; thư mục này chỉ thêm dữ liệu đầu vào và văn bản kỳ vọng.

Mỗi thư mục con gồm hai tệp. `input.json` là dữ liệu chuẩn hoá mà MOD-06 gửi cho document-service, khoá theo tag của manifest; ngày viết theo ISO 8601, số viết dạng thập phân, nhãn viết bằng mã (ví dụ `DAM_BAO`) để bộ sinh tra nhãn tiếng Việt từ bảng `nhan` của manifest. Các trường C2 không mã hoá trong tệp này; kiểm thử tự bọc chúng thành phong bì `{"c2": ...}` với AAD `[formInstanceId, tag]` (`GoldenForms.sealedInput`). `expected.txt` là các dòng phải xuất hiện theo đúng thứ tự trong văn bản trích từ PDF/A; khoảng trắng và xuống dòng trong ô bảng được gộp trước khi so.

Kiểm thử dùng dữ liệu này: `ManifestMergeTest` (điền DOCX, không cần Gotenberg) và `FormTemplatesIT` (Gotenberg, veraPDF, kho đối tượng). Khi sửa mẫu Word hoặc dữ liệu, chạy `FormTemplatesIT`, mở `target/golden-pdf/<MÃ_MẪU>.txt` và cập nhật `expected.txt` cho khớp. Toàn bộ họ tên và đơn vị là giả lập.

Bảng chấm điểm mang `policy_version` phải trùng phiên bản Policy Pack của kỳ (bộ sinh từ chối nếu khác). Mẫu 3 được bộ sinh sắp lại theo thứ tự đơn vị, mức xếp loại rồi họ tên và đánh lại số thứ tự, nên thứ tự hàng trong `input.json` cố ý xáo trộn. Kiểm thử 2.000 hàng của Mẫu 3 sinh dữ liệu ngay trong `FormTemplatesIT`, không lưu tệp.
