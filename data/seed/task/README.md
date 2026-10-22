# Dữ liệu khởi tạo: thư viện danh mục công việc mẫu (CORE-02-01)

Dữ liệu mẫu cho bảng `task.work_catalog` và `task.catalog_item` (TL-03 "Schema task (MOD-02)"), chỉ dùng ở môi trường dev và kiểm thử. Mọi dòng là **giả lập**: tên công việc, điểm chuẩn và thời gian chuẩn được đặt để màn hình danh mục (SCR-0401) và kế hoạch (SCR-0402) có dữ liệu thử, không lấy từ danh mục thật của cơ quan nào. Thư viện quốc gia chính thức (`scope = QUOC_GIA`, `source = THU_VIEN_QG`) thay thế các tệp này khi có văn bản.

## Tệp

| Tệp | Nội dung |
|---|---|
| `work_catalog.csv` | Hai danh mục theo vị trí việc làm |
| `catalog_item.csv` | Bảy mục công việc, nối với danh mục bằng `catalog_code` |

CSV UTF-8, dấu phẩy, dòng đầu là tên cột. Cột `ghi_chu` không nạp vào bảng.

## Quy tắc nạp

Bộ nạp (CORE-02-03 hoặc công cụ dev) gắn mã tenant đích, sinh `id` UUIDv7 và các cột chung, đặt `version = version_no`. Ở `catalog_item`, hai cặp cột trùng nghĩa của TL-03 được ghi cùng giá trị: `standard_score = base_score` và `task_group = default_group`. `position_id` để trống vì vị trí việc làm thuộc tenant.
