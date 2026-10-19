# WEB-014 · CSP chặt và Trusted Types cho bản phục vụ nginx

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-014` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-002 |
| Truy vết | TL-01 CSP, TL-04 SEC (an toàn ứng dụng web) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Áp CSP như TL-01 (không `unsafe-inline` cho script, không `unsafe-eval`, nonce theo request, Trusted Types) và chứng minh ứng dụng chạy không vi phạm.

## 2. Đầu vào bắt buộc đọc

- wiki/08-bao-mat-frontend.md

## 3. Phạm vi công việc

- nginx sinh nonce mỗi request (sub_filter hoặc njs) và gắn vào thẻ script/style; Angular `ngCspNonce`.
- Chính sách Trusted Types `angular`, `angular#bundler`, `vt-federation`.
- Header bảo mật khác (HSTS khi HTTPS, X-Content-Type-Options, Referrer-Policy, Permissions-Policy).

## 4. Ngoài phạm vi

- (không có)

## 5. Tệp được phép sửa

- deploy/web/**
- frontend/projects/fe-shell/src/index.html
- frontend/angular.json

## 6. Tiêu chí hoàn thành

- Playwright duyệt các màn hình hiện có, không có báo cáo vi phạm CSP.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-014 trong `docs/tasks/README.md`.
