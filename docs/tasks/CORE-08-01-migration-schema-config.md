# CORE-08-01 · MOD-08 Migration schema `config`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi; là migration đầu tiên của lõi vì mọi schema phân vùng theo kỳ lấy `period_bucket` từ `config.period`) |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033 |
| Truy vết | MOD-08 → schema `config`: `config.tenant_setting`, `config.period`, `config.period_status_log`, `config.holiday_calendar`, `config.ui_pack`, `config.ui_pack_version`, `config.form_schema`, `config.component_registry`, `config.feature_flag`, `config.role_binding`, `config.period_lock_order`, `config.period_lock_step`, `config.dashboard_def` → EVT-09, EVT-23, EVT-26 (qua outbox) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo toàn bộ schema `config` của MOD-08 bằng Flyway đúng TL-03, gồm bảng, ràng buộc, chỉ mục, RLS, trigger chuyển trạng thái kỳ và bảng outbox, inbox, idempotency của schema. Khi xong, một CSDL PostgreSQL 17 trống chạy migration lên được schema `config` hoàn chỉnh, kiểm thử chứng minh các ràng buộc nghiệp vụ ở tầng CSDL (kỳ không chồng lấn, chỉ năm chuyển trạng thái kỳ hợp lệ, bốn mắt ở `role_binding` và `period_lock_order`) hoạt động. Đây là nền cho mọi task CORE-08 còn lại và cho các schema theo kỳ của MOD-01..MOD-04.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema config (MOD-08)" (dòng 1405), "DDL mẫu: config.period" và từng mục "Bảng config.*" từ dòng 1481 đến 1673; mục quy ước đặt tên, kiểu dữ liệu chuẩn, cột chung, phân loại C0–C3 ở đầu tài liệu.
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-03.md` (bảng bổ sung, mâu thuẫn tên cụm CSDL).
- Baseline `00-co-so-thiet-ke-chung.md` mục 9.1 (trạng thái chuẩn) và danh mục EVT (EVT-09, EVT-23, EVT-26).
- Bản trích `extract/tl03-phan1.md` mục C4 (đã gom điểm lệch).
- `contracts/openapi/modules/config.yaml` (để chọn tên cột trùng nghĩa theo hợp đồng).

## 3. Phạm vi công việc

- Viết migration `V1__config_core.sql` cho `tenant_setting` (UQ `NULLS NOT DISTINCT (tenant_id, setting_key, scope_unit_id)`), `period` (EX `ex_period__no_overlap`, CHECK `period_bucket`, CHECK `DA_KHOA` ⇒ `locked_at`, trigger `tg_period__transition` gọi `config.fn_check_period_transition()` chỉ cho năm chuyển hợp lệ), `period_status_log` (chỉ ghi thêm), `holiday_calendar` (UQ `(tenant_id, calendar_date)`, `day_kind` ba giá trị).
- Viết migration `V2__config_ui.sql` cho `ui_pack`, `ui_pack_version` (trạng thái `NHAP`, `DANG_KIEM_TRA`, `CHO_DUYET`, `DA_PHAT_HANH`, `THAY_THE`), `form_schema` (trạng thái `NHAP`, `DA_PHAT_HANH`, `NGUNG`), `component_registry` (không có `tenant_id`, UQ `(registry_version, component_key)`, không bật RLS); tạo FK vòng `ui_pack.current_version_id` sau hoặc `DEFERRABLE` (suy luận).
- Viết migration `V3__config_access.sql` cho `feature_flag`, `role_binding` (CHECK `role_code` chín vai trò baseline mục 10, CHECK `approved_by <> granted_by`), `period_lock_order` (UQ một phần, CHECK người duyệt khác người lập, người duyệt huỷ khác người đề nghị huỷ), `period_lock_step`, `dashboard_def`.
- Tạo `config.outbox_event`, `config.inbox_processed`, `config.inbox_aggregate_version`, `config.idempotency_key` theo mẫu chung của NEN, bật RLS `rls_<bảng>__tenant` cho mọi bảng có `tenant_id`, ghi `COMMENT ON COLUMN` phân loại C0–C3 cho mọi cột.
- Điểm lệch cần lưu ý: `ui_pack_version` và `form_schema` có cặp cột trùng nghĩa (`tokens`/`tokens_json`, `layouts`/`layout_json`, `policy_pack_version_id`/`policy_pack_ref`, `form_code`/`form_key`, `json_schema`/`schema_json`, `ui_schema`/`ui_json`). Chọn một bộ theo tên trường trong `config.yaml` (API-first), ghi lựa chọn vào chú thích migration và `wiki/14-quyet-dinh-va-cau-hoi-mo.md`; không tạo cả hai cột.
- Seed `component_registry` một phiên bản ví dụ lấy từ registry SDUI hiện có của web (`frontend/projects/sdui-renderer`), chỉ đọc để lấy tên component, đánh dấu (suy luận).
- Viết kiểm thử Testcontainers PostgreSQL 17: ma trận chuyển trạng thái kỳ (năm chuyển hợp lệ qua, mọi chuyển khác bị trigger chặn), kỳ chồng lấn cùng `kind` và `flow` bị EX chặn, `role_binding` tự duyệt bị CHECK chặn, `period_status_log` từ chối UPDATE và DELETE với vai trò `svc_config`, rò rỉ tenant qua RLS bị chặn.

## 4. Ngoài phạm vi

- Mã Java của domain và API (CORE-08-02 trở đi).
- Bảng `config.user_preference` hoặc bảng cá nhân hoá khác (chờ CNH-01).
- Bảng `config.delegation` của đề xuất WEB-058 (CORE-08-16, sau khi duyệt).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**` (chỉ kiểm thử migration)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (ghi quyết định chọn bộ cột)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm kiểm thử migration bằng Testcontainers PostgreSQL 17.
- Lint quy ước đặt tên và kiểm tra chú thích phân loại cột của NEN chạy qua với schema `config`.
- Chạy migration hai lần trên CSDL trống cho cùng kết quả; không sửa migration đã gộp.
- Kiểm thử chứng minh đủ năm ràng buộc nêu ở mục 3; báo cáo liệt kê bộ cột đã chọn cho hai bảng có cột trùng nghĩa.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
