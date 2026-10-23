# Sổ tay vận hành audit-archiver (kho bất biến nhật ký kiểm toán)

> Nguồn: TL-04 "Kafka: MirrorMaker 2" (đoạn `audit-archiver`), "Bảng lịch sao lưu" (dòng `sys.audit.v1`), "Mục tiêu và nguyên tắc 3-2-1-1-0", SOC và SIEM; TL-01 "Tách nhật ký kiểm toán"; NFR-09; G6; SEC-43; INF-24.
> Mã nguồn `services-go/audit-archiver`, chart `deploy/charts/audit-archiver`.

Sổ tay này dành cho SRE trực cell và đội an ninh (SOC). `audit-archiver` là đường đi riêng của topic `sys.audit.v1`, không qua MirrorMaker 2: dịch vụ đọc topic, gom từng lô tối đa 4 phút, ghi ra tệp Parquet nén zstd giữ nguyên thân Avro và header phong bì, ký kê khai của lô bằng khoá Transit trong OpenBao, rồi tải lên bucket Object Lock COMPLIANCE 10 năm của kho sao lưu phía Nam. Nhờ vậy nhật ký kiểm toán có bản sao bất biến không quá 5 phút sau khi phát, kể cả khi Kafka phía Nam gặp sự cố.

## Dữ liệu trong kho

Mỗi lô của một phân vùng Kafka gồm ba đối tượng cùng tên gốc `cell-<mã>/<topic>/<yyyy>/<MM>/<dd>/<HH>/<phân vùng>-<offset bắt đầu>` (giờ UTC của bản ghi đầu lô, số đệm 0):

| Đuôi | Nội dung |
|---|---|
| `.json` | Kê khai: dải offset `[tuOffset, denOffset)`, offset đầu cuối, số bản ghi, thời điểm bản ghi đầu cuối, SHA-256 và kích thước tệp dữ liệu, khoá và SHA-256 của kê khai trước cùng phân vùng (chuỗi băm), tên khoá ký, hạn giữ |
| `.sig` | Chữ ký ed25519 của đúng các byte kê khai, dạng `vault:v<phiên bản>:<base64>` |
| `.parquet` | Bản ghi Kafka: topic, phân vùng, offset, thời điểm, khoá, thân Avro nguyên vẹn, header |

Thứ tự ghi luôn là kê khai, chữ ký rồi dữ liệu, và offset Kafka chỉ được xác nhận sau khi cả ba đã ghi và tệp dữ liệu đã khoá. Offset xác nhận mang metadata là khoá kê khai cuối, nên khi khởi động lại dịch vụ tiếp tục từ kê khai cuối trong kho chứ không theo offset Kafka: không mất và không trùng. Kê khai có mà thiếu dữ liệu (sự cố giữa hai lần ghi) thì dịch vụ đọc lại đúng dải offset trong Kafka và chỉ tải lên khi SHA-256 khớp kê khai đã ký.

Hạn giữ 10 năm theo TL-04 là tham số `archive.retentionYears`. NFR-09 ghi "≥ 10 năm, cần đối chiếu quy định lưu trữ hồ sơ cán bộ" còn TL-03 giữ hồ sơ cán bộ 20 năm; câu hỏi kéo dài lên 20 năm đang chờ chủ đầu tư ở `wiki/14`. Tăng hạn chỉ áp cho lô ghi sau khi đổi; lô cũ muốn kéo dài thì quản trị kho đặt lại `PutObjectRetention` với hạn dài hơn (COMPLIANCE cho phép kéo dài, không cho rút ngắn).

## Chuẩn bị trước khi cài

Mọi bước dưới đây làm một lần cho mỗi cell. Khoá truy cập không bao giờ in ra màn hình hay ghi vào tệp; chỉ đi qua OpenBao.

1. **Bucket trên kho sao lưu** (quản trị kho sao lưu, INF-17): tạo bucket `audit-cell-<mã>` bật Object Lock (`--object-lock-enabled-for-bucket`), đặt mặc định COMPLIANCE 10 năm để đối tượng nào lọt khỏi hạn riêng vẫn bị khoá:
   `aws s3api put-object-lock-configuration --bucket audit-cell-<mã> --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Years":10}}}'`
