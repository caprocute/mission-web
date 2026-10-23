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
| Kafka 3.9 (KRaft) | `mission-dev-kafka` | `35092` / `35094` | `KAFKA_PORT` / `KAFKA_EXTERNAL_PORT` | `events` | Không dùng ZooKeeper; `35092` là listener TUNNEL quảng bá `127.0.0.1`, chỉ mở trên loopback máy chủ, dùng trên máy chủ và qua hầm SSH; `35094` quảng bá `DEV_HOST`, chỉ dùng khi tường lửa mở cổng |
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
| APISIX 3.13 (cổng API dev) | `mission-dev-apisix` | `35000` | `APISIX_PORT` | `backend` | Tuyến theo tiền tố module, kiểm JWT realm `mission` (`apisix/apisix.yaml`, INF-22) |
| mission-core (SVC-05) | `mission-dev-mission-core` | `35100` / `35101` | `MISSION_CORE_PORT` / `MISSION_CORE_MGMT_PORT` | `backend` | Cổng quản trị `35101` chỉ mở trên loopback máy chủ |
| tenant-registry (SVC-03, Go) | `mission-dev-tenant-registry` | `35110` | `TENANT_REGISTRY_PORT` | `backend` | Schema `registry` |
| org-service (SVC-04) | `mission-dev-org-service` | `35111` | `ORG_SERVICE_PORT` | `backend` | Schema `org` |
| workflow-service (SVC-06) | `mission-dev-workflow-service` | `35112` | `WORKFLOW_SERVICE_PORT` | `backend` | CSDL `flowable` |
| scoring-service (SVC-07) | `mission-dev-scoring-service` | `35113` | `SCORING_SERVICE_PORT` | `backend` | Schema `scoring` |
| document-service (SVC-11) | `mission-dev-document-service` | `35114` | `DOCUMENT_SERVICE_PORT` | `backend` | Schema `docgen`, cần gotenberg, MinIO |
| evidence-service (SVC-08) | `mission-dev-evidence-service` | `35115` | `EVIDENCE_SERVICE_PORT` | `backend` | Schema `evidence`, quét virus tắt |
| esign-service (SVC-10) | `mission-dev-esign-service` | `35116` | `ESIGN_SERVICE_PORT` | `backend` | Schema `esign`, nhà cung cấp ký giả lập |
| integration-gateway (SVC-13) | `mission-dev-integration-gateway` | `35117` | `INTEGRATION_GATEWAY_PORT` | `backend` | Schema `integ`, chỉ cổng nội bộ |
| analytics-api (SVC-15) | `mission-dev-analytics-api` | `35118` | `ANALYTICS_API_PORT` | `backend` | Cần profile `analytics` |
| search (SVC-16) | `mission-dev-search` | `35119` | `SEARCH_SERVICE_PORT` | `backend` | Cần OpenSearch (profile `search`) |
| ledger-service (SVC-09, Go) | `mission-dev-ledger-service` | `35120` | `LEDGER_SERVICE_PORT` | `backend` | CSDL `ledger` |
| notification-service (SVC-12, Go) | `mission-dev-notification-service` | `35121` | `NOTIFICATION_SERVICE_PORT` | `backend` | Schema `notify` |

---

## 2. Kết nối qua cổng hầm SSH (SSH Tunnel) khi máy chủ hạn chế mở cổng

Khi máy chủ dev chỉ mở cổng SSH (`22`) và không mở trực tiếp dải cổng `35xxx` ra mạng ngoài, lập trình viên mở một phiên hầm SSH từ máy trạm cá nhân:

