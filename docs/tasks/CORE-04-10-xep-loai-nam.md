# CORE-04-10 · QT-10/SCR-1001 Xếp loại năm: lập từ bốn quý, thiếu quý, chặn khi còn khiếu nại, tính lại

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A lập hồ sơ năm và xử lý thiếu quý; B danh sách, giải thích, chặn khiếu nại, tính lại) |
| Phụ thuộc | CORE-04-08, DV-07-07 |
| Truy vết | QT-10 → SCR-1001 → API `GET /api/v1/appraisals/annual` (`listAnnualAppraisalRatings`), `GET .../annual/{id}/explanation` → MOD-04 → `appraisal.annual_rating`, `appraisal.appraisal_profile` (kỳ năm), `appraisal.score_snapshot` → nhận EVT-09 (quý IV), EVT-10 → phát EVT-11, EVT-12 (`periodType = NAM`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi quý IV khoá và đủ bốn quyết định quý (hoặc rơi vào trường hợp đặc thù), MOD-04 lập hồ sơ năm trong ≤ 15 phút, nhận ảnh chụp năm từ SVC-07, cung cấp danh sách xếp loại năm có lọc liên thông và giải thích, chặn quyết định năm khi còn khiếu nại quý chưa kết luận, và tính lại năm khi ảnh chụp quý đổi sau hậu kiểm. Chuỗi tự đánh giá, thẩm định, quyết định năm tái dùng API của QT-09 ở chế độ năm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-10: "Mô tả các bước" (dòng 367), "Quy tắc nghiệp vụ" (dòng 385: BR-10-01..06), "Ngoại lệ và xử lý" (dòng 402), "SCR-1001. Xếp loại năm" (dòng 414, MSG-10-01..04).
- TL-03 bảng `annual_rating` (dòng 2917), `score_snapshot` (dòng 2849).
- `contracts/openapi/modules/appraisals.yaml`: `listAnnualAppraisalRatings`, `getAnnualAppraisalExplanation`, `createAppraisalBatchDecision` (lỗi `APPRAISAL-409-COMPLAINT-OPEN`).
- Bản trích `tl02b §QT-10 BE-Q10-01, 04, 05`.

## 3. Phạm vi công việc

- Mở rộng consumer EVT-09: với kỳ quý IV, lập `appraisal_profile` kỳ năm và `annual_rating` (CHECK `period_bucket % 10 = 0`) cho người có đủ bốn bản `QUYET_DINH` quý hoặc có trường hợp đặc thù PL 6 (BR-10-01); quý "không xếp loại" bị loại khỏi trung bình; ghi `quarter_ratings`, `has_khtnv_quarter`, `consecutive_khtnv`.
- Consumer EVT-10 kỳ năm lưu ảnh chụp năm (tham chiếu bốn ảnh chụp quý, danh sách phiên bản từng quý — BR-10-06) và mở bước tự đánh giá năm như QT-09.
- `listAnnualAppraisalRatings` lọc `flag=lien_thong` theo `block_reason` hoặc `reassign_flag`; `getAnnualAppraisalExplanation` trả giải thích liên thông (ví dụ có quý KHTNV nên không HTXSNV năm, MSG-10-01, BR-10-03).
- Chặn quyết định năm khi còn khiếu nại quý chưa kết luận: kiểm bằng trạng thái `KHIEU_NAI` của chính các hồ sơ quý trong MOD-04 (hồ sơ vào nhánh này khi nhận EVT-25 và rời khi MOD-07 kết luận qua cổng `api` của MOD-04), không gọi ngược sang MOD-07, trả 409 `APPRAISAL-409-COMPLAINT-OPEN` + MSG-10-03.
- Tính lại năm khi ảnh chụp quý đổi (sau QT-13): yêu cầu SVC-07 tính lại, đưa hồ sơ năm đã quyết định về bước quyết định, đánh dấu quyết định cũ bị thay thế, phát EVT-21 với MSG-10-04.
- Bảo đảm lập hồ sơ năm ≤ 15 phút sau EVT-09 quý IV (ghi lô như CORE-04-02).

## 4. Ngoài phạm vi

- DMN năm và ảnh chụp năm ở SVC-07 (DV-07-07); cờ bố trí lại (CORE-04-11); phân nhóm triển vọng (CORE-04-12).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử: người thiếu một quý không đặc thù không có hồ sơ năm; người có quý KHTNV nhận `block_reason`; quyết định năm khi còn khiếu nại quý bị 409; ảnh chụp quý đổi kích hoạt tính lại và quyết định cũ được đánh dấu thay thế, không bị xoá.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
