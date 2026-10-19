# WEB-020 · SCR-0001 Giao diện trang đăng nhập (theme Keycloak)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-020` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ1 – Định danh, tổ chức (đợt 1 TL-04) |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-004, WEB-006 |
| Truy vết | QT-00 → SCR-0001 → SVC-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Làm theme đăng nhập Keycloak theo SCR-0001 (SSO, VNeID, MFA), dùng token màu/chữ của tenant mặc định.

## 2. Đầu vào bắt buộc đọc

- wiki/07-xac-thuc-phan-quyen.md
- wiki/03-giao-dien-theo-tenant.md
- TL-02 QT-00 SCR-0001

## 3. Phạm vi công việc

- Theme FreeMarker cho login, OTP, đổi mật khẩu; nút "Đăng nhập bằng VNeID" (identity provider).
- Tiếng Việt đầy đủ, WCAG AA.

## 4. Ngoài phạm vi

- Cấu hình VNeID thật.

## 5. Tệp được phép sửa

- infra/keycloak-theme/**
- infra/dev/docker-compose.yml (gắn theme)

## 6. Tiêu chí hoàn thành

- Theme hiển thị trên Keycloak dev với tài khoản thử.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-020 trong `docs/tasks/README.md`.
