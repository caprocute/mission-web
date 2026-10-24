# INF-14 · ADR-12, NFR-10 Quan sát phần 1: OTel Collector lấy mẫu theo đuôi, Tempo, Prometheus và Thanos, Loki, Fluent Bit có bộ đệm đĩa

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-14` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-08 |
| Truy vết | ADR-12 → TL-04 ch.10 (mục 10.2–10.4) → NFR-09, NFR-10 → SEC-07, SEC-53 → topic `sys.audit.v1` → tiêu chí B1–B3, B6, G1–G5, G7 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng ngăn xếp thu thập quan sát của cell và cụm quản trị theo TL-04 chương 10: OTel Collector hai tầng có lấy mẫu theo đuôi, Tempo giữ trace 14 ngày, Prometheus theo cặp mỗi cell kèm Thanos, Loki giữ log nóng 90 ngày, Fluent Bit có bộ đệm đĩa và che dữ liệu cá nhân, luồng nhật ký kiểm toán sang Kafka `sys.audit.v1`. Khi xong, một request đi từ trình duyệt qua APISIX tới service thử và Kafka tạo một trace liền mạch tra được trên Tempo, log của request tra được theo `trace_id`, và chặn Loki 5 phút không làm mất dòng log nào.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Quan sát hệ thống": "Mục tiêu và hiện trạng" (3255–3263), "Trace: OpenTelemetry và lấy mẫu theo đuôi" (3265–3331), "Log: Fluent Bit có bộ đệm đĩa, không mất log khi tải cao" (3333–3441), "Metric: Prometheus, Thanos và SLI" (3443–3482), "Mức đáp ứng các tiêu chí B và G" (3561–3579).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-04.md` dòng 45 (cách hiểu NFR-10 chờ chủ đầu tư xác nhận), dòng 47 (AGPL chỉ dùng nội bộ).
- `infra/dev/docker-compose.yml` profile `observability` (grafana/otel-lgtm ở dev), `docs/tasks/WEB-011-telemetry-faro.md` (Faro phía web cần điểm nhận).

## 3. Phạm vi công việc

- Viết chart `deploy/charts/otel-collector`: DaemonSet tầng agent với exporter `loadbalancing` theo `traceID`, StatefulSet tầng gateway 3–6 pod mỗi cell với `memory_limiter`, `attributes/redact` (xoá `authorization`, băm `enduser.id`, xoá `db.statement`), `transform/redact_url` che `token|code|password`, `tail_sampling` (100% lỗi, GET > 300 ms, mọi request > 800 ms, `vt.debug=1`, 10% bình thường), `batch`, exporter `otlp/tempo` có hàng đợi lưu tệp, đúng cấu hình TL-04 mục 10.2; thêm điểm nhận Faro cho SVC-17 sau APISIX.
- Viết values Tempo (lưu Ceph S3 cụm quản trị, giữ 14 ngày), Prometheus Operator một cặp mỗi cell (scrape 15 s qua ServiceMonitor/PodMonitor do `vt-service` sinh, native histogram), Thanos sidecar, Query, Store, Compactor (raw 30 ngày, 5 phút 180 ngày, 1 giờ 2 năm), Grafana trong cụm quản trị.
- Viết values Loki (TSDB, Ceph S3, giữ nóng 90 ngày, chép khối cũ sang bucket lạnh giữ thêm 1 năm) và Fluent Bit DaemonSet đúng cấu hình TL-04 mục 10.3: `storage.path`, `storage.type filesystem`, `Mem_Buf_Limit 64M`, input `k8saudit`, `redact.lua` che JWT (`eyJ...`), số CCCD 12 chữ số, số điện thoại, email; `rewrite_tag` đưa dataset `audit|security` sang Kafka `sys.audit.v1` (ssl, `acks all`, idempotence), phần còn lại sang Loki với nhãn `cell, namespace, service, level`.
- Đặt volume đệm riêng 20 GB mỗi node và cấu hình kubelet `containerLogMaxSize 100Mi`, `containerLogMaxFiles 5` trong values cụm (INF-08 dùng).
- Viết recording metric `trace_completeness_ratio` theo cách hiểu hai vế của NFR-10 trong TL-04 mục 10.2, ghi rõ chờ chủ đầu tư xác nhận.
- Điểm lệch cần lưu ý: Grafana, Loki, Tempo giấy phép AGPL chỉ dùng nội bộ SaaS; bản cài tại chỗ dùng phương án Apache-2.0 khi pháp chế chưa duyệt (TL-04 mục 9.9). Chart phải có cờ thay thế để INF-23 dùng; ghi vào wiki 12.
- Gợi ý tách: (a) OTel Collector và Tempo; (b) Prometheus, Thanos, Grafana; (c) Loki, Fluent Bit, luồng kiểm toán.

## 4. Ngoài phạm vi

- SLI, cảnh báo, dashboard (INF-15); SIEM và quy tắc phát hiện (INF-19).
- OTel trong mã ứng dụng (NEN-016) và Faro trong shell web (WEB-011).

## 5. Tệp được phép sửa

- `deploy/charts/otel-collector/**`, `deploy/charts/observability-cell/**`, `deploy/charts/observability-mgmt/**` (tên suy luận), `deploy/environments/kind/**`.
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` không lỗi; `otelcol validate --config` đạt cho cấu hình gateway và agent; `fluent-bit --dry-run` đạt; kiểm thử `redact.lua` bằng bộ dòng mẫu (JWT, CCCD, số điện thoại) cho kết quả đã che.
- Trên kind: một request thử qua APISIX → service mẫu → Kafka → consumer mẫu tạo một trace có đủ chặng trên Tempo (B1); log của request tra được theo `trace_id` trên Loki (G3); target Prometheus `up` 100% (B3).
- Chặn Loki 5 phút trong lúc sinh log đều, sau khi mở lại số dòng nhận bằng số dòng phát, `fluentbit_output_dropped_records_total` bằng 0 (bản rút gọn của nghiệm thu G4).
- Truy vấn LogQL tìm `eyJ` và chuỗi 12 chữ số trên log thử trả 0 kết quả (G7).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
