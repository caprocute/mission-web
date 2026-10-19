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

PostgreSQL tạo sẵn database `mission` với các schema `okr, task, product, appraisal, policy, dossier, oversight, config, org_replica, org, registry, scoring, evidence, esign, docgen, notify, integ, ai, apicurio`, mỗi schema có một role cùng tên dùng chung mật khẩu `MISSION_DB_PASSWORD`; hai database riêng `flowable` (SVC-06) và `ledger` (SVC-09); role `debezium` có quyền replication. Keycloak nạp realm `mission` với 11 vai trò AC, client công khai `mission-web` (Authorization Code + PKCE S256) và năm tài khoản thử `canbo`, `quanly`, `lanhdao`, `thamdinh`, `quantri` (mật khẩu tạm `Mission@123`, buộc đổi ở lần đăng nhập đầu).

## Cách chạy

```bash
cd infra/dev
./scripts/gen-env.sh                       # sinh .env với mật khẩu ngẫu nhiên (một lần)
# sửa PUBLIC_HOST, KEYCLOAK_PUBLIC_URL, WEB_PUBLIC_URL, DATA_DIR trong .env cho đúng máy chủ
docker compose --profile core up -d        # bật lõi
docker compose --profile core --profile events --profile observability up -d
./scripts/health.sh                        # kiểm tra
```

Dữ liệu nằm dưới `DATA_DIR` (bind mount) chứ không ở thư mục gốc của Docker, vì ổ chứa Docker trên máy chủ dev gần đầy. Mọi container giới hạn log ở 3 tệp × 20 MB, Kafka giữ dữ liệu 72 giờ, Valkey tối đa 512 MB.

## Khác biệt so với production

Môi trường này không thay thế được kiến trúc production của TL-04: production chạy trên Kubernetes với CloudNativePG + PgBouncer, Valkey Cluster, Kafka nhiều broker, Ceph RGW với Object Lock chế độ COMPLIANCE, OpenSearch nhiều node có bảo mật, Istio ambient mTLS, OpenBao + HSM cho bí mật. Ở dev, các thành phần chỉ phục vụ phát triển và kiểm thử chức năng; không đặt dữ liệu thật hay dữ liệu cá nhân vào đây.
