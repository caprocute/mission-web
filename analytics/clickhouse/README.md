# Kho phân tích ClickHouse của cell (DV-15-01)

Thư mục này chứa lược đồ kho phân tích `analytics_<cell>` của SVC-15 (ADR-14) theo TL-03 "CDC phân tích sang ClickHouse" và "Mô hình sao trong ClickHouse". Dữ liệu đi theo đường: bảng nghiệp vụ PostgreSQL → connector Debezium phân tích (DV-15-02) → topic `cdc.<db>.<schema>.<table>` → bảng Kafka engine `src_*` → materialized view → bảng chiều `dim_*`, bảng sự kiện `fact_*` dạng `ReplicatedReplacingMergeTree(_version, _is_deleted)`. Cube (DV-15-03) đọc các bảng này qua tài khoản kỹ thuật có row policy theo `tenant_id`.

## Thành phần

| Tệp | Nội dung |
|---|---|
| `migrations/V001__ham_chuyen_doi_cdc.sql` | Hàm `mission_cdc_*` đổi giá trị Debezium (ngày, mốc thời gian, mã giả danh, dấu xoá) sang kiểu ClickHouse |
| `migrations/V002__bang_chieu.sql` | `dim_org_unit` (SCD2), `dim_person` (chỉ khoá giả danh), `dim_period`, `dim_subject_group`, `dim_policy_version` |
| `migrations/V003__bang_su_kien.sql` | `fact_task`, `fact_task_assignee` (bảng cầu), `fact_task_progress`, `fact_evidence`, `fact_score`, `fact_rating`, `fact_okr_checkin`, `fact_okr_objective`, `fact_appraisal_profile`, `dim_org_closure` (bao đóng cây đơn vị); bảng tra cứu nội bộ `lk_*`; `cdc_error` |
| `migrations/V004__tu_dien_tra_cuu.sql` | Tài khoản nạp từ điển và các từ điển gắn đơn vị, người cho bảng sự kiện (kể cả đơn vị của cá nhân chủ trì) |
| `migrations/V005__nguon_cdc_kafka.sql` | 17 bảng Kafka engine, một bảng cho mỗi topic nguồn |
| `migrations/V006__nap_bang_dich.sql` | Materialized view nạp bảng đích và view chuyển bản ghi lỗi sang `cdc_error` |
| `migrations/V007__bang_tong_hop_tinh.sql` | `agg_task_tinh`, `agg_score_tinh`, `agg_rating_tinh` cho DV-15-11 (chỉ tạo bảng) |
| `migrations/V008__bang_dan_xuat.sql` | Refreshable materialized view: `dim_org_scope` (đơn vị kèm `org_path` trên hai cây, cho Cube lọc theo nhánh) và `data_freshness` (độ mới dữ liệu theo nguồn) |
| `migrations/V009__phan_quyen_doc.sql` | Vai trò, tài khoản Cube và job bất thường, quyền theo cột, row policy, quyền ghi pre-aggregation của Cube trong `<db>_pa_*` |
| `migrations/V010__view_cube.sql` | View nguồn của Cube (`cube_*`, SQL SECURITY INVOKER): định nghĩa dòng cho chỉ số, đọc qua view để Cube dựng được pre-aggregation với quyền theo cột |
| `migrations/V011__anomaly_out.sql` | Bảng kết quả `anomaly_out` của job phát hiện bất thường (DV-15-07) và quyền ghi riêng bảng đó cho tài khoản job |
| `migrations/V012__ket_qua_then_chot.sql` | Dựng lại nguồn `okr.key_result` với chỉ tiêu, giá trị xuất phát, chiều tốt và thêm `fact_okr_key_result` cho dấu hiệu 3 (DV-15-08) |
| `migrations/V013__de_nghi_va_tu_dong.sql` | Nguồn `task.task_change_request` (`fact_task_change_request`) cho dấu hiệu 8, bảng lượt chạy `anomaly_run` và cột tự đóng của `anomaly_out` (DV-15-09) |
| `migrations/V015__tong_hop_quoc_gia.sql` | Job làm mới `agg_*` mỗi giờ về đơn vị cấp tỉnh, khung xuất `v_agg_xuat` che nhóm nhỏ k = 10, mốc `agg_xuat_moc`, vai trò `<db>_agg` của bộ xuất (DV-15-11) |
| `migrate.py` | Bộ áp migration (chỉ thư viện chuẩn) |
| `agg_export.py` | Bộ xuất DV-15-11: đọc `v_agg_xuat`, phát Avro `AggregateMetric` lên `agg.province.metric.v1` (MirrorMaker 2 ở `analytics/mirrormaker` đưa sang kho quốc gia) |
| `phan-loai-ngoai-repo.json` | Phân loại cột của schema chưa có migration trong repo (`appraisal`), chép từ TL-03 |
| `tests/` | Kiểm tra tĩnh phân loại cột, kiểm thử bộ áp, kiểm thử tích hợp ClickHouse + Kafka |

## Áp lược đồ

