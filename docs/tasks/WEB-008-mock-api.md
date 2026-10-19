# WEB-008 · Tách dữ liệu giả lập thành interceptor và fixture theo module

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-008` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-007 |
| Truy vết | Baseline mục 13 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bỏ việc rẽ nhánh `useMockApi` trong từng client; thay bằng `mockApiInterceptor` trả fixture JSON theo đường dẫn để component và client luôn đi đúng đường thật.

## 2. Đầu vào bắt buộc đọc

- wiki/06-quy-uoc-api.md
- wiki/10-quy-uoc-ma-nguon.md

## 3. Phạm vi công việc

- Fixture JSON theo module dưới `api-client/src/mock/<module>/`; hỗ trợ độ trễ, lỗi giả lập (409, 422, 500) bật qua query `?mock-error=`.
- Chuyển `TaskApi` hiện có sang cơ chế mới, giữ nguyên hành vi màn hình.

## 4. Ngoài phạm vi

- Mock cho WebSocket/SSE.

## 5. Tệp được phép sửa

- frontend/projects/api-client/**
- frontend/projects/fe-task/**

## 6. Tiêu chí hoàn thành

- Màn hình Việc của tôi và Chi tiết nhiệm vụ chạy như cũ với interceptor mới.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-008 trong `docs/tasks/README.md`.
