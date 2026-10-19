# Hạ tầng dùng chung môi trường DEV

Thư mục này mô tả bộ hạ tầng dùng chung mà mọi lập trình viên và AI agent của phân hệ web Mission dùng khi phát triển. Bộ hạ tầng bám danh mục "hạ tầng dùng chung trong cell" của baseline (mục 5) và TL-04, nhưng thu gọn mỗi loại về một instance để chạy được trên một máy chủ dev. Mọi thành phần chạy bằng Docker Compose với tên dự án `mission-dev`, một mạng Docker riêng `mission-dev`, và cổng nằm trong dải 35xxx để không đụng các hệ thống khác đang chạy trên cùng máy.

## Thành phần và profile

Compose chia thành profile để chỉ bật phần cần dùng. Bảng dưới liệt kê thành phần, quyết định kiến trúc mà nó đại diện, và cổng mặc định trên máy chủ.

| Profile | Dịch vụ | Đại diện cho | Cổng mặc định |
|---|---|---|---|
| core | postgres (17) | ADR-02, mỗi module một schema + tài khoản | 35432 |
| core | valkey (8) | ADR-18, cache theo cell | 35379 |
| core | keycloak (26.4) realm `mission` | ADR-08, SVC-02 | 35080 |
| core | minio + bucket `mission-evidence` có Object Lock | ADR-13 (thay Ceph RGW ở dev) | 35900 (API), 35901 (console) |
| core | mailpit | Hộp thư giả cho SVC-12 | 35025 (SMTP), 35825 (UI) |
| events | kafka (3.9, KRaft) + 26 topic EVT | ADR-03 | 35094 |
| events | apicurio registry (3.0) | ADR-03 | 35081 |
| events | connect (Debezium 3.1) | Outbox → Kafka | 35083 (chỉ 127.0.0.1) |
| events | kafka-ui | Quan sát topic | 35082 |
| search | opensearch (2.19) | ADR-15, SVC-16 | 35920 (chỉ 127.0.0.1) |
| analytics | clickhouse (25.3) | ADR-14, SVC-15 | 35123 |
| observability | grafana/otel-lgtm | ADR-12 thu gọn | 35300 (Grafana), 35317/35318 (OTLP) |
| gateway | apisix (3.12, standalone) | SVC-01 | 35000 |
| web | nginx phục vụ fe-shell + fe-task | SVC-17 | 35200 |

PostgreSQL tạo sẵn database `mission` với các schema `okr, task, product, appraisal, policy, dossier, oversight, config, org_replica, org, registry, scoring, evidence, esign, docgen, notify, integ, ai, apicurio`, mỗi schema có một role cùng tên dùng chung mật khẩu `MISSION_DB_PASSWORD`; hai database riêng `flowable` (SVC-06) và `ledger` (SVC-09); role `debezium` có quyền replication. Keycloak nạp realm `mission` (WEB-056) với 11 vai trò AC đúng theo `docs/phan-quyen/ma-tran.json`, client công khai `mission-web` (Authorization Code + PKCE S256) dùng client scope chung `mission-context` (vai trò realm, `tenant`, `unit_id`, `acr`), luồng đăng nhập `mission-browser` có mức xác thực `aal1`/`aal2` và OTP bắt buộc với năm vai trò AAL2 của BR-00-01, cùng mười tài khoản thử phủ đủ vai trò AC-01..AC-10:

| Tài khoản | Vai trò | Đơn vị (`unit_id`) | OTP khi đăng nhập |
|---|---|---|---|
| `nentang` | `platform_admin` | – | Có |
| `quantri` | `tenant_admin`, `policy_author` | `phong-tong-hop` | Có |
| `chinhsach` | `policy_author` | `phong-tong-hop` | Không |
| `lanhdao` | `member`, `leader` | `phong-tong-hop` | Không |
| `quanly` | `member`, `manager` | `phong-tong-hop` | Không |
| `canbo` | `member` | `phong-tong-hop` | Không |
| `thamdinh` | `member`, `appraiser` | `ban-to-chuc` | Có |
| `quyetdinh` | `decider` | `ban-to-chuc` | Có |
| `duyet2` | `second_approver` | `ban-to-chuc` | Có |
| `kiemtra` | `member`, `inspector` | `uy-ban-kiem-tra` | Không |

