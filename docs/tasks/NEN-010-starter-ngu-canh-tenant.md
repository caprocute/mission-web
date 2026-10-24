# NEN-010 · ADR-07/ADR-22 vt-platform-starter: ngữ cảnh tenant từ JWT, đặt `app.tenant_id` cục bộ giao dịch qua PgBouncer

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-010` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-009, NEN-033 |
| Truy vết | ADR-07, ADR-22 → TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS" → TL-03 "Đặt ngữ cảnh tenant an toàn qua PgBouncer" → mọi bảng có `tenant_id` và chính sách `rls_<bảng>__tenant` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Làm cho mọi service Java tự động mang đúng tenant từ token đã xác thực xuống tới PostgreSQL: bộ lọc HTTP đọc claim tenant, giữ trong ngữ cảnh của yêu cầu (an toàn với luồng ảo), và trình quản lý giao dịch gọi `set_config('app.tenant_id', ?, true)` ngay sau `BEGIN` để RLS lọc dữ liệu. Đây là lớp bảo vệ chính chống rò rỉ dữ liệu giữa các tenant ở mô hình pool và bridge, nên phải đúng cả khi PgBouncer chế độ giao dịch dùng lại kết nối.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Đa khách thuê": "Lan truyền ngữ cảnh tenant từ JWT tới RLS" (L2670–2716, mã mẫu `TenantContextFilter`, `ConnectionPreparer`); ADR-22 (L3602–3610); mục "Cấu hình mẫu" của SVC-05 (L880–886: `okrkpi.tenant.claim`, `db-setting`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Đặt ngữ cảnh tenant an toàn qua PgBouncer" (L706–734) và "Bảo mật theo dòng" (L684–704).
- Kết quả NEN-033 (mẫu RLS, cấu hình PgBouncer); `infra/dev/keycloak/realm-mission.json` (mapper claim hiện có).

## 3. Phạm vi công việc

- Thêm vào `vt-platform-starter` gói `tenant`: `TenantContext` (đọc/ghi tenant hiện hành, `run(tenantId, Runnable)` cho job và consumer), `TenantContextFilter` đọc claim từ JWT đã được Spring Security xác thực, trả 403 Problem Details khi thiếu claim, ghi `tenant.id` vào MDC.
- Tên claim cấu hình qua `okrkpi.tenant.claim` (mặc định `tenant_id` theo TL-01 L881); hồ sơ `dev` đặt theo realm dev. Điểm lệch cần lưu ý: TL-01 dùng `tenant_id`, TL-03 L758 dùng `tid`, realm dev dùng `tenant`; task không tự chọn mà để cấu hình và ghi vào báo cáo cho NEN-004.
- `TenantJpaTransactionManager` (kế thừa `JpaTransactionManager`) gọi `select set_config('app.tenant_id', ?, true)` trong `doBegin`, và biến thể cho `DataSourceTransactionManager`/`JdbcClient`; không có tenant thì đặt chuỗi rỗng để RLS trả rỗng; tuyệt đối không dùng `SET` mức phiên.
- Chọn cơ chế lưu ngữ cảnh: TL-01 nêu `ScopedValue`, nhưng trên Java 21 đây là API xem trước (cần `--enable-preview`); hiện thực sau giao diện `TenantContext` để đổi được, mặc định dùng `ThreadLocal` tương thích luồng ảo và ghi lựa chọn vào báo cáo (điểm lệch cần lưu ý).
- Luật ArchUnit dùng chung `PlatformArchRules.noDirectConnectionAccess` cấm gọi `DataSource.getConnection()` ngoài gói hạ tầng của thư viện nền (TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS", L2716), và luật cấm truy vấn ngoài giao dịch tường minh ở lớp repository (suy luận từ TL-03 L706–734).
- Kiểm thử tích hợp Testcontainers: PostgreSQL 17 + PgBouncer chế độ `transaction` (`default_pool_size = 1`), một bảng mẫu có RLS theo mẫu NEN-033; hai yêu cầu xen kẽ của tenant A và B qua cùng một kết nối máy chủ không bao giờ thấy dữ liệu của nhau; yêu cầu thiếu claim nhận 403.

## 4. Ngoài phạm vi

- Định tuyến tới đúng CSDL theo `registry.tenant_cell` và mã 421: NEN-036.
- Bộ kiểm thử rò rỉ đầy đủ năm lớp: NEN-034, NEN-035.
- Mapper claim production trên Keycloak: DV-02-02.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `tenant`, cấu hình, kiểm thử)
- `backend/platform-bom/pom.xml` (chỉ thêm Testcontainers module cần thiết)
- `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh, gồm kiểm thử tích hợp PgBouncer.
- Kiểm thử chứng minh: sau giao dịch của tenant A, giao dịch tiếp theo trên cùng kết nối máy chủ không còn `app.tenant_id` của A (`current_setting('app.tenant_id', true)` rỗng hoặc bằng B).
- Kiểm thử âm: gọi `SET app.tenant_id` mức phiên trong mã thử bị luật kiểm tra phát hiện (ArchUnit hoặc kiểm thử đơn vị trên câu SQL).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 17 (nếu đã có) mục "Ngữ cảnh tenant trong mã".

## 7. Báo cáo

Theo AGENTS.md mục 6.
