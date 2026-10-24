# NEN-011 · ADR-03 vt-platform-starter: OutboxWriter, EventEnvelope, tuần tự hoá Avro qua Apicurio

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-011` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-007, NEN-032 |
| Truy vết | ADR-03 → baseline mục 8.1 → TL-03 "Transactional Outbox và Debezium", bảng `<schema>.outbox_event` → topic EVT-01..EVT-26 → Debezium EventRouter |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp cho mọi service Java một cách duy nhất để phát sự kiện: `OutboxWriter.append(EventEnvelope)` tuần tự hoá dữ liệu Avro theo lược đồ đăng ký ở Apicurio và chèn một dòng vào `<schema>.outbox_event` trong cùng giao dịch với thay đổi nghiệp vụ. Debezium đọc WAL và đẩy lên Kafka; mã nghiệp vụ không bao giờ gửi Kafka trực tiếp. Khi xong, sự kiện chỉ tồn tại khi giao dịch nghiệp vụ commit, và bên nhận có đủ header để định tuyến, khử trùng lặp, lần vết.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Bảng outbox_event" (L768–822, DDL L787–819) và chương "Cơ chế đồng bộ dữ liệu" mục "Transactional Outbox và Debezium" (L4205–4267: `OutboxEventPublisher`, cấu hình connector mẫu, thứ tự, giám sát).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Outbox" của SVC-05 (L754–807) và "Phong bì sự kiện" (L1285–1315); ADR-03 (L3412).
- `contracts/events/catalog.yaml`, `backend/libs/vt-events` (NEN-007); migration mẫu của NEN-032.

## 3. Phạm vi công việc

- Thêm gói `outbox` vào `vt-platform-starter`: `EventEnvelope.of(topic, tenantId, aggregateType, aggregateId, aggregateVersion, payload, classification)` và `OutboxWriter.append(...)` yêu cầu giao dịch đang mở (`Propagation.MANDATORY`), ném lỗi nếu gọi ngoài giao dịch.
- Ghi đúng cột TL-03: `id` (UUIDv7), `tenant_id`, `aggregate_type`, `aggregate_id`, `aggregate_version` (= `row_version`), `event_type`, `topic`, `event_key` (`tenantId:aggregateId`), `payload` (Avro nhị phân), `schema_version`, `headers` jsonb, `created_at`; tên bảng lấy theo schema của mô-đun gọi (cấu hình `okrkpi.outbox.schema`).
- `headers` gồm `traceparent` (từ OpenTelemetry hiện hành), `actorid`, `tenantid`, `classification`, `policyversion` khi có, `aggregateversion`, `ce_type`, và global id lược đồ Apicurio (`apicurio.value.globalId`) để EventRouter đặt vào header Kafka như cấu hình connector mẫu TL-03.
- Tuần tự hoá bằng serde Avro của Apicurio 3.x (tra global id theo lược đồ, đệm kết quả tra cứu); khi Apicurio không phản hồi thì giao dịch thất bại rõ ràng thay vì ghi payload không có lược đồ (suy luận, ghi vào báo cáo).
- Chặn phát sự kiện mang dữ liệu C2: `EventEnvelope` từ chối `classification = C2/C3` (TL-03: không sự kiện nào mang C2).
- Viết cấu hình connector Debezium tham chiếu (`PostgresConnector`, `pgoutput`, `snapshot.mode=never`, EventRouter `route.by.field=topic`, `table.field.event.key=event_key`, `ByteArrayConverter`, `heartbeat.interval.ms=10000`) trong tài nguyên kiểm thử để kiểm thử đầu cuối dùng; cấu hình production thuộc INF-10.
- Kiểm thử tích hợp Testcontainers: PostgreSQL + Kafka + Apicurio + Debezium Connect; một use case thử ghi một dòng nghiệp vụ và một sự kiện EVT-05 trong cùng giao dịch; commit thì thông điệp xuất hiện trên `task.task.progressed.v1` với khoá `tenantId:aggregateId` và đủ header; rollback thì không có thông điệp.
- Điểm lệch cần lưu ý: TL-01 mục "Outbox" dùng cột `aggregate_key`, `schema_id`, `occurred_at`, còn TL-03 L787–819 dùng `aggregate_id`, `aggregate_version`, `event_key`, `schema_version`, `created_at`; task theo TL-03 (chi tiết hơn, là nguồn DDL) và ghi vào báo cáo.

## 4. Ngoài phạm vi

- DDL bảng outbox và phân vùng: NEN-032; consumer và inbox: NEN-012.
- Connector production, giám sát độ trễ slot, công cụ phát lại outbox: INF-10 (và ghi vào báo cáo nếu chưa có task nhận).
- Sự kiện trong tiến trình giữa module (Event Publication Registry): CORE-00-02.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `outbox`, kiểm thử, tài nguyên kiểm thử)
- `backend/platform-bom/pom.xml` (chỉ thêm serde Apicurio, Testcontainers Kafka/Debezium)
- `wiki/18-su-kien-va-dong-bo-du-lieu.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh, gồm kiểm thử đầu cuối outbox → Debezium → Kafka.
- Đo trong kiểm thử: thời gian từ commit tới khi consumer thử nhận thông điệp, báo cáo p50/p99 trên 1.000 sự kiện (mục tiêu TL-03 p99 ≤ 2 giây, chỉ để tham khảo trong môi trường kiểm thử).
- Kiểm thử âm: gọi `append` ngoài giao dịch, hoặc với `classification = C2`, đều ném lỗi.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 18 (nếu đã có) mục "Phát sự kiện trong mã".

## 7. Báo cáo

Theo AGENTS.md mục 6.
