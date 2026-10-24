# DV-14-07 · SVC-14/SCR-0302 Chuyển giọng nói thành văn bản (`transcribeSpeech`)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-14-07` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-14-01 (khung ai-gateway, đổi token sang `ai_readonly`); DV-14-03 (rào chắn đầu ra, `guardrail_event`) (bổ sung) |
| Truy vết | QT-03 bước 6 (nói để nhập vướng mắc), QT-18 → SCR-0302 "Nói để nhập", SCR-1803 "Nói" → API `POST /api/v1/ai/speech-to-text` (`transcribeSpeech`, `SpeechToTextRequest`, `SpeechToTextResult`, `413 AI-413-AUDIO-TOO-LARGE`) → SVC-14 → dịch vụ nhận dạng tiếng nói tiếng Việt (chưa chốt nhà cung cấp) → không lưu âm thanh; MSG-18-10 |
| Trạng thái | Chưa bắt đầu (nhà cung cấp nhận dạng tiếng nói cần người dùng chốt, xem mục 3) |

## 1. Mục tiêu

Trên di động, cán bộ và đảng viên cao tuổi có thể nói thay vì gõ ở ô vướng mắc khi check-in (SCR-0302) và ô ghi chú ở màn hình chế độ cao tuổi (SCR-1803); thiết bị ưu tiên nhận dạng tại chỗ, khi không có thì gọi `transcribeSpeech`. Task này hiện thực thao tác đó ở ai-gateway: nhận đoạn ghi âm ngắn, gửi bộ nhận dạng tiếng Việt chạy trong hạ tầng, trả văn bản để người dùng xác nhận trước khi lưu (MSG-18-10), không lưu âm thanh. Khi xong, MOB-04 và màn hình chế độ cao tuổi có đường dự phòng nhận dạng ở máy chủ.

## 2. Đầu vào bắt buộc đọc

- `contracts/openapi/modules/platform.yaml` `transcribeSpeech` (L2029–2077) và lược đồ `SpeechToTextRequest`, `SpeechToTextResult` (L3501–3515: ≤ 60 giây, ≤ 2 MB (suy luận), `language` mặc định `vi-VN`, `purpose`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 bước 6 (L1020), bảng trường SCR-0302 (L1131: "Giọng nói chuyển thành văn bản trên thiết bị hoặc qua SVC-14"), hành động "Nói để nhập" (L1137).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` SCR-1803 (trường ghi chú, hành động "Nói", MSG-18-10).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "SVC-14 ai-gateway" (L1184–1192), mục AI (OCR qua dịch vụ Viettel AI, L3094) làm tiền lệ dùng dịch vụ AI nội bộ.
- Thẻ `docs/tasks/DV-14-01-khung-ai-gateway.md`, `DV-14-03-rao-chan-ai.md`, `DV-14-05-ocr-minh-chung.md`, MOB-04.

## 3. Phạm vi công việc

- Hiện thực `transcribeSpeech` (Idempotency-Key, multipart): kiểm kiểu nội dung thật của tệp (`audio/webm`, `audio/ogg`, `audio/wav`, `audio/mp4`) chứ không tin phần khai báo; quá thời lượng hoặc kích thước trả `413 AI-413-AUDIO-TOO-LARGE`; roles `manager`, `member`, `ai_readonly`, phạm vi `B`.
- Viết cổng `SpeechRecognizer` với bản hiện thực gọi dịch vụ nhận dạng tiếng Việt chạy trong cell qua mạng nội bộ và bản giả lập cho kiểm thử; chuyển mã âm thanh về định dạng bộ nhận dạng cần (ffmpeg trong ảnh container, nêu lý do nếu là phụ thuộc mới). Điểm lệch cần lưu ý: TL không chỉ định bộ nhận dạng; ví dụ hợp đồng dùng tên mô hình kiểu Viettel AI; chọn dịch vụ ASR của Viettel AI theo tiền lệ OCR (suy luận), cấu hình bằng biến môi trường, chờ người dùng xác nhận và cấp quyền thử.
- Âm thanh chỉ giữ trong bộ nhớ trong suốt yêu cầu, không ghi đĩa, không ghi nhật ký, không gửi ra ngoài cell; văn bản trả về đi qua rào chắn đầu ra của DV-14-03 (cắt ≤ 1.000 ký tự, chặn nội dung lời nhắc độc hại) và ghi `guardrail_event` khi chặn; không lưu văn bản ở ai-gateway (người dùng xác nhận rồi mới lưu qua API nghiệp vụ).
- Đo và trả `durationMs`, `confidence`, `model`; giới hạn tốc độ theo người dùng (ví dụ 10 lần mỗi phút, suy luận) và hàng đợi có giới hạn, quá tải trả 503 kèm `Retry-After`; tôn trọng mức suy giảm 1 (tắt trợ lý AI) bằng cờ cấu hình.
- Metric độ trễ p95 theo độ dài đoạn ghi, tỷ lệ lỗi theo nhà cung cấp; kiểm thử với bộ mẫu âm thanh tiếng Việt ngắn tự thu (không dữ liệu cá nhân thật).

## 4. Ngoài phạm vi

- Nhận dạng trên thiết bị và giao diện xác nhận (MOB-04, WEB chế độ cao tuổi); triển khai máy chủ mô hình ASR trong cell (hạ tầng, ghi vào báo cáo nếu cần task riêng).

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `.env.example` (chỉ tên biến cấu hình bộ nhận dạng, không có giá trị thật)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && ruff check . && mypy app && pytest` xanh, gồm kiểm thử với bộ nhận dạng giả lập: tệp giả mạo kiểu bị 422, tệp quá dài bị 413, văn bản vượt 1.000 ký tự bị cắt, không có tệp nào được ghi ra đĩa trong lúc xử lý (kiểm bằng thư mục tạm chỉ đọc).
- Kiểm thử hợp đồng phản hồi `transcribeSpeech` khớp `platform.yaml`; `pip-audit` không lỗ hổng mức cao.
- Báo cáo ghi rõ đã hay chưa thử với bộ nhận dạng thật và số đo độ trễ; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
