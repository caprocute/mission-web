> Nguồn: TL-03 chương "Giới thiệu" (Quy ước đặt tên, Kiểu dữ liệu chuẩn, Cột chung, Phân loại dữ liệu C0 đến C3), chương "Nguyên tắc thiết kế dữ liệu" (Ứng dụng không có quyền DDL), chương "Quản lý thay đổi lược đồ và di trú từ Oracle" (Flyway và quy trình thay đổi lược đồ); `backend/libs/vt-db-migration/README.md` (NEN-030).

# Thiết kế CSDL và đa khách thuê

Trang này gom tri thức CSDL cho người viết mã máy chủ. Phần tổng quan về sở hữu dữ liệu, ba mô hình thuê bao và RLS do task NEN-002 viết; hiện trang mới có mục "Viết migration" do NEN-030 tạo.

## Viết migration

Mỗi schema có một bộ migration Flyway riêng trong mô-đun sở hữu nó, ở `src/main/resources/db/migration/<schema>/`, và bảng lịch sử `flyway_schema_history` nằm trong chính schema đó. Migration chạy bằng Kubernetes Job với tài khoản `mig_<schema>`, là chủ sở hữu mọi đối tượng trong schema; ứng dụng chỉ có tài khoản `svc_<schema>`, đặt `spring.flyway.enabled=false` và `spring.jpa.hibernate.ddl-auto=validate`, và khi khởi động kiểm phiên bản lược đồ bằng `vt.db.schema-guard` để dừng nếu lược đồ cũ hơn mức mã cần. Lý do là tiêu chí K1 của bản đánh giá: hệ hiện hành để ứng dụng tự chạy `ALTER TABLE` lúc khởi động, vừa rủi ro an ninh vừa làm nhiều pod cùng sửa lược đồ.

Migration dùng chung mọi schema (outbox, inbox, idempotency của NEN-032) nằm ở `db/common`, đánh số dải `V0_x` và dùng placeholder `${flyway:defaultSchema}`; migration riêng của schema bắt đầu từ `V1` (suy luận, TL-03 không quy định dải số). Chỉ thêm mới, không sửa migration đã gộp, và thay đổi phá vỡ đi theo expand and contract qua ba bản phát hành; ràng buộc nặng thêm bằng `NOT VALID` rồi `VALIDATE`.

Mỗi mô-đun có migration kế thừa `MigrationConventionsTest` (test-jar của `vt-db-migration`) trong một lớp `*IT`. Lớp này áp migration lên PostgreSQL 17 Testcontainers và chặn build khi tên đối tượng sai quy ước (`pk_`, `fk_<bảng>__<đích>`, `uq_`, `ck_`, `ex_`, `ix_`, `rls_<bảng>__`, `tg_`, `fn_`), có chữ hoa, dùng kiểu ngoài danh sách chuẩn (ví dụ `varchar` không độ dài, `timestamp` không múi giờ, `numeric` khác ba độ chính xác chuẩn), cột cờ hay cột `_at` sai kiểu, hoặc cột thiếu chú thích phân loại JSON `{"cls":"C0..C3"}`. Cột `C2` phải là `bytea` hậu tố `_enc` trong bảng có `dek_id`. Bẫy hay gặp: mọi cột, kể cả `id` và `tenant_id`, đều cần chú thích phân loại; tên ràng buộc mặc định của PostgreSQL (`*_pkey`, `*_fkey`, `*_key`) luôn bị bắt, nên phải đặt tên tường minh bằng `CONSTRAINT`.

Danh sách cột C2, C3 mà lint xuất ra (`target/generated-resources/cdc/<schema>-column-exclude-list.txt`) là đầu vào cho `column.exclude.list` của connector Debezium phân tích (DV-15-02).

## Liên quan

- `backend/libs/vt-db-migration/README.md`
- `docs/quy-dinh/BACKEND.md` mục 3
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
