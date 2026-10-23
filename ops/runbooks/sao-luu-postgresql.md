# Sổ tay vận hành: Sao lưu PostgreSQL bằng Barman Cloud và khôi phục thử hằng ngày

> **Mã truy vết:** INF-17 · TL-04 chương 8 "Mục tiêu và nguyên tắc 3-2-1-1-0", "PostgreSQL: CloudNativePG và Barman Cloud", "Bảng lịch sao lưu" · NFR-05, NFR-09 · SEC-10, SEC-42, SEC-44 · OPS-18, OPS-21 · DR-04, DR-05, DR-06.
> **Thành phần:** plugin `barman-cloud.cloudnative-pg.io` (plugin-barman-cloud v0.15, cài cùng CNPG trong `cnpg-system`); `ObjectStore` `bk-sn-<cụm>` và `ScheduledBackup` `<cụm>-daily` của chart `data-pg` (`templates/sao-luu.yaml`, khối `backup`); bucket `bk-cell-<mã>-pg` trên kho sao lưu `ceph-sn-bk` (chart `rgw-cell` với `values-kho-sao-luu.yaml`); CronJob `restore-check` và `giu-dai-han` trong namespace `data-pg-restore` (chart `backup-cell`).

## 1. Sơ đồ và lịch

Mỗi cụm của cell (`pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure`; hồ sơ compact còn `pg-core` và `pg-ledger`) đẩy WAL liên tục qua sidecar Barman Cloud trong pod instance (`archive_timeout` 60 giây, nên RPO khi khôi phục từ sao lưu không quá khoảng 1 phút) và chụp bản nền hằng ngày lúc 01:30 giờ Việt Nam từ standby (lịch CNPG tính theo UTC: `0 30 18 * * *`), sau tác vụ đối chiếu điểm OPS-21 lúc 00:30. Dữ liệu nằm ở `s3://bk-cell-<mã>-pg/<cụm>/<cụm>/base/<backupId>/` và `.../wals/`, nén (WAL `zstd`, bản nền `lz4`) và mã hoá phía máy chủ `AES256`.

| Lớp | Giữ | Bất biến | Cơ chế |
|---|---|---|---|
| Bản nền và WAL (PITR) | 35 ngày | Object Lock COMPLIANCE 35 ngày (mặc định của bucket) | Vòng đời bucket đặt delete marker sau 36 ngày, xoá phiên bản cũ khi khoá hết hạn |
| Bản cuối tháng | 12 tháng | Hạn giữ từng đối tượng tới cùng ngày năm sau | Job `giu-dai-han` chép sang `dai-han/thang/<cụm>/`, vòng đời dọn sau 367 ngày |
| Bản cuối năm `pg-ledger`, `pg-secure` | 10 năm | Hạn giữ từng đối tượng 10 năm | Job `giu-dai-han` ngày 31/12 chép sang `dai-han/nam/<cụm>/`, vòng đời dọn sau 3654 ngày |

Khoá S3 của cell (`kv/cell-<mã>/backup/pg`, Secret `bk-sn-s3-pg`) chỉ đọc, ghi, liệt kê và kéo dài hạn giữ (ở COMPLIANCE không rút ngắn được); không có `DeleteObject`, không đổi Object Lock, vòng đời hay chính sách. Vì vậy `retentionPolicy: 35d` của `ObjectStore` chỉ để CNPG đánh dấu bản hết hạn trong danh mục: khi CNPG thử xoá, RGW từ chối và CNPG ghi cảnh báo, sao lưu mới không bị ảnh hưởng. Mọi việc dọn chạy bằng vòng đời dưới tài khoản quản trị kho sao lưu, miền quản trị riêng chỉ vào được qua PAM (SEC-42).

## 2. Triển khai lần đầu cho một cell

