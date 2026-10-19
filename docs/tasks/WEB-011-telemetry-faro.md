# WEB-011 · Telemetry: Grafana Faro + OpenTelemetry web

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-011` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | S (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | ADR-12, NFR-10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Gửi lỗi, Web Vitals và trace phía trình duyệt về OTLP dev (Grafana LGTM) để đo LCP/INP thật.

## 2. Đầu vào bắt buộc đọc

- wiki/09-hieu-nang-va-trai-nghiem.md
- wiki/12-ha-tang-dev-va-trien-khai.md

## 3. Phạm vi công việc

- Tích hợp Faro Web SDK + OTel instrumentation fetch; thay `traceparentInterceptor` bằng instrumentation khi bật.
- Lấy mẫu theo cấu hình; không gửi dữ liệu C2/C3 hay nội dung nhập liệu.

## 4. Ngoài phạm vi

- Dashboard Grafana.

## 5. Tệp được phép sửa

- frontend/projects/telemetry/**
- frontend/projects/fe-shell/src/app/app.config.ts

## 6. Tiêu chí hoàn thành

- Thấy trace trình duyệt nối với span APISIX trong Grafana dev.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-011 trong `docs/tasks/README.md`.
