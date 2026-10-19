# WEB-023 · SCR-0203 Phê duyệt OKR

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-023` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ2 – Lõi nghiệp vụ (đợt 2 TL-04) |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-022 |
| Truy vết | QT-02 → SCR-0203 → `/api/v1/okr` + user task SVC-06 → MOD-01, SVC-06 → EVT-01 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực màn hình SCR-0203 "Phê duyệt OKR" trong remote `fe-okr` đúng đặc tả TL-02, dùng dữ liệu giả lập qua `@vt/api-client` khi backend chưa sẵn sàng, sẵn sàng chuyển sang API thật chỉ bằng cấu hình.

## 2. Đầu vào bắt buộc đọc

- wiki/05*-quy-trinh-va-man-hinh*.md (trang chứa QT tương ứng)
- wiki/04-ma-dinh-danh-va-truy-vet.md
- wiki/06-quy-uoc-api.md
- wiki/10-quy-uoc-ma-nguon.md
- TL-02 mục QT-02, đặc tả SCR-0203 (mockup, bảng trường, bảng chức năng, BR, MSG)

## 3. Phạm vi công việc

- Đọc đặc tả màn hình, liệt kê đủ trường, chức năng, quy tắc BR và thông báo MSG vào đầu tệp component dưới dạng chú thích ngắn để đối chiếu.
- Bổ sung model TypeScript và phương thức client cho `/api/v1/okr` + user task SVC-06 trong `@vt/api-client` (kèm dữ liệu giả lập), tên trường theo bảng của TL-03.
- Hộp việc duyệt, duyệt/trả lại có lý do, tách vai trò người lập ≠ người duyệt.

## 4. Ngoài phạm vi

- Mọi logic nghiệp vụ phía máy chủ (mission-core, workflow-service).
- Phiên bản di động Flutter (SVC-18).

## 5. Tệp được phép sửa

- frontend/projects/fe-okr/**
- frontend/projects/api-client/src/lib/** (thêm model và client của module)
- frontend/projects/fe-shell/src/app/app.routes.ts (chỉ khi cần gắn tuyến)

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-023 trong `docs/tasks/README.md`.
