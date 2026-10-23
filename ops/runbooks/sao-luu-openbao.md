# Sổ tay vận hành: Snapshot Raft của OpenBao

> **Mã truy vết:** INF-18 · TL-04 chương 8 "OpenBao, HSM và khoá", "Bảng lịch sao lưu" (dòng OpenBao) · SEC-10, SEC-12.
> **Thành phần:** CronJob `openbao-sao-luu-raft` (namespace `secure-data`, template `sao-luu-raft.yaml` của chart `vt-platform/ha-tang/openbao`); chính sách `deploy/openbao/chinh-sach/sao-luu-raft.hcl`, vai trò auth kubernetes `sao-luu-raft` (`deploy/openbao/cau-hinh.sh`); bucket `bk-cell-<mã>-bao`, Object Lock COMPLIANCE 90 ngày; cảnh báo `OpenBaoSnapshotStale`.

## 1. Cách chạy

Phút 07 mỗi giờ, container `chup` (ảnh OpenBao) đăng nhập bằng token ServiceAccount `sao-luu-raft` và chạy `bao operator raft snapshot save`; chính sách chỉ cho đọc `sys/storage/raft/snapshot`, không khôi phục được. Container `tai-len` (ảnh backup-tools) kiểm tệp là gzip hợp lệ rồi đẩy lên `s3://bk-cell-<mã>-bao/raft/<năm>/<tháng>/<YYYYMMDDTHHMMZ>.snap` bằng khoá không có quyền xoá. Nội dung snapshot đã được mã hoá bằng khoá của chính OpenBao, khoá gốc nằm trong HSM, nên snapshot chỉ khôi phục được khi có HSM chứa khoá mở tương ứng.

## 2. Xử lý `OpenBaoSnapshotStale`

Cảnh báo báo khi 2 giờ không có bản thành công. Xem `kubectl -n secure-data logs job/<job> -c chup` và `-c tai-len`:

- `permission denied` ở bước chụp: vai trò hoặc chính sách chưa được nạp, chạy lại `deploy/openbao/cau-hinh.sh`.
- "snapshot Raft rỗng hoặc hỏng": cụm Raft không có leader hoặc yêu cầu bị ngắt; xem `bao operator raft list-peers` và sổ tay `openbao-van-hanh.md`.
- Lỗi S3: Secret `bk-sn-s3-bao` (`kv/cell-<mã>/backup/bao`), CA `vt-noi-bo-ca`, `saoLuuRaft.khoSaoLuu.egressTo`.

## 3. Khôi phục (DR, quy trình bốn mắt)

Khôi phục thử hằng quý vào một cụm OpenBao cô lập có HSM chứa cùng khoá mở (ghép miền bảo mật của nhà sản xuất): tải bản cần dùng, dựng cụm mới cùng cấu hình seal, `bao operator raft snapshot restore -force <tệp>` bằng token quản trị lấy theo nghi thức khoá (`openbao-hsm.md`), rồi kiểm tra KV, Transit và PKI. Không khôi phục vào cụm đang phục vụ khi chưa có quyết định của người phụ trách, vì thao tác này ghi đè mọi thay đổi sau thời điểm snapshot.
