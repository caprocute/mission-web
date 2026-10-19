# WEB-061 · SCR-1505 Thư viện và trình soạn mẫu luồng (nội bộ nền tảng)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-061` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ4 – Điều hành, cấu hình, mở rộng |
| Cỡ việc | L (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-015, WEB-046 |
| Truy vết | QT-15 → SCR-1505 → `/api/v1/workflow/templates` → SVC-06 (Flowable), repo mẫu luồng → config.wf_template.published.v1 (đề xuất BS-01) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực màn hình SCR-1505 "Thư viện và trình soạn mẫu luồng (nội bộ nền tảng)" trong remote `fe-config-studio` đúng đặc tả BS-01, dùng dữ liệu giả lập qua `@vt/api-client` khi backend chưa sẵn sàng, sẵn sàng chuyển sang API thật chỉ bằng cấu hình.

## 2. Đầu vào bắt buộc đọc

- wiki/05*-quy-trinh-va-man-hinh*.md (trang chứa QT tương ứng)
- wiki/04-ma-dinh-danh-va-truy-vet.md
- wiki/06-quy-uoc-api.md
- wiki/10-quy-uoc-ma-nguon.md
- /mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md (đã duyệt 19/10/2026; toàn bộ mục SCR-1505, SCR-1506, Giới hạn tải, Thay đổi dữ liệu)
- wiki/08-bao-mat-frontend.md
- wiki/09-hieu-nang-va-trai-nghiem.md
- wiki/15a-ma-tran-phan-quyen.md (các dòng của SCR-1505)
- TL-02 mục QT-15 và SCR-1504 (bối cảnh); đặc tả SCR-1505 nằm trong BS-01 vì chưa ghép vào TL-02

## 3. Phạm vi công việc

- Đọc đặc tả màn hình, liệt kê đủ trường, chức năng, quy tắc BR và thông báo MSG vào đầu tệp component dưới dạng chú thích ngắn để đối chiếu.
- Bổ sung model TypeScript và phương thức client cho `/api/v1/workflow/templates` trong `@vt/api-client` (kèm dữ liệu giả lập), tên trường theo bảng của TL-03.
- Ba vùng: thư viện mẫu (mã, phiên bản, trạng thái NHAP/DAT_KIEM_THU/CHO_PHE_DUYET/HIEU_LUC/NGUNG, số tenant dùng, số test), trình soạn bpmn-js nạp lười với bảng màu chỉ gồm phần tử được phép và khối dựng sẵn ("Bước ký số", "Ký lô", "Leo thang quá hạn"), bảng thuộc tính khai báo tham số (sinh `param_schema` JSON Schema dùng lại cho SCR-1504).
- Linter bpmnlint tuỳ biến chạy trên trình duyệt sau mỗi thay đổi, hiện thực đúng 4 quy tắc chống mạng nhện của TL-01 và giới hạn tải (≤ 12 user task mỗi nhánh, ≤ 50 phần tử đa thể hiện, cấm service task đồng bộ, call activity một tầng); lỗi tô đỏ trên sơ đồ. Đóng gói quy tắc thành gói dùng chung để máy chủ/CI chạy lại.
- Chạy test kịch bản và thử tải rút gọn (202 + job, ngân sách ≤ 20 câu SQL mỗi lần hoàn thành user task); đề nghị phát hành (tạo merge request); phê duyệt bốn mắt bởi `second_approver` khác người soạn, step-up MFA; theo dõi saga triển khai cuốn chiếu theo cell (bảng `flowable.wf_template_rollout`, trạng thái CHO/DA_TRIEN_KHAI/LOI/DA_BU_TRU).
- Chỉ vai trò con `wf_template_author` của AC-01 thấy màn hình; tenant không thấy. Phiên bản HIEU_LUC chỉ đọc.
- bpmn-js và bpmnlint là phụ thuộc mới: nêu lý do trong PR (BS-01 đã chỉ định), kiểm tra tương thích CSP (không eval) và nạp lười để giữ ngân sách remote.

## 4. Ngoài phạm vi

- Engine Flowable kiểm thử, CI repo mẫu luồng, saga SVC-06 (phía máy chủ).
- Cho tenant vẽ BPMN tự do (trái ADR-04).

## 5. Tệp được phép sửa

- frontend/projects/fe-config-studio/**
- frontend/projects/api-client/src/lib/** (thêm model và client của module)
- frontend/projects/fe-shell/src/app/app.routes.ts (chỉ khi cần gắn tuyến)

## 6. Tiêu chí hoàn thành

- Linter bắt được ví dụ vi phạm cho cả 4 quy tắc và các giới hạn tải; test đơn vị cho từng quy tắc.
- bpmn-js không nằm trong gói tải đầu của fe-config-studio.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Ẩn/hiện chức năng đúng ma trận phân quyền cho mọi vai trò liên quan; thao tác bị chặn ở máy chủ vẫn hiển thị lỗi 403 đúng MSG.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-061 trong `docs/tasks/README.md`.
