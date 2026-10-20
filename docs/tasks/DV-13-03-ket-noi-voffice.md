# DV-13-03 · SVC-13/EVT-24 Bộ kết nối vOffice: văn bản chỉ đạo đến và văn bản đã ban hành

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-13-01 |
| Truy vết | QT-05 bước 1b, QT-07 (BR-07-13) → API `GET /api/v1/evidences/external-docs` (MOD-03 gọi qua SVC-13) → SVC-13 → bảng `integ.connector`, `integ.inbox_message`, `integ.mapping_xref`, `integ.sync_cursor`, `integ.dead_letter`, `integ.outbox_event` → EVT-24 `integ.document.received.v1` |
| Trạng thái | Bị chặn bởi bên ngoài: cần môi trường thử vOffice (phần mã và kiểm thử với vOffice giả lập làm được ngay) |

## 1. Mục tiêu

Hiện thực bộ kết nối vOffice (và ĐHTN cùng giao thức) hai chiều: chiều vào kéo văn bản chỉ đạo mới theo con trỏ mỗi 30 giây (hoặc nhận webhook CloudEvents khi vOffice hỗ trợ), ánh xạ đơn vị nhận, lưu nguyên văn và phát EVT-24; chiều tra cứu cho phép MOD-03 tìm văn bản đã ban hành của đơn vị để làm minh chứng mà không tải lại tệp. Khi xong, văn bản chỉ đạo từ vOffice xuất hiện ở hàng chờ tạo nhiệm vụ (SCR-0502) gần thời gian thực.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1409–1426, dòng vOffice, ĐHTN: `api/v2/directives?updatedSince=&limit=200`, 30 giây), "Lớp chống ô nhiễm" (L1358–1407, ví dụ route vOffice với khoá `voffice:${id}:${version}`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 (L1391, bước 1b L1425, ngoại lệ "Văn bản gửi nhầm đơn vị" L1481), QT-07 (BR-07-13 L1933, bảng trường SCR-0701 L1967).
- `contracts/openapi/modules/products.yaml` `searchEvidenceExternalDocs`; `contracts/openapi/modules/tasks.yaml` phần văn bản chỉ đạo (SCR-0502).
- `00-co-so-thiet-ke-chung.md` mục 7 (EVT-24); bản trích `extract/tl02a-may-chu.md` QT-05 (BE-05-6), QT-07 (BE-07-6).

## 3. Phạm vi công việc

- Viết route kéo `voffice-directives`: gọi API vOffice theo `updatedSince` từ `sync_cursor` (stream `directives`), trang 200, chu kỳ 30 giây; chống trùng bằng `idempotentConsumer` khoá `voffice:${id}:${version}`; lưu `inbox_message` (`raw_enc`) trước khi chuyển đổi.
- Viết translator văn bản chỉ đạo sang thực thể mô hình chuẩn nhóm 5 (Task/văn bản nguồn): số ký hiệu, ngày, trích yếu, cơ quan ban hành, đơn vị nhận (tra `mapping_xref` `DON_VI`), tệp đính kèm (chuyển sang evidence-service, chỉ giữ tham chiếu); phát EVT-24 qua outbox, khoá `tenantId:aggregateId`.
- Hỗ trợ đầu vào EdXML từ vOffice khi vOffice đẩy theo NĐ 30/2020 (dùng chung bộ phân tích EdXML với DV-13-05 nếu đã có, nếu chưa thì viết bộ phân tích tối thiểu ở đây và để DV-13-05 dùng lại).
- Viết use case tra cứu văn bản đã ban hành của đơn vị (số, ngày, trích yếu, người ký, trạng thái chữ ký số) cho MOD-03 gọi qua cổng nội bộ, kiểm chữ ký số gốc khi nhận (BR-07-13), dùng tệp gốc không tải lại.
- Gửi phản hồi "gửi nhầm đơn vị" về vOffice khi MOD-02 bỏ văn bản với lý do, nếu vOffice có giao diện (cờ tính năng theo `connector`).
- Điểm lệch cần lưu ý: TL-01 (L1415) mô tả vOffice qua REST OpenAPI kéo có con trỏ, TL-02a (L1425) mô tả vOffice gửi EdXML; task hỗ trợ cả hai, mặc định REST kéo theo TL-01, ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Trích nhiệm vụ bằng AI và màn hình xác nhận (CORE-02-16, DV-14-04); bộ kết nối iOffice (DV-13-04).
- Đọc bảng tổ chức `VHR_ORG`, `VHR_EMPLOYEE` qua `JDBC_RO` trong giai đoạn chuyển tiếp (task hệ cũ LEG tương ứng).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử tích hợp với vOffice giả lập (WireMock): 3 trang văn bản, một văn bản đổi phiên bản, một lần vOffice lỗi 5xx; kết quả đúng số EVT-24, không trùng, con trỏ tiến đúng, thông điệp lỗi vào `dead_letter`.
- Văn bản có đơn vị nhận chưa ánh xạ được thì không phát EVT-24 mà vào hàng xử lý của quản trị tenant, có lý do rõ.
- Kiểm thử hợp đồng phía tiêu dùng (Pact) với vOffice giả lập được công bố cho QA-02 dùng lại.
- Khi có môi trường thử vOffice: chạy kéo thật và ghi kết quả; nếu chưa có, ghi rõ trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
