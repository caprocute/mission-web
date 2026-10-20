# DV-08-01 · SVC-08 Khung evidence-service WebFlux, migration schema `evidence`, client S3 cho Ceph RGW

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-08-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | NEN-019, NEN-033 |
| Truy vết | SVC-08 → schema `evidence` (`evidence.object_meta`, `evidence.object_version`, `evidence.dedup_index`, `evidence.scan_result`, `evidence.upload_session`) → Ceph RGW bucket theo tenant; ADR-13 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng microservice evidence-service trên Spring Boot 3.5 WebFlux (Java 21) theo khung chung của nền móng: migration Flyway cho schema `evidence` có RLS theo tenant, outbox, client S3 (AWS SDK for Java v2) trỏ tới Ceph RGW với bucket bật Object Lock, chart Helm và health check. Khi xong, service khởi động được trong môi trường dev, kết nối CSDL dữ liệu tenant và RGW, và sẵn sàng cho các task tải lên, khử trùng, quét, OCR.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-08 evidence-service" (dòng 1086–1106).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "SVC-08 evidence-service" (dòng 512–520), "Schema evidence (SVC-08)" (dòng 3391–3471) và chương "Kho đối tượng Ceph", mục "Tổ chức bucket" (dòng 4507).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Định cỡ kho đối tượng Ceph" (dòng 812) và "Ceph: multisite RGW và Object Lock" (dòng 2176).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` ADR-13.
- Hợp đồng: `contracts/openapi/modules/products.yaml` nhóm thẻ "Tệp" (`/files/**`, `x-mission.owner: SVC-08`).
- Quy định: `docs/quy-dinh/BACKEND.md` (API-first, outbox, RLS), `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Tạo mô-đun `backend/services/evidence-service` dùng thư viện nền (NEN-019 khung microservice, NEN-033 outbox/RLS), WebFlux và R2DBC hoặc JDBC trên luồng riêng theo quy ước của BACKEND.md.
- Migration Flyway tạo năm bảng `object_meta`, `object_version`, `dedup_index` (PK `tenant_id`, `sha256`), `scan_result` (CHECK `verdict`), `upload_session` (CHECK `status` IN `MO`, `HOAN_TAT`, `HUY`; chỉ mục `expires_at`) đúng từ điển TL-03; bật RLS theo `tenant_id`; tài khoản CSDL riêng của service.
- Bảng outbox của schema `evidence` và cấu hình Debezium theo khung chung.
- Client S3 tới Ceph RGW: tạo bucket theo tenant khi cấp phát (gọi từ saga DV-03-04, giao diện nội bộ), bật Object Lock chế độ compliance với thời hạn giữ theo tham số, khoá đối tượng theo địa chỉ nội dung `t/<tenant>/sha256/<2 ký tự đầu>/<băm>`.
- Sinh giao diện máy chủ từ `products.yaml` (chỉ đường dẫn `/files/**`), các handler trả 501 cho tới khi task sau hiện thực.
- Chart `deploy/charts/evidence-service` (HPA theo số kết nối, NetworkPolicy, ảnh distroless không root – SEC-49).
- Điểm lệch cần lưu ý: tên bucket là `evidence-<tenant>`/`evidence-pool` ở TL-01 nhưng `ev-<cell>-<tenant>` ở TL-03; chọn theo TL-03 (mô tả kho chi tiết hơn) và ghi vào wiki trang 14.

## 4. Ngoài phạm vi

- Logic tải lên, khử trùng, quét, OCR (DV-08-02..DV-08-05); `getFilePreview` (chưa có hàng việc).

## 5. Tệp được phép sửa

- `backend/pom.xml` (thêm mô-đun)
- `backend/services/evidence-service/**`
- `deploy/charts/evidence-service/**`
- `infra/dev/` (chỉ thêm bucket hoặc biến cấu hình RGW cho dev, không ghi bí mật)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/evidence-service -am verify` chạy xanh, gồm Testcontainers PostgreSQL (migration, RLS chặn đọc chéo tenant) và MinIO hoặc RGW giả lập (ghi đối tượng có Object Lock, xoá bị từ chối).
- ArchUnit: không truy cập schema khác; `helm lint deploy/charts/evidence-service` và `helm template ... | kubeconform -strict` sạch.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
