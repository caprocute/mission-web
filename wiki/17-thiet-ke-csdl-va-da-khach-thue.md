> Nguồn: TL-03 chương "Giới thiệu" (Quy ước đặt tên, Kiểu dữ liệu chuẩn, Cột chung, Phân loại dữ liệu C0 đến C3), chương "Nguyên tắc thiết kế dữ liệu" (Ứng dụng không có quyền DDL), chương "Quản lý thay đổi lược đồ và di trú từ Oracle" (Flyway và quy trình thay đổi lược đồ); `backend/libs/vt-db-migration/README.md` (NEN-030).

# Thiết kế CSDL và đa khách thuê

Trang này gom tri thức CSDL cho người viết mã máy chủ. Phần tổng quan về sở hữu dữ liệu, ba mô hình thuê bao và RLS do task NEN-002 viết; hiện trang mới có các mục "Vai trò CSDL" (NEN-031), "RLS và phân vùng" (NEN-033) và "Viết migration" (NEN-030).

## Vai trò CSDL

Mỗi schema có bốn vai trò: `mig_<schema>` sở hữu mọi đối tượng và chỉ Flyway dùng, `svc_<schema>` là tài khoản ứng dụng chỉ có DML, `ro_<schema>` chỉ đọc, `cdc_<schema>` cho Debezium chỉ đọc `outbox_event`. Không vai trò nào có `BYPASSRLS`, `INHERIT`, `SUPERUSER` hay quyền tạo CSDL, vai trò. `svc_<schema>` có `statement_timeout = 5s`, `idle_in_transaction_session_timeout = 10s`, nên một giao dịch quên đóng không giữ kết nối PgBouncer quá 10 giây. Vì mỗi mô-đun của mission-core dùng một DataSource đăng nhập bằng vai trò riêng, truy vấn nhầm sang schema mô-đun khác bị CSDL từ chối (`42501 permission denied`), ranh giới mô-đun trở thành ràng buộc kỹ thuật chứ không chỉ là quy ước ArchUnit.

Ba ngoại lệ duy nhất: các mô-đun mission-core đọc `org_replica` (chỉ `svc_org_replica` ghi), có DML trên `modulith.event_publication`, và mọi `svc_` chỉ `SELECT, INSERT` trên `keyring.dek` (chỉ `svc_keyring` đổi trạng thái). Vai trò và quyền do `DatabaseBootstrap` của `vt-db-migration` tạo, idempotent; chạy lại sau migration để cấp quyền cho bảng mới mà quyền mặc định không phủ (ví dụ `outbox_event` cho `cdc_`). Bẫy: vai trò PostgreSQL là toàn cụm, nên ở cell bridge các CSDL `td_<mã>` trên cùng cụm dùng chung tên vai trò; cô lập tỉnh dựa vào định tuyến CSDL và RLS (suy luận). Hạ tầng dev hiện vẫn dùng một vai trò trùng tên schema trong một CSDL `mission`; NEN-020 chuyển dev sang bộ vai trò này.

## RLS và phân vùng

Mọi bảng có `tenant_id` bật RLS ở chế độ `FORCE`, kể cả ở cell silo chỉ có một tenant, vì cả ba mô hình silo, bridge, pool dùng chung một lược đồ. Migration không tự viết chính sách mà gọi `<schema>.fn_enable_tenant_rls('<schema>.<bảng>')`, hàm dùng chung do `vt-db-migration` cài vào mọi schema; chính sách `rls_<bảng>__tenant` so `tenant_id` với `nullif(current_setting('app.tenant_id', true), '')::uuid`, nên thiếu ngữ cảnh thì đọc rỗng và ghi lỗi chứ không bao giờ thấy dữ liệu tenant khác. Ngữ cảnh chỉ được đặt bằng `set_config('app.tenant_id', ?, true)` trong giao dịch tường minh, vì PgBouncer chạy chế độ giao dịch và biến mức phiên sẽ rò sang tenant khác.

