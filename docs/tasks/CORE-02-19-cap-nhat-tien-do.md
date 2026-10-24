# CORE-02-19 · QT-06/SCR-0601 Cập nhật tiến độ chỉ ghi thêm, EVT-05, xung đột 409, cập nhật hàng loạt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-19` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-13 |
| Truy vết | QT-06 → SCR-0601, SCR-0503, SCR-0504 → API `POST /api/v1/tasks/{id}/progress`, `POST /tasks/progress-batch` → MOD-02 → bảng `task.task_progress`, `task.task` (`progress_pct`), `task.idempotency_key`, `task.outbox_event` → EVT-05 `task.task.progressed.v1`; BR-06-02, BR-06-03; MSG-06-01 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực thao tác ghi nhiều nhất của cán bộ theo đúng mẫu `UpdateProgressUseCase` của TL-01: mỗi lần cập nhật thêm một dòng `task.task_progress` (không sửa, không xoá), cập nhật `progress_pct` của nhiệm vụ với khoá lạc quan, phát EVT-05 qua outbox, trả kết quả với ngân sách khoảng 25 ms phía máy chủ. Cập nhật đồng thời từ web và di động được phát hiện bằng 409 cho bản đến sau; ứng dụng di động gửi lại sau mất sóng bằng cùng `Idempotency-Key` không tạo dòng trùng.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Quản lý giao dịch" (mẫu `UpdateProgressUseCase`, L710–752), "Outbox" (L754).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 "Mô tả các bước" (L1693–1707), BR-06-02, BR-06-03 (L1724–1739), "Ngoại lệ và xử lý" (cập nhật đồng thời, L1749), SCR-0601 (L1754–1798, API L1784), "Liên kết thiết kế" (ngân sách 25 ms, L1849).
- `contracts/openapi/README.md` điểm chốt HD-03 (`POST /tasks/{id}/progress`).
- `contracts/openapi/modules/tasks.yaml`: `updateTaskProgress`, `updateTaskProgressBatch`, lược đồ `ProgressUpdate`, `ProgressChannel`.

## 3. Phạm vi công việc

- Hiện thực `updateTaskProgress` (Idempotency-Key, If-Match) theo mẫu TL-01: tra khoá idempotency trước; `findForUpdate` theo `row_version`, lệch trả 409 `TASK-409-VERSION-CONFLICT`; `PeriodApi.assertWritable`; ghi `task_progress` (`progress_pct`, `done_quantity`, `note`, `channel`), cập nhật `task.progress_pct`, outbox EVT-05 (`TienDoDaCapNhat`, phân loại C1); lưu kết quả vào khoá idempotency; `@Transactional(timeout = 5)`.
- Kiểm BR-06-03: chỉ chủ trì đổi phần trăm; người phối hợp chỉ ghi nhật ký (dòng tiến độ không đổi `progress_pct`), gửi phần trăm thì 403.
- Kiểm BR-06-02: phần trăm thấp hơn lần trước mà không có ghi chú thì 422 kèm MSG-06-01.
- Cập nhật lần đầu khi nhiệm vụ đang `DA_GIAO` tự chuyển `DANG_THUC_HIEN` qua máy trạng thái (QT-05) và phát EVT-06.
- Hiện thực `updateTaskProgressBatch`: mỗi mục mang `rowVersion` riêng, mỗi mục một giao dịch, kết quả từng mục (thành công, 409, 422), không để một mục lỗi làm hỏng mục khác.
- Viết schema Avro `TienDoDaCapNhat` cho `task.task.progressed.v1` nếu chưa có.
- Gắn chỉ số OpenTelemetry thời gian xử lý để theo dõi ngân sách 25 ms.

## 4. Ngoài phạm vi

- Dự báo nguy cơ trễ (SVC-15) và gợi ý tiến độ từ sản phẩm đã nộp; điểm nghẽn (CORE-02-20).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `contracts/events/avro/task/**` (chỉ thêm schema EVT-05 nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka.
- Hai cập nhật cùng `If-Match` gửi đồng thời: một thành công, một nhận 409; gửi lại cùng `Idempotency-Key` sau mất kết nối trả cùng kết quả, chỉ một dòng `task_progress`, một EVT-05.
- Phối hợp đổi phần trăm nhận 403; giảm phần trăm không ghi chú nhận 422 MSG-06-01.
- Kiểm thử vi mô: xử lý phía máy chủ p95 ≤ 25 ms trên Testcontainers với CSDL ấm (ngoài thời gian mạng).
- Kiểm thử hợp đồng cho hai operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
