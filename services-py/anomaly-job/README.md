# Job phát hiện bất thường (DV-15-07)

Job của SVC-15 (ADR-14) chạy 02:00 hằng đêm theo cell, thực hiện QT-14 bước 1: chạy các luật phát hiện trên kho ClickHouse của cell, ghi kết quả vào `anomaly_out` (migration `V011`), rồi phát EVT-20 `analytics.anomaly.detected.v1` cho mọi dòng chưa phát. Mission-core (MOD-07) tiêu thụ sự kiện qua inbox và dựng cảnh báo cho người có thẩm quyền.

## Cách chạy

Với mỗi tenant có hồ sơ đánh giá trong kho, job lấy bộ ngưỡng qua `GET /api/v1/config/anomaly-thresholds` (token client-credentials cộng tiêu đề `X-Tenant-Id`). Không lấy được ngưỡng thì tenant đó bị bỏ qua và mã thoát khác 0. Mỗi luật là một tệp `INSERT ... SELECT` trong `app/rules/`, bỏ qua `alert_key` đã có, nên chạy lại không sinh cảnh báo mới. Bộ phát mã hoá Avro theo lược đồ đã đăng ký trong Apicurio (không tự đăng ký), đặt global id ở tiêu đề `apicurio.value.globalId`, khoá `tenant:subjectId`, `id` là UUIDv5 tất định của `(tenant, alertKey)`, và chỉ ghi đã phát sau khi Kafka xác nhận.

```bash
anomaly-job                    # phát hiện rồi phát
anomaly-job --tenant <uuid>    # chỉ một tenant
anomaly-job --chi-phat         # chỉ phát các dòng còn sót
```

Biến môi trường bắt buộc: `ANALYTICS_CELL`, `CLICKHOUSE_URL`, `CLICKHOUSE_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `REGISTRY_URL`, `CONFIG_API_URL`, `OIDC_TOKEN_URL`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`. Biến `KAFKA_<X>` khác được chuyển thành cấu hình librdkafka `x`. Bí mật lấy từ OpenBao, không ghi vào repo.

## Kiểm thử

```bash
uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -e '.[dev]'
.venv/bin/ruff check . && .venv/bin/mypy app && .venv/bin/python -m pytest -q
```

Kiểm thử tích hợp dựng ClickHouse 25.8, Kafka 3.9 và Apicurio 3.0.9, áp toàn bộ migration kho, nạp dữ liệu vàng `data/golden/anomaly`, rồi kiểm tra hai luật đầu, việc chạy lại, hợp đồng EVT-20 và quyền của tài khoản job.

## Còn chờ

Dấu hiệu 3 đến 8 thuộc DV-15-08, DV-15-09. Chart, Secret và mạng ở cụm thật chờ INF; `deploy/cronjob.yaml` chỉ là mẫu. Các giả định đã chọn ghi ở `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-15-07.
