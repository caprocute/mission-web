# DV-14-03 · SVC-14 Rào chắn đầu vào, đầu ra và `guardrail_event`; không gửi dữ liệu C3

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-14-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | L |
| Phụ thuộc | DV-14-01; DV-14-02 (đối chiếu nguồn với danh sách đã truy xuất) nên gộp trước (bổ sung) |
| Truy vết | QT-17 (BR-17-02, BR-17-04, BR-17-07, MSG-17-02) → API `/api/v1/ai/*` → SVC-14 → bảng `ai.guardrail_event`, `ai.suggestion_log` → TL-01 ch.17 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực rào chắn hai đầu cho mọi yêu cầu AI: đầu vào phát hiện tiêm lệnh trong câu hỏi và nội dung tài liệu, che C2 không cần thiết, chặn C3 tuyệt đối; đầu ra kiểm định dạng JSON theo lược đồ, trích nguồn phải thuộc danh sách đã truy xuất, không chứa dữ liệu ngoài phạm vi, không chấm điểm hay đề xuất mức xếp loại hay nhận xét cán bộ. Khi xong, mọi vi phạm bị chặn hoặc che và ghi `guardrail_event`, rào chắn đầu vào chạy ≤ 200 ms.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Nguyên tắc" và "Kiến trúc cổng mô hình" của chương "Trí tuệ nhân tạo" (L3084–3094), "Giới hạn tìm kiếm, phân tích và AI" (L2333).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 "Quy tắc nghiệp vụ" (L1864, BR-17-02, BR-17-04, BR-17-07), "Ngoại lệ và xử lý" (L1878), SCR-1701 (L1890, MSG-17-02).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `ai.guardrail_event` (L4005: `rule_code` `PII_OUT`, `PROMPT_INJECTION`, `OUT_OF_SCOPE`, `C2_REQUEST`).
- `contracts/openapi/modules/platform.yaml` trường `blocked` trong phản hồi AI; bản trích `extract/tl02b-may-chu.md` QT-17 (BE-Q17-03).

## 3. Phạm vi công việc

- Rào chắn đầu vào: bộ phát hiện tiêm lệnh (luật + mô hình phân loại nhỏ tự lưu trữ) chạy trên câu hỏi và trên từng đoạn tài liệu đưa vào ngữ cảnh; đoạn nghi tiêm lệnh bị loại khỏi ngữ cảnh và ghi `PROMPT_INJECTION`.
- Che C2: nhận diện số định danh, số điện thoại, ngày sinh, địa chỉ email và trường được đánh dấu C2 trong dữ liệu lõi, thay bằng nhãn trước khi gửi mô hình; yêu cầu cố ý hỏi C2 ngoài chức năng ghi `C2_REQUEST`; dữ liệu C3 không bao giờ vào ngữ cảnh (kiểm cả ở tầng lấy dữ liệu).
- Chặn ca dùng bị cấm theo BR-17-04 (chấm điểm, đề xuất mức, nhận xét cán bộ) bằng phân loại ý định trước khi gọi mô hình và kiểm lại đầu ra; trả `blocked` với thông điệp MSG-17-02, ghi `OUT_OF_SCOPE`.
- Rào chắn đầu ra: kiểm JSON theo lược đồ của ca dùng, mỗi trích dẫn phải nằm trong danh sách đoạn đã truy xuất (BR-17-02), không lộ dữ liệu cá nhân (`PII_OUT`), không vượt 3 mục tiêu O với ca soạn OKR (BR-17-06); vi phạm thì chặn hoặc che theo `action`.
- Đo thời gian rào chắn đầu vào (mục tiêu ≤ 200 ms) và tỷ lệ chặn theo `rule_code`; bộ ca kiểm thử đối kháng tiếng Việt lưu ở `data/golden/ai-guardrail/`.

## 4. Ngoài phạm vi

- API ca dùng (DV-14-04..06); quy trình đánh giá chất lượng mô hình định kỳ (vận hành).

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `data/golden/ai-guardrail/**`

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && pytest` xanh, gồm bộ ca đối kháng: ≥ 95% ca tiêm lệnh mẫu bị phát hiện, 100% ca hỏi chấm điểm hay xếp loại bị chặn, 0 ca lọt số định danh trong đầu ra.
- Rào chắn đầu vào p95 ≤ 200 ms trên máy thử (ghi số đo); mọi lần chặn có một dòng `guardrail_event`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
