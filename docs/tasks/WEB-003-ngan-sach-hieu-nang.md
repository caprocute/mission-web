# WEB-003 · Ngân sách bundle và Lighthouse CI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-003` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | NFR-03, TL-01 "Ngân sách hiệu năng" |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Biến ngân sách hiệu năng thành kiểm tra tự động làm build thất bại khi vượt.

## 2. Đầu vào bắt buộc đọc

- wiki/09-hieu-nang-va-trai-nghiem.md

## 3. Phạm vi công việc

- Đặt `budgets` trong angular.json cho fe-shell (initial ≤ 250 KB gzip ước lượng) và từng remote (≤ 180 KB).
- Cấu hình Lighthouse CI chạy trên bản build với giả lập 4G, ngưỡng LCP ≤ 2,5 s, CLS ≤ 0,1, accessibility ≥ 0,95.

## 4. Ngoài phạm vi

- Tối ưu sâu (WEB-052).

## 5. Tệp được phép sửa

- frontend/angular.json
- frontend/lighthouserc.json
- frontend/package.json

## 6. Tiêu chí hoàn thành

- Chạy thử Lighthouse CI cho trang chủ và "Việc của tôi", lưu kết quả vào báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-003 trong `docs/tasks/README.md`.
