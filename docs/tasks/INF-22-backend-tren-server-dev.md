# INF-22 · SVC-05, YC-HT-01, YC-PT-08 Triển khai backend lên server dev bằng Docker Compose (profile backend, cổng 35xxx) để kiểm thử tích hợp thật

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-22` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | CORE-00-01, NEN-020 |
| Truy vết | YC-HT-01, YC-PT-08 → SVC-05 mission-core và các service đã có khung → API `/api/v1/*` qua APISIX dev → schema theo module trong DB `mission` → topic EVT trên Kafka dev |
| Trạng thái | Chưa bắt đầu (cần người dùng bật Remote Control trên máy Mac để thao tác server dev) |

## 1. Mục tiêu

Đưa mission-core và các service backend đã có khung lên server dev dùng chung bằng Docker Compose, trong project `mission-dev`, cổng dải 35xxx, sau APISIX dev, để frontend và bộ kiểm thử đầu cuối gọi được API thật thay cho dữ liệu giả lập. Khi xong, lời gọi `/api/v1/...` tới APISIX dev (cổng 35000, địa chỉ máy chủ lấy từ `.env`, không ghi vào repo) trả dữ liệu từ mission-core thật, đăng nhập bằng realm `mission` của Keycloak dev, và sự kiện outbox đi tới Kafka dev.

## 2. Đầu vào bắt buộc đọc

- `infra/dev/README.md` (toàn bộ, đặc biệt mục "Ghi chú vận hành trên server dev": cổng 8081 bận, mission-core nghe 35100; CentOS 7; compose v2.17), `infra/dev/docker-compose.yml`, `infra/dev/apisix/apisix.yaml`.
- `AGENTS.md` mục 7, `docs/quy-dinh/HA-TANG.md` mục 3, `docs/quy-dinh/BACKEND.md` mục 5.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Môi trường phi sản xuất" (526–541) để định vị dev trong năm loại môi trường.
- `contracts/openapi/README.md` (tiền tố `/api/v1`, mỗi module một tệp), kết quả của NEN-020 (profile dev backend) và CORE-00-01 (khung mission-core).

## 3. Phạm vi công việc

- Thêm profile `backend` vào `infra/dev/docker-compose.yml`: dịch vụ `mission-core` (ảnh dựng từ `backend/` bằng Jib hoặc Dockerfile dev), nghe cổng 35100 trên máy, nối mạng `mission-dev`, biến môi trường đọc từ `.env` (không giá trị thật trong repo), healthcheck `/actuator/health/readiness` trên cổng quản trị.
- Thêm vào cùng profile các service đã có khung tại thời điểm làm (ví dụ `tenant-registry`, `org-service`, `workflow-service`), mỗi service một cổng 351xx ghi trong bảng README; service nào chưa có khung thì ghi "chưa có" trong báo cáo, không tạo giả.
- Sửa `infra/dev/apisix/apisix.yaml`: định tuyến theo tiền tố module (`/api/v1/registry`, `/api/v1/org`, `/api/v1/workflow`…) tới đúng upstream thay cho một upstream chung, bật `openid-connect` kiểm JWT của realm `mission` cho `/api/v1/*`, giữ `request-id` và `limit-count`.
- Viết script `infra/dev/scripts/deploy-backend.sh`: kéo hoặc dựng ảnh theo digest, `docker compose --profile backend up -d`, chạy migration Flyway bằng tài khoản `mig_<schema>`, gọi `health.sh`; chỉ thao tác container có nhãn project `mission-dev`.
- Cập nhật `infra/dev/scripts/health.sh` kiểm thêm profile `backend`.
- Thực hiện triển khai thật lên server dev qua Remote Control trên máy của người dùng; không dừng, xoá hay sửa container ngoài project `mission-dev` (AGENTS.md mục 7).
- Viết kiểm thử khói `infra/dev/scripts/smoke-backend.sh`: lấy token của tài khoản thử bằng client `mission-web` (mật khẩu đọc từ `.env`), gọi `GET /api/v1/tasks/mine` và `GET /api/v1/periods` qua APISIX, kiểm mã 200 và header `ETag`, kiểm một sự kiện xuất hiện trên topic sau một thao tác ghi.

## 4. Ngoài phạm vi

- Triển khai Kubernetes (INF-08 trở đi); bộ kiểm thử đầu cuối đầy đủ (QA-03).
- Chuyển các remote web sang API thật (WEB-070..WEB-072).

## 5. Tệp được phép sửa

- `infra/dev/docker-compose.yml`, `infra/dev/apisix/**`, `infra/dev/scripts/**`, `infra/dev/README.md`, `infra/dev/.env.example`.
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `docker compose --profile backend config` hợp lệ trên máy cục bộ; `shellcheck` sạch cho script mới.
- Trên server dev (qua Remote Control): `health.sh` báo xanh cho profile `backend`; `smoke-backend.sh` đạt cả ba kiểm tra; ghi kết quả (không ghi địa chỉ, tài khoản) vào báo cáo.
- Request không token tới `/api/v1/*` qua APISIX bị 401; request có token hợp lệ đi đúng upstream theo tiền tố.
- Không có container nào ngoài project `mission-dev` bị thay đổi (đối chiếu `docker ps` trước và sau, chỉ ghi số lượng).
- Báo cáo cuối task theo AGENTS.md mục 6; README và wiki 12 cập nhật bảng cổng mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
