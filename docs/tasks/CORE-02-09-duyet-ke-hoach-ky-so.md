# CORE-02-09 · QT-04/SCR-0403 Duyệt kế hoạch có ký số, EVT-18 rồi EVT-03

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-08, DV-10-02 |
| Truy vết | QT-04 → SCR-0403 → API `POST /api/v1/plans/{id}/versions/{v}/approve` (202), `GET /api/v1/jobs/{id}`, `POST /api/v1/esign/requests` (SVC-10) → MOD-02, SVC-10, SVC-06 → bảng `task.plan_version` (`approved_by/at`, `sign_request_id`, `is_current`), `task.inbox_processed`, `task.outbox_event` → nhận EVT-18 `esign.signature.completed.v1`, phát EVT-03 `task.plan.approved.v1`; BR-04-05, BR-04-08, BR-04-09; MSG-04-09, MSG-04-13 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bước duyệt kế hoạch có ký số: người duyệt (AC-04, khác người lập, phiên AAL2 trong 15 phút) bấm duyệt, máy chủ tạo yêu cầu ký trên SVC-10 trên băm nội dung đã khoá và trả 202; chỉ khi nhận EVT-18 xác nhận chữ ký hợp lệ, phiên bản mới chuyển `DA_DUYET`, trở thành phiên bản hiệu lực và phát EVT-03 để sinh nhiệm vụ. Ký số lỗi thì giữ `CHO_DUYET`, xếp hàng ký thử lại và báo người duyệt khi ký xong.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Trạng thái đối tượng" (L1195–1209), BR-04-05, BR-04-08, BR-04-09 (L1211–1230), "Ngoại lệ và xử lý" (ký số lỗi, L1238), SCR-0403 (L1342–1382, API L1371–1373), "Liên kết thiết kế" (L1383–1385).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` bảng sự kiện EVT-03, EVT-18.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục SVC-10 esign-service và thư viện consumer Kafka + inbox.
- `contracts/openapi/modules/tasks.yaml` `approvePlanVersion`; `contracts/openapi/modules/dossiers.yaml` `/esign/requests`, `/esign/requests/{id}`; `contracts/openapi/modules/jobs.yaml`.
- `wiki/15a-ma-tran-phan-quyen.md` mục "Ràng buộc tách vai trò, bốn mắt và xác thực bước lên".

## 3. Phạm vi công việc

- Hiện thực `approvePlanVersion` (If-Match, Idempotency-Key): kiểm phiên bản `CHO_DUYET`, người gọi là người duyệt được chỉ định (hoặc duyệt thay hợp lệ), khác người lập (BR-04-08, 403 `TASK-403-SAME-AUTHOR`), claim `acr`/thời điểm xác thực đạt AAL2 trong 15 phút; tạo job và ghi outbox yêu cầu ký kèm `content_hash` và `signMethod`; trả 202 + `Location`.
- Kiểm BR-04-05 ở bước duyệt: còn dòng ngoài danh mục chưa có điểm thì chặn duyệt (422). Điểm lệch cần lưu ý: TL-02a nói người duyệt chấm điểm việc ngoài danh mục khi duyệt, nhưng thân yêu cầu của `approvePlanVersion` chỉ có `signMethod`, `comment` và `savePlanVersion` chỉ cho vai trò `manager`; task không tự mở rộng hợp đồng, chỉ chặn duyệt khi thiếu điểm và ghi đề nghị bổ sung hợp đồng vào báo cáo.
- Viết consumer EVT-18 dùng thư viện consumer chuẩn có inbox khử trùng: chữ ký hợp lệ và băm khớp `content_hash` thì trong một giao dịch chuyển `DA_DUYET`, ghi `approved_by/at`, `sign_request_id`, đặt `is_current` (bỏ `is_current` của phiên bản hiệu lực cũ), ghi outbox EVT-03 (`KeHoachDaDuyet`) và EVT-21 MSG-04-13 tới người duyệt, đánh dấu job `HOAN_TAT`, báo workflow-service hoàn thành tiến trình.
- Ký lỗi hoặc quá 120 giây không có xác nhận MySign (MSG-04-09): giữ `CHO_DUYET`, job báo lỗi có thể thử lại; hàng đợi ký thử lại phía esign-service; tuyệt đối không chuyển `DA_DUYET` khi chưa có chữ ký.
- BR-04-09: phiên bản `DA_DUYET` bất biến; thêm kiểm thử mọi đường ghi vào phiên bản và dòng đã duyệt đều bị chặn (ở lớp domain và, nếu chưa có, trigger CSDL bổ sung qua migration mới).
- Viết schema Avro `KeHoachDaDuyet` cho `task.plan.approved.v1` trong `contracts/events/avro/` nếu chưa có, chế độ BACKWARD; payload đủ để CORE-02-11 sinh nhiệm vụ mà không phải đọc lại nhiều lần (id kế hoạch, phiên bản, kỳ, đơn vị, phiên bản cũ nếu là điều chỉnh).
- Gợi ý tách: (a) API duyệt, kiểm quyền, AAL2, job và yêu cầu ký; (b) consumer EVT-18, chuyển trạng thái, EVT-03, thử lại.

## 4. Ngoài phạm vi

- Bộ kết nối MySign và hàng đợi ký trong esign-service (DV-10-02); sinh nhiệm vụ từ EVT-03 (CORE-02-11).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ migration mới nếu cần trigger bất biến)
- `contracts/events/avro/task/**` (chỉ thêm schema EVT-03 nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka với esign-service giả lập.
- Người lập tự duyệt nhận 403; phiên quá 15 phút từ lần xác thực AAL2 nhận lỗi yêu cầu xác thực bước lên.
- EVT-18 đến hai lần chỉ chuyển trạng thái và phát EVT-03 một lần; EVT-18 có băm lệch thì không chuyển `DA_DUYET` và ghi cảnh báo.
- Sau khi duyệt phiên bản 2, chỉ phiên bản 2 có `is_current` với trạng thái `DA_DUYET` (UQ một phần không bị vi phạm).
- Báo cáo cuối task theo AGENTS.md mục 6, nêu đề nghị bổ sung hợp đồng cho chấm điểm việc ngoài danh mục.

## 7. Báo cáo

Theo AGENTS.md mục 6.
