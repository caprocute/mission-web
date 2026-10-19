# WEB-009 · SDUI: kiểm chữ ký UI Pack, kiểm phiên bản registry, telemetry

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-009` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-004 |
| Truy vết | ADR-05 lớp 2, TL-01 "Bảo mật: chống XSS và CSP" |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn tất các lớp bảo vệ còn thiếu của renderer SDUI.

## 2. Đầu vào bắt buộc đọc

- wiki/03-giao-dien-theo-tenant.md
- wiki/08-bao-mat-frontend.md

## 3. Phạm vi công việc

- Kiểm chữ ký gói (ECDSA P-256 qua WebCrypto) trước khi áp; khoá công khai của nền tảng nạp từ config.json.
- Kiểm `requires.registry` theo semver với `REGISTRY_VERSION`; gói không tương thích thì giữ bản cũ.
- Ghi telemetry khi gặp component lạ, thuộc tính bị loại, biểu thức CEL lỗi.
- Giới hạn chi phí đánh giá CEL (độ sâu, độ dài).

## 4. Ngoài phạm vi

- Trình soạn UI Pack.

## 5. Tệp được phép sửa

- frontend/projects/sdui-renderer/**

## 6. Tiêu chí hoàn thành

- Test: gói sai chữ ký bị từ chối, component lạ bị bỏ, thuộc tính `javascript:` bị loại.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-009 trong `docs/tasks/README.md`.
