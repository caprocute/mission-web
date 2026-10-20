# DV-15-03 · QT-16/SCR-1601 Cube: chỉ số chuẩn của bảng điều hành, chiều đơn vị, kỳ, khối, luồng, bộ lọc an ninh

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | L |
| Phụ thuộc | DV-15-01 |
| Truy vết | QT-16 (BR-16-01, BR-16-02, BR-16-05, BR-16-08) → SCR-1601, SCR-1602 → Cube (lớp ngữ nghĩa SVC-15) → bảng ClickHouse `fact_*`, `dim_*` → ADR-14 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Xây lớp ngữ nghĩa Cube cho chỉ số chuẩn hoá của nền tảng: bốn chỉ số bảng điều hành và danh mục chỉ số cho trình dựng dashboard, chiều đơn vị, kỳ, tuần, khối đối tượng, luồng Đảng/Nhà nước, với bộ lọc an ninh theo tenant và phạm vi cây tổ chức lấy từ JWT và pre-aggregation làm mới mỗi 5 phút. Khi xong, mọi truy vấn chỉ đi qua chỉ số đã công bố (không SQL tự do) và bảng điều hành phản hồi dưới 1 giây.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-16 (L1611), "Quy tắc nghiệp vụ" (L1655, BR-16-01..08), SCR-1601 (L1678, bảng trường: `okr.tien_do_o`, `task.ty_le_dung_han`, `task.so_viec_cham`, `appraisal.cho_tham_dinh`, chiều `org_unit`, tuần), SCR-1602 (L1728, danh mục chỉ số).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Phân quyền phía đọc" (L4347: `queryRewrite`, pre-aggregation 5 phút).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-15 analytics" (L1194–1204).
- `TL-04-trien-khai-van-hanh.md` PERF-24 (300 truy vấn dashboard/giây, p95 ≤ 1 giây); bản trích `extract/tl02b-may-chu.md` QT-16 (BE-Q16-02).

## 3. Phạm vi công việc

- Viết mô hình Cube trong `analytics/cube/model/`: cube cho nhiệm vụ, tiến độ, OKR/check-in, minh chứng, điểm, xếp loại; measures đúng tên TL-02b (`okr.tien_do_o`, `task.ty_le_dung_han`, `task.so_viec_cham`, `appraisal.cho_tham_dinh`) và các chỉ số của SCR-1602 (điểm KPI trung bình, phân bố mức xếp loại, số minh chứng chờ nghiệm thu…).
- Chiều: đơn vị (SCD2, có đường dẫn cây `org_path`), kỳ, tuần, khối đối tượng, luồng; mỗi measure và dimension có mô tả tiếng Việt và phiên bản lớp ngữ nghĩa để `getSemanticModel` trả về.
- `queryRewrite` thêm bộ lọc `tenant_id` và tập đơn vị trong phạm vi lấy từ `securityContext` (tenant, `org_path` từ JWT); truy vấn thiếu ngữ cảnh bị từ chối.
- Chỉ tiêu KT-XH chỉ đưa vào measure khi `da_cong_bo` (BR-16-05); mọi phản hồi có thời điểm dữ liệu mới nhất để hiển thị độ trễ (BR-16-08).
- Pre-aggregation theo (tenant, đơn vị, kỳ) làm mới mỗi 5 phút; lưu trong ClickHouse.
- Viết bộ ca kiểm thử chỉ số với dữ liệu ClickHouse mẫu và giá trị kỳ vọng tính tay, lưu `data/golden/analytics-measures/`.

## 4. Ngoài phạm vi

- `analytics-api` proxy và đệm (DV-15-04); Superset nhúng (DV-15-05); triển khai Cube trên cụm (INF).

## 5. Tệp được phép sửa

- `analytics/cube/**`
- `data/golden/analytics-measures/**`
- `infra/dev/` (Cube trong profile phân tích, nếu chưa có)

## 6. Tiêu chí hoàn thành

- Kiểm thử tích hợp Cube + ClickHouse (Testcontainers hoặc Docker Compose cục bộ): mọi chỉ số trong bộ ca vàng trả đúng giá trị; người có `org_path` đơn vị A không nhận số của đơn vị ngoài nhánh A; thiếu `securityContext` bị từ chối.
- Truy vấn bảng điều hành dùng pre-aggregation (kiểm trong nhật ký Cube) và p95 ≤ 1 giây ở tải thử cục bộ (ghi số đo; PERF-24 đầy đủ ở QA-11).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
