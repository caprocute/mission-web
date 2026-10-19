# WEB-051 · Plugin chuyên ngành mẫu và manifest theo tenant

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-051` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ4 – Điều hành, cấu hình, mở rộng |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-015 |
| Truy vết | ADR-05 lớp 4, baseline 5.2 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Làm plugin mẫu `fe-plugin-giaoduc` và cơ chế shell nạp plugin theo phần `plugins` của UI Pack, có kiểm chữ ký manifest.

## 2. Đầu vào bắt buộc đọc

- wiki/03-giao-dien-theo-tenant.md
- wiki/02-kien-truc-frontend.md
- wiki/08-bao-mat-frontend.md

## 3. Phạm vi công việc

- Plugin chỉ gọi API công bố, có tuyến riêng và một widget SDUI.
- Manifest plugin của tenant đã ký; chính sách Trusted Types `vt-federation` chỉ nhận URL trong manifest.

## 4. Ngoài phạm vi

- Plugin Y tế, LLVT.

## 5. Tệp được phép sửa

- frontend/projects/fe-plugin-giaoduc/**
- frontend/projects/fe-shell/**
- frontend/projects/sdui-renderer/**

## 6. Tiêu chí hoàn thành

- Tenant thử có plugin hiển thị thêm menu và widget; tenant khác không thấy.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-051 trong `docs/tasks/README.md`.
