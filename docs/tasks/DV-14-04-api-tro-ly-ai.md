# DV-14-04 · QT-17/SCR-1701 API trợ lý: soạn OKR, soát KR, trích nhiệm vụ, giải thích điểm, 202 khi hàng đợi đầy

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-14-04` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-14-03; DV-14-02 (truy xuất RAG) phải gộp trước (bổ sung) |
| Truy vết | QT-17 (BR-17-04..06, MSG-17-01..03), QT-05 bước 2 (SCR-0502), QT-02 (SCR-0202) → API `POST /api/v1/ai/assist/okr`, `POST /api/v1/ai/okr/review`, `GET /api/v1/ai/extractions`, `POST /api/v1/ai/suggestions`, `GET /api/v1/ai/suggestions/{id}` → SVC-14 → bảng `ai.suggestion_log`, `ai.doc_chunk` → nhận EVT-24 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực các API trợ lý tầng 1 và 2 theo hợp đồng: soạn mục tiêu và KR bám văn bản căn cứ, soát KR mơ hồ hoặc trùng KR đơn vị khác, tự trích nhiệm vụ từ văn bản chỉ đạo khi nhận EVT-24, giải thích điểm số và hỏi đáp quy định qua điểm vào chung; mọi gợi ý gắn nhãn AI, có nguồn, ghi `suggestion_log`, trả 202 khi hàng đợi đầy. Khi xong, SCR-1701, SCR-0202 và SCR-0502 chạy với API thật.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 (L1822–1941: bước, "Quy tắc nghiệp vụ" L1864, "Ngoại lệ và xử lý" L1878, SCR-1701 L1890 với MSG-17-01..03).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 SCR-0502 (L1536, trích nhiệm vụ ≤ 1 phút, MSG-05-10), QT-02 SCR-0202 (trợ lý soát KR).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Bốn tầng ứng dụng AI" (L3096).
- `contracts/openapi/modules/platform.yaml` operation `assistOkrDraft`, `reviewOkrKeyResults`, `getDocExtraction`, `requestAiSuggestion`, `getAiSuggestion` và lược đồ liên quan.
- `TL-04-trien-khai-van-hanh.md` PERF-26 (10 yêu cầu/giây, TTFT p95 ≤ 2 giây); bản trích `extract/tl02b-may-chu.md` QT-17 (BE-Q17-04).

## 3. Phạm vi công việc

- Hiện thực `assistOkrDraft`: lấy văn bản căn cứ, bản nháp O/KR và KR đơn vị khác qua API lõi bằng token `ai_readonly`, truy xuất RAG, sinh tối đa 3 mục tiêu (BR-17-06) với KR có chỉ tiêu đo được, đánh dấu KR trùng đơn vị khác; trọng số KR gợi ý cộng đúng 100%.
- Hiện thực `reviewOkrKeyResults`: luật soát KR mơ hồ ("tăng cường", "đẩy mạnh" không có chỉ tiêu) kết hợp mô hình (BR-17-05), trả gợi ý theo MSG-17-01.
- Viết consumer EVT-24 tự trích nhiệm vụ trong ≤ 1 phút, ghi mỗi nhiệm vụ một dòng `suggestion_log` (`use_case = GOI_Y_NHIEM_VU`), không đọc đoạn C3, chống lời nhắc độc hại trong văn bản đến qua rào chắn; hiện thực `getDocExtraction` với trạng thái `DANG_TRICH`, `KHONG_TRICH_DUOC` (MSG-05-10).
- Hiện thực `requestAiSuggestion` cho giải thích điểm số (đọc vết tính qua API lõi của chính người dùng) và hỏi đáp quy định; `getAiSuggestion` trả kết quả của yêu cầu 202.
- Mọi API: `Idempotency-Key` cho POST, 202 + job khi hàng đợi đầy (MSG-17-03), `blocked` khi rào chắn chặn (MSG-17-02), nhãn "do AI gợi ý" và nguồn trong mọi gợi ý; ai-gateway không ghi gì vào schema nghiệp vụ, người dùng tự ghi qua API lõi.
- Điểm lệch cần lưu ý: TL-02b (L1855–1862) ghi `POST /ai/suggestions/{id}/accept` và `.../dismiss`, hợp đồng gộp thành `POST /ai/suggestions/{id}/decision`; task và DV-14-06 theo hợp đồng (API-first).

## 4. Ngoài phạm vi

- Ghi nhận quyết định gợi ý và nguồn (DV-14-06); cờ `ai_assisted`, kiểm trọng số ở MOD-01 (CORE-01-13); màn hình xác nhận nhiệm vụ từ văn bản (CORE-02-16).
- `POST /ai/speech-to-text` (`transcribeSpeech`): chưa có hàng khung riêng, xem báo cáo của người lập kế hoạch.

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `data/golden/ai-assist/**` (bộ câu hỏi và đáp án kỳ vọng để kiểm hồi quy)

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && pytest` xanh, gồm kiểm thử hợp đồng so khớp phản hồi với `platform.yaml` cho năm operation, kiểm thử với mô hình giả (trả cố định) và với mô hình nhỏ dev.
- Kiểm thử chứng minh: không bao giờ trả quá 3 mục tiêu; gợi ý không có nguồn bị chặn; hàng đợi đầy trả 202 rồi `getAiSuggestion` trả kết quả; EVT-24 giao lại không sinh gợi ý trùng.
- Đo cục bộ thời gian tới token đầu tiên với mô hình dev và ghi vào báo cáo; PERF-26 đầy đủ chạy ở QA-11.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
