# CORE-02-27 · MOD-02 Danh mục bốn cấp kế thừa (khối, tỉnh), khoảng chỉnh điểm chuẩn, mức hệ số và trục trên mục danh mục (DC-01, DC-02)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-27` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | NEN-044, CORE-02-03, CORE-05-12 |
| Truy vết | ADR-33 DC-01, DC-02 → API `tasks.yaml` (`/catalog`, `/plans`, `/tasks`) → MOD-02 → bảng `task.work_catalog`, `task.catalog_item`, `task.plan_item`, `task.task` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Danh mục công việc kế thừa được qua bốn cấp hệ thống → khối → tỉnh → đơn vị, cấp dưới chỉ chỉnh điểm chuẩn trong khoảng cho phép, và mỗi mục mang mức của từng loại hệ số cùng trục, để scoring có đủ dữ liệu tính điểm công việc mà lõi không lưu giá trị hệ số.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): "DC-01", "DC-02".
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- Migration `task/V1__work_catalog_program_plan.sql`, `task/V2__task_assignee_progress_state_log.sql`; mã CORE-02-03, CORE-02-04 đã gộp.

## 3. Phạm vi công việc

- Migration: mở rộng CHECK `ck_work_catalog__scope` thêm `KHOI`, `TINH`; thêm `score_adjust_min`, `score_adjust_max` cho `catalog_item`; thêm `coefficient_levels jsonb NOT NULL DEFAULT '{}'` và `axis_code varchar(16)` cho `catalog_item`, `plan_item`, `task`.
- Kiểm khoảng chỉnh khi tạo hoặc sửa dòng kế thừa; sao chép mức hệ số và trục từ mục danh mục sang kế hoạch và nhiệm vụ khi giao.
- Mã loại hệ số hợp lệ đọc từ gói qua `PolicyQueryApi`, không viết cứng; độ khó 1–5 hiện có được coi là một loại hệ số.
- Nhập Excel (CORE-02-04) nhận thêm cột mức hệ số và trục.

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Giá trị hệ số và công thức (thuộc gói); giao diện.

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh.
- Kiểm thử: dòng tỉnh chỉnh điểm chuẩn +25% khi khung ±20% bị 422; mức hệ số lạ (không có trong gói của kỳ) bị 422; dữ liệu cũ không có mức hệ số vẫn đọc được.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
