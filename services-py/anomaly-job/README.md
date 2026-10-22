# Job phát hiện bất thường (DV-15-07)

Job của SVC-15 (ADR-14) chạy 02:00 hằng đêm theo cell, thực hiện QT-14 bước 1: chạy các luật phát hiện trên kho ClickHouse của cell, ghi kết quả vào `anomaly_out` (migration `V011`), rồi phát EVT-20 `analytics.anomaly.detected.v1` cho mọi dòng chưa phát. Mission-core (MOD-07) tiêu thụ sự kiện qua inbox và dựng cảnh báo cho người có thẩm quyền.

## Luật đã có

| Dấu hiệu | Tệp | Đối tượng | Ghi chú |
|---|---|---|---|
| 1 `TU_CHAM_CAO` | `app/rules/tu_cham_cao.sql` | cá nhân (mã hồ sơ) | DV-15-07 |
| 2 `CAO_BANG` | `app/rules/cao_bang.sql` | đơn vị | DV-15-07 |
| 3 `MUC_TIEU_DE` | `app/rules/muc_tieu_de.sql` | đơn vị sở hữu mục tiêu, mã KR trong bằng chứng | DV-15-08, đọc `fact_okr_key_result` (V012) |
| 5 `CAP_NHAT_DON` | `app/rules/cap_nhat_don.sql` | đơn vị | DV-15-08, cần lịch ngày làm việc của tenant |
| 7 `CHE_NHO` | `app/rules/che_nho.sql` | cá nhân (mã hồ sơ), chưa có hồ sơ thì đơn vị | DV-15-08 |

Chuỗi bằng chứng chỉ chứa mã (`dieu_kien:`, `ky:`, `key_result:`, `task:`, …) và số liệu (`so_lieu:<KHOÁ>=<số>`); `app/rules/giai_thich.py` dựng câu giải thích tiếng Việt từ chuỗi đó.

## Cách chạy

Với mỗi tenant có hồ sơ đánh giá trong kho, job lấy bộ ngưỡng qua `GET /api/v1/config/anomaly-thresholds` (token client-credentials cộng tiêu đề `X-Tenant-Id`). Không lấy được ngưỡng thì tenant đó bị bỏ qua và mã thoát khác 0. Mỗi luật là một tệp `INSERT ... SELECT` trong `app/rules/`, bỏ qua `alert_key` đã có, nên chạy lại không sinh cảnh báo mới. Bộ phát mã hoá Avro theo lược đồ đã đăng ký trong Apicurio (không tự đăng ký), đặt global id ở tiêu đề `apicurio.value.globalId`, khoá `tenant:subjectId`, `id` là UUIDv5 tất định của `(tenant, alertKey)`, và chỉ ghi đã phát sau khi Kafka xác nhận.

```bash
anomaly-job                    # phát hiện rồi phát
anomaly-job --tenant <uuid>    # chỉ một tenant
anomaly-job --chi-phat         # chỉ phát các dòng còn sót
```

Biến môi trường bắt buộc: `ANALYTICS_CELL`, `CLICKHOUSE_URL`, `CLICKHOUSE_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `REGISTRY_URL`, `CONFIG_API_URL`, `OIDC_TOKEN_URL`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`. Biến `KAFKA_<X>` khác được chuyển thành cấu hình librdkafka `x`. Bí mật lấy từ OpenBao, không ghi vào repo.

## Dự báo nguy cơ trễ hạn (DV-15-10)

Gói `app/delay_risk` là dịch vụ chạy liên tục (`python -m app.delay_risk.main`, mẫu `deploy/delay-risk.yaml`) cho QT-06 bước 2. Dịch vụ tiêu thụ EVT-05 `task.task.progressed.v1`, chờ bản ghi tiến độ của sự kiện tới kho qua CDC (tối đa `DELAY_RISK_MAX_WAIT_S`, mặc định 240 giây, quá thì giữ giá trị cũ), rồi tính lại từ trạng thái hiện tại của kho: nhịp là lượng hoàn thành mỗi ngày làm việc trong 5 ngày làm việc gần nhất, n là số ngày làm việc cần để làm nốt, m là số ngày làm việc còn lại tới hạn theo lịch `getWorkingCalendar`. Mức Thấp khi n/m ≤ 0,8, Trung bình khi ≤ 1,0, Cao khi lớn hơn hoặc đã hết hạn hay nhịp bằng 0 (đổi bằng `DELAY_RISK_WINDOW_DAYS`, `DELAY_RISK_MIN_DAYS`, `DELAY_RISK_VUA_RATIO`, `DELAY_RISK_CAO_RATIO`). Kết quả ghi qua cổng MOD-02 `PUT /api/v1/tasks/{taskId}/delay-risk` (operation đề xuất, chưa có trong hợp đồng), thân chỉ là dữ liệu n, m, nhịp để giao diện dựng MSG-06-02. Biến bắt buộc: `ANALYTICS_CELL`, `CLICKHOUSE_URL`, `CLICKHOUSE_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `CORE_API_URL`, `OIDC_TOKEN_URL`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`.

## Kiểm thử

```bash
uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -e '.[dev]'
.venv/bin/ruff check . && .venv/bin/mypy app && .venv/bin/python -m pytest -q
```

Kiểm thử tích hợp dựng ClickHouse 25.8, Kafka 3.9 và Apicurio 3.0.9, áp toàn bộ migration kho, nạp dữ liệu vàng `data/golden/anomaly`, rồi kiểm tra hai luật đầu, việc chạy lại, hợp đồng EVT-20 và quyền của tài khoản job. Phần dự báo trễ hạn kiểm với bộ ca vàng `data/golden/delay-risk` (công thức) và luồng EVT-05 → kho → cổng MOD-02 giả.

## Còn chờ

Dấu hiệu 3 đến 8 thuộc DV-15-08, DV-15-09. Chart, Secret và mạng ở cụm thật chờ INF; `deploy/cronjob.yaml` chỉ là mẫu. Các giả định đã chọn ghi ở `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-15-07.
