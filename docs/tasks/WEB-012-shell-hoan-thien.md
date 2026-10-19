# WEB-012 · Shell: thông báo thời gian thực, tìm kiếm, xử lý lỗi toàn cục

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-012` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-006, WEB-007 |
| Truy vết | QT-00 → SCR-0002 → `/api/v1/notifications` (SVC-12), SVC-16 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện khung ứng dụng theo SCR-0002: chuông thông báo (SSE từ SVC-12), ô tìm kiếm toàn cục (SVC-16), trang lỗi, banner mất mạng.

## 2. Đầu vào bắt buộc đọc

- wiki/02-kien-truc-frontend.md
- wiki/05*-quy-trinh-va-man-hinh*.md (trang chứa QT tương ứng)
- TL-02 QT-00 SCR-0002

## 3. Phạm vi công việc

- Chuông thông báo đếm chưa đọc, danh sách, đánh dấu đã đọc; kết nối SSE có tự nối lại.
- Tìm kiếm toàn cục gọi `/api/v1/...search` (giả lập), điều hướng tới đối tượng.
- ErrorHandler toàn cục gửi telemetry; trang lỗi thân thiện; banner offline.

## 4. Ngoài phạm vi

- Thông báo đẩy di động.

## 5. Tệp được phép sửa

- frontend/projects/fe-shell/**

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-012 trong `docs/tasks/README.md`.
