# Danh mục sổ tay vận hành (runbook)

Mỗi cảnh báo trong `deploy/charts/observability-cell/rules/`, `deploy/charts/observability-mgmt/rules/` và các luật riêng của chart dịch vụ có chú thích `runbook_url` dạng `https://runbook.vt.local/okr/<tên>[#<mục>]` (hoặc `{{ $rb }}/<tên>` với `runbookBase` của chart); trang đó hiển thị tệp `ops/runbooks/<tên>.md`, hoặc `ops/runbooks/canh-bao/<tên>.md` khi thư mục gốc không có tệp cùng tên (OPS-14). Sổ tay chi tiết của một thành phần nằm ở thư mục gốc; sổ tay theo cảnh báo do VH-02 viết nằm ở `canh-bao/`; sổ tay của 26 quy trình OPS do VH-01, VH-02 viết nằm ở `ops/`. Mọi sổ tay mới chép từ `_mau.md`.

Kiểm liên kết trước khi gộp (mọi `runbook_url` trỏ tới tệp và mục có thật, mọi liên kết tương đối giữa các sổ tay có tệp):

```bash
python3 -I ops/runbooks/kiem-lien-ket.py              # mã thoát 1 khi có liên kết gãy
python3 -I ops/runbooks/kiem-lien-ket.py --liet-ke    # bảng sổ tay → cảnh báo → tệp luật
npx --yes markdownlint-cli@0.45.0 --disable MD013 -- ops/runbooks   # tắt luật độ dài dòng: sổ tay viết mỗi đoạn một dòng
```

## Quy trình OPS

Bảng theo TL-04 mục 11.1. Cột "Sổ tay chi tiết" là sổ tay thành phần mà sổ tay OPS dẫn tới.

| Mã | Quy trình | Sổ tay | Sổ tay chi tiết |
|---|---|---|---|
| OPS-01 | GitOps: ApplicationSet theo cell | [ops-01](ops/ops-01-gitops-applicationset.md) | |
| OPS-02 | Phát hành theo đợt cell | [ops-02](ops/ops-02-phat-hanh-theo-dot-cell.md) | |
| OPS-03 | Canary với phân tích SLO | [ops-03](ops/ops-03-canary-phan-tich-slo.md) | |
| OPS-04 | Cờ tính năng theo tenant | [ops-04](ops/ops-04-co-tinh-nang-theo-tenant.md) | |
| OPS-05 | Quay lui tự động | [ops-05](ops/ops-05-quay-lui-tu-dong.md) | |
| OPS-06 | Đóng băng thay đổi trước mốc hạn | [ops-06](ops/ops-06-dong-bang-thay-doi.md) | |
| OPS-07 | Đưa tenant mới vào vận hành ≤ 1 ngày | [ops-07](ops/ops-07-dua-tenant-vao-van-hanh.md) | [cap-phat-kich-hoat-tenant](cap-phat-kich-hoat-tenant.md) |
| OPS-08 | Chuyển tenant giữa cell | [ops-08](ops/ops-08-chuyen-tenant-giua-cell.md) | [chuyen-tenant-giua-cell](chuyen-tenant-giua-cell.md) |
| OPS-09 | Migration CSDL expand, contract | [ops-09](ops/ops-09-migration-csdl.md) | |
| OPS-10 | Phát hành Policy Pack ≤ 5 ngày | [ops-10](ops/ops-10-phat-hanh-policy-pack.md) | |
| OPS-11 | Phát hành UI Pack ≤ 1 ngày | [ops-11](ops/ops-11-phat-hanh-ui-pack.md) | |
| OPS-12 | Bản LTS cho mô hình cách ly | [ops-12](ops/ops-12-ban-lts-cach-ly.md) | `docs/quan-tri-cai-dat/cach-ly.md` |
| OPS-13 | Trực vận hành và mức sự cố | [ops-13](ops/ops-13-truc-van-hanh-muc-su-co.md) | |
| OPS-14 | Runbook | [ops-14](ops/ops-14-runbook.md) | [_mau](_mau.md) |
| OPS-15 | Nâng cấp Kubernetes | [ops-15](ops/ops-15-nang-cap-kubernetes.md) | |
| OPS-16 | Vá node | [ops-16](ops/ops-16-va-node.md) | [an-ninh-kube-bench](an-ninh-kube-bench.md) |
| OPS-17 | Danh mục chuẩn bị trước mốc hạn | [ops-17](ops/ops-17-chuan-bi-truoc-moc-han.md) | [saga-chot-ky](saga-chot-ky.md) |
| OPS-18 | Diễn tập khôi phục | [ops-18](ops/ops-18-dien-tap-khoi-phuc.md) | [sao-luu-postgresql](sao-luu-postgresql.md), [sao-luu-kubernetes](sao-luu-kubernetes.md) |
| OPS-19 | Hội đồng thay đổi (CAB) | [ops-19](ops/ops-19-hoi-dong-thay-doi.md) | |
| OPS-20 | Xoay vòng khoá | [ops-20](ops/ops-20-xoay-vong-khoa.md) | [xoay-vong-khoa-du-lieu](xoay-vong-khoa-du-lieu.md), [openbao-van-hanh](openbao-van-hanh.md), [openbao-pki](openbao-pki.md) |
| OPS-21 | Tính lại và đối chiếu điểm hằng đêm | [ops-21](ops/ops-21-tinh-lai-doi-chieu-diem.md) | [doi-soat-diem-anh-chup](doi-soat-diem-anh-chup.md), [doi-soat-tai-lap-diem](doi-soat-tai-lap-diem.md) |
| OPS-22 | Kiểm tra chuỗi băm hằng ngày | [ops-22](ops/ops-22-kiem-tra-chuoi-bam.md) | [doi-soat-so-cai](doi-soat-so-cai.md) |
| OPS-23 | Thêm cell mới | [ops-23](ops/ops-23-them-cell-moi.md) | [ceph-multisite](ceph-multisite.md), [openbao-van-hanh](openbao-van-hanh.md) |
| OPS-24 | Xử lý thư chết (DLQ) | [ops-24](ops/ops-24-xu-ly-thu-chet.md) | [phat-lai-outbox](phat-lai-outbox.md) |
| OPS-25 | Rà soát năng lực | [ops-25](ops/ops-25-ra-soat-nang-luc.md) | |
| OPS-26 | Vá thư viện và CVE | [ops-26](ops/ops-26-va-thu-vien-cve.md) | |

