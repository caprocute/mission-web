# CORE-01-01 · MOD-01 Migration schema `okr` và phiên bản mục tiêu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033; cần NEN-032 (bảng dùng chung outbox, inbox, idempotency) và CORE-08-01 (`config.period` sinh `period_bucket`) đã gộp (bổ sung, hiển nhiên theo thứ tự TL-03) |
| Truy vết | QT-02, QT-03 → MOD-01 `okr` → bảng `okr.basis`, `okr.objective`, `okr.key_result`, `okr.objective_basis`, `okr.objective_review`, `okr.okr_link`, `okr.kr_checkin`, `okr.outbox_event` → EVT-01, EVT-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo bằng Flyway toàn bộ schema `okr` của MOD-01 đúng từ điển dữ liệu TL-03: bảy bảng nghiệp vụ, bảng outbox, inbox và idempotency của schema, phân vùng theo kỳ, RLS hai lớp và các ràng buộc toàn vẹn (tổng trọng số KR, check-in chỉ ghi thêm, lý do ghi đè). Khi xong, mọi task CORE-01 sau có nền dữ liệu ổn định để viết entity, repository và use case, và các bất biến quan trọng nhất (trọng số ≤ 1, check-in đã gửi không sửa) được CSDL tự bảo vệ chứ không chỉ dựa vào mã.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema okr (MOD-01)" (L1867–2015) với từng bảng `okr.basis` … `okr.kr_checkin`; các mục "Quy ước đặt tên", "Kiểu dữ liệu chuẩn", "Cột chung của bảng nghiệp vụ", "Phân loại dữ liệu C0 đến C3", "Bảng outbox_event", "Bảng idempotency_key".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 mục "Trạng thái đối tượng" (L798–813) và QT-03 mục "Trạng thái đối tượng" (L1027–1042).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9 (schema `okr`), 9.1 (trạng thái `okr.objective.status`).
- `contracts/openapi/modules/okr.yaml` phần `components/schemas` (giá trị liệt kê `OkrObjectiveStatus`, `OkrMetricType`, `OkrRiskFlag`, `OkrContentSource`, `OkrCheckinStatus`, `OkrLinkKind`…) để CHECK khớp hợp đồng.
- Bản trích `extract/tl03-phan1.md` mục C6 và E (câu hỏi mở); `wiki/04-ma-dinh-danh-va-truy-vet.md`.

## 3. Phạm vi công việc

- Viết migration thứ nhất (tên phiên bản theo quy ước Flyway của NEN-030) tạo `okr.basis` (không phân vùng, FK tự tham chiếu `parent_basis_id`), `okr.objective` (PARTITION BY LIST `period_bucket`, CHECK `status` IN (`NHAP`,`CHO_DUYET`,`DA_DUYET`,`DONG`), CHECK `owner_person_id` bắt buộc khi `owner_type='CA_NHAN'`, `tier` 1–3, cột `version_no`, `over_limit_reason`, `wf_process_instance_id`, `ai_assisted`), `okr.objective_basis` và `okr.objective_review` (chỉ ghi thêm, UQ `(period_bucket, objective_id, version_no, reviewer_id)`).
- Viết migration thứ hai tạo `okr.key_result` (FK hai cột `(period_bucket, objective_id)`, `weight` 0–1, `content_source` IN (`HUMAN`,`AI_ACCEPTED`,`AI_EDITED`), `risk_flag`, `checkin_frequency`), `okr.okr_link` (UQ `(tenant_id, from_type, from_id, to_type, to_id)`, hai chỉ mục chiều xuôi và ngược) và `okr.kr_checkin` (UQ `(tenant_id, idempotency_key, period_bucket)`, CHECK `override_reason` bắt buộc khi `value` khác `source_value`).
- Hiện thực constraint trigger DEFERRABLE INITIALLY DEFERRED `tg_key_result__weight_sum` chặn tổng `weight` các KR của một mục tiêu vượt 1 ở thời điểm commit (cách hiện thực là suy luận từ TL-03 "tổng ≤ 1 bằng trigger hoãn").
- Hiện thực trigger `tg_kr_checkin__immutable` chặn UPDATE nội dung và DELETE khi `status='DA_GUI'`; GRANT cho `svc_okr` chỉ INSERT, SELECT và UPDATE theo cột (`status`, `verified_by`, `verified_at`).
- Tạo `okr.outbox_event`, `okr.inbox_processed`, `okr.inbox_aggregate_version`, `okr.idempotency_key` bằng tiện ích migration dùng lại của NEN-032; bật ENABLE + FORCE RLS `rls_<bảng>__tenant` cho mọi bảng và gọi `fn_create_period_partitions` cho các bảng theo kỳ.
- Ghi `COMMENT ON COLUMN` phân loại C0–C3 cho mọi cột (ví dụ `objective_review.comment` là C1) để lint CI của NEN-030 chạy qua.
- Điểm lệch cần lưu ý: `okr.okr_link` có `period_id` nhưng TL-03 không ghi `period_bucket` hay phân vùng, trái câu "mọi bảng có `period_id` đều phân vùng" (L1878); task chọn không phân vùng `okr_link` theo từ điển bảng và ghi lại trong `wiki/14`. `kr_checkin` cần UPDATE cột `status` (`NHAP`→`DA_GUI`) dù TL-03 chỉ cho UPDATE `verified_*`; task cấp thêm quyền UPDATE cột `status` và ghi rõ lý do. `objective` có cả `progress` và `progress_pct`: tạo đủ cả hai như TL-03, ghi `progress_pct` là cột dùng cho API.
- Bảng `okr.period_state` có trong danh mục sở hữu MOD-01 nhưng không có từ điển dữ liệu: không tạo, ghi vào báo cáo.

## 4. Ngoài phạm vi

- Entity JPA, repository, use case (CORE-01-02 trở đi).
- Bảng `ai.suggestion_log` (DV-14-01) và bảng `flowable.*` (DV-06-02).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/infrastructure/migration/**` (kiểm thử migration)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (ghi điểm lệch đã chọn)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm kiểm thử Testcontainers PostgreSQL 17 áp toàn bộ migration từ đầu.
- Kiểm thử tích hợp chứng minh: chèn ba KR có tổng trọng số 1,2 trong một giao dịch bị từ chối khi commit; UPDATE hoặc DELETE một `kr_checkin` `DA_GUI` bị từ chối; check-in có `value` khác `source_value` mà thiếu `override_reason` bị CHECK chặn; hai tenant không đọc được dòng của nhau khi đặt `app.tenant_id` khác nhau.
- Lint tên và chú thích phân loại cột của NEN-030 không báo lỗi trên schema `okr`.
- EXPLAIN truy vấn `objective` theo `(tenant_id, owner_unit_id, period_id)` có điều kiện `period_bucket` chỉ chạm một phân vùng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật với các điểm lệch đã chọn.

## 7. Báo cáo

Theo AGENTS.md mục 6.
