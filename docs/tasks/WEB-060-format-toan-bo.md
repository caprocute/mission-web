# WEB-060 · Chạy Prettier toàn workspace một lần

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-060` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S |
| Phụ thuộc | WEB-001 |
| Truy vết | NFR-11, WCAG 2.1 AA, AGENTS.md quy tắc 9 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đưa toàn bộ mã cũ về đúng Prettier trong một commit riêng để `format:check` xanh và các diff sau không lẫn thay đổi định dạng.

## 2. Đầu vào bắt buộc đọc

- wiki/09-hieu-nang-va-trai-nghiem.md
- wiki/03-giao-dien-theo-tenant.md

## 3. Phạm vi công việc

- Chạy `npm run format` một lần, commit riêng chỉ chứa thay đổi định dạng.
- Thêm `format:check` vào pipeline (ghi chú cho WEB-002).

## 4. Ngoài phạm vi

- Tính năng mới.

## 5. Tệp được phép sửa

- frontend/**

## 6. Tiêu chí hoàn thành

- `npm run format:check`, lint, test, build đều xanh.
- Không có thay đổi logic nào trong commit.

## 7. Báo cáo

Theo AGENTS.md mục 6.
