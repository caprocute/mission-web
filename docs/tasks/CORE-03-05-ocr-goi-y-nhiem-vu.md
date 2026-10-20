# CORE-03-05 · BR-07-09 OCR và trích thông tin minh chứng, gợi ý nhiệm vụ khớp

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-03-03, DV-08-05 |
| Truy vết | QT-07 → SCR-0701, SCR-0702 → API `POST /api/v1/ai/evidence/extract` (`extractEvidenceFields`, SVC-14), `PUT /api/v1/evidences/{id}/versions/{v}` → MOD-03 → `product.evidence_version` (`ai_prefill`, `user_confirmed_at`, `doc_number`, `doc_date`, `doc_summary`), `ai.suggestion_log` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Phía MOD-03 nhận kết quả OCR của SVC-14 cho minh chứng vừa tải lên, điền sẵn số ký hiệu, ngày ban hành, trích yếu và gợi ý nhiệm vụ khớp, đồng thời giữ đúng nguyên tắc AI không quyết định: ô do AI điền chỉ có hiệu lực khi người nộp xác nhận, mọi ô giữ hoặc sửa được ghi vết (BR-07-09). Việc này rút ngắn thao tác nộp trên di động mà không làm sai lệch dữ liệu tính điểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: mục "Quy ước về nội dung do AI tạo" (dòng 255–259), QT-07 "Mô tả các bước" bước 4 (dòng 1887), BR-07-09 (dòng 1929), "SCR-0702. Nộp minh chứng trên di động" (dòng 1992, bảng trường dòng 2014, MSG-07-06..08).
- TL-03 DDL `product.evidence_version` (dòng 2430, CHECK `ai_prefill` ⇒ `user_confirmed_at`).
- `contracts/openapi/modules/platform.yaml`: `extractEvidenceFields`, `decideAiSuggestion`; `contracts/openapi/modules/products.yaml`: `updateEvidenceVersionDraft`, `submitEvidenceVersion`.
- Bản trích `tl02a §QT-07 BE-07-7`.

## 3. Phạm vi công việc

- Viết cổng gọi SVC-14 từ MOD-03 (hoặc tiếp nhận kết quả bất đồng bộ theo cách DV-08-05 chốt) với thời hạn ≤ 30 giây; ảnh không đủ nét trả MSG-07-06, độ khớp nhiệm vụ < 0,5 trả MSG-07-08 và không tự gán nhiệm vụ.
- Khi lưu nháp có ô AI điền: đặt `ai_prefill = true`, lưu danh sách ô AI điền; khi người nộp xác nhận, đặt `user_confirmed_at` và gửi `decideAiSuggestion` với danh sách ô giữ nguyên và ô đã sửa.
- Chặn nộp phiên bản có `ai_prefill` mà chưa xác nhận (lớp mã, bổ sung CHECK ở CSDL đã có từ CORE-03-01).
- Không gửi tệp có `has_e2ee = true` (C3) tới SVC-14 trong mọi trường hợp (TL-02a dòng 259); kiểm thử phủ trường hợp này.
- Ghi sổ cái qua outbox việc người nộp xác nhận ô AI (BR-07-09: ghi vết ô giữ/sửa).
- Kiểm thử với bản giả SVC-14: OCR thành công, ảnh mờ, không khớp nhiệm vụ, quá thời hạn.

## 4. Ngoài phạm vi

- Mô hình OCR và `ai.suggestion_log` phía SVC-14 (DV-14-xx, DV-08-05); giao diện viền tím (WEB, MOB).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh.
- Kiểm thử chứng minh: nộp phiên bản AI điền chưa xác nhận bị chặn; tệp C3 không bao giờ tạo lời gọi tới SVC-14; quá 30 giây trả lỗi có thể thử lại, phiên bản vẫn ở `NHAP`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
