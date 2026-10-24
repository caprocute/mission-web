# INF-18 · TL-04 ch.8 Sao lưu phần 2: MirrorMaker 2, Ceph multisite, snapshot OpenSearch, ClickHouse, Valkey, giữ outbox 72 giờ, lịch sao lưu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-18` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-17, INF-12 |
| Truy vết | TL-04 ch.8 (mục 8.3, 8.4, 8.5, 8.7, 8.9, 8.10) → NFR-05, NFR-09 → DR-07, DR-08, DR-10 (bước 8), DR-15 → bảng `<schema>.outbox_event`, `processed_event` → SEC-44 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện sao lưu và nhân bản cho các kho dữ liệu ngoài PostgreSQL theo bảng lịch sao lưu TL-04 mục 8.9: Kafka nhân bản một chiều sang TTDL phía Nam bằng MirrorMaker 2, minh chứng nhân bản multisite và sao lưu ngày bằng `rclone`, OpenSearch và ClickHouse chụp định kỳ, OpenBao chụp Raft mỗi giờ, kho Git và Harbor có bản gương, và cơ chế giữ outbox 72 giờ cùng công cụ phát lại phục vụ chuyển vùng. Khi xong, mọi dòng trong bảng lịch sao lưu có cấu hình dạng mã, có cách kiểm tra tự động, và Valkey được ghi rõ là không sao lưu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Kafka: MirrorMaker 2" (2114–2174), "Ceph: multisite RGW và Object Lock" (2176–2215), "OpenSearch, ClickHouse, Valkey" (2217–2244), "OpenBao, HSM và khoá" (2297–2303), "Bảng lịch sao lưu" (2309–2330), "Giữ outbox 72 giờ để phát lại sau chuyển vùng" (2332–2334).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Transactional Outbox và Debezium" (4205–4267, dọn phân vùng outbox) và mục inbox (4269–4287).
- Chart Kafka (INF-10), RGW (INF-12), sao lưu CSDL (INF-17).

## 3. Phạm vi công việc

- Viết CR `KafkaMirrorMaker2 mm2-hl-to-sn` đúng mẫu TL-04 mục 8.3: phiên bản 4.0.0, 3 replica, mTLS hai chiều, `topicsPattern` các miền nghiệp vụ và hệ thống, loại `.*\.(retry|replay)\..*`, `IdentityReplicationPolicy`, source `tasksMax 24`, checkpoint đồng bộ offset 30 s; tài liệu thao tác đảo chiều khi chuyển vùng.
- Viết Job sao lưu ngày minh chứng bằng `rclone sync --backup-dir` dưới tài khoản chỉ đọc nguồn sang kho sao lưu, và Job kiểm mẫu 1.000 đối tượng mỗi ngày so SHA-256 với `evidence.evidence_version` (đọc qua tài khoản `ro_evidence`).
- Viết chính sách OpenSearch Snapshot Management `daily-to-sn` (tạo 02:00, xoá 04:00, giữ 7–14 bản, repo `bk-sn`, chỉ mục `okr-*,mission-*,evidence-*,org-*`, múi giờ `Asia/Ho_Chi_Minh`) và CronJob ClickHouse `BACKUP DATABASE ... TO S3` (đầy đủ chủ nhật, gia tăng hằng ngày, giữ 5 tuần, khoá qua Secret ESO).
- Ghi quyết định Valkey không sao lưu (bộ nhớ đệm, khoá chống trùng, bộ đếm giới hạn tốc độ) trong runbook và wiki.
- Viết CronJob chụp Raft OpenBao mỗi giờ (`bao operator raft snapshot save`), giữ 90 ngày; gương kho Git sang phía Nam kèm bundle hằng ngày; cấu hình nhân bản Harbor sang `harbor-sn` theo sự kiện đẩy ảnh, Job kéo thử theo digest hằng ngày.
- Viết cấu hình giữ dòng outbox đã đọc 72 giờ (phân vùng theo ngày, dọn bằng `DROP PARTITION`) dưới dạng tham số chung cho migration của các service, và công cụ phát lại theo DR-10 bước 8: Debezium phía Nam snapshot `when_needed` trên outbox, lọc `created_at` ≥ điểm an toàn (WAL cuối − 15 phút); consumer khử trùng theo `eventId`.
- Điểm lệch cần lưu ý: TL-03 dọn outbox bằng pg_partman `p_retention='3 days'`, TL-04 nói giữ 72 giờ; hai cách cùng thời hạn, chọn pg_partman của TL-03 làm cơ chế. Tác vụ `audit-archiver` (`sys.audit.v1` → Parquet ký, Object Lock 10 năm) có trong bảng lịch nhưng không có hàng khung riêng: không làm trong task này, ghi vào báo cáo để bổ sung khung.
- Gợi ý tách: (a) MM2 + công cụ phát lại outbox; (b) rclone + kiểm mẫu minh chứng; (c) OpenSearch, ClickHouse, OpenBao, Git, Harbor.

## 4. Ngoài phạm vi

