# WEB-xxx · <Mã SCR/hạng mục> <Tên task>

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-xxx` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐx – … |
| Cỡ việc | S / M / L |
| Phụ thuộc | WEB-… |
| Truy vết | QT-.. → SCR-.. → `/api/v1/..` → MOD-../SVC-.. → EVT-.. |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu
Một đoạn nói rõ kết quả người dùng nhìn thấy.

## 2. Đầu vào bắt buộc đọc
- Trang wiki và mục TL cụ thể.

## 3. Phạm vi công việc
- Việc cụ thể, kiểm chứng được.

## 4. Ngoài phạm vi
- Việc không làm trong task này.

## 5. Tệp được phép sửa
- Đường dẫn hoặc glob.

## 6. Tiêu chí hoàn thành
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo
Theo AGENTS.md mục 6.
