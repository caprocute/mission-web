# CORE-08-18 · SCR-0110 Vai trò tuỳ chỉnh, danh mục quyền, đẩy sang OPA và Keycloak

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo); task máy chủ đọc thêm `docs/quy-dinh/BACKEND.md`. Chạy `./scripts/agent-task.sh CORE-08-18` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-16, NEN-045 |
| Truy vết | QT-01 → SCR-0110 → `/api/v1/config/permission-catalog`, `/api/v1/config/custom-roles…`, `/api/v1/config/me/custom-roles` → MOD-08 → `config.custom_role`, `config.role_binding`, OPA, Keycloak |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Quản trị đơn vị tạo, sửa, ngừng vai trò tuỳ chỉnh và cấp vai trò đó cho cán bộ như vai trò hệ thống; thay đổi có hiệu lực ngay ở OPA và Keycloak.

## 2. Đầu vào bắt buộc đọc
- BS-04 (ADR-34, BR-01-23, BR-01-24, mục API và Dữ liệu)
- `contracts/openapi/modules/config.yaml` phần vai trò tuỳ chỉnh
- CORE-08-16 (cấp vai trò, `RealmRoleSync`)

## 3. Phạm vi công việc
- Bảng `config.custom_role` (migration V14, RLS), kho JDBC, dịch vụ kiểm BR-01-23 dựa trên danh mục quyền sinh từ ma trận (`permission-catalog.json`).
- Bộ điều khiển theo hợp đồng: danh mục (ETag), danh sách kèm số người giữ, tạo, sửa (`PUT`, `If-Match`), ngừng (thu hồi mọi bản ghi cấp đang mở), vai trò của tôi.
- Đẩy định nghĩa sang OPA qua Data API khi lưu, khi khởi động và mỗi phút; tạo realm role `tc_…` trên Keycloak (cần `manage-realm`).
- `RoleBindingService` nhận mã `tc_…` đang hiệu lực; `RealmRoleSync` gồm vai trò tuỳ chỉnh.

## 4. Ngoài phạm vi
- Vai trò gốc `tenant_admin`, `platform_admin`, `decider`, `second_approver`, `policy_author` (giới hạn ở BS-04).

## 5. Tệp được phép sửa
- `backend/mission-core/src/**/config/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/shared/infrastructure/identity/KeycloakAdminClient.java`
- `docs/phan-quyen/gen_matrix.py` (sinh danh mục quyền)
- `infra/dev/keycloak/realm-mission.json`, `infra/dev/scripts/them-client-mission-core.sh`

## 6. Tiêu chí hoàn thành
- `CustomRoleServiceTest`, `AccessApiIT`, `ArchitectureRulesTest` (không thêm lỗi mới) xanh.
- Kiểm thử của task chạy xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
