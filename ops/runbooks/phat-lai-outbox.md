# Sổ tay vận hành: Phát lại sự kiện từ bảng Outbox (`outbox-replay`)

> **Mã truy vết:** NEN-022 · ADR-03 · TL-03 "Transactional Outbox và Debezium" (L4205–4267) · TL-04 DR-10 ("Giữ outbox 72 giờ để phát lại sau chuyển vùng", L2332) & OPS-24 (L3869).
> **Công cụ:** `services-go/outbox-replay` (`outbox-replay` CLI / Kubernetes Job)
> **Danh mục nguồn:** `contracts/events/outbox-sources.yaml` (đối chiếu với `contracts/events/catalog.yaml`)

---

## 1. Khi nào sử dụng sổ tay này

Sử dụng công cụ `outbox-replay` để phát lại sự kiện từ bảng `<schema>.outbox_event` lên Kafka trong các tình huống vận hành sau:

1. **Replication slot bị vô hiệu (`wal_status = 'lost'`):** Slot `dbz_outbox_<db>` trên PostgreSQL vượt ngưỡng `max_slot_wal_keep_size = 64GB` khi Kafka hoặc Kafka Connect ngừng hoạt động kéo dài; sau khi tạo lại slot mới, cần phát lại khoảng thời gian bị hụt.
2. **Chuyển vùng thảm hoạ (DR-10 / INF-18):** Sau khi nâng cấp cụm PostgreSQL dự phòng tại DC2 lên làm Primary, slot logic không tự chuyển sang cụm mới; cần phát lại các dòng `outbox_event` trong khoảng cửa sổ chuyển vùng (tối đa 72 giờ / 3 ngày theo `pg_partman`).
3. **Phục hồi riêng một consumer cụ thể:** Một consumer gặp lỗi xử lý hoặc mất trạng thái cục bộ cần nhận lại sự kiện qua topic trung gian `<topic>.replay.<consumer>` mà không phát lại cho toàn bộ các consumer khác trên topic gốc.

> **Lưu ý giới hạn thời gian giữ dữ liệu:** `pg_partman` giữ các phân vùng `<schema>.outbox_event` trong **3 ngày (72 giờ)** (`p_retention = '3 days'`). Nếu mốc `--from` vượt quá 72 giờ tính từ thời điểm chạy, `outbox-replay` sẽ đọc `partman.part_config` và in cảnh báo `CẢNH BÁO: Mốc --from ... vượt quá thời hạn giữ phân vùng outbox`.

---

## 2. Kiểm tra trạng thái replication slot và vùng thời gian cần phát lại

Trước khi chạy công cụ, kiểm tra trạng thái slot Debezium và độ trễ WAL trên PostgreSQL:

```sql
-- 1. Kiểm tra trạng thái slot và độ trễ WAL
SELECT
    slot_name,
    database,
    active,
    wal_status,
    pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS wal_lag
FROM pg_replication_slots
WHERE slot_name LIKE 'dbz_outbox_%';

-- 2. Kiểm tra nhịp tim cuối cùng mà Debezium đã ghi nhận
SELECT * FROM platform.dbz_heartbeat ORDER BY last_heartbeat_ts DESC;

-- 3. Kiểm tra thời hạn giữ phân vùng pg_partman của bảng outbox
SELECT parent_table, retention, premake
FROM partman.part_config
WHERE parent_table = 'task.outbox_event';
```

Từ `last_heartbeat_ts` (hoặc thời điểm sự cố bắt đầu), lùi lại tối thiểu **5 phút** làm mốc `--from` để tránh bỏ sót giao dịch mở lúc sự cố; phần chồng lấp sẽ được bảng `inbox_processed` ở các consumer tự động khử trùng.

---

## 3. Bước 1: Kiểm tra danh mục và chạy thử (`--dry-run`)

