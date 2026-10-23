# CronJob snapshot Raft mỗi giờ (INF-18, TL-04 mục 8.10): chỉ đọc snapshot, không khôi phục được (khôi phục dùng
# sys/storage/raft/snapshot-force bằng token quản trị theo quy trình bốn mắt, ops/runbooks/sao-luu-openbao.md).
path "sys/storage/raft/snapshot" { capabilities = ["read"] }
