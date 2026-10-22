# Sổ tay vận hành kho đối tượng Ceph RGW của cell

> Nguồn: ADR-13; TL-04 mục 3.8, mục 8.4 "Ceph: multisite RGW và Object Lock"; TL-03 "Kho đối tượng Ceph", "Lưu giữ, lưu trữ và xoá dữ liệu"; TL-05 "Kho đối tượng cho minh chứng"; SEC-44; INF-12.
> Chart `deploy/charts/rgw-cell`, script `deploy/charts/rgw-cell/scripts/khoi-tao-multisite.sh`.

Sổ tay này dành cho SRE lưu trữ trực cell. Mỗi cell có một realm RGW riêng (`cell-<mã>`) với zonegroup `cell-<mã>-zg` và hai zone: `cell-<mã>-hl` là zone master ở Hoà Lạc, `cell-<mã>-sn` ở phía Nam. Hai zone đồng bộ bất đồng bộ cả siêu dữ liệu (người dùng, bucket, chính sách) và đối tượng. Realm theo cell để chuyển vùng từng cell độc lập.

## Khởi tạo kho của một cell mới

Bước khởi tạo làm một lần cho mỗi cell, trên máy quản trị Ceph có `radosgw-admin` (với cephadm: `RGW_ADMIN="cephadm shell -- radosgw-admin"`) và đã đăng nhập OpenBao (`bao login`) với quyền ghi `kv/ceph/cell-<mã>/*`.

1. Tạo trước pool dữ liệu EC 4+2 cho lớp lưu trữ HDD (`POOL_HDD`, mặc định `cell-<mã>.rgw.hdd-ec.data`) trên cả hai cụm Ceph. Ở hồ sơ compact và S chạy Rook-Ceph, lớp HDD là pool bản sao 3 (TL-05).
2. Phía Hoà Lạc: `CELL=<mã> VAI=hl deploy/charts/rgw-cell/scripts/khoi-tao-multisite.sh`. Script tạo realm, zonegroup, zone master, người dùng hệ thống đồng bộ, lớp lưu trữ `HDD_EC`, tài khoản quản trị kho `kho-quan-tri-cell-<mã>` và người dùng S3 của service (`evidence-service`, `document-service`, `ledger-service`, `tenant-registry`), rồi commit period. Khoá truy cập được đọc từ OpenBao; khoá chưa có thì script sinh ngẫu nhiên và ghi vào OpenBao, không in ra màn hình. Người dùng service có `max_buckets -1`, tức không tự tạo được bucket.
3. Khởi động lại RGW phía Hoà Lạc để phục vụ zone mới: `ceph orch redeploy rgw.<dịch vụ>` (cephadm) hoặc đặt `zone` trong CephObjectStore (Rook).
4. Phía Nam: `CELL=<mã> VAI=sn …/khoi-tao-multisite.sh`, rồi khởi động lại RGW phía Nam.
5. Kiểm tra: `radosgw-admin sync status --rgw-zone=cell-<mã>-sn` phải báo "metadata is caught up with master" và "data is caught up with source".
6. Cài chart `rgw-cell` (Argo CD, sau ESO). Job `rgw-bucket-<băm>` trong `secure-data` tạo sáu bucket, bật versioning và Object Lock, đặt vòng đời và chính sách bucket. ESO tạo Secret `rgw-<service>` trong namespace của từng service.

Script và Job đều chạy lại được nhiều lần cho cùng kết quả. Thêm người dùng service mới: thêm uid vào `NGUOI_DUNG`, chạy lại bước 2, rồi khai quyền ở `nguoiDung` của values.

## Bucket và chế độ khoá

| Bucket | Tên trong cell | Object Lock | Ghi chú |
|---|---|---|---|
| `evidence` | `ev-cell-<mã>` | COMPLIANCE 10 năm (lớp `THUC_HIEN`) | SVC-08 đặt hạn dài hơn theo đối tượng khi cần; chuyển lớp HDD sau 30 ngày |
| `dossier` | `ds-cell-<mã>` | COMPLIANCE 20 năm | |
| `templates` | `tpl-cell-<mã>` | không | document-service chỉ đọc |
| `ledger-archive` | `la-cell-<mã>` | COMPLIANCE 20 năm | |
| `uploads-tmp` | `tmp-cell-<mã>` | không, không versioning | hết hạn sau 1 ngày; nơi duy nhất service được xoá |
| `transfer` | `tr-cell-<mã>` | GOVERNANCE 1 năm | gói chuyển liên cell (DV-03-07) |

Chế độ COMPLIANCE không cho bất kỳ ai, kể cả tài khoản quản trị kho, xoá phiên bản hay giảm hạn giữ trước hạn. Object Lock chỉ bật được lúc tạo bucket: nếu Job báo "bucket đã có nhưng không bật Object Lock", phải tạo bucket mới và chép dữ liệu, không có lệnh sửa tại chỗ. Đổi hạn giữ mặc định của bucket trong values chỉ áp cho đối tượng ghi sau đó.

Yêu cầu xoá dữ liệu cá nhân (Nghị định 13/2023/NĐ-CP) đối với minh chứng đã khoá được xử lý bằng xoá mật mã: xoá DEK của đối tượng trong OpenBao, không xoá đối tượng. Giữ pháp lý (`legal_hold` trên `evidence.object_meta`) do evidence-service đặt bằng `PutObjectLegalHold`.

## Cảnh báo

**CephSyncBehind**. Có shard đồng bộ tụt lại và đối tượng cũ nhất chưa đồng bộ quá 10 phút. Chạy `radosgw-admin sync status --rgw-zone=cell-<mã>-sn` và `radosgw-admin sync error list`. Nguyên nhân thường gặp: đợt tải lên dồn cuối kỳ (chờ, theo dõi xu hướng giảm), đường mạng giữa hai TTDL, RGW phía Nam quá tải hoặc người dùng đồng bộ `sync-cell-<mã>` bị đổi khoá (khoá trong zone phải khớp OpenBao; chạy lại bước 2 và 4). Trong lúc đồng bộ chậm, RPO của minh chứng khi chuyển vùng bằng độ trễ hiện tại.

**CephSyncDung**. Không còn số liệu đồng bộ: kiểm tra mô-đun prometheus của ceph-mgr và việc scrape từ namespace `observability`.

## Chuyển vùng (failover) kho đối tượng

Khi TTDL Hoà Lạc mất, theo kịch bản DR của cell: trên phía Nam chạy `radosgw-admin zone modify --rgw-zone=cell-<mã>-sn --master --default` rồi `radosgw-admin period update --commit`, khởi động lại RGW phía Nam và đổi DNS `s3.cell-<mã>` (GeoDNS, INF-20). Khi Hoà Lạc trở lại, kéo realm về, chờ đồng bộ bắt kịp rồi mới chuyển master trở lại. Kho sao lưu phía Nam là cụm riêng (INF-17, INF-18), không phải zone thứ hai này.

## Kiểm thử

- Tĩnh: `deploy/charts/rgw-cell/tests/chay-kiem-thu.sh`.
- Trên kind với hai cụm Ceph chạy bằng container: `deploy/environments/kind/kiem-thu-kho-doi-tuong.sh`.
