# DV-06-10 · BS-01/SCR-1506 API giám sát tiến trình theo mẫu: chuyển người xử lý, gia hạn, sơ đồ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-06-02 |
| Truy vết | QT-15 (BS-01) → SCR-1506 → `GET /api/v1/workflow/instances` (`listWorkflowInstances`), `GET /api/v1/workflow/instances/{businessKey}/diagram` (`getWorkflowDiagram`), `POST /api/v1/workflow/tasks/{taskId}/reassign` (`reassignWorkflowTask`), `POST /api/v1/workflow/tasks/{taskId}/extend` (`extendWorkflowTask`) → SVC-06 → bảng `flowable.wf_task_projection`; ghi sổ cái SVC-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản trị tenant xem các tiến trình của tenant mình, quản trị nền tảng xem tổng hợp theo cell, và cả hai xử lý tình huống thường gặp mà không phá tính tái lập của hồ sơ: chuyển người xử lý khi cán bộ nghỉ hoặc chuyển công tác, gia hạn một bước, xem sơ đồ với bước hiện tại. Khi xong, mọi thao tác ghi sổ cái, dữ liệu đọc từ bảng chiếu thay vì `ACT_*`, và không có API sửa biến hay nhảy bước.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md` mục "SCR-1506. Giám sát tiến trình theo mẫu".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08 SCR-0801 thao tác sơ đồ (dòng 2233).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2: đổi người duyệt khi điều động là uỷ quyền có lưu vết (dòng 1955–2019).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` các thao tác ở bảng truy vết.
- Wiki: `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-1506 nếu có, API `workflow`), `wiki/15-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `listWorkflowInstances` đọc từ `wf_task_projection` (lọc theo mẫu, trạng thái, quá hạn, đơn vị; phân trang con trỏ ≤ 200); `tenant_admin` chỉ thấy tenant mình, `platform_admin` thấy số tổng hợp theo cell, không thấy nội dung hồ sơ.
- Hiện thực `getWorkflowDiagram`: trả định nghĩa BPMN của phiên bản đang chạy và bước hiện tại dạng dữ liệu (không trả HTML hay SVG có script).
- Hiện thực `reassignWorkflowTask` (Idempotency-Key, lý do bắt buộc): người nhận mới phải hợp lệ theo vai trò và tách vai trò (OPA), cập nhật engine và bảng chiếu cùng giao dịch, ghi sổ cái qua outbox.
- Hiện thực `extendWorkflowTask` (Idempotency-Key, lý do bắt buộc): dời timer hạn của bước theo ngày làm việc, giới hạn theo tham số mẫu, ghi sổ cái.
- Không có đường nào sửa biến tiến trình hay chuyển bước tuỳ ý; kiểm thử khẳng định điều này ở tầng API.
- Kiểm thử: chuyển người xử lý sang người vi phạm tách vai trò bị 403; gia hạn vượt giới hạn bị 422; bản ghi sổ cái sinh đúng một lần cho mỗi thao tác; kiểm thử hợp đồng.

## 4. Ngoài phạm vi

- Màn hình SCR-1506 (WEB-062); chỉ số giám sát engine (DV-06-11).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Testcontainers, kiểm thử hợp đồng, `openapi-diff`; độ phủ dòng `domain`, `application` ≥ 80%.
- Truy vấn danh sách tiến trình không chạm bảng `ACT_RU_*` (kiểm bằng kiểm thử đếm câu SQL).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