```bash
ssh -N \
  -L 35000:127.0.0.1:35000 \
  -L 35432:127.0.0.1:35432 \
  -L 35433:127.0.0.1:35433 \
  -L 35379:127.0.0.1:35379 \
  -L 35080:127.0.0.1:35080 \
  -L 35181:127.0.0.1:35181 \
  -L 35820:127.0.0.1:35820 \
  -L 35092:127.0.0.1:35092 \
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

**Kafka qua hầm SSH (INF-22).** Broker có ba listener cho client. `INTERNAL` (`kafka:9092`) dành cho container cùng mạng `mission-net`. `TUNNEL` mở ở cổng `KAFKA_PORT` (mặc định `35092`) chỉ trên loopback của máy chủ (`KAFKA_TUNNEL_BIND=127.0.0.1`) và quảng bá `KAFKA_TUNNEL_ADVERTISED_HOST:KAFKA_PORT` (mặc định `127.0.0.1:35092`), nên client chạy ngay trên máy chủ hoặc trên máy trạm qua `-L 35092:127.0.0.1:35092` đều nhận về đúng địa chỉ trong metadata; đây cũng là mặc định `KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:35092` của các service backend. `EXTERNAL` (`35094`) quảng bá `DEV_HOST` và chỉ dùng được khi tường lửa mở cổng đó; trước đây hầm SSH chỉ chuyển `35094`, nên client nhận lại địa chỉ công khai của máy chủ và không kết nối được. Sau khi đổi, cần áp lại container `mission-dev-kafka` (mục 2.1).

**Kiểm thử đầu cuối qua hầm SSH (QA-03/04).** Chế độ `E2E_MODE=he-thong` của Playwright gọi API qua APISIX dev, nên hầm phải chuyển cả cổng `35000` (đã có trong danh sách mặc định của `scripts/dev-tunnel.sh` và `.env.example`). Đặt `E2E_APISIX_URL=http://127.0.0.1:35000` và `E2E_KC_URL=http://127.0.0.1:35080`; địa chỉ Keycloak phải viết đúng `127.0.0.1` chứ không phải `localhost`, vì token mang `iss` theo địa chỉ đăng nhập và service kiểm `iss` theo `KEYCLOAK_PUBLIC_URL` (mục 3.1), lệch tên máy thì mọi lời gọi bị 401. Cổng web giữ `4200` vì client `mission-web` của realm chỉ cho redirect URI đó. Compose dev chưa có kafka-ui, nên bỏ trống `E2E_KAFKA_UI_URL` thì các ca kiểm sự kiện được ghi "Chờ" thay vì báo lỗi. Danh sách biến đầy đủ nằm ở mục cuối `.env.example` và `frontend/e2e/hanh-trinh/README.md`.

### 2.1. Đồng bộ compose trên máy chủ với repo (INF-22)

Máy chủ dev giữ một bản `infra/dev` riêng kèm `.env` thật. Khi repo đổi `infra/dev`, chạy từ một bản checkout trên máy trạm có SSH tới máy chủ (ví dụ phiên trên máy Mac):

```bash
git pull                                   # lấy đúng commit cần áp
infra/dev/scripts/sync-server.sh plan      # liệt kê khoá .env máy chủ còn thiếu, tệp sẽ chép, kiểm compose config; không đổi gì
infra/dev/scripts/sync-server.sh apply     # chép tệp rồi docker compose -p mission-dev up -d --build
```

Script đọc `DEV_SERVER_HOST`, `DEV_SERVER_SSH_PORT`, `DEV_SERVER_USER`, `DEV_SERVER_INFRA_DIR` (thư mục `infra/dev` trên máy chủ, chứa `.env` thật) và `DEV_SERVER_PROFILES` (mặc định `core security events`) từ `.env` ở gốc repo. Nó không bao giờ ghi đè `.env`, `data/`, `softhsm/tokens/`, `softhsm/tsa/` trên máy chủ, ghi commit đã áp vào `.repo-commit`, chỉ áp lại project `mission-dev` (không dùng `--remove-orphans` hay `prune`) và in số container đang chạy trước và sau để đối chiếu. Khoá mới trong `.env.example` mà máy chủ chưa có được in tên (không in giá trị); compose dùng giá trị mặc định cho tới khi bổ sung. Trước khi chép, script chạy `sync_preflight.py`: dựng cấu hình compose mới trên máy trạm (cần `docker compose` và `python3`, không cần daemon) bằng tệp của repo và các khoá không bí mật của `.env` máy chủ (`*_DATA`, `*_PORT`, `*_BIND`, `*_HOST`), rồi so với container thật của project trên máy chủ. Lần áp bị chặn khi một dịch vụ đang chạy sẽ được dựng lại trên chỗ lưu dữ liệu khác (volume có tên khác, thư mục gắn khác hoặc mất điểm gắn) hoặc khi cổng mới đang bị container khác giữ; tên container khác (ví dụ `mission-dev-postgres-1` thành `mission-dev-postgres`) chỉ được báo lưu ý vì compose dựng lại theo nhãn dịch vụ.