2. **Người dùng S3 `audit-archiver`** trên kho sao lưu, khoá lưu vào `kv/ceph/backup/audit-archiver` (trường `access-key`, `secret-key`). Chính sách bucket chỉ cho các quyền sau; không có `s3:DeleteObject*`, `s3:BypassGovernanceRetention`, `s3:PutBucketObjectLockConfiguration`:
   ```json
   {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["arn:aws:iam:::user/audit-archiver"]},
     "Action":["s3:PutObject","s3:PutObjectRetention","s3:GetObject","s3:GetObjectRetention","s3:ListBucket","s3:GetBucketObjectLockConfiguration"],
     "Resource":["arn:aws:s3:::audit-cell-<mã>","arn:aws:s3:::audit-cell-<mã>/*"]}]}
   ```
   Kiểm toán viên dùng một người dùng riêng chỉ có `s3:GetObject`, `s3:ListBucket`.
3. **Khoá ký trong OpenBao** (INF-13): `bao write -f transit/keys/audit-archiver type=ed25519 exportable=false allow_plaintext_backup=false`. Chính sách `audit-archiver` chỉ có `update` trên `transit/sign/audit-archiver` và `read` trên `transit/keys/audit-archiver`; vai trò Kubernetes `audit-archiver` gắn tài khoản dịch vụ `secure/audit-archiver`, audience `openbao`.
4. **Kafka** (INF-10): KafkaUser `audit-archiver` có ACL Read và Describe trên `sys.audit.v1`, Read trên nhóm `audit-archiver`; Secret `kafka-audit-archiver-tls` được sao chép sang namespace `secure`.
5. **Khoá công khai cho kiểm toán viên**: sau khi cài, chạy `kubectl -n secure exec deploy/audit-archiver -- /audit-archiver khoa-cong-khai > audit-archiver.pub.json` và giao tệp cho bộ phận kiểm toán qua kênh có ký nhận. Mỗi lần xoay khoá Transit, xuất lại tệp (tệp giữ mọi phiên bản khoá).

## Cài và kiểm chứng

1. Cài chart qua Argo CD với `global.cell=<mã>` và `archive.s3Endpoint` của kho sao lưu. Kiểm: `kubectl -n secure get pods -l app.kubernetes.io/name=audit-archiver` có hai pod `Ready`; nhật ký có dòng "nhận phân vùng" cho mỗi phân vùng.
2. Sau 5 phút, chỉ số `audit_archiver_do_tre_lo_cuoi_giay` của mọi phân vùng phải nhỏ hơn 300 và `audit_archiver_tep_luu_total` tăng.
3. Chạy thử job kiểm: `kubectl -n secure create job --from=cronjob/audit-archiver-kiem-tra kiem-tra-thu`; job phải kết thúc thành công, nhật ký có "kiểm kho lưu trữ đạt".
4. Từ máy kiểm toán, `audit-verify -endpoint … -bucket audit-cell-<mã> -cell cell-<mã> -khoa-cong-khai audit-archiver.pub.json -tu <hôm qua>` phải in "đạt".
5. Thử bất biến bằng tài khoản quản trị kho: `aws s3api delete-object --bucket audit-cell-<mã> --key <một tệp .parquet> --version-id <id>` phải bị từ chối `AccessDenied`.

Quay lui: `audit-archiver` không sửa dữ liệu nào ngoài kho bất biến, nên quay lui chỉ là đưa chart về phiên bản trước qua Argo CD. Phiên bản cũ đọc được kho do phiên bản mới ghi khi `phienBan` của kê khai không đổi; phiên bản mới tăng `phienBan` thì không quay lui mà sửa tiến. Gỡ hẳn dịch vụ không xoá được dữ liệu đã ghi; trong lúc gỡ, nhật ký kiểm toán vẫn nằm trên Kafka theo `retention.ms` của topic và được lưu tiếp khi cài lại (dịch vụ tiếp tục từ kê khai cuối).

## Xử lý cảnh báo

### Chậm quá 5 phút

