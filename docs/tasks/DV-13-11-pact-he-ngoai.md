# DV-13-11 · SVC-13/J Kiểm thử hợp đồng Pact với từng hệ ngoài bằng đối tác giả lập

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (mở rộng dần theo từng bộ kết nối ở Đợt 2, 3) |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-03, QA-02 |
| Truy vết | Trục J (kiểm thử và chất lượng), L10 → SVC-13 → bộ kết nối vOffice, iOffice, Trục EdXML, NDXP, CSDL đảng viên, HRM, MySign/CSC, TSA |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lập bộ kiểm thử hợp đồng hướng người tiêu dùng (Pact) cho kỳ vọng của SVC-13 với từng hệ ngoài, công bố lên Pact Broker, có job định kỳ kiểm trên môi trường thử của đối tác; khi đối tác chưa hợp tác, kiểm ngược trên bản ghi tương tác thật đã che dữ liệu. Khi xong, mọi thay đổi giao diện phía đối tác hoặc phía translator bị phát hiện trước khi lên môi trường dùng chung.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1426, đoạn kiểm thử hợp đồng Pact), "Tháp kiểm thử" (L3124), "Đường ống CI/CD" (L3130).
- Thẻ QA-02 (Pact Broker), DV-13-03..08 (các bộ kết nối đã gộp).
- `docs/quy-dinh/BACKEND.md` mục 4.

## 3. Phạm vi công việc

- Viết hợp đồng Pact phía tiêu dùng cho vOffice (đã có giả lập từ DV-13-03) và khung chung để mỗi bộ kết nối thêm hợp đồng của mình: tương tác REST, tin nhắn (message pact) cho đầu vào đẩy.
- Công bố hợp đồng lên Pact Broker với phiên bản theo commit và nhánh; bước `can-i-deploy` trong pipeline chặn triển khai khi hợp đồng chưa được xác minh.
- Viết job định kỳ chạy xác minh trên môi trường thử của đối tác khi có (cấu hình theo đối tác, bí mật từ OpenBao); chưa có thì chạy xác minh trên bản ghi tương tác thật đã che dữ liệu, lưu ở `data/golden/integ-recordings/` (không có dữ liệu cá nhân thật).
- Viết công cụ che dữ liệu bản ghi tương tác (thay tên, số định danh, số điện thoại bằng giá trị giả nhất quán) và kiểm thử của chính công cụ.
- Ghi hướng dẫn thêm hợp đồng cho bộ kết nối mới vào wiki trang kiểm thử.

## 4. Ngoài phạm vi

- Dựng Pact Broker và tích hợp pipeline chung (QA-02, INF-05).
- Hợp đồng frontend với API (QA-02).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/src/test/**`
- `data/golden/integ-recordings/**`
- `wiki/11-kiem-thu-va-chat-luong.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử Pact phía tiêu dùng cho vOffice và ít nhất một bộ kết nối khác đã gộp.
- Hợp đồng xuất hiện trên Pact Broker (hoặc tệp hợp đồng lưu trong repo nếu Broker chưa sẵn, ghi rõ); thay đổi giả định trường bắt buộc làm xác minh thất bại đúng như mong đợi.
- Bản ghi tương tác trong repo qua kiểm tra quét dữ liệu cá nhân (gitleaks và mẫu regex CCCD, số điện thoại) không phát hiện gì.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 11 được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