**Cảnh báo dữ liệu.** Máy chủ dev dùng chung đang lưu dữ liệu ở thư mục gắn `<DEV_SERVER_INFRA_DIR>/data/<dịch vụ>`, còn compose của repo mặc định dùng volume có tên. Trước lần `apply` đầu tiên, đặt trong `.env` máy chủ các biến `POSTGRES_DATA`, `KAFKA_DATA`, `MINIO_DATA`, `VALKEY_DATA`, `OPENSEARCH_DATA`, `CLICKHOUSE_DATA`, `GRAFANA_DATA`, `PROMETHEUS_DATA`, `LOKI_DATA`, `TEMPO_DATA` thành đường dẫn tuyệt đối tới đúng thư mục dữ liệu hiện có (xem `docker inspect` của container đang chạy), chạy `plan` tới khi không còn mục `[DỪNG]`. Nếu bỏ qua bước này, Postgres, Kafka, MinIO sẽ khởi động trên volume trống và mất dữ liệu Keycloak, tổ chức, registry, schema mission-core; script chặn trường hợp đó, không có cờ bỏ qua.

Khi máy trạm hoặc máy chủ thiếu `rsync`, script chép bằng `tar` các tệp git theo dõi, nên tệp đã xoá khỏi repo phải xoá tay trên máy chủ.

Ba trường hợp `plan` chỉ báo lưu ý, không chặn:

- **Giao diện và danh sách mật khẩu cấm của Keycloak.** Compose gắn `../keycloak-theme/mission` và `./keycloak/password-blacklists` (realm đòi `mat-khau-pho-bien.txt`). Script chép thêm `infra/keycloak-theme` sang thư mục anh em `<thư mục cha của DEV_SERVER_INFRA_DIR>/keycloak-theme`, nên không còn phải chép tay vào container.
- **Volume ẩn danh do ảnh khai `VOLUME`** (`/mnt/shared/config`, `/etc/kafka/secrets` của Kafka; `/openbao/file`, `/openbao/logs` của OpenBao và openbao-init). Compose kế thừa chúng khi dựng lại container cùng dịch vụ, kể cả khi tên container đổi, vì script không dùng `-V`. OpenBao dev chạy `-dev` nên dữ liệu khoá thật nằm trong bộ nhớ; `openbao-init` nạp lại khoá mẫu sau mỗi lần dựng.
- **Đổi tên container** (`mission-dev-postgres-1` thành `mission-dev-postgres`…). Compose tìm container cũ theo nhãn dịch vụ, đổi tên nó tạm, tạo container mới với cùng chỗ lưu dữ liệu rồi xoá container cũ. Lệnh tay hay kịch bản trên máy chủ còn gọi tên `-1` phải đổi sang tên mới.

Dịch vụ được thay thế khai ở `SYNC_RETIRE` (mặc định `connect=debezium`: máy chủ từng chạy Debezium dưới tên dịch vụ `connect`, giữ cổng 35083). `plan` coi container cũ sẽ bị xoá, nên không báo tranh cổng; nếu container cũ còn volume có tên hoặc thư mục dưới `data/` thì chặn. `apply` dừng và xoá đúng container có nhãn `com.docker.compose.project=mission-dev` và `com.docker.compose.service=connect` ngay trước `up`.

**Các bước cho phiên trên máy Mac** (lần áp đầu tiên):

