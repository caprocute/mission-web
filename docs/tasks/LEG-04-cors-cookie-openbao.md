# LEG-04 · SEC-06..SEC-08, SEC-10 Vá S07, S08, S11 (CORS, cookie Secure/SameSite, token khỏi URL và log) và OpenBao tối thiểu cho hệ cũ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0a – khắc phục khẩn (2 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | LEG-03, INF-13 (xem điểm lệch ở mục 3) |
| Truy vết | S07, S08, S11; SEC-06, SEC-07, SEC-08, SEC-10; TL-04 "Khắc phục khẩn…", "Đợt 0a"; tiêu chí D1, D5, D7, D9 |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Hệ hiện hành không còn cho mọi origin gọi kèm thông tin xác thực, cookie có `Secure` và `SameSite`, JWT không còn đi trong URL hay nằm trong nhật ký, khoá ký JWT đã xoay, và bí mật của hệ cũ được đọc từ OpenBao qua External Secrets thay vì tệp cấu hình. Cùng với LEG-02, LEG-03, task này đóng đủ tám lỗ hổng của đợt 0a.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05" (bảng SEC-06..SEC-08), mục "Quản lý bí mật và khoá: OpenBao, ESO, HSM" (dòng 2866, SEC-10), "Đợt 0a" (OpenBao tối thiểu 3 node khoá tạm và ESO).
- Kết quả LEG-01, LEG-02 (danh sách bí mật đã chuyển ra biến môi trường).
- Đầu vào người dùng cần cấp:
  - Quyền ghi các repo hệ cũ và quyền triển khai cấu hình lên môi trường đang chạy.
  - Danh sách origin hợp lệ của từng tenant đang dùng hệ cũ.
  - Nơi chạy OpenBao tối thiểu cho hệ cũ (cụm hoặc máy được cấp) và người giữ khoá mở niêm phong tạm.
  - Quyền xoá nhật ký cũ có chứa JWT (LogCenter hoặc hệ lưu nhật ký hiện tại).

## 3. Phạm vi công việc

- S07 (SEC-06): thay CORS mở bằng danh sách origin theo tenant; thêm bảo vệ CSRF cho các form còn dùng cookie phiên.
- S11 (SEC-08): đặt `Secure`, `HttpOnly` (với cookie phiên), `SameSite` cho mọi cookie; bỏ `?token=` khỏi 5 URL đã xác minh ở LEG-01, chuyển sang header Bearer hoặc URL ký trước có hạn ngắn.
- S08 (SEC-07): xoá ghi JWT, refresh token và thân request trong `JwtTokenFilter`; thêm che dữ liệu nhạy cảm trong cấu hình Logback; xoá nhật ký cũ chứa token; xoay khoá ký JWT (phối hợp LEG-02).
- SEC-10 tối thiểu: dựng OpenBao 3 node với khoá mở niêm phong tạm và External Secrets Operator (hoặc cơ chế tương đương trên hạ tầng hiện có), chuyển bí mật của hệ cũ từ biến môi trường tạm sang OpenBao.
- Viết runbook cho OpenBao tối thiểu (khởi tạo, mở niêm phong, sao lưu, chuyển sang OpenBao 5 node của INF-13 sau này).
- Điểm lệch cần lưu ý: khung đặt phụ thuộc INF-13 (OpenBao 5 node tự mở khoá bằng HSM, thuộc đợt 1 theo TL-04), trong khi đợt 0a chỉ cần OpenBao tối thiểu; task này dựng bản tối thiểu để không chờ INF-13 và ghi điểm lệch vào wiki trang 14.

## 4. Ngoài phạm vi

- OpenBao 5 node có HSM, PKI nội bộ (INF-13); CORS ở APISIX cho nền mới (DV-01-01).

## 5. Tệp được phép sửa

- Mã nguồn và cấu hình (CORS, cookie, Logback, đọc bí mật) trong các repo hệ cũ (đường dẫn do người dùng cấp)
- `deploy/environments/legacy/**` (giá trị OpenBao tối thiểu, ExternalSecret cho hệ cũ)
- `ops/runbooks/openbao-toi-thieu-he-cu.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- Kiểm thử của các repo hệ cũ xanh; ZAP kiểm CORS và cookie không còn phát hiện mức cao.
- Tìm chuỗi `eyJ` trong nhật ký mới sau triển khai: 0 kết quả; tìm `token=` trong nhật ký truy cập: 0 kết quả.
- Bí mật của hệ cũ được nạp từ OpenBao; xoá biến môi trường tạm mà ứng dụng vẫn khởi động được.
- `helm lint`/`kubeconform` cho manifest mới (nếu triển khai trên K8s) không lỗi; báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
