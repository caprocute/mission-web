# DV-04-10 · ADR-34 Công tắc đồng bộ nhân sự ngoài

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo); task máy chủ đọc thêm `docs/quy-dinh/BACKEND.md`. Chạy `./scripts/agent-task.sh DV-04-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Bổ sung theo BS-04 (24/10/2026) |
| Cỡ việc | S |
| Phụ thuộc | DV-04-06, DV-04-07, DV-13-06 |
| Truy vết | QT-01 → SCR-0104 → `/scim/v2/**`, `/api/v1/org/sync…` → SVC-04, SVC-13 |
| Trạng thái | Xem `TIEN-DO-HE-THONG.md` |

## 1. Mục tiêu
Đồng bộ nhân sự từ hệ thống ngoài trở thành tính năng tuỳ chọn, mặc định tắt; mã đồng bộ giữ nguyên để bật lại bằng cấu hình.

## 2. Đầu vào bắt buộc đọc
- BS-04 (ADR-34 mục 1)

## 3. Phạm vi công việc
- `okrkpi.org.external-sync.enabled` chặn SCIM và lệnh đồng bộ bằng 404 `feature-disabled`; đọc lịch sử và bảng lệch vẫn dùng được.
- `okrkpi.integ.sync-run.enabled` tắt bộ chạy của integration-gateway; compose chỉ bật nguồn giả lập khi `DONG_BO_NGOAI=true`.
- Cờ `org.externalSync` trong danh mục cờ tính năng.

## 4. Ngoài phạm vi
- Xoá mã đồng bộ.

## 5. Tệp được phép sửa
- `backend/services/org-service/**`, `backend/services/integration-gateway/**`, `infra/dev/docker-compose.yml`, `infra/dev/.env.example`, `data/seed/feature-flags/**`

## 6. Tiêu chí hoàn thành
- `ExternalSyncGateTest` và IT đồng bộ (bật cờ trong IT) xanh.
- Kiểm thử của task chạy xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo
Theo AGENTS.md mục 6.
