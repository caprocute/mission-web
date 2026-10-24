# CORE-02-15 · QT-05/BR-05-06 Phát hiện nhiệm vụ gần giống và ghi lý do

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-15` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-14, DV-16-01 |
| Truy vết | QT-05 → SCR-0501, SCR-0502 → API `GET /api/v1/tasks/{id}/similar`, `POST /tasks/assign-check`, `POST /tasks/{id}/assign` (`similarReason`) → MOD-02, SVC-16, SVC-14 (`ai.doc_chunk`) → bảng `task.task` (`dup_fingerprint`, `ix_task__dup`) → BR-05-06; MSG-05-03 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Phát hiện nhiệm vụ gần giống trong cùng đơn vị và kỳ trước khi giao (ngưỡng tương đồng 0,7), để tránh một việc bị giao hai lần và bị tính điểm hai lần. Người giao phải chọn gộp hoặc ghi lý do vẫn tạo mới; lựa chọn được lưu để người duyệt và hậu kiểm xem lại.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 BR-05-06 (L1457–1471), SCR-0501 (API `similar`, L1525), "Liên kết thiết kế" (L1660–1662), QT-02 SCR-0202 (phát hiện KR gần giống nhiệm vụ, L953).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "DDL mẫu: task.task" (`dup_fingerprint`, `ix_task__dup`).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` dòng SVC-16 search (không chứa C2/C3).
- `contracts/openapi/modules/tasks.yaml`: `listSimilarTasks`, `checkTaskAssignment`, `assignTask`, lược đồ `SimilarTask`, `AssignRequest.similarReason`.

## 3. Phạm vi công việc

- Tính `dup_fingerprint` khi lưu nhiệm vụ (chuẩn hoá tiêu đề, sản phẩm, đơn vị chủ trì; băm) để bắt trùng gần như nguyên văn bằng chỉ mục `ix_task__dup` mà không cần gọi dịch vụ ngoài.
- Viết port `SimilarTaskFinder` và adapter gọi SVC-16 (và chỉ mục vector theo thiết kế của SVC-14 nếu SVC-16 chuyển tiếp), lọc cùng tenant, đơn vị, kỳ, ngưỡng 0,7, loại nhiệm vụ `is_restricted`; timeout ngắn, lỗi thì trả cảnh báo "chưa kiểm được" và không chặn giao (suy luận).
- Hiện thực `listSimilarTasks` sắp theo độ tương đồng giảm dần, khớp `SimilarTask`.
- Tích hợp vào `AssignmentChecker` (CORE-02-14): có nhiệm vụ gần giống thì `assignTask` bắt buộc `similarReason` (MSG-05-03), lưu lý do vào nhật ký trạng thái để hậu kiểm.
- Điểm lệch cần lưu ý: BR-05-06 cho chọn "gộp hoặc ghi lý do", nhưng hợp đồng chỉ có `similarReason` cho nhiệm vụ thường và `mergeSourceDocIntoTask` cho văn bản đến; task coi "gộp" với nhiệm vụ thường là huỷ bản nháp mới và mở nhiệm vụ cũ (không cần API mới) và ghi lựa chọn vào `wiki/14`.
- Cung cấp cùng port cho BR-02-06 (KR giống nhiệm vụ) của CORE-01-04 qua `TaskApi` nếu cần (suy luận).

## 4. Ngoài phạm vi

- Chỉ mục OpenSearch và indexer EVT-04 (DV-16-01); chỉ mục vector `ai.doc_chunk` (DV-14-02).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, dùng stub HTTP cho SVC-16.
- Kiểm thử: hai nhiệm vụ cùng tiêu đề khác khoảng trắng và dấu câu có cùng `dup_fingerprint`; giao khi có nhiệm vụ gần giống mà thiếu lý do trả 422 với `rule` BR-05-06; SVC-16 lỗi thì giao vẫn được kèm cảnh báo.
- Không trả nhiệm vụ của đơn vị hoặc tenant khác trong kết quả gần giống.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