Bộ áp đọc tham số từ biến môi trường. Mật khẩu ba tài khoản cần ít nhất 16 ký tự; ở môi trường thật lấy từ OpenBao, không ghi vào repo.

```bash
export CLICKHOUSE_URL=http://127.0.0.1:35123 CLICKHOUSE_USER=default CLICKHOUSE_PASSWORD=...   # từ infra/dev/.env
export ANALYTICS_CELL=dev ANALYTICS_KAFKA_BROKERS=kafka:9092
export ANALYTICS_CUBE_PASSWORD=... ANALYTICS_ANOMALY_PASSWORD=... ANALYTICS_DICT_PASSWORD=...
python3 analytics/clickhouse/migrate.py --dry-run   # xem tệp sẽ áp
python3 analytics/clickhouse/migrate.py
```

Các biến khác: `ANALYTICS_DB` (mặc định `analytics_<cell>`), `ANALYTICS_CLUSTER` (mặc định `cell_<cell>`), `ANALYTICS_KAFKA_GROUP`, `ANALYTICS_KAFKA_CONSUMERS`, `ANALYTICS_KAFKA_FLUSH_MS`, `ANALYTICS_CORE_DB` (CSDL của mission-core, mặc định `tenant_data`), `ANALYTICS_ORG_DB` (CSDL của org-service, mặc định `org`). Ở dev, ClickHouse chạy bằng `docker compose --profile analytics up -d` trong `infra/dev`, cụm tên `cell_dev`.

Tệp đã áp không được sửa: thay đổi lược đồ viết thành tệp số tiếp theo. Bộ áp dừng khi phát hiện tổng kiểm của một tệp đã áp khác đi.

## Hợp đồng với connector phân tích (DV-15-02)

Kafka engine đọc bản ghi JSON không kèm lược đồ, mỗi trường là chuỗi. Connector cần `ExtractNewRecordState` với `add.fields=op,lsn,source.ts_ms`, `delete.handling.mode=rewrite`, `drop.tombstones=true`, cùng `decimal.handling.mode=string`. Cột có `COMMENT 'gia_danh'` trong `V005` phải được băm bằng `column.mask.hash.SHA-256.with.salt.<muối_tenant>` (64 ký tự hex). Bảng nguồn đặt `REPLICA IDENTITY FULL` để bản ghi xoá mang đủ cột. Trường thừa trong bản ghi bị bỏ qua, nên connector có thể gửi nhiều cột hơn danh sách ở đây, miễn không có cột C2 chưa giả danh hay C3 (cấu hình `column.exclude.list` do `cdc-config-gen` sinh).

## Kiểm thử

```bash
cd analytics/clickhouse
uv venv .venv && uv pip install --python .venv/bin/python pytest 'testcontainers>=4.9' 'confluent-kafka>=2.6' ruff
.venv/bin/python -m pytest -q          # cần Docker cho phần tích hợp; không có Docker thì phần đó bị bỏ qua
```

Kiểm thử tích hợp dựng Kafka 3.9 và ClickHouse 25.8 với cấu hình `infra/dev/clickhouse`, áp toàn bộ migration, rồi kiểm tra tạo, sửa, xoá cho cả 18 nguồn, việc gắn đơn vị qua từ điển, bản ghi lỗi, độ trễ p99 và phân quyền đọc. Ảnh có thể đổi bằng `CLICKHOUSE_IMAGE`, `KAFKA_IMAGE`.

## Bộ xuất số liệu tổng hợp (DV-15-11)

Bộ xuất chạy một lượt rồi thoát (CronJob, suy luận mỗi 10 phút) bằng tài khoản mang vai trò `<db>_agg`; tài khoản này chỉ đọc được khung xuất và ghi được bảng mốc. Kafka dùng mTLS với tài khoản `analytics` đã có quyền ghi `agg.province.metric.v1` (`deploy/charts/vt-platform/ha-tang/kafka`).

```bash
export CLICKHOUSE_URL=... ANALYTICS_CELL=dev ANALYTICS_AGG_USER=analytics_dev_agg ANALYTICS_AGG_PASSWORD=...
export KAFKA_BOOTSTRAP_SERVERS=... KAFKA_SSL_CA_LOCATION=... KAFKA_SSL_CERTIFICATE_LOCATION=... KAFKA_SSL_KEY_LOCATION=...
export APICURIO_REGISTRY_URL=http://.../apis/registry/v3
python3 analytics/clickhouse/agg_export.py
```

## Việc chờ hạ tầng (INF-11)

Cụm ClickHouse thật phải đặt các cấu hình mà `infra/dev/clickhouse/config.d/cell-dev.xml` đặt cho dev: `custom_settings_prefixes` có `SQL_`, `kafka/auto_offset_reset = earliest`, `background_message_broker_schedule_pool_size` không nhỏ hơn số bảng Kafka engine, nơi lưu tài khoản `replicated`, macro `{shard}` và `{replica}`. Bảng `Distributed` để đọc chéo shard, kích thước từ điển ở quy mô CELL-HCM và số luồng tiêu thụ mỗi bảng sẽ chốt cùng INF-11. Chi tiết quyết định và điểm lệch: `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-15-01.
