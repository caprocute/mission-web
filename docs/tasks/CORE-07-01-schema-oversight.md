# CORE-07-01 · MOD-07 Migration schema `oversight`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033 |
| Truy vết | QT-12, QT-13, QT-14 → MOD-07 `oversight` → bảng `oversight.complaint`, `oversight.complaint_response`, `oversight.reopen_request`, `oversight.reopen_approval`, `oversight.post_audit`, `oversight.anomaly_alert`, `oversight.alert_action` → (EVT-20, EVT-22, EVT-25 ở task sau) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo bộ migration Flyway cho schema `oversight` của MOD-07 theo TL-03: khiếu nại và trả lời (C2), yêu cầu mở lại kỳ bốn mắt với ràng buộc người duyệt khác người yêu cầu, hậu kiểm, cảnh báo bất thường và hành động xử lý. Đây là schema có thời hạn lưu dài nhất, nên các ràng buộc bất biến và tách vai trò phải nằm ngay ở CSDL.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Schema oversight (MOD-07)" (dòng 3091) và các bảng `oversight.complaint` (dòng 3103), `oversight.complaint_response` (dòng 3127), `oversight.reopen_request` (dòng 3144), `oversight.reopen_approval` (dòng 3164), `oversight.post_audit` (dòng 3181), `oversight.anomaly_alert` (dòng 3197), `oversight.alert_action` (dòng 3213); danh mục "MOD-07 oversight" (dòng 440).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` "Trạng thái đối tượng" của QT-12 (dòng 782), QT-13 (dòng 984), QT-14 (dòng 1186).
- `contracts/openapi/modules/oversight.yaml`: các enum trạng thái và loại hành động.
- Bản trích `tl03-phan2 §A3`.

## 3. Phạm vi công việc

- Tạo bảy bảng phân vùng `LIST (period_bucket)`, PK `(period_bucket, id)`, bảy cột chung, RLS, COMMENT phân loại; cột C2 (`content_enc`, `request_text_enc`, `reserve_opinion_enc`, `response_enc`, `findings_enc`) kèm `dek_id` FK `keyring.dek`.
- `complaint`: CHECK `target_stage`, `kind`, `status` chín giá trị (gồm `RUT`), `outcome`; `attachment_object_ids uuid[]`, `response_deadline`. `complaint_response`: CHECK `record_kind`, `adjustment_json`, `sign_request_id`; `REVOKE UPDATE, DELETE` (bản ghi trả lời chỉ thêm, suy luận từ BR-12-10).
- `reopen_request`: CHECK `scope`, `scope_ids` khác rỗng khi `scope <> 'TOAN_KY'`, `approvals_required >= 2` mặc định 2, `status` sáu giá trị, `code` UQ (định dạng `MLK-yyyy-nnn` kiểm ở mã), `open_until`, `executed_at`, `relocked_at`.
- `reopen_approval`: UQ `(period_bucket, reopen_request_id, approver_id)`, trigger `approver_id <> requested_by`, CHECK `approver_role IN ('decider','second_approver')`, `decision`, `mfa_acr`, `sign_request_id` NOT NULL; `REVOKE UPDATE, DELETE`.
- `post_audit`: CHECK `audit_kind`, `target_type`, `conclusion`. `anomaly_alert`: CHECK `signal_code` tám giá trị, `severity`, `status` tám giá trị, UQ `(tenant_id, source_event_id, period_bucket)` cho khử trùng EVT-20.
- `alert_action`: CHECK `action` theo hợp đồng (`XAC_NHAN_HOP_LE`, `GIAO_KIEM_TRA`, `KET_LUAN`, `GHI_CHU`, `DONG`, `TU_DONG`) cùng cột cờ chặn còn hiệu lực cho hành động tự động (hợp đồng có trường `active`, TL-03 chưa có cột tương ứng; thêm như cột bổ sung và ghi vào `wiki/14`).
- Tạo outbox, inbox, `idempotency_key` cho schema `oversight`.
- Điểm lệch cần lưu ý: TL-03 CHECK `alert_action.action` chỉ có bốn giá trị, hợp đồng thêm `KET_LUAN` (theo TL-02b SCR-1401) và `TU_DONG`; chọn hợp đồng và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Domain và API (CORE-07-02..11); trạng thái kỳ `MO_LAI_HAU_KIEM` ở `config` (CORE-08-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL 17.
- Kiểm thử: người yêu cầu tự phê duyệt bị trigger chặn; cùng người duyệt hai lần bị UQ chặn; `scope = 'HO_SO'` với `scope_ids` rỗng bị chặn; EVT-20 trùng `source_event_id` bị UQ chặn; rò rỉ tenant không xảy ra.
- Lint đặt tên và kiểm tra COMMENT phân loại xanh.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
