# DV-08-05 · QT-07/SCR-0702 Gọi OCR qua ai-gateway cho minh chứng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-08-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | DV-08-02, DV-14-05; bổ sung DV-08-04 (chỉ OCR tệp đã quét sạch, suy luận) |
| Truy vết | QT-07 → SCR-0702 → `POST /api/v1/ai/evidence/extract` (`extractEvidenceFields`, owner SVC-14) → SVC-08 (cấp nội dung tệp, lưu văn bản OCR) → schema `evidence`; BR-07-09; MSG-07-06, MSG-07-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Minh chứng chụp hoặc tải lên dạng ảnh, PDF được đọc chữ bằng dịch vụ OCR qua ai-gateway để gợi ý điền sẵn trường và để phục vụ phát hiện minh chứng gần giống. Khi xong, evidence-service cấp cho ai-gateway quyền đọc nội dung tệp đã quét sạch một cách có kiểm soát, kích hoạt OCR nền sau khi quét, lưu văn bản trích được, và tuyệt đối không gửi tệp C3.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Kiến trúc cổng mô hình" (dòng 3088–3094: OCR bất đồng bộ khi nhận sự kiện lưu đối tượng), bảng dấu hiệu (dòng 3116), mục "Chính sách theo loại dữ liệu" (dòng 2337: AI không xử lý C3).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 SCR-0702 (chụp minh chứng có OCR, MSG-07-06..MSG-07-08, dòng 2026–2028), BR-07-09 (dòng 1929).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` `/ai/evidence/extract` (`extractEvidenceFields`); `contracts/openapi/modules/products.yaml` nhóm "Tệp".

## 3. Phạm vi công việc

- Cung cấp cho ai-gateway một cách đọc nội dung tệp theo `objectId`: URL ký trước 5 phút cấp qua API nội bộ có xác thực dịch vụ (mTLS, tài khoản dịch vụ SVC-14), chỉ khi phiên bản đã `SACH` và không phải C3.
- Khi nhận `evidence.object.scanned.v1` kết luận sạch cho tệp thuộc loại cần OCR (ảnh, PDF quét), xếp việc OCR nền: gọi ai-gateway, nhận văn bản trích, lưu cùng phiên bản đối tượng trong schema `evidence`.
- Lỗi OCR hoặc ảnh không đủ nét không làm hỏng minh chứng; ghi trạng thái để giao diện báo MSG-07-06 khi người dùng gọi `extractEvidenceFields` đồng bộ.
- Chặn ở hai lớp với tệp C3: không xếp việc OCR và từ chối cấp URL cho ai-gateway.
- Kiểm thử với ai-gateway giả lập: tệp sạch có văn bản lưu lại; tệp nhiễm không được OCR; tệp C3 bị từ chối; lỗi ai-gateway được thử lại có giới hạn.
- Điểm lệch cần lưu ý: TL-01 ghi văn bản OCR "lưu trong schema `evidence`" nhưng từ điển TL-03 không có bảng hay cột cho văn bản OCR; đề xuất thêm cột hoặc bảng phụ qua migration, ghi "(suy luận)" và báo người phụ trách CSDL trước khi gộp. Sự kiện kích hoạt ở TL-01 là `sys.evidence.object.stored.v1`; task này kích hoạt sau quét sạch để không OCR tệp nhiễm (suy luận).

## 4. Ngoài phạm vi

- Mô hình OCR, guardrail và API `extractEvidenceFields` ở ai-gateway (DV-14-05); ghi ô AI điền vào minh chứng ở MOD-03 (CORE-03); MinHash ở SVC-15.

## 5. Tệp được phép sửa

- `backend/services/evidence-service/**`
- `deploy/charts/evidence-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/evidence-service -am verify` chạy xanh gồm bốn ca kiểm thử ở mục 3 (WireMock cho ai-gateway, Testcontainers cho PostgreSQL và Kafka).
- Kiểm thử chứng minh không có đường nào gửi tệp C3 tới ai-gateway.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
