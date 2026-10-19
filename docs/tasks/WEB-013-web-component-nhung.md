# WEB-013 · Chế độ nhúng `<vt-okr-app>` cho cổng văn phòng số

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-013` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-006 |
| Truy vết | SVC-17 "Phục vụ và nhúng" |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đóng gói shell thành web component (Angular Elements) để nhúng vào vOffice/iOffice, đăng nhập một lần qua OIDC.

## 2. Đầu vào bắt buộc đọc

- wiki/02-kien-truc-frontend.md
- wiki/08-bao-mat-frontend.md

## 3. Phạm vi công việc

- Project `fe-embed` xuất custom element `vt-okr-app` với thuộc tính `tenant`, `route`.
- Kiểm tra `frame-ancestors`/CSP và SSO khi nhúng; trang demo nhúng.

## 4. Ngoài phạm vi

- Tích hợp phía vOffice.

## 5. Tệp được phép sửa

- frontend/projects/fe-embed/**
- frontend/angular.json
- deploy/web/**

## 6. Tiêu chí hoàn thành

- Trang HTML demo nhúng hiển thị "Việc của tôi" sau đăng nhập.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-013 trong `docs/tasks/README.md`.
