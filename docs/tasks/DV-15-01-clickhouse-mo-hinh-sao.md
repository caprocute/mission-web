# DV-15-01 · SVC-15/ADR-14 ClickHouse `analytics_<cell>` mô hình sao, Kafka engine và materialized view

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | L |
| Phụ thuộc | INF-11, NEN-007 |
| Truy vết | QT-14, QT-16 → SVC-15 → CSDL ClickHouse `analytics_<cell>`: `fact_task`, `fact_task_progress`, `fact_evidence`, `fact_score`, `fact_rating`, `fact_okr_checkin`, `dim_org_unit`, `dim_period`, `dim_subject_group`, `dim_policy_version`, `dim_person` → nguồn topic `cdc.<db>.<schema>.<table>` → ADR-14 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng kho phân tích ClickHouse của một cell theo mô hình sao TL-03: bảng Kafka engine đọc topic CDC, materialized view nạp vào bảng fact/dim dạng `ReplicatedReplacingMergeTree(_version, _is_deleted)`, phân vùng theo kỳ, sắp theo tenant và đơn vị, chỉ cột C0, C1 và C2 đã giả danh hoá. Khi xong, thay đổi ở PostgreSQL xuất hiện trong ClickHouse với độ trễ p99 ≤ 60 giây và mọi bảng có `tenant_id` để Cube áp bộ lọc an ninh.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "CDC phân tích sang ClickHouse" (L4313–4349: "Đường đi dữ liệu", "Bảng đích và mô hình sao", "Phân quyền phía đọc"), "Mô hình sao trong ClickHouse" (L4580), "Bảng tổng hợp xử lý theo phân loại" (L4172).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-15 analytics" (L1194–1204), ADR-14 (L3522).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` định cỡ ClickHouse (2 shard × 2 bản sao ở CELL-HCM, 3 Keeper).
- `00-co-so-thiet-ke-chung.md` mục 8 cơ chế 4; bản trích `extract/tl03-phan2.md` mục B7, `extract/tl01-thanh-phan.md` mục C14.

## 3. Phạm vi công việc

- Viết DDL ClickHouse trong `analytics/clickhouse/` (áp bằng công cụ migration nhẹ, ví dụ thư mục số thứ tự chạy tuần tự có bảng ghi phiên bản, suy luận): CSDL `analytics_<cell>` tham số hoá, bảng `ON CLUSTER` có bản sao.
- Tạo sáu bảng fact và năm bảng dim theo TL-03; mẫu `fact_task_progress`: `ReplicatedReplacingMergeTree(_version, _is_deleted)`, `PARTITION BY period_bucket`, `ORDER BY (tenant_id, org_unit_id, task_id, id)`, `person_key FixedString(32)`; `dim_org_unit` kiểu SCD2; `dim_person` chỉ mã giả danh và thuộc tính làm thô.
- Tạo bảng Kafka engine cho từng topic `cdc.<db>.<schema>.<table>` cần dùng và materialized view chuyển bản ghi Debezium (`__op`, `__lsn`, `__source_ts_ms`, `__deleted`) sang bảng đích với `_version` từ LSN.
- Tạo row policy theo `tenant_id` cho tài khoản Cube ở cell pool và tài khoản chỉ đọc cho job phát hiện bất thường; không tài khoản nào đọc được cột không có trong danh sách cho phép.
- Viết bảng tổng hợp `agg_*` theo đơn vị cấp tỉnh làm nguồn cho DV-15-11 (chỉ tạo bảng, chưa đẩy).
- Viết bài kiểm tra chặn cột C2 chưa giả danh hoặc C3 trong DDL (đối chiếu với COMMENT phân loại xuất từ PostgreSQL).
- Gợi ý tách nếu quá lớn: (a) dim và fact nhiệm vụ, OKR; (b) fact minh chứng, điểm, xếp loại và row policy.

## 4. Ngoài phạm vi

- Cài cụm ClickHouse và Keeper (INF-11); connector Debezium phân tích (DV-15-02); mô hình Cube (DV-15-03).

## 5. Tệp được phép sửa

- `analytics/clickhouse/**`
- `infra/dev/` (ClickHouse một nút trong profile phân tích, nếu chưa có)

## 6. Tiêu chí hoàn thành

- Kiểm thử tích hợp (pytest hoặc Testcontainers) với ClickHouse + Kafka: gửi bản ghi CDC mẫu (tạo, sửa, xoá) cho từng bảng nguồn, bảng đích sau `OPTIMIZE ... FINAL` phản ánh đúng trạng thái cuối; xoá mềm đánh dấu `_is_deleted`.
- Độ trễ từ lúc ghi vào topic tới khi truy vấn thấy trong ClickHouse p99 ≤ 60 giây ở tải thử cục bộ (ghi số đo).
- Row policy: tài khoản Cube với `tenant_id` A không đọc được dòng tenant B.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
