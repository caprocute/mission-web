# CNH-02 · NFR-11 Backend lưu tuỳ chọn cá nhân của người dùng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CNH-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CNH-01, CORE-08-01; ghép bố cục trang chủ cần CORE-08-11 (bổ sung so với khung) |
| Truy vết | NFR-11, ADR-05 → SCR-0002 và các màn hình danh sách → API đề xuất ở CNH-01 (dưới `/api/v1/config`, sau khi được thêm vào `config.yaml`) → MOD-08 → bảng đề xuất `config.user_preference`, `config.saved_filter` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực ở MOD-08 việc lưu và đọc tuỳ chọn cá nhân của chính người dùng (chế độ cao tuổi, bố cục trang chủ, cột hiển thị, dashboard mặc định) và bộ lọc đã lưu trên danh sách, đúng thiết kế đã được duyệt ở CNH-01. Khi xong, web và di động đọc ghi được các tuỳ chọn này qua API có `If-Match`, và `getHomeLayout` trả bố cục đã áp lớp phủ cá nhân.

## 2. Đầu vào bắt buộc đọc

- Đề xuất đã duyệt của CNH-01 (`docs/de-xuat/de-xuat-ca-nhan-hoa.md`) và quyết định ghi ở `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.
- `contracts/openapi/modules/config.yaml` bản đã bổ sung đường dẫn tuỳ chọn cá nhân (sau task sửa hợp đồng).
- `TL-03-thiet-ke-csdl.md` quy ước chung (cột chung, RLS, phân loại cột) và "Bảng config.tenant_setting" (dòng 1481).
- Thẻ CORE-08-11 (`getHomeLayout`).

## 3. Phạm vi công việc

- Phần cần bổ sung thiết kế trước khi làm: bảng, API, mã SCR, BR, MSG và dòng ma trận phân quyền chưa có trong TL; chỉ bắt đầu khi CNH-01 được duyệt và hợp đồng đã có đường dẫn. Thiếu thì dừng và báo cáo.
- Theo đề xuất mặc định (suy luận, đổi theo quyết định thực tế): migration mới tạo `config.user_preference` (UQ `(tenant_id, user_id, pref_key)`, `value jsonb` ≤ 16 KB, `value_schema_version`) và `config.saved_filter` (tối đa 20 bộ mỗi màn hình mỗi người); RLS theo tenant, chính sách OPA chỉ cho chủ bản ghi đọc ghi (phạm vi "Bản thân").
- API tuỳ chọn: đọc theo tiền tố, ghi từng khoá có `If-Match` (409 khi lệch), xoá; kiểm `value` theo lược đồ của từng loại khoá (`ui.mode` chỉ `default` hoặc `elderly`, `list.*.columns` chỉ tên cột có trong danh mục cột của màn hình, `home.layout` chỉ mã widget).
- API bộ lọc đã lưu: tạo có Idempotency-Key, sửa có `If-Match`, xoá; điều kiện lọc chỉ chứa mã và khoảng giá trị, từ chối văn bản tự do để tránh lưu dữ liệu C2.
- Mở rộng `getHomeLayout`: áp lớp phủ thứ tự và ẩn widget của người dùng lên cây đã lọc theo vai trò; widget không còn trong UI Pack bị bỏ qua; ETag tính cả phiên bản lớp phủ.
- Khi tài khoản bị vô hiệu hoá hoặc chuyển tenant, xoá tuỳ chọn và bộ lọc theo sự kiện của tổ chức (suy luận, xác nhận ở CNH-01).

## 4. Ngoài phạm vi

- Dashboard cá nhân (dùng `config.dashboard_def` và API của DV-15-04); giao diện web và di động (CNH-03 đến CNH-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/resources/db/migration/config/**` (migration mới)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `deploy/policies/**` (quy tắc phạm vi "Bản thân" cho tuỳ chọn cá nhân)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers cho: người khác không đọc được tuỳ chọn (403 hoặc 404), `If-Match` lệch trả 409, giá trị sai lược đồ trả 422, vượt 20 bộ lọc trả 422, lớp phủ trang chủ áp đúng và ETag đổi khi lớp phủ đổi.
- Kiểm thử hợp đồng khớp `config.yaml` bản đã bổ sung; `opa test` xanh.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu rõ chỗ đã theo đề xuất mặc định và chỗ đã đổi theo quyết định.

## 7. Báo cáo

Theo AGENTS.md mục 6.
