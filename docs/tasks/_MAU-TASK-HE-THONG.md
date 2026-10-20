# <MÃ> · <Mã thiết kế liên quan> <Tên task>

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh <MÃ>` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 / 2 / 3 (theo `docs/KE-HOACH-TONG-THE-HE-THONG.md`) |
| Luồng đề xuất | B1..B11, W, DP |
| Cỡ việc | S (≤ nửa ngày agent) / M (≤ 1 ngày) / L (> 1 ngày, nên tách) |
| Phụ thuộc | Mã task phải gộp trước |
| Truy vết | QT-.. → API `/api/v1/..` → MOD-../SVC-.. → bảng `schema.bang` → EVT-.. (hoặc WI/PERF/DR/SEC/OPS với task hạ tầng) |
| Trạng thái | Chưa bắt đầu / Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) / Chờ người dùng chốt |

## 1. Mục tiêu
Một đoạn văn nói rõ kết quả nhìn thấy hoặc kiểm chứng được khi task xong và vì sao nó cần cho hệ thống.

## 2. Đầu vào bắt buộc đọc
- Mục TL, BS cụ thể (đường dẫn bản Markdown trong `/mnt/project-files/kien-truc-okr-kpi/src/` và số mục).
- Tệp hợp đồng OpenAPI/Avro liên quan, trang wiki liên quan.

## 3. Phạm vi công việc
- Việc cụ thể, kiểm chứng được, viết thành câu đầy đủ.

## 4. Ngoài phạm vi
- Việc không làm trong task này và task nào sẽ làm.

## 5. Tệp được phép sửa
- Đường dẫn hoặc glob.

## 6. Tiêu chí hoàn thành
- Lệnh build và kiểm thử cụ thể chạy xanh (theo quy định phân hệ).
- Tiêu chí nghiệp vụ hoặc phi chức năng đo được (BR, MSG, NFR, PERF…).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo
Theo AGENTS.md mục 6.
