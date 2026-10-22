# Sổ tay vận hành: Chuyển tenant giữa các cell (`tenant-registry`)

> **Mã truy vết:** DV-03-07 · ADR-29, ADR-07 · TL-01 "Chuyển tenant giữa các cell" · TL-03 `registry.tenant_cell`, "Sổ cái bất biến", "Kho đối tượng Ceph" · hợp đồng `platform.yaml` `requestTenantMove`, `approveTenantMove`, `getTenantMove`, `cancelTenantMove`, `resolveTenantCell` (421).
> **Dịch vụ:** SVC-03 `services-go/tenant-registry` (gói `internal/move`), chart `deploy/charts/tenant-registry` (khối `move`).

## 1. Khi nào chuyển

Cell vượt 1,5 triệu tài khoản hoặc 75.000 người dùng đồng thời (ADR-29), hoặc tenant tốt nghiệp lên bridge, silo. Thời gian chỉ đọc mục tiêu 5–15 phút cho tenant 100.000 tài khoản; lên lịch cắt chuyển ngoài giờ hành chính bằng `plannedAt`.

## 2. Chuẩn bị (trước khi đề nghị)

1. Cụm PostgreSQL nguồn và đích chạy `wal_level = logical`, còn chỗ trống `max_replication_slots`, `max_wal_senders`:
   ```sql
   SHOW wal_level;
   SELECT count(*) AS slot_dang_dung, current_setting('max_replication_slots') FROM pg_replication_slots;
   ```
2. CSDL dữ liệu tenant ở cell đích (`td_pool` hoặc `td_<mã>`) đã có đủ schema cùng phiên bản Flyway với nguồn (so `flyway_schema_history` hai bên). Thiếu bảng thì saga dừng ở `PROVISION_TARGET` và tự quay lui.
3. Cell đích `HOAT_DONG`, đúng mô hình thuê bao, tải sau khi thêm không quá 85% (BR-01-02); tenant đang `DANG_VAN_HANH`.
4. Secret `tenant-registry-move` có mục của cả hai cell trong `cell-db.json` và `objects.json`; multisite Ceph RGW (INF-12) đã đồng bộ bucket `evidence`.
5. Lưu lại số liệu đối chiếu trước chuyển ở nguồn (để lập biên bản):
   ```sql
   SELECT 'okr.objective' AS bang, count(*) FROM okr.objective WHERE tenant_id = '<tenant_id>';  -- lặp cho các bảng chính
   ```

## 3. Thực hiện (bốn mắt)

1. Quản trị nền tảng thứ nhất: `POST /api/v1/registry/tenants/{id}/move` với `If-Match` là phiên bản tenant, thân `targetCellId`, `reason` (số văn bản), tuỳ chọn `targetTenancyModel`, `dataRegion`, `plannedAt`. Kết quả `CHO_DUYET`.
2. Quản trị nền tảng thứ hai (khác người đề nghị, đã xác thực bước lên AAL2): `POST .../move/{moveId}/approve` với `If-Match` là phiên bản lệnh. Tenant sang `DANG_CHUYEN_CELL`; saga chạy sáu bước:

| Bước | Việc làm | Người dùng |
|---|---|---|
| `PROVISION_TARGET` | Liệt kê bảng có `tenant_id` (trừ outbox, inbox), kiểm cấu trúc đích, tạo phân vùng của tenant ở đích, đặt tạm `REPLICA IDENTITY FULL` cho bảng có khoá chính không chứa `tenant_id` | Ghi bình thường |
| `INITIAL_SNAPSHOT` | Publication `mv_<moveId>` có `WHERE (tenant_id = ...)` ở nguồn, subscription cùng tên ở đích, chép đối tượng lần đầu | Ghi bình thường |
| `WAL_CATCHUP` | Chờ mọi bảng `r` (ready) trong `pg_subscription_rel` và độ trễ dưới 1 giây | Ghi bình thường |
| `READ_ONLY_FREEZE` | Đợi tới `plannedAt`; phát `TENANT_WRITE_LOCKED`; đặt rào chặn ghi ở nguồn; chờ đích xác nhận tới LSN hiện tại; so khớp số dòng và tổng băm từng bảng; đồng bộ và so khớp đối tượng | Chỉ đọc (503) |
| `SWITCH_ACTIVE_CELL` | Chuyển giao sổ cái (niêm phong ở nguồn, nối chuỗi ở đích); đóng gắn cell cũ, mở gắn cell mới; gỡ subscription, publication; khôi phục replica identity | Chỉ đọc |
| `REOPEN_WRITE` | Tenant về `DANG_VAN_HANH`, phát `TENANT_WRITE_UNLOCKED`; giữ dữ liệu nguồn 30 ngày | Ghi ở cell mới |

