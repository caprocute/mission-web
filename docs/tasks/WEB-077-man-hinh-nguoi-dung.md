# WEB-077 · SCR-0108, SCR-0109 Màn hình Người dùng và Chi tiết người dùng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Chạy `./scripts/agent-task.sh WEB-077` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | M |
| Phụ thuộc | DV-04-09, WEB-058 |
| Truy vết | QT-01 → SCR-0108, SCR-0109 → `/api/v1/org/users…`, `/api/v1/org/assignments…` → SVC-04 |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Quản trị đơn vị xem người dùng theo cây đơn vị, thêm tài khoản, khoá, mở khoá, đặt lại mật khẩu, sửa thông tin, thêm và kết thúc kiêm nhiệm; người chỉ có quyền xem không thấy nút thao tác.

## 2. Đầu vào bắt buộc đọc
- BS-04 (màn hình, thông điệp)
- wiki/15a (khoá SCR-0108, SCR-0109), wiki/16 (nội dung giao diện)

## 3. Phạm vi công việc
- `UserAdminApi` trong `@vt/api-client`, dữ liệu giả lập `user-admin`.
- Trang `/admin/users`, `/admin/users/:id`, hộp thêm người dùng, mật khẩu tạm (hiện một lần), phân công.
- Thẻ Người dùng trên trang chủ quản trị.

## 4. Ngoài phạm vi
- Phân quyền quản trị theo nhánh.

## 5. Tệp được phép sửa
- `frontend/projects/api-client/src/**` (user-admin)
- `frontend/projects/fe-admin/src/app/pages/users/**`, `admin.routes.ts`, `pages/admin-home.page.ts`

## 6. Tiêu chí hoàn thành
- `npx ng build fe-task && npx ng build fe-shell && npx ng build fe-admin` thành công.
- Kiểm thử Vitest của màn hình chạy xanh; `npm run check:ui-text` không có lỗi; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ người cao tuổi và bàn phím dùng được; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
