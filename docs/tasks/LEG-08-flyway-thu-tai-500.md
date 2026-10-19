# LEG-08 · Đợt 0c/PERF-01, PERF-04 Flyway cho phần mới và thử tải hệ cũ ở 500 người dùng đồng thời

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c – chịu lỗi và cổng chất lượng (6 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | LEG-07 |
| Truy vết | K03 (một phần); OPS-09; PERF-01, PERF-04 (rút gọn); TL-04 "Đợt 0c"; TL-01 "Lộ trình chuyển đổi…", mục "Đợt 0c" (p95 < 3 s ở 500 CCU) |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Mọi thay đổi lược đồ Oracle của hệ cũ từ nay đi qua Flyway (không thêm bảng mới vào schema `VOFFICE`), và có kết quả thử tải chứng minh hệ cũ đạt p95 < 3 giây ở 500 người dùng đồng thời qua APISIX. Đây là điều kiện chuyển bước cuối của đợt 0 trước khi bắt đầu đợt 1.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Đợt 0c" (dòng 3915–3927: Flyway OPS-09, thử tải 500 phiên PERF-01, PERF-04; cách đo k6 500 VU tại APISIX), "Danh mục quy trình OPS" (OPS-09), "Danh mục kịch bản thử tải PERF", "Bảng chỉ số theo dõi và cách đo" (bảng mới trong `VOFFICE` = 0).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, "Flyway và quy trình thay đổi lược đồ" (expand/contract).
- Kết quả LEG-06 (APISIX trước hệ cũ), LEG-07.
- Đầu vào người dùng cần cấp:
  - Một môi trường thử tải cho hệ cũ có cấu hình tương đương production và dữ liệu đã giả danh hoá (hoặc được phép dùng theo quy định dữ liệu cá nhân).
  - Tài khoản Oracle quyền thay đổi lược đồ ở môi trường thử, đầu mối DBA duyệt baseline Flyway trên production.
  - Danh sách hành trình người dùng chính của hệ cũ để dựng kịch bản k6.

## 3. Phạm vi công việc

- Thêm Flyway vào repo hệ cũ có thay đổi lược đồ: baseline từ lược đồ hiện tại (không tạo lại bảng), migration mới theo expand/contract, chạy bằng Job trước khi triển khai; chặn trong pipeline mọi DDL ngoài Flyway.
- Thêm kiểm tra định kỳ đếm bảng mới trong `VOFFICE` (chỉ số TL-04) và báo khi khác 0.
- Viết kịch bản k6 cho các hành trình chính của hệ cũ, tải 500 VU tại APISIX, hồ sơ PERF-01 rút gọn (tải thường) và PERF-04 rút gọn (đột biến ngày hạn).
- Chạy thử tải, thu p95, tỷ lệ lỗi, CPU, kết nối Oracle; tìm điểm nghẽn và đề xuất sửa (sửa nhỏ trong phạm vi task, sửa lớn mở task riêng).
- Lưu kịch bản và kết quả vào kho kết quả thử tải theo quy ước TL-04.

## 4. Ngoài phạm vi

- Thử tải nền mới (QA-08); tối ưu lớn hệ cũ ngoài điểm nghẽn rõ ràng.

## 5. Tệp được phép sửa

- Cấu hình Flyway và migration trong các repo hệ cũ (đường dẫn do người dùng cấp)
- `infra/perf/legacy/**` (kịch bản k6 cho hệ cũ)
- `ops/runbooks/thu-tai-he-cu.md`

## 6. Tiêu chí hoàn thành

- Pipeline của repo có lược đồ chạy Flyway `validate` xanh; một yêu cầu gộp có DDL ngoài Flyway bị chặn.
- k6 500 VU tại APISIX: p95 < 3 s, lỗi < 0,1% trong 1 giờ tải thường; đột biến phục hồi p95 trong 3 phút.
- Số bảng mới trong `VOFFICE` = 0 trong tuần đo.
- Báo cáo kèm số liệu và đồ thị; báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
