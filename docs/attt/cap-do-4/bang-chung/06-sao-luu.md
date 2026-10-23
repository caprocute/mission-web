> Nguồn: TL-04 chương 8 (sao lưu, DR), mục 8.1 (SEC-44), bảng OPS (OPS-18); task INF-12, INF-17, INF-18, QA-14, QA-15. Ngày đánh giá: 23/10/2026.

# Bằng chứng 06: Sao lưu 3-2-1-1-0 bất biến (SEC-44)

## Yêu cầu

Dữ liệu có ít nhất 3 bản, trên 2 loại phương tiện, 1 bản ở địa điểm khác, 1 bản bất biến (Object Lock ở chế độ COMPLIANCE) và 0 lỗi khi kiểm khôi phục. TL-04 yêu cầu bằng chứng là cấu hình theo chương 8 và biên bản diễn tập khôi phục.

## Biện pháp đã có

Chart `deploy/charts/backup-cell` gom các thành phần sao lưu của một cell. Cụ thể, `deploy/charts/backup-cell/templates/velero.yaml` sao lưu tài nguyên Kubernetes và `deploy/charts/backup-cell/templates/etcd.yaml` chụp etcd. `deploy/charts/backup-cell/templates/restore-check.yaml` cùng `deploy/charts/backup-cell/files/restore-check.sh` khôi phục bản sao lưu PostgreSQL hằng ngày sang cụm tạm và chạy câu kiểm (`deploy/charts/backup-cell/files/kiem-tra.sql`, `deploy/charts/backup-cell/files/kiem-so-cai.sql`). `deploy/charts/backup-cell/files/giu-dai-han.sh` quản lý thời hạn giữ dài hạn. Kho sao lưu bất biến được khai báo ở `deploy/charts/rgw-cell/values-kho-sao-luu.yaml`, và `deploy/environments/kind/kiem-thu-kho-doi-tuong.sh` kiểm rằng đối tượng trong bucket Object Lock không xoá được trước hạn. Các runbook liên quan là `ops/runbooks/sao-luu-postgresql.md`, `ops/runbooks/sao-luu-kubernetes.md` và `ops/runbooks/ceph-multisite.md`.

## Cách thu bằng chứng

Script `thu-bang-chung.sh` xuất trạng thái các bản sao lưu Velero và kết quả các lần chạy `restore-check` gần nhất ra thư mục bằng chứng. Biên bản diễn tập quý và năm (QA-14, QA-15) được lưu tại đơn vị vận hành; hồ sơ chỉ ghi số hiệu biên bản.

## Trạng thái và điểm còn thiếu

Trạng thái: **Một phần**. Cấu hình và kiểm thử trên kind đã có. INF-17 và INF-18 đang làm phần còn lại: MirrorMaker 2, snapshot của OpenSearch, ClickHouse, Valkey và lịch sao lưu đầy đủ. Bản ở địa điểm khác cần trung tâm dữ liệu thứ hai (INF-28). Biên bản diễn tập khôi phục chưa có (QA-14, QA-15).
