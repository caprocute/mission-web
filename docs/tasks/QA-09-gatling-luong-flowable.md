# QA-09 · PERF-11..PERF-13 Kịch bản Gatling kiểm chứng góp ý G2 về engine luồng Flowable

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (PERF-11, PERF-12 là điều kiện sang đợt 3) |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-08, DV-06-04 |
| Truy vết | Góp ý G2, ADR-04, NFR-03 → TL-04 mục 7.5 → PERF-11, PERF-12, PERF-13 → SVC-07 workflow-service, `pg-flowable`, `flowable.wf_task_projection`, `wf_binding` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết ba kịch bản Gatling cho luồng duyệt nhiều bước có trạng thái, đo thông lượng engine, tác động của số tenant và kích thước bảng runtime, kèm các truy vấn kiểm tra tiêu chí đạt của TL-04 mục 7.5. Khi xong, kịch bản chạy rút gọn được trên kind hoặc staging, và chạy đầy đủ trên `CELL-PERF` trong QA-12.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 7.5 "Kịch bản riêng cho góp ý G2" (dòng 1778–1808), mục 7.3 (Gatling cho luồng nhiều bước).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` ADR-04 và mục ngưỡng tách engine; `TL-05` mục 12.2 hàng PERF-11 theo hồ sơ.
- `contracts/openapi/modules/tasks.yaml` (`listPlanApprovals`, `approvePlanVersion`), `platform.yaml` (`completeWorkflowTask`, `batchCompleteWorkflowTasks`), `docs/tasks/DV-06-04-*.md` (30 mẫu luồng).

## 3. Phạm vi công việc

- Dự án Gatling (Java 21, Maven) trong `infra/perf/gatling/`: mô phỏng người duyệt mở danh sách việc chờ (đọc từ `wf_task_projection` qua API), mở chi tiết, hoàn thành user task với biến quyết định.
- PERF-11: tải 150 lần hoàn thành mỗi giây tăng tới 300; khẳng định p95 hoàn thành ≤ 500 ms ở 150/giây và ≤ 800 ms ở 300/giây, p95 danh sách ≤ 300 ms, lỗi < 0,1%.
- PERF-12: khởi tạo 1.000 tenant phân bố Zipf, 30 mẫu, 4 phiên bản; truy vấn đếm `ACT_RE_PROCDEF` ≤ 120 sau khởi tạo; tỷ lệ trúng cache định nghĩa ≥ 99,9%; so sánh với chạy 10 tenant (chênh ≤ 10%); chạy linter 30 mẫu theo quy tắc ADR-04.
- PERF-13: nạp trước 1,2 triệu tiến trình đang chạy (dùng bộ sinh của DL-08), chạy 24 giờ; truy vấn kích thước `ACT_RU_*` ≤ 60 GB, trung bình biến ≤ 10, không biến > 4 KB; so p95 giờ 1 với giờ 24.
- Tỷ lệ xung đột khoá lạc quan < 0,5% đo bằng metric của workflow-service; truy vấn SQL kiểm tra đặt trong `infra/perf/kiem-tra/flowable/*.sql`.
- Tham số theo hồ sơ cell (10, 40, 100, 150..300 lần mỗi giây) đọc từ `infra/perf/profiles/`.

## 4. Ngoài phạm vi

- Sửa engine hay mẫu BPMN khi kịch bản phát hiện điểm gãy (mở task riêng, cập nhật ngưỡng tách engine theo TL-04 mục 7.5 tiêu chí 4).
- Chiến dịch thật trên `CELL-PERF` (QA-12).

## 5. Tệp được phép sửa

- `infra/perf/gatling/**`, `infra/perf/kiem-tra/flowable/**`, `infra/perf/profiles/**` (khoá `flowable`), `infra/perf/README.md` (mục Gatling).

## 6. Tiêu chí hoàn thành

- `cd infra/perf/gatling && ./mvnw -q verify` xanh; `./mvnw gatling:test -Dsimulation=Perf11 -Dprofile=compact -Dscale=0.05` chạy được trên server dev hoặc kind với workflow-service thật.
- Truy vấn kiểm tra chạy được trên `pg-flowable` thử và trả số đo; linter mẫu báo 0 vi phạm trên bộ mẫu hiện có (hoặc liệt kê vi phạm).
- Báo cáo ghi rõ số đo rút gọn và nói rõ không phải kết luận cho sản xuất.

## 7. Báo cáo

Theo AGENTS.md mục 6.

### Điểm lệch cần lưu ý

- TL-04 mô tả hoàn thành task qua `/plans/approvals/{taskId}/complete`; hợp đồng có `listPlanApprovals`, `approvePlanVersion` và `completeWorkflowTask`. Kịch bản dùng operationId trong hợp đồng.
