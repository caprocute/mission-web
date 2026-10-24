# NEN-045 · ADR-34 Chính sách OPA cho vai trò tuỳ chỉnh theo tenant

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo); task máy chủ đọc thêm `docs/quy-dinh/BACKEND.md`. Chạy `./scripts/agent-task.sh NEN-045` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | S |
| Phụ thuộc | NEN-040, NEN-044 |
| Truy vết | QT-01 → SCR-0110 → `/api/v1/config/custom-roles` → MOD-08 → OPA `data.tenant_roles` |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Máy chủ cho phép hay từ chối thao tác của người giữ vai trò tuỳ chỉnh `tc_…` đúng như vai trò gốc bị thu hẹp: chỉ những chức năng đã chọn, phạm vi không rộng hơn vai trò gốc, kế thừa AAL2, tách vai trò và bốn mắt.

## 2. Đầu vào bắt buộc đọc
- `docs/thiet-ke/BS-04-quan-tri-nguoi-dung-va-phan-quyen-chi-tiet.md` (ADR-34 mục 4, 5; BR-01-23)
- wiki/15 (phân quyền), wiki/20 (OPA cùng pod)
- `backend/opa/okrkpi/authz.rego` hiện có

## 3. Phạm vi công việc
- `authz.rego` đọc `data.tenant_roles[tenant][code]` (`base`, `status`, `permissions`) và coi vai trò `tc_…` đang hiệu lực như vai trò gốc bị thu hẹp.
- Kiểm lại ở OPA rằng phạm vi từng chức năng không rộng hơn phạm vi vai trò gốc, phòng dữ liệu bị đẩy sai.
- Kiểm thử `opa test backend/opa` cho các ca: vai trò hiệu lực, đã ngừng, chức năng ngoài tập chọn, phạm vi rộng hơn gốc, tenant khác.

## 4. Ngoài phạm vi
- Phát hành định nghĩa vai trò thành bundle thứ hai trên cụm thật (ghi ở BS-04, task hạ tầng sau).

## 5. Tệp được phép sửa
- `backend/opa/okrkpi/**`

## 6. Tiêu chí hoàn thành
- `opa test backend/opa` xanh; ca mới của vai trò tuỳ chỉnh có mặt trong `authz_test.rego`.
- Kiểm thử của task chạy xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
