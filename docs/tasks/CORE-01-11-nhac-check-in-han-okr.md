# CORE-01-11 · QT-02/QT-03 Nhắc check-in, nhắc trước hạn và quá hạn OKR

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-01-08, DV-12-01; cần CORE-08-04 (lịch ngày làm việc) và DV-06-03 (timer của `duyet-okr`) (bổ sung) |
| Truy vết | QT-02, QT-03 → (tác vụ nền, không có SCR) → MOD-01, SVC-12, SVC-06 → bảng `okr.key_result`, `okr.kr_checkin`, `okr.objective`, `okr.outbox_event` → EVT-21; BR-03-08; MSG-02-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực các tác vụ nền nhắc việc của OKR: sáng thứ Năm nhắc chủ sở hữu các KR chưa check-in trong chu kỳ theo tần suất của KR; phát hiện KR quá hạn check-in hai chu kỳ liên tiếp để ghi nhận cho người đứng đầu (BR-03-08); nhắc người duyệt trước hạn duyệt OKR một ngày (MSG-02-12) và nhắc khi quá hạn. Mọi nhắc việc đi qua EVT-21 để notification-service gửi, lõi không tự gửi thông báo.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 "Mô tả các bước" bước 1 (L1015), BR-03-08 (L1044–1055); QT-02 "Ngoại lệ và xử lý" (quá hạn duyệt, người duyệt vắng, L837–838), SCR-0203 (nhắc trước hạn, L976).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` đoạn về timer nhắc của mẫu BPMN (`remindAfter` trong `wf_binding`, L1953).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` bảng sự kiện EVT-21.
- `contracts/openapi/modules/okr.yaml` lược đồ `OkrCheckinFrequency` (`TUAN`, `HAI_TUAN`, `THANG`).

## 3. Phạm vi công việc

- Viết tác vụ định kỳ chạy 07:00 thứ Năm theo múi giờ `Asia/Ho_Chi_Minh` (giờ chạy cấu hình được): với mỗi tenant, tìm KR của mục tiêu `DA_DUYET` trong kỳ `MO` chưa có check-in `DA_GUI` trong chu kỳ hiện tại theo `checkin_frequency`, ghi EVT-21 theo lô tới chủ sở hữu và người được giao check-in thay.
- Bảo đảm tác vụ chỉ chạy một lần trên nhiều bản sao pod (khoá bằng CSDL hoặc cơ chế lập lịch do CORE-00-01 cung cấp; không thêm thư viện mới nếu chưa được duyệt), xử lý theo lô có giới hạn và tiếp tục được sau khi pod khởi động lại.
- Phát hiện KR quá hạn check-in hai chu kỳ liên tiếp (BR-03-08): ghi EVT-21 tới người đứng đầu đơn vị và cung cấp số liệu cho bảng điều hành qua sự kiện (việc hiển thị và dấu hiệu bất thường thuộc SVC-15, suy luận).
- Nhắc trước hạn duyệt OKR một ngày (MSG-02-12) và nhắc quá hạn tới người duyệt và người đứng đầu: nếu DV-06-03 đã hiện thực bằng timer của `duyet-okr`, task chỉ hiện thực external worker nhận job nhắc và ghi EVT-21, không dựng tác vụ trùng; ghi rõ lựa chọn trong báo cáo.
- Tôn trọng lịch ngày làm việc của tenant (CORE-08-04): ngày nghỉ thì dời nhắc sang ngày làm việc kế tiếp (suy luận).

## 4. Ngoài phạm vi

- Kênh gửi (đẩy, thư trong ứng dụng, SMS) và tuỳ chọn nhận thông báo của người dùng (DV-12-01).
- Bảng điều hành và phát hiện dấu hiệu bất thường (SVC-15).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử với đồng hồ giả lập: KR tần suất `TUAN` chưa check-in tuần này nhận đúng một nhắc; KR đã check-in không nhận; KR `THANG` chỉ nhận trong tuần cuối chu kỳ tháng (quy tắc chọn tuần là suy luận, ghi rõ).
- Hai bản sao chạy cùng lúc chỉ sinh một bộ EVT-21; chạy lại sau lỗi giữa chừng không gửi trùng.
- Kiểm thử hiệu năng: 100.000 KR trong một tenant được quét xong dưới 2 phút trên Testcontainers (suy luận).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
