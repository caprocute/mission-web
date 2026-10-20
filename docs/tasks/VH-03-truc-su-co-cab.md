# VH-03 · OPS-06, OPS-13, OPS-19 Trực vận hành, mức sự cố SEV-1..SEV-4, hội đồng thay đổi và đóng băng thay đổi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (trước khi CELL-G4 nhận người dùng) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | VH-01 |
| Truy vết | NFR-05 → TL-04 mục 11.3, 11.7 → OPS-06, OPS-13, OPS-19 → Alertmanager (INF-15), sync window Argo CD (INF-04), cảnh báo `ErrorBudgetHalfSpent` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lập bộ quy trình con người cho vận hành: ca trực SRE 24/7 hai cấp, phân loại và xử lý sự cố SEV-1..SEV-4 kèm thông báo, phân tích sau sự cố không quy lỗi cá nhân, hội đồng thay đổi ba loại và quy trình trong cửa sổ đóng băng. Khi xong, mỗi sự cố và mỗi thay đổi có đường đi rõ ràng, có mẫu biểu, và đã diễn tập trên bàn một lần.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 11.3 "Đóng băng thay đổi và hội đồng thay đổi" (dòng 3763–3791), 11.7 "Trực vận hành, mức sự cố và runbook" (dòng 3834–3848: bảng SEV), 11.8 OPS-17 (danh mục trước mốc hạn), 9.7 (SOC 24/7, SEC-43).
- `docs/tasks/VH-01-runbook-ops-01-13.md` (mẫu runbook), `INF-04-gitops-argo-cd.md` (sync window), `INF-15-slo-canh-bao-dashboard.md` (định tuyến critical gọi trực, warning nhắn tin).

## 3. Phạm vi công việc

- `ops/van-hanh/truc-van-hanh.md`: tổ chức ca 24/7, cấp 1 tiếp nhận cảnh báo và chạy runbook, cấp 2 chuyên sâu theo lĩnh vực (nền tảng, CSDL, lưu trữ, an ninh); bàn giao ca; trực tăng cường trước mốc hạn (OPS-17).
- `ops/van-hanh/muc-su-co.md`: bảng SEV-1..SEV-4 đúng thời gian phản hồi, khắc phục, tần suất thông báo của TL-04; vai trò IC, người liên lạc, người ghi chép; tiêu chí nâng, hạ mức; phối hợp SOC khi có yếu tố an ninh.
- Mẫu thông báo sự cố gửi lãnh đạo và khách hàng bằng văn phong nghiêm túc, không lộ chi tiết kỹ thuật nhạy cảm.
- Mẫu phân tích sau sự cố `ops/van-hanh/mau-phan-tich-sau-su-co.md` (trong 5 ngày làm việc cho SEV-1, SEV-2; hạng mục có người và hạn, theo dõi tới khi đóng).
- `ops/van-hanh/hoi-dong-thay-doi.md`: thay đổi chuẩn, thường (CAB tuần), khẩn (CAB khẩn trực tuyến với quản lý thay đổi, trưởng ca SRE, chủ sản phẩm); quy tắc cell đã tiêu > 50% ngân sách lỗi trước ngày 15 tạm dừng thay đổi thường; mẫu phiếu yêu cầu thay đổi.
- `ops/van-hanh/dong-bang.md`: lịch cửa sổ đóng băng (24 tháng cuối quý tới hết 10 tháng đầu quý sau; 20/1 tới 05/2), chỉ sửa lỗi nghiêm trọng và vá CVSS ≥ 9 qua CAB khẩn, cách đồng bộ thủ công có kiểm soát.
- Diễn tập trên bàn một sự cố SEV-1 giả định (mất primary `pg-core` cell thí điểm trong giờ cao điểm) với đủ vai trò; biên bản lưu `ops/dien-tap/`.

## 4. Ngoài phạm vi

- Cấu hình Alertmanager và sync window (INF-15, INF-04); hệ thống gọi trực và hợp đồng SOC (chờ lựa chọn nhà cung cấp; trong task này dùng đích cảnh báo giả lập, địa chỉ nhà cung cấp nằm trong cấu hình).

## 5. Tệp được phép sửa

- `ops/van-hanh/**`, `ops/dien-tap/<ngày>-truc-sev1.md`.

## 6. Tiêu chí hoàn thành

- Đủ các tệp ở mục 3; `markdownlint ops/van-hanh` sạch; số liệu SEV khớp TL-04 từng ô (người duyệt đối chiếu).
- Biên bản diễn tập trên bàn có mốc thời gian, quyết định của IC, hạng mục khắc phục.
- Không có tên người, số điện thoại, địa chỉ máy chủ trong tệp (danh sách liên lạc lưu ngoài repo).

## 7. Báo cáo

Theo AGENTS.md mục 6.
