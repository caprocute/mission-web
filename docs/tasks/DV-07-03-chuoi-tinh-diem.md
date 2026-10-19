# DV-07-03 · QT-07, QT-09 Chuỗi tính A, B, C, KPI nhóm, điểm và `score_trace`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | L |
| Phụ thuộc | DV-07-02 |
| Truy vết | QT-07 bước 11, QT-09 → SCR-0903 → `GET /api/v1/appraisals/profiles/{id}/score-trace` (`getAppraisalScoreTrace`, owner SVC-07) → SVC-07 → bảng `scoring.score_input_v`, `scoring.score_result`, `scoring.score_trace`; BR-07-05, BR-07-07, BR-09-01..BR-09-05 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực chuỗi tính điểm theo Policy Pack: từ đầu vào của một nhiệm vụ tính A (khối lượng), B (chất lượng), C (tiến độ), KPI nhiệm vụ, rồi KPI nhóm và điểm của hồ sơ; mỗi bước ghi vết vào `score_trace` với mã quy tắc và căn cứ pháp lý. Khi xong, cùng một đầu vào và cùng phiên bản luôn cho cùng kết quả, cán bộ xem được vì sao mình có điểm đó, và kiểm thử thuộc tính trên 10.000 bộ dữ liệu không vi phạm miền giá trị.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09 bảng BR (dòng 87–107: BR-09-01..BR-09-05), kiểm thử thuộc tính (dòng 1509).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 bước 11 (dòng 1895–1897: công thức A, B, C khi có phiên bản bị yêu cầu bổ sung, `revision_cause`), BR-07-05, BR-07-07.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (dòng 2448).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `score_input_v`, `score_trace` (dòng 3346, 3375).
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` `getAppraisalScoreTrace`.
- Wiki: `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/13-thuat-ngu.md`.

## 3. Phạm vi công việc

- Định nghĩa tài liệu đầu vào chuẩn của một hồ sơ (danh sách nhiệm vụ, số lượng giao, sản phẩm `DA_XAC_NHAN`, mức chất lượng, ngày nộp phiên bản cuối được xác nhận, ngày chậm chủ quan, `revision_cause`, cờ chặn của cảnh báo trùng), lưu `score_input_v` theo `input_hash`, dùng lại khi trùng băm.
- Tính theo quy tắc CEL của gói: A = min(số sản phẩm duyệt / kế hoạch, 1) với tỷ lệ vượt ghi riêng `ty_le_vuot` (BR-09-04); B ∈ {1; 0,75; 0,5; 0,25; 0} lấy trung bình khi nhiều sản phẩm (BR-07-07); C = max(0, 1 − 0,05 × ngày chậm chủ quan) với ngày chậm giữa phiên bản bị bổ sung và phiên bản đạt tính theo `revision_cause`; KPI = (A + B + C)/3 mặc định (BR-09-01).
- Chỉ đếm minh chứng `DA_XAC_NHAN` và không bị cờ chặn trùng (BR-07-05, BR-09-02).
- Điểm = Σ KPI nhóm × điểm tối đa nhóm, tổng tối đa 100, làm tròn 1 chữ số nửa lên (BR-09-03); tiêu chí chung quy 4 mức theo tỷ lệ 100 / ≥ 90 / ≥ 60 / < 60, máy chủ tính lại và không tin kết quả cel-js (BR-09-05).
- Ghi `score_trace` khoảng 40 dòng mỗi kết quả (`rule_code`, `legal_ref`, `inputs`, `output` ngắn), không chứa C2.
- Hiện thực `getAppraisalScoreTrace` đọc vết theo hồ sơ, kiểm quyền đọc qua OPA, đúng lược đồ hợp đồng.
- Kiểm thử: ca vàng từ `data/golden/` (bộ ca của Policy Pack mẫu); kiểm thử thuộc tính 10.000 bộ (điểm ∈ [0; 100], C ≥ 0, A ≤ 1); hai lần tính cùng đầu vào cho cùng `result_hash`.
- Gợi ý tách: (a) đầu vào chuẩn, A, B, C, KPI nhiệm vụ; (b) KPI nhóm, điểm, tiêu chí chung, vết, API.

## 4. Ngoài phạm vi

- Xếp loại bằng DMN và câu giải thích (DV-07-05); consumer sự kiện (DV-07-04).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `data/golden/` (thêm ca vàng tính điểm, không chứa dữ liệu thật)
- `wiki/13-thuat-ngu.md` (chỉ bổ sung thuật ngữ A, B, C nếu thiếu)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm ca vàng, kiểm thử thuộc tính, kiểm thử hợp đồng `getAppraisalScoreTrace`; độ phủ dòng `domain`, `application` ≥ 80%.
- Kết quả cel-java trùng kết quả cel-js trên toàn bộ ca vàng dùng chung (đối chiếu với bộ ca của NEN-041).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