1. `git pull` nhánh `main`, kiểm `.env` ở gốc repo có `DEV_SERVER_HOST`, `DEV_SERVER_INFRA_DIR`.
2. Trên máy chủ, ghi danh sách connector cũ để so sau khi áp: `curl -s http://127.0.0.1:35083/connectors`. Debezium mới dùng topic trạng thái `_debezium_configs`, `_debezium_offsets`, `_debezium_status`; nếu `connect` cũ dùng topic khác thì connector phải đăng ký lại sau khi áp.
3. Bổ sung `*_DATA` trong `.env` máy chủ như đoạn cảnh báo ở trên, rồi chạy `infra/dev/scripts/sync-server.sh plan` tới khi không còn `[DỪNG]`; đọc các dòng `[lưu ý]` (đổi tên, volume ẩn danh, dịch vụ thay thế).
4. Chạy `infra/dev/scripts/sync-server.sh apply`. Sau khi xong: `docker ps --filter label=com.docker.compose.project=mission-dev`, `infra/dev/scripts/health.sh`, và kiểm Keycloak đọc được realm `mission` (trang đăng nhập có giao diện `mission`, đổi mật khẩu bằng một mật khẩu trong danh sách cấm bị từ chối).
5. So `curl -s http://127.0.0.1:35083/connectors` với danh sách ở bước 2, đăng ký lại connector thiếu.
6. Quay lui khi lỗi: `git checkout <commit trong .repo-commit trước đó>` trên máy trạm rồi `apply` lại; dữ liệu ở `data/` và volume ẩn danh không bị đụng.

### 2.2. Triển khai lại máy chủ dev bằng một lệnh (INF-22)

`infra/dev/scripts/trien-khai-lai.sh plan|apply [service...]` gom mọi bước trên thành một quy trình cho phiên trên máy Mac. Script cùng cách với stack dựng trên máy cloud: hạ tầng theo profile `core security events search`, `create-topics.sh`, `them-client-provisioner.sh`, rồi `deploy-backend.sh` (dựng ảnh, migration, bật, chờ healthcheck, nạp kỳ hiện hành) và `seed-org.sh`, `smoke-backend.sh`, `health.sh`. Mặc định triển khai `apisix mission-core web` (mission-core cổng 35100; web vào qua APISIX 35000). Dịch vụ compose chưa có ở commit đang chạy (ví dụ `web` khi chưa gộp) được báo và bỏ qua.

Profile `backend` dựng ảnh từ `../../backend`, `../../frontend`, nên máy chủ cần bố cục repo. Script chép mã nguồn của commit HEAD (`git archive`, chỉ tệp git theo dõi) vào `DEV_SERVER_REPO_DIR` (mặc định `/home/<DEV_SERVER_USER>/mission-web`) và dùng `<DEV_SERVER_REPO_DIR>/infra/dev` làm thư mục hạ tầng. Lần đầu, `.env` được sao (quyền 600, không đọc nội dung) từ thư mục cũ `DEV_SERVER_INFRA_DIR`. Thư mục cũ giữ nguyên, và dữ liệu vẫn ở đó qua các biến `*_DATA` đặt đường dẫn tuyệt đối. `sync_preflight.py` coi mọi thư mục gắn có `/data/` trong đường dẫn, hoặc điểm gắn mà cấu hình mới dùng volume, là dữ liệu, nên thiếu `*_DATA` vẫn bị chặn sau khi đổi thư mục.

Jar Java dựng trên máy trạm khi có JDK 21 và Node (`JAR_BUILD=may-tram`, mặc định khi đủ công cụ), rồi chép `target/*.jar` lên máy chủ và chạy `deploy-backend.sh` với `SKIP_JARS=1`. Jar độc lập kiến trúc nên ảnh vẫn dựng trên máy chủ. `JAR_BUILD=may-chu` để máy chủ tự dựng (cần JDK 21, Node). `plan` báo dung lượng trống ở thư mục gốc Docker: dựng toàn bộ ảnh cần khoảng 12 GB cache (`MIN_FREE_GB`).

Các bước cho phiên trên máy Mac:

