# CORE-03-01 · MOD-03 Migration schema `product` và trigger đóng băng phiên bản minh chứng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033 |
| Truy vết | QT-07 → MOD-03 `product` → bảng `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `product.e2ee_key_wrap` → (EVT-07, EVT-08 phát ở task sau) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo bộ migration Flyway cho schema `product` của MOD-03 đúng từ điển dữ liệu TL-03, kèm các ràng buộc toàn vẹn mà TL-03 yêu cầu đặt ngay trong CSDL: phiên bản minh chứng đã nộp không sửa được nội dung, phiên bản `DA_XAC_NHAN` không huỷ được, không xoá được dòng nào, người nghiệm thu khác người nộp. Khi xong, mọi task CORE-03-xx sau có nền bảng ổn định và các quy tắc BR-07-01, BR-07-06 đã có lớp bảo vệ thứ hai ở CSDL ngoài kiểm tra ở mã.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: mục "Schema product (MOD-03)" (dòng 2416), "DDL mẫu: product.evidence_version" (dòng 2430), các bảng `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `product.e2ee_key_wrap` (dòng 2516–2606); danh mục sở hữu "MOD-03 product" (dòng 393).
- Cùng tệp: quy ước đặt tên, kiểu chuẩn, cột chung, phân loại C0–C3 bằng `COMMENT ON COLUMN` (chương đầu, dòng 44–137); bảng `task.e2ee_key_wrap` (dòng 2401) làm mẫu cấu trúc.
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9 và 9.1 (trạng thái chuẩn của `evidence_version`).
- Hợp đồng `contracts/openapi/modules/products.yaml` (để đối chiếu tên trường DTO với cột, không sửa hợp đồng).
- Bản trích `tl03-phan1 §C8` trong kế hoạch tổng thể; `wiki/04-ma-dinh-danh-va-truy-vet.md`.

## 3. Phạm vi công việc

- Viết migration tạo `product.deliverable` và `product.evidence` phân vùng `LIST (period_bucket)`, PK `(period_bucket, id)`, FK hai cột `(period_bucket, deliverable_id)`, chỉ mục `(tenant_id, task_id)`, RLS `rls_<bảng>__tenant` theo mẫu của NEN-033.
- Viết migration `product.evidence_version` đúng DDL mẫu: UQ `(period_bucket, evidence_id, version_no)`, CHECK `status` sáu giá trị `NHAP`, `DA_NOP`, `DANG_NGHIEM_THU`, `DA_XAC_NHAN`, `YEU_CAU_BO_SUNG`, `TU_CHOI`, CHECK `source`, `scan_status`, độ dài băm 32 byte, ba CHECK toàn vẹn (đã nộp thì có đối tượng, băm, người và thời điểm nộp; `ai_prefill` thì có `user_confirmed_at` khi rời `NHAP`; trạng thái nghiệm thu thì `scan_status = 'SACH'`) và các cột bổ sung `is_duplicate_suspect`, `duplicate_of_id`, `has_e2ee`, `excluded_period_id`, `reuse_justification`, `attested_at`, `doc_number`, `doc_date`, `doc_summary`.
- Viết trigger `tg_evidence_version__freeze` gọi `product.fn_freeze_evidence_version()`: chặn sửa cột nội dung khi trạng thái khác `NHAP`, chặn đổi trạng thái rời `DA_XAC_NHAN`; `REVOKE DELETE` khỏi `svc_product` trên cả bốn bảng nghiệp vụ.
- Viết migration `product.acceptance` với CHECK `quality_level IN (1, 0.75, 0.5, 0.25, 0)` bắt buộc khi `decision = 'DA_XAC_NHAN'`, CHECK `revision_cause` năm giá trị, cột `comment_enc` + `dek_id` FK `keyring.dek`, và trigger so `reviewer_id` với `evidence_version.submitted_by` (BR-07-06).
- Tạo `product.e2ee_key_wrap` theo cấu trúc `task.e2ee_key_wrap` với `object_type = 'EVIDENCE_FILE'`, cùng `outbox_event`, `inbox_processed`, `inbox_aggregate_version`, `idempotency_key` cho schema `product` theo mẫu dùng chung.
- Ghi `COMMENT ON COLUMN` phân loại `cls` cho mọi cột để kiểm tra CI phân loại chạy qua.
- Điểm lệch cần lưu ý: từ điển ghi UQ `(evidence_id, version_no)` còn DDL có `period_bucket` đứng đầu; chọn theo DDL vì bảng phân vùng. `acceptance` có cả `comment` (C1) và `comment_enc` (C2); giữ cả hai như TL-03 và ghi câu hỏi mở vào `wiki/14`.

## 4. Ngoài phạm vi

- Bảng `product.period_state` và hàm `fn_guard_period_write` (CORE-04-01 làm cho cả `okr`, `task`, `product`).
- Mã domain, máy trạng thái và API (CORE-03-02, CORE-03-03 trở đi); bảng `evidence.*` của SVC-08 (DV-08-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**` (kiểm thử migration và trigger)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (ghi điểm lệch)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL 17 chạy Flyway từ đầu.
- Kiểm thử chứng minh: sửa `object_id` hoặc `content_sha256` của phiên bản `DA_NOP` bị chặn; phiên bản có `ai_prefill` mà chưa `user_confirmed_at` không chuyển sang `DA_NOP` được; phiên bản `scan_status <> 'SACH'` không vào `DANG_NGHIEM_THU` được; `DELETE` bị từ chối; `acceptance` có `reviewer_id` trùng người nộp bị chặn; `quality_level = 0.6` bị chặn.
- Kiểm thử rò rỉ tenant: đặt `app.tenant_id` của tenant A không đọc được dòng của tenant B ở cả bốn bảng.
- Lint đặt tên và kiểm tra COMMENT phân loại trong CI không báo lỗi.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
