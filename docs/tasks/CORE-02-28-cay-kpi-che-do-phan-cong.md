# CORE-02-28 · MOD-02 Cây nhiệm vụ nhiều bậc (KPI, công việc, công việc con) và chế độ phân công theo hồ sơ (DC-04, DC-06)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-28` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | NEN-044, CORE-02-13, CORE-05-12 |
| Truy vết | ADR-33 DC-04, DC-06 → API `tasks.yaml` (`/tasks`) → MOD-02 → bảng `task.task` (`parent_task_id`, `node_kind`, `component_score`), `task.task_assignee` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Một tập khách hàng có thể phân rã nhiệm vụ thành KPI, công việc và công việc con, và bật quy tắc "mỗi công việc đúng một người, điểm việc cha chỉ tính khi mọi việc con xong" bằng tham số hồ sơ, trong khi mặc định vẫn là "một chủ trì, nhiều phối hợp".

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): "DC-04" (phần cây KPI), "DC-06".
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- Migration `task/V2__task_assignee_progress_state_log.sql` (cột `parent_task_id` đã có); mã CORE-02-13 (máy trạng thái từ Policy Pack).

## 3. Phạm vi công việc

- Migration: `node_kind varchar(16) NOT NULL DEFAULT 'NHIEM_VU'` với CHECK (`NHIEM_VU`, `KPI`, `CONG_VIEC`, `CONG_VIEC_CON`), `component_score numeric(6,3)`; ràng buộc cây không vòng và độ sâu theo khuôn phân rã của hồ sơ.
- Dịch vụ miền kiểm quy tắc chế độ phân công đọc từ `PolicyQueryApi`: với `MOT_NGUOI_MOT_VIEC`, việc lá đúng một người, tổng điểm thành phần của việc con bằng điểm việc cha, lỗi `TASK-422-ASSIGNMENT-MODE`.
- Tổng hợp trạng thái việc cha từ việc con (chỉ "xong" khi mọi việc con xong) dưới dạng hàm đọc dùng cho `inputs`.

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Màn giao việc (CORE-02-14 dùng lại dịch vụ miền này; giao diện ở task WEB).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh.
- Kiểm thử chạy cùng bộ với hai tham số: `CHU_TRI_PHOI_HOP` giữ hành vi cũ; `MOT_NGUOI_MOT_VIEC` chặn việc lá hai người và lệch tổng điểm thành phần.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
