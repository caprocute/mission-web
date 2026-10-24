# WEB-079 · SCR-0104 Ẩn đồng bộ nhân sự ngoài theo cờ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Chạy `./scripts/agent-task.sh WEB-079` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | S |
| Phụ thuộc | WEB-076, DV-04-10 |
| Truy vết | QT-01 → SCR-0104, SCR-0102 → `/api/v1/ui/feature-flags` → MOD-08 |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Khi cờ `org.externalSync` tắt, người dùng không thấy màn hình, thẻ và bước chọn nguồn nhân sự của đồng bộ ngoài.

## 2. Đầu vào bắt buộc đọc
- BS-04 (ADR-34 mục 1)
- WEB-076 (đọc cờ tính năng)

## 3. Phạm vi công việc
- Tuyến `org/sync` có `featureFlagGuard(EXTERNAL_SYNC_FLAG)`; thẻ trang chủ quản trị và bước nguồn nhân sự của SCR-0102 ẩn theo cờ.
- Kiểm thử trình duyệt bỏ qua tuyến của tính năng tuỳ chọn khi cờ tắt.

## 4. Ngoài phạm vi
- Không có.

## 5. Tệp được phép sửa
- `frontend/projects/fe-admin/src/app/**`
- `frontend/projects/api-client/src/lib/feature-flags.ts`
- `frontend/e2e/matrix.ts`

## 6. Tiêu chí hoàn thành
- `npx ng build fe-task && npx ng build fe-shell && npx ng build fe-admin` thành công.
- Kiểm thử Vitest của màn hình chạy xanh; `npm run check:ui-text` không có lỗi; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ người cao tuổi và bàn phím dùng được; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
