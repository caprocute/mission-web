# CORE-07-03 · SCR-1202 Chọn người xử lý qua OPA, máy trạng thái khiếu nại, tiếp nhận, không thụ lý, rút

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-02 (bổ sung đề xuất: NEN-040 cho gói chính sách OPA, DV-06-04 cho mẫu luồng `xu-ly-khieu-nai`) |
| Truy vết | QT-12 bước 3–4 → SCR-1202 → API `GET /api/v1/oversight/complaints`, `GET .../complaints/{id}`, `POST .../complaints/{id}/accept`, `POST .../complaints/{id}/reject`, `POST .../complaints/{id}/withdraw` → MOD-07 → `oversight.complaint` (`MOI` → `DA_TIEP_NHAN` / `KHONG_THU_LY` / `RUT` → `DONG`) → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực máy trạng thái khiếu nại theo TL-02b (bổ sung `RUT` theo TL-03) và chính sách OPA chọn người xử lý hợp lệ: có vai trò `appraiser` ở cấp có thẩm quyền, loại trừ người đã nghiệm thu, thẩm định, đề xuất, quyết định hồ sơ (BR-12-04); danh sách rỗng thì chuyển cấp trên theo cây tổ chức. Người xử lý tiếp nhận hoặc không thụ lý có lý do, người khiếu nại rút được trước khi có kết luận.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-12: "Mô tả các bước" (dòng 761, bước 3–4 và đoạn giải thích bước 3), "Trạng thái đối tượng" (dòng 782), BR-12-04, BR-12-11 (dòng 802), "Ngoại lệ và xử lý" (dòng 818), "SCR-1202. Xử lý khiếu nại" (dòng 879, MSG-12-05).
- TL-03 bảng `oversight.complaint` (dòng 3103, chín trạng thái).
- `contracts/openapi/modules/oversight.yaml`: `listComplaints`, `getComplaint`, `acceptComplaint`, `rejectComplaint`, `withdrawComplaint` (lỗi `OVERSIGHT-403-SEPARATION-OF-DUTIES`, `OVERSIGHT-409-INVALID-STATE`).
- `wiki/15-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-1202; bản trích `tl02b §QT-12 BE-Q12-03, 04`.

## 3. Phạm vi công việc

- Viết aggregate `Complaint` với bảng chuyển trạng thái đầy đủ của TL-02b cộng `RUT`; mọi chuyển trạng thái ghi sổ cái qua outbox.
- Viết quy tắc Rego "ứng viên xử lý khiếu nại": đầu vào là hồ sơ, danh sách người đã tham gia (đọc qua cổng `api` của MOD-03, MOD-04), cây tổ chức; đầu ra là danh sách hợp lệ; rỗng thì nâng cấp trên; SVC-06 gọi quy tắc này khi giao task, MOD-07 gọi lại khi nhận lệnh ghi (MSG-12-05 khi bị từ chối).
- `acceptComplaint`: người được giao, `MOI` → `DA_TIEP_NHAN`, đặt hạn tiếp nhận 2 ngày làm việc đã qua kiểm; `rejectComplaint`: bắt buộc lý do (quá hạn, sai thẩm quyền, trùng nội dung đã trả lời BR-12-11), `KHONG_THU_LY`, tự `DONG` sau khi gửi EVT-21.
- `withdrawComplaint`: chỉ người khiếu nại, trước khi có kết luận, bắt buộc lý do; `RUT` rồi `DONG`; gọi cổng MOD-04 đưa hồ sơ về `DA_KY_SO`; nội dung vẫn được lưu.
- `listComplaints`, `getComplaint`: phân trang con trỏ, lọc theo phạm vi; nội dung C2 giải mã cho người có quyền và ghi nhật ký giải mã (BR-12-10).
- Điểm lệch cần lưu ý: trong `oversight.yaml`, mô tả của `acceptComplaint` đang chép nhầm mô tả của `rejectComplaint` (chuyển sang `KHONG_THU_LY`); hiện thực `acceptComplaint` theo TL-02b (`DA_TIEP_NHAN`) và đề xuất task sửa mô tả hợp đồng, ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Giải trình, kết luận, trả lời (CORE-07-04); mẫu BPMN và timer giao task (DV-06-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `deploy/policies/` (chỉ quy tắc Rego chọn người xử lý khiếu nại và ca `opa test`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `opa test` xanh với ca đơn vị nhỏ phải nâng cấp trên.
- Kiểm thử: người đã thẩm định hồ sơ gọi tiếp nhận bị 403; không thụ lý thiếu lý do bị 422; rút sau khi `DA_TRA_LOI` bị 409; mỗi bảng chuyển trạng thái có ca kiểm thử.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
