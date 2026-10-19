# NEN-020 · Môi trường dev phía máy chủ: OPA, OpenBao, topic kỹ thuật, tài khoản CSDL `svc_/mig_/ro_/cdc_`, Testcontainers dùng chung

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-020` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-031 (bổ sung nên có: NEN-007 cho danh sách topic kỹ thuật, NEN-040 cho bundle OPA; khi chưa có thì dùng thư mục chính sách rỗng) |
| Truy vết | YC-HT-01 → TL-03 "Không truy cập chéo và quyền tối thiểu" → ADR-03, ADR-17, ADR-21 → `infra/dev` (Compose project `mission-dev`, dải cổng 35xxx) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện hạ tầng dev để mã máy chủ chạy và kiểm thử được với đúng mô hình thiết kế: bổ sung OPA và OpenBao (hiện chưa có), các topic kỹ thuật và topic thử lại/DLQ, schema `modulith`, `keyring`, và chuyển tài khoản CSDL dev từ "một role trùng tên schema" sang bốn vai trò `mig_/svc_/ro_/cdc_<schema>` như TL-03. Đồng thời tạo bộ cấu hình Testcontainers dùng chung để kiểm thử tích hợp của mọi mô-đun Java dựng cùng một hạ tầng giống dev. Kafka, Apicurio, Debezium Connect và Valkey đã có ở profile `events` và `core`, task này chỉ kiểm lại và bổ sung phần thiếu.

## 2. Đầu vào bắt buộc đọc

- `infra/dev/README.md`, `infra/dev/docker-compose.yml`, `infra/dev/postgres/init/01-schemas.sh`, `infra/dev/scripts/create-topics.sh`, `infra/dev/.env.example`, `infra/dev/keycloak/realm-mission.json`.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Không truy cập chéo và quyền tối thiểu" (L149–179, SQL mẫu), "Danh mục lược đồ EVT-01 đến EVT-26" đoạn topic kỹ thuật (L4470), bảng `modulith.event_publication`, `keyring.dek` (L894–927).
- Kết quả NEN-031 (mẫu tạo vai trò theo schema); `AGENTS.md` mục 7 và `docs/quy-dinh/BACKEND.md` mục 5.

## 3. Phạm vi công việc

- Thêm profile mới `security` (tên suy luận) gồm `opa` (OPA 1.x, nạp chính sách từ thư mục gắn vào, cổng 35181) và `openbao` (OpenBao 2.x, bật Transit và KV, cổng 35820, chế độ dev chỉ dùng cho dev), có healthcheck và giới hạn nhật ký như dịch vụ khác; không dùng cổng ngoài dải 35xxx.
- Viết lại `postgres/init/01-schemas.sh` theo mẫu NEN-031: mỗi schema có `mig_<schema>` (sở hữu), `svc_<schema>` (DML, `NOBYPASSRLS`), `ro_<schema>`, `cdc_<schema>` (REPLICATION); thêm schema `modulith`, `keyring` và các ngoại lệ quyền (`svc_org_replica` ghi `org_replica`, mọi `svc_<module>` của mission-core có DML trên `modulith.event_publication`, SELECT/INSERT trên `keyring.dek`); mật khẩu lấy từ biến môi trường, không có giá trị thật trong repo.
- Ghi trong `infra/dev/README.md` cách nâng một máy dev đã khởi tạo theo kiểu cũ (script chuyển đổi `postgres/migrate-roles.sh` chạy được nhiều lần), vì init chỉ chạy khi thư mục dữ liệu trống.
- Bổ sung `scripts/create-topics.sh`: topic kỹ thuật `sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`, `registry.tenant.changed.v1`, `org.bootstrap.v1`, `period.drain.marker`; topic `org.*` dùng `cleanup.policy=compact,delete`; ghi cách tạo `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>` (tham số hoá theo danh sách consumer).
- Thêm `infra/dev/openbao/init.sh` tạo khoá Transit mẫu `kek-<tenant>` cho các tenant thử và khoá HMAC chỉ mục mù, idempotent.
- Tạo mô-đun `backend/libs/vt-testkit/` cung cấp container Testcontainers dùng chung (PostgreSQL 17 chạy cùng script init, PgBouncer chế độ giao dịch, Kafka 3.9, Apicurio 3.x, Debezium Connect, Keycloak 26 nạp `realm-mission.json`, OPA, OpenBao, Valkey 8) với phiên bản ảnh khớp `docker-compose.yml`, và hướng dẫn bật `testcontainers.reuse.enable`.
- Cập nhật `infra/dev/.env.example` (chỉ tên biến mới, không giá trị thật) và bảng profile trong README.

## 4. Ngoài phạm vi

- Chạy thay đổi lên server dev dùng chung và profile chạy backend: INF-22 (task này chỉ sửa tệp và kiểm cục bộ).
- Định tuyến APISIX theo từng service: nhóm DV-01; production: INF-10, INF-11, INF-13.

## 5. Tệp được phép sửa

- `infra/dev/docker-compose.yml`, `infra/dev/README.md`, `infra/dev/.env.example`
- `infra/dev/postgres/**`, `infra/dev/scripts/create-topics.sh`, `infra/dev/openbao/**` (mới), `infra/dev/opa/**` (mới, chỉ README và thư mục chính sách rỗng)
- `backend/libs/vt-testkit/**`, `backend/libs/pom.xml` (chỉ thêm mô-đun)

## 6. Tiêu chí hoàn thành

- `docker compose -p mission-dev --profile core --profile events --profile security config` hợp lệ; nếu phiên có Docker thì `up -d` cục bộ và `infra/dev/scripts/health.sh` báo mọi dịch vụ sẵn sàng (ghi rõ nếu không có Docker).
- Truy vấn kiểm quyền sau init: `svc_task` không có DDL, không có USAGE trên schema khác, `rolbypassrls = false` cho mọi `svc_`/`ro_`; ghi câu truy vấn và kết quả vào báo cáo.
- `cd backend && ./mvnw -q -pl libs/vt-testkit -am verify` chạy xanh với một kiểm thử khởi động đủ container.
- Không có mật khẩu, địa chỉ máy chủ thật trong mọi tệp sửa; `.env` không bị commit.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật `wiki/12-ha-tang-dev-va-trien-khai.md` mục hạ tầng dev phía máy chủ.

## 7. Báo cáo

Theo AGENTS.md mục 6.
