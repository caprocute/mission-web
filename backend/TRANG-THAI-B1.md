# Trạng thái bàn giao luồng B1 "Nền móng backend và CSDL"

> Ngày 20/10/2026. Người dùng đã chuyển các task backend sang công cụ AI khác (YC-PT-15 trong sổ tay yêu cầu), nên luồng B1 dừng tại đây và không mở task mới. Tệp này ghi trạng thái từng task để bên tiếp nhận biết phần nào đã xong, phần nào còn dở và phần dở nằm ở đâu.

## Đã xong, đã kiểm thử, có trên nhánh `claude/b1-nen-mong-backend-8pacfm`

`./mvnw -B verify` toàn backend xanh ở commit NEN-010 (kernel, starter, vt-db-migration, vt-events; kiểm thử tích hợp chạy bằng Testcontainers).

| Task | Nội dung | Ghi chú |
|---|---|---|
| NEN-002, 003, 004 | Wiki backend (trang 17 đến 21, trang 14) | đã gộp vào main |
| NEN-005 | Khung Maven đa mô-đun, POM cha, BOM, cổng chất lượng | đã gộp vào main |
| NEN-030 | `vt-db-migration`: Flyway theo schema, lặp nhiều CSDL, chặn phiên bản | đã gộp vào main |
| NEN-031 | Vai trò CSDL `mig_`/`svc_`/`ro_`/`cdc_` và quyền mặc định | đã gộp vào main |
| NEN-033 | RLS chuẩn, hàm phân vùng theo kỳ, cấu hình PgBouncer tham chiếu | đã gộp vào main |
| NEN-032 | Bảng dùng chung outbox, inbox, idempotency, modulith, keyring; pg_partman | đã gộp vào main |
| NEN-009 | `vt-kernel`, lỗi RFC 9457 theo danh mục mã lỗi | đã đẩy, chờ thread chính gộp |
| NEN-016 | Chịu lỗi, timeout mặc định, quan sát, nhật ký che dữ liệu | đã đẩy, chờ thread chính gộp |
| NEN-007 | Lược đồ Avro EVT-01..EVT-26, sinh lớp Java, kiểm tương thích | đã đẩy, chờ thread chính gộp |
| NEN-040 | Gói chính sách OPA và kiểm thử Rego | đã đẩy, chờ thread chính gộp |
| NEN-010 | Ngữ cảnh tenant từ JWT tới RLS, kiểm thử qua PgBouncer | đã đẩy, chờ thread chính gộp |

## Dừng dở dang, chưa vào nhánh nào

Phần dở được lưu thành bản vá trong thư mục dùng chung `/mnt/project-files/b1-backend-ban-do-dang/` (áp bằng `git apply` lên commit gốc ghi bên dưới). Chưa có bản nào chạy `verify` đầy đủ; bên tiếp nhận quyết định giữ, sửa hay bỏ.

| Task | Gốc | Tệp | Trạng thái |
|---|---|---|---|
| NEN-006 | 23405d8 | `NEN-006-do-dang.patch` | Mô-đun `vt-api-contract` (sinh mã từ OpenAPI) và khung `vt-contract-test`; dừng giữa bước tiền xử lý hợp đồng, chưa có kiểm thử |
| NEN-011 | bf4063f | `NEN-011-012-do-dang.patch`, `TRANG-THAI-NEN-011-NEN-012.md` | OutboxWriter, EventEnvelope, serde Apicurio, SMT tách header; 28 kiểm thử đơn vị xanh; thiếu kiểm thử tích hợp Debezium |
| NEN-012 | | | Chưa bắt đầu |
| NEN-013, NEN-015 | bf4063f | `NEN-013-015-do-dang.patch` | Idempotency-Key (interceptor, store JDBC), ETag, phân trang con trỏ, job 202; dừng giữa chừng, chưa chạy kiểm thử; phần mã hoá NEN-015 chưa bắt đầu |
| NEN-017 | bf4063f | `NEN-017-do-dang.patch`, `TRANG-THAI-NEN-017.md` | Thư viện Go `services-go/vtplatform` biên dịch được, chỉ có kiểm thử đồng bộ hợp đồng |
| NEN-018 | | | Chưa bắt đầu |

## Chưa bắt đầu

NEN-008, NEN-014, NEN-019, NEN-020, NEN-021, NEN-022, NEN-034, NEN-036, NEN-042, NEN-043.

## Điểm cần người phụ trách quyết định

- `disruptor` 4.0.0 trong BOM nằm ngoài danh sách thư viện được phép (NEN-016 thêm để Log4j2 ghi bất đồng bộ).
- Tên claim tenant lệch giữa TL-01 (`tenant_id`), TL-03 (`tid`) và realm dev (`tenant`); starter để cấu hình `okrkpi.tenant.claim`, mặc định `tenant_id`.
- TL-01 gợi ý `ScopedValue` cho ngữ cảnh tenant, nhưng đây là tính năng preview trên Java 21; NEN-010 dùng `ThreadLocal` với API có phạm vi.
- Cột header của outbox: DDL TL-03 không có `trace_parent`, `classification`, `schema_id` mà cấu hình Debezium mẫu cần; topic `sys.audit.v1` không qua được CHECK bốn đoạn (chi tiết trong tệp trạng thái NEN-011).
- Các điểm lệch và câu hỏi mở phía máy chủ ghi ở wiki trang 14.
