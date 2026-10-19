# WEB-050 · SCR-1801 Cấu hình giao diện tenant và SCR-1802 Trình dựng biểu mẫu động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-050` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ4 – Điều hành, cấu hình, mở rộng |
| Cỡ việc | L (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-004, WEB-009, WEB-010 |
| Truy vết | QT-18 → SCR-1801 → `/api/v1/ui`, `/api/v1/config` → MOD-08 → EVT-23 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực màn hình SCR-1801 "Cấu hình giao diện tenant và SCR-1802 Trình dựng biểu mẫu động" trong remote `fe-config-studio` đúng đặc tả TL-02, dùng dữ liệu giả lập qua `@vt/api-client` khi backend chưa sẵn sàng, sẵn sàng chuyển sang API thật chỉ bằng cấu hình.

## 2. Đầu vào bắt buộc đọc

- wiki/05*-quy-trinh-va-man-hinh*.md (trang chứa QT tương ứng)
- wiki/04-ma-dinh-danh-va-truy-vet.md
- wiki/06-quy-uoc-api.md
- wiki/10-quy-uoc-ma-nguon.md
- wiki/03-giao-dien-theo-tenant.md
- TL-02 mục QT-18, đặc tả SCR-1801 (mockup, bảng trường, bảng chức năng, BR, MSG)

## 3. Phạm vi công việc

- Đọc đặc tả màn hình, liệt kê đủ trường, chức năng, quy tắc BR và thông báo MSG vào đầu tệp component dưới dạng chú thích ngắn để đối chiếu.
- Bổ sung model TypeScript và phương thức client cho `/api/v1/ui`, `/api/v1/config` trong `@vt/api-client` (kèm dữ liệu giả lập), tên trường theo bảng của TL-03.
- Soạn token (xem trước trực tiếp, kiểm tương phản), bố cục trang chủ SDUI, biểu mẫu động; phát hành UI Pack có phiên bản, ≤ 1 ngày (NFR-07).

## 4. Ngoài phạm vi

- SCR-1803 (di động).

## 5. Tệp được phép sửa

- frontend/projects/fe-config-studio/**
- frontend/projects/api-client/src/lib/** (thêm model và client của module)
- frontend/projects/fe-shell/src/app/app.routes.ts (chỉ khi cần gắn tuyến)

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-050 trong `docs/tasks/README.md`.
