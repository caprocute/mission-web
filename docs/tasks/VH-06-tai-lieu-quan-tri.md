# VH-06 · TL-04 ch.2 Tài liệu quản trị và cài đặt cho ba mô hình phân phối

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (cùng bản LTS cách ly đầu tiên) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-23 |
| Truy vết | TL-04 mục 2.1–2.4, 9.9; TL-05 chương 9 → ba mô hình: SaaS Viettel Cloud, đám mây riêng, cài cách ly → chart `vt-platform`, values-saas, values-private, values-airgap → OPS-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết bộ tài liệu cho người quản trị hệ thống phía khách hàng và đội triển khai: yêu cầu hạ tầng, cài đặt, cấu hình, nâng cấp, sao lưu và khôi phục, giám sát, cho từng mô hình phân phối. Khi xong, một kỹ sư chưa tham gia dự án cài được bản đám mây riêng hồ sơ compact trên cụm thử chỉ bằng tài liệu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 2.1 "Nguyên tắc chung" (dòng 91), 2.2 "Mô hình 1: SaaS" (dòng 115), 2.3 "Mô hình 2: đám mây riêng" (dòng 121), 2.4 "Mô hình 3: cài cách ly" (dòng 127), 9.9 "Cài đặt cách ly" (dòng 3208–3251), 11.6 (bản LTS, OPS-12).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` chương 9 "Bản cài đám mây riêng và bản cài cách ly" (dòng 632–660), Phụ lục A (values bốn hồ sơ).
- `docs/tasks/INF-02-chart-dich-vu-vt-platform.md`, `INF-03-values-dinh-co-cell.md`, `INF-23-goi-cai-cach-ly.md`, `VH-01`, `VH-02` (runbook dùng lại).

## 3. Phạm vi công việc

- `docs/quan-tri-cai-dat/README.md` (vị trí suy luận): chọn mô hình và hồ sơ (compact, S, M, L) theo quy mô khách hàng, bảng yêu cầu phần cứng tối thiểu từ TL-05.
- Hướng dẫn cài từng mô hình: chuẩn bị cụm, Harbor nội bộ, OpenBao và HSM (thẻ M trong N giao khách hàng), nạp gói, cài `vt-platform` với values tương ứng, kiểm sau cài.
- Bản cách ly: kiểm chữ ký gói và SBOM trong mạng cách ly, nạp vào Harbor nội bộ, cập nhật bản vá LTS (18 tháng), không cần kết nối ra ngoài.
- Quản trị thường ngày: tạo tenant (trỏ VH-04), sao lưu và kiểm khôi phục, xoay vòng khoá, xem dashboard và cảnh báo, nâng cấp phiên bản, ngoại lệ và biện pháp bù ở hồ sơ compact (TL-05 mục 9.2).
- Ma trận trách nhiệm giữa Viettel và khách hàng cho từng mô hình (ai giữ khoá, ai vận hành, ai trực).
- Chạy thử hướng dẫn cài bản đám mây riêng hồ sơ compact trên kind hoặc k3d bằng gói INF-23; ghi bước lệch.

## 4. Ngoài phạm vi

- Hướng dẫn người dùng nghiệp vụ (VH-05); runbook chi tiết (VH-01, VH-02 đã có, chỉ trỏ tới).

## 5. Tệp được phép sửa

- `docs/quan-tri-cai-dat/**`.

## 6. Tiêu chí hoàn thành

- Đủ tài liệu cho ba mô hình; `markdownlint docs/quan-tri-cai-dat` sạch.
- Cài bản compact trên k3d theo đúng tài liệu thành công, ghi thời gian và bước lệch trong báo cáo; mọi lệnh trong tài liệu đã chạy thật.
- Không có địa chỉ máy chủ, tài khoản, mật khẩu, khoá trong tài liệu (dùng chỗ giữ chỗ và tham chiếu kho bí mật).

## 7. Báo cáo

Theo AGENTS.md mục 6.
