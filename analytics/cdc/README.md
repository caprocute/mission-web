# cdc-config-gen: connector CDC phân tích (DV-15-02)

Công cụ `cdc_config_gen.py` sinh cấu hình connector Debezium phân tích cho từng CSDL nguồn theo TL-03 "CDC phân tích sang ClickHouse". Danh sách bảng lấy từ các bảng Kafka engine của kho ClickHouse (`analytics/clickhouse/migrations/V005__nguon_cdc_kafka.sql`), nên phía nguồn và phía đích luôn cùng một danh sách. Với mỗi bảng, công cụ đọc chú thích phân loại `COMMENT ON COLUMN ... '{"cls": ...}'` và quyết định cột nào bị loại, cột nào phải giả danh; cột thiếu chú thích làm công cụ dừng với lỗi.

| Đầu ra | Nội dung |
|---|---|
| `generated/<db>/connector.yaml` | `KafkaConnector` (Strimzi) của CSDL: `pgoutput`, slot `dbz_cdc_<db>`, publication `pub_analytics`, topic `cdc.<db>.<schema>.<table>`, `ExtractNewRecordState` thêm `__op`, `__lsn`, `__source_ts_ms`, `__deleted`, `column.exclude.list`, bộ biến đổi giả danh |
| `generated/<db>/publication.sql` | Tạo hoặc cập nhật `pub_analytics` (`publish_via_partition_root`), đặt `REPLICA IDENTITY FULL` cho bảng cha và mọi phân vùng; chạy lại được |
| `smt/` | Plugin Kafka Connect `TenantPseudonymize`: thay mã người ở khoá và giá trị bản ghi bằng `hex(HMAC-SHA256(k_tenant, giá_trị))`, `k_tenant` dẫn xuất từ khoá chủ và `tenant_id` |

Quy tắc phân loại: cột C3, C2, cột `_enc`, `_bidx`, cột có `"pii": true` và thuộc tính định danh trực tiếp (họ tên, mã CBCCVC của `person_v`) bị loại; cột mã người (`person_id`, `actor_id`, `created_by`… và khoá `id` của `person_v`) được giả danh. Mật khẩu vai trò CDC và khoá chủ giả danh chỉ xuất hiện dưới dạng `${env:...}`; ở cụm thật Kafka Connect nhận chúng từ Secret đồng bộ từ OpenBao.

## Chạy

```bash
python3 analytics/cdc/cdc_config_gen.py            # sinh lại generated/ từ migration trong repo
python3 analytics/cdc/cdc_config_gen.py --check    # bước CI: khác tệp đã commit thì thoát mã 1
python3 analytics/cdc/cdc_config_gen.py --dsn tenant_data=postgresql://... --check   # đối chiếu CSDL đang chạy
../../backend/mvnw -q -f analytics/cdc/smt/pom.xml package                           # đóng jar plugin
```

Chế độ mặc định chỉ dùng thư viện chuẩn và đọc migration trong repo (cộng `analytics/clickhouse/phan-loai-ngoai-repo.json` cho schema chưa có migration); chế độ `--dsn` cần `psycopg` và đọc `pg_description`.

## Kiểm thử

```bash
cd analytics/cdc
uv venv .venv && uv pip install --python .venv/bin/python pytest 'testcontainers>=4.9' 'confluent-kafka>=2.6' 'psycopg[binary]>=3.2' ruff
.venv/bin/python -m pytest -q
```

Kiểm thử tích hợp dựng PostgreSQL 17, Kafka 3.9 và Debezium Connect 3.0 có plugin vừa đóng, sinh cấu hình từ `pg_description`, chạy `publication.sql`, đăng ký connector rồi đọc topic: không còn cột C2, mã người là băm 32 byte khác nhau giữa hai tenant, bản ghi xoá mang đủ cột.

## Việc chờ hạ tầng

Chart Kafka Connect của INF-10 (`deploy/charts/vt-platform/ha-tang/kafka`) cần: đưa jar `smt` vào ảnh Connect, cấp biến `PG_HOST`, `PG_PORT`, `PG_CDC_ANALYTICS_USER`, `PG_CDC_ANALYTICS_PASSWORD`, `ANALYTICS_PSEUDONYM_KEY` từ Secret, bật config provider `env`, và áp `generated/<db>/connector.yaml`. Mỗi CSDL cần vai trò `REPLICATION` chỉ đọc các bảng được đưa vào và bảng `public.debezium_heartbeat`. Chi tiết: `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-15-02.
