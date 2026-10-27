# DV-04-09 · SCR-0108, SCR-0109 Quản trị tài khoản trong org-service

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo); task máy chủ đọc thêm `docs/quy-dinh/BACKEND.md`. Chạy `./scripts/agent-task.sh DV-04-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | M |
| Phụ thuộc | DV-04-04 |
| Truy vết | QT-01 → SCR-0108, SCR-0109 → `/api/v1/org/users…`, `/api/v1/org/job-titles`, `/api/v1/org/assignments…` → SVC-04 → `org.user_account`, `org.person`, `org.assignment`, Keycloak; EVT-16 |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Quản trị đơn vị tạo tài khoản cán bộ (kèm đơn vị chính, chức danh, mật khẩu tạm), sửa thông tin, khoá, mở khoá, đặt lại mật khẩu, thêm và kết thúc kiêm nhiệm ngay trong ứng dụng.

## 2. Đầu vào bắt buộc đọc
- BS-04 (ADR-34 mục 2, 3; BR-01-19..22; quyết định chi tiết khi hiện thực)
- `contracts/openapi/modules/org.yaml`
- DV-04-04 (phân công hai trục thời gian)

## 3. Phạm vi công việc
- Bảng `org.user_account` (migration V11), dịch vụ `UserAccountService`, cổng Keycloak (`KeycloakAccountAdapter`) tạo người dùng, khoá, mở khoá (thu hồi phiên), đặt mật khẩu tạm.
- `AssignmentAdminService` hiện thực tạo, sửa, kết thúc phân công; `listPersonAssignments` trả thêm `rowVersion`.
- Tạo tài khoản và đặt lại mật khẩu: AAL2, gặp lại khoá idempotency trả 409, không ghi mật khẩu tạm vào nhật ký.

## 4. Ngoài phạm vi
- Kiểm tổng tỷ lệ kiêm nhiệm ≤ 100%; phân quyền quản trị theo nhánh đơn vị.

## 5. Tệp được phép sửa
- `backend/services/org-service/**`
- `contracts/openapi/modules/org.yaml` (chỉ `rowVersion` của `Assignment`)

## 6. Tiêu chí hoàn thành
- `mvn -pl services/org-service -am verify` xanh, gồm `UserAccountApiIT`, `OrgAssignmentApiIT`.
- Kiểm thử của task chạy xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