## Sổ tay theo cảnh báo

| Tên (`runbook_url`) | Tệp | Cảnh báo | Nội dung |
|---|---|---|---|
| `slo-availability` | [canh-bao](canh-bao/slo-availability.md) | SLOAvailabilityFastBurn, SLOAvailabilitySlowBurn, CNPGClusterNotHealthy | Tiêu ngân sách lỗi khả dụng (SLO-AVL); cụm PostgreSQL mất primary |
| `slo-latency` | [canh-bao](canh-bao/slo-latency.md) | SLOReadLatencyBurn, SLOWriteLatencyBurn | Request đọc, ghi chậm quá ngưỡng (SLO-READ, SLO-WRITE) |
| `scoring-lag` | [canh-bao](canh-bao/scoring-lag.md) | ScoringLagHigh | Độ trễ tính điểm (SLO-SCORE) |
| `kafka-lag` | [canh-bao](canh-bao/kafka-lag.md) | KafkaConsumerLagGrowing | Lag consumer Kafka tăng liên tục |
| `dlq` | [canh-bao](canh-bao/dlq.md) | DLQNotEmpty | Hàng thư chết có bản ghi mới (OPS-24) |
| `outbox` | [canh-bao](canh-bao/outbox.md) | OutboxBacklog | Outbox tồn đọng; xem thêm `phat-lai-outbox.md` |
| `ledger-lag` | [canh-bao](canh-bao/ledger-lag.md) | LedgerLagHigh | Độ trễ ghi sổ cái (SLO-LEDGER) |
| `dr-14` | [canh-bao](canh-bao/dr-14.md) | LedgerChainBroken | Chuỗi băm sổ cái hỏng (DR-14) |
| `doi-soat-so-cai` | [gốc](doi-soat-so-cai.md) | LedgerKiemTraNgungChay, LedgerNiemPhongNgungChay, LedgerTSALoi, LedgerDauThoiGianQuaHan | Kiểm tra toàn vẹn, niêm phong và dấu thời gian sổ cái (DL-12, DV-09-03, DV-09-04) |
| `doi-soat` | [canh-bao](canh-bao/doi-soat.md) | MissionDoiSoatLechChuaXuLy, MissionDoiSoatDaTuSua | Đối soát của mission-core, chọn sổ tay theo nhãn `reconcile` |
| `doi-soat-*` | gốc | Cảnh báo đối soát riêng của từng job (R1 đến R7) | `doi-soat-ban-sao-to-chuc`, `doi-soat-diem-anh-chup`, `doi-soat-kho-phan-tich`, `doi-soat-minh-chung-ceph`, `doi-soat-outbox-kafka`, `doi-soat-tai-lap-diem` |
| `flowable-g2` | [canh-bao](canh-bao/flowable-g2.md) | Flowable* (23 cảnh báo, mỗi nhóm một mục `#...`) | Chỉ số giám sát Flowable của workflow-service (G2, DV-06-11) |
| `pg-replica-lag` | [canh-bao](canh-bao/pg-replica-lag.md) | CNPGReplicaClusterLag | Replica cluster phía Nam trễ |
| `pg-cpu` | [canh-bao](canh-bao/pg-cpu.md) | CNPGPrimaryCPUHigh | CPU primary PostgreSQL cao |
| `pgbouncer` | [canh-bao](canh-bao/pgbouncer.md) | PgBouncerWaitingClients | Client chờ kết nối PgBouncer |
| `backup` | [canh-bao](canh-bao/backup.md) | BackupMissing | Thiếu bản sao lưu nền hoặc lưu WAL trễ |
| `restore-check` | [canh-bao](canh-bao/restore-check.md) | RestoreCheckStale | Không có lần khôi phục thử thành công trong 8 ngày |
| `ceph-sync` | [canh-bao](canh-bao/ceph-sync.md) | CephSyncBehind | Đồng bộ multisite Ceph chậm |
| `ceph-multisite` | [gốc](ceph-multisite.md) | Cảnh báo kho đối tượng RGW | Vận hành kho đối tượng Ceph RGW của cell |
| `logging` | [canh-bao](canh-bao/logging.md) | FluentBitDropping | Fluent Bit bỏ bản ghi log |
| `tracing` | [canh-bao](canh-bao/tracing.md) | TraceCompletenessLow | Độ đầy đủ trace dưới 99% (NFR-10) |
| `certs` | [canh-bao](canh-bao/certs.md) | CertificateExpiringSoon | Chứng thư sắp hết hạn |
| `openbao` | [canh-bao](canh-bao/openbao.md) | OpenBaoSealed | OpenBao bị niêm phong |
| `openbao-van-hanh` | [gốc](openbao-van-hanh.md) | Cảnh báo vận hành OpenBao | Vận hành OpenBao của cell |
| `supply-chain` | [canh-bao](canh-bao/supply-chain.md) | KyvernoImageRejected | Kyverno từ chối ảnh không ký |
| `runtime-security` | [canh-bao](canh-bao/runtime-security.md) | FalcoCritical, FalcoNodeMissing, FalcosidekickDauRaLoi | Falco mức Critical và sức khoẻ Falco |
| `an-ninh-cong-ra`, `an-ninh-kube-bench` | gốc | Cảnh báo an ninh nền của security-cell | [an-ninh-cong-ra](an-ninh-cong-ra.md), [an-ninh-kube-bench](an-ninh-kube-bench.md) |
| `audit-archiver` | [gốc](audit-archiver.md) | Cảnh báo kho bất biến nhật ký kiểm toán (mỗi cảnh báo một mục) | Vận hành audit-archiver |
| `degradation` | [gốc](degradation.md) | SaturationLevel2 | Cell vào mức suy giảm 2 (TL-04 mục 6.9) |
| `capacity` | [canh-bao](canh-bao/capacity.md) | NodeCapacityHeadroomLow | Năng lực dự phòng node pool dưới 15% |
| `release-freeze` | [canh-bao](canh-bao/release-freeze.md) | ErrorBudgetHalfSpent | Dừng phát hành khi tiêu quá nửa ngân sách lỗi trước ngày 15 (OPS-19) |
| `saga-chot-ky` | [gốc](saga-chot-ky.md) | MSG-08-04 (nhật ký, `okrkpi_saga_period_close_over_budget_total`) | Theo dõi và xử lý saga chốt kỳ `CHOT_KY` (DV-06-06) |
| `nang-bac` | [canh-bao](canh-bao/nang-bac.md) | NG01..NG14 | Ngưỡng nâng bậc của TL-05 và quy trình nâng bậc |

Sổ tay thành phần khác ở thư mục gốc: `phat-lai-outbox.md` (phát lại sự kiện từ outbox, NEN-022), `suy-giam-ba-muc.md` (bộ điều khiển suy giảm ba mức, INF-25), `duong-canh-bao-cell.md` (đường cảnh báo từ cell lên Alertmanager cụm quản trị, INF-15), `org-republish.md`, `luu-tru-xoa-khoi-phuc.md`, `an-ninh-pam-rbac.md`, `an-ninh-soc-quy-tac.md`, `an-ninh-falco.md`, `openbao-hsm.md`.

Sổ tay đã có: `phat-lai-outbox.md` (phát lại sự kiện từ outbox, NEN-022), `degradation.md` (bật, tắt mức suy giảm của cổng API, DV-01-02), `geodns.md` (GeoDNS, chuyển vùng cell và `pg-registry`, INF-20), `ban-sao-am.md` (bản sao ấm ở TTDL thứ hai, INF-28).
