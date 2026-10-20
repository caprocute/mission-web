# CORE-01-08 · QT-03/SCR-0301 Check-in KR chỉ ghi thêm, nháp và gửi idempotent

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-02; cần CORE-08-03 (chặn ghi kỳ đã khoá) và CORE-01-06 (mục tiêu `DA_DUYET` để kiểm BR-03-01) (bổ sung) |
| Truy vết | QT-03 → SCR-0301, SCR-0302 → API `POST /api/v1/okr/key-results/{id}/checkins`, `GET /okr/key-results/{id}/checkins`, `GET /okr/key-results?owner=me&needCheckin=true` → MOD-01 → bảng `okr.kr_checkin`, `okr.key_result`, `okr.objective`, `okr.idempotency_key` → BR-03-01..BR-03-05 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực check-in KR theo đúng ADR-04 tầng 1 (trực tiếp trong MOD-01, không qua BPMN): lưu nháp chỉ người tạo thấy, gửi một lần bằng `Idempotency-Key`, bản đã gửi bất biến; khi gửi, máy chủ cập nhật giá trị hiện tại của KR và tính lại tiến độ mục tiêu trong cùng giao dịch, trả kết quả trong ≤ 1 giây. Đây là thao tác thường xuyên nhất của QT-03 (khoảng 1,2 triệu lần mỗi tuần, 40% dồn vào chiều thứ Sáu), và là nguồn chính cho bảng điều hành.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 "Thông tin chung" (tải, L994), "Mô tả các bước" (L1011–1025), "Trạng thái đối tượng" (L1027–1042), "Quy tắc nghiệp vụ" (L1044–1055), "Ngoại lệ và xử lý" (L1057–1066), SCR-0301 (L1069–1111), SCR-0302 (L1112–1144).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng okr.kr_checkin", "Bảng idempotency_key".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Quản lý giao dịch" (mẫu `UpdateProgressUseCase`, L710–752).
- `contracts/openapi/modules/okr.yaml`: `createOkrCheckin`, `listOkrCheckins`, `listOkrKeyResults` (tham số `owner=me`, `needCheckin`), lược đồ `OkrCheckinCreate`, `OkrCheckinResult`.

## 3. Phạm vi công việc

- Hiện thực `createOkrCheckin` với `status=NHAP` (lưu hoặc ghi đè bản nháp duy nhất của người tạo cho KR đó) và gửi (`DA_GUI`, bắt buộc `Idempotency-Key`); gửi lại cùng khoá trả đúng kết quả cũ (khoá UQ `(tenant_id, idempotency_key, period_bucket)`), đúng yêu cầu gửi lại sau mất sóng của di động.
- Kiểm BR-03-01 (KR thuộc mục tiêu `DA_DUYET`, kỳ `MO`; kỳ khoá trả 409 kèm MSG-03-05), BR-03-02 (bắt buộc giá trị hiện tại và mức tự tin `CAO`/`TRUNG_BINH`/`THAP`), BR-03-03 (không có API sửa, xoá check-in đã gửi), giá trị tối đa 4 chữ số thập phân, kết quả nổi bật và vướng mắc ≤ 1.000 ký tự, vướng mắc bắt buộc khi tự tin Thấp nếu Policy Pack bật.
- Kiểm BR-03-05: giá trị thay đổi > 50% khoảng chỉ tiêu so với lần trước thì trả 422 `OKR-422-LARGE-CHANGE-UNCONFIRMED` (MSG-03-02) trừ khi yêu cầu mang `confirmLargeChange=true`.
- Người được check-in là chủ sở hữu KR hoặc người được giao check-in thay (kiểm qua port để CORE-01-12 cắm vào); người khác nhận 403.
- Khi gửi: cập nhật `key_result.current_value`, tính lại tiến độ KR và mục tiêu bằng `ProgressCalculator` của CORE-01-02, khoá lạc quan trên `key_result`; tất cả trong một giao dịch có `timeout = 5`.
- Hiện thực `listOkrCheckins` (con trỏ, mới nhất trước, chỉ người tạo thấy bản nháp của mình) và bộ lọc `owner=me`, `needCheckin` của `listOkrKeyResults` cho danh sách KR cần check-in của SCR-0301, SCR-0302 và khối trang chủ SCR-0002.
- Để chỗ cho CORE-01-09 (cờ rủi ro, EVT-02) và CORE-01-10 (số liệu nguồn, ghi đè) bằng điểm mở rộng rõ ràng trong use case gửi, không hiện thực trước.

## 4. Ngoài phạm vi

- Cờ rủi ro và phát EVT-02 (CORE-01-09); số liệu nguồn và BR-03-04 (CORE-01-10); nhắc check-in (CORE-01-11); uỷ quyền check-in thay (CORE-01-12).
- Kịch bản tải đỉnh chiều thứ Sáu ở môi trường perf (nhóm PERF, phối hợp TL-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử tích hợp Testcontainers: gửi hai lần cùng `Idempotency-Key` chỉ tạo một dòng `DA_GUI`; gửi cùng khoá với nội dung khác trả 422; check-in KR của mục tiêu `CHO_DUYET` bị từ chối; check-in khi kỳ `DA_KHOA` trả 409 với MSG-03-05.
- Sau khi gửi, `current_value` của KR và tiến độ mục tiêu khớp công thức; hai lần gửi đồng thời vào cùng KR không làm mất cập nhật (một bản thắng, bản kia thử lại hoặc nhận 409).
- Kiểm thử vi mô: thời gian xử lý gửi phía máy chủ p95 ≤ 200 ms trên Testcontainers (suy luận, để giữ mục tiêu ≤ 1 giây đầu cuối).
- Kiểm thử hợp đồng với `okr.yaml` cho ba operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
