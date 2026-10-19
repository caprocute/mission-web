# LEG-03 · SEC-02..SEC-05 Vá khẩn S01–S05 (tài khoản ẩn, tuyến công khai, cookie mật khẩu, ORDER BY)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0a – khắc phục khẩn (2 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | LEG-01 (S01 bí mật trong mã thuộc LEG-02) |
| Truy vết | S02, S03, S04, S05; SEC-02, SEC-03, SEC-04, SEC-05, SEC-41; TL-04 "Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05"; tiêu chí D3, D4, D7 |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Bốn lỗ hổng nghiêm trọng còn lại của nhóm S01–S05 trên hệ đang chạy bị đóng và không khai thác được khi kiểm thử lại: 9 tài khoản thử cấp token không cần mật khẩu bị xoá, tuyến chứa "/public" không còn bỏ qua JWT, mật khẩu không còn nằm trong cookie, và lỗi chèn SQL qua ORDER BY cùng các chỗ nối chuỗi SQL được sửa. Đây là điều kiện chuyển bước của đợt 0a ("pentest lại S01–S05 không khai thác được").

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, mục "Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05" (dòng 2443–2517: bảng SEC-01..SEC-08, WI sửa S03 với `PUBLIC_ENDPOINTS`, quy tắc Semgrep `vt-sql-string-concat`, map `SORTABLE`, kiểm thử lại SEC-41), "Danh mục SEC và ánh xạ trục D".
- Kết quả LEG-01 (vị trí S02–S05 đã xác minh); bản đánh giá `/mnt/project-files/kien-truc-okr-kpi/src/_inputs/danh-gia-kien-truc.txt`.
- Đầu vào người dùng cần cấp:
  - Quyền ghi các repo hệ cũ và môi trường build, kiểm thử của hệ cũ.
  - Quy trình và quyền phát hành bản vá khẩn lên production (người duyệt thay đổi, khung giờ).
  - Danh sách 9 tài khoản thử và xác nhận được phép vô hiệu; đầu mối buộc người dùng đổi mật khẩu.
  - Đội hoặc đơn vị kiểm thử xâm nhập độc lập cho bước kiểm thử lại.

## 3. Phạm vi công việc

- S02 (SEC-02): xoá nhánh mã cấp token không mật khẩu, vô hiệu 9 tài khoản và thu hồi token đã cấp; thêm quy tắc Semgrep chặn so sánh tên đăng nhập với hằng.
- S03 (SEC-03): thay so khớp chuỗi "/public" bằng danh sách `PUBLIC_ENDPOINTS` khớp chính xác `METHOD path` đã chuẩn hoá; mặc định từ chối; thêm kiểm thử với các biến thể đường dẫn.
- S04 (SEC-04): xoá mã ghi mật khẩu vào cookie JS, xoá cookie trên trình duyệt người dùng ở lần truy cập kế tiếp, buộc đổi mật khẩu cho tài khoản bị ảnh hưởng.
- S05 (SEC-05): thay ORDER BY nối chuỗi bằng map `SORTABLE` danh sách trắng; sửa các chỗ nối chuỗi SQL (TL-04 ước khoảng 150); thêm quy tắc Semgrep `vt-sql-string-concat` ở chế độ báo cáo cho mã cũ, chặn cho mã thay đổi.
- Mỗi bản vá có kiểm thử đơn vị hoặc tích hợp trong repo hệ cũ chứng minh lỗ hổng không còn.
- Phối hợp kiểm thử lại độc lập (SEC-41): ZAP 200 biến thể đường dẫn cho S03, sqlmap cho S05, kiểm tra cookie trên trình duyệt cho S04.
- Gợi ý tách: LEG-03a S02 và S04; LEG-03b S03; LEG-03c S05 và Semgrep.

## 4. Ngoài phạm vi

- Bí mật trong mã (LEG-02); CORS, cookie Secure/SameSite, token trong URL và nhật ký (LEG-04); APISIX làm điểm kiểm JWT duy nhất (LEG-06).

## 5. Tệp được phép sửa

- Mã nguồn, kiểm thử và cấu hình Semgrep trong các repo hệ cũ (đường dẫn do người dùng cấp), chỉ ở những chỗ liên quan S02–S05
- `ops/runbooks/va-khan-he-cu.md` trong repo này

## 6. Tiêu chí hoàn thành

- Kiểm thử của từng repo hệ cũ chạy xanh với các kiểm thử mới.
- Semgrep 0 phát hiện mới cho mã thay đổi; sqlmap 0; ZAP 0 biến thể vượt xác thực; không còn cookie chứa mật khẩu.
- Báo cáo kiểm thử lại độc lập ghi S02–S05 không khai thác được (hoặc ghi rõ việc còn lại).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
