# CORE-01-09 · QT-03/BR-03-06 Cờ rủi ro KR và EVT-02

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-01-08 |
| Truy vết | QT-03 → SCR-0301, SCR-0201 → API `POST /api/v1/okr/key-results/{id}/checkins` → MOD-01 → bảng `okr.key_result` (`risk_flag`), `okr.kr_checkin`, `okr.outbox_event` → EVT-02 `okr.keyresult.checkedin.v1`, EVT-21 `notify.message.requested.v1`; BR-03-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bổ sung vào use case gửi check-in máy trạng thái cờ rủi ro của KR và phát sự kiện: mỗi check-in gửi đi ghi EVT-02 vào outbox cùng giao dịch; KR có mức tự tin Thấp hai lần liên tiếp tự chuyển `RUI_RO`, phát EVT-21 để thông báo lãnh đạo đơn vị và đưa lên bảng điều hành trong ≤ 5 phút. Lãnh đạo nhờ vậy thấy sớm KR có nguy cơ không đạt mà không cần đọc từng check-in.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 "Trạng thái đối tượng" (cờ rủi ro, L1027–1042), "Quy tắc nghiệp vụ" BR-03-06, "Mô tả các bước" bước 8–9 (L1022–1023), "Liên kết thiết kế" (L1145–1147); QT-02 SCR-0201 (MSG-02-01 KR Thấp hai tuần, L886).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` bảng sự kiện EVT-02, EVT-21.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Outbox" (L754).
- `contracts/openapi/modules/okr.yaml`: `createOkrCheckin` (`evt: [EVT-02, EVT-21]`), lược đồ `OkrRiskFlag`, `OkrCheckinResult`.

## 3. Phạm vi công việc

- Viết lớp domain `RiskFlagPolicy` thuần: `BINH_THUONG` → `THEO_DOI` khi một lần Thấp (không thông báo); `THEO_DOI` → `RUI_RO` khi Thấp lần thứ hai liên tiếp; `THEO_DOI` → `BINH_THUONG` khi Trung bình hoặc Cao; `RUI_RO` → `BINH_THUONG` sau hai lần liên tiếp không Thấp; chỉ tính check-in `DA_GUI`, theo thứ tự `checkin_at`.
- Gọi `RiskFlagPolicy` trong use case gửi check-in của CORE-01-08, cập nhật `key_result.risk_flag` trong cùng giao dịch và trả cờ mới trong `OkrCheckinResult`.
- Ghi outbox EVT-02 (`KrDaCheckIn`) cho mỗi check-in `DA_GUI`, khoá thông điệp `tenantId:keyResultId` để giữ thứ tự theo KR; payload không chứa dữ liệu C2.
- Khi KR chuyển sang `RUI_RO`, ghi thêm outbox EVT-21 tới lãnh đạo đơn vị sở hữu (người nhận phân giải qua `org_replica`), nội dung theo MSG-02-01.
- Viết schema Avro `KrDaCheckIn` cho topic `okr.keyresult.checkedin.v1` trong `contracts/events/avro/` nếu chưa có, chế độ tương thích BACKWARD.

## 4. Ngoài phạm vi

- Gửi thông báo thật (DV-12-01) và hiển thị trên bảng điều hành (SVC-15, DV-15-03).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `contracts/events/avro/okr/**` (chỉ thêm schema EVT-02 nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử đơn vị bảng chuyển đủ các chuỗi: Thấp–Thấp ra `RUI_RO`; Thấp–Cao–Thấp chỉ ra `THEO_DOI`; `RUI_RO` rồi Cao–Trung bình về `BINH_THUONG`; `RUI_RO` rồi Cao–Thấp vẫn `RUI_RO`.
- Kiểm thử tích hợp: mỗi check-in gửi có đúng một bản ghi EVT-02 trong outbox cùng giao dịch; lần chuyển `RUI_RO` có thêm đúng một EVT-21; giao dịch lỗi thì không còn sự kiện nào.
- Schema Avro qua kiểm tra tương thích BACKWARD; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
