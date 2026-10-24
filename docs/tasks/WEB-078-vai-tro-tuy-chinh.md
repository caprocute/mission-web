# WEB-078 · SCR-0110 Màn hình Vai trò và quyền, áp vai trò tuỳ chỉnh ở giao diện

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Chạy `./scripts/agent-task.sh WEB-078` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-18, WEB-054 |
| Truy vết | QT-01 → SCR-0110 → `/api/v1/config/custom-roles…`, `/api/v1/config/me/custom-roles` → MOD-08 |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Quản trị đơn vị tạo vai trò riêng bằng cách chọn chức năng và phạm vi từ vai trò có sẵn, cấp vai trò đó tại màn hình Vai trò theo đơn vị; người giữ vai trò tuỳ chỉnh thấy đúng menu, tuyến và nút được phép.

## 2. Đầu vào bắt buộc đọc
- BS-04 (ADR-34 mục 4, 5; BR-01-23)
- wiki/15, wiki/15a, wiki/16

## 3. Phạm vi công việc
- `CustomRoleApi`, `MyCustomRolesLoader`, dữ liệu giả lập `custom-roles`.
- Trang `/admin/roles`, hộp tạo, sửa vai trò theo nhóm chức năng, ngừng sử dụng có lý do.
- `PermissionService.setCustomRoles()`; shell nạp vai trò tuỳ chỉnh của người dùng khi khởi động và khi đổi phân công.
- Màn hình cấp vai trò hiện tên vai trò tuỳ chỉnh và cho chọn vai trò tuỳ chỉnh đang hiệu lực.

## 4. Ngoài phạm vi
- Không có.

## 5. Tệp được phép sửa
- `frontend/projects/api-client/src/**` (custom-role)
- `frontend/projects/auth/src/lib/permission.service*.ts`
- `frontend/projects/fe-shell/src/app/app.config.ts`
- `frontend/projects/fe-admin/src/app/pages/access/**`, `admin.routes.ts`

## 6. Tiêu chí hoàn thành
- `npx ng build fe-task && npx ng build fe-shell && npx ng build fe-admin` thành công.
- Kiểm thử Vitest của màn hình chạy xanh; `npm run check:ui-text` không có lỗi; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ người cao tuổi và bàn phím dùng được; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