`AuditArchiverChamQua5Phut`: bản ghi cũ nhất chưa vào kho đã quá 5 phút. Xem nhật ký pod tìm `giai_doan` lỗi. `ghi_lo` là lỗi ghi kho hoặc ký: kiểm kết nối tới kho sao lưu qua egress gateway, hạn mức bucket, OpenBao (`bao status`, chính sách `audit-archiver`). `doc_kafka` là lỗi đọc Kafka. Dịch vụ tự thử lại mỗi giây với cùng lô đã ký, nên khi nguyên nhân hết thì độ trễ tự giảm; không cần khởi động lại. Nếu kho sao lưu phía Nam mất hẳn, ghi sự cố an ninh: nhật ký kiểm toán vẫn còn trên Kafka và OpenSearch (TL-01) nhưng chưa có bản bất biến.

### Phân vùng dừng

`AuditArchiverPhanVungDung`: dịch vụ dừng một phân vùng vì chuỗi kê khai đứt, kê khai đã xác nhận không đọc được, một khoá đối tượng đã có nội dung khác, hoặc Kafka đã xoá offset chưa lưu. Đây là sự cố toàn vẹn, chuyển SOC ngay; không khởi động lại để "chữa". Lấy lý do trong nhật ký (`khoi_phuc`, `ghi_lo`), chạy `audit-verify` cho phân vùng đó để xác định mắt xích hỏng. Không bao giờ sửa offset nhóm bằng tay để vượt qua lỗi: làm vậy tạo lỗ hổng trong chuỗi nhật ký kiểm toán. Khi SOC kết luận và có quyết định bằng văn bản, mới bắt đầu một chuỗi mới bằng cách đặt offset nhóm (kèm metadata rỗng) và ghi biên bản vào hồ sơ sự cố.

### Lỗi ghi kho

`AuditArchiverGhiKhoLoi`: lỗi lặp lại ở một giai đoạn. Cách xử lý như mục chậm quá 5 phút; nếu lỗi là `xac_nhan_offset` thì kho vẫn đủ, chỉ xác nhận Kafka chậm.

### Không có pod

`AuditArchiverKhongCoPod`: kiểm Deployment, node pool `secure`, NetworkPolicy. Trong lúc không có pod, dữ liệu chờ trên Kafka; dịch vụ lưu bù khi chạy lại.

### Kiểm tra không đạt

`AuditArchiverKiemTraKhongDat` hoặc `AuditArchiverKiemTraJobLoi`: job hằng ngày phát hiện chữ ký sai, chuỗi đứt, tệp bị sửa, số bản ghi lệch hoặc offset Kafka vượt kho. Chuyển SOC; lấy báo cáo JSON trong nhật ký job (`ket_qua`), chạy lại `audit-verify` toàn bộ (`-tu` bỏ trống) từ máy kiểm toán độc lập để đối chứng. Bản ghi cảnh báo "kho đi trước offset đã xác nhận" là bình thường sau một sự cố và không làm job lỗi.

### Kiểm tra ngừng chạy

`AuditArchiverKiemTraNgungChay`: CronJob không chạy xong trong 26 giờ. Kiểm lịch, Pushgateway, quyền đọc OpenBao (khoá công khai) và Kafka của job, chạy tay như bước 3 phần cài đặt.

## Dành cho kiểm toán viên

`audit-verify` là công cụ độc lập, chỉ cần tệp khoá công khai và tài khoản S3 chỉ đọc, không cần truy cập OpenBao hay Kafka. Công cụ kiểm chữ ký từng kê khai, chuỗi băm giữa các kê khai, dải offset liên tục, SHA-256 và số bản ghi của từng tệp Parquet, in báo cáo JSON và thoát mã 0 khi đạt, 1 khi có sai lệch. Kiểm toàn bộ chuỗi một topic: bỏ trống `-tu`; kiểm một khoảng thời gian: `-tu` và `-den` theo RFC 3339. Đọc tệp Parquet bằng công cụ bất kỳ (DuckDB, Spark): cột `value` là thân Avro theo lược đồ `sys.audit.v1` trong registry.
