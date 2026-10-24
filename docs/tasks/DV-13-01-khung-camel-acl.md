# DV-13-01 · SVC-13/ADR-20 Khung integration-gateway Camel, migration schema `integ`, lớp chống ô nhiễm, `mapping_xref`, thư chết

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | NEN-019, NEN-033; NEN-015 (mã hoá C2 cho `raw_enc`, `payload_enc`) phải gộp trước (bổ sung) |
| Truy vết | QT-01, QT-05 → SVC-13 → bảng `integ.connector`, `integ.inbox_message`, `integ.mapping_xref`, `integ.sync_cursor`, `integ.dead_letter`, `integ.outbound_message`, `integ.transfer_package`, `integ.outbox_event` → EVT-24 → ADR-20 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng integration-gateway (Java 21, Spring Boot 3.5, Apache Camel 4) với khung lớp chống ô nhiễm dùng chung cho mọi bộ kết nối: Adapter theo giao thức, Translator sang mô hình dữ liệu chuẩn và tra/tạo `mapping_xref`, Publisher phát sự kiện nội bộ; lưu nguyên văn trước khi xử lý, chống trùng, thư chết riêng mỗi bộ kết nối. Khi xong, mỗi bộ kết nối DV-13-03..08 chỉ cần viết adapter và translator, còn độ tin cậy, chống trùng, con trỏ đồng bộ và thư chết đã có sẵn.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-13 integration-gateway" (L1170–1182), "Lớp chống ô nhiễm" (L1358–1407, mẫu route Camel), "Mô hình dữ liệu chuẩn theo tám nhóm PL 4" (L1461), ADR-20 (L3582).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema integ (SVC-13)" (L3856–3956) với từ điển bảy bảng; "Tích hợp hệ ngoài qua SVC-13" (L4384); "REST đồng bộ có Resilience4j" (L4380).
- `00-co-so-thiet-ke-chung.md` mục 8 (cơ chế 8: chỉ qua SVC-13, `integ.mapping_xref`).
- Bản trích `extract/tl01-thanh-phan.md` mục C12, D8, D11; `extract/tl03-phan2.md` mục A11, B10.

## 3. Phạm vi công việc

- Tạo `backend/services/integration-gateway/` bằng `scripts/new-service.sh`, gói `vn.viettel.okrkpi.integ`, phụ thuộc `camel-kafka`, `camel-rest`, `camel-cxf`, `camel-sftp`, `camel-jackson`, `camel-resilience4j`.
- Viết migration `db/migration/integ` tạo `connector` (UQ `tenant_id`,`connector_code`; CHECK `protocol` IN (`REST`,`SOAP`,`EDXML`,`XROAD`,`SFTP`,`JDBC_RO`); CHECK `direction` IN (`VAO`,`RA`,`HAI_CHIEU`); `schedule_cron`; `secret_path`), `inbox_message` (phân vùng ngày; UQ (`received_day`,`tenant_id`,`connector_id`,`external_msg_id`); `raw_enc` C2; CHECK `status` IN (`MOI`,`DA_CHUYEN_DOI`,`DA_PHAT`,`LOI`); `emitted_event_id`), `mapping_xref` (EXCLUDE theo `valid_period` cần `btree_gist`; `entity_type` gồm `DON_VI`, `NHAN_SU`, `VAN_BAN`), `sync_cursor`, `dead_letter`, `outbound_message`, `transfer_package`, cùng outbox, inbox, RLS.
- Viết khung route chuẩn: `deadLetterChannel("direct:integ-dead-letter")` 5 lần, trễ 2.000 ms, nhân 2; circuit breaker Resilience4j (tỷ lệ lỗi 50%, cửa sổ 20, mở 30 giây, timeout 5.000 ms); `idempotentConsumer` với `JdbcMessageIdRepository`; kéo theo lịch có `SyncCursorService`; mỗi bộ kết nối một bulkhead riêng.
- Viết `MappingXrefService` (tra hoặc tạo ánh xạ mã ngoài ↔ UUIDv7 nội bộ theo thời gian hiệu lực, chống vòng lặp khi đồng bộ hai chiều) và `CanonicalPublisher` phát sự kiện mô hình chuẩn lên Kafka nội bộ qua outbox; không bảng nội bộ nào ngoài `integ` chứa mã hệ ngoài.
- Viết pipeline chiều ra dùng chung: consumer sự kiện cần gửi ra ngoài ghi `outbound_message`, worker gửi với thử lại luỹ thừa (`next_attempt_at`), chỉ coi xong khi bên nhận xác nhận (`ack_ref`).
- Cung cấp use case xử lý thư chết (liệt kê, chạy lại, đánh dấu đã xử lý với `resolved_by/at`) cho quản trị tenant; điểm lệch cần lưu ý: TL-01 (L1358–1407) nêu "giao diện xử lý thủ công cho quản trị tenant" nhưng hợp đồng chỉ có `POST /api/v1/org/sync-runs/{id}/retry` do SVC-04 sở hữu; task hiện thực use case phía SVC-13 cho SVC-04 gọi qua cổng nội bộ (suy luận) và ghi vào `wiki/14`.
- Gợi ý tách nếu quá lớn: (a) khung service + migration; (b) khung route, mapping, pipeline ra, thư chết.

## 4. Ngoài phạm vi

- Từng bộ kết nối cụ thể (DV-13-03..08), CloudEvents ra ngoài có JWS và mTLS (DV-13-02).
- Repo `canonical-model` sinh JSON Schema và Avro cho tám nhóm (NEN-007 và task dữ liệu chuẩn tương ứng).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `backend/pom.xml` (chỉ thêm module)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh: Testcontainers PostgreSQL 17 + Kafka, ArchUnit, độ phủ ≥ 80% cho `domain` và `application`.
- Kiểm thử với một bộ kết nối mẫu giả lập: thông điệp trùng `external_msg_id` chỉ xử lý một lần; lỗi 5 lần vào `dead_letter` và chạy lại được; ánh xạ chồng thời gian hiệu lực bị EXCLUDE chặn; mạch mở sau tỷ lệ lỗi 50% trong 20 lời gọi.
- `raw_enc`, `payload_enc` chỉ chứa bản mã; nhật ký không có nội dung thông điệp.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 ghi điểm lệch giao diện thư chết.

## 7. Báo cáo

Theo AGENTS.md mục 6.
