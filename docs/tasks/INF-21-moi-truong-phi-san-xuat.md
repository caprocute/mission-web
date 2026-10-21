# INF-21 · TL-05 Môi trường phi sản xuất: staging, CELL-PERF, cụm sinh tải, môi trường tạm theo nhánh cho E2E

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-21` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (staging, môi trường tạm theo nhánh); CELL-PERF và `perf-gen` ở đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-04 |
| Truy vết | TL-05 "Môi trường phi sản xuất" (QM-1, QM-3) → TL-04 mục 7.3 "Môi trường và công cụ thử tải" → TL-01 ch.18 "Tháp kiểm thử" (Playwright trên môi trường tạm theo nhánh) → SEC-52 (không dữ liệu thật) → OPS-02, OPS-15 → tiêu chí J5, J7 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Khai báo dạng mã các môi trường phi sản xuất mà TL-05 yêu cầu: staging (hồ sơ S rút gọn, cùng chart và phiên bản production, điểm dừng bắt buộc của mọi bản phát hành), `CELL-PERF` (cùng chart, phần cứng giống production, tầng dữ liệu thường trực, tầng ứng dụng dựng theo chiến dịch), cụm sinh tải `perf-gen` với k6-operator, và môi trường tạm theo nhánh để chạy bộ hành trình đầu cuối. Khi xong, khi có tài nguyên chỉ cần áp cấu hình qua Argo CD, còn hiện tại cùng cấu hình đã dựng được bản thu nhỏ trên kind/k3d.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Môi trường phi sản xuất" (526–541), "Vùng điều phối toàn cục và cụm quản trị" (508–524), "Mốc mua sắm theo lộ trình" (618–630), "Bộ kịch bản theo hồ sơ" (735–749).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Môi trường và công cụ thử tải" (1709–1741, `TestRun` mẫu), "Danh mục kịch bản thử tải PERF" (1743–1776, lịch chạy).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tháp kiểm thử" (3124–3128).
- Kho GitOps của INF-04, values hồ sơ của INF-03, `docs/quy-dinh/HA-TANG.md` mục 3.

## 3. Phạm vi công việc

- Viết khai báo môi trường `deploy/environments/staging/` (hồ sơ S rút gọn mức `minReplicas`, không GPU, tầng dữ liệu trên VM), cell `deploy/cells/stg/cell.yaml` và ApplicationSet đưa staging vào trước đợt cell đầu tiên của OPS-02.
- Viết khai báo `deploy/environments/perf/` và `deploy/cells/perf/` cho `CELL-PERF`: hồ sơ M ở QM-1..QM-3, nâng L trước QM-4 (TL-05); tách tầng dữ liệu thường trực và tầng ứng dụng bật theo chiến dịch bằng cờ trong values.
- Viết cụm `perf-gen`: values k6-operator, ConfigMap `perf-scripts` nạp từ `infra/perf/` (QA-08 viết kịch bản), mẫu `TestRun` theo TL-04 mục 7.3 (`parallelism`, `separate: true`, thẻ `testid`, xuất Prometheus remote write), client Keycloak riêng cho thử tải dùng client credentials hạn 2 giờ (chỉ khai báo tên client, không bí mật).
- Viết môi trường tạm theo nhánh: ApplicationSet dùng bộ sinh pull request (suy luận về cơ chế) tạo namespace `pr-<số>` với hồ sơ `compact`, dữ liệu sinh tổng hợp, tự xoá khi đóng nhánh; dùng cho bộ hành trình QA-04..QA-07.
- Ghi quy tắc dữ liệu: không dùng dữ liệu thật ngoài sản xuất (SEC-52); staging và CELL-PERF chỉ nạp dữ liệu tổng hợp từ DL-08 hoặc dữ liệu đã che.
- Viết tài liệu danh mục tài nguyên cần cấp cho từng môi trường theo TL-05 (số node, vCPU, RAM, máy vật lý) để người dùng làm đề xuất cấp phát.

## 4. Ngoài phạm vi

- Kịch bản thử tải và chiến dịch thử tải (QA-08..QA-12); bộ sinh dữ liệu quy mô (DL-08).
- Mua sắm, cấp phát máy thật.
- Dựng và kiểm thử trên tài nguyên hạ tầng thật (staging khoảng 22 node, CELL-PERF 14 máy vật lý, `perf-gen` khoảng 10 VM) khi được cấp; khi đó chỉ đổi values theo môi trường.

## 5. Tệp được phép sửa

- `deploy/environments/staging/**`, `deploy/environments/perf/**`, `deploy/environments/pr/**`, `deploy/cells/stg/**`, `deploy/cells/perf/**`, `deploy/argocd/**` phần môi trường phi sản xuất.
- `infra/perf/README.md`, `deploy/environments/kind/**`, `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `helm template` cho staging, CELL-PERF (M và L) và môi trường tạm chạy sạch; `kubeconform -strict` đạt; ApplicationSet sinh đúng Application.
- Trên kind/k3d: dựng bản thu nhỏ của môi trường tạm theo nhánh (hồ sơ `compact`) và cài k6-operator; chạy một `TestRun` mẫu 1 phút tới service thử, số đo xuất ra Prometheus.
- Tài liệu tài nguyên cần cấp có đủ con số TL-05 cho từng môi trường; kích thước và địa chỉ của staging, CELL-PERF, `perf-gen` nằm trong values theo môi trường, nên dựng trên tài nguyên thật chỉ đổi cấu hình.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
