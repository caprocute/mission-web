# Sổ tay vận hành: Gương kho Git và nhân bản Harbor sang phía Nam

> **Mã truy vết:** INF-18 · TL-04 "Bảng lịch sao lưu" (dòng Kho Git, Harbor), mục 12 (gói phát hành, Harbor) · SEC-55, OPS-01.
> **Thành phần:** CronJob `sao-luu-git-guong`, `sao-luu-git-bundle`, `kiem-keo-harbor` (namespace `sao-luu`, chart `backup-cell`); `deploy/clusters/mgmt/harbor/cau-hinh.yaml` (quy tắc `nhan-ban-sang-sn`), `cau-hinh-sn.yaml` (project và robot của `harbor-sn`); bucket `bk-cell-<mã>-git` (Object Lock 1 năm); cảnh báo `GitBackupStale`, `GitCloneCheckStale`, `HarborPullCheckFailed`, `HarborPullCheckStale`.

## 1. Kho Git

Khối `git` của backup-cell chỉ bật ở bản phát hành đặt cạnh máy chủ Git (cụm quản trị, suy luận). Mỗi 15 phút, `sao-luu-git-guong` clone `--mirror` từng kho trong `git.kho` ở `git.vt.local` rồi `push --mirror` sang `git-sn.vt.local`; đây là cách thay cho đồng bộ liên tục khi máy chủ Git không tự đẩy gương. 03:15 hằng ngày, `sao-luu-git-bundle` tạo `git bundle --all`, kiểm bằng `bundle verify`, đẩy lên `s3://bk-cell-<mã>-git/bundle/<kho>/<ngày>.bundle`. Chủ nhật, job còn clone thử từ bundle và từ gương phía Nam, so toàn bộ ref với kho gốc. Token chỉ đọc kho gốc và token đẩy gương nằm ở OpenBao (`git.token`, `git.tokenGuong`), đưa cho git qua credential helper đọc tệp, không nằm trên dòng lệnh.

Khi `GitBackupStale` hoặc `GitCloneCheckStale` báo, xem nhật ký job. "clone thử từ gương … không khớp" nghĩa là gương tụt sau kho gốc hoặc có người đẩy thẳng vào gương; gương chỉ được ghi bằng job này. Khôi phục kho: `git clone --mirror <bundle>` rồi đẩy lên máy chủ Git mới, hoặc trỏ Argo CD sang `git-sn.vt.local` khi chuyển vùng.

## 2. Harbor

Quy tắc `nhan-ban-sang-sn` (kích hoạt theo sự kiện đẩy ảnh) chép mọi ảnh, chữ ký và attestation của project `okr`, `platform`, `k8s` sang `harbor-sn`. Thứ tự áp: chạy `ap-dung-cau-hinh.sh` với `CAU_HINH=cau-hinh-sn.yaml`, `HARBOR_URL=https://harbor-sn.vt.local`, `BAO_ROBOT=kv/mgmt/harbor-sn/robots` để tạo project và robot `replicate-sn`, `pull-cell-<mã>`, `pull-mgmt` trên harbor-sn; sau đó chạy với `cau-hinh.yaml` trên harbor-hl để tạo đích `harbor-sn` và bật quy tắc. Ảnh proxy-cache (`dockerhub`, `quay`…) không được nhân bản; khi chuyển vùng, harbor-sn kéo lại ảnh bên thứ ba qua cache riêng (suy luận).

07:30 hằng ngày, `kiem-keo-harbor` ở mỗi cell lấy digest của các container đang chạy thuộc ba project trên, chọn tối đa 50 digest, kéo manifest từ harbor-sn theo đúng digest bằng robot `pull-cell-<mã>` và kiểm SHA-256 của nội dung nhận được. `HarborPullCheckFailed` nghĩa là có ảnh đang chạy mà phía Nam không có: xem `GET /api/v2.0/replication/executions?policy_id=<id>` trên harbor-hl, chạy lại lần nhân bản lỗi, rồi chạy tay job kiểm.