3. Theo dõi: `GET .../move/{moveId}` (`status`, `currentStep`) hoặc `GET /api/v1/jobs/{moveId}`; chi tiết:
   ```sql
   SELECT params->>'status' AS trang_thai, params->>'currentStep' AS buoc, params->>'readOnlySeconds' AS giay_chi_doc,
          jsonb_pretty(params->'verification') AS so_khop
   FROM registry.job WHERE id = '<moveId>';
   ```
   Ở cụm đích: `SELECT srrelid::regclass, srsubstate FROM pg_subscription_rel;` Ở nguồn: `SELECT application_name, replay_lag FROM pg_stat_replication WHERE application_name = 'mv_<moveId không gạch>';`

## 4. Kiểm chứng sau chuyển

1. `params.verification`: mọi bảng `ok = true`, `sourceRows = targetRows`, `sourceSum = targetSum`; `params.objects.ok = true`. Ghi `readOnlySeconds` vào biên bản.
2. Danh bạ: `SELECT cell_id, valid_from, valid_to FROM registry.tenant_cell WHERE tenant_id = '<tenant_id>' ORDER BY valid_from;` có đúng một dòng hiệu lực ở cell đích.
3. Định tuyến: gọi `GET /api/v1/registry/resolve?tenant=<mã>` bằng token có claim `cell` cũ phải nhận 421 với `Location` là URL cell mới; người dùng tải lại trang là vào cell mới.
4. Ghi ở cell cũ bị chặn (lỗi `25006`), ghi ở cell mới được. Không còn `pg_publication`, `pg_subscription`, slot `mv_%`.
5. Sổ cái: ledger-service cell đích có bản ghi `TENANT_MOVED_IN` mang `sealId` của cell nguồn; công cụ kiểm toàn vẹn chuỗi chạy đạt.

## 5. Quay lui

- **Trước cắt chuyển** (`SWITCH_ACTIVE_CELL` chưa xong): `POST .../move/{moveId}/cancel` bởi một quản trị nền tảng (ghi đề nghị huỷ), rồi một quản trị nền tảng khác gọi lại để xác nhận. Saga gỡ subscription, publication, rào chặn, khôi phục replica identity, xoá dữ liệu đã chép và phân vùng đã tạo ở đích, mở khoá sổ cái nguồn, đưa tenant về `DANG_VAN_HANH`, lệnh `DA_HUY`. Lệnh còn `CHO_DUYET` thì một người huỷ là đủ.
- **Saga tự quay lui** khi một bước trước cắt chuyển lỗi hết số lần thử (lệnh `THAT_BAI`, `params.failure` ghi lý do).
- **Sau cắt chuyển**: không quay lui tự động (409 `REGISTRY-409-MOVE-ALREADY-CUTOVER`). Nếu cần trả về cell cũ, tạo lệnh chuyển mới theo chiều ngược lại (dữ liệu nguồn cũ còn giữ 30 ngày chỉ dùng để đối chiếu, không ghi đè).
- **Quay lui chưa xong** (`params.failure` bắt đầu "Quay lui chưa xong"): dọn tay theo thứ tự `DROP SUBSCRIPTION IF EXISTS mv_<id>` (đích), `DROP PUBLICATION IF EXISTS mv_<id>` và `pg_drop_replication_slot` (nguồn), `DROP TRIGGER IF EXISTS mv_<id> ON <bảng>` (nguồn), `ALTER TABLE <bảng> REPLICA IDENTITY DEFAULT` theo `params.replicaIdentity`, `DELETE FROM <bảng> WHERE tenant_id = ...` ở đích, rồi khởi động lại một pod tenant-registry để saga ghi kết quả.

## 6. Dọn dữ liệu cell nguồn sau 30 ngày

Tác vụ định kỳ (`MOVE_PURGE_INTERVAL`, mặc định 24 giờ) gỡ rào chặn và xoá dữ liệu tenant ở cụm nguồn trong cùng một giao dịch, xoá đối tượng ở bucket không khoá Object Lock, ghi số dòng đã xoá vào `params.purgedRows`, `params.purgedAt`. Lập biên bản từ hai trường này cùng số liệu so khớp ở mục 4; bucket có Object Lock (`evidence`, `dossier` compliance) do chính sách vòng đời của Ceph xử lý khi hết hạn khoá.