Mọi tài khoản thử thuộc tenant `default`, dùng chung mật khẩu `KC_TEST_USER_PASSWORD` và đã có sẵn OTP với khoá `KC_TEST_OTP_SECRET` (cả hai trong `.env`, không commit); mã OTP sinh bằng ứng dụng TOTP bất kỳ hoặc trong kiểm thử tự động từ khoá này. Tài khoản không có vai trò AAL2 chỉ bị hỏi OTP khi ứng dụng yêu cầu `acr_values=aal2` (step-up). `ai_readonly` (AC-12) không có tài khoản vì trợ lý AI đọc dưới danh tính người dùng qua ai-gateway. Kiểm tra realm khớp ma trận bằng `python3 docs/phan-quyen/check_realm.py`.

## Cách chạy

```bash
cd infra/dev
./scripts/gen-env.sh                       # sinh .env với mật khẩu ngẫu nhiên (một lần)
# sửa PUBLIC_HOST, KEYCLOAK_PUBLIC_URL, WEB_PUBLIC_URL, DATA_DIR trong .env cho đúng máy chủ
docker compose --profile core up -d        # bật lõi
docker compose --profile core --profile events --profile observability up -d
./scripts/health.sh                        # kiểm tra
```

### Nạp lại realm `mission` sau khi sửa `realm-mission.json`

Keycloak chỉ nhập realm khi realm chưa tồn tại (`--import-realm` bỏ qua realm đã có), nên sau khi sửa tệp realm phải xoá realm cũ rồi khởi động lại container. Việc này xoá cả phiên và tài khoản tạo tay trong realm `mission` ở dev; chỉ thao tác trên container của project `mission-dev`:

```bash
cd infra/dev
# .env sinh trước WEB-056 chưa có ba biến mới: thêm với giá trị ngẫu nhiên (gen-env.sh không ghi đè .env đã có)
for v in KC_TEST_USER_PASSWORD KC_TEST_OTP_SECRET MISSION_CORE_CLIENT_SECRET; do
  grep -q "^$v=" .env || echo "$v=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)" >> .env
done
docker compose exec keycloak sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" \
  && /opt/keycloak/bin/kcadm.sh delete realms/mission'
docker compose --profile core up -d --force-recreate keycloak
```

Dữ liệu nằm dưới `DATA_DIR` (bind mount) chứ không ở thư mục gốc của Docker, vì ổ chứa Docker trên máy chủ dev gần đầy. Mọi container giới hạn log ở 3 tệp × 20 MB, Kafka giữ dữ liệu 72 giờ, Valkey tối đa 512 MB.

## Khác biệt so với production

Môi trường này không thay thế được kiến trúc production của TL-04: production chạy trên Kubernetes với CloudNativePG + PgBouncer, Valkey Cluster, Kafka nhiều broker, Ceph RGW với Object Lock chế độ COMPLIANCE, OpenSearch nhiều node có bảo mật, Istio ambient mTLS, OpenBao + HSM cho bí mật. Ở dev, các thành phần chỉ phục vụ phát triển và kiểm thử chức năng; không đặt dữ liệu thật hay dữ liệu cá nhân vào đây.

## Ghi chú vận hành trên server dev (19/10/2026)

Hạ tầng đã chạy trên server dev với sáu profile core, events, observability, search, analytics và gateway; profile web chưa bật. Khi triển khai trên CentOS 7 (kernel 3.10) có một số điều chỉnh so với bản gốc. Image `postgres:17-alpine` bị seccomp chặn ghi vào `pg_wal`, nên chuyển sang `postgres:17` bản Debian thay vì nới seccomp. Image `minio/mc` bản RELEASE cũ đã bị gỡ khỏi Docker Hub, nên dùng `minio/mc:latest`; image `minio/minio` đang dùng bản có sẵn trên server và sẽ không pull được ở máy mới, cần chọn nguồn khác khi dựng lại. `health.sh` dùng `docker ps` lọc theo nhãn project vì compose v2.17 không nhận `ps --format` dạng template. Khi chép bundle từ macOS phải xoá các tệp `._*`, vì một tệp như vậy trong `postgres/init` làm hỏng bước khởi tạo.

Cổng 8081 trên server đã có dịch vụ khác, nên mission-core khi chạy dev phải nghe ở cổng 35100; `apisix/apisix.yaml` đã trỏ upstream tới `host.docker.internal:35100`. OpenSearch cảnh báo `vm.max_map_count=65530` (khuyến nghị 262144); một node dev vẫn chạy được, muốn sửa phải có sudo. Grafana LGTM khởi động mất khoảng ba phút. Debezium Connect và OpenSearch chỉ mở trên 127.0.0.1 của server, truy cập qua SSH tunnel. Mật khẩu dịch vụ nằm trong `/home/provision/mission-infra/.env` trên server (chmod 600), không chép vào repo hay chat.
