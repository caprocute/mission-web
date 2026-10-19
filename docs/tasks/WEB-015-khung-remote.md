# WEB-015 · Công cụ tạo remote mới và dựng khung 8 remote

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-015` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | Baseline mục 5.2 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo script sinh remote chuẩn (federation config, routes, widgets, styles, test mẫu) và dựng khung `fe-okr`, `fe-evidence`, `fe-appraisal`, `fe-dossier`, `fe-oversight`, `fe-dashboard`, `fe-config-studio`, `fe-admin`.

## 2. Đầu vào bắt buộc đọc

- wiki/02-kien-truc-frontend.md
- wiki/10-quy-uoc-ma-nguon.md

## 3. Phạm vi công việc

- Script tạo project, đăng ký cổng dev (4202–4209), thêm vào manifest, Dockerfile và nginx.
- Shell thay trang "planned" bằng `remoteRoutes()` cho từng remote đã có khung.

## 4. Ngoài phạm vi

- Nội dung màn hình (các task GĐ1–GĐ4).

## 5. Tệp được phép sửa

- frontend/**
- scripts/new-remote.sh
- frontend/projects/fe-shell/public/federation.manifest.json
- deploy/web/**

## 6. Tiêu chí hoàn thành

- Cả 10 project build thành công; điều hướng tới từng remote hiển thị trang khung.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-015 trong `docs/tasks/README.md`.
