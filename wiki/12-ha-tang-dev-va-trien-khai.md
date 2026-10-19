> Nguồn: `infra/dev/README.md`, `infra/dev/docker-compose.yml`, `infra/dev/apisix/apisix.yaml`; `deploy/web/Dockerfile`, `deploy/web/40-runtime-config.sh`, `deploy/web/nginx.conf`; `AGENTS.md` mục 7; TL-01 mục CI/CD và triển khai (Argo CD, Kyverno, canary, giấy phép); TL-04 mục OPS-01 (GitOps), mục lộ trình (đợt 0a đến 3), mục cell; Baseline mục 4 (ADR-13, ADR-16).

# Hạ tầng dev và triển khai

Trang này mô tả hạ tầng dùng chung ở môi trường phát triển, cách đóng gói ứng dụng web và cách nó được triển khai lên Kubernetes ở production. Theo yêu cầu bảo mật của dự án, trang không ghi địa chỉ máy chủ, tên tài khoản hay mật khẩu; các thông tin đó nằm trong tệp `.env` ở gốc repo và trong `infra/dev/.env`, cả hai không commit (`AGENTS.md` mục 7).

## Hạ tầng dev bằng Docker Compose

Hạ tầng dev chạy bằng Docker Compose với tên project `mission-dev`, mạng `mission-dev` và dải cổng 35xxx trên một máy chủ dùng chung với hệ thống khác. Quy định bắt buộc là không dừng, xoá hay sửa container nào ngoài project `mission-dev`. Môi trường dev không chứa dữ liệu thật hay dữ liệu cá nhân. Các dịch vụ được chia theo profile để chỉ bật phần cần dùng.

| Profile | Thành phần | Ghi chú |
|---|---|---|
| `core` | PostgreSQL 17, Valkey 8, Keycloak 26.4, MinIO, Mailpit | Realm `mission`; bucket minh chứng có Object Lock |
| `events` | Kafka 3.9 chế độ KRaft, Apicurio Registry 3.0, Debezium Connect 3.1, Kafka UI | 26 topic theo danh mục EVT; Connect chỉ nghe trên giao diện cục bộ |
| `search` | OpenSearch 2.19 | Chỉ nghe trên giao diện cục bộ |
| `analytics` | ClickHouse 25.3 | Dữ liệu dashboard |
| `observability` | Gói Grafana LGTM có OTel Collector | Log, trace, metric một container |
| `gateway` | APISIX 3.12 chế độ standalone | Định tuyến `/api/v1/*` và trang web |
| `web` | Ảnh `mission/web:dev` build từ `deploy/web/Dockerfile` | nginx phục vụ `fe-shell` và `fe-task` |

PostgreSQL có cơ sở dữ liệu `mission` với một schema cho mỗi module (`okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config`, `org_replica`, `org`, `registry`, `scoring`, `evidence`, `esign`, `docgen`, `notify`, `integ`, `ai`, `apicurio`), mỗi schema có một role riêng; Flowable và sổ cái (`ledger`) có cơ sở dữ liệu riêng, và có role riêng cho Debezium. Keycloak nạp realm `mission` với 11 vai trò AC và client công khai `mission-web` dùng Authorization Code + PKCE S256 (xem [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)). MinIO được dùng ở dev thay cho Ceph RGW của ADR-13; bucket minh chứng bật Object Lock để mô phỏng lưu giữ không sửa được.

Cách chạy theo README: sinh tệp biến môi trường bằng `scripts/gen-env.sh`, bật `docker compose --profile core up -d`, thêm các profile `events`, `observability` khi cần, rồi kiểm tra bằng `scripts/health.sh`. Dữ liệu lưu qua bind mount theo biến `DATA_DIR`. Để không chiếm tài nguyên máy dùng chung, log container giới hạn 3 tệp 20 MB, Kafka giữ dữ liệu 72 giờ, Valkey giới hạn 512 MB.

APISIX ở dev có hai tuyến: `/api/v1/*` chuyển tới mission-core chạy trên máy phát triển, gắn `X-Request-Id` và giới hạn 600 request mỗi 60 giây theo địa chỉ nguồn, trả 429 khi vượt; `/*` chuyển tới container web. Ở dev, ứng dụng web mặc định chạy với `AUTH_ENABLED=false` và `USE_MOCK_API=true`, nên giao diện dùng được trước khi có backend.

## Đóng gói ứng dụng web

`deploy/web/Dockerfile` build hai bước. Bước một dùng `node:22-alpine` chạy `npx ng build fe-task && npx ng build fe-shell`. Bước hai dùng `nginxinc/nginx-unprivileged:1.27-alpine`, chép shell vào thư mục gốc web và `fe-task` vào `remotes/fe-task`, chạy với `USER 101` và cổng 8080. Một ảnh duy nhất phục vụ shell lẫn remote là cách làm cho giai đoạn đầu; khi có nhiều remote với nhịp phát hành riêng, mỗi remote có thể có ảnh riêng (suy luận, nguồn chưa chốt).

Cấu hình chạy không được nhúng vào bản build. Khi container khởi động, `40-runtime-config.sh` sinh `config.json` và `federation.manifest.json` từ biến môi trường `CELL`, `TENANT`, `API_BASE_URL`, `UI_PACK_BASE_URL`, `AUTH_ENABLED`, `AUTH_ISSUER`, `AUTH_CLIENT_ID`, `USE_MOCK_API`, `FE_TASK_URL`. Nhờ vậy cùng một ảnh đã ký chạy được ở mọi cell và môi trường, đúng yêu cầu ảnh tham chiếu theo digest (SEC-55).