1. Kéo `main`, kiểm `.env` ở gốc repo có `DEV_SERVER_HOST`, `DEV_SERVER_USER`, `DEV_SERVER_INFRA_DIR` (thư mục cũ) và, nếu khác mặc định, `DEV_SERVER_REPO_DIR`.
2. Trong `.env` máy chủ (thư mục cũ), đặt `*_DATA` theo mục 2.1 và bổ sung `WEB_ORIGIN` (địa chỉ người dùng mở web qua APISIX, ví dụ `http://127.0.0.1:35000` khi dùng hầm SSH). Không sinh lại `.env`.
3. Ghi danh sách connector cũ: `curl -s http://127.0.0.1:35083/connectors` trên máy chủ.
4. `infra/dev/scripts/trien-khai-lai.sh plan`: compose config hợp lệ với mọi profile, máy chủ đủ dung lượng, không còn `[DỪNG]`. Khi thiếu chỗ, dọn ảnh và cache dựng cũ của project `mission-dev` (không đụng container ngoài project).
5. `infra/dev/scripts/trien-khai-lai.sh apply`. Chỉ khi bước 4 sạch: lần áp dựng lại minio, minio-init với ảnh mới (giữ `MINIO_DATA`), OPA, kafka-init, Keycloak có giao diện và danh sách mật khẩu cấm, xoá `connect` cũ, rồi bật mission-core, APISIX, web.
6. Kiểm: `smoke-backend.sh` đạt, mở web qua APISIX và đăng nhập bằng tài khoản thử, so connector với bước 3.
7. Quay lui: `DEV_SERVER_REPO_DIR` mới có thể bỏ. Đặt lại thư mục cũ bằng `sync-server.sh apply` với `DEV_SERVER_INFRA_DIR` cũ, sau khi `git checkout` commit trước đó.

---

## 3. Cách vận hành các profile

```bash
cd infra/dev
cp .env.example .env   # điền mật khẩu thật vào .env (không commit)

# 1. Chỉ bật nền tảng cốt lõi (Postgres + PgBouncer, Valkey, Keycloak)
docker compose up -d

# 2. Bật thêm bảo mật (OPA 1.x + OpenBao 2.x kèm khoá KEK mẫu)
docker compose --profile core --profile security up -d

# 3. Bật thêm luồng sự kiện (Kafka KRaft, Apicurio, Debezium); dịch vụ một lần kafka-init tự tạo đủ topic
docker compose --profile core --profile security --profile events up -d
docker compose logs kafka-init   # "Hoàn tất: tạo mới …, đã có …"

# 4. Bật toàn bộ hạ tầng
docker compose --profile all up -d

# 5. Kiểm tra sức khoẻ
./scripts/health.sh
```

Danh sách topic của dev là `kafka/topics.txt`, sinh từ danh mục topic của production (`deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml`, cùng nguồn với KafkaTopic của Strimzi) bằng `python3 -I infra/dev/scripts/sinh-danh-sach-topic.py`; khi `contracts/events` đổi và danh mục được sinh lại, chạy lại lệnh này và commit tệp (`--kiem` báo lệch). Mọi topic ở dev có `KAFKA_PARTITIONS` phân vùng (mặc định 3), replication-factor 1. Muốn tạo lại bằng tay (ví dụ sau khi xoá dữ liệu Kafka): `docker compose --profile events run --rm kafka-init`, hoặc `./scripts/create-topics.sh` trên máy chủ.

Service Java bật bộ uỷ quyền OPA (`OPA_ENABLED=true`, tắt thì API của mission-core trả 401) và gọi OPA với timeout `OPA_CONNECT_TIMEOUT`, `OPA_READ_TIMEOUT` (mặc định 2s ở dev, thay cho 50/100 ms của TL-01 vốn dành cho OPA cùng pod; qua đường hầm SSH, timeout ngắn làm lời gọi quá hạn và bị từ chối với mã 403). Chạy mission-core trên máy trạm bằng hồ sơ Spring `dev` (`application-dev.yml`) dùng cùng các biến, OPA ở `127.0.0.1:35181` qua hầm.

---

### 3.1. Profile `backend`: APISIX dev và service dựng từ repo (INF-22)

