# CORE-02-02 · MOD-02 Migration schema `task` phần nhiệm vụ có phân vùng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-01 |
| Truy vết | QT-05, QT-06 → MOD-02 `task` → bảng `task.task`, `task.task_assignee`, `task.task_progress`, `task.task_blocker`, `task.task_change_request`, `task.task_source_doc`, `task.task_state_log` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo phần nhiệm vụ của schema `task`, nơi có khối lượng ghi lớn nhất hệ thống (ở CELL-HCM mỗi quý khoảng 14,4 triệu nhiệm vụ, 58 triệu dòng tiến độ, 72 triệu dòng nhật ký trạng thái). Bảng phải phân vùng theo kỳ, chỉ mục đúng các truy vấn nóng ("Việc của tôi", nhiệm vụ quá hạn, tìm gần giống) và tự bảo vệ các bất biến: một chủ trì, tiến độ và nhật ký trạng thái chỉ ghi thêm, người quyết định đề nghị khác người gửi.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema task (MOD-02)", "DDL mẫu: task.task" (L2039–2113) và các bảng `task.task` (L2238), `task.task_assignee` (L2274), `task.task_progress` (L2293), `task.task_blocker` (L2309), `task.task_change_request` (L2328), `task.task_source_doc` (L2353), `task.task_state_log` (L2369).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Trạng thái đối tượng" (L1437–1455), QT-06 "Trạng thái đối tượng" (L1708–1722).
- `contracts/openapi/modules/tasks.yaml` lược đồ `TaskStatus`, `TaskSource`, `AssigneeRole`, `BlockerStatus`, `ChangeRequestType`, `ChangeRequestStatus`.
- Bản trích `extract/tl03-phan1.md` mục C7 (T-DB-T3..T5) và E.

## 3. Phạm vi công việc

- Tạo `task.task` đúng DDL mẫu: PK `(period_bucket, id)`, UQ `uq_task__code (period_bucket, tenant_id, code)`, CHECK `status` bảy giá trị (`NHAP`…`HUY`), `source` IN (`KE_HOACH`,`VAN_BAN`,`DANG_KY`,`DOT_XUAT`), `progress_pct` 0–1, `HOAN_THANH ⇒ completed_at`, `is_restricted`, `dup_fingerprint`, `state_machine_version`, cùng các cột thêm từ TL-02a (`acceptor_id`, `milestones`, `score_due_date`, `delay_risk`); sáu chỉ mục `ix_task__tenant_due_p`, `ix_task__plan_item`, `ix_task__key_result`, `ix_task__program`, `ix_task__dup`, `ix_task__overdue_p`.
- Tạo `task_assignee` với UQ một phần `uq_task_assignee__one_lead_p (period_bucket, task_id) WHERE role='CHU_TRI' AND removed_at IS NULL`, chỉ mục `(tenant_id, person_id, period_bucket)` cho "Việc của tôi", hai trục thời gian và ràng buộc EXCLUDE chống chồng lấn theo `(task_id, person_id, role)` có thêm `period_bucket WITH =` (bắt buộc với bảng phân vùng, suy luận theo giới hạn PostgreSQL).
- Tạo `task_progress` (UQ `(period_bucket, tenant_id, idempotency_key)`, chỉ ghi thêm), `task_blocker` (status IN (`MO`,`DANG_XU_LY`,`DA_GIAI_QUYET`,`DONG`)), `task_change_request` (status năm giá trị, CHECK `decided_by <> created_by`), `task_source_doc` (`source_system`, `paragraph_no`), `task_state_log` (REVOKE UPDATE, DELETE từ `svc_task`).
- Mọi bảng vệ tinh mang `period_bucket` của nhiệm vụ cha và FK hai cột tới `task.task`; bật RLS cha và con; tạo phân vùng qua `fn_create_period_partitions`.
- Điểm lệch cần lưu ý: UQ của `task_source_doc` trong TL-03 dùng cột `doc_ref` không có trong bảng; task dùng `external_doc_id` thay cho `doc_ref` (khoá theo mã văn bản + số đoạn đúng ý BR-05-07) và ghi `wiki/14`. Cặp `source`/`source_type` (TL-02a có `PHAT_SINH`, TL-03 có `DOT_XUAT`) và `key_result_id`/`kr_id`: chỉ tạo `source` và `key_result_id` theo TL-03 và hợp đồng, không tạo cột trùng nghĩa.
- Điểm lệch cần lưu ý: TL-03 đặt CHECK `task_change_request.request_type` IN (`GIA_HAN`, `DIEU_CHINH_KHOI_LUONG`, `DIEU_CHINH_NOI_DUNG`, `HUY`), còn TL-02a và hợp đồng `ChangeRequestType` dùng `GIA_HAN`, `DOI_SO_LUONG`, `DOI_SAN_PHAM`, `TAM_DUNG`, `HUY` và đã ghi "TL-03 cần sửa ràng buộc CHECK"; task theo hợp đồng (API-first), dùng năm giá trị của hợp đồng trong CHECK và ghi `wiki/14`.
- Ghi chú phân loại C0–C3 cho từng cột; `note` của tiến độ là C1 theo TL-01.

## 4. Ngoài phạm vi

- Bảng E2EE `task.task_secret_content`, `task.e2ee_key_wrap` (bổ sung C3, ADR-06): không nằm trong hàng khung này, đề nghị có task riêng.
- Máy trạng thái và API (CORE-02-13 trở đi).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/infrastructure/migration/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh với Testcontainers PostgreSQL 17.
- Kiểm thử: thêm chủ trì thứ hai đang hiệu lực bị UQ chặn; UPDATE hoặc DELETE `task_state_log` dưới vai trò `svc_task` bị từ chối; `task_change_request` có `decided_by = created_by` bị CHECK chặn; `HOAN_THANH` thiếu `completed_at` bị chặn.
- EXPLAIN truy vấn "Việc của tôi" theo `(tenant_id, person_id, period_bucket)` và truy vấn quá hạn chỉ chạm phân vùng kỳ hiện tại và dùng đúng chỉ mục.
- Sinh 1 triệu nhiệm vụ giả lập trong một kỳ, truy vấn "Việc của tôi" p95 ≤ 20 ms (suy luận theo ngân sách SCR-0503).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
