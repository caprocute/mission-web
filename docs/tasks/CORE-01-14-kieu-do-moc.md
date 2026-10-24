# CORE-01-14 · MOD-01 Kiểu đo mốc có thời hạn, mốc trung gian, chiều tối đa, tử số và mẫu số (DC-04)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-14` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | NEN-044, CORE-01-01, CORE-08-04 |
| Truy vết | ADR-33 DC-04 → API `okr.yaml` (KR, `milestones`) → MOD-01 → bảng `okr.key_result`, `okr.kr_milestone` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

KR có thể đo bằng mốc có thời hạn với mốc trung gian và điều kiện công nhận; lõi ghi ngày đạt và số ngày làm việc chậm theo lịch nghỉ, còn cách quy đổi ra tỷ lệ hoàn thành nằm trong gói.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): "DC-04".
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- Migration `okr/V2__okr_ket_qua_check_in.sql`; TL-03 schema `okr`; lịch làm việc CORE-08-04.

## 3. Phạm vi công việc

- Migration: CHECK `metric_type` thêm `MOC`; CHECK `direction` thêm `TOI_DA`; cột `numerator_label`, `denominator_label`; bảng `okr.kr_milestone` (`key_result_id`, `seq`, `due_date`, `recognition_condition`, `achieved_at`, `evidence_ref`, cột chung, RLS).
- API tạo, sửa (If-Match), ghi nhận đạt mốc (Idempotency-Key); tính số ngày làm việc chậm qua lịch của MOD-08.
- Công bố số liệu mốc trong `OkrApi` để `inputs` của MOD-04 đọc được.

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Quy đổi tỷ lệ hoàn thành (thuộc gói); giao diện check-in mốc (task WEB).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/main/resources/db/migration/okr/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh.
- Kiểm thử: mốc đạt sau hạn qua một kỳ nghỉ lễ đếm đúng số ngày làm việc; KR bốn kiểu đo cũ không đổi hành vi; cách ly tenant của `kr_milestone`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
