# CORE-02-07 · QT-04/BR-04-01 Hạn duyệt kế hoạch theo lịch ngày làm việc, nhắc trước hạn và quá hạn

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-02-05, CORE-08-04; cần CORE-05-03 (tham số `plan_approval_deadline_rule`) và DV-12-01 (gửi EVT-21) (bổ sung) |
| Truy vết | QT-04 → SCR-0402, SCR-0403 (hạn hiển thị) → API `GET /api/v1/plans/{id}/versions/{v}` (trường hạn duyệt) → MOD-02, MOD-08 (`config.holiday_calendar` qua thư viện ngày làm việc), MOD-05 → bảng `task.plan_version`, `config.period` → EVT-21; BR-04-01; MSG-04-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tính tự động hạn duyệt kế hoạch quý theo tham số Policy Pack `plan_approval_deadline_rule` (tuần 1 là tuần lịch chứa ngày đầu tháng; hạn là ngày làm việc cuối của tuần 2 tháng đầu quý) và lịch nghỉ của tenant; nhắc người lập và người duyệt trước hạn 3 ngày làm việc; khi quá hạn không chặn duyệt nhưng ghi vết và báo người đứng đầu (MSG-04-08) để QT-14 dùng làm dấu hiệu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` mục "Lịch nghiệp vụ của một quý" (L178–186), QT-04 "Mô tả các bước" (L1193), BR-04-01 (L1211–1230), SCR-0402 (L1310).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema config" (bảng `config.period`, `config.holiday_calendar`).
- `contracts/openapi/modules/tasks.yaml` lược đồ `PlanVersion` (trường hạn duyệt), `contracts/openapi/modules/config.yaml` `/config/calendar`.
- Thư viện tính ngày làm việc dùng chung của CORE-08-04.

## 3. Phạm vi công việc

- Viết hàm thuần `PlanDeadlineCalculator` nhận quy tắc từ Policy Pack, ngày đầu kỳ và lịch ngày làm việc, trả hạn duyệt; phủ các trường hợp tuần đầu tháng bắt đầu giữa tuần, ngày làm bù, nghỉ Tết rơi vào tuần 2.
- Điểm lệch cần lưu ý: TL-02a đề xuất lưu hạn vào `config.period.plan_deadline`, nhưng bảng này thuộc MOD-08 và MOD-02 không được ghi chéo schema. Task tính hạn khi đọc và lưu đệm theo (tenant, kỳ), hoặc đọc từ `PeriodApi` nếu CORE-08-02 đã cung cấp trường này; ghi lựa chọn vào `wiki/14`.
- Trả hạn duyệt trong phản hồi phiên bản kế hoạch đúng trường của hợp đồng.
- Tác vụ định kỳ hằng ngày (chạy một lần trên nhiều bản sao): kế hoạch chưa `DA_DUYET` còn 3 ngày làm việc tới hạn thì ghi EVT-21 tới người lập và người duyệt; quá hạn thì ghi EVT-21 tới người đứng đầu đơn vị kèm MSG-04-08, và đánh dấu kế hoạch duyệt muộn trong dữ liệu để bảng điều hành và QT-14 dùng (cách đánh dấu là suy luận, không thêm trạng thái mới).
- Khi lịch nghỉ của tenant thay đổi, hạn được tính lại ở lần đọc sau (vô hiệu bộ đệm theo sự kiện ứng dụng của MOD-08).

## 4. Ngoài phạm vi

- Thư viện ngày làm việc và quản trị lịch nghỉ (CORE-08-04); dấu hiệu bất thường (SVC-15).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử bảng: ít nhất 8 ca lịch (quý bắt đầu thứ Hai, thứ Tư, Chủ nhật; tuần 2 có ngày lễ; có ngày làm bù) cho hạn đúng như tính tay.
- Kiểm thử với đồng hồ giả lập: nhắc trước hạn gửi đúng một lần; quá hạn gửi đúng một lần tới người đứng đầu; duyệt sau hạn vẫn thành công.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
