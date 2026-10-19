# WEB-057 · Kiểm thử tự động đối chiếu ma trận phân quyền bằng Playwright

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-057` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Phân quyền (xuyên giai đoạn) |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-055, WEB-056 |
| Truy vết | wiki/15a → `docs/phan-quyen/ma-tran.json` → `fe-shell` menu và tuyến → Keycloak dev (tài khoản thử theo vai trò) → SEC-18, SEC-40 |
| Trạng thái | Xong, chờ gộp |

## 1. Mục tiêu

Có một bộ kiểm thử đầu cuối sinh từ `ma-tran.json` chứng minh giao diện ẩn hiện đúng ma trận: với mỗi vai trò thử và mỗi tuyến hoặc mục menu, kiểm thử khẳng định người dùng thấy hay không thấy, vào được hay bị chặn. Khi ai đó sửa ma trận mà không sửa giao diện (hoặc ngược lại), kiểm thử đỏ.

## 2. Đầu vào bắt buộc đọc

- wiki/15a-ma-tran-phan-quyen.md, wiki/15-phan-quyen.md
- wiki/11-kiem-thu-va-chat-luong.md (tầng kiểm thử đầu cuối)
- docs/phan-quyen/ma-tran.json, frontend/projects/fe-shell/src/app/layout/nav.ts, app.routes.ts
- infra/dev/README.md (tài khoản thử; mật khẩu chỉ đọc từ `.env` cục bộ)

## 3. Phạm vi công việc

- Thêm Playwright (`@playwright/test`, đúng công cụ TL-01 chọn cho kiểm thử đầu cuối) nếu WEB-001 chưa thêm; nêu lý do trong PR theo quy tắc 1 của `AGENTS.md`.
- Sinh danh sách ca kiểm thử từ `ma-tran.json` và bảng ánh xạ "tuyến hoặc mục menu ↔ khoá chức năng" do WEB-055 tạo: mỗi cặp (vai trò, tuyến) là một ca, kỳ vọng thấy hoặc không thấy tính từ ô của vai trò đó.
- Chạy được ở hai chế độ: chế độ giả lập (tắt đăng nhập, người dùng giả lập mang đúng một vai trò, chỉ trong cấu hình kiểm thử) để chạy nhanh trong pipeline, và chế độ Keycloak dev với tài khoản thử của WEB-056, thông tin đăng nhập đọc từ biến môi trường.
- Với vài thao tác tiêu biểu mà máy chủ giả lập trả 403 hoặc 401 step-up, khẳng định giao diện hiện đúng MSG (ví dụ MSG-02-11 khi duyệt OKR của chính mình).
- Xuất báo cáo dạng bảng vai trò × tuyến (đạt, lệch) để dán vào PR.

## 4. Ngoài phạm vi

- Kiểm thử chính sách OPA phía máy chủ (`opa test` thuộc backend).
- Kiểm thử xâm nhập, IDOR thủ công (WEB-053).

## 5. Tệp được phép sửa

- frontend/e2e/** (thư mục mới cho Playwright) hoặc frontend/projects/fe-shell/e2e/**
- frontend/package.json, frontend/package-lock.json (chỉ thêm Playwright và script `e2e`)
- frontend/projects/fe-shell/public/config*.json (cấu hình riêng cho kiểm thử, nếu cần)
- wiki/11-kiem-thu-va-chat-luong.md

## 6. Tiêu chí hoàn thành

- Bộ kiểm thử chạy xanh ở chế độ giả lập cho mọi vai trò AC-01 đến AC-10 và AC-12 trên mọi tuyến hiện có; ít nhất một lần chạy xanh ở chế độ Keycloak dev, ghi kết quả trong báo cáo.
- Đổi một ô trong `ma-tran.json` (không sửa giao diện) làm ít nhất một ca đỏ.
- Không ghi mật khẩu, token vào mã, log kiểm thử hay ảnh chụp.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-057 trong `docs/tasks/README.md`.