Bằng vtctl (INF-26): lệnh dựng Job `outbox-replay` từ chart `deploy/charts/vtctl-jobs` và chạy trong namespace Job của cell. Không có `--confirm` thì vtctl chỉ kiểm Job phía máy chủ (`kubectl create --dry-run=server`), không tạo gì. Có `--confirm` thì Job chạy công cụ với `--confirm`, người chạy lấy từ token đăng nhập vtctl. Lệnh cần quyền tạo Job (`operate-data`, cấp qua PAM).

```bash
vtctl -e <môi trường> -c <cell> outbox replay --schema task --tenant <uuid-tenant> \
  --from 2026-10-20T08:00:00Z --to 2026-10-20T10:30:00Z --reason "INC-2026-1020-slot-lost"
# Chạy thật, chờ Job xong; thêm --consumer <tên> để chỉ phát qua <topic>.replay.<consumer> (mục 4.2).
vtctl -e <môi trường> -c <cell> outbox replay --schema task --tenant <uuid-tenant> \
  --from 2026-10-20T08:00:00Z --to 2026-10-20T10:30:00Z --reason "INC-2026-1020-slot-lost" --wait 4h --confirm
```

Công cụ đọc thông tin nối từ biến môi trường, Job nạp từ Secret khai ở `outboxReplay.envFromSecret`: `DATABASE_URL` (vai trò chỉ đọc có `SELECT` trên `<schema>.outbox_event` và `partman.part_config`; công cụ tự đặt `app.tenant_id` trong từng giao dịch chỉ đọc nên RLS vẫn áp dụng), `KAFKA_BROKERS` (danh sách cách nhau bởi dấu phẩy, chỉ cần khi `--confirm`), và `KAFKA_TLS_CA_FILE`, `KAFKA_TLS_CERT_FILE`, `KAFKA_TLS_KEY_FILE` cho mTLS. Thiếu biến cần thiết thì công cụ dừng với mã 1 và nêu tên biến.

Phương án dự phòng khi chạy công cụ trực tiếp trong pod của cell (lệnh thủ công):

Mặc định `outbox-replay` luôn chạy ở chế độ `--dry-run=true` (không gửi thông điệp lên Kafka, không ghi kiểm toán, chỉ kiểm tra hợp đồng `outbox-sources.yaml` và đếm số bản ghi theo topic và tenant).

```bash
# Kiểm tra tính nhất quán giữa outbox-sources.yaml và catalog.yaml
outbox-replay validate \
  --sources /etc/mission/events/outbox-sources.yaml \
  --catalog /etc/mission/events/catalog.yaml

# Chạy thử (dry-run) cho một tenant và khoảng thời gian
outbox-replay \
  --schema task \
  --tenant 11111111-1111-4111-8111-111111111111 \
  --from 2026-10-20T08:00:00Z \
  --to   2026-10-20T10:30:00Z
```

Kiểm tra kết quả JSON in ra:
- `dryRun: true`
- `totalMatched`: tổng số dòng khớp bộ lọc
- `countByTopic`: số lượng bản ghi theo từng topic (mọi topic phải nằm trong `outbox-sources.yaml`)
- `retentionExceeded`: phải là `false` (nếu `true`, kiểm tra xem phân vùng ngày cũ còn tồn tại hay không).

---

## 4. Bước 2: Chạy phát lại thật (`--confirm`)

Khi số lượng bản ghi ở bước chạy thử đã khớp kỳ vọng, thêm cờ `--confirm`, `--actor` và `--reason` (mã phiếu sự cố):

### 4.1. Phát lại lên topic gốc (cho mọi consumer của topic)

```bash
outbox-replay \
  --schema task \
  --tenant 11111111-1111-4111-8111-111111111111 \
  --from 2026-10-20T08:00:00Z \
  --to   2026-10-20T10:30:00Z \
  --rate-limit 500 \
  --batch-size 500 \
  --actor "ops.nguyen-van-a" \
  --reason "INC-2026-1020-slot-lost" \
  --confirm
```

