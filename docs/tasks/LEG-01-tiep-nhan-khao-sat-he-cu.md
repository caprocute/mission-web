# LEG-01 · Đợt 0a/ADR-20 Tiếp nhận và khảo sát bốn repo của hệ hiện hành, Oracle và hạ tầng đang chạy

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0a – khắc phục khẩn (2 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | S (≤ nửa ngày agent sau khi có quyền truy cập) |
| Phụ thuộc | – |
| Truy vết | ADR-20; TL-01 "Lộ trình chuyển đổi theo Strangler Fig"; TL-04 "Lộ trình triển khai › Đợt 0a"; chỉ số theo dõi (điểm đáp ứng 17,4/100, 12 vấn đề nghiêm trọng, khoảng 65 phát hiện bí mật, 23 bảng trong schema `VOFFICE`) |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Có một bản tiếp nhận hệ hiện hành đủ để mọi task LEG và DL-09 bắt đầu: danh mục repo và cách build, bản đồ mô-đun và endpoint, vị trí đã xác minh của các lỗ hổng S01–S14, 17 điểm ghép nối với Oracle `VOFFICE`, sơ đồ hạ tầng đang chạy và số đo nền cho bảng chỉ số theo dõi chuyển đổi. Không sửa gì ở hệ cũ trong task này.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md` và `docs/quy-dinh/HA-TANG.md` (nhóm LEG áp cả hai).
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: mục "Tài liệu liên quan" (danh sách repo hiện tại), chương "Lộ trình chuyển đổi theo Strangler Fig" (dòng 3140–3184), "ADR-20. Strangler Fig với APISIX và lớp chống ô nhiễm".
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Khắc phục khẩn năm lỗ hổng nghiêm trọng S01 đến S05" (dòng 2443), chương "Lộ trình triển khai" (dòng 3875–3994).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, "Di trú từ hệ hiện tại theo mẫu Strangler Fig".
- Bản đánh giá kiến trúc `/mnt/project-files/kien-truc-okr-kpi/src/_inputs/danh-gia-kien-truc.txt` (vấn đề S, C, F, K… và vị trí trong mã).
- Đầu vào người dùng cần cấp:
  - Quyền đọc (clone) mọi repo hệ hiện hành: TL-01 nêu năm repo `backend-missions`, `dhtn_fe`, `backend2.0`, `web-spring`, `twd_mission`, TL-04 nói "4 repo"; người dùng xác nhận danh sách đúng và repo triển khai (manifest, cấu hình máy chủ) nếu có.
  - Tài khoản Oracle chỉ đọc vào schema `VOFFICE` trên bản sao hoặc máy chờ, kèm quyền đọc từ điển dữ liệu.
  - Mô tả hoặc quyền xem hạ tầng đang chạy (máy chủ, cụm, cân bằng tải, LogCenter), đầu mối vận hành và đầu mối vOffice.

## 3. Phạm vi công việc

- Lập danh mục repo: ngôn ngữ, phiên bản Java/Spring, công cụ build, số dòng mã, độ phủ kiểm thử hiện có, cách chạy cục bộ, pipeline hiện có.
- Lập bản đồ mô-đun nghiệp vụ và endpoint (đặc biệt `PUBLIC_ENDPOINTS`, `JwtTokenFilter`, các API danh mục công việc sẽ chuyển đầu tiên theo LEG-10).
- Xác minh vị trí S01–S05, S07, S08, S11 trong mã (tệp, dòng) để LEG-02..LEG-04 dùng; không chép giá trị bí mật nào, chỉ ghi vị trí đã che.
- Lập danh mục 17 điểm ghép nối Oracle (`VHR_ORG`, `VHR_EMPLOYEE`, `USER_TOKENS`, `NOTIFICATION`, `SMS_MASTER`…) theo repo và tệp; danh sách sơ bộ 23 bảng OKR/KPI làm đầu vào DL-09.
- Vẽ sơ đồ hạ tầng đang chạy: số node, số replica, pool kết nối Oracle (khoảng 5.000 kết nối theo TL-04), cách triển khai, cách ghi nhật ký.
- Ghi số đo nền cho bảng chỉ số TL-04 (điểm đáp ứng, vấn đề nghiêm trọng, phát hiện bí mật, pipeline có cổng chất lượng 0/4, request có trace 0%).
- Ghi kết quả vào một trang wiki mới về hệ hiện hành và cập nhật mục lục; điểm lệch số repo (năm hay bốn) ghi vào wiki trang 14.

## 4. Ngoài phạm vi

- Mọi sửa đổi hệ cũ (LEG-02..LEG-11); ánh xạ chi tiết 23 bảng (DL-09).

## 5. Tệp được phép sửa

- `wiki/` (trang mới về hệ hiện hành, số trang theo mục lục lúc làm; dòng mục lục trong `wiki/README.md`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- Trang wiki có đủ bảy phần ở mục 3, mỗi lỗ hổng S01–S05, S07, S08, S11 có vị trí đã xác minh hoặc ghi rõ "không tìm thấy".
- `gitleaks` trên repo `mission-web` xanh sau khi thêm trang (không lọt bí mật, địa chỉ máy chủ hay tài khoản).
- Danh sách đầu vào còn thiếu (nếu có) được liệt kê trong báo cáo để người dùng cấp tiếp.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