Profile `backend` chạy cổng API dev (APISIX, cổng `35000`) cùng mission-core và các service đã có khung, mỗi service một cổng `351xx` theo bảng ở mục 1. Chưa có trong profile: ai-gateway (SVC-14, chưa có khung service), cel-js-runner và outbox-replay (công cụ chạy theo lệnh, không phải dịch vụ thường trực). Ảnh Java dựng từ jar trong `target/` của từng mô-đun bằng `backend/Dockerfile.java` (nền JRE có curl để compose kiểm `/actuator/health/readiness` trên cổng quản trị 8081); ảnh Go dựng trong Docker từ `services-go/` bằng `backend/Dockerfile.go`. Vì cần jar dựng trước, các service này không thuộc profile `all`.

```bash
# Trên máy có Docker và bản checkout đầy đủ của repo (JDK 21 và Node để dựng jar), hạ tầng core, security, events đang chạy:
infra/dev/scripts/deploy-backend.sh                    # dựng jar, dựng ảnh, migration, bật service, chờ healthcheck, nạp kỳ hiện hành, gọi health.sh
infra/dev/scripts/deploy-backend.sh mission-core apisix
infra/dev/scripts/smoke-backend.sh                     # sức khoẻ, 401 khi không token, GET tasks/mine và periods có JWT thử
```

`deploy-backend.sh` chỉ kiểm hạ tầng đang chạy chứ không bật hay dựng lại Postgres, Kafka (tránh gắn nhầm volume khi `.env` thiếu `*_DATA`, xem mục 2.1), và chỉ thao tác project `mission-dev` (in số container trước và sau). Migration chạy trước khi bật service: schema của mission-core (`keyring` trước) và của scoring, document, evidence, esign, integration-gateway bằng `MigrationRunner` của `vt-db-migration` trong chính ảnh service, tài khoản `mig_<schema>`, nối thẳng PostgreSQL; org-service bằng hồ sơ Spring `migrate` (`mig_org`); workflow-service tự chạy Flyway schema `flowable` lúc khởi động bằng `mig_flowable`. Migration của ba service Go có lệnh tạo và đặt tham số vai trò nên chạy bằng tài khoản quản trị PostgreSQL, chỉ lần đầu (khi bảng mốc chưa có), giống chế độ `AUTO_MIGRATE` dev của chính service. Khi có mission-core, bước cuối gọi `seed-ky-hien-hanh.sh`: đăng nhập quản trị đơn vị thử `quantri` qua client `mission-web` (như kiểm thử khói, cần `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`) rồi chạy `data/seed/config/nap_ky_hien_hanh.py` qua APISIX để tenant thử có kỳ đang mở bao trùm hôm nay, nếu không cây mục tiêu trả 404. Script chạy lại không tạo trùng, lỗi ở bước này chỉ cảnh báo, `SKIP_SEED=1` bỏ qua.

Token của Keycloak mang `iss` theo địa chỉ dùng để đăng nhập, nên `KEYCLOAK_PUBLIC_URL` (mặc định `http://127.0.0.1:35080`) phải trùng địa chỉ mà trình duyệt và kiểm thử khói dùng; service trong container kiểm `iss` theo giá trị này và lấy JWKS qua đường nội bộ `keycloak:8080`. Kiểm thử khói đăng nhập tài khoản thử (mặc định `canbo`, đổi bằng `SMOKE_USER`) qua client `mission-web` đúng như trình duyệt (Authorization Code + PKCE, mã OTP sinh từ `KC_TEST_OTP_SECRET`, `scripts/lay-token-thu.py`), nên không cần bật direct grant cho client nào. Kiểm sự kiện sau thao tác ghi là tuỳ chọn: đặt `SMOKE_WRITE_PATH`, `SMOKE_EVENT_TOPIC` (và `SMOKE_WRITE_METHOD`, `SMOKE_WRITE_BODY`).

### 3.1. Chẩn đoán tuyến API (`kiem-tuyen.py`)

Khi màn hình hiện trống hay API trả 404, chạy một lệnh trên máy chủ dev (cần Python 3.10 trở lên và PyYAML; thiếu thì cài bằng `python3 -m pip install --user pyyaml`):

