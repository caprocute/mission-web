# DV-15-09 · QT-14/BR-06-12 Dấu hiệu bất thường 6, 8, tự đóng khi hết dấu hiệu và tỷ lệ chấp nhận khách quan bất thường

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-07; DV-09-01 (dữ liệu sổ cái cho dấu hiệu 6) (bổ sung) |
| Truy vết | QT-14 bước 1, 10 (BR-14-01, BR-14-06, BR-14-07), QT-06 (BR-06-12) → SCR-1401 → SVC-15 job phát hiện → ClickHouse `fact_task`, `fact_score`, `anomaly_out` → EVT-20 → MOD-07 `oversight.anomaly_alert` trạng thái `TU_DONG_DONG` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện bộ luật bằng dấu hiệu 6 (sửa dữ liệu khi đã biết kết quả), dấu hiệu 8 (miễn trừ "khách quan" lặp lại) và luật tỷ lệ chấp nhận khách quan bất thường của người duyệt theo BR-06-12, đồng thời hiện thực tín hiệu "hết dấu hiệu" để MOD-07 tự đóng cảnh báo khi job chạy lại không còn phát hiện. Khi xong, tám dấu hiệu (trừ dấu hiệu 4 tức thời) chạy đủ trong job đêm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14 "Thông tin chung" (L1131, dòng 6, 8), "Mô tả các bước" (L1171, bước 10 tự đóng), "Trạng thái đối tượng" (L1186: `MOI` → `TU_DONG_DONG`), "Quy tắc nghiệp vụ" (L1202).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 BR-06-12 (L1739) và phần liên kết thiết kế (L1849).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng sự kiện EVT-20 (L4460, L4468).
- `contracts/openapi/modules/oversight.yaml` `listAnomalyAlerts`, `getAnomalyAlert` (trạng thái cảnh báo) để hiểu phía tiêu thụ.
- Bản trích `extract/tl02b-may-chu.md` QT-14 (BE-Q14-08), `extract/tl02a-may-chu.md` QT-06 (BE-06-9).

## 3. Phạm vi công việc

- Dấu hiệu 6: có thay đổi mục tiêu, kế hoạch, nghiệm thu sau thời điểm tính điểm tạm hoặc sau khoá kỳ (qua mở lại); nguồn là thay đổi gắn cờ hậu kiểm từ CDC và mốc kỳ trong `dim_period`; luôn mức Cao, xếp hạng theo mức ảnh hưởng điểm.
- Dấu hiệu 8: một người nghiệm thu áp miễn trừ chậm khách quan ≥ 3 lần trong 2 quý, hoặc tỷ lệ miễn trừ vượt ngưỡng khối; đối tượng là người nghiệm thu (mã giả danh).
- Luật BR-06-12: tỷ lệ chấp nhận nguyên nhân khách quan của một người duyệt cao bất thường so với khối; phát EVT-20 với `signalCode` riêng và giải thích; nếu trùng ý nghĩa với dấu hiệu 8, gộp vào dấu hiệu 8 và ghi lựa chọn trong wiki (suy luận).
- Tự đóng: với mỗi cảnh báo đang mở mà lần chạy mới không còn phát hiện, job phát tín hiệu "hết dấu hiệu" để MOD-07 chuyển `MOI` → `TU_DONG_DONG`; cơ chế truyền tín hiệu chưa được TL mô tả (xem điểm lệch).
- Dấu hiệu 6 không thay thế cảnh báo phá chuỗi sổ cái (BR-14-07, do ledger-service phát); job không đọc dữ liệu sổ cái ngoài mã tham chiếu.
- Điểm lệch cần lưu ý: TL-03 chỉ định nghĩa EVT-20 cho phát hiện, không có trường hay sự kiện cho "hết dấu hiệu"; task đề xuất phát lại EVT-20 cùng `alertKey` với `severity` đặc biệt hoặc trường bổ sung trong lược đồ (cần duyệt thay đổi lược đồ ở NEN-007), không tự đặt mã EVT mới.

## 4. Ngoài phạm vi

- Consumer EVT-20 và máy trạng thái `anomaly_alert` (CORE-07); dấu hiệu 4 tức thời (evidence-service); ngưỡng phát hiện trên màn hình (CORE-08, WEB).

## 5. Tệp được phép sửa

- `services-py/anomaly-job/**`
- `data/golden/anomaly/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-py/anomaly-job && ruff check . && mypy app && pytest` xanh.
- Kiểm thử tích hợp Testcontainers ClickHouse + Kafka: ca dương, âm cho dấu hiệu 6, 8 và BR-06-12 khớp kỳ vọng; ca "minh chứng đã thay" ở lần chạy hai sinh tín hiệu tự đóng cho đúng `alertKey` cũ.
- Mọi cảnh báo dấu hiệu 6 có mức Cao.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
