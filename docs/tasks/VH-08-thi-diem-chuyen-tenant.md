# VH-08 · OPS-07 Kế hoạch đưa tenant thí điểm vào sử dụng và mở rộng theo nhóm tenant

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (tenant thí điểm), Đợt 3 (mở rộng theo nhóm tenant) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-16 |
| Truy vết | TL-01 chương "Lộ trình chuyển đổi theo Strangler Fig" (chỉ dùng tiêu chí đợt 2, đợt 3 và chỉ số "tỉ lệ người dùng trên nền tảng mới"; phần Strangler của ADR-20 không áp dụng); TL-04 mục 12.6, 12.7, 11.4 → OPS-04, OPS-07, OPS-08 → SVC-03 → `registry.tenant` |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Lập kế hoạch và quy trình đưa tenant vào sử dụng Mission, một hệ mới độc lập không thay thế và không chuyển dữ liệu từ hệ cũ: tenant thí điểm ở đợt 2, sau đó mở rộng theo nhóm tenant, mỗi nhóm bắt đầu sử dụng từ đầu một kỳ đánh giá với dữ liệu khởi tạo mới (cơ cấu tổ chức, nhân sự, danh mục, Policy Pack), tới khi toàn bộ tenant đã đăng ký dùng hệ ở đợt 3. Khi xong, có kế hoạch mở rộng theo nhóm có tiêu chí vào, ra và tạm dừng cho từng nhóm, đã diễn tập một lần đưa nhóm thử vào sử dụng trên staging.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Lộ trình chuyển đổi theo Strangler Fig" (dòng 3140–3184): chỉ dùng nội dung đợt 2, đợt 3 (tenant thí điểm, quy mô quốc gia) và chỉ số "tỉ lệ người dùng trên nền tảng mới"; các phần Strangler, đợt 0a–0c, chuyển dữ liệu kỳ cũ và tắt hệ cũ không áp dụng (quyết định ngày 20/10/2026, `docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 12.6, 12.7 (dòng 3946–3978), 11.4 (OPS-07, OPS-08), 11.2 (OPS-04 cờ tính năng), 11.3 (cửa sổ đóng băng).
- `docs/tasks/QA-16-uat-tenant-thi-diem.md`, `VH-04-dien-tap-onboarding.md`.

## 3. Phạm vi công việc

- `docs/dua-vao-su-dung/ke-hoach-mo-rong.md` (vị trí suy luận): chia nhóm tenant (theo cell đích, quy mô, mốc kỳ), thứ tự nhóm, lịch bám đầu kỳ đánh giá và tránh cửa sổ đóng băng.
- Cho mỗi nhóm: tiêu chí vào (UAT hoặc vận hành của nhóm trước đạt, SLO cell ổn định, dữ liệu tổ chức và nhân sự khởi tạo đã đối soát với nguồn của tenant), các bước (onboarding OPS-07, nạp dữ liệu khởi tạo, gán Policy Pack và mở kỳ, bật cờ tính năng OPS-04 cho tenant, cấp tài khoản và mở đăng nhập), tiêu chí ra, điều kiện và bước tạm dừng mở rộng hoặc tắt cờ cho một tenant khi có sự cố.
- Quy trình OPS-08 chuyển tenant giữa các cell của hệ mới khi tenant vượt cell pool hoặc sáp nhập đơn vị: chỉ đọc 5–15 phút, đối soát dòng và băm, đổi danh bạ và DNS, giữ nguồn 30 ngày (trỏ runbook VH-01).
- Kế hoạch truyền thông và hỗ trợ người dùng: thông báo trước, kênh hỗ trợ, tài liệu VH-05, trực tăng cường tuần đầu.
- Bảng theo dõi tỉ lệ người dùng đã dùng nền tảng theo nhóm (đầu vào VH-09).
- Diễn tập trên staging: đưa một nhóm hai tenant thử vào sử dụng với dữ liệu khởi tạo giả lập, rồi tắt cờ và tạm dừng một tenant.

## 4. Ngoài phạm vi

- Chuyển dữ liệu từ hệ cũ, định tuyến Strangler và tắt hệ cũ: không áp dụng, vì Mission là hệ mới độc lập.
- Onboarding (VH-04); UAT (QA-16).
- Nối và kiểm thử với hệ thật khi có môi trường: điền bảng nhóm bằng danh sách tenant, lịch kỳ đánh giá và cam kết thật của khách hàng; khi đó chỉ thay dữ liệu đầu vào của kế hoạch, không đổi quy trình.

## 5. Tệp được phép sửa

- `docs/dua-vao-su-dung/**`, `ops/dien-tap/<ngày>-mo-rong-nhom.md`.

## 6. Tiêu chí hoàn thành

- Kế hoạch có đủ nhóm, tiêu chí vào, ra, quay lui; người duyệt đối chiếu với TL-01 và TL-04 không có mâu thuẫn.
- Biên bản diễn tập đưa nhóm thử vào sử dụng và tạm dừng một tenant trên staging; dữ liệu khởi tạo của nhóm thử đối soát khớp với nguồn giả lập.
- Bảng nhóm có mẫu điền sẵn bằng danh sách tenant giả lập, tách khỏi phần quy trình để sau này chỉ thay dữ liệu.

## 7. Báo cáo

Theo AGENTS.md mục 6.