1. Cài plugin-barman-cloud v0.15 (manifest của dự án CNPG, ảnh qua Harbor) vào `cnpg-system`; plugin cần cert-manager.
2. Trên máy quản trị kho sao lưu: tạo người dùng RGW `bk-cell-<mã>-pg`, `-ch`, `-k8s` và tài khoản quản trị `kho-quan-tri-cell-<mã>` bằng `radosgw-admin` của `ceph-sn-bk`; ghi khoá vào OpenBao: khoá quản trị ở `kv/ceph-sn-bk/cell-<mã>/kho-quan-tri-cell-<mã>` (thuộc tính `access-key`, `secret-key`), khoá của cell ở `kv/cell-<mã>/backup/<pg|ch|k8s>` (thuộc tính `ACCESS_KEY_ID`, `ACCESS_SECRET_KEY`).
3. Argo CD đồng bộ bản phát hành thứ hai của `rgw-cell`: `-f deploy/charts/rgw-cell/values-kho-sao-luu.yaml --set cell=<mã>`. Job `rgw-bucket-<băm>` trong `secure-data` tạo ba bucket có Object Lock, đặt vòng đời và chính sách; chạy lại cho cùng kết quả.
4. Ở values `data-pg` của cell: giữ `backup.enabled: true`, đặt `backup.egressTo` là khối địa chỉ RGW kho sao lưu (NetworkPolicy `pg-sao-luu`); Secret CA `vt-noi-bo-ca` phải có trong `data-pg` và `secure-data`.
5. Cài chart `backup-cell` với `cell`, `image.digest`, `khoSaoLuu.egressTo`, `pushgateway`, `restoreCheck.lechNgay` (khác nhau giữa các cell), và `cums` khớp các cụm thật của hồ sơ (compact chỉ hai cụm).
6. Kiểm: `kubectl -n data-pg get objectstores,scheduledbackups`; sau 01:30 `kubectl -n data-pg get backups` có bản `completed`; `kubectl cnpg status pg-core -n data-pg` ở mục "Continuous Backup status" có "Working WAL archiving: OK".

## 3. Cảnh báo `BackupMissing`

Có hai biến thể, phân biệt bằng nhãn `vt_ly_do`.

- `ban-nen`: `barman_cloud_cloudnative_pg_io_last_available_backup_timestamp` cũ hơn 26 giờ. Xem `kubectl -n <ns> get backups -l cnpg.io/cluster=<cụm> --sort-by=.metadata.creationTimestamp` và `describe` bản lỗi; nguyên nhân thường gặp: standby không sẵn sàng (CNPG chuyển sang primary khi `prefer-standby`), sidecar thiếu bộ nhớ, khoá S3 hết hạn hoặc bị thu hồi, RGW kho sao lưu đầy. Khi đã sửa, chụp bù: `kubectl cnpg backup <cụm> -n <ns> --method plugin --plugin-name barman-cloud.cloudnative-pg.io`.
- `wal`: primary không lưu trữ WAL quá 5 phút. Xem `kubectl -n <ns> logs <cụm>-<n> -c plugin-barman-cloud` và `SELECT * FROM pg_stat_archiver;` trên primary. WAL dồn lại trên đĩa `walStorage` cho tới khi lưu trữ được; nếu đĩa WAL vượt 80% thì báo trực CSDL ngay, không xoá tệp WAL bằng tay.

Lỗi "Expected empty archive" xuất hiện khi một cụm được tạo lại cùng tên trên đường dẫn đã có dữ liệu (Object Lock không cho xoá): đặt `serverName` mới cho cụm (tham số plugin trong `clusters.yaml`, ví dụ `pg-core-r2`) và ghi vào biên bản; đường dẫn cũ vẫn dùng để khôi phục tới khi hết hạn.

## 4. Khôi phục thử hằng ngày (`restore-check`)

CronJob `restore-check` chạy 05:00 hằng ngày, chọn cụm thứ `(số ngày từ 1970-01-01 + lechNgay) mod số cụm`, dựng `ObjectStore` chỉ đọc `rc-<cụm>` và cụm CNPG tạm `rc-<cụm>-<ngày>` (một instance, không lưu trữ WAL) trong `data-pg-restore`, khôi phục tới 1 giờ trước, rồi chạy trên mọi CSDL:

- khoá ngoại: không còn ràng buộc chưa xác thực, không có dòng con mồ côi;
- đếm dòng so thống kê: bảng có `reltuples` từ 1.000 trở lên phải còn ít nhất một nửa số dòng thống kê;
- với `pg-ledger` (CSDL `ledger`): chuỗi băm từng phân vùng nối liền (`seq_no` liên tiếp, `prev_hash` bằng `entry_hash` liền trước), `chain_head` trỏ đúng bản ghi cuối.

Kết quả lên Pushgateway: `backup_restore_check_success` (1 hoặc 0), `backup_restore_duration_seconds`, `backup_restore_check_last_run_timestamp_seconds`, `backup_restore_check_last_success_timestamp_seconds` (chỉ khi đạt), nhãn `cell`, `cluster`. Cụm tạm luôn bị xoá khi job kết thúc, kể cả khi hỏng.

