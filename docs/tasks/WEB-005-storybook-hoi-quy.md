# WEB-005 · Storybook và kiểm thử hồi quy giao diện theo bộ token

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-005` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-004 |
| Truy vết | TL-01 "Kiểm thử hồi quy giao diện theo tenant" tầng 1 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng tầng 1 của chiến lược kiểm thử: mỗi component trong registry SDUI có story, được chụp ảnh với 4 bộ token và quét axe-core.

## 2. Đầu vào bắt buộc đọc

- wiki/11-kiem-thu-va-chat-luong.md
- wiki/03-giao-dien-theo-tenant.md

## 3. Phạm vi công việc

- Storybook cho Angular 21; story cho `vt.layout.grid`, 4 widget hiện có, `vt-form`.
- Playwright chụp ảnh từng story × 4 bộ token, so sánh ảnh chuẩn; chạy axe-core.

## 4. Ngoài phạm vi

- Tầng 3, 4 (smoke test UI Pack, hồi quy 20 pack) – để GĐ4.

## 5. Tệp được phép sửa

- frontend/.storybook/**
- frontend/projects/**/*.stories.ts
- frontend/e2e/**
- frontend/package.json

## 6. Tiêu chí hoàn thành

- `npm run test:visual` chạy xanh, ảnh chuẩn được commit.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-005 trong `docs/tasks/README.md`.
