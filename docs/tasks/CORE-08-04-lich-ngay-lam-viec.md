# CORE-08-04 · BR-06-01 Lịch ngày làm việc và thư viện tính ngày làm việc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-01 |
| Truy vết | QT-06, QT-08 (SCR-0801) → BR-06-01, BR-15-03 → `GET /api/v1/config/calendar` (`getWorkingCalendar`), `GET/POST /api/v1/config/holiday-calendars` (`listHolidayCalendarDays`, `createHolidayCalendarDay`), `PUT/DELETE /api/v1/config/holiday-calendars/{id}` → MOD-08 → `config.holiday_calendar` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp lịch ngày nghỉ, nghỉ bù, làm bù theo tenant và một thư viện tính ngày làm việc dùng chung cho mọi module (hạn duyệt kế hoạch, hạn nghiệm thu, cờ quá hạn, thời hạn mở lại kỳ 1–10 ngày làm việc, timer của workflow). Khi xong, quản trị tenant quản lý lịch qua API đúng hợp đồng, và mọi nơi trong lõi tính "n ngày làm việc" bằng cùng một hàm cho cùng kết quả, khớp hàm `ngay_lam_viec` của CEL.

## 2. Đầu vào bắt buộc đọc

- `TL-02a-quy-trinh.md` mục "QT-06. Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn và điều chỉnh" (BR-06-01) và "SCR-0801" (dòng 2194, phần lịch nghỉ và làm bù).
- `TL-03-thiet-ke-csdl.md` mục "Bảng config.holiday_calendar" (dòng 1529).
- `TL-02b-quy-trinh.md` QT-15 "Quy tắc nghiệp vụ" (BR-15-03: `ngay_lam_viec` đọc `holiday_calendar`).
- `contracts/openapi/modules/config.yaml` các đường dẫn `/config/calendar`, `/config/holiday-calendars`, `/config/holiday-calendars/{id}`.
- Bản trích `extract/tl02a-may-chu.md` QT-06 (BE-06-3) và mục "Việc dùng chung" BE-CHUNG-02 trong `extract/tl02b-may-chu.md`.

## 3. Phạm vi công việc

- Hiện thực bốn thao tác CRUD lịch nghỉ đúng hợp đồng: tạo cần Idempotency-Key, sửa và xoá cần `If-Match` (409 khi lệch `row_version`); trùng ngày trả lỗi theo UQ `(tenant_id, calendar_date)`.
- Hiện thực `getWorkingCalendar`: trả ngày làm việc và ngày nghỉ của một khoảng, gộp lịch `QUOC_GIA` và `TENANT`, có `ETag` để web đệm.
- Công bố `config.api.WorkingCalendarApi` với các hàm cộng n ngày làm việc, đếm ngày làm việc giữa hai mốc, kiểm một ngày có phải ngày làm việc; quy ước thứ Bảy, Chủ nhật nghỉ trừ ngày `LAM_BU` (suy luận theo lịch Việt Nam, ghi trong mã).
- Đệm lịch theo tenant và năm, làm mới khi lịch thay đổi trong cùng pod và qua sự kiện ứng dụng.
- Đưa bộ ca kiểm thử ngày làm việc (dữ liệu YAML dùng chung) vào thư mục ca kiểm thử chung của `vt-cel` (NEN-041) để cel-java và cel-js đối chiếu cùng kết quả với hàm `ngay_lam_viec`.
- Quyền theo ma trận `wiki/15a` (dòng lịch làm việc của SCR-0801) qua OPA.

## 4. Ngoài phạm vi

- Hiện thực hàm CEL `ngay_lam_viec` trong `vt-cel` (NEN-041) và trong `@vt/form-engine`.
- Màn hình quản lý lịch phía web (WEB-034 hoặc WEB-072).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `data/golden/ngay-lam-viec/**` (ca kiểm thử dùng chung; suy luận vị trí)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers cho CRUD, `If-Match` 409, idempotency.
- Kiểm thử đơn vị thư viện ngày làm việc phủ: qua Tết có nghỉ bù, có ngày làm bù thứ Bảy, khoảng qua năm, n = 0; độ phủ ≥ 80%.
- Kiểm thử hợp đồng bốn đường dẫn khớp `config.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
