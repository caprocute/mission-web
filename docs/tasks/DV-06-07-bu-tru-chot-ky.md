# DV-06-07 · QT-08 Bù trừ saga chốt kỳ, chạy lại hồ sơ lỗi, kiểm tất định bằng chạy lại mẫu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-06-06 |
| Truy vết | QT-08 → SCR-0801 → `POST /api/v1/periods/{id}/lock/cancel` (`requestPeriodLockCancel`), `.../lock/cancel/approve` (`approvePeriodLockCancel`), `.../lock/retry-failed` (`retryFailedPeriodLock`), `GET .../lock/failures` (`listPeriodLockFailures`) (MOD-08 gọi xuống SVC-06) → bảng `flowable.saga_instance`, `flowable.saga_step`, `scoring.score_job` (`KIEM_TRA_TAI_LAP`) → EVT-26 `config.period.unlocked.v1`, EVT-10; BR-08-09, BR-08-11, BR-08-12; MSG-08-05, MSG-08-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Saga chốt kỳ phải hoàn tác được an toàn khi cần huỷ, chạy lại được những hồ sơ tính lỗi mà không tính lại cả kỳ, và chứng minh tính điểm là tất định. Khi xong, huỷ khoá kỳ cần hai người duyệt và chỉ được trước khi bước ký hoàn tất, bù trừ chạy theo thứ tự ngược và đưa kỳ về `MO` với EVT-26; hồ sơ lỗi chạy lại thành lô mới; mỗi lần chốt kỳ có 0,1% hồ sơ ngẫu nhiên được tính lại và so khớp với kết quả đã lưu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08: bù trừ (dòng 2133–2142), điều khiển (dòng 2233–2236), ngoại lệ chấp nhận để lại hồ sơ lỗi (dòng 2184), BR-08-09, BR-08-11, BR-08-12, MSG-08-05, MSG-08-08.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` saga chốt kỳ (dòng 4359–4378: bù trừ bước 1 phát EVT-26) và bảng `scoring.score_job` (dòng 3332).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-26 KyDaHuyKhoa, `config.period.unlocked.v1`).
- Hợp đồng: `contracts/openapi/modules/config.yaml` các thao tác ở bảng (mã `PERIOD-409-POINT-OF-NO-RETURN`).
- Wiki: `wiki/15-phan-quyen.md` (bốn mắt), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Bù trừ toàn bộ theo thứ tự ngược: huỷ yêu cầu ký đang chờ; đánh dấu thu hồi tệp đã sinh (không xoá do Object Lock); đánh dấu lô điểm `superseded`, không xoá; trả kỳ về `MO`, mở ghi, gỡ cờ "không tính trong kỳ", phát EVT-26 qua MOD-08; bước tiền điều kiện và bước hoàn tất không có bù trừ.
- Chỉ cho bù trừ khi chưa qua điểm không quay lại (trước khi bước ký hoàn tất); sau đó trả 409 `PERIOD-409-POINT-OF-NO-RETURN` (BR-08-09); hai người duyệt (người đề nghị và AC-09 khác người) được MOD-08 kiểm, SVC-06 kiểm lại trạng thái saga.
- Chạy lại hồ sơ lỗi: nhận yêu cầu từ `retryFailedPeriodLock` (Idempotency-Key), tạo lô mới chỉ cho hồ sơ trong hàng đợi lỗi, phát EVT-10 khi tính xong; hỗ trợ ghi nhận "chấp nhận để lại hồ sơ lỗi kèm lý do" để saga đi tiếp (TL-02a dòng 2184; hợp đồng chưa có thao tác riêng, xem điểm lệch).
- Kiểm tất định (BR-08-11): sau bước tính điểm, chọn ngẫu nhiên 0,1% hồ sơ, tạo `score_job` loại `KIEM_TRA_TAI_LAP`, so `result_hash` với kết quả đã lưu; lệch thì dừng saga ở `LOI_CAN_XU_LY` và cảnh báo vận hành.
- Cảnh báo lịch khoá muộn hơn tham số để báo cáo trước ngày 10 tháng đầu quý sau (BR-08-12) khi lập lịch saga.
- Kiểm thử: huỷ ở từng bước trước điểm không quay lại đều về `MO` và phát EVT-26 đúng một lần; huỷ sau điểm không quay lại bị 409; chạy lại 100 hồ sơ lỗi chỉ tính đúng 100 hồ sơ; kiểm tất định phát hiện được một kết quả bị sửa.
- Điểm lệch cần lưu ý: TL-02a (dòng 2138) ghi sự kiện huỷ khoá kỳ "đề xuất, chưa có mã EVT", nhưng baseline mục 7 đã cấp EVT-26 KyDaHuyKhoa; dùng EVT-26. Thao tác "chấp nhận để lại hồ sơ lỗi" chưa có trong hợp đồng; hiện thực phía SVC-06 dưới dạng lệnh nội bộ và ghi đề nghị bổ sung thao tác vào wiki trang 14 cho task sửa hợp đồng.

## 4. Ngoài phạm vi

- API điều khiển ở MOD-08 và kiểm hai người duyệt (CORE-08-05); khoá lại sau hậu kiểm (task saga mở lại của QT-13).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `ops/runbooks/` (mục huỷ khoá kỳ và chạy lại hồ sơ lỗi)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh với bốn nhóm ca ở mục 3.
- Không có lệnh bù trừ nào xoá dữ liệu đã niêm phong hoặc đối tượng có Object Lock.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
