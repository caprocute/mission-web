# DV-13-09 · SVC-13/EVT-19, EVT-12 Gửi bản neo sổ cái cho bên thứ ba và chia sẻ quyết định xếp loại ra ngoài

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-02, DV-09-03; DV-13-06 (adapter NDXP tới CSDL CBCCVC) nên gộp trước cho chiều EVT-12 (bổ sung) |
| Truy vết | QT-09, QT-10 (xếp loại đã quyết định), QT-13 (BR-13-09 bản neo) → SVC-13 → bảng `integ.outbound_message`, `integ.connector`, `integ.inbox_processed`; biên nhận về `ledger.anchor_receipt` (SVC-09 ghi) → nhận EVT-19 `ledger.seal.created.v1`, EVT-12 `appraisal.rating.decided.v1` → ADR-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện hai luồng chiều ra có giá trị pháp lý của SVC-13: gửi bản neo gốc cell hằng ngày tới ít nhất hai bên độc lập với nhà vận hành và trả biên nhận cho ledger-service; chia sẻ quyết định xếp loại (EVT-12) lên CSDL quốc gia CBCCVC dưới dạng CloudEvents có ký. Khi xong, mỗi ngày có biên nhận bản neo từ hai nơi trở lên, và mỗi quyết định xếp loại được gửi đi và xác nhận đúng một lần.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Niêm phong Merkle, dấu thời gian và bản neo" (L2601–2609), "SVC-13 integration-gateway" (L1176, nhận EVT-12, EVT-19), "Phong bì sự kiện" (L1285–1315, ví dụ EVT-12 chia sẻ lên CSDL CBCCVC).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `ledger.anchor_receipt` (L3656), `integ.outbound_message` (L3930), "Niêm phong Merkle và dấu thời gian" (L4128, neo hằng ngày tới ít nhất 2 bên), "Tích hợp hệ ngoài qua SVC-13" (L4384).
- `00-co-so-thiet-ke-chung.md` mục 7 (EVT-12, EVT-19), ADR-09.
- Bản trích `extract/tl01-thanh-phan.md` mục C12.3, D5.

## 3. Phạm vi công việc

- Viết consumer EVT-19 (inbox idempotent): với bản niêm phong được đánh dấu là bản neo trong ngày, tạo `outbound_message` cho từng đích neo cấu hình trong `connector`, gửi gốc cell 32 byte kèm chữ ký, token TSA, mã cell, cửa sổ.
- Hiện thực các đích neo theo cấu hình: hệ thống của cơ quan chủ quản (ví dụ Ban Tổ chức qua LGSP), lưu trữ của đơn vị kiểm toán độc lập, cell khác, TSA thứ hai; chỉ coi xong khi đích trả biên nhận.
- Trả biên nhận cho ledger-service để ghi `ledger.anchor_receipt` (UQ `cell_seal_id`,`anchor_target`): qua luồng nội bộ tiền tố `sys.` hoặc cổng nội bộ của ledger (suy luận; không đặt mã EVT mới), vì chỉ `svc_ledger` được ghi schema `ledger`.
- Viết consumer EVT-12: dựng CloudEvents theo ví dụ TL-01 với mã ngoài cán bộ (`subjectRef.scheme=cbccvc`), ký JWS, gửi qua adapter NDXP tới CSDL CBCCVC, thử lại luỹ thừa tới khi có xác nhận; quyết định bị huỷ hay thay sau hậu kiểm gửi bản cập nhật có tham chiếu bản trước.
- Cảnh báo (metric + EVT-21 cho quản trị) khi một ngày chưa đủ hai biên nhận neo, hoặc thông điệp EVT-12 quá hạn thử lại.
- Điểm lệch cần lưu ý: TL-03 đặt `anchor_target` IN (`CELL_KHAC`,`KHO_VPTW`,`TSA_DOC_LAP`,`OBJECT_LOCK`), TL-01 (L2607) nêu hai bên là "hệ thống của cơ quan chủ quản (Ban Tổ chức qua LGSP)" và "lưu trữ của đơn vị kiểm toán độc lập"; task dùng bộ giá trị TL-03 cho CSDL, ánh xạ đích LGSP vào `KHO_VPTW` hoặc ghi câu hỏi mở vào `wiki/14` nếu không khớp.

## 4. Ngoài phạm vi

- Dựng niêm phong và chọn gốc neo (DV-09-03); bucket Object Lock nhận bản neo (đích `OBJECT_LOCK` do ledger-service ghi trực tiếp, INF-12).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử tích hợp: một EVT-19 tạo đúng số `outbound_message` theo số đích, đích giả lập lỗi rồi phục hồi được gửi lại, biên nhận được chuyển đi đúng một lần mỗi đích.
- EVT-12 giao lại hai lần chỉ sinh một thông điệp ra; tải trọng có chữ ký JWS kiểm được và không có dữ liệu C2.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
