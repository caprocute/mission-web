# WEB-007 · `@vt/api-client`: sinh client từ OpenAPI, xử lý lỗi, 409, job 202

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-007` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | Baseline mục 13 → mọi `/api/v1/*` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Chuẩn hoá tầng gọi API để mọi remote dùng chung một cách xử lý lỗi, xung đột và tác vụ dài.

## 2. Đầu vào bắt buộc đọc

- wiki/06-quy-uoc-api.md
- wiki/10-quy-uoc-ma-nguon.md

## 3. Phạm vi công việc

- Quy trình sinh model/client từ tệp OpenAPI 3.1 của mission-core (khi chưa có tệp thật, viết tệp mẫu cho `/api/v1/tasks`). Chọn công cụ sinh mã chạy lúc build, nêu lý do.
- Trình bày lỗi RFC 9457 thống nhất (toast + lỗi theo trường); mẫu UI xử lý 409 "dữ liệu đã thay đổi – tải lại/so sánh".
- `JobTracker`: theo dõi 202 + `Location: /api/v1/jobs/{id}` bằng thăm dò có giãn cách hoặc SSE, hiển thị tiến trình.

## 4. Ngoài phạm vi

- Mock server (WEB-008).

## 5. Tệp được phép sửa

- frontend/projects/api-client/**
- frontend/tools/openapi/**

## 6. Tiêu chí hoàn thành

- Test đơn vị cho interceptor Idempotency-Key, If-Match, ánh xạ ProblemDetails, JobTracker.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-007 trong `docs/tasks/README.md`.
