# WEB-071 · YC-PT-08 Nối màn hình đánh giá, hồ sơ số, giám sát với API thật

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-071` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – Nối nghiệp vụ lõi với máy chủ thật |
| Luồng đề xuất | W |
| Cỡ việc | L (nên tách theo remote: fe-appraisal, fe-dossier, fe-oversight) |
| Phụ thuộc | WEB-070, CORE-04-10, CORE-06-08, CORE-07-11 |
| Truy vết | QT-09, QT-10 → SCR-09xx, SCR-10xx → `/api/v1/appraisals/**`; QT-11 → SCR-11xx → `/api/v1/dossiers/**`, `/api/v1/jobs/{id}`; QT-12..QT-14 → SCR-12xx..SCR-14xx → `/api/v1/oversight/**`, `/api/v1/ledger/**` → MOD-04, MOD-06, MOD-07, SVC-07, SVC-09, SVC-10, SVC-11 → EVT-11, EVT-12, EVT-17, EVT-18, EVT-22, EVT-25; YC-PT-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Các màn hình tự đánh giá, thẩm định, quyết định, xếp loại năm, hội nghị kiểm điểm, gói hồ sơ số, khiếu nại, mở lại kỳ, sổ cái và cảnh báo bất thường chạy với backend thật trên server dev, tắt giả lập theo module bằng cơ chế của WEB-070. Khi xong, luồng tự đánh giá → thẩm định → quyết định có ký số, đóng gói hồ sơ và khiếu nại chạy đầu cuối trên hệ thống thật, kể cả các bước chờ ký số và tác vụ dài.

## 2. Đầu vào bắt buộc đọc

- `wiki/06-quy-uoc-api.md` (đã cập nhật ở WEB-070), `wiki/08-bao-mat-frontend.md` (dữ liệu C2, C3), `wiki/11-kiem-thu-va-chat-luong.md`.
- `contracts/openapi/modules/appraisals.yaml`, `dossiers.yaml`, `oversight.yaml`, `jobs.yaml`.
- Thẻ WEB-035 đến WEB-044 (màn hình đã làm với giả lập).
- `wiki/15a-ma-tran-phan-quyen.md` các dòng SCR của QT-09 đến QT-14.

## 3. Phạm vi công việc

- Tắt giả lập cho `appraisals`, `dossiers`, `oversight`, `ledger` trên môi trường thật; chạy từng màn hình, sửa client và màn hình khi frontend lệch hợp đồng, ghi lỗi cho task backend khi máy chủ lệch.
- Kiểm `If-Match` dùng chung ETag hồ sơ (`appraisal_profile.row_version`) cho mọi thao tác ghi lên hồ sơ, xử lý 409 không mất nội dung đang soạn.
- Kiểm các bước chờ ký số: phản hồi 202, theo dõi `JobTracker` tới khi trạng thái hồ sơ đổi sau EVT-18; hiển thị trạng thái chờ đúng nhãn nghiệp vụ, không hiện mã trạng thái thô.
- Kiểm dữ liệu C2 (nhận xét, góp ý, khiếu nại) chỉ hiển thị theo quyền và không lưu cục bộ dạng rõ; luồng C3 chỉ theo E2EE đã thiết kế.
- Kiểm thao tác hai người duyệt (mở lại kỳ) với hai tài khoản thử khác nhau và step-up thật.
- Thêm kịch bản Playwright với backend thật cho ba luồng: đánh giá quý, đóng gói hồ sơ, khiếu nại.

## 4. Ngoài phạm vi

- Màn hình quản trị, cấu hình, Policy Pack, bảng điều hành (WEB-072).
- Sửa mã máy chủ hay hợp đồng OpenAPI.

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/**`
- `frontend/projects/fe-appraisal/**`, `frontend/projects/fe-dossier/**`, `frontend/projects/fe-oversight/**`
- `frontend/projects/fe-shell/public/config.json`
- `frontend/e2e/**`
- `wiki/06-quy-uoc-api.md`, `wiki/11-kiem-thu-va-chat-luong.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Ba kịch bản Playwright với backend thật xanh trên môi trường QA-03; quét bộ nhớ trình duyệt sau kịch bản không thấy dữ liệu C2 dạng rõ.
- Báo cáo liệt kê chênh lệch đã gặp và nơi xử lý; báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
