# CORE-02-08 · QT-04/SCR-0403 Gửi duyệt kế hoạch: khoá nội dung, băm SHA-256, BPMN `duyet-ke-hoach`, trả lại

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-06, DV-06-03 |
| Truy vết | QT-04 → SCR-0402, SCR-0403 → API `POST /api/v1/plans/{id}/versions/{v}/submit`, `POST /plans/{id}/versions/{v}/return`, `GET /plans/approvals` → MOD-02, SVC-06 → bảng `task.plan_version` (`content_hash`, `submitted_at`, `return_reason`, `wf_process_instance_id`), `task.outbox_event`, `flowable.wf_binding`, `flowable.wf_task_projection` → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho người lập gửi duyệt phiên bản kế hoạch: máy chủ kiểm lại toàn bộ quy tắc, khoá nội dung, tính băm SHA-256 làm dữ liệu cho chữ ký số, chuyển `CHO_DUYET` và tạo tiến trình `duyet-ke-hoach`; người duyệt trả lại có ý kiến thì phiên bản về `TRA_LAI` và mở khoá. Băm nội dung bảo đảm thứ người duyệt ký đúng là thứ đã gửi.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Mô tả các bước" (L1178–1194), "Trạng thái đối tượng" (L1195–1209), SCR-0402 (API gửi L1325–1331), SCR-0403 (L1342–1382).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` đoạn ba tầng điều phối (L1887–1889), `wf_binding` (L2023).
- `contracts/openapi/modules/tasks.yaml`: `submitPlanVersion`, `returnPlanVersion`, `listPlanApprovals`.
- Thẻ CORE-01-05 (cơ chế khởi tạo tiến trình đã chọn cho `duyet-okr`, dùng lại nguyên cơ chế).

## 3. Phạm vi công việc

- Hiện thực `submitPlanVersion` (Idempotency-Key, If-Match): chỉ từ `NHAP` hoặc `TRA_LAI`; gọi `PlanChecker` (CORE-02-06), còn lỗi chặn trả 422 kèm danh sách; hợp lệ thì chuyển `CHO_DUYET`, ghi `submitted_at`, tính `content_hash` SHA-256 trên biểu diễn chuẩn hoá của phiên bản và các dòng (thứ tự `item_no`, định dạng số và ngày cố định), ghi outbox yêu cầu khởi tạo tiến trình `duyet-ke-hoach` trong cùng giao dịch.
- Viết đặc tả biểu diễn chuẩn hoá (canonical form) trong chú thích mã và `wiki/14` để esign-service và công cụ kiểm tra độc lập tính lại được cùng băm.
- Khoá nội dung: mọi `savePlanVersion` vào phiên bản `CHO_DUYET` trả 409; gửi lại sau `TRA_LAI` tính băm mới.
- Hiện thực `returnPlanVersion`: chỉ người duyệt, ý kiến bắt buộc; chuyển `TRA_LAI`, ghi `return_reason`, outbox EVT-21 tới người lập, báo workflow-service kết thúc tiến trình qua outbox.
- Hiện thực `listPlanApprovals`: kế hoạch chờ người gọi duyệt (kể cả duyệt thay theo `wf_binding`), đọc từ `wf_task_projection`, mỗi dòng kèm hạn duyệt của CORE-02-07.
- Điểm lệch cần lưu ý: TL-01 (L1066) ghi workflow-service "nhận EVT-03 … để khởi tạo tiến trình", trong khi EVT-03 `KeHoachDaDuyet` chỉ phát sau khi duyệt (TL-02a QT-04); task theo TL-02a: tiến trình `duyet-ke-hoach` khởi tạo khi gửi duyệt, dùng cơ chế của CORE-01-05, không tự đặt mã EVT mới, ghi `wiki/14`.

## 4. Ngoài phạm vi

- Duyệt có ký số và EVT-03 (CORE-02-09); điều chỉnh, so sánh phiên bản (CORE-02-10); mẫu BPMN (DV-06-03).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka.
- Băm của cùng một phiên bản tính hai lần (khác thứ tự đọc từ CSDL) giống nhau; đổi một ký tự trong một dòng làm băm khác.
- Gửi lặp cùng `Idempotency-Key` chỉ tạo một yêu cầu tiến trình; sửa khi `CHO_DUYET` trả 409; trả lại không ý kiến trả 422.
- Kiểm thử hợp đồng cho ba operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
