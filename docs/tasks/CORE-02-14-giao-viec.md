# CORE-02-14 · QT-05/SCR-0501 Giao việc: nháp, sửa, xoá, giao đơn lẻ và đồng loạt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-14` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-13, CORE-00-03; cần CORE-01-02 (`OkrApi`) và CORE-02-05 (dòng kế hoạch đã duyệt) (bổ sung) |
| Truy vết | QT-05 → SCR-0501 → API `POST /api/v1/tasks`, `GET /tasks/drafts/new`, `GET /tasks/{id}/draft`, `PUT/DELETE /tasks/{id}`, `POST /tasks/assign-check`, `POST /tasks/{id}/assign`, `POST /tasks/bulk-assign` (202), `GET /tasks/{id}`, `GET /tasks/{id}/assignees`, `GET /tasks` → MOD-02, `org_replica` → bảng `task.task`, `task.task_assignee`, `task.task_state_log`, `task.outbox_event` → EVT-04 `task.task.assigned.v1`, EVT-21; BR-05-01..05, BR-05-09; MSG-05-01, MSG-05-02, MSG-05-14 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho người giao việc (AC-04, AC-05) soạn nhiệm vụ nháp, kiểm tra tức thì và giao: nhiệm vụ chuyển `NHAP` → `DA_GIAO`, ghi nhật ký trạng thái, phát EVT-04 để thông báo, tìm kiếm, phân tích và sổ cái cùng biết; giao đồng loạt cho nhiều đơn vị tạo mỗi đơn vị một nhiệm vụ riêng cùng nội dung, căn cứ và tính điểm riêng. Danh sách việc của đơn vị cho lãnh đạo xem cũng được hiện thực ở đây.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Mô tả các bước" (L1420–1436), "Quy tắc nghiệp vụ" BR-05-01..BR-05-05, BR-05-09 (L1457–1471), "Ngoại lệ và xử lý" (giao đồng loạt, L1482), SCR-0501 (L1486–1535, mã nhiệm vụ L1502, API L1522–1526).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Giao tiếp giữa module" (`AssignTaskUseCase` dùng `PeriodApi`, `OkrApi`, L553–557).
- `contracts/openapi/modules/tasks.yaml`: `createTaskDraft`, `getNewTaskDraft`, `getTaskDraft`, `saveTaskDraft`, `deleteTaskDraft`, `checkTaskAssignment`, `assignTask` (`AssignRequest`, `AssignResult`), `bulkAssignTasks`, `getTask`, `listTaskAssignees`, `listUnitTasks`.
- `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0501.

## 3. Phạm vi công việc

- Hiện thực CRUD nháp: `createTaskDraft`, `saveTaskDraft` (If-Match), `deleteTaskDraft` (chỉ khi `NHAP`), `getNewTaskDraft` và `getTaskDraft` (khung soạn có danh sách chọn chủ trì, phối hợp, người nghiệm thu, dòng kế hoạch, KR, mục danh mục); sinh mã `NV-yyyy-Qn-nnnn` duy nhất trong tenant.
- Viết `AssignmentChecker` dùng chung cho `checkTaskAssignment` và `assignTask`: BR-05-01 đúng một chủ trì (MSG-05-01); BR-05-02 người nghiệm thu khác chủ trì và phối hợp (MSG-05-02); BR-05-03 đủ sản phẩm, số lượng, đơn vị tính, tiêu chuẩn chất lượng, hạn trong kỳ; BR-05-04 nhiệm vụ từ kế hoạch thuộc phiên bản `DA_DUYET` hiệu lực; BR-05-09 nhiệm vụ ngoài kế hoạch phải có căn cứ; tên không chứa từ mơ hồ. Kết quả theo mức `CHAN`, `CANH_BAO`.
- Hiện thực `assignTask` (Idempotency-Key, If-Match) qua `TaskTransitionService` lệnh ASSIGN: `DA_GIAO`, `task_state_log`, outbox EVT-04 (`NhiemVuDaGiao`) và EVT-21 MSG-05-14 tới chủ trì; KR liên kết kiểm qua `OkrApi`; người được giao kiểm qua `org_replica` (còn công tác, thuộc phạm vi).
- Hiện thực `bulkAssignTasks` trả 202: mỗi đơn vị một nhiệm vụ riêng (mã riêng, chủ trì là đơn vị đó), mỗi nhiệm vụ một giao dịch, lỗi một đơn vị không chặn đơn vị khác, kết quả tổng hợp trong job.
- Hiện thực `getTask`, `listTaskAssignees`, `listUnitTasks` (danh sách việc của đơn vị, con trỏ, lọc theo kỳ, trạng thái, KR — phục vụ cả `GET /tasks?krId=` của SCR-0201) với kiểm phạm vi OPA; nhiệm vụ `is_restricted` che tiêu đề với người không có quyền.
- Viết schema Avro `NhiemVuDaGiao` cho `task.task.assigned.v1` nếu chưa có.
- Gợi ý tách: (a) CRUD nháp, đọc và danh sách đơn vị; (b) bộ kiểm tra, giao đơn lẻ, giao đồng loạt.

## 4. Ngoài phạm vi

- Tìm nhiệm vụ gần giống, BR-05-06 (CORE-02-15); nhiệm vụ từ văn bản và giao theo lô `assign-batch` (CORE-02-16); cảnh báo khối lượng BR-05-11 (CORE-02-18); nhắc và giao lại thuộc QT-16 (`remindTask`, `reassignTask`, không có trong hàng khung này).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `contracts/events/avro/task/**` (chỉ thêm schema EVT-04 nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka.
- Mỗi quy tắc BR-05-01..05, BR-05-09 có ca chặn với đúng `rule` trong `errors[]`; giao lặp cùng `Idempotency-Key` không phát EVT-04 hai lần; giao nhiệm vụ không còn `NHAP` trả 409.
- Giao đồng loạt cho 50 đơn vị tạo 50 nhiệm vụ mã khác nhau, mỗi nhiệm vụ một EVT-04.
- Kiểm thử hợp đồng cho mười một operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
