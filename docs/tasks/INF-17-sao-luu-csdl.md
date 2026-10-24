# INF-17 · TL-04 ch.8, OPS-18 Sao lưu phần 1: CNPG Barman Cloud, kho sao lưu bất biến, `restore-check` hằng ngày, Velero và etcd

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-17` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-11 |
| Truy vết | TL-04 ch.8 (mục 8.1, 8.2, 8.6, 8.9, 8.12) → NFR-05, NFR-09 → SEC-42, SEC-44 → OPS-18, OPS-21 → DR-04, DR-05, DR-06, DR-09 → tiêu chí C5 (TCVN sao lưu) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng lớp sao lưu thứ hai độc lập với nhân bản theo nguyên tắc 3-2-1-1-0 của TL-04 chương 8 cho phần CSDL và cụm Kubernetes: sáu cụm PostgreSQL sao lưu nền và WAL liên tục bằng plugin Barman Cloud của CNPG sang kho sao lưu bất biến, etcd chụp mã hoá mỗi 6 giờ, Velero sao lưu tài nguyên và volume ngoài CSDL, và tác vụ `restore-check` khôi phục thử mỗi ngày làm bằng chứng cho số "0". Khi xong, trên kind một cụm PostgreSQL thử được khôi phục tự động từ bản sao lưu mới nhất, có metric thành công và cảnh báo khi quá hạn.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Sao lưu dự phòng và phục hồi thảm hoạ": "Mục tiêu và nguyên tắc 3-2-1-1-0" (1981–2001), "PostgreSQL: CloudNativePG và Barman Cloud" (2003–2078), "Kubernetes: Velero và etcd" (2246–2295), "Bảng lịch sao lưu" (2309–2330), "Diễn tập khôi phục" (2431–2437).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Kho sao lưu" (463–473).
- Chart `pg-cell` của INF-11, cấu hình OpenBao và ESO của INF-13.

## 3. Phạm vi công việc

- Viết cấu hình bucket kho sao lưu `ceph-sn-bk` theo cell (`bk-cell-<x>-pg`, `bk-cell-<x>-ch`, `bk-cell-<x>-k8s`): Object Lock COMPLIANCE 35 ngày mặc định, 10 năm cho bản cuối năm `pg-ledger`, `pg-secure`; khoá S3 của cell chỉ `PutObject`, `GetObject`, `ListBucket`, cấm `DeleteObject` và đổi cấu hình khoá; dọn bằng lifecycle dưới tài khoản quản trị kho (SEC-44, SEC-42).
- Trong chart `pg-cell`, thêm CR `ObjectStore` (plugin `barman-cloud.cloudnative-pg.io`) cho mỗi cụm: `retentionPolicy: 35d`, nén `zstd`, mã hoá `AES256`, WAL `maxParallel 8`, `archive_timeout: 60s`; khoá S3 nạp qua ESO từ đường dẫn `kv/cell-<x>/backup/pg`.
- Thêm `ScheduledBackup` 01:30 hằng ngày (`method: plugin`, `target: prefer-standby`), sau tác vụ đối chiếu điểm OPS-21 lúc 00:30, và chính sách giữ bản cuối tháng 12 tháng.
- Viết CronJob `restore-check` trong namespace `data-pg-restore`: luân phiên mỗi ngày một cụm của một cell, dựng cụm tạm từ bản nền mới nhất + WAL tới 1 giờ trước, chạy truy vấn kiểm (đếm dòng so thống kê, ràng buộc khoá ngoại, với `pg-ledger` gọi kiểm chuỗi băm khi DV-09 sẵn sàng), xuất `backup_restore_check_success`, `backup_restore_duration_seconds`, rồi huỷ cụm.
- Viết luật cảnh báo `BackupMissing` (không có bản nền 26 giờ hoặc WAL trễ > 5 phút) và `RestoreCheckStale` (8 ngày) để INF-15 nạp.
- Viết CronJob chụp etcd mỗi 6 giờ: `etcdctl snapshot save`, `etcdutl snapshot status`, mã hoá `age` với recipient lấy từ OpenBao, đẩy lên `bk-cell-<x>-k8s`, xoá tệp tạm an toàn; giữ 30 ngày.
- Viết `Schedule` Velero `cell-resources-6h` (loại `kube-system`, `velero`, events, pods, replicasets; không chụp volume) và `stateful-volumes-daily` (namespace `ingress`, `platform-harbor`, `observability`, `snapshotMoveData`); loại `data-pg`, `data-kafka`, `data-search`.
- Gợi ý tách: (a) bucket + Barman Cloud + ScheduledBackup; (b) `restore-check` + cảnh báo; (c) etcd + Velero.

## 4. Ngoài phạm vi

- MirrorMaker 2, Ceph multisite, OpenSearch, ClickHouse, outbox 72 giờ (INF-18); replica cluster CNPG phía Nam và diễn tập DR (QA-14, QA-15).
- Dựng cụm Ceph sao lưu phía Nam vật lý và băng LTO (hạ tầng thật).

## 5. Tệp được phép sửa

- `deploy/charts/pg-cell/**` (ObjectStore, ScheduledBackup), `deploy/charts/backup-cell/**` (restore-check, etcd, Velero; tên suy luận), `deploy/charts/rgw-cell/**` phần bucket sao lưu.
- `ops/runbooks/sao-luu-*.md`, `deploy/environments/kind/**`, `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` (schema CRD CNPG, plugin Barman Cloud, Velero) không lỗi.
- Trên kind với kho S3 thử có Object Lock (MinIO hoặc RGW của INF-12): cụm PostgreSQL thử có bản nền và WAL liên tục; xoá thử một bản sao lưu bằng khoá của cell bị từ chối.
- `restore-check` chạy hết một vòng trên kind: dựng cụm tạm, truy vấn kiểm đạt, metric `backup_restore_check_success = 1`, cụm tạm bị huỷ; khi làm hỏng bản nền thử, metric bằng 0 và cảnh báo kích hoạt (kiểm bằng `promtool test rules`).
- Khôi phục PITR thử tới một thời điểm trước khi xoá dữ liệu, dữ liệu trở lại đúng (bằng chứng cho DR-06 rút gọn).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.

## 8. Bằng chứng chạy thật trên kind (24/10/2026, luồng B8)

Lần chạy dùng cụm gọn `vt-cell-kind` (`cluster-gon.yaml`, một control plane và một worker, kindnet, không Cilium) với cell-base đã cài. Kho sao lưu là một container Ceph RGW (`ceph-bk`) trên mạng kind, realm `cell-kind`, dựng bằng chart `rgw-cell` với `values-kind-rgw-sao-luu.yaml`; khoá S3 của cell là khoá giả lập OpenBao nằm ngoài repo. Toàn bộ các bước được gom vào `deploy/environments/kind/kiem-thu-sao-luu.sh` để chạy lại.

| Tiêu chí | Kết quả |
|---|---|
| Kho S3 có Object Lock | Tám bucket sao lưu tạo với Object Lock chế độ COMPLIANCE, hạn giữ tới 28/11/2026. |
| Bản nền và WAL liên tục | WAL được lưu trữ liên tục; ba bản nền `thu-nen-2` (20261024T032939), `thu-nen-3` (20261024T042327), `thu-nen-4` (20261024T042707). |
| Xoá bằng khoá của cell bị từ chối | Xoá một phiên bản, xoá không kèm VersionId và đổi cấu hình khoá đều trả `AccessDenied`; ngay cả quản trị kho cũng bị khoá đối tượng chặn. |
| PITR tới trước thời điểm xoá dữ liệu | Khôi phục về mốc trước lệnh xoá trả lại đủ 1000 dòng (bằng chứng DR-06 rút gọn). |
| `restore-check` một vòng | `rc-thu-7`: ĐẠT sau 62 s, `backup_restore_check_success 1`, `backup_restore_check_target_reached 1`, cụm tạm và ObjectStore tạm bị xoá. |
| Nguồn nhàn rỗi sau mốc | `rc-thu-9`: ĐẠT sau 94 s, tự chuyển sang khôi phục tới hết WAL, `target_reached 0`. |
| Bản nền hỏng | Ghi đè `thu-nen-3` bằng một phiên bản rác; `rc-thu-8` HỎNG ("General error exit code 4"), `backup_restore_check_success 0`. |
| Cảnh báo | `promtool check rules` (18 quy tắc) và `promtool test rules` đạt; kiểm thử tĩnh backup-cell 42/42, rgw-cell 11/11, script 73/73. |

Lỗi phát hiện khi chạy thật và đã sửa trong commit 60302170:

1. `restore-check.sh` sinh `nodeSelector: {}` và `tolerations: []` rỗng; CNPG coi đó là thay đổi đặc tả và khởi động lại primary vô hạn. Script nay bỏ hẳn khối affinity khi không có giá trị.
2. Khi cụm nguồn không có giao dịch sau mốc kiểm, PostgreSQL dừng với "recovery ended before configured recovery target was reached" và lần kiểm luôn hỏng. Script đọc nhật ký Job khôi phục, nhận ra trường hợp này, dựng lại cụm tạm khôi phục tới hết WAL và ghi metric mới `backup_restore_check_target_reached` (Role thêm quyền `pods/log get`).
3. Chính sách bucket của `rgw-cell` thiếu `s3:PutObject` trên ARN bucket cho tải lên nhiều phần; `values-kho-sao-luu.yaml` tắt `pam-ghi-phien` không dùng cho kho sao lưu.
4. Ảnh etcd trong `anh/Dockerfile` chuyển sang `gcr.io/etcd-development/etcd:v3.6.4` ghim digest (ảnh cũ không còn kéo được).
5. `nap-anh.sh` xử lý được dạng `repo:tag@sha256`, `tao-cum.sh` có công tắc `KHONG_CILIUM`, thêm `cluster-gon.yaml` cho máy 4 vCPU.

Chưa chạy: `kiem-thu-sao-luu.sh` được ghép từ các bước đã chạy tay ở trên nhưng chưa chạy liền một mạch từ đầu đến cuối; Velero và Job snapshot etcd chưa chạy trên kind vì cụm gọn không đủ CPU cho thêm thành phần và kind không có etcd ngoài để kiểm khôi phục; diễn tập DR đầy đủ đã bỏ khỏi phạm vi (YC-PT-19).
