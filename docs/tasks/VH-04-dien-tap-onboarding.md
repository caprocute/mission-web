# VH-04 · OPS-07 Diễn tập vòng đời tenant: đưa tenant mới vào vận hành trong một ngày

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | DV-03-05, INF-04 |
| Truy vết | NFR-07 → TL-04 mục 11.4 → OPS-07 → SVC-03 tenant-registry, SVC-02 Keycloak Organization, OpenBao Transit `kek-tenant-<id>`, CNPG `Database`, Flyway, PgBouncer → `registry.tenant` → sự kiện `sys.tenant.provisioned.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Diễn tập trọn quy trình đưa một tenant mới vào vận hành trong một ngày làm việc, cho cả ba mô hình pool, bridge và silo (silo ở mức kiểm tra kích hoạt OPS-23), đo thời gian từng bước, và chạy thử các trạng thái tạm ngừng, chấm dứt. Khi xong, có biên bản chứng minh NFR-07 cho onboarding, và danh sách bước còn thủ công cần tự động hoá.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 11.4 "Vòng đời tenant" (dòng 3793–3820: tám bước, CR `Database` mẫu, Flyway, PgBouncer), mục 2.7 (bản ghi CNAME tenant).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 (khởi tạo tenant, SCR-0102) và danh sách trạng thái vòng đời tenant mà TL-04 mục 11.4 dẫn từ TL-02.
- `docs/tasks/DV-03-05-*.md`, `DV-03-04-*.md` (saga cấp phát), `INF-04-gitops-argo-cd.md`, `VH-01-runbook-ops-01-13.md` (runbook OPS-07).

## 3. Phạm vi công việc

- Hồ sơ tenant thử (tên, mã đơn vị, mô hình, cell đích, tên miền thử, IdP liên kết thử) cho một tenant pool và một tenant bridge; không dùng dữ liệu thật.
- Chạy pipeline onboarding trên staging (hoặc kind): (1) danh bạ SVC-03; (2) CSDL bridge bằng CR `Database` hoặc dòng `tenant` và RLS cho pool; (3) KEK `kek-tenant-<id>`; (4) Organization Keycloak và IdP; (5) cây tổ chức; (6) Policy Pack, UI Pack mặc định; (7) CNAME; (8) kiểm khói.
- Ghi mốc thời gian từng bước và tổng thời gian; kiểm chuyển trạng thái `KHOI_TAO` → `DANG_CAP_PHAT` → `DANG_CAU_HINH` → `SAN_SANG` → `DANG_VAN_HANH` và sự kiện `sys.tenant.provisioned.v1`.
- Chạy lại pipeline giữa chừng sau lỗi cố ý ở bước 4 để kiểm tính idempotent.
- Kiểm khói sau onboarding: người dùng thử đăng nhập có OTP, thấy giao diện UI Pack mặc định, tạo được một OKR; hành trình đầu cuối QT-00 của QA-04 chạy xanh với tenant mới.
- Diễn tập `TAM_NGUNG` (chặn đăng nhập và chặn ghi ở APISIX) rồi kích hoạt lại; `DA_CHAM_DUT` trên tenant thử (xuất bàn giao, huỷ KEK) và xác nhận dữ liệu không còn đọc được.
- Biên bản `ops/dien-tap/<ngày>-ops-07.md` và cập nhật runbook OPS-07 theo bước lệch.

## 4. Ngoài phạm vi

- Sửa saga cấp phát khi lỗi (mở task cho DV-03); tenant silo thật (cần cell riêng, OPS-23); chuyển tenant giữa cell (VH-08).

## 5. Tệp được phép sửa

- `ops/dien-tap/<ngày>-ops-07.md`, `ops/runbooks/ops/ops-07-*.md` (chỉ sửa bước lệch), `data/seed/onboarding-thu/**` (hồ sơ tenant thử).

## 6. Tiêu chí hoàn thành

- Hai tenant thử (pool, bridge) vào trạng thái đang vận hành, tổng thời gian mỗi tenant ≤ 1 ngày làm việc, ghi trong biên bản.
- `E2E_MODE=he-thong npm run e2e:he-thong -- --grep "QT-00"` xanh với tenant mới.
- Tạm ngừng chặn được đăng nhập và ghi; chấm dứt làm dữ liệu tenant thử không giải mã được.

## 7. Báo cáo

Theo AGENTS.md mục 6.
