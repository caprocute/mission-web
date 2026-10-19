# WEB-001 · Công cụ chất lượng mã: ESLint, Prettier, Vitest cho toàn workspace

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-001` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | không |
| Truy vết | NFR (chất lượng) → C9, J2 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Thiết lập bộ lệnh kiểm tra chất lượng thống nhất để mọi task sau đều chạy được một lệnh duy nhất trước khi báo xong.

## 2. Đầu vào bắt buộc đọc

- wiki/10-quy-uoc-ma-nguon.md
- wiki/11-kiem-thu-va-chat-luong.md

## 3. Phạm vi công việc

- Cài angular-eslint (bản khớp Angular 21) với quy tắc: cấm `innerHTML` binding, cấm `bypassSecurityTrust*`, bắt buộc standalone, ưu tiên `inject()`.
- Thêm script npm: `lint`, `format:check`, `test:all` (Vitest cho mọi project), `build:all` (fe-task rồi fe-shell).
- Viết ít nhất một test mẫu cho `@vt/design-system` (`flattenTokens`, `tokensToCss`) và `@vt/form-engine` (`evaluateRules`).

## 4. Ngoài phạm vi

- Pipeline Jenkins (WEB-002).

## 5. Tệp được phép sửa

- frontend/package.json
- frontend/angular.json
- frontend/eslint.config.js
- frontend/.prettierrc
- frontend/projects/*/tsconfig.spec.json

## 6. Tiêu chí hoàn thành

- `npm run lint && npm run test:all && npm run build:all` chạy xanh trên workspace hiện tại.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-001 trong `docs/tasks/README.md`.
