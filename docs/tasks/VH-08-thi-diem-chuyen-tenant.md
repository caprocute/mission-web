# VH-08 · OPS-08 Kế hoạch đưa tenant thí điểm vào sử dụng và chuyển tenant theo nhóm

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (tenant thí điểm), Đợt 3 (chuyển theo nhóm, tắt hệ cũ) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-16 |
| Truy vết | ADR-20 Strangler Fig → TL-01 chương "Lộ trình chuyển đổi theo Strangler Fig"; TL-04 mục 12.6, 12.7, 11.4 → OPS-04, OPS-07, OPS-08 → SVC-01 APISIX (định tuyến theo tenant), SVC-03 → `registry.tenant` |
| Trạng thái | Bị chặn bởi bên ngoài: cần danh sách tenant, lịch kỳ đánh giá và cam kết của khách hàng (lập kế hoạch và diễn tập trên staging được) |

## 1. Mục tiêu

Lập kế hoạch và quy trình đưa tenant vào hệ mới: tenant thí điểm ở đợt 2, sau đó chuyển theo nhóm từ đầu kỳ đánh giá, dữ liệu kỳ cũ chuyển chỉ đọc và niêm phong sổ cái bản chuyển đổi, tới khi tắt hệ hiện hành ở đợt 3. Khi xong, có kế hoạch chuyển theo nhóm có tiêu chí vào, ra và quay lui cho từng nhóm, đã diễn tập một lần chuyển nhóm thử trên staging.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Lộ trình chuyển đổi theo Strangler Fig" (dòng 3140–3184: đợt 2 "chuyển tenant theo nhóm từ đầu kỳ; dữ liệu kỳ cũ chuyển chỉ đọc + niêm phong sổ cái bản chuyển đổi"; chỉ số "tỉ lệ người dùng trên nền tảng mới").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 12.6, 12.7 (dòng 3946–3978), 11.4 (OPS-07, OPS-08), 11.2 (OPS-04 cờ tính năng), 11.3 (cửa sổ đóng băng).
- `docs/tasks/QA-16-uat-tenant-thi-diem.md`, `VH-04-dien-tap-onboarding.md`, `LEG-10-strangler-theo-tenant.md`, `LEG-11-chuyen-tenant-tat-he-cu.md`.

## 3. Phạm vi công việc

- `docs/dua-vao-su-dung/ke-hoach-chuyen-doi.md` (vị trí suy luận): chia nhóm tenant (theo cell đích, quy mô, mốc kỳ), thứ tự nhóm, lịch bám đầu kỳ và tránh cửa sổ đóng băng.
- Cho mỗi nhóm: tiêu chí vào (UAT nhóm trước đạt, SLO cell ổn định, dữ liệu tổ chức đối soát xong), các bước (onboarding OPS-07, chuyển dữ liệu kỳ cũ chỉ đọc, niêm phong sổ cái, bật cờ, đổi định tuyến APISIX theo tenant), tiêu chí ra, điều kiện và bước quay lui về hệ cũ.
- Quy trình OPS-08 chuyển tenant giữa cell khi tenant vượt cell pool hoặc sáp nhập đơn vị: chỉ đọc 5–15 phút, đối soát dòng và băm, đổi danh bạ và DNS, giữ nguồn 30 ngày (trỏ runbook VH-01).
- Kế hoạch truyền thông và hỗ trợ người dùng: thông báo trước, kênh hỗ trợ, tài liệu VH-05, trực tăng cường tuần đầu.
- Bảng theo dõi tỉ lệ người dùng trên nền tảng mới theo nhóm (đầu vào VH-09).
- Diễn tập trên staging: chuyển một nhóm hai tenant thử từ hệ cũ giả lập sang hệ mới và quay lui một tenant.

## 4. Ngoài phạm vi

- Công cụ chuyển dữ liệu hệ cũ và tắt hệ cũ (LEG-10, LEG-11); onboarding (VH-04); UAT (QA-16); tắt hạ tầng hệ cũ.

## 5. Tệp được phép sửa

- `docs/dua-vao-su-dung/**`, `ops/dien-tap/<ngày>-chuyen-nhom.md`.

## 6. Tiêu chí hoàn thành

- Kế hoạch có đủ nhóm, tiêu chí vào, ra, quay lui; người duyệt đối chiếu với TL-01 và TL-04 không có mâu thuẫn.
- Biên bản diễn tập chuyển nhóm và quay lui trên staging; đối soát dòng và băm khớp.
- Khi có danh sách tenant thật: bảng nhóm điền đủ; khi chưa có, ghi "Bị chặn bởi bên ngoài" và để mẫu.

## 7. Báo cáo

Theo AGENTS.md mục 6.
