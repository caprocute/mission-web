# WEB-072 · YC-PT-08 Nối màn hình quản trị, cấu hình, Policy Pack, bảng điều hành với API thật

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-072` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – Nối nghiệp vụ lõi với máy chủ thật |
| Luồng đề xuất | W |
| Cỡ việc | L (nên tách: a. fe-admin (tenant, tổ chức, kỳ, khoá kỳ); b. fe-config-studio (Policy Pack, UI Pack, biểu mẫu); c. fe-dashboard) |
| Phụ thuộc | WEB-070, CORE-05-10, CORE-08-11, DV-15-04 |
| Truy vết | QT-01 → SCR-01xx → `/api/v1/config/tenants`, `/api/v1/org/**`; QT-08 → SCR-0801 → `/api/v1/periods/**`, `/api/v1/config/holiday-calendars`; QT-15 → SCR-1501..SCR-1504 → `/api/v1/policies/**`; QT-16 → SCR-1601, SCR-1602 → `/api/v1/analytics/**`; QT-18 → SCR-1801, SCR-1802 → `/api/v1/ui/**` → MOD-05, MOD-08, SVC-03, SVC-04, SVC-15 → EVT-09, EVT-13, EVT-23; YC-PT-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Các màn hình quản trị tenant và tổ chức, quản lý kỳ và khoá kỳ, Config Studio (Policy Pack, UI Pack, biểu mẫu động) và bảng điều hành chạy với backend thật, tắt giả lập theo module. Khi xong, quản trị viên trên server dev soạn và phát hành được một Policy Pack, một UI Pack, thấy shell áp giao diện mới ở lần tải trang kế tiếp sau EVT-23, khoá được một kỳ thử và xem bảng điều hành với số liệu thật.

## 2. Đầu vào bắt buộc đọc

- `wiki/03-giao-dien-theo-tenant.md`, `wiki/06-quy-uoc-api.md`, `wiki/11-kiem-thu-va-chat-luong.md`.
- `contracts/openapi/modules/config.yaml`, `policies.yaml`, `org.yaml`, `platform.yaml` (nhóm `/analytics`, `/workflow`, `/registry`), `jobs.yaml`.
- Thẻ CORE-08-11 (phát hành UI Pack, ETag), CORE-05-10 (saga phát hành Policy Pack), DV-15-04 (API dashboard), WEB-034, WEB-045 đến WEB-050.
- `wiki/15a-ma-tran-phan-quyen.md` các dòng SCR của QT-01, QT-08, QT-15, QT-16, QT-18.

## 3. Phạm vi công việc

- Tắt giả lập cho `config`, `periods`, `policies`, `ui`, `org`, `analytics`, `workflow` trên môi trường thật; chạy từng màn hình, sửa client và màn hình khi frontend lệch hợp đồng, ghi lỗi cho task backend khi máy chủ lệch.
- `UiPackLoader` của shell đọc UI Pack thật qua CDN dev hoặc `GET /ui/packs/current`, kiểm chữ ký bằng khoá công khai nền tảng của môi trường dev, dùng `If-None-Match` và IndexedDB đúng TL-01 "Cơ chế bộ đệm"; xác nhận giao diện đổi ở lần điều hướng sau khi phát hành, không đổi giữa lúc đang nhập.
- Trang chủ dùng `GET /ui/layouts/home` thật; widget không có trong registry bị bỏ qua và ghi telemetry.
- Kiểm tác vụ dài 202 thật: chạy kiểm thử Policy Pack, phát hành UI Pack (job `RENDER`), khoá kỳ (saga), với `JobTracker` và màn hình tiến độ.
- Kiểm bốn mắt và step-up thật: người soạn không tự duyệt được Policy Pack, UI Pack; người lập lệnh khoá kỳ không tự duyệt.
- Thêm kịch bản Playwright với backend thật cho: phát hành UI Pack rồi thấy token mới; phát hành Policy Pack qua kiểm thử vàng; tạo kỳ và lệnh khoá kỳ.

## 4. Ngoài phạm vi

- Sửa mã máy chủ, hợp đồng OpenAPI hay cấu hình CDN.
- Quản trị vai trò và uỷ quyền (WEB-058 pha 2, chờ CORE-08-16).

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/**`
- `frontend/projects/fe-admin/**`, `frontend/projects/fe-config-studio/**`, `frontend/projects/fe-dashboard/**`
- `frontend/projects/sdui-renderer/src/lib/ui-pack-loader.ts` (chỉ phần đọc nguồn thật và kiểm chữ ký)
- `frontend/projects/fe-shell/public/config.json`
- `frontend/e2e/**`
- `wiki/03-giao-dien-theo-tenant.md`, `wiki/06-quy-uoc-api.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Ba kịch bản Playwright với backend thật xanh trên môi trường QA-03; UI Pack sai chữ ký bị shell từ chối và giữ bản đã lưu.
- Báo cáo liệt kê chênh lệch đã gặp và nơi xử lý; báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
