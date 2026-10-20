# WEB-070 · YC-PT-08 Nối màn hình OKR, giao việc, minh chứng với API thật

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-070` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – Nối nghiệp vụ lõi với máy chủ thật |
| Luồng đề xuất | W |
| Cỡ việc | L (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; nên tách theo remote: fe-okr, fe-task, fe-evidence) |
| Phụ thuộc | QA-03, CORE-01-08, CORE-02-19, CORE-03-07 |
| Truy vết | QT-02, QT-03 → SCR-02xx, SCR-03xx → `/api/v1/okr/**`; QT-04..QT-06 → SCR-04xx..SCR-06xx → `/api/v1/catalog`, `/api/v1/plans`, `/api/v1/tasks/**`; QT-07 → SCR-07xx → `/api/v1/products`, `/api/v1/evidences`, `/api/v1/files` → MOD-01, MOD-02, MOD-03, SVC-08 → EVT-01, EVT-02, EVT-05, EVT-08; YC-PT-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Các màn hình mục tiêu và OKR, giao việc, nhiệm vụ, minh chứng và nghiệm thu chạy với mission-core thật trên server dev thay vì dữ liệu giả lập, và việc chuyển từ giả lập sang thật làm được theo từng module chỉ bằng `config.json`. Khi xong, người kiểm thử đăng nhập Keycloak dev, đi hết luồng lập OKR → check-in, lập kế hoạch → giao việc → cập nhật tiến độ, nộp minh chứng → nghiệm thu trên backend thật mà không có lỗi console, và mọi chênh lệch giữa hợp đồng, giả lập và máy chủ được ghi lại.

## 2. Đầu vào bắt buộc đọc

- `wiki/06-quy-uoc-api.md`, `wiki/02-kien-truc-frontend.md`, `wiki/11-kiem-thu-va-chat-luong.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`.
- `contracts/openapi/modules/okr.yaml`, `tasks.yaml`, `products.yaml`, `jobs.yaml`, `common/common.yaml`.
- `frontend/projects/api-client/src/lib/runtime-config.ts`, `src/mock/mock-api.interceptor.ts` (WEB-008), thẻ QA-03 (môi trường kiểm thử đầu cuối với hệ thống thật).
- `wiki/15a-ma-tran-phan-quyen.md` các dòng SCR của QT-02 đến QT-07.

## 3. Phạm vi công việc

- Mở rộng `RuntimeConfig` cho phép bật giả lập theo module (ví dụ danh sách tiền tố đường dẫn còn dùng giả lập), giữ tương thích với cờ `useMockApi` hiện tại; `mockApiInterceptor` chỉ chặn các đường dẫn thuộc module còn bật giả lập. Ghi quy ước vào `wiki/06`.
- Chạy từng màn hình của fe-okr, fe-task, fe-evidence với backend thật trên server dev (theo QA-03), so phản hồi thật với kiểu sinh từ hợp đồng; sửa client và màn hình khi lệch do frontend; khi máy chủ lệch hợp đồng, không sửa hợp đồng mà ghi lỗi cho task backend tương ứng.
- Kiểm các quy ước đầu cuối với máy chủ thật: `Idempotency-Key` khi ghi, `If-Match` và xử lý 409 bằng `<vt-conflict-notice>`, phân trang con trỏ, 202 + `Location` qua `JobTracker`, tải tệp nhiều phần của SVC-08, lỗi RFC 9457 hiện đúng thông điệp MSG.
- Kiểm step-up thật với Keycloak dev cho thao tác AAL2 (nghiệm thu năm mức) và hiển thị 403 đúng MSG khi bị chặn ở máy chủ.
- Cập nhật giả lập để khớp hành vi thật đã quan sát (dữ liệu giả lập vẫn dùng cho kiểm thử đơn vị và Storybook).
- Thêm kịch bản Playwright chạy với backend thật cho ba luồng chính, gắn nhãn để chỉ chạy khi có môi trường QA-03.

## 4. Ngoài phạm vi

- Màn hình đánh giá, hồ sơ, giám sát (WEB-071); quản trị, cấu hình, bảng điều hành (WEB-072).
- Sửa mã máy chủ hay hợp đồng OpenAPI.

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/**`
- `frontend/projects/fe-okr/**`, `frontend/projects/fe-task/**`, `frontend/projects/fe-evidence/**`
- `frontend/projects/fe-shell/public/config.json`
- `frontend/e2e/**`
- `wiki/06-quy-uoc-api.md`, `wiki/11-kiem-thu-va-chat-luong.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Ba kịch bản Playwright với backend thật chạy xanh trên môi trường QA-03; bật lại giả lập toàn bộ thì bộ kiểm thử cũ vẫn xanh.
- Báo cáo liệt kê từng chênh lệch hợp đồng, giả lập, máy chủ đã gặp và nơi xử lý.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
