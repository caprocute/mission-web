# CORE-02-01 · MOD-02 Migration schema `task` phần danh mục và kế hoạch

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033; cần NEN-032 (mẫu outbox, inbox, idempotency) và CORE-08-01 (`config.period`) đã gộp (bổ sung) |
| Truy vết | QT-04 → MOD-02 `task` → bảng `task.work_catalog`, `task.catalog_item`, `task.program`, `task.program_milestone`, `task.plan`, `task.plan_version`, `task.plan_item`, `task.outbox_event`, `task.inbox_processed`, `task.inbox_aggregate_version`, `task.idempotency_key` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo bằng Flyway phần danh mục công việc (Bảng 3) và kế hoạch công tác quý có phiên bản (Bảng 1) của schema `task`, cùng các bảng kỹ thuật outbox, inbox, idempotency của schema này. Các bất biến cốt lõi được CSDL tự bảo vệ: chỉ một phiên bản kế hoạch đã duyệt hiện hành, mỗi dòng kế hoạch đúng một chủ trì, phiên bản sau phiên bản 1 phải có lý do điều chỉnh.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema task (MOD-02)" (L2017–2038) và các bảng `task.work_catalog` (L2117), `task.catalog_item` (L2133), `task.program`/`task.program_milestone` (L2156), `task.plan` (L2180), `task.plan_version` (L2191), `task.plan_item` (L2212); các mục quy ước chung, "Bảng outbox_event", "Bảng idempotency_key".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Trạng thái đối tượng" (L1195–1209) và SCR-0401, SCR-0402 (bảng trường).
- `contracts/openapi/modules/tasks.yaml` lược đồ `WorkCatalogStatus`, `CatalogItemStatus`, `PlanVersionStatus`, `TaskGroup`.
- Bản trích `extract/tl03-phan1.md` mục C7 (hạng mục T-DB-T1, T-DB-T2) và E.

## 3. Phạm vi công việc

- Tạo `work_catalog` (UQ `(tenant_id, code, version)`, `scope`, `source`, `status`, `parent_catalog_id` tự tham chiếu), `catalog_item` (UQ `(work_catalog_id, code)`, `difficulty` 1–5, `standard_score` và `base_score` 0–100, `task_group` IN (`THUONG_XUYEN`,`THEN_CHOT`)), `program`, `program_milestone`; các bảng này không phân vùng.
- Tạo `plan` (PARTITION BY LIST `period_bucket`, UQ NULLS NOT DISTINCT `(period_bucket, tenant_id, period_id, owner_unit_id, owner_person_id)`), `plan_version` (status IN (`NHAP`,`CHO_DUYET`,`DA_DUYET`,`TRA_LAI`), UQ một phần `uq_plan_version__approved_p (period_bucket, plan_id) WHERE status='DA_DUYET' AND is_current`, CHECK `change_reason` bắt buộc khi `version_no > 1`, `content_hash`, `sign_request_id`, `wf_process_instance_id`), `plan_item` (CHECK đúng một trong `lead_unit_id`, `lead_person_id` khác NULL, `co_unit_ids uuid[]`, `origin_item_id`, `change_reason`, `co_assignees jsonb`).
- Tạo `task.outbox_event`, `task.inbox_processed`, `task.inbox_aggregate_version`, `task.idempotency_key` bằng tiện ích dùng lại của NEN-032; ENABLE + FORCE RLS cho mọi bảng; gọi `fn_create_period_partitions` cho bảng theo kỳ (biến thể 16 phân vùng `HASH (tenant_id)` ở cell pool).
- Ghi `COMMENT ON COLUMN` phân loại C0–C3 cho mọi cột; seed thư viện danh mục mẫu cho môi trường dev trong `data/seed/` (suy luận, không chứa dữ liệu thật).
- Điểm lệch cần lưu ý: `work_catalog.status` theo TL-03 là `NHAP`, `HIEU_LUC`, `NGUNG` trong khi hợp đồng `WorkCatalogStatus` là `NHAP`, `AP_DUNG`, `HET_HIEU_LUC` (đánh dấu suy luận); `catalog_item` không có cột `status` ở TL-03 nhưng hợp đồng có `CatalogItemStatus` và TL-02a đề xuất cột này. Task giữ CHECK theo TL-03 ở CSDL, ánh xạ sang giá trị hợp đồng ở lớp API (CORE-02-03), thêm cột `catalog_item.status` đánh dấu "bổ sung", và ghi cả hai điểm vào `wiki/14` để người phụ trách chốt.
- Các cặp cột trùng nghĩa (`version`/`version_no`, `standard_score`/`base_score`, `task_group`/`default_group`) tạo đủ như TL-03, ghi rõ cột nào là chuẩn cho API.

## 4. Ngoài phạm vi

- Bảng nhiệm vụ và vệ tinh (CORE-02-02); domain, use case, API (CORE-02-03 trở đi).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/infrastructure/migration/**`
- `data/seed/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh với Testcontainers PostgreSQL 17 áp toàn bộ migration.
- Kiểm thử: chèn hai `plan_version` `DA_DUYET` cùng `is_current` của một kế hoạch bị UQ chặn; `plan_item` có cả `lead_unit_id` lẫn `lead_person_id` bị CHECK chặn; `plan_version` số 2 thiếu `change_reason` bị chặn; `difficulty = 6` bị chặn.
- RLS: tenant B không đọc, không ghi được dòng của tenant A; lint tên và chú thích phân loại của NEN-030 không báo lỗi.
- Báo cáo cuối task theo AGENTS.md mục 6; `wiki/14` ghi các điểm lệch.

## 7. Báo cáo

Theo AGENTS.md mục 6.
