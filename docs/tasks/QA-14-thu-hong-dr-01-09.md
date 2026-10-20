# QA-14 · DR-01..DR-09 Thử hỏng PERF-07 và diễn tập khôi phục trong một trung tâm dữ liệu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-14` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (PERF-07 rút gọn dùng cho tiêu chí đợt 0b) |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-17, QA-08 |
| Truy vết | NFR-05, NFR-09 → TL-04 mục 8.11 "Kịch bản DR", 8.12 "Diễn tập khôi phục" → DR-01..DR-09, PERF-07, PERF-09, PERF-10, OPS-14, OPS-18 → CNPG `pg-*`, Strimzi Kafka, Ceph RGW, Cluster API, Velero |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết runbook DR-01..DR-09 theo cấu trúc thống nhất của OPS-14 và diễn tập từng kịch bản, đo RPO, RTO thật, lập biên bản. Khi xong, mỗi kịch bản trong một TTDL có runbook đã chạy thử ít nhất một lần, biên bản ghi số đo so với bảng DR, và các bước không chạy đúng runbook đã thành hạng mục khắc phục.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 8.11 "Kịch bản DR" (dòng 2336–2429: bảng DR và chi tiết DR-05..DR-09), 8.12 "Diễn tập khôi phục" (dòng 2431–2437), 8.2 (CNPG, Barman Cloud, PITR), 8.6 (Velero, etcd); mục 7.4 hàng PERF-07, PERF-09, PERF-10; mục 11.7 (cấu trúc runbook OPS-14).
- `docs/tasks/INF-17-sao-luu-csdl.md` (`restore-check`, Velero, etcd), `INF-18-sao-luu-du-lieu-khac.md`, `QA-08-k6-perf-01-10.md` (tải nền, tệp chaos).

## 3. Phạm vi công việc

- Runbook `ops/runbooks/dr/dr-01.md` .. `dr-09.md`: triệu chứng, tác động, cách phát hiện (cảnh báo nào), bước theo nhánh, leo thang, xác nhận khắc phục, RPO và RTO mục tiêu, người chịu trách nhiệm theo bảng DR.
- DR-01, DR-02, DR-03: chạy PERF-07 (tắt node mỗi 10 phút trong 1 giờ) và PERF-10 rút gọn; kiểm lỗi < 0,1%, pod xếp lại ≤ 2 phút.
- DR-04: PERF-09 (xoá pod primary `pg-core`), đo thời gian chuyển ≤ 30 s và RPO = 0 bằng đối soát số giao dịch.
- DR-05, DR-06: khôi phục toàn cụm và PITR sang cụm tạm (không đè cụm đang chạy) từ Barman Cloud; đo RTO ≤ 1 giờ và ≤ 4 giờ; đối chiếu dữ liệu sau khôi phục.
- DR-07, DR-08: chuyển `bootstrap.servers` sang cụm Kafka khác bằng một commit GitOps, Debezium đọc tiếp từ slot; đổi zone master RGW; đo mất sự kiện bằng đối soát outbox.
- DR-09: dựng lại cụm Kubernetes của cell bằng Cluster API và Argo CD, khôi phục tài nguyên bằng Velero, đo RTO ≤ 4 giờ.
- Biên bản mỗi lần diễn tập `ops/dien-tap/<ngày>-<DR-xx>.md`: kịch bản, mốc thời gian từng bước, RPO, RTO đo được, bước lệch runbook, hạng mục khắc phục (bằng chứng cho C05 và TCVN nhóm "Sao lưu dự phòng").
- Kiểm tạm khi chưa có cụm sản xuất: chạy trên kind ba node (ba vùng giả lập) với CNPG, Strimzi, MinIO hoặc Rook một node thay Ceph; ghi rõ khác biệt.
- Gợi ý tách: (a) DR-01..DR-04 và PERF-07; (b) DR-05, DR-06; (c) DR-07..DR-09.

## 4. Ngoài phạm vi

- DR-10..DR-16 và chuyển vùng giữa hai TTDL (QA-15); viết cấu hình sao lưu (INF-17, INF-18).

## 5. Tệp được phép sửa

- `ops/runbooks/dr/dr-0*.md`, `ops/dien-tap/**`, `infra/perf/chaos/**` (chỉ tác động mới cho DR), `deploy/environments/kind/**` (cụm diễn tập).

## 6. Tiêu chí hoàn thành

- Mỗi DR-01..DR-09 có runbook và ít nhất một biên bản diễn tập trên kind hoặc staging, ghi RPO, RTO đo được; kịch bản nào không chạy được trên môi trường thử ghi rõ lý do.
- PERF-07 rút gọn đạt: 0 lỗi 5xx khi tắt một node trong 1 giờ (tiêu chí đợt 0b).
- `markdownlint ops/runbooks/dr ops/dien-tap` sạch; mọi `runbook_url` của cảnh báo liên quan (`CNPGClusterNotHealthy`, `BackupMissing`) trỏ tới tệp có thật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
