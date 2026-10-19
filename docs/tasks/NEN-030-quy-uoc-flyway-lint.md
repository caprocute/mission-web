# NEN-030 · TL-03 Quy ước Flyway theo schema, tài khoản `mig_<schema>`, lint tên, kiểu và chú thích phân loại cột trong CI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-030` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-005 |
| Truy vết | ADR-02 → TL-03 "Quy ước đặt tên", "Kiểu dữ liệu chuẩn", "Phân loại dữ liệu C0 đến C3 trên từng cột", "Ứng dụng không có quyền DDL", "Flyway và quy trình thay đổi lược đồ" → mọi schema của baseline mục 9 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đặt nền cho mọi migration của hệ thống: mỗi schema một bộ Flyway riêng chạy bằng tài khoản `mig_<schema>` (ứng dụng không có quyền DDL), một bộ chạy migration dùng được cho cả Kubernetes Job và kiểm thử, và các bài kiểm tra tự động chặn migration sai quy ước đặt tên, sai kiểu chuẩn hay thiếu chú thích phân loại cột. Khi xong, các task CORE-0x-01, DV-xx-01 viết migration vào đúng thư mục và CI tự bắt lỗi quy ước trước khi người duyệt phải đọc.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Giới thiệu": "Quy ước đặt tên" (L44–72), "Kiểu dữ liệu chuẩn" (L74–95), "Cột chung của bảng nghiệp vụ" (L97–115), "Phân loại dữ liệu C0 đến C3 trên từng cột" (L117–137); chương "Nguyên tắc thiết kế dữ liệu": "Ứng dụng không có quyền DDL" (L181–185), "Không phụ thuộc SQL đặc thù của hãng" (L187–191); mục "Ba mô hình thuê bao trên cùng một lược đồ" (L621–627); chương "Quản lý thay đổi lược đồ và di trú từ Oracle" mục "Flyway và quy trình thay đổi lược đồ" (L4592–4598).
- `docs/quy-dinh/BACKEND.md` mục 3 quy ước 7.

## 3. Phạm vi công việc

- Tạo mô-đun `backend/libs/vt-db-migration/` gồm: bộ chạy `MigrationRunner` (lớp `main`) nhận danh sách schema và chuỗi kết nối, với mỗi schema chạy Flyway 11 bằng tài khoản `mig_<schema>`, `locations = classpath:db/common, classpath:db/migration/<schema>`, `defaultSchema = <schema>`, `table = flyway_schema_history` trong chính schema đó; cho phép lặp nhiều CSDL (mô hình bridge, mỗi nhóm 5 CSDL song song, CSDL lỗi không chặn CSDL khác) theo TL-03 L4596.
- Quy ước thư mục và đánh số ghi trong `backend/libs/vt-db-migration/README.md`: migration của mission-core ở `backend/mission-core/src/main/resources/db/migration/<schema>/`, của microservice ở `backend/services/<ten>/src/main/resources/db/migration/<schema>/`; migration dùng chung mọi schema ở `db/common` dùng placeholder `${flyway:defaultSchema}`, đánh số dải riêng để không đụng số của schema (suy luận về dải số); chỉ thêm mới theo expand/contract, ràng buộc nặng dùng `NOT VALID` rồi `VALIDATE`.
- `SchemaVersionGuard` (auto-configuration nhỏ) kiểm `flyway_schema_history` của schema mình khi khởi động và dừng ứng dụng nếu phiên bản thấp hơn mức tối thiểu khai trong cấu hình; ứng dụng đặt `spring.flyway.enabled=false`, `spring.jpa.hibernate.ddl-auto=validate`.
- Lint quy ước (chạy sau khi áp toàn bộ migration lên PostgreSQL 17 Testcontainers): truy vấn `pg_catalog`/`information_schema` kiểm tên đối tượng theo L44–72 (`pk_`, `fk_<bảng>__<đích>`, `uq_`, `ck_`, `ex_`, `ix_`, `rls_<bảng>__tenant`, `tg_`, `fn_`), không định danh có chữ hoa (trừ bảng Flowable `ACT_*`, `FLW_*`), kiểu nằm trong danh sách chuẩn L74–95, cột `is_`/`has_` là `boolean NOT NULL DEFAULT false`, cột `_at` là `timestamptz`.
- Lint phân loại: mọi cột có `COMMENT` JSON hợp lệ có khoá `cls` ∈ {C0, C1, C2, C3}; cột `"cls":"C2"` phải là `bytea` có hậu tố `_enc` và bảng có `dek_id`; xuất danh sách cột cần loại khỏi CDC phân tích (`column.exclude.list`) thành tệp sinh để DV-15-02 dùng.
- Bộ lint là lớp kiểm thử dùng lại `MigrationConventionsTest` mà mỗi mô-đun có migration kế thừa; kèm bộ migration mẫu nhỏ đúng và sai trong `src/test/resources` để chứng minh lint bắt lỗi.
- Ghi khung "CI ba bước" của TL-03 (áp lên CSDL trống, áp lên ảnh chụp lược đồ bản đang chạy, kiểm chú thích/RLS/quyền) trong README; bước 2 chỉ bật khi có bản phát hành đầu tiên (suy luận).

## 4. Ngoài phạm vi

- Tạo vai trò CSDL: NEN-031; bảng dùng chung (outbox, inbox, idempotency): NEN-032; RLS và phân vùng kỳ: NEN-033; kiểm tra RLS đủ trên mọi bảng: NEN-034.
- Kubernetes Job/Helm hook chạy migration: INF-01, INF-02 (task này chỉ cung cấp bộ chạy).

## 5. Tệp được phép sửa

- `backend/libs/vt-db-migration/**` (mới), `backend/libs/pom.xml`, `backend/pom.xml` (chỉ thêm mô-đun)
- `backend/platform-bom/pom.xml` (chỉ thêm Flyway, driver PostgreSQL nếu chưa có)
- `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-db-migration -am verify` chạy xanh; bộ migration mẫu sai làm từng luật lint thất bại với thông báo chỉ rõ bảng, cột, luật.
- `MigrationRunner` chạy trên hai CSDL Testcontainers liên tiếp, CSDL thứ hai có migration lỗi thì CSDL thứ nhất vẫn hoàn tất và mã thoát khác 0.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 17 (nếu đã có) mục "Viết migration".

## 7. Báo cáo

Theo AGENTS.md mục 6.