`nginx.conf` đệm một năm với `immutable` cho tệp có băm trong tên, đệm 60 giây cho `index.html`, `federation.manifest.json`, `remoteEntry.json`, `config.json` và `current.json`, và chuyển mọi đường dẫn khác về `index.html` để định tuyến phía client hoạt động. Phần header bảo mật còn thiếu được phân tích ở [Bảo mật frontend](08-bao-mat-frontend.md) và thuộc task WEB-014.

## Khác biệt giữa dev và production

Production chạy trên Kubernetes 1.36 theo từng cell, với các thành phần tương ứng nhưng ở cấu hình chịu lỗi và bảo mật đầy đủ. Bảng dưới đối chiếu theo README của `infra/dev`.

| Thành phần | Dev | Production |
|---|---|---|
| PostgreSQL | Một container | CloudNativePG với PgBouncer |
| Valkey | Một container | Valkey Cluster |
| Kafka | Một broker KRaft | Nhiều broker |
| Lưu trữ đối tượng | MinIO | Ceph RGW với Object Lock chế độ COMPLIANCE |
| OpenSearch | Một node | Nhiều node, bật bảo mật |
| Mạng dịch vụ | Mạng Docker | Istio ambient với mTLS |
| Bí mật | Tệp `.env` | OpenBao với HSM |

MinIO có giấy phép AGPL và bị TL-01 loại khỏi production; dự án vẫn dùng ở dev theo quyết định đã chốt (xem [trang 14](14-quyet-dinh-va-cau-hoi-mo.md)). Tương tự, TL-01 chỉ cho phép các thành phần AGPL như Grafana, Loki, Tempo và k6 dùng nội bộ, không phân phối kèm sản phẩm.

## Triển khai lên Kubernetes

ADR-16 chọn Jenkins cho CI và Argo CD cho CD theo mô hình GitOps. OPS-01 mô tả kho GitOps gồm `clusters/`, `cells/<cell>/cell.yaml`, `apps/<service>/` và `envs/`; Argo CD dùng ApplicationSet dạng ma trận cell nhân service, đồng bộ cuốn chiếu (RollingSync) theo nhãn `wave`. Kyverno chỉ cho chạy ảnh có chữ ký cosign và tuân chính sách (không root, tham chiếu theo digest). Phát hành dùng canary bằng Argo Rollouts: 5% trong 15 phút, rồi 25%, 50% và 100%, có thể dừng và quay lui tự động theo chỉ số.

Thứ tự phát hành giữa các cell theo OPS-02 gồm bốn đợt: đợt 1 là CELL-G4 (cell bridge nhỏ nhất trong nhóm SaaS, đóng vai trò "chim hoàng yến"); đợt 2 là CELL-G1 đến G3 và CELL-SH; đợt 3 là CELL-HN và CELL-DTW; đợt 4 là CELL-HCM. Giữa các đợt có thời gian ổn định tối thiểu 24 giờ ngày làm việc, để lỗi chỉ lộ ra ở tải lớn có cơ hội được phát hiện ở cell nhỏ trước. TL-04 đề xuất thêm cell thí điểm nội bộ CELL-NB đứng trước CELL-G4 như đợt 0. Một bản web mới vì vậy có thể chạy ở các cell khác nhau với phiên bản khác nhau trong vài ngày, nên UI Pack và API phải tương thích ngược trong khoảng đó (suy luận). Không phát hành trong cửa sổ đóng băng trước các mốc hạn của kỳ đánh giá (OPS-06); lịch đóng băng của từng cell được một tác vụ trong kho GitOps sinh từ bảng `config.period` của MOD-08, cùng nguồn với các khung tăng năng lực trước của KEDA. Ngân sách lỗi quá 50% trước ngày 15 của tháng thì dừng phát hành tính năng (OPS-19). Bản LTS ra mỗi 6 tháng và được vá trong 18 tháng (OPS-12).

## Lộ trình theo TL-04

TL-04 chia lộ trình thành các đợt. Đợt 0a kéo dài 2 tuần, xử lý ngay các lỗ hổng S01 đến S05, S07, S08 và S11 của hệ cũ: gitleaks, xoay vòng bí mật, OpenBao tối thiểu, sửa CORS và cookie, bỏ token khỏi URL và log, rồi kiểm tra lại. Đợt 0b kéo dài 6 tuần: chart `vt-service`, đặt APISIX trước hệ cũ để sinh `traceparent`, dựng OTel, Tempo, Prometheus, Fluent Bit và cụm quản trị Argo CD, Harbor, Grafana, với mục tiêu có trace xuyên bốn tầng. Đợt 0c kéo dài 6 tuần: Resilience4j, cổng chất lượng Jenkins, Kyverno chế độ Enforce, Flyway và thử tải 500 người dùng đồng thời với p95 dưới 3 giây.

Đợt 1 (quý 4 năm 2026 đến quý 1 năm 2027) dựng CELL-G4 và vùng điều phối toàn cục, HSM, OpenBao, Keycloak có MFA, `state` và `nonce`, OPA, mạng, SIEM, sao lưu, với mục tiêu khởi tạo tenant trong một ngày. Đợt 2 (quý 1 đến quý 2 năm 2027) dựng 6 cụm PostgreSQL, Kafka với Debezium và MirrorMaker 2, Ceph Object Lock, sổ cái, pipeline Policy Pack, HPA và KEDA, chạy đủ PERF-01 đến PERF-25 và canary theo cell. Đợt 3 (từ quý 3 năm 2027) mở 8 cell SaaS, dự trữ 2.000 vCPU, GeoDNS, khôi phục thảm hoạ, ứng dụng di động, AI, và thay toàn bộ giao diện ZK của hệ cũ bằng Angular. Các giai đoạn GĐ0 đến GĐ4 trong `docs/tasks/README.md` được ghép với các đợt này (GĐ1 ứng với đợt 1, GĐ2 ứng với đợt 2).

## Liên quan

- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
