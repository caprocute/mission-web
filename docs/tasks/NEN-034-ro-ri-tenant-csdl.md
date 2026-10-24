# NEN-034 · ADR-07 Kiểm thử rò rỉ tenant lớp CSDL: danh mục RLS, hai tenant, PgBouncer dùng lại kết nối, phân vùng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-034` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-033, NEN-010 |
| Truy vết | ADR-07, ADR-22 → TL-03 "Kiểm thử rò rỉ tenant" lớp 1–4 → TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS" (kiểm thử bảo vệ) → mọi bảng có `tenant_id` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng bộ kiểm thử tự động chứng minh không có rò rỉ dữ liệu giữa các tenant ở tầng CSDL, chạy trong mọi build có migration: kiểm danh mục (mọi bảng có `tenant_id` đều bật RLS, FORCE và có chính sách), kiểm hành vi hai tenant sinh tự động từ danh mục bảng, kiểm dưới PgBouncer dùng lại kết nối, và luật quét mã chặn đặt tenant sai cách. Bộ kiểm thử tự phủ schema mới khi các task migration thêm bảng, nên đây là "lưới an toàn" cho toàn bộ chương trình mà không cần ai nhớ viết lại.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Kiểm thử rò rỉ tenant" (L736–754, truy vấn danh mục L742–752, năm lớp), "Bảo mật theo dòng" (L684–704), "Đặt ngữ cảnh tenant an toàn qua PgBouncer" (L706–734).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Lan truyền ngữ cảnh tenant từ JWT tới RLS" (L2716: ArchUnit cấm `DataSource.getConnection()`, Testcontainers hai tenant chặn merge).
- Kết quả NEN-033 (hàm RLS, phân vùng, PgBouncer tham chiếu), NEN-010 (`TenantJpaTransactionManager`).

## 3. Phạm vi công việc

- Lớp 1 – danh mục: lớp kiểm thử dùng lại `TenantIsolationCatalogTest` chạy truy vấn TL-03 L742–752 sau khi áp toàn bộ migration của mô-đun, thất bại nếu có dòng; mở rộng kiểm cả phân vùng con (`relispartition`) và bảng không có `tenant_id` phải nằm trong danh sách ngoại lệ có lý do.
- Lớp 2 – hai tenant sinh tự động: với mỗi bảng có `tenant_id`, sinh dòng hợp lệ tối thiểu cho tenant A và B (dựa trên kiểu cột, NOT NULL, CHECK, FK trong cùng schema; bảng không sinh được thì yêu cầu mô-đun cung cấp fixture), rồi dưới ngữ cảnh A kiểm SELECT/UPDATE/DELETE dòng B = 0 dòng, INSERT với `tenant_id` của B lỗi, không đặt ngữ cảnh thì SELECT rỗng.
- Lớp 3 – PgBouncer: container PgBouncer theo cấu hình tham chiếu NEN-033 với `default_pool_size = 1`; xen kẽ hàng nghìn giao dịch của hai tenant qua `TenantJpaTransactionManager` và `JdbcClient`, kiểm không giao dịch nào thấy dòng của tenant kia và `current_setting('app.tenant_id', true)` không rò sang giao dịch sau.
- Phân vùng: với bảng theo kỳ, kiểm cả truy vấn trực tiếp phân vùng con (vai trò `svc_` bị từ chối) và chế độ pool 16 phân vùng băm.
- Lớp 4 – quét mã: luật Semgrep `backend/semgrep/tenant-context.yml` chặn `SET app.` không có `LOCAL`, `set_config(..., false)`, `SET SESSION`, áp cho Java, Go, Python và SQL; kèm tệp mẫu sai để chứng minh luật bắt được.
- Đóng gói lớp 1–3 thành phụ thuộc kiểm thử (`vt-db-migration` classifier `tests` hoặc mô-đun `vt-tenant-test`, suy luận) để mỗi mô-đun có migration chỉ cần kế thừa; viết hướng dẫn ngắn trong README.

## 4. Ngoài phạm vi

- Lớp API, bộ đệm Valkey, chỉ mục tìm kiếm, kho phân tích: NEN-035.
- Lớp 5 (tenant canh gác trên môi trường thật, job mỗi giờ, cảnh báo SOC): nhóm VH/QA sau khi có API thật.

## 5. Tệp được phép sửa

- `backend/libs/vt-db-migration/src/test/**` hoặc `backend/libs/vt-tenant-test/**` (mới), `backend/libs/pom.xml` (nếu thêm mô-đun)
- `backend/semgrep/**` (mới)
- `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-db-migration -am verify` (và mô-đun mới nếu có) chạy xanh trên các schema mẫu `task`, `appraisal`, `modulith`, `keyring`.
- Kiểm thử âm: một migration mẫu quên `FORCE ROW LEVEL SECURITY` làm lớp 1 thất bại; một chính sách viết sai (`USING (true)`) làm lớp 2 thất bại; một mẫu mã `SET app.tenant_id` làm Semgrep báo lỗi (`semgrep --config backend/semgrep --error`).
- Lớp 3 chạy tối thiểu 5.000 giao dịch xen kẽ, báo cáo thời gian chạy để quyết định đưa vào mọi build hay chỉ build đêm.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 17 (nếu đã có) mục "Kiểm thử rò rỉ tenant".

## 7. Báo cáo

Theo AGENTS.md mục 6.
