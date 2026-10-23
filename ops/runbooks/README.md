# Danh mục sổ tay vận hành (runbook)

Mỗi cảnh báo trong `deploy/charts/observability-cell/rules/` và `deploy/charts/observability-mgmt/rules/` có chú thích `runbook_url` dạng `https://runbook.vt.local/okr/<tên>`; trang đó hiển thị tệp `ops/runbooks/<tên>.md` (OPS-14). Danh mục dưới đây giữ cho đường dẫn không gãy. Nội dung từng sổ tay do VH-01, VH-02 viết; tệp chưa có nghĩa là sổ tay chưa được viết.

| Tên (tệp `<tên>.md`) | Cảnh báo | Nội dung |
|---|---|---|
| `slo-availability` | SLOAvailabilityFastBurn, SLOAvailabilitySlowBurn | Tiêu ngân sách lỗi khả dụng (SLO-AVL) |
| `slo-latency` | SLOReadLatencyBurn, SLOWriteLatencyBurn | Request đọc, ghi chậm quá ngưỡng (SLO-READ, SLO-WRITE) |
| `scoring-lag` | ScoringLagHigh | Độ trễ tính điểm (SLO-SCORE) |
| `kafka-lag` | KafkaConsumerLagGrowing | Lag consumer Kafka tăng liên tục |
| `dlq` | DLQNotEmpty | Hàng thư chết có bản ghi mới (OPS-24) |
| `outbox` | OutboxBacklog | Outbox tồn đọng; xem thêm `phat-lai-outbox.md` |
| `ledger-lag` | LedgerLagHigh | Độ trễ ghi sổ cái (SLO-LEDGER) |
| `dr-14` | LedgerChainBroken | Chuỗi băm sổ cái hỏng (DR-14) |
| `doi-soat-so-cai` | LedgerKiemTraNgungChay, LedgerNiemPhongNgungChay, LedgerTSALoi, LedgerDauThoiGianQuaHan | Kiểm tra toàn vẹn, niêm phong và dấu thời gian sổ cái (DL-12, DV-09-03, DV-09-04) |
| `pg-replica-lag` | CNPGReplicaClusterLag | Replica cluster phía Nam trễ |
| `pg-cpu` | CNPGPrimaryCPUHigh | CPU primary PostgreSQL cao |
| `pgbouncer` | PgBouncerWaitingClients | Client chờ kết nối PgBouncer |
| `backup` | BackupMissing | Thiếu bản sao lưu nền hoặc lưu WAL trễ |
| `restore-check` | RestoreCheckStale | Không có lần khôi phục thử thành công trong 8 ngày |
| `ceph-sync` | CephSyncBehind | Đồng bộ multisite Ceph chậm |
| `logging` | FluentBitDropping | Fluent Bit bỏ bản ghi log |
| `tracing` | TraceCompletenessLow | Độ đầy đủ trace dưới 99% (NFR-10) |
| `certs` | CertificateExpiringSoon | Chứng thư sắp hết hạn |
| `openbao` | OpenBaoSealed | OpenBao bị niêm phong |
| `supply-chain` | KyvernoImageRejected | Kyverno từ chối ảnh không ký |
| `runtime-security` | FalcoCritical | Falco mức Critical |
| `degradation` | SaturationLevel2 | Cell vào mức suy giảm 2 (TL-04 mục 6.9) |
| `capacity` | NodeCapacityHeadroomLow | Năng lực dự phòng node pool dưới 15% |
| `release-freeze` | ErrorBudgetHalfSpent | Dừng phát hành khi tiêu quá nửa ngân sách lỗi trước ngày 15 (OPS-19) |
| `saga-chot-ky` | MSG-08-04 (nhật ký, `okrkpi_saga_period_close_over_budget_total`) | Theo dõi và xử lý saga chốt kỳ `CHOT_KY` (DV-06-06) |
| `nang-bac` | NG01..NG14 | Ngưỡng nâng bậc của TL-05 và quy trình nâng bậc |

Sổ tay đã có: `phat-lai-outbox.md` (phát lại sự kiện từ outbox, NEN-022).
