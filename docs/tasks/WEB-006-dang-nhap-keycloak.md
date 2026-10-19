# WEB-006 · Đăng nhập Keycloak, chọn đơn vị và vai trò (phần web của QT-00)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-006` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | QT-00 → SCR-0001, SCR-0002 → SVC-02 (Keycloak) → `/api/v1/org` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện `@vt/auth` để đăng nhập thật với realm `mission` trên Keycloak dev, chọn đơn vị khi kiêm nhiệm, đăng xuất qua end_session_endpoint.

## 2. Đầu vào bắt buộc đọc

- wiki/07-xac-thuc-phan-quyen.md
- wiki/05*-quy-trinh-va-man-hinh*.md (trang chứa QT tương ứng)
- TL-02 QT-00
- infra/dev/README.md (realm mission, tài khoản thử)

## 3. Phạm vi công việc

- Bật `auth.enabled`, xử lý callback, làm mới token, hết phiên; interceptor xử lý 401 (đăng nhập lại) và 403 (thông báo theo MSG của QT-00).
- Hộp chọn đơn vị/vai trò khi người dùng có nhiều phân công (dữ liệu từ `/api/v1/org`, giả lập khi chưa có), lưu lựa chọn trong phiên.
- Móc step-up MFA: hàm `requireStepUp(acr)` dùng cho thao tác quan trọng (ký, quyết định) theo lớp bảo vệ 6.

## 4. Ngoài phạm vi

- Giao diện trang đăng nhập (theme Keycloak) – WEB-020.

## 5. Tệp được phép sửa

- frontend/projects/auth/**
- frontend/projects/fe-shell/src/app/layout/**
- frontend/projects/fe-shell/public/config.json

## 6. Tiêu chí hoàn thành

- Đăng nhập được bằng 5 tài khoản thử trên Keycloak dev; menu đổi theo vai trò.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-006 trong `docs/tasks/README.md`.
