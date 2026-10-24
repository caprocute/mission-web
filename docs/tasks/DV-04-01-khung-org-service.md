# DV-04-01 · SVC-04 Khung org-service và migration schema `org` hai trục thời gian có ràng buộc loại trừ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | NEN-019, NEN-033; NEN-015 (cột `_enc`, `_bidx`, `keyring.dek`) và NEN-032 (bảng outbox, inbox, idempotency mẫu) nên có trước (suy luận) |
| Truy vết | QT-01 → SVC-04 org-service → schema `org`: `org_change_order`, `subject_group`, `job_title`, `org_unit`, `org_unit_relation`, `person`, `person_contact`, `person_public_key`, `position`, `assignment`, `reporting_line`, `scim_sync_log`, `sync_discrepancy`, `outbox_event`, `inbox_processed`, `idempotency_key`; ADR-02, ADR-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng org-service (Java 21, Spring Boot 3.5) từ mẫu service chuẩn và toàn bộ lược đồ `org` của TL-03 theo mô hình hai trục thời gian: trục hiệu lực `valid_from/valid_to` và trục ghi nhận `recorded_from/recorded_to`, không bao giờ sửa nội dung một phiên bản mà đóng `recorded_to` rồi chèn phiên bản mới. Khi xong, ràng buộc loại trừ GiST ở CSDL chặn chồng lấn phiên bản và hai phân công chính cùng lúc; tài khoản ứng dụng chỉ cập nhật được cột đóng phiên bản; cột C2 lưu dạng mã hoá.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-04 org-service" (dòng 1028–1046).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "SVC-04 org-service" (dòng 475–486), "Schema org (SVC-04 org-service)" (dòng 1046–1328, DDL mẫu `org.assignment` dòng 1060–1115), "Dữ liệu tổ chức hai trục thời gian" (dòng 4020–4086), "Mã hoá dữ liệu" (dòng 4142), "Bảo mật theo dòng (Row-Level Security)" (dòng 684).
- Baseline `00-co-so-thiet-ke-chung.md` mục 9 (schema `org`).
- Hợp đồng: `contracts/openapi/modules/org.yaml` (để thấy trường nào được trả ra).
- Wiki: `wiki/04-ma-dinh-danh-va-truy-vet.md`, `wiki/08-bao-mat-frontend.md` (phân lớp C0–C3), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Sinh org-service bằng `scripts/new-service.sh` (NEN-019): kiến trúc lục giác, actuator, probe, nhật ký JSON, OpenTelemetry, giao diện API sinh từ `org.yaml` bằng openapi-generator (`interfaceOnly=true`), chart Helm.
- Migration nhóm 1: bật `btree_gist`; mẫu cột hai trục (cột sinh `valid_period daterange`, `recorded_period tstzrange`, CHECK `valid_from < valid_to`, `recorded_from < recorded_to`, EX chống chồng lấn phiên bản, chỉ mục một phần `WHERE recorded_to = 'infinity'`); bảng `org_change_order`, `subject_group` (`K1`…`K9`), `job_title`, `org_unit`, `org_unit_relation` (EX theo `tree`, `child_unit_id` với `relation_type = 'TRUC_THUOC'`).
- Migration nhóm 2: `person` (cột `birth_date_enc`, `national_id_enc`, `party_member_no_enc`, `national_id_bidx`, `party_member_no_bidx`, `dek_id`), `person_contact`, `person_public_key`, `position`; mọi cột có `COMMENT ON COLUMN` phân loại C0–C3.
- Migration nhóm 3: `assignment` với `ex_assignment__one_primary` và `ex_assignment__version_no_overlap`, chỉ mục `ix_assignment__person_current_p`, `ix_assignment__unit_current_p`; `reporting_line`.
- Migration nhóm 4: `scim_sync_log` phân vùng tháng bằng `pg_partman` giữ 13 tháng, `sync_discrepancy`; `outbox_event`, `inbox_processed`, `idempotency_key`; RLS `rls_<bảng>__tenant` cho mọi bảng; vai trò `svc_org` chỉ `UPDATE (recorded_to, updated_at, updated_by, row_version)` theo cột và không `DELETE` trên bảng hai trục.
- Viết thành phần ghi phiên bản dùng chung (một giao dịch: đóng `recorded_to = now()` rồi chèn bản mới với `recorded_from = now()`) và hàm `org.assignment_asof(tenant, valid date, recorded timestamptz)` cùng CTE đệ quy cây đơn vị theo TL-03 dòng 4048–4076.
- Kiểm thử Testcontainers PostgreSQL 17 theo kịch bản V1/V2/V3 (Hình 7.1 TL-03): hai phân công chính chồng lấn bị chặn; đóng rồi chèn thành công; `svc_org` không UPDATE được cột nghiệp vụ; hai tenant không thấy dữ liệu của nhau qua RLS.
- Điểm lệch cần lưu ý: `org.scim_sync_log` phân vùng theo `received_at` nên khoá chính phải chứa `received_at`, làm khoá ngoại `sync_discrepancy.sync_run_id → scim_sync_log(id)` không khả thi (suy luận); chọn bỏ khoá ngoại vật lý, giữ tham chiếu logic có chỉ mục và ghi vào wiki trang 14. TL-01 gọi bảng là `org.position_assignment`, TL-03 là `org.assignment`; dùng tên TL-03.
- Gợi ý tách theo bốn nhóm migration ở trên (TL-03 cũng chia T-DB-O1..O4).

## 4. Ngoài phạm vi

- API đơn vị, thay đổi tổ chức, phân công, nhập Excel, SCIM, đối soát (DV-04-02..DV-04-07).
- Bản sao `org_replica` trong mission-core (CORE-00-03).

## 5. Tệp được phép sửa

- `backend/services/org-service/**` (mã, `src/main/resources/db/migration/org/`, kiểm thử)
- `backend/pom.xml` (thêm mô-đun)
- `deploy/charts/org-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh: Testcontainers PostgreSQL 17 cho bốn ca ở mục 3, ArchUnit kiến trúc lục giác, lint tên và chú thích phân loại cột (NEN-030).
- `helm lint deploy/charts/org-service` và `helm template | kubeconform -strict` không lỗi.
- Không có cột C2 nào ở dạng rõ; `grep` migration không thấy `DELETE` được cấp cho `svc_org`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
