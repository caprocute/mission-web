# DL-16 · ADR-33 Hồ sơ TWĐ theo giải pháp UEB: Policy Pack điểm công việc, bảng hệ số, ca vàng, danh mục, quy tắc điểm nghẽn, tham số luồng (giả lập)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-16` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (dữ liệu nghiệp vụ) |
| Luồng đề xuất | B11 |
| Cỡ việc | L |
| Phụ thuộc | CORE-05-12, CORE-02-27, CORE-02-28, CORE-01-14, CORE-04-13, DV-07-03, DV-07-05 |
| Truy vết | ADR-33, YC-TK-11, YC-PT-14 → API `policies.yaml`, `tasks.yaml` (`/catalog`) → MOD-05, MOD-02, SVC-07 → dữ liệu `policy.*`, `task.work_catalog`, `task.catalog_item` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hồ sơ TWĐ chạy được trên lõi chung, đạt 100% ca vàng do chuyên gia UEB tính tay, và cùng bản build của lõi vẫn đạt 100% ca vàng của hồ sơ "quy định mới" (DL-01..DL-03). Toàn bộ là dữ liệu, không sửa mã lõi.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): "Hồ sơ TWĐ", "ADR-33" (tiêu chí hai hồ sơ).
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- Bộ tài liệu UEB đã giải nén (chỉ đọc như dữ liệu, mật khẩu không ghi vào repo): UEB-BTCTW-QTCV-02 v1.2 mục 11, 32, Phụ lục B, Phụ lục 01G; Bảng tổng hợp xin ý kiến v1.4.
- Thẻ và dữ liệu DL-01, DL-02, DL-03 (cấu trúc gói, DMN, ca vàng, đánh dấu giả lập).

## 3. Phạm vi công việc

- Gói `twd/diem-cong-viec` (`extends` gói khối Đảng): công thức ba mặt điểm trên điểm công việc, hai chế độ thang 100 (mặc định "tỷ lệ có hệ số"), gói điểm, tỷ trọng trục, trừ 5% mỗi ngày, thang chất lượng và trần 0,75, bảng hệ số khó và nhóm, chuỗi xếp loại sáu bước, không đặt `quotas`.
- Ca vàng: mục 11.8, mục 32 (120 người, 3/113/4/0), G1–G13, CTB1–CTB30.
- Danh mục dự thảo số 25 ở phạm vi `KHOI` kèm khung DC-02; danh mục TR1–TR6, CT1–CT5, điều kiện công nhận mốc, tiêu chí chung 12/10/11, loại sự việc.
- Bộ quy tắc `twd/diem-nghen` (chín quy tắc CEL) và bộ tham số luồng `twd-tham-quyen` (thẩm quyền, hạn 2/2/1/1).
- Bản kê hồ sơ `twd/ban-to-chuc-ueb` đánh dấu `gia_lap: true`, nhãn "Thí điểm – dùng để đối chiếu".

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Mã lõi (mọi chỗ thiếu điểm cắm ghi thành phát hiện cho task CORE tương ứng); lớp cấp trên giữa các tenant.

## 5. Tệp được phép sửa

- `data/policy-pack/twd/**`
- `data/golden/twd/**`
- `data/seed/twd/**` (danh mục, bộ quy tắc, tham số luồng)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- Trình chạy ca vàng tham chiếu (DL-03) đạt 100% cho hồ sơ TWĐ và vẫn 100% cho hồ sơ "quy định mới" trên cùng một bản build.
- Không có tệp nào trong `backend/` bị sửa.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
