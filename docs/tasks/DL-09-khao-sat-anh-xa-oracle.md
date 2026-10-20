# DL-09 · ADR-20/TL-03 ch.15 Khảo sát và ánh xạ 23 bảng Oracle của hệ hiện hành sang lược đồ mới

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 – định danh, tổ chức và tích hợp (khảo sát làm sớm để kịp di trú ở đợt 2) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | LEG-01 |
| Truy vết | ADR-20; TL-03 "Di trú từ hệ hiện tại theo mẫu Strangler Fig" → schema Oracle `VOFFICE` (`VHR_ORG`, `VHR_EMPLOYEE`, `USER_TOKENS`, `NOTIFICATION`, `SMS_MASTER` và 23 bảng OKR/KPI) → schema đích `task`, `okr`, `product`, `evidence`, `appraisal`, `config` (MOD-02, MOD-01, MOD-03, SVC-08, MOD-04, MOD-08) → `integ.mapping_xref` (SVC-13) → R8 |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền đọc Oracle hệ hiện hành |

## 1. Mục tiêu

Chốt danh sách chính xác 23 bảng OKR/KPI và 17 điểm ghép nối trong schema `VOFFICE`, kèm bảng ánh xạ đến từng cột của lược đồ mới, quy tắc đổi kiểu, quy tắc đổi giá trị trạng thái sang bộ trạng thái chuẩn và danh sách câu SQL đặc thù phải viết lại. TL-03 ghi rõ danh sách này "chốt ở giai đoạn khảo sát"; DL-10, DL-11 và LEG-10 đều dựa vào kết quả này.

## 2. Đầu vào bắt buộc đọc

- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, chương "Quản lý thay đổi lược đồ và di trú từ Oracle" (dòng 4590–4618), mục "Di trú từ hệ hiện tại theo mẫu Strangler Fig"; mục "Không phụ thuộc SQL đặc thù của hãng" (dòng 187); các mục schema đích.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, "Chuyển dần theo Strangler Fig với APISIX" (dòng 1428) và "ADR-20. Strangler Fig với APISIX và lớp chống ô nhiễm".
- Kết quả LEG-01 (trang wiki hệ hiện hành); bản đánh giá kiến trúc `/mnt/project-files/kien-truc-okr-kpi/src/_inputs/danh-gia-kien-truc.txt`.
- Đầu vào người dùng cần cấp: tài khoản Oracle chỉ đọc vào schema `VOFFICE` trên bản sao hoặc máy chờ (không phải máy chính), quyền đọc từ điển dữ liệu (`ALL_TAB_COLUMNS`, `ALL_CONSTRAINTS`), bản xuất DDL, số dòng và dung lượng từng bảng; quyền đọc mã nguồn các repo hệ cũ để tra câu SQL.

## 3. Phạm vi công việc

- Lập danh mục 23 bảng OKR/KPI theo năm nhóm của TL-03 (khoảng 9 bảng kế hoạch, nhiệm vụ, tiến độ sang `task`; 4 bảng mục tiêu, KR sang `okr`; 3 bảng sản phẩm, tệp sang `product`, `evidence`; 4 bảng đánh giá sang `appraisal`; 3 bảng cấu hình sang `config`, `task.work_catalog`), ghi số dòng, dung lượng, khoá, chỉ mục.
- Lập danh mục 17 điểm ghép nối (`VHR_ORG`, `VHR_EMPLOYEE`, `USER_TOKENS`, `NOTIFICATION`, `SMS_MASTER`…) kèm repo và tệp mã đang dùng, làm đầu vào cho LEG-09 và LEG-11.
- Viết bảng ánh xạ máy đọc được `data/migration/oracle/anh-xa.yaml`: cột nguồn → bảng, cột đích; quy tắc đổi kiểu (`NUMBER`, `DATE` có giờ sang `timestamptz` UTC, chuỗi rỗng là NULL, chuẩn hoá NFC); quy tắc đổi mã trạng thái cũ sang trạng thái chuẩn; cột nào thuộc mức C2 phải mã hoá khi chuyển.
- Ghi quy tắc ánh xạ định danh người, đơn vị cũ sang `org` qua `integ.mapping_xref`; hồ sơ đánh giá cũ chuyển với `snapshot_kind = 'CHOT_KY'` và Policy Pack "di trú" theo TL-03.
- Liệt kê câu SQL đặc thù (`ROWNUM`, `CONNECT BY`, `NVL`, `DECODE`…) ảnh hưởng tới dữ liệu chuyển và cách viết lại.
- Lưu DDL đã xuất (chỉ cấu trúc, không dữ liệu) trong `data/migration/oracle/ddl/`; không chép dòng dữ liệu, tên người hay giá trị C2 nào vào repo.
- Ghi câu hỏi mở và bảng nào "không chuyển" kèm lý do vào wiki trang 14.

## 4. Ngoài phạm vi

- Viết ETL, chạy chuyển dữ liệu (DL-10, DL-11); đồng bộ hai chiều và CDC Oracle (LEG-10).
- Sửa mã hệ cũ (nhóm LEG).

## 5. Tệp được phép sửa

- `data/migration/oracle/**` (thư mục mới, suy luận theo cấu trúc `data/`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` và trang wiki hệ hiện hành do LEG-01 tạo

## 6. Tiêu chí hoàn thành

- 23/23 bảng có trong bảng ánh xạ, mỗi bảng hoặc có đích cụ thể hoặc ghi "không chuyển" kèm lý do; 17/17 điểm ghép nối có chủ sở hữu thay thế (SVC-04, SVC-12, SVC-02…).
- Tập lệnh kiểm `python -I data/migration/oracle/kiem_anh_xa.py` xác nhận mọi cột đích tồn tại trong migration Flyway hiện có của schema đích; cột không tồn tại được liệt kê để mở task bổ sung migration.
- Quét repo không có dữ liệu cá nhân hay bí mật (gitleaks xanh).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
