# CORE-07-05 · QT-12 Bảo lưu ý kiến, tự đóng khiếu nại, nhật ký giải mã, gợi ý khiếu nại trùng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | S |
| Phụ thuộc | CORE-07-04, CORE-00-05 |
| Truy vết | QT-12 bước 11–12 → SCR-1201, SCR-1202 → API `POST /api/v1/oversight/complaints/{id}/reserve` (`reserveComplaintOpinion`), `GET .../complaints/{id}` → MOD-07 → `oversight.complaint` (`DA_TRA_LOI` / `DANG_THI_HANH` → `DONG`), `oversight.complaint_response` (`BAO_LUU`) → EVT-21; nhận EVT-22 hoặc tín hiệu hoàn tất mở lại |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn tất vòng đời khiếu nại: người khiếu nại xác nhận bảo lưu ý kiến trong 5 ngày làm việc và ý kiến được in vào Mẫu 3 (BR-12-09); hệ thống tự đóng khiếu nại khi hết hạn xác nhận, khi không thụ lý đã gửi thông báo, hoặc khi việc mở lại kỳ gắn với khiếu nại hoàn tất. Mọi lần giải mã nội dung khiếu nại được ghi sổ cái (BR-12-10), và người xử lý được gợi ý khiếu nại cùng nội dung đã trả lời (BR-12-11).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-12: "Mô tả các bước" (dòng 761, bước 11–12), "Trạng thái đối tượng" (dòng 782), BR-12-09..11 (dòng 802), "SCR-1201" (dòng 832, MSG-12-04), "SCR-1202" (dòng 879).
- TL-03 bảng `oversight.complaint_response` (dòng 3127), `oversight.reopen_request` (dòng 3144, `DA_THUC_HIEN`, `DA_DONG`).
- `contracts/openapi/modules/oversight.yaml`: `reserveComplaintOpinion`, `getComplaint`, lược đồ `Complaint`.
- Bản trích `tl02b §QT-12 BE-Q12-08, 09`.

## 3. Phạm vi công việc

- `reserveComplaintOpinion` (`Idempotency-Key`): chỉ người khiếu nại, khiếu nại `DA_TRA_LOI`, trong 5 ngày làm việc; mã hoá `reserve_opinion_enc`, tạo `complaint_response` loại `BAO_LUU`; đóng khiếu nại `DONG`; gọi cổng MOD-04 đưa hồ sơ về `DA_KY_SO`; cung cấp dữ liệu bảo lưu cho SVC-11 in Mẫu 3 qua cổng đọc.
- Tác vụ hẹn giờ theo ngày làm việc: `DA_TRA_LOI` giữ nguyên hết hạn xác nhận → `DONG`; `KHONG_THU_LY` đã gửi thông báo → `DONG`; chạy lại an toàn, mỗi lần đóng ghi sổ cái.
- Đóng `DANG_THI_HANH` khi yêu cầu mở lại gắn khiếu nại chuyển `DA_THUC_HIEN` và việc tính lại xong (nhận sự kiện tương ứng do CORE-07-09 phát hoặc gọi qua cổng ứng dụng nội bộ MOD-07; ghi rõ cách chọn).
- Bảo đảm mọi điểm giải mã `content_enc`, `request_text_enc`, `response_enc`, `reserve_opinion_enc` đi qua thư viện ghi nhật ký giải mã của CORE-00-05; kiểm thử phủ từng điểm.
- Gợi ý trùng: khi mở khiếu nại `MOI`, so cấu phần (`target_stage`, cấu phần ảnh chụp) với khiếu nại đã trả lời cùng hồ sơ, trả danh sách mã khiếu nại nghi trùng cho người xử lý; người xử lý quyết định.
- Điểm lệch cần lưu ý: hợp đồng chưa có trường hay endpoint trả danh sách khiếu nại nghi trùng; nếu lược đồ `Complaint` không có chỗ phù hợp thì không tự thêm trường, ghi đề xuất task sửa hợp đồng vào `wiki/14` và chỉ làm phần tính gợi ý ở lớp ứng dụng.

## 4. Ngoài phạm vi

- In Mẫu 3 (DV-11-03); mở lại kỳ (CORE-07-07..09).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử: bảo lưu sau 5 ngày làm việc bị 422; tác vụ đóng chạy hai lần không ghi sổ cái hai lần; mỗi lần đọc nội dung C2 có đúng một bản ghi nhật ký giải mã.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
