# CORE-04-01 · MOD-04 Migration schema `appraisal` và trigger `period_state` bảo vệ dữ liệu kỳ đã khoá

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A `appraisal_profile`, `general_criteria_result`, `self_assessment`; B `score_snapshot`, `rating_proposal`, `rating_explanation`, `batch_decision`; C `annual_rating`, `prospect_group`; D `period_state` và trigger chặn ghi) |
| Phụ thuộc | CORE-00-02, NEN-033 (bổ sung đề xuất: CORE-01-01, CORE-02-01, CORE-03-01 phải gộp trước phần D vì trigger gắn vào bảng của các schema đó) |
| Truy vết | MOD-04 → bảng `appraisal.*` (9 bảng); `okr.period_state`, `task.period_state`, `product.period_state` → nhận EVT-09, EVT-22, EVT-26 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo schema `appraisal` đầy đủ theo TL-03 với các bảo đảm tái lập và bất biến (ảnh chụp điểm không sửa, mức đề xuất không xoá, mỗi bước lưu riêng), cùng lớp bảo vệ thứ hai ở CSDL cho dữ liệu kỳ đã khoá: bảng `period_state` và hàm `fn_guard_period_write()` trong các schema `okr`, `task`, `product`, cộng trigger chặn ghi bảng con khi hồ sơ đánh giá đã `DA_KHOA`. Khi xong, không đường ghi nào vòng qua ứng dụng sửa được dữ liệu kỳ đã chốt.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Schema appraisal (MOD-04)" (dòng 2607), "Bảo vệ dữ liệu kỳ đã khoá ở tầng CSDL" (dòng 2623), "DDL mẫu: appraisal.score_snapshot" (dòng 2665), "DDL mẫu: appraisal.rating_proposal" (dòng 2728), các bảng dòng 2787–2969; danh mục "MOD-04 appraisal" (dòng 405).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9, 9.1 (trạng thái hồ sơ) và mục 7 (EVT-09, EVT-22, EVT-26).
- Bản trích `tl03-phan2 §A1`; `contracts/openapi/modules/appraisals.yaml` (đối chiếu tên trường).

## 3. Phạm vi công việc

- Tạo `appraisal_profile` (UQ `(period_bucket, tenant_id, period_id, person_id, flow)`, `flow IN ('DANG','NHA_NUOC')`, CHECK `status` theo baseline 9.1 gồm nhánh `KHIEU_NAI`, CHECK `decider_id <> appraiser_id` và cả hai khác `person_id`), `general_criteria_result`, `self_assessment` (cột C2 `_enc` + `dek_id`, `content_hash`).
- Tạo `score_snapshot` theo DDL mẫu: CHECK `snapshot_kind`, `auto_rating_code`, băm 32 byte, luồng `NHA_NUOC` bắt buộc có `general_criteria_score` và `kpi_score`, UQ một phần `uq_score_snapshot__current_p`; `REVOKE UPDATE, DELETE` và chỉ `GRANT UPDATE (superseded_by_id, updated_at, updated_by, row_version)`.
- Tạo `rating_proposal` theo DDL mẫu với CHECK vai trò theo `level_kind`, `ck_..._actor_is_human`, `ck_..._signed`, UQ một phần theo `is_current`, `REVOKE DELETE`; tạo `rating_explanation`, `batch_decision` (`profile_count` 1..1000, `status` năm giá trị).
- Tạo `annual_rating` (CHECK `period_bucket % 10 = 0`) và `prospect_group` với quyền cột: chỉ `svc_appraisal` được SELECT `group_code`, `ro_appraisal` không; COMMENT `{"cls":"C2","enc":false,"justification":...}` để CI chấp nhận.
- Tạo `period_state` và `fn_guard_period_write()` cho từng schema `okr`, `task`, `product` theo DDL mẫu (ERRCODE `object_not_in_prerequisite_state`, bắt buộc `app.reopen_request_id` khi `MO_LAI_HAU_KIEM`); gắn trigger vào các bảng thực hiện theo kỳ của ba schema (danh sách bảng ghi rõ trong báo cáo).
- Viết consumer có inbox cập nhật `period_state` từ EVT-09, EVT-22, EVT-26 và sự kiện bắt đầu khoá của saga (nếu DV-06-06 đã định nghĩa).
- Thiết kế trigger chặn ghi bảng con của hồ sơ ở `DA_KHOA` (TL-03 không có DDL mẫu, ghi "(suy luận)" trong chú thích migration).
- Tạo outbox, inbox, `idempotency_key` cho schema `appraisal`; COMMENT phân loại cho mọi cột.
- Điểm lệch cần lưu ý: `annual_rating` có cả `relocation_proposed` và `reassign_flag` cùng nghĩa; giữ cả hai như TL-03, dùng `reassign_flag` trong mã (theo TL-02b) và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Kiểm trạng thái kỳ ở tầng ứng dụng và lỗi 409 `period-locked` (CORE-08-03); domain hồ sơ (CORE-04-02 trở đi).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/appraisal/**`
- `backend/mission-core/src/main/resources/db/migration/okr/**`, `.../task/**`, `.../product/**` (chỉ thêm migration mới cho `period_state`, hàm và trigger)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/infrastructure/**` (consumer `period_state`)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh với Testcontainers PostgreSQL 17.
- Kiểm thử: UPDATE cột cấm của `score_snapshot` lỗi; hai ảnh chụp hiện hành cùng hồ sơ lỗi; DELETE `rating_proposal` lỗi; `ro_appraisal` SELECT `group_code` lỗi; ghi `task.task` khi `period_state = 'DA_KHOA'` lỗi; khi `MO_LAI_HAU_KIEM` ghi được chỉ khi có `app.reopen_request_id`.
- Đo chi phí trigger trên 10.000 dòng ghi, kết quả gần mức < 20 µs/dòng của TL-03 và ghi vào báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
