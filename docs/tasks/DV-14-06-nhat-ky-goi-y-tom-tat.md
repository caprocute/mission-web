# DV-14-06 · QT-17, QT-16 Nhật ký gợi ý (chấp nhận, sửa, bỏ qua, nguồn) và tóm tắt tuần cho lãnh đạo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-14-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-14-04; DV-15-04 (analytics-api cung cấp số liệu cho tóm tắt tuần) nên gộp trước (bổ sung) |
| Truy vết | QT-17 (BR-17-08, BR-17-09), QT-16 (BR-16-06, SCR-1601 trường 8 "Tóm tắt tuần") → API `POST /api/v1/ai/suggestions/{id}/decision`, `GET /api/v1/ai/suggestions/{id}/sources`, `GET /api/v1/ai/suggestions` → SVC-14 → bảng `ai.suggestion_log` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khép vòng đo chất lượng AI: ghi quyết định của người dùng với từng gợi ý (chấp nhận, sửa với các trường đã sửa, bỏ qua kèm lý do), trả "dữ liệu đã dùng" cho từng gợi ý, và chạy job tóm tắt tuần sáng thứ Hai cho lãnh đạo hiển thị trên bảng điều hành. Khi xong, tỷ lệ chấp nhận gợi ý đo được theo ca dùng (điều kiện mở rộng tầng 2 là trên 70%) và tóm tắt tuần luôn có nhãn AI, nguồn, thời điểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 "Quy tắc nghiệp vụ" (L1864, BR-17-08, BR-17-09), QT-16 "Quy tắc nghiệp vụ" (L1655, BR-16-06), SCR-1601 (L1678, trường 8 và "Xem dữ liệu đã dùng").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Bốn tầng ứng dụng AI" (L3096, ngưỡng chấp nhận 70%).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `ai.suggestion_log` (L3984).
- `contracts/openapi/modules/platform.yaml` `decideAiSuggestion`, `listAiSuggestionSources`, `listAiSuggestions`.
- Bản trích `extract/tl02b-may-chu.md` QT-17 (BE-Q17-05, BE-Q17-07).

## 3. Phạm vi công việc

- Hiện thực `decideAiSuggestion`: ghi `user_action` (`CHAP_NHAN`, `SUA`, `BO_QUA`), `edited_fields[]`, `accepted_by`, lý do khi bỏ qua; chỉ người nhận gợi ý được ghi; `Idempotency-Key`; không ghi gì vào schema nghiệp vụ (cờ `ai_assisted`, `content_source` do MOD-01 ghi khi người dùng lưu).
- Hiện thực `listAiSuggestionSources`: trả các đoạn tài liệu, truy vấn và số liệu đã dùng (`sources_json`, `chunk_ids`) mà người xem được đọc; đoạn ngoài quyền trả dạng che.
- Hiện thực `listAiSuggestions` lọc theo loại (ví dụ `TOM_TAT_TUAN`), đơn vị, kỳ, phân trang con trỏ.
- Viết job tóm tắt tuần chạy sáng thứ Hai theo múi giờ Việt Nam: với mỗi lãnh đạo, lấy số liệu tổng hợp trong phạm vi của họ qua analytics-api (danh tính và phạm vi theo cách chốt, suy luận: token exchange theo người nhận), sinh 3–5 ý, ghi `suggestion_log` `kind = TOM_TAT_TUAN` với nguồn và thời điểm dữ liệu (BR-16-06).
- Xuất metric tỷ lệ chấp nhận, sửa, bỏ qua theo `use_case` và theo tenant; ghi rõ công thức vào wiki trang AI hoặc trang 14.
- Điểm lệch cần lưu ý: TL-03 có cả `user_action` và `decision` trùng nghĩa; task dùng `user_action` theo CHECK và để `decision` theo cách đã chốt ở DV-14-01, ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Hiển thị tóm tắt trên SCR-1601 (WEB-047); số liệu bảng điều hành (DV-15-04).

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && pytest` xanh, gồm kiểm thử hợp đồng ba operation với `platform.yaml`; người khác ghi quyết định cho gợi ý không phải của mình nhận 403.
- Job tóm tắt tuần chạy với analytics-api giả lập sinh đúng một bản cho mỗi lãnh đạo, chạy lại trong cùng tuần không sinh trùng; mỗi ý có ít nhất một nguồn.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
