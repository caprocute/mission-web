# DV-03-07 · ADR-29 Chuyển tenant giữa các cell bằng sao chép logic, hai người duyệt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-03-05; DV-01-04 (lớp ngoài xử lý 421), INF-12 (Ceph multisite) cho kiểm thử trên cụm (suy luận) |
| Truy vết | ADR-29, ADR-07 → `POST /api/v1/registry/tenants/{id}/move` (`requestTenantMove`), `POST /api/v1/registry/tenants/{id}/move/{moveId}/approve` (`approveTenantMove`) → SVC-03, SVC-09 → bảng `registry.tenant`, `registry.tenant_cell`, `ledger.ledger_entry` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi một tenant lớn lên vượt ngưỡng của cell (1,5 triệu tài khoản hoặc 75.000 người dùng đồng thời theo ADR-29), quản trị nền tảng chuyển tenant sang cell khác mà không mất dữ liệu và chỉ khoá ghi vài phút. Khi xong, đề nghị chuyển phải có người thứ hai duyệt; dữ liệu PostgreSQL và tệp minh chứng được sao chép, so khớp; danh bạ toàn cục chuyển sang cell đích; client cũ nhận 421 và tự chuyển; sổ cái nối tiếp liền mạch.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Chuyển tenant giữa các cell" (dòng 2738–2740) và ADR-29 "Ngưỡng kích thước cell và chuyển tenant bằng sao chép logic" (dòng 3672).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `registry.tenant_cell` (dòng 976–989), mục "Sổ cái bất biến" (dòng 4088) và bucket Ceph (mục "Ceph", dòng 4507–4537).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` `requestTenantMove`, `approveTenantMove`, `resolveTenantCell` (421).
- Wiki: `wiki/01-tong-quan-he-thong.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/15-phan-quyen.md` (bốn mắt), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `requestTenantMove` (Idempotency-Key, `If-Match`) tạo đề nghị `CHO_DUYET` và `approveTenantMove` bởi một `platform_admin` khác người đề nghị (bốn mắt, AAL2); chỉ khi duyệt mới bắt đầu chạy, tenant sang `DANG_CHUYEN_CELL`.
- Bước sao chép: tạo publication có `WHERE (tenant_id = '<id>')` trên cụm nguồn (PostgreSQL ≥ 15) và subscription trên cụm đích cho mọi schema của tenant; đồng bộ bucket Ceph RGW của tenant.
- Bước cắt chuyển: khi độ trễ sao chép < 1 giây, chuyển tenant chỉ đọc (APISIX trả 503 + `Retry-After`), so khớp số dòng và tổng băm theo bảng, cập nhật `registry.tenant_cell` (đóng bản ghi cũ, mở bản ghi mới, ràng buộc không chồng lấn bảo đảm), mở ghi ở cell đích.
- Sổ cái: cell đích tiếp tục chuỗi từ `entry_hash` cuối của nguồn, ghi bản ghi chuyển giao niêm phong ở cả hai cell (phối hợp API của ledger-service).
- Giữ dữ liệu nguồn 30 ngày rồi mới dọn; có lệnh huỷ trước cắt chuyển để quay lại trạng thái cũ.
- Kiểm thử trên hai cụm PostgreSQL Testcontainers: sao chép một tenant pool có dữ liệu mẫu, so khớp đạt, tenant khác trong cùng cụm không bị ảnh hưởng; người đề nghị tự duyệt bị chặn; client gọi cell cũ sau cắt chuyển nhận 421.
- Điểm lệch cần lưu ý: TL-03 chưa có bảng lưu đề nghị chuyển cell dù hợp đồng có `moveId`; không tự tạo bảng ngoài thiết kế, ghi đề xuất bảng bổ sung "(suy luận)" vào wiki trang 14 để người phụ trách duyệt trước khi viết migration.
- Gợi ý tách: (a) đề nghị, duyệt bốn mắt, trạng thái; (b) sao chép, so khớp, cắt chuyển CSDL; (c) đồng bộ Ceph và nối sổ cái; mục tiêu thời gian chỉ đọc 5–15 phút cho 100.000 tài khoản (ADR-29).

## 4. Ngoài phạm vi

- Thủ tục vận hành chi tiết và diễn tập (runbook VH, QA-15).
- Dời tenant giữa hai trung tâm dữ liệu khi sự cố (DR-10).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**`
- `deploy/charts/tenant-registry/**`
- `ops/runbooks/` (thêm runbook chuyển tenant giữa cell, có bước kiểm chứng và quay lui)
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go test ./... && govulncheck ./...` chạy xanh với kiểm thử hai cụm.
- Kịch bản kiểm thử ghi lại thời gian chỉ đọc và kết quả so khớp số dòng, tổng băm; không mất dòng nào.
- Runbook có bước kiểm chứng và bước quay lui bằng tiếng Việt.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
