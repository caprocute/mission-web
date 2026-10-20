# VH-01 · OPS-01..OPS-13 Runbook quy trình phát hành, cấu hình và vòng đời tenant

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (OPS-01, OPS-06, OPS-07, OPS-13 cần sẵn từ đợt 1) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-04 |
| Truy vết | NFR-07 → TL-04 mục 11.1–11.7 → OPS-01..OPS-13 → Argo CD ApplicationSet, Argo Rollouts `slo-api`, OpenFeature flagd, Flyway, pipeline Policy Pack và UI Pack → SVC-03, MOD-05, MOD-08 → EVT-13, EVT-23 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết runbook cho mười ba quy trình OPS-01..OPS-13 theo cấu trúc thống nhất của OPS-14, mỗi runbook có lệnh, truy vấn, dashboard cụ thể và đã chạy thử trên môi trường thử. Khi xong, người trực cấp 1 làm được phát hành theo đợt, canary, quay lui, bật cờ theo tenant, onboarding, chuyển tenant, migration, phát hành Policy Pack, UI Pack và bản LTS chỉ bằng runbook.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 11.1 "Danh mục quy trình OPS" (dòng 3583–3614), 11.2 "GitOps và phát hành theo đợt cell" (dòng 3616–3761), 11.3 "Đóng băng thay đổi" (dòng 3763), 11.4 "Vòng đời tenant" (dòng 3793), 11.5 "Migration CSDL" (dòng 3822), 11.6 "Phát hành Policy Pack, UI Pack và bản LTS" (dòng 3826), 11.7 "Trực vận hành, mức sự cố và runbook" (dòng 3834); mục 9.9 (đóng gói LTS cách ly).
- `docs/tasks/INF-04-gitops-argo-cd.md`, `INF-23-goi-cai-cach-ly.md`, `DV-03-05-*.md` (saga cấp phát tenant), `DL-01-policy-pack-quy-dinh-moi.md`, `DL-06-ui-pack-mac-dinh.md`.

## 3. Phạm vi công việc

- Mẫu runbook chung `ops/runbooks/_mau.md`: triệu chứng hoặc điều kiện kích hoạt, tác động, kiểm tra nhanh (lệnh, truy vấn, dashboard), hành động theo nhánh, khi nào leo thang, xác nhận hoàn tất, người chịu trách nhiệm và mức tự động hoá theo bảng OPS.
- OPS-01..OPS-03, OPS-05: thêm service vào ApplicationSet, theo dõi RollingSync bốn đợt (CELL-G4 trước), đọc kết quả AnalysisTemplate `slo-api`, dừng, thăng cấp thủ công, quay lui; khoảng ổn định ≥ 24 giờ ngày làm việc giữa đợt.
- OPS-04: tạo, bật dần cờ theo tenant trong kho GitOps, dọn cờ quá 90 ngày.
- OPS-06: kiểm sync window đang áp, đồng bộ thủ công qua vai trò release-manager khi CAB khẩn duyệt.
- OPS-07, OPS-08: chạy pipeline onboarding tám bước và đọc trạng thái tenant; chuyển tenant giữa cell bằng nhân bản logic PostgreSQL 17, đối soát dòng và băm, đổi danh bạ SVC-03 và DNS, giữ nguồn 30 ngày.
- OPS-09: quy tắc mở rộng, thu hẹp qua ba bản phát hành, xử lý migration lỗi ở hook `PreSync`, `lock_timeout`.
- OPS-10, OPS-11, OPS-12: lịch năm ngày Policy Pack (ca vàng 100%, so sánh bóng, duyệt, phát hành EVT-13); UI Pack trong một ngày (lược đồ, axe-core, ≤ 30 KB, ảnh so sánh, EVT-23); chọn và đóng gói bản LTS, vá an ninh 18 tháng.
- OPS-13: bảng SEV-1..SEV-4, sơ đồ gọi trực hai cấp (nội dung ca trực chi tiết ở VH-03).
- Mỗi runbook chạy thử ít nhất một lần trên kind hoặc staging; ghi ngày chạy thử và người chạy ở đầu tệp.
- Gợi ý tách: (a) OPS-01..OPS-06; (b) OPS-07..OPS-09; (c) OPS-10..OPS-13.

## 4. Ngoài phạm vi

- Công cụ `vtctl` (đề xuất hàng khung mới); runbook theo cảnh báo và OPS-14..OPS-26 (VH-02); trực vận hành, CAB (VH-03).

## 5. Tệp được phép sửa

- `ops/runbooks/_mau.md`, `ops/runbooks/ops/ops-01-*.md` .. `ops-13-*.md`, `ops/runbooks/README.md` (thêm dòng danh mục).

## 6. Tiêu chí hoàn thành

- Đủ 13 runbook theo mẫu; `markdownlint ops/runbooks` sạch; mọi lệnh trong runbook có thật trong công cụ đã cài ở môi trường thử (kiểm bằng chạy thử, không suy đoán cờ dòng lệnh).
- Chạy thử trên kind: một lần phát hành qua RollingSync và canary bị quay lui do lỗi cố ý; một lần onboarding tenant thử; một lần phát hành UI Pack thử.
- Không có địa chỉ máy chủ, tài khoản, mật khẩu trong runbook (dùng biến và tham chiếu kho bí mật).

## 7. Báo cáo

Theo AGENTS.md mục 6.
