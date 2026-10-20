# VH-02 · OPS-14..OPS-26 Runbook bảo trì, định kỳ và runbook theo cảnh báo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (runbook cảnh báo sao lưu và CSDL từ đợt 1) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-17 |
| Truy vết | NFR-05, NFR-09 → TL-04 mục 11.7–11.9, 10.5 → OPS-14..OPS-26, cảnh báo mục 10.5 → Cluster API, kured, `restore-check`, OpenBao, SVC-06 scoring, SVC-09 ledger, Kafka DLQ, Renovate, Dependency-Track |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết runbook cho OPS-14..OPS-26 và một runbook cho mỗi cảnh báo trong bảng TL-04 mục 10.5, để mọi `runbook_url` của INF-15 trỏ tới nội dung đã chạy thử. Khi xong, người trực cấp 1 xử lý được mọi cảnh báo tối thiểu và làm được các quy trình bảo trì, định kỳ chỉ bằng runbook.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 11.7 (OPS-14, dòng 3834–3848), 11.8 "Bảo trì nền tảng" (dòng 3849–3856), 11.9 "Quy trình định kỳ khác" (dòng 3857–3873), 10.5 "Cảnh báo theo tốc độ tiêu ngân sách lỗi" (dòng 3484, bảng cảnh báo), 9.4 (lịch xoay vòng khoá), 8.12 (diễn tập).
- `docs/tasks/INF-15-slo-canh-bao-dashboard.md` (tên cảnh báo, `ops/runbooks/README.md`), `INF-17-sao-luu-csdl.md`, `INF-18-sao-luu-du-lieu-khac.md`, `QA-14-thu-hong-dr-01-09.md` (runbook DR).

## 3. Phạm vi công việc

- OPS-15, OPS-16: nâng cấp Kubernetes n-1 theo quý (staging và `CELL-PERF` trước, `kubent` hoặc `pluto`, operator trước, control plane theo đợt cell, worker `maxSurge: 1`, `maxUnavailable: 0`); vá node hằng tháng, kured khi vá nhân khẩn.
- OPS-17: danh mục chuẩn bị mười ngày trước mốc hạn (lịch tăng trước KEDA, dự trữ, PERF-04, ngân sách lỗi, trực tăng cường, thông báo, diễn tập gần nhất, dung lượng ≥ 30% trống) và theo dõi saga khoá kỳ.
- OPS-18, OPS-20: đọc kết quả `restore-check`, lịch diễn tập (trỏ runbook DR của QA-14, QA-15); xoay vòng khoá theo lịch mục 9.4, chỉ số `vt_key_age_days`.
- OPS-21, OPS-22: xử lý hồ sơ bị đánh dấu `LOI` sau tính lại đêm; xử lý `verify_run` thất bại (chuyển DR-14).
- OPS-23: thêm cell mới sáu bước, mục tiêu năm ngày làm việc. OPS-24: phân loại DLQ (lỗi dữ liệu, lỗi mã, không hợp lệ vĩnh viễn), phát lại qua `<topic>.replay.<consumer>`, hết DLQ trong một ngày làm việc. OPS-25: rà soát năng lực quý (đầu vào QA-12). OPS-26: Renovate, Dependency-Track, hạn vá theo mức, VEX.
- Runbook theo cảnh báo `ops/runbooks/canh-bao/<TênCảnhBáo>.md` cho đủ cảnh báo của bảng 10.5 (từ `SLOAvailabilityFastBurn` tới `ErrorBudgetHalfSpent`), tên tệp khớp `runbook_url` của INF-15.
- Mỗi runbook chạy thử trên kind hoặc staging, hoặc ghi rõ "chưa chạy thử được" và lý do.
- Gợi ý tách: (a) OPS-15..OPS-18, OPS-20; (b) OPS-21..OPS-26; (c) runbook theo cảnh báo.

## 4. Ngoài phạm vi

- Công cụ `vtctl` tự động hoá thao tác (đề xuất hàng khung mới; runbook ghi lệnh thủ công tương đương); runbook DR (QA-14, QA-15); OPS-01..OPS-13 (VH-01).

## 5. Tệp được phép sửa

- `ops/runbooks/ops/ops-14-*.md` .. `ops-26-*.md`, `ops/runbooks/canh-bao/**`, `ops/runbooks/kiem-lien-ket.py`, `ops/runbooks/README.md` (thêm dòng danh mục).

## 6. Tiêu chí hoàn thành

- Đủ runbook cho OPS-14..OPS-26 và mọi cảnh báo bảng 10.5; script `ops/runbooks/kiem-lien-ket.py` (hoặc `promtool` kết hợp grep) xác nhận mọi `runbook_url` trong luật Prometheus trỏ tới tệp có thật.
- Chạy thử trên kind: phát lại DLQ một topic thử; một cảnh báo `BackupMissing` giả lập xử lý theo runbook; nâng một bản minor Kubernetes của cụm kind theo runbook OPS-15.
- `markdownlint ops/runbooks` sạch; không có thông tin bí mật hay địa chỉ máy chủ.

## 7. Báo cáo

Theo AGENTS.md mục 6.
