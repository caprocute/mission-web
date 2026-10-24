# CORE-06-01 · MOD-06 Migration schema `dossier`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033 |
| Truy vết | QT-11 → MOD-06 `dossier` → bảng `dossier.review_meeting`, `dossier.review_comment`, `dossier.dossier_package`, `dossier.form_instance`, `dossier.special_case`, `dossier.remediation_item` → (nhận EVT-12, EVT-17, EVT-18, EVT-22 ở task sau) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo bộ migration Flyway cho schema `dossier` của MOD-06 đúng từ điển TL-03: hội nghị kiểm điểm, góp ý mã hoá C2, gói hồ sơ số, biểu mẫu đã sinh, trường hợp đặc thù và hạn chế cần khắc phục. Khi xong, các task CORE-06-02..08 có nền bảng ổn định, phân vùng theo kỳ và RLS theo tenant.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Schema dossier (MOD-06)" (dòng 2971) và các bảng `dossier.review_meeting` (dòng 2985), `dossier.review_comment` (dòng 3005), `dossier.dossier_package` (dòng 3021), `dossier.form_instance` (dòng 3040), `dossier.special_case` (dòng 3059), `dossier.remediation_item` (dòng 3075); danh mục "MOD-06 dossier" (dòng 429).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11 "Trạng thái đối tượng" (dòng 562).
- `contracts/openapi/modules/dossiers.yaml`: lược đồ `DossierPackageCreate`, `DossierMeeting*`, enum trạng thái hội nghị.
- Bản trích `tl03-phan2 §A2`.

## 3. Phạm vi công việc

- Tạo sáu bảng phân vùng `LIST (period_bucket)`, PK `(period_bucket, id)`, bảy cột chung, RLS theo mẫu NEN-033, COMMENT phân loại cho mọi cột.
- `review_meeting`: CHECK `meeting_type`, `status` (`DU_KIEN`, `DA_GUI_DU_THAO`, `DA_HOP`, `DA_LAP_BIEN_BAN`, `DA_KY`), `participants_json`, `vote_json`, `minutes_sha256` 32 byte.
- `review_comment`: FK `meeting_id`, CHECK `comment_kind`, `kind`, `phase`, `comment_enc` bắt buộc + `dek_id` FK `keyring.dek`; `REVOKE UPDATE, DELETE` vì góp ý không sửa (suy luận từ yêu cầu lưu vết biên bản).
- `form_instance`: CHECK `form_code` (`MAU_1A`..`MAU_1D`, `MAU_2`, `MAU_3`, `BANG_CHAM_DIEM`), `status`, băm 32 byte; `special_case`: CHECK `detected_by`, `effect`, `detail_enc` C2; `remediation_item`: CHECK `status`, `content_enc` C2.
- `dossier_package`: CHECK `package_kind`, `status` sáu giá trị, `transferred_to`, `code` UQ trong tenant (định dạng `DP-yyyy-{Q/N}-nnnn` kiểm ở mã); trigger chặn mọi UPDATE nội dung khi `status IN ('DA_NOP','DA_LUU_TRU')` trừ chuyển sang `DA_LUU_TRU` (BR-11-10).
- Tạo outbox, inbox, `idempotency_key` cho schema `dossier`.
- Điểm lệch cần lưu ý: TL-03 đặt UQ gói theo người (`person_id`, `package_kind`), còn TL-02b ("một gói cho mỗi đơn vị và kỳ") và hợp đồng (`DossierPackageCreate` có `orgUnitId`, không có `personId`) lập gói theo đơn vị. Chọn theo hợp đồng: thêm `org_unit_id`, UQ `(period_bucket, tenant_id, org_unit_id, package_kind)`, giữ `person_id` có thể rỗng cho gói con diện TW theo người (suy luận); ghi rõ là cột bổ sung trong chú thích migration và vào `wiki/14`.

## 4. Ngoài phạm vi

- Domain, API, consumer (CORE-06-02..08); bảng `docgen.*` của SVC-11 (DV-11-01).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL 17 chạy Flyway từ đầu.
- Kiểm thử: hai gói cùng đơn vị, kỳ, loại bị UQ chặn; sửa gói `DA_NOP` bị trigger chặn; rò rỉ tenant không xảy ra trên sáu bảng; lint đặt tên và kiểm tra COMMENT phân loại xanh.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
