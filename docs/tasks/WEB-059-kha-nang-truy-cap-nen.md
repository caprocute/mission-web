# WEB-059 · Sửa lỗi khả năng truy cập nền

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-059` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S |
| Phụ thuộc | WEB-001 |
| Truy vết | NFR-11, WCAG 2.1 AA, AGENTS.md quy tắc 9 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Loại bỏ các lỗi axe-core mức critical/serious có sẵn từ trước để mọi task màn hình sau có nền sạch khi kiểm tra.

## 2. Đầu vào bắt buộc đọc

- wiki/09-hieu-nang-va-trai-nghiem.md
- wiki/03-giao-dien-theo-tenant.md

## 3. Phạm vi công việc

- `p-progressbar` gắn `aria-level` sai và thiếu tên: bọc hoặc truyền `aria-label`/`ariaValueText` đúng ở fe-task và widget OKR của shell.
- Tay kéo `p-slider` thiếu nhãn ở SCR-0601.
- Tương phản token: #ee0033 trên nền hồng/trắng và #64748b trên #f5f6f8 chưa đạt 4,5:1; chỉnh token trong UI Pack mặc định và preset, giữ nhận diện màu.
- CLS 0,199 ở trang chủ do `p-card` của widget: giữ chỗ chiều cao.

## 4. Ngoài phạm vi

- Tính năng mới.

## 5. Tệp được phép sửa

- frontend/projects/design-system/**
- frontend/projects/fe-shell/**
- frontend/projects/fe-task/**

## 6. Tiêu chí hoàn thành

- axe-core không còn lỗi critical/serious ở trang chủ, Việc của tôi, Chi tiết nhiệm vụ, cả chế độ thường và cao tuổi.
- Lighthouse accessibility ≥ 0,95 cho `/` và `/tasks`.
- Build, test, lint xanh.

## 7. Báo cáo

Theo AGENTS.md mục 6.
