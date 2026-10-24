# CORE-01-10 · QT-03/BR-03-04 Số liệu nguồn của KR qua cổng tích hợp, ghi đè có lý do

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-08, DV-13-08 |
| Truy vết | QT-03 → SCR-0301 → API `GET /api/v1/okr/key-results/{id}/source-value`, `POST /okr/key-results/{id}/checkins` → MOD-01, SVC-13 → bảng `okr.key_result` (`data_source`, `formula_text`), `okr.kr_checkin` (`source_value`, `source_fetched_at`, `override_reason`) → BR-03-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho KR có nguồn số liệu (ví dụ CSDL đảng viên, HRM) lấy giá trị gợi ý qua integration-gateway trong ≤ 3 giây, lưu kèm thời điểm lấy; người dùng xác nhận hoặc ghi đè kèm lý do, và máy chủ lưu cả giá trị nguồn lẫn giá trị người nhập để người xác nhận KR đối chiếu. Nguồn lỗi không chặn check-in: người dùng nhập tay và bản ghi được đánh dấu nhập tay.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 "Mô tả các bước" bước 3 và đoạn giải thích ngay sau bảng (L1017, L1025), BR-03-04 (L1044–1055), "Ngoại lệ và xử lý" (nguồn lỗi, L1061), SCR-0301 (L1099–1101).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` phần chịu lỗi khi gọi service khác của SVC-05 và chương tích hợp (bộ kết nối HRM, CSDL đảng viên).
- `contracts/openapi/modules/okr.yaml`: `getOkrKeyResultSourceValue` (502 `OKR-502-SOURCE-UNAVAILABLE`, MSG-03-03), `createOkrCheckin` (trường `sourceValue`, `overrideReason`), lược đồ `OkrSourceValue`.
- Tài liệu cổng nội bộ do DV-13-08 công bố cho số liệu nguồn của KR.

## 3. Phạm vi công việc

- Viết port `KrSourceValuePort` trong `okr.application` và adapter gọi SVC-13 trong `okr.infrastructure` với timeout tổng 3 giây, ngắt mạch (circuit breaker) và không thử lại quá ngân sách thời gian; lời gọi nằm ngoài giao dịch CSDL.
- Hiện thực `getOkrKeyResultSourceValue`: chỉ cho KR có `data_source`; trả `value`, `fetchedAt`, `dataSource`; lỗi hoặc quá hạn trả 502 `OKR-502-SOURCE-UNAVAILABLE` gắn MSG-03-03.
- Trong use case gửi check-in: nhận `sourceValue`, `sourceFetchedAt` do client gửi lại từ lần lấy gần nhất, kiểm BR-03-04 (giá trị nhập khác `source_value` thì `override_reason` bắt buộc, MSG-03-04), lưu cả hai giá trị; check-in không có số liệu nguồn do lỗi được đánh dấu nhập tay (cách đánh dấu theo trường hợp đồng hoặc `channel`, ghi rõ lựa chọn).
- Chống giả mạo giá trị nguồn (suy luận): máy chủ so `sourceValue` client gửi với giá trị lưu đệm ngắn hạn của lần lấy gần nhất theo KR và người dùng; lệch thì coi như ghi đè và yêu cầu lý do.
- Ghi chỉ số OpenTelemetry cho thời gian và tỷ lệ lỗi gọi nguồn theo `data_source`.

## 4. Ngoài phạm vi

- Bộ kết nối HRM, CSDL đảng viên trong integration-gateway (DV-13-08).
- Tính KR theo công thức tự động từ nhiều nguồn (không có trong TL).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, dùng WireMock hoặc stub HTTP cho SVC-13 (không gọi hệ thống thật).
- Kiểm thử: nguồn trả sau 5 giây thì API trả 502 trong ≤ 3,2 giây; ngắt mạch mở sau chuỗi lỗi và đóng lại khi nguồn phục hồi.
- Check-in có giá trị khác nguồn mà thiếu lý do trả 422 với MSG-03-04; có lý do thì lưu đủ `source_value`, `source_fetched_at`, `override_reason`.
- Kiểm thử hợp đồng với `okr.yaml` cho `getOkrKeyResultSourceValue` xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