- `RestoreCheckFailed`: đọc nhật ký `kubectl -n data-pg-restore logs job/<job>`. "job khôi phục ... thất bại" là bản nền hoặc WAL không đọc được (hỏng, thiếu, sai khoá giải mã): kiểm bản nền bằng `kubectl -n <ns> get backup <tên> -o yaml` rồi chụp bản nền mới ngay; dòng "khoá ngoại ... có dòng không trỏ tới", "bảng ... còn ... dòng" hoặc "sổ cái: ..." là dữ liệu khôi phục không nhất quán, báo đội CSDL và đội ATTT (với sổ cái, đối chiếu bản neo ngoài theo DR-14).
- `RestoreCheckStale`: không có lần đạt trong 8 ngày cho một cụm, hoặc không có chỉ số nào của cell trong 2 ngày (CronJob bị treo, Pushgateway không tới được, `concurrencyPolicy: Forbid` chặn vì lần trước chưa xong).
- Chạy tay một lần: `kubectl -n data-pg-restore create job --from=cronjob/restore-check rc-tay-$(date +%s)`.

## 5. Khôi phục theo thời điểm (PITR)

Khôi phục luôn vào một cụm mới, không ghi đè cụm đang chạy. Ví dụ đưa `pg-core` về trước một lệnh xoá nhầm lúc 10:42 giờ Việt Nam:

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: { name: pg-core-pitr, namespace: data-pg }
spec:
  instances: 3
  imageName: <ảnh của data-pg>
  storage: { storageClass: nvme-local, size: 6Ti }
  bootstrap:
    recovery:
      source: nguon
      recoveryTarget: { targetTime: "2026-11-05 03:41:00+00" }
  externalClusters:
    - name: nguon
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters: { barmanObjectName: bk-sn-pg-core, serverName: pg-core }
  plugins:
    - name: barman-cloud.cloudnative-pg.io
      isWALArchiver: true
      parameters: { barmanObjectName: bk-sn-pg-core, serverName: pg-core-pitr }
```

`serverName` của cụm mới phải khác cụm cũ để WAL mới không ghi vào đường dẫn cũ. Sau khi đối chiếu dữ liệu, chuyển ứng dụng theo kịch bản DR tương ứng (DR-06) và ghi biên bản. Khôi phục từ bản cuối tháng, cuối năm: tạo `ObjectStore` riêng với `destinationPath: s3://bk-cell-<mã>-pg/dai-han/<thang|nam>`, cùng khoá và CA, rồi dùng `serverName` là tên cụm gốc; bản dài hạn chỉ có đoạn WAL của chính bản nền nên chỉ khôi phục được tới điểm nhất quán cuối bản nền (không PITR).

## 6. Giữ bản cuối tháng, cuối năm (`giu-dai-han`)

CronJob chạy 04:30 các ngày 28–31 và chỉ làm việc vào ngày cuối tháng: với mỗi cụm, lấy `Backup` đã `completed` trong 24 giờ qua, chép thư mục bản nền, các tệp WAL từ `beginWal` tới `endWal` (gồm tệp `.backup`) và tệp `.history` sang `dai-han/`, rồi đặt hạn giữ COMPLIANCE từng đối tượng. Chỉ số `backup_long_term_copy_success`. Khi hỏng (thường do bản nền đêm đó lỗi), chụp bù bản nền và chạy tay `kubectl -n data-pg-restore create job --from=cronjob/giu-dai-han gd-tay-$(date +%s)` ngay trong ngày cuối tháng; sang ngày mới thì đặt biến `NGAY` của job tạm về ngày cuối tháng.

## 7. Kiểm chứng định kỳ không được xoá

Mỗi quý, với khoá của cell (không phải khoá quản trị), thử xoá một phiên bản trong `bk-cell-<mã>-pg`: `aws s3api delete-object --bucket bk-cell-<mã>-pg --key <khoá> --version-id <phiên bản>` phải trả `AccessDenied`; `put-object-retention` rút ngắn hạn giữ phải bị từ chối; ghi kết quả vào hồ sơ OPS-18.

## 8. Trên kind

`deploy/environments/kind/values-kind-data-pg.yaml` (khối `backup`), `values-kind-backup-cell.yaml` và `values-kind-rgw-sao-luu.yaml` dùng container RGW zone `sn` của `kiem-thu-kho-doi-tuong.sh` (172.18.0.51, HTTP, không SSE) làm kho sao lưu; Secret `bk-sn-s3-pg` tạo tay từ khoá của người dùng `bk-cell-kind-pg`. Thứ tự: dựng RGW, cài plugin-barman-cloud, cài `rgw-cell` với hai tệp values kho sao lưu, cài `data-pg` kind, chờ bản nền đầu, chạy tay `restore-check`. Kiểm thử tĩnh chạy được không cần cụm: `deploy/charts/backup-cell/tests/chay-kiem-thu.sh` (thêm `PGHOST`, `PGPORT` để chạy truy vấn kiểm trên PostgreSQL thật).
