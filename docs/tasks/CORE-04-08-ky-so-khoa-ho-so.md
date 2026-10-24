# CORE-04-08 · EVT-18, EVT-12 Chuyển `DA_KY_SO` khi ký xong và khoá hồ sơ khi hết hạn khiếu nại

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-04-07 (bổ sung đề xuất: CORE-08-04 cho hàm ngày làm việc) |
| Truy vết | QT-09 bước 13–14 → MOD-04 → `appraisal.appraisal_profile` (`DA_QUYET_DINH` → `DA_KY_SO` → `DA_KHOA`), `appraisal.batch_decision` → nhận EVT-18 (`esign.signature.completed.v1`) → phát EVT-12 (`appraisal.rating.decided.v1`), EVT-11, EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi SVC-10 báo ký xong từng tài liệu, MOD-04 hoàn tất bước chuyển trạng thái tương ứng (tự đánh giá, thẩm định, quyết định), với quyết định thì chuyển hồ sơ sang `DA_KY_SO`, phát EVT-12 để MOD-06 lập gói hồ sơ và thông báo kết quả cho cán bộ (MSG-09-11). Hết thời hạn khiếu nại mà không có khiếu nại mở thì hồ sơ tự sang `DA_KHOA`. Đây là chỗ nối QT-09 với QT-11 và QT-12.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: "Mô tả các bước" (dòng 40, bước 13–14), "Trạng thái đối tượng" (dòng 65), BR-09-15 (dòng 87), MSG-09-11, MSG-09-16 (dòng 279, 330); QT-12 BR-12-01 (thời hạn khiếu nại 10 ngày làm việc).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 7 (EVT-12, EVT-18, người nhận).
- TL-03 mục "Inbox idempotent" (dòng 4269).
- Bản trích `tl02b §QT-09 BE-Q09-10, 12`.

## 3. Phạm vi công việc

- Viết consumer EVT-18 có inbox và kiểm thứ tự theo `aggregate_version`: tra yêu cầu ký thuộc tài liệu nào của MOD-04 (phiếu tự đánh giá, kết quả thẩm định, quyết định) và gọi lệnh domain tương ứng của CORE-04-03; EVT-18 của tài liệu không thuộc MOD-04 bị bỏ qua sạch.
- Với quyết định: chuyển `DA_QUYET_DINH` → `DA_KY_SO`, ghi outbox EVT-12 (kèm `periodType`, luồng, mức, không chứa C2) và EVT-21 (MSG-09-11 kèm hạn khiếu nại) trong cùng giao dịch; cập nhật tiến độ `batch_decision` và phát MSG-09-16 khi lô xong.
- Viết tác vụ hẹn giờ hạn khiếu nại: tính hạn theo tham số `khieu_nai.thoi_han` (mặc định 10 ngày làm việc) bằng thư viện ngày làm việc; quá hạn và không có khiếu nại mở thì chuyển `DA_KHOA`, phát EVT-11; chạy lại an toàn.
- Hồ sơ đang `KHIEU_NAI` không bị khoá tự động; chuyển `DA_KHOA` khi khoá gói hồ sơ (gọi từ MOD-06 qua cổng sự kiện) được xử lý cùng lệnh `lock`.
- Đăng ký lược đồ Avro EVT-12 nếu chưa có (BACKWARD).

## 4. Ngoài phạm vi

- Nhận EVT-25 chuyển `KHIEU_NAI` (CORE-07-02); kết luận khiếu nại (CORE-07-04); lập gói (CORE-06-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `contracts/events/avro/` (chỉ thêm lược đồ EVT-12 nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử: EVT-18 trùng chỉ chuyển trạng thái một lần và phát EVT-12 một lần; lô 1.000 hồ sơ nhận 1.000 EVT-18 xử lý hết trong thời gian ghi vào báo cáo; hồ sơ có khiếu nại mở không bị khoá khi tới hạn.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
