# CORE-07-02 · QT-12/SCR-1201 Khiếu nại: lưu nháp mã hoá, kiểm thời hạn, nộp có ký, chuyển hồ sơ `KHIEU_NAI`, EVT-25

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-01, CORE-04-08 |
| Truy vết | QT-12 → SCR-1201 → API `GET /api/v1/oversight/complaints/eligible-profiles`, `POST /api/v1/oversight/complaints`, `PUT .../complaints/{id}`, `POST .../complaints/{id}/submit` → MOD-07 → `oversight.complaint`; `appraisal.appraisal_profile` (`DA_KY_SO` → `KHIEU_NAI`, qua cổng `api` của MOD-04) → SVC-10 → EVT-18 → EVT-25 (`oversight.complaint.filed.v1`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cán bộ chọn kết quả đánh giá của mình còn trong hạn, soạn khiếu nại (nội dung mã hoá C2), ký số và gửi; máy chủ kiểm thời hạn và hình thức (quá hạn chỉ còn bảo lưu ý kiến), chuyển hồ sơ sang nhánh `KHIEU_NAI` và phát EVT-25 để SVC-06 khởi động luồng xử lý. Kết quả hiện hành vẫn có hiệu lực trong thời gian xử lý (BR-12-06, MSG-12-02).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-12: "Mô tả các bước" (dòng 761), "Trạng thái đối tượng" (dòng 782), "Quy tắc nghiệp vụ" (dòng 802: BR-12-01..03, BR-12-06, BR-12-10), "Ngoại lệ và xử lý" (dòng 818), "SCR-1201. Gửi khiếu nại" (dòng 832, MSG-12-01..03).
- TL-03 bảng `oversight.complaint` (dòng 3103).
- `contracts/openapi/modules/oversight.yaml`: `listComplainableProfiles`, `createComplaint`, `updateComplaint`, `submitComplaint` (lỗi `OVERSIGHT-422-DEADLINE-PASSED`, `OVERSIGHT-422-NO-TARGET`, `OVERSIGHT-409-INVALID-STATE`); `contracts/openapi/modules/products.yaml` nhóm `/files/uploads` cho tệp kèm.
- Bản trích `tl02b §QT-12 BE-Q12-01, 02`.

## 3. Phạm vi công việc

- `listComplainableProfiles`: hồ sơ `DA_KY_SO` của người gọi, ngày công bố, hạn khiếu nại theo `khieu_nai.thoi_han` (10 ngày làm việc), hình thức còn được phép, danh sách cấu phần của ảnh chụp điểm (đọc qua cổng `api` của MOD-04).
- `createComplaint`, `updateComplaint` (`If-Match`): lưu nháp, mã hoá `content_enc`, `request_text_enc` bằng `FieldEncryptor` mục đích `KHIEU_NAI`; tệp kèm chỉ nhận mã đối tượng đã quét sạch, ≤ 20 MB.
- `submitComplaint` (`Idempotency-Key`, step-up khi ký): kiểm BR-12-02 (kết quả, ≥ 1 cấu phần, lý do 50–8.000 ký tự, đề nghị ≤ 500 ký tự), BR-12-03 (`kind`), BR-12-01 (quá hạn chỉ cho `BAO_LUU`, MSG-12-01); tạo yêu cầu ký nội dung tới SVC-10, trả 202.
- Consumer EVT-18 của chữ ký khiếu nại: đặt `status = 'MOI'`, `response_deadline`, gọi lệnh `fileComplaint` của MOD-04 để hồ sơ sang `KHIEU_NAI`, ghi outbox EVT-25 và EVT-21 (MSG-12-02) cùng giao dịch.
- Đăng ký lược đồ Avro EVT-25 nếu chưa có; payload không chứa nội dung C2.
- Mỗi lần giải mã nội dung khiếu nại để đọc ghi sổ cái (BR-12-10) qua thư viện CORE-00-05.

## 4. Ngoài phạm vi

- Chọn người xử lý và các bước sau (CORE-07-03..05); mẫu BPMN `xu-ly-khieu-nai` (DV-06-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/api/**` (chỉ khi cần thêm lệnh `fileComplaint` vào cổng công bố của MOD-04, ghi rõ trong báo cáo)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `contracts/events/avro/` (chỉ thêm lược đồ EVT-25 nếu chưa có)
- `deploy/policies/` (chỉ quy tắc Rego cho người khiếu nại)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka; mã lỗi khớp `oversight.yaml`.
- Kiểm thử: quá hạn với `kind = 'KHIEU_NAI'` bị 422; thiếu cấu phần bị 422; hồ sơ chỉ sang `KHIEU_NAI` sau EVT-18; EVT-25 phát đúng một lần; cột `_enc` không chứa văn bản rõ.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
