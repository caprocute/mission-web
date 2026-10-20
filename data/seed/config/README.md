# Dữ liệu khởi tạo: lịch ngày nghỉ quốc gia (DL-07)

Nguồn dữ liệu cho bảng `config.holiday_calendar` (TL-03 mục "Schema config (MOD-08)") của tenant hệ thống, `scope = QUOC_GIA`. Tenant thường đọc lịch này qua `listHolidayCalendarDays` và `getWorkingCalendar` (CORE-08-04) theo chính sách RLS đã chốt ở CORE-08-01; lịch riêng của tenant (`scope = TENANT`) do quản trị tenant nhập qua SCR-0801, không nằm ở đây.

## Tệp

| Tệp | Nội dung |
|---|---|
| `holiday_calendar_2026.csv` | 17 dòng năm 2026 |
| `holiday_calendar_2027.csv` | 14 dòng năm 2027 |

Mỗi năm một tệp để thêm năm mới không đụng năm cũ. CSV UTF-8, dấu phẩy, dòng đầu là tên cột. Ngày không được trùng trên toàn bộ các tệp (khoá duy nhất `(tenant_id, calendar_date)`).

## Cột

| Cột | Nạp vào bảng | Ý nghĩa |
|---|---|---|
| `calendar_date` | có | Ngày dương lịch, ISO 8601 |
| `day_kind` | có | `NGHI_LE` (ngày lễ, tết), `NGHI_BU` (nghỉ bù khi lễ trùng ngày nghỉ hằng tuần, hoặc ngày nghỉ do hoán đổi), `LAM_BU` (thứ Bảy làm bù cho ngày hoán đổi) |
| `name` | có | Tên hiển thị, tiếng Việt, tối đa 100 ký tự |
| `scope` | có | Luôn `QUOC_GIA` |
| `doi_chieu` | không | `CO_DINH`: ngày dương lịch cố định theo luật; `DA_DOI_CHIEU`: ngày quy đổi âm lịch hoặc nghỉ bù, làm bù đã khớp với văn bản chính thức; `CHO_DOI_CHIEU`: ngày quy đổi âm lịch chưa có văn bản để đối chiếu |
| `nguon` | không | Văn bản căn cứ (số hiệu, ngày) |
| `nguon_url` | không | Đường dẫn trang chính phủ hoặc bộ chủ quản |
| `ghi_chu` | không | Ghi chú đối chiếu |

`tenant_id` không ghi trong tệp: bộ nạp gắn mã tenant hệ thống lúc nạp. Các cột chung (`id`, `created_at`, `created_by`…) do bộ nạp sinh.

## Phạm vi và căn cứ

- Ngày lễ theo Bộ luật Lao động 2019 Điều 112 khoản 1 và ngày 24/11 (Ngày Văn hóa Việt Nam) theo Nghị quyết 28/2026/QH16, hiệu lực 01/7/2026.
- Lịch áp dụng cho cán bộ, công chức, viên chức (cơ quan Đảng, Nhà nước). Khối doanh nghiệp được tự chọn phương án nghỉ Tết, ngày liền kề Quốc khánh; tenant nào khác lịch thì nhập lịch riêng.
- Ngày âm lịch quy đổi bằng thuật toán Hồ Ngọc Đức (UTC+7) rồi so với thông báo của Bộ Nội vụ.
- Ngày nghỉ bù, làm bù chỉ ghi khi có văn bản. Chưa ghi: nghỉ bù 03/05/2027 (1/5/2027 trùng thứ Bảy) và mọi hoán đổi năm 2027 ngoài Tết, Quốc khánh, vì đang chờ thông báo chính thức.
- Hạn chế: phiên soạn dữ liệu không mở trực tiếp được trang gov.vn (proxy chặn); nội dung các văn bản được đối chiếu qua kết quả tìm kiếm trên trang chính phủ, nhiều nguồn khớp nhau. Trước khi nạp môi trường thật, người phụ trách cần mở bản gốc từng văn bản trong cột `nguon_url` để xác nhận. Số hiệu thông báo hoán đổi Quốc khánh 2026 (31/8 ↔ 22/8) chưa xác định.

## Cách nạp đề xuất

Đề xuất migration lặp Flyway `R__config_holiday_calendar_quoc_gia.sql` trong `backend/mission-core/src/main/resources/db/migration/config/`, sinh từ các tệp CSV này lúc build (CSV là nguồn, SQL là sản phẩm sinh, không sửa tay), thay vì bộ nạp khi khởi động, vì:

1. TL-03 (tiêu chí K1) tách quyền: migration chạy trong Job với vai trò `mig_config`, ứng dụng chạy với `svc_config` không có quyền DDL; ghi dữ liệu tenant hệ thống từ Job đúng tài khoản có thẩm quyền, không phải mở RLS hay cấp quyền ghi cho ứng dụng.
2. Flyway chạy lại `R__` khi checksum đổi, tức là mỗi lần sửa CSV; không đổi thì không chạy.
3. Có vết trong `flyway_schema_history`, đi cùng phiên bản phát hành.

Câu lệnh dự kiến: `INSERT … ON CONFLICT (tenant_id, calendar_date) DO UPDATE SET day_kind, name, updated_at, updated_by, row_version = row_version + 1` cho tenant hệ thống; xoá các dòng `scope = 'QUOC_GIA'` của tenant hệ thống không còn trong tệp. Mã tenant hệ thống và tài khoản dịch vụ ghi `created_by` lấy từ placeholder Flyway, không ghi cứng. Chưa viết migration vì mô-đun `mission-core` (CORE-08-01) chưa có.

## Kiểm tra tĩnh

```bash
python -I data/seed/config/kiem_du_lieu_nen.py data/seed
```

Tập lệnh kiểm tra cấu trúc tệp lịch nghỉ và khung danh mục khối, chức danh: đúng cột, ngày hợp lệ và không trùng, mọi dòng có nguồn, ngày làm bù rơi vào cuối tuần, ngày nghỉ bù rơi vào ngày thường, và ngày Tết, Giỗ Tổ khớp phép quy đổi âm lịch.

## Kiểm tra tĩnh

```bash
python -I data/seed/config/kiem_du_lieu_nen.py data/seed
```

Tập lệnh kiểm tra cấu trúc tệp lịch nghỉ và khung danh mục khối, chức danh: đúng cột, ngày hợp lệ và không trùng, mọi dòng có nguồn, ngày làm bù rơi vào cuối tuần, ngày nghỉ bù rơi vào ngày thường, và ngày Tết, Giỗ Tổ khớp phép quy đổi âm lịch (`amlich.py`, thuật toán Hồ Ngọc Đức, múi giờ UTC+7).
