# NEN-033 · ADR-07/ADR-22 Mẫu RLS chuẩn, hàm `fn_create_period_partitions`, cấu hình PgBouncer chế độ giao dịch

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-033` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | L (nên tách: a) hàm RLS chuẩn + cấu hình PgBouncer tham chiếu; b) `fn_create_period_partitions` + biến thể pool 16 phân vùng băm + kiểm thử) |
| Phụ thuộc | NEN-031 |
| Truy vết | ADR-07, ADR-22 → TL-03 "Bảo mật theo dòng (Row-Level Security)", "Đặt ngữ cảnh tenant an toàn qua PgBouncer", "Phân vùng", "Kết nối và PgBouncer" → mọi bảng có `tenant_id`; bảng theo kỳ có `period_bucket` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp ba mảnh nền mà mọi migration nghiệp vụ dùng lại: (1) cách chuẩn bật RLS cho một bảng (`ENABLE` + `FORCE`, chính sách `rls_<bảng>__tenant` cho `svc_` và `ro_`, dùng `current_setting('app.tenant_id', true)`), (2) hàm `fn_create_period_partitions` tạo phân vùng LIST theo `period_bucket` khi tạo kỳ, kèm biến thể pool chia tiếp 16 phân vùng HASH theo `tenant_id`, bảo vệ hai lớp trên phân vùng con, và (3) cấu hình PgBouncer chế độ giao dịch tham chiếu. Khi xong, task migration của từng schema chỉ gọi hàm chuẩn thay vì tự viết chính sách, và RLS vẫn có hiệu lực kể cả khi ai đó truy vấn trực tiếp phân vùng con.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Bảo mật theo dòng (Row-Level Security)" (L684–704, chính sách mẫu, đoạn phân vùng con L704), "Đặt ngữ cảnh tenant an toàn qua PgBouncer" (L706–734), "Bố trí theo mô hình pool" (L665–682), "Cột chung của bảng nghiệp vụ" (L97–115: `period_bucket` = năm×10+quý), chương "Hiệu năng và mở rộng" mục "Phân vùng" (L4560–4564) và "Kết nối và PgBouncer" (L4570–4574).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Lan truyền ngữ cảnh tenant từ JWT tới RLS" (L2670–2716, SQL mẫu L2700–2712), ADR-22 (L3602–3610).
- Kết quả NEN-030, NEN-031.

## 3. Phạm vi công việc

- Viết thủ tục `fn_enable_tenant_rls(p_table regclass)` (do `mig_<schema>` sở hữu, tạo trong mỗi schema qua `db/common`) bật `ENABLE` + `FORCE ROW LEVEL SECURITY` và tạo `rls_<bảng>__tenant FOR ALL TO svc_<schema>, ro_<schema> USING/WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid)`; idempotent; dùng được trong migration của mọi schema.
- Viết `fn_create_period_partitions(p_parent regclass, p_period_bucket int)` `SECURITY DEFINER`, `search_path` cố định, do `mig_<schema>` sở hữu, `svc_<schema>` chỉ có `EXECUTE`: tạo phân vùng LIST cho `period_bucket`; nếu CSDL là pool (tham số hoặc cấu hình trong schema, suy luận) thì chia tiếp 16 phân vùng con `HASH (tenant_id)`; trên mỗi phân vùng con thu hồi quyền trực tiếp của vai trò ứng dụng, bật RLS + chính sách như bảng cha; gọi lại với cùng tham số không lỗi.
- Không tạo phân vùng mặc định (TL-03: để lỗi nhập kỳ sai lộ ra ngay); chèn dòng có `period_bucket` chưa có phân vùng phải lỗi rõ.
- Cấu hình PgBouncer tham chiếu `backend/libs/vt-db-migration/src/main/resources/pgbouncer/pgbouncer.ini.template`: `pool_mode = transaction`, `max_prepared_statements = 200`, `server_reset_query = ''`, `server_check_query = 'select 1'`, `default_pool_size = 60`, `max_client_conn = 4000`; với bridge một mục `[databases]` mỗi `td_<mã>` tối đa 30 kết nối máy chủ; ghi rõ PgBouncer ≥ 1.21 (TL-01 Phụ lục B chọn 1.23). Đây là tham chiếu cho NEN-020 (dev), NEN-034 (kiểm thử) và INF-11 (CNPG Pooler).
- Ghi trong README của `vt-db-migration` hai quy tắc bắt buộc: chỉ đặt tenant bằng `set_config('app.tenant_id', ?, true)` trong giao dịch tường minh; mọi chỉ mục ở bảng pool bắt đầu bằng `tenant_id`.
- Kiểm thử Testcontainers: bảng mẫu theo kỳ ở chế độ bridge và pool; tạo phân vùng hai kỳ; tenant A không đọc/ghi được dòng của B qua bảng cha và qua phân vùng con; `svc_` không SELECT trực tiếp được phân vùng con; thiếu `app.tenant_id` thì đọc rỗng, ghi lỗi.
- Điểm lệch cần lưu ý: TL-01 L2700–2712 đặt tên chính sách `tenant_isolation` và dùng `nullif(current_setting(...), '')::uuid`; TL-03 dùng `rls_<bảng>__tenant` theo quy ước đặt tên; task theo TL-03 (quy ước đặt tên CSDL) nhưng dùng `nullif(...)` để chuỗi rỗng không gây lỗi ép kiểu (suy luận, ghi vào báo cáo). Định nghĩa đầy đủ `fn_create_period_partitions` TL-03 hẹn ở chương 14 nhưng không có; task tự thiết kế theo các ràng buộc đã nêu và ghi giả định.

## 4. Ngoài phạm vi

- Job tạo trước phân vùng hai kỳ khi tạo kỳ: CORE-08-02 (gọi hàm của task này).
- Bộ kiểm thử rò rỉ toàn danh mục: NEN-034; cụm PgBouncer production: INF-11.

## 5. Tệp được phép sửa

- `backend/libs/vt-db-migration/src/main/resources/db/common/**`, `backend/libs/vt-db-migration/src/main/resources/pgbouncer/**` (mới)
- `backend/libs/vt-db-migration/src/test/**`, `backend/libs/vt-db-migration/README.md`

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-db-migration -am verify` chạy xanh với các kịch bản ở mục 3, cả chế độ bridge và pool.
- `EXPLAIN` truy vấn có `period_bucket` chỉ quét một phân vùng (một kiểm thử đọc `EXPLAIN (FORMAT JSON)`).
- Báo cáo cuối task theo AGENTS.md mục 6, liệt kê giả định về hàm phân vùng; cập nhật wiki trang 17 (nếu đã có) mục RLS và phân vùng.

## 7. Báo cáo

Theo AGENTS.md mục 6.
