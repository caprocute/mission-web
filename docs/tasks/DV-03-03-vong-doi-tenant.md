# DV-03-03 · QT-01/SCR-0101, SCR-0102 Tenant: máy trạng thái, API nháp, gợi ý mô hình thuê bao và chọn cell theo tải

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-03-01 |
| Truy vết | QT-01 → SCR-0101, SCR-0102 → `GET/POST /api/v1/config/tenants` (`listConfigTenants`, `createConfigTenant`), `GET/PATCH/DELETE /api/v1/config/tenants/{id}` (`getConfigTenant`, `updateConfigTenant`, `cancelConfigTenant`), `POST /api/v1/config/tenants/export` (`exportConfigTenants`), `POST /api/v1/registry/tenants` (`createRegistryTenant`), `GET /api/v1/registry/tenants/{id}` (`getRegistryTenant`) → SVC-03 → bảng `registry.tenant`, `registry.tenant_status_log`, `registry.cell`; BR-01-01, BR-01-02; MSG-01-01, MSG-01-03, MSG-01-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản trị nền tảng khai báo tenant mới, lưu nháp từng bước wizard, nhận gợi ý mô hình thuê bao và cell phù hợp, và hệ thống giữ vòng đời tenant đúng máy trạng thái có lưu vết. Khi xong, danh sách tenant SCR-0101 và các bước 1–3 của SCR-0102 chạy được với dữ liệu thật; mọi chuyển trạng thái sai thứ tự bị chặn; không thể đặt tenant vào cell sẽ vượt 85% tải.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 (dòng 476–746): bước 1–3 (dòng 505–512), trạng thái tenant (dòng 521–539), BR-01-01, BR-01-02 (dòng 541–556), SCR-0101 (dòng 572), SCR-0102 (dòng 610), bảng MSG (dòng 607–654).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Đa khách thuê" (dòng 2653) và ADR-07 (dòng 3452).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `registry.tenant`, `registry.cell`, `registry.tenant_status_log` (dòng 941–1027).
- Hợp đồng: `contracts/openapi/modules/config.yaml` các thao tác `/config/tenants*` nêu ở bảng; `contracts/openapi/modules/platform.yaml` `createRegistryTenant`, `getRegistryTenant`; `contracts/openapi/modules/jobs.yaml` (`getJob`, cho xuất 202).
- Wiki: `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0101, SCR-0102), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực máy trạng thái tenant theo TL-02a: `KHOI_TAO` → `DANG_CAP_PHAT` → `DANG_CAU_HINH` (hoặc về `KHOI_TAO` khi saga bù trừ) → `SAN_SANG` → `DANG_VAN_HANH` ↔ `TAM_NGUNG` → `DA_CHAM_DUT`, cộng `DANG_CHUYEN_CELL` của TL-03; mọi chuyển ghi `registry.tenant_status_log` cùng giao dịch và phát sự kiện qua outbox.
- Hiện thực `createConfigTenant`, `updateConfigTenant` (JSON Merge Patch, `If-Match`, 409 khi lệch phiên bản; bước 1–3 do `platform_admin`, bước 4–5 do `tenant_admin` của tenant), `cancelConfigTenant` (chỉ khi `KHOI_TAO`), `getConfigTenant` (bước wizard hiện tại), `listConfigTenants` (lọc `cell`, `model`, `status`, phân trang con trỏ ≤ 200), `exportConfigTenants` (202 + `/jobs/{id}`), và `createRegistryTenant`, `getRegistryTenant`.
- Gợi ý mô hình thuê bao theo số tài khoản dự kiến và khối: bắt buộc silo khi > 1.000.000 tài khoản hoặc khối Đảng Trung ương, lực lượng vũ trang (BR-01-01, chặn).
- Chọn cell: tính tải = `account_count` / `capacity_accounts`, chỉ gợi ý cell cùng mô hình còn dưới 85% sau khi thêm; không có cell phù hợp trả MSG-01-03; chặn lưu khi chọn cell vượt 85% (BR-01-02); cảnh báo cell vượt 85% (MSG-01-01).
- Kiểm tên tenant và tên miền duy nhất (MSG-01-04), sinh `onboarding_code` dạng `ONB-yyyy-nnnn`, `kek_ref` bắt buộc với silo; ghi nhận `Idempotency-Key` 48 giờ cho lệnh ghi.
- Tác vụ đồng bộ `account_count` mỗi giờ từ các cell (đọc chỉ số do cell báo về; giao thức báo cáo ghi "(suy luận)").
- Kiểm thử: bảng chuyển trạng thái đầy đủ (hợp lệ và không hợp lệ); BR-01-01, BR-01-02 biên 85%; 409 khi `If-Match` lệch; kiểm thử hợp đồng với `config.yaml` và `platform.yaml`.
- Điểm lệch cần lưu ý: hợp đồng đặt `/config/tenants*` dưới `x-mission.owner: MOD-08`, nhưng dữ liệu tenant thuộc schema `registry` của SVC-03 toàn cục (TL-02a QT-01, TL-03). Chọn hiện thực các thao tác này trong tenant-registry đúng lược đồ hợp đồng và để cổng toàn cục định tuyến tiền tố `/config/tenants` tới SVC-03; không sửa hợp đồng trong task này, ghi đề nghị sửa `x-mission.owner` vào wiki trang 14 và báo cáo.
- Điểm lệch cần lưu ý: ngưỡng bridge là 5.000–1.000.000 tài khoản ở TL-02a nhưng 5.000–150.000 ở TL-01 chương "Đa khách thuê"; đặt ngưỡng là tham số, mặc định theo TL-02a (đặc tả nghiệp vụ của màn hình), ghi vào wiki trang 14.
- Gợi ý tách nếu quá lớn: (a) máy trạng thái + nhật ký + API nháp; (b) gợi ý mô hình, chọn cell, đồng bộ `account_count`, xuất 202.

## 4. Ngoài phạm vi

- Saga cấp phát và kích hoạt (DV-03-04, DV-03-05); kiểm IdP (DV-03-06); chuyển cell (DV-03-07).
- Màn hình SCR-0101, SCR-0102 (WEB-016, WEB-017).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**`
- `deploy/charts/tenant-registry/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md` (chỉ ghi chú hiện thực)

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go test ./... && govulncheck ./...` chạy xanh; kiểm thử hợp đồng với hai tệp hợp đồng đạt; độ phủ dòng gói nghiệp vụ ≥ 80%.
- Mọi phản hồi lỗi nghiệp vụ là Problem Details có `type` trỏ BR hoặc MSG tương ứng.
- Không có thao tác ghi nào bỏ qua `tenant_status_log`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
