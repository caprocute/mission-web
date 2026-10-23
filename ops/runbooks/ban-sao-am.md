# Sổ tay vận hành: Bản sao ấm của cell ở TTDL thứ hai

> **Mã truy vết:** INF-28 · NFR-05 (RPO ≤ 15 phút, RTO ≤ 4 giờ khi mất TTDL) · TL-04 "Topology hai trung tâm dữ liệu", "PostgreSQL: CloudNativePG và Barman Cloud", "Kafka: MirrorMaker 2", "Ceph: multisite RGW", "Kịch bản DR" · DR-10, DR-16, OPS-18.
> **Thành phần:** khối `replica` của chart `data-pg` (CNPG distributed topology), `KafkaMirrorMaker2` của chart `kafka`, chart `deploy/charts/standby-check` (CronJob `kiem-ban-sao-am`, cảnh báo), `deploy/cells/<cell>/values-hl.yaml` và `values-sn.yaml`, cụm mẫu `deploy/clusters/cell-g4-sn`. Diễn tập chuyển vùng và quay về thuộc QA-15.

## 1. Bản sao ấm gồm những gì

TTDL nào là primary của cell được khai ở `deploy/cells/<cell>/cell.yaml` (`primarySite`, mặc định `hl`). ApplicationSet suy ra `global.siteRole` (primary hoặc standby) cho từng TTDL, và mọi khác biệt giữa hai TTDL đều suy ra từ vai trò đó:

| Thành phần | TTDL primary | TTDL chờ |
|---|---|---|
| Sáu cụm CNPG | `replica.self = primary = pg-<cụm>-hl`, khởi tạo bằng initdb, có CR Database và Job cấp quyền | `replica.self = pg-<cụm>-sn`, nhận WAL từ primary (dựng bằng `pg_basebackup`), không CR Database, không Job cấp quyền; cùng kích thước |
| Kafka | Kafka Connect (Debezium) chạy, tài khoản `mm2-hl-user` chỉ đọc, listener liên TTDL 9094 | `KafkaMirrorMaker2` `mm2-hl-to-sn` (IdentityReplicationPolicy, bỏ `retry`, `replay`, đồng bộ offset mỗi 30 s), không Debezium |
| RGW | zone master | zone thứ hai của multisite (INF-12), đồng bộ bucket và Object Lock |
| Ứng dụng | theo hồ sơ định cỡ | mỗi workload 1 replica (min HPA, KEDA), max giữ nguyên; AI tắt |

## 2. Kiểm tra hằng ngày và cảnh báo

CronJob `kiem-ban-sao-am` (namespace `observability` của cụm phía Nam, 06:30) hỏi Prometheus: mỗi cụm CNPG đang phục hồi và trễ WAL dưới 60 s, MirrorMaker 2 có thông điệp mới trong 24 giờ và trễ dưới 60 s, RGW không tụt shard. Thiếu số liệu là đỏ. Kết quả từng mục ở nhật ký Job (mỗi dòng một JSON):

```bash
kubectl -n observability create job --from=cronjob/kiem-ban-sao-am kiem-tay-$(date +%s)
kubectl -n observability logs job/kiem-tay-<số> | tail -1     # {"sanSang": true, "khongDat": [], ...}
```

Cảnh báo: `PgReplicationLag*` (5 và 10 phút, observability-cell), `Mm2ReplicationLagWarning` và `Mm2ReplicationLagCritical` (5 và 10 phút), `Mm2Absent`, `CephSyncBehind`, `CephSyncDung` (rgw-cell), `StandbyCheckFailed` (không có lần kiểm thành công trong 26 giờ; chỉ số `vt:ban_sao_am_kiem_tra_dat` cho OPS-18). Dashboard "Bản sao ấm": `deploy/charts/standby-check/files/dashboard.json`.

## 3. Xử lý khi không đạt

1. **Trễ WAL hoặc replica không phục hồi:** `kubectl -n data-pg get cluster pg-core -o jsonpath='{.status.conditions}'` ở cụm phía Nam; kiểm luồng 5432 tới `pg-core-rw.data-pg.cell-<cell>-hl.vt.local` (NetworkPolicy `pg-instance`, `replica.peerCIDRs`), chứng thư `pg-core-replication` và CA `pg-core-ca-hl` còn hạn. Khi có kho Barman (INF-17, `replica.walArchive.enabled`), replica tự đọc WAL từ kho trong lúc luồng trực tiếp đứt.
2. **MirrorMaker 2 không có thông điệp mới:** `kubectl -n data-kafka get kafkamirrormaker2 mm2-hl-to-sn -o yaml` (trạng thái connector); kiểm listener `lienttdl` 9094 phía Hoà Lạc, Secret `mm2-hl-user` (chứng thư nguồn chép qua OpenBao `kv/cell-<mã>/kafka/mm2-nguon`) và `kafka-cell-cluster-ca-cert-hl` ở phía Nam. Sự kiện chưa sang được phát lại từ outbox (sổ tay `phat-lai-outbox.md`).
3. **RGW tụt shard:** sổ tay `ceph-multisite.md`.

## 4. Đổi vai trò (chuyển vùng có kế hoạch, quay về)

Chuyển vùng là một commit có chữ ký đổi `primarySite` trong `deploy/cells/<cell>/cell.yaml`, kèm nâng lớp ứng dụng ở tệp values của TTDL mới (bỏ các khoá `minReplicas: 1` trong `values-sn.yaml`). Argo CD đổi `replica.primary` ở cả hai phía (CNPG hạ cấp cụm cũ, ghi WAL cuối, promote cụm mới), đảo chiều MirrorMaker 2 và chuyển Debezium sang TTDL mới. Sau đó đổi `active_dc` của cell trong SVC-03 để `dns-sync` trỏ điểm vào cell (sổ tay `geodns.md`). Quay lui: hoàn nguyên commit. Trình tự đầy đủ, kiểm chứng và thời gian mục tiêu là phần của QA-15 (DR-10, DR-16).

## 5. Kiểm thử

`deploy/charts/standby-check/tests/chay-kiem-thu.sh`: lint, kubeconform, kube-linter chart `standby-check`, kiểm thử đơn vị `kiem_tra.py` (xanh, đỏ khi trễ WAL, khi ngắt nhân bản, khi thiếu số liệu, khi Prometheus lỗi), render `vt-platform` ở hai TTDL cho 9 cell với các bất biến của mục 1, kubeconform hai hồ sơ đại diện, cụm `cell-g4-sn`. Chưa kiểm trên hai cụm thật hay hai cụm kind (độ trễ WAL đo được, MirrorMaker 2 sao topic thử): để QA-15 hoặc khi có TTDL thứ hai.
