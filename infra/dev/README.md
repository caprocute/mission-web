# Hạ tầng Dev dùng chung — Dự án Mission (`infra/dev/`)

Thư mục này chứa cấu hình Docker Compose cho môi trường phát triển dùng chung của dự án **Mission** (QĐ 366 · OKR/KPI), bám sát danh mục công nghệ tại **TL-01 Phụ lục B**, **TL-03** và **TL-04**.

> **Quy tắc an toàn:** Server dev đang chạy các hệ thống khác. Tuyệt đối **không** dừng/xoá container không mang tiền tố `mission-dev-`, **không** dùng `docker system prune`, và **không** commit tệp `.env` chứa mật khẩu thật.

---

## 1. Bảng cổng dịch vụ (dải `35xxx`) và biến môi trường động

Toàn bộ cổng host đặt trong dải `35000–35999` và đều cấu hình động qua biến môi trường:

| Dịch vụ | Container | Cổng host (mặc định) | Biến môi trường cổng | Profile | Ghi chú |
|---|---|---|---|---|---|
| PostgreSQL 17 (`+ pg_partman`) | `mission-dev-postgres` | `35432` | `POSTGRES_PORT` | `core` | Dành cho Flyway (`mig_<schema>`) & Debezium (`cdc_<schema>`) |
| PgBouncer (`transaction` mode) | `mission-dev-pgbouncer` | `35433` | `PGBOUNCER_PORT` | `core` | Dành cho HikariCP của ứng dụng (`svc_<schema>`) |
| Valkey 8 | `mission-dev-valkey` | `35379` | `VALKEY_PORT` | `core` | Cache L2, khoá phân tán, thu hồi JWT `jti` |
| Keycloak 26 | `mission-dev-keycloak` | `35080` | `KEYCLOAK_PORT` | `core` | Realm `mission` được nạp tự động khi khởi động |
| OPA 1.x | `mission-dev-opa` | `35181` | `OPA_PORT` | `security` | Đánh giá chính sách RBAC + ABAC Rego (`/v1/data/okrkpi/authz/decision`) |
| OpenBao 2.x | `mission-dev-openbao` | `35820` | `OPENBAO_PORT` | `security` | Transit KEK (`kek-default`, `kek-tenant-demo`, `blind-index-default`) & `kv-v2` (`secret/`) |
| Kafka 3.9 (KRaft) | `mission-dev-kafka` | `35092` / `35094` | `KAFKA_PORT` / `KAFKA_EXTERNAL_PORT` | `events` | Không dùng ZooKeeper; kết nối ngoài qua `35094` |
| Apicurio Registry 3.x | `mission-dev-apicurio` | `35081` | `APICURIO_PORT` | `events` | Lưu schema Avro/JSON vào PostgreSQL (`schema=apicurio`) |
| Debezium Connect 3.x | `mission-dev-debezium` | `35083` | `DEBEZIUM_PORT` | `events` | CDC Outbox từ PostgreSQL sang Kafka bằng `cdc_<schema>` |
| MinIO (S3 API / Console) | `mission-dev-minio` | `35900` / `35901` | `MINIO_API_PORT` / `MINIO_CONSOLE_PORT` | `search` | Tương thích Ceph RGW; tự tạo 4 bucket chuẩn |
| OpenSearch 2.x | `mission-dev-opensearch` | `35200` | `OPENSEARCH_PORT` | `search` | Tìm kiếm toàn văn (thay Elasticsearch theo Phụ lục B) |
| ClickHouse 25.8 | `mission-dev-clickhouse` | `35123` | `CLICKHOUSE_HTTP_PORT` | `analytics` | Kho phân tích SVC-15 một nút (Keeper nhúng, cụm `cell_dev`); migration ở `analytics/clickhouse` |
| Cube v1.3.60 | `mission-dev-cube` | `35400` | `CUBE_PORT` | `analytics` | Lớp ngữ nghĩa chỉ số chuẩn SVC-15 (DV-15-03); mô hình ở `analytics/cube` |
| OTel Collector (gRPC / HTTP) | `mission-dev-otel` | `35317` / `35318` | `OTEL_GRPC_PORT` / `OTEL_HTTP_PORT` | `obs` | Nhận trace/metric/log OTLP từ ứng dụng |
| Loki 3.x | `mission-dev-loki` | `35310` | `LOKI_PORT` | `obs` | Lưu log tập trung |
| Tempo 2.x | `mission-dev-tempo` | `35320` | `TEMPO_PORT` | `obs` | Lưu trace phân tán |
| Prometheus 3.x | `mission-dev-prometheus` | `35990` | `PROMETHEUS_PORT` | `obs` | Thu thập metric |
| Grafana 11.x | `mission-dev-grafana` | `35300` | `GRAFANA_PORT` | `obs` | Đã gắn sẵn datasource Prometheus, Loki, Tempo |
| SoftHSM2 2.6 (khởi tạo token) | `mission-dev-softhsm-init` | không mở cổng | `ESIGN_HSM_PIN`, `ESIGN_HSM_SO_PIN` | `backend` | Token `mission-dev` cho esign-service (DV-10-03), lưu ở `softhsm/tokens` (không commit) |
| TSA RFC 3161 giả lập | `mission-dev-tsa-mock` | `35350` | `TSA_MOCK_PORT` | `backend` | `openssl ts -reply`; gốc tin cậy xuất ra `softhsm/tsa/ca.pem` |

