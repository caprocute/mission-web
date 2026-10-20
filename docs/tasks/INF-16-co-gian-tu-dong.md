# INF-16 · TL-04 ch.6 Co giãn: Prometheus Adapter cho HPA, KEDA theo độ trễ Kafka, KEDA theo lịch hạn, Cluster Autoscaler có pod giữ chỗ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-16` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-14, INF-10 |
| Truy vết | TL-04 ch.6 (mục 6.1–6.7) → SVC-05, SVC-06, SVC-07 → MOD-08 bảng `config.period`, API `GET /api/v1/periods` → OPS-06 → PERF-04, PERF-19 → NFR-02 → tiêu chí C5, E3, F8 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực vòng điều khiển co giãn của TL-04 chương 6: HPA theo CPU và chỉ số tuỳ chỉnh qua Prometheus Adapter, KEDA theo độ trễ consumer Kafka cho worker, KEDA theo lịch để tăng trước các mốc hạn sinh tự động từ kỳ đánh giá, co giãn riêng `workflow-executor` theo hàng đợi job Flowable, và Cluster Autoscaler qua Cluster API kèm pod giữ chỗ. Khi xong, trên cụm thử, tăng độ trễ topic đầu vào tính điểm làm scoring-service tăng pod, và cửa sổ tăng trước mốc hạn nâng `minReplicas` mission-core đúng giờ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Cơ chế co giãn tự động": "Tổng quan vòng điều khiển co giãn" (1200–1206), "HPA autoscaling/v2 với chỉ số tài nguyên và chỉ số tuỳ chỉnh" (1208–1248), "KEDA theo độ trễ consumer Kafka" (1250–1326), "KEDA theo lịch: tăng trước các mốc hạn" (1328–1413), "Co giãn node: Cluster Autoscaler trên Viettel Cloud" (1415–1500), "Co giãn workflow-service và async executor của Flowable" (1502–1565), "Bảo vệ CSDL khi co giãn" (1567–1577).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Số replica của các service bất đồng bộ và vùng bảo mật" (259–276).
- `contracts/openapi/modules/config.yaml` thao tác `listPeriods`; chart `vt-service` (INF-01), values hồ sơ (INF-03).

## 3. Phạm vi công việc

- Viết values Prometheus Adapter với rule `http_server_requests_per_second` (rate 1 phút, loại `/actuator.*`) và rule external `mission_core_p95_when_busy_ms` đúng mẫu TL-04 mục 6.2.
- Viết `ScaledObject` KEDA cho scoring-service đúng mẫu mục 6.3 (2/24, `pollingInterval 15`, `cooldownPeriod 600`, trigger `product.evidence.accepted.v1` lag 500/activation 10, `scoring.recompute.requested.v1` lag 200/activation 1, CPU 80%, `TriggerAuthentication` mTLS); viết tương tự cho document-service, ClamAV scanner, notification-service, search indexer với ngưỡng đặt trong values.
- Viết `ScaledObject` mission-core (target Rollout, 6/24, trigger CPU 85 và `rps-per-pod` 220) có trigger cron `deadline-default` múi giờ `Asia/Ho_Chi_Minh` 06:00–23:00 cho ba cửa sổ của TL-04 mục 6.4 (tuần cuối quý, ngày 1–10 đầu quý, 20/1 đến 1/2) với số pod tối thiểu theo service.
- Viết công cụ sinh `prescale-windows.yaml` cho từng cell: đọc kỳ qua `GET /api/v1/periods`, sinh cửa sổ bổ sung, mở merge request cho người trực duyệt; `deadline-default` luôn là sàn (OPS-06).
- Viết `ScaledObject workflow-executor` (2/10, trigger Prometheus `max(flowable_async_jobs_due_unlocked{namespace="core"})` threshold 400, activation 50, CPU 75) và cấu hình tách `workflow-api` (HPA) với `workflow-executor` (KEDA) theo mục 6.6.
- Viết Cluster Autoscaler provider Cluster API: mỗi pool co giãn ba `MachineDeployment` (một mỗi vùng), `--balance-similar-node-groups=true`, annotation min/max và capacity; `PriorityClass capacity-reservation` (value −10, `preemptionPolicy: Never`) và Deployment pause giữ chỗ cho app-general và app-worker.
- Điểm lệch cần lưu ý: ngưỡng KEDA cho document, ClamAV, notification, indexer không có trong TL-04; đặt giá trị khởi điểm (suy luận), đánh dấu chờ hiệu chuẩn ở QA-11, QA-12.
- Gợi ý tách: (a) Prometheus Adapter + HPA chuẩn; (b) KEDA Kafka cho worker; (c) KEDA cron + công cụ sinh cửa sổ; (d) Cluster Autoscaler + pod giữ chỗ.

## 4. Ngoài phạm vi

- Bộ điều khiển suy giảm ba mức và cắt tải APISIX (giới hạn tốc độ ở INF-08; bộ điều khiển suy giảm đề xuất bổ sung hàng khung).
- Thử tải kiểm chứng PERF-04, PERF-19 (QA-08, QA-11).

## 5. Tệp được phép sửa

- `deploy/charts/vt-service/**` (template ScaledObject cron, Rollout target), `deploy/charts/<ten-dich-vu>/values.yaml` phần scaling, `deploy/charts/autoscaling-cell/**` (Prometheus Adapter, Cluster Autoscaler, pod giữ chỗ; tên suy luận).
- `deploy/clusters/**` (MachineDeployment), `deploy/tools/prescale/**` (công cụ sinh cửa sổ, suy luận), `deploy/environments/kind/**`.
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm template`, `kubeconform -strict` (schema CRD KEDA, Cluster API) không lỗi; Kyverno (INF-07) không phát hiện workload nào có hai bộ co giãn.
- Trên kind có KEDA và Strimzi thu nhỏ: đẩy lag topic thử vượt 500 làm consumer thử tăng pod, hết lag thì giảm sau `cooldownPeriod`; cron cửa sổ thử (đặt lệch giờ để chạy ngay) nâng `minReplicas` đúng.
- Công cụ sinh cửa sổ có kiểm thử đơn vị với dữ liệu kỳ giả lập theo lược đồ `listPeriods`.
- Cluster Autoscaler kiểm bằng Cluster API provider Docker (CAPD) trên máy chạy kind nếu đủ tài nguyên; nếu không, báo cáo ghi rõ chưa kiểm chứng phần node.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
