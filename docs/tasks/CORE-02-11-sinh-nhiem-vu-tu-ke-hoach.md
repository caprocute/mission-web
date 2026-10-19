# CORE-02-11 · QT-04/QT-05 Consumer EVT-03 sinh, cập nhật, huỷ nhiệm vụ; xác nhận dòng liên đơn vị

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-09, CORE-02-13 |
| Truy vết | QT-04 bước 8, QT-05 bước 1a → (tác vụ nền) → MOD-02 → bảng `task.plan_item`, `task.task`, `task.task_assignee`, `task.task_state_log`, `task.inbox_processed`, `task.outbox_event` → nhận EVT-03 `task.plan.approved.v1`, phát EVT-06 `task.task.statuschanged.v1`; BR-04-11; MSG-04-10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi kế hoạch quý được duyệt (EVT-03), MOD-02 tự sinh nhiệm vụ `NHAP` cho từng dòng mới, cập nhật nhiệm vụ chưa bắt đầu cho dòng sửa, huỷ nhiệm vụ cho dòng bị bỏ nếu chưa có minh chứng được xác nhận, trong ≤ 1 phút. Nhờ đó người giao việc ở QT-05 chỉ cần mở nhiệm vụ nháp đã có đủ sản phẩm, số lượng, hạn, điểm, tiêu chuẩn và giao đi. Task cũng hiện thực bước xác nhận dòng có đơn vị chủ trì khác trước khi duyệt.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Mô tả các bước" bước 8 (L1190), "Ngoại lệ và xử lý" (dòng có đơn vị chủ trì khác, L1239); QT-05 "Mô tả các bước" bước 1a (L1424); mục "Quan hệ giữa các quy trình" (L176).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương tích hợp: thư viện consumer Kafka chuẩn và inbox idempotent, thứ tự và phân vùng topic.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.task", bảng `inbox_processed`, `inbox_aggregate_version`.
- `contracts/openapi/modules/tasks.yaml` lược đồ `Task`, `TaskSource`; schema Avro EVT-03 do CORE-02-09 viết.

## 3. Phạm vi công việc

- Viết consumer EVT-03 dùng thư viện consumer chuẩn: khử trùng qua `inbox_processed`, bỏ qua sự kiện cũ qua `inbox_aggregate_version`, mỗi kế hoạch xử lý trong giao dịch theo lô có giới hạn.
- Dòng mới: tạo `task.task` `NHAP`, `source='KE_HOACH'`, `plan_item_id`, sao chép sản phẩm, số lượng, đơn vị tính, hạn, điểm, mức khó, tiêu chuẩn chất lượng, minh chứng yêu cầu từ dòng kế hoạch và mục danh mục; tạo `task_assignee` chủ trì và phối hợp; sinh mã `NV-yyyy-Qn-nnnn` duy nhất trong tenant; ghi `task_state_log` qua máy trạng thái CORE-02-13.
- Dòng sửa: cập nhật nhiệm vụ còn `NHAP` hoặc `DA_GIAO` chưa nhận (suy luận cho "chưa bắt đầu"); nhiệm vụ đang thực hiện giữ hạn (BR-04-12 đã chặn ở CORE-02-10).
- Dòng bỏ: chuyển `HUY` qua máy trạng thái nếu nhiệm vụ chưa có minh chứng `DA_XAC_NHAN` (BR-04-11, dữ liệu từ `TaskApi` CORE-02-12), phát EVT-06; có thì ghi lỗi đối soát và EVT-21 tới người lập.
- Sau khi xử lý xong một kế hoạch, ghi EVT-21 MSG-04-10 báo người lập số nhiệm vụ đã sinh; đo thời gian từ EVT-03 tới hoàn tất bằng chỉ số OpenTelemetry.
- Xác nhận dòng liên đơn vị: dòng có `lead_unit_id` khác đơn vị lập kế hoạch phải được đơn vị chủ trì xác nhận nhận việc trước khi duyệt; từ chối thì dòng bị đánh lỗi khi `PlanChecker` chạy. Điểm lệch cần lưu ý: `tasks.yaml` chưa có thao tác xác nhận hoặc từ chối dòng; task hiện thực trạng thái xác nhận trong domain (lưu trong `plan_item` qua migration bổ sung, đánh dấu "bổ sung") và ghi đề nghị bổ sung hợp đồng, không tự mở endpoint.

## 4. Ngoài phạm vi

- Giao việc từ nhiệm vụ nháp (CORE-02-14); nhiệm vụ từ văn bản (CORE-02-16).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ migration mới cho trạng thái xác nhận dòng)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka.
- Kế hoạch 500 dòng sinh đủ 500 nhiệm vụ `NHAP` trong ≤ 60 giây; EVT-03 nhận hai lần không sinh trùng; mã nhiệm vụ không trùng khi hai kế hoạch xử lý song song.
- Điều chỉnh bỏ một dòng chưa có minh chứng sinh EVT-06 `HUY`; bỏ dòng có minh chứng xác nhận không huỷ và có cảnh báo.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu đề nghị bổ sung hợp đồng xác nhận dòng liên đơn vị.

## 7. Báo cáo

Theo AGENTS.md mục 6.
