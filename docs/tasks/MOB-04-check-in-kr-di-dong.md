# MOB-04 · QT-03/SCR-0302 Check-in KR trên di động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-02, CORE-01-08 |
| Truy vết | QT-03 → SCR-0302 (BR-03-02, BR-03-05, MSG-03-06, MSG-03-07) → API `GET /api/v1/okr/key-results` (`listOkrKeyResults`), `GET /api/v1/okr/key-results/{id}/source-value` (`getOkrKeyResultSourceValue`), `POST /api/v1/okr/key-results/{id}/checkins` (`createOkrCheckin`, Idempotency-Key), `POST /api/v1/ai/speech-to-text` (`transcribeSpeech`) → MOD-01, SVC-14 → bảng `okr.key_result`, `okr.kr_checkin` → EVT-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cán bộ và đảng viên ở chi bộ cơ sở check-in KR trên điện thoại trong ba chạm kể từ thông báo "Check-in tuần": mở KR, chọn mức tự tin (giá trị nguồn đã điền sẵn), gửi. Khi mất sóng, check-in được lưu mã hoá trên máy và tự gửi khi có mạng với cùng khoá chống ghi trùng, đúng đặc tả SCR-0302.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: QT-03 "Quy tắc nghiệp vụ" (BR-03-02 bắt buộc giá trị và mức tự tin, BR-03-05 cảnh báo thay đổi > 50% khoảng chỉ tiêu), đặc tả SCR-0301 và SCR-0302 "Check-in trên di động" (dòng 1112: bảng trường, bảng chức năng, MSG-03-06, MSG-03-07), "Liên kết thiết kế".
- Hợp đồng `contracts/openapi/modules/okr.yaml` (`listOkrKeyResults`, `getOkrKeyResultSourceValue`, `createOkrCheckin`, ví dụ `channel: MOBILE`), `contracts/openapi/modules/platform.yaml` (`transcribeSpeech`).
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0301, SCR-0302).

## 3. Phạm vi công việc

- Dựng tab "OKR": danh sách KR cần check-in theo hạn, mỗi KR có giá trị trên chỉ tiêu, mức tự tin lần trước và nút "Check-in"; kèm dòng giải thích dữ liệu được lưu tạm có mã hoá khi mất sóng.
- Dựng màn hình nhập: giá trị hiện tại bằng thanh trượt hoặc gõ số (bước 0,1 theo đơn vị), mặc định là giá trị nguồn (`getOkrKeyResultSourceValue`) hoặc lần trước; ba nút mức tự tin cỡ lớn; ô vướng mắc ≤ 1.000 ký tự; nút "Gửi check-in" cố định cuối màn hình.
- Cảnh báo ngay trên máy khi giá trị thay đổi quá 50% khoảng chỉ tiêu (BR-03-05) và yêu cầu xác nhận; máy chủ vẫn kiểm lại.
- Nút "Nói để nhập": ưu tiên nhận dạng trên thiết bị, nếu không có thì gọi `transcribeSpeech`; hiện văn bản để người dùng xác nhận trước khi điền.
- Gửi `createOkrCheckin` với `channel = MOBILE` và `Idempotency-Key` sinh khi bấm gửi; mất sóng thì xếp hàng qua MOB-02, hiện MSG-03-06; có mạng lại thì hiện MSG-03-07 với số check-in đã gửi.
- Nhận liên kết sâu từ thông báo đẩy (MOB-09) mở thẳng màn hình nhập của KR, bảo đảm ba chạm.

## 4. Ngoài phạm vi

- Tạo, sửa, duyệt OKR (chỉ trên máy tính); thông báo đẩy (MOB-09); chế độ cao tuổi (MOB-08).

## 5. Tệp được phép sửa

- `mobile/lib/features/okr/**`, `mobile/test/**`, `mobile/integration_test/**`, tệp ARB tiếng Việt trong `mobile/`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh (bắt buộc giá trị và mức tự tin, cảnh báo > 50%, giữ nguyên khoá idempotency khi gửi lại).
- Kiểm thử tích hợp với máy chủ giả lập theo hợp đồng: ba check-in khi mất mạng được gửi đúng ba lần khi có mạng, máy chủ không nhận trùng.
- Đếm chạm từ thông báo tới gửi thành công = 3; `meetsGuideline` đạt cho hai màn hình.
- Chạy trên trình giả lập Android và iOS (ghi rõ nền tảng chưa chạy được); báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
