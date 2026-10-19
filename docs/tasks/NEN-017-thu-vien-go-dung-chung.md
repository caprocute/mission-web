# NEN-017 · SVC-03/SVC-09/SVC-12 Thư viện Go dùng chung: tenant, outbox/inbox, lỗi RFC 9457, OpenTelemetry

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-017` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-007, NEN-032 |
| Truy vết | ADR-03, ADR-12, ADR-22 → SVC-03 tenant-registry, SVC-09 ledger-service, SVC-12 notification-service (Go 1.23, `pgx`, `franz-go`) → bảng `outbox_event`, `inbox_processed`, `inbox_aggregate_version` → `registry.tenant.changed.v1`, EVT-19, EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho ba service Go (tenant-registry, ledger, notification) cùng một thư viện nền tương đương `vt-platform-starter` của Java, để quy ước đa khách thuê, outbox, inbox, lỗi và quan sát không bị viết lại ba lần theo ba kiểu. Khi xong, mỗi service Go đặt được `app.tenant_id` cục bộ giao dịch qua `pgx`, ghi outbox trong cùng giao dịch, tiêu thụ Kafka bằng `franz-go` với inbox idempotent, trả lỗi `application/problem+json` cùng danh mục mã lỗi, và phát trace, metric, nhật ký JSON có che dữ liệu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục SVC-03 (L1007–1026), SVC-09 (L1108–1116), SVC-12 (L1156–1168), "Bảo đảm giao nhận: outbox và inbox" (L1238–1271), "OpenTelemetry và lan truyền ngữ cảnh trace" (L2929–2935).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Đặt ngữ cảnh tenant an toàn qua PgBouncer" (L706–734: Go dùng pgx sau `BeginTx`), "Transactional Outbox và Debezium" (L4205–4267), "Inbox idempotent" (L4269–4287).
- Kết quả NEN-007 (`contracts/events/**`), NEN-009 (danh mục mã lỗi `error-codes.json`), NEN-011, NEN-012 (hành vi Java để làm tương đương).

## 3. Phạm vi công việc

- Tạo mô-đun Go `services-go/vtplatform/` (Go 1.23, `go.mod` riêng; đường dẫn mô-đun ghi trong README, suy luận) với các gói `tenant`, `db`, `outbox`, `inbox`, `problem`, `otel`, `logx`.
- `tenant` + `db`: middleware `net/http` đọc claim tenant từ JWT đã xác thực (tên claim cấu hình được như NEN-010), lưu trong `context.Context`, trả 403 Problem Details khi thiếu; hàm `db.InTx(ctx, pool, fn)` mở giao dịch `pgx` rồi `select set_config('app.tenant_id', $1, true)` trước khi chạy `fn`; không có API đặt tenant mức phiên.
- `outbox`: `outbox.Append(ctx, tx, Envelope)` ghi cùng cột TL-03 như NEN-011, payload Avro tuần tự hoá theo lược đồ trong `contracts/events/avro` (thư viện Avro Go và cách tra global id qua API tương thích của Apicurio là suy luận, ghi lựa chọn vào báo cáo), chặn `classification` C2/C3.
- `inbox`: vòng tiêu thụ `franz-go` với thứ tự xử lý như NEN-012 (giao dịch → tenant → `inbox_processed ON CONFLICT DO NOTHING` → `inbox_aggregate_version` → xử lý → commit → commit offset), thử lại 3 lần rồi `<topic>.retry.<consumer>` và `<topic>.dlq.<consumer>`.
- `problem`: ghi `application/problem+json` đúng lược đồ `ProblemDetails`, nạp cùng `error-codes.json` của NEN-009 (chép bằng bước sinh mã, không sửa tay).
- `otel` + `logx`: khởi tạo OTel Go SDK (trace, metric) với `service.name`, `cell`; `log/slog` handler JSON có `trace.id`, `span.id`, `tenant.id` và bộ che JWT, số định danh 12 chữ số, số điện thoại, email.
- Kiểm thử bằng `testcontainers-go` (PostgreSQL + PgBouncer chế độ giao dịch, Kafka, Apicurio): cách ly tenant qua PgBouncer `default_pool_size=1`; outbox trong giao dịch rollback không để lại dòng; inbox bỏ trùng và bỏ phiên bản cũ.

## 4. Ngoài phạm vi

- Khung từng service Go: DV-03-01, DV-09-01, DV-12-01.
- Thư viện băm chuẩn hoá sổ cái (JCS + NFC, chọn chuỗi): DV-09-02.

## 5. Tệp được phép sửa

- `services-go/vtplatform/**` (mới)
- `services-go/README.md` (mới, ngắn: cấu trúc, lệnh kiểm thử)

## 6. Tiêu chí hoàn thành

- `cd services-go/vtplatform && go vet ./... && go test ./...` chạy xanh (kiểm thử tích hợp chạy khi có Docker; ghi rõ nếu môi trường không có Docker).
- `govulncheck ./...` không có lỗ hổng mức cao; `gofmt -l .` rỗng.
- Bản ghi outbox do Go ghi được Debezium cấu hình của NEN-011 chuyển đúng topic với cùng header như Java (một kiểm thử đối chiếu hoặc ghi rõ cách đã kiểm).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 16 (nếu đã có) mục service Go.

## 7. Báo cáo

Theo AGENTS.md mục 6.
