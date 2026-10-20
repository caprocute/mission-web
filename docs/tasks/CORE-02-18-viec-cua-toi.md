# CORE-02-18 · QT-05/SCR-0503 "Việc của tôi": danh sách con trỏ, lọc nhanh, nhận việc, xuất, cảnh báo khối lượng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-18` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-14; cần CORE-08-04 (ngày làm việc để tính "Trễ n ngày") (bổ sung) |
| Truy vết | QT-05 → SCR-0503, SCR-0504, SCR-0002 → API `GET /api/v1/tasks/mine`, `POST /tasks/{id}/accept`, `POST /tasks/export` (202), `POST /tasks/assign-check` (BR-05-11) → MOD-02 → bảng `task.task_assignee` (chỉ mục `(tenant_id, person_id, period_bucket)`), `task.task` → EVT-06; BR-05-11; MSG-05-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực truy vấn đọc nhiều nhất của cán bộ: danh sách "Việc của tôi" phân trang con trỏ, bộ lọc nhanh có số đếm, tình trạng "Trễ n ngày" theo ngày làm việc; nút nhận việc chuyển `DA_GIAO` → `DANG_THUC_HIEN`; xuất Excel danh sách đang lọc bằng tác vụ 202; cảnh báo người giao khi chủ trì đã quá nhiều việc trong tuần (BR-05-11). Truy vấn phải nhanh ở quy mô cell lớn vì 300.000 người dùng đồng thời mở trang này đầu tiên.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Mô tả các bước" (nhận việc trong 2 ngày làm việc, L1433; khối lượng việc, L1429), BR-05-11, SCR-0503 (L1585–1625, API L1613–1619), SCR-0504 (L1626–1659).
- `contracts/openapi/README.md` điểm chốt HD-04 (`GET /tasks/mine`).
- `contracts/openapi/modules/tasks.yaml`: `listMyTasks` (`MyTasksQuickFilter`: `TAT_CA`, `CHAM`, `SAP_HAN`, `CHO_NGHIEM_THU`, `HOAN_THANH`), `acceptTask`, `exportTasks`, `checkTaskAssignment`.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` danh mục kịch bản PERF liên quan "Việc của tôi".

## 3. Phạm vi công việc

- Hiện thực `listMyTasks`: nhiệm vụ người gọi là chủ trì hoặc phối hợp còn hiệu lực trong kỳ, con trỏ ổn định theo (hạn, id), `limit` mặc định 20, tối đa 200; số đếm mỗi bộ lọc nhanh tính bằng một truy vấn gộp; "Trễ n ngày" và "sắp hạn" tính theo lịch ngày làm việc tenant; nhiệm vụ `NHAP` không hiện trừ nhiệm vụ đăng ký của chính người gọi.
- Truy vấn chỉ chạm phân vùng kỳ và dùng chỉ mục `(tenant_id, person_id, period_bucket)`; không kiểm phạm vi đơn vị qua OPA cho danh sách này (theo HD-04), chỉ lọc theo người gọi.
- Hiện thực `acceptTask` (Idempotency-Key, If-Match) qua `TaskTransitionService` lệnh ACCEPT: chỉ chủ trì, `DANG_THUC_HIEN`, `accepted_at` trên `task_assignee`, EVT-06.
- Hiện thực `exportTasks` trả 202 + job; tệp Excel gồm đúng các cột hiển thị, không chứa dữ liệu C2; dùng cùng thư viện Excel đã duyệt ở CORE-02-04.
- Thêm quy tắc BR-05-11 vào `AssignmentChecker`: đếm nhiệm vụ đang mở của chủ trì trong tuần chứa hạn (ngưỡng tham số Policy Pack), vượt thì cảnh báo MSG-05-04, không chặn.
- Cung cấp truy vấn tóm tắt cho khối "Việc của tôi hôm nay" để CORE-00-04 dùng lại (qua `TaskApi` hoặc dịch vụ ứng dụng nội bộ).

## 4. Ngoài phạm vi

- Cập nhật tiến độ (CORE-02-19); trang chủ tổng hợp SCR-0002 (CORE-00-04); nhắc nhận việc quá 2 ngày (gộp vào CORE-02-23 nếu cần, ghi trong báo cáo).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử trên 1 triệu nhiệm vụ giả lập một kỳ: `listMyTasks` p95 ≤ 50 ms phía máy chủ, EXPLAIN chỉ chạm một phân vùng; duyệt hết danh sách bằng con trỏ không trùng, không sót.
- "Trễ n ngày" đúng qua cuối tuần và ngày lễ; nhận việc của người không phải chủ trì trả 403; nhận việc lặp cùng `Idempotency-Key` chỉ phát một EVT-06.
- Kiểm thử hợp đồng cho `listMyTasks`, `acceptTask`, `exportTasks` xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
