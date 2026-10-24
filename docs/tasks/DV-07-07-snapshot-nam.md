# DV-07-07 · QT-10 Snapshot năm (băm của các băm quý) và DMN năm

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | DV-07-05 |
| Truy vết | QT-10 → SCR-1001 → SVC-07 → bảng `scoring.score_result`, `scoring.score_trace` → EVT-10 (`periodType = NAM`) cho MOD-04 ghi `appraisal.annual_rating`, `appraisal.score_snapshot`; BR-10-01..BR-10-06; MSG-10-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tính kết quả xếp loại năm từ bốn kết quả quý đã quyết định: điểm năm theo tham số `nam.cach_tinh`, DMN năm có dòng chặn HTXSNV khi có quý KHTNV, cờ đề xuất bố trí lại khi hai quý liên tiếp KHTNV, và `input_hash` năm là băm của bốn băm quý cộng tham số năm. Khi xong, kết quả năm tái lập được, giữ đúng phiên bản Policy Pack từng quý, và tự tính lại khi một quý được điều chỉnh sau hậu kiểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-10 (dòng 336–508): bước 2–7, BR-10-01..BR-10-06 (dòng 385–400), ngoại lệ (dòng 402–410), MSG-10-04 (dòng 460).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `appraisal.annual_rating` (tra bằng grep trong schema appraisal) và "Schema scoring (SVC-07)" (dòng 3316).
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` `listAnnualAppraisalRatings`, `getAnnualAppraisalExplanation` (để biết dữ liệu MOD-04 cần nhận); `contracts/openapi/modules/policies.yaml` `getProspectCriteria`.
- Wiki: `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Nhận yêu cầu tính năm khi quý IV khoá và đủ bốn quyết định quý hoặc có trường hợp đặc thù (BR-10-01; điều kiện do MOD-04 kiểm, SVC-07 nhận danh sách hồ sơ năm qua `inputs` chế độ năm, suy luận).
- Điểm năm theo `nam.cach_tinh` (mặc định `TRUNG_BINH_QUY`; trung bình có trọng số hoặc cách khác theo tham số, mỗi giá trị có ca vàng riêng – BR-10-02); quý không xếp loại theo PL 6 loại khỏi trung bình.
- DMN năm: dòng chặn HTXSNV khi có quý KHTNV đứng trước dòng HTXSNV, câu giải thích nêu quý (BR-10-03); điều kiện năm áp như quý trên số liệu cả năm (BR-10-05); cờ `reassign_flag` khi hai quý liên tiếp KHTNV (BR-10-04).
- `input_hash` năm = băm JCS của danh sách bốn `input_hash` quý (kèm phiên bản gói từng quý) và tham số năm; không tính lại quý cũ bằng phiên bản mới (BR-10-06).
- Tự tính lại năm khi nhận EVT-10 của một quý đã điều chỉnh sau hậu kiểm; kết quả mới kèm giá trị cũ để MOD-04 thông báo MSG-10-04.
- Kiểm thử: ca vàng mỗi giá trị `nam.cach_tinh`; ca có một quý KHTNV; ca hai quý liên tiếp KHTNV; ca quý IV được điều chỉnh làm điểm năm đổi.
- Điểm lệch cần lưu ý: TL-02b không nói rõ ai sinh gợi ý nhóm triển vọng `suggested_group` (SVC-07 hay MOD-04); không làm trong task này, ghi vào báo cáo và wiki trang 14.

## 4. Ngoài phạm vi

- Lập hồ sơ năm, phân nhóm triển vọng, tạo việc bố trí lại (CORE-04); giao diện xếp loại năm (WEB-039).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `data/golden/` (ca vàng năm)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm bốn nhóm ca ở mục 3.
- `input_hash` năm tái lập được từ bốn băm quý trong kiểm thử độc lập.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
