# INF-11 · ADR-02, ADR-14, ADR-15, ADR-18 PostgreSQL CloudNativePG sáu cụm, PgBouncer, Valkey, OpenSearch, ClickHouse theo định cỡ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-08 |
| Truy vết | TL-04 mục 4.5–4.7, 6.7 → TL-03 ch. "Các cụm PostgreSQL trong một cell", "Bảo mật theo dòng", "Bộ nhớ đệm Valkey" → ADR-02, ADR-07, ADR-14, ADR-15, ADR-18, ADR-22 → SEC-57 → NFR-05 (mất node CSDL RTO ≤ 5 phút) → tiêu chí E2, E5, C5 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khai báo dạng mã toàn bộ tầng dữ liệu trong một cell: sáu cụm PostgreSQL 17 do CloudNativePG quản lý kèm PgBouncer chế độ transaction, Valkey Cluster, OpenSearch và ClickHouse, với tham số và kích thước lấy từ values hồ sơ của INF-03. Khi xong, trên kind các cụm thu nhỏ chạy được, xoá pod primary thì chuyển đổi trong ≤ 30 s, ứng dụng thử qua PgBouncer đặt `app.tenant_id` cục bộ giao dịch và RLS chặn đọc chéo tenant.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Định cỡ PostgreSQL" (662–751, CR `pg-core` mẫu 681–747), "Định cỡ Kafka, Valkey, OpenSearch, ClickHouse" (753–775), "Định cỡ pool kết nối CSDL" (777–810), "Bảo vệ CSDL khi co giãn" (1567–1577).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Không truy cập chéo và quyền tối thiểu" (149–180, vai trò `svc_`/`ro_`/`mig_`/`cdc_`), "Các cụm PostgreSQL trong một cell" (631–644), bố trí silo, bridge, pool (645–683), "Bảo mật theo dòng (Row-Level Security)" (684–755), mục Valkey (4476–4505).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "PostgreSQL" (311–355), "Ngân sách kết nối theo hồ sơ" (357–372), "Kafka và Valkey", "OpenSearch và ClickHouse" (374–404).
- `docs/quy-dinh/BACKEND.md` mục 3 (quy ước 5, 7), values hồ sơ của INF-03.

## 3. Phạm vi công việc

- Viết chart `deploy/charts/pg-cell` sinh sáu CR `Cluster` của CNPG (`pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure`) từ values: ảnh PostgreSQL 17 theo digest, `instances: 3`, `synchronous: {method: any, number: 1, dataDurability: required}`, `primaryUpdateStrategy: supervised`, tham số đúng CR mẫu `pg-core` (ví dụ `statement_timeout 30s`, `lock_timeout 5s`, `idle_in_transaction_session_timeout 60s`, `ssl_min_protocol_version TLSv1.3`), `pg_hba` chỉ `hostssl ... cert`, StorageClass `nvme-local`, anti-affinity theo vùng bắt buộc, `nodeSelector` pool dữ liệu; `pg-ledger`, `pg-secure` đặt vào pool `security-zone`.
- Khi `postgres.consolidated: true` (hồ sơ `compact`), sinh hai cụm `pg-core` (DB `core`, `flowable`, `platform`, `iam`) và `pg-ledger` (DB `ledger`, `esign`, `ai`) theo TL-05.
- Sinh `Pooler` PgBouncer `rw` và `ro` cho mỗi cụm: `pool_mode = transaction`, `max_prepared_statements = 200`, `default_pool_size` theo (db, user) từ values (ví dụ `pg-core`: task 14, okr 6, appraisal 6, product 5, config 3, policy 2, dossier 2, oversight 2), `reserve_pool_size` 2–3, `query_wait_timeout = 5`.
- Viết Job khởi tạo CSDL và vai trò theo TL-03: mỗi schema bốn vai trò `svc_<schema>` (NOBYPASSRLS), `ro_<schema>`, `mig_<schema>` (sở hữu đối tượng), `cdc_<schema>` (REPLICATION); bridge mỗi tỉnh một database; CR `Database` của CNPG khi có thể; mật khẩu lấy từ OpenBao qua ESO (không ghi giá trị), chuẩn bị cho secret động của INF-13.
- Viết values Valkey Cluster 3 shard × (primary + replica khác vùng), TLS và ACL, `maxmemory` theo hồ sơ, `allkeys-lfu`; OpenSearch (master, data, TLS, vai trò); ClickHouse 2 shard × 2 bản sao + 3 Keeper. TL-04 không chỉ định operator hay chart cho ba thành phần này: chọn một phương án cho mỗi thành phần, nêu lý do trong PR và chờ người phụ trách duyệt (AGENTS.md quy tắc 1).
- Điểm lệch cần lưu ý: TL-03 mô tả năm cụm `pg-biz`, `pg-wf`, `pg-ledger`, `pg-iam`, `pg-sec`, còn TL-04 mục 4.5 và TL-05 (v1.0, mới nhất) mô tả sáu cụm `pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure`. Chọn sáu cụm theo TL-04/TL-05, giữ cách bố trí CSDL `td_<mã>` và `cell_shared` của TL-03 bên trong `pg-core`, `pg-platform`; ghi bảng ánh xạ vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.
- Gợi ý tách: (a) CNPG sáu cụm + Pooler; (b) Job vai trò, database, RLS kiểm thử; (c) Valkey; (d) OpenSearch và ClickHouse.

## 4. Ngoài phạm vi

- Sao lưu Barman Cloud, replica cluster phía Nam (INF-17, INF-18); secret động OpenBao (INF-13).
- Migration Flyway của từng module (task NEN, CORE); kiểm ngân sách kết nối trong CI (INF-03).

## 5. Tệp được phép sửa

- `deploy/charts/pg-cell/**`, `deploy/charts/valkey-cell/**`, `deploy/charts/opensearch-cell/**`, `deploy/charts/clickhouse-cell/**` (tên chart suy luận), `deploy/charts/vt-platform/values*.yaml` phần bật chart.
- `deploy/environments/kind/**`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template` cho bốn hồ sơ, `kubeconform -strict` (schema CRD CNPG) không lỗi; hồ sơ `compact` sinh đúng hai cụm.
- Trên kind có operator CNPG: cụm thu nhỏ `pg-core` (3 instance nhỏ) sẵn sàng; xoá pod primary, bản dự phòng lên primary trong ≤ 30 s và ghi tiếp được qua Pooler (đo và ghi trong báo cáo).
- Kiểm thử RLS qua PgBouncer thật: phiên của `svc_task` đặt `set_config('app.tenant_id', <A>, true)` không đọc được dòng tenant B; `SET` cấp phiên bị chặn theo quy ước; kết nối không TLS bị từ chối.
- Valkey, OpenSearch, ClickHouse thu nhỏ khởi động được trên kind và từ chối kết nối không xác thực (ghi rõ thành phần nào không chạy được vì tài nguyên).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
