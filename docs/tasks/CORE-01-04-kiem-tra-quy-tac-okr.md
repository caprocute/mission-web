# CORE-01-04 · QT-02/BR-02-01..07 Bộ kiểm tra quy tắc OKR ở máy chủ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-03, NEN-041; cần CORE-05-03 (`PolicyQueryApi`) để đọc danh sách từ mơ hồ và ngưỡng theo tenant (bổ sung) |
| Truy vết | QT-02 → SCR-0202, SCR-0203 → API `POST /api/v1/okr/objectives/{id}/submit`, `GET /okr/compose-context`, `GET /okr/tree`, `GET /okr/reviews` (trường kết quả kiểm tra) → MOD-01, MOD-05 → bảng `okr.objective`, `okr.key_result`, `okr.objective_basis`, `okr.okr_link` → BR-02-01..BR-02-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bộ kiểm tra quy tắc OKR chạy ở máy chủ, dùng cùng bộ luật CEL với trình duyệt (cel-java phía máy chủ, cel-js phía web), trả kết quả theo ba mức Đạt, cảnh báo, lỗi chặn. Bộ kiểm tra là nguồn sự thật cuối cùng: nút trình ở web có thể bật, nhưng máy chủ vẫn chặn khi vi phạm quy tắc chặn. Kết quả được tính lại mỗi lần người duyệt mở, không lưu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 "Mô tả các bước" (bộ kiểm tra cú pháp và danh sách từ mơ hồ, L796), "Quy tắc nghiệp vụ" (L815–829), "Ngoại lệ và xử lý" (L831–841), SCR-0203 (kết quả kiểm tra tính lại khi mở, L959).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 (L1822–1941), phần cờ `ai_assisted`, kiểm trọng số 100% và luật soát KR mơ hồ.
- `contracts/openapi/modules/okr.yaml`: lược đồ `OkrCheckResult`, `OkrCheckLevel`, `OkrRule`, `OkrReviewCheck`; thao tác `submitOkrObjective` (danh sách `rules`).
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (CEL dùng chung), bộ ca kiểm thử chung cel-java/cel-js của NEN-041.

## 3. Phạm vi công việc

- Viết `OkrRuleChecker` ở lớp `okr.application` nhận một mục tiêu cùng KR, căn cứ, liên kết và trả danh sách kết quả `{rule, level, target, message}` khớp `OkrCheckResult`.
- Hiện thực từng quy tắc: BR-02-01 (1–3 mục tiêu mỗi đơn vị mỗi kỳ, vượt 3 cần `over_limit_reason`, mức cảnh báo, MSG-02-04); BR-02-02 (≥ 1 căn cứ và liên kết mục tiêu cấp trên trừ cấp cao nhất, mức chặn; cấp trên chưa duyệt thì chặn ở bước trình, MSG-02-03); BR-02-03 (2–5 KR, cảnh báo); BR-02-04 (KR có đơn vị đo, giá trị đầu, chỉ tiêu, hạn, người xác nhận, chặn); BR-02-05 (KR chứa từ mơ hồ mà không kèm chỉ tiêu định lượng, chặn, MSG-02-05); BR-02-06 (KR giống nhiệm vụ hoặc sản phẩm một lần, cảnh báo, MSG-02-06); BR-02-07 (tổng trọng số KR = 100%, chặn, MSG-02-07).
- Biểu diễn các quy tắc có thể biểu diễn bằng CEL (BR-02-03, 04, 05, 07) qua thư viện `vt-cel` của NEN-041; danh sách từ mơ hồ ("tăng cường", "đẩy mạnh", "kịp thời", "nâng cao" và danh sách bổ sung) và ngưỡng đọc từ Policy Pack qua `PolicyQueryApi`, không viết cứng trong mã.
- BR-02-06 gọi cổng tìm tương đồng qua một port trong `okr.application`; khi SVC-16 chưa sẵn sàng hoặc lỗi, quy tắc trả mức cảnh báo "chưa kiểm được" và không chặn trình (suy luận theo nguyên tắc AI/tìm kiếm lỗi không chặn soạn ở L840).
- Gắn kết quả kiểm tra vào phản hồi `getOkrTree`, `getOkrComposeContext`, `listOkrReviews` (trường kiểm tra theo hợp đồng) và cung cấp cho `submitOkrObjective` dùng ở CORE-01-05.
- Bộ ca kiểm thử dạng bảng (đầu vào JSON, kết quả mong đợi) đặt cạnh ca kiểm thử chung của NEN-041 để web chạy cùng ca bằng cel-js.

## 4. Ngoài phạm vi

- Gợi ý viết lại KR bằng AI (CORE-01-13).
- Kiểm tra BR-02-08, BR-02-09 khi duyệt (CORE-01-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `data/golden/okr-rules/**` (ca kiểm thử dùng chung với cel-js)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Mỗi quy tắc BR-02-01..07 có ít nhất một ca đạt và một ca vi phạm; ca "trọng số 99,9%" bị chặn, ca "Đẩy mạnh tuyên truyền" không kèm số bị chặn, ca "Đẩy mạnh tuyên truyền tới 100% chi bộ" không bị chặn.
- Cùng bộ ca trong `data/golden/okr-rules/` cho kết quả giống hệt nhau giữa cel-java và cel-js (chạy bộ đối chiếu của NEN-041).
- Bộ kiểm tra một mục tiêu năm KR chạy ≤ 50 ms p95 trong kiểm thử vi mô (suy luận, để kết quả tính lại khi mở không làm chậm SCR-0203).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
