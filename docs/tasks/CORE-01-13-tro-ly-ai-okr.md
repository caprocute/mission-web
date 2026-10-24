# CORE-01-13 · QT-17/SCR-1701 Trợ lý AI cho OKR: ghi nhận nội dung AI ở MOD-01

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-04, DV-14-04 |
| Truy vết | QT-17, QT-02 → SCR-1701, SCR-0202 → API `PUT /api/v1/okr/objectives/{id}`, `POST /okr/key-results`, `GET /okr/objectives/{id}/history` (phía lõi); `POST /api/v1/ai/okr/review`, `POST /ai/assist/okr`, `GET /ai/suggestions/{id}` (phía SVC-14) → MOD-01, SVC-14 → bảng `okr.objective` (`ai_assisted`, `ai_suggestion_id`), `okr.key_result` (`content_source`, `ai_assisted`, `ai_suggestion_id`), `ai.suggestion_log` → BR-02-11, BR-17-01, BR-17-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện phần của MOD-01 trong trợ lý AI soạn OKR: lõi chỉ nhận nội dung AI khi người soạn xác nhận, đánh dấu nguồn nội dung (`AI_ACCEPTED`, `AI_EDITED`) và cờ `ai_assisted` trên mục tiêu, KR, liên kết bản ghi với gợi ý trong `ai.suggestion_log`, và lưu danh sách ô giữ nguyên, ô đã sửa để người duyệt và sổ cái truy được. AI (vai trò `ai_readonly`) không bao giờ ghi trực tiếp vào schema `okr`.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 (L1822–1941), SCR-1701 (L1890), quy tắc BR-17-01..BR-17-09.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` mục "Quy ước về nội dung do AI tạo" (L255–259); QT-02 BR-02-11 và SCR-0202 (L928).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng okr.objective", "Bảng okr.key_result" (cột `ai_suggestion_id`, `content_source`, `ai_assisted`).
- `contracts/openapi/modules/platform.yaml`: `reviewOkrKeyResults`, `assistOkrDraft`, `/ai/suggestions/{id}`, `/ai/suggestions/{id}/decision`; `contracts/openapi/modules/okr.yaml`: `updateOkrObjective`, `createOkrKeyResult` (BR-02-11).

## 3. Phạm vi công việc

- Trong `updateOkrObjective` và `createOkrKeyResult`: KR hoặc mục tiêu mang `contentSource` khác `HUMAN` phải kèm `aiSuggestionId`; máy chủ kiểm gợi ý tồn tại, thuộc cùng tenant và người dùng, chưa bị bỏ, qua cổng đọc của SVC-14 (`GET /ai/suggestions/{id}`), ngoài giao dịch CSDL; sai thì trả 422.
- Đặt `ai_assisted = true` cho mục tiêu khi ít nhất một trường hoặc KR của nó có nguồn AI (BR-17-08); giữ `content_source` ở mức KR theo TL-03.
- Tính và lưu danh sách ô giữ nguyên và ô đã sửa so với gợi ý (so nội dung lưu với `output` của gợi ý), đưa vào lịch sử mục tiêu (`listOkrObjectiveHistory`) và vào payload EVT-01 khi duyệt để SVC-09 ghi sổ cái (cách đưa vào payload là suy luận, đối chiếu schema Avro của CORE-01-06).
- Bảo đảm nội dung AI không tự đổi trạng thái: trình duyệt vẫn chạy toàn bộ `OkrRuleChecker`, kể cả BR-02-05 đối với KR do AI soạn; AI đề xuất quá 3 mục tiêu vẫn bị BR-02-01 xử lý như nội dung người soạn.
- Kiểm thử kiến trúc: không có đường ghi nào vào schema `okr` dưới danh tính `ai_readonly`; các API đọc OKR trả cho `ai_readonly` không chứa trường C2 hay C3.
- Lỗi hoặc quá tải ở SVC-14 không chặn lưu nội dung `HUMAN` (L840).

## 4. Ngoài phạm vi

- ai-gateway, rào chắn, chỉ mục `doc_chunk`, ghi `ai.suggestion_log` và quyết định dùng/bỏ (DV-14-01..DV-14-04).
- Giao diện trợ lý trên web (task WEB).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `contracts/events/avro/okr/**` (chỉ thêm trường tuỳ chọn có giá trị mặc định vào schema EVT-01, tương thích BACKWARD)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, dùng stub HTTP cho SVC-14.
- Kiểm thử: lưu KR `AI_ACCEPTED` không kèm `aiSuggestionId` trả 422; kèm gợi ý của người khác trả 422; kèm gợi ý hợp lệ thì `ai_assisted` của mục tiêu là `true` và lịch sử ghi đúng danh sách ô đã sửa.
- Kiểm thử ArchUnit hoặc kiểm thử quyền chứng minh `ai_readonly` không gọi được thao tác ghi nào của `okr.yaml` (403).
- Schema Avro EVT-01 sau khi thêm trường vẫn qua kiểm tra tương thích BACKWARD; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
