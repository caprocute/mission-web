# WEB-010 · Form engine: widget, test CEL dùng chung, benchmark

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-010` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | ADR-05 lớp 3, ADR-19 |
| Trạng thái | Hoàn thành một phần: ngân sách dựng 50 trường chưa đạt, xem wiki/09 |

## 1. Mục tiêu

Đưa `@vt/form-engine` tới mức dùng được cho phiếu đánh giá Mẫu 1B.

## 2. Đầu vào bắt buộc đọc

- wiki/03-giao-dien-theo-tenant.md
- wiki/11-kiem-thu-va-chat-luong.md
- TL-01 "Biểu mẫu động: Formly, JSON Schema và CEL"

## 3. Phạm vi công việc

- Bản đồ widget: `vt.input.score`, `vt.input.textarea`, `vt.input.date`, `vt.input.catalog` (chọn từ danh mục), nhóm theo `ui.sections`.
- Định dạng test case vàng cho quy tắc CEL (đầu vào, kết quả mong đợi) dùng chung với cel-java; runner chạy trên cel-js trong Vitest.
- Hỗ trợ `serverOnly` và hiển thị lỗi máy chủ trả về theo trường.
- Benchmark dựng biểu mẫu 50 trường ≤ 50 ms, đánh giá toàn bộ quy tắc ≤ 5 ms.

## 4. Ngoài phạm vi

- Trình dựng biểu mẫu (WEB-050).

## 5. Tệp được phép sửa

- frontend/projects/form-engine/**

## 6. Tiêu chí hoàn thành

- Mẫu 1B mẫu (schema + rules) dựng và kiểm tra đúng; benchmark đạt.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-010 trong `docs/tasks/README.md`.
