# INF-15 · TL-04 ch.10, TL-05 NG-01..NG-14 Quan sát phần 2: SLI, dashboard Grafana, cảnh báo theo tốc độ tiêu ngân sách lỗi, 24 cảnh báo tối thiểu, ngưỡng nâng bậc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-15` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-14 |
| Truy vết | TL-04 mục 7.1 (SLO-AVL, SLO-READ, SLO-WRITE, SLO-ASYNC, SLO-PAGE, SLO-LCP, SLO-SCORE, SLO-LEDGER), mục 10.4–10.6 → NFR-03, NFR-04, NFR-10 → OPS-13, OPS-19, OPS-25 → TL-05 NG-01..NG-14 → tiêu chí B4, B5, B7, B8 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Biến SLO của TL-04 và ngưỡng nâng bậc của TL-05 thành luật Prometheus, cảnh báo và dashboard dạng mã: luật ghi SLI, cảnh báo theo tốc độ tiêu ngân sách lỗi đa cửa sổ, bảng 24 cảnh báo tối thiểu có liên kết runbook, định tuyến Alertmanager theo mức độ, bốn nhóm dashboard Grafana và nhóm luật "kế hoạch năng lực" NG-01..NG-14 không gọi trực ca. Khi xong, mọi luật qua `promtool test rules`, và từ một cảnh báo người trực đi tới trace liên quan trong không quá 3 thao tác.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "SLO và ngân sách lỗi" (1666–1689), "Metric: Prometheus, Thanos và SLI" (3443–3482, `PrometheusRule slo-api-recording`), "Cảnh báo theo tốc độ tiêu ngân sách lỗi" (3484–3553, bảng cảnh báo 3528–3551), "Dashboard và giám sát phía người dùng" (3555–3559).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Danh mục ngưỡng NG" (700–717), "Quy trình nâng bậc" (721–727).
- Chart quan sát của INF-14, `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Viết `PrometheusRule slo-api-recording` (interval 30 s): `slo:api_requests:rate5m`, `slo:api_errors:rate5m`, `slo:api_read_fast:ratio_rate5m` (le 300), `slo:api_availability_errors:ratio_rate{5m,30m,1h,6h}`, nhãn `kind` read/write/async; không đặt nhãn tenant ở metric tổng (đếm theo tenant để ClickHouse).
- Viết cảnh báo burn-rate: `SLOAvailabilityFastBurn` (1 giờ và 5 phút > 14,4 × 0,001, for 2m, critical), `SLOAvailabilitySlowBurn` (6 giờ và 30 phút > 6 × 0,001, for 15m, warning), `SLOReadLatencyBurn`, `SLOWriteLatencyBurn`, `ErrorBudgetHalfSpent` (> 50% trước ngày 15, phục vụ OPS-19).
- Viết đủ bảng cảnh báo tối thiểu của TL-04 mục 10.5 (ví dụ `ScoringLagHigh`, `KafkaConsumerLagGrowing`, `DLQNotEmpty`, `OutboxBacklog`, `LedgerLagHigh`, `LedgerChainBroken`, `CNPGReplicaClusterLag`, `CNPGPrimaryCPUHigh`, `PgBouncerWaitingClients`, `BackupMissing`, `RestoreCheckStale`, `CephSyncBehind`, `FluentBitDropping`, `TraceCompletenessLow`, `CertificateExpiringSoon`, `OpenBaoSealed`, `KyvernoImageRejected`, `FalcoCritical`, `SaturationLevel2`, `NodeCapacityHeadroomLow`), mỗi cảnh báo có `runbook_url` trỏ tới tệp trong `ops/runbooks/` (VH-01, VH-02 viết nội dung).
- Viết cấu hình Alertmanager: gom theo `cell`, `alertname`; ức chế hệ quả (ví dụ `CNPGClusterNotHealthy` ức chế `OutboxBacklog`); critical gọi trực, warning nhắn tin (OPS-13).
- Viết nhóm luật NG-01..NG-14 của TL-05 với nhãn `severity: capacity`, gửi kênh đội nền tảng, không gọi trực; điều kiện "hai kỳ đỉnh liên tiếp" hiện thực bằng ghi nhận theo kỳ (suy luận về cách hiện thực, ghi rõ trong mô tả luật).
- Viết dashboard dạng mã (Grafonnet theo TL-04) cho bốn nhóm: SLO theo cell; theo service (Golden Signals, pool, Kafka lag, Resilience4j); hạ tầng dữ liệu; nghiệp vụ kỳ đánh giá; có exemplar sang Tempo và liên kết trace sang Loki.

## 4. Ngoài phạm vi

- Thu thập và lưu trữ (INF-14); nội dung runbook (VH-01, VH-02); dashboard tiến độ chuyển đổi (VH-09).
- Đo SLO-PAGE, SLO-LCP phía trình duyệt (WEB-011 cung cấp dữ liệu Faro; ở đây chỉ dựng luật và panel).

## 5. Tệp được phép sửa

- `deploy/charts/observability-cell/rules/**`, `deploy/charts/observability-mgmt/alertmanager/**`, `deploy/charts/observability-mgmt/dashboards/**` (suy luận về vị trí).
- `ops/runbooks/README.md` (chỉ danh mục tên tệp runbook để `runbook_url` không gãy).
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `promtool check rules` đạt cho mọi tệp luật; `promtool test rules` có ca kiểm cho mỗi cảnh báo burn-rate, mỗi cảnh báo trong bảng tối thiểu và mỗi luật NG (ít nhất một ca kích hoạt, một ca không kích hoạt).
- `amtool check-config` đạt; ca thử ức chế cho thấy `OutboxBacklog` không gửi khi cụm CNPG lỗi.
- Dashboard biên dịch ra JSON hợp lệ và nạp được vào Grafana trên kind; từ panel cảnh báo tới trace mẫu ≤ 3 thao tác (B5), có ảnh chụp trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
