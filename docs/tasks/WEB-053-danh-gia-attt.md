# WEB-053 · Đánh giá ATTT frontend: OWASP ZAP và ASVS mức 2

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-053` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ4 – Điều hành, cấu hình, mở rộng |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-014 |
| Truy vết | NFR-08, TL-04 "Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP" |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Chạy ZAP baseline trên bản dev, đối chiếu checklist ASVS L2 phần phía client, sửa lỗi trong phạm vi web.

## 2. Đầu vào bắt buộc đọc

- wiki/08-bao-mat-frontend.md
- TL-04 mục ASVS và ZAP

## 3. Phạm vi công việc

- Báo cáo phát hiện, mức độ, cách sửa; mở task sửa cho lỗi ngoài phạm vi.

## 4. Ngoài phạm vi

- (không có)

## 5. Tệp được phép sửa

- docs/security/**
- deploy/web/**

## 6. Tiêu chí hoàn thành

- Không còn phát hiện mức High trong ZAP baseline.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-053 trong `docs/tasks/README.md`.
