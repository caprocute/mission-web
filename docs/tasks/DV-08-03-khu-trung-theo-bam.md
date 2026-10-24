# DV-08-03 · QT-14/SVC-08 Khử trùng theo băm và phát hiện minh chứng trùng tức thời phát EVT-20

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-08-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | DV-08-02 |
| Truy vết | QT-07 (SCR-0701 cột "Giải trình dùng lại", SCR-0703 cờ trùng), QT-14 bước 1 (dấu hiệu 4) → `POST /api/v1/files/uploads/{uploadId}/complete` (`completeFileUpload`, trường `dedup`) → SVC-08 → `evidence.dedup_index` → EVT-20; BR-07-03; MSG-07-01, MSG-07-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hai lần tải cùng nội dung trong một tenant trỏ về cùng một đối tượng vật lý để tiết kiệm kho, và khi cùng băm xuất hiện ở minh chứng của người khác, hệ thống báo ngay cho người nộp (phải giải trình) và phát tín hiệu dấu hiệu bất thường "minh chứng trùng" mà không chờ job đêm. Khi xong, khử trùng không bao giờ vượt ranh giới tenant và không làm lộ việc một tenant khác có tệp nào.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema evidence (SVC-08)" (dòng 3395: khoá theo nội dung, khử trùng chỉ trong tenant) và "Bảng evidence.dedup_index" (dòng 3437).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 BR-07-03 (dòng 1923), MSG-07-01 (dòng 1986), MSG-07-11 (dòng 2076).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14 bảng bước (dòng 1175–1176: phát hiện tức thời dấu hiệu 4).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` bảng tám dấu hiệu (dòng 3116, "Minh chứng trùng hoặc dùng lại").
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` bảng EVT (EVT-20 `analytics.anomaly.detected.v1`).
- Hợp đồng: `contracts/openapi/modules/products.yaml` schema kết quả `completeFileUpload`; lược đồ Avro EVT-20 trong `contracts/events/avro/`.

## 3. Phạm vi công việc

- Khi hoàn tất tải lên, tra `dedup_index` theo (`tenant_id`, `sha256`): nếu có, không ghi đối tượng vật lý mới mà tạo `object_version` trỏ `object_key` cũ và tăng `ref_count`; nếu chưa, chèn dòng mới; hai yêu cầu đồng thời cùng băm không tạo hai đối tượng (khoá chính và `INSERT ... ON CONFLICT`).
- Duy trì `owners_sample` tối đa 16 người sở hữu đầu tiên (TL-03).
- Trả trường `dedup` trong phản hồi `completeFileUpload` gồm cờ trùng và tham chiếu tối thiểu đủ để MOD-03 hiển thị MSG-07-01 (mã nhiệm vụ, người, ngày lấy ở MOD-03; SVC-08 chỉ trả đối tượng gốc, suy luận).
- Khi băm trùng thuộc người sở hữu khác, phát EVT-20 dấu hiệu 4 qua outbox với khoá chống trùng theo dấu hiệu và đối tượng để MOD-07 không tạo cảnh báo lặp (QT-14 bước 2).
- Tệp C3 bỏ qua khử trùng hoàn toàn.
- Không truy vấn chéo tenant ở bất kỳ đường nào; kiểm thử chứng minh tenant B tải cùng tệp với tenant A vẫn nhận `dedup` rỗng.
- Điểm lệch cần lưu ý: baseline ghi EVT-20 do SVC-15 phát, còn TL-02b và TL-03 mô tả phát hiện tức thời khi tải lên. Chọn: SVC-08 phát EVT-20 cho riêng dấu hiệu 4 (tức thời), SVC-15 vẫn phát các dấu hiệu khác; ghi vào wiki trang 14 và báo cáo để người phụ trách xác nhận (có thể đổi thành SVC-08 phát sự kiện kỹ thuật cho SVC-15 phát lại EVT-20).

## 4. Ngoài phạm vi

- Ô "Giải trình dùng lại" và cờ trùng ở MOD-03 (CORE-03); so khớp gần giống bằng MinHash trên văn bản OCR (SVC-15, DV-15).

## 5. Tệp được phép sửa

- `backend/services/evidence-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/evidence-service -am verify` chạy xanh gồm Testcontainers: cùng tệp hai người trong một tenant cho một đối tượng vật lý, `ref_count` = 2, đúng một EVT-20; khác tenant thì không trùng; 20 yêu cầu đồng thời cùng băm cho một dòng `dedup_index`.
- Kiểm thử hợp đồng lược đồ Avro EVT-20 tương thích.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
