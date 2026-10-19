# DV-14-05 · SVC-14/SCR-0702 OCR minh chứng

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-14-01; DV-14-03 (rào chắn đầu vào cho nội dung tài liệu) nên gộp trước (bổ sung) |
| Truy vết | QT-07 (SCR-0701, SCR-0702 "Đọc văn bản", MSG-07-06, MSG-07-08), QT-14 dấu hiệu 4 (OCR ≥ 95%) → API `POST /api/v1/ai/evidence/extract` → SVC-14 → bảng `ai.suggestion_log`; kết quả văn bản cho SVC-08 (`evidence`) → topic `sys.evidence.object.stored.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp OCR minh chứng qua dịch vụ OCR của Viettel AI: người nộp chụp hoặc tải văn bản, hệ thống đọc số ký hiệu, ngày ban hành, trích yếu và gợi ý nhiệm vụ đang mở khớp nhất; đồng thời trả văn bản OCR cho evidence-service lưu để tìm kiếm và phát hiện minh chứng trùng nội dung. Khi xong, SCR-0702 trên di động và SCR-0701 trên web dùng được nút "Đọc văn bản" với API thật.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Kiến trúc cổng mô hình" (L3088, đoạn OCR chạy bất đồng bộ khi nhận `sys.evidence.object.stored.v1`, kết quả lưu trong schema `evidence`), "Tám dấu hiệu bất thường" (L3107, minh chứng trùng nội dung).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 (L1851), SCR-0702 (L1992), MSG-07-06, MSG-07-08.
- `contracts/openapi/modules/platform.yaml` `extractEvidenceFields` và lược đồ `EvidenceExtractRequest`.
- Bản trích `extract/tl01-thanh-phan.md` mục C13.

## 3. Phạm vi công việc

- Viết client dịch vụ OCR Viettel AI (tiếng Việt, PDF và ảnh) có thời hạn, giới hạn đồng thời, Resilience4j tương đương; ảnh không đủ nét trả 422 theo MSG-07-06.
- Hiện thực `extractEvidenceFields`: lấy tệp đã tải lên qua evidence-service bằng token `ai_readonly`, OCR, trích số ký hiệu, ngày ban hành, trích yếu bằng luật và mô hình, tìm nhiệm vụ đang mở của người nộp qua API lõi, trả `matchedTask` khi độ khớp ≥ 0,5 (dưới ngưỡng để rỗng theo MSG-07-08); ghi `suggestion_log`.
- Không đọc tệp minh chứng mật (C3) và không gửi C3 tới OCR; tệp C2 chỉ xử lý khi người dùng có quyền và không lưu nội dung C2 trong nhật ký.
- Cung cấp chức năng OCR cho evidence-service gọi khi nhận `sys.evidence.object.stored.v1` (DV-08-05 là bên gọi và bên lưu kết quả).
- Điểm lệch cần lưu ý: hợp đồng chỉ có `extractEvidenceFields` cho vai trò `member`, `ai_readonly`, không có thao tác OCR cho tài khoản dịch vụ của SVC-08; task hiện thực cổng nội bộ chỉ nhận mTLS từ evidence-service (suy luận) và đề xuất mở task sửa hợp đồng nếu cần công bố, ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Lưu văn bản OCR và phát hiện trùng nội dung ở evidence-service (DV-08-05, DV-08-03); xác nhận và nộp minh chứng (CORE-03-05).

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && pytest` xanh, gồm kiểm thử với OCR giả lập trả văn bản mẫu và với bộ ảnh mẫu (rõ, mờ): ảnh mờ trả 422, văn bản mẫu trích đúng số ký hiệu, ngày, trích yếu.
- Kiểm thử hợp đồng phản hồi `extractEvidenceFields` khớp `platform.yaml`; độ khớp dưới 0,5 trả `matchedTask` rỗng.
- Báo cáo ghi rõ đã hay chưa thử với dịch vụ OCR Viettel thật.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
