# DV-06-02 · SVC-06 Bảng `wf_template`, `wf_binding`, `wf_task_projection`; API hộp việc, hoàn thành, hoàn thành hàng loạt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-06-01 |
| Truy vết | QT-02, QT-04, QT-09 (SCR-0203 hộp việc) → `GET /api/v1/workflow/inbox` (`listWorkflowInbox`), `POST /api/v1/workflow/tasks/{taskId}/complete` (`completeWorkflowTask`), `POST /api/v1/workflow/tasks/batch-complete` (`batchCompleteWorkflowTasks`), `GET /api/v1/workflow/instances/{businessKey}/history` (`getWorkflowHistory`) → SVC-06 → bảng `flowable.wf_template`, `flowable.wf_binding`, `flowable.wf_task_projection` → EVT-11 (bên phát là MOD-04) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người duyệt mở hộp việc và thấy mọi việc cần duyệt của mình trong vài mili giây mà không phải truy vấn bảng `ACT_*` của Flowable lúc cao điểm; hoàn thành một việc hoặc tối đa 200 việc một lần. Khi xong, hộp việc đọc bảng phẳng `wf_task_projection` được cập nhật trong cùng giao dịch với engine, truy vấn < 5 ms, và lịch sử của một hồ sơ xem được theo business key.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-06 workflow-service" (dòng 1048–1070), mục "Bảng tham số theo tenant" (dòng 2021–2023), đoạn hộp việc `wf_task_projection` (dòng 2082) và Phụ lục C "Dịch vụ nền" (dòng 3865).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema flowable và saga (SVC-06)" từ điển dữ liệu (dòng 3241–3283).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` các thao tác ở bảng truy vết.
- Wiki: `wiki/06-quy-uoc-api.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng API `workflow`), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Migration Flyway tạo `wf_template` (UQ `tenant_id`, `template_code`, `version_no`; `bpmn_xml`, `bpmn_sha256`; `status` theo TL-03), `wf_binding` (UQ NULLS NOT DISTINCT `tenant_id`, `process_kind`, `subject_group_id`, `flow`, `period_type`), `wf_task_projection` (PK `task_id` = `ACT_RU_TASK.ID_`, chỉ mục `(tenant_id, assignee_id, due_at)` và `candidate_role`), outbox, inbox, idempotency.
- Viết `FlowableEventListener` cho `TASK_CREATED`, `TASK_ASSIGNED`, `TASK_COMPLETED` cập nhật `wf_task_projection` trong cùng giao dịch engine; tiêu đề việc chỉ mang dữ liệu C0/C1.
- Hiện thực `listWorkflowInbox` (`assignee=me` hoặc ứng viên theo nhóm `${tenantId}:${role}:${orgUnitId}`, phân trang con trỏ ≤ 200, lọc theo hạn), `getWorkflowHistory` (đọc lịch sử engine theo business key, kiểm quyền đọc qua OPA).
- Hiện thực `completeWorkflowTask` (Idempotency-Key, kiểm người gọi là assignee hoặc ứng viên hợp lệ, kiểm tách vai trò qua OPA) và `batchCompleteWorkflowTasks` (≤ 200 việc, kết quả từng việc, lỗi từng việc không làm hỏng cả lô).
- Lỗi khoá lạc quan của Flowable trả 409 Problem Details để client thử lại; đếm tỉ lệ lỗi này làm chỉ số.
- Kiểm thử: tạo và hoàn thành việc thì bảng chiếu đổi trong cùng giao dịch (rollback thì không đổi); truy vấn hộp việc trên 1 triệu dòng chiếu cho p95 < 5 ms ở CSDL; lô 201 việc bị 422; kiểm thử hợp đồng.
- Điểm lệch cần lưu ý: TL-01 ghi cột `assignee`, `candidate_key`, `title`, `status`; TL-03 ghi `assignee_id`, `candidate_role`; dùng tên TL-03 (thiết kế CSDL). Tiền tố `workflow` không có trong baseline mục 13 nhưng có trong hợp đồng; giữ hợp đồng.

## 4. Ngoài phạm vi

- Mẫu BPMN cụ thể (DV-06-03, DV-06-04); API lưu `wf_binding` (DV-06-08); chuyển người xử lý, gia hạn (DV-06-10).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Testcontainers, kiểm thử hợp đồng và `openapi-diff`; độ phủ dòng `domain`, `application` ≥ 80%.
- Số đo p95 truy vấn hộp việc ghi trong báo cáo (mục tiêu < 5 ms tại CSDL, API đọc p95 ≤ 300 ms theo NFR-03).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
