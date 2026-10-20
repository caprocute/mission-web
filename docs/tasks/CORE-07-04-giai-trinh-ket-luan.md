# CORE-07-04 · SCR-1202 Yêu cầu giải trình, timer hạn, kết luận, văn bản trả lời có ký, tạo yêu cầu mở lại

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A giải trình và hạn; B kết luận, tính thử, văn bản trả lời, ký, mở lại) |
| Phụ thuộc | CORE-07-03, DV-07-08 (bổ sung đề xuất: CORE-07-07 cho tạo yêu cầu mở lại) |
| Truy vết | QT-12 bước 5–10 → SCR-1202 → API `POST /api/v1/oversight/complaints/{id}/explanation-requests`, `.../explanations`, `GET .../responses`, `PUT .../conclusion`, `POST .../respond`; `POST /api/v1/appraisals/profiles/{id}/simulate` (SVC-07) → MOD-07 → `oversight.complaint`, `oversight.complaint_response` → SVC-11 (mẫu TL-KN-01), SVC-10 → EVT-18, EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người xử lý yêu cầu các bên giải trình có hạn, tính thử phương án điều chỉnh bằng đúng phiên bản Policy Pack của kỳ, ghi kết luận và sinh văn bản trả lời từ mẫu; người có thẩm quyền ký văn bản trả lời trong thời hạn 15 ngày làm việc. Kết luận có điều chỉnh với kỳ đã khoá không sửa trực tiếp dữ liệu mà tạo yêu cầu mở lại kỳ (BR-12-07, MSG-12-07).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-12: "Mô tả các bước" (dòng 761, bước 5–10 và đoạn ví dụ tính thử), "Trạng thái đối tượng" (dòng 782), BR-12-04, BR-12-05, BR-12-07, BR-12-08 (dòng 802), "Ngoại lệ và xử lý" (dòng 818: bên giải trình quá hạn vẫn chuyển `CHO_KET_LUAN`, kỳ chưa khoá điều chỉnh qua QT-07), "SCR-1202" (dòng 879, MSG-12-05..08).
- TL-03 bảng `oversight.complaint_response` (dòng 3127).
- `contracts/openapi/modules/oversight.yaml`: `requestComplaintExplanations`, `submitComplaintExplanation`, `listComplaintResponses`, `saveComplaintConclusion` (`OVERSIGHT-422-CONCLUSION-MISSING`), `respondComplaint`; `contracts/openapi/modules/appraisals.yaml`: `simulateAppraisalScore`.
- Bản trích `tl02b §QT-12 BE-Q12-05, 06, 07`.

## 3. Phạm vi công việc

- `requestComplaintExplanations`: một `complaint_response` loại `YEU_CAU` cho mỗi bên, hạn mặc định 3 ngày làm việc, chuyển `DANG_GIAI_TRINH`, EVT-21 cho từng bên.
- `submitComplaintExplanation`: bên được yêu cầu gửi giải trình (C2) và tệp kèm, tạo bản ghi `GIAI_TRINH`; khi mọi yêu cầu đã trả lời hoặc quá hạn thì tự `CHO_KET_LUAN` (tác vụ hẹn giờ theo ngày làm việc kiểm hạn).
- Tính thử: chuyển tiếp tới `simulateAppraisalScore` của SVC-07 với `policy_version` của ảnh chụp gốc (BR-12-08), không lưu; trả MSG-12-06.
- `saveComplaintConclusion` (`If-Match`): kết quả `GIU_NGUYEN`/`DIEU_CHINH`/`BAO_LUU`; `outcome <> 'GIU_NGUYEN'` bắt buộc `adjustment_json` trong miền hợp lệ của cấu phần; từ `DA_TIEP_NHAN` không qua giải trình bắt buộc ghi chú; kiểm lại OPA BR-12-04; kỳ `DA_KHOA` và có điều chỉnh thì trả cảnh báo MSG-12-07 và tạo `reopen_request` qua cổng ứng dụng của CORE-07-07.
- Yêu cầu SVC-11 sinh văn bản trả lời từ mẫu TL-KN-01 có xem trước.
- `respondComplaint` (202, AC-08, step-up): tạo yêu cầu ký; EVT-18 → `DA_TRA_LOI`, gửi MSG-12-04 cho người khiếu nại; có `reopen_request` gắn kèm thì `DANG_THI_HANH`; giữ nguyên thì gọi cổng MOD-04 đưa hồ sơ về `DA_KY_SO`.
- Nhắc trước hạn trả lời 2 ngày làm việc (MSG-12-08) và chuyển cấp khi quá hạn 15 ngày (phối hợp timer của mẫu luồng DV-06-04, ghi rõ bên nào giữ timer).

## 4. Ngoài phạm vi

- API mô phỏng ở SVC-07 (DV-07-08); mẫu văn bản TL-KN-01 ở SVC-11; mở lại kỳ (CORE-07-07, CORE-07-08); bảo lưu và tự đóng (CORE-07-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `deploy/policies/` (chỉ quy tắc Rego cho kết luận và trả lời khiếu nại)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka; mã lỗi khớp `oversight.yaml`.
- Kiểm thử: bên giải trình quá hạn vẫn đưa khiếu nại sang `CHO_KET_LUAN`; kết luận điều chỉnh trên kỳ đã khoá tạo `reopen_request` và không đổi dữ liệu kỳ; tính thử không tạo bản ghi nào trong `appraisal` hay `scoring`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
