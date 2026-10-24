# VH-09 · TL-01 ma trận 110 tiêu chí Chấm lại checklist và bảng chỉ số chuyển đổi mỗi quý

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c, sau đó lặp mỗi quý tới hết đợt 3 |
| Luồng đề xuất | B11 |
| Cỡ việc | S (≤ nửa ngày agent; mỗi lần chấm quý sau đó làm theo quy trình) |
| Phụ thuộc | VH-03 |
| Truy vết | TL-01 chương "Ma trận truy vết 110 tiêu chí" (trục A..L), "Chỉ số theo dõi chuyển đổi"; TL-04 mục 12.8 "Bảng chỉ số theo dõi và cách đo", chương 13 → điểm đáp ứng tổng hợp, vấn đề nghiêm trọng còn mở, phiên đồng thời đạt SLO |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lập quy trình và công cụ chấm lại 110 tiêu chí của bản đánh giá mỗi quý theo ma trận truy vết, cùng bảng chỉ số chuyển đổi đo tự động khi có thể. Khi xong, mỗi quý có một bản chấm ghi điểm từng tiêu chí kèm bằng chứng, điểm tổng hợp so với mốc (≥ 40 sau đợt 0, ≥ 60 sau đợt 2, ≥ 75 sau đợt 3), và bảng chỉ số đối chiếu với mục tiêu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Ma trận truy vết 110 tiêu chí" (dòng 3186–3386), "Chỉ số theo dõi chuyển đổi" (dòng 3174–3184).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 12.8 "Bảng chỉ số theo dõi và cách đo" (dòng 3980–3994), chương 13 "Ma trận truy vết" (dòng 3996–4129).
- `docs/KE-HOACH-TONG-THE-HE-THONG.md`, `docs/tasks/danh-muc-he-thong.tsv` (task nào đóng tiêu chí nào).

## 3. Phạm vi công việc

- Tệp dữ liệu `docs/danh-gia-quy/tieu-chi.yaml` (vị trí suy luận): 110 tiêu chí theo trục, mỗi tiêu chí có mã, mô tả ngắn, task đáp ứng, cách kiểm bằng chứng.
- Script `docs/danh-gia-quy/cham.py`: đọc bản chấm quý `docs/danh-gia-quy/<năm>-Q<n>.yaml` (điểm, bằng chứng, người chấm), tính điểm tổng hợp theo thang của bản đánh giá, so với quý trước và mốc đợt, xuất báo cáo Markdown.
- Bảng chỉ số chuyển đổi chín dòng của TL-04 mục 12.8 với nguồn đo từng dòng (gitleaks, `trace_completeness_ratio`, Resilience4j, nhãn `vt-pipeline`, PERF-03 và Faro, Kyverno `vt-require-ha`, nhãn vấn đề nghiêm trọng); dòng đo tự động lấy từ Prometheus bằng truy vấn ghi trong tệp, dòng thủ công ghi người đo.
- Quy trình chấm quý `docs/danh-gia-quy/quy-trinh.md`: thời điểm (sau rà soát năng lực OPS-25), người chấm độc lập với người làm, cách xử lý tiêu chí tụt điểm, đưa kết quả vào CAB (VH-03).
- Chấm lần đầu với hiện trạng để kiểm công cụ (điểm cơ sở 17,4 theo TL-01).

## 4. Ngoài phạm vi

- Dashboard Grafana "Tiến độ đợt" tự động hoàn chỉnh (đề xuất mở rộng sau, suy luận); sửa tiêu chí chưa đạt (task theo phân hệ).

## 5. Tệp được phép sửa

- `docs/danh-gia-quy/**`.

## 6. Tiêu chí hoàn thành

- `python docs/danh-gia-quy/cham.py docs/danh-gia-quy/<năm>-Q<n>.yaml` chạy và in điểm tổng hợp; kiểm thử nhỏ `pytest docs/danh-gia-quy` xanh (đủ 110 tiêu chí, không trùng mã).
- Bản chấm cơ sở cho ra điểm khớp 17,4 (hoặc giải thích chênh lệch).

## 7. Báo cáo

Theo AGENTS.md mục 6.
