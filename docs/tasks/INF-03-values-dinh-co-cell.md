# INF-03 · TL-05 Values định cỡ bốn hồ sơ cell, `capacity/assumptions.yaml`, script tính bảng, kiểm ngân sách kết nối trong CI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-02 |
| Truy vết | TL-05 (GD-01..GD-26, hồ sơ `compact`/`S`/`M`/`L`, Phụ lục A) → TL-04 ch.4 mục 4.7 (ngân sách kết nối) → NFR-01, NFR-06 → tiêu chí C5, vấn đề F01 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Biến các bảng định cỡ của TL-05 và TL-04 chương 4 thành dữ liệu máy đọc được trong kho GitOps: bốn tệp values theo hồ sơ cell, tệp giả định `capacity/assumptions.yaml` có nguồn và ngày đo, một script tính lại bảng định cỡ từ giả định, và bước CI chặn mọi thay đổi làm vượt ngân sách kết nối CSDL. Khi xong, đổi một giả định GD-xx rồi chạy script sẽ cho ra số replica và pool mới, và một merge request tăng `maxReplicas` vượt ngân sách bị CI từ chối.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md`: "Danh mục giả định định cỡ" (85–118), "Công thức" (120–148), "Tải đầu vào theo hồ sơ cell" (150–170), "Bốn hồ sơ năng lực cell" (194–207), chương "Định cỡ tầng ứng dụng theo hồ sơ cell" (233–301), "PostgreSQL" (311–355), "Ngân sách kết nối theo hồ sơ" (357–372), "Vòng hiệu chuẩn" (751–755), Phụ lục A (757–875).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Định cỡ pool kết nối CSDL" (777–810), "Bảo vệ CSDL khi co giãn" (1567–1577).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-05.md` (đề xuất sửa TL-04 chưa áp dụng).

## 3. Phạm vi công việc

- Viết `deploy/charts/vt-platform/sizing/values-sizing-m.yaml` đúng mẫu TL-05 Phụ lục A (dòng 762–872): `global.sizing: M`, `topology.zones`, `replicas{min,max}` cho 26 workload, pool tám module của mission-core, `postgres.clusters.*`, `kafka`, `valkey`, `opensearch`, `clickhouse`, `vllm`, `nodePools`.
- Dựng ba tệp `values-sizing-compact.yaml`, `values-sizing-s.yaml`, `values-sizing-l.yaml` cùng cấu trúc từ các bảng TL-05 chương 4, 5 và TL-04 chương 4, 5 (TL-05 chỉ in mẫu M); `compact` có `postgres.consolidated: true` (chart sinh hai cụm CNPG `pg-core`, `pg-ledger`), một vùng, `minReplicas` 2 và pool mission-core 12/pod.
- Viết values cho hai cell khởi đầu: `deploy/cells/nb/values.yaml` (CELL-NB, hồ sơ `compact`, tạm chốt ở wiki 14) và `deploy/cells/g4/values.yaml` (CELL-G4, hồ sơ M), theo thứ tự chồng TL-05: mô hình → hồ sơ → cell.
- Viết `deploy/capacity/assumptions.yaml` chứa GD-01..GD-26 với giá trị, đơn vị, nguồn (mục TL-05), ngày đo và kịch bản PERF hiệu chuẩn (ví dụ GD-08 kiểm bằng PERF-02).
- Viết script Python `deploy/capacity/tinh_dinh_co.py` tính theo công thức TL-05 (`CCU = 0,05A`, `RPS_đỉnh = 2 × CCU/20`, `n_s = ceil(p_s × RPS_đỉnh / T_s)`, `maxReplicas ≥ ceil(1,5 n_s)`) và in bảng so sánh với values đang có, đánh dấu chênh > 20% (vòng hiệu chuẩn TL-05).
- Viết bước kiểm ngân sách kết nối `deploy/capacity/kiem_ngan_sach_ket_noi.py`: dựng toàn bộ values của một cell, kiểm hai bất đẳng thức TL-04 mục 4.7 (Σ(maxReplicas × pool) + C_job + C_admin ≤ N_pooler × max_client_conn; N_pooler × Σ default_pool_size + C_direct + R ≤ max_connections), `max_connections` theo hồ sơ 200/200/300/400 (TL-05), xuất bảng vào báo cáo CI và trả mã lỗi khi vi phạm.
- Điểm lệch cần lưu ý: bảng node TL-04 mục 4.4 (hạng M) khác số TL-05 (M 30/46 node, 568/824 vCPU); `_ghi-chu-TL-05.md` đề nghị sửa TL-04 nhưng chưa áp dụng. Chọn số của TL-05 (bản v1.0 mới hơn, chuyên về định cỡ) và ghi vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 4. Ngoài phạm vi

- Luật cảnh báo NG-01..NG-14 (INF-15); chạy PERF để hiệu chuẩn GD-xx (QA-12).
- Cluster API và Cluster Autoscaler đọc `nodePools` (INF-16).

## 5. Tệp được phép sửa

- `deploy/charts/vt-platform/sizing/**`, `deploy/cells/**`, `deploy/capacity/**` (thư mục mới, suy luận về vị trí trong kho GitOps).
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- `helm template deploy/charts/vt-platform -f values-saas.yaml -f sizing/values-sizing-<x>.yaml -f deploy/cells/<cell>/values.yaml` chạy sạch cho bốn hồ sơ và hai cell; `compact` sinh đúng hai cụm CNPG.
- `pytest deploy/capacity` xanh, gồm ca: số mẫu `pg-core` CELL-HCM của TL-04 mục 4.7 (24 × 24 = 576 + 20 + 10 = 606 ≤ 3 × 1.000; 3 × 40 + 20 + 30 = 170 ≤ 400) cho kết quả đạt; một bộ values cố ý tăng `maxReplicas` mission-core lên 40 cho kết quả vi phạm.
- Script tính lại cho hồ sơ M trùng bảng TL-05 trong sai số làm tròn; chênh lệch được liệt kê trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 12 có mục "Định cỡ và hiệu chuẩn".

## 7. Báo cáo

Theo AGENTS.md mục 6.
