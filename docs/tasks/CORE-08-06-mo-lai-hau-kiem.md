# CORE-08-06 · QT-13 Mở lại hậu kiểm ở MOD-08

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a. trạng thái và bộ đệm phạm vi; b. consumer EVT-22 và timer hết hạn khoá lại) |
| Phụ thuộc | CORE-08-03 |
| Truy vết | QT-13 → BR-13-01, BR-13-05, MSG-13-04 → MOD-08 → `config.period` (`MO_LAI_HAU_KIEM`), `config.period_status_log` (`cause_type` `MO_LAI_HAU_KIEM`, `KHOA_LAI`) → nhận EVT-22 (`oversight.period.reopened.v1`), phát EVT-09 khi khoá lại |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi MOD-07 phát EVT-22 sau khi đủ hai phê duyệt mở lại kỳ, MOD-08 chuyển kỳ sang `MO_LAI_HAU_KIEM` chỉ trong phạm vi được mở (hồ sơ, bảng, trường) và thời hạn được duyệt; mọi lệnh ghi ngoài phạm vi vẫn nhận 409 `period-locked`; hết hạn hoặc hậu kiểm xong thì kỳ tự khoá lại qua saga phạm vi hẹp và phát EVT-09. Khi xong, kịch bản mở lại → sửa trong phạm vi → hết hạn → khoá lại chạy đầu cuối có kiểm thử.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` mục "QT-13. Hậu kiểm, mở lại kỳ (hai người duyệt)": "Mô tả các bước" (dòng 962), "Trạng thái đối tượng" (dòng 984), "Quy tắc nghiệp vụ" (dòng 999, BR-13-01, BR-13-05), bảng MSG-13-04 (dòng 1076).
- `TL-02a-quy-trinh.md` QT-08 "Trạng thái đối tượng" (dòng 2146: `DA_KHOA` → `MO_LAI_HAU_KIEM` → `DA_KHOA`) và đoạn khoá lại sau hậu kiểm (BE-08-14 trong bản trích).
- `TL-03-thiet-ke-csdl.md` "Bảng config.period" và "Bảng config.period_status_log (bổ sung)".
- `contracts/events/avro/` lược đồ EVT-22 và EVT-09 (nếu đã có; nếu chưa, đối chiếu baseline danh mục EVT).
- Bản trích `extract/tl02b-may-chu.md` QT-13 (BE-Q13-04, BE-Q13-05).

## 3. Phạm vi công việc

- Consumer EVT-22 có inbox khử trùng lặp: chuyển `config.period` `DA_KHOA` → `MO_LAI_HAU_KIEM`, tăng `reopen_count`, ghi `period_status_log` với `cause_ref` là mã yêu cầu `MLK-yyyy-nnn`, lưu `scope_json` và `open_until` lấy từ sự kiện.
- Mở rộng bộ đệm của CORE-08-03 để nạp phạm vi mở (tập đối tượng được phép ghi); `assertWritable` nhận thêm định danh đối tượng và cho ghi khi đối tượng nằm trong phạm vi, ngoài phạm vi trả 409 với `detail` theo MSG-13-04; giữ mục tiêu < 0,1 ms mỗi lần kiểm.
- Timer hết hạn: tác vụ định kỳ idempotent tìm kỳ `MO_LAI_HAU_KIEM` quá `open_until` (tính theo ngày làm việc), khởi chạy saga khoá lại phạm vi hẹp ở SVC-06, đánh dấu hồ sơ "chưa hoàn tất hậu kiểm" theo ngoại lệ QT-13 bằng sự kiện cho MOD-07 (không gọi trực tiếp).
- Khi saga khoá lại xong: kỳ về `DA_KHOA`, ghi `cause_type` `KHOA_LAI`, phát EVT-09 qua outbox.
- Kiểm thử ma trận: ghi trong phạm vi khi đang mở thành công; ghi ngoài phạm vi 409; ghi sau khi khoá lại 409; nhận EVT-22 hai lần chỉ xử lý một lần.

## 4. Ngoài phạm vi

- Tạo và duyệt yêu cầu mở lại, OPA bốn mắt (nhóm CORE-07).
- Tính lại, sinh lại, ký lại sau EVT-22 (SVC-07, SVC-11, SVC-10).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka) cho bốn kịch bản ở mục 3.
- Đo kiểm phạm vi khi trúng đệm < 0,1 ms mỗi lần.
- Trigger chuyển trạng thái kỳ của CORE-08-01 chấp nhận đúng hai chuyển liên quan, không cần sửa migration cũ.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
