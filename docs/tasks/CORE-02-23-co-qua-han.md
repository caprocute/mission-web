# CORE-02-23 · QT-06 Tác vụ 00:05 đặt cờ quá hạn, nhắc, tính lại khi lịch nghỉ đổi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-23` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-02-19, CORE-08-04; cần DV-12-01 (gửi EVT-21) (bổ sung) |
| Truy vết | QT-06 bước 8 → (tác vụ nền) → MOD-02, MOD-08 (lịch ngày làm việc) → bảng `task.task` (chỉ mục `ix_task__overdue_p`, `due_date`, `score_due_date`), `task.outbox_event` → EVT-21; MSG-06-03 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mỗi ngày lúc 00:05 giờ Việt Nam, đặt cờ quá hạn cho nhiệm vụ đã qua hạn tính điểm mà chưa `CHO_NGHIEM_THU` hoặc `HOAN_THANH`, và gửi nhắc kèm hệ số C dự kiến (MSG-06-03) qua EVT-21. Khi lịch nghỉ của tenant thay đổi, hạn và số ngày chậm của nhiệm vụ chưa hoàn thành được tính lại. Cờ quá hạn là thuộc tính tính theo lịch ngày làm việc, không phải trạng thái (TL-01, TL-02a).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 "Mô tả các bước" bước 8 (L1704), "Ngoại lệ và xử lý" (lịch nghỉ đổi, L1750), QT-05 "Trạng thái đối tượng" (cờ `qua_han`, L1443).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Máy trạng thái nhiệm vụ" (cờ quá hạn là thuộc tính, L599–708).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "DDL mẫu: task.task" (chỉ mục `ix_task__overdue_p`).
- Thư viện ngày làm việc của CORE-08-04.

## 3. Phạm vi công việc

- Viết tác vụ định kỳ 00:05 `Asia/Ho_Chi_Minh` (giờ cấu hình được), chạy một lần trên nhiều bản sao, xử lý theo tenant và theo lô dùng chỉ mục `ix_task__overdue_p`, tiếp tục được sau khi pod khởi động lại.
- Xác định nhiệm vụ quá hạn: `score_due_date` (hoặc `due_date` khi chưa có) đã qua theo lịch ngày làm việc và trạng thái không thuộc `CHO_NGHIEM_THU`, `HOAN_THANH`, `HUY`; thời gian `TAM_DUNG` không tính (BR-06-10). Lưu cờ theo cách TL-03 cho phép (dùng chỉ mục một phần đã có; nếu cần cột cờ thì migration bổ sung, đánh dấu "bổ sung", ghi rõ lựa chọn).
- Ghi EVT-21 MSG-06-03 tới chủ trì và người giao cho nhiệm vụ mới quá hạn trong ngày, kèm số ngày làm việc chậm và C dự kiến (cùng công thức xem trước của CORE-02-21); không nhắc lặp mỗi ngày quá một lần cho cùng nhiệm vụ.
- Lắng nghe sự kiện ứng dụng "lịch nghỉ đã đổi" của MOD-08: tính lại cờ quá hạn và số ngày chậm cho nhiệm vụ chưa hoàn thành của tenant đó trong kỳ đang mở; nếu hạn tính điểm đổi thì phát EVT-06 để scoring-service tính lại (suy luận).
- Nhắc nhận việc khi nhiệm vụ `DA_GIAO` quá 2 ngày làm việc chưa nhận (tham số, L1433) được gộp vào cùng tác vụ nếu người phụ trách đồng ý; ghi trong báo cáo.

## 4. Ngoài phạm vi

- Quản trị lịch nghỉ (CORE-08-04); dự báo nguy cơ trễ `delay_risk` (SVC-15).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ migration mới nếu cần cột cờ)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử với đồng hồ giả lập: nhiệm vụ hạn thứ Sáu chưa nộp được đặt cờ sáng thứ Hai (qua cuối tuần đúng số ngày làm việc); nhiệm vụ `TAM_DUNG` không bị đặt cờ; thêm ngày nghỉ bù làm nhiệm vụ hết quá hạn sau khi tính lại.
- Hai bản sao chạy cùng lúc chỉ sinh một bộ EVT-21; 1 triệu nhiệm vụ mở trong một kỳ được quét xong dưới 5 phút trên Testcontainers (suy luận).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
