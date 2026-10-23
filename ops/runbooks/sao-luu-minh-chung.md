# Sổ tay vận hành: Sao lưu ngày và kiểm mẫu minh chứng, biểu mẫu ký

> **Mã truy vết:** INF-18 · TL-04 chương 8 "Ceph: multisite RGW và Object Lock", "Bảng lịch sao lưu" (dòng Minh chứng, biểu mẫu ký) · SEC-42, SEC-44 · SVC-08 bảng `evidence.object_version`.
> **Thành phần:** CronJob `sao-luu-minh-chung`, `kiem-mau-minh-chung` (namespace `sao-luu`, chart `deploy/charts/backup-cell`); tài khoản RGW chỉ đọc `sao-luu-minh-chung` (chart `rgw-cell`); bucket `bk-cell-<mã>-ev` trên kho sao lưu `ceph-sn-bk`, Object Lock COMPLIANCE 10 năm; cảnh báo `EvidenceBackupSyncStale`, `EvidenceBackupSampleMismatch`, `EvidenceBackupSampleStale`.

## 1. Hai lớp bảo vệ

Multisite RGW (sổ tay `ceph-multisite.md`) giữ bản thứ hai ở phía Nam với độ trễ vài phút, nhưng nó cùng miền quản trị với bản chính: một tài khoản quản trị bị chiếm có thể làm hỏng cả hai. Vì vậy mỗi đêm (00:30) bucket `ev-cell-<mã>` (minh chứng) và `ds-cell-<mã>` (biểu mẫu đã ký) còn được chép sang kho sao lưu riêng bằng `rclone sync --backup-dir`. Job đọc nguồn bằng tài khoản `sao-luu-minh-chung` chỉ có quyền đọc và liệt kê tiền tố `t/`, ghi đích bằng khoá `bk-cell-<mã>-ev` không có quyền xoá. Bố cục đích:

| Đường dẫn | Nội dung |
|---|---|
| `hien-tai/<bucket nguồn>/t/<tenant>/sha256/<ab>/<băm>` | Bản gương của nguồn, cùng khoá theo nội dung |
| `thay-the/<ngày>/<bucket nguồn>/…` | Bản mà rclone phải dời đi vì nguồn đã đổi hoặc mất (`--backup-dir`) |

Khoá nguồn là băm nội dung và bản chính có Object Lock, nên trong vận hành bình thường không có tệp nào bị đổi hay mất ở nguồn. `--immutable` làm job lỗi ngay khi một tệp đã sao lưu lại đổi nội dung; tệp mất ở nguồn làm bước dời sang `thay-the/` lỗi vì khoá không có quyền xoá, nên bản sao ở `hien-tai/` vẫn còn nguyên. Cả hai trường hợp đều là dấu hiệu sự cố của kho chính.

Biểu mẫu ký giữ 20 năm ở bản chính nhưng bản sao giữ theo hạn 10 năm của bảng lịch sao lưu (lệch ghi ở wiki 14).

## 2. Xử lý `EvidenceBackupSyncStale`

1. Đọc nhật ký: `kubectl -n sao-luu logs job/<job sao-luu-minh-chung mới nhất>`. Dòng `ERROR … immutable` nêu đúng khoá đã đổi nội dung ở nguồn; dòng `AccessDenied` khi `DeleteObject` nêu khoá đã mất ở nguồn.
2. Tệp đổi hoặc mất ở nguồn: báo SOC, đối chiếu với `evidence.object_version` (băm, kích thước) và xử lý theo `doi-soat-minh-chung-ceph.md` mục 2; bản đúng nằm ở `hien-tai/` của kho sao lưu. Không xoá tay bản sao để job chạy qua.
3. Lỗi kết nối hoặc TLS: kiểm tra NetworkPolicy `sao-luu-minh-chung`, Secret `rgw-sao-luu-minh-chung` (ESO từ `kv/ceph/cell-<mã>/sao-luu-minh-chung`), `bk-sn-s3-ev` (`kv/cell-<mã>/backup/ev`).
4. Chạy lại: `kubectl -n sao-luu create job --from=cronjob/sao-luu-minh-chung sao-luu-minh-chung-tay`.

## 3. Kiểm mẫu hằng ngày và `EvidenceBackupSampleMismatch`

06:00 hằng ngày, `kiem-mau-minh-chung` chia 1.000 mẫu cho các tenant trong `minhChung.kiemMau.tenants`, chọn phiên bản tệp tạo trước ngày kiểm theo thứ tự băm của ngày (mỗi ngày một mẫu khác, cùng ngày thì như nhau), đọc bằng vai trò chỉ đọc `ro_evidence` qua Pooler `pg-platform-pooler-ro`, tải đúng bản sao ở `hien-tai/`, tính SHA-256 và kích thước rồi so với giá trị SVC-08 đã ghi lúc nhận tệp. Bảng lịch sao lưu của TL-04 gọi bảng này là `evidence_version`; bảng mang băm của tệp vật lý là `evidence.object_version` của SVC-08 (bảng `product.evidence_version` của mission-core chỉ là bản chiếu).

Khi cảnh báo báo thiếu hoặc lệch, nhật ký có dòng `THIẾU:` hoặc `LỆCH:` với bucket, khoá, tenant. Bản sao bị lệch trên một kho có Object Lock là sự cố nghiêm trọng (can thiệp trái phép hoặc hỏng đĩa): báo SOC, so bản chính bằng `doi-soat-minh-chung-ceph.md`, rồi chép lại bản đúng vào `hien-tai/` bằng tài khoản quản trị kho sao lưu sau khi có biên bản. `EvidenceBackupSampleStale` dạng `absent` nghĩa là chưa khai danh sách tenant (job chưa được dựng) hoặc job không đẩy được chỉ số.

## 4. Khôi phục từ bản sao

Tệp cần khôi phục được chép từ `s3://bk-cell-<mã>-ev/hien-tai/<bucket>/<khoá>` về bucket chính bằng tài khoản quản trị kho theo quy trình bốn mắt; khoá giữ nguyên nên không phải sửa CSDL. Sau khi chép, chạy đối soát quý của `doi-soat-minh-chung-ceph.md` cho tenant liên quan để xác nhận.
