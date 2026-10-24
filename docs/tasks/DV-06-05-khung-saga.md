# DV-06-05 · ADR-04 tầng 3 Khung saga: bước, bù trừ, trạng thái bền, 202 và theo dõi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-06-01 |
| Truy vết | ADR-04 tầng 3 → SVC-06 → bảng `flowable.saga_instance`, `flowable.saga_step` → `GET /api/v1/jobs/{id}` (`getJob`); baseline mục 8.5; dùng bởi QT-01 (cấp phát tenant), QT-08 (chốt kỳ), QT-13 (mở lại hậu kiểm), QT-15 (phát hành Policy Pack) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có một khung saga dùng chung cho mọi tác vụ dài nhiều bước trong hệ thống: mỗi saga là định nghĩa BPMN với external worker task, mỗi bước có lệnh, sự kiện trả lời mong đợi, số lần thử và hành động bù trừ; trạng thái lưu bền để saga chạy tiếp sau khi pod chết hay cell chuyển trung tâm dự phòng; có điểm không quay lại; mọi bước ghi sổ cái. Khi xong, saga chốt kỳ, cấp phát tenant và phát hành Policy Pack chỉ cần khai báo bước của mình.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2 "Thiết kế ba tầng" (dòng 1877–1889) và mục "SVC-06 workflow-service" (dòng 1048–1070, saga tầng 3).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08: lưu bền, tiếp tục sau chuyển dự phòng theo khoá idempotency (dòng 2189), BR-08-08, BR-08-10.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema flowable và saga (SVC-06)" bảng `saga_instance` (dòng 3284), `saga_step` (dòng 3297) và mục saga chốt kỳ trong "Cơ chế đồng bộ dữ liệu" (dòng 4359–4378).
- Baseline `00-co-so-thiet-ke-chung.md` mục 8 (cơ chế 5: saga điều phối).
- Hợp đồng: `contracts/openapi/modules/jobs.yaml` `getJob`; `contracts/openapi/modules/platform.yaml` `startPeriodCloseSaga` (mẫu khởi động saga nội bộ, mã `WORKFLOW-409-SAGA-EXISTS`).
- Wiki: `wiki/06-quy-uoc-api.md` (tác vụ 202), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Migration tạo `saga_instance` (`saga_type`, UQ `tenant_id`, `saga_type`, `correlation_id`; `status` `DANG_CHAY`, `CHO_SU_KIEN`, `HOAN_TAT`, `DANG_BU_TRU`, `DA_BU_TRU`, `LOI_CAN_XU_LY`; `point_of_no_return_passed`) và `saga_step` (UQ `saga_instance_id`, `step_no`; `step_code`, `command_event_id`, `reply_event_id`, `compensation_code`, `attempts`, `last_error`) đúng TL-03.
- Viết thư viện khung: khai báo bước (lệnh, sự kiện trả lời, thời hạn, chính sách thử lại có lùi dần, bù trừ, cờ điểm không quay lại), sinh hoặc dùng mẫu BPMN chung có external worker task, ghi `saga_step` trong cùng giao dịch với engine.
- Khởi động idempotent: lần gọi trùng `correlation_id` trả lại saga cũ; saga khác cùng loại đang chạy cho cùng khoá trả 409; trả 202 và tài nguyên job để theo dõi tiến độ qua `getJob`.
- Điều khiển: tạm dừng sau bước hiện tại, tiếp tục, thử lại bước lỗi, yêu cầu bù trừ toàn bộ (chỉ khi chưa qua điểm không quay lại), chuyển `LOI_CAN_XU_LY` khi hết số lần thử.
- Bù trừ chạy theo thứ tự ngược các bước đã xong; bước không có bù trừ được bỏ qua có ghi vết.
- Mọi bước, lần thử, lệnh điều khiển phát bản ghi sổ cái qua outbox (BR-08-10); chiếu tiến độ ra sự kiện để MOD-08 dựng `config.period_lock_step` (suy luận: chiếu do MOD-08 làm).
- Kiểm thử: saga ba bước giả lập chạy hết; lỗi bước 2 thử lại rồi bù trừ bước 1; giết engine giữa bước rồi khởi động lại saga chạy tiếp đúng bước; gọi trùng khoá không tạo saga mới.
- Gợi ý tách: (a) bảng, thư viện khai báo bước, khởi động idempotent; (b) điều khiển, bù trừ, chịu lỗi khởi động lại.

## 4. Ngoài phạm vi

- Bước cụ thể của từng saga (DV-06-06, DV-06-07, DV-03-04, DV-03-05, CORE-05-10).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `backend/libs/` (chỉ khi cần thư viện client saga dùng chung cho dịch vụ Java khác, ghi lý do)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Testcontainers PostgreSQL + Kafka và bốn ca ở mục 3; độ phủ dòng `domain`, `application` ≥ 80%.
- Phản hồi khởi động trả trong ≤ 200 ms (NFR-03, tác vụ nặng chạy nền).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