- Replica cluster CNPG phía Nam và các bước chuyển vùng (QA-15, VH-02); `audit-archiver` (đề xuất hàng khung mới).
- Băng LTO ngoại tuyến hằng tháng (hạ tầng vật lý).

## 5. Tệp được phép sửa

- `deploy/charts/kafka-cell/**` (MM2), `deploy/charts/backup-cell/**`, `deploy/charts/opensearch-cell/**`, `deploy/charts/clickhouse-cell/**`, `deploy/charts/openbao-cell/**` phần snapshot, `deploy/clusters/mgmt/harbor/**` phần nhân bản.
- `deploy/tools/outbox-replay/**` (công cụ phát lại, suy luận về vị trí), `ops/runbooks/sao-luu-*.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm template`, `kubeconform -strict` (schema Strimzi, Velero) không lỗi; `shellcheck` sạch cho script.
- Trên kind với hai cụm Kafka thu nhỏ (hoặc hai cụm trên hai namespace): MM2 nhân bản topic thử, đối soát offset bằng 0 chênh lệch sau khi dừng phía nguồn.
- Công cụ phát lại outbox có kiểm thử tích hợp (Testcontainers PostgreSQL + Kafka): phát lại từ điểm an toàn không tạo bản ghi trùng ở consumer có inbox.
- Snapshot OpenSearch và backup ClickHouse thu nhỏ chạy và khôi phục được vào chỉ mục, database tạm; Job kiểm mẫu minh chứng phát hiện một đối tượng bị sửa có chủ đích.
- Báo cáo cuối task theo AGENTS.md mục 6, có bảng đối chiếu từng dòng của bảng lịch sao lưu với tệp cấu hình tương ứng.

## 7. Báo cáo

Theo AGENTS.md mục 6.

## 8. Bằng chứng chạy thật trên kind (24/10/2026, luồng B8)

Lần chạy dùng cụm gọn `vt-cell-kind` như INF-17, cùng kho Ceph RGW `ceph-bk` có Object Lock.

**MirrorMaker 2** (`deploy/environments/kind/kiem-thu-mm2.sh`, 11/11 đạt). Hai namespace `kafka-hl` (vai trò nguồn) và `kafka-sn` (vai trò đích) cài cùng chart kafka; Strimzi theo dõi cả hai; mỗi namespace có default-deny, DNS và đường tới kube-apiserver, nên NetworkPolicy `mm2-ra` của chart phải tự đủ. Sau khi ghi 8000 bản tin và dừng phía nguồn, offset cuối hai phía khớp tuyệt đối (phân vùng 0: 2236, phân vùng 1: 5764, chênh lệch 0), nội dung trùng khớp theo SHA, topic đích giữ đúng tên (chính sách định danh), topic thử lại bị loại trừ, offset nhóm dịch sang đích không vượt nguồn (nguồn 0:2236 1:1764, đích 0:2236 1:1, MM2 dịch thận trọng), và người dùng `mm2-hl-user` chỉ đọc được phía nguồn.

**Snapshot OpenSearch** (`deploy/environments/kind/kiem-thu-snapshot-opensearch.sh`, 13/13 đạt). OpenSearch 2.19.3 với plugin repository-s3 (kiểm SHA-512), kho `bk-sn` trên bucket Object Lock, chính sách SM `daily-to-sn` cùng bản sao chạy mỗi phút; snapshot `daily-to-sn-moi-phut-2026-10-24t04:35:11-tpmjrz5e` tạo thành công; vai trò `sao-luu` của chart bị 403 khi ghi chỉ mục, tạo hay xoá snapshot; `khoi-phuc-thu-opensearch.sh` khôi phục được cả bốn nhóm chỉ mục vào chỉ mục tạm và số tài liệu khớp.

Lỗi phát hiện khi chạy thật và đã sửa trong commit 60302170:

1. CR `KafkaMirrorMaker2` ghi cứng `replication.factor: 3` cho topic nội bộ, không chạy được trên cụm ít broker; nay lấy từ `mirrorMaker2.heSoNhanBan` (mặc định 3).
2. Connector checkpoint chỉ phát hiện nhóm mới mỗi 600 giây nên offset nhóm đồng bộ chậm hơn mục tiêu; thêm `refresh.groups.interval.seconds` theo `dongBoOffsetGiay`.
3. Vai trò `sao-luu` chỉ có `indices_monitor` nên `_cat/count` trả 403; `khoi-phuc-thu-opensearch.sh` chuyển sang đếm bằng `_cat/indices?h=docs.count`.

Chưa chạy: backup ClickHouse thu nhỏ (cụm gọn không đủ CPU cho ClickHouse cùng Kafka và OpenSearch); kiểm thử tích hợp công cụ phát lại outbox không thuộc yêu cầu chạy thật lần này; Ceph multisite cần hai cụm Ceph nên chưa dựng; Job kiểm mẫu phát hiện đối tượng bị sửa và script ClickHouse mới kiểm trong `deploy/charts/backup-cell/tests/kiem-thu-script.sh` (PostgreSQL thật có RLS, kho S3 giả; 73/73 đạt), chưa chạy trên kho Ceph.
