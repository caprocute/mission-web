# NEN-022 · ADR-03 Công cụ phát lại outbox và danh mục connector Debezium outbox theo schema

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-022` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-011; NEN-017 (thư viện Go dùng chung) (bổ sung); NEN-032 (bảng `outbox_event` mẫu) (bổ sung) |
| Truy vết | ADR-03 → TL-03 "Transactional Outbox và Debezium" (L4205–4267: connector mẫu, dọn outbox, giám sát, công cụ phát lại) → TL-04 "Giữ outbox 72 giờ để phát lại sau chuyển vùng" (L2332), OPS-24 (L3869) → bảng `<schema>.outbox_event`, `platform.dbz_heartbeat` → mọi topic EVT-01..EVT-26 và topic kỹ thuật |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

TL-03 yêu cầu "công cụ phát lại của nền tảng" để phát lại sự kiện từ bảng `outbox_event` theo dải thời gian khi slot sao chép logic bị vô hiệu do vượt `max_slot_wal_keep_size`, khi connector hỏng lâu, hoặc sau chuyển vùng. Task này viết công cụ đó dưới dạng CLI dùng chung, cùng tệp danh mục nguồn outbox máy đọc được (CSDL nào, schema nào, bảng outbox nào, được phát lên topic nào, thuộc connector nào), làm đầu vào cho bộ sinh `KafkaConnector` của INF-10 và cho kiểm tra ACL. Khi xong, người vận hành phát lại được một dải sự kiện của một tenant, một schema, một khoảng thời gian mà không tạo sự kiện ma, và consumer khử trùng bằng inbox.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Transactional Outbox và Debezium" (L4205–4267: cấu hình `outbox-td_hcm`, đoạn chọn một connector mỗi CSDL, "Dọn bảng outbox" `p_retention = '3 days'`, "Thứ tự và trùng lặp", "Giám sát" và câu "phát lại từ bảng outbox theo dải thời gian bằng công cụ phát lại của nền tảng"), "Bảng outbox_event" (L768), "Inbox idempotent" (L4269).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Outbox" (connector và slot theo schema) và "Quy ước topic" (L1279–1283).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Giữ outbox 72 giờ để phát lại sau chuyển vùng" (L2332–2334), OPS-24 (L3869: `vtctl dlq replay`, topic trung gian `<topic>.replay.<consumer>`).
- Thẻ `docs/tasks/NEN-011-*.md` (cột outbox, header), `INF-10-*.md` (bộ sinh `KafkaConnector`), `INF-18-*.md` (phát lại cho DR-10), `INF-26-*.md` (`vtctl`).

## 3. Phạm vi công việc

- Viết `contracts/events/outbox-sources.yaml`: mỗi dòng là một schema có bảng `outbox_event` (`database`, `schema`, `outboxTable`, `connector`, danh sách topic được phép phát lấy từ `catalog.yaml`), phủ CSDL dữ liệu tenant của mission-core và scoring, docgen, cùng CSDL riêng của các service có outbox (org, evidence, tenant-registry…, tra bảng sở hữu dữ liệu TL-03 chương 2). Kèm kiểm thử: mọi topic trong tệp có trong `catalog.yaml`, mọi EVT có đúng một schema phát.
- Điểm lệch cần lưu ý: TL-01 đặt mỗi schema một connector và một slot, TL-03 chọn mỗi CSDL một connector liệt kê mọi bảng outbox trong `table.include.list` (13 slot giải mã cùng một WAL là lãng phí); chọn theo TL-03, nhưng tệp danh mục cho phép tách một schema sang connector riêng bằng cách đổi cột `connector` (đúng ngoại lệ TL-03 nêu). INF-10 sinh `KafkaConnector` từ tệp này.
- Viết CLI `outbox-replay` bằng Go (dùng `pgx`, `franz-go`, thư viện NEN-017): đọc `<schema>.outbox_event` theo bộ lọc `--tenant`, `--schema`, `--from`/`--to` theo `created_at`, `--event-id`, `--aggregate-id`, `--topic`; phát lại đúng như Outbox Event Router (khoá `event_key`, giá trị `payload` nguyên byte Avro, header `ce_type`, `ce_tenantid`, `traceparent`, `ce_classification`, `apicurio.value.globalId`, giữ nguyên `event_id`) tới topic gốc hoặc `<topic>.replay.<consumer>` khi chỉ định `--consumer`.
- An toàn: chế độ mặc định `--dry-run` chỉ đếm theo topic và tenant; chạy thật cần `--confirm` và ghi một sự kiện kiểm toán lên `sys.audit.v1` (ai, bộ lọc, số bản ghi); từ chối topic không có trong `outbox-sources.yaml`; producer `enable.idempotence=true`, `acks=all`; giới hạn tốc độ có thể đặt; dừng giữa chừng thì in mốc `created_at`/`id` cuối để chạy tiếp.
- Cảnh báo khi dải thời gian vượt thời hạn giữ outbox: Điểm lệch cần lưu ý: TL-03 giữ 3 ngày bằng pg_partman, TL-04 giữ 72 giờ; hai con số trùng nhau, công cụ đọc thời hạn thật từ cấu hình `part_config` và báo khi phần đầu dải đã bị dọn.
- Đóng gói ảnh container không root để chạy như Job Kubernetes; viết `ops/runbooks/phat-lai-outbox.md` (khi nào dùng, kiểm tra slot, chạy thử, chạy thật, xác nhận consumer khử trùng, quay lui không cần vì phát lại là idempotent ở phía nhận).
- Kiểm thử tích hợp Testcontainers PostgreSQL 17 + Kafka: chèn 1.000 dòng outbox hai tenant, phát lại một tenant trong một khoảng thời gian thì đúng số thông điệp, đúng khoá và header; phát lại hai lần không đổi trạng thái của consumer mẫu có inbox.

## 4. Ngoài phạm vi

- Triển khai Kafka Connect, sinh `KafkaConnector` và topic dạng mã (INF-10); chế độ phát lại sau chuyển vùng bằng Debezium snapshot `when_needed` của DR-10 (INF-18 dùng lại tệp danh mục và có thể gọi CLI này); lệnh `vtctl` bọc CLI (INF-26); phát lại DLQ theo offset (OPS-24, INF-26).
- Phát lại sự kiện tổ chức trạng thái đầy đủ `org-republish` (DV-04-08).

## 5. Tệp được phép sửa

- `contracts/events/outbox-sources.yaml` (mới), `contracts/events/README.md` (thêm mục mô tả tệp)
- `services-go/outbox-replay/**` (mới; vị trí suy luận, công cụ dùng chung cho INF-18 và INF-26)
- `ops/runbooks/phat-lai-outbox.md`
- `wiki/18-su-kien-va-dong-bo-du-lieu.md` (chỉ mục phát lại outbox)

## 6. Tiêu chí hoàn thành

- `cd services-go/outbox-replay && go vet ./... && go test ./... && govulncheck ./...` chạy xanh, gồm kiểm thử tích hợp mục 3.
- Thông điệp phát lại so từng byte với thông điệp Debezium đã phát cho cùng dòng outbox (giá trị, khoá, header) trong kiểm thử có Debezium Connect, hoặc so với cấu hình EventRouter mẫu nếu chưa dựng được Connect (ghi rõ).
- Kiểm thử danh mục: `outbox-sources.yaml` khớp `catalog.yaml`; topic lạ bị từ chối.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu đề xuất để INF-18 phụ thuộc NEN-022 và dùng lại CLI.

## 7. Báo cáo

Theo AGENTS.md mục 6.
