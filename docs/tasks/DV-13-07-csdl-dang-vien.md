# DV-13-07 · SVC-13/QT-01 Bộ kết nối CSDL đảng viên

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-06 |
| Truy vết | QT-01 bước 7, QT-03 (BR-03-04 số liệu nguồn), QT-07 (đường dẫn hệ thống nguồn) → SVC-13 → bảng `integ.connector`, `integ.sync_cursor`, `integ.inbox_message`, `integ.mapping_xref` → EVT-15, EVT-16 (do SVC-04 phát) |
| Trạng thái | Bị chặn bởi bên ngoài: cần kết nối thử với hạ tầng CSDL đảng viên của khối Đảng |

## 1. Mục tiêu

Kéo dữ liệu đảng viên và tổ chức đảng từ CSDL đảng viên qua hạ tầng riêng của khối Đảng theo lịch, chuyển sang mô hình chuẩn và giao cho org-service như luồng CSDL CBCCVC, đồng thời cung cấp số liệu nguồn cho KR khi tenant cấu hình. Khi xong, tenant khối Đảng có danh sách đảng viên, chi bộ khớp với nguồn chính thức mà không nhập tay.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 bước 7 (L505–517), QT-03 (BR-03-04, nguồn ví dụ CSDL đảng viên), QT-07 bước 1 (L1884, đường dẫn hệ thống nguồn).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1409–1426, dòng CSDL đảng viên: hạ tầng riêng khối Đảng, vào, ra theo quy định, đợt 3), "Mô hình dữ liệu chuẩn theo tám nhóm PL 4" (L1461, mã ngoài đảng viên).
- Thẻ DV-13-06 và mã adapter, translator đã gộp.

## 3. Phạm vi công việc

- Viết adapter theo giao diện mà hạ tầng CSDL đảng viên cung cấp (cấu hình trong `connector`, giao thức lấy từ tài liệu đối tác), kéo theo lịch với `sync_cursor`.
- Viết translator đảng viên, tổ chức đảng sang mô hình chuẩn nhóm 1 với mã ngoài đảng viên trong `mapping_xref`; dữ liệu cá nhân xử lý như C2 theo BR-01-12.
- Giao dữ liệu cho org-service theo cùng cơ chế DV-13-06; tổ chức đảng ánh xạ vào cây tổ chức của tenant khối Đảng.
- Cung cấp số liệu nguồn cho KR (ví dụ tỷ lệ đảng viên được kết nạp) qua use case chung với DV-13-08 nếu tenant bật; lỗi nguồn thì trả lỗi để giao diện cho nhập tay (MSG-03-03).
- Chiều ra "theo quy định": chỉ hiện thực khi có văn bản xác định dữ liệu được gửi; trước đó để cờ tắt và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Khớp người và bảng lệch ở org-service (DV-04-07); giao diện đối soát (WEB-019).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử với nguồn giả lập: đảng viên mới, chuyển sinh hoạt, ra khỏi Đảng được chuyển thành lệnh đúng cho org-service, không trùng khi chạy lại.
- Không có số định danh dạng rõ trong thông điệp, bảng `integ` và nhật ký.
- Báo cáo ghi rõ đã hay chưa kết nối thử nguồn thật.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
