# NEN-036 · ADR-07 Định tuyến tới đúng CSDL theo mô hình silo, bridge, pool (`registry.tenant_cell`, mã 421)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-036` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (mốc M0 – Nền móng; cần DV-03-02 của mốc M1) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-033, DV-03-02 (bổ sung nên có: NEN-012 để nhận `registry.tenant.changed.v1`) |
| Truy vết | ADR-07, ADR-29 → TL-03 "Định tuyến tới đúng CSDL", "Bố trí theo mô hình silo/bridge/pool" → bảng `registry.tenant_cell` (đọc bản sao) → `GET /api/v1/registry/resolve` (`resolveTenantCell`), topic `registry.tenant.changed.v1` → phản hồi 421 `MisdirectedRequest` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mỗi service có CSDL theo tenant phải biết một yêu cầu thuộc CSDL nào: `td_<mã>` riêng (silo, bridge) hay `td_pool` chung (pool), và phải từ chối yêu cầu của tenant không thuộc cell hiện tại bằng 421 kèm tên miền đúng thay vì ghi nhầm. Task này đưa cơ chế đó vào thư viện nền: bảng định tuyến trong bộ nhớ nạp từ danh bạ tenant, làm mới theo sự kiện, và `DataSource` định tuyến theo tenant của yêu cầu. Khi xong, một request không bao giờ ghi vào CSDL của tenant hay cell khác, kể cả trong lúc chuyển tenant giữa cell.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Định tuyến tới đúng CSDL" (L756–758), "Bố trí theo mô hình silo" (L645–657), "bridge" (L659–663: một CSDL mỗi tỉnh, pool ứng dụng 2–8 kết nối mỗi tenant mỗi pod), "pool" (L665–682), "Schema registry" (L929–1044, bảng `tenant_cell`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Định tuyến tới cell" (L2718–2720), "Chuyển tenant giữa các cell" (L2738–2740), mục SVC-03 (L1007–1026).
- `contracts/openapi/modules/platform.yaml` (`resolveTenantCell`, `listCells`), `contracts/openapi/common/common.yaml` (`MisdirectedRequest`); kết quả NEN-010, NEN-033, DV-03-02.

## 3. Phạm vi công việc

- Gói `routing` trong `vt-platform-starter`: `TenantDirectory` giữ bản đọc trong bộ nhớ của `registry.tenant_cell` cho cell hiện tại (tenant → cell, mô hình thuê bao, tên CSDL, tên miền đúng), nạp lúc khởi động qua API của tenant-registry bằng tài khoản dịch vụ (AC-11, client credentials) và làm mới theo `registry.tenant.changed.v1`; có thời hạn đệm tối đa theo TL-01 (5 phút) khi không nhận được sự kiện.
- `TenantRoutingDataSource` (dựa `AbstractRoutingDataSource`): pool Hikari riêng cho mỗi `td_<mã>` (bridge, silo) với kích thước 2–8 kết nối mỗi tenant mỗi pod theo TL-03, một pool cho `td_pool`; tạo pool lười khi tenant đầu tiên xuất hiện, đóng pool tenant không dùng sau thời gian chờ (suy luận); không có tenant thì không cấp kết nối.
- Bộ lọc HTTP chạy sau `TenantContextFilter`: tenant không thuộc cell hiện tại → 421 Problem Details (lược đồ `MisdirectedRequest`) kèm tên miền đúng; tenant đang ở trạng thái chuyển chỉ đọc → 503 kèm `Retry-After` cho thao tác ghi (TL-01 "Chuyển tenant giữa các cell", suy luận về cách nhận biết trạng thái từ danh bạ).
- Tương đương cho Go: bản đồ pool `pgx` theo tenant trong `services-go/vtplatform/db` nếu NEN-017 đã gộp; nếu chưa thì ghi việc còn lại vào báo cáo.
- Kiểm thử Testcontainers: một cụm PostgreSQL với `td_hcm_a`, `td_hcm_b`, `td_pool`; danh bạ giả lập (WireMock hoặc stub theo hợp đồng `resolveTenantCell`); yêu cầu của tenant bridge vào đúng CSDL riêng, tenant pool vào `td_pool` với RLS; đổi cell qua sự kiện thì yêu cầu kế tiếp nhận 421; tenant lạ nhận 421 chứ không 500.
- Điểm lệch cần lưu ý: TL-03 lấy tenant từ claim `tid`, TL-01 dùng `tenant_id`; dùng cùng cấu hình claim của NEN-010. Mô hình thuê bao ở `registry.tenant_cell.tenancy_model` viết thường và thiếu `AIRGAP` so với `registry.tenant` (TL-03 câu hỏi mở); task đọc không phân biệt hoa thường và ghi vào báo cáo.

## 4. Ngoài phạm vi

- API và schema `registry` của tenant-registry: DV-03-01, DV-03-02; chuyển tenant giữa cell: DV-03-07.
- Định tuyến cell ở APISIX và cookie định tuyến: nhóm DV-01; bản sao đọc (`readOnly`) và định tuyến replica: CORE-00-01.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `routing`, kiểm thử)
- `services-go/vtplatform/db/**` (chỉ khi NEN-017 đã gộp)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh với các kịch bản ở mục 3; nếu có phần Go thì `go test ./...` xanh.
- Đo: chi phí tra bảng định tuyến mỗi yêu cầu dưới 10 µs (bản đồ trong bộ nhớ), báo cáo số đo.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 17 (nếu đã có) mục "Định tuyến CSDL".

## 7. Báo cáo

Theo AGENTS.md mục 6.
