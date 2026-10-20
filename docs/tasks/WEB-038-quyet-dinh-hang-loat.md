# WEB-038 · SCR-0904 Quyết định xếp loại hàng loạt và ký số

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-038` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ3 – Đánh giá, hồ sơ, giám sát |
| Cỡ việc | L (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-037 |
| Truy vết | QT-09 → SCR-0904 → `/api/v1/appraisals`, `/api/v1/esign` → MOD-04, SVC-10 → EVT-12, EVT-18 |
| Trạng thái | Xong, chờ gộp |

## 1. Mục tiêu

Hiện thực màn hình SCR-0904 "Quyết định xếp loại hàng loạt và ký số" trong remote `fe-appraisal` đúng đặc tả TL-02, dùng dữ liệu giả lập qua `@vt/api-client` khi backend chưa sẵn sàng, sẵn sàng chuyển sang API thật chỉ bằng cấu hình.

## 2. Đầu vào bắt buộc đọc

- wiki/05*-quy-trinh-va-man-hinh*.md (trang chứa QT tương ứng)
- wiki/04-ma-dinh-danh-va-truy-vet.md
- wiki/06-quy-uoc-api.md
- wiki/10-quy-uoc-ma-nguon.md
- TL-02 mục QT-09, đặc tả SCR-0904 (mockup, bảng trường, bảng chức năng, BR, MSG)
- wiki/15a-ma-tran-phan-quyen.md (các dòng của SCR-0904)

## 3. Phạm vi công việc

- Đọc đặc tả màn hình, liệt kê đủ trường, chức năng, quy tắc BR và thông báo MSG vào đầu tệp component dưới dạng chú thích ngắn để đối chiếu.
- Bổ sung model TypeScript và phương thức client cho `/api/v1/appraisals`, `/api/v1/esign` trong `@vt/api-client` (kèm dữ liệu giả lập), tên trường theo bảng của TL-03.
- Chọn nhiều hồ sơ, quyết định mức, ký lô (202 + job), step-up MFA; báo cáo kết quả từng hồ sơ.

## 4. Ngoài phạm vi

- Mọi logic nghiệp vụ phía máy chủ (mission-core, workflow-service).
- Phiên bản di động Flutter (SVC-18).

## 5. Tệp được phép sửa

- frontend/projects/fe-appraisal/**
- frontend/projects/api-client/src/lib/** (thêm model và client của module)
- frontend/projects/fe-shell/src/app/app.routes.ts (chỉ khi cần gắn tuyến)

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Ẩn/hiện chức năng đúng ma trận phân quyền cho mọi vai trò liên quan; thao tác bị chặn ở máy chủ vẫn hiển thị lỗi 403 đúng MSG.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-038 trong `docs/tasks/README.md`.
