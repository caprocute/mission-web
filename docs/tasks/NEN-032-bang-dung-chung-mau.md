# NEN-032 · TL-03 Bảng dùng chung mẫu: `outbox_event`, `inbox_processed`, `inbox_aggregate_version`, `idempotency_key`, schema `modulith`, `keyring`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-032` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-030 (bổ sung nên có: NEN-031 để kiểm quyền `cdc_`/`svc_` trên bảng mới) |
| Truy vết | ADR-03, ADR-23, ADR-30, HD-02 → TL-03 "Cách trình bày và các bảng dùng chung" → `<schema>.outbox_event`, `<schema>.inbox_processed`, `<schema>.inbox_aggregate_version`, `<schema>.idempotency_key`, `modulith.event_publication`, `keyring.dek` |
| Trạng thái | Hoàn thành (20/10/2026, đã gộp vào `main`: `db/common-business/`, `db/migration/modulith/`, `db/migration/keyring/`, `SharedTablesIT` trong `backend/libs/vt-db-migration/`) |

## 1. Mục tiêu

Viết một lần, dưới dạng migration dùng chung có tham số schema, các bảng kỹ thuật mà mọi schema nghiệp vụ đều cần, cùng hai schema kỹ thuật `modulith` và `keyring`. Khi xong, schema nào chạy bộ Flyway của NEN-030 cũng tự có outbox phân vùng theo ngày, inbox, bảng phiên bản aggregate, kho khoá idempotency 48 giờ, đúng DDL TL-03 và đúng quy ước RLS; các thư viện NEN-011..NEN-015 có bảng thật để kiểm thử.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Cách trình bày và các bảng dùng chung" (L762–928): bảng `outbox_event` (L768–822, DDL L787–819), `inbox_processed` (L824–863), `inbox_aggregate_version` (L851–873), `idempotency_key` (L875–892), `modulith.event_publication` (L894–908), `keyring.dek` (L910–927); mục "Transactional Outbox và Debezium" (L4259: dọn phân vùng).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Giao tiếp giữa module" (L553–597: cấu hình Event Publication Registry).
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md` HD-02 (48 giờ).
- Kết quả NEN-030 (thư mục `db/common`, placeholder `${flyway:defaultSchema}`).

## 3. Phạm vi công việc

- Migration dùng chung `outbox_event`: đúng cột TL-03 (`id`, `tenant_id`, `aggregate_type varchar(64)`, `aggregate_id`, `aggregate_version bigint`, `event_type varchar(96)`, `topic varchar(128)` CHECK regex `^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$`, `event_key varchar(80)`, `payload bytea`, `schema_version int`, `headers jsonb DEFAULT '{}'`, `created_at`); PK `(id, created_at)`; `PARTITION BY RANGE (created_at)` theo ngày bằng `partman.create_parent` (premake 7), giữ 3 ngày, `retention_keep_table=false`; RLS `ENABLE` + `FORCE` với `rls_outbox_event__tenant`; không chỉ mục phụ; SELECT cho `cdc_<schema>`.
- Migration dùng chung `inbox_processed` (PK `(consumer, event_id)`, `processed_at` có `ix_inbox_processed__processed_at`, `tenant_id`, `source_topic`, `kafka_partition`, `kafka_offset`, không phân vùng, RLS) và `inbox_aggregate_version` (PK `(consumer, aggregate_type, aggregate_id)`, `tenant_id`, `last_version bigint`, `last_event_id`, `updated_at`, RLS).
- Migration dùng chung `idempotency_key`: PK `(tenant_id, actor_id, idem_key, created_day)`, RANGE theo `created_day` bằng pg_partman giữ 48 giờ, `request_hash bytea`, `status` CHECK (`DANG_XU_LY`, `XONG`), `response_code smallint`, `response_body jsonb` (không C2), `resource_ref`, `created_at`; RLS.
- Migration schema `modulith`: bảng `event_publication` theo lược đồ Spring Modulith 1.4 cộng `tenant_id` và RLS, chỉ mục `(listener_id, completion_date)` và chỉ mục một phần `WHERE completion_date IS NULL`; ghi chú cấu hình `spring.modulith.events.jdbc.schema-initialization.enabled=false`, `republish-outstanding-events-on-restart=true` để CORE-00-02 dùng.
- Migration schema `keyring`: bảng `dek` đúng cột TL-03 (`purpose` CHECK sáu giá trị, UQ `(tenant_id, purpose, valid_month)`, `wrapped_dek`, `kek_ref`, `alg_version`, `status` CHECK `ACTIVE`/`DECRYPT_ONLY`/`RETIRED`/`DESTROYED`, `destroyed_at`); ghi rõ `keyring` phải chạy trước mọi schema có cột `_enc` (FK `dek_id`).
- Mọi cột có `COMMENT` phân loại JSON để qua lint của NEN-030.
- Job dọn: dùng `partman.run_maintenance_proc()` cho bảng phân vùng; với `inbox_processed` viết hàm `fn_purge_inbox_processed(p_older_than interval)` xoá theo lô (14 ngày) và với `modulith.event_publication` hàm dọn bản ghi hoàn tất quá 7 ngày; lịch chạy là việc của ứng dụng hoặc CronJob (suy luận, ghi vào báo cáo).
- Điểm lệch cần lưu ý: DDL inbox mẫu của TL-03 viết cho schema `appraisal` nhưng TL-03 L766 áp cho mọi schema – task viết dạng dùng chung; TL-01 ghi idempotency 24 giờ – theo HD-02 giữ 48 giờ.

## 4. Ngoài phạm vi

- Hàm RLS chuẩn và phân vùng theo kỳ: NEN-033 (task này có thể viết RLS trực tiếp, sau đó NEN-033 thay bằng hàm chuẩn nếu cần).
- Thư viện ghi/đọc các bảng: NEN-011, NEN-012, NEN-013, NEN-015.
- Cài pg_partman vào ảnh PostgreSQL dev/kiểm thử: NEN-020 (task này dùng ảnh có sẵn pg_partman trong kiểm thử và ghi rõ ảnh đã dùng).

## 5. Tệp được phép sửa

- `backend/libs/vt-db-migration/src/main/resources/db/common/**`, `backend/libs/vt-db-migration/src/main/resources/db/migration/modulith/**`, `backend/libs/vt-db-migration/src/main/resources/db/migration/keyring/**`
- `backend/libs/vt-db-migration/src/test/**`

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-db-migration -am verify` chạy xanh: áp bộ dùng chung lên hai schema mẫu (`task`, `appraisal`) cùng `modulith`, `keyring` trên PostgreSQL 17 có pg_partman; lint NEN-030 xanh.
- Kiểm thử: chèn outbox ngày D-4 rồi chạy bảo trì thì phân vùng cũ bị gỡ; chèn `topic` sai định dạng bị CHECK chặn; hai tenant không đọc được dòng inbox/idempotency của nhau.
- Báo cáo cuối task theo AGENTS.md mục 6, ghi phiên bản pg_partman và ảnh PostgreSQL đã dùng.

## 7. Báo cáo

Theo AGENTS.md mục 6.