Bảng theo kỳ phân vùng LIST theo `period_bucket` (năm×10 + quý). Phân vùng do `fn_create_period_partitions` tạo khi mở kỳ (SECURITY DEFINER, vai trò ứng dụng chỉ có EXECUTE); ở CSDL pool (tham số mức CSDL `app.tenancy_model = 'pool'`) mỗi kỳ chia tiếp 16 phân vùng con HASH theo `tenant_id`. Phân vùng con bị thu hồi quyền trực tiếp và có RLS riêng, vì chính sách của bảng cha không áp khi truy vấn thẳng phân vùng con. Không có phân vùng mặc định để lỗi nhập kỳ sai lộ ra ngay.

Bẫy quan trọng: vì pool chia băm theo `tenant_id`, khoá chính và ràng buộc duy nhất của mọi bảng theo kỳ phải chứa cả `period_bucket` và `tenant_id` (ví dụ `(period_bucket, tenant_id, id)`), nếu không migration chạy được ở silo, bridge nhưng hàm tạo phân vùng lỗi ở pool. TL-03 ghi khoá chính `(period_bucket, id)`; điểm lệch này cần NEN-004 đưa vào trang 14. Bẫy thứ hai: `mig_<schema>` cũng chịu RLS do `FORCE`, nên migration điền dữ liệu phải chạy bằng `svc_` theo từng tenant (suy luận).

## Viết migration

Mỗi schema có một bộ migration Flyway riêng trong mô-đun sở hữu nó, ở `src/main/resources/db/migration/<schema>/`, và bảng lịch sử `flyway_schema_history` nằm trong chính schema đó. Migration chạy bằng Kubernetes Job với tài khoản `mig_<schema>`, là chủ sở hữu mọi đối tượng trong schema; ứng dụng chỉ có tài khoản `svc_<schema>`, đặt `spring.flyway.enabled=false` và `spring.jpa.hibernate.ddl-auto=validate`, và khi khởi động kiểm phiên bản lược đồ bằng `vt.db.schema-guard` để dừng nếu lược đồ cũ hơn mức mã cần. Lý do là tiêu chí K1 của bản đánh giá: hệ hiện hành để ứng dụng tự chạy `ALTER TABLE` lúc khởi động, vừa rủi ro an ninh vừa làm nhiều pod cùng sửa lược đồ.

Migration dùng chung mọi schema (outbox, inbox, idempotency của NEN-032) nằm ở `db/common`, đánh số dải `V0_x` và dùng placeholder `${flyway:defaultSchema}`; migration riêng của schema bắt đầu từ `V1` (suy luận, TL-03 không quy định dải số). Chỉ thêm mới, không sửa migration đã gộp, và thay đổi phá vỡ đi theo expand and contract qua ba bản phát hành; ràng buộc nặng thêm bằng `NOT VALID` rồi `VALIDATE`.

Mỗi mô-đun có migration kế thừa `MigrationConventionsTest` (test-jar của `vt-db-migration`) trong một lớp `*IT`. Lớp này áp migration lên PostgreSQL 17 Testcontainers và chặn build khi tên đối tượng sai quy ước (`pk_`, `fk_<bảng>__<đích>`, `uq_`, `ck_`, `ex_`, `ix_`, `rls_<bảng>__`, `tg_`, `fn_`), có chữ hoa, dùng kiểu ngoài danh sách chuẩn (ví dụ `varchar` không độ dài, `timestamp` không múi giờ, `numeric` khác ba độ chính xác chuẩn), cột cờ hay cột `_at` sai kiểu, hoặc cột thiếu chú thích phân loại JSON `{"cls":"C0..C3"}`. Cột `C2` phải là `bytea` hậu tố `_enc` trong bảng có `dek_id`. Bẫy hay gặp: mọi cột, kể cả `id` và `tenant_id`, đều cần chú thích phân loại; tên ràng buộc mặc định của PostgreSQL (`*_pkey`, `*_fkey`, `*_key`) luôn bị bắt, nên phải đặt tên tường minh bằng `CONSTRAINT`.

Danh sách cột C2, C3 mà lint xuất ra (`target/generated-resources/cdc/<schema>-column-exclude-list.txt`) là đầu vào cho `column.exclude.list` của connector Debezium phân tích (DV-15-02).

## Liên quan

- `backend/libs/vt-db-migration/README.md`
- `docs/quy-dinh/BACKEND.md` mục 3
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
