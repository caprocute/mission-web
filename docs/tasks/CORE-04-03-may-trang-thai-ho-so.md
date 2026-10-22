# CORE-04-03 · QT-09 Máy trạng thái `appraisal_profile` và chặn chuyển bước thiếu lý do

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-04-01 |
| Truy vết | QT-09 → MOD-04 (domain) → `appraisal.appraisal_profile.status`, `appraisal.rating_proposal`, `appraisal.rating_explanation` → EVT-11 (`appraisal.profile.stepcompleted.v1`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực trong lớp `domain` của MOD-04 máy trạng thái hồ sơ đánh giá đúng baseline 9.1 và bảng chuyển trạng thái của TL-02b, gồm hai vòng quay lui (trả lại bổ sung, yêu cầu xem lại), nhánh `KHIEU_NAI`, và quy tắc nền tảng BR-09-12: bốn mức lưu riêng, mức sau khác mức trước liền kề thì không chuyển bước khi chưa có giải trình. Mọi API của CORE-04-04 trở đi chỉ gọi lệnh domain này.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: "Trạng thái đối tượng" (dòng 65, bảng chuyển trạng thái), "Quy tắc nghiệp vụ" (dòng 87, BR-09-12, BR-09-14..16 là quy tắc nền tảng viết bằng mã).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9.1.
- TL-03 "DDL mẫu: appraisal.rating_proposal" (dòng 2728, đoạn dòng 2783 về `explanation_required`), bảng `rating_explanation` (dòng 2901).
- Bản trích `tl02b §QT-09 BE-Q09-02`.

## 3. Phạm vi công việc

- Viết aggregate `AppraisalProfile` với các lệnh ứng với từng dòng bảng chuyển trạng thái: `openSelfAssessment` (EVT-10), `markSubmitted`, `returnForRevision`, `markAppraised`, `propose` hoặc `skipProposal`, `requestReview`, `decide`, `markSigned` (EVT-18), `fileComplaint` (EVT-25), `concludeComplaint`, `lock`.
- Ghi mức bằng cách chèn `rating_proposal` mới và đặt dòng cũ `is_current = false`, không cập nhật mức tại chỗ; tính `explanation_required` khi mức khác mức liền kề trước đó.
- Chặn chuyển bước khi `explanation_required = true` mà chưa có `rating_explanation` hoặc thiếu `reason`; trả 422 RFC 9457 trỏ BR-09-12 (TL-02b quy ước 422 khi thiếu `reason`).
- Đếm số lần trả lại; vượt tham số (mặc định 2, lấy từ `PolicyQueryApi`) thì chặn.
- Phát EVT-11 qua outbox ở mọi bước chuyển, kèm bước, người thực hiện, mã tác nhân AC; không chứa trường C2.
- Kiểm thử bảng chuyển trạng thái đầy đủ và kiểm thử thuộc tính "không có chuỗi lệnh nào đưa hồ sơ ra khỏi `DA_KHOA`".

### Bổ sung theo BS-03 (ADR-33, 22/10/2026)

Nguồn: `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md`. Phần dưới mở rộng phạm vi của thẻ; mọi giá trị riêng của khách hàng đi qua cấu hình, không viết cứng trong mã (YC-TK-11). Nếu thẻ phụ thuộc chưa gộp, làm phần gốc của thẻ trước và để phần bổ sung sau cờ hoặc ghi rõ trong báo cáo.

- Máy trạng thái chặn chuyển bước khi mức của dòng `appraisal.rating_proposal` khác `score_snapshot.auto_rating_code` của ảnh chụp người đó đã thấy mà thiếu lý do trong `appraisal.rating_explanation` (DC-05). Bốn mức con người đưa ra vẫn là bốn giá trị `stage` như TL-03, không thêm cột.

## 4. Ngoài phạm vi

- API HTTP, ký số, OPA tách vai trò (CORE-04-04, CORE-04-05, CORE-04-07, CORE-04-08); luồng BPMN `duyet-ho-so-danh-gia` (DV-06-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; ArchUnit xác nhận lớp `domain` không phụ thuộc hạ tầng.
- Độ phủ dòng `domain` và `application` phần mới ≥ 80%; mọi dòng của bảng chuyển trạng thái TL-02b có ít nhất một ca kiểm thử.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
