# DV-03-01 · SVC-03 Khung service Go tenant-registry và migration schema `registry`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | NEN-017 (thư viện Go dùng chung); NEN-030, NEN-031 (quy ước Flyway, vai trò CSDL) nên có trước (suy luận) |
| Truy vết | QT-01 → SVC-03 tenant-registry (toàn cục) → bảng `registry.tenant`, `registry.cell`, `registry.tenant_cell`, `registry.tenant_domain`, `registry.tenant_idp`, `registry.tenant_status_log`, `registry.tenant_quota`; ADR-07; NFR-04 (99,95% vùng toàn cục) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có dịch vụ `tenant-registry` viết bằng Go 1.23 chạy được, dựng đúng lược đồ `registry` của TL-03, có probe, đo lường, lỗi RFC 9457 và kiểm thử với PostgreSQL thật. Khi xong, các task DV-03 sau chỉ việc thêm API và nghiệp vụ; danh bạ tenant toàn cục có ràng buộc không chồng lấn thời gian gán cell ngay ở CSDL.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-03 tenant-registry" (dòng 1007–1026).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "SVC-03 tenant-registry (toàn cục)" (dòng 327–338) và "Schema registry (toàn cục, SVC-03)" (dòng 929–1044), cùng các mục quy ước "Quy ước đặt tên" (dòng 44), "Kiểu dữ liệu chuẩn" (dòng 74), "Bảng outbox_event" (dòng 768).
- Baseline `00-co-so-thiet-ke-chung.md` mục 5 (SVC-03), 6 (cell), 9 (schema `registry`).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` tag Registry (để biết mô hình dữ liệu trả ra), `contracts/openapi/common/common.yaml` (Problem Details).
- Wiki: `wiki/01-tong-quan-he-thong.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Dựng mô-đun Go `services-go/tenant-registry` dùng `net/http`, `pgx` 5, thư viện NEN-017 (lỗi RFC 9457, OpenTelemetry, outbox), cấu hình bằng biến môi trường, probe `/healthz` và `/readyz`, nhật ký JSON có `traceparent`.
- Viết migration Flyway V1: bật `btree_gist`; tạo `registry.tenant` (CHECK `code`, `sector`, `tenancy_model`, `status`, `flows`), `registry.cell`, `registry.tenant_cell` với `ex_tenant_cell__no_overlap`, `registry.tenant_domain` (UQ `domain`), `registry.tenant_idp` (UQ `(tenant_id, idp_alias)`), đúng tên ràng buộc theo quy ước TL-03.
- Viết migration V2: `registry.tenant_status_log` (chỉ ghi thêm), `registry.tenant_quota`, bảng `outbox_event` của schema; vai trò `mig_registry`, `svc_registry`, `ro_registry` với quyền tối thiểu (svc không có DDL, không DELETE trên `tenant_status_log`).
- Viết seed chỉ dùng cho dev và kiểm thử: các cell `CELL-HCM`, `CELL-HN`, `CELL-G1`…`CELL-G4`, `CELL-SH`, `CELL-DTW` với mô hình thuê bao theo baseline mục 6 (suy luận: seed cho dev).
- Viết tầng truy cập dữ liệu (repository) cho tenant, cell, tenant_cell, tenant_domain, tenant_idp dùng câu lệnh có tham số, khoá lạc quan bằng `row_version` (0 dòng cập nhật → lỗi xung đột 409).
- Viết Dockerfile distroless không root và chart `deploy/charts/tenant-registry` (3 replica trải 3 vùng, `resources`, NetworkPolicy mặc định chặn).
- Kiểm thử Testcontainers PostgreSQL 17: migration chạy sạch từ đầu; hai bản ghi `tenant_cell` chồng lấn thời gian cho cùng tenant bị từ chối; `svc_registry` không chạy được DDL.
- Điểm lệch cần lưu ý: TL-03 ghi `registry.tenant.status` 8 giá trị (có `DANG_CHUYEN_CELL`) trong khi TL-02a đề xuất 7 trạng thái; `tenant_cell.tenancy_model` dùng chữ thường và thiếu `AIRGAP` trong khi `tenant.tenancy_model` chữ hoa. Giữ đúng DDL của TL-03 (thiết kế CSDL là nguồn cho migration), ghi hai điểm vào wiki trang 14.

## 4. Ngoài phạm vi

- API phân giải (DV-03-02); vòng đời tenant (DV-03-03); saga cấp phát (DV-03-04, DV-03-05).
- Cụm PostgreSQL toàn cục 3 node (task INF vùng toàn cục).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**` (mã, migration Flyway trong `db/migration/registry/`, kiểm thử, Dockerfile)
- `deploy/charts/tenant-registry/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go vet ./... && go test ./... && govulncheck ./...` chạy xanh, gồm kiểm thử Testcontainers PostgreSQL ở mục 3.
- `helm lint deploy/charts/tenant-registry` và `helm template | kubeconform -strict` không lỗi; container chạy không root.
- Lint quy ước tên và chú thích phân loại cột (NEN-030) qua trên migration mới.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
