# NEN-012 · ADR-03 vt-platform-starter: consumer Kafka chuẩn với inbox idempotent, thử lại có backoff, DLQ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-012` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-011 |
| Truy vết | ADR-03 → baseline mục 8.2 → TL-03 "Inbox idempotent" → bảng `<schema>.inbox_processed`, `<schema>.inbox_aggregate_version` → topic `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp khung consumer Kafka dùng chung cho mọi service Java: nhận ít nhất một lần nhưng xử lý đúng một lần nhờ inbox, bỏ qua sự kiện cũ đến muộn nhờ phiên bản aggregate, đặt ngữ cảnh tenant trước khi chạm CSDL, thử lại có kiểm soát và chuyển lỗi vĩnh viễn sang DLQ kèm lý do. Khi xong, task nghiệp vụ chỉ viết một hàm xử lý với annotation và không phải tự lo trùng lặp, thứ tự hay offset.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Inbox idempotent" (L4269–4287) và bảng `inbox_processed`, `inbox_aggregate_version` (L824–873).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Bảo đảm giao nhận: outbox và inbox" (L1238–1271), "Thứ tự và phân vùng" (L1273–1275), "Quy ước topic" (L1279–1283).
- Kết quả NEN-010 (`TenantContext.run`), NEN-011 (header sự kiện), NEN-032 (DDL inbox).

## 3. Phạm vi công việc

- Thêm gói `inbox` vào `vt-platform-starter`: annotation `@InboxListener(topic, consumer, aggregateType)` dựa trên Spring Kafka, giải tuần tự Avro bằng serde Apicurio, đọc `tenantid`, `aggregateversion`, `traceparent` từ header.
- Thứ tự xử lý đúng TL-03: mở giao dịch → `TenantContext` theo header → `INSERT ... ON CONFLICT DO NOTHING RETURNING` vào `inbox_processed` (bỏ qua nếu đã có) → kiểm `inbox_aggregate_version` (bỏ qua nếu phiên bản ≤ `last_version`, cập nhật nếu lớn hơn) → gọi hàm xử lý → commit → xác nhận offset sau commit (`TransactionSynchronization.afterCommit`).
- Thử lại: lỗi tạm thời thử tại chỗ 3 lần có backoff, sau đó chuyển `<topic>.retry.<consumer>` với độ trễ 1, 5, 30 phút; lỗi vĩnh viễn (giải tuần tự, vi phạm nghiệp vụ, thiếu tenant) chuyển ngay `<topic>.dlq.<consumer>` kèm header lý do, mã lỗi và `traceparent`.
- Cấu hình producer dùng chung `acks=all`, `enable.idempotence=true`; consumer `isolation.level=read_committed`, `max.poll.records` cấu hình được (suy luận về giá trị mặc định).
- Đo lường: metric số sự kiện xử lý, bỏ qua do trùng, bỏ qua do cũ, chuyển retry, chuyển DLQ, thời gian xử lý, theo `consumer` và `topic`; đưa vào readiness khi consumer mất kết nối quá ngưỡng (suy luận).
- Job dọn `inbox_processed` quá 14 ngày (lớn hơn thời gian giữ topic 7 ngày) theo lô, chạy bằng khoá phân tán đơn giản trên CSDL để chỉ một pod chạy (suy luận).
- Kiểm thử tích hợp Testcontainers (Kafka, PostgreSQL, Apicurio): gửi trùng cùng `event_id` → xử lý một lần; gửi phiên bản 3 rồi 2 → bản 2 bị bỏ; hàm xử lý ném lỗi tạm thời → vào topic retry rồi thành công; ném lỗi vĩnh viễn → vào DLQ có lý do; tắt CSDL giữa chừng → offset không bị xác nhận.
- Điểm lệch cần lưu ý: TL-01 L1246 ghi DLQ `<topic>.dlq` và "lặp 5 lần", TL-01 L1281 và TL-03 ghi `<topic>.dlq.<consumer>` và thử lại 3 lần rồi topic retry; task theo TL-03 và ghi vào báo cáo.

## 4. Ngoài phạm vi

- Consumer Go/Python: NEN-017, NEN-018; consumer nghiệp vụ cụ thể (ví dụ `org_replica`): CORE-00-03 và các task DV.
- Khai báo topic retry/DLQ dạng mã trên Strimzi: INF-10; ở dev: NEN-020.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `inbox`, kiểm thử)
- `backend/platform-bom/pom.xml` (chỉ thêm phụ thuộc Spring Kafka nếu chưa có)
- `wiki/18-su-kien-va-dong-bo-du-lieu.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh với đủ năm kịch bản kiểm thử ở mục 3.
- Kiểm thử tải nhỏ: 10.000 sự kiện có 10% trùng lặp và 5% đảo thứ tự cho kết quả đúng tuyệt đối (số dòng tác dụng bằng số sự kiện duy nhất mới nhất).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 18 (nếu đã có) mục "Viết consumer".

## 7. Báo cáo

Theo AGENTS.md mục 6.