---

## 2. Kết nối qua cổng hầm SSH (SSH Tunnel) khi máy chủ hạn chế mở cổng

Khi máy chủ dev chỉ mở cổng SSH (`22`) và không mở trực tiếp dải cổng `35xxx` ra mạng ngoài, lập trình viên mở một phiên hầm SSH từ máy trạm cá nhân:

```bash
ssh -N \
  -L 35432:127.0.0.1:35432 \
  -L 35433:127.0.0.1:35433 \
  -L 35379:127.0.0.1:35379 \
  -L 35080:127.0.0.1:35080 \
  -L 35181:127.0.0.1:35181 \
  -L 35820:127.0.0.1:35820 \
  -L 35094:127.0.0.1:35094 \
  -L 35081:127.0.0.1:35081 \
  -L 35083:127.0.0.1:35083 \
  -L 35900:127.0.0.1:35900 \
  -L 35200:127.0.0.1:35200 \
  -L 35123:127.0.0.1:35123 \
  -L 35400:127.0.0.1:35400 \
  -L 35318:127.0.0.1:35318 \
  -L 35300:127.0.0.1:35300 \
  <SSH_USER>@<SSH_HOST>
```

Mọi service backend (`application-dev.yaml`) và công cụ dev đều mặc định trỏ về `127.0.0.1` với các biến môi trường động (`DB_HOST`, `DB_PORT`, `PGBOUNCER_PORT`, `VALKEY_HOST`, `VALKEY_PORT`, `KEYCLOAK_ISSUER_URI`, `OPA_URL`, `OPENBAO_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `APICURIO_REGISTRY_URL`, `OTLP_METRICS_URI`), nên chạy ngay qua hầm SSH mà không cần sửa mã hay tệp cấu hình.

---

## 3. Cách vận hành các profile

```bash
cd infra/dev
cp .env.example .env   # điền mật khẩu thật vào .env (không commit)

# 1. Chỉ bật nền tảng cốt lõi (Postgres + PgBouncer, Valkey, Keycloak)
docker compose up -d

# 2. Bật thêm bảo mật (OPA 1.x + OpenBao 2.x kèm khoá KEK mẫu)
docker compose --profile core --profile security up -d

# 3. Bật thêm luồng sự kiện (Kafka KRaft, Apicurio, Debezium) và tạo đầy đủ topic
docker compose --profile core --profile security --profile events up -d
./scripts/create-topics.sh

# 4. Bật toàn bộ hạ tầng
docker compose --profile all up -d

# 5. Kiểm tra sức khoẻ
./scripts/health.sh
```

---

## 4. Mô hình 4 tài khoản CSDL theo schema và nâng cấp volume `pg_data`

Theo **TL-03 ("Tài khoản kết nối CSDL", "Ứng dụng không có quyền DDL")**, mỗi schema `<schema>` có đúng 4 tài khoản PostgreSQL (tất cả đều `NOBYPASSRLS`, `NOINHERIT`, `NOCREATEDB`, `NOCREATEROLE`):

1. `mig_<schema>`: chủ sở hữu schema, chỉ dùng khi chạy Flyway DDL trực tiếp vào PostgreSQL (`35432`), có quyền gọi `partman.*`.
2. `svc_<schema>`: tài khoản chạy ứng dụng qua PgBouncer (`35433`), chỉ có quyền DML (`SELECT, INSERT, UPDATE, DELETE`), bị áp RLS và khoá cứng `statement_timeout=5s`, `idle_in_transaction_session_timeout=10s`, `lock_timeout=2s`.
3. `ro_<schema>`: chỉ đọc (`SELECT`), phục vụ đối soát/chẩn đoán.
4. `cdc_<schema>`: có quyền `REPLICATION` + `SELECT` để Debezium đọc WAL và `outbox_event`.

Các ngoại lệ liên schema theo TL-03 đã được cấu hình sẵn:
- 8 tài khoản `svc_<module>` của `mission-core` (`config`, `policy`, `okr`, `task`, `product`, `appraisal`, `dossier`, `oversight`) chỉ có quyền `SELECT` trên `org_replica` (chỉ `svc_org_replica` mới có quyền ghi), và có quyền đọc/ghi trên `modulith`.
- Mọi `svc_<schema>` nghiệp vụ có quyền `SELECT, INSERT` trên `keyring` (chỉ `svc_keyring` mới có `UPDATE, DELETE`).

### Nâng cấp trên volume `pg_data` đã tồn tại (không cần xoá dữ liệu)

Khi volume `pg_data` đã được tạo từ trước, `/docker-entrypoint-initdb.d/01-schemas.sh` sẽ không tự chạy lại. Để cập nhật `pg_partman`, các schema mới (`modulith`, `keyring`) và bộ tài khoản `mig_`/`svc_`/`ro_`/`cdc_` mà **không mất dữ liệu**:

```bash
# Chạy lại build ảnh postgres (có cài postgresql-17-partman) và khởi động lại
docker compose up -d --build postgres pgbouncer

# Chạy script di trú role bất biến (idempotent)
./postgres/migrate-roles.sh
```

---

## 5. Realm Keycloak dev (`mission`)

Tệp `keycloak/realm-mission.json` tạo sẵn:
- **Realm**: `mission`
- **Client SPA (PKCE)**: `mission-web` (`redirectUris`: `http://localhost:4200/*`)
- **Client dịch vụ**: `mission-core`; `org-service` (tài khoản dịch vụ, vai trò `svc_org_service`, bí mật `ORG_SERVICE_CLIENT_SECRET`)
- **Các vai trò chuẩn**: `SYS_ADMIN`, `LANH_DAO_DON_VI`, `THU_TRUONG_CO_QUAN`, `CHUYEN_VIEN_THAM_MUU`, `CAN_BO`, `HOI_DONG_THAM_DINH`, `KIEM_TRA_GIAM_SAT`
- **Protocol mappers**: tự động gắn `tenant_id`, `org_path`, `roles` vào access token và ID token.

---

## 6. SoftHSM2 và máy chủ dấu thời gian giả lập cho esign-service (DV-10-03)

Môi trường dev không có HSM thật, nên esign-service dùng SoftHSM2 qua cùng giao diện PKCS#11 như HSM production, và dùng một máy chủ dấu thời gian RFC 3161 giả lập (YC-PT-13). PKCS#11 là thư viện nạp thẳng vào tiến trình JVM chứ không phải dịch vụ mạng, nên container `softhsm-init` chỉ khởi tạo token trong thư mục `softhsm/tokens`; esign-service chạy trên máy dev nạp `libsofthsm2` của chính máy đó và đọc cùng thư mục token qua biến `SOFTHSM2_CONF`.

```bash
cd infra/dev
# Đặt ESIGN_HSM_PIN và ESIGN_HSM_SO_PIN trong .env (không commit), rồi:
docker compose --profile backend up -d softhsm-init tsa-mock

# Trên máy chạy esign-service (macOS: brew install softhsm; Ubuntu: apt install softhsm2):
printf 'directories.tokendir = %s\nobjectstore.backend = file\n' "$PWD/softhsm/tokens" > softhsm/softhsm2.local.conf
export SOFTHSM2_CONF=$PWD/softhsm/softhsm2.local.conf
export ESIGN_HSM_ENABLED=true ESIGN_HSM_LIBRARY=/usr/lib/softhsm/libsofthsm2.so ESIGN_HSM_PROVISION_DEV_KEYS=true
export ESIGN_TSA_ENABLED=true ESIGN_TSA_URLS=http://127.0.0.1:35350/ ESIGN_TSA_TRUST_ANCHORS=$PWD/softhsm/tsa/ca.pem
```

Lần khởi động đầu, esign-service tự tạo hai khoá ECDSA P-256 trong token (`mission-org-sign` cho chữ ký tổ chức, `mission-seal` cho niêm phong sổ cái) với `CKA_EXTRACTABLE=false` cùng chứng thư tự ký; production nạp khoá theo quy trình nghi lễ khoá và tắt `ESIGN_HSM_PROVISION_DEV_KEYS`. Readiness `/actuator/health/readiness` có chỉ báo `hsm` ký thử một băm cố định, nên khi token mất hoặc phiên PKCS#11 hỏng thì pod chuyển DOWN. Kiểm tra dấu thời gian bằng tay: `openssl ts -verify -digest <gốc hex> -in token.tst -token_in -CAfile softhsm/tsa/ca.pem`.

