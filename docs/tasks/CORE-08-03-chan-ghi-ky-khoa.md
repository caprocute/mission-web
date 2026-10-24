# CORE-08-03 · BR-08-06 Chặn ghi khi kỳ đang khoá hoặc đã khoá

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-02 |
| Truy vết | QT-08 bước 1, QT-13 → BR-08-06, BR-13-01, MSG-08-06 → `config.api.PeriodApi.assertWritable` → `config.period` → lỗi 409 `https://okrkpi.vn/problems/period-locked`; nhận EVT-09, EVT-26 để làm mới bộ đệm |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bảo đảm khi kỳ ở `DANG_KHOA` hoặc `DA_KHOA`, mọi lệnh ghi vào dữ liệu của kỳ ở MOD-01, MOD-02, MOD-03 và phần gắn chính sách theo kỳ của MOD-05 bị từ chối bằng 409 `period-locked` theo RFC 9457, với chi phí kiểm tra rất nhỏ nhờ bộ đệm trạng thái kỳ trong bộ nhớ. Khi xong, một cơ chế dùng chung được công bố để các module gắn vào cổng ghi, có kiểm thử chứng minh và có quy tắc kiến trúc nhắc module mới phải dùng.

## 2. Đầu vào bắt buộc đọc

- `TL-02a-quy-trinh.md` mục "QT-08. Khoá kỳ (saga chốt kỳ)", tiểu mục "Mô tả các bước" (bước 1, dòng 2118) và "Quy tắc nghiệp vụ" (BR-08-06, dòng 2163); bảng thông báo MSG-08-06 (dòng 2245).
- `TL-02b-quy-trinh.md` mục "QT-13. Hậu kiểm, mở lại kỳ (hai người duyệt)", "Quy tắc nghiệp vụ" (BR-13-01, dòng 999).
- `TL-03-thiet-ke-csdl.md` mục "Schema config (MOD-08)" dòng 1405–1415 (bộ đệm Caffeine ≤ 30 s, trigger chặn ghi ở `appraisal`).
- `contracts/openapi/common/common.yaml` ví dụ lỗi `ky-da-khoa` (dòng 171) và schema `Problem`.
- Bản trích `extract/tl02a-may-chu.md` QT-08 (BE-08-2).

## 3. Phạm vi công việc

- Thêm bộ đệm trạng thái kỳ (Caffeine, hạn ≤ 30 giây theo TL-03) sau `PeriodApi.assertWritable`, mục tiêu dưới 0,1 ms mỗi lần kiểm khi trúng đệm (TL-02b QT-13).
- Làm mới đệm ngay trong pod khi nhận sự kiện ứng dụng chuyển trạng thái kỳ, và giữa các pod khi tiêu thụ EVT-09 (`config.period.locked.v1`) và EVT-26 (`config.period.unlocked.v1`) qua consumer có inbox.
- Định nghĩa `PeriodLockedException` ở gói `config.api` và bộ ánh xạ lỗi chung sang problem `type` `https://okrkpi.vn/problems/period-locked`, `status` 409, `code` theo tiền tố module gọi (ví dụ `TASK-409-PERIOD-LOCKED`, `POLICY-409-PERIOD-LOCKED` như hợp đồng), `detail` theo MSG-08-06.
- Công bố cách gắn dùng chung (chú thích hoặc lớp tiện ích ở tầng `application`) để module gọi `assertWritable` trước mọi lệnh ghi dữ liệu theo kỳ; gắn vào các cổng ghi của module đã có mã tại thời điểm làm task và liệt kê trong báo cáo các module còn thiếu.
- Viết quy tắc ArchUnit: lớp dịch vụ ghi của `okr`, `task`, `product` và lớp ghi `policy_binding` phải đi qua cơ chế kiểm kỳ (suy luận cách kiểm).
- Ở trạng thái `MO_LAI_HAU_KIEM`, task này coi như vẫn khoá (trả 409); kiểm phạm vi được mở làm ở CORE-08-06.

## 4. Ngoài phạm vi

- Trigger chặn ghi ở schema `appraisal` (task CORE-04 tương ứng).
- Phạm vi mở lại hậu kiểm (CORE-08-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/shared/**` (chỉ bộ ánh xạ lỗi chung, nếu thư viện nền chưa có)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/{okr,task,product,policy}/application/**` (chỉ thêm lời gọi kiểm kỳ)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL và Kafka: khoá kỳ rồi ghi vào MOD-02 nhận 409 đúng thân problem; mở lại (EVT-26) thì ghi được trong vòng thời gian làm mới đệm.
- Kiểm thử vi đo (JMH hoặc kiểm thử thời gian) cho thấy kiểm khi trúng đệm < 0,1 ms.
- ArchUnit xanh với quy tắc mới; kiểm thử hợp đồng xác nhận thân lỗi khớp `common.yaml`.
- Báo cáo nêu rõ module nào đã gắn kiểm kỳ, module nào chưa.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
