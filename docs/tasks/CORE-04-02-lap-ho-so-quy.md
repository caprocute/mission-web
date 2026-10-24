# CORE-04-02 · EVT-09, EVT-10 Lập hồ sơ quý theo tổ chức tại thời điểm khoá kỳ và lưu `score_snapshot`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-04-01, CORE-00-03 (bổ sung đề xuất: CORE-06-05 cho trường hợp đặc thù, có thể làm sau bằng cổng rỗng) |
| Truy vết | QT-08 bước 5, QT-09 bước 1 → MOD-04 → `appraisal.appraisal_profile` (`CHUA_LAP` → `TU_DANH_GIA`), `appraisal.score_snapshot`; đọc `org_replica.*` → nhận EVT-09 (`config.period.locked.v1`), EVT-10 (`scoring.score.computed.v1`) → phát EVT-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Sau khi kỳ quý khoá, MOD-04 tự lập hồ sơ đánh giá cho mọi cán bộ theo luồng `DANG`/`NHA_NUOC`, dựa trên phân công tổ chức có hiệu lực tại ngày khoá kỳ (hai trục thời gian), trong ≤ 15 phút; khi SVC-07 phát EVT-10, MOD-04 lưu ảnh chụp điểm và mở bước tự đánh giá. Đây là điểm vào của toàn bộ QT-09.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: "Mô tả các bước" (dòng 40, bước 1 dòng 44), "Trạng thái đối tượng" (dòng 65), "Ngoại lệ và xử lý" (dòng 113, ngoại lệ 2 và 6).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08 "Mô tả các bước" bước 2 và 5 (MOD-04 nhận EVT-10, tạo hồ sơ `CHUA_LAP`).
- TL-03 bảng `appraisal.appraisal_profile` (dòng 2787), DDL `score_snapshot` (dòng 2665, cách thay ảnh chụp hai bước dòng 2726); mục "Dữ liệu tổ chức hai trục thời gian" (dòng 4020).
- TL-03 danh mục "MOD-06 dossier" (dòng 429) và đoạn đầu "Schema dossier" (dòng 2975) về cổng `special_case`.
- Bản trích `tl02b §QT-09 BE-Q09-01`, `tl02a §QT-08 BE-08-7`.

## 3. Phạm vi công việc

- Viết consumer EVT-09 có inbox (khử trùng theo `event_id`, thứ tự theo `aggregate_version`): với kỳ quý, đọc `org_replica` tại `valid_date` = ngày khoá kỳ và `recorded_at` = thời điểm khoá, tạo `appraisal_profile` trạng thái `CHUA_LAP` cho từng người và từng luồng, ghi `org_asof_recorded_at`, `org_asof_valid_date`, `policy_pack_version_id`.
- Áp trường hợp đặc thù qua một cổng do MOD-04 khai báo và MOD-06 hiện thực (ví dụ hồ sơ "không xếp loại"); trước khi CORE-06-05 xong, cổng trả rỗng.
- Ghi lô bằng câu lệnh theo lô để đạt ≤ 15 phút cho số hồ sơ của cell lớn (1,2–2,4 triệu dòng/quý); xử lý lại an toàn khi consumer chạy lại.
- Viết consumer EVT-10 có inbox: thay ảnh chụp theo hai bước (đặt `superseded_by_id` cũ rồi chèn mới), đặt `current_snapshot_id`, chuyển `CHUA_LAP` → `TU_DANH_GIA`, phát EVT-11 và EVT-21 qua outbox; điểm tạm tính trước khoá kỳ (`TAM_TINH`) chỉ lưu ảnh chụp, không đổi trạng thái hồ sơ.
- Không có API ghi `score_snapshot` (BR-09-16); kiểm bằng ArchUnit rằng chỉ consumer được gọi kho ghi ảnh chụp.
- Điểm lệch cần lưu ý: TL-03 cho MOD-04 đọc `special_case` qua cổng của MOD-06, còn ma trận phụ thuộc (dossier phụ thuộc appraisal) cấm chiều ngược; chọn đảo phụ thuộc (cổng khai báo ở MOD-04) và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Tính điểm (DV-07-xx); hồ sơ năm (CORE-04-10); phát hiện trường hợp đặc thù (CORE-06-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `contracts/events/avro/` (chỉ thêm lược đồ EVT-11 nếu chưa có)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử: người điều động sau ngày khoá kỳ vẫn được lập hồ sơ ở đơn vị cũ; EVT-09 phát hai lần chỉ tạo một bộ hồ sơ; EVT-10 cũ hơn ảnh chụp hiện hành bị bỏ qua.
- Đo lập 100.000 hồ sơ trên máy kiểm thử và ngoại suy, ghi vào báo cáo so với ngân sách ≤ 15 phút.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