### 4.2. Phát lại riêng cho một consumer qua topic trung gian (`--consumer`)

Khi chỉ một consumer bị ảnh hưởng, truyền `--consumer <tên-consumer>` để công cụ định tuyến thông điệp sang `<topic>.replay.<consumer>`:

```bash
outbox-replay \
  --schema task \
  --tenant 11111111-1111-4111-8111-111111111111 \
  --from 2026-10-20T08:00:00Z \
  --to   2026-10-20T10:30:00Z \
  --topic task.task.statuschanged.v1 \
  --consumer svc-scoring \
  --actor "ops.nguyen-van-a" \
  --reason "INC-2026-1020-scoring-resync" \
  --confirm
```

### 4.3. Tiếp tục chạy khi bị ngắt giữa chừng (Checkpoint Resume)

Nếu tiến trình nhận tín hiệu `SIGINT`/`SIGTERM` hoặc mất kết nối giữa chừng, công cụ in ra dòng `INTERRUPTED CHECKPOINT` trên `stderr` và trường `lastCreatedAt`, `lastEventId` trong kết quả JSON, đồng thời vẫn gửi sự kiện kiểm toán `TAM_DUNG` lên `sys.audit.v1`. Để chạy tiếp từ đúng vị trí dừng:

```bash
outbox-replay \
  --schema task \
  --tenant 11111111-1111-4111-8111-111111111111 \
  --from 2026-10-20T08:00:00Z \
  --to   2026-10-20T10:30:00Z \
  --after-created-at "2026-10-20T09:14:22.418921Z" \
  --after-id "0195b000-0000-7000-8000-000000000142" \
  --actor "ops.nguyen-van-a" \
  --reason "INC-2026-1020-resume" \
  --confirm
```

---

## 5. Bước 3: Xác nhận khử trùng ở Consumer và kiểm toán

1. **Kiểm tra sự kiện kiểm toán (`sys.audit.v1`):**
   Xác nhận trường `auditEventId` trong đầu ra của `outbox-replay` đã được `SVC-09 ledger-service` / `audit-archiver` ghi nhận với `action = "PHAT_LAI_OUTBOX"`.
2. **Kiểm tra khử trùng tại bảng `<schema>.inbox_processed` của consumer:**
   ```sql
   SELECT consumer, source_topic, COUNT(*) AS processed_rows, MAX(processed_at) AS last_processed_at
   FROM scoring.inbox_processed
   WHERE tenant_id = '11111111-1111-4111-8111-111111111111'
     AND processed_at >= NOW() - INTERVAL '30 minutes'
   GROUP BY consumer, source_topic;
   ```
3. **Kiểm tra metric consumer:**
   Quan sát metric `inbox_duplicate_events_total` và `kafka_consumer_group_lag` trên Grafana để chắc chắn consumer đã tiêu thụ hết lô phát lại và không phát sinh bản ghi lỗi trên `<topic>.dlq.<consumer>`.

---

## 6. Quy trình quay lui (Rollback)

**Không cần quay lui dữ liệu khi phát lại đúng phạm vi:**
- Công cụ giữ nguyên `event_id` (`ce_id`), `event_key` (`tenantId:aggregateId`), `aggregate_version` và `payload` Avro nguyên bản.
- Mọi consumer trong hệ thống đều thực thi mẫu Inbox idempotent (`INSERT INTO <schema>.inbox_processed ... ON CONFLICT (consumer, event_id) DO NOTHING`) và kiểm tra phiên bản đơn điệu (`<schema>.inbox_aggregate_version`), nên sự kiện đã xử lý hoặc sự kiện có phiên bản cũ hơn trạng thái hiện tại đều bị bỏ qua an toàn.
- Nếu phát hiện truyền nhầm `--consumer` sang một topic `.replay.<consumer>` chưa có consumer lắng nghe, chỉ cần dừng Job và xoá topic tạm `<topic>.replay.<consumer>` theo quy trình quản trị Kafka.
