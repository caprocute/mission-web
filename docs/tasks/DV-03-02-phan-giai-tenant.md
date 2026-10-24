# DV-03-02 · QT-00/SVC-03 API phân giải tenant theo tên miền, danh sách cell, bộ đệm

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-03-01 |
| Truy vết | QT-00 bước 2 → SCR-0001 → `GET /api/v1/registry/resolve` (`resolveTenantCell`), `GET /api/v1/registry/cells` (`listCells`) → SVC-03 → bảng `registry.tenant_domain`, `registry.tenant_cell`, `registry.tenant_idp`, `registry.cell`; MSG-00-07; NFR-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Trước khi đăng nhập, shell và APISIX lớp ngoài cần biết người dùng thuộc tenant nào, tenant ở cell nào, tenant bật những phương thức đăng nhập nào và tenant có đang tạm ngừng không. Task này hiện thực hai API phân giải và liệt kê cell của hợp đồng, có bộ đệm, cookie định tuyến ký HMAC và mã 421 khi tenant đã chuyển cell. Khi xong, phân giải trúng đệm đạt p95 ≤ 50 ms và trang đăng nhập có đủ thông tin công khai để hiển thị.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 bước 2 (dòng 325) và trang đăng nhập SCR-0001 (dòng 379, nội dung công khai dòng 324, 393), ngoại lệ tenant `TAM_NGUNG` (dòng 373, MSG-00-07).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-03 tenant-registry" (dòng 1007–1026) và "Định tuyến tới cell" (dòng 2718).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `registry.tenant_domain` (dòng 991–1001, đệm 30 s ở dòng 1029).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` thao tác `resolveTenantCell` (tham số `tenant`/`host`, cookie, 421 `REGISTRY-421-TENANT-MOVED`, 503 `Retry-After: 30`), `listCells`.
- Wiki: `wiki/06-quy-uoc-api.md`, `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `resolveTenantCell`: bắt buộc đúng một trong `tenant` hoặc `host` (422 nếu sai); tra `tenant_domain` hoặc `tenant.code`, lấy bản ghi `tenant_cell` đang hiệu lực, cell, phương thức đăng nhập từ `tenant_idp` và trạng thái tenant; trả đúng lược đồ hợp đồng, header `Cache-Control: private, max-age=300`.
- Đặt cookie định tuyến ký HMAC hạn 5 phút; khoá HMAC đọc từ OpenBao qua biến môi trường do External Secrets cấp; thêm hàm kiểm cookie dùng chung cho DV-01-04.
- Trả 421 kèm `Location` tới `apiBaseUrl` của cell mới khi request đến từ cell cũ hoặc tenant đang `DANG_CHUYEN_CELL` đã cắt chuyển; trả thông tin trạng thái `TAM_NGUNG` để giao diện hiện MSG-00-07.
- Bộ đệm trong tiến trình có hạn (mặc định 30 giây theo TL-03) và vô hiệu khi tenant, tên miền hoặc gán cell đổi; khi CSDL không truy cập được, phục vụ từ đệm còn hạn, không có thì 503 + `Retry-After: 30`.
- Hiện thực `listCells` cho `platform_admin` (kiểm vai trò bằng token), có phân trang con trỏ, trả tải hiện tại (`account_count` / `capacity_accounts`).
- Kiểm thử: đơn vị cho xác định tenant theo host và mã; Testcontainers PostgreSQL cho 421, 503 khi mất CSDL có và không có đệm; kiểm thử hợp đồng so phản hồi với `platform.yaml`; benchmark Go cho đường trúng đệm.
- Điểm lệch cần lưu ý: tiền tố `/registry` không có trong danh sách baseline mục 13 nhưng có trong hợp đồng; theo API-first giữ đường dẫn hợp đồng.

## 4. Ngoài phạm vi

- APISIX lớp ngoài gọi API này (DV-01-04); xử lý 421 ở `fe-shell` (task WEB).
- Định tuyến tới đúng CSDL trong cell (NEN-036).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**`
- `deploy/charts/tenant-registry/**` (tham chiếu bí mật khoá HMAC)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go test ./... && govulncheck ./...` chạy xanh; kiểm thử hợp đồng với `contracts/openapi/modules/platform.yaml` đạt.
- Benchmark đường trúng đệm cho p95 ≤ 50 ms (QT-00 bước 2) trên máy kiểm thử; ghi số đo vào báo cáo.
- Cookie bị sửa hoặc hết hạn bị hàm kiểm từ chối.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
