# CORE-07-10 · QT-14/SCR-1401 Cảnh báo bất thường: consumer EVT-20, chống trùng, gộp, hành động tự động, cờ chặn minh chứng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-01, DV-15-07 |
| Truy vết | QT-14 → SCR-1401 → MOD-07 → `oversight.anomaly_alert`, `oversight.alert_action` (tự động) → nhận EVT-20 (`analytics.anomaly.detected.v1`, từ SVC-15 và SVC-09) → phát EVT-21 (MSG-14-01); SVC-07 đọc cờ chặn (BR-09-02, BR-14-04) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

MOD-07 nhận mọi tín hiệu bất thường (tám dấu hiệu từ job đêm của SVC-15, minh chứng trùng tức thời, phá chuỗi sổ cái từ SVC-09), tạo cảnh báo không trùng, gộp thành cảnh báo cấp đơn vị khi quá nhiều, áp hành động tự động như cờ "tạm chưa tính điểm" cho minh chứng trùng mà SVC-07 phải đọc. Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm (MSG-14-02).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14: "Thông tin chung" (dòng 1131, bảng tám dấu hiệu), "Mô tả các bước" (dòng 1171), "Trạng thái đối tượng" (dòng 1186), "Quy tắc nghiệp vụ" (dòng 1202: BR-14-01, BR-14-04, BR-14-06, BR-14-07), "Ngoại lệ và xử lý" (dòng 1216: gộp cấp đơn vị, saga chốt kỳ hiển thị cảnh báo dấu hiệu 4 còn mở).
- TL-03 bảng `oversight.anomaly_alert` (dòng 3197), `oversight.alert_action` (dòng 3213).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 7 (EVT-20 do SVC-15 phát, MOD-07 và SVC-12 nhận).
- Bản trích `tl02b §QT-14 BE-Q14-01, 02`.

## 3. Phạm vi công việc

- Consumer EVT-20 có inbox: khử trùng theo `source_event_id` và theo khoá (tenant, dấu hiệu, đối tượng, kỳ); tạo `anomaly_alert` trạng thái `MOI` với `signal_code`, `severity`, `evidence_chain` chỉ chứa mã (không chứa C2, C3).
- Gộp: khi số cảnh báo cùng dấu hiệu trong một đơn vị vượt ngưỡng tham số thì tạo một cảnh báo cấp đơn vị, gắn các cảnh báo con (ngưỡng đọc từ `config.tenant_setting` qua cổng `api` của MOD-08).
- Hành động tự động: với `MINH_CHUNG_TRUNG`, ghi `alert_action` loại `TU_DONG` có cờ chặn còn hiệu lực; cung cấp truy vấn "minh chứng đang bị chặn" qua cổng đọc để SVC-07 loại khỏi cấu phần A (BR-09-02) cho tới khi xác nhận hợp lệ.
- Phá chuỗi sổ cái luôn tạo cảnh báo mức `CAO` (BR-14-07).
- Job chạy lại không còn dấu hiệu: cảnh báo `MOI` chuyển `TU_DONG_DONG` rồi `DONG`.
- Sau mỗi đợt job đêm, phát EVT-21 tổng hợp số cảnh báo mức Cao mới cho người xử lý trong phạm vi (MSG-14-01), không phát từng cảnh báo.
- Cung cấp cổng đọc "cảnh báo dấu hiệu 4 còn mở của kỳ" cho saga chốt kỳ (DV-06-06).

## 4. Ngoài phạm vi

- Job phát hiện (DV-15-07..09); phát hiện trùng tức thời ở SVC-08; xử lý cảnh báo của con người (CORE-07-11).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử: EVT-20 lặp chỉ tạo một cảnh báo; 100.000 EVT-20 một quý xử lý hết trong thời gian ghi vào báo cáo; minh chứng trùng bị chặn tới khi có `XAC_NHAN_HOP_LE`; payload và `evidence_chain` không chứa trường C2.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
