# QA-15 · DR-10..DR-16 Diễn tập khôi phục thảm hoạ và chuyển vùng giữa hai trung tâm dữ liệu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-15` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (DR-12, DR-14, DR-15 diễn tập trên bàn từ đợt 2) |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-14, INF-18, INF-20 |
| Truy vết | NFR-05 (RPO 15 phút, RTO 4 giờ cấp TTDL) → TL-04 mục 8.2, 8.10, 8.11, 8.12 → DR-10..DR-16, OPS-18 → CNPG replica cluster `pg-*-sn`, MirrorMaker 2, Ceph multisite, PowerDNS GeoDNS, `dns-sync`, OpenBao, `ledger.verify_run` |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Hoàn thiện phần chờ ấm phía Nam còn thiếu (replica cluster CNPG), viết runbook DR-10..DR-16 và diễn tập chuyển vùng một cell, quay về theo DR-16, cùng các kịch bản an ninh và khoá. Khi xong, có biên bản DR-10 đo được RPO ≤ 15 phút, RTO ≤ 4 giờ (điều kiện đợt 3), và lịch diễn tập hằng quý một cell, hằng năm toàn phần.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 8.11 (dòng 2336–2429: DR-10..DR-16 và phân tích RPO của DR-10), 8.12 (dòng 2431–2437), 8.2 (mẫu `values-cell-hcm-sn.yaml` phần CNPG, dòng 2083), 8.10 (giữ outbox 72 giờ), 8.7 (OpenBao, HSM), mục 2.5–2.7 (topology hai TTDL, GeoDNS).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` mục 8.3 "Năng lực dự trữ cho chuyển vùng" (dòng 577).
- `docs/tasks/INF-18-sao-luu-du-lieu-khac.md`, `INF-20-vung-dieu-phoi-toan-cuc.md`, `INF-13-openbao-pki-hsm.md`, `QA-14-thu-hong-dr-01-09.md`.

## 3. Phạm vi công việc

- Viết values replica cluster CNPG phía Nam cho mỗi cụm `pg-*` của cell (`replica.enabled`, `externalClusters` từ kho sao lưu và luồng WAL, theo mẫu dòng 2083) và cảnh báo trễ WAL 5 phút, 10 phút.
- Runbook `ops/runbooks/dr/dr-10.md` .. `dr-16.md`; DR-10 ghi rõ quyết định chuyển vùng là của IC, không tự động, kèm danh sách kiểm trước khi quyết định.
- DR-10 cho một cell: thăng cấp replica phía Nam, chuyển Kafka (MirrorMaker 2), zone master RGW, `active_site` để `dns-sync` đổi bản ghi, phát lại outbox 72 giờ; chạy ở phía Nam ≥ 4 giờ; đo RPO bằng đối soát giao dịch, RTO từ lúc quyết định.
- DR-16: dựng lại Hoà Lạc ở vai trò thứ cấp (`replica.primary = pg-*-sn`), đồng bộ, quay về ngoài giờ cao điểm.
- DR-13: tắt vùng điều phối toàn cục phía Hoà Lạc, kiểm GeoDNS `ifurlup` chuyển trong ≤ 15 phút, đăng nhập và chọn đơn vị vẫn chạy.
- DR-11, DR-12, DR-14, DR-15: diễn tập trên bàn (kịch bản, vai trò SOC, IC, quản trị khoá) và phần kỹ thuật làm được trên môi trường thử: khôi phục từ bản sạch Object Lock; xoay khoá ký JWT và thu hồi lease secret động; cố ý sửa một bản ghi sổ cái thử để `verify_run` phát hiện; dựng lại OpenBao từ snapshot Raft với DEK đệm 15 phút.
- Biên bản `ops/dien-tap/<ngày>-<DR-xx>.md` theo mẫu QA-14; lịch diễn tập năm `ops/dien-tap/lich-<năm>.md` theo mục 8.12.
- Ghi chú phân công (2026-10-23): replica cluster CNPG phía Nam (dòng đầu mục này) đã làm trong INF-28 (`deploy/charts/vt-platform/ha-tang/data-pg`, `deploy/cells/*/values-{hl,sn}.yaml`, chart `standby-check`, runbook `ops/runbooks/ban-sao-am.md`); QA-15 chỉ dùng lại khi diễn tập.
- Chế độ chờ của ứng dụng (INF-28, đã làm): ApplicationSet truyền `global.siteRole` (primary/standby) và chart `vt-service` đặt biến `VT_SITE_ROLE`, `VT_SITE`. Ở TTDL chờ, `vt-platform-starter` ép tắt Flyway và mọi listener Kafka (`spring.kafka.listener.auto-startup`, `okrkpi.inbox.listener-auto-startup`), bỏ qua lượt chạy của job `@Scheduled`, `MigrationRunner` thoát 0; thư viện Go `vtplatform/site` dừng consumer, job định kỳ và auto-migrate của ledger, notification; Job migration của org-service, tenant-registry không render. Health báo vai trò ở `/actuator/health/site` (Java) và `/healthz/site` (Go). DR-10 kiểm thêm: sau khi đổi `primarySite` và Argo CD đồng bộ, pod phía Nam báo `role=primary`, `paused=[]`, consumer chạy lại từ offset đã dịch của MirrorMaker 2; phía Hoà Lạc (khi dựng lại theo DR-16) báo `role=standby`.
- Gợi ý tách: (a) replica cluster và DR-10, DR-16; (b) DR-13; (c) DR-11, DR-12, DR-14, DR-15.

## 4. Ngoài phạm vi

- Mua sắm và dựng TTDL phía Nam, năng lực dự trữ 2.000 vCPU (hạ tầng thật); HSM vật lý (INF-13).
- MirrorMaker 2, Ceph multisite, GeoDNS (INF-18, INF-20 đã viết cấu hình).
- Diễn tập DR-10 thật cho một cell trên TTDL thứ hai khi có; khi đó chỉ đổi tham số môi trường của runbook.

## 5. Tệp được phép sửa

- `deploy/cells/*/values-sn.yaml` phần CNPG replica cluster (tên tệp theo INF-04), `deploy/charts/pg-cell/**` chỉ khối `replica` và `externalClusters`.
- `ops/runbooks/dr/dr-1*.md`, `ops/dien-tap/**`, `deploy/environments/kind/**` (hai cụm mô phỏng hai TTDL).

## 6. Tiêu chí hoàn thành

- Trên hai cụm kind: DR-10 và DR-16 chạy trọn theo runbook, biên bản ghi RPO, RTO đo được; DR-13 chuyển GeoDNS thử thành công.
- `helm template` chart `pg-cell` với values phía Nam hợp lệ; `kubeconform` sạch.
- Biên bản diễn tập trên bàn cho DR-11, DR-12, DR-14, DR-15 có hạng mục khắc phục.
- Biên bản DR-10 trên hai cụm kind ghi RPO, RTO đo được so với mốc RPO ≤ 15 phút, RTO ≤ 4 giờ; runbook dùng tham số theo môi trường nên diễn tập trên TTDL thật chỉ đổi cấu hình.

## 7. Báo cáo

Theo AGENTS.md mục 6.