```bash
infra/dev/scripts/kiem-tuyen.py                 # mọi thao tác GET không tham số bắt buộc trong contracts/openapi
infra/dev/scripts/kiem-tuyen.py --module okr --module config --all   # chỉ hai mô-đun, in mọi dòng
infra/dev/scripts/kiem-tuyen.py --no-token --json > ket-qua.json
```

Script gọi mỗi thao tác hai lần: một lần qua APISIX (`APISIX_PORT`), một lần thẳng tới cổng công bố của service mà `apisix/apisix.yaml` chọn cho đường dẫn đó (cổng lấy từ `docker-compose.yml` và `.env`). Token lấy như kiểm thử khói (`SMOKE_TOKEN`, nếu không có thì đăng nhập tài khoản thử bằng `lay-token-thu.py`). Kết quả được xếp vào các nhóm `2xx`, `401/403` (token hoặc OPA), `404-tuyen` (service không có tuyến: ảnh cũ, sai cổng, thiếu mã), `404-problem` (có tuyến nhưng thiếu dữ liệu, ví dụ tenant chưa có kỳ hiện hành hay chưa phát hành UI Pack), `404-chan` (tuyến internal-only chặn có chủ đích), `roi-web` (không tuyến API nào khớp nên APISIX trả trang web), `ket-noi` (service chưa chạy) và `khac`. Script in ảnh, nhãn commit (`org.opencontainers.image.revision`, `deploy-backend.sh` gắn khi dựng ảnh; ảnh dựng trước thay đổi này hiện `-`), thời điểm dựng và trạng thái của các container `mission-dev-*` khi có docker CLI, rồi in bảng tóm tắt theo service và các thao tác có vấn đề. Mã thoát là 1 khi có `404-tuyen`, `roi-web` hay `ket-noi` qua APISIX. Script không in token hay thân phản hồi.

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
- **Client saga cấp phát tenant**: `tenant-registry-provisioner` (tài khoản dịch vụ, vai trò `realm-management` gồm `manage-realm`, `manage-users`, `manage-identity-providers`, `view-realm`, `query-users`; bí mật `PROVISION_CLIENT_SECRET`). Keycloak chỉ nhập realm khi realm chưa có, nên với Keycloak dev đã chạy từ trước, chạy `./scripts/them-client-provisioner.sh` để thêm client này.
- **Các vai trò chuẩn**: `SYS_ADMIN`, `LANH_DAO_DON_VI`, `THU_TRUONG_CO_QUAN`, `CHUYEN_VIEN_THAM_MUU`, `CAN_BO`, `HOI_DONG_THAM_DINH`, `KIEM_TRA_GIAM_SAT`
- **Protocol mappers**: tự động gắn `tenant_id`, `org_path`, `roles` vào access token và ID token.


### Cấp phát tenant trên dev (VH-04)

tenant-registry trong profile `backend` bật saga cấp phát (`provisionConfigTenant`, `activateConfigTenant`): bước Organization, IdP và tài khoản quản trị tenant chạy trên Keycloak dev thật qua client `tenant-registry-provisioner`; PowerDNS, cert-manager, API nội bộ mission-core (Policy Pack, UI Pack, lịch nghỉ), cây tổ chức và mục lệch của org-service do container `provision-gia-lap` giả lập (YC-PT-13, mã ở `services-go/tenant-registry/cmd/provision-gia-lap`). Bộ giả lập mặc định coi mọi tenant đã có cây tổ chức (`GIA_LAP_CO_CAY=1`). Biến `PROVISION_CELL_DB_FILE` chưa đặt, nên saga bỏ qua bước CSDL bridge và KEK. Kiểm IdP trước kích hoạt (`testConfigTenantIdp`) cần issuer mà tenant-registry gọi tới được, ví dụ `http://keycloak:8080/realms/mission`. Diễn tập trọn vòng đời bằng `data/seed/onboarding-thu/dien-tap.sh` (xem README trong thư mục đó).

```bash
./scripts/them-client-provisioner.sh            # một lần, khi Keycloak đã nhập realm trước khi có client
docker compose --profile backend up -d --build provision-gia-lap tenant-registry
```

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

