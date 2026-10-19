# WEB-004 · Biên dịch Design Token và kiểm tra tương phản

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-004` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | ADR-05 lớp 1 → MOD-08 → EVT-23 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Xây công cụ Node biên dịch token W3C của UI Pack sang CSS custom properties và preset, kèm bộ kiểm tra WCAG, để MOD-08 dùng khi phát hành UI Pack và shell không phải tự biên dịch.

## 2. Đầu vào bắt buộc đọc

- wiki/03-giao-dien-theo-tenant.md
- wiki/09-hieu-nang-va-trai-nghiem.md
- TL-01 chương 7 "Pipeline token sang CSS variables và preset PrimeNG"

## 3. Phạm vi công việc

- Công cụ CLI `tools/token-compiler` (TypeScript, có thể dùng Style Dictionary) đọc pack.json, sinh `compiledCss`, kiểm tra tương phản chữ/nền ≥ 4,5:1 cho mọi cặp khai báo, báo lỗi nếu vượt 30 KB.
- Bốn bộ token mẫu: mặc định, tương phản cao, cao tuổi, thương hiệu tối; đưa vào `public/ui-packs/` dưới các tenant thử.
- Giữ `tokensToCss` phía trình duyệt làm phương án dự phòng, có test so khớp đầu ra với công cụ.

## 4. Ngoài phạm vi

- Giao diện soạn UI Pack (WEB-050).

## 5. Tệp được phép sửa

- frontend/projects/design-system/**
- frontend/tools/token-compiler/**
- frontend/projects/fe-shell/public/ui-packs/**

## 6. Tiêu chí hoàn thành

- Chạy công cụ trên 4 bộ token, cả 4 đạt kiểm tra; một bộ cố ý tương phản thấp bị từ chối.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-004 trong `docs/tasks/README.md`.
